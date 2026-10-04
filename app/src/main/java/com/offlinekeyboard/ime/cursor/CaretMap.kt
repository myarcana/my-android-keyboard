package com.offlinekeyboard.ime.cursor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A screen-space rectangle.
 *
 * Plain Kotlin rather than `android.graphics.RectF` so that everything in this package that is
 * pure geometry -- building the map, hit-testing it, steering on it -- runs in the JVM unit tests.
 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val height: Float get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f
    val isValid: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            bottom > top && right >= left
}

/**
 * One character as an editor reported it, already mapped to screen coordinates.
 *
 * @param offset absolute offset of the character in the editor's text.
 * @param box where it is drawn, or null when the editor gave nothing usable for it.
 * @param newline a hard line break. Its caret stop sits at the end of the row it terminates.
 * @param boundary whether a caret may sit just before this character. False inside a grapheme
 *   cluster -- between the halves of a surrogate pair, after a zero-width joiner -- where a caret
 *   would split one visible character in two.
 * @param line the editor's own line index when it says (TextBoundsInfo does); -1 to infer rows
 *   from the geometry (composing-region bounds carry none).
 * @param rtl the character runs right to left, so the caret before it is at its right edge.
 * @param collapsible whitespace. An editor may give it no rectangle at all -- the space a soft
 *   wrap swallows -- and the caret before it is then the end of the row it follows.
 * @param hidden the editor says the character is clipped out of view (scrolled out of its box).
 */
data class CharBox(
    val offset: Int,
    val box: Box?,
    val newline: Boolean = false,
    val boundary: Boolean = true,
    val line: Int = -1,
    val rtl: Boolean = false,
    val collapsible: Boolean = false,
    val hidden: Boolean = false,
)

/**
 * One visual row of text: every place the caret can stand on it, in text order.
 *
 * `offsets[i]` is drawn at `xs[i]`. Rows tile the text without overlapping offsets, which is the
 * property that keeps the caret from jumping: an offset at a soft wrap belongs to exactly one row,
 * the one the editor actually draws it on (the start of the next row), so the map never predicts
 * the caret somewhere the editor will not put it.
 */
class Row(
    val top: Float,
    val bottom: Float,
    val offsets: IntArray,
    val xs: FloatArray,
    /** Begins where the row before it soft-wrapped, so its first stop is also that row's end. */
    val softStart: Boolean = false,
    /** An empty line, placed by interpolation rather than measured. */
    val estimated: Boolean = false,
    /** Every glyph on it was reported clipped out of view. */
    val hidden: Boolean = false,
) {
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = bottom - top
    val size: Int get() = offsets.size

    /** Average distance between neighbouring stops: the row's character width, near enough. */
    val gap: Float = if (xs.size > 1) {
        ((xs.maxOrNull()!! - xs.minOrNull()!!) / (xs.size - 1)).coerceAtLeast(1f)
    } else {
        (height * 0.4f).coerceAtLeast(1f)
    }

    fun translated(dx: Float, dy: Float) =
        Row(
            top + dy, bottom + dy, offsets, FloatArray(xs.size) { xs[it] + dx },
            softStart, estimated, hidden,
        )

    /**
     * Whether the editor may draw the caret at stop [s] somewhere other than this row. The first
     * stop after a soft wrap is the same offset as the end of the row above, and editors disagree
     * about which of the two they draw it at.
     */
    fun ambiguous(s: Int): Boolean = estimated || (softStart && s == 0)
}

/** A caret position on the map: a row, and a stop along it. */
data class Hit(val row: Int, val stop: Int)

/**
 * Where every caret position in a stretch of text is drawn on screen.
 *
 * This is what lets the trackpad set the caret *absolutely*. The finger moves a marker in screen
 * pixels; the map turns the marker into a text offset locally, with no round trip to the app; and
 * the offset goes to the editor as one `setSelection`. There is no feedback loop to lag, no
 * estimate to accumulate error, and no arrow key whose effect has to be waited for.
 *
 * @param atTextStart the first row begins at the start of the text, so there is nothing above.
 * @param atTextEnd the last row ends at the end of the text, so there is nothing below.
 */
class CaretMap(val rows: List<Row>, val atTextStart: Boolean, val atTextEnd: Boolean) {

