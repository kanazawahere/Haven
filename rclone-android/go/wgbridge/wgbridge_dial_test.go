package wgbridge

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// These tests peer two userspace WireGuard tunnels over loopback UDP (the
// upstream wireguard-go netstack example pattern) so we can exercise the
// real TunnelHandle.Dial / ListenTCP code paths without a device or a
// remote peer. Used to reproduce the regression where a second outbound
// dial through a netstack that also hosts a live ListenTCP listener times
// out (the "2nd tmux session over WireGuard" failure) — see #230-adjacent
// tmux report.

func genKey(t *testing.T) (privHex, pubHex string) {
	t.Helper()
	var priv [32]byte
	if _, err := rand.Read(priv[:]); err != nil {
		t.Fatal(err)
	}
	// curve25519 clamping.
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64
	pub, err := curve25519.X25519(priv[:], curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(priv[:]), hex.EncodeToString(pub)
}

// bringUp creates a netstack-backed device at addr with no peer configured
// yet (added later once both listen ports are known), and returns the
// TunnelHandle, the device, and the actual UDP listen port the kernel picked.
func bringUp(t *testing.T, privHex, addr string) (*TunnelHandle, *device.Device, int) {
	t.Helper()
	ip := netip.MustParseAddr(addr)
	tun, tnet, err := netstack.CreateNetTUN([]netip.Addr{ip}, nil, 1420)
	if err != nil {
		t.Fatalf("CreateNetTUN(%s): %v", addr, err)
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(),
		device.NewLogger(device.LogLevelError, "wgtest("+addr+"): "))
	if err := dev.IpcSet("private_key=" + privHex + "\nlisten_port=0\n"); err != nil {
		t.Fatalf("IpcSet(%s): %v", addr, err)
	}
	if err := dev.Up(); err != nil {
		t.Fatalf("Up(%s): %v", addr, err)
	}
	return &TunnelHandle{dev: dev, tnet: tnet, bindAddr: ip}, dev, listenPort(t, dev)
}

func listenPort(t *testing.T, dev *device.Device) int {
	t.Helper()
	cfg, err := dev.IpcGet()
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(cfg, "\n") {
		if v, ok := strings.CutPrefix(line, "listen_port="); ok {
			var p int
			fmt.Sscanf(v, "%d", &p)
			return p
		}
	}
	t.Fatal("no listen_port in IpcGet output")
	return 0
}

func addPeer(t *testing.T, dev *device.Device, peerPubHex, peerAddr string, peerPort int) {
	t.Helper()
	uapi := fmt.Sprintf("public_key=%s\nendpoint=127.0.0.1:%d\nallowed_ip=%s/32\n",
		peerPubHex, peerPort, peerAddr)
	if err := dev.IpcSet(uapi); err != nil {
		t.Fatalf("IpcSet add peer: %v", err)
	}
}

// echoServe accepts on ln and echoes every chunk back, one goroutine per
// connection, until the listener is closed.
func echoServe(ln *Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go func(c *Conn) {
			defer c.Close()
			for {
				b, err := c.Read(4096)
				if err != nil || len(b) == 0 {
					return
				}
				if err := c.Write(b); err != nil {
					return
				}
			}
		}(c)
	}
}

