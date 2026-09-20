package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import com.offlinekeyboard.ime.layout.Squash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three gestures the space bar gained: flick down to put the keyboard away, flick sideways
 * to squash the board toward that edge for one-handed reach.
 *
 * The cases that matter most here are the ones about what these gestures must *not* do. The
 * space bar was already carrying a tap and the trackpad, and both of those are gestures a thumb
 * makes constantly -- so most of this file is about the boundary rather than the happy path.
 */
class SpaceFlickTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
    private val config = GestureConfig()

    private fun fsm() = TouchFsm(geometry, config)
    private fun space(): KeyRect = geometry.keyRects.first { it.key.id == "space" }

    private inline fun <reified T> List<GestureOutput>.has(): Boolean = any { it is T }

    /** A stroke fast enough to be a throw: 60px in 40ms is well past the 0.5px/ms floor. */
    private fun flick(dx: Float, dy: Float, ms: Long = 40L): List<GestureOutput> {
        val s = space()
        val f = fsm()
        f.onDown(s.centerX, s.centerY, 0)
        return f.onMove(s.centerX + dx, s.centerY + dy, ms)
    }

    // --- flick down: put the keyboard away -------------------------------------------------

    @Test
    fun `a downward flick on the space bar dismisses the keyboard`() {
        val out = flick(dx = 0f, dy = geometry.keyHeight)
        assertTrue(out.has<GestureOutput.DismissKeyboard>())
    }

    /**
     * The gesture is spent once it has fired, so the release does not also type a space. A
     * dismissal that left a stray space in the field would be noticed only after the keyboard
     * had gone, which is the worst moment to discover it.
     */
    @Test
    fun `dismissing does not also type a space when the finger lifts`() {
        val s = space()
        val f = fsm()
        f.onDown(s.centerX, s.centerY, 0)
        f.onMove(s.centerX, s.centerY + geometry.keyHeight, 40)
        val out = f.onUp(s.centerX, s.centerY + geometry.keyHeight, 60)
        assertFalse(out.has<GestureOutput.CommitPrimary>())
    }

    @Test
    fun `it fires once, however much the finger wobbles across the threshold`() {
        val s = space()
        val f = fsm()
        f.onDown(s.centerX, s.centerY, 0)
        val first = f.onMove(s.centerX, s.centerY + geometry.keyHeight, 40)
        val second = f.onMove(s.centerX, s.centerY + geometry.keyHeight * 1.5f, 60)
        assertTrue(first.has<GestureOutput.DismissKeyboard>())
        assertFalse("the keyboard cannot be dismissed twice", second.has<GestureOutput.DismissKeyboard>())
    }

    /**
     * A thumb rolling off the bottom of the space bar at the end of a sentence is the gesture
     * this must not be confused with, and it is why the threshold is three quarters of a key
     * rather than the three pixels an ordinary flick asks for.
     */
    @Test
    fun `a short downward roll off the space bar still types a space`() {
        val s = space()
        val f = fsm()
        f.onDown(s.centerX, s.centerY, 0)
        f.onMove(s.centerX, s.centerY + geometry.keyHeight * 0.3f, 40)
        val out = f.onUp(s.centerX, s.centerY + geometry.keyHeight * 0.3f, 60)
        assertFalse(out.has<GestureOutput.DismissKeyboard>())
        assertTrue(out.has<GestureOutput.CommitPrimary>())
    }

    // --- flick sideways: squash the board --------------------------------------------------

    @Test
    fun `a rightward flick asks for a squash to the right`() {
        val out = flick(dx = geometry.keyUnit * 2f, dy = 0f)
        assertEquals(
            SquashDirection.RIGHT,
            out.filterIsInstance<GestureOutput.SquashFlick>().single().toward,
        )
    }

    @Test
    fun `a leftward flick asks for a squash to the left`() {
        val out = flick(dx = -geometry.keyUnit * 2f, dy = 0f)
        assertEquals(
            SquashDirection.LEFT,
            out.filterIsInstance<GestureOutput.SquashFlick>().single().toward,
        )
    }

    @Test
    fun `squashing does not also type a space`() {
        val s = space()
        val f = fsm()
        f.onDown(s.centerX, s.centerY, 0)
        f.onMove(s.centerX + geometry.keyUnit * 2f, s.centerY, 40)
        val out = f.onUp(s.centerX + geometry.keyUnit * 2f, s.centerY, 60)
        assertFalse(out.has<GestureOutput.CommitPrimary>())
    }

    @Test
    fun `a short sideways wobble still types a space`() {
        val s = space()
        val f = fsm()
        f.onDown(s.centerX, s.centerY, 0)
        f.onMove(s.centerX + geometry.keyUnit * 0.4f, s.centerY, 40)
        val out = f.onUp(s.centerX + geometry.keyUnit * 0.4f, s.centerY, 60)
        assertFalse(out.has<GestureOutput.SquashFlick>())
        assertTrue(out.has<GestureOutput.CommitPrimary>())
    }

    // --- the boundary with the trackpad ----------------------------------------------------

    /**
     * The gesture the squash could most easily have broken. Sliding along the space bar while
     * waiting for the hold is the documented way into the trackpad, and it covers exactly the
     * distance a squash flick does -- so speed, not distance, has to be what separates them.
     */
    @Test
    fun `a slow sideways drift keeps its trackpad instead of squashing`() {
        val s = space()
        val f = fsm()
        f.onDown(s.centerX, s.centerY, 0)
        val drift = f.onMove(s.centerX + geometry.keyHeight, s.centerY, config.longPressMs - 1)
        assertFalse("a creeping finger must not squash", drift.has<GestureOutput.SquashFlick>())
        assertTrue(f.onLongPressTimeout(config.longPressMs).has<GestureOutput.TrackpadStarted>())
    }

    @Test
    fun `a slow downward drift does not dismiss the keyboard`() {
        val s = space()
        val f = fsm()
        f.onDown(s.centerX, s.centerY, 0)
        val drift = f.onMove(s.centerX, s.centerY + geometry.keyHeight, config.longPressMs - 1)
        assertFalse(drift.has<GestureOutput.DismissKeyboard>())
    }

    /** Once the trackpad is live, a long pan is a pan -- never a squash. */
    @Test
    fun `panning the trackpad never squashes however far it goes`() {
        val s = space()
        val f = fsm()
        f.onDown(s.centerX, s.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)
        val pan = f.onMove(s.centerX + geometry.keyUnit * 4f, s.centerY, config.longPressMs + 20)
        assertFalse(pan.has<GestureOutput.SquashFlick>())
        assertTrue(pan.has<GestureOutput.TrackpadPan>())
    }

    // --- the boundary with everything else -------------------------------------------------

    @Test
    fun `these gestures belong to the space bar alone`() {
        val g = geometry.keyRects.first { it.key.id == "g" }
        val f = fsm()
        f.onDown(g.centerX, g.centerY, 0)
        val out = f.onMove(g.centerX, g.centerY + geometry.keyHeight, 40)
        assertFalse("a flick off a letter must not close the keyboard", out.has<GestureOutput.DismissKeyboard>())
    }

    /**
     * A diagonal satisfying both readings squashes rather than dismisses: the board moving is
     * recoverable with the opposite flick, while the keyboard vanishing mid-sentence is not.
     */
    @Test
    fun `an ambiguous diagonal squashes rather than dismissing`() {
        val out = flick(dx = geometry.keyUnit * 2f, dy = geometry.keyHeight)
        assertTrue(out.has<GestureOutput.SquashFlick>())
        assertFalse(out.has<GestureOutput.DismissKeyboard>())
    }

    // --- the state machine the flicks drive ------------------------------------------------

    @Test
    fun `flicking right walks the board from left, through full width, to right`() {
        assertEquals(Squash.NONE, Squash.LEFT.flicked(Squash.RIGHT))
        assertEquals(Squash.RIGHT, Squash.NONE.flicked(Squash.RIGHT))
        assertEquals(Squash.RIGHT, Squash.RIGHT.flicked(Squash.RIGHT))
    }

    @Test
    fun `flicking left walks it back again`() {
        assertEquals(Squash.NONE, Squash.RIGHT.flicked(Squash.LEFT))
        assertEquals(Squash.LEFT, Squash.NONE.flicked(Squash.LEFT))
        assertEquals(Squash.LEFT, Squash.LEFT.flicked(Squash.LEFT))
    }

    /**
     * The one property the three states exist to have: a flick never moves the board *against*
     * the flick. A cycling transition would send a right flick's board leftward once in three,
     * landing the keys under the other thumb of the hand that asked for this.
     */
    @Test
    fun `a flick never moves the board against itself`() {
        val order = listOf(Squash.LEFT, Squash.NONE, Squash.RIGHT)
        for (from in order) {
            assertTrue(
                "right flick from $from went left",
                order.indexOf(from.flicked(Squash.RIGHT)) >= order.indexOf(from),
            )
            assertTrue(
                "left flick from $from went right",
                order.indexOf(from.flicked(Squash.LEFT)) <= order.indexOf(from),
            )
        }
    }

    /** A flick that changes nothing is still reported, so the view can decline it quietly. */
    @Test
    fun `undoing a squash restores the full-width board`() {
        assertEquals(Squash.NONE, Squash.NONE.flicked(Squash.RIGHT).flicked(Squash.LEFT))
        assertEquals(Squash.NONE, Squash.NONE.flicked(Squash.LEFT).flicked(Squash.RIGHT))
    }
}
