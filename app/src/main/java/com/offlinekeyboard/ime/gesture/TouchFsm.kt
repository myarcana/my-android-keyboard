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
    /** Travel per cursor step in trackpad mode. */
    val trackpadStepXRatio: Float = 0.5f,
    val trackpadStepYRatio: Float = 0.9f,
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
    /** Cursor steps; +x is right, +y is down. Emitted one step at a time. */
    data class CursorMove(val dx: Int, val dy: Int) : GestureOutput
    data object TrackpadEnded : GestureOutput

    data class SpecialKey(val type: KeyType, val keyId: String) : GestureOutput
}

enum class GestureState { IDLE, PRESSED, FLICK, GLIDE, ACCENTS, TRACKPAD }

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
    private var residualX = 0f
    private var residualY = 0f

    private val flickDistance get() = config.flickDistanceRatio * geometry.keyHeight
    private val glideDistance get() = config.glideDistanceRatio * geometry.keyUnit
    private val flickToGlideDistance get() = config.flickToGlideRatio * geometry.keyUnit
    private val trackpadStepX get() = config.trackpadStepXRatio * geometry.keyUnit
    private val trackpadStepY get() = config.trackpadStepYRatio * geometry.keyHeight

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
                residualX = 0f
                residualY = 0f
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
            GestureState.TRACKPAD -> onMoveWhileTrackpad(x, y)
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
    private fun onMoveWhileTrackpad(x: Float, y: Float): List<GestureOutput> {
        val anchor = trackpadAnchor ?: return emptyList()
        residualX += x - anchor.x
        residualY += y - anchor.y
        trackpadAnchor = PathPoint(x, y, anchor.t)

        val out = mutableListOf<GestureOutput>()
        while (abs(residualX) >= trackpadStepX) {
            val step = if (residualX > 0) 1 else -1
            residualX -= step * trackpadStepX
            out += GestureOutput.CursorMove(step, 0)
        }
        while (abs(residualY) >= trackpadStepY) {
            val step = if (residualY > 0) 1 else -1
            residualY -= step * trackpadStepY
            out += GestureOutput.CursorMove(0, step)
        }
        return out
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
            GestureState.TRACKPAD -> listOf(GestureOutput.TrackpadEnded)
            GestureState.IDLE -> emptyList()
        }
        reset()
        return result + GestureOutput.KeyHighlighted(null)
    }

    fun onCancel(): List<GestureOutput> {
        val wasGlide = state == GestureState.GLIDE
        reset()
        return buildList {
            if (wasGlide) add(GestureOutput.FlickPreviewCleared)
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
        residualX = 0f
        residualY = 0f
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
