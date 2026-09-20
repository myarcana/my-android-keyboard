package com.offlinekeyboard.ime.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One backspace removes one visible character.
 *
 * Each case names the number of UTF-16 code units the cluster occupies, because that number --
 * not 1 -- is what `deleteSurroundingText` has to be given. The bug these guard against was a
 * hardcoded 1, which is right only for the first block of cases.
 */
class GraphemeClusterTest {

    private fun assertLastCluster(expected: Int, text: String) {
        assertEquals("cluster length of ${describe(text)}", expected, GraphemeCluster.lastClusterLength(text))
    }

    /** Code points as hex, so a failure message says which characters were involved. */
    private fun describe(text: String) =
        text.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }

    // --- the cases the old code already got right ------------------------------------------

    @Test
    fun `empty text deletes nothing`() {
        assertLastCluster(0, "")
    }

    @Test
    fun `ascii is one unit`() {
        assertLastCluster(1, "a")
        assertLastCluster(1, "hello")
        assertLastCluster(1, "hi there")
    }

    @Test
    fun `non-ascii letters that fit in one unit stay one unit`() {
        assertLastCluster(1, "café")
        assertLastCluster(1, "日本")
    }

    // --- surrogate pairs ---------------------------------------------------------------------

    @Test
    fun `plain emoji is a surrogate pair`() {
        // U+1F600. Deleting 1 left a lone low surrogate, which renders as tofu.
        assertLastCluster(2, "😀")
        assertLastCluster(2, "hi 😀")
    }

    @Test
    fun `only the last emoji goes`() {
        assertLastCluster(2, "😀😀")
    }

    // --- skin tone modifiers -----------------------------------------------------------------

    @Test
    fun `skin tone binds to the person in front of it`() {
        // U+1F44D U+1F3FD: thumbs up, medium skin tone. Deleting 2 stripped the tone and left
        // the thumb, so the press looked like it changed the emoji rather than removing it.
        assertLastCluster(4, "👍🏽")
        assertLastCluster(4, "nice 👍🏽")
    }

    // --- zero-width joiner sequences ----------------------------------------------------------

    @Test
    fun `zwj family is one character`() {
        // U+1F468 ZWJ U+1F469 ZWJ U+1F467: man, woman, girl. Eight units.
        assertLastCluster(8, "👨‍👩‍👧")
    }

    @Test
    fun `longer zwj family is still one character`() {
        // Four people joined: man, woman, girl, boy.
        assertLastCluster(11, "👨‍👩‍👧‍👦")
    }

    @Test
    fun `zwj sequence with a skin tone`() {
        // U+1F469 U+1F3FD ZWJ U+1F4BB: woman, medium skin tone, laptop -- "woman technologist".
        assertLastCluster(7, "👩🏽‍💻")
    }

    @Test
    fun `text before a zwj sequence is untouched`() {
        assertLastCluster(8, "family: 👨‍👩‍👧")
    }

    // --- flags ---------------------------------------------------------------------------------

    @Test
    fun `flag is a pair of regional indicators`() {
        // U+1F1EF U+1F1F5. Deleting 2 turned the flag into a lone letter J.
        assertLastCluster(4, "🇯🇵")
    }

    @Test
    fun `two flags give up one flag per press`() {
        // The run is four indicators; only the last pair may go, or both flags would vanish.
        assertLastCluster(4, "🇯🇵🇫🇷")
    }

    @Test
    fun `a lone regional indicator is its own character`() {
        // An odd run: the trailing indicator is not part of a complete flag.
        assertLastCluster(2, "🇯🇵🇫")
    }

    // --- variation selectors and keycaps --------------------------------------------------------

    @Test
    fun `variation selector stays with its base character`() {
        // U+2764 U+FE0F: heart forced to emoji presentation.
        assertLastCluster(2, "❤️")
    }

    @Test
    fun `keycap is one character`() {
        // U+0031 U+FE0F U+20E3: the digit one as a key.
        assertLastCluster(3, "1️⃣")
    }

    // --- combining marks ------------------------------------------------------------------------

    @Test
    fun `combining accent goes with the letter it sits on`() {
        // "e" followed by U+0301. Deleting 1 left a bare "e", which looked like nothing happened.
        assertLastCluster(2, "e\u0301")
    }

    // --- degenerate input -------------------------------------------------------------------------

    @Test
    fun `a stray joiner deletes only itself`() {
        // Nothing in front to join, so the joiner is the whole cluster rather than eating text.
        assertLastCluster(1, "\u200D")
    }

    @Test
    fun `a lone low surrogate does not read past the start`() {
        assertLastCluster(1, "\uDE00")
    }

    @Test
    fun `never reports more than the text holds`() {
        val text = "👨‍👩‍👧"
        assert(GraphemeCluster.lastClusterLength(text) <= text.length)
    }
}
