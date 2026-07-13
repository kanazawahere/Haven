package sh.haven.feature.sftp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Characterisation of the paste-walk contract: depth-first expansion in
 * listing order, destination-path construction, top-level flags, and the
 * symlink rules (top-level directory symlinks are followed, mid-walk
 * symlinks become leaves so cycles terminate).
 */
class PasteWalkTest {

    private fun file(path: String, size: Long = 1) =
        SftpEntry(path.substringAfterLast('/'), path, false, size, 0, "")

    private fun dir(path: String, isSymlink: Boolean = false) =
        SftpEntry(path.substringAfterLast('/'), path, true, 0, 0, "", isSymlink = isSymlink)

    /** Lister over a fixed parent→children map; unknown paths are empty. */
    private fun lister(tree: Map<String, List<SftpEntry>>): suspend (String) -> List<SftpEntry> =
        { path -> tree[path] ?: emptyList() }

    @Test fun flattensDepthFirstInListingOrder() = runBlocking {
        val tree = mapOf(
            "/src/a" to listOf(file("/src/a/1.txt"), dir("/src/a/sub"), file("/src/a/2.txt")),
            "/src/a/sub" to listOf(file("/src/a/sub/3.txt")),
        )
        val leaves = walkPasteLeaves(listOf(dir("/src/a")), "/dest", lister(tree))
        assertEquals(
            listOf("/dest/a/1.txt", "/dest/a/sub/3.txt", "/dest/a/2.txt"),
            leaves.map { it.destPath },
        )
        assertEquals(
            listOf("/src/a/1.txt", "/src/a/sub/3.txt", "/src/a/2.txt"),
            leaves.map { it.sourcePath },
        )
    }

    @Test fun topLevelFlagOnlyOnDirectClipboardFiles() = runBlocking {
        val tree = mapOf("/d" to listOf(file("/d/nested.txt")))
        val leaves = walkPasteLeaves(listOf(file("/top.txt"), dir("/d")), "/dest", lister(tree))
        assertEquals(listOf(true, false), leaves.map { it.isTopLevel })
    }

    @Test fun emptyDirectoryYieldsNoLeaves() = runBlocking {
        val leaves = walkPasteLeaves(listOf(dir("/empty")), "/dest", lister(emptyMap()))
        assertTrue(leaves.isEmpty())
    }

    @Test fun trailingSlashOnDestRootIsNormalised() = runBlocking {
        val leaves = walkPasteLeaves(listOf(file("/f.txt")), "/dest/", lister(emptyMap()))
        assertEquals("/dest/f.txt", leaves.single().destPath)
    }

    @Test fun topLevelSymlinkDirectoryIsFollowed() = runBlocking {
        val tree = mapOf("/link" to listOf(file("/link/inside.txt")))
        val leaves = walkPasteLeaves(listOf(dir("/link", isSymlink = true)), "/dest", lister(tree))
        assertEquals(listOf("/dest/link/inside.txt"), leaves.map { it.destPath })
    }

    @Test fun nestedSymlinkDirectoryBecomesLeafNotRecursion() = runBlocking {
        val tree = mapOf(
            "/a" to listOf(dir("/a/loop", isSymlink = true), file("/a/f.txt")),
            "/a/loop" to listOf(file("/a/loop/should-not-appear")),
        )
        val leaves = walkPasteLeaves(listOf(dir("/a")), "/dest", lister(tree))
        assertEquals(listOf("/dest/a/loop", "/dest/a/f.txt"), leaves.map { it.destPath })
    }

    @Test fun symlinkCycleTerminates() = runBlocking {
        // /a contains a symlink back to /a — recursing would never return.
        val tree = mapOf("/a" to listOf(dir("/a/self", isSymlink = true), file("/a/f.txt")))
        val leaves = walkPasteLeaves(listOf(dir("/a")), "/dest", lister(tree))
        assertEquals(2, leaves.size)
    }

    @Test fun listingFailurePropagates(): Unit = runBlocking {
        try {
            walkPasteLeaves(listOf(dir("/a")), "/dest") { throw IOException("channel closed") }
            fail("expected the listing failure to propagate")
        } catch (e: IOException) {
            assertEquals("channel closed", e.message)
        }
    }
}

class UniqueNameCandidateTest {

    @Test fun insertsCounterBeforeExtension() {
        assertEquals("/d/report (1).pdf", uniqueNameCandidate("/d/report.pdf", 1))
        assertEquals("/d/report (37).pdf", uniqueNameCandidate("/d/report.pdf", 37))
    }

    @Test fun noExtensionAppends() {
        assertEquals("/d/Makefile (2)", uniqueNameCandidate("/d/Makefile", 2))
    }

    @Test fun dotfileKeepsSuffixAtEnd() {
        assertEquals("/home/.bashrc (1)", uniqueNameCandidate("/home/.bashrc", 1))
    }

    @Test fun bareNameWithoutDirectory() {
        assertEquals("f (1).txt", uniqueNameCandidate("f.txt", 1))
    }
}
