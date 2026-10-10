package com.offlinekeyboard.ime.glide

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Words the lexicon once could not type, and the cache that hid the fix from the phone.
 *
 * "cannot" was missing because the crawl behind the ranking splits it into "can not", leaving a
 * residue at rank 106,594, far past the cut. "homophone" and "heteronym" were missing because the
 * cut was a word count, and ordinary long words the crawl saw rarely fell past it.
 */
class LexiconCoverageTest {

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
    fun `words the tokenizer split are in the lexicon`() {
        val lexicon = lexicon ?: return
        listOf("cannot", "gimme", "lemme").forEach {
            assertNotNull("$it is missing", lexicon.logProbability(it))
        }
    }

    /** Scored beside its contraction, not buried at the floor where it could never win. */
    @Test
    fun `cannot is scored as a common word`() {
        val lexicon = lexicon ?: return
        val cannot = lexicon.logProbability("cannot")!!
        val cant = lexicon.logProbability("can't")!!
        assertTrue("cannot $cannot vs can't $cant", cant - cannot < 2.5f)
    }

    /** The crawl predates them, so they have no count and only the INFORMAL list keeps them. */
    @Test
    fun `words newer than the crawl are in the lexicon`() {
        val lexicon = lexicon ?: return
        listOf("emoji", "emojis", "github").forEach {
            assertNotNull("$it is missing", lexicon.logProbability(it))
        }
    }

    @Test
    fun `long words past the head of the crawl are in the lexicon`() {
        val lexicon = lexicon ?: return
        listOf(
            "homophone", "homophones", "heteronym", "heteronyms", "homonym", "palindrome",
            "onomatopoeia", "alliteration", "euphemism", "preposition",
        ).forEach { assertNotNull("$it is missing", lexicon.logProbability(it)) }
    }

    /**
     * web2 is a 1934 dictionary, so it has "Chinese" but not "Taiwanese", and the dictionary
     * check past rank 15,000 dropped every demonym of a country it predates. Scored from the
     * crawl, "taiwanese" should sit among the common words, not at the floor.
     */
    @Test
    fun `nationality words web2 predates are in the lexicon`() {
        val lexicon = lexicon ?: return
        listOf(
            "taiwanese", "hakka", "hokkien", "bangladeshi", "singaporean", "kazakh", "zimbabwean",
            "syrians", "nigerians",
        ).forEach { assertNotNull("$it is missing", lexicon.logProbability(it)) }
        val taiwanese = lexicon.logProbability("taiwanese")!!
        val chinese = lexicon.logProbability("chinese")!!
        assertTrue("taiwanese $taiwanese vs chinese $chinese", chinese - taiwanese < 5f)
    }

    /**
     * The deep pass admits long words only; a short one would hide inside longer gestures, which
     * is the whole reason the short-word limits exist. And its second-corpus check keeps out the
     * misspellings the suffix rules would otherwise read as inflections.
     */
    @Test
    fun `the deep pass admits no rare short words and no misspellings`() {
        val lexicon = lexicon ?: return
        listOf(
            "sew", "rye", "wud", "switchs", "happenned", "writting", "openning", "softwared",
            "logined",
        ).forEach {
            assertEquals("$it should not be here", null, lexicon.logProbability(it))
        }
    }

    @Test
    fun `the fingerprint follows the contents`() {
        val a = Lexicon.of("keyboard" to 500, "glide" to 400)
        assertEquals(a.fingerprint, Lexicon.of("keyboard" to 500, "glide" to 400).fingerprint)
        assertNotEquals(a.fingerprint, Lexicon.of("keyboard" to 500, "glide" to 401).fingerprint)
        assertNotEquals(a.fingerprint, Lexicon.of("keyboard" to 500).fingerprint)
        assertNotEquals(
            a.fingerprint,
            Lexicon.of("keyboard" to 500, "glide" to 400, "cannot" to 623).fingerprint,
        )
    }
}
