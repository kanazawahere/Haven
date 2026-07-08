package sh.haven.feature.sftp

import android.database.Cursor
import android.provider.DocumentsContract
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #273: a picked Termux folder took ~30s to enumerate (4.3 MB, small tree) because
 * DocumentFile.listFiles() discards the cursor and each child.name/isDirectory/length()
 * issues its own ContentResolver.query(). These tests pin the fix: ONE query per
 * directory, and the metadata comes out of that single cursor.
 */
class ScanDocumentTreeTest {

    private val DIR = DocumentsContract.Document.MIME_TYPE_DIR

    /** Rows: (docId, name, mime, size). A null size models a provider that omits COLUMN_SIZE. */
    private fun cursorOf(rows: List<Array<Any?>>): Cursor {
        var pos = -1
        val c = io.mockk.mockk<Cursor>(relaxed = true)
        io.mockk.every { c.moveToNext() } answers { ++pos < rows.size }
        io.mockk.every { c.getString(0) } answers { rows[pos][0] as String? }
        io.mockk.every { c.getString(1) } answers { rows[pos][1] as String? }
        io.mockk.every { c.getString(2) } answers { rows[pos][2] as String? }
        io.mockk.every { c.isNull(3) } answers { rows[pos][3] == null }
        io.mockk.every { c.getLong(3) } answers { rows[pos][3] as Long }
        io.mockk.every { c.close() } returns Unit
        return c
    }

    /** tree: root/{a.txt(10), sub/{b.bin(20), deep/{c(30)}}, empty/} */
    private val tree: Map<String, List<Array<Any?>>> = mapOf(
        "root" to listOf(
            arrayOf<Any?>("id-a", "a.txt", "text/plain", 10L),
            arrayOf<Any?>("id-sub", "sub", DIR, null),
            arrayOf<Any?>("id-empty", "empty", DIR, null),
        ),
        "id-sub" to listOf(
            arrayOf<Any?>("id-b", "b.bin", "application/octet-stream", 20L),
            arrayOf<Any?>("id-deep", "deep", DIR, null),
        ),
        "id-deep" to listOf(arrayOf<Any?>("id-c", "c", "text/plain", 30L)),
        "id-empty" to emptyList(),
    )

    @Test
    fun `issues exactly one query per directory and no query per file`() = runTest {
        val queried = mutableListOf<String>()
        val files = scanDocumentTree(
            rootDocId = "root",
            rootName = "Termux",
            queryChildren = { id -> queried.add(id); cursorOf(tree[id] ?: emptyList()) },
        )
        // 4 directories (root, sub, deep, empty) -> 4 queries; the 3 files add none.
        // The old DocumentFile walk cost 4 listFiles() + ~3 queries x 6 children ≈ 22.
        assertEquals(4, queried.size)
        assertEquals(setOf("root", "id-sub", "id-deep", "id-empty"), queried.toSet())
        assertEquals(3, files.size)
    }

    @Test
    fun `emits leaf files only, with relative paths and sizes from the same cursor`() = runTest {
        val files = scanDocumentTree("root", "Termux", { id -> cursorOf(tree[id] ?: emptyList()) })
            .associateBy { it.relativePath }

        assertEquals(setOf("Termux/a.txt", "Termux/sub/b.bin", "Termux/sub/deep/c"), files.keys)
        assertEquals(10L, files.getValue("Termux/a.txt").length)
        assertEquals(20L, files.getValue("Termux/sub/b.bin").length)
        assertEquals(30L, files.getValue("Termux/sub/deep/c").length)
        assertEquals("id-c", files.getValue("Termux/sub/deep/c").docId)
    }

    @Test
    fun `a provider that omits SIZE yields zero rather than throwing`() = runTest {
        val rows = listOf(arrayOf<Any?>("id-x", "x.txt", "text/plain", null))
        val files = scanDocumentTree("root", "R", { cursorOf(rows) })
        assertEquals(1, files.size)
        assertEquals(0L, files.single().length)
    }

    @Test
    fun `a null cursor for a directory is skipped, not fatal`() = runTest {
        val files = scanDocumentTree("root", "R", { id -> if (id == "root") cursorOf(tree["root"]!!) else null })
        assertEquals(listOf("R/a.txt"), files.map { it.relativePath })
    }

    @Test
    fun `progress is reported every 25 files`() = runTest {
        val rows = (1..60).map { arrayOf<Any?>("id-$it", "f$it", "text/plain", 1L) }
        val ticks = mutableListOf<Int>()
        scanDocumentTree("root", "R", { cursorOf(rows) }, onProgress = { ticks.add(it) })
        assertEquals(listOf(25, 50), ticks)
    }
}
