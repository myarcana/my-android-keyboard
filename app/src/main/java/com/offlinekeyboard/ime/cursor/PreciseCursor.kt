package com.offlinekeyboard.ime.cursor

import android.graphics.Matrix
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.view.KeyEvent
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.TextBoundsInfo
import android.view.inputmethod.TextBoundsInfoResult
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The spacebar trackpad, placing the caret absolutely.
 *
 * The old trackpad steered the caret toward the marker with arrow keys, closing the loop on the
 * app's caret reports. Every correction waited a round trip, so the caret always trailed the
 * marker, and every estimate it steered with -- character width, line pitch, where rows wrap --
 * was wrong somewhere, so it overshot and hunted. This replaces the loop with a map.
 *
 * When the hold takes, the editor is asked where every character in and around the visible text
 * is drawn ([CaretMap]). From then on each finger movement is a local lookup -- marker to offset,
 * with hysteresis -- and, when the offset changes, one `setSelection`. Nothing waits on the app,
 * so the caret is wherever the marker says the moment the app redraws; nothing is estimated, so
 * there is nothing to drift.
 *
 * The map comes from the first of these the editor supports:
 *
 * 1. **`requestTextBoundsInfo`** (Android 14). Exact, side-effect free, whole lines. Every
 *    platform `TextView`/`EditText`: Instagram, most note apps, Firefox's address bar.
 * 2. **The composing region.** Editors report per-character bounds for composing text, so the
 *    text around the caret is briefly marked composing -- no text changes -- and the bounds read
 *    back. Chrome and WebView, Compose, older `TextView`s.
 * 3. **The same, by replacement, for Firefox.** GeckoView reports bounds only for a composition
 *    *Gecko itself* started, and it never starts one from a composing region, only from composing
 *    text. So in a plain `<input>`/`<textarea>` the text either side of the caret is re-set as
 *    composing text identical to itself, caret held in place, and the bounds read back. Never in
 *    a rich editor (`contenteditable`), where replacing text would drop its formatting.
 *
 * When none works the host falls back to the old steering ([Host.fallBack]).
 *
 * The map is in screen coordinates and editors scroll, so it is kept in register by the caret
 * reports the app keeps sending: each says where the caret at some offset is *now*, the map says
 * where that offset was, and any difference is the text having moved -- the map moves by it. The
 * marker does not. Reports are the only feedback used, and only for that.
 */
class PreciseCursor(private val host: Host) {

    interface Host {
        val inputConnection: InputConnection?
        val editorInfo: EditorInfo?
        /** The selection as `onUpdateSelection` last reported it. */
        val selectionStart: Int
        val selectionEnd: Int
        /** Screen y of the top of the keyboard: nothing below it is visible text. */
        fun keyboardTop(): Float
        fun screenWidth(): Float
        fun screenHeight(): Float
        fun mainExecutor(): Executor
        /** The marker moved or changed size. */
        fun redraw()
        /** Nothing here can map this editor; use the old steering, with this travel banked. */
        fun fallBack(bankX: Float, bankY: Float, needsComposition: Boolean)
        fun trace(message: String)
    }

    enum class Phase { IDLE, ACQUIRING, ACTIVE, FAILED }

    private enum class Source { TEXT_BOUNDS, COMPOSING, GECKO_REPLACE }

    var phase = Phase.IDLE
        private set

    /** Whether pans and reports belong here rather than to the fallback. */
    val owns: Boolean get() = phase == Phase.ACQUIRING || phase == Phase.ACTIVE

    val steer = CaretSteer()
    private val handler = Handler(Looper.getMainLooper())
    /** Bumped on every start and stop, so a late callback from an earlier gesture is ignored. */
    private var generation = 0
    private var source: Source? = null

    // --- what the gesture started from -------------------------------------------------------
    private var startCaret = -1
    private var bankX = 0f
    private var bankY = 0f

    // --- the latest caret report ---------------------------------------------------------------
    /** [x, top, bottom] on screen, or null before any report. */
    private var caretPoint: FloatArray? = null
    private var caretPointOffset = -1
    private var editorBox: Box? = null
    private var reportsSeen = 0

    /**
     * Systematic difference between where an editor reports a caret and where its character
     * bounds put the same offset -- the caret's own top versus the line box's, say. Measured once
     * per map, while the map is known fresh, so that only a *change* reads as scrolling.
     */
    private var baselineX = Float.NaN
    private var baselineY = Float.NaN
    private var baselineSource: Source? = null

    // --- Firefox ---------------------------------------------------------------------------------
    /** The editor answers caret requests only while a composing region exists: GeckoView. */
    private var geckoLike = false
    /** Holding a one-character composing region, nowhere near the caret, for that reason. */
    private var holdAt = -1
    /** Offset whose caret report has been asked for and not yet received. */
    private var geckoAsked = -1
    private var geckoAskedAt = 0L
    private var textLength = -1

    // --- placing the caret -----------------------------------------------------------------------
    private var sentStart = -1
    private var sentEnd = -1
    private var sentAt = 0L
    private var awaitingAck = false
    private var queued: IntArray? = null
    private var lastPlacedByEdge = false

    // --- selection -------------------------------------------------------------------------------
    var selecting = false
        private set
    private var anchor = -1
    /** Which end of a selection the editor's caret report describes: +1 the end, -1 the start. */
    private var reportedEnd = 0

    // --- edge scrolling --------------------------------------------------------------------------
    /** The editor does not scroll to reveal a caret set by offset, so edges step with arrows. */
    private var edgeViaKeys = false
    private var unrevealedSteps = 0
    private var edgeAwaiting = -1
    private var edgeSentAt = 0L
    private var reacquiring = false
    /** Edge scrolling was tried and the editor did not follow; the marker keeps to what is visible. */
    private var edgeScrollDisabled = false

    // --- the probe -------------------------------------------------------------------------------
    private class Probe(
        val kind: Kind,
        val base: Int,
        val text: String,
        val atTextStart: Boolean,
        val atTextEnd: Boolean,
        val startsAtLineStart: Boolean,
    ) {
        enum class Kind { REGION, REPLACE_BEFORE, REPLACE_AFTER }
    }

