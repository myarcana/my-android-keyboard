package com.offlinekeyboard.ime

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
                """
                Cursor test pad.

                a
                bb
                ccc
                dd
                e

                Short lines above, so the ends of a line are easy to reach: left and right
                should stop dead at each end instead of hopping onto the line above or below.

                Here is a deliberately long line that will soft-wrap across several rows of the
                display even though it is a single line of text with no line break in it, which
                is the case where horizontal movement should still flow freely from one visual
                row to the next because nothing has actually ended.

                x
                yy

                iiiiiiiiii
                WWWWWWWWWW

                Two lines above are the same length in characters but very different in width,
                which is what the granular marker uses to work out how far through a character
                the finger has travelled.

                one
                two
                three
                four
                five
                six
                seven
                eight
                nine
                ten

                Ten short lines above give vertical movement something to travel through: hold
                space and slide up and down to cross them.

                End of the pad.
                """.trimIndent(),
            )
            setSelection(0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(field)
        }
        setContentView(root)

        // targetSdk 35+ lays activities out edge to edge, so without this the first lines of
        // text run underneath the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        field.requestFocus()
        // Start at the top: the short-line section is the interesting part, and focusing the
        // field otherwise leaves the view wherever it was last scrolled.
        field.post { field.setSelection(0) }
    }
}
