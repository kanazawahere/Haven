/*
 * libhaven_usb.so — Haven's guest USB interposition shim (Slice 3, HID/hidraw).
 *
 * LD_PRELOADed into a native guest app (or, in Slice 4, DllMap'd for Mono),
 * this fakes a /dev/hidraw* device backed by Haven's USB proxy. The app opens
 * what looks like a normal hidraw node and does the usual ioctl/read/write; the
 * shim translates those into UsbProxyProtocol requests over the abstract socket
 * \0haven-usb, which the Android UsbBroker fulfils against the real device.
 * No real device node, no /dev/bus/usb, no root.
 *
 * Interposed: open/open64/openat/openat64 (match /dev/hidraw*), ioctl
 * (HIDIOCGRDESCSIZE/GRDESC/GRAWINFO/GRAWNAME), read, write, close, poll.
 * Everything else (and any fd we don't own) falls through to the real libc via
 * dlsym(RTLD_NEXT) — so DllMap'ing all of "libc" to this .so is safe.
 *
 * Built per-ABI with the glibc/musl cross toolchain (see build-haven-usb.sh),
 * _FORTIFY_SOURCE off so it loads under both glibc and musl.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>

/* ---- hidraw ioctl ABI (define locally to avoid header skew on musl) ------ */
#define HID_MAX_DESCRIPTOR_SIZE 4096
struct haven_hidraw_report_descriptor { uint32_t size; uint8_t value[HID_MAX_DESCRIPTOR_SIZE]; };
struct haven_hidraw_devinfo { uint32_t bustype; int16_t vendor; int16_t product; };
#define HIDIOCGRDESCSIZE  _IOR('H', 0x01, int)
#define HIDIOCGRDESC      _IOR('H', 0x02, struct haven_hidraw_report_descriptor)
#define HIDIOCGRAWINFO    _IOR('H', 0x03, struct haven_hidraw_devinfo)
#define HIDIOCGRAWNAME(len) _IOC(_IOC_READ, 'H', 0x04, len)
#define HIDIOCGRAWPHYS(len) _IOC(_IOC_READ, 'H', 0x05, len)
#define BUS_USB 0x03

/* ---- proxy protocol (mirror UsbProxyProtocol.kt) ------------------------- */
#define SOCK_NAME "haven-usb"
#define OP_GET_DESCRIPTORS 0x02
#define OP_CONTROL 0x03
#define OP_BULK 0x04
#define OP_CLOSE 0x05
#define USB_DT_REPORT 0x22
#define MAX_MANAGED 16

/* Per-open device state. The managed fd we hand back IS the socket fd. */
struct managed {
    int fd;                 /* socket fd, -1 = slot free */
    int hid_iface;          /* HID interface number */
    int ep_in, ep_out;      /* interrupt endpoint addresses (0 = none) */
    int16_t vendor, product;
    uint32_t report_desc_len;
    uint8_t report_desc[HID_MAX_DESCRIPTOR_SIZE];
};

static struct managed g_managed[MAX_MANAGED];
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

/* ---- real libc symbols --------------------------------------------------- */
static int (*real_open)(const char *, int, ...);
static int (*real_open64)(const char *, int, ...);
static int (*real_openat)(int, const char *, int, ...);
static int (*real_openat64)(int, const char *, int, ...);
static ssize_t (*real_read)(int, void *, size_t);
static ssize_t (*real_write)(int, const void *, size_t);
static int (*real_close)(int);
static int (*real_ioctl)(int, unsigned long, ...);
static int (*real_poll)(struct pollfd *, nfds_t, int);

static int g_debug = 0;
#define DBG(...) do { if (g_debug) { fprintf(stderr, "haven-usb: " __VA_ARGS__); fflush(stderr); } } while (0)

static void init_reals(void) {
    static int done = 0;
    if (done) return;
    g_debug = getenv("HAVEN_USB_DEBUG") != NULL;
    /* Free-slot sentinel is -1, but the static table is zero-initialized
     * (fd 0 = a valid fd). Mark every slot free before first use. */
    for (int i = 0; i < MAX_MANAGED; i++) g_managed[i].fd = -1;
    real_open = dlsym(RTLD_NEXT, "open");
    real_open64 = dlsym(RTLD_NEXT, "open64");
    real_openat = dlsym(RTLD_NEXT, "openat");
    real_openat64 = dlsym(RTLD_NEXT, "openat64");
    real_read = dlsym(RTLD_NEXT, "read");
    real_write = dlsym(RTLD_NEXT, "write");
    real_close = dlsym(RTLD_NEXT, "close");
    real_ioctl = dlsym(RTLD_NEXT, "ioctl");
    real_poll = dlsym(RTLD_NEXT, "poll");
    done = 1;
}

