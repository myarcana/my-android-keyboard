package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.LayoutGeometry
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

data class PathPoint(val x: Float, val y: Float, val t: Long)

/**
 * Gesture thresholds. Ratios rather than pixels so behaviour is identical on any screen
 * density; the debug overlay tunes these by feel on a real device.
 */
data class GestureConfig(
    val longPressMs: Long = 500L,
    /**
     * Downward travel needed to read as a flick, as a fraction of key height.
     *
     * Measured, not guessed: see docs/GESTURE_BANK.md. At the original 0.45 -- 54px on the
     * target phone -- ten of sixty-four recorded flicks never registered at all, six of them on
     * m, where the bottom row leaves nowhere to swipe to. The recorded flicks reach down to
     * 27.8px, and recorded taps travel *zero* pixels, with five to eleven move events all at the
     * identical coordinate. The gap between the two classes is empty, so the number is set by
     * the platform instead: 8dp of touch slop over a 40dp key is 0.20, and below that Android
     * itself still calls the finger stationary.
     */
    val flickDistanceRatio: Float = 0.20f,
    /**
     * How far the finger drags the key's symbol, as a fraction of key height.
     *
     * Not a threshold -- nothing is decided by it. It is the distance the symbol itself has to
     * cover to get from its resting slot to the letter's place, so setting the finger's travel
     * equal to it makes the two move as one: the symbol sits under the thumb and is dragged
     * down, rather than being played back at some speed of its own. That is the whole difference
     * between an animation and a manoeuvre, and the number is therefore the view's glyph geometry
     * (0.74 - 0.28 of key height) rather than anything measured off a hand.
     *
     * Deliberately not [flickDistanceRatio]. A flick commits after 0.20 because that is Android's
     * touch slop and the bank showed nothing between a tap and a flick to separate them better,
     * and the symbol is only 44% of the way home at that point. So the pull has a point of no
     * return partway down it, like any detent: past 0.20 the letter has faded out and releasing
     * gives the symbol, and coming back up above it puts the letter back and gives the letter.
     */
    val flickTravelRatio: Float = 0.46f,
    /**
     * |dy| must exceed this multiple of |dx| for a flick; otherwise it reads as a glide.
     *
     * This is what separates a flick from "ok". On the o key, flicks leave at a ratio of 6.6 or
     * more, while gliding "ok" leaves at 1.6 to 4.3 -- because k is half a key to the left, so
     * the word departs about 25 degrees off vertical and the flick does not. Nothing else
     * separates those two: "ok" is in fact the *straightest* gesture in the bank, straighter
     * than the average flick, several of which hook through 80 degrees at the lift.
     */
    val verticalDominance: Float = 4.25f,
    /**
     * How far the finger may drift and still count as holding still, as a fraction of key
     * height. Beyond this the accent popup will not open, however long the press lasts.
     *
     * A long press means held *still*, which is what it means everywhere else on the platform.
     * Without this, starting a glide slowly opens the accent popup instead: a recorded glide of
     * "on" dawdled for 447ms before picking up speed, and the popup fired at 500ms while the
     * finger was already 24px down the key. Same value as [flickDistanceRatio], and for the same
     * reason -- it is Android's touch slop, the distance below which the platform itself still
     * calls the finger stationary.
     */
    val longPressSlopRatio: Float = 0.20f,
    /**
     * Path length that turns a press into a glide, as a fraction of key width.
     *
     * Briefly 1.4 on a 128-gesture bank, then back to 1.2 when 80 more arrived: a recorded "ok"
     * travelled 122px against the 129px that 1.4 demanded and was read as a plain tap. Short
     * words that stop one row down have very little path to offer.
     */
    val glideDistanceRatio: Float = 1.2f,
    /**
     * Longer path length that promotes an in-progress flick into a glide.
     *
     * The midpoint of the winning range rather than either end of it: an "ex" glide that had
     * already been read as a flick needed 246px to escape, and 2.8 asked for 258px.
     */
    val flickToGlideRatio: Float = 2.5f,
    /**
     * Upward travel on backspace that clears the line, as a fraction of key height. Larger than
     * [flickDistanceRatio] because this gesture destroys text: a thumb drifting off the key
     * must not trigger it, and there is nothing to undo it with.
     */
    val bulkDeleteDistanceRatio: Float = 0.8f,
    /**
     * Trackpad gain: pixels the granular cursor travels per pixel of finger movement.
     * Vertical is deliberately higher -- a line is a much longer journey than a character, and
     * there is less room to move vertically on a keyboard than horizontally.
     */
    val trackpadGainX: Float = 1.17f,
    val trackpadGainY: Float = 1.45f,

    /**
     * Pointer acceleration. Below the slow speed the gain is untouched, so slow movement keeps
     * its fine-grained feel exactly; from there it ramps up to the maximum at the fast speed,
     * letting a quick flick cross a long line. Speeds are in pixels of finger travel per
     * millisecond.
     *
     * The two axes have separate curves on purpose. There is far less vertical room on a
     * keyboard-sized trackpad than horizontal, so vertical has to reach its multiplier sooner
     * and go further to cover a document. The cost is that a fast diagonal drag is steeper than
     * the finger's own path -- acceleration bends the direction rather than only scaling it.
     */
    val trackpadSlowSpeed: Float = 0.15f,
    val trackpadFastSpeed: Float = 2.2f,
    val trackpadMaxAccel: Float = 4f,
    val trackpadSlowSpeedY: Float = 0.10f,
    val trackpadFastSpeedY: Float = 1.1f,
    val trackpadMaxAccelY: Float = 7f,
    /** A single move event is a noisy speed estimate, so it is smoothed. 1 = no smoothing. */
    val trackpadSpeedSmoothing: Float = 0.4f,
)

