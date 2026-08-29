package com.offlinekeyboard.ime

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.inputmethodservice.InputMethodService
import android.view.MotionEvent
import android.view.View

/**
 * Phase 0 scaffold.
 *
 * This exists to prove the IME plumbing end to end: the service can be enabled in system
 * settings, its view is shown when a text field is focused, and it can commit text through the
 * InputConnection. Phase 1 replaces [PlaceholderKeyboardView] with the real iOS-geometry key
 * layout and the touch state machine, which is where the actual work of this project lives.
 */
class KeyboardService : InputMethodService() {

    override fun onCreateInputView(): View = PlaceholderKeyboardView(this)

    /** Temporary: any tap commits a character, which is enough to verify the InputConnection. */
    private inner class PlaceholderKeyboardView(context: android.content.Context) : View(context) {

        private val background = Paint().apply { color = Color.parseColor("#D1D4DA") }
        private val label = Paint().apply {
            color = Color.parseColor("#3C3C43")
            textSize = 44f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            // Roughly the height of the iOS keyboard; Phase 1 derives this from the layout data.
            setMeasuredDimension(
                MeasureSpec.getSize(widthSpec),
                (resources.displayMetrics.density * 260).toInt(),
            )
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
            canvas.drawText("Offline Keyboard", width / 2f, height / 2f - 20f, label)
            canvas.drawText("Phase 0 — tap to test input", width / 2f, height / 2f + 40f, label)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                currentInputConnection?.commitText(".", 1)
                performClick()
                return true
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean = super.performClick()
    }
}
