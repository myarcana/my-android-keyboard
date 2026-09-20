package com.offlinekeyboard.ime.glide

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That "emoji" can be glided.
 *
 * The word was absent from the shipped lexicon entirely, so tracing e-m-o-j-i decoded to
 * whatever else shared the shape and the word itself could never be produced. It was missing
 * rather than mis-scored because the lexicon is built from Norvig's count_1w, a 2012 crawl that
 * predates the word's use in English -- the same reason "wifi" needs a hand-written count.
 */
class LexiconEmojiWordTest {

    private fun asset(name: String): File? {
        var dir: File? = File("").absoluteFile
        var found: File? = null
        repeat(5) {
            val candidate = dir?.resolve("app/src/main/assets/$name")
            if (found == null && candidate != null && candidate.isFile) found = candidate
            dir = dir?.parentFile
        }
        return found
    }

    private val lexicon: Lexicon? = asset(LEXICON_ASSET)?.inputStream()?.use(Lexicon::load)

    @Test
    fun `emoji is in the lexicon`() {
        val lexicon = lexicon ?: return
        assertTrue("\"emoji\" missing from ${LEXICON_ASSET}", lexicon.words.contains("emoji"))
    }

    @Test
    fun `the plural is there too`() {
        val lexicon = lexicon ?: return
        assertTrue("\"emojis\" missing from ${LEXICON_ASSET}", lexicon.words.contains("emojis"))
    }

    /**
     * The decoder answers in letters and the keyboard commits a spelling, so the glided form has
     * to map back to the word itself rather than to some other spelling.
     */
    @Test
    fun `the glided form spells the word`() {
        val lexicon = lexicon ?: return
        assertEquals(listOf("emoji"), lexicon.spellings("emoji"))
    }

    /**
     * Decoding only ever scans the bucket for a glide's two endpoints. A word absent from its
     * own bucket is in the lexicon but unreachable, which is the failure this guards.
     */
    @Test
    fun `it sits in the bucket its endpoints select`() {
        val lexicon = lexicon ?: return
        val bucket = lexicon.bucket('e', 'i')
        val words = bucket.map { lexicon.words[it] }
        assertTrue("\"emoji\" not in the e..i bucket", words.contains("emoji"))
    }

    /**
     * Scored as a word people write, so it can outrank the noise sharing its shape.
     *
     * Pinned against "nope" and "um" rather than an absolute number: those two carry the same
     * hand-written count as "emoji" in build_lexicon.py's INFORMAL table, so a regenerate that
     * rescales every frequency moves all three together and this keeps holding. Note the shipped
     * asset predates some edits to that table -- "wifi" is listed at the same count but scores
     * higher in the file -- which is exactly why this compares against entries whose value in
     * the asset was confirmed, not against the table.
     */
    @Test
    fun `it is scored like the other modern words`() {
        val lexicon = lexicon ?: return
        val emoji = lexicon.logProbability("emoji")
        assertNotNull("\"emoji\" has no frequency", emoji)
        for (peer in listOf("nope", "um")) {
            val score = lexicon.logProbability(peer) ?: continue
            assertEquals("\"emoji\" should score with \"$peer\"", score, emoji!!, 0.01f)
        }
    }

    /**
     * Frequency is what breaks the tie when several words share a shape, so a word nobody can
     * outrank is as good as absent. "emoji" should beat the rare words in its own bucket.
     */
    @Test
    fun `it outranks the rare words sharing its bucket`() {
        val lexicon = lexicon ?: return
        val emoji = lexicon.logProbability("emoji") ?: return
        val bucket = lexicon.bucket('e', 'i')
        val rarer = bucket.count { lexicon.logFrequency[it] < lexicon.logFrequency[bucket.first { i -> lexicon.words[i] == "emoji" }] }
        assertTrue("\"emoji\" outranks nothing in its bucket", rarer > 0)
        assertTrue(emoji < 0f)
    }
}
