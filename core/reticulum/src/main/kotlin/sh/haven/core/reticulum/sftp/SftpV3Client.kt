package sh.haven.core.reticulum.sftp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import sh.haven.core.reticulum.ReticulumExecSession
import sh.haven.core.reticulum.sftp.SftpV3Codec.SftpPacket
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * An SFTP v3 client driven over a single [ReticulumExecSession] (an exec'd
 * `sftp-server` over one persistent Reticulum Link). Every file op shares ONE
 * Link — no per-op handshake.
 *
 * **Wire constraints (from the rnsh substrate):**
 *  - [ReticulumExecSession.writeStdin] sends one MDU-bounded Channel message per
 *    call, so packets are chunked at [STDIN_CHUNK]; the server reassembles by
 *    SFTP length-prefix.
 *  - `stdout` is an UNLIMITED flow of raw byte deltas at arbitrary boundaries; a
 *    single reader coroutine drains it and re-frames length-prefixed packets.
 *  - We NEVER call [ReticulumExecSession.closeStdin] (the Python listener kills
 *    the command ~50 ms after a stdin-EOF). [close] tears down the Link.
 *  - **The markqvist/rnsh listener echoes stdin onto stdout** (process.py:
 *    "forward input immediately for visibility"), mirroring every request back —
 *    as whole, well-framed SFTP packets — interleaved with the server's
 *    responses in no guaranteed order. SFTP requests and responses occupy
 *    disjoint msgType ranges, so the reader simply drops any request-typed
 *    packet (the echo) and dispatches only responses ([SftpV3Codec.isResponseType]).
 *    A direct pipe (no echo) carries only responses and is unaffected.
 *
 * **v1 is synchronous** — [requestMutex] serializes each request→response.
 */
