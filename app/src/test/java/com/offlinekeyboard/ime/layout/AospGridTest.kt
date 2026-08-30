package com.offlinekeyboard.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * That our letter keys sit on the grid an Android-trained swipe decoder expects.
 *
 * This is the compatibility surface with every glide decoder in existence, and it is worth
 * stating as a fact rather than leaving as a coincidence, because it is one -- a happy one. The
 * layout's *arrangement* is iOS's, but its *proportions* were measured off Gboard on the device
 * rather than taken from iOS, and Gboard is AOSP's grid. So the letters land where AOSP puts
 * them without anyone having aimed at that.
 *
 * The grid below is AOSP LatinIME's `rows_qwerty.xml`, which partitions the width in tenths:
 * ten keys on the top row, nine on the home row inset by half a key, and seven on the bottom row
 * inset by one and a half keys to leave room for shift and delete. It ignores gaps, because in
 * AOSP the touch regions are contiguous and only the centres are meaningful.
 *
 * Our residual disagreement comes entirely from having visible gaps and a side margin, which
 * shifts each row's keys a fraction of a key toward its own centre. It is largest at the ends of
 * the top row, on `q` and `p`, where it is 0.054 of a key width -- five pixels on the phone, and
 * a twentieth of the width of the key it is talking about.
 *
 * **This is why "our geometry is not Android's" was never a real obstacle to using a decoder
 * trained on Android geometry**, and the note in docs/PLAN.md that said otherwise was wrong. The
 * stronger reason is in that same document: the current generation of these models takes the key
 * centres as a runtime tensor, so a layout it has never seen costs it nothing at all.
 */
class AospGridTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)

    /** AOSP's centres, as a percentage of keyboard width. */
    private val aosp: Map<Char, Float> = buildMap {
        "qwertyuiop".forEachIndexed { i, c -> put(c, 10f * i + 5f) }
        "asdfghjkl".forEachIndexed { i, c -> put(c, 5f + 10f * i + 5f) }
        "zxcvbnm".forEachIndexed { i, c -> put(c, 15f + 10f * i + 5f) }
    }

    /**
     * A sixteenth of a key width. The measured worst case is 0.054 and the bound is just above
     * it -- close enough that a real change to the metrics trips this, loose enough that it is
     * not tripped by a rounding difference.
     */
    private val tolerance = 0.06f

    @Test
    fun `every letter sits on the AOSP grid`() {
        val keyWidthPercent = 100f * geometry.keyUnit / Metrics.REFERENCE_WIDTH
        var worst = 0f
        var worstKey = ' '
        aosp.forEach { (letter, expected) ->
            val rect = geometry.letterKeys[letter - 'a']
                ?: throw AssertionError("the layout has no '$letter' key")
            val ours = 100f * rect.centerX / Metrics.REFERENCE_WIDTH
            val offByKeys = abs(ours - expected) / keyWidthPercent
            if (offByKeys > worst) {
                worst = offByKeys
                worstKey = letter
            }
        }
        println("furthest letter from the AOSP grid: '$worstKey', %.3f of a key width".format(worst))
        assertTrue(
            "'$worstKey' is %.3f of a key width off the AOSP grid".format(worst),
            worst <= tolerance,
        )
    }

    /** Three rows, evenly pitched, is the other half of the grid. */
    @Test
    fun `the three letter rows are evenly pitched`() {
        val rows = "qaz".map { geometry.letterKeys[it - 'a']!!.centerY }
        assertEquals("row pitch must be uniform", rows[1] - rows[0], rows[2] - rows[1], 0.01f)
    }

    // --- the frame a layout-agnostic decoder is given -------------------------------------

    /**
     * The normalised frame is the three letter rows -- not the whole view, and not the whole key
     * area either.
     *
     * A decoder gets the key centres and the finger's path in one [0,1] square and has no way at
     * all to detect a frame it did not expect: the failure mode is not an error, it is quietly
     * worse words. Two things are tempting to include and both are wrong. Our suggestion strip
     * takes a fifth of the view; the bottom row (space, globe, return) takes a quarter of the
     * keys. Neither is somewhere a word gesture can go, and either one stretches the square so
     * every key lands somewhere the decoder does not expect it.
     */
    @Test
    fun `the normalised frame is the letter rows`() {
        val q = geometry.letterKeys['q' - 'a']!!
        val z = geometry.letterKeys['z' - 'a']!!
        assertEquals("the top row's top is 0", 0f, geometry.normalisedY(q.top), 0.001f)
        assertEquals("the bottom row of letters is 1", 1f, geometry.normalisedY(z.bottom), 0.001f)
        assertEquals("the left edge is 0", 0f, geometry.normalisedX(0f), 0.001f)
        assertEquals("the right edge is 1", 1f, geometry.normalisedX(Metrics.REFERENCE_WIDTH), 0.001f)
        assertTrue(
            "the space bar must be below the frame, not inside it",
            geometry.normalisedY(geometry.keyAreaBottom) > 1f,
        )
    }

    /**
     * How far our rows sit from the layout FUTO's fixed English decoder was trained against.
     *
     * Theirs is three contiguous rows, so its centres are exactly 1/6, 1/2 and 5/6. We draw
     * visible gaps between rows, so ours sit slightly further apart. Reported rather than
     * asserted tight, because the right response to a difference here is to measure whether the
     * decoder cares, not to fake the coordinates -- the encoder takes key centres as input
     * precisely so that a layout can be itself.
     */
    @Test
    fun `how far our rows are from the reference layout`() {
        val reference = mapOf('q' to 1f / 6f, 'a' to 0.5f, 'z' to 5f / 6f)
        reference.forEach { (letter, expected) ->
            val ours = geometry.normalisedY(geometry.letterKeys[letter - 'a']!!.centerY)
            println("row '%c': ours %.3f, FUTO's reference %.3f, %+.3f".format(letter, ours, expected, ours - expected))
            assertEquals("row '$letter' is a long way from the reference", expected, ours, 0.05f)
        }
    }

    /** Out of bounds is information, not an error: a finger really can leave the keys. */
    @Test
    fun `the frame is not clamped`() {
        assertTrue(geometry.normalisedY(0f) < 0f)
        assertTrue(geometry.normalisedX(Metrics.REFERENCE_WIDTH * 1.1f) > 1f)
    }

    /**
     * The whole grid in the shape a layout-agnostic encoder consumes it: 26 key centres in the
     * normalised frame. Printed, because the number that matters when integrating one of those
     * models is whether these coordinates look like the layout it was trained on -- and reading
     * them beats trusting that they do.
     */
    @Test
    fun `the key tensor a decoder would be handed`() {
        println("\nnormalised letter centres (x, y) in the key area frame:")
        ('a'..'z').chunked(9).forEach { chunk ->
            println("  " + chunk.joinToString("  ") { c ->
                val rect = geometry.letterKeys[c - 'a']!!
                "%c %.3f,%.3f".format(c, geometry.normalisedX(rect.centerX), geometry.normalisedY(rect.centerY))
            })
        }
        ('a'..'z').forEach { c ->
            val rect = geometry.letterKeys[c - 'a']!!
            val x = geometry.normalisedX(rect.centerX)
            val y = geometry.normalisedY(rect.centerY)
            assertTrue("'$c' x=$x is outside the frame", x in 0f..1f)
            assertTrue("'$c' y=$y is outside the frame", y in 0f..1f)
        }
    }
}
