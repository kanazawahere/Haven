package sh.haven.feature.sftp.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.ssh.sftp.ListResult
import sh.haven.core.ssh.sftp.SftpAttrs

/**
 * Pins [SftpTransport.list] symlink handling: directory symlinks are
 * resolved to navigable directories (post-list, #144 buffer rule) and
 * every symlink carries [sh.haven.feature.sftp.SftpEntry.isSymlink] so the
 * paste walker can refuse to recurse through it.
 */
class SftpTransportSymlinkTest {

    private fun attrs(name: String, dir: Boolean = false, link: Boolean = false) =
        SftpAttrs(name, dir, link, if (dir) 0 else 5, 0, "", 0, 0)

    private fun session(entries: List<SftpAttrs>, statResults: Map<String, SftpAttrs>) =
        object : FakeSftpSession() {
            override suspend fun list(path: String, onEntry: (SftpAttrs) -> ListResult) {
                entries.forEach { if (onEntry(it) == ListResult.BREAK) return }
            }
            override suspend fun stat(path: String): SftpAttrs =
                statResults[path] ?: throw java.io.IOException("no such file: $path")
        }

    @Test fun directorySymlinkIsResolvedAndMarked() = runBlocking {
        val s = session(
            entries = listOf(attrs("real", dir = true), attrs("link", link = true)),
            statResults = mapOf("/root/link" to attrs("link", dir = true)),
        )
        val entries = SftpTransport({ s }).list("/root")
        val link = entries.single { it.name == "link" }
        assertTrue(link.isDirectory)
        assertTrue(link.isSymlink)
        val real = entries.single { it.name == "real" }
        assertTrue(real.isDirectory)
        assertFalse(real.isSymlink)
    }

    @Test fun fileSymlinkStaysFileAndMarked() = runBlocking {
        val s = session(
            entries = listOf(attrs("flink", link = true)),
            statResults = mapOf("/root/flink" to attrs("flink")),
        )
        val e = SftpTransport({ s }).list("/root").single()
        assertFalse(e.isDirectory)
        assertTrue(e.isSymlink)
    }

    @Test fun brokenSymlinkKeptAsNonDirectory() = runBlocking {
        val s = session(entries = listOf(attrs("dangling", link = true)), statResults = emptyMap())
        val e = SftpTransport({ s }).list("/root").single()
        assertFalse(e.isDirectory)
        assertTrue(e.isSymlink)
        assertEquals("/root/dangling", e.path)
    }
}