sealed interface GestureOutput {
    data class KeyHighlighted(val keyId: String?) : GestureOutput
    data class CommitPrimary(val keyId: String, val text: String) : GestureOutput
    data class CommitSecondary(val keyId: String, val text: String) : GestureOutput

    /** Flick is previewed but NOT committed, so it can still become a glide. */
    data class FlickPreview(val keyId: String, val text: String) : GestureOutput
    data object FlickPreviewCleared : GestureOutput

    data class ShowAccents(val keyId: String, val accents: List<String>) : GestureOutput
    data class AccentHighlighted(val index: Int) : GestureOutput
    data class CommitAccent(val keyId: String, val text: String) : GestureOutput
    data object HideAccents : GestureOutput

    data object GlideStarted : GestureOutput
    data class GlideUpdated(val path: List<PathPoint>) : GestureOutput
    data class GlideCompleted(val path: List<PathPoint>) : GestureOutput

    /** Backspace was held: the host should start repeating deletions until it is released. */
    data object BackspaceRepeatStarted : GestureOutput
    data object BackspaceRepeatEnded : GestureOutput

    /** Swipe up on backspace: clear the line, or the whole field if it has only one. */
    data object BulkDelete : GestureOutput

    data object TrackpadStarted : GestureOutput

    /**
     * Move the granular cursor by this many pixels. The gain is already applied.
     *
     * The trackpad drives the *marker*, not the caret: the marker is the thing the finger is
     * directly controlling, so it moves smoothly and continuously in screen space. The caret
     * follows it afterwards, as closely as the text allows.
     */
    data class TrackpadPan(val dx: Float, val dy: Float) : GestureOutput

    /** Selection began: the anchor is dropped wherever the caret currently sits. */
    data object SelectionStarted : GestureOutput
    data object TrackpadEnded : GestureOutput

    data class SpecialKey(val type: KeyType, val keyId: String) : GestureOutput

    /**
     * Every completed gesture, whatever it turned into, with the raw path attached.
     *
     * Emitted unconditionally rather than only while collecting: the state machine is the only
     * place that sees a whole gesture and the verdict it reached in the same breath, and it must
     * not have a second, differently-behaving code path that only runs during data collection.
     * Deciding whether a gesture is worth keeping belongs to whoever is listening.
     */
    data class GestureCaptured(val trace: GestureTrace) : GestureOutput
}

enum class GestureState {
    IDLE,
    PRESSED,
    FLICK,
    GLIDE,
    ACCENTS,
    TRACKPAD,
    SELECTING,

    /** Backspace held down, deleting repeatedly. An upward swipe from here clears the line. */
    BACKSPACE,

    /**
     * The gesture has already done its work and is waiting for the finger to lift. Without it a
     * swipe that clears the line would also delete a character when released.
     */
    SPENT,
}

/**
 * One state machine per pointer. Pure logic: no Android types, an injected timestamp on every
 * event, and no internal clock, so every transition is deterministically testable on the JVM.
 *
 * The transition that matters most is FLICK -> GLIDE. A downward flick is never committed while
 * the finger is still down, so a swipe that starts downward and keeps going becomes a glide
 * rather than typing a stray symbol.
 */
