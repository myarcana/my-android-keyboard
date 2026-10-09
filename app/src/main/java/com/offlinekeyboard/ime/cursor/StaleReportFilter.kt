package com.offlinekeyboard.ime.cursor

import android.os.Handler
import android.view.inputmethod.CursorAnchorInfo
import kotlin.math.abs

/**
 * Holds back caret reports that carry a new selection but the old caret position.
 *
 * **[platform]** Chromium (Chrome, every WebView, and apps built on one -- ColorOS Notes is) builds
 * each report from two sources updated at different times: the selection comes from the IME
 * adapter's text state, which changes the moment `setSelection` lands, and the insertion marker
 * from the last composited frame, which changes a frame later. So every caret move produces two
 * reports: first the new offset with the old position, then the new offset with the new
 * position. Measured on the phone:
 *
 * ```
 * sel=[1031] x=441   (fresh)
 * sel=[1035] x=441   (stale: offset moved four characters, position did not)
 * sel=[1035] x=516   (fresh)
 * ```
 *
 * Read as truth, the stale report says the text scrolled by four characters, and the map would
 * be slid by that much -- the caret then lands four characters from where the marker is.
 *
 * So a report whose selection changed while its position did not is held. If a report with a new
 * position follows -- it does, within a frame, in Chromium -- the held one is dropped. If nothing
 * follows it is delivered after all, because a caret really can change offset without moving on
 * screen: a single-line field scrolling sideways to keep its caret at the edge does exactly that.
 */
class StaleReportFilter(private val handler: Handler, private val holdMs: Long = 60L) {

    private var lastStart = Int.MIN_VALUE
    private var lastEnd = Int.MIN_VALUE
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var held: CursorAnchorInfo? = null
    private var deliverHeld: ((CursorAnchorInfo) -> Unit)? = null

    private val release = Runnable {
        val h = held ?: return@Runnable
        val d = deliverHeld
        held = null
        deliverHeld = null
        remember(h)
        d?.invoke(h)
    }

    fun reset() {
        handler.removeCallbacks(release)
        held = null
        deliverHeld = null
        lastStart = Int.MIN_VALUE
        lastEnd = Int.MIN_VALUE
        lastX = Float.NaN
        lastY = Float.NaN
    }

    /** Passes [info] to [deliver] now, later, or never, as described above. */
    fun offer(info: CursorAnchorInfo, deliver: (CursorAnchorInfo) -> Unit) {
        val p = point(info)
        val selectionChanged = lastStart != Int.MIN_VALUE &&
            (info.selectionStart != lastStart || info.selectionEnd != lastEnd) &&
            info.selectionStart >= 0
        val samePlace = p != null && !lastX.isNaN() &&
            abs(p[0] - lastX) < 0.5f && abs(p[1] - lastY) < 0.5f
        if (selectionChanged && samePlace) {
            handler.removeCallbacks(release)
            held = info
            deliverHeld = deliver
            handler.postDelayed(release, holdMs)
            return
        }
        // Anything else supersedes a held report: if it moved, the held one was stale.
        handler.removeCallbacks(release)
        held = null
        deliverHeld = null
        remember(info)
        deliver(info)
    }

    private fun remember(info: CursorAnchorInfo) {
        lastStart = info.selectionStart
        lastEnd = info.selectionEnd
        val p = point(info)
        if (p != null) {
            lastX = p[0]
            lastY = p[1]
        }
    }

    private fun point(info: CursorAnchorInfo): FloatArray? {
        val h = info.insertionMarkerHorizontal
        val t = info.insertionMarkerTop
        if (h.isNaN() || t.isNaN()) return null
        val pts = floatArrayOf(h, t)
        info.matrix.mapPoints(pts)
        return pts
    }
}
