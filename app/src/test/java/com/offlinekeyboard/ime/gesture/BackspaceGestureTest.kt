package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Requirement 11: hold backspace to repeat, swipe to delete in bulk.
 *
 * Up clears the line before the cursor, down removes the word before it. The two are one
 * gesture read by the sign of its vertical travel, so they are tested together and mostly in
 * pairs: anything true of one direction -- fires once, does not also type a character on lift,
 * stops a running repeat first -- has to be true of the other.
 */
class BackspaceGestureTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
    private val config = GestureConfig()

    private fun fsm() = TouchFsm(geometry, config)
    private fun key(id: String): KeyRect = geometry.keyRects.first { it.key.id == id }

    private inline fun <reified T> List<GestureOutput>.has(): Boolean = any { it is T }

    /** Far enough up to cross the threshold, whatever it is tuned to. */
    private val swipeUp get() = -config.bulkDeleteDistanceRatio * geometry.keyHeight - 1f

    /** The mirror of [swipeUp], the same distance the other way. */
    private val swipeDown get() = config.bulkDeleteDistanceRatio * geometry.keyHeight + 1f

    @Test
    fun `a tap still deletes one character`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        val out = f.onUp(b.centerX, b.centerY, 80)
        assertEquals(KeyType.BACKSPACE, out.filterIsInstance<GestureOutput.SpecialKey>().single().type)
    }

    @Test
    fun `holding starts the repeat`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        assertTrue(f.onLongPressTimeout(config.longPressMs).has<GestureOutput.BackspaceRepeatStarted>())
        assertEquals(GestureState.BACKSPACE, f.state)
    }

    @Test
    fun `releasing a hold stops the repeat and does not delete again`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)
        val out = f.onUp(b.centerX, b.centerY, 900)
        assertTrue(out.has<GestureOutput.BackspaceRepeatEnded>())
        assertFalse(out.has<GestureOutput.SpecialKey>())
    }

    @Test
    fun `a cancelled hold still stops the repeat`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)
        assertTrue(f.onCancel().has<GestureOutput.BackspaceRepeatEnded>())
    }

    @Test
    fun `swiping up after the hold clears the line, and stops the repeat first`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)
        val out = f.onMove(b.centerX, b.centerY + swipeUp, 700)
        assertTrue(out.has<GestureOutput.DeleteLine>())
        assertTrue(out.has<GestureOutput.BackspaceRepeatEnded>())
    }

    @Test
    fun `swiping down after the hold deletes a word, and stops the repeat first`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)
        val out = f.onMove(b.centerX, b.centerY + swipeDown, 700)
        assertTrue(out.has<GestureOutput.BulkDelete>())
        assertTrue(out.has<GestureOutput.BackspaceRepeatEnded>())
    }

    @Test
    fun `swiping up without holding first clears the line too`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        assertTrue(f.onMove(b.centerX, b.centerY + swipeUp, 60).has<GestureOutput.DeleteLine>())
    }

    @Test
    fun `swiping down without holding first deletes a word too`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        assertTrue(f.onMove(b.centerX, b.centerY + swipeDown, 60).has<GestureOutput.BulkDelete>())
    }

    /** Up is the line and down is the word, never the other way round. */
    @Test
    fun `each direction means only its own delete`() {
        val b = key("backspace")
        val up = fsm().also { it.onDown(b.centerX, b.centerY, 0) }
            .onMove(b.centerX, b.centerY + swipeUp, 60)
        assertFalse(up.has<GestureOutput.BulkDelete>())

        val down = fsm().also { it.onDown(b.centerX, b.centerY, 0) }
            .onMove(b.centerX, b.centerY + swipeDown, 60)
        assertFalse(down.has<GestureOutput.DeleteLine>())
    }

    @Test
    fun `the delete happens once per gesture, however much the finger wobbles`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        f.onMove(b.centerX, b.centerY + swipeUp, 60)
        val again = f.onMove(b.centerX, b.centerY, 90) + f.onMove(b.centerX, b.centerY + swipeUp, 120)
        assertFalse(again.has<GestureOutput.DeleteLine>())
    }

    /**
     * A finger that crosses up and then swings down past the threshold must not delete twice,
     * and must not turn one gesture into both deletes. SPENT is what guarantees it.
     */
    @Test
    fun `reversing direction after a delete does not fire the other one`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        f.onMove(b.centerX, b.centerY + swipeUp, 60)
        val reversed = f.onMove(b.centerX, b.centerY + swipeDown, 120)
        assertFalse(reversed.has<GestureOutput.BulkDelete>())
        assertFalse(reversed.has<GestureOutput.DeleteLine>())
    }

    @Test
    fun `lifting after a line delete does not also delete a character`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        f.onMove(b.centerX, b.centerY + swipeUp, 60)
        assertFalse(f.onUp(b.centerX, b.centerY + swipeUp, 200).has<GestureOutput.SpecialKey>())
    }

    @Test
    fun `lifting after a word delete does not also delete a character`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        f.onMove(b.centerX, b.centerY + swipeDown, 60)
        assertFalse(f.onUp(b.centerX, b.centerY + swipeDown, 200).has<GestureOutput.SpecialKey>())
    }

    /** The threshold guards both directions: a drift either way is still just a tap. */
    @Test
    fun `a small drift off the key does not delete anything`() {
        val b = key("backspace")
        for (dy in listOf(-0.3f, 0.3f)) {
            val f = fsm()
            f.onDown(b.centerX, b.centerY, 0)
            val out = f.onMove(b.centerX + 3f, b.centerY + geometry.keyHeight * dy, 40)
            assertFalse(out.has<GestureOutput.BulkDelete>())
            assertFalse(out.has<GestureOutput.DeleteLine>())
        }
    }

    @Test
    fun `a swipe off a modifier key is never a glide`() {
        val shift = key("shift")
        val f = fsm()
        f.onDown(shift.centerX, shift.centerY, 0)
        val out = f.onMove(shift.centerX + geometry.keyUnit * 2f, shift.centerY, 60)
        assertFalse(out.has<GestureOutput.GlideStarted>())
    }
}