    val isEmpty: Boolean get() = rows.isEmpty()
    val top: Float get() = rows.minOf { it.top }
    val bottom: Float get() = rows.maxOf { it.bottom }
    val minX: Float get() = rows.minOf { r -> r.xs.minOrNull() ?: Float.POSITIVE_INFINITY }
    val maxX: Float get() = rows.maxOf { r -> r.xs.maxOrNull() ?: Float.NEGATIVE_INFINITY }

    /** Median row height, for scaling thresholds when no particular row is in question. */
    val typicalRowHeight: Float =
        rows.map { it.height }.sorted().let { if (it.isEmpty()) 40f else it[it.size / 2] }

    val typicalGap: Float =
        rows.filter { it.size > 1 }.map { it.gap }.sorted()
            .let { if (it.isEmpty()) typicalRowHeight * 0.4f else it[it.size / 2] }

    fun offsetOf(hit: Hit): Int = rows[hit.row].offsets[hit.stop]

    /**
     * The map position the editor draws [offset] at, or null if it is outside the map.
     *
     * An offset that is not itself a stop -- inside a grapheme cluster -- resolves to the nearest
     * stop on the row whose range contains it.
     */
    fun locate(offset: Int): Hit? {
        for (i in rows.indices) {
            val idx = rows[i].offsets.binarySearch(offset)
            if (idx >= 0) return Hit(i, idx)
        }
        for (i in rows.indices) {
            val o = rows[i].offsets
            if (o.isNotEmpty() && offset >= o.first() && offset <= o.last()) {
                var best = 0
                for (s in o.indices) if (abs(o[s] - offset) < abs(o[best] - offset)) best = s
                return Hit(i, best)
            }
        }
        return null
    }

    /** Screen point of [offset]: its x, and the vertical centre of its row. */
    fun pointOf(offset: Int): FloatArray? = locate(offset)?.let {
        floatArrayOf(rows[it.row].xs[it.stop], rows[it.row].centerY)
    }

    /** Indices of the rows whose vertical centre lies within [top]..[bottom], or null if none. */
    fun rowsWithin(top: Float, bottom: Float): IntRange? {
        var first = -1
        var last = -1
        for (i in rows.indices) {
            val c = rows[i].centerY
            if (c >= top && c <= bottom) {
                if (first < 0) first = i
                last = i
            }
        }
        return if (first < 0) null else first..last
    }

    /**
     * The caret position for a marker at ([x], [y]).
     *
     * Hysteresis keeps the current position until the marker is decisively closer to another,
     * which is what stops a finger resting on the boundary between two characters -- or two
     * rows -- from flickering the caret between them. It is a fraction of a character (or a row),
     * small enough that the caret still visibly tracks the marker.
     *
     * @param prev the position the caret is at now, for the hysteresis.
     * @param rowRange rows that may be chosen; the trackpad restricts this to the visible ones.
     * @param xMin stops left of this are not chosen unless the row has nothing else.
     * @param xMax likewise to the right.
     */
    fun hit(
        x: Float,
        y: Float,
        prev: Hit?,
        rowRange: IntRange = rows.indices,
        xMin: Float = Float.NEGATIVE_INFINITY,
        xMax: Float = Float.POSITIVE_INFINITY,
    ): Hit? {
        if (rows.isEmpty()) return null
        val lo = max(rowRange.first, 0)
        val hi = min(rowRange.last, rows.size - 1)
        if (lo > hi) return null
        var best = lo
        var bestOut = Float.MAX_VALUE
        var bestCentre = Float.MAX_VALUE
        for (i in lo..hi) {
            val r = rows[i]
            val out = verticalDistance(y, r)
            val centre = abs(y - r.centerY)
            if (out < bestOut || (out == bestOut && centre < bestCentre)) {
                best = i
                bestOut = out
                bestCentre = centre
            }
        }
        var row = best
        if (prev != null && prev.row != best && prev.row in lo..hi && prev.row < rows.size) {
            val p = rows[prev.row]
            if (verticalDistance(y, p) <= ROW_HYSTERESIS * p.height) row = prev.row
        }
        val stop = nearestStop(row, x, if (prev?.row == row) prev.stop else -1, xMin, xMax)
        return Hit(row, stop)
    }

