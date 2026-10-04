package com.offlinekeyboard.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The granular cursor, drawn over the app being typed into.
 *
 * Two marks:
 *
 * - **The marker**, a solid bar exactly where the finger has put it -- continuous, between
 *   characters and between lines, moving every frame the finger does. This is the thing the user
 *   steers.
 * - **The landing**, a faint bar at the character boundary the caret is being set to. It is drawn
 *   from the keyboard's own map of the text, so it moves in the same frame as the marker, before
 *   the app has redrawn its caret -- the app's caret then appears underneath it.
 *
 * It fills the screen and is shown once per gesture; moving the marks is an `invalidate`, not a
 * window relayout, so it costs nothing per frame. It lives in a PopupWindow because an IME
 * cannot draw inside the target app's window, and its coordinates are screen coordinates, the
 * same as `CursorAnchorInfo`'s once mapped through its matrix.
 *
 * It is deliberately a bare bar with no label: a cursor should read as a cursor.
 */
class CursorIndicatorView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val barWidth = 3f * density
    private val landingWidth = 2f * density

    private var markerX = Float.NaN
    private var markerY = Float.NaN
    private var markerH = 0f
    private var landingX = Float.NaN
    private var landingY = Float.NaN
    private var landingH = 0f

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E68A3FFF") }
    private val landing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#668A3FFF") }
    private val rect = RectF()
    private val origin = IntArray(2)

    /** Moves the marker; [h] is the height of the line it is over. NaN hides it. */
    fun setMarker(x: Float, y: Float, h: Float) {
        if (x == markerX && y == markerY && h == markerH) return
        markerX = x
        markerY = y
        markerH = h
        invalidate()
    }

    /** Moves the landing bar. NaN hides it. */
    fun setLanding(x: Float, y: Float, h: Float) {
        if (x == landingX && y == landingY && h == landingH) return
        landingX = x
        landingY = y
        landingH = h
        invalidate()
    }

    fun clear() {
        setMarker(Float.NaN, Float.NaN, 0f)
        setLanding(Float.NaN, Float.NaN, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        // Screen to view: wherever the window manager actually put the popup, which may be offset
        // by a cutout or the status bar from where it was asked to go.
        getLocationOnScreen(origin)
        val ox = origin[0].toFloat()
        val oy = origin[1].toFloat()
        val overlapping = abs(landingX - markerX) < barWidth && abs(landingY - markerY) < landingH / 2f
        if (!landingX.isNaN() && !landingY.isNaN() && landingH > 0f && !overlapping) {
            val r = landingWidth / 2f
            rect.set(
                landingX - ox - r, landingY - oy - landingH / 2f,
                landingX - ox + r, landingY - oy + landingH / 2f,
            )
            canvas.drawRoundRect(rect, r, r, landing)
        }
        if (!markerX.isNaN() && !markerY.isNaN() && markerH > 0f) {
            val r = barWidth / 2f
            rect.set(
                markerX - ox - r, markerY - oy - markerH / 2f,
                markerX - ox + r, markerY - oy + markerH / 2f,
            )
            canvas.drawRoundRect(rect, r, r, bar)
        }
    }

    private fun abs(v: Float) = if (v < 0f) -v else v
}
