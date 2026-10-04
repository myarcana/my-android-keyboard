package com.offlinekeyboard.ime.asr

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The space, or not, between what is in the field and the dictated segment that follows it.
 *
 * Each case is written as the text before the caret and the field after the commit, since that
 * is what the speaker sees.
 */
class DictationSpacingTest {

    private fun dictate(before: String, text: String): String =
        before + (if (DictationSpacing.needsSpace(before, text)) " " else "") + text

    @Test
    fun `a segment after a word is separated from it`() {
        assertEquals("hello world", dictate("hello", "world"))
        assertEquals("ok, then", dictate("ok,", "then"))
        assertEquals("done. Next", dictate("done.", "Next"))
    }

    @Test
    fun `nothing is added at the start of the field or after whitespace`() {
        assertEquals("hello", dictate("", "hello"))
        assertEquals("hello world", dictate("hello ", "world"))
        assertEquals("line\nnext", dictate("line\n", "next"))
    }

    @Test
    fun `an opening bracket keeps the dictated word against it`() {
        assertEquals("see (this", dictate("see (", "this"))
        assertEquals("(this", dictate("(", "this"))
        assertEquals("a [note", dictate("a [", "note"))
        assertEquals("x {y", dictate("x {", "y"))
    }

    @Test
    fun `an opening quote keeps the dictated word against it`() {
        assertEquals("said \u201chello", dictate("said \u201c", "hello"))
        assertEquals("said \u2018hello", dictate("said \u2018", "hello"))
        assertEquals("dit \u00abbonjour", dictate("dit \u00ab", "bonjour"))
    }

    @Test
    fun `a straight quote opens after a space and closes after a word`() {
        assertEquals("say \"hello", dictate("say \"", "hello"))
        assertEquals("\"hello", dictate("\"", "hello"))
        assertEquals("(\"hello", dictate("(\"", "hello"))
        assertEquals("he said \"hi\" then", dictate("he said \"hi\"", "then"))
        assertEquals("the dogs' bowls", dictate("the dogs'", "bowls"))
    }

    @Test
    fun `a closing bracket is followed by a space like a word is`() {
        assertEquals("(aside) then", dictate("(aside)", "then"))
    }

    @Test
    fun `full-width openers take no space either`() {
        assertEquals("（hello", dictate("（", "hello"))
        assertEquals("「hello", dictate("「", "hello"))
        assertEquals("【hello", dictate("【", "hello"))
    }

    @Test
    fun `Han beside Han takes no space, Latin beside Han takes one`() {
        assertEquals("今天下午", dictate("今天", "下午"))
        assertEquals("the best 豆花", dictate("the best", "豆花"))
        assertEquals("豆花 is good", dictate("豆花", "is good"))
        assertEquals("好。then", dictate("好。", "then"))
    }

    @Test
    fun `a segment starting with punctuation goes against the text`() {
        assertEquals("hello,", dictate("hello", ","))
    }
}
