package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.Layout
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import com.offlinekeyboard.ime.layout.PopupEntry
import com.offlinekeyboard.ime.layout.PopupGrid
import com.offlinekeyboard.ime.layout.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The language menu must fit above its key however many languages are enabled.
 *
 * A boundary test rather than a behaviour one, and it caught a real bug. The first version of
 * [PopupGrid.column] squeezed rows toward a floor of half a key and then let the column keep
 * growing, so from ten languages the menu extended *below* the globe key -- under the bottom row,
 * which is drawn over it, and off the bottom of the view. Those entries could still be
 * highlighted by [PopupGrid.entryAt] but could never be seen or reached.
 *
 * Ten enabled languages is not hypothetical: a multilingual household reaches it easily, and the
 * failure is invisible to anyone testing with two.
 */
class ColumnStressTest {

    private fun gridFor(count: Int): Pair<PopupGrid, LayoutGeometry> {
        val entries = (1..count).map {
            PopupEntry.Language("ime/.X|$it", "Language $it", current = it == 1)
        }
        val layout = Layout(
            id = IosLayouts.QWERTY_LOWER.id,
            rows = IosLayouts.QWERTY_LOWER.rows.map { row ->
                Row(
                    row.keys.map { key ->
                        if (key.type == KeyType.GLOBE) key.copy(languages = entries) else key
                    },
                )
            },
        )
        val g = LayoutGeometry(layout, Metrics.REFERENCE_WIDTH)
        val key = g.keyRects.first { it.key.type == KeyType.GLOBE }
        return PopupGrid.of(key, g) to g
    }

    @Test
    fun `the menu fits above the key and inside the view for any number of languages`() {
        for (n in 1..30) {
            val (grid, g) = gridFor(n)
            val key = g.keyRects.first { it.key.type == KeyType.GLOBE }

            assertTrue("n=$n starts above the view at ${grid.top}", grid.top >= -0.01f)
            assertTrue(
                "n=$n reaches ${grid.top + grid.height}, past the key at ${key.top}",
                grid.top + grid.height <= key.top + 0.01f,
            )
            assertTrue("n=$n starts left of the margin", grid.left >= g.margin - 0.01f)
            assertTrue(
                "n=$n reaches ${grid.left + grid.width}, past the keyboard's ${g.widthPx}",
                grid.left + grid.width <= g.widthPx - g.margin + 0.01f,
            )
        }
    }

    /** Every language must occupy a cell, whatever shape the menu took to fit them all. */
    @Test
    fun `no language is dropped or duplicated however the menu is arranged`() {
        for (n in 1..30) {
            val (grid, _) = gridFor(n)
            val labels = grid.entries.filterIsInstance<PopupEntry.Language>().map { it.label }
            assertEquals("n=$n", n, labels.size)
            assertEquals("n=$n has a duplicate", labels.toSet().size, labels.size)
        }
    }

    /**
     * The gesture is "slide up and down", so the menu stays one column for as long as it can.
     * Eight is what fits above the bottom row on the reference geometry; the assertion's point is
     * that a realistic number of languages never triggers the sideways fallback.
     */
    @Test
    fun `a realistic number of languages stays a single column`() {
        for (n in 1..8) {
            val (grid, _) = gridFor(n)
            assertEquals("n=$n should not need columns", 1, grid.columns)
            assertEquals("n=$n", n, grid.rows)
        }
    }

    /** Padding goes at the front, so the primary must still land on a real entry. */
    @Test
    fun `the menu never opens on a blank cell`() {
        for (n in 1..30) {
            val (grid, _) = gridFor(n)
            assertTrue(
                "n=$n opens on a blank cell",
                grid.entries[grid.primary] is PopupEntry.Language,
            )
        }
    }
}
