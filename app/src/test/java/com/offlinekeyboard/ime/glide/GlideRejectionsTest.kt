package com.offlinekeyboard.ime.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deleting a glided word teaches the next glide in that place not to offer it again.
 *
 * The rule these check is a conversation, not a lookup: the decoder proposes, the user deletes,
 * the decoder proposes its next best, and so on down the ranking. What can go wrong is all in the
 * bookkeeping between the turns -- a rejection recorded twice, a rejection recorded against a
 * delete that was not a rejection at all, a cycle that sticks at the second candidate, a memory
 * that never lets go. Each of those is a test here.
 *
 * The ranked candidate list stands in for the engine. That is the whole of the engine's
 * contribution to this feature -- [GlideEngine.decode] returns words best first -- so nothing is
 * lost by not running the models, and the tests stay on the laptop.
 */
class GlideRejectionsTest {

    /**
     * A plausible ranking for one ambiguous path: the classic `hello`/`gelp`/`help` cluster.
     * Unsure throughout -- the best word is well short of [GlideRejections.SURE] -- because that
     * is the case the rejection memory is for.
     */
    private val ranked = unsure("hello", "help", "heap", "gel")

    /** The ranking a clean, deliberate redraw of `hello` gets: nothing else comes close. */
    private val sure = listOf(
        GlideCandidate("hello", 0.97f),
        GlideCandidate("help", 0.02f),
        GlideCandidate("heap", 0.01f),
    )

    private fun unsure(vararg words: String) =
        words.map { GlideCandidate(it, 0.5f / words.size.coerceAtLeast(1)) }

    private fun rejections() = GlideRejections()

    /** One full turn: the word is offered at [start], typed, and then deleted. */
    private fun rejectAt(r: GlideRejections, start: Int, candidates: List<GlideCandidate> = ranked): String {
        val word = r.next(start, candidates)!!
        r.committed(start, word)
        r.rejectLast()
        return word
    }

    // --- the basic promise --------------------------------------------------------------------

    @Test
    fun `the first glide offers the best candidate`() {
        assertEquals("hello", rejections().next(0, ranked))
    }

    @Test
    fun `deleting the word offers the second candidate next time`() {
        val r = rejections()
        assertEquals("hello", rejectAt(r, 0))
        assertEquals("help", r.next(0, ranked))
    }

    /**
     * The requirement in one line: keep deleting and the ranking keeps advancing. Stopping at
     * the second candidate would make the third-choice word unreachable by gliding, which is the
     * same dead end the feature exists to remove, only one step further along.
     */
    @Test
    fun `deleting repeatedly walks down the whole ranking`() {
        val r = rejections()
        assertEquals(ranked.map { it.word }, ranked.indices.map { rejectAt(r, 0) })
    }

    /**
     * Past the end it starts again rather than typing nothing. A glide that silently does
     * nothing reads as a broken keyboard, and by this point the user has been shown everything
     * the decoder has.
     */
    @Test
    fun `running out of candidates wraps back to the best one`() {
        val r = rejections()
        ranked.forEach { _ -> rejectAt(r, 0) }
        assertEquals("hello", r.next(0, ranked))
    }

    /** And the lap after the wrap advances again, rather than pinning to the first candidate. */
    @Test
    fun `the cycle can be walked more than once`() {
        val r = rejections()
        repeat(ranked.size) { rejectAt(r, 0) }
        assertEquals("hello", rejectAt(r, 0))
        assertEquals("help", r.next(0, ranked))
    }

    // --- what is and is not a rejection -------------------------------------------------------

    /**
     * A word that was left alone is a word that was right. Accepting has to *clear* the place,
     * not merely stop adding to it: the offsets in a field are reused constantly as text is
     * edited, and a rejection that outlived the word it was about would demote a candidate for a
     * sentence nobody has complained about.
     */
    @Test
    fun `accepting the word forgets what was rejected there`() {
        val r = rejections()
        rejectAt(r, 0)
        val second = r.next(0, ranked)
        assertEquals("help", second)
        r.committed(0, second!!)
        r.acceptedAt(0)
        assertEquals("hello", r.next(0, ranked))
    }

    /**
     * Two deletes in a row are one rejection. The second delete is the user removing whatever
     * they typed *instead*, and there is no longer a glided word in the field for it to refuse;
     * counting it would skip a candidate the user never saw.
     */
    @Test
    fun `deleting twice does not reject two candidates`() {
        val r = rejections()
        rejectAt(r, 0)
        assertFalse("nothing is left to take back", r.rejectLast())
        assertEquals("help", r.next(0, ranked))
    }

    /** A delete with no glide behind it is somebody else's edit, and says nothing about words. */
    @Test
    fun `a delete with no glide committed rejects nothing`() {
        val r = rejections()
        assertFalse(r.rejectLast())
        assertEquals("hello", r.next(0, ranked))
    }

    // --- the place ----------------------------------------------------------------------------

