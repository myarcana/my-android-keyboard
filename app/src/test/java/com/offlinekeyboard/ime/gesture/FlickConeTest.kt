package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import com.offlinekeyboard.ime.layout.Squash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The per-key flick cone: a key with no word below it may be flicked at a lean, and a flick
 * from it does not turn into a word just by going far.
 *
 * The two reported failures, as tests: `(` on `h` typed `in`, `hub`, `iv` or `on`, and a
 * downward swipe on the bottom row glided at all. Against them, the cases that must not move,
 * like `in` from `i` and `ok` from `o`.
 */
class FlickConeTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
    private val config = GestureConfig()

    /** The real lexicon's statistics, the same table the phone builds, or null without it. */
    private val words: WordStarts = GestureReplay.WORDS

    private fun key(id: String, g: LayoutGeometry = geometry): KeyRect =
        g.keyRects.first { it.key.id == id }

    private fun cone(id: String, w: WordStarts = words, g: LayoutGeometry = geometry) =
        FlickCone.of(key(id, g), g, w, config)

    /**
     * Runs a straight stroke from the centre of [id], [degrees] off vertical (positive is to the
     * right), [keyHeights] long, in ten even samples over 100ms, and returns what it typed.
     */
    private fun stroke(
        id: String,
        degrees: Float,
        keyHeights: Float,
        w: WordStarts = words,
        c: GestureConfig = config,
    ): String {
        val k = key(id)
        val f = TouchFsm(geometry, c, FlickPrior(), FlickPrior.Context.UNKNOWN, w)
        val length = keyHeights * geometry.keyHeight
        val rad = Math.toRadians(degrees.toDouble())
        fun at(s: Int) = Pair(
            k.centerX + (sin(rad) * length * s / 10).toFloat(),
            k.centerY + (cos(rad) * length * s / 10).toFloat(),
        )
        f.onDown(k.centerX, k.centerY, 0)
        for (s in 1..10) {
            val (x, y) = at(s)
            f.onMove(x, y, s * 10L)
        }
        val (x, y) = at(10)
        val out = f.onUp(x, y, 110) + f.onGlideResumeTimeout()
        return when {
            out.any { it is GestureOutput.GlideCompleted } -> "GLIDE"
            out.any { it is GestureOutput.CommitSecondary } -> "FLICK"
            out.any { it is GestureOutput.CommitPrimary } -> "TAP"
            else -> "NONE"
        }
    }

    // --- the reported cases ------------------------------------------------------------------

    /** `(` on `h`, flicked at the lean a thumb actually uses, short and long. */
    @Test
    fun `a leaning flick on h types the bracket, not a word`() {
        listOf(-30f, -20f, 20f, 30f).forEach { lean ->
            listOf(0.6f, 1.2f, 2.5f).forEach { length ->
                assertEquals("h at $lean deg, $length keys", "FLICK", stroke("h", lean, length))
            }
        }
    }

    /**
     * The other half of the complaint: a vigorous, straight flick down from `h` ran past the
     * flick-to-glide distance and became a word. Nothing below `h` starts a word, so it stays
     * a flick however far it goes, for as long as it stays in the cone.
     */
    @Test
    fun `a long straight flick on h does not turn into a glide`() {
        val far = config.flickToGlideRatio * geometry.keyUnit / geometry.keyHeight * 1.5f
        assertEquals("FLICK", stroke("h", 0f, far))
    }

    @Test
    fun `nothing on the bottom row glides downward`() {
        listOf("z", "x", "c", "v", "b", "n", "m").forEach { id ->
            listOf(-40f, 0f, 40f).forEach { lean ->
                assertEquals("$id at $lean deg", "FLICK", stroke(id, lean, 3f))
            }
        }
    }

    /**
     * The bottom row does not need the lexicon to know nothing is below it. Before the lexicon
     * loads, and in any build without one, the geometry alone opens it.
     */
    @Test
    fun `the bottom row opens even with no word statistics`() {
        listOf("z", "m").forEach { id ->
            val c = cone(id, WordStarts.UNKNOWN)
            assertEquals(config.flickConeMaxDegrees, c.leftDegrees, 0.01f)
            assertEquals(config.flickConeMaxDegrees, c.rightDegrees, 0.01f)
        }
        // And everything with a letter under it stays as strict as before until the words say.
        listOf("h", "i", "o", "d").forEach { id ->
            val c = cone(id, WordStarts.UNKNOWN)
            assertEquals("$id left", 0f, c.leftDegrees, 0.01f)
            assertEquals("$id right", 0f, c.rightDegrees, 0.01f)
        }
    }

    // --- what must not move ------------------------------------------------------------------

    /**
     * `in` is 55% of i-words and `n` is almost straight below. The cone must stay exactly the
     * configured one, or gliding `in` becomes an `8`.
     */
    @Test
    fun `keys with a word straight below them are exactly as strict as before`() {
        if (words === WordStarts.UNKNOWN) return
        listOf("i", "o", "e", "k").forEach { id ->
            val c = cone(id)
            assertEquals("$id left", 0f, c.leftDegrees, 0.01f)
            assertEquals("$id right", 0f, c.rightDegrees, 0.01f)
        }
    }

    /** `ok` leaves `o` about 25 degrees left of vertical. It is still a glide. */
    @Test
    fun `gliding ok is still a glide`() {
        assertEquals("GLIDE", stroke("o", -25f, 1.6f))
    }

    /**
     * A side is only opened where no word goes. `th` is 57% of t-words and heads down-right, so
     * `t` stays strict on the right and opens on the left.
     */
    @Test
    fun `a key opens only on the side no word goes`() {
        if (words === WordStarts.UNKNOWN) return
        val t = cone("t")
        assertTrue("t left should open", t.leftDegrees >= 30f)
        assertTrue("t right must stay near h", t.rightDegrees < 30f)
        val u = cone("u")
        assertTrue("u left opens", u.leftDegrees >= 30f)
        assertEquals("u right is where un goes", 0f, u.rightDegrees, 0.01f)
    }

    /** A glide that turns sideways out of the cone is still a word, even from `h`. */
    @Test
    fun `a stroke that leaves the cone still becomes a glide`() {
        assertEquals("GLIDE", stroke("h", 70f, 2f))
        assertEquals("GLIDE", stroke("m", -80f, 2f))
    }

    /**
     * A tap whose thumb rolls a few pixels down at a lean is still a tap. The bank has one on
     * `h`: 14px down and 4px across. The wide cone asks for more travel than the narrow one.
     */
    @Test
    fun `a thumb rolling off a tap at a lean is still a tap`() {
        val k = key("h")
        val f = TouchFsm(geometry, config, FlickPrior(), FlickPrior.Context.UNKNOWN, words)
        // 14px of 120 at 1080 wide, scaled to this geometry.
        val dy = geometry.keyHeight * 14f / 120f
        val dx = geometry.keyHeight * 4f / 120f
        f.onDown(k.centerX, k.centerY, 0)
        f.onMove(k.centerX + dx, k.centerY + dy, 30)
        val out = f.onUp(k.centerX + dx, k.centerY + dy, 60)
        assertTrue(out.any { it is GestureOutput.CommitPrimary && it.text == "h" })
    }

    /** Setting the limit to zero gives back the old machine exactly. */
    @Test
    fun `a zero limit turns the cone off`() {
        val off = config.copy(flickConeMaxDegrees = 0f)
        assertEquals("TAP", stroke("h", 30f, 0.6f, c = off))
        assertEquals("GLIDE", stroke("h", 0f, 3f, c = off))
        listOf("h", "m", "z").forEach { id ->
            val c = FlickCone.of(key(id), geometry, words, off)
            assertEquals(0f, c.leftDegrees, 0f)
            assertEquals(0f, c.rightDegrees, 0f)
        }
    }

    /**
     * The angles are measured on the geometry, so a squashed board, which pulls the keys closer
     * sideways, narrows the cones by as much as it moves the letters. `u`'s left side on a
     * squashed board is still bounded by `h`, just at a steeper angle.
     */
    @Test
    fun `a squashed board narrows the cones with the keys`() {
        val squashed = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH, squash = Squash.RIGHT)
        val full = cone("t", WordStarts.UNKNOWN)
        val narrow = cone("t", WordStarts.UNKNOWN, squashed)
        assertTrue(narrow.leftDegrees <= full.leftDegrees)
        assertTrue(narrow.rightDegrees <= full.rightDegrees)
    }

    /** The prior still applies: a lean mid-word asks for more travel, as the narrow cone does. */
    @Test
    fun `the mid-word prior still raises the travel the wide cone asks for`() {
        val k = key("h")
        val dy = config.flickConeMinTravelRatio * geometry.keyHeight * 1.1f
        val dx = dy * 0.5f // about 27 degrees: outside the narrow cone, inside the wide one.
        fun flicks(context: FlickPrior.Context): Boolean {
            val f = TouchFsm(geometry, config, FlickPrior(), context, words)
            f.onDown(k.centerX, k.centerY, 0)
            f.onMove(k.centerX + dx, k.centerY + dy, 30)
            return f.onUp(k.centerX + dx, k.centerY + dy, 60)
                .any { it is GestureOutput.CommitSecondary }
        }
        assertTrue("at a boundary", flicks(FlickPrior.Context(before = ' ')))
        assertTrue("mid-word", !flicks(FlickPrior.Context(before = 'l', composing = true)))
    }
}
