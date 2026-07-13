package sh.haven.feature.sftp.transport

import sh.haven.core.ssh.sftp.ListResult
import sh.haven.core.ssh.sftp.SftpAttrs
import sh.haven.core.ssh.sftp.SftpSession
import sh.haven.core.ssh.sftp.SftpWriteMode
import java.io.InputStream
import java.io.OutputStream

/** Test double — subclasses override only the members a test exercises. */
open class FakeSftpSession : SftpSession {
    override val isConnected: Boolean = true
    override suspend fun list(path: String, onEntry: (SftpAttrs) -> ListResult): Unit =
        throw NotImplementedError("list")
    override suspend fun stat(path: String): SftpAttrs = throw NotImplementedError("stat")
    override suspend fun download(
        srcPath: String,
        output: OutputStream,
        onBytes: (transferred: Long, total: Long) -> Unit,
    ): Unit = throw NotImplementedError("download")
    override suspend fun upload(
        input: InputStream,
        sizeHint: Long,
        destPath: String,
        mode: SftpWriteMode,
        onBytes: (transferred: Long, total: Long) -> Unit,
    ): Unit = throw NotImplementedError("upload")
    override suspend fun home(): String = throw NotImplementedError("home")
    override suspend fun openInputStream(path: String, offset: Long): InputStream =
        throw NotImplementedError("openInputStream")
    override suspend fun mkdir(path: String): Unit = throw NotImplementedError("mkdir")
    override suspend fun rmdir(path: String): Unit = throw NotImplementedError("rmdir")
    override suspend fun rm(path: String): Unit = throw NotImplementedError("rm")
    override suspend fun rename(from: String, to: String): Unit = throw NotImplementedError("rename")
    override suspend fun chmod(path: String, mode: Int): Unit = throw NotImplementedError("chmod")
    override fun close() {}
}

/**
 * Fake SFTP server filesystem for mkdir contract tests: single-level mkdir
 * that fails when the parent is missing (SSH_FX_NO_SUCH_FILE) or the path
 * already exists (SSH_FX_FAILURE) — the behaviour of real sftp-servers.
 */
class SftpServerSim(
    initialDirs: Set<String> = setOf("/"),
    private val files: Set<String> = emptySet(),
) : FakeSftpSession() {
    val dirs = initialDirs.toMutableSet()
    val mkdirCalls = mutableListOf<String>()

    override suspend fun mkdir(path: String) {
        mkdirCalls.add(path)
        val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
        if (parent !in dirs) throw java.io.IOException("no such file: $parent")
        if (path in dirs || path in files) throw java.io.IOException("failure: $path exists")
        dirs.add(path)
    }

    override suspend fun stat(path: String): SftpAttrs = when {
        path in dirs -> SftpAttrs(path.substringAfterLast('/'), true, false, 0, 0, "drwxr-xr-x", 0, 0)
        path in files -> SftpAttrs(path.substringAfterLast('/'), false, false, 1, 0, "-rw-r--r--", 0, 0)
        else -> throw java.io.IOException("no such file: $path")
    }
}
