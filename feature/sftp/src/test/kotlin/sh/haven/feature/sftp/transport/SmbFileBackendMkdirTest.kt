package sh.haven.feature.sftp.transport

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import sh.haven.core.smb.SmbClient
import sh.haven.core.smb.SmbFileEntry

/**
 * Pins the [FileBackend.mkdir] contract (`mkdir -p` semantics) for
 * [SmbFileBackend]. smbj's DiskShare.mkdir creates a single level and
 * fails when the parent is missing (#273) — the backend must walk.
 */
class SmbFileBackendMkdirTest {

    /** mockk'd SmbClient simulating smbj: single-level mkdir over [dirs]. */
    private fun clientSim(dirs: MutableSet<String>, files: Set<String> = emptySet()): SmbClient {
        val client = mockk<SmbClient>()
        every { client.mkdir(any()) } answers {
            val path = firstArg<String>()
            val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
            if (parent !in dirs) throw RuntimeException("STATUS_OBJECT_PATH_NOT_FOUND")
            if (path in dirs || path in files) throw RuntimeException("STATUS_OBJECT_NAME_COLLISION")
            dirs.add(path)
        }
        every { client.listDirectory(any()) } answers {
            val parent = firstArg<String>()
            val children = dirs.filter {
                it != "/" && it.substringBeforeLast('/', "").ifEmpty { "/" } == parent
            }.map { SmbFileEntry(it.substringAfterLast('/'), it, true, 0, 0, "") } +
                files.filter {
                    it.substringBeforeLast('/', "").ifEmpty { "/" } == parent
                }.map { SmbFileEntry(it.substringAfterLast('/'), it, false, 1, 0, "") }
            children
        }
        return client
    }

    @Test fun createsMissingIntermediateParents() = runBlocking {
        val dirs = mutableSetOf("/")
        SmbFileBackend(clientSim(dirs)).mkdir("/a/b/c")
        assertTrue("/a" in dirs)
        assertTrue("/a/b" in dirs)
        assertTrue("/a/b/c" in dirs)
    }

    @Test fun existingDirectoryIsNoOp() = runBlocking {
        val dirs = mutableSetOf("/", "/a")
        SmbFileBackend(clientSim(dirs)).mkdir("/a") // must not throw
        assertTrue("/a" in dirs)
    }

    @Test fun fileCollisionPropagates(): Unit = runBlocking {
        val dirs = mutableSetOf("/")
        try {
            SmbFileBackend(clientSim(dirs, files = setOf("/a"))).mkdir("/a/b")
            fail("expected failure for file collision at /a")
        } catch (_: Exception) {
            // expected
        }
    }
}
