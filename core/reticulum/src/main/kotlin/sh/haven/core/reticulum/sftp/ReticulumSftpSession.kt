package sh.haven.core.reticulum.sftp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import sh.haven.core.reticulum.ReticulumTransport
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * A persistent SFTP session over one Reticulum Link: it exec's `sftp-server`
 * once (lazily) and speaks SFTP v3 to it via [SftpV3Client]. All file ops
 * share the one Link — no per-op handshake (the throughput win over
 * `ReticulumFileBackend`, which opens a fresh Link per command).
 *
 * Held per-profile in `ReticulumSessionManager.SessionState` and torn down with
 * it. Exposes the [SftpEntry]-shaped surface the `feature/sftp` adapter maps to
 * the browser's entry type (this module can't depend on `feature/sftp`).
 *
 * Improvements over the exec backend: real mtime / owner / group / permissions
 * from SFTP ATTRS, and true `openInputStream(offset > 0)` (SFTP READ is seekable).
 */
class ReticulumSftpSession(
    private val transport: ReticulumTransport,
    private val destinationHash: String,
    private val sftpServerPath: String,
) {
    /** One directory/stat entry, mapped to the browser's `SftpEntry` by the adapter. */
    data class Entry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedTime: Long,
        val permissions: String,
        val owner: String,
        val group: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientMutex = Mutex()
    @Volatile private var client: SftpV3Client? = null

    private suspend fun client(): SftpV3Client {
        client?.let { if (!it.isClosed) return it }
        return clientMutex.withLock {
            client?.let { if (!it.isClosed) return it }
            // Exec sftp-server directly (binary subsystem, no shell). NOTE: the
            // markqvist rnsh listener echoes our stdin back onto stdout even in
            // all-pipe mode (process.py: "forward input immediately for
            // visibility"), interleaved with the server's responses;
            // SftpV3Client strips that echo. See the echo-handling there.
            val exec = transport.execCommand(destinationHash, listOf(sftpServerPath))
            val c = SftpV3Client(exec)
            try {
                c.handshake()
            } catch (e: Exception) {
                c.close()
                throw IOException("sftp-server handshake failed ($sftpServerPath): ${e.message}", e)
            }
            client = c
            c
        }
    }

    suspend fun list(path: String): List<Entry> {
        val dir = if (path.isBlank()) "/" else path.trimEnd('/').ifEmpty { "/" }
        val c = client()
        val handle = c.openDir(dir)
        val names = ArrayList<SftpV3Codec.SftpName>()
        try {
            while (true) {
                val batch = c.readDir(handle) ?: break
                names.addAll(batch)
            }
        } finally {
            c.closeHandle(handle)
        }
        val entries = ArrayList<Entry>(names.size)
        val symlinks = ArrayList<Int>()
        for (n in names) {
            if (n.filename == "." || n.filename == "..") continue
            val full = join(dir, n.filename)
            entries.add(n.toEntry(full))
            if (n.attrs.isSymlink) symlinks.add(entries.size - 1)
        }
        // Resolve symlink-to-directory so they navigate (mirror SftpTransport.list).
        for (i in symlinks) {
            val e = entries[i]
            try {
                val target = c.stat(e.path)
                if (target.isDirectory) entries[i] = e.copy(isDirectory = true)
            } catch (_: Exception) { /* dangling symlink — leave as file */ }
        }
        return entries
    }

    suspend fun stat(path: String): Entry {
        val attrs = client().stat(path)
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
        return attrs.toEntry(name, path, longname = null)
    }

    suspend fun readBytes(path: String): ByteArray {
        val c = client()
        val handle = c.openRead(path)
        val out = ByteArrayOutputStream()
        try {
            var offset = 0L
            while (true) {
                val chunk = c.read(handle, offset, READ_CHUNK) ?: break
                if (chunk.isEmpty()) break
                out.write(chunk); offset += chunk.size
            }
        } finally {
            c.closeHandle(handle)
        }
        return out.toByteArray()
    }

    /** Streaming read from [offset] (SFTP READ is seekable — unlike the exec backend). */
    suspend fun openInputStream(path: String, offset: Long): InputStream {
        val c = client()
        val handle = c.openRead(path)
        val pipeIn = PipedInputStream(64 * 1024)
        val pipeOut = PipedOutputStream(pipeIn)
        scope.launch {
            var pos = offset
            try {
                while (true) {
                    val chunk = c.read(handle, pos, READ_CHUNK) ?: break
                    if (chunk.isEmpty()) break
                    pipeOut.write(chunk); pos += chunk.size
                }
                pipeOut.flush()
            } catch (e: Throwable) {
                Log.w(TAG, "openInputStream pump failed for $path", e)
            } finally {
                runCatching { pipeOut.close() }
                c.closeHandle(handle)
            }
        }
        return pipeIn
    }

    suspend fun writeBytes(path: String, data: ByteArray) {
        val c = client()
        val handle = c.openWrite(path)
        try {
            var off = 0
            // SFTP WRITE supports a zero-length write to create an empty file.
            do {
                val n = minOf(WRITE_CHUNK, data.size - off)
                c.write(handle, off.toLong(), data, off, n)
                off += n
            } while (off < data.size)
        } finally {
            c.closeHandle(handle)
        }
    }

    /** `mkdir -p`: create missing parents; an existing directory is a no-op. */
    suspend fun mkdir(path: String) {
        val c = client()
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        var cur = ""
        for (p in parts) {
            cur += "/$p"
            try {
                val a = c.stat(cur)
                if (!a.isDirectory) throw IOException("$cur exists and is not a directory")
            } catch (_: FileNotFoundException) {
                c.mkdir(cur)
            }
        }
    }

    suspend fun rename(from: String, to: String) = client().rename(from, to)

    /** Recursive delete (the FileBackend contract). Symlinks are removed, not followed. */
    suspend fun delete(path: String) {
        val c = client()
        val attrs = try { c.lstat(path) } catch (e: FileNotFoundException) { return }
        if (attrs.isDirectory && !attrs.isSymlink) {
            val handle = c.openDir(path)
            val children = ArrayList<String>()
            try {
                while (true) {
                    val batch = c.readDir(handle) ?: break
                    for (n in batch) if (n.filename != "." && n.filename != "..") children.add(join(path, n.filename))
                }
            } finally {
                c.closeHandle(handle)
            }
            for (child in children) delete(child)
            c.rmdir(path)
        } else {
            c.remove(path)
        }
    }

    fun close() {
        client?.close()
        client = null
        scope.cancel()
    }

    // ---- mapping helpers -------------------------------------------------

    private fun SftpV3Codec.SftpName.toEntry(fullPath: String): Entry =
        attrs.toEntry(filename, fullPath, longname)

    private fun SftpV3Codec.SftpAttrs.toEntry(name: String, fullPath: String, longname: String?): Entry {
        // owner/group: parse the ls -l-style longname if present (OpenSSH gives
        // real names), else fall back to the numeric uid/gid from ATTRS.
        var owner = uid.toString()
        var group = gid.toString()
        if (longname != null) {
            val cols = longname.trim().split(Regex("\\s+"))
            if (cols.size >= 4 && cols[0].length == 10) { owner = cols[2]; group = cols[3] }
        }
        return Entry(
            name = name,
            path = fullPath,
            isDirectory = isDirectory,
            size = size,
            // SftpEntry.modifiedTime is epoch SECONDS (the browser formatter does
            // *1000, matching SSH/SCP); SFTP v3 mtime is already epoch seconds.
            // Passing millis here produced year-58384 dates.
            modifiedTime = modifiedTimeSeconds,
            permissions = permString(),
            owner = owner,
            group = group,
        )
    }

    private fun join(dir: String, name: String): String =
        if (dir == "/" || dir.isEmpty()) "/$name" else "${dir.trimEnd('/')}/$name"

    companion object {
        private const val TAG = "ReticulumSftpSession"
        private const val READ_CHUNK = 32 * 1024
        private const val WRITE_CHUNK = 16 * 1024
    }
}
