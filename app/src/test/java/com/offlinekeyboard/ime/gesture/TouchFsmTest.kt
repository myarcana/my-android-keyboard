package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.IosMetrics
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.LayoutGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchFsmTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, IosMetrics.REFERENCE_WIDTH)
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
        val out = f.onMove(space.centerX + stepX, space.centerY + stepY, 600)
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

        val afterSelect = f.onMove(space.centerX + stepX * 2f, space.centerY, 700)
            .filterIsInstance<GestureOutput.CursorMove>()
        assertTrue("expected steps after selecting", afterSelect.isNotEmpty())
        assertTrue("steps must extend the selection", afterSelect.all { it.extend })
    }

    @Test
    fun `selection extends vertically across lines too`() {
        val (f, space) = trackpadFsm()
        f.onSecondaryTap()
        val stepY = config.trackpadStepYRatio * geometry.keyHeight
        val out = f.onMove(space.centerX, space.centerY + stepY, 700)
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
    fun `lifting off ends selection mode`() {
        val (f, space) = trackpadFsm()
        f.onSecondaryTap()
        val out = f.onUp(space.centerX, space.centerY, 900)
        assertTrue(out.has<GestureOutput.TrackpadEnded>())
        assertEquals(GestureState.IDLE, f.state)
    }
}
