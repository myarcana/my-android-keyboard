package com.offlinekeyboard.ime.glide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideSpacingTest {

    @Test
    fun `a letter right after a glided word starts a new word`() {
        assertTrue(GlideSpacing.needsSpace("a", glideEnd = 5, caret = 5, before = 'o'))
        assertTrue(GlideSpacing.needsSpace("Q", glideEnd = 5, caret = 5, before = 'o'))
        assertTrue(GlideSpacing.needsSpace("é", glideEnd = 5, caret = 5, before = 'o'))
    }

    @Test
    fun `punctuation and digits go against the word`() {
        listOf(".", ",", "?", "!", "'", "-", "1", " ").forEach {
            assertFalse(it, GlideSpacing.needsSpace(it, glideEnd = 5, caret = 5, before = 'o'))
        }
    }

    @Test
    fun `no second space when one is already there`() {
        assertFalse(GlideSpacing.needsSpace("a", glideEnd = 5, caret = 5, before = ' '))
        assertFalse(GlideSpacing.needsSpace("a", glideEnd = 5, caret = 5, before = null))
    }

    @Test
    fun `a caret that has moved since the glide takes no space`() {
        assertFalse(GlideSpacing.needsSpace("a", glideEnd = 5, caret = 3, before = 'l'))
    }

    @Test
    fun `an unknown caret still gets the space`() {
        assertTrue(GlideSpacing.needsSpace("a", glideEnd = -1, caret = 5, before = 'o'))
        assertTrue(GlideSpacing.needsSpace("a", glideEnd = 5, caret = -1, before = 'o'))
    }
}
