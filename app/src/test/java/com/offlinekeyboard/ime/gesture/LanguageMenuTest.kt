package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.Key
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.Layout
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import com.offlinekeyboard.ime.layout.PopupEntry
import com.offlinekeyboard.ime.layout.PopupGrid
import com.offlinekeyboard.ime.layout.PopupShape
import com.offlinekeyboard.ime.layout.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The globe key's iOS-style language menu: hold, slide up or down, release on a language.
 *
 * Built on the same [TouchFsm] popup machinery as the accent popups, so these tests are mostly
 * about the two things that genuinely differ -- the menu is a single column laid out upward from
 * the key, and its entries name a destination rather than text to type.
 *
 * [com.offlinekeyboard.ime.LanguageMenu] itself is not exercised here: reading the enabled IMEs
 * needs a real [android.view.inputmethod.InputMethodManager], which does not exist on the JVM.
 * What is testable without a device is everything downstream of that list, which is where the
 * interaction lives.
 */
class LanguageMenuTest {

    private val config = GestureConfig()

    /** Three languages, the middle one in use -- the shape the shipped `method.xml` produces. */
    private val languages = listOf(
        PopupEntry.Language("other/.Ime", "Gboard"),
        PopupEntry.Language("self/.Ime|1", "English", current = true),
        PopupEntry.Language("self/.Ime|2", "繁體拼音"),
    )

    /**
     * The shipped bottom row with languages attached to its globe key.
     *
     * Built from [IosLayouts.QWERTY_LOWER] rather than by hand so the globe sits where it really
     * sits -- near the left edge of the bottom row, which is the position that makes the popup's
     * edge clamping matter.
     */
    private fun layoutWithLanguages(entries: List<PopupEntry.Language> = languages): Layout =
        Layout(
            id = IosLayouts.QWERTY_LOWER.id,
            rows = IosLayouts.QWERTY_LOWER.rows.map { row ->
                Row(
                    row.keys.map { key ->
                        if (key.type == KeyType.GLOBE) key.copy(languages = entries) else key
                    },
                )
            },
        )

    private fun geometry(entries: List<PopupEntry.Language> = languages) =
        LayoutGeometry(layoutWithLanguages(entries), Metrics.REFERENCE_WIDTH)

    private fun globe(g: LayoutGeometry): KeyRect = g.keyRects.first { it.key.type == KeyType.GLOBE }

    private inline fun <reified T> List<GestureOutput>.only(): T = filterIsInstance<T>().single()

    private inline fun <reified T> List<GestureOutput>.has(): Boolean = any { it is T }

    // --- opening ---------------------------------------------------------------------------

    @Test
    fun `long press on the globe key opens the language menu`() {
        val g = geometry()
        val key = globe(g)
        val f = TouchFsm(g, config)
        f.onDown(key.centerX, key.centerY, 0)
        val out = f.onLongPressTimeout(config.longPressMs)

        assertEquals(GestureState.ACCENTS, f.state)
        assertEquals(
            languages.map { it.label },
            out.only<GestureOutput.ShowAccents>().entries
                .filterIsInstance<PopupEntry.Language>()
                .map { it.label },
        )
    }

    /** A globe key with no languages attached is the one we ship; holding it must do nothing. */
    @Test
    fun `long press on a globe key with no languages does nothing`() {
        val g = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
        val key = globe(g)
        val f = TouchFsm(g, config)
        f.onDown(key.centerX, key.centerY, 0)
        assertTrue(f.onLongPressTimeout(config.longPressMs).isEmpty())
        assertEquals(GestureState.PRESSED, f.state)
    }

    /** Tapping is untouched: it still cycles, which is what the service does with SpecialKey. */
    @Test
    fun `tapping the globe key still asks the service to cycle`() {
        val g = geometry()
        val key = globe(g)
        val f = TouchFsm(g, config)
        f.onDown(key.centerX, key.centerY, 0)
        val out = f.onUp(key.centerX, key.centerY, 80)
        assertEquals(KeyType.GLOBE, out.only<GestureOutput.SpecialKey>().type)
        assertTrue(!out.has<GestureOutput.CommitLanguage>())
    }

    // --- shape -----------------------------------------------------------------------------

    @Test
    fun `the language menu is a single column stacked above the key`() {
        val g = geometry()
        val grid = PopupGrid.of(globe(g), g)

        assertEquals(PopupShape.COLUMN, grid.shape)
        assertEquals(1, grid.columns)
        assertEquals(languages.size, grid.rows)
        // Entirely above the key it belongs to, so the hand holding the globe covers nothing.
        assertTrue(grid.top + grid.height <= globe(g).top)
    }

    /** Wide cells: a language name is a word, and a one-key-wide cell cannot show one. */
    @Test
    fun `language cells are wider than a key`() {
        val g = geometry()
        val grid = PopupGrid.of(globe(g), g)
        assertTrue(grid.cellWidth > g.keyUnit * 2f)
    }

    /**
     * The globe sits near the left edge, so a cell three and a half keys wide would hang off it.
     * Clamping is what keeps the whole menu on screen and therefore reachable.
     */
    @Test
    fun `the menu stays inside the keyboard`() {
        val g = geometry()
        val grid = PopupGrid.of(globe(g), g)
        assertTrue(grid.left >= g.margin - 0.01f)
        assertTrue(grid.left + grid.width <= g.widthPx - g.margin + 0.01f)
    }