class TouchFsm(
    private val geometry: LayoutGeometry,
    private val config: GestureConfig = GestureConfig(),
) {
    var state: GestureState = GestureState.IDLE
        private set

    private var origin: KeyRect? = null
    private var down: PathPoint? = null
    private val path = mutableListOf<PathPoint>()
    private var pathLength = 0f
    private var accentIndex = 0
    private var trackpadAnchor: PathPoint? = null
    /** Smoothed finger speed in px/ms, for pointer acceleration. */
    private var trackpadSpeed = 0f

    private val flickDistance get() = config.flickDistanceRatio * geometry.keyHeight
    private val flickTravel get() = config.flickTravelRatio * geometry.keyHeight
    private val glideDistance get() = config.glideDistanceRatio * geometry.keyUnit
    private val flickToGlideDistance get() = config.flickToGlideRatio * geometry.keyUnit
    private val bulkDeleteDistance get() = config.bulkDeleteDistanceRatio * geometry.keyHeight

    /** Deadline the host should schedule a [onLongPressTimeout] callback for, or null. */
    val longPressDeadline: Long? get() = down?.let { it.t + config.longPressMs }

    /** The key the finger came down on, or null between gestures. */
    val originKeyId: String? get() = origin?.key?.id

    /**
     * How far the origin key's glyphs should have slid toward their flicked positions, 0..1.
     *
     * The iPadOS animation follows the finger, so the view needs the progress of a flick that has
     * not happened yet -- not just the verdict once it has. It is a property rather than a
     * [GestureOutput] because it changes on every touch sample and means nothing to anyone but
     * the renderer: emitting it would push a per-sample event through the service and into the
     * gesture bank.
     *
     * The conditions are exactly [onMoveWhilePressed]'s, minus the distance that one is testing,
     * so the glyphs move only while a flick is genuinely still possible. A sideways drag or a key
     * with no secondary leaves them at rest, and a flick that grows into a glide drops back to 0
     * and lets them fall home.
     */
    val flickProgress: Float get() = (flickPull / flickTravel).coerceIn(0f, 1f)

    /**
     * True while releasing would commit the secondary. Derived from where the finger is now, not
     * latched when it first crossed: pulling back up disarms it again, which is what makes the
     * gesture something you can change your mind about halfway through.
     *
     * Safe against the bank: of 208 recorded gestures, none crossed 0.20 of a key height and then
     * lifted back above it. Real flicks retract 0.0px at the lift by median and 0.5px at the 90th
     * percentile, and the shortest one recorded still ended 33.7px down against the 24px this
     * asks for -- so no gesture anyone has actually made changes its verdict by being read here
     * instead of at the crossing.
     */
    val flickArmed: Boolean get() = flickPull > flickDistance

    /** Downward travel currently being read as a flick, in pixels. 0 when none is. */
    private val flickPull: Float
        get() {
            if (state != GestureState.PRESSED && state != GestureState.FLICK) return 0f
            val key = origin ?: return 0f
            if (key.key.secondary == null || key.key.type == KeyType.BACKSPACE) return 0f
            val start = down ?: return 0f
            val now = path.lastOrNull() ?: return 0f
            val dy = now.y - start.y
            if (dy <= 0f) return 0f
            // Dominance gates getting into a flick, not staying in one: several recorded flicks
            // hook through 80 degrees at the lift, and the symbol must not fly home because the
            // thumb rolled sideways on its way off the glass.
            if (state == GestureState.PRESSED &&
                dy <= config.verticalDominance * abs(now.x - start.x)
            ) {
                return 0f
            }
            return dy
        }

    fun onDown(x: Float, y: Float, t: Long): List<GestureOutput> {
        reset()
        val key = geometry.keyAt(x, y) ?: return emptyList()
        origin = key
        val p = PathPoint(x, y, t)
        down = p
        path += p
        state = GestureState.PRESSED
        return listOf(GestureOutput.KeyHighlighted(key.key.id))
    }

    fun onLongPressTimeout(t: Long): List<GestureOutput> {
        if (state != GestureState.PRESSED) return emptyList()
        val key = origin ?: return emptyList()
        return when {
            key.key.type == KeyType.SPACE -> {
                state = GestureState.TRACKPAD
                trackpadAnchor = path.last()
                trackpadSpeed = 0f
                listOf(GestureOutput.TrackpadStarted)
            }
            key.key.type == KeyType.BACKSPACE -> {
                state = GestureState.BACKSPACE
                listOf(GestureOutput.BackspaceRepeatStarted)
            }
            // Deliberately not applied to the space bar above: holding space and starting to
            // move before the timeout is the normal way into the trackpad, and there is no
            // glide competing for that gesture. The conflict is only ever accents versus a
            // slow-starting glide.
            key.key.accents.isNotEmpty() && !hasDrifted() -> {
                state = GestureState.ACCENTS
                accentIndex = 0
                listOf(
                    GestureOutput.ShowAccents(key.key.id, key.key.accents),
                    GestureOutput.AccentHighlighted(0),
                )
            }
            else -> emptyList()
        }
    }

    /** True once the finger has travelled far enough that it is no longer holding still. */
    private fun hasDrifted(): Boolean {
        val start = down ?: return false
        val now = path.lastOrNull() ?: return false
        val limit = config.longPressSlopRatio * geometry.keyHeight
        return hypot(now.x - start.x, now.y - start.y) > limit
    }

    fun onMove(x: Float, y: Float, t: Long): List<GestureOutput> {
        if (state == GestureState.IDLE) return emptyList()
        val previous = path.lastOrNull() ?: return emptyList()
        val p = PathPoint(x, y, t)
        pathLength += hypot(x - previous.x, y - previous.y)
        path += p

        val start = down ?: return emptyList()
        val dx = x - start.x
        val dy = y - start.y

        return when (state) {
            GestureState.PRESSED -> onMoveWhilePressed(dx, dy)
            GestureState.FLICK -> onMoveWhileFlicking()
            GestureState.GLIDE -> listOf(GestureOutput.GlideUpdated(path.toList()))
            GestureState.ACCENTS -> onMoveWhileShowingAccents(x)
            GestureState.TRACKPAD, GestureState.SELECTING -> onMoveWhileTrackpad(x, y, t)
            GestureState.BACKSPACE -> bulkDeleteIfSwipedUp(dy)
            GestureState.IDLE, GestureState.SPENT -> emptyList()
        }
    }

    private fun onMoveWhilePressed(dx: Float, dy: Float): List<GestureOutput> {
        val key = origin ?: return emptyList()
        val isDownward = dy > 0
        val verticallyDominant = abs(dy) > config.verticalDominance * abs(dx)

        if (key.key.type == KeyType.BACKSPACE) {
            // The requirement is "hold, then swipe up", but a swipe up without the hold means
            // the same thing and there is nothing else an upward swipe from backspace could be.
            return bulkDeleteIfSwipedUp(dy)
        }
        if (isDownward && verticallyDominant && abs(dy) > flickDistance && key.key.secondary != null) {
            state = GestureState.FLICK
            return listOf(GestureOutput.FlickPreview(key.key.id, key.key.secondary))
        }
        // Only letters can start a word: a swipe off shift or 123 is a mis-hit, not a glide.
        if (pathLength > glideDistance && key.key.type == KeyType.CHARACTER) {
            state = GestureState.GLIDE
            return listOf(GestureOutput.GlideStarted, GestureOutput.GlideUpdated(path.toList()))
        }
        return emptyList()
    }

    /**
     * Clears the line, once. The gesture goes SPENT rather than back to PRESSED so that lifting
     * the finger afterwards does not also delete a character, and so a wobbling finger that
     * crosses the threshold repeatedly cannot clear line after line.
     */
    private fun bulkDeleteIfSwipedUp(dy: Float): List<GestureOutput> {
        if (dy > -bulkDeleteDistance) return emptyList()
        val wasRepeating = state == GestureState.BACKSPACE
        state = GestureState.SPENT
        return buildList {
            if (wasRepeating) add(GestureOutput.BackspaceRepeatEnded)
            add(GestureOutput.BulkDelete)
        }
    }

    /**
     * Requirement 4: a flick that keeps travelling becomes a glide. The flick was only ever a
     * preview, so nothing needs to be undone in the editor -- just clear the preview.
     */
    private fun onMoveWhileFlicking(): List<GestureOutput> {
        if (pathLength > flickToGlideDistance) {
            state = GestureState.GLIDE
            return listOf(
                GestureOutput.FlickPreviewCleared,
                GestureOutput.GlideStarted,
                GestureOutput.GlideUpdated(path.toList()),
            )
        }
        return emptyList()
    }

    private fun onMoveWhileShowingAccents(x: Float): List<GestureOutput> {
        val key = origin ?: return emptyList()
        val accents = key.key.accents
        if (accents.isEmpty()) return emptyList()
        // The popup is centred on the key and one key-width per accent.
        val popupLeft = key.centerX - accents.size * geometry.keyUnit / 2f
        val index = ((x - popupLeft) / geometry.keyUnit).toInt().coerceIn(0, accents.size - 1)
        if (index == accentIndex) return emptyList()
        accentIndex = index
        return listOf(GestureOutput.AccentHighlighted(index))
    }

    /**
     * Requirement 5: two-dimensional cursor movement. Vertical steps are emitted as their own
     * events so the host can send DPAD_UP/DOWN -- only the text view knows where lines wrap.
     */
    /**
     * A second finger tapped while the trackpad is active. The caret stops being a caret and
     * becomes one end of a selection: the anchor stays where it is and subsequent movement
     * drags the other end.
     */
    fun onSecondaryTap(): List<GestureOutput> {
        if (state != GestureState.TRACKPAD) return emptyList()
        state = GestureState.SELECTING
        return listOf(GestureOutput.SelectionStarted)
    }

    /**
     * Requirement 5. Pans the granular cursor by the finger's movement, scaled by the gain.
     *
     * Nothing here knows about characters or lines: this is pure screen-space motion, which is
     * what keeps the marker smooth. Turning that position into a caret position is the
     * service's job, and it does it by watching where the app reports the caret to be.
     */
    private fun onMoveWhileTrackpad(x: Float, y: Float, t: Long): List<GestureOutput> {
        val anchor = trackpadAnchor ?: return emptyList()
        val dx = x - anchor.x
        val dy = y - anchor.y
        val dt = (t - anchor.t).coerceAtLeast(1L)
        trackpadAnchor = PathPoint(x, y, t)
        if (dx == 0f && dy == 0f) return emptyList()

        val instant = hypot(dx, dy) / dt
        trackpadSpeed += (instant - trackpadSpeed) * config.trackpadSpeedSmoothing

        return listOf(
            GestureOutput.TrackpadPan(
                dx * config.trackpadGainX * accelerationFor(
                    trackpadSpeed,
                    config.trackpadSlowSpeed,
                    config.trackpadFastSpeed,
                    config.trackpadMaxAccel,
                ),
                dy * config.trackpadGainY * accelerationFor(
                    trackpadSpeed,
                    config.trackpadSlowSpeedY,
                    config.trackpadFastSpeedY,
                    config.trackpadMaxAccelY,
                ),
            ),
        )
    }

    /**
     * Maps finger speed to a gain multiplier.
     *
     * Squared rather than linear so the curve leaves slow movement alone: precise positioning
     * should feel exactly as it did before acceleration existed, and only deliberate fast
     * movement should cover ground.
     */
    private fun accelerationFor(speed: Float, lo: Float, hi: Float, max: Float): Float {
        if (hi <= lo) return 1f
        val ramp = ((speed - lo) / (hi - lo)).coerceIn(0f, 1f)
        return 1f + (max - 1f) * ramp * ramp
    }

    fun onUp(x: Float, y: Float, t: Long): List<GestureOutput> {
        val key = origin
        // Read from the lift point itself, not from the last move: a device does not always send
        // a move at the position the finger left from, and those last few pixels are exactly the
        // part of the pull that decides it.
        val armedAtRelease = down?.let { y - it.y > flickDistance } ?: false
        val result: List<GestureOutput> = when (state) {
            GestureState.PRESSED -> tapOutput(key)
            GestureState.FLICK -> {
                val secondary = key?.key?.secondary
                when {
                    secondary == null -> emptyList()
                    // Pulled down and then brought back up: the symbol never landed, so this was
                    // a keypress with a wobble in it.
                    !armedAtRelease -> listOf(GestureOutput.FlickPreviewCleared) + tapOutput(key)
                    else -> listOf(
                        GestureOutput.FlickPreviewCleared,
                        GestureOutput.CommitSecondary(key.key.id, secondary),
                    )
                }
            }
            GestureState.GLIDE -> listOf(GestureOutput.GlideCompleted(path.toList()))
            GestureState.ACCENTS -> {
                val accents = key?.key?.accents.orEmpty()
                buildList {
                    add(GestureOutput.HideAccents)
                    accents.getOrNull(accentIndex)?.let {
                        add(GestureOutput.CommitAccent(key!!.key.id, it))
                    }
                }
            }
            GestureState.TRACKPAD, GestureState.SELECTING -> listOf(GestureOutput.TrackpadEnded)
            // The repeat already deleted; releasing must not delete once more.
            GestureState.BACKSPACE -> listOf(GestureOutput.BackspaceRepeatEnded)
            GestureState.IDLE, GestureState.SPENT -> emptyList()
        }
        val captured = capture(PathPoint(x, y, t), armedAtRelease)
        reset()
        return result + listOfNotNull(captured) + GestureOutput.KeyHighlighted(null)
    }

    /** What releasing a key with no gesture on it does. */
    private fun tapOutput(key: KeyRect?): List<GestureOutput> = when {
        key == null -> emptyList()
        key.key.type == KeyType.CHARACTER || key.key.type == KeyType.SPACE ||
            key.key.type == KeyType.RETURN ->
            listOf(GestureOutput.CommitPrimary(key.key.id, key.key.primary))
        else -> listOf(GestureOutput.SpecialKey(key.key.type, key.key.id))
    }

    /**
     * Snapshots the gesture that just ended, for [GestureOutput.GestureCaptured].
     *
     * The up point is appended unless the device already sent that exact sample. Android usually
     * sends a move at the lift position first, but not always, and the last few pixels before
     * the lift are exactly the part of a downward swipe that decides what it was. The timestamp
     * counts as part of "exact": a tap that never moved still has to record when it ended, or a
     * replay of it has no duration and cannot tell a tap from a long press.
     */
    private fun capture(up: PathPoint, armed: Boolean): GestureOutput.GestureCaptured? {
        val key = origin ?: return null
        val last = path.lastOrNull() ?: return null
        val full = if (last == up) path.toList() else path + up
        return GestureOutput.GestureCaptured(
            GestureTrace(
                startKeyId = key.key.id,
                verdict = when (state) {
                    GestureState.PRESSED -> GestureVerdict.TAP
                    GestureState.FLICK -> if (armed) GestureVerdict.FLICK else GestureVerdict.TAP
                    GestureState.GLIDE -> GestureVerdict.GLIDE
                    GestureState.ACCENTS -> GestureVerdict.ACCENT
                    GestureState.TRACKPAD, GestureState.SELECTING -> GestureVerdict.TRACKPAD
                    GestureState.BACKSPACE, GestureState.SPENT, GestureState.IDLE ->
                        GestureVerdict.NONE
                },
                layoutId = geometry.layout.id,
                widthPx = geometry.widthPx,
                keyUnitPx = geometry.keyUnit,
                keyHeightPx = geometry.keyHeight,
                thresholds = GestureThresholds.of(config),
                path = full,
            ),
        )
    }

    fun onCancel(): List<GestureOutput> {
        val wasGlide = state == GestureState.GLIDE
        // Must still emit TrackpadEnded: the service holds a physical shift key down for the
        // duration of a selection, and would otherwise never release it.
        val wasTrackpad = state == GestureState.TRACKPAD || state == GestureState.SELECTING
        val wasRepeating = state == GestureState.BACKSPACE
        reset()
        return buildList {
            if (wasGlide) add(GestureOutput.FlickPreviewCleared)
            if (wasTrackpad) add(GestureOutput.TrackpadEnded)
            if (wasRepeating) add(GestureOutput.BackspaceRepeatEnded)
            add(GestureOutput.KeyHighlighted(null))
        }
    }

    private fun reset() {
        state = GestureState.IDLE
        origin = null
        down = null
        path.clear()
        pathLength = 0f
        accentIndex = 0
        trackpadAnchor = null
        trackpadSpeed = 0f
    }

    /** Keys the glide path passed through, nearest-centre per sample, de-duplicated. */
    fun pathKeys(points: List<PathPoint>): List<String> =
        points.mapNotNull { geometry.nearestKey(it.x, it.y)?.key?.id }
            .fold(mutableListOf<String>()) { acc, id ->
                if (acc.lastOrNull() != id) acc.add(id)
                acc
            }

    @Suppress("unused")
    private fun stepsFor(distance: Float, step: Float): Int = (distance / step).roundToInt()
}
