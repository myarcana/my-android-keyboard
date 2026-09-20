package com.offlinekeyboard.ime.layout

import com.offlinekeyboard.ime.tap.SpatialModel
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

    /**
     * The strip and the letters must between them claim every pixel of the bar exactly once.
     *
     * This is the property the old dead band gave away for free by construction, and the reason
     * it is worth asserting now: the boundary is computed from the spatial model rather than set
     * as a fraction, so a refit or a change of strip height moves it. A gap would swallow presses
     * silently; an overlap would make a tap mean two things.
     */
    @Test
    fun `every pixel of the strip belongs to exactly one of the strip and the keys`() {
        val e = geometry.keyRects.first { it.key.id == "e" }
        var y = 0f
        while (y < geometry.stripHeight) {
            val letter = geometry.isLetterReach(e.centerX, y)
            val key = idAt(e.centerX, y)
            assertEquals(
                "at y=$y, isLetterReach says $letter but keyForPress returned $key",
                letter,
                key != null,
            )
            y += 1f
        }
    }

    /**
     * The emoji keep the whole bar. Under the shipped sigma the entire strip sits more than five
     * standard deviations above where a thumb aiming at the top row lands, so none of it is
     * plausibly a letter press -- which is exactly the real estate the dead band used to take.
     */
    @Test
    fun `the whole strip is available to the emoji`() {
        val e = geometry.keyRects.first { it.key.id == "e" }
        var y = 0f
        while (y < geometry.stripHeight) {
            assertNull(
                "a press at y=$y is inside the strip and must not reach a key",
                idAt(e.centerX, y),
            )
            y += 1f
        }
    }

    /**
     * The press that turned "book" into a book emoji, replayed. It was 26px above `e` and dead
     * centre of `e`'s column, and it landed on the emoji strip.
     */
    @Test
    fun `the recorded press that ate a word now types the letter it aimed at`() {
        assertEquals("e", idAt(254.1f, 124.3f))
    }

    /**
     * The same press, under the *honest* spatial fit.
     *
     * Worth its own test because the refit documented on
     * [com.offlinekeyboard.ime.tap.SpatialModel.MEASURED_SIGMA_X] nearly doubles the scatter and
     * halves the offset, which is what brings the boundary close to the bar. It is the binding
     * case for [com.offlinekeyboard.ime.tap.SpatialModel.LETTER_REACH_SIGMAS]: under this fit the
     * strip's bottom edge sits at 2.675 sigma, so the 2.5 threshold clears it by 0.175 sigma --
     * about 4px -- while a threshold of 3 would take a slice of the bar back off the emoji.
     *
     * **That margin is thin, and deliberately pinned here.** If the spatial model is refitted
     * again and this test goes red, the threshold and the strip height are what to look at; the
     * failure is the design telling the truth, not a flaky assertion to relax.
     *
     * Note the recorded press itself is not the delicate part: at y=124.3 it is inside the top
     * row's own rectangle, so `keyAt` claims it before any arbitration happens, under either fit.
     * What this pins is the *strip* staying whole next to it.
     *
     * The model is passed explicitly rather than taken from the shipped constants so that this
     * keeps testing the arbitration on a known distribution the day the fit is changed.
     */
    @Test
    fun `the recorded press still types its letter under the honest spatial fit`() {
        val honest = LayoutGeometry(
            IosLayouts.QWERTY_LOWER,
            1080f,
            SpatialModel(offsetY = 0.099f, sigmaY = 0.227f),
        )
        assertEquals("e", honest.keyForPress(254.1f, 124.3f)?.key?.id)
        // ...and the strip is still wholly the emoji's under that fit, which is the constraint
        // that actually fixes the threshold. See SpatialModel.LETTER_REACH_SIGMAS.
        assertNull(honest.keyForPress(254.1f, honest.stripHeight - 1f))
    }

    @Test
    fun `a press well below the last row is not snapped upward`() {
        assertNull(idAt(geometry.widthPx / 2f, geometry.heightPx + 100f))
    }
}
