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

/** Requirement 11: hold backspace to repeat, swipe up to clear the line. */
class BackspaceGestureTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
    private val config = GestureConfig()

    private fun fsm() = TouchFsm(geometry, config)
    private fun key(id: String): KeyRect = geometry.keyRects.first { it.key.id == id }

    private inline fun <reified T> List<GestureOutput>.has(): Boolean = any { it is T }

    /** Far enough up to cross the threshold, whatever it is tuned to. */
    private val swipeUp get() = -config.bulkDeleteDistanceRatio * geometry.keyHeight - 1f

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
    fun `swiping up after the hold clears, and stops the repeat first`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)
        val out = f.onMove(b.centerX, b.centerY + swipeUp, 700)
        assertTrue(out.has<GestureOutput.BulkDelete>())
        assertTrue(out.has<GestureOutput.BackspaceRepeatEnded>())
    }

    @Test
    fun `swiping up without holding first clears too`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        assertTrue(f.onMove(b.centerX, b.centerY + swipeUp, 60).has<GestureOutput.BulkDelete>())
    }

    @Test
    fun `clearing happens once per gesture, however much the finger wobbles`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        f.onMove(b.centerX, b.centerY + swipeUp, 60)
        val again = f.onMove(b.centerX, b.centerY, 90) + f.onMove(b.centerX, b.centerY + swipeUp, 120)
        assertFalse(again.has<GestureOutput.BulkDelete>())
    }

    @Test
    fun `lifting after a clear does not also delete a character`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        f.onMove(b.centerX, b.centerY + swipeUp, 60)
        assertFalse(f.onUp(b.centerX, b.centerY + swipeUp, 200).has<GestureOutput.SpecialKey>())
    }

    @Test
    fun `a small drift off the key does not clear anything`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        val out = f.onMove(b.centerX + 3f, b.centerY - geometry.keyHeight * 0.3f, 40)
        assertFalse(out.has<GestureOutput.BulkDelete>())
    }

    @Test
    fun `swiping down off backspace does nothing at all`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        val out = f.onMove(b.centerX, b.centerY + geometry.keyHeight, 60)
        assertTrue(out.isEmpty())
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
