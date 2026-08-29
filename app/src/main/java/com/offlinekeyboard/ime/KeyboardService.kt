package com.offlinekeyboard.ime

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
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

/** Logs every gesture output to logcat; the only way to observe multi-touch, which adb cannot drive. */
private const val DEBUG_GESTURES = true

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
        currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
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
        chaseCaret()
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val point = caretPoint(info) ?: return
        val previousX = caretX
        val previousTop = caretTop

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

        updateIndicator()
        chaseCaret()
    }

    /**
     * Moves the caret toward wherever the marker is now.
     *
     * This is a feedback loop, not dead reckoning: every round re-derives the error from the
     * position the *app* reports for its own caret, so a wrong character-width estimate costs
     * an extra round rather than accumulating. That is what stops the two drifting apart.
     *
     * Vertical is resolved first and then the round ends, because changing line moves the caret
     * horizontally too; the next update handles the new horizontal error.
     */
    private fun chaseCaret() {
        if (!trackpadActive || caretX.isNaN() || markerX.isNaN()) return
        if (pendingHorizontal != 0 || pendingVertical != 0) return // await the last round's result
        val meta = if (extendingSelection) {
            KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        } else {
            0
        }

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

    private fun effectiveCharWidth(): Float =
        charWidth.takeIf { it > 0f } ?: (lineHeight.takeIf { it > 1f } ?: 40f) * 0.45f

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
     * Press and hold a real shift key for as long as the selection gesture lasts.
     *
     * Setting META_SHIFT_ON on the arrow events is not enough on its own. TextView decides
     * whether an arrow extends a selection in ArrowKeyMovementMethod.isSelecting(), which reads
     * the *text buffer's* meta state via MetaKeyKeyListener -- and that is only ever set by
     * genuine KEYCODE_SHIFT_LEFT key events passing through. A synthesised metaState on the
     * arrow itself is ignored, so the caret just moved and nothing was ever selected.
     */
    private fun beginSelection() {
        if (extendingSelection) return
        extendingSelection = true
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0))
    }

    /** Always paired with [beginSelection], including when the gesture is cancelled. */
    private fun endSelection() {
        if (!extendingSelection) return
        extendingSelection = false
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(
            KeyEvent(
                now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0,
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
            ),
        )
    }
}
