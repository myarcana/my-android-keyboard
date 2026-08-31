package com.offlinekeyboard.testpad

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Scratch text fields for exercising the keyboard during development.
 *
 * Two of them, because they are not interchangeable and one of them silently was not testing
 * what it looked like it was testing.
 *
 * The **cursor pad** is multi-line and pre-filled on purpose: the spacebar trackpad has to move
 * the cursor up and down across real wrapped lines, which needs more than a single empty row to
 * test against. It declares NO_SUGGESTIONS so that nothing between the keys and the field can
 * alter the text a cursor test is measuring.
 *
 * The **word field** is there because that flag also turns off tap decoding, which reads a run of
 * letter taps as a word. Typing into the cursor pad to check it therefore proved nothing, twice:
 * the feature was correctly disabled and looked broken. A field that does not opt out is the only
 * place on this phone where its behaviour can be seen at all.
 *
 * Its own app, because it shares nothing with the keyboard but a developer -- no code, no
 * process, no files.
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

        // Single-line and suggestion-friendly: an ordinary field, of the kind the keyboard meets
        // everywhere outside this app. Deliberately not pre-filled -- what is being watched here
        // is a word appearing letter by letter.
        val words = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "word field -- tap decoding runs here"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // The only field here a password manager will offer to fill. An autofill service reads
        // the view structure, not the keyboard, so it ignores the two fields above however they
        // are typed into: without an autofill hint there is nothing for it to recognise, and the
        // inline suggestion strip stays empty no matter how well the keyboard supports it.
        //
        // A username rather than a password on purpose. ColorOS binds its own secure keyboard to
        // any field whose inputType carries a password variation, so a password field here would
        // not be served by this keyboard at all and would prove nothing about its strip.
        val login = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setAutofillHints(View.AUTOFILL_HINT_USERNAME)
            hint = "username field -- password manager chips appear here"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(words)
            addView(login)
            addView(field)
        }
        setContentView(root)

        // targetSdk 35+ lays activities out edge to edge, so without this the first lines of
        // text run underneath the status bar.
        //
        // The keyboard inset matters just as much: without it the field extends *behind* the
        // keyboard, so the editor considers a caret down there perfectly visible and never
        // scrolls. Anything testing scroll behaviour against this pad would be testing nothing.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        // The cursor pad keeps the focus, so every existing way of using this app is unchanged.
        // Reaching the word field is one tap.
        field.requestFocus()
        // Start at the top: the short-line section is the interesting part, and focusing the
        // field otherwise leaves the view wherever it was last scrolled.
        field.post { field.setSelection(0) }
    }
}
