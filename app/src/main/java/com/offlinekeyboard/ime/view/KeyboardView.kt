package com.offlinekeyboard.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import androidx.core.view.WindowInsetsCompat
import com.offlinekeyboard.ime.gesture.GestureConfig
import com.offlinekeyboard.ime.gesture.GestureOutput
import com.offlinekeyboard.ime.gesture.PathPoint
import com.offlinekeyboard.ime.gesture.TouchFsm
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.Metrics
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.Layout
import com.offlinekeyboard.ime.layout.LayoutGeometry

/**
 * Palette sampled pixel-by-pixel from Gboard on the target device, so the keyboard sits in the
 * same visual language as the rest of the system rather than approximating it by eye.
 *
 * The dark values are estimates -- resample them against Gboard in dark mode when tuning.
 */
private data class Theme(
    val background: Int,
    val key: Int,
    val specialKey: Int,
    val keyPressed: Int,
    val text: Int,
    val secondaryText: Int,
    val popup: Int,
    val popupSelected: Int,
    val glideTrail: Int,
) {
    companion object {
        val LIGHT = Theme(
            background = Color.parseColor("#ECEDFB"),
            key = Color.WHITE,
            specialKey = Color.parseColor("#E2DFFF"),
            keyPressed = Color.parseColor("#CFCBEE"),
            text = Color.parseColor("#181B25"),
            secondaryText = Color.parseColor("#6B6B7B"),
            popup = Color.WHITE,
            popupSelected = Color.parseColor("#6750A4"),
            glideTrail = Color.parseColor("#996750A4"),
        )
        val DARK = Theme(
            background = Color.parseColor("#1B1B1F"),
            key = Color.parseColor("#303034"),
            specialKey = Color.parseColor("#45464F"),
            keyPressed = Color.parseColor("#5A5A63"),
            text = Color.parseColor("#E5E1E6"),
            secondaryText = Color.parseColor("#A0A0AC"),
            popup = Color.parseColor("#303034"),
            popupSelected = Color.parseColor("#6750A4"),
            glideTrail = Color.parseColor("#99B0A0E8"),
        )
    }
}

/**
 * Draws the keyboard and turns touches into [GestureOutput]s.
 *
 * One [TouchFsm] per pointer, so two-thumb typing works: each finger runs its own independent
 * tap/flick/glide/trackpad state machine.
 */
private val ICON_KEYS = setOf(
    KeyType.SHIFT,
    KeyType.BACKSPACE,
    KeyType.GLOBE,
    KeyType.MIC,
    KeyType.RETURN,
)

