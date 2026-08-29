package com.offlinekeyboard.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
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
    /** The caret cannot go further vertically -- the end of the text -- so stop trying. */
    private var verticalStuck = false

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
    /** Set when the moving end has run out of line; cleared when the finger comes back. */
    private var selectionBlocked = false
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
        val ok = currentInputConnection?.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_MONITOR,
        )
        if (DEBUG_GESTURES) android.util.Log.d(TAG, "requestCursorUpdates -> $ok")
        markerX = Float.NaN
        markerCenterY = Float.NaN
        caretX = Float.NaN
        caretTop = Float.NaN
        pendingHorizontal = 0
        pendingVertical = 0
        verticalStuck = false
        updateIndicator()
    }

    private fun stopTrackpad() {
        trackpadActive = false
        markerX = Float.NaN
        markerCenterY = Float.NaN
        currentInputConnection?.requestCursorUpdates(0)
        indicatorPopup?.takeIf { it.isShowing }?.let { runCatching { it.dismiss() } }
    }

    /** The finger moves the marker, freely, in screen space. Nothing constrains it to the text. */
    private fun panMarker(dx: Float, dy: Float) {
        if (!trackpadActive || markerX.isNaN()) return
        val metrics = resources.displayMetrics
        markerX = (markerX + dx).coerceIn(0f, metrics.widthPixels.toFloat())
        markerCenterY = (markerCenterY + dy).coerceIn(0f, metrics.heightPixels.toFloat())
        verticalStuck = false
        updateIndicator()
        if (extendingSelection) {
            // Steer on the pan as well as on cursor updates: CURSOR_UPDATE_MONITOR only fires
            // when the cursor actually moves, so waiting for one would deadlock -- no movement,
            // no update, no movement.
            extendSelection(dy)
            steerSelection()
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
    private fun extendSelection(dy: Float) {
        val lh = lineHeight.takeIf { it > 1f } ?: return
        selectionBankY += dy
        var moved = false
        while (abs(selectionBankY) >= lh) {
            val step = if (selectionBankY > 0) 1 else -1
            selectionBankY -= step * lh
            if (!moved) {
                currentInputConnection?.setSelection(selectionMovingEnd, selectionMovingEnd)
                moved = true
            }
            sendArrow(
                if (step > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP,
                0,
            )
        }
        if (moved) {
            selectionLineTop = Float.NaN // the line is about to change; re-learn it
            selectionBlocked = false
        }
    }

    /**
     * Moves the dragged end of the selection toward the marker, closed-loop.
     *
     * The selection is deliberately stored *reversed* -- setSelection(movingEnd, anchor). The
     * highlight is identical either way, but the insertion marker follows the selection's
     * start span, so reversing it makes the app report the end being dragged instead of the
     * fixed one. Measured on device: setSelection(204, 211) reports x=409.6, the position of
     * 204, while setSelection(211, 204) reports x=548.6, the position of 211.
     *
     * That report is the feedback signal. Each round re-derives the error from it, so an
     * inaccurate character width costs one extra round instead of accumulating into the drift
     * that made long selections progressively wrong.
     */
    private fun steerSelection() {
        val ic = currentInputConnection ?: return
        val selStart = lastSelStart
        val selEnd = lastSelEnd
        val insH = caretX
        val insT = caretTop
        if (selStart < 0 || insH.isNaN()) return

        if (selStart == selEnd) {
            // Collapsed: either the drag has not moved yet, or we collapsed deliberately to
            // change line. Adopt the position and carry on steering -- returning here would
            // mean the selection could never grow in the first place.
            selectionMovingEnd = selStart
            selectionPrevMovingEnd = selStart
            selectionLineTop = insT
        } else {
            selectionMovingEnd = selStart // reversed, so the start span is the dragged end

            // The line changed without us asking: the moving end wrapped past the end of its
            // line. Put it back and stop pushing until the finger comes back.
            if (!selectionLineTop.isNaN() && abs(insT - selectionLineTop) > 1f &&
                selectionPrevMovingEnd >= 0
            ) {
                selectionBlocked = true
                selectionMovingEnd = selectionPrevMovingEnd
                ic.setSelection(selectionMovingEnd, selectionAnchor)
                return
            }
            if (selectionLineTop.isNaN()) selectionLineTop = insT

            // Learn the real character advance from what the last correction actually moved.
            if (selectionAppliedChars != 0 && !selectionPrevInsH.isNaN()) {
                val advance = abs(insH - selectionPrevInsH) / abs(selectionAppliedChars)
                if (advance > 1f && advance < 200f) charWidth = advance
            }
        }

        val error = markerX - insH
        val chars = (error / effectiveCharWidth()).roundToInt().coerceIn(-16, 16)
        if (DEBUG_GESTURES) android.util.Log.d(
            TAG,
            "steer sel=[$selStart,$selEnd] insH=$insH markerX=$markerX err=$error " +
                "cw=${effectiveCharWidth()} chars=$chars blocked=$selectionBlocked " +
                "anchor=$selectionAnchor moving=$selectionMovingEnd",
        )
        if (selectionBlocked) {
            // Only resume once the finger has come back past the stuck position.
            if (chars >= 0) {
                selectionAppliedChars = 0
                return
            }
            selectionBlocked = false
        }
        if (chars == 0) {
            selectionAppliedChars = 0
            return
        }
        selectionPrevMovingEnd = selectionMovingEnd
        selectionPrevInsH = insH
        selectionAppliedChars = chars
        selectionMovingEnd = (selectionMovingEnd + chars).coerceAtLeast(0)
        ic.setSelection(selectionMovingEnd, selectionAnchor)
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        if (DEBUG_GESTURES) android.util.Log.d(
            TAG,
            "anchor sel=[${info.selectionStart},${info.selectionEnd}] insH=${info.insertionMarkerHorizontal}",
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
            // If a vertical push produced no movement we are at the end of the text. Stop
            // pushing, so the horizontal chase can still run while the marker sits beyond it.
            if (pendingVertical != 0 && abs(point[1] - previousTop) < 1f) verticalStuck = true
        }
        pendingHorizontal = 0
        pendingVertical = 0

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
        if (extendingSelection) steerSelection() else chaseCaret()
    }

    private fun chaseCaret() {
        if (!trackpadActive || caretX.isNaN() || markerX.isNaN()) return
        // Selections are steered by steerSelection, which stores them reversed so the reported
        // marker follows the dragged end rather than the fixed one.
        if (extendingSelection) return
        if (pendingHorizontal != 0 || pendingVertical != 0) return // await the last round's result
        val meta = 0

        val lh = lineHeight.takeIf { it > 1f } ?: return
        val lines = ((markerCenterY - (caretTop + lh / 2f)) / lh).roundToInt().coerceIn(-12, 12)
        if (lines != 0 && !verticalStuck) {
            val step = if (lines > 0) 1 else -1
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
        selectionBlocked = false
    }

    /** Leaves the selection in place, normalised to the conventional order. */
    private fun endSelection() {
        if (!extendingSelection) return
        extendingSelection = false
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
