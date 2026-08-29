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

    // --- requirement 5: spacebar trackpad -----------------------------------------------

    private fun trackpadOf(f: TouchFsm, space: com.offlinekeyboard.ime.layout.KeyRect) {
        f.onDown(space.centerX, space.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)
    }

    private fun trackpadFsm(): Pair<TouchFsm, com.offlinekeyboard.ime.layout.KeyRect> {
        val space = key("space")
        val f = fsm()
        f.onDown(space.centerX, space.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)
        return f to space
    }

    @Test
    fun `long press on space enters trackpad mode`() {
        val (f, _) = trackpadFsm()
        assertEquals(GestureState.TRACKPAD, f.state)
    }

    @Test
    fun `trackpad pans the marker by the finger movement, scaled by the gain`() {
        val (f, space) = trackpadFsm()
        val pan = f.onMove(space.centerX + 100f, space.centerY, 600)
            .only<GestureOutput.TrackpadPan>()
        assertEquals(100f * config.trackpadGainX, pan.dx, 0.01f)
        assertEquals(0f, pan.dy, 0.01f)
    }

    @Test
    fun `trackpad pans in both axes at once`() {
        val (f, space) = trackpadFsm()
        val pan = f.onMove(space.centerX + 40f, space.centerY - 30f, 600)
            .only<GestureOutput.TrackpadPan>()
        assertEquals(40f * config.trackpadGainX, pan.dx, 0.01f)
        assertEquals(-30f * config.trackpadGainY, pan.dy, 0.01f)
    }

    @Test
    fun `each pan reports only the movement since the last one`() {
        val (f, space) = trackpadFsm()
        f.onMove(space.centerX + 50f, space.centerY, 1000)
        val second = f.onMove(space.centerX + 80f, space.centerY, 2000)
            .only<GestureOutput.TrackpadPan>()
        assertEquals(30f * config.trackpadGainX, second.dx, 0.01f)
    }

    @Test
    fun `vertical travel is more sensitive than horizontal`() {
        // A line is a longer journey than a character, and there is less room to move
        // vertically on a keyboard than horizontally.
        assertTrue(config.trackpadGainY > config.trackpadGainX)
    }

    @Test
    fun `a stationary finger produces no pan`() {
        val (f, space) = trackpadFsm()
        assertTrue(f.onMove(space.centerX, space.centerY, 600).isEmpty())
    }

    @Test
    fun `panning is unbounded -- nothing clamps it to a line`() {
        val (f, space) = trackpadFsm()
        var total = 0f
        var x = space.centerX
        var t = 1000L
        repeat(20) {
            x += 60f
            t += 1000 // slow enough that acceleration leaves the gain alone
            total += f.onMove(x, space.centerY, t).only<GestureOutput.TrackpadPan>().dx
        }
        assertEquals(20 * 60f * config.trackpadGainX, total, 0.5f)
    }

    // --- velocity sensitivity ------------------------------------------------------------

    /** Drags [distance] repeatedly with [dt] between samples, returning the last pan. */
    private fun drag(f: TouchFsm, from: Float, y: Float, distance: Float, dt: Long, steps: Int):
        GestureOutput.TrackpadPan {
        var x = from
        var t = 1000L
        var last: GestureOutput.TrackpadPan? = null
        repeat(steps) {
            x += distance
            t += dt
            last = f.onMove(x, y, t).only<GestureOutput.TrackpadPan>()
        }
        return last!!
    }

    @Test
    fun `slow movement is not accelerated at all`() {
        val (f, space) = trackpadFsm()
        // 60px over a full second: precise positioning must feel exactly as it did before
        val pan = drag(f, space.centerX, space.centerY, 60f, 1000, 3)
        assertEquals(60f * config.trackpadGainX, pan.dx, 0.01f)
    }

    @Test
    fun `fast movement travels much further for the same finger distance`() {
        val (f, space) = trackpadFsm()
        val slow = drag(fsm().also { trackpadOf(it, space) }, space.centerX, space.centerY, 120f, 1000, 3)
        val fast = drag(f, space.centerX, space.centerY, 120f, 20, 4)
        assertTrue(
            "fast pan ${fast.dx} should far exceed slow pan ${slow.dx}",
            fast.dx > slow.dx * 3f,
        )
    }

    @Test
    fun `acceleration never exceeds the configured maximum`() {
        val (f, space) = trackpadFsm()
        val pan = drag(f, space.centerX, space.centerY, 300f, 1, 6)
        assertTrue(
            "pan ${pan.dx} exceeded the cap",
            pan.dx <= 300f * config.trackpadGainX * config.trackpadMaxAccel + 0.01f,
        )
    }

    @Test
    fun `acceleration does not bend the direction of travel`() {
        val (f, space) = trackpadFsm()
        var x = space.centerX
        var y = space.centerY
        var t = 1000L
        var pan: GestureOutput.TrackpadPan? = null
        repeat(4) {
            x += 100f
            y -= 50f
            t += 20
            pan = f.onMove(x, y, t).only<GestureOutput.TrackpadPan>()
        }
        // undo the per-axis gains; what remains must have the finger's own 100:-50 ratio
        val ratio = (pan!!.dx / config.trackpadGainX) / (pan!!.dy / config.trackpadGainY)
        assertEquals(100f / -50f, ratio, 0.01f)
    }

    @Test
    fun `speed does not carry over into the next drag`() {
        val (f, space) = trackpadFsm()
        drag(f, space.centerX, space.centerY, 300f, 1, 5) // fast
        f.onUp(space.centerX, space.centerY, 5000)

        f.onDown(space.centerX, space.centerY, 6000)
        f.onLongPressTimeout(6000 + config.longPressMs)
        val pan = f.onMove(space.centerX + 60f, space.centerY, 8000)
            .only<GestureOutput.TrackpadPan>()
        assertEquals(60f * config.trackpadGainX, pan.dx, 0.01f)
    }

    @Test
    fun `space still types a space on a quick tap`() {
        val space = key("space")
        val f = fsm()
        f.onDown(space.centerX, space.centerY, 0)
        val out = f.onUp(space.centerX, space.centerY, 60)
        assertEquals(" ", out.only<GestureOutput.CommitPrimary>().text)
    }

    // --- selection from trackpad mode ----------------------------------------------------

    @Test
    fun `tapping again during trackpad mode starts a selection`() {
        val (f, _) = trackpadFsm()
        val out = f.onSecondaryTap()
        assertEquals(GestureState.SELECTING, f.state)
        assertTrue(out.has<GestureOutput.SelectionStarted>())
    }

    @Test
    fun `panning continues while selecting`() {
        val (f, space) = trackpadFsm()
        f.onSecondaryTap()
        val pan = f.onMove(space.centerX + 40f, space.centerY, 700)
            .only<GestureOutput.TrackpadPan>()
        assertEquals(40f * config.trackpadGainX, pan.dx, 0.01f)
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
}
