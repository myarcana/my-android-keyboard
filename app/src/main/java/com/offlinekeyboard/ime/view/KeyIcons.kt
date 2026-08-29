package com.offlinekeyboard.ime.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.offlinekeyboard.ime.layout.KeyType

/**
 * Vector key glyphs, drawn as paths.
 *
 * Colour emoji were used for the globe and microphone at first and looked amateurish next to
 * Gboard, which uses flat monochrome icons throughout. These are stroked paths sized relative
 * to the key, so they stay crisp at any density and follow the theme's text colour.
 */
object KeyIcons {

    fun draw(canvas: Canvas, type: KeyType, cx: Float, cy: Float, size: Float, paint: Paint) {
        val w = size
        val h = size
        when (type) {
            KeyType.SHIFT -> shift(canvas, cx, cy, w, h, paint)
            KeyType.BACKSPACE -> backspace(canvas, cx, cy, w, h, paint)
            KeyType.GLOBE -> globe(canvas, cx, cy, w, paint)
            KeyType.MIC -> mic(canvas, cx, cy, w, h, paint)
            KeyType.RETURN -> enter(canvas, cx, cy, w, h, paint)
            else -> Unit
        }
    }

    /** Outlined up arrow: triangular head over a narrower stem. */
    private fun shift(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, p: Paint) {
        val hw = w / 2f
        val hh = h / 2f
        val stem = w * 0.22f
        val path = Path().apply {
            moveTo(cx, cy - hh)
            lineTo(cx + hw, cy + hh * 0.05f)
            lineTo(cx + stem, cy + hh * 0.05f)
            lineTo(cx + stem, cy + hh)
            lineTo(cx - stem, cy + hh)
            lineTo(cx - stem, cy + hh * 0.05f)
            lineTo(cx - hw, cy + hh * 0.05f)
            close()
        }
        canvas.drawPath(path, p)
    }

    /** Pentagon pointing left, with a cross inside. */
    private fun backspace(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, p: Paint) {
        val hw = w / 2f
        val hh = h * 0.36f
        val path = Path().apply {
            moveTo(cx - hw, cy)
            lineTo(cx - hw * 0.35f, cy - hh)
            lineTo(cx + hw, cy - hh)
            lineTo(cx + hw, cy + hh)
            lineTo(cx - hw * 0.35f, cy + hh)
            close()
        }
        canvas.drawPath(path, p)

        val x = hh * 0.42f
        val ox = cx + hw * 0.28f
        canvas.drawLine(ox - x, cy - x, ox + x, cy + x, p)
        canvas.drawLine(ox + x, cy - x, ox - x, cy + x, p)
    }

    /** Circle with a meridian and an equator. */
    private fun globe(canvas: Canvas, cx: Float, cy: Float, w: Float, p: Paint) {
        val r = w / 2f
        canvas.drawCircle(cx, cy, r, p)
        canvas.drawLine(cx - r, cy, cx + r, cy, p)
        canvas.drawOval(RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), p)
    }

    /** Capsule with a arc cradle and a short stand. */
    private fun mic(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, p: Paint) {
        val capsuleW = w * 0.42f
        val top = cy - h * 0.45f
        val bottom = cy + h * 0.08f
        canvas.drawRoundRect(
            RectF(cx - capsuleW / 2f, top, cx + capsuleW / 2f, bottom),
            capsuleW / 2f,
            capsuleW / 2f,
            p,
        )
        val cradle = w * 0.36f
        canvas.drawArc(
            RectF(cx - cradle, cy - cradle * 0.55f, cx + cradle, cy + cradle * 0.95f),
            10f,
            160f,
            false,
            p,
        )
        canvas.drawLine(cx, cy + cradle * 0.72f, cx, cy + h * 0.45f, p)
    }

    /** Arrow that drops down then turns left, like a return key. */
    private fun enter(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, p: Paint) {
        val right = cx + w * 0.42f
        val left = cx - w * 0.42f
        val top = cy - h * 0.32f
        val bottom = cy + h * 0.24f
        val path = Path().apply {
            moveTo(right, top)
            lineTo(right, bottom)
            lineTo(left, bottom)
        }
        canvas.drawPath(path, p)
        val head = w * 0.22f
        canvas.drawLine(left, bottom, left + head, bottom - head, p)
        canvas.drawLine(left, bottom, left + head, bottom + head, p)
    }
}