    private var probe: Probe? = null
    /** Characters either side of the caret to probe; shrinks once if an editor will not measure that many. */
    private var regionWindow = REGION_WINDOW
    /** Where the caret was when the probe began: where it is put back, and what it calibrates. */
    private var probeCaretOffset = -1
    /** The Firefox probe is two halves either side of the caret; the first is kept here. */
    private var probeBefore: List<CharBox>? = null
    private var probeBeforeCaret: FloatArray? = null
    private var probeWindow: IntArray? = null
    private var composing = false

    private val timeouts = ArrayList<Runnable>()

    // =============================================================================================
    // lifecycle

    fun start(caret: Int) {
        reset()
        generation++
        phase = Phase.ACQUIRING
        startCaret = caret
        val ic = host.inputConnection
        if (ic == null || caret < 0) {
            fail("no connection or caret")
            return
        }
        trace("START caret=$caret pkg=${host.editorInfo?.packageName}")
        ic.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requestTextBounds()
        } else {
            startRegionProbe(caret)
        }
    }

    fun stop() {
        trace("STOP phase=$phase")
        generation++
        cancelTimeouts()
        handler.removeCallbacks(edgeTick)
        handler.removeCallbacks(followUp)
        val ic = host.inputConnection
        if (phase == Phase.ACTIVE) {
            awaitingAck = false
            flushQueued()
            // Leave a selection in the conventional order. It was kept with the dragged end as
            // the "end" so the editor scrolled to follow that end; the range is the same.
            if (selecting && anchor >= 0 && sentEnd >= 0 && anchor > sentEnd) {
                ic?.setSelection(sentEnd, anchor)
            }
        }
        if (composing || holdAt >= 0) {
            ic?.finishComposingText()
            composing = false
            holdAt = -1
        }
        if (probe != null && probe!!.kind != Probe.Kind.REGION && probeCaretOffset >= 0) {
            // A replacement probe was cut short; its caret may be at either end of the window.
            ic?.setSelection(probeCaretOffset, probeCaretOffset)
        }
        probe = null
        phase = Phase.IDLE
    }

    private fun reset() {
        cancelTimeouts()
        handler.removeCallbacks(edgeTick)
        steer.reset()
        source = null
        startCaret = -1
        bankX = 0f
        bankY = 0f
        caretPoint = null
        caretPointOffset = -1
        editorBox = null
        reportsSeen = 0
        baselineX = Float.NaN
        baselineY = Float.NaN
        baselineSource = null
        geckoLike = false
        holdAt = -1
        geckoAsked = -1
        textLength = -1
        sentStart = -1
        sentEnd = -1
        awaitingAck = false
        queued = null
        lastPlacedByEdge = false
        selecting = false
        anchor = -1
        reportedEnd = 0
        edgeViaKeys = false
        unrevealedSteps = 0
        edgeAwaiting = -1
        reacquiring = false
        edgeScrollDisabled = false
        regionWindow = REGION_WINDOW
        probe = null
        probeCaretOffset = -1
        probeBefore = null
        probeBeforeCaret = null
        probeWindow = null
        composing = false
    }

    private fun fail(why: String) {
        trace("FAIL $why")
        // Nothing still in flight -- a late TextBoundsInfo, a probe report -- may act now.
        generation++
        cancelTimeouts()
        val ic = host.inputConnection
        if (composing) {
            ic?.finishComposingText()
            composing = false
        }
        val p = probe
        if (p != null && p.kind != Probe.Kind.REGION && probeCaretOffset >= 0) {
            ic?.setSelection(probeCaretOffset, probeCaretOffset)
        }
        probe = null
        phase = Phase.FAILED
        host.fallBack(bankX, bankY, geckoLike)
    }

    // =============================================================================================
    // input from the host

    fun pan(dx: Float, dy: Float) {
        when (phase) {
            Phase.ACQUIRING -> {
                bankX += dx
                bankY += dy
                host.redraw()
            }
            Phase.ACTIVE -> {
                steer.pan(dx, dy)
                update()
            }
            else -> Unit
        }
    }

    /** Where to draw the marker, and how tall: null when there is nowhere yet. */
    fun marker(): FloatArray? {
        if (phase == Phase.ACTIVE && steer.seeded) {
            return floatArrayOf(steer.markerX, steer.markerY, steer.markerHeight())
        }
        val c = caretPoint ?: return null
        val h = c[2] - c[1]
        return floatArrayOf(c[0] + bankX, (c[1] + c[2]) / 2f + bankY, h)
    }

    /** Where the caret will land when the finger lifts, for the release animation. */
    fun landing(): FloatArray? {
        val h = steer.hit ?: return null
        if (phase != Phase.ACTIVE || h.row !in steer.map.rows.indices) return null
        val r = steer.map.rows[h.row]
        if (h.stop !in 0 until r.size) return null
        return floatArrayOf(r.xs[h.stop], r.centerY, r.height)
    }

    fun beginSelection() {
        if (selecting) return
        val from = steer.hit?.let { steer.map.offsetOf(it) } ?: sentEnd.takeIf { it >= 0 } ?: startCaret
        if (from < 0) return
        selecting = true
        anchor = from
        if (geckoLike) {
            // Its reports cannot be matched to an end of a selection (see onCursorAnchorInfo),
            // so the map cannot follow a scroll: keep to what is visible.
            edgeScrollDisabled = true
            steer.edgeScrollAllowed = false
            steer.clamp()
        }
        trace("SELECT anchor=$anchor")
    }

    fun onUpdateSelection(selStart: Int, selEnd: Int) {
        if (!owns) return
        if (awaitingAck && (selEnd == sentEnd || selStart == sentEnd)) {
            awaitingAck = false
            flushQueued()
        }
        if (phase == Phase.ACTIVE && probe == null && !awaitingAck && queued == null &&
            selEnd != sentEnd && sentEnd >= 0 && edgeViaKeys
        ) {
            // An arrow key moved the caret at an edge; follow it, and map further when it has
            // gone past what the map covers.
            val at = steer.map.locate(selEnd)
            if (at != null) steer.hit = at else reacquire()
            sentStart = selStart
            sentEnd = selEnd
        }
        if (phase == Phase.ACTIVE && probe == null) {
            if (geckoLike) askGecko(selEnd)
            // Editors scroll to reveal a moved caret a frame or more after moving it, and a report
            // sent with the move can predate the scroll. Once movement pauses, ask again.
            handler.removeCallbacks(followUp)
            handler.postDelayed(followUp, FOLLOW_UP_MS)
        }
    }

    private val followUp = Runnable {
        if (phase != Phase.ACTIVE || probe != null) return@Runnable
        if (geckoLike) {
            askGecko(host.selectionEnd)
        } else {
            host.inputConnection?.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
            )
        }
    }

    fun onCursorAnchorInfo(info: CursorAnchorInfo) {
        if (!owns) return
        reportsSeen++
        val offset = reportedOffset(info)
        val point = caretOf(info)
        if (point != null) {
            caretPoint = point
            caretPointOffset = offset
        }
        info.editorBoundsInfo?.editorBounds?.let { b ->
            val r = RectF(b)
            info.matrix.mapRect(r)
            if (r.width() > 1f && r.height() > 1f) editorBox = Box(r.left, r.top, r.right, r.bottom)
        }
        if (probe != null) {
            onProbeReport(info, point)
            return
        }
        if (phase != Phase.ACTIVE) {
            if (!steer.seeded) host.redraw()
            return
        }
        if (point == null || offset < 0) return
        if (!steer.edgeScrollAllowed && !edgeScrollDisabled && !steer.map.isEmpty) {
            // The map arrived before the first caret report; scrolling can be followed now.
            steer.edgeScrollAllowed = true
        }
        // A GeckoView report names no offset; it is matched to the one asked about. If the
        // selection has moved since, the report may describe either, and taking it for the wrong
        // one reads as the text scrolling by a character.
        if (geckoLike && (offset != host.selectionEnd || awaitingAck)) return
        // GeckoView's caret during a selection could be either end, and nothing in its report
        // says which; guessing wrong reads as a scroll of the selection's width.
        if (geckoLike && selecting) return
        track(offset, point)
    }

    // =============================================================================================
    // the map, from TextBoundsInfo

    private fun requestTextBounds() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val ic = host.inputConnection ?: return
        val gen = generation
        val h = host.screenHeight()
        val w = host.screenWidth()
        // Whole lines are returned for whatever the rectangle touches vertically, so a screen
        // above and below the visible text leaves room to scroll before the map needs extending.
        val area = RectF(-w, -h, 2f * w, max(host.keyboardTop(), h * 0.5f) + h)
        val timeout = after(TEXT_BOUNDS_TIMEOUT_MS) {
            if (gen == generation && source == null) {
                trace("TBI timeout")
                startRegionProbe(currentCaret())
            }
        }
        try {
            ic.requestTextBoundsInfo(area, host.mainExecutor()) { result ->
                handler.removeCallbacks(timeout)
                if (gen != generation) return@requestTextBoundsInfo
                onTextBounds(result)
            }
        } catch (t: Throwable) {
            trace("TBI threw $t")
            handler.removeCallbacks(timeout)
            startRegionProbe(currentCaret())
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun onTextBounds(result: TextBoundsInfoResult) {
        val tbi = result.textBoundsInfo
        val ok = result.resultCode == TextBoundsInfoResult.CODE_SUCCESS && tbi != null
        trace("TBI code=${result.resultCode} range=${tbi?.startIndex}..${tbi?.endIndex}")
        if (!ok) {
            if (reacquiring) {
                reacquiring = false
                return
            }
            startRegionProbe(currentCaret())
            return
        }
        val map = mapFrom(tbi!!)
        if (map.isEmpty && (tbi.endIndex > tbi.startIndex || !fieldIsEmpty())) {
            if (reacquiring) {
                reacquiring = false
                return
            }
            startRegionProbe(currentCaret())
            return
        }
        abandonProbe()
        source = Source.TEXT_BOUNDS
        install(map)
    }

    /** Drops a probe in flight, undoing what it did to the field. */
    private fun abandonProbe() {
        val p = probe ?: return
        trace("PROBE abandoned ${p.kind}")
        cancelTimeouts()
        finishProbeComposition()
        if (p.kind != Probe.Kind.REGION && probeCaretOffset >= 0) {
            host.inputConnection?.setSelection(probeCaretOffset, probeCaretOffset)
        }
        probe = null
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun mapFrom(tbi: TextBoundsInfo): CaretMap {
        val start = tbi.startIndex
        val end = tbi.endIndex
        val m = Matrix()
        tbi.getMatrix(m)
        val graphemes = tbi.graphemeSegmentFinder
        val lines = tbi.lineSegmentFinder
        val r = RectF()
        val boxes = ArrayList<CharBox>(end - start)
        for (i in start until end) {
            tbi.getCharacterBounds(i, r)
            m.mapRect(r)
            val flags = tbi.getCharacterFlags(i)
            val lineStart = lines.previousStartBoundary(i + 1)
            boxes.add(
                CharBox(
                    offset = i,
                    box = Box(r.left, r.top, r.right, r.bottom),
                    newline = flags and TextBoundsInfo.FLAG_CHARACTER_LINEFEED != 0,
                    boundary = graphemes.previousStartBoundary(i + 1) == i,
                    line = if (lineStart >= 0) lineStart else -1,
                    rtl = tbi.getCharacterBidiLevel(i) and 1 == 1,
                    collapsible = flags and TextBoundsInfo.FLAG_CHARACTER_WHITESPACE != 0,
                ),
            )
        }
        val atEnd = isTextEnd(end)
        return CaretMapBuilder.build(
            boxes,
            endOffset = end,
            atTextStart = start == 0,
            atTextEnd = atEnd,
            startsAtLineStart = true,
        )
    }

    private fun isTextEnd(offset: Int): Boolean {
        if (textLength >= 0) return offset >= textLength
        val ic = host.inputConnection ?: return true
        val hi = selectionHigh().takeIf { it >= 0 } ?: return true
        if (offset < hi) return false
        val want = offset - hi
        val after = ic.getTextAfterCursor(want + 1, 0) ?: return true
        if (after.length <= want) {
            textLength = hi + after.length
            return true
        }
        return false
    }

    /** The upper end of the editor's selection: where `getTextAfterCursor` counts from. */
    private fun selectionHigh(): Int {
        val a = host.selectionStart
        val b = host.selectionEnd
        return if (a >= 0 && b >= 0) max(a, b) else currentCaret()
    }

    private fun fieldIsEmpty(): Boolean {
        val ic = host.inputConnection ?: return true
        return ic.getTextBeforeCursor(1, 0).isNullOrEmpty() && ic.getTextAfterCursor(1, 0).isNullOrEmpty()
    }

    // =============================================================================================
    // the map, from the composing region

    private fun startRegionProbe(caret: Int) {
        if (!owns) return
        if (selecting && phase == Phase.ACTIVE) {
            // Marking text composing can collapse a selection in some editors.
            reacquiring = false
            return
        }
        val ic = host.inputConnection ?: return fail("no connection")
        if (caret < 0) return fail("no caret")
        if (isGeckoPackage()) geckoLike = true
        if (geckoLike && !plainField()) {
            // A contenteditable in Firefox. Marking its text composing makes Gecko start a real
            // composition over it, and committing that rewrites the text as a plain string --
            // across paragraphs and formatting. Not worth a cursor.
            return if (phase == Phase.ACTIVE) {
                reacquiring = false
            } else {
                fail("gecko rich editor")
            }
        }
        val window = readWindow(caret, regionWindow) ?: return fail("cannot read text")
        if (window.text.isEmpty()) {
            source = Source.COMPOSING
            install(CaretMap.EMPTY)
            return
        }
        val p = Probe(
            Probe.Kind.REGION, window.start, window.text,
            window.atTextStart, window.atTextEnd, window.startsAtLineStart,
        )
        probe = p
        probeCaretOffset = caret
        composing = true
        holdAt = -1 // the probe's region replaces any region held for caret reports
        trace("PROBE region ${p.base}..${p.base + p.text.length}")
        ic.setComposingRegion(p.base, p.base + p.text.length)
        ic.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
        )
        val gen = generation
        after(PROBE_TIMEOUT_MS) {
            if (gen == generation && probe === p) {
                probeGaveNothing("timeout")
            }
        }
    }

    private class Window(
        val start: Int,
        val text: String,
        val caret: Int,
        val atTextStart: Boolean,
        val atTextEnd: Boolean,
        val startsAtLineStart: Boolean,
    )

    /**
     * Text either side of [caret], cut to whole lines where it can be: a window starting part way
     * through a line would have a first row that is only part of a row.
     */
    private fun readWindow(caret: Int, size: Int): Window? {
        val ic = host.inputConnection ?: return null
        // Text before the cursor is counted from the selection's lower end and text after from
        // its upper end, whichever way round the editor holds them; a selection in between is
        // read on its own so the window is contiguous.
        val a = host.selectionStart
        val b = host.selectionEnd
        val lo = if (a >= 0 && b >= 0) min(a, b) else caret
        val hi = if (a >= 0 && b >= 0) max(a, b) else caret
        val selected = if (lo < hi) {
            ic.getSelectedText(0)?.toString()?.takeIf { it.length == hi - lo } ?: return null
        } else {
            ""
        }
        val before = ic.getTextBeforeCursor(size, 0)?.toString() ?: return null
        val after = ic.getTextAfterCursor(size, 0)?.toString() ?: return null
        val atStart = before.length < size
        val atEnd = after.length < size
        var from = 0
        if (!atStart) {
            val nl = before.indexOf('\n')
            if (nl >= 0) from = nl + 1
        }
        var to = after.length
        if (!atEnd) {
            val nl = after.lastIndexOf('\n')
            if (nl >= 0) to = nl + 1
        }
        val text = before.substring(from) + selected + after.substring(0, to)
        if (atEnd) textLength = hi + after.length
        return Window(
            start = lo - (before.length - from),
            text = text,
            caret = caret,
            atTextStart = atStart && from == 0,
            atTextEnd = atEnd && to == after.length,
            startsAtLineStart = atStart || from > 0,
        )
    }

    private fun onProbeReport(info: CursorAnchorInfo, caret: FloatArray?) {
        val p = probe ?: return
        val composed = info.composingText ?: return
        // The editor's own copy of the text may differ in representation -- a contenteditable's
        // block boundaries, say -- but a report for this probe has its length.
        if (composed.length != p.text.length) return
        val cs = info.composingTextStart
        val boxes = ArrayList<CharBox>(p.text.length)
        var placed = 0
        var needed = 0
        for (k in p.text.indices) {
            val ch = p.text[k]
            val r = info.getCharacterBounds(cs + k)
            val flags = info.getCharacterBoundsFlags(cs + k)
            var box: Box? = null
            if (r != null) {
                val rr = RectF(r)
                info.matrix.mapRect(rr)
                box = Box(rr.left, rr.top, rr.right, rr.bottom)
                if (box.isValid) placed++
            }
            if (ch != '\n') needed++
            boxes.add(
                CharBox(
                    offset = p.base + k,
                    box = box,
                    newline = ch == '\n',
                    boundary = CaretMapBuilder.isCaretBoundary(p.text, k),
                    rtl = flags and CursorAnchorInfo.FLAG_IS_RTL != 0,
                    collapsible = ch.isWhitespace(),
                    hidden = flags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION == 0 &&
                        flags and CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION != 0,
                ),
            )
        }
        trace("PROBE report kind=${p.kind} placed=$placed/$needed cs=$cs")
        if (needed > 0 && placed * 10 < needed * 6) {
            if (p.kind == Probe.Kind.REGION) {
                // The composing text without its bounds. GeckoView always answers like this --
                // it measures only a composition Gecko started itself -- and gives itself away
                // by numbering the composition from 0 wherever it is. Anything else may simply
                // not have measured yet (Chrome answers the immediate request with what it had),
                // so it gets until the timeout, and a nudge.
                geckoLike = geckoLike || (cs == 0 && p.base != 0) || isGeckoPackage()
                if (geckoLike) {
                    probeGaveNothing("no bounds")
                } else {
                    host.inputConnection?.requestCursorUpdates(
                        InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
                    )
                }
            }
            return
        }
        cancelTimeouts()
        when (p.kind) {
            Probe.Kind.REGION -> {
                finishProbeComposition()
                source = Source.COMPOSING
                install(
                    CaretMapBuilder.build(
                        boxes,
                        endOffset = p.base + p.text.length,
                        atTextStart = p.atTextStart,
                        atTextEnd = p.atTextEnd,
                        startsAtLineStart = p.startsAtLineStart,
                        trimPartialRows = true,
                    ),
                    caret,
                )
            }
            Probe.Kind.REPLACE_BEFORE -> {
                probeBefore = boxes
                probeBeforeCaret = caret
                startReplaceAfter()
            }
            Probe.Kind.REPLACE_AFTER -> {
                val before = probeBefore.orEmpty()
                val shifted = alignAfterHalf(boxes, caret)
                finishProbeComposition()
                host.inputConnection?.setSelection(probeCaretOffset, probeCaretOffset)
                val w = probeWindow!!
                source = Source.GECKO_REPLACE
                install(
                    CaretMapBuilder.build(
                        before + shifted,
                        endOffset = w[1],
                        atTextStart = w[2] == 1,
                        atTextEnd = w[3] == 1,
                        startsAtLineStart = w[4] == 1,
                        trimPartialRows = true,
                    ),
                    probeBeforeCaret ?: caret,
                )
            }
        }
    }

    /**
     * The two Firefox halves are measured a frame or two apart, and Gecko may have scrolled in
     * between. Both reports put the caret at the same offset, so any difference between them is
     * that scroll, and the second half is moved back by it.
     */
    private fun alignAfterHalf(boxes: List<CharBox>, caret: FloatArray?): List<CharBox> {
        val a = probeBeforeCaret ?: return boxes
        val b = caret ?: return boxes
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        if (abs(dx) < 0.5f && abs(dy) < 0.5f) return boxes
        return boxes.map { c ->
            c.copy(box = c.box?.let { Box(it.left + dx, it.top + dy, it.right + dx, it.bottom + dy) })
        }
    }

    private fun probeGaveNothing(why: String) {
        val p = probe ?: return
        trace("PROBE nothing ($why) kind=${p.kind} gecko=$geckoLike")
        cancelTimeouts()
        finishProbeComposition()
        if (p.kind != Probe.Kind.REGION) {
            host.inputConnection?.setSelection(probeCaretOffset, probeCaretOffset)
            probe = null
            if (phase == Phase.ACTIVE) {
                reacquiring = false
                return
            }
            fail("replacement probe $why")
            return
        }
        probe = null
        if (geckoLike && plainField() && !(selecting && phase == Phase.ACTIVE)) {
            startReplaceBefore()
            return
        }
        if (!geckoLike && regionWindow > REGION_WINDOW_SMALL && p.text.length > 2 * REGION_WINDOW_SMALL) {
            // Some editors measure only so many characters of a composition. Try a smaller one
            // before giving up on the map altogether.
            regionWindow = REGION_WINDOW_SMALL
            startRegionProbe(currentCaret())
            return
        }
        if (phase == Phase.ACTIVE) {
            reacquiring = false
            return
        }
        fail("region probe $why")
    }

    private fun finishProbeComposition() {
        if (composing) {
            host.inputConnection?.finishComposingText()
            composing = false
        }
    }

    // --- Firefox: the replacement probe --------------------------------------------------------

    /**
     * Whether text may be replaced by itself without losing anything. GeckoView offers images
     * (`contentMimeTypes`) only to `contenteditable`, which can carry formatting a plain string
     * would erase; `<input>` and `<textarea>` hold nothing but the string.
     */
    private fun plainField(): Boolean {
        val info = host.editorInfo ?: return false
        if (!info.contentMimeTypes.isNullOrEmpty()) return false
        val cls = info.inputType and android.text.InputType.TYPE_MASK_CLASS
        if (cls != android.text.InputType.TYPE_CLASS_TEXT) return false
        return when (info.inputType and android.text.InputType.TYPE_MASK_VARIATION) {
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            -> false
            else -> true
        }
    }

    private fun isGeckoPackage(): Boolean {
        val pkg = host.editorInfo?.packageName ?: return false
        return pkg.startsWith("org.mozilla.") || pkg in GECKO_PACKAGES
    }

    private fun startReplaceBefore() {
        val caret = currentCaret()
        probeCaretOffset = caret
        holdAt = -1
        val window = readWindow(caret, REPLACE_WINDOW) ?: return fail("cannot read text")
        val split = window.caret - window.start
        probeWindow = intArrayOf(
            window.start,
            window.start + window.text.length,
            if (window.atTextStart) 1 else 0,
            if (window.atTextEnd) 1 else 0,
            if (window.startsAtLineStart) 1 else 0,
            split,
            caret,
        )
        probeBefore = null
        probeBeforeCaret = null
        if (split <= 0) {
            probeBefore = emptyList()
            startReplaceAfter()
            return
        }
        replaceProbe(
            Probe(
                Probe.Kind.REPLACE_BEFORE, window.start, window.text.substring(0, split),
                window.atTextStart, false, window.startsAtLineStart,
            ),
            newCursorPosition = 1,
        )
    }

    private fun startReplaceAfter() {
        val w = probeWindow ?: return fail("no window")
        val caret = w[6]
        val text = readText(caret, w[1] - caret)
        if (text.isNullOrEmpty()) {
            val before = probeBefore.orEmpty()
            finishProbeComposition()
            host.inputConnection?.setSelection(probeCaretOffset, probeCaretOffset)
            probe = null
            if (before.isEmpty()) return fail("nothing measured")
            source = Source.GECKO_REPLACE
            install(
                CaretMapBuilder.build(
                    before, endOffset = caret, atTextStart = w[2] == 1, atTextEnd = w[3] == 1,
                    startsAtLineStart = w[4] == 1, trimPartialRows = true,
                ),
                probeBeforeCaret,
            )
            return
        }
        finishProbeComposition()
        replaceProbe(
            Probe(Probe.Kind.REPLACE_AFTER, caret, text, false, w[3] == 1, false),
            newCursorPosition = 0,
        )
    }

    private fun readText(from: Int, length: Int): String? {
        val ic = host.inputConnection ?: return null
        if (length <= 0) return ""
        // The caret is held at [from] by the probe, which is where this counts from.
        return ic.getTextAfterCursor(length, 0)?.toString()
    }

    /**
     * Re-sets [Probe.text] as composing text over itself. The text is passed as a [SpannableString]
     * so the connection adds none of its default composing styling, and with the cursor at
     * whichever end of the window the caret already is, so the caret does not move and nothing
     * scrolls to follow it.
     */
    private fun replaceProbe(p: Probe, newCursorPosition: Int) {
        val ic = host.inputConnection ?: return fail("no connection")
        probe = p
        composing = true
        trace("PROBE replace ${p.kind} ${p.base}..${p.base + p.text.length}")
        ic.setComposingRegion(p.base, p.base + p.text.length)
        ic.setComposingText(SpannableString(p.text), newCursorPosition)
        ic.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
        )
        val gen = generation
        after(REPLACE_TIMEOUT_MS) {
            if (gen == generation && probe === p) probeGaveNothing("timeout")
        }
    }

    // =============================================================================================
    // installing a map

    private fun install(map: CaretMap, probeCaret: FloatArray? = null) {
        probe = null
        val current = currentCaret()
        steer.install(map, if (selecting) (sentEnd.takeIf { it >= 0 } ?: current) else current)
        updateBand()
        // The baseline is a property of the source -- how its caret reports sit against its
        // character bounds -- so a map re-read from the same source keeps it. Re-measuring from
        // the latest report would fold in any scroll since that report was sent.
        if (baselineSource != source || probeCaret != null) {
            baselineX = Float.NaN
            baselineY = Float.NaN
        }
        baselineSource = source
        // A probe report carries the caret measured together with the bounds; otherwise the last
        // report is as good, since nothing has moved the caret yet.
        val cp = probeCaret ?: caretPoint
        val cpOffset = if (probeCaret != null) probeCaretOffset else caretPointOffset
        if (cp != null && cpOffset >= 0 && baselineX.isNaN()) calibrate(cpOffset, cp)
        trace(
            "MAP source=$source rows=${map.rows.size} start=${map.atTextStart} end=${map.atTextEnd} " +
                "band=${steer.bandTop}..${steer.bandBottom} x ${steer.bandLeft}..${steer.bandRight}",
        )
        if (geckoLike) holdForReports(current)
        steer.edgeScrollAllowed = caretPoint != null && !map.isEmpty && !edgeScrollDisabled
        if (phase == Phase.ACQUIRING) {
            phase = Phase.ACTIVE
            val cp2 = caretPoint
            steer.seed(
                current,
                cp2?.get(0) ?: Float.NaN,
                cp2?.let { (it[1] + it[2]) / 2f } ?: Float.NaN,
                bankX, bankY,
            )
            bankX = 0f
            bankY = 0f
            sentStart = host.selectionStart
            sentEnd = host.selectionEnd
            if (selecting && anchor < 0) anchor = current
            update()
        } else {
            reacquiring = false
            // Whatever was held back while the probe ran is recomputed from the marker now.
            queued = null
            update()
        }
        if (!steer.edgeScrollAllowed) {
            // No caret report yet; ask, so scrolling can be followed once one arrives.
            host.inputConnection?.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
            )
        }
    }

    private fun updateBand() {
        val kb = host.keyboardTop()
        val e = editorBox
        val trusted = e != null && when (source) {
            Source.TEXT_BOUNDS -> true
            // Firefox has been seen publishing bounds hundreds of pixels off the text.
            Source.GECKO_REPLACE -> false
            else -> !geckoLike && caretPoint.let { c ->
                c == null || (c[0] >= e.left - 2f && c[0] <= e.right + 2f &&
                    c[1] >= e.top - 2f && c[2] <= e.bottom + 2f)
            }
        }
        steer.screenLeft = 0f
        steer.screenTop = 0f
        steer.screenRight = host.screenWidth()
        steer.screenBottom = host.screenHeight()
        if (trusted && e != null) {
            steer.outerTop = max(e.top, 0f)
            steer.outerBottom = min(e.bottom, kb)
            steer.outerLeft = e.left
            steer.outerRight = e.right
        } else {
            steer.outerTop = 0f
            steer.outerBottom = kb
            steer.outerLeft = Float.NEGATIVE_INFINITY
            steer.outerRight = Float.POSITIVE_INFINITY
        }
    }

    private fun calibrate(offset: Int, p: FloatArray) {
        val at = steer.map.locate(offset) ?: return
        val row = steer.map.rows[at.row]
        if (row.ambiguous(at.stop)) return
        baselineX = p[0] - row.xs[at.stop]
        baselineY = p[1] - row.top
        // A caret is drawn a pixel or two off its character's edge in some editors; more than a
        // character off is not that, it is the map being stale, and calibrating it in would hide
        // the error for the rest of the gesture.
        if (abs(baselineX) > row.gap) baselineX = 0f
        if (abs(baselineY) > row.height * 0.6f) baselineY = 0f
        trace("CALIBRATE off=$offset bx=$baselineX by=$baselineY")
    }

    // =============================================================================================
    // following the text as it scrolls

    private fun track(offset: Int, p: FloatArray) {
        val at = steer.map.locate(offset) ?: return
        val row = steer.map.rows[at.row]
        if (row.ambiguous(at.stop)) return
        if (baselineX.isNaN()) {
            calibrate(offset, p)
            return
        }
        val dx = p[0] - row.xs[at.stop] - baselineX
        val dy = p[1] - row.top - baselineY
        // Only the end being dragged is what the editor scrolled to reveal; a selection's anchor
        // reported moving is the text moving, but says nothing about where the edge is.
        val placedHere = offset == sentEnd
        if (abs(dx) < SCROLL_EPSILON && abs(dy) < SCROLL_EPSILON) {
            if (edgeAwaiting == offset) {
                val waited = SystemClock.uptimeMillis() - edgeSentAt
                if (waited < UNREVEALED_AFTER_MS) {
                    // Too soon to tell: scrolling to reveal a caret may happen a frame or two
                    // after the caret moved, and the report may predate it.
                    handler.removeCallbacks(followUp)
                    handler.postDelayed(followUp, UNREVEALED_AFTER_MS - waited)
                    return
                }
                edgeAwaiting = -1
                if (lastPlacedByEdge && !rowVisible(at.row)) noteUnrevealed()
            }
            return
        }
        trace("SCROLL off=$offset dx=$dx dy=$dy")
        steer.scrolled(dx, dy)
        if (placedHere) steer.learnEdgeFromScroll(dx, dy, offset)
        if (edgeAwaiting == offset) {
            edgeAwaiting = -1
            unrevealedSteps = 0
        }
        updateBand()
        update()
    }

    private fun rowVisible(row: Int): Boolean {
        val r = steer.map.rows[row]
        return r.centerY >= steer.bandTop && r.centerY <= steer.bandBottom
    }

    /** The editor did not scroll to show a caret placed past its edge. */
    private fun noteUnrevealed() {
        unrevealedSteps++
        trace("UNREVEALED $unrevealedSteps")
        if (unrevealedSteps < 2) return
        if (selecting) {
            edgeScrollDisabled = true
            steer.edgeScrollAllowed = false
            steer.clamp()
        } else {
            edgeViaKeys = true
        }
        // Bring the caret back to where the marker can see it.
        steer.hit = null
        update()
    }

    /**
     * Which offset a caret report describes.
     *
     * Most editors say, in the report's selection. GeckoView leaves it at -1, so its reports are
     * matched to the request that produced them -- one is ever outstanding -- or to the
     * selection `onUpdateSelection` last gave. With a selection, platform text views report the
     * selection's start and GeckoView its moving end; which one an editor does is learned the
     * first time the two are far enough apart to tell.
     */
    private fun reportedOffset(info: CursorAnchorInfo): Int {
        val s = info.selectionStart
        val e = info.selectionEnd
        if (s >= 0 && e >= 0) {
            if (s == e) return s
            if (reportedEnd == 0) learnReportedEnd(info, s, e)
            return if (reportedEnd > 0) e else s
        }
        if (geckoAsked >= 0) {
            val o = geckoAsked
            geckoAsked = -1
            val latest = host.selectionEnd
            if (latest >= 0 && latest != o && phase == Phase.ACTIVE) handler.post { askGecko(latest) }
            return o
        }
        return host.selectionEnd
    }

    private fun learnReportedEnd(info: CursorAnchorInfo, s: Int, e: Int) {
        val p = caretOf(info) ?: return
        val ps = steer.map.pointOf(s) ?: return
        val pe = steer.map.pointOf(e) ?: return
        if (abs(ps[0] - pe[0]) < 4f && abs(ps[1] - pe[1]) < 4f) return
        val ds = abs(p[0] - ps[0]) + abs((p[1] + p[2]) / 2f - ps[1])
        val de = abs(p[0] - pe[0]) + abs((p[1] + p[2]) / 2f - pe[1])
        if (ds < 3f && de > 6f) reportedEnd = -1
        if (de < 3f && ds > 6f) reportedEnd = 1
    }

    /**
     * GeckoView reports its caret only when asked and only while a composing region exists. A
     * one-character region is held for it, far from wherever the caret is heading: Gecko will
     * not move its caret into a composing region it did not start itself, so a region the caret
     * reached would stop the caret dead there.
     */
    private fun holdForReports(near: Int) {
        val ic = host.inputConnection ?: return
        val len = textLength.takeIf { it > 0 } ?: run {
            val after = ic.getTextAfterCursor(1 shl 16, 0)?.length ?: return
            (selectionHigh().coerceAtLeast(0) + after).also { textLength = it }
        }
        // The held character must stay clear of the caret on both sides, which a very short
        // text cannot offer; such a field has nothing to scroll anyway.
        if (len < 4) return
        val at = if (near < len / 2) len - 1 else 0
        if (holdAt == at) return
        holdAt = at
        ic.setComposingRegion(at, at + 1)
        askGecko(near)
    }

    private fun askGecko(offset: Int) {
        if (!geckoLike || offset < 0) return
        val now = SystemClock.uptimeMillis()
        if (geckoAsked >= 0 && now - geckoAskedAt < GECKO_ASK_TIMEOUT_MS) return
        geckoAsked = offset
        geckoAskedAt = now
        host.inputConnection?.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
        )
    }

    // =============================================================================================
    // moving the caret

    /** Recomputes the caret from the marker, places it if it changed, and redraws. */
    private fun update() {
        if (phase != Phase.ACTIVE) return
        val before = steer.hit
        val hit = steer.target()
        if (hit != null && hit != before) place(hit, byEdge = false)
        else if (hit != null && sentEnd != steer.map.offsetOf(hit) && queued == null && !awaitingAck) {
            place(hit, byEdge = false)
        }
        scheduleEdge()
        host.redraw()
    }

    private fun place(hit: Hit, byEdge: Boolean) {
        val o = steer.map.offsetOf(hit)
        val start = if (selecting) anchor else o
        lastPlacedByEdge = byEdge
        if (geckoLike && holdAt >= 0 && abs(o - holdAt) < 2) holdForReports(o)
        send(start, o)
    }

    /**
     * Sends a selection, never more than one ahead of the editor. A fast drag changes the offset
     * every frame, and an editor slower than that would otherwise build a queue of positions it
     * then plays back -- lag. Only the newest is kept while one is in flight.
     */
    private fun send(start: Int, end: Int) {
        if (start == sentStart && end == sentEnd) {
            queued = null
            return
        }
        if (probe != null) {
            // Moving the caret mid-probe would move it inside the text being measured. The probe
            // is a frame or two; the newest position goes out the moment it is done.
            queued = intArrayOf(start, end)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (awaitingAck && now - sentAt < ACK_TIMEOUT_MS) {
            queued = intArrayOf(start, end)
            return
        }
        val ic = host.inputConnection ?: return
        queued = null
        sentStart = start
        sentEnd = end
        sentAt = now
        awaitingAck = true
        ic.setSelection(start, end)
        if (!awaitingAckTimeoutPosted) {
            awaitingAckTimeoutPosted = true
            handler.postDelayed(ackTimeout, ACK_TIMEOUT_MS)
        }
    }

    private var awaitingAckTimeoutPosted = false
    private val ackTimeout = Runnable {
        awaitingAckTimeoutPosted = false
        if (awaitingAck) {
            // Not every editor echoes a selection it normalised; do not wait forever.
            awaitingAck = false
            flushQueued()
        }
    }

    private fun flushQueued() {
        val q = queued ?: return
        queued = null
        send(q[0], q[1])
    }

    // --- edges ---------------------------------------------------------------------------------

    private fun scheduleEdge() {
        handler.removeCallbacks(edgeTick)
        if (phase == Phase.ACTIVE && (steer.verticalEdge() != 0 || steer.horizontalEdge() != 0)) {
            handler.post(edgeTick)
        }
    }

    private val edgeTick = object : Runnable {
        override fun run() {
            if (phase != Phase.ACTIVE) return
            val v = steer.verticalEdge()
            val hz = steer.horizontalEdge()
            if (v == 0 && hz == 0) return
            val now = SystemClock.uptimeMillis()
            if (edgeAwaiting >= 0 && now - edgeSentAt < EDGE_AWAIT_MS) {
                handler.postDelayed(this, 16)
                return
            }
            edgeAwaiting = -1
            if (reacquiring) {
                handler.postDelayed(this, 32)
                return
            }
            if (edgeViaKeys && !selecting) {
                stepWithKey(v, hz)
            } else {
                val step = steer.edgeStep()
                if (step == null) {
                    reacquire()
                    handler.postDelayed(this, 32)
                    return
                }
                steer.hit = step
                place(step, byEdge = true)
                edgeAwaiting = steer.map.offsetOf(step)
                edgeSentAt = now
            }
            host.redraw()
            handler.postDelayed(this, edgeInterval())
        }
    }

    /** An arrow key, which every editor scrolls to show, for editors that do not do it for an offset. */
    private fun stepWithKey(v: Int, h: Int) {
        val ic = host.inputConnection ?: return
        val code = when {
            v > 0 -> KeyEvent.KEYCODE_DPAD_DOWN
            v < 0 -> KeyEvent.KEYCODE_DPAD_UP
            h > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> KeyEvent.KEYCODE_DPAD_LEFT
        }
        // Down at the end of the text, or up at its start, moves focus out of the field.
        val caret = host.selectionEnd
        if (code == KeyEvent.KEYCODE_DPAD_UP && caret <= 0) return
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && ic.getTextAfterCursor(1, 0).isNullOrEmpty()) return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        edgeSentAt = now
        awaitingAck = false
        queued = null
    }

    private fun edgeInterval(): Long {
        val ramp = (steer.edgeOvershoot() / EDGE_FULL_SPEED_PX).coerceIn(0f, 1f)
        return (EDGE_SLOWEST_MS - (EDGE_SLOWEST_MS - EDGE_FASTEST_MS) * ramp).toLong()
    }

    /** The marker is at the edge of what the map covers and the text goes on: map more of it. */
    private fun reacquire() {
        if (reacquiring) return
        reacquiring = true
        trace("REACQUIRE source=$source")
        when (source) {
            Source.TEXT_BOUNDS -> requestTextBounds()
            Source.COMPOSING -> startRegionProbe(currentCaret())
            Source.GECKO_REPLACE -> if (selecting) reacquiring = false else {
                if (holdAt >= 0) {
                    host.inputConnection?.finishComposingText()
                    holdAt = -1
                }
                startReplaceBefore()
            }
            null -> reacquiring = false
        }
    }

    // =============================================================================================

    private fun currentCaret(): Int {
        if (phase == Phase.ACTIVE && sentEnd >= 0) return sentEnd
        val e = host.selectionEnd
        return if (e >= 0) e else startCaret
    }

    private fun caretOf(info: CursorAnchorInfo): FloatArray? {
        val h = info.insertionMarkerHorizontal
        val t = info.insertionMarkerTop
        val b = info.insertionMarkerBottom
        if (h.isNaN() || t.isNaN() || b.isNaN()) return null
        val pts = floatArrayOf(h, t, h, b)
        info.matrix.mapPoints(pts)
        return floatArrayOf(pts[0], pts[1], pts[3])
    }

    private fun after(ms: Long, block: () -> Unit): Runnable {
        val r = object : Runnable {
            override fun run() {
                timeouts.remove(this)
                block()
            }
        }
        timeouts.add(r)
        handler.postDelayed(r, ms)
        return r
    }

    private fun cancelTimeouts() {
        for (r in timeouts) handler.removeCallbacks(r)
        timeouts.clear()
        handler.removeCallbacks(ackTimeout)
        awaitingAckTimeoutPosted = false
    }

    private fun trace(message: String) = host.trace("PC $message")

    companion object {
        /** Characters either side of the caret measured through the composing region. */
        const val REGION_WINDOW = 700
        const val REGION_WINDOW_SMALL = 160
        /** Smaller for Firefox, where measuring means re-setting the text. */
        const val REPLACE_WINDOW = 400
        const val TEXT_BOUNDS_TIMEOUT_MS = 150L
        const val PROBE_TIMEOUT_MS = 200L
        const val REPLACE_TIMEOUT_MS = 260L
        /** How long a sent selection may go unacknowledged before the next is sent anyway. */
        const val ACK_TIMEOUT_MS = 40L
        const val GECKO_ASK_TIMEOUT_MS = 120L
        /** When to ask again for the caret after it moved, to see the scroll the move caused. */
        const val FOLLOW_UP_MS = 70L
        /** How long an editor gets to scroll a caret into view before it is taken not to. */
        const val UNREVEALED_AFTER_MS = 140L
        /** Report-to-map disagreement below this is noise, not scrolling. */
        const val SCROLL_EPSILON = 1.0f
        const val EDGE_AWAIT_MS = 180L
        const val EDGE_SLOWEST_MS = 260L
        const val EDGE_FASTEST_MS = 45L
        const val EDGE_FULL_SPEED_PX = 300f

        val GECKO_PACKAGES = setOf(
            "io.github.forkmaintainers.iceraven",
            "us.spotco.fennec_dos",
            "org.ironfoxoss.ironfox",
            "net.waterfox.android.release",
            "org.torproject.torbrowser",
        )
    }
}
