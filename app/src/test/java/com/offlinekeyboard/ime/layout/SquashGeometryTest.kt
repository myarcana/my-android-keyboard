package com.offlinekeyboard.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The one-handed squash: the board narrows toward one edge so the far letters come within reach
 * of the thumb already holding the phone.
 *
 * The reach that was asked for is specific -- squashing right should leave `p` roughly where it
 * is and bring `a` to roughly where `f` normally sits -- and that requirement is what fixes the
 * scale. It is checked here against the geometry rather than against the constant, so a
 * recalibration of [Metrics] that moved the letters fails here instead of silently changing what
 * the gesture reaches.
 */
class SquashGeometryTest {

    private fun geometry(squash: Squash = Squash.NONE) =
        LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH, squash = squash)

    private fun LayoutGeometry.centreOf(id: String): Float =
        keyRects.first { it.key.id == id }.centerX

    private fun LayoutGeometry.rectOf(id: String): KeyRect = keyRects.first { it.key.id == id }

    // --- the reach the gesture was asked for -----------------------------------------------

    /**
     * The headline requirement. Both halves are "roughly", and the tolerance is half a key of
     * the *squashed* board -- near enough that the thumb lands on the right key, which is the
     * only sense in which a reach can be correct.
     */
    @Test
    fun `squashing right brings a to where f was, leaving p where it is`() {
        val full = geometry()
        val squashed = geometry(Squash.RIGHT)
        val tolerance = squashed.keyUnit / 2f

        assertEquals(
            "a must land about where f normally sits",
            full.centreOf("f"),
            squashed.centreOf("a"),
            tolerance,
        )
        assertEquals(
            "p must stay roughly put",
            full.centreOf("p"),
            squashed.centreOf("p"),
            tolerance,
        )
    }

    /** The mirror image: squashing left anchors the left end and draws the right one in. */
    @Test
    fun `squashing left is the mirror of squashing right`() {
        val full = geometry()
        val left = geometry(Squash.LEFT)
        val right = geometry(Squash.RIGHT)
        val width = Metrics.REFERENCE_WIDTH

        // Reflecting the left-squashed board about the centre line reproduces the right-squashed
        // one. A reflection reverses the order of a row, so `q`'s mirrored rectangle is compared
        // against `p`'s -- comparing each key against itself would be asking the board to be
        // symmetric about its own centre, which a QWERTY row is not.
        // The three letter rows only. Reflection pairs the nth key from one end with the nth
        // from the other, which is a statement about *rectangles* and so needs the row to be
        // made of equal ones. That holds for the letters and not for the bottom row, where the
        // mode key is 1.5 units against return's 2.5 -- a row that is not a mirror of itself
        // cannot be expected to reflect onto itself key for key, however correct the squash is.
        val letterRowTops = left.letterKeys.filterNotNull().map { it.top }.distinct()
        letterRowTops.forEach { top ->
            val leftRow = left.keyRects.filter { it.top == top }.sortedBy { it.left }
            val rightRow = right.keyRects.filter { it.top == top }.sortedBy { it.left }
            // One side reversed, not both: reflecting the row is what turns the leftmost key of
            // one board into the rightmost of the other. Reversing both would cancel out and
            // quietly assert the thing this test exists to deny.
            leftRow.reversed().zip(rightRow).forEach { (l, r) ->
                assertEquals("${l.key.id} is not mirrored", width - l.right, r.left, 0.01f)
            }
        }

        // The bottom row cannot mirror key-for-key, but the board it sits on must still be
        // reflected: its outer edges are what say the whole squash moved the same distance.
        val leftBottom = left.keyRects.filter { it.top !in letterRowTops }
        val rightBottom = right.keyRects.filter { it.top !in letterRowTops }
        assertEquals(
            "the bottom row's span is not mirrored",
            width - leftBottom.minOf { it.left },
            rightBottom.maxOf { it.right },
            0.01f,
        )
        assertTrue("the left squash must actually move q", left.centreOf("q") < full.centreOf("q"))
    }

    // --- what the squash must not change ---------------------------------------------------

    /**
     * A horizontal squash, as asked for: the keys narrow and the rows keep their height, so the
     * space bar is still under the thumb that just flicked it and the window does not resize.
     */
    @Test
    fun `the squash is horizontal only`() {
        val full = geometry()
        val squashed = geometry(Squash.RIGHT)

        assertEquals("row height must not change", full.keyHeight, squashed.keyHeight, 0.01f)
        assertEquals("the keyboard must not change height", full.heightPx, squashed.heightPx, 0.01f)
        assertEquals("the strip must not change height", full.stripHeight, squashed.stripHeight, 0.01f)
        full.keyRects.zip(squashed.keyRects).forEach { (a, b) ->
            assertEquals("${a.key.id} changed row", a.top, b.top, 0.01f)
        }
        assertTrue("keys must actually narrow", squashed.keyUnit < full.keyUnit)
    }

    /**
     * The suggestion strip stays full width. It is read at a glance and tapped deliberately, not
     * reached for blindly, and narrowing it would cost the Chinese candidates room they need.
     */
    @Test
    fun `the suggestion strip keeps its full width`() {
        assertEquals(geometry().stripMargin, geometry(Squash.RIGHT).stripMargin, 0.01f)
    }

    /** Nothing may spill outside the view, and the rows must stay in order and apart. */
    @Test
    fun `a squashed board stays on screen and its keys never overlap`() {
        for (squash in Squash.entries) {
            val g = geometry(squash)
            g.keyRects.forEach { rect ->
                assertTrue("${rect.key.id} runs off the left in $squash", rect.left >= -0.01f)
                assertTrue(
                    "${rect.key.id} runs off the right in $squash",
                    rect.right <= Metrics.REFERENCE_WIDTH + 0.01f,
                )
            }
            g.keyRects.groupBy { it.top }.forEach { (_, row) ->
                row.sortedBy { it.left }.zipWithNext { a, b ->
                    assertTrue("${a.key.id} overlaps ${b.key.id} in $squash", a.right <= b.left + 0.001f)
                }
            }
        }
    }

    @Test
    fun `every key is still hit-testable at its own centre when squashed`() {
        val g = geometry(Squash.RIGHT)
        g.keyRects.forEach { rect ->
            assertEquals(rect.key.id, g.keyAt(rect.centerX, rect.centerY)?.key?.id)
        }
    }

    // --- the space the squash frees --------------------------------------------------------

    /**
     * The cleared band is not a near miss. It is where the hand holding the phone rests, so a
     * touch there must hit nothing rather than snapping to the edge key -- which, on a
     * right-squashed board, is `q`, `a` and shift.
     */
    @Test
    fun `a press in the freed band hits nothing`() {
        val g = geometry(Squash.RIGHT)
        val row = g.rectOf("a")
        assertNull(g.keyForPress(g.contentLeft / 2f, row.centerY))
    }

    /** The full-width board has no such band, and must keep snapping presses in its gaps. */
    @Test
    fun `the full-width board still snaps presses that land in a gap`() {
        val g = geometry()
        val q = g.rectOf("q")
        val a = g.rectOf("a")
        val inRowGap = (q.bottom + a.top) / 2f
        assertTrue("a press in the row gap must still find a key", g.keyForPress(q.centerX, inRowGap) != null)
    }

    // --- what downstream consumers are told ------------------------------------------------

    /**
     * The glide decoder is handed the same unit square whichever state the board is in. It
     * reasons in a normalised grid, so a squashed board must look to it like a narrower screen,
     * not like a keyboard whose letters have all slid to one side.
     */
    @Test
    fun `the normalised grid follows the keys rather than the view`() {
        for (squash in Squash.entries) {
            val g = geometry(squash)
            assertEquals(
                "q sits at the same normalised place in $squash",
                geometry().normalisedX(geometry().centreOf("q")),
                g.normalisedX(g.centreOf("q")),
                0.001f,
            )
            assertEquals(
                "p sits at the same normalised place in $squash",
                geometry().normalisedX(geometry().centreOf("p")),
                g.normalisedX(g.centreOf("p")),
                0.001f,
            )
        }
    }

    /**
     * The squash threshold must mean the same physical distance in every state, or the board
     * gets easier to flick the smaller it is -- and a thumb crossing a narrow space bar would
     * unsquash it by accident.
     */
    @Test
    fun `the unsquashed key unit is the same whatever the state`() {
        val full = geometry().unsquashedKeyUnit
        for (squash in Squash.entries) {
            assertEquals(full, geometry(squash).unsquashedKeyUnit, 0.01f)
        }
        assertEquals(full, geometry().keyUnit, 0.01f)
    }

    // --- the scale itself ------------------------------------------------------------------

    /**
     * [Squash.SQUASHED_SCALE] is not a taste. It is the solution of "p stays, a reaches f",
     * recomputed here from the geometry so the constant cannot drift away from the requirement
     * it was derived from.
     */
    @Test
    fun `the scale is the one the reach requires`() {
        val full = geometry()
        val a = full.centreOf("a")
        val f = full.centreOf("f")
        val p = full.centreOf("p")
        val required = (f - p) / (a - p)
        assertTrue(
            "scale is ${Squash.SQUASHED_SCALE}, the reach needs $required",
            abs(Squash.SQUASHED_SCALE - required) < 0.01f,
        )
    }

    @Test
    fun `the full-width board is exactly the unsquashed one`() {
        val plain = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
        val none = geometry(Squash.NONE)
        assertEquals(1f, Squash.NONE.scale, 0.0001f)
        plain.keyRects.zip(none.keyRects).forEach { (a, b) ->
            assertEquals(a.left, b.left, 0.0001f)
            assertEquals(a.right, b.right, 0.0001f)
        }
    }
}
