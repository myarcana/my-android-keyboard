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

    /**
     * The correction is the ground truth, and it only exists because the mistake was kept. Under
     * the old lab the `u` here was never recorded at all -- it started on the wrong key, so it
     * was refused -- and the bank was left with a run of taps in which nothing ever went wrong.
     */
    @Test
    fun `a mistake and its correction are both in the transcript`() {
        val r = run("ok")
        r.apply(PassageRun.Edit("o", 0))
        r.apply(PassageRun.Edit("i", 0))
        assertEquals("oi", r.actual)
        assertEquals("the passage and the typing part company at the second character", 1, r.correctPrefix)

        r.apply(PassageRun.Edit("", 1))
        assertEquals("o", r.actual)
        r.apply(PassageRun.Edit("k", 0))
        assertEquals("ok", r.actual)
        assertEquals(2, r.correctPrefix)
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
        assertEquals(0, r.correctPrefix)
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
