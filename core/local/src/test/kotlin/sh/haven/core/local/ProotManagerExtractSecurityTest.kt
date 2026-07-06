package sh.haven.core.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Security coverage for the rootfs extractor/deleter: a malicious imported
 * tarball must not escape the install dir (zip-slip, security-review #21) and
 * deleting a rootfs must never follow a symlink out of the tree (#22).
 */
class ProotManagerExtractSecurityTest {

    @Test
    fun `isWithinDir accepts the dir itself and real children`() {
        val base = Files.createTempDirectory("within-base").toFile()
        try {
            assertTrue(isWithinDir(base, base))
            assertTrue(isWithinDir(base, File(base, "usr/bin/busybox")))
        } finally {
            forceDeleteRecursively(base)
        }
    }

    @Test
    fun `isWithinDir rejects dotdot traversal`() {
        val base = Files.createTempDirectory("within-base").toFile()
        try {
            assertFalse(isWithinDir(base, File(base, "../../etc/passwd")))
        } finally {
            forceDeleteRecursively(base)
        }
    }

    @Test
    fun `isWithinDir rejects a sibling sharing the name prefix`() {
        val base = Files.createTempDirectory("within-base").toFile()
        val sibling = File(base.parentFile, base.name + "2").apply { mkdirs() }
        try {
            // /x/base vs /x/base2 must not be treated as parent/child.
            assertFalse(isWithinDir(base, sibling))
        } finally {
            forceDeleteRecursively(base)
            forceDeleteRecursively(sibling)
        }
    }

    @Test
    fun `isWithinDir rejects a path written through an escaping symlink`() {
        val base = Files.createTempDirectory("within-base").toFile()
        val external = Files.createTempDirectory("within-ext").toFile()
        try {
            Files.createSymbolicLink(File(base, "link").toPath(), external.toPath())
            assertFalse(isWithinDir(base, File(base, "link/pwned")))
        } finally {
            forceDeleteRecursively(base)
            forceDeleteRecursively(external)
        }
    }

    @Test
    fun `forceDeleteRecursively unlinks a symlink without deleting its target`() {
        val base = Files.createTempDirectory("del-base").toFile()
        val external = Files.createTempDirectory("del-ext").toFile()
        val externalFile = File(external, "keep.txt").apply { writeText("important") }
        try {
            Files.createSymbolicLink(File(base, "escape").toPath(), external.toPath())

            assertTrue(forceDeleteRecursively(base))
            assertFalse("the rootfs tree is gone", base.exists())
            assertTrue("the symlink target must survive the delete", externalFile.exists())
        } finally {
            forceDeleteRecursively(external)
        }
    }
}