/* ---- low-level socket framing ------------------------------------------- */
static int io_full(int fd, void *buf, size_t n, int writing) {
    uint8_t *p = buf; size_t done = 0;
    while (done < n) {
        ssize_t r = writing ? real_write(fd, p + done, n - done)
                            : real_read(fd, p + done, n - done);
        if (r <= 0) return -1;
        done += (size_t)r;
    }
    return 0;
}
static uint32_t be32(uint32_t v) {
    return ((v & 0xFF) << 24) | ((v & 0xFF00) << 8) | ((v >> 8) & 0xFF00) | ((v >> 24) & 0xFF);
}

static int proxy_connect(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, SOCK_NAME, sizeof(SOCK_NAME) - 1);
    socklen_t len = offsetof(struct sockaddr_un, sun_path) + 1 + sizeof(SOCK_NAME) - 1;
    if (connect(fd, (struct sockaddr *)&addr, len) < 0) { real_close(fd); return -1; }
    return fd;
}

/* Send a framed request (opcode + payload), read a framed response into *out
 * (caller frees). Returns status (>=0 bytes / <0 error), out_len set. */
static int proxy_request(int fd, const uint8_t *body, uint32_t body_len, uint8_t **out, uint32_t *out_len) {
    uint32_t flen = be32(body_len);
    if (io_full(fd, &flen, 4, 1) < 0) return -1;
    if (io_full(fd, (void *)body, body_len, 1) < 0) return -1;
    uint32_t rlen_be;
    if (io_full(fd, &rlen_be, 4, 0) < 0) return -1;
    uint32_t rlen = be32(rlen_be);
    if (rlen < 4 || rlen > (1u << 20)) return -1;
    uint8_t *resp = malloc(rlen);
    if (!resp) return -1;
    if (io_full(fd, resp, rlen, 0) < 0) { free(resp); return -1; }
    uint32_t st_be; memcpy(&st_be, resp, 4);
    int32_t status = (int32_t)be32(st_be);
    if (out && status > 0) {
        *out = malloc(rlen - 4);
        memcpy(*out, resp + 4, rlen - 4);
        *out_len = rlen - 4;
    } else if (out) { *out = NULL; *out_len = 0; }
    free(resp);
    return status;
}

static int proxy_get_descriptors(int fd, uint8_t **out, uint32_t *out_len) {
    uint8_t op = OP_GET_DESCRIPTORS;
    return proxy_request(fd, &op, 1, out, out_len);
}

static int proxy_control(int fd, int rt, int req, int val, int idx, int len, uint8_t **out, uint32_t *out_len) {
    /* opcode(1) rt(1) req(1) value(2) index(2) length(2) timeout(4) = 13 bytes */
    uint8_t body[13];
    body[0] = OP_CONTROL;
    body[1] = (uint8_t)rt;
    body[2] = (uint8_t)req;
    body[3] = (uint8_t)(val >> 8); body[4] = (uint8_t)val;        /* u16 BE */
    body[5] = (uint8_t)(idx >> 8); body[6] = (uint8_t)idx;
    body[7] = (uint8_t)(len >> 8); body[8] = (uint8_t)len;
    uint32_t t = be32(1000); memcpy(body + 9, &t, 4);             /* u32 BE timeout */
    return proxy_request(fd, body, sizeof(body), out, out_len);
}

static int proxy_bulk(int fd, int ep, const uint8_t *data, int data_len, int read_len,
                      uint8_t **out, uint32_t *out_len) {
    uint32_t blen = 1 + 1 + 4 + 4 + (uint32_t)(data ? data_len : 0);
    uint8_t *body = malloc(blen);
    body[0] = OP_BULK;
    body[1] = (uint8_t)ep;
    uint32_t l = be32((uint32_t)read_len); memcpy(body + 2, &l, 4);
    uint32_t t = be32(1000); memcpy(body + 6, &t, 4);
    if (data && data_len) memcpy(body + 10, data, data_len);
    int rc = proxy_request(fd, body, blen, out, out_len);
    free(body);
    return rc;
}

