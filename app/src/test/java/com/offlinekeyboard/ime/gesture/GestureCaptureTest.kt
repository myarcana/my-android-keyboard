package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recording side: that every gesture leaves a trace, that the trace says what the keyboard
 * decided, and that the trace survives a trip through the file format unchanged.
 *
 * A bank whose lines cannot be read back, or whose labels drift, is worse than no bank at all --
 * it is a large file of confident nonsense that would go on to tune the real thing.
 */
class GestureCaptureTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
    private val config = GestureConfig()

    private fun fsm() = TouchFsm(geometry, config)
    private fun key(id: String): KeyRect = geometry.keyRects.first { it.key.id == id }

    private fun List<GestureOutput>.captured(): GestureTrace? =
        filterIsInstance<GestureOutput.GestureCaptured>().singleOrNull()?.trace

    // --- what the state machine emits -------------------------------------------------------

    @Test
    fun `a tap is captured as a tap`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        val trace = f.onUp(q.centerX, q.centerY, 90).captured()
        assertNotNull(trace)
        assertEquals(GestureVerdict.TAP, trace!!.verdict)
        assertEquals("q", trace.startKeyId)
        assertEquals(IosLayouts.QWERTY_LOWER.id, trace.layoutId)
    }

    @Test
    fun `a flick is captured as a flick, with the geometry it was made on`() {
        val i = key("i")
        val f = fsm()
        f.onDown(i.centerX, i.centerY, 0)
        f.onMove(i.centerX, i.centerY + geometry.keyHeight * 0.6f, 40)
        val trace = f.onUp(i.centerX, i.centerY + geometry.keyHeight * 0.6f, 70).captured()
        assertEquals(GestureVerdict.FLICK, trace!!.verdict)
        assertEquals(Metrics.REFERENCE_WIDTH, trace.widthPx, 0.01f)
        assertEquals(geometry.keyUnit, trace.keyUnitPx, 0.01f)
        assertEquals(geometry.keyHeight, trace.keyHeightPx, 0.01f)
        assertEquals(config.flickDistanceRatio, trace.thresholds!!.flickDistanceRatio, 0.001f)
    }

    /**
     * A lifted glide is not a finished one, so the capture comes from the resume window closing
     * rather than from the lift. Anything that recorded at the lift would be recording a
     * fragment: the finger may still be coming back to finish the word.
     */
    @Test
    fun `a glide is captured when its resume window closes`() {
        val q = key("q")
        val w = key("w")
        val e = key("e")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        f.onMove(w.centerX, w.centerY, 30)
        f.onMove(e.centerX, e.centerY, 60)
        assertNull(f.onUp(e.centerX, e.centerY, 90).captured())
        val trace = f.onGlideResumeTimeout().captured()
        assertEquals(GestureVerdict.GLIDE, trace!!.verdict)
    }

    /**
     * The lift is part of the gesture. Without it a tap has no duration at all, and a swipe's
     * last few pixels -- the ones that decide whether it kept going -- are missing.
     */
    @Test
    fun `the lift point is part of the captured path`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        val trace = f.onUp(q.centerX, q.centerY, 120).captured()
        assertEquals(2, trace!!.path.size)
        assertEquals(120L, trace.path.last().t)
    }

    @Test
    fun `a press that landed on no key captures nothing`() {
        val f = fsm()
        f.onDown(-50f, -50f, 0)
        assertNull(f.onUp(-50f, -50f, 40).captured())
    }

    /**
     * Guards the shortcut in [GestureReplay]: it stops feeding samples the moment a gesture
     * reaches GLIDE, which is only sound while nothing can leave that state. If a future
     * transition out of GLIDE is added, this fails and the sweep gets fixed with it.
     */
    @Test
    fun `glide is terminal`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        f.onMove(q.centerX + geometry.keyUnit * 2f, q.centerY, 30)
        assertEquals(GestureState.GLIDE, f.state)
        listOf(0f to 300f, -400f to 0f, 0f to -300f, 500f to 500f).forEach { (dx, dy) ->
            f.onMove(q.centerX + dx, q.centerY + dy, 60)
            assertEquals(GestureState.GLIDE, f.state)
        }
    }

    // --- the file format --------------------------------------------------------------------

    private fun record(typed: String, path: List<PathPoint>) = GestureRecord(
        id = "abc12345",
        at = 1_756_000_000_000L,
        typed = typed,
        sessionId = "sess1234",
        seq = 0,
        trace = GestureTrace(
            startKeyId = "i",
            verdict = GestureVerdict.GLIDE,
            layoutId = IosLayouts.QWERTY_LOWER.id,
            widthPx = 1080f,
            keyUnitPx = 92f,
            keyHeightPx = 120f,
            thresholds = GestureThresholds.of(config),
            path = path,
        ),
    )

    @Test
    fun `a record survives the round trip`() {
        val original = record(
            "I'm",
            listOf(PathPoint(100f, 200f, 0), PathPoint(103.4f, 260.9f, 33), PathPoint(105f, 330f, 71)),
        )
        val decoded = GestureRecordCodec.decode(GestureRecordCodec.encode(original))!!
        // Everything the line carries comes back unchanged. The verdict and the thresholds are
        // deliberately not among them: a classification is a function of the path and the
        // numbers that were live, both of which are still here, and the numbers moved to the
        // session line where they are written once instead of once per gesture.
        assertEquals(original.copy(trace = original.trace.copy(verdict = null, thresholds = null)),
            decoded)
        assertNull("a verdict is recomputed, not stored", decoded.trace.verdict)
        assertNull("thresholds live on the session line", decoded.trace.thresholds)
    }

    /**
     * A withdrawn label has to survive the file, and so does the absence of one. A reader that
     * turned a missing `void` into an empty string would quietly withdraw the entire bank, and a
     * writer that emitted one on every line would put the exception on 286 lines that are fine.
     */
    @Test
    fun `a withdrawal survives the round trip, and its absence stays an absence`() {
        val path = listOf(PathPoint(100f, 200f, 0), PathPoint(101f, 418f, 550))
        val evidence = record("d", path)
        assertNull(evidence.voidReason)
        assertFalse(GestureRecordCodec.encode(evidence).contains("void"))
        assertNull(GestureRecordCodec.decode(GestureRecordCodec.encode(evidence))!!.voidReason)

        val withdrawn = evidence.copy(voidReason = "an abandoned flick, not the tap it claims")
        val decoded = GestureRecordCodec.decode(GestureRecordCodec.encode(withdrawn))
        assertEquals(
            withdrawn.copy(trace = withdrawn.trace.copy(verdict = null, thresholds = null)),
            decoded,
        )
    }

    /**
     * The l key's flick secondary is a double quote and the k key's is an apostrophe, so quoting
     * is not a theoretical concern in this file: it is the two most interesting symbols on the
     * keyboard.
     */
    @Test
    fun `quotes and backslashes survive the round trip`() {
        listOf("\"", "'", "\\", "a\"b\\c", "\n").forEach { text ->
            val decoded = GestureRecordCodec.decode(
                GestureRecordCodec.encode(record(text, listOf(PathPoint(1f, 2f, 0), PathPoint(3f, 4f, 9)))),
            )
            assertEquals(text, decoded?.typed)
        }
    }

    @Test
    fun `timestamps are stored relative to the finger going down`() {
        val original = record(
            "in",
            listOf(PathPoint(0f, 0f, 9_000_000L), PathPoint(0f, 40f, 9_000_120L)),
        )
        val decoded = GestureRecordCodec.decode(GestureRecordCodec.encode(original))!!
        assertEquals(0L, decoded.path.first().t)
        assertEquals(120L, decoded.path.last().t)
        assertEquals(120L, decoded.durationMs)
    }

    @Test
    fun `an unreadable line is skipped rather than fatal`() {
        assertNull(GestureRecordCodec.decode("{ not json"))
        assertNull(GestureRecordCodec.decode(""))
        assertNull(GestureRecordCodec.decode("""{"v":1,"id":"x"}"""))
    }

    @Test
    fun `derived measures describe the gesture`() {
        val r = record(
            "in",
            listOf(PathPoint(0f, 0f, 0), PathPoint(0f, 30f, 50), PathPoint(0f, 90f, 120)),
        )
        assertEquals(90f, r.pathLength, 0.01f)
        assertEquals(90f, r.displacement, 0.01f)
        assertEquals(120L, r.durationMs)
    }
}
