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
import com.offlinekeyboard.ime.layout.IosMetrics
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.Layout
import com.offlinekeyboard.ime.layout.LayoutGeometry

/** iOS keyboard colours, light and dark. */
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
            background = Color.parseColor("#D1D4DA"),
            key = Color.WHITE,
            specialKey = Color.parseColor("#ADB3BC"),
            keyPressed = Color.parseColor("#BFC4CC"),
            text = Color.BLACK,
            secondaryText = Color.parseColor("#8E8E93"),
            popup = Color.WHITE,
            popupSelected = Color.parseColor("#1A86FF"),
            glideTrail = Color.parseColor("#801A86FF"),
        )
        val DARK = Theme(
            background = Color.parseColor("#2C2C2E"),
            key = Color.parseColor("#6C6C70"),
            specialKey = Color.parseColor("#4A4A4E"),
            keyPressed = Color.parseColor("#8A8A8E"),
            text = Color.WHITE,
            secondaryText = Color.parseColor("#B0B0B4"),
            popup = Color.parseColor("#6C6C70"),
            popupSelected = Color.parseColor("#1A86FF"),
            glideTrail = Color.parseColor("#801A86FF"),
        )
    }
}

/**
 * Draws the keyboard and turns touches into [GestureOutput]s.
 *
 * One [TouchFsm] per pointer, so two-thumb typing works: each finger runs its own independent
 * tap/flick/glide/trackpad state machine.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onOutput: (List<GestureOutput>) -> Unit = {}

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
        val h = IosMetrics.HEIGHT_IN_KEY_WIDTHS *
            (w / IosMetrics.REFERENCE_WIDTH * IosMetrics.KEY_WIDTH)
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

        val radius = 5f * (width / IosMetrics.REFERENCE_WIDTH)
        g.keyRects.forEach { rect -> drawKey(canvas, rect, radius, t, g) }

        if (glidePath.size > 1) drawGlideTrail(canvas, g, t)
        accentPopup?.let { (anchor, accents, selected) ->
            drawAccentPopup(canvas, g, t, anchor, accents, selected, radius)
        }
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

        rect.key.secondary?.let { secondary ->
            label.color = t.secondaryText
            label.textSize = g.keyUnit * 0.34f
            canvas.drawText(secondary, rect.centerX, rect.top + g.keyHeight * 0.30f, label)
        }

        val text = primaryLabel(rect)
        if (text.isNotEmpty()) {
            label.color = t.text
            label.textSize = if (rect.key.type == KeyType.CHARACTER) {
                g.keyUnit * 0.66f
            } else {
                g.keyUnit * 0.42f
            }
            val baseline = if (rect.key.secondary != null) {
                rect.centerY + g.keyHeight * 0.26f
            } else {
                rect.centerY - (label.descent() + label.ascent()) / 2f
            }
            canvas.drawText(text, rect.centerX, baseline, label)
        }
    }

    private fun primaryLabel(rect: KeyRect): String = when (rect.key.type) {
        KeyType.SHIFT -> "⇧"
        KeyType.BACKSPACE -> "⌫"
        KeyType.GLOBE -> "🌐"
        KeyType.MIC -> "🎤"
        KeyType.RETURN -> "return"
        KeyType.SPACE -> ""
        else -> rect.key.primary
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
                if (trackpad != null) {
                    consumedPointers += id
                    emit(trackpad.onSecondaryTap())
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
                    pointers[id]?.let { fsm ->
                        emit(fsm.onMove(event.getX(i), event.getY(i), event.eventTime))
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                cancelLongPress(id)
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiHandler.removeCallbacksAndMessages(null)
    }
}