    /**
     * The stop on [row] nearest [x], with hysteresis toward [keep] when it is on this row.
     * Stops outside [xMin]..[xMax] are only chosen if no stop is inside.
     */
    fun nearestStop(
        row: Int,
        x: Float,
        keep: Int = -1,
        xMin: Float = Float.NEGATIVE_INFINITY,
        xMax: Float = Float.POSITIVE_INFINITY,
    ): Int {
        val r = rows[row]
        val anyInside = r.xs.any { it >= xMin && it <= xMax }
        var best = -1
        for (s in r.xs.indices) {
            if (anyInside && (r.xs[s] < xMin || r.xs[s] > xMax)) continue
            if (best < 0 || abs(r.xs[s] - x) < abs(r.xs[best] - x)) best = s
        }
        if (best < 0) best = 0
        if (keep in r.xs.indices && keep != best &&
            (!anyInside || (r.xs[keep] >= xMin && r.xs[keep] <= xMax)) &&
            abs(r.xs[keep] - x) <= abs(r.xs[best] - x) + STOP_HYSTERESIS * r.gap
        ) {
            return keep
        }
        return best
    }

    fun translated(dx: Float, dy: Float): CaretMap =
        CaretMap(rows.map { it.translated(dx, dy) }, atTextStart, atTextEnd)

    private fun verticalDistance(y: Float, r: Row): Float = when {
        y < r.top -> r.top - y
        y > r.bottom -> y - r.bottom
        else -> 0f
    }

    companion object {
        /** A row is kept until the marker is this fraction of its height outside it. */
        const val ROW_HYSTERESIS = 0.2f
        /** A stop is kept until another is closer by this fraction of a character. */
        const val STOP_HYSTERESIS = 0.25f

        val EMPTY = CaretMap(emptyList(), atTextStart = true, atTextEnd = true)
    }
}

/**
 * Turns character boxes into a [CaretMap].
 *
 * The two sources differ in what they say. TextBoundsInfo gives every character its line, so
 * rows are exact. Composing-region bounds (Firefox, Chrome, Compose) give rectangles only, so a
 * new row is recognised geometrically: a character that does not vertically overlap the row so
 * far has wrapped.
 */
object CaretMapBuilder {

