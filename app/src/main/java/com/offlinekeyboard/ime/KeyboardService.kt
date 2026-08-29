package com.offlinekeyboard.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.PopupWindow
import com.offlinekeyboard.ime.view.CursorIndicatorView
import kotlin.math.abs
import kotlin.math.roundToInt
import com.offlinekeyboard.ime.gesture.GestureOutput
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.Layout
import com.offlinekeyboard.ime.view.KeyboardView

/**
 * Translates gesture outputs into edits on the focused text field.
 *
 * Deliberately absent: autocorrect. A tapped key produces exactly that character, always.
 * Word decoding will exist only to turn a glide gesture into a word (Phase 2).
 */
private const val TAG = "OfflineKeyboard"

/**
 * Auto-scroll rate while the marker is parked past an edge of the visible text, in milliseconds
 * per line. Proportional to how far past the edge the marker is: just over the line creeps, a
 * long way past moves quickly, which is how dragging a selection to the edge of a window behaves
 * everywhere else.
 */
private const val EDGE_SCROLL_SLOWEST_MS = 260L
private const val EDGE_SCROLL_FASTEST_MS = 45L
/** Distance past the edge, in pixels, at which the fastest rate is reached. */
private const val EDGE_SCROLL_FULL_SPEED_PX = 420f

/**
 * Debug tooling: logs every gesture output, and registers a broadcast receiver that stands in
 * for the second finger of the selection gesture, which adb cannot send. Tied to the build type
 * so the receiver -- which is necessarily exported -- never exists in a release build.
 */
private val DEBUG_GESTURES = BuildConfig.DEBUG

class KeyboardService : InputMethodService() {

    private var keyboardView: KeyboardView? = null

    private enum class ShiftState { OFF, ONE_SHOT, LOCKED }

    private var shift = ShiftState.OFF
    private var lastShiftTapAt = 0L

    /** True while a physical shift key is being held down to drag a selection. */
    private var extendingSelection = false

    // --- granular cursor ---
    private var indicator: CursorIndicatorView? = null
    private var indicatorPopup: PopupWindow? = null
    private var trackpadActive = false

    /**
     * The granular cursor, in screen coordinates. This is what the finger drives directly, and
     * it is never derived from the caret -- which is why it moves smoothly.
     */
    private var markerX = Float.NaN
    private var markerCenterY = Float.NaN

    /** Caret position last reported by the app, in screen coordinates. */
    private var caretX = Float.NaN
    private var caretTop = Float.NaN
    private var caretBottom = 0f
    private var lineHeight = 0f
    private var charWidth = 0f

    /** Arrow keys sent but not yet reflected in a CursorAnchorInfo update. */
    private var pendingHorizontal = 0
    private var pendingVertical = 0
    /** How long we have waited for that reflection, so an impossible move cannot wedge us. */
    private var chaseWaitTicks = 0
    /** Caret offset when the outstanding vertical arrows were sent, to tell moved from stuck. */
    private var pendingFromOffset = -1

    /**
     * True once a vertical step moved the caret in the text but not on screen, which is the
     * signature of the editor scrolling to keep a pinned caret in view.
     */
    private var scrollPinned = false

    private val handler = Handler(Looper.getMainLooper())
    /** Repeats a single line step while the marker is held past an edge of the visible text. */
    private val edgeScrollTick = object : Runnable {
        override fun run() {
            val dir = edgeScrollDirection()
            if (!trackpadActive || dir == 0) return
            scrollOneLine(dir)
            handler.postDelayed(this, edgeScrollInterval())
        }
    }
    /**
     * Which way the caret has run out of text: +1 cannot go further down, -1 cannot go further
     * up, 0 free. Directional and sticky, so the marker can be stopped from travelling further
     * that way -- distance it accumulates beyond the end of the text has to be un-travelled
     * before anything responds again, which reads as the cursor freezing and then snapping.
     */
    private var verticalStuckDir = 0

    /** Horizontal extent of the editor on screen, for spotting the end of a wrapped row. */
    private var editorLeft = 0f
    private var editorRight = Float.NaN

