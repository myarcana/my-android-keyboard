package com.offlinekeyboard.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * That a press landing in the gap between keys still reaches a key.
 *
 * Written from a recorded typing session rather than from theory. In one passage typed at speed,
 * 205 finger-downs reached the view and 8 of them produced nothing at all: every one had landed
 * in a gap, six of the eight in the ~31px channel between the top row and the home row. The
 * person typing experiences that as the keyboard refusing to register keystrokes, which is
 * exactly what it is doing.
 *
 * The gaps are unavoidable -- they are how the keyboard is drawn -- so the hit test has to
 * account for them rather than the thumb.
 */
class KeyForPressTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, 1080f)

    private fun idAt(x: Float, y: Float): String? = geometry.keyForPress(x, y)?.key?.id

    private val topRow = geometry.keyRects.first { it.key.id == "q" }
    private val homeRow = geometry.keyRects.first { it.key.id == "a" }

    @Test
    fun `a press inside a key still lands on that key`() {
        assertEquals("q", idAt(topRow.centerX, topRow.centerY))
        assertEquals("a", idAt(homeRow.centerX, homeRow.centerY))
    }

    @Test
    fun `the channel between two rows is not a dead zone`() {
        val gapTop = topRow.bottom
        val gapBottom = homeRow.top
        // Sampled across the whole gap, at the horizontal centre of a top-row key.
        var y = gapTop
        while (y < gapBottom) {
            assertEquals(
                "no key for a press at y=$y, in the gap between rows",
                true,
                idAt(topRow.centerX, y) != null,
            )
            y += 1f
        }
    }

    @Test
    fun `a press in the row gap goes to the row it is nearer`() {
        val justBelowTop = topRow.bottom + 1f
        val justAboveHome = homeRow.top - 1f
        assertEquals("q", idAt(topRow.centerX, justBelowTop))
        assertEquals("a", idAt(homeRow.centerX, justAboveHome))
    }

    @Test
    fun `the gutter between two keys in a row goes to one of them`() {
        val f = geometry.keyRects.first { it.key.id == "f" }
        val g = geometry.keyRects.first { it.key.id == "g" }
        val gutter = (f.right + g.left) / 2f
        assertEquals(true, idAt(gutter, f.centerY) in setOf("f", "g"))
        // Biased toward f, it should be f.
        assertEquals("f", idAt(f.right + 1f, f.centerY))
    }

    @Test
    fun `the suggestion strip above the keys is left alone`() {
        assertNull(idAt(geometry.widthPx / 2f, geometry.stripHeight / 2f))
        assertNull(idAt(geometry.widthPx / 2f, 0f))
    }

    @Test
    fun `a press well below the last row is not snapped upward`() {
        assertNull(idAt(geometry.widthPx / 2f, geometry.heightPx + 100f))
    }
}
