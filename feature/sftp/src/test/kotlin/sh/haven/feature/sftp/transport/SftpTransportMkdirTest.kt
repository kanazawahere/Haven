package sh.haven.feature.sftp.transport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Pins the [FileBackend.mkdir] contract (`mkdir -p` semantics) for
 * [SftpTransport]: missing intermediate parents are created, an existing
 * directory is a no-op, and file collisions / genuine failures propagate.
 */
class SftpTransportMkdirTest {

    private fun transport(session: FakeSftpSession) = SftpTransport({ session })

    @Test fun createsMissingIntermediateParents() = runBlocking {
        val sim = SftpServerSim()
        transport(sim).mkdir("/a/b/c")
        assertTrue("/a" in sim.dirs)
        assertTrue("/a/b" in sim.dirs)
        assertTrue("/a/b/c" in sim.dirs)
    }

    @Test fun existingDirectoryIsNoOp() = runBlocking {
        val sim = SftpServerSim(initialDirs = setOf("/", "/a"))
        transport(sim).mkdir("/a") // must not throw
        assertEquals(setOf("/", "/a"), sim.dirs)
    }

    @Test fun existingPrefixIsWalkedThrough() = runBlocking {
        val sim = SftpServerSim(initialDirs = setOf("/", "/a"))
        transport(sim).mkdir("/a/b")
        assertTrue("/a/b" in sim.dirs)
    }

    @Test fun fileCollisionPropagates() = runBlocking {
        val sim = SftpServerSim(files = setOf("/a"))
        try {
            transport(sim).mkdir("/a/b")
            fail("expected IOException for file collision at /a")
        } catch (_: IOException) {
            // expected — /a is a file, not a directory
        }
    }

    @Test fun genuineFailurePropagates() = runBlocking {
        val boom = IOException("permission denied")
        val session = object : FakeSftpSession() {
            override suspend fun mkdir(path: String) = throw boom
            override suspend fun stat(path: String) = throw IOException("no such file")
        }
        try {
            transport(session).mkdir("/denied/dir")
            fail("expected the mkdir failure to propagate")
        } catch (e: IOException) {
            assertEquals("permission denied", e.message)
        }
    }
}
