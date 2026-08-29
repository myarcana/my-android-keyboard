package com.offlinekeyboard.ime.candidates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedWordTest {

    private fun queries(before: String) = TypedWord.endingAt(before).map { it.query }

    @Test
    fun `the word at the caret is offered`() {
        assertEquals(listOf("want pizza", "pizza"), queries("i want pizza"))
        assertEquals(listOf("pizza"), queries("pizza"))
    }

    @Test
    fun `the two-word form comes first so the longer name can win`() {
        assertEquals(listOf("thumbs up", "up"), queries("thumbs up"))
    }

    @Test
    fun `length is what a chosen emoji replaces`() {
        val two = TypedWord.endingAt("nice thumbs up").first()
        assertEquals("thumbs up", two.query)
        assertEquals("thumbs up".length, two.length)
    }

    @Test
    fun `a finished word is not a query`() {
        assertTrue(queries("pizza ").isEmpty())
        assertTrue(queries("pizza.").isEmpty())
        assertTrue(queries("").isEmpty())
    }

    @Test
    fun `punctuation before the word does not join to it`() {
        assertEquals(listOf("pizza"), queries("(pizza"))
        assertEquals(listOf("pizza"), queries("eat,pizza"))
    }

    @Test
    fun `a hyphen or apostrophe is part of the word`() {
        assertEquals(listOf("a t-shirt", "t-shirt"), queries("a t-shirt"))
        assertTrue(queries("o'clock").contains("o'clock"))
    }

    @Test
    fun `a line break ends the two-word form`() {
        assertEquals(listOf("pizza"), queries("hot\npizza"))
    }
}