class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onOutput: (List<GestureOutput>) -> Unit = {}

    /** Tapped a suggestion. The index is into [candidates]. */
    var onCandidate: (Int) -> Unit = {}

    /**
     * What the suggestion strip shows: emoji in English, Chinese candidates in the CJK modes.
     * Never English words -- see [com.offlinekeyboard.ime.candidates.EmojiIndex].
     */
    /**
     * Replaces the suggestions while dictation is running. The strip is the only spare row on a
     * keyboard, and a microphone that is listening has to say so somewhere the eye already is.
     */
    var status: String? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var candidates: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            pressedCandidate = -1
            invalidate()
        }

    var layout: Layout = IosLayouts.QWERTY_LOWER
        set(value) {
            field = value
            geometry = null
            requestLayout()
            invalidate()
        }

    private var geometry: LayoutGeometry? = null
    private val config = GestureConfig()
    private val uiHandler = Handler(Looper.getMainLooper())

    private val pointers = mutableMapOf<Int, TouchFsm>()
    private val longPressRunnables = mutableMapOf<Int, Runnable>()

    /**
     * Pointers that were swallowed by an active trackpad rather than starting their own
     * gesture, so their eventual UP does not type anything.
     */
    private val consumedPointers = mutableSetOf<Int>()

    private var highlightedKeyId: String? = null
    private var accentPopup: Triple<KeyRect, List<String>, Int>? = null
    private var glidePath: List<PathPoint> = emptyList()
    private var trackpadActive = false
    private var selecting = false

    /** Index of the suggestion under a finger, for the pressed highlight. */
    private var pressedCandidate = -1
    /** Pointers that went down on the strip, so their UP commits a suggestion, not a key. */
    private val candidatePointers = mutableMapOf<Int, Int>()

    /**
     * Height of the system navigation bar. From targetSdk 35 the IME window is laid out
     * edge-to-edge, so without this the bottom row sits underneath the nav buttons.
     */
    private var navBarInset = 0

    private val theme: Theme
        get() = if (
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        ) Theme.DARK else Theme.LIGHT

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun geometry(): LayoutGeometry =
        geometry ?: LayoutGeometry(layout, width.toFloat()).also { geometry = it }

    /** Bottom of the key area, above the reserved navigation-bar space. */
    private val keyAreaBottom: Float get() = (height - navBarInset).toFloat()

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = WindowInsetsCompat.toWindowInsetsCompat(insets)
            .getInsets(WindowInsetsCompat.Type.navigationBars())
            .bottom
        if (bottom != navBarInset) {
            navBarInset = bottom
            requestLayout()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = Metrics.HEIGHT_IN_KEY_WIDTHS *
            (w / Metrics.REFERENCE_WIDTH * Metrics.KEY_WIDTH)
        // Keys occupy the top; the inset is empty space reserved below them, which keeps
        // drawing and touch coordinates identical (both measured from the top).
        setMeasuredDimension(w, h.toInt() + navBarInset)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geometry = null
    }

    // --- drawing --------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val g = geometry()
        val t = theme
        canvas.drawColor(t.background)

        if (trackpadActive) {
            drawTrackpadHint(canvas, g, t)
            return
        }

        val radius = g.cornerRadius
        drawCandidates(canvas, g, t, radius)
        g.keyRects.forEach { rect -> drawKey(canvas, rect, radius, t, g) }

        if (glidePath.size > 1) drawGlideTrail(canvas, g, t)
        accentPopup?.let { (anchor, accents, selected) ->
            drawAccentPopup(canvas, g, t, anchor, accents, selected, radius)
        }
    }

    /**
     * The suggestion strip. Cells are square and left-aligned rather than stretched to fill the
     * width: emoji are square, and a fixed cell means a suggestion does not jump sideways as
     * the list behind it grows or shrinks with each letter typed.
     */
    private fun drawCandidates(canvas: Canvas, g: LayoutGeometry, t: Theme, radius: Float) {
        status?.let { message ->
            label.color = t.secondaryText
            label.textSize = g.stripHeight * 0.34f
            canvas.drawText(
                message,
                width / 2f,
                g.stripHeight / 2f - (label.descent() + label.ascent()) / 2f,
                label,
            )
            return
        }
        if (candidates.isEmpty()) return
        val cell = candidateCellWidth(g)
        val top = g.stripHeight * 0.12f
        val bottom = g.stripHeight * 0.88f
        label.textSize = (bottom - top) * 0.74f
        candidates.take(visibleCandidateCount(g)).forEachIndexed { i, candidate ->
            val left = g.margin + i * cell
            if (i == pressedCandidate) {
                fill.color = t.keyPressed
                canvas.drawRoundRect(
                    RectF(left + cell * 0.06f, top, left + cell * 0.94f, bottom),
                    radius,
                    radius,
                    fill,
                )
            }
            label.color = t.text
            canvas.drawText(
                candidate,
                left + cell / 2f,
                (top + bottom) / 2f - (label.descent() + label.ascent()) / 2f,
                label,
            )
        }
    }

    private fun candidateCellWidth(g: LayoutGeometry): Float = g.stripHeight

    private fun visibleCandidateCount(g: LayoutGeometry): Int =
        ((width - 2 * g.margin) / candidateCellWidth(g)).toInt().coerceAtLeast(1)

    /** Which suggestion a touch landed on, or -1 for none. */
    private fun candidateAt(x: Float, y: Float, g: LayoutGeometry): Int {
        if (y >= g.stripHeight || candidates.isEmpty() || status != null) return -1
        val i = ((x - g.margin) / candidateCellWidth(g)).toInt()
        return if (i in 0 until minOf(candidates.size, visibleCandidateCount(g))) i else -1
    }

    private fun drawKey(canvas: Canvas, rect: KeyRect, radius: Float, t: Theme, g: LayoutGeometry) {
        val isSpecial = rect.key.type != KeyType.CHARACTER && rect.key.type != KeyType.SPACE
        fill.color = when {
            rect.key.id == highlightedKeyId -> t.keyPressed
            isSpecial -> t.specialKey
            else -> t.key
        }
        canvas.drawRoundRect(
            RectF(rect.left, rect.top, rect.right, rect.bottom),
            radius,
            radius,
            fill,
        )

        // iPadOS flick secondary, small and tucked above the primary glyph
        rect.key.secondary?.let { secondary ->
            label.color = t.secondaryText
            label.textSize = g.keyUnit * 0.30f
            canvas.drawText(secondary, rect.centerX, rect.top + g.keyHeight * 0.28f, label)
        }

        if (rect.key.type in ICON_KEYS) {
            icon.color = t.text
            icon.strokeWidth = g.keyUnit * 0.055f
            KeyIcons.draw(canvas, rect.key.type, rect.centerX, rect.centerY, g.keyUnit * 0.46f, icon)
            return
        }

        val text = rect.key.primary
        if (text.isBlank()) return
        label.color = t.text
        label.textSize = if (rect.key.type == KeyType.CHARACTER) {
            g.keyUnit * 0.62f
        } else {
            g.keyUnit * 0.42f
        }
        val baseline = if (rect.key.secondary != null) {
            rect.centerY + g.keyHeight * 0.24f
        } else {
            rect.centerY - (label.descent() + label.ascent()) / 2f
        }
        canvas.drawText(text, rect.centerX, baseline, label)
    }

    private fun drawGlideTrail(canvas: Canvas, g: LayoutGeometry, t: Theme) {
        trail.color = t.glideTrail
        trail.strokeWidth = g.keyUnit * 0.18f
        val path = Path().apply {
            moveTo(glidePath.first().x, glidePath.first().y)
            glidePath.drop(1).forEach { lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, trail)
    }

    private fun drawAccentPopup(
        canvas: Canvas,
        g: LayoutGeometry,
        t: Theme,
        anchor: KeyRect,
        accents: List<String>,
        selected: Int,
        radius: Float,
    ) {
        val w = accents.size * g.keyUnit
        val left = (anchor.centerX - w / 2f).coerceIn(g.margin, width - g.margin - w)
        val bottom = anchor.top - g.rowGap * 0.4f
        val top = bottom - g.keyHeight * 1.15f

        fill.color = t.popup
        canvas.drawRoundRect(RectF(left, top, left + w, bottom), radius * 2, radius * 2, fill)

        accents.forEachIndexed { i, accent ->
            val cx = left + (i + 0.5f) * g.keyUnit
            if (i == selected) {
                fill.color = t.popupSelected
                canvas.drawRoundRect(
                    RectF(left + i * g.keyUnit, top, left + (i + 1) * g.keyUnit, bottom),
                    radius * 2,
                    radius * 2,
                    fill,
                )
            }
            label.color = if (i == selected) Color.WHITE else t.text
            label.textSize = g.keyUnit * 0.6f
            canvas.drawText(
                accent,
                cx,
                (top + bottom) / 2f - (label.descent() + label.ascent()) / 2f,
                label,
            )
        }
    }

    private fun drawTrackpadHint(canvas: Canvas, g: LayoutGeometry, t: Theme) {
        label.color = t.secondaryText
        label.textSize = g.keyUnit * 0.5f
        canvas.drawText(
            if (selecting) "◀  select  ▶" else "←   ↑   ↓   →",
            width / 2f,
            keyAreaBottom / 2f,
            label,
        )
    }

    // --- touch ----------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = geometry()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                // A second finger while the spacebar trackpad is live starts a selection
                // rather than pressing a key.
                val trackpad = pointers.values.firstOrNull {
                    it.state == com.offlinekeyboard.ime.gesture.GestureState.TRACKPAD
                }
                val candidate =
                    if (trackpad == null) candidateAt(event.getX(i), event.getY(i), g) else -1
                if (trackpad != null) {
                    consumedPointers += id
                    emit(trackpad.onSecondaryTap())
                } else if (candidate >= 0) {
                    candidatePointers[id] = candidate
                    pressedCandidate = candidate
                    invalidate()
                } else {
                    val fsm = TouchFsm(g, config)
                    pointers[id] = fsm
                    emit(fsm.onDown(event.getX(i), event.getY(i), event.eventTime))
                    scheduleLongPress(id, fsm)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    candidatePointers[id]?.let { pressed ->
                        // Sliding off the suggestion cancels it, as sliding off a key does.
                        val still = candidateAt(event.getX(i), event.getY(i), g) == pressed
                        val shown = if (still) pressed else -1
                        if (shown != pressedCandidate) {
                            pressedCandidate = shown
                            invalidate()
                        }
                    }
                    pointers[id]?.let { fsm ->
                        if (fsm.state == com.offlinekeyboard.ime.gesture.GestureState.TRACKPAD ||
                            fsm.state == com.offlinekeyboard.ime.gesture.GestureState.SELECTING
                        ) {
                            android.util.Log.d(
                                "TP",
                                "FINGER t=${event.eventTime} p=$id x=${event.getX(i)} y=${event.getY(i)}",
                            )
                        }
                        emit(fsm.onMove(event.getX(i), event.getY(i), event.eventTime))
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                cancelLongPress(id)
                candidatePointers.remove(id)?.let { pressed ->
                    val committed = pressedCandidate == pressed
                    pressedCandidate = -1
                    invalidate()
                    if (committed) onCandidate(pressed)
                    return true
                }
                if (consumedPointers.remove(id)) return true
                pointers.remove(id)?.let { fsm ->
                    emit(fsm.onUp(event.getX(i), event.getY(i), event.eventTime))
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                pointers.keys.toList().forEach { cancelLongPress(it) }
                pointers.values.forEach { emit(it.onCancel()) }
                pointers.clear()
                consumedPointers.clear()
                candidatePointers.clear()
                pressedCandidate = -1
            }
        }
        return true
    }

    private fun scheduleLongPress(id: Int, fsm: TouchFsm) {
        val runnable = Runnable { emit(fsm.onLongPressTimeout(System.currentTimeMillis())) }
        longPressRunnables[id] = runnable
        uiHandler.postDelayed(runnable, config.longPressMs)
    }

    private fun cancelLongPress(id: Int) {
        longPressRunnables.remove(id)?.let { uiHandler.removeCallbacks(it) }
    }

    /** Applies the visual half of each output, then forwards everything to the service. */
    private fun emit(outputs: List<GestureOutput>) {
        if (outputs.isEmpty()) return
        var repaint = false
        outputs.forEach { out ->
            when (out) {
                is GestureOutput.KeyHighlighted -> { highlightedKeyId = out.keyId; repaint = true }
                GestureOutput.FlickPreviewCleared -> repaint = true
                is GestureOutput.FlickPreview -> repaint = true
                is GestureOutput.ShowAccents -> {
                    geometry().keyRects.firstOrNull { it.key.id == out.keyId }?.let {
                        accentPopup = Triple(it, out.accents, 0)
                    }
                    repaint = true
                }
                is GestureOutput.AccentHighlighted ->
                    accentPopup?.let { accentPopup = it.copy(third = out.index); repaint = true }
                GestureOutput.HideAccents -> { accentPopup = null; repaint = true }
                GestureOutput.GlideStarted -> { glidePath = emptyList(); repaint = true }
                is GestureOutput.GlideUpdated -> { glidePath = out.path; repaint = true }
                is GestureOutput.GlideCompleted -> { glidePath = emptyList(); repaint = true }
                GestureOutput.TrackpadStarted -> { trackpadActive = true; repaint = true }
                GestureOutput.SelectionStarted -> { selecting = true; repaint = true }
                GestureOutput.TrackpadEnded -> {
                    trackpadActive = false; selecting = false; repaint = true
                }
                else -> Unit
            }
        }
        if (repaint) invalidate()
        onOutput(outputs)
    }

    /**
     * Debug only. adb can drive a single pointer, so the two-finger selection gesture cannot be
     * scripted; this lets a broadcast stand in for the second finger while testing.
     */
    fun debugStartSelection() {
        val trackpad = pointers.values.firstOrNull {
            it.state == com.offlinekeyboard.ime.gesture.GestureState.TRACKPAD
        } ?: return
        emit(trackpad.onSecondaryTap())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiHandler.removeCallbacksAndMessages(null)
    }
}