    /**
     * The memory is keyed on where the word was typed, so the same path elsewhere in the
     * sentence starts from the top. Nobody has said anything about *that* word yet.
     */
    @Test
    fun `a rejection at one place does not affect another`() {
        val r = rejections()
        rejectAt(r, 0)
        assertEquals("hello", r.next(40, ranked))
    }

    /** Places keep their own counters, and interleaving them does not mix the two up. */
    @Test
    fun `two places advance independently`() {
        val r = rejections()
        rejectAt(r, 0)
        rejectAt(r, 40)
        rejectAt(r, 0)
        assertEquals("heap", r.next(0, ranked))
        assertEquals("help", r.next(40, ranked))
    }

    // --- edges --------------------------------------------------------------------------------

    /** No candidates is a path that decoded to nothing; there is no word to offer or to blame. */
    @Test
    fun `an empty candidate list offers nothing`() {
        assertNull(rejections().next(0, emptyList()))
    }

    /**
     * A single-candidate path still types its word every time. The wrap makes this fall out
     * rather than needing a case of its own, and the alternative -- one delete and the path is
     * mute forever -- would be the worst version of the bug this fixes.
     */
    @Test
    fun `a lone candidate survives being rejected`() {
        val r = rejections()
        val only = unsure("hello")
        assertEquals("hello", rejectAt(r, 0, only))
        assertEquals("hello", r.next(0, only))
    }

    /**
     * A shorter list than last time -- the engine is free to return a different number of
     * candidates for a differently drawn path -- must not leave the user with nothing.
     */
    @Test
    fun `a shorter candidate list still offers a word`() {
        val r = rejections()
        rejectAt(r, 0)
        assertEquals("hello", r.next(0, unsure("hello")))
    }

    /** Focus left the field; every offset now names different text. */
    @Test
    fun `clearing forgets everything`() {
        val r = rejections()
        rejectAt(r, 0)
        r.clear()
        assertEquals("hello", r.next(0, ranked))
        assertNull(r.pendingCommit())
    }

    /** What the delete-watcher reads to know which word it is looking for, and where. */
    @Test
    fun `the pending commit is the word last typed`() {
        val r = rejections()
        r.committed(7, "hello")
        assertEquals(7 to "hello", r.pendingCommit())
    }

    /**
     * A word glided with shift armed goes into the field as `Hello` while the ranking still
     * spells it `hello`, and the two are matched against different things -- the field and the
     * candidate list. Recording one spelling for both jobs meant a capitalised word could never
     * be rejected: nothing in the ranking ever compared equal to `Hello`, so the first candidate
     * was offered again forever. The word starting a sentence is exactly where shift is armed,
     * which made this the common case rather than a corner of one.
     */
    @Test
    fun `a capitalised word still advances the ranking`() {
        val r = rejections()
        val first = r.next(0, ranked)
        assertEquals("hello", first)
        r.committed(0, written = "Hello", candidate = first!!)
        // The watcher looks for what the field was actually given.
        assertEquals(0 to "Hello", r.pendingCommit())
        assertTrue(r.rejectLast())
        assertEquals("help", r.next(0, ranked))
    }

    // --- when the decoder is sure -------------------------------------------------------------

    /**
     * Deleting a word and drawing it again cleanly is the user asking for it again -- to repeat
     * it, or because the delete was about something else. A refusal must not override a decoder
     * that has no doubt, or the word becomes impossible to glide in that spot.
     */
    @Test
    fun `a confident redraw types the refused word again`() {
        val r = rejections()
        rejectAt(r, 0)
        assertEquals("hello", r.next(0, sure))
    }

    /** And it keeps doing so: insisting twice is not an argument for moving on. */
    @Test
    fun `a confident redraw survives repeated deletes`() {
        val r = rejections()
        rejectAt(r, 0)
        assertEquals("hello", rejectAt(r, 0, sure))
        assertEquals("hello", r.next(0, sure))
    }

    /**
     * The refusal is overridden, not forgotten. A sloppy redraw after the confident one is back
     * to being a question the decoder cannot answer, and the delete still steers it.
     */
    @Test
    fun `an unsure redraw after a confident one still moves on`() {
        val r = rejections()
        rejectAt(r, 0)
        assertEquals("hello", r.next(0, sure))
        assertEquals("help", r.next(0, ranked))
    }

    /** Just under the bar is still a guess, and a guess defers to the user's delete. */
    @Test
    fun `a best word just short of sure still defers to the refusal`() {
        val r = rejections()
        rejectAt(r, 0)
        val almost = listOf(
            GlideCandidate("hello", GlideRejections.SURE - 0.01f),
            GlideCandidate("help", 0.1f),
        )
        assertEquals("help", r.next(0, almost))
    }

    /** Confidence never manufactures a refusal: with nothing deleted, the best word is typed. */
    @Test
    fun `confidence changes nothing where nothing was refused`() {
        assertEquals("hello", rejections().next(0, sure))
        assertEquals("hello", rejections().next(0, ranked))
    }
}
