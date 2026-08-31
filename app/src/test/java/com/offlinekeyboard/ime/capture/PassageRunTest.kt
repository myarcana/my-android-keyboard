package com.offlinekeyboard.ime.capture

import com.offlinekeyboard.ime.gesture.GestureIntent
import com.offlinekeyboard.ime.gesture.GestureSession
import com.offlinekeyboard.ime.gesture.GestureSessionCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript: that folding every gesture's edit in order reproduces exactly what was typed.
 *
 * This is the property the whole bank now rests on. The mapping from gesture to output is
 * recorded rather than inferred, so if the fold does not rebuild the text the recording is
 * lying -- and every later question, about mis-hits or decoders or thresholds, would be asked of
 * a transcript that never happened.
 */
class PassageRunTest {

    private fun target(word: String) = Target(
        id = "word:$word",
        intent = GestureIntent.WORD,
        startKeyId = word.take(1),
        expected = word,
        letters = word,
    )

    private fun run(vararg words: String) =
        PassageRun("test", words.joinToString(" "), words.map(::target))

    @Test
    fun `the passage is its tokens, space separated`() {
        assertEquals("ok i am", run("ok", "i", "am").intended)
    }

    @Test
    fun `folding the edits rebuilds what was typed`() {
        val r = run("ok", "i", "am")
        "ok i am".forEach { r.apply(PassageRun.Edit(it.toString(), 0)) }
        assertEquals("ok i am", r.actual)
        assertEquals(7, r.position)
        assertTrue(r.isComplete)
    }

    /**
     * A glide types a whole word at once, and often a space in front of it. Nothing about that is
     * recoverable by lining strings up afterwards, which is why the edit is recorded rather than
     * reconstructed.
     */
    @Test
    fun `one gesture may produce several characters`() {
        val r = run("ok", "then")
        r.apply(PassageRun.Edit("ok", 0))
        r.apply(PassageRun.Edit(" then", 0))
        assertEquals("ok then", r.actual)
        assertEquals(2, r.count)
    }

    // --- how the passage is marked up against what was typed -------------------------------

    private fun PassageRun.type(text: String) =
        text.forEach { apply(PassageRun.Edit(it.toString(), 0)) }

    private fun marksOf(r: PassageRun) = r.alignment().marks.joinToString("") {
        when (it) {
            PassageRun.Mark.CORRECT -> "."
            PassageRun.Mark.WRONG -> "x"
            else -> "_"
        }
    }

    /**
     * The bug this replaced: a common prefix turns one mistyped letter into a red remainder of
     * the passage, because the two strings never agree again index for index. Reported from a
     * real session as "all the letters get highlighted red after the first mistake", which is
     * exactly what it did.
     */
    @Test
    fun `one wrong letter marks one letter, not the rest of the passage`() {
        val r = run("ok", "then")
        r.type("ok thzn")
        assertEquals("....." + "x" + ".", marksOf(r))
        assertEquals(1, r.alignment().wrong)
    }

    /**
     * The other half of the same bug. A dropped character shifts every later one, and by index
     * alone the whole tail reads as wrong and the caret stops saying what to type next.
     */
    @Test
    fun `a dropped character does not condemn everything after it`() {
        val r = run("ok", "then")
        r.type("ok thn")
        val marks = marksOf(r)
        assertEquals("the passage is 7 characters", 7, marks.length)
        assertEquals("only the missing letter is unaccounted for", 0, r.alignment().wrong)
        assertEquals(1, marks.count { it == '_' })
    }

    @Test
    fun `an extra character is counted as extra rather than as a mistake`() {
        val r = run("ok")
        r.type("okk")
        assertEquals(1, r.alignment().inserted)
        assertEquals(0, r.alignment().wrong)
    }

    @Test
    fun `the caret is where the typist has reached`() {
        val r = run("ok", "then")
        assertEquals(0, r.alignment().caret)
        r.type("ok t")
        assertEquals(4, r.alignment().caret)
        r.type("hen")
        assertEquals(7, r.alignment().caret)
    }

