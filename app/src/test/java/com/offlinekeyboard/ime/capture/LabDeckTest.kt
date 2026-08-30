package com.offlinekeyboard.ime.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules the deck exists to enforce, checked on the pure form of the deal.
 *
 * Both are the kind that fail silently. A deal that repeats a passage within a pass wastes a
 * session on words the thumb typed yesterday; a deal that starves the collision drill leaves the
 * bank growing steadily while the one question it was built to answer gains no new evidence at
 * all -- and the total at the bottom of the screen goes up either way.
 */
class LabDeckTest {

    private val seed = 20260831L

    @Test
    fun `a pass deals every passage in the library exactly once`() {
        val slots = LabDeck.slots(librarySize = 47, seed = seed, pass = 0)
        val dealt = slots.filter { it != LabDeck.DRILL }.sorted()
        assertEquals((0 until 47).toList(), dealt)
    }

    @Test
    fun `the drill is never more than DRILL_EVERY passages away`() {
        val slots = LabDeck.slots(librarySize = 47, seed = seed, pass = 3)
        var since = 0
        slots.forEach { slot ->
            if (slot == LabDeck.DRILL) {
                since = 0
            } else {
                since++
                assertTrue("$since passages since the last drill", since <= LabDeck.DRILL_EVERY)
            }
        }
        assertEquals("a pass must open on the drill", LabDeck.DRILL, slots.first())
    }

    @Test
    fun `each pass deals a different order`() {
        val first = LabDeck.slots(librarySize = 47, seed = seed, pass = 0)
        val second = LabDeck.slots(librarySize = 47, seed = seed, pass = 1)
        assertTrue("the second pass repeats the first in order", first != second)
    }

    /** An empty library still has to give the lab something to show. */
    @Test
    fun `an empty library deals the drill`() {
        assertEquals(listOf(LabDeck.DRILL), LabDeck.slots(librarySize = 0, seed = seed, pass = 0))
    }

    // --- the streak ---------------------------------------------------------------------------

    @Test
    fun `a gesture the next day extends the run and resets the day`() {
        assertEquals(6 to 0, LabProgress.rollover(stored = 10L, today = 11L, streak = 5, count = 90))
    }

    @Test
    fun `a gap of more than a day starts the run again at one`() {
        assertEquals(1 to 0, LabProgress.rollover(stored = 10L, today = 14L, streak = 5, count = 90))
    }

    @Test
    fun `a gesture on the same day changes nothing`() {
        assertEquals(5 to 90, LabProgress.rollover(stored = 10L, today = 10L, streak = 5, count = 90))
    }

    /** The very first gesture ever recorded is a one day run, not a zero day one. */
    @Test
    fun `the first gesture starts a run`() {
        assertEquals(1 to 0, LabProgress.rollover(stored = 0L, today = 20_000L, streak = 0, count = 0))
    }
}
