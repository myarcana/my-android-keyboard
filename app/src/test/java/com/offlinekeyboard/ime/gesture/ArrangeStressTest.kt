package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.PopupEntry
import com.offlinekeyboard.ime.layout.PopupGrid
import com.offlinekeyboard.ime.layout.RowRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PopupGrid.arrangeForThumb] over every shape it can be handed.
 *
 * The real keys exercise a handful of combinations; this sweeps the space, because the
 * arrangement is index arithmetic over two independently bounded sides and the interesting cases
 * are the degenerate ones -- no room on a side, more entries than cells, a primary at either end
 * of the list. An off-by-one there throws out of the replay harness rather than failing a
 * comparison, so it has to be caught by construction.
 */
class ArrangeStressTest {

    @Test
    fun `every shape arranges without losing or inventing an entry`() {
        for (n in 1..12) {
            val entries = (0 until n).map { PopupEntry.Accent("e$it") }
            for (primary in 0 until n) {
                for (perRow in 1..14) {
                    for (leftOf in 0 until perRow) {
                        val room = RowRoom(perRow = perRow, leftOfPrimary = leftOf)
                        val where = "n=$n primary=$primary perRow=$perRow leftOf=$leftOf"

                        val out = PopupGrid.arrangeForThumb(entries, primary, room)

                        // The primary must survive as itself, and be findable.
                        assertTrue("$where: primary out of range",
                            out.primary in out.entries.indices)
                        assertEquals("$where: wrong entry is primary",
                            entries[primary], out.entries[out.primary])

                        // Every entry appears exactly once; nothing is duplicated or dropped.
                        val real = out.entries.filter { it != PopupEntry.Blank }
                        assertEquals("$where: entries lost or duplicated",
                            entries.sortedBy { it.text }, real.sortedBy { (it as PopupEntry.Accent).text })

                        // The row width must divide the cells, and never exceed the room.
                        val cols = out.columns ?: real.size
                        assertTrue("$where: width $cols exceeds room $perRow", cols <= perRow)
                        assertEquals("$where: cells do not fill whole rows",
                            0, out.entries.size % cols)
                    }
                }
            }
        }
    }
}
