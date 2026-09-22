package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.EditAction
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import com.offlinekeyboard.ime.layout.PopupEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flicking up runs the key's popup primary: up from `c` copies, without waiting for a long press.
 *
 * The mapping itself is not what these test. It is [com.offlinekeyboard.ime.layout.Key.flickUp],
 * derived from the same [PopupEntry] the long press commits, so restating "c means copy" here
 * would only assert that a one-line getter reads a list. What is worth testing is the part that
 * can actually go wrong: an upward gesture now competes with the glide, which starts *every* word
 * typed from the bottom row with an upward stroke. The interesting cases are all about that
 * boundary -- when the action wins, when the word wins, and that nothing is committed while the
 * two are still in doubt.
 */
class UpFlickTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
    private val config = GestureConfig()

    private fun fsm() = TouchFsm(geometry, config)
    private fun key(id: String): KeyRect = geometry.keyRects.first { it.key.id == id }

    private inline fun <reified T> List<GestureOutput>.only(): T = filterIsInstance<T>().single()
    private inline fun <reified T> List<GestureOutput>.has(): Boolean = any { it is T }

    /** Just past the distance that arms the flick. */
    private val armingPull get() = config.upFlickDistanceRatio * geometry.keyHeight + 2f

    /**
     * Flicks straight up from a key and lifts, in one move. The single move is deliberate: a
     * real flick is fast, so the device may well report only one sample between down and up.
     */
    private fun flickUp(id: String, pull: Float = armingPull): List<GestureOutput> {
        val k = key(id)
        val f = fsm()
        f.onDown(k.centerX, k.centerY, 0)
        f.onMove(k.centerX, k.centerY - pull, 40)
        return f.onUp(k.centerX, k.centerY - pull, 60)
    }

    // --- the gesture itself ------------------------------------------------------------------

    @Test
    fun `flicking up on c copies`() {
        val out = flickUp("c")
        assertEquals(EditAction.COPY, out.only<GestureOutput.CommitAction>().action)
    }

    @Test
    fun `the other lettered shortcuts flick up too`() {
        assertEquals(EditAction.PASTE, flickUp("v").only<GestureOutput.CommitAction>().action)
        assertEquals(EditAction.CUT, flickUp("x").only<GestureOutput.CommitAction>().action)
        assertEquals(EditAction.UNDO, flickUp("z").only<GestureOutput.CommitAction>().action)
        assertEquals(EditAction.REDO, flickUp("y").only<GestureOutput.CommitAction>().action)
        assertEquals(
            EditAction.SELECT_ALL,
            flickUp("a").only<GestureOutput.CommitAction>().action,
        )
    }

    /**
     * The flick and the long press are two speeds of one promise, so they must commit the same
     * thing. Nothing compares them at runtime -- each reads [PopupEntry] for itself -- which is
     * exactly why they are compared here.
     */
    @Test
    fun `flicking up commits what holding the key would have`() {
        val k = key("c")
        val held = fsm().run {
            onDown(k.centerX, k.centerY, 0)
            onLongPressTimeout(config.longPressMs)
            onUp(k.centerX, k.centerY, 700)
        }
        assertEquals(
            held.only<GestureOutput.CommitAction>().action,
            flickUp("c").only<GestureOutput.CommitAction>().action,
        )
    }

    /**
     * A key whose popup holds only accents flicks up to the one the popup would open on, and it
     * arrives as text rather than as an action. The two kinds of entry travel as different
     * outputs all the way to the service, which is what stops an action being typed as a word.
     */
    @Test
    fun `a key with only accents flicks up to its primary accent`() {
        val out = flickUp("e")
        val expected = (key("e").key.flickUp as PopupEntry.Accent).text
        assertEquals(expected, out.only<GestureOutput.CommitAccent>().text)
        assertTrue(!out.has<GestureOutput.CommitAction>())
    }

    @Test
    fun `an action is never committed as text`() {
        val out = flickUp("c")
        assertTrue(!out.has<GestureOutput.CommitAccent>())
        assertTrue(!out.has<GestureOutput.CommitPrimary>())
        assertTrue(!out.has<GestureOutput.CommitSecondary>())
    }

    /** A key with no popup at all has nothing to flick to, so the stroke stays a glide or a tap. */
    @Test
    fun `a key with no popup does not flick up`() {
        val out = flickUp("t")
        assertTrue(!out.has<GestureOutput.CommitAction>())
        assertTrue(!out.has<GestureOutput.CommitAccent>())
        assertTrue(!out.has<GestureOutput.UpFlickArmed>())
    }

    // --- not committing by accident ----------------------------------------------------------

    /**
     * The whole gesture is a promise not to fire early. Arming is visible -- the key shows what
     * it would do -- but nothing reaches the editor until the finger leaves the glass, which is
     * what lets a stroke that keeps going become a word instead.
     */
    @Test
    fun `arming commits nothing while the finger is still down`() {
        val k = key("c")
        val f = fsm()
        f.onDown(k.centerX, k.centerY, 0)
        val moved = f.onMove(k.centerX, k.centerY - armingPull, 40)
        assertEquals(GestureState.UP_FLICK, f.state)
        assertEquals("c", moved.only<GestureOutput.UpFlickArmed>().keyId)
        assertTrue(!moved.has<GestureOutput.CommitAction>())
    }

    /** A short upward drift is a wobble on a keypress, not a command. */
    @Test
    fun `a small upward movement still types the letter`() {
        val out = flickUp("c", pull = config.upFlickDistanceRatio * geometry.keyHeight - 4f)
        assertEquals("c", out.only<GestureOutput.CommitPrimary>().text)
        assertTrue(!out.has<GestureOutput.CommitAction>())
    }

    /**
     * Arming and then coming back down disarms. Read from where the finger is at the lift rather
     * than latched at the crossing, so changing your mind halfway is part of the gesture.
     */
    @Test
    fun `pulling back down after arming types the letter instead`() {
        val k = key("c")
        val f = fsm()
        f.onDown(k.centerX, k.centerY, 0)
        f.onMove(k.centerX, k.centerY - armingPull, 40)
        val back = f.onMove(k.centerX, k.centerY - 2f, 80)
        assertTrue(back.has<GestureOutput.UpFlickDisarmed>())
        // RECALLED rather than PRESSED: the stroke it already spent must not be handed to the
        // glide. See [GestureState.RECALLED].
        assertEquals(GestureState.RECALLED, f.state)

        val out = f.onUp(k.centerX, k.centerY - 2f, 100)
        assertEquals("c", out.only<GestureOutput.CommitPrimary>().text)
        assertTrue(!out.has<GestureOutput.CommitAction>())
    }

    /**
     * Pulling back and going up again re-arms. The finger never left the key, so the gesture is
     * still a live choice rather than something spent -- and a hand that hesitates halfway
     * through a flick should not have to lift and start over.
     */
    @Test
    fun `going back up after pulling back arms the flick again`() {
        val k = key("c")
        val f = fsm()
        f.onDown(k.centerX, k.centerY, 0)
        f.onMove(k.centerX, k.centerY - armingPull, 40)
        f.onMove(k.centerX, k.centerY - 2f, 80)
        val again = f.onMove(k.centerX, k.centerY - armingPull, 120)
        assertEquals("c", again.only<GestureOutput.UpFlickArmed>().keyId)

        val out = f.onUp(k.centerX, k.centerY - armingPull, 140)
        assertEquals(EditAction.COPY, out.only<GestureOutput.CommitAction>().action)
    }

    /**
     * The path spent going up and coming back must not be handed to the glide. This is the case
     * that made [GestureState.RECALLED] necessary: by the time a finger has travelled a key
     * height up and back it has covered twice the glide distance, so returning it to an ordinary
     * press meant abandoning an action started a word instead of typing the letter.
     */
    @Test
    fun `a recalled flick never turns into a glide`() {
        val k = key("c")
        val f = fsm()
        f.onDown(k.centerX, k.centerY, 0)
        f.onMove(k.centerX, k.centerY - armingPull, 40)
        f.onMove(k.centerX, k.centerY - 2f, 80)
        // More movement on the key, well past the glide distance in accumulated path.
        val wobble = f.onMove(k.centerX + 3f, k.centerY - 1f, 100)
        assertTrue(!wobble.has<GestureOutput.GlideStarted>())

        val out = f.onUp(k.centerX + 3f, k.centerY - 1f, 120)
        assertEquals("c", out.only<GestureOutput.CommitPrimary>().text)
        assertTrue(!out.has<GestureOutput.GlideSuspended>())
    }

    /** A diagonal is a word leaving the key, not a flick. Dominance is what separates them. */
    @Test
    fun `a diagonal stroke does not arm the flick`() {
        val k = key("c")
        val f = fsm()
        f.onDown(k.centerX, k.centerY, 0)
        val out = f.onMove(k.centerX + armingPull * 1.5f, k.centerY - armingPull, 40)
        assertTrue(!out.has<GestureOutput.UpFlickArmed>())
        assertTrue(f.state != GestureState.UP_FLICK)
    }

    // --- the glide keeps its stroke ----------------------------------------------------------

    /**
     * The case the whole design is built around: gliding a word that starts on `c` leaves the key
     * upward and must stay a glide. The action is given up the moment the stroke is long enough
     * to be a word, and -- crucially -- nothing was committed while it was armed.
     */
    @Test
    fun `an upward stroke that keeps going becomes a glide, not an action`() {
        val c = key("c")
        val f = fsm()
        f.onDown(c.centerX, c.centerY, 0)
        f.onMove(c.centerX, c.centerY - armingPull, 30)
        assertEquals(GestureState.UP_FLICK, f.state)

        // Carry on to another key, as a word would.
        val a = key("a")
        val escaped = f.onMove(a.centerX, a.centerY, 60)
        assertTrue(escaped.has<GestureOutput.UpFlickDisarmed>())
        assertTrue(escaped.has<GestureOutput.GlideStarted>())
        assertEquals(GestureState.GLIDE, f.state)

        val out = f.onUp(a.centerX, a.centerY, 90)
        assertTrue(!out.has<GestureOutput.CommitAction>())
        assertTrue(out.has<GestureOutput.GlideSuspended>())
    }

    /**
     * A key that cannot start a word has no glide to fall back to, so a long upward stroke from
     * it simply does nothing rather than beginning a word from a key that cannot spell.
     */
    @Test
    fun `a long stroke from a non-character key does not start a glide`() {
        val ret = key("return")
        val f = fsm()
        f.onDown(ret.centerX, ret.centerY, 0)
        f.onMove(ret.centerX, ret.centerY - armingPull, 30)
        val escaped = f.onMove(ret.centerX, ret.centerY - geometry.keyUnit * 3f, 60)
        assertTrue(escaped.has<GestureOutput.UpFlickDisarmed>())
        assertTrue(!escaped.has<GestureOutput.GlideStarted>())

        val out = f.onUp(ret.centerX, ret.centerY - geometry.keyUnit * 3f, 90)
        assertTrue(!out.has<GestureOutput.CommitAction>())
        assertTrue(!out.has<GestureOutput.SpecialKey>())
    }

    // --- the gestures that were already there ------------------------------------------------

    @Test
    fun `the downward flick still commits the secondary`() {
        val c = key("c")
        val f = fsm()
        f.onDown(c.centerX, c.centerY, 0)
        f.onMove(c.centerX, c.centerY + 25f, 40)
        val out = f.onUp(c.centerX, c.centerY + 25f, 80)
        assertEquals("+", out.only<GestureOutput.CommitSecondary>().text)
    }

    /**
     * Backspace keeps its upward swipe. It has no popup, so [Key.flickUp] is null and the bulk
     * delete is reached before anything here is consulted -- but the two gestures are the same
     * stroke on adjacent keys, so the overlap is worth pinning down.
     */
    @Test
    fun `swiping up on backspace still deletes the line`() {
        val b = key("backspace")
        val f = fsm()
        f.onDown(b.centerX, b.centerY, 0)
        val out = f.onMove(b.centerX, b.centerY - geometry.keyHeight, 40)
        assertTrue(out.has<GestureOutput.DeleteLine>())
        assertTrue(!out.has<GestureOutput.UpFlickArmed>())
    }

    /** The space bar's own gestures are unaffected: it has no popup to flick to. */
    @Test
    fun `the space bar does not flick up`() {
        val out = flickUp("space")
        assertTrue(!out.has<GestureOutput.UpFlickArmed>())
        assertTrue(!out.has<GestureOutput.CommitAction>())
    }

    /**
     * The globe key is refused deliberately. Its popup primary is the *next* language rather
     * than a fixed destination, so a flick would mean something different every time it was
     * made -- the one thing a gesture learned by the hand must not do.
     */
    @Test
    fun `the globe key does not flick up, even when it has a menu`() {
        val globe = IosLayouts.QWERTY_LOWER.rows.flatMap { it.keys }.first { it.id == "globe" }
        val withLanguages = globe.copy(
            languages = listOf(
                PopupEntry.Language("a", "English", current = true),
                PopupEntry.Language("b", "Pinyin"),
            ),
        )
        assertNull(withLanguages.flickUp)
    }

    // --- what the bank records ---------------------------------------------------------------

    /**
     * The gesture bank has to see this gesture for what it was. It records as ACCENT -- the same
     * verdict the long press that commits the same entry produces -- rather than a new name,
     * because the bank reads these back with `valueOf` and a name invented here would crash any
     * build that predates it.
     */
    @Test
    fun `a committed up-flick is captured as an accent`() {
        val trace = flickUp("c").only<GestureOutput.GestureCaptured>().trace
        assertEquals(GestureVerdict.ACCENT, trace.verdict)
        assertEquals("c", trace.startKeyId)
    }

    /** One that was abandoned committed nothing, and must not be recorded as though it had. */
    @Test
    fun `an abandoned up-flick is not captured as an accent`() {
        val k = key("c")
        val f = fsm()
        f.onDown(k.centerX, k.centerY, 0)
        f.onMove(k.centerX, k.centerY - armingPull, 40)
        val out = f.onUp(k.centerX, k.centerY - 2f, 80)
        val trace = out.only<GestureOutput.GestureCaptured>().trace
        assertNotNull(trace)
        assertTrue(trace.verdict != GestureVerdict.ACCENT)
    }
}
