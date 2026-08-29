package com.offlinekeyboard.ime

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
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
class KeyboardService : InputMethodService() {

    private var keyboardView: KeyboardView? = null

    private enum class ShiftState { OFF, ONE_SHOT, LOCKED }

    private var shift = ShiftState.OFF
    private var lastShiftTapAt = 0L

    override fun onCreateInputView(): View =
        KeyboardView(this).also { view ->
            keyboardView = view
            view.onOutput = ::handleOutputs
            applyLayout()
        }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
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
        outputs.forEach { out ->
            when (out) {
                is GestureOutput.CommitPrimary -> commit(out.text)
                is GestureOutput.CommitSecondary -> commit(out.text)
                is GestureOutput.CommitAccent -> commit(out.text)
                is GestureOutput.CursorMove -> moveCursor(out.dx, out.dy)
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
     */
    private fun moveCursor(dx: Int, dy: Int) {
        repeat(kotlin.math.abs(dx)) {
            sendDownUpKeyEvents(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        }
        repeat(kotlin.math.abs(dy)) {
            sendDownUpKeyEvents(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        }
    }
}
