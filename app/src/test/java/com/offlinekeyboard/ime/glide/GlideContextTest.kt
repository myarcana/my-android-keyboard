package com.offlinekeyboard.ime.glide

import org.junit.Assert.assertEquals
import org.junit.Test

class GlideContextTest {

    @Test
    fun `the words before the caret, oldest first`() {
        assertEquals(listOf("the", "weather", "is", "so"), GlideContext.words("the weather is so"))
        // A glide puts its space in front of the word, so the caret usually sits against a letter;
        // a trailing space must read the same.
        assertEquals(listOf("the", "weather", "is", "so"), GlideContext.words("the weather is so "))
    }

    @Test
    fun `an empty field is a sentence start`() {
        assertEquals(emptyList<String>(), GlideContext.words(""))
        assertEquals(emptyList<String>(), GlideContext.words("   "))
    }

    @Test
    fun `stops at the end of the previous sentence`() {
        assertEquals(listOf("it", "is"), GlideContext.words("Hello there. it is"))
        assertEquals(emptyList<String>(), GlideContext.words("Really? "))
        assertEquals(listOf("so"), GlideContext.words("Wow!\nso"))
        assertEquals(listOf("and"), GlideContext.words("first line\nand"))
    }

    @Test
    fun `punctuation inside the sentence is not a word`() {
        assertEquals(
            listOf("well", "I", "said", "hi", "there"),
            GlideContext.words("well, I said \"hi\" (there"),
        )
    }

    @Test
    fun `apostrophes inside a word keep it whole`() {
        assertEquals(listOf("I'm", "so"), GlideContext.words("I'm so"))
        // The typographic apostrophe is spelled the way the model's vocabulary spells it.
        assertEquals(listOf("don't", "be"), GlideContext.words("don\u2019t be"))
        // A quote around a word is not part of it.
        assertEquals(listOf("so", "good"), GlideContext.words("'so good'"))
    }

    @Test
    fun `a word cut off by the lookbehind window is dropped`() {
        // Exactly the window's length, as getTextBeforeCursor returns it when there is more text.
        val long = "x".repeat(GlideContext.LOOKBEHIND - 10) + "ther is so"
        assertEquals(listOf("is", "so"), GlideContext.words(long))
    }

    @Test
    fun `keeps only the most recent words`() {
        val many = (1..30).joinToString(" ") { "w$it" }
        val words = GlideContext.words(many)
        assertEquals(GlideContext.MAX_WORDS, words.size)
        assertEquals("w30", words.last())
    }
}
