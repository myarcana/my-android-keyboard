package com.offlinekeyboard.ime.tap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** That a re-reading changes which letter a tap was without disturbing anything else about it. */
class PendingWordTest {

    private fun word(literals: String, upper: Set<Int> = emptySet()) = PendingWord().apply {
        literals.forEachIndexed { i, c -> add(i.toFloat(), 0f, c, upper = i in upper) }
    }

    @Test
    fun `the literal reading is what was tapped`() {
        assertEquals("rhe", word("rhe").textFor(null))
    }

    /**
     * Case belongs to the tap, not to the reading. "Rhys" is capitalised because shift was down
     * for its first keypress, and it stays capitalised through a re-reading of any of its
     * letters -- the decoder only ever works in lowercase.
     */
    @Test
    fun `case survives a re-reading`() {
        val pending = word("rhys", upper = setOf(0))
        assertEquals("Rhys", pending.textFor(null))
        assertEquals("Thys", pending.textFor("thys"))
    }

    /**
     * A reading of the wrong length is not a reading of these taps. [TapDecoder] guarantees it
     * cannot happen; this is what the guarantee costs to check.
     */
    @Test
    fun `a reading of the wrong length is ignored`() {
        val pending = word("rhe")
        assertEquals("rhe", pending.textFor("there"))
        assertEquals("rhe", pending.textFor("th"))
    }

    @Test
    fun `unchanged text is recognised so the field is not written to twice`() {
        val pending = word("the")
        assertTrue(pending.hasChanged("the"))
        pending.markShown("the")
        assertFalse(pending.hasChanged("the"))
        assertTrue(pending.hasChanged("thf"))
    }

    @Test
    fun `clearing forgets the taps and what was on screen`() {
        val pending = word("the")
        pending.markShown("the")
        pending.clear()
        assertTrue(pending.isEmpty)
        assertEquals(0, pending.length)
        assertTrue(pending.hasChanged("the"))
    }
}
