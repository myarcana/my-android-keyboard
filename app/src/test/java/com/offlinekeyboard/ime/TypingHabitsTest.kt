package com.offlinekeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plane-return rule, stated as the sentences a thumb would use if it could talk.
 *
 * The negative cases carry more weight than the positive ones here: the whole value of the rule
 * is that it fires on exactly two characters and leaves the rest of the punctuation row alone.
 */
class TypingHabitsTest {

    @Test
    fun `space goes back to the letters`() {
        assertTrue(TypingHabits.returnsToLetters(" "))
    }

    @Test
    fun `apostrophe goes back to the letters`() {
        assertTrue(TypingHabits.returnsToLetters("'"))
    }

    /**
     * The whole point of the punctuation row staying put: a price, a phone number or a decimal
     * is typed without the plane flickering underneath the thumb.
     */
    @Test
    fun `the punctuation row does not go back to the letters`() {
        listOf(".", ",", "?", "!", "-", "/", ":", ";", "$", "%", "&", "@").forEach {
            assertFalse("$it should stay on the symbol plane", TypingHabits.returnsToLetters(it))
        }
    }

    /**
     * A quote opens a passage that may well start with a digit, and its closing half follows
     * text that has already taken the plane wherever it needed to go.
     */
    @Test
    fun `the double quote does not go back to the letters`() {
        assertFalse(TypingHabits.returnsToLetters("\""))
    }

    @Test
    fun `digits do not go back to the letters`() {
        ('0'..'9').forEach { assertFalse(TypingHabits.returnsToLetters(it.toString())) }
    }

    /** Typing a letter is not a reason to switch planes; it means we are already on them. */
    @Test
    fun `letters do not trigger a switch`() {
        assertFalse(TypingHabits.returnsToLetters("a"))
        assertFalse(TypingHabits.returnsToLetters("Z"))
    }
}
