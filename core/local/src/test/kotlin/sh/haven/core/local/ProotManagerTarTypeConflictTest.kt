package sh.haven.core.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Regression coverage for #324: a malformed or duplicate tar entry can reuse
 * a path with a different type than what's already there (e.g. a leftover
 * directory placeholder later replaced by a regular file). Without
 * [clearPathIfWrongType], `mkdirs()` / `FileOutputStream` silently no-op or
 * throw on the stale type instead of letting the new entry win — which can
 * leave a file dpkg expects (e.g. `/var/lib/dpkg/status`) as a directory,
 * and proot's link2symlink extension returns EPERM when asked to hardlink a
 * directory.
 */
class ProotManagerTarTypeConflictTest {

    private fun tempDir(): File = Files.createTempDirectory("tar-type-conflict-test").toFile()

    @Test
    fun `a leftover directory is replaced when the entry wants a file`() {
        val root = tempDir()
        val path = File(root, "var/lib/dpkg/status").apply { mkdirs() }
        assertTrue(path.isDirectory)

        clearPathIfWrongType(path, entryIsDir = false)

        assertTrue("the stale directory should be gone", !path.exists())
        forceDeleteRecursively(root)
    }

    @Test
    fun `a leftover non-empty directory is fully cleared when the entry wants a file`() {
        val root = tempDir()
        val path = File(root, "var/lib/dpkg/status").apply { mkdirs() }
        File(path, "nested.txt").writeText("stale")
        assertTrue(path.isDirectory)

        clearPathIfWrongType(path, entryIsDir = false)

        assertTrue("a non-empty stale directory should be fully removed", !path.exists())
        forceDeleteRecursively(root)
    }

    @Test
    fun `a leftover file is replaced when the entry wants a directory`() {
        val root = tempDir()
        File(root, "var/lib").mkdirs()
        val path = File(root, "var/lib/dpkg")
        path.writeText("stale file where a directory belongs")
        assertTrue(path.isFile)

        clearPathIfWrongType(path, entryIsDir = true)

        assertTrue("the stale file should be gone", !path.exists())
        forceDeleteRecursively(root)
    }

    @Test
    fun `a matching-type path is left untouched`() {
        val root = tempDir()
        File(root, "var/lib/dpkg").mkdirs()
        val file = File(root, "var/lib/dpkg/status")
        file.writeText("real status content")

        clearPathIfWrongType(file, entryIsDir = false)

        assertTrue("a same-type file should survive the guard", file.exists())
        assertEquals("real status content", file.readText())
        forceDeleteRecursively(root)
    }

    @Test
    fun `a nonexistent path is a no-op`() {
        val root = tempDir()
        val missing = File(root, "var/lib/dpkg/status")

        clearPathIfWrongType(missing, entryIsDir = false)

        assertTrue(!missing.exists())
        forceDeleteRecursively(root)
    }
}