/* ---- descriptor parsing: find HID interface, endpoints, report-desc len -- */
static void parse_descriptors(struct managed *m, const uint8_t *d, uint32_t n) {
    m->hid_iface = 0; m->ep_in = 0; m->ep_out = 0; m->report_desc_len = 0;
    if (n >= 12) { m->vendor = (int16_t)(d[8] | (d[9] << 8)); m->product = (int16_t)(d[10] | (d[11] << 8)); }
    uint32_t i = 0; int cur_iface = -1, in_hid = 0;
    while (i + 2 <= n) {
        uint8_t blen = d[i], btype = d[i + 1];
        if (blen < 2 || i + blen > n) break;
        if (btype == 0x04) {                 /* INTERFACE */
            cur_iface = d[i + 2];
            in_hid = (d[i + 5] == 0x03);     /* bInterfaceClass == HID */
            if (in_hid) m->hid_iface = cur_iface;
        } else if (btype == 0x21 && in_hid) { /* HID descriptor */
            /* report descriptor length at offset 7..8 (first class desc) */
            if (i + 8 < n) m->report_desc_len = d[i + 7] | (d[i + 8] << 8);
        } else if (btype == 0x05 && in_hid) { /* ENDPOINT on the HID iface */
            uint8_t addr = d[i + 2];
            if (addr & 0x80) { if (!m->ep_in) m->ep_in = addr; }
            else { if (!m->ep_out) m->ep_out = addr; }
        }
        i += blen;
    }
}

static struct managed *find(int fd) {
    for (int i = 0; i < MAX_MANAGED; i++) if (g_managed[i].fd == fd) return &g_managed[i];
    return NULL;
}

static int is_hidraw_path(const char *path) {
    return path && strncmp(path, "/dev/hidraw", 11) == 0;
}

/* Open the proxy, fetch + parse descriptors, fetch the HID report descriptor,
 * and register a managed slot. Returns the socket fd, or -1 (errno set). */
static int open_managed(void) {
    init_reals();
    int fd = proxy_connect();
    if (fd < 0) { errno = ENOENT; return -1; }

    uint8_t *desc = NULL; uint32_t desc_len = 0;
    if (proxy_get_descriptors(fd, &desc, &desc_len) <= 0) { real_close(fd); errno = EIO; return -1; }

    pthread_mutex_lock(&g_lock);
    struct managed *m = find(-1);
    if (!m) { pthread_mutex_unlock(&g_lock); free(desc); real_close(fd); errno = EMFILE; return -1; }
    memset(m, 0, sizeof(*m));
    m->fd = fd;
    parse_descriptors(m, desc, desc_len);
    DBG("descs: len=%u iface=%d ep_in=0x%x ep_out=0x%x vid=%04x pid=%04x report_len=%u\n",
        desc_len, m->hid_iface, m->ep_in, m->ep_out, (uint16_t)m->vendor, (uint16_t)m->product, m->report_desc_len);
    free(desc);

    /* Fetch the HID report descriptor via control GET_DESCRIPTOR(0x22). */
    if (m->report_desc_len > 0 && m->report_desc_len <= HID_MAX_DESCRIPTOR_SIZE) {
        uint8_t *rd = NULL; uint32_t rd_len = 0;
        int want = m->report_desc_len;
        int rc = proxy_control(fd, 0x81, 0x06, (USB_DT_REPORT << 8), m->hid_iface,
                               want, &rd, &rd_len);
        DBG("report ctrl: want=%d rc=%d rd_len=%u first=%02x%02x\n",
            want, rc, rd_len, rd && rd_len > 0 ? rd[0] : 0, rd && rd_len > 1 ? rd[1] : 0);
        if (rc > 0 && rd) {
            uint32_t cp = rd_len < (uint32_t)want ? rd_len : (uint32_t)want;
            memcpy(m->report_desc, rd, cp);
            m->report_desc_len = cp;
        }
        free(rd);
    }
    pthread_mutex_unlock(&g_lock);
    return fd;
}

/* ---- interposers --------------------------------------------------------- */
static int open_common(const char *path) {
    if (is_hidraw_path(path)) {
        int fd = open_managed();
        return fd; /* errno already set on failure */
    }
    return -2; /* sentinel: not ours, fall through */
}

