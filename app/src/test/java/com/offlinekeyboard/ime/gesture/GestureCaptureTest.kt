package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import org.junit.Assert.assertEquals
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
        assertEquals(config.flickDistanceRatio, trace.thresholds.flickDistanceRatio, 0.001f)
    }

    @Test
    fun `a glide is captured as a glide`() {
        val q = key("q")
        val w = key("w")
        val e = key("e")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        f.onMove(w.centerX, w.centerY, 30)
        f.onMove(e.centerX, e.centerY, 60)
        val trace = f.onUp(e.centerX, e.centerY, 90).captured()
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

    private fun record(expected: String, path: List<PathPoint>) = GestureRecord(
        id = "abc12345",
        at = 1_756_000_000_000L,
        intent = GestureIntent.WORD,
        promptId = "i:word:I'm",
        expected = expected,
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
        val decoded = GestureRecordCodec.decode(GestureRecordCodec.encode(original))
        assertEquals(original, decoded)
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
            assertEquals(text, decoded?.expected)
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
        assertEquals(GestureIntent.WORD, r.verdictIntent)
    }

    // --- the drill catalogue ------------------------------------------------------------------

    @Test
    fun `every drill starts on a key that exists and can flick`() {
        val ids = geometry.keyRects.associateBy { it.key.id }
        com.offlinekeyboard.ime.capture.Drills.session(reps = 1).forEach { drill ->
            val key = ids[drill.startKeyId]
            assertNotNull("no key '${drill.startKeyId}' for drill ${drill.id}", key)
            assertNotNull(
                "key '${drill.startKeyId}' has no flick secondary, so ${drill.id} is impossible",
                key!!.key.secondary,
            )
        }
    }

    @Test
    fun `symbol drills ask for the secondary the key actually has`() {
        val ids = geometry.keyRects.associateBy { it.key.id }
        com.offlinekeyboard.ime.capture.Drills.session(reps = 1)
            .filter { it.intent == GestureIntent.SYMBOL }
            .forEach { drill ->
                assertEquals(
                    "drill ${drill.id} asks for the wrong symbol",
                    ids[drill.startKeyId]!!.key.secondary,
                    drill.expected,
                )
            }
    }

    /**
     * The criterion that makes a word drill worth collecting: two letters, the second below the
     * first. Both halves matter, and neither is obvious.
     *
     * *Two letters*, because that makes the whole glide a single stroke with no corner in it. A
     * longer word gives itself away as soon as it changes direction: "was" leaves w heading down
     * and half a key left, which looks exactly like a flick -- for about forty pixels, after
     * which the path turns back rightward towards s and stops resembling one. Note that "was"
     * passes the below-the-start test perfectly well, since s does sit below w. Only the corner
     * rules it out, which is why counting the letters is the part that does the work.
     *
     * *Below*, because a flick only ever goes downward, so a word leaving sideways or upward was
     * never in the running.
     *
     * This test exists because the first version of the catalogue got both halves wrong.
     */
    @Test
    fun `word drills are two-key words that hang below their start key`() {
        val ids = geometry.keyRects.associateBy { it.key.id }
        val pitch = geometry.keyUnit + geometry.gap
        com.offlinekeyboard.ime.capture.Drills.session(reps = 1)
            .filter { it.intent == GestureIntent.WORD }
            .forEach { drill ->
                // Glide typing goes letter to letter; punctuation such as the apostrophe in
                // "I'm" is filled in afterwards and is never part of the path.
                val letters = drill.expected.lowercase().filter { it.isLetter() }
                assertEquals(
                    "${drill.id}: '${drill.expected}' is ${letters.length} keys, so its glide has " +
                        "a corner in it and stops looking like a flick",
                    2,
                    letters.length,
                )
                val start = ids[drill.startKeyId]!!
                val end = ids[letters.last().toString()]
                assertNotNull("no key '${letters.last()}' for drill ${drill.id}", end)
                assertTrue(
                    "${drill.id}: '${drill.expected}' ends on '${letters.last()}', which is not " +
                        "below '${drill.startKeyId}'",
                    end!!.centerY - start.centerY >= geometry.keyHeight,
                )
                assertTrue(
                    "${drill.id}: '${drill.expected}' ends %.1f keys sideways -- too far across "
                        .format(kotlin.math.abs(end.centerX - start.centerX) / pitch) +
                        "to be mistaken for a swipe down",
                    kotlin.math.abs(end.centerX - start.centerX) <= 1.6f * pitch,
                )
            }
    }

    /** Word drills exist to collide with the flick, so they must start on the flick's key. */
    @Test
    fun `word drills spell words that start on the drill key`() {
        com.offlinekeyboard.ime.capture.Drills.session(reps = 1)
            .filter { it.intent == GestureIntent.WORD }
            .forEach { drill ->
                assertEquals(
                    "drill ${drill.id} spells a word that does not start on its key",
                    drill.startKeyId,
                    drill.expected.first().lowercase(),
                )
            }
    }

    @Test
    fun `a session alternates symbol and word rather than blocking them`() {
        val session = com.offlinekeyboard.ime.capture.Drills.session(reps = 2)
        val paired = session.filter { drill ->
            com.offlinekeyboard.ime.capture.Drills.PAIRS.any { it.startKeyId == drill.startKeyId }
        }
        paired.windowed(2).forEach { (a, b) ->
            if (a.startKeyId == b.startKeyId) {
                assertTrue(
                    "two ${a.intent} drills in a row on ${a.startKeyId}",
                    a.intent != b.intent,
                )
            }
        }
    }
}
