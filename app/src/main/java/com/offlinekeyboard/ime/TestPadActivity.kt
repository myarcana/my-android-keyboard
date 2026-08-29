package com.offlinekeyboard.ime

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout

/**
 * A scratch text field for exercising the keyboard during development.
 *
 * Multi-line and pre-filled on purpose: the spacebar trackpad has to move the cursor up and
 * down across real wrapped lines, which needs more than a single empty row to test against.
 */
class TestPadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP or Gravity.START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.TRANSPARENT)
            setText(
                buildString {
                    appendLine("Type here.")
                    appendLine()
                    appendLine("Flick down on a key for its symbol.")
                    appendLine("Keep swiping and it becomes a glide.")
                    appendLine("Hold space to move the cursor in 2D.")
                },
            )
            setSelection(text.length)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(field)
            },
        )
        field.requestFocus()
    }
}