    /**
     * Many languages must still all fit, because a menu you cannot slide to the end of hides the
     * language you wanted. The rows compress rather than the column overflowing the view.
     */
    @Test
    fun `a long menu compresses its rows rather than running off the top`() {
        val many = (1..8).map { PopupEntry.Language("ime/.X|$it", "Language $it", it == 1) }
        val g = geometry(many)
        val grid = PopupGrid.of(globe(g), g)

        assertEquals(8, grid.rows)
        assertTrue("menu must not start above the view", grid.top >= 0f)
        assertTrue("menu must not cover its own key", grid.top + grid.height <= globe(g).top)
    }

    // --- sliding and releasing ---------------------------------------------------------------

    @Test
    fun `sliding up the menu highlights and commits the language there`() {
        val g = geometry()
        val key = globe(g)
        val f = TouchFsm(g, config)
        f.onDown(key.centerX, key.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)

        // The top row is the far end of the list -- the other keyboard.
        val grid = PopupGrid.of(key, g)
        val target = 0
        val x = grid.cellLeft(target) + grid.cellWidth / 2f
        val y = grid.cellTop(target) + grid.cellHeight / 2f

        val moved = f.onMove(x, y, 600)
        assertEquals(target, moved.only<GestureOutput.AccentHighlighted>().index)

        val out = f.onUp(x, y, 700)
        assertEquals("other/.Ime", out.only<GestureOutput.CommitLanguage>().languageId)
        assertTrue(out.has<GestureOutput.HideAccents>())
    }

    /** Every row must be reachable, which is the whole point of sliding rather than cycling. */
    @Test
    fun `every language in the menu can be selected by sliding to its row`() {
        val g = geometry()
        val key = globe(g)
        val grid = PopupGrid.of(key, g)

        languages.forEachIndexed { index, language ->
            val f = TouchFsm(g, config)
            f.onDown(key.centerX, key.centerY, 0)
            f.onLongPressTimeout(config.longPressMs)
            val x = grid.cellLeft(index) + grid.cellWidth / 2f
            val y = grid.cellTop(index) + grid.cellHeight / 2f
            f.onMove(x, y, 600)
            assertEquals(
                language.id,
                f.onUp(x, y, 700).only<GestureOutput.CommitLanguage>().languageId,
            )
        }
    }

    /**
     * Sliding past the bottom of the menu holds the last row rather than falling off it.
     *
     * This matters more here than on an accent popup: the finger is already at the bottom of the
     * screen on the globe key, so the natural "come back down" undo-gesture overshoots.
     */
    @Test
    fun `sliding below the menu keeps the nearest language`() {
        val g = geometry()
        val key = globe(g)
        val f = TouchFsm(g, config)
        f.onDown(key.centerX, key.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)

        val grid = PopupGrid.of(key, g)
        val x = grid.left + grid.cellWidth / 2f
        f.onMove(x, grid.top + grid.height + g.keyHeight * 3f, 600)
        val out = f.onUp(x, grid.top + grid.height + g.keyHeight * 3f, 700)
        assertEquals(languages.last().id, out.only<GestureOutput.CommitLanguage>().languageId)
    }

    // --- what releasing without sliding does -------------------------------------------------

    /**
     * Releasing straight away must *change* language, not land back on the one in use. A menu
     * that opens on the current entry gives a hold-and-release the one outcome nobody wanted.
     */
    @Test
    fun `releasing without sliding switches away from the current language`() {
        val g = geometry()
        val key = globe(g)
        val f = TouchFsm(g, config)
        f.onDown(key.centerX, key.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)

        val committed = f.onUp(key.centerX, key.centerY, 700)
            .only<GestureOutput.CommitLanguage>().languageId
        assertNotEquals(languages.first { it.current }.id, committed)
    }

    /** With the current language last in the list, "one further down" has to wrap. */
    @Test
    fun `the opening selection wraps when the current language is nearest the thumb`() {
        val entries = listOf(
            PopupEntry.Language("a", "A"),
            PopupEntry.Language("b", "B"),
            PopupEntry.Language("c", "C", current = true),
        )
        val layout = Key("globe", "", languages = entries, type = KeyType.GLOBE).popupLayout
        assertEquals(0, layout.primary)
    }

    /** The opening highlight is the primary, and is announced so the view lights the right row. */
    @Test
    fun `the menu opens on its primary row`() {
        val g = geometry()
        val key = globe(g)
        val f = TouchFsm(g, config)
        f.onDown(key.centerX, key.centerY, 0)
        val out = f.onLongPressTimeout(config.longPressMs)

        val grid = PopupGrid.of(key, g)
        assertEquals(grid.primary, out.only<GestureOutput.AccentHighlighted>().index)
    }

    // --- the guards the accent popup already has must still apply -----------------------------

    @Test
    fun `a finger that has drifted does not open the language menu`() {
        val g = geometry()
        val key = globe(g)
        val f = TouchFsm(g, config)
        f.onDown(key.centerX, key.centerY, 0)
        f.onMove(
            key.centerX - 4f,
            key.centerY + config.longPressSlopRatio * g.keyHeight + 2f,
            config.longPressMs - 1,
        )
        assertTrue(f.onLongPressTimeout(config.longPressMs).isEmpty())
        assertEquals(GestureState.PRESSED, f.state)
    }
}