int open(const char *path, int flags, ...) {
    init_reals();
    int r = open_common(path);
    if (r != -2) return r;
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_open(path, flags, mode);
}
int open64(const char *path, int flags, ...) {
    init_reals();
    int r = open_common(path);
    if (r != -2) return r;
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_open64 ? real_open64(path, flags, mode) : real_open(path, flags, mode);
}
int openat(int dirfd, const char *path, int flags, ...) {
    init_reals();
    int r = open_common(path);
    if (r != -2) return r;
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_openat(dirfd, path, flags, mode);
}
int openat64(int dirfd, const char *path, int flags, ...) {
    init_reals();
    int r = open_common(path);
    if (r != -2) return r;
    mode_t mode = 0;
    if (flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return real_openat64 ? real_openat64(dirfd, path, flags, mode) : real_openat(dirfd, path, flags, mode);
}

int ioctl(int fd, unsigned long request, ...) {
    init_reals();
    va_list ap; va_start(ap, request); void *arg = va_arg(ap, void *); va_end(ap);
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (!m) { pthread_mutex_unlock(&g_lock); return real_ioctl(fd, request, arg); }

    int ret = 0;
    if (request == (unsigned long)HIDIOCGRDESCSIZE) {
        *(int *)arg = (int)m->report_desc_len;
    } else if (request == (unsigned long)HIDIOCGRDESC) {
        struct haven_hidraw_report_descriptor *r = arg;
        uint32_t cp = r->size < m->report_desc_len ? r->size : m->report_desc_len;
        if (cp == 0) cp = m->report_desc_len;
        memcpy(r->value, m->report_desc, cp);
        r->size = cp;
    } else if (request == (unsigned long)HIDIOCGRAWINFO) {
        struct haven_hidraw_devinfo *info = arg;
        info->bustype = BUS_USB; info->vendor = m->vendor; info->product = m->product;
    } else if (_IOC_NR(request) == 0x04) { /* HIDIOCGRAWNAME(len) */
        snprintf((char *)arg, _IOC_SIZE(request), "Haven USB %04x:%04x",
                 (uint16_t)m->vendor, (uint16_t)m->product);
        ret = (int)strlen((char *)arg);
    } else if (_IOC_NR(request) == 0x05) { /* HIDIOCGRAWPHYS(len) */
        snprintf((char *)arg, _IOC_SIZE(request), "haven-usb");
        ret = (int)strlen((char *)arg);
    } else {
        errno = ENOTTY; ret = -1;
    }
    pthread_mutex_unlock(&g_lock);
    return ret;
}

ssize_t read(int fd, void *buf, size_t count) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (!m) { pthread_mutex_unlock(&g_lock); return real_read(fd, buf, count); }
    int ep = m->ep_in, sock = m->fd;
    pthread_mutex_unlock(&g_lock);
    if (!ep) { errno = EIO; return -1; }
    uint8_t *out = NULL; uint32_t out_len = 0;
    int rc = proxy_bulk(sock, ep, NULL, 0, (int)count, &out, &out_len);
    if (rc < 0) { free(out); errno = EIO; return -1; }
    uint32_t cp = out_len < count ? out_len : (uint32_t)count;
    if (out && cp) memcpy(buf, out, cp);
    free(out);
    return (ssize_t)cp;
}

ssize_t write(int fd, const void *buf, size_t count) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (!m) { pthread_mutex_unlock(&g_lock); return real_write(fd, buf, count); }
    int ep = m->ep_out, sock = m->fd;
    pthread_mutex_unlock(&g_lock);
    if (!ep) { errno = EIO; return -1; }
    int rc = proxy_bulk(sock, ep, buf, (int)count, 0, NULL, NULL);
    if (rc < 0) { errno = EIO; return -1; }
    return rc; /* bytes written */
}

int close(int fd) {
    init_reals();
    pthread_mutex_lock(&g_lock);
    struct managed *m = find(fd);
    if (m) {
        uint8_t op = OP_CLOSE;
        proxy_request(m->fd, &op, 1, NULL, NULL);
        m->fd = -1;
        pthread_mutex_unlock(&g_lock);
        return real_close(fd);
    }
    pthread_mutex_unlock(&g_lock);
    return real_close(fd);
}

int poll(struct pollfd *fds, nfds_t nfds, int timeout) {
    init_reals();
    /* If any polled fd is ours, report it readable: reads are synchronous proxy
     * round-trips that block on the device, so the caller can read() directly.
     * Non-managed fds are left to the real poll in a separate pass. */
    int managed_ready = 0, has_other = 0;
    pthread_mutex_lock(&g_lock);
    for (nfds_t i = 0; i < nfds; i++) {
        if (find(fds[i].fd)) { fds[i].revents = fds[i].events & POLLIN; if (fds[i].revents) managed_ready++; }
        else has_other = 1;
    }
    pthread_mutex_unlock(&g_lock);
    if (managed_ready && !has_other) return managed_ready;
    return real_poll(fds, nfds, managed_ready ? 0 : timeout);
}