    /**
     * @param boxes the characters, in text order, contiguous from the first box's offset.
     * @param endOffset the offset just past the last character.
     * @param atTextStart the first box is the start of the text.
     * @param atTextEnd [endOffset] is the end of the text: the caret may stand after the last
     *   character, and a final newline opens one more (empty) row.
     * @param startsAtLineStart the first box begins a line: the text before it ends in a hard
     *   break, or there is none.
     * @param trimPartialRows the boxes are an arbitrary window of the text, so the first and last
     *   rows may be fragments of rows whose other part is not here. A fragment would snap the
     *   caret to its first known stop, so it is dropped -- unless it starts a line, ends with a
     *   break, or is the end of the text, which make it whole. TextBoundsInfo always covers whole
     *   lines and needs none of this.
     */
    fun build(
        boxes: List<CharBox>,
        endOffset: Int,
        atTextStart: Boolean,
        atTextEnd: Boolean,
        startsAtLineStart: Boolean = atTextStart,
        trimPartialRows: Boolean = false,
    ): CaretMap {
        val acc = ArrayList<RowAcc>()
        var cur: RowAcc? = null
        var lastWasNewline = startsAtLineStart
        for (b in boxes) {
            // Whitespace drawn with no width is whitespace the layout collapsed -- typically the
            // space at a soft wrap, which a browser parks at the start of the next row. Its caret
            // belongs at the end of the row it follows, which is where "no box" puts it.
            val box = b.box?.takeIf { it.isValid && !(b.collapsible && it.right - it.left < 0.5f) }
            if (b.newline && cur == null && !lastWasNewline) {
                // The break ending a row that began before this stretch of text: none of that
                // row is here, so there is nothing to give a stop to. Not an empty line.
                lastWasNewline = true
                continue
            }
            if (b.newline) {
                // A hard break ends its row. The caret just before it is the end of that row,
                // drawn after the last glyph rather than wherever the editor put the break's own
                // (often degenerate) rectangle. A break with no row open is an empty line.
                val row = cur ?: RowAcc(lineStart = true, ownBox = box).also { acc.add(it) }
                row.addStop(b.offset, if (row.hasGlyph) row.lastEdge else Float.NaN)
                row.closed = true
                cur = null
                lastWasNewline = true
                continue
            }
            if (box == null) {
                // Some editors give a collapsed space -- the one a soft wrap swallows -- no
                // rectangle. The caret before it is still the end of the row it follows.
                // Anything else without one is skipped: a stretch of text the editor could not place
                // must not be piled up at the end of the row, where the caret would be sent to an
                // offset drawn somewhere else entirely.
                val row = cur
                if (b.collapsible && row != null && row.hasGlyph && b.boundary &&
                    row.offsets.lastOrNull() != b.offset
                ) {
                    row.addStop(b.offset, row.lastEdge)
                }
                continue
            }
            var row = cur
            if (row != null && row.wrapsTo(b, box)) row = null
            if (row == null) {
                // A soft wrap: the caret at this offset is drawn at the start of the new row, so
                // the row before it gets no stop here. That is what keeps a caret at the end of a
                // wrapped row from being shown on one row and drawn by the editor on the next.
                row = RowAcc(lineStart = lastWasNewline).also { acc.add(it) }
                cur = row
            }
            lastWasNewline = false
            if (b.boundary) row.addStop(b.offset, if (b.rtl) box.right else box.left)
            row.addGlyph(box, b.rtl, b.line, b.hidden)
        }
        if (atTextEnd) {
            val row = cur
            if (row != null && row.hasGlyph) {
                row.addStop(endOffset, row.lastEdge)
            } else if (row == null && (lastWasNewline || boxes.isEmpty())) {
                acc.add(RowAcc(lineStart = true).also { it.addStop(endOffset, Float.NaN) })
            }
        }
        placeEmptyRows(acc)
        val kept = acc.filter { it.offsets.isNotEmpty() && it.top.isFinite() }.toMutableList()
        val dropFirst = trimPartialRows && kept.size > 1 && !kept.first().lineStart
        var dropLast = trimPartialRows && !atTextEnd && kept.size > 1 && !kept.last().closed
        if (dropFirst && dropLast && kept.size <= 2) dropLast = false
        if (dropLast) kept.removeAt(kept.size - 1)
        if (dropFirst) kept.removeAt(0)
        val rows = kept.map { it.toRow() }
        return CaretMap(
            rows,
            atTextStart = atTextStart && !dropFirst,
            atTextEnd = atTextEnd && !dropLast,
        )
    }

    /**
     * Gives empty lines a position, by spacing them evenly between the rows around them.
     *
     * Editors disagree about where a line break's own rectangle is -- the end of the line it
     * ends, the start of the next, or nowhere -- so it is not used when neighbours are available.
     * Interpolating from the rows either side is right for any of them.
     */
    private fun placeEmptyRows(acc: List<RowAcc>) {
        val placed = acc.filter { it.hasGlyph }
        val pitch = estimatePitch(placed)
        val left = placed.filter { it.lineStart }.mapNotNull { it.xs.firstOrNull() }.minOrNull()
            ?: placed.mapNotNull { it.xs.minOrNull() }.minOrNull()
        var i = 0
        while (i < acc.size) {
            if (acc[i].hasGlyph) {
                i++
                continue
            }
            var j = i
            while (j < acc.size && !acc[j].hasGlyph) j++
            val before = acc.getOrNull(i - 1)
            val after = acc.getOrNull(j)
            val k = j - i
            for (n in 0 until k) {
                val row = acc[i + n]
                val (top, height) = when {
                    before != null && after != null -> {
                        val step = (after.top - before.top) / (k + 1)
                        before.top + step * (n + 1) to before.height
                    }
                    before != null -> before.top + pitch * (n + 1) to before.height
                    after != null -> after.top - pitch * (k - n) to after.height
                    row.ownBox != null -> row.ownBox.top to row.ownBox.height
                    else -> Float.NaN to Float.NaN
                }
                row.top = top
                row.bottom = top + height
                val x = left ?: row.ownBox?.left ?: Float.NaN
                for (s in row.xs.indices) if (row.xs[s].isNaN()) row.xs[s] = x
            }
            i = j
        }
        // An end-of-text stop on a row that had glyphs is already placed; any NaN left is a row
        // with no geometry at all, which the caller drops.
    }

