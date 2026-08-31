package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The finger-lift leniency: a glide interrupted by a momentary lift is still one glide.
 *
 * This is the failure the feature exists to prevent, and it is worth naming precisely, because
 * it is not "the word comes out wrong". It is that a word the user never meant to finish gets
 * *typed* -- committed into the field, while their finger is still travelling toward the rest of
 * it -- and then the remainder of the gesture types a second word beside it. One skip on the
 * glass produces two wrong words and a correction.
 *
 * The opposite mistake is real too, and everything here is as much about not making it: two
 * words deliberately glided in quick succession must not be run together into one.
 */
class GlideResumeTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
    private val config = GestureConfig()

    private fun fsm() = TouchFsm(geometry, config)
    private fun key(id: String): KeyRect = geometry.keyRects.first { it.key.id == id }

    /** Glides from one key to another, leaving the finger down at the end. */
    private fun TouchFsm.glide(from: String, to: String, at: Long): Long {
        val a = key(from)
        val b = key(to)
        onDown(a.centerX, a.centerY, at)
        var t = at
        (1..6).forEach { step ->
            t = at + step * 10L
            val f = step / 6f
            onMove(a.centerX + (b.centerX - a.centerX) * f, a.centerY + (b.centerY - a.centerY) * f, t)
        }
        return t
    }

    // --- the mistake this exists to prevent ---------------------------------------------------

    @Test
    fun `a momentary lift does not end the glide`() {
        val f = fsm()
        val t = f.glide("q", "e", 0)
        val e = key("e")

        val out = f.onUp(e.centerX, e.centerY, t)
        assertTrue("the lift should suspend, not complete", out.contains(GestureOutput.GlideSuspended))
        assertTrue(f.isSuspended)
        assertTrue(
            "nothing may be typed while the finger might still come back",
            out.none { it is GestureOutput.GlideCompleted },
        )

        // Back down 40ms later, a third of a key away: a skip, not a decision.
        val resumeAt = t + 40
        assertTrue(f.canResume(e.centerX + geometry.keyUnit * 0.3f, e.centerY, resumeAt))
        f.onResume(e.centerX + geometry.keyUnit * 0.3f, e.centerY, resumeAt)
        assertEquals(GestureState.GLIDE, f.state)
    }

    @Test
    fun `a resumed glide is one gesture, with the gap recorded`() {
        val f = fsm()
        val t = f.glide("q", "e", 0)
        val e = key("e")
        val r = key("r")
        f.onUp(e.centerX, e.centerY, t)
        f.onResume(e.centerX + 8f, e.centerY, t + 40)
        f.onMove(r.centerX, r.centerY, t + 60)

        val outputs = f.onUp(r.centerX, r.centerY, t + 70) +
            f.onGlideResumeTimeout()
        val completed = outputs.filterIsInstance<GestureOutput.GlideCompleted>().single()
        assertEquals("one lift, so one stroke boundary", 1, completed.strokeStarts.size)

        val trace = outputs.filterIsInstance<GestureOutput.GestureCaptured>().single().trace
        assertEquals(GestureVerdict.GLIDE, trace.verdict)
        assertEquals(completed.strokeStarts, trace.strokeStarts)

        val record = GestureRecord(id = "x", at = 0, typed = "qer", trace = trace)
        val gap = record.gaps.single()
        assertEquals(40L, gap.ms)
        assertTrue("the gap distance should be the pixels skipped", gap.px in 1f..40f)
    }

    // --- the opposite mistake ------------------------------------------------------------------

    @Test
    fun `a slow return is a new gesture, not a continuation`() {
        val f = fsm()
        val t = f.glide("q", "e", 0)
        val e = key("e")
        f.onUp(e.centerX, e.centerY, t)
        assertFalse(f.canResume(e.centerX, e.centerY, t + config.glideResumeMs + 1))
    }

    @Test
    fun `a return far from the lift is a new gesture`() {
        val f = fsm()
        val t = f.glide("q", "e", 0)
        val e = key("e")
        val far = geometry.keyUnit * config.glideResumeRadiusRatio + 1f
        assertFalse(f.canResume(e.centerX + far, e.centerY, t + 20))
    }

    /**
     * Reaching for backspace or the space bar is a finished word however fast the reach was.
     * Reading it as a continuation would swallow the very keypress meant to correct the word.
     */
    @Test
    fun `coming back down on a non-letter key ends the word`() {
        listOf("space", "backspace", "shift").forEach { id ->
            val f = fsm()
            val t = f.glide("q", "e", 0)
            val e = key("e")
            f.onUp(e.centerX, e.centerY, t)
            val target = key(id)
            // Position the test where the key is, but keep the timing well inside the window so
            // that only the key's type can be what rejects it.
            assertFalse(
                "a press on $id should end the glide",
                f.canResume(target.centerX, target.centerY, t + 10),
            )
        }
    }

    // --- closing the window --------------------------------------------------------------------

    @Test
    fun `the window closing types the word`() {
        val f = fsm()
        val t = f.glide("q", "e", 0)
        val e = key("e")
        f.onUp(e.centerX, e.centerY, t)
        val out = f.onGlideResumeTimeout()
        assertEquals(1, out.filterIsInstance<GestureOutput.GlideCompleted>().size)
        assertEquals(GestureState.IDLE, f.state)
        assertTrue("a closed window leaves nothing suspended", !f.isSuspended)
    }

    /** The deadline is what the host schedules its timeout against; without it nothing fires. */
    @Test
    fun `a suspended glide publishes when it must be closed`() {
        val f = fsm()
        val t = f.glide("q", "e", 0)
        val e = key("e")
        assertEquals(null, f.glideResumeDeadline)
        f.onUp(e.centerX, e.centerY, t)
        assertEquals(t + config.glideResumeMs, f.glideResumeDeadline)
    }

    /**
     * A gesture interrupted by the system is dropped rather than typed. A cancel is not evidence
     * that anybody finished a word.
     */
    @Test
    fun `a cancelled suspension types nothing`() {
        val f = fsm()
        val t = f.glide("q", "e", 0)
        val e = key("e")
        f.onUp(e.centerX, e.centerY, t)
        val out = f.onCancel()
        assertTrue(out.none { it is GestureOutput.GlideCompleted })
        assertEquals(GestureState.IDLE, f.state)
    }
}