// TestConcurrentDialThroughNetstack reproduces the "second dial fails"
// regression. Tunnel A dials tunnel B's echo server repeatedly, keeping
// every connection open (mirroring multiple live tmux sessions). After a
// ListenTCP listener is bound on A's netstack (mirroring the MCP endpoint
// now sharing the SSH tunnel), we check whether a fresh dial still works.
func TestConcurrentDialThroughNetstack(t *testing.T) {
	privA, pubA := genKey(t)
	privB, pubB := genKey(t)
	const addrA, addrB = "10.55.0.1", "10.55.0.2"

	tunA, devA, portA := bringUp(t, privA, addrA)
	defer devA.Close()
	tunB, devB, portB := bringUp(t, privB, addrB)
	defer devB.Close()

	addPeer(t, devA, pubB, addrB, portB)
	addPeer(t, devB, pubA, addrA, portA)

	lnB, err := tunB.ListenTCP(22)
	if err != nil {
		t.Fatalf("B ListenTCP(22): %v", err)
	}
	defer lnB.Close()
	go echoServe(lnB)

	open := make([]*Conn, 0)
	dial := func(label string) bool {
		c, err := tunA.Dial(addrB, 22, 4000)
		if err != nil {
			t.Logf("%-28s dial FAILED: %v", label, err)
			return false
		}
		// Confirm the link actually carries data (deadline via the
		// in-package net.Conn so a half-open dial can't hang the test).
		_ = c.c.SetDeadline(time.Now().Add(2 * time.Second))
		if err := c.Write([]byte("ping")); err != nil {
			t.Logf("%-28s connected but write FAILED: %v", label, err)
			c.Close()
			return false
		}
		b, err := c.Read(16)
		if err != nil || string(b) != "ping" {
			t.Logf("%-28s connected but echo FAILED: got %q err=%v", label, b, err)
			c.Close()
			return false
		}
		_ = c.c.SetDeadline(time.Time{})
		open = append(open, c) // keep it open, like a live session
		t.Logf("%-28s OK", label)
		return true
	}
	defer func() {
		for _, c := range open {
			c.Close()
		}
	}()

	ok1 := dial("dial#1 (no A-listener)")
	ok2 := dial("dial#2 (no A-listener)")

	lnA, err := tunA.ListenTCP(8730)
	if err != nil {
		t.Fatalf("A ListenTCP(8730): %v", err)
	}
	defer lnA.Close()
	go func() {
		for {
			c, err := lnA.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()

	ok3 := dial("dial#3 (A-listener active)")
	ok4 := dial("dial#4 (A-listener active)")

	t.Logf("SUMMARY ok1=%v ok2=%v ok3=%v ok4=%v", ok1, ok2, ok3, ok4)
	if !ok1 {
		t.Fatal("dial#1 failed — test harness broken (handshake/peering), not the regression")
	}
	// The regression: a later dial stops working. Surface exactly which.
	if !(ok2 && ok3 && ok4) {
		t.Errorf("REGRESSION REPRODUCED: a follow-up dial failed (ok2=%v ok3=%v ok4=%v)", ok2, ok3, ok4)
	}
}

// TestDialUnderLoad mirrors production more faithfully: A's listener has a
// live, busy inbound connection (the laptop↔device MCP link), the first
// session stays open and transferring, and then a burst of TRULY CONCURRENT
// dials goes out at once (the MCP watchdog dialing at the same moment the
// user opens a second session). Checks every concurrent dial completes.
func TestDialUnderLoad(t *testing.T) {
	privA, pubA := genKey(t)
	privB, pubB := genKey(t)
	const addrA, addrB = "10.56.0.1", "10.56.0.2"

	tunA, devA, portA := bringUp(t, privA, addrA)
	defer devA.Close()
	tunB, devB, portB := bringUp(t, privB, addrB)
	defer devB.Close()
	addPeer(t, devA, pubB, addrB, portB)
	addPeer(t, devB, pubA, addrA, portA)

	// B echoes on :22 (the SSH host the user dials).
	lnB, err := tunB.ListenTCP(22)
	if err != nil {
		t.Fatalf("B ListenTCP(22): %v", err)
	}
	defer lnB.Close()
	go echoServe(lnB)

	// A hosts the MCP listener on :8730 (shares A's netstack with the dials).
	lnA, err := tunA.ListenTCP(8730)
	if err != nil {
		t.Fatalf("A ListenTCP(8730): %v", err)
	}
	defer lnA.Close()
	go echoServe(lnA)

	stop := make(chan struct{})
	defer close(stop)

	// Live, busy inbound link: B dials A:8730 and hammers it continuously
	// (stand-in for the active laptop↔device MCP connection).
	busy, err := tunB.Dial(addrA, 8730, 4000)
	if err != nil {
		t.Fatalf("busy inbound link dial: %v", err)
	}
	defer busy.Close()
	go func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			if err := busy.Write([]byte("xxxxxxxx")); err != nil {
				return
			}
			if _, err := busy.Read(64); err != nil {
				return
			}
		}
	}()

	// One long-lived outbound session that stays open + transferring.
	first, err := tunA.Dial(addrB, 22, 4000)
	if err != nil {
		t.Fatalf("first session dial: %v", err)
	}
	defer first.Close()
	go func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			if err := first.Write([]byte("yyyy")); err != nil {
				return
			}
			if _, err := first.Read(64); err != nil {
				return
			}
		}
	}()

	// Now a burst of truly-concurrent dials to the same host:port.
	const burst = 8
	results := make([]error, burst)
	var wg sync.WaitGroup
	for i := 0; i < burst; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			c, err := tunA.Dial(addrB, 22, 6000)
			if err != nil {
				results[i] = err
				return
			}
			defer c.Close()
			_ = c.c.SetDeadline(time.Now().Add(3 * time.Second))
			if err := c.Write([]byte("ping")); err != nil {
				results[i] = fmt.Errorf("write: %w", err)
				return
			}
			if b, err := c.Read(16); err != nil || string(b) != "ping" {
				results[i] = fmt.Errorf("echo: got %q err=%w", b, err)
			}
		}(i)
	}
	wg.Wait()

	fails := 0
	for i, err := range results {
		if err != nil {
			fails++
			t.Logf("concurrent dial #%d FAILED: %v", i, err)
		}
	}
	t.Logf("SUMMARY concurrent burst: %d/%d ok", burst-fails, burst)
	if fails > 0 {
		t.Errorf("REGRESSION REPRODUCED: %d/%d concurrent dials failed", fails, burst)
	}
}
