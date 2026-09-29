package com.offlinekeyboard.ime.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.offlinekeyboard.ime.layout.EditAction
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

    /**
     * The glyph for an edit action in the long-press popup.
     *
     * Drawn rather than lettered, and the reason is the width: a popup slot is one key wide, and
     * "Select all" is not. The icons are the conventional ones -- two stacked pages for copy,
     * a clipboard for paste, scissors for cut -- because a popup that opens under a thumb is
     * read in the moment it appears, and a shape already learned elsewhere is read faster than
     * any word would be. [EditAction.label] carries the name for the cases where there is room
     * for one and for accessibility, which is where the words belong.
     */
    fun drawAction(canvas: Canvas, action: EditAction, cx: Float, cy: Float, size: Float, p: Paint) {
        when (action) {
            EditAction.SELECT_ALL -> selectAll(canvas, cx, cy, size, p)
            EditAction.CUT -> cut(canvas, cx, cy, size, p)
            EditAction.COPY -> copy(canvas, cx, cy, size, p)
            EditAction.PASTE -> paste(canvas, cx, cy, size, p)
            EditAction.UNDO -> undoRedo(canvas, cx, cy, size, p, mirrored = false)
            EditAction.REDO -> undoRedo(canvas, cx, cy, size, p, mirrored = true)
        }
    }

    /** A dashed marquee around a filled block: the selection rectangle, as every tool draws it. */
    private fun selectAll(canvas: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val h = s * 0.42f
        val dash = h * 0.5f
        // Four corners, each an L, leaving the midpoints open -- a marching-ants rectangle that
        // reads as "a region" rather than as a solid box, which would read as a key.
        listOf(
            Triple(cx - h, cy - h, 1f to 1f),
            Triple(cx + h, cy - h, -1f to 1f),
            Triple(cx - h, cy + h, 1f to -1f),
            Triple(cx + h, cy + h, -1f to -1f),
        ).forEach { (x, y, dir) ->
            canvas.drawLine(x, y, x + dir.first * dash, y, p)
            canvas.drawLine(x, y, x, y + dir.second * dash, p)
        }
        val inner = h * 0.34f
        canvas.drawLine(cx - inner, cy, cx + inner, cy, p)
    }

    /** Scissors: two blades crossing over two finger loops. */
    private fun cut(canvas: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val h = s * 0.46f
        val pivotY = cy + h * 0.30f
        val r = h * 0.26f
        canvas.drawLine(cx - h * 0.52f, cy - h, cx + h * 0.34f, pivotY - r * 0.4f, p)
        canvas.drawLine(cx + h * 0.52f, cy - h, cx - h * 0.34f, pivotY - r * 0.4f, p)
        canvas.drawCircle(cx - h * 0.42f, pivotY + r * 0.55f, r, p)
        canvas.drawCircle(cx + h * 0.42f, pivotY + r * 0.55f, r, p)
    }

    /** Two offset pages: the back one peeking out behind the front one. */
    private fun copy(canvas: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.34f
        val h = s * 0.42f
        val off = s * 0.14f
        val r = s * 0.08f
        canvas.drawRoundRect(
            RectF(cx - w - off, cy - h - off, cx + w - off, cy + h - off),
            r,
            r,
            p,
        )
        canvas.drawRoundRect(
            RectF(cx - w + off, cy - h + off, cx + w + off, cy + h + off),
            r,
            r,
            p,
        )
    }

    /** A clipboard: a board with a clip at the top. */
    private fun paste(canvas: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.38f
        val h = s * 0.46f
        val r = s * 0.09f
        canvas.drawRoundRect(RectF(cx - w, cy - h * 0.78f, cx + w, cy + h), r, r, p)
        val clipW = w * 0.52f
        val clipH = h * 0.30f
        canvas.drawRoundRect(
            RectF(cx - clipW, cy - h, cx + clipW, cy - h + clipH),
            r * 0.6f,
            r * 0.6f,
            p,
        )
    }

    /**
     * A U-turn arrow: a bold arrowhead pointing left along the top, with the shaft running right
     * and turning back underneath it. Mirrored for redo, so the head points right.
     *
     * This replaced a 250-degree arc with a small hooked head, which could not be read. At popup
     * size the arc looked like a circle and the head like a flaw in its stroke, so nothing said
     * which way it went, and undo and redo looked the same. This shape is the one iOS and most
     * editors use (`arrow.uturn.backward`). Its direction comes from where the arrowhead is,
     * leading on the left or on the right, and that stays readable even when the curve does not.
     *
     * The head is a closed, filled chevron rather than two stroked lines, because a solid
     * triangle is still clearly an arrowhead at a few millimetres across. One path serves both
     * directions and redo is a mirror of it, so the two stay mirror images whenever the shape is
     * tuned.
     */
    private fun undoRedo(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        s: Float,
        p: Paint,
        mirrored: Boolean,
    ) {
        // Vertical extent runs from the head's upper corner to the bottom of the shaft; this puts
        // the middle of that span on cy.
        val top = cy - s * 0.12f
        val r = s * 0.22f
        val bottom = top + 2f * r
        val turnX = cx + s * 0.18f
        val tipX = cx - s * 0.44f
        val head = s * 0.24f
        val tail = cx - s * 0.12f

        canvas.save()
        if (mirrored) canvas.scale(-1f, 1f, cx, cy)

        // The shaft: from the tail along the bottom, round the turn, and back along the top to
        // just behind the head, so the stroke's round cap is hidden inside the filled triangle.
        val shaft = Path().apply {
            moveTo(tail, bottom)
            lineTo(turnX, bottom)
            arcTo(RectF(turnX - r, top, turnX + r, bottom), 90f, -180f, false)
            lineTo(tipX + head * 0.6f, top)
        }
        canvas.drawPath(shaft, p)

        // The head, filled. The paint is borrowed rather than copied so the icon keeps whatever
        // colour the popup is drawing in, including the white of a highlighted cell.
        val arrow = Path().apply {
            moveTo(tipX, top)
            lineTo(tipX + head, top - head * 0.85f)
            lineTo(tipX + head, top + head * 0.85f)
            close()
        }
        val style = p.style
        p.style = Paint.Style.FILL_AND_STROKE
        canvas.drawPath(arrow, p)
        p.style = style

        canvas.restore()
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
