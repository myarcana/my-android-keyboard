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

    // --- swipe down: a word and whatever trails it -----------------------------------------

    /** Applies one swipe-down delete to [before] and returns what is left. */
    private fun afterFlick(before: String): String =
        before.dropLast(WordBoundary.flickDeleteLength(before))

    @Test
    fun `a flick deletes the word before the cursor`() {
        assertEquals("hello ", afterFlick("hello world"))
        assertEquals("hello ", afterFlick("hello wor"))
        assertEquals("", afterFlick("hello"))
    }

    /** With the cursor after the period, "hello." is one unit, not a "." and then a "hello". */
    @Test
    fun `one punctuation mark after a word goes with it`() {
        assertEquals("", afterFlick("hello."))
        assertEquals("well ", afterFlick("well done!"))
        assertEquals("he said \"", afterFlick("he said \"hi\""))
    }

    /** With the cursor after the space, "hello " is one unit too. */
    @Test
    fun `one space after a word goes with it`() {
        assertEquals("", afterFlick("hello "))
        assertEquals("hello ", afterFlick("hello world "))
    }

    @Test
    fun `one punctuation mark and then one space after a word all go with it`() {
        assertEquals("", afterFlick("hello. "))
        assertEquals("One. ", afterFlick("One. Two. "))
        assertEquals("one ", afterFlick("one two, "))
    }

    /** A run of spaces was typed on purpose, so it goes by itself and the word survives. */
    @Test
    fun `a run of spaces goes by itself`() {
        assertEquals("One Two", afterFlick("One Two    "))
        assertEquals("hello world", afterFlick("hello world  "))
        assertEquals("One. Two.", afterFlick("One. Two.  "))
    }

    /** Likewise an ellipsis or "?!" is its own unit, not an ending of the word before it. */
    @Test
    fun `a run of punctuation goes by itself`() {
        assertEquals("Okay", afterFlick("Okay..."))
        assertEquals("really really", afterFlick("really really?!"))
        // One space after the run goes with the run; two are a run of their own.
        assertEquals("wait", afterFlick("wait... "))
        assertEquals("wait...", afterFlick("wait...  "))
        assertEquals("", afterFlick("..."))
    }

    @Test
    fun `a word-internal joiner before trailing punctuation stays in the word`() {
        assertEquals("see ", afterFlick("see e.g."))
        assertEquals("pi is ", afterFlick("pi is 3.14."))
    }

    @Test
    fun `punctuation with no word behind it goes by itself`() {
        assertEquals("", afterFlick("..."))
        assertEquals("a ", afterFlick("a - "))
        assertEquals("", afterFlick("   "))
    }

    @Test
    fun `punctuation ends a word`() {
        assertEquals("hello-", afterFlick("hello-world"))
        assertEquals("(", afterFlick("(aside"))
        assertEquals("\"", afterFlick("\"quoted"))
        assertEquals("one,", afterFlick("one,two"))
    }

    @Test
    fun `word-internal punctuation stays inside the word`() {
        assertEquals("I ", afterFlick("I don't"))
        assertEquals("pi is ", afterFlick("pi is 3.14"))
        assertEquals("x = ", afterFlick("x = foo_bar"))
    }

    @Test
    fun `repeated flicks walk back one word at a time`() {
        var text = "Hi, there! How are you?"
        text = afterFlick(text); assertEquals("Hi, there! How are ", text)
        text = afterFlick(text); assertEquals("Hi, there! How ", text)
        text = afterFlick(text); assertEquals("Hi, there! ", text)
        text = afterFlick(text); assertEquals("Hi, ", text)
        text = afterFlick(text); assertEquals("", text)
    }

    @Test
    fun `a flick never crosses a hard line break`() {
        assertEquals("first\n", afterFlick("first\nsecond"))
        assertEquals("first\n", afterFlick("first\n   "))
        assertEquals("first.\n", afterFlick("first.\nsecond. "))
        assertEquals(0, WordBoundary.flickDeleteLength("first\n"))
        assertEquals(0, WordBoundary.flickDeleteLength(""))
    }

    @Test
    fun `a symbol or emoji goes one visible character at a time`() {
        assertEquals("hi ", afterFlick("hi 👍🏽"))
        assertEquals("hi 👍🏽", afterFlick("hi 👍🏽👍🏽"))
        assertEquals("1 ", afterFlick("1 +"))
        assertEquals("cost", afterFlick("cost$"))
        assertEquals("hi ", afterFlick("hi 👍🏽! "))
    }

    @Test
    fun `an accented letter built from a combining mark is still part of the word`() {
        assertEquals("a ", afterFlick("a cafe\u0301"))
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