    /**
     * While extending a selection, the finger's travel is banked here until it amounts to a
     * whole character or line. See [extendSelection] for why this is not the closed loop that
     * plain cursor movement uses.
     */
    private var selectionBankY = 0f

    /** Caret offset in the text, tracked from CursorAnchorInfo while the caret is collapsed. */
    private var caretOffset = -1

    /** The selection's fixed end, and the end being dragged. */
    private var selectionAnchor = -1
    private var selectionMovingEnd = -1
    private var selectionPrevMovingEnd = -1
    /** Top of the line the moving end is on, to detect it wrapping onto another line. */
    private var selectionLineTop = Float.NaN
    private var selectionAppliedChars = 0
    private var selectionPrevInsH = Float.NaN
    /**
     * A snapshot of the field's text, taken when the drag begins, used to find line boundaries
     * so the moving end can be clamped to its own line. No editing happens during a drag, so it
     * cannot go stale.
     */
    private var selectionText: CharSequence? = null
    private var selectionTextStart = 0
    /** A line change is in flight; wait for the app to report it before steering again. */
    private var selectionAwaitingLine = false
    /** Position collapsed onto for a line change, to tell the arrow's result from its echo. */
    private var selectionPreStepEnd = -1
    private var selectionWaitTicks = 0
    /** Last reported selection spans, so steering can run on a pan as well as on an update. */
    private var lastSelStart = -1
    private var lastSelEnd = -1

