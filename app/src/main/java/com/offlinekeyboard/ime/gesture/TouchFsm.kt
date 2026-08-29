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
    /** Downward travel needed to read as a flick, as a fraction of key height. */
    val flickDistanceRatio: Float = 0.45f,
    /** |dy| must exceed this multiple of |dx| for a flick; otherwise it reads as a glide. */
    val verticalDominance: Float = 1.5f,
    /** Path length that turns a press into a glide, as a fraction of key width. */
    val glideDistanceRatio: Float = 1.2f,
    /** Longer path length that promotes an in-progress flick into a glide. */
    val flickToGlideRatio: Float = 2.0f,
    /**
     * Trackpad gain: pixels the granular cursor travels per pixel of finger movement.
     * Vertical is deliberately higher -- a line is a much longer journey than a character, and
     * there is less room to move vertically on a keyboard than horizontally.
     */
    val trackpadGainX: Float = 0.55f,
    val trackpadGainY: Float = 1.2f,

    /**
     * Pointer acceleration. Below [trackpadSlowSpeed] the gain is untouched, so slow movement
     * keeps its fine-grained feel exactly; from there it ramps up to [trackpadMaxAccel] times
     * at [trackpadFastSpeed], letting a quick flick cross a long line. Speeds are in pixels of
     * finger travel per millisecond.
     */
    val trackpadSlowSpeed: Float = 0.15f,
    val trackpadFastSpeed: Float = 2.2f,
    val trackpadMaxAccel: Float = 4f,
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
}

enum class GestureState { IDLE, PRESSED, FLICK, GLIDE, ACCENTS, TRACKPAD, SELECTING }

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
    private val glideDistance get() = config.glideDistanceRatio * geometry.keyUnit
    private val flickToGlideDistance get() = config.flickToGlideRatio * geometry.keyUnit

    /** Deadline the host should schedule a [onLongPressTimeout] callback for, or null. */
    val longPressDeadline: Long? get() = down?.let { it.t + config.longPressMs }

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
            key.key.accents.isNotEmpty() -> {
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
            GestureState.IDLE -> emptyList()
        }
    }

    private fun onMoveWhilePressed(dx: Float, dy: Float): List<GestureOutput> {
        val key = origin ?: return emptyList()
        val isDownward = dy > 0
        val verticallyDominant = abs(dy) > config.verticalDominance * abs(dx)

        if (isDownward && verticallyDominant && abs(dy) > flickDistance && key.key.secondary != null) {
            state = GestureState.FLICK
            return listOf(GestureOutput.FlickPreview(key.key.id, key.key.secondary))
        }
        if (pathLength > glideDistance) {
            state = GestureState.GLIDE
            return listOf(GestureOutput.GlideStarted, GestureOutput.GlideUpdated(path.toList()))
        }
        return emptyList()
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
        val accel = accelerationFor(trackpadSpeed)

        // The same factor on both axes, so acceleration never bends the direction of travel.
        return listOf(
            GestureOutput.TrackpadPan(
                dx * config.trackpadGainX * accel,
                dy * config.trackpadGainY * accel,
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
    private fun accelerationFor(speed: Float): Float {
        val lo = config.trackpadSlowSpeed
        val hi = config.trackpadFastSpeed
        if (hi <= lo) return 1f
        val ramp = ((speed - lo) / (hi - lo)).coerceIn(0f, 1f)
        return 1f + (config.trackpadMaxAccel - 1f) * ramp * ramp
    }

    fun onUp(x: Float, y: Float, t: Long): List<GestureOutput> {
        val key = origin
        val result: List<GestureOutput> = when (state) {
            GestureState.PRESSED -> when {
                key == null -> emptyList()
                key.key.type == KeyType.CHARACTER || key.key.type == KeyType.SPACE ||
                    key.key.type == KeyType.RETURN ->
                    listOf(GestureOutput.CommitPrimary(key.key.id, key.key.primary))
                else -> listOf(GestureOutput.SpecialKey(key.key.type, key.key.id))
            }
            GestureState.FLICK ->
                key?.key?.secondary?.let {
                    listOf(
                        GestureOutput.FlickPreviewCleared,
                        GestureOutput.CommitSecondary(key.key.id, it),
                    )
                } ?: emptyList()
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
            GestureState.IDLE -> emptyList()
        }
        reset()
        return result + GestureOutput.KeyHighlighted(null)
    }

    fun onCancel(): List<GestureOutput> {
        val wasGlide = state == GestureState.GLIDE
        // Must still emit TrackpadEnded: the service holds a physical shift key down for the
        // duration of a selection, and would otherwise never release it.
        val wasTrackpad = state == GestureState.TRACKPAD || state == GestureState.SELECTING
        reset()
        return buildList {
            if (wasGlide) add(GestureOutput.FlickPreviewCleared)
            if (wasTrackpad) add(GestureOutput.TrackpadEnded)
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
