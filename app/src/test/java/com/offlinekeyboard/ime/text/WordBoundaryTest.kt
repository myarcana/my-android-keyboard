package com.offlinekeyboard.ime.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Swiping down on backspace deletes the word before the cursor; swiping up takes the line.
 *
 * Each case is written as the text before the cursor and what should survive the delete, since
 * that is the thing a typist actually sees.
 */
class WordBoundaryTest {

    /** Applies one word-delete to [before] and returns what is left. */
    private fun afterDelete(before: String): String =
        before.dropLast(WordBoundary.deleteLength(before))

    /** Applies one line-delete to [before] and returns what is left. */
    private fun afterLineDelete(before: String): String =
        before.dropLast(WordBoundary.lineDeleteLength(before))

    @Test
    fun `deletes the word before the cursor`() {
        assertEquals("hello ", afterDelete("hello world"))
    }

    @Test
    fun `takes the trailing space with the word, leaving the one before it`() {
        // "world " goes entirely, so typing the next word does not need a space typed first.
        assertEquals("hello ", afterDelete("hello world "))
    }

    @Test
    fun `mid-word deletes only back to the start of that word`() {
        assertEquals("hello ", afterDelete("hello wor"))
    }

    @Test
    fun `a single word leaves the field empty`() {
        assertEquals("", afterDelete("hello"))
    }

    @Test
    fun `repeating the gesture walks back a word at a time`() {
        var text = "one two three"
        text = afterDelete(text)
        assertEquals("one two ", text)
        text = afterDelete(text)
        assertEquals("one ", text)
        text = afterDelete(text)
        assertEquals("", text)
    }

    @Test
    fun `punctuation attached to a word goes with it`() {
        assertEquals("well ", afterDelete("well done!"))
    }

    /**
     * The spaces *behind* the deleted word are left alone: they were typed deliberately and are
     * not part of the word, so the delete stops as soon as it has taken the word and the gap it
     * was sitting in.
     */
    @Test
    fun `a run of several spaces is consumed along with the word`() {
        assertEquals("hello   ", afterDelete("hello   world   "))
    }

    /**
     * The gesture must not join a line to the one above it. Structure the user did not ask to
     * remove is the one thing here that retyping the word does not put back.
     */
    @Test
    fun `stops at a line break rather than eating the line above`() {
        assertEquals("first\n", afterDelete("first\nsecond"))
    }

    @Test
    fun `at the start of a line there is no word to take`() {
        assertEquals(0, WordBoundary.deleteLength("first\n"))
    }

    @Test
    fun `empty text deletes nothing`() {
        assertEquals(0, WordBoundary.deleteLength(""))
    }

    /**
     * Emoji are counted in UTF-16 code units because that is what `deleteSurroundingText`
     * counts; a four-unit emoji is one "word" and must go whole.
     */
    @Test
    fun `an emoji word is removed completely`() {
        assertEquals("hi ", afterDelete("hi 👍🏽"))
    }

    // --- line delete ----------------------------------------------------------------------

    @Test
    fun `a line delete takes every word on the line`() {
        assertEquals("", afterLineDelete("hello world again"))
    }

    /**
     * The newline survives. Taking it too would pull the cursor onto the line above and join
     * two lines the user never asked to join -- the same structure [deleteLength] protects.
     */
    @Test
    fun `a line delete stops at the break without crossing it`() {
        assertEquals("first\n", afterLineDelete("first\nsecond third"))
    }

    @Test
    fun `only the last line goes, however many are behind it`() {
        assertEquals("one\ntwo\n", afterLineDelete("one\ntwo\nthree"))
    }

    /** Leading spaces are part of the line: clearing it leaves nothing but the break. */
    @Test
    fun `indentation on the line is cleared with it`() {
        assertEquals("first\n", afterLineDelete("first\n    indented"))
    }

    @Test
    fun `trailing spaces are part of the line too`() {
        assertEquals("", afterLineDelete("hello world   "))
    }

    /** Nothing on the line means nothing to clear; the break above is not the line's to take. */
    @Test
    fun `an empty line deletes nothing`() {
        assertEquals(0, WordBoundary.lineDeleteLength("first\n"))
    }

    @Test
    fun `empty text has no line to delete`() {
        assertEquals(0, WordBoundary.lineDeleteLength(""))
    }
}