    /** Debug only: stands in for the second finger, which adb cannot send. */
    private val debugSelectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d(TAG, "debug broadcast: ${intent?.action}")
            when (intent?.action) {
                "com.offlinekeyboard.ime.DEBUG_REVSEL" -> {
                    // Does a reversed selection make the app report the *moving* end?
                    val a = intent.getIntExtra("a", 204)
                    val b = intent.getIntExtra("b", 211)
                    currentInputConnection?.requestCursorUpdates(
                        InputConnection.CURSOR_UPDATE_MONITOR,
                    )
                    currentInputConnection?.setSelection(a, b)
                    android.util.Log.d(TAG, "debug setSelection($a, $b)")
                }
                else -> keyboardView?.debugStartSelection()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG_GESTURES) {
            registerReceiver(
                debugSelectReceiver,
                IntentFilter().apply {
                    addAction("com.offlinekeyboard.ime.DEBUG_SELECT")
                    addAction("com.offlinekeyboard.ime.DEBUG_REVSEL")
                },
                Context.RECEIVER_EXPORTED,
            )
        }
    }

    override fun onDestroy() {
        if (DEBUG_GESTURES) runCatching { unregisterReceiver(debugSelectReceiver) }
        super.onDestroy()
    }

    override fun onCreateInputView(): View =
        KeyboardView(this).also { view ->
            keyboardView = view
            view.onOutput = ::handleOutputs
            applyLayout()
        }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        endSelection()
        stopTrackpad()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shift = ShiftState.OFF
        applyLayout()
    }

    private var plane: Layout = IosLayouts.QWERTY_LOWER

    private fun applyLayout() {
        val target = when {
            plane.id.startsWith("en_qwerty") && shift != ShiftState.OFF -> IosLayouts.QWERTY_UPPER
            plane.id.startsWith("en_qwerty") -> IosLayouts.QWERTY_LOWER
            else -> plane
        }
        keyboardView?.layout = target
    }

    private fun handleOutputs(outputs: List<GestureOutput>) {
        if (DEBUG_GESTURES) outputs.forEach { android.util.Log.d(TAG, "gesture: $it") }
        outputs.forEach { out ->
            when (out) {
                is GestureOutput.CommitPrimary -> commit(out.text)
                is GestureOutput.CommitSecondary -> commit(out.text)
                is GestureOutput.CommitAccent -> commit(out.text)
                GestureOutput.SelectionStarted -> beginSelection()
                GestureOutput.TrackpadStarted -> startTrackpad()
                is GestureOutput.TrackpadPan -> panMarker(out.dx, out.dy)
                GestureOutput.TrackpadEnded -> {
                    endSelection()
                    stopTrackpad()
                }
                is GestureOutput.SpecialKey -> handleSpecialKey(out.type)
                is GestureOutput.GlideCompleted -> Unit // Phase 2: decode the path into a word
                else -> Unit
            }
        }
    }

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
        // iOS one-shot shift: the next letter is capitalised, then shift releases.
        if (shift == ShiftState.ONE_SHOT && text.isNotBlank()) {
            shift = ShiftState.OFF
            applyLayout()
        }
    }

    private fun handleSpecialKey(type: KeyType) {
        when (type) {
            KeyType.SHIFT -> toggleShift()
            KeyType.BACKSPACE -> backspace()
            KeyType.MODE_SWITCH -> cycleplane()
            KeyType.GLOBE -> switchToNextInputMethod(false)
            KeyType.MIC -> Unit // Phase 4: offline dictation
            else -> Unit
        }
    }

    /** Tap for one-shot shift; a second tap within 300ms locks caps, as on iOS. */
    private fun toggleShift() {
        val now = System.currentTimeMillis()
        shift = when {
            shift != ShiftState.OFF && now - lastShiftTapAt < 300 -> ShiftState.LOCKED
            shift == ShiftState.OFF -> ShiftState.ONE_SHOT
            else -> ShiftState.OFF
        }
        lastShiftTapAt = now
        applyLayout()
    }

    private fun cycleplane() {
        plane = when (plane.id) {
            "numbers" -> IosLayouts.SYMBOLS
            "symbols" -> IosLayouts.QWERTY_LOWER
            else -> IosLayouts.NUMBERS
        }
        shift = ShiftState.OFF
        applyLayout()
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
    }

    /**
     * Requirement 5. Vertical movement goes through DPAD key events rather than setSelection,
     * because only the text view knows where its lines wrap.
     *
     * With [extend] set, the same arrows are sent with shift held, which every text view reads
     * as "drag the free end of the selection" -- so selection follows lines exactly the way
     * caret movement does, with no separate code path.
     */
    // --- the granular cursor leads; the caret follows -------------------------------------

    private fun startTrackpad() {
        trackpadActive = true
        // IMMEDIATE as well as MONITOR. MONITOR alone only delivers when the cursor *moves*,
        // so a second trackpad gesture with no editing in between would never receive a seed
        // position, leaving the marker unplaced and the whole gesture inert. That is why it
        // previously took a tap in the text to "wake up" between drags.
        val ok = currentInputConnection?.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
        )
        if (DEBUG_GESTURES) android.util.Log.d(TAG, "requestCursorUpdates -> $ok")
        markerX = Float.NaN
        markerCenterY = Float.NaN
        caretX = Float.NaN
        caretTop = Float.NaN
        pendingHorizontal = 0
        pendingVertical = 0
        verticalStuckDir = 0
        updateIndicator()
    }

    private fun stopTrackpad() {
        trackpadActive = false
        scrollPinned = false
        handler.removeCallbacks(edgeScrollTick)
        markerX = Float.NaN
        markerCenterY = Float.NaN
        currentInputConnection?.requestCursorUpdates(0)
        indicatorPopup?.takeIf { it.isShowing }?.let { runCatching { it.dismiss() } }
    }

    /** The finger moves the marker, freely, in screen space. Nothing constrains it to the text. */
    private fun panMarker(dx: Float, dy: Float) {
        if (!trackpadActive || markerX.isNaN()) return
        val metrics = resources.displayMetrics
        // Movement back the other way frees it again.
        if (verticalStuckDir != 0 && dy != 0f && (dy > 0f) != (verticalStuckDir > 0)) {
            verticalStuckDir = 0
        }
        // Do not let the marker travel past where the caret can actually follow.
        val effectiveDy = if (verticalStuckDir != 0 && (dy > 0f) == (verticalStuckDir > 0)) 0f else dy
        markerX = (markerX + dx).coerceIn(0f, metrics.widthPixels.toFloat())
        markerCenterY = (markerCenterY + effectiveDy).coerceIn(0f, metrics.heightPixels.toFloat())
        updateIndicator()
        updateEdgeScroll()
        if (extendingSelection) {
            // Steer on the pan as well as on cursor updates: CURSOR_UPDATE_MONITOR only fires
            // when the cursor actually moves, so waiting for one would deadlock -- no movement,
            // no update, no movement.
            //
            // steerSelection must be reached even while a line change is outstanding, because
            // that is where the wait is timed out. Gating it here meant an arrow that moved
            // nothing -- at the top or bottom of the text -- left the latch set forever, and
            // the selection stopped responding entirely.
            if (!extendSelection(effectiveDy)) steerSelection()
        } else {
            chaseCaret()
        }
    }

    /**
     * Vertical half of dragging a selection. Horizontal is handled by the closed loop in
     * [steerSelection]; only line changes need a keystroke, because an offset cannot express
     * "one visual line down" when lines soft-wrap.
     *
     * To move a line the selection is briefly collapsed onto the moving end so a plain arrow
     * key acts on it, and is re-applied once the new position is reported.
     */
    private fun extendSelection(dy: Float): Boolean {
        val lh = lineHeight.takeIf { it > 1f } ?: return false
        selectionBankY += dy
        // One line change at a time: the next arrow must act on the position the previous one
        // produced, which is not known until the app reports it.
        if (selectionAwaitingLine) return false

        var moved = false
        // Round to the nearest line rather than waiting for a whole one, so the marker never
        // leads the selection by more than half a line. Strict, because at exactly half a line
        // a non-strict test would step back and forth forever.
        while (abs(selectionBankY) > lh / 2f) {
            val step = if (selectionBankY > 0) 1 else -1
            selectionBankY -= step * lh
            if (!moved) {
                selectionPreStepEnd = selectionMovingEnd
                // Put the span into its natural order so that the arrow key, which moves
                // SELECTION_END, moves the end we are dragging. The highlighted range is
                // unchanged by this -- only which end Android calls the start -- so unlike
                // collapsing the selection it produces no visible flicker.
                currentInputConnection?.setSelection(selectionAnchor, selectionMovingEnd)
                moved = true
            }
            sendArrow(
                if (step > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
            )
        }
        if (moved) {
            selectionAwaitingLine = true
            selectionWaitTicks = 0
        }
        return moved
    }

    /**
     * Bounds of the line containing [offset], as absolute text offsets. Falls back to the
     * offset itself when no snapshot is available, which simply disables clamping.
     */
    private fun lineBounds(offset: Int): IntRange? {
        val text = selectionText ?: return null
        val local = offset - selectionTextStart
        if (local < 0 || local > text.length) return null
        var start = 0
        for (i in local - 1 downTo 0) {
            if (text[i] == '\n') {
                start = i + 1
                break
            }
        }
        var end = text.length
        for (i in local until text.length) {
            if (text[i] == '\n') {
                end = i
                break
            }
        }
        return (start + selectionTextStart)..(end + selectionTextStart)
    }

    /**
     * Moves the dragged end of the selection toward the marker, closed-loop.
     *
     * The selection is deliberately stored *reversed* -- setSelection(movingEnd, anchor). The
     * highlight is identical either way, but the insertion marker follows the selection's start
     * span, so reversing it makes the app report the end being dragged instead of the fixed
     * one. Measured on device: setSelection(204, 211) reports x=409.6, the position of 204,
     * while setSelection(211, 204) reports x=548.6, the position of 211.
     *
     * That report is the feedback signal. Each round re-derives the error from it, so an
     * inaccurate character width costs one extra round instead of accumulating into drift.
     *
     * The target is clamped to the moving end's own line. Changing line is what vertical
     * movement is for; letting a horizontal correction wrap would send the loop chasing down
     * the document. Clamping rather than detecting the wrap and reverting matters: the revert
     * made the selection visibly jump back, and any vertical jitter released the block that
     * suppressed it, so dragging past the end of a line flickered rapidly between the two.
     */
    private fun steerSelection() {
        val ic = currentInputConnection ?: return
        val selStart = lastSelStart
        val selEnd = lastSelEnd
        val insH = caretX
        if (selStart < 0 || insH.isNaN()) return

        // Which span is the end we are dragging depends on the order the selection is stored
        // in: reversed while steering horizontally, natural while an arrow key changes line.
        val collapsed = selStart == selEnd
        val naturalOrder = !collapsed && selStart == selectionAnchor
        val reportedMovingEnd = if (naturalOrder) selEnd else selStart

        if (selectionAwaitingLine) {
            // setSelection and sendKeyEvent take different routes to the editor, so the span
            // swap is echoed back before the arrow has been applied. Acting on that echo would
            // undo the line change. Wait for a position that is actually different.
            if (reportedMovingEnd == selectionPreStepEnd && selectionWaitTicks < 4) {
                selectionWaitTicks++
                return
            }
            selectionAwaitingLine = false
        }
        selectionMovingEnd = reportedMovingEnd

        if (naturalOrder) {
            // Restore the reversed order so the reported insertion marker follows the dragged
            // end again. Same range, so invisible. insH currently describes the anchor, so
            // there is nothing useful to steer on until the next report.
            ic.setSelection(selectionMovingEnd, selectionAnchor)
            selectionAppliedChars = 0
            return
        }

        if (collapsed) {
            // A purely vertical drag produces no horizontal error, so the selection must be
            // applied here rather than waiting for a correction to need it.
            if (selectionMovingEnd != selectionAnchor) {
                ic.setSelection(selectionMovingEnd, selectionAnchor)
            }
        } else {
            // Learn the real character advance from what the last correction actually moved.
            if (selectionAppliedChars != 0 && !selectionPrevInsH.isNaN()) {
                val advance = abs(insH - selectionPrevInsH) / abs(selectionAppliedChars)
                if (advance > 1f && advance < 200f) charWidth = advance
            }
        }

        val error = markerX - insH
        val chars = (error / effectiveCharWidth()).roundToInt().coerceIn(-24, 24)
        if (chars == 0) {
            selectionAppliedChars = 0
            return
        }

        val bounds = lineBounds(selectionMovingEnd)
        val target = (selectionMovingEnd + chars).let {
            if (bounds != null) it.coerceIn(bounds.first, bounds.last) else it.coerceAtLeast(0)
        }
        if (target == selectionMovingEnd) {
            // Already at the edge of the line; the marker is free to carry on without us.
            selectionAppliedChars = 0
            return
        }
        selectionPrevInsH = insH
        selectionAppliedChars = target - selectionMovingEnd
        selectionMovingEnd = target
        ic.setSelection(selectionMovingEnd, selectionAnchor)
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        if (DEBUG_GESTURES) android.util.Log.d(
            TAG,
            "anchor sel=[${info.selectionStart},${info.selectionEnd}] " +
                "insH=${info.insertionMarkerHorizontal} insT=${info.insertionMarkerTop} " +
                "markerY=$markerCenterY",
        )
        val point = caretPoint(info) ?: return
        val previousX = caretX
        val previousTop = caretTop
        if (info.selectionStart == info.selectionEnd) caretOffset = info.selectionStart

        (point[3] - point[1]).takeIf { it > 1f }?.let { lineHeight = it }

        if (!previousX.isNaN()) {
            // Measure the caret's real advance per step, from purely horizontal moves only: a
            // change of line moves x arbitrarily and would poison the estimate.
            if (pendingHorizontal != 0 && pendingVertical == 0) {
                val advance = abs(point[0] - previousX) / abs(pendingHorizontal)
                if (advance > 1f && advance < 200f) charWidth = advance
            }
            // Whether a vertical push achieved anything must be judged by the caret's *offset*,
            // not its position on screen. When the view scrolls it deliberately holds the caret
            // still on screen, so screen position says "did not move" for the one case where it
            // moved the most -- which latched vertical movement off during every scroll.
            if (pendingVertical != 0 && pendingFromOffset >= 0) {
                val movedInText = info.selectionStart != pendingFromOffset
                verticalStuckDir = if (movedInText) 0 else if (pendingVertical > 0) 1 else -1
                // Moved in the text but not on screen: the editor is scrolling underneath a
                // pinned caret. Its reported position will not close the error, so vertical
                // movement has to be handed to the rate-limited scroll instead of chased.
                scrollPinned = movedInText && abs(point[1] - previousTop) < 1f
            }

            // Deliberately no attempt to move the marker with the scrolling text. Doing so
            // changes the very error that caused the scroll, and the two fight: measured on
            // device the view scrolled up and down by one line repeatedly, with the caret
            // offset unchanged. Leaving the marker fixed on screen gives the behaviour that is
            // actually wanted -- the caret moves within the visible text, and only pushes the
            // view when it reaches the edge.
        }
        pendingHorizontal = 0
        pendingVertical = 0
        pendingFromOffset = -1

        info.editorBoundsInfo?.editorBounds?.let { bounds ->
            val corners = floatArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
            info.matrix.mapPoints(corners)
            editorLeft = corners[0]
            editorRight = corners[2]
        }

        caretX = point[0]
        caretTop = point[1]
        caretBottom = point[3]

        // Seed the marker on the caret at the start of the drag; free thereafter.
        if (trackpadActive && markerX.isNaN()) {
            markerX = caretX
            markerCenterY = caretTop + lineHeight / 2f
        }

        lastSelStart = info.selectionStart
        lastSelEnd = info.selectionEnd

        updateIndicator()
        updateEdgeScroll()
        if (extendingSelection) steerSelection() else chaseCaret()
    }

    /**
     * +1 when the marker is held below the visible text, -1 above it, 0 within it.
     *
     * The visible text ends where the keyboard begins; anything below that is the user pushing
     * past the bottom of what they can see.
     */
    /**
     * +1 to keep scrolling down, -1 up, 0 to leave it to the ordinary chase.
     *
     * Only active once the editor has been seen scrolling under a pinned caret. Until then the
     * caret is moving normally within the visible text and the chase closes the error properly.
     */
    private fun edgeScrollDirection(): Int {
        if (!scrollPinned || !trackpadActive) return 0
        if (markerCenterY.isNaN() || caretTop.isNaN()) return 0
        val lh = lineHeight.takeIf { it > 1f } ?: return 0
        val lines = (markerCenterY - (caretTop + lh / 2f)) / lh
        return when {
            lines > 0.5f -> 1
            lines < -0.5f -> -1
            else -> 0
        }
    }

    /**
     * Steps the caret one line so the editor scrolls to keep it in view.
     *
     * Deliberately one line per tick rather than closing the whole error at once. Once the caret
     * is pushed past the edge the editor pins it there and scrolls the text instead, so its
     * reported position stops changing and the error never resolves -- an error-driven chase
     * therefore runs away, and measured on device it reached the end of the document in a single
     * short drag. A fixed rate turns that into a steady scroll.
     */
    private fun scrollOneLine(direction: Int) {
        if (extendingSelection) {
            extendSelection(direction * (lineHeight.takeIf { it > 1f } ?: 60f))
            return
        }
        if (pendingFromOffset < 0) pendingFromOffset = caretOffset
        sendArrow(
            if (direction > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP,
            0,
        )
        pendingVertical += direction
    }

    /** Milliseconds until the next line, from how far the marker is beyond the caret. */
    private fun edgeScrollInterval(): Long {
        val lh = lineHeight.takeIf { it > 1f } ?: return EDGE_SCROLL_SLOWEST_MS
        val past = abs(markerCenterY - (caretTop + lh / 2f))
        val ramp = (past / EDGE_SCROLL_FULL_SPEED_PX).coerceIn(0f, 1f)
        return (EDGE_SCROLL_SLOWEST_MS - (EDGE_SCROLL_SLOWEST_MS - EDGE_SCROLL_FASTEST_MS) * ramp)
            .toLong()
    }

    private fun updateEdgeScroll() {
        handler.removeCallbacks(edgeScrollTick)
        if (trackpadActive && edgeScrollDirection() != 0) handler.post(edgeScrollTick)
    }

    private fun chaseCaret() {
        if (!trackpadActive || caretX.isNaN() || markerX.isNaN()) return
        // Selections are steered by steerSelection, which stores them reversed so the reported
        // marker follows the dragged end rather than the fixed one.
        if (extendingSelection) return
        // Await the previous round's result -- but not forever. An arrow at the very top or
        // bottom of the text moves nothing, so no cursor update is ever delivered, and waiting
        // unconditionally wedges the chase permanently: the caret simply stops following the
        // marker from then on.
        if (pendingHorizontal != 0 || pendingVertical != 0) {
            if (chaseWaitTicks < 3) {
                chaseWaitTicks++
                return
            }
            // Timing out *is* the signal that the arrows achieved nothing: a move that changes
            // the caret always reports back. Nothing reported means the caret is against the
            // start or end of the text, which is the only way to learn it -- waiting for an
            // update that will never come would leave the marker free to keep travelling
            // beyond the text, and every pixel of that has to be un-travelled before the caret
            // responds again.
            if (pendingVertical != 0) {
                verticalStuckDir = if (pendingVertical > 0) 1 else -1
            }
            pendingHorizontal = 0
            pendingVertical = 0
        }
        chaseWaitTicks = 0
        val meta = 0

        val lh = lineHeight.takeIf { it > 1f } ?: return
        val lines = ((markerCenterY - (caretTop + lh / 2f)) / lh).roundToInt().coerceIn(-12, 12)
        // While parked past an edge the repeating scroll owns vertical movement; an
        // error-driven step here would race it and overshoot.
        val stuckThisWay = verticalStuckDir != 0 && (lines > 0) == (verticalStuckDir > 0)
        if (lines != 0 && !stuckThisWay && edgeScrollDirection() == 0) {
            val step = if (lines > 0) 1 else -1
            if (pendingFromOffset < 0) pendingFromOffset = caretOffset
            repeat(abs(lines)) {
                sendArrow(
                    if (step > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP,
                    meta,
                )
                pendingVertical += step
            }
            return
        }

        // Deadband of half a character stops the caret dithering around the marker.
        val advance = effectiveCharWidth()
        val chars = ((markerX - caretX) / advance).roundToInt().coerceIn(-24, 24)
        if (chars == 0) return
        val step = if (chars > 0) 1 else -1
        repeat(abs(chars)) {
            // Never cross a line break sideways: up and down is what changes line.
            if (atLineEdge(forward = step > 0)) return
            // Nor a soft wrap, which has no character to detect. A wrapped row by definition
            // reaches the editor's edge, so a caret within a character of it is at the end of
            // its row; stepping past would drop the caret to the far left of the next row and
            // flip the vertical and horizontal errors at once, which is what made the caret
            // thrash. This is deliberately stateless -- a latch released on the next vertical
            // move and crossed straight back over.
            if (atRowEdge(step > 0)) return
            sendArrow(
                if (step > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT,
                meta,
            )
            pendingHorizontal += step
        }
    }

    /**
     * Measured from the caret's real movement once the caret has moved horizontally at all;
     * until then, a third of the line height, which is about the average advance of lowercase
     * text in a proportional font. The measurement persists for the life of the service, so
     * this fallback only applies before the very first cursor move in a field.
     */
    /** True when the caret sits within a character of the editor's left or right edge. */
    private fun atRowEdge(forward: Boolean): Boolean {
        if (caretX.isNaN()) return false
        val margin = effectiveCharWidth() * 1.2f
        return if (forward) {
            !editorRight.isNaN() && caretX + margin >= editorRight
        } else {
            caretX - margin <= editorLeft
        }
    }

    private fun effectiveCharWidth(): Float =
        charWidth.takeIf { it > 0f } ?: (lineHeight.takeIf { it > 1f } ?: 40f) * 0.33f

    /**
     * Horizontal movement stops at the start and end of a line rather than wrapping onto the
     * neighbouring one: left/right is for moving within a line, up/down is for changing line.
     *
     * "Line" here means a hard break. A soft-wrapped line has no character to detect, so
     * movement still flows across a wrap -- which is the same position in the text, just drawn
     * on the next row.
     */
    private fun atLineEdge(forward: Boolean): Boolean {
        val ic = currentInputConnection ?: return false
        val neighbour = if (forward) {
            ic.getTextAfterCursor(1, 0)
        } else {
            ic.getTextBeforeCursor(1, 0)
        }
        return neighbour.isNullOrEmpty() || neighbour.toString() == "\n"
    }

    /** sendDownUpKeyEvents cannot carry a meta state, so build the events by hand. */
    private fun sendArrow(keyCode: Int, meta: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /** Caret as [x, top, x, bottom] in screen coordinates, or null if the app reports none. */
    private fun caretPoint(info: CursorAnchorInfo): FloatArray? {
        val h = info.insertionMarkerHorizontal
        val t = info.insertionMarkerTop
        val b = info.insertionMarkerBottom
        if (h.isNaN() || t.isNaN() || b.isNaN()) return null
        val pts = floatArrayOf(h, t, h, b)
        info.matrix.mapPoints(pts)
        return pts
    }

    /** Draws the marker wherever the finger has put it. */
    private fun updateIndicator() {
        if (!trackpadActive) return
        val kv = keyboardView ?: return
        if (markerX.isNaN() || markerCenterY.isNaN()) return

        val lh = lineHeight.takeIf { it > 1f } ?: (20f * resources.displayMetrics.density)
        val view = indicator ?: CursorIndicatorView(this).also { indicator = it }
        view.lineHeightPx = lh.roundToInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        // CursorAnchorInfo's matrix yields screen coordinates, but showAtLocation places the
        // popup relative to the parent's *window* origin -- which for an IME is the top of the
        // keyboard, roughly 1400px down. Convert between the two explicitly.
        val onScreen = IntArray(2)
        val inWindow = IntArray(2)
        kv.getLocationOnScreen(onScreen)
        kv.getLocationInWindow(inWindow)

        val x = (markerX - view.measuredWidth / 2f).roundToInt() - (onScreen[0] - inWindow[0])
        val y = (markerCenterY - view.measuredHeight / 2f).roundToInt() - (onScreen[1] - inWindow[1])

        val popup = indicatorPopup ?: PopupWindow(view).apply {
            isTouchable = false
            isFocusable = false
            isClippingEnabled = false
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            setBackgroundDrawable(null)
            indicatorPopup = this
        }
        runCatching {
            if (popup.isShowing) {
                popup.update(x, y, -1, -1)
            } else {
                popup.showAtLocation(kv, Gravity.NO_GRAVITY, x, y)
            }
        }.onFailure {
            if (DEBUG_GESTURES) android.util.Log.d(TAG, "indicator failed: $it")
        }
    }

    /**
     * Begins a selection at the current caret position.
     *
     * No shift key is held. Selection is applied directly with setSelection, which is what lets
     * it be stored reversed so the app reports the dragged end -- see [steerSelection].
     */
    private fun beginSelection() {
        if (extendingSelection) return
        // CursorAnchorInfo is asynchronous and may not have arrived yet, so fall back to
        // asking the editor directly rather than refusing to start.
        val start = caretOffset.takeIf { it >= 0 }
            ?: currentInputConnection
                ?.getExtractedText(ExtractedTextRequest(), 0)
                ?.let { it.startOffset + it.selectionStart }
            ?: return
        if (start < 0) return
        extendingSelection = true
        selectionAnchor = start
        selectionMovingEnd = start
        selectionPrevMovingEnd = start
        selectionLineTop = caretTop
        selectionBankY = 0f
        selectionAppliedChars = 0
        selectionPrevInsH = Float.NaN
        // Hold a real shift key for the duration. Arrow keys only extend a selection when the
        // text buffer's meta state is set, and only a genuine KEYCODE_SHIFT_LEFT press does
        // that -- META_SHIFT_ON on the arrow event alone is ignored.
        currentInputConnection?.let { ic ->
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0),
            )
        }
        currentInputConnection
            ?.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 shl 16 }, 0)
            ?.let {
                selectionText = it.text
                selectionTextStart = it.startOffset.coerceAtLeast(0)
            }
        selectionAwaitingLine = false
    }

    /** Leaves the selection in place, normalised to the conventional order. */
    private fun endSelection() {
        if (!extendingSelection) return
        extendingSelection = false
        currentInputConnection?.let { ic ->
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(
                KeyEvent(
                    now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0,
                    KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
                ),
            )
        }
        if (selectionAnchor >= 0 && selectionMovingEnd >= 0) {
            currentInputConnection?.setSelection(
                minOf(selectionAnchor, selectionMovingEnd),
                maxOf(selectionAnchor, selectionMovingEnd),
            )
        }
        selectionAnchor = -1
        selectionMovingEnd = -1
    }
}