    /**
     * A passage is typed a bit at a time, and the part not reached yet is not a mistake. Charging
     * for it leaves the alignment table full of ties and the caret lands anywhere: nine
     * characters into a long passage it was reported at the very end.
     */
    @Test
    fun `a barely started passage puts the caret near the start`() {
        val r = PassageRun("t", "the deadline is friday but i would rather be early")
        r.type("the deadl")
        val a = r.alignment()
        assertEquals(9, a.caret)
        assertEquals(0, a.wrong)
        assertEquals("nothing past the caret is judged", 9, a.marks.count { it == PassageRun.Mark.CORRECT })
    }

    @Test
    fun `nothing typed leaves the whole passage untyped`() {
        val r = run("ok", "then")
        assertEquals("_______", marksOf(r))
        assertEquals(0, r.alignment().caret)
    }

    @Test
    fun `a corrected mistake leaves no mark`() {
        val r = run("ok")
        r.type("oi")
        assertEquals(1, r.alignment().wrong)
        r.apply(PassageRun.Edit("", 1))
        r.type("k")
        assertEquals("..", marksOf(r))
        assertEquals(0, r.alignment().wrong)
    }

    /**
     * The correction is the ground truth, and it only exists because the mistake was kept. Under
     * the old lab the `i` here was never recorded at all -- it started on the wrong key, so it
     * was refused -- and the bank was left with a run of taps in which nothing ever went wrong.
     */
    @Test
    fun `a mistake and its correction are both in the transcript`() {
        val r = run("ok")
        r.apply(PassageRun.Edit("o", 0))
        r.apply(PassageRun.Edit("i", 0))
        assertEquals("oi", r.actual)
        assertEquals("one letter wrong, and it is the second", 1, r.alignment().wrong)

        r.apply(PassageRun.Edit("", 1))
        assertEquals("o", r.actual)
        r.apply(PassageRun.Edit("k", 0))
        assertEquals("ok", r.actual)
        assertEquals(0, r.alignment().wrong)
        assertEquals("every one of them is a gesture in the bank", 4, r.count)
    }

    @Test
    fun `deleting more than exists cannot go below empty`() {
        val r = run("ok")
        r.apply(PassageRun.Edit("o", 0))
        r.apply(PassageRun.Edit("", 9))
        assertEquals("", r.actual)
        assertEquals(0, r.position)
    }

    /** The lab never blocks, so the typing may run past the passage or drift off it entirely. */
    @Test
    fun `typing may drift from the passage without anything stopping`() {
        val r = run("ok")
        r.apply(PassageRun.Edit("zzzz", 0))
        assertEquals("zzzz", r.actual)
        assertTrue("nothing refuses it and the run goes on", r.isComplete)
    }

    // --- the token hint, which is a hint ------------------------------------------------------

    @Test
    fun `the token at a position is the one being aimed at`() {
        val r = run("ok", "i", "am")
        assertEquals("ok", r.targetAt(0)?.expected)
        assertEquals("ok", r.targetAt(1)?.expected)
        assertEquals("i", r.targetAt(3)?.expected)
        assertEquals("am", r.targetAt(5)?.expected)
    }

    @Test
    fun `token ranges cover the passage text`() {
        val r = run("ok", "i", "am")
        assertEquals(listOf(0 until 2, 3 until 4, 5 until 7), r.tokenRanges())
        r.tokenRanges().forEachIndexed { i, range ->
            assertEquals(r.targets[i].display, r.intended.substring(range.first, range.last + 1))
        }
    }

    @Test
    fun `a run with no tokens has no hint to give`() {
        assertNull(PassageRun("x", "abc").targetAt(0))
        assertFalse(PassageRun("x", "abc").isComplete)
    }

    // --- the session line ---------------------------------------------------------------------

    @Test
    fun `a session line survives the file format`() {
        val session = GestureSession(
            id = "abc12345",
            at = 1756000004000,
            passageId = "corpus:12",
            intended = "ok i am",
            actual = "oi i am",
        )
        val back = GestureSessionCodec.decode(GestureSessionCodec.encode(session))
        assertEquals(session, back)
    }

    /**
     * A gesture line is not a session line and must not be read as one. They share a file, and a
     * reader that confused them would silently take a gesture's `id` for a run's.
     */
    @Test
    fun `a gesture line is not mistaken for a session`() {
        assertNull(GestureSessionCodec.decode("""{"v":4,"id":"x","intent":"LETTER"}"""))
        assertNull(GestureSessionCodec.decode("not json at all"))
    }
}
