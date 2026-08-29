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

    // --- granular cursor indicator ---
    private var indicator: CursorIndicatorView? = null
    private var indicatorPopup: PopupWindow? = null
    private var trackpadActive = false

    /** Total unclamped finger travel for this drag, in step units. */
    private var offsetX = 0f
    private var offsetY = 0f

    /**
     * Steps the caret actually took. The difference from the finger's travel is the overshoot:
     * zero-ish while the caret keeps up, growing without limit once it is stuck at a line end.
     */
    private var appliedX = 0
    private var appliedY = 0

    /** Steps sent but not yet confirmed by a CursorAnchorInfo update. */
    private var pendingX = 0
    private var pendingY = 0

    /** Caret position last reported by the app, in screen coordinates. */
    private var caretX = Float.NaN
    private var caretTop = Float.NaN
    private var caretBottom = 0f

    /** Caret position including steps sent but not yet reported, so the marker never lags. */
    private var predictedX = Float.NaN
    private var predictedTop = Float.NaN

    /** Measured from how far the caret actually moves per step in this field. */
    private var charWidth = 0f
    private var lineHeight = 0f

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
                is GestureOutput.CursorMove -> moveCursor(out.dx, out.dy, out.extend)
                GestureOutput.SelectionStarted -> beginSelection()
                GestureOutput.TrackpadStarted -> startTrackpad()
                is GestureOutput.CursorProgress -> {
                    offsetX = out.offsetX
                    offsetY = out.offsetY
                    updateIndicator()
                }
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
    private fun moveCursor(dx: Int, dy: Int, extend: Boolean) {
        val meta = if (extend) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0

        val hStep = if (dx > 0) 1 else -1
        repeat(abs(dx)) {
            // A step refused at a line edge is not "applied", so it becomes overshoot and the
            // granular cursor keeps travelling while the caret stays put.
            if (!atLineEdge(forward = dx > 0)) {
                sendArrow(
                    if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT,
                    meta,
                )
                appliedX += hStep
                pendingX += hStep
                if (!predictedX.isNaN()) predictedX += hStep * effectiveCharWidth()
            }
        }

        val vStep = if (dy > 0) 1 else -1
        repeat(abs(dy)) {
            sendArrow(
                if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP,
                meta,
            )
            appliedY += vStep
            pendingY += vStep
            if (!predictedTop.isNaN() && lineHeight > 1f) predictedTop += vStep * lineHeight
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
        // Empty means the very start or end of the field: nothing to move onto either.
        return neighbour.isNullOrEmpty() || neighbour.toString() == "\n"
    }

    /** sendDownUpKeyEvents cannot carry a meta state, so build the events by hand. */
    private fun sendArrow(keyCode: Int, meta: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
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

    // --- granular cursor indicator -------------------------------------------------------

    /**
     * Requirement: show where the *granular* cursor is while the trackpad is in use.
     *
     * The caret can only sit between characters, but the finger is somewhere continuous in
     * between. An IME cannot draw inside the target app's text field, so the indicator lives in
     * a PopupWindow, positioned in screen coordinates from the caret location the app reports
     * through CursorAnchorInfo.
     */
    private fun startTrackpad() {
        trackpadActive = true
        currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
        offsetX = 0f
        offsetY = 0f
        appliedX = 0
        appliedY = 0
        pendingX = 0
        pendingY = 0
        caretX = Float.NaN
        caretTop = Float.NaN
        predictedX = Float.NaN
        predictedTop = Float.NaN
        updateIndicator()
    }

    private fun stopTrackpad() {
        trackpadActive = false
        predictedX = Float.NaN
        predictedTop = Float.NaN
        currentInputConnection?.requestCursorUpdates(0)
        indicatorPopup?.takeIf { it.isShowing }?.let { runCatching { it.dismiss() } }
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val point = caretPoint(info) ?: return
        val hadCaret = !caretX.isNaN()

        (point[3] - point[1]).takeIf { it > 1f }?.let { lineHeight = it }

        if (hadCaret) {
            // Measure the real advance per step. Only trust a purely horizontal move: a change
            // of line moves x arbitrarily, which would poison the estimate.
            if (pendingX != 0 && pendingY == 0) {
                val advance = abs(point[0] - caretX) / abs(pendingX)
                if (advance > 1f && advance < 200f) charWidth = advance
            }
            // A vertical step the caret could not take -- the ends of the text -- is not
            // applied, so it becomes overshoot and the granular cursor carries on.
            if (pendingY != 0 && lineHeight > 1f) {
                val moved = ((point[1] - caretTop) / lineHeight).roundToInt()
                appliedY -= (pendingY - moved)
            }
        }
        pendingX = 0
        pendingY = 0

        caretX = point[0]
        caretTop = point[1]
        caretBottom = point[3]
        predictedX = caretX
        predictedTop = caretTop
        updateIndicator()
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

    /**
     * Places the granular cursor at the caret plus however far the finger has travelled beyond
     * what the caret could absorb.
     *
     * Anchoring to the caret rather than to a fixed origin is what keeps the two in step: the
     * marker advances by the caret's *measured* advance, so the error cannot accumulate over a
     * long drag. The overshoot term is what still lets it roam -- once the caret is stuck at
     * the end of a short line, or at the ends of the text, nothing is applied and the marker
     * carries on freely in both axes.
     *
     * Shown lazily, because CursorAnchorInfo arrives asynchronously and is not available at the
     * instant the trackpad starts.
     */
    private fun updateIndicator() {
        if (!trackpadActive) return
        val kv = keyboardView ?: return
        if (predictedX.isNaN() || predictedTop.isNaN()) return

        val lh = lineHeight.takeIf { it > 1f } ?: (20f * resources.displayMetrics.density)
        val cw = effectiveCharWidth()

        val overshootX = offsetX - appliedX
        val overshootY = offsetY - appliedY

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
        val originX = onScreen[0] - inWindow[0]
        val originY = onScreen[1] - inWindow[1]

        // Clamped only to the display, so it stays visible -- not to the text or the line.
        val metrics = resources.displayMetrics
        val screenX = (predictedX + overshootX * cw - view.measuredWidth / 2f)
            .coerceIn(0f, (metrics.widthPixels - view.measuredWidth).toFloat())
        val screenY = (predictedTop + overshootY * lh)
            .coerceIn(0f, (metrics.heightPixels - view.measuredHeight).toFloat())

        val x = screenX.roundToInt() - originX
        val y = screenY.roundToInt() - originY

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