class SftpV3Client(private val exec: ReticulumExecSession) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<SftpPacket>>()
    private val versionDeferred = CompletableDeferred<SftpPacket.Version>()
    private val nextId = AtomicInteger(1)
    private val requestMutex = Mutex()

    @Volatile private var closed = false

    init {
        startReader()
        scope.launch { runCatching { exec.stderr.collect { /* discard */ } } }
    }

    // ---- public op surface ----------------------------------------------

    /** Send FXP_INIT, await FXP_VERSION; require protocol v3. */
    suspend fun handshake() {
        requestMutex.withLock { send(SftpV3Codec.buildInit()) }
        val version = withTimeout(HANDSHAKE_TIMEOUT_MS) { versionDeferred.await() }
        if (version.version < SftpV3Codec.PROTOCOL_VERSION) {
            throw IOException("sftp-server speaks v${version.version}; need v${SftpV3Codec.PROTOCOL_VERSION}")
        }
    }

    suspend fun realPath(path: String): String =
        when (val p = request { SftpV3Codec.buildRealPath(it, path) }) {
            is SftpPacket.Name -> p.names.firstOrNull()?.filename ?: path
            is SftpPacket.Status -> throw SftpV3Codec.statusToException(p)
            else -> throw IOException("realpath: unexpected ${p::class.simpleName}")
        }

    suspend fun openDir(path: String): ByteArray = expectHandle("opendir") { SftpV3Codec.buildOpenDir(it, path) }

    /** Returns the next batch of names, or null at end of directory. */
    suspend fun readDir(handle: ByteArray): List<SftpV3Codec.SftpName>? =
        when (val p = request { SftpV3Codec.buildReadDir(it, handle) }) {
            is SftpPacket.Name -> p.names
            is SftpPacket.Status -> if (p.code == SftpV3Codec.FX_EOF) null else throw SftpV3Codec.statusToException(p)
            else -> throw IOException("readdir: unexpected ${p::class.simpleName}")
        }

    suspend fun closeHandle(handle: ByteArray) {
        runCatching { expectOk("close") { SftpV3Codec.buildClose(it, handle) } }
    }

    suspend fun stat(path: String): SftpV3Codec.SftpAttrs = expectAttrs("stat") { SftpV3Codec.buildStat(it, path) }
    suspend fun lstat(path: String): SftpV3Codec.SftpAttrs = expectAttrs("lstat") { SftpV3Codec.buildLStat(it, path) }

    suspend fun openRead(path: String): ByteArray =
        expectHandle("open") { SftpV3Codec.buildOpen(it, path, SftpV3Codec.FXF_READ) }

    suspend fun openWrite(path: String): ByteArray =
        expectHandle("open") {
            SftpV3Codec.buildOpen(it, path, SftpV3Codec.FXF_WRITE or SftpV3Codec.FXF_CREAT or SftpV3Codec.FXF_TRUNC)
        }

    /** Returns the data read, or null at EOF. */
    suspend fun read(handle: ByteArray, offset: Long, len: Int): ByteArray? =
        when (val p = request { SftpV3Codec.buildRead(it, handle, offset, len) }) {
            is SftpPacket.Data -> p.data
            is SftpPacket.Status -> if (p.code == SftpV3Codec.FX_EOF) null else throw SftpV3Codec.statusToException(p)
            else -> throw IOException("read: unexpected ${p::class.simpleName}")
        }

    suspend fun write(handle: ByteArray, offset: Long, data: ByteArray, dataOff: Int, dataLen: Int) =
        expectOk("write") { SftpV3Codec.buildWrite(it, handle, offset, data, dataOff, dataLen) }

    suspend fun mkdir(path: String) = expectOk("mkdir") { SftpV3Codec.buildMkdir(it, path) }
    suspend fun rmdir(path: String) = expectOk("rmdir") { SftpV3Codec.buildRmdir(it, path) }
    suspend fun remove(path: String) = expectOk("remove") { SftpV3Codec.buildRemove(it, path) }
    suspend fun rename(from: String, to: String) = expectOk("rename") { SftpV3Codec.buildRename(it, from, to) }

    /** Tear down the Link (never sends a stdin-EOF) and fail any pending ops. */
    fun close() {
        if (closed) return
        closed = true
        runCatching { exec.close() }
        failAll(IOException("reticulum sftp session closed"))
        scope.cancel()
    }

    val isClosed: Boolean get() = closed

    // ---- request plumbing ------------------------------------------------

    private suspend inline fun request(crossinline build: (Int) -> ByteArray): SftpPacket {
        if (closed) throw IOException("reticulum sftp session closed")
        val id = nextId.getAndIncrement() and 0x7FFFFFFF
        val deferred = CompletableDeferred<SftpPacket>()
        return requestMutex.withLock {
            pending[id] = deferred
            try {
                send(build(id))
                withTimeout(OP_TIMEOUT_MS) { deferred.await() }
            } finally {
                pending.remove(id)
            }
        }
    }

    private suspend inline fun expectHandle(op: String, crossinline build: (Int) -> ByteArray): ByteArray =
        when (val p = request(build)) {
            is SftpPacket.Handle -> p.handle
            is SftpPacket.Status -> throw SftpV3Codec.statusToException(p)
            else -> throw IOException("$op: unexpected ${p::class.simpleName}")
        }

    private suspend inline fun expectAttrs(op: String, crossinline build: (Int) -> ByteArray): SftpV3Codec.SftpAttrs =
        when (val p = request(build)) {
            is SftpPacket.Attrs -> p.attrs
            is SftpPacket.Status -> throw SftpV3Codec.statusToException(p)
            else -> throw IOException("$op: unexpected ${p::class.simpleName}")
        }

    private suspend inline fun expectOk(op: String, crossinline build: (Int) -> ByteArray) {
        when (val p = request(build)) {
            is SftpPacket.Status -> if (p.code != SftpV3Codec.FX_OK) throw SftpV3Codec.statusToException(p)
            else -> throw IOException("$op: unexpected ${p::class.simpleName}")
        }
    }

    /** Chunk a framed packet under the per-writeStdin MDU ceiling. */
    private suspend fun send(packet: ByteArray) {
        var off = 0
        while (off < packet.size) {
            val end = minOf(off + STDIN_CHUNK, packet.size)
            exec.writeStdin(packet.copyOfRange(off, end))
            off = end
        }
    }

    private fun startReader() {
        scope.launch {
            var acc = ByteArray(0)
            try {
                exec.stdout.collect { delta ->
                    acc = if (acc.isEmpty()) delta else acc + delta

                    var pos = 0
                    while (acc.size - pos >= 4) {
                        val len = readU32(acc, pos)
                        if (len < 1 || len > SftpV3Codec.MAX_PACKET) throw IOException("sftp framing desync: $len")
                        if (acc.size - pos - 4 < len) break
                        val type = acc[pos + 4].toInt() and 0xFF
                        val body = acc.copyOfRange(pos + 4, pos + 4 + len)
                        pos += 4 + len
                        // The markqvist rnsh listener echoes our stdin — whole SFTP
                        // *request* packets — back onto stdout, interleaved with the
                        // server's responses and in no guaranteed order. Requests and
                        // responses use disjoint msgType ranges, so drop any
                        // request-typed packet (the echo) and dispatch only responses.
                        if (SftpV3Codec.isResponseType(type)) {
                            dispatch(SftpV3Codec.parsePacket(body))
                        }
                    }
                    acc = if (pos == acc.size) EMPTY else acc.copyOfRange(pos, acc.size)
                }
                failAll(IOException("reticulum sftp link closed"))
            } catch (e: CancellationException) {
                failAll(IOException("reticulum sftp session closed"))
            } catch (e: Throwable) {
                failAll(IOException("reticulum sftp reader error: ${e.message}", e))
            }
        }
    }

    private fun dispatch(packet: SftpPacket) {
        if (packet is SftpPacket.Version) {
            versionDeferred.complete(packet)
            return
        }
        val id = when (packet) {
            is SftpPacket.Status -> packet.id
            is SftpPacket.Handle -> packet.id
            is SftpPacket.Data -> packet.id
            is SftpPacket.Name -> packet.id
            is SftpPacket.Attrs -> packet.id
            is SftpPacket.Version -> return
        }
        pending.remove(id)?.complete(packet)
    }

    private fun failAll(cause: IOException) {
        if (!versionDeferred.isCompleted) versionDeferred.completeExceptionally(cause)
        // Drain the live concurrent map's weakly-consistent value view directly and fail
        // each pending op (completeExceptionally is idempotent, so a racing dispatch() that
        // already completed one is harmless). The previous `pending.keys.toList()` snapshot
        // raced with concurrent pending.remove() in dispatch()/request(): at size==1, Kotlin's
        // toList() fast-path calls iterator().next(), which threw NoSuchElementException when
        // the sole entry was removed mid-snapshot.
        for (deferred in pending.values) deferred.completeExceptionally(cause)
        pending.clear()
    }

    private fun readU32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    companion object {
        /** Max bytes per writeStdin call (one Channel message, under link MDU ~423). */
        private const val STDIN_CHUNK = 384

        private const val HANDSHAKE_TIMEOUT_MS = 30_000L
        private const val OP_TIMEOUT_MS = 120_000L

        private val EMPTY = ByteArray(0)
    }
}
