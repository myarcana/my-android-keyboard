package com.offlinekeyboard.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The granular cursor: a thin bar placed at the interpolated caret position.
 *
 * The system caret can only sit between characters. While the trackpad is in use the finger is
 * somewhere continuous in between, and this shows exactly where that is -- a fraction of a
 * character across, and a fraction of a line down.
 *
 * It is deliberately a bare marker with no label: it is a cursor, and it should read as one.
 * A distinct colour separates it from the system's own caret sitting at the nearest character
 * boundary.
 *
 * It lives in a PopupWindow because an IME cannot draw inside the target app's text field.
 */
class CursorIndicatorView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val barWidth = 3f * density

    /** Height of the text line the caret is on; set by the service from CursorAnchorInfo. */
    var lineHeightPx: Int = (20f * density).toInt()
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E68A3FFF")
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(barWidth.toInt().coerceAtLeast(1), lineHeightPx.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        val r = barWidth / 2f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, bar)
    }
}
