package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.Metrics
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.LayoutGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TouchFsmTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
    private val config = GestureConfig()

    private fun fsm() = TouchFsm(geometry, config)
    private fun key(id: String): KeyRect = geometry.keyRects.first { it.key.id == id }

    private inline fun <reified T> List<GestureOutput>.only(): T =
        filterIsInstance<T>().single()

    private inline fun <reified T> List<GestureOutput>.has(): Boolean =
        any { it is T }

    // --- tap ------------------------------------------------------------------------------

    @Test
    fun `tap commits the primary character`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        val out = f.onUp(q.centerX, q.centerY, 80)
        assertEquals("q", out.only<GestureOutput.CommitPrimary>().text)
    }

    @Test
    fun `tap never produces a glide`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        f.onMove(q.centerX + 2f, q.centerY + 2f, 20)
        val out = f.onUp(q.centerX + 2f, q.centerY + 2f, 60)
        assertTrue(out.has<GestureOutput.CommitPrimary>())
        assertTrue(!out.has<GestureOutput.GlideCompleted>())
    }

    // --- iPadOS flick-down ----------------------------------------------------------------

    @Test
    fun `downward flick commits the secondary character`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        val moved = f.onMove(q.centerX, q.centerY + 25f, 40)
        assertEquals("1", moved.only<GestureOutput.FlickPreview>().text)
        assertEquals(GestureState.FLICK, f.state)

        val out = f.onUp(q.centerX, q.centerY + 25f, 80)
        assertEquals("1", out.only<GestureOutput.CommitSecondary>().text)
    }

    @Test
    fun `flick is only previewed while the finger is down, never committed early`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        val moved = f.onMove(q.centerX, q.centerY + 25f, 40)
        assertTrue(moved.has<GestureOutput.FlickPreview>())
        assertTrue("nothing may be committed before release", !moved.has<GestureOutput.CommitSecondary>())
    }

    @Test
    fun `a key with no secondary does not flick`() {
        val space = key("space")
        val f = fsm()
        f.onDown(space.centerX, space.centerY, 0)
        f.onMove(space.centerX, space.centerY + 25f, 40)
        assertTrue(f.state != GestureState.FLICK)
    }

    @Test
    fun `diagonal movement is not a flick because it is not vertically dominant`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        f.onMove(q.centerX + 24f, q.centerY + 25f, 40)
        assertTrue(f.state != GestureState.FLICK)
    }

    // --- requirement 4: flick promotes to glide -------------------------------------------

    @Test
    fun `flick that keeps travelling is promoted to a glide`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        f.onMove(q.centerX, q.centerY + 25f, 40)
        assertEquals(GestureState.FLICK, f.state)

        // keep going -- past the longer flick-to-glide threshold
        val promoted = f.onMove(q.centerX + 60f, q.centerY + 30f, 80)
        assertEquals(GestureState.GLIDE, f.state)
        assertTrue(promoted.has<GestureOutput.FlickPreviewCleared>())
        assertTrue(promoted.has<GestureOutput.GlideStarted>())

        val out = f.onUp(q.centerX + 60f, q.centerY + 30f, 120)
        assertTrue("promoted gesture must end as a glide", out.has<GestureOutput.GlideCompleted>())
        assertTrue("and must not type the flick symbol", !out.has<GestureOutput.CommitSecondary>())
    }

    @Test
    fun `horizontal swipe becomes a glide without ever flicking`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        val out = f.onMove(q.centerX + 45f, q.centerY, 40)
        assertEquals(GestureState.GLIDE, f.state)
        assertTrue(out.has<GestureOutput.GlideStarted>())
    }

    @Test
    fun `glide path reports the keys it crossed`() {
        val f = fsm()
        val q = key("q")
        val w = key("w")
        val e = key("e")
        f.onDown(q.centerX, q.centerY, 0)
        f.onMove(w.centerX, w.centerY, 30)
        f.onMove(e.centerX, e.centerY, 60)
        val out = f.onUp(e.centerX, e.centerY, 90)
        val path = out.only<GestureOutput.GlideCompleted>().path
        assertEquals(listOf("q", "w", "e"), f.pathKeys(path))
    }

    // --- long press accents ---------------------------------------------------------------

    @Test
    fun `long press on a key with accents opens the popup`() {
        val e = key("e")
        val f = fsm()
        f.onDown(e.centerX, e.centerY, 0)
        val out = f.onLongPressTimeout(config.longPressMs)
        assertEquals(GestureState.ACCENTS, f.state)
        assertEquals(listOf("è", "é", "ê", "ë", "ē", "ė", "ę"), out.only<GestureOutput.ShowAccents>().accents)
    }

    @Test
    fun `sliding across the accent popup selects and commits one`() {
        val e = key("e")
        val f = fsm()
        f.onDown(e.centerX, e.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)

        val accents = e.key.accents
        val popupLeft = e.centerX - accents.size * geometry.keyUnit / 2f
        val thirdX = popupLeft + 2.5f * geometry.keyUnit
        f.onMove(thirdX, e.centerY, 600)

        val out = f.onUp(thirdX, e.centerY, 700)
        assertEquals("ê", out.only<GestureOutput.CommitAccent>().text)
        assertTrue(out.has<GestureOutput.HideAccents>())
    }

    @Test
    fun `long press does nothing on a key with no accents`() {
        val f = fsm()
        val t = key("t")
        f.onDown(t.centerX, t.centerY, 0)
        val out = f.onLongPressTimeout(config.longPressMs)
        assertTrue(out.isEmpty())
        assertEquals(GestureState.PRESSED, f.state)
    }

    @Test
    fun `long press timeout is ignored once the gesture has become a glide`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        f.onMove(q.centerX + 45f, q.centerY, 40)
        assertEquals(GestureState.GLIDE, f.state)
        assertTrue(f.onLongPressTimeout(config.longPressMs).isEmpty())
        assertEquals(GestureState.GLIDE, f.state)
    }

    // --- requirement 5: spacebar trackpad -------------------------------------------------

    @Test
    fun `long press on space enters trackpad mode`() {
        val space = key("space")
        val f = fsm()
        f.onDown(space.centerX, space.centerY, 0)
        val out = f.onLongPressTimeout(config.longPressMs)
        assertEquals(GestureState.TRACKPAD, f.state)
        assertTrue(out.has<GestureOutput.TrackpadStarted>())
    }

    @Test
    fun `trackpad moves the cursor horizontally`() {
        val space = key("space")
        val f = fsm()
        f.onDown(space.centerX, space.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)

        val stepX = config.trackpadStepXRatio * geometry.keyUnit
        val out = f.onMove(space.centerX + stepX * 2.2f, space.centerY, 600)
        val moves = out.filterIsInstance<GestureOutput.CursorMove>()
        assertEquals(2, moves.size)
        assertTrue(moves.all { it.dx == 1 && it.dy == 0 })
    }

    @Test
    fun `trackpad moves the cursor vertically across lines`() {
        val space = key("space")
        val f = fsm()
        f.onDown(space.centerX, space.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)

        val stepY = config.trackpadStepYRatio * geometry.keyHeight
        val out = f.onMove(space.centerX, space.centerY - stepY * 1.5f, 600)
        val moves = out.filterIsInstance<GestureOutput.CursorMove>()
        assertEquals(1, moves.size)
        assertEquals(-1, moves.single().dy)
    }

    @Test
    fun `trackpad reports both axes, not just horizontal`() {
        val space = key("space")
        val f = fsm()
        f.onDown(space.centerX, space.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)

        val stepX = config.trackpadStepXRatio * geometry.keyUnit
        val stepY = config.trackpadStepYRatio * geometry.keyHeight
        val out = f.onMove(space.centerX + stepX * 1.2f, space.centerY + stepY * 1.2f, 600)
        val moves = out.filterIsInstance<GestureOutput.CursorMove>()
        assertTrue("expected a horizontal step", moves.any { it.dx == 1 })
        assertTrue("expected a vertical step", moves.any { it.dy == 1 })
    }

    @Test
    fun `space still types a space on a quick tap`() {
        val space = key("space")
        val f = fsm()
        f.onDown(space.centerX, space.centerY, 0)
        val out = f.onUp(space.centerX, space.centerY, 60)
        assertEquals(" ", out.only<GestureOutput.CommitPrimary>().text)
    }

    // --- misc -----------------------------------------------------------------------------

    @Test
    fun `touch outside any key is ignored`() {
        val f = fsm()
        val out = f.onDown(-50f, -50f, 0)
        assertTrue(out.isEmpty())
        assertEquals(GestureState.IDLE, f.state)
    }

    @Test
    fun `shift and backspace report as special keys rather than committing text`() {
        for (id in listOf("shift", "backspace")) {
            val k = key(id)
            val f = fsm()
            f.onDown(k.centerX, k.centerY, 0)
            val out = f.onUp(k.centerX, k.centerY, 60)
            assertNotNull(out.only<GestureOutput.SpecialKey>())
            assertTrue(!out.has<GestureOutput.CommitPrimary>())
        }
    }

    @Test
    fun `state is fully reset between gestures`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        f.onMove(q.centerX + 45f, q.centerY, 40)
        f.onUp(q.centerX + 45f, q.centerY, 80)
        assertEquals(GestureState.IDLE, f.state)
        assertNull(f.longPressDeadline)

        // a fresh tap must not inherit the previous path length
        f.onDown(q.centerX, q.centerY, 200)
        val out = f.onUp(q.centerX, q.centerY, 260)
        assertEquals("q", out.only<GestureOutput.CommitPrimary>().text)
    }

    // --- selection from trackpad mode ---------------------------------------------------

    private fun trackpadFsm(): Pair<TouchFsm, com.offlinekeyboard.ime.layout.KeyRect> {
        val space = key("space")
        val f = fsm()
        f.onDown(space.centerX, space.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)
        return f to space
    }

    @Test
    fun `vertical travel is twice as sensitive as horizontal per key dimension`() {
        // A line is a longer journey than a character, so the same finger movement covers more.
        assertTrue(
            "vertical step must be the smaller fraction",
            config.trackpadStepYRatio < config.trackpadStepXRatio,
        )
    }

    @Test
    fun `trackpad reports sub-step progress between caret positions`() {
        val (f, space) = trackpadFsm()
        val stepX = config.trackpadStepXRatio * geometry.keyUnit
        // move a third of a step: too little to move the caret, but the finger has travelled
        val out = f.onMove(space.centerX + stepX / 3f, space.centerY, 600)
        assertTrue("caret must not move yet", out.filterIsInstance<GestureOutput.CursorMove>().isEmpty())
        val progress = out.only<GestureOutput.CursorProgress>()
        assertEquals(0.333f, progress.fractionX, 0.02f)
        assertEquals(0f, progress.fractionY, 0.02f)
    }

    @Test
    fun `progress resets toward zero after a step is emitted`() {
        val (f, space) = trackpadFsm()
        val stepX = config.trackpadStepXRatio * geometry.keyUnit
        val out = f.onMove(space.centerX + stepX * 1.2f, space.centerY, 600)
        assertEquals(1, out.filterIsInstance<GestureOutput.CursorMove>().size)
        assertEquals(0.2f, out.only<GestureOutput.CursorProgress>().fractionX, 0.02f)
    }

    @Test
    fun `caret snaps to the nearest boundary, not the one just passed`() {
        val (f, space) = trackpadFsm()
        val stepX = config.trackpadStepXRatio * geometry.keyUnit

        // just over half a step: the nearest boundary is the next one, so the caret moves now
        val out = f.onMove(space.centerX + stepX * 0.6f, space.centerY, 600)
        assertEquals(1, out.filterIsInstance<GestureOutput.CursorMove>().size)
        // and the granular position is now *behind* the caret, by the remaining 0.4
        assertEquals(-0.4f, out.only<GestureOutput.CursorProgress>().fractionX, 0.02f)
    }

    @Test
    fun `under half a step leaves the caret alone`() {
        val (f, space) = trackpadFsm()
        val stepX = config.trackpadStepXRatio * geometry.keyUnit
        val out = f.onMove(space.centerX + stepX * 0.4f, space.centerY, 600)
        assertTrue(out.filterIsInstance<GestureOutput.CursorMove>().isEmpty())
        assertEquals(0.4f, out.only<GestureOutput.CursorProgress>().fractionX, 0.02f)
    }

    @Test
    fun `granular position never drifts more than half a step from the caret`() {
        val (f, space) = trackpadFsm()
        val stepX = config.trackpadStepXRatio * geometry.keyUnit
        var x = space.centerX
        // drag across several characters in uneven increments
        for (i in 1..25) {
            x += stepX * 0.37f
            val out = f.onMove(x, space.centerY, 600L + i * 10)
            out.filterIsInstance<GestureOutput.CursorProgress>().forEach {
                assertTrue(
                    "progress drifted to ${it.fractionX}",
                    abs(it.fractionX) <= 0.5f + 0.001f,
                )
            }
        }
    }

    @Test
    fun `half-step travel terminates instead of oscillating`() {
        val (f, space) = trackpadFsm()
        val stepY = config.trackpadStepYRatio * geometry.keyHeight
        // exactly half a step: a non-strict comparison would step back and forth forever
        val out = f.onMove(space.centerX, space.centerY + stepY * 0.5f, 600)
        assertTrue(out.filterIsInstance<GestureOutput.CursorMove>().size <= 1)
    }

    @Test
    fun `tapping again during trackpad mode starts a selection`() {
        val (f, _) = trackpadFsm()
        val out = f.onSecondaryTap()
        assertEquals(GestureState.SELECTING, f.state)
        assertTrue(out.has<GestureOutput.SelectionStarted>())
    }

    @Test
    fun `movement after starting a selection extends it instead of moving the caret`() {
        val (f, space) = trackpadFsm()
        val stepX = config.trackpadStepXRatio * geometry.keyUnit

        val beforeSelect = f.onMove(space.centerX + stepX, space.centerY, 600)
            .filterIsInstance<GestureOutput.CursorMove>()
        assertTrue("caret moves plainly first", beforeSelect.all { !it.extend })

        f.onSecondaryTap()

        val afterSelect = f.onMove(space.centerX + stepX * 2.4f, space.centerY, 700)
            .filterIsInstance<GestureOutput.CursorMove>()
        assertTrue("expected steps after selecting", afterSelect.isNotEmpty())
        assertTrue("steps must extend the selection", afterSelect.all { it.extend })
    }

    @Test
    fun `selection extends vertically across lines too`() {
        val (f, space) = trackpadFsm()
        f.onSecondaryTap()
        val stepY = config.trackpadStepYRatio * geometry.keyHeight
        val out = f.onMove(space.centerX, space.centerY + stepY * 1.2f, 700)
            .filterIsInstance<GestureOutput.CursorMove>()
        assertEquals(1, out.size)
        assertEquals(1, out.single().dy)
        assertTrue(out.single().extend)
    }

    @Test
    fun `a second tap does nothing unless the trackpad is active`() {
        val q = key("q")
        val f = fsm()
        f.onDown(q.centerX, q.centerY, 0)
        assertTrue(f.onSecondaryTap().isEmpty())
        assertEquals(GestureState.PRESSED, f.state)
    }

    @Test
    fun `cancelling during selection still ends the trackpad`() {
        val (f, _) = trackpadFsm()
        f.onSecondaryTap()
        val out = f.onCancel()
        assertTrue(
            "a held shift key would otherwise never be released",
            out.has<GestureOutput.TrackpadEnded>(),
        )
        assertEquals(GestureState.IDLE, f.state)
    }

    @Test
    fun `cancelling during plain trackpad also ends it`() {
        val (f, _) = trackpadFsm()
        assertTrue(f.onCancel().has<GestureOutput.TrackpadEnded>())
    }

    @Test
    fun `lifting off ends selection mode`() {
        val (f, space) = trackpadFsm()
        f.onSecondaryTap()
        val out = f.onUp(space.centerX, space.centerY, 900)
        assertTrue(out.has<GestureOutput.TrackpadEnded>())
        assertEquals(GestureState.IDLE, f.state)
    }
}
