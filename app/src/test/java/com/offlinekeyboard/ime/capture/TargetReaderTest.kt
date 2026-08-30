package com.offlinekeyboard.ime.capture

import com.offlinekeyboard.ime.gesture.GestureConfig
import com.offlinekeyboard.ime.gesture.GestureIntent
import com.offlinekeyboard.ime.gesture.GestureOutput
import com.offlinekeyboard.ime.gesture.GestureTrace
import com.offlinekeyboard.ime.gesture.GestureVerdict
import com.offlinekeyboard.ime.gesture.TouchFsm
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a prose word records whatever the thumb did to it, under a label that is true.
 *
 * The traces are made by driving the real [TouchFsm] rather than by hand-building a
 * [GestureTrace]. A test that invented its own verdicts would be checking the labelling rule
 * against a second, private opinion of what a tap is -- and the labelling rule is only worth
 * anything if it agrees with the state machine the keyboard actually ships.
 */
class TargetReaderTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
    private val config = GestureConfig()

    private fun key(id: String): KeyRect = geometry.keyRects.first { it.key.id == id }

    private fun List<GestureOutput>.trace(): GestureTrace =
        filterIsInstance<GestureOutput.GestureCaptured>().single().trace

    private fun tap(id: String): GestureTrace {
        val k = key(id)
        val f = TouchFsm(geometry, config)
        f.onDown(k.centerX, k.centerY, 0)
        return f.onUp(k.centerX, k.centerY, 90).trace()
    }

    private fun flick(id: String): GestureTrace {
        val k = key(id)
        val f = TouchFsm(geometry, config)
        f.onDown(k.centerX, k.centerY, 0)
        f.onMove(k.centerX, k.centerY + geometry.keyHeight * 0.6f, 40)
        return f.onUp(k.centerX, k.centerY + geometry.keyHeight * 0.6f, 70).trace()
    }

    /**
     * A glide across [ids], sampled densely enough to pass the glide distance threshold.
     *
     * The lift does not end it. A finger leaving the glass only *suspends* a glide, so that a
     * thumb that skipped can rejoin it; the gesture is captured when the resume window closes.
     * Driving it any other way here would capture nothing, which is exactly what the first
     * version of this helper did.
     */
    private fun glide(vararg ids: String): GestureTrace {
        val f = TouchFsm(geometry, config)
        val keys = ids.map(::key)
        f.onDown(keys[0].centerX, keys[0].centerY, 0)
        var t = 0L
        for (i in 1 until keys.size) {
            val from = keys[i - 1]
            val to = keys[i]
            repeat(8) { step ->
                t += 12
                val f01 = (step + 1) / 8f
                f.onMove(
                    from.centerX + (to.centerX - from.centerX) * f01,
                    from.centerY + (to.centerY - from.centerY) * f01,
                    t,
                )
            }
        }
        val last = keys.last()
        f.onUp(last.centerX, last.centerY, t + 20)
        return f.onGlideResumeTimeout().trace()
    }

    private fun word(text: String) = Target(
        id = "word:$text",
        intent = GestureIntent.WORD,
        startKeyId = text.take(1),
        expected = text,
        letters = text,
    )

    private fun keep(r: TargetReader.Reading) = r as TargetReader.Reading.Keep

    // --- a word may be glided, exactly as before ---------------------------------------------

    @Test
    fun `a glide of the whole word is one word record`() {
        val reader = TargetReader(word("ok"))
        val kept = keep(reader.read(glide("o", "k")))
        assertEquals(GestureIntent.WORD, kept.intent)
        assertEquals("ok", kept.expected)
        assertEquals(null, kept.word)
        assertTrue("a glided word finishes the target", kept.complete)
    }

    // --- or tapped out, which is the whole point of the change --------------------------------

    /**
     * Under the old rule this collected one mislabelled record and three rejections: the first
     * tap was filed as a *glide of " okay"* carrying a single-tap path, and the rest were thrown
     * out for starting on the wrong key.
     */
    @Test
    fun `a word tapped out is one labelled letter per tap`() {
        val reader = TargetReader(word("okay"))
        val letters = listOf("o", "k", "a", "y")
        letters.forEachIndexed { i, letter ->
            val kept = keep(reader.read(tap(letter)))
            assertEquals(GestureIntent.LETTER, kept.intent)
            assertEquals(letter, kept.expected)
            assertEquals("the letter must carry the word it came from", "okay", kept.word)
            assertEquals(i, kept.letterIndex)
            assertEquals("only the last letter finishes the word", i == letters.lastIndex, kept.complete)
        }
    }

    /**
     * The sample the old lab destroyed. The passage asked for a letter, the heuristic produced a
     * symbol, and that disagreement is the most valuable thing the bank can hold -- it is direct
     * evidence that the flick threshold is too loose for ordinary typing. Recording it as a
     * LETTER with a FLICK verdict is what lets `verdictIntent` show the disagreement; rejecting
     * it deleted the evidence.
     */
    @Test
    fun `a flick where a letter was wanted is recorded as that letter`() {
        val reader = TargetReader(word("in"))
        val trace = flick("i")
        assertEquals(GestureVerdict.FLICK, trace.verdict)
        val kept = keep(reader.read(trace))
        assertEquals(GestureIntent.LETTER, kept.intent)
        assertEquals("i", kept.expected)
        assertEquals("in", kept.word)
        assertFalse(kept.complete)
    }

    @Test
    fun `a one-letter word is a single letter and finishes at once`() {
        val reader = TargetReader(word("a"))
        val kept = keep(reader.read(tap("a")))
        assertEquals(GestureIntent.LETTER, kept.intent)
        assertTrue(kept.complete)
    }

    // --- what it refuses, and what it says about it -------------------------------------------

    /**
     * The message the lab prints comes from here, and "sorry begins on S" is actively unhelpful
     * once three of its letters are down. The key that is due moves through the word.
     */
    @Test
    fun `a wrong key names the letter now due, not the first`() {
        val reader = TargetReader(word("okay"))
        reader.read(tap("o"))
        reader.read(tap("k"))
        val wrong = reader.read(tap("z")) as TargetReader.Reading.WrongKey
        assertEquals("a", wrong.expectedKeyId)
        assertEquals("a", reader.expectedKeyId)
        assertEquals(2, reader.tapped)
    }

    @Test
    fun `a glide part-way through a tapped word is refused rather than mislabelled`() {
        val reader = TargetReader(word("okay"))
        reader.read(tap("o"))
        assertEquals(TargetReader.Reading.MidWordGlide, reader.read(glide("k", "a")))
        assertEquals("no letter may be consumed by a refusal", 1, reader.tapped)
    }

    @Test
    fun `undo steps back one letter`() {
        val reader = TargetReader(word("okay"))
        reader.read(tap("o"))
        reader.read(tap("k"))
        reader.stepBack()
        assertEquals(1, reader.tapped)
        assertEquals("k", reader.expectedKeyId)
    }

    // --- the drill is untouched, and must be ---------------------------------------------------

    /**
     * The collision passage exists to ask for one named gesture on one named key. A reader that
     * accepted anything there would collect exactly the ambiguity the drill was built to resolve.
     */
    @Test
    fun `a drill target still demands the one gesture it asked for`() {
        val drill = Target(
            id = "flick:i:8",
            intent = GestureIntent.SYMBOL,
            startKeyId = "i",
            expected = "8",
        )
        val reader = TargetReader(drill)
        assertFalse(reader.isWord)

        val wrong = reader.read(tap("o")) as TargetReader.Reading.WrongKey
        assertEquals("i", wrong.expectedKeyId)

        val kept = keep(reader.read(flick("i")))
        assertEquals(GestureIntent.SYMBOL, kept.intent)
        assertEquals("8", kept.expected)
        assertEquals(null, kept.word)
        assertTrue(kept.complete)
    }

    @Test
    fun `a drill tap that barely moves is still a tap, not a nothing`() {
        val drill = Target(
            id = "tap:i",
            intent = GestureIntent.LETTER,
            startKeyId = "i",
            expected = "i",
        )
        val kept = keep(TargetReader(drill).read(tap("i")))
        assertEquals(GestureIntent.LETTER, kept.intent)
        assertTrue(kept.complete)
    }
}
