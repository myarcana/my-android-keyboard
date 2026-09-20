package com.offlinekeyboard.ime.glide

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a glided form comes back spelled the way it is written.
 *
 * The bug this pins: gliding `d-o-n-t` typed "dont". Every decoder answers in the alphabet it
 * traversed, and the apostrophe is not on the path -- so without [Lexicon.spellings] putting it
 * back, the keyboard committed a word that is not English and that no one could have been aiming
 * for, since "dont" is not in the dictionary at all.
 */
class LexiconSpellingTest {

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

    /**
     * The reported case, and the rest of the contractions that have only one spelling. These are
     * the unambiguous half: there is no bare "dont" to choose against, so restoring the
     * apostrophe cannot cost anything.
     */
    @Test
    fun `a contraction with one spelling is spelled with its apostrophe`() {
        val lexicon = lexicon ?: return
        listOf(
            "dont" to "don't",
            "wheres" to "where's",
            "cant" to "can't",
            "im" to "I'm",
            "thats" to "that's",
            "youre" to "you're",
            "whats" to "what's",
        ).forEach { (glided, spelled) ->
            assertEquals(glided, listOf(spelled), lexicon.spellings(glided))
        }
    }

    /**
     * The other half. "its" and "it's" are both words, so the decode has to offer both rather
     * than silently picking -- and the more frequent one leads, because that is the one the
     * keyboard commits when the user just glides and keeps going.
     */
    @Test
    fun `a form that is two real words offers both, likeliest first`() {
        val lexicon = lexicon ?: return
        assertEquals(listOf("it's", "its"), lexicon.spellings("its"))
        assertEquals(listOf("were", "we're"), lexicon.spellings("were"))
        assertEquals(listOf("well", "we'll"), lexicon.spellings("well"))
    }

    /** An ordinary word is returned unchanged, without needing an entry of its own. */
    @Test
    fun `an ordinary word is its own spelling`() {
        val lexicon = lexicon ?: return
        listOf("keyboard", "the", "glide", "zebra").forEach {
            assertEquals(listOf(it), lexicon.spellings(it))
        }
    }

    /**
     * The invariant behind the whole mechanism: whatever a decoder returns, it returns a glided
     * form, and every glided form in the lexicon must map to at least one real spelling. A form
     * answering with something that is not a word in the lexicon would be the original bug.
     */
    @Test
    fun `every spelling offered is a word in the lexicon`() {
        val lexicon = lexicon ?: return
        val known = lexicon.words.toHashSet()
        var checked = 0
        lexicon.letters.forEach { glided ->
            lexicon.spellings(glided).forEach { spelling ->
                assertTrue("$glided -> $spelling is not in the lexicon", spelling in known)
                checked++
            }
        }
        assertTrue(checked >= lexicon.size)
    }

    /**
     * A spelling is only ever offered for the form it is actually glided as. Stripping to a-z is
     * what the decoder does to the path, so it is what the mapping has to agree with.
     */
    @Test
    fun `a spelling glides to the form it was offered for`() {
        val lexicon = lexicon ?: return
        lexicon.letters.toHashSet().forEach { glided ->
            lexicon.spellings(glided).forEach { spelling ->
                assertEquals(glided, spelling.lowercase().filter { it in 'a'..'z' })
            }
        }
    }

    /** The mapping is built from the pair the lexicon already stores, so a tiny one works too. */
    @Test
    fun `spellings come from the lexicon it was built from`() {
        val small = Lexicon.of("don't" to 730, "its" to 872, "it's" to 876, "keyboard" to 500)
        assertEquals(listOf("don't"), small.spellings("dont"))
        assertEquals(listOf("it's", "its"), small.spellings("its"))
        assertEquals(listOf("keyboard"), small.spellings("keyboard"))
    }
}
