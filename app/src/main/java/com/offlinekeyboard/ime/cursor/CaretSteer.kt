package com.offlinekeyboard.ime.cursor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The trackpad's marker, and the caret position it stands for -- pure geometry, no Android.
 *
 * The finger moves the marker in screen pixels. [target] turns the marker into a caret position
 * on the [map], locally and at once, so the caret can be set the moment the finger moves rather
 * than after a round trip to the app. Nothing here waits for, or integrates, anything the app
 * reports: the only thing the app's reports are used for is [scrolled], which slides the map when
 * the text moves underneath it.
 *
 * The *band* is the part of the screen where text is actually visible: the editor's own bounds
 * where it publishes trustworthy ones, never below the top of the keyboard, and narrowed further
 * whenever the editor is seen scrolling to reveal the caret -- which shows exactly where its
 * visible edge is. Only rows inside the band are offered to the marker, because a caret placed in
 * a row the user cannot see makes the editor scroll, and that moves every row under the marker.
 *
 * Past the band's edge the marker parks, and [edgeStep] moves the caret one row (or one stop, for
 * a single-line field) beyond the edge at a rate set by how far past it the marker is. The editor
 * scrolls to show it; the map follows via [scrolled]; repeat.
 */
class CaretSteer(
    /** How far past the band the marker may be pushed, which sets the auto-scroll speed. */
    private val overshootMax: Float = 420f,
) {
    var map: CaretMap = CaretMap.EMPTY
        private set

    /** The visible area as the source describes it: editor bounds, cut off by the keyboard. */
    var outerTop = Float.NEGATIVE_INFINITY
    var outerBottom = Float.POSITIVE_INFINITY
    var outerLeft = Float.NEGATIVE_INFINITY
    var outerRight = Float.POSITIVE_INFINITY

    /**
     * Edges learned by watching the editor scroll. Kept apart from the outer ones, which a source
     * may refresh on every report, because what they correct is exactly what those get wrong:
     * text clipped by padding, or by a container the editor does not know about.
     */
    private var learnedTop = Float.NEGATIVE_INFINITY
    private var learnedBottom = Float.POSITIVE_INFINITY
    private var learnedLeft = Float.NEGATIVE_INFINITY
    private var learnedRight = Float.POSITIVE_INFINITY

    val bandTop: Float get() = max(outerTop, learnedTop)
    val bandBottom: Float get() = min(outerBottom, learnedBottom)
    val bandLeft: Float get() = max(outerLeft, learnedLeft)
    val bandRight: Float get() = min(outerRight, learnedRight)

    /** Hard limits for the marker: the display. */
    var screenLeft = 0f
    var screenTop = 0f
    var screenRight = Float.POSITIVE_INFINITY
    var screenBottom = Float.POSITIVE_INFINITY

    /**
     * Whether the caret may be pushed past the band to scroll. Only when the editor reports its
     * caret, because only then can the map follow the scroll; without that the map would go stale
     * the moment the text moved, so the marker is held to what is visible instead.
     */
    var edgeScrollAllowed = false

    var markerX = Float.NaN
        private set
    var markerY = Float.NaN
        private set

    /** The caret position the marker currently stands for. */
    var hit: Hit? = null

    val seeded: Boolean get() = !markerX.isNaN() && !markerY.isNaN()

    /** Replaces the map, keeping the caret's identity: the hit is re-found by text offset. */
    fun install(newMap: CaretMap, currentOffset: Int) {
        map = newMap
        hit = if (currentOffset >= 0) newMap.locate(currentOffset) else null
    }

    /**
     * Places the marker on the caret at [offset], plus finger travel made before there was a map
     * to place it on. Falls back to ([fallbackX], [fallbackY]) -- the caret as the app last
     * reported it -- when the offset is not on the map, and to the middle of the visible text
     * when there is not even that.
     */
    fun seed(offset: Int, fallbackX: Float, fallbackY: Float, dx: Float, dy: Float) {
        val located = if (offset >= 0) map.locate(offset) else null
        val vis = visibleRows()
        val start: FloatArray = when {
            located != null -> {
                hit = located
                floatArrayOf(map.rows[located.row].xs[located.stop], map.rows[located.row].centerY)
            }
            !fallbackX.isNaN() && !fallbackY.isNaN() -> floatArrayOf(fallbackX, fallbackY)
            vis != null -> {
                val r = map.rows[(vis.first + vis.last) / 2]
                floatArrayOf(r.xs[0], r.centerY)
            }
            else -> floatArrayOf(0f, 0f)
        }
        markerX = start[0] + dx
        markerY = start[1] + dy
        clamp()
    }

    fun pan(dx: Float, dy: Float) {
        if (!seeded) return
        markerX += dx
        markerY += dy
        clamp()
    }

    /** Rows whose centre is inside the band; all rows if the band excludes every one of them. */
    fun visibleRows(): IntRange? {
        if (map.isEmpty) return null
        var first = -1
        var last = -1
        for (i in map.rows.indices) {
            val r = map.rows[i]
            if (r.hidden || r.centerY < bandTop || r.centerY > bandBottom) continue
            if (first < 0) first = i
            last = i
        }
        if (first >= 0) return first..last
        // Nothing qualifies: the band is wrong, not the text. Better every row than none.
        return map.rowsWithin(bandTop, bandBottom) ?: map.rows.indices
    }

    private fun moreAbove(vis: IntRange) = vis.first > 0 || !map.atTextStart
    private fun moreBelow(vis: IntRange) = vis.last < map.rows.lastIndex || !map.atTextEnd

    /**
     * Keeps the marker where the caret can follow it.
     *
     * Travel the caret cannot follow must not accumulate: every pixel of it would have to be
     * un-travelled before anything responded again, which reads as the cursor freezing and then
     * snapping. So the marker stops a quarter of a row past the first and last rows it could
     * reach, and only goes further where there is more text to scroll to.
     */
    fun clamp() {
        if (!seeded) return
        val vis = visibleRows() ?: return
        val first = map.rows[vis.first]
        val last = map.rows[vis.last]
        val yMin = first.top -
            if (edgeScrollAllowed && moreAbove(vis)) overshootMax else first.height * 0.25f
        val yMax = last.bottom +
            if (edgeScrollAllowed && moreBelow(vis)) overshootMax else last.height * 0.25f
        markerY = safeClamp(markerY, max(yMin, screenTop), min(yMax, screenBottom))

        val row = map.rows[rowForLimits(vis)]
        // Sideways the marker may go as far as the text does, a little past it -- the end of a
        // short line can be overshot and the caret stays at its end -- but not past a side of
        // the band, where the text is clipped out of sight.
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (i in vis) for (x in map.rows[i].xs) {
            lo = min(lo, x)
            hi = max(hi, x)
        }
        var xl = max(lo - 0.5f * map.typicalGap, bandLeft)
        var xr = min(hi + map.typicalGap, bandRight)
        if (xl > xr) {
            xl = row.xs.minOrNull() ?: xl
            xr = row.xs.maxOrNull() ?: xr
        }
        if (edgeScrollAllowed && hiddenLeft(row)) xl -= overshootMax
        if (edgeScrollAllowed && hiddenRight(row)) xr += overshootMax
        markerX = safeClamp(markerX, max(xl, screenLeft), min(xr, screenRight))
    }

    private fun rowForLimits(vis: IntRange): Int {
        val h = hit
        if (h != null && h.row in vis) return h.row
        var best = vis.first
        for (i in vis) {
            if (abs(map.rows[i].centerY - markerY) < abs(map.rows[best].centerY - markerY)) best = i
        }
        return best
    }

    private fun hiddenLeft(row: Row) = bandLeft.isFinite() && row.xs.any { it < bandLeft - 1f }
    private fun hiddenRight(row: Row) = bandRight.isFinite() && row.xs.any { it > bandRight + 1f }

    /**
     * The caret position for the marker, or the current one unchanged while the marker is parked
     * past an edge -- the auto-scroll owns movement then, and a position computed from the
     * visible rows would drag the caret back from the row it just scrolled to.
     */
    fun target(): Hit? {
        if (!seeded || map.isEmpty) return null
        if (verticalEdge() != 0 || horizontalEdge() != 0) return hit
        val vis = visibleRows() ?: return null
        val xMin = bandLeft - 1f
        val xMax = bandRight + 1f
        val prev = hit?.takeIf { it.row in map.rows.indices && it.stop in 0 until map.rows[it.row].size }
        val h = map.hit(markerX, markerY, prev, vis, xMin, xMax) ?: return hit
        hit = h
        return h
    }

    /** +1 while the marker is held below the visible rows with more text there, -1 above, else 0. */
    fun verticalEdge(): Int {
        if (!edgeScrollAllowed || !seeded) return 0
        val vis = visibleRows() ?: return 0
        val first = map.rows[vis.first]
        val last = map.rows[vis.last]
        return when {
            moreBelow(vis) && markerY > last.bottom + EDGE_DEADZONE * last.height -> 1
            moreAbove(vis) && markerY < first.top - EDGE_DEADZONE * first.height -> -1
            else -> 0
        }
    }

    /** The same for a row wider than the editor -- a single-line field scrolled sideways. */
    fun horizontalEdge(): Int {
        if (!edgeScrollAllowed || !seeded) return 0
        val h = hit ?: return 0
        if (h.row !in map.rows.indices) return 0
        val row = map.rows[h.row]
        val visible = row.xs.filter { it >= bandLeft - 1f && it <= bandRight + 1f }
        if (visible.isEmpty()) return 0
        val gap = row.gap
        return when {
            hiddenRight(row) && markerX > visible.max() + EDGE_DEADZONE * 2f * gap -> 1
            hiddenLeft(row) && markerX < visible.min() - EDGE_DEADZONE * 2f * gap -> -1
            else -> 0
        }
    }

    /** How far past the edge the marker is, in pixels, which sets how fast to scroll. */
    fun edgeOvershoot(): Float {
        val vis = visibleRows() ?: return 0f
        return when (verticalEdge()) {
            1 -> markerY - map.rows[vis.last].bottom
            -1 -> map.rows[vis.first].top - markerY
            else -> {
                val h = hit ?: return 0f
                val row = map.rows[h.row]
                val visible = row.xs.filter { it >= bandLeft - 1f && it <= bandRight + 1f }
                if (visible.isEmpty()) return 0f
                when (horizontalEdge()) {
                    1 -> markerX - visible.max()
                    -1 -> visible.min() - markerX
                    else -> 0f
                }
            }
        }.coerceAtLeast(0f)
    }

    /**
     * The next caret position past the edge the marker is parked at: the row beyond the visible
     * ones (at the marker's x), or the next stop beyond the visible part of the row. Null when
     * the map holds nothing further that way -- the map needs extending, or the text has ended.
     */
    fun edgeStep(): Hit? {
        val vis = visibleRows() ?: return null
        when (verticalEdge()) {
            1 -> {
                val idx = vis.last + 1
                return if (idx < map.rows.size) Hit(idx, map.nearestStop(idx, markerX)) else null
            }
            -1 -> {
                val idx = vis.first - 1
                return if (idx >= 0) Hit(idx, map.nearestStop(idx, markerX)) else null
            }
        }
        val dir = horizontalEdge()
        if (dir == 0) return null
        val h = hit ?: return null
        val row = map.rows[h.row]
        var best = -1
        for (s in row.xs.indices) {
            val x = row.xs[s]
            if (dir > 0 && x > bandRight + 0.5f && (best < 0 || x < row.xs[best])) best = s
            if (dir < 0 && x < bandLeft - 0.5f && (best < 0 || x > row.xs[best])) best = s
        }
        return if (best >= 0) Hit(h.row, best) else null
    }

    /** Whether an edge wants a step that the map cannot supply, while the text goes on. */
    fun needsMoreMap(): Boolean {
        if (verticalEdge() == 0 && horizontalEdge() == 0) return false
        return edgeStep() == null
    }

    /** The text moved by ([dx], [dy]) on screen; the map moves with it. The marker does not. */
    fun scrolled(dx: Float, dy: Float) {
        map = map.translated(dx, dy)
    }

    /**
     * The editor scrolled by ([dx], [dy]) to bring the caret at [offset] into view, so that caret
     * is now just inside the visible edge -- which is the one reliable measurement of where the
     * edge is, in an editor that does not publish its bounds.
     */
    fun learnEdgeFromScroll(dx: Float, dy: Float, offset: Int) {
        val at = map.locate(offset) ?: return
        val row = map.rows[at.row]
        if (dy > 0.5f) learnedTop = max(learnedTop, row.top - 1f)
        if (dy < -0.5f) learnedBottom = min(learnedBottom, row.bottom + 1f)
        val x = row.xs[at.stop]
        if (dx > 0.5f) learnedLeft = max(learnedLeft, x - 1f)
        if (dx < -0.5f) learnedRight = min(learnedRight, x + 1f)
    }

    /** Forgets everything about one gesture: the map, the marker, and the learned edges. */
    fun reset() {
        map = CaretMap.EMPTY
        hit = null
        markerX = Float.NaN
        markerY = Float.NaN
        outerTop = Float.NEGATIVE_INFINITY
        outerBottom = Float.POSITIVE_INFINITY
        outerLeft = Float.NEGATIVE_INFINITY
        outerRight = Float.POSITIVE_INFINITY
        learnedTop = Float.NEGATIVE_INFINITY
        learnedBottom = Float.POSITIVE_INFINITY
        learnedLeft = Float.NEGATIVE_INFINITY
        learnedRight = Float.POSITIVE_INFINITY
        edgeScrollAllowed = false
    }

    /** Height to draw the marker at: the row it is over. */
    fun markerHeight(): Float {
        val h = hit
        if (h != null && h.row in map.rows.indices) return map.rows[h.row].height
        return map.typicalRowHeight
    }

    private fun safeClamp(v: Float, lo: Float, hi: Float): Float =
        if (lo > hi) (lo + hi) / 2f else v.coerceIn(lo, hi)

    companion object {
        /** Fraction of a row past the last visible one before the auto-scroll starts. */
        const val EDGE_DEADZONE = 0.35f
    }
}
