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
        val pillBottom = height - pointerHeight - stalkHeight
        val radius = pillBottom / 2f

        canvas.drawRoundRect(RectF(0f, 0f, w, pillBottom), radius, radius, pill)

        // downward pointer, then a hairline stalk down to the exact granular point
        val cx = w / 2f
        canvas.drawPath(
            Path().apply {
                moveTo(cx - pointerHeight, pillBottom)
                lineTo(cx + pointerHeight, pillBottom)
                lineTo(cx, pillBottom + pointerHeight)
                close()
            },
            pill,
        )
        canvas.drawLine(cx, pillBottom + pointerHeight, cx, height.toFloat(), stalk)

        canvas.drawText(
            label,
            cx,
            pillBottom / 2f - (text.descent() + text.ascent()) / 2f,
            text,
        )
    }

    /** Vertical distance from the view's top to the point it is pointing at. */
    val pointerOffsetY: Int get() = height
}