    private fun estimatePitch(rows: List<RowAcc>): Float {
        val diffs = rows.zipWithNext { a, b -> b.top - a.top }.filter { it > 0f }.sorted()
        if (diffs.isNotEmpty()) return diffs[diffs.size / 2]
        // One row to go on: its own height, since a line box includes the line's spacing. Empty
        // rows are never used to measure where text is, only to put a caret on, so an error
        // here costs where an empty line's marker is drawn and nothing else.
        val heights = rows.map { it.height }.sorted()
        return if (heights.isNotEmpty()) heights[heights.size / 2] else 48f
    }

    private class RowAcc(val lineStart: Boolean, val ownBox: Box? = null) {
        var top = Float.NaN
        var bottom = Float.NaN
        val height: Float get() = bottom - top
        val offsets = ArrayList<Int>()
        val xs = ArrayList<Float>()
        var hasGlyph = false
        /** Ended by a hard break, so it is known to be whole at its end. */
        var closed = false
        var lastEdge = Float.NaN
        var line = -1
        /** The first glyph's box: the reference a wrapped glyph fails to overlap. */
        var refTop = Float.NaN
        var refBottom = Float.NaN

        fun addStop(offset: Int, x: Float) {
            offsets.add(offset)
            xs.add(x)
        }

        var anyVisible = false

        fun addGlyph(box: Box, rtl: Boolean, line: Int, hidden: Boolean) {
            if (!hidden) anyVisible = true
            if (!hasGlyph) {
                refTop = box.top
                refBottom = box.bottom
                top = box.top
                bottom = box.bottom
                this.line = line
            } else {
                top = min(top, box.top)
                bottom = max(bottom, box.bottom)
            }
            hasGlyph = true
            lastEdge = if (rtl) box.left else box.right
        }

        /** Whether [box] is on a later row than this one. */
        fun wrapsTo(b: CharBox, box: Box): Boolean {
            if (!hasGlyph) return false
            if (b.line >= 0 && line >= 0) return b.line != line
            val overlap = min(refBottom, box.bottom) - max(refTop, box.top)
            return overlap < 0.5f * min(refBottom - refTop, box.height)
        }

        fun toRow(): Row {
            // Text order is left to right for the common case; keep it, and keep xs aligned.
            return Row(
                top, bottom, offsets.toIntArray(), xs.toFloatArray(),
                softStart = hasGlyph && !lineStart,
                estimated = !hasGlyph,
                hidden = hasGlyph && !anyVisible,
            )
        }
    }

    // --- grapheme boundaries, for sources that give text but not segmentation ---------------

    private const val ZWJ = '\u200D'

    /**
     * Whether a caret may sit just before `text[i]`: false where it would split what is drawn as
     * one character. The same cases [com.offlinekeyboard.ime.text.GraphemeCluster] handles for
     * backspace -- surrogate pairs, joiners, modifiers, flags -- and "yes" for everything else.
     */
    fun isCaretBoundary(text: CharSequence, i: Int): Boolean {
        if (i <= 0 || i >= text.length) return true
        val c = text[i]
        val p = text[i - 1]
        if (Character.isLowSurrogate(c) && Character.isHighSurrogate(p)) return false
        if (c == ZWJ || p == ZWJ) return false
        if (c in '\uFE00'..'\uFE0F' || c == '\u20E3') return false
        if (p == '\r' && c == '\n') return false
        val cp = Character.codePointAt(text, i)
        when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            -> return false
        }
        if (cp in 0x1F3FB..0x1F3FF) return false // skin tone
        if (cp in 0xE0020..0xE007F) return false // tag characters (subdivision flags)
        if (isRegionalIndicator(cp)) {
            var j = i
            var before = 0
            while (j >= 2 && isRegionalIndicator(Character.codePointAt(text, j - 2))) {
                before++
                j -= 2
            }
            return before % 2 == 0
        }
        return true
    }

    private fun isRegionalIndicator(cp: Int) = cp in 0x1F1E6..0x1F1FF
}
