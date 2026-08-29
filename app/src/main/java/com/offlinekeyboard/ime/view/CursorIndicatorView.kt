package com.offlinekeyboard.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * A small floating pill that shows where the *granular* cursor is.
 *
 * The caret in a text field can only sit between characters, but during trackpad movement the
 * finger is somewhere continuous in between. This renders that intermediate position: the pill
 * is placed at the interpolated point and its label reports how far through the current
 * character and line the finger has travelled.
 *
 * It lives in a PopupWindow rather than in the keyboard view, because an IME cannot draw inside
 * the target application -- but it can position its own window anywhere on screen.
 */
class CursorIndicatorView(context: Context) : View(context) {

    var label: String = ""
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** True when the pill hangs below the caret, pointing up at it, for carets near the top. */
    var pointsUp: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6322F3D") }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 11f * density
    }
    private val stalk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6322F3D")
        strokeWidth = 1.5f * density
    }

    private val pointerHeight = 5f * density
    private val stalkHeight = 10f * density

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = (text.measureText(label.ifEmpty { "000%  000%" }) + 18f * density).toInt()
        val h = (24f * density + pointerHeight + stalkHeight).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val pillHeight = h - pointerHeight - stalkHeight
        val radius = pillHeight / 2f

        val pillTop = if (pointsUp) h - pillHeight else 0f
        val pillBottom = pillTop + pillHeight
        canvas.drawRoundRect(RectF(0f, pillTop, w, pillBottom), radius, radius, pill)

        // pointer and a hairline stalk running to the exact granular point
        val tip = if (pointsUp) 0f else h
        val base = if (pointsUp) pillTop else pillBottom
        val pointerTip = if (pointsUp) base - pointerHeight else base + pointerHeight
        canvas.drawPath(
            Path().apply {
                moveTo(cx - pointerHeight, base)
                lineTo(cx + pointerHeight, base)
                lineTo(cx, pointerTip)
                close()
            },
            pill,
        )
        canvas.drawLine(cx, pointerTip, cx, tip, stalk)

        canvas.drawText(
            label,
            cx,
            (pillTop + pillBottom) / 2f - (text.descent() + text.ascent()) / 2f,
            text,
        )
    }

    /** Vertical distance from the view's top to the point it is pointing at. */
    val pointerOffsetY: Int get() = height
}
