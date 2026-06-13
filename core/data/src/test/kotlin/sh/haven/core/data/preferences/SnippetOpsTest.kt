package sh.haven.core.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the snippet placement/library logic behind #244: a snippet toggled
 * "Off" must move to the library (still reachable from the scissors sheet),
 * not be discarded, and the scissors list is the union of placed + library
 * snippets.
 */
class SnippetOpsTest {

    private fun custom(label: String, send: String = "$label\n") = ToolbarItem.Custom(label, send)

    private fun layoutOf(row1: List<ToolbarItem>, row2: List<ToolbarItem>) =
        ToolbarLayout(listOf(row1, row2))

    @Test
    fun `library json round-trips`() {
        val items = listOf(custom("deploy", "make deploy\n"), custom("esc", ""))
        val back = SnippetOps.libraryFromJson(SnippetOps.libraryToJson(items))
        assertEquals(items, back)
    }

    @Test
    fun `blank or malformed library json yields empty`() {
        assertTrue(SnippetOps.libraryFromJson("").isEmpty())
        assertTrue(SnippetOps.libraryFromJson("not json").isEmpty())
        // entries missing label/send are skipped
        assertTrue(SnippetOps.libraryFromJson("""[{"label":"x"}]""").isEmpty())
    }

    @Test
    fun `allSnippets is the union of placed and library, deduped`() {
        val onBar = custom("a")
        val inLib = custom("b")
        val layout = layoutOf(listOf(ToolbarItem.BuiltIn(ToolbarKey.ESC_KEY), onBar), listOf(inLib))
        // inLib also duplicated into the library to prove dedup
        val all = SnippetOps.allSnippets(layout, listOf(inLib, custom("c")))
        assertEquals(listOf("a", "b", "c"), all.map { it.label })
    }

    @Test
    fun `addToLibrary appends new and is a no-op for an existing snippet`() {
        val a = custom("a")
        val layout = layoutOf(listOf(a), emptyList())
        // already placed on the bar -> not duplicated into library
        assertTrue(SnippetOps.addToLibrary(layout, emptyList(), a).isEmpty())
        // brand-new -> added
        val b = custom("b")
        assertEquals(listOf(b), SnippetOps.addToLibrary(layout, emptyList(), b))
    }

    @Test
    fun `delete removes from both rows and library`() {
        val a = custom("a")
        val b = custom("b")
        val layout = layoutOf(listOf(a), emptyList())
        val (newLayout, newLib) = SnippetOps.delete(layout, listOf(b), a)
        assertFalse(newLayout.rows.flatten().contains(a))
        assertEquals(listOf(b), newLib) // unrelated library entry untouched

        val (l2, lib2) = SnippetOps.delete(layout, listOf(b), b)
        assertTrue(lib2.isEmpty())
        assertTrue(l2.rows[0].contains(a)) // placed snippet untouched
    }

    @Test
    fun `place to OFF moves a placed snippet into the library (the 244 fix)`() {
        val a = custom("a")
        val layout = layoutOf(listOf(ToolbarItem.BuiltIn(ToolbarKey.ESC_KEY), a), emptyList())
        val (newLayout, newLib) = SnippetOps.place(layout, emptyList(), a, row = null)
        assertFalse(newLayout.rows.flatten().contains(a)) // no longer a button
        assertEquals(listOf(a), newLib) // kept in the library
    }

    @Test
    fun `place onto a row promotes a library snippet to a button and removes it from the library`() {
        val a = custom("a")
        val layout = layoutOf(listOf(ToolbarItem.BuiltIn(ToolbarKey.ESC_KEY)), listOf(ToolbarItem.BuiltIn(ToolbarKey.SHIFT)))
        val (newLayout, newLib) = SnippetOps.place(layout, listOf(a), a, row = 1)
        assertTrue(newLayout.rows[1].contains(a))
        assertTrue(newLib.isEmpty())
    }

    @Test
    fun `place moving from row1 to row2 leaves a single copy`() {
        val a = custom("a")
        val layout = layoutOf(listOf(a), emptyList())
        val (newLayout, _) = SnippetOps.place(layout, emptyList(), a, row = 1)
        assertFalse(newLayout.rows[0].contains(a))
        assertTrue(newLayout.rows[1].contains(a))
        assertEquals(1, newLayout.rows.flatten().count { it == a })
    }
}
