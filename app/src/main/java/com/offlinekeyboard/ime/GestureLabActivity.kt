package com.offlinekeyboard.ime

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.offlinekeyboard.ime.capture.Drill
import com.offlinekeyboard.ime.capture.Drills
import com.offlinekeyboard.ime.capture.GestureBank
import com.offlinekeyboard.ime.capture.GestureCapture
import com.offlinekeyboard.ime.gesture.GestureIntent
import java.util.concurrent.Executors

/**
 * The gesture lab: it asks for one gesture at a time, watches you make it, and files it away
 * under what it asked for.
 *
 * The point is the *label*. A recording of a swipe is worth very little on its own, because the
 * whole question -- did that person mean the symbol behind the key, or the first letter of a
 * word -- lives in their head and nowhere in the touch data. Asking first, and recording second,
 * is the only way to get an answer that is not a guess. Everything else here exists to keep the
 * labels honest: the drills alternate so the hand cannot fall into a rhythm, gestures that start
 * on the wrong key are thrown away rather than mislabelled, and the running score shows what the
 * *current* heuristic would have done, so it is obvious when the boundary is being found.
 *
 * The bank it fills is read back by GestureBankReplayTest, which replays every sample through
 * the real state machine and sweeps the thresholds against it.
 */
class GestureLabActivity : Activity() {

    private var reps = 4
    private var queue: List<Drill> = emptyList()
    private var index = 0

    private var sessionRecorded = 0
    private var sessionAgreed = 0
    private var sessionDecided = 0

    private lateinit var chip: TextView
    private lateinit var instruction: TextView
    private lateinit var why: TextView
    private lateinit var progress: TextView
    private lateinit var feedback: TextView
    private lateinit var bankLine: TextView
    private lateinit var imeWarning: TextView
    private lateinit var field: EditText
    private lateinit var repsButton: Button

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gesture-lab-io").apply { isDaemon = true }
    }

    // --- palette ---------------------------------------------------------------------------

    private val night: Boolean
        get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private val bg get() = if (night) Color.parseColor("#12131A") else Color.parseColor("#F6F6FB")
    private val card get() = if (night) Color.parseColor("#1E1F28") else Color.WHITE
    private val ink get() = if (night) Color.parseColor("#ECECF2") else Color.parseColor("#181B25")
    private val muted get() = if (night) Color.parseColor("#8E8EA0") else Color.parseColor("#6B6B7B")
    private val symbolHue = Color.parseColor("#2F6FED")
    private val wordHue = Color.parseColor("#D97706")
    private val letterHue = Color.parseColor("#6B4FBB")
    private val goodHue = Color.parseColor("#0F8A4F")
    private val badHue = Color.parseColor("#C62828")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        startSession()
    }

    override fun onResume() {
        super.onResume()
        GestureCapture.onResult = ::onCaptureResult
        armCurrent()
        refreshBankLine()
        refreshImeWarning()
        field.requestFocus()
        showKeyboard()
    }

    override fun onPause() {
        super.onPause()
        // Disarming here is what keeps ordinary typing out of the bank: the keyboard goes on
        // emitting gestures, and with no drill armed they are dropped on the floor.
        GestureCapture.disarm()
        GestureCapture.onResult = null
    }

    // --- session ---------------------------------------------------------------------------

    private fun startSession() {
        queue = Drills.session(reps)
        index = 0
        sessionRecorded = 0
        sessionAgreed = 0
        sessionDecided = 0
        feedback.text = "Word glides type nothing yet -- it is the path that is recorded, " +
            "and the ones read wrongly are the most useful of all."
        feedback.setTextColor(muted)
        showCurrent()
    }

    private val current: Drill? get() = queue.getOrNull(index)

    private fun showCurrent() {
        val drill = current
        if (drill == null) {
            chip.text = "DONE"
            chip.background = pill(goodHue)
            instruction.text = "Session complete"
            why.text = "$sessionRecorded gestures recorded. Start another, or pull the bank " +
                "with tools/gestures.sh pull."
            progress.text = ""
            GestureCapture.disarm()
            return
        }
        val hue = when (drill.intent) {
            GestureIntent.SYMBOL -> symbolHue
            GestureIntent.WORD -> wordHue
            GestureIntent.LETTER -> letterHue
        }
        chip.text = drill.intent.name
        chip.background = pill(hue)
        instruction.text = drill.instruction
        instruction.setTextColor(hue)
        why.text = drill.why
        progress.text = "${index + 1} of ${queue.size}   ·   reps $reps"
        field.setText("")
        armCurrent()
    }

    private fun armCurrent() {
        current?.let { GestureCapture.arm(it) } ?: GestureCapture.disarm()
    }

    private fun onCaptureResult(result: GestureCapture.Result) {
        when (result.outcome) {
            GestureCapture.Outcome.WRONG_KEY -> {
                feedback.setTextColor(muted)
                feedback.text = "Started on another key -- not recorded. " +
                    "Begin on ${result.drill.startKeyId.uppercase()}."
            }
            GestureCapture.Outcome.TOO_SMALL -> {
                feedback.setTextColor(muted)
                feedback.text = "Barely moved -- not recorded."
            }
            GestureCapture.Outcome.RECORDED -> {
                val record = result.record ?: return
                sessionRecorded++
                val read = record.verdictIntent
                if (read != null) {
                    sessionDecided++
                    if (read == record.intent) sessionAgreed++
                }
                val agreed = read == record.intent
                feedback.setTextColor(if (agreed) goodHue else badHue)
                feedback.text = buildString {
                    append(if (agreed) "correct" else "WRONG")
                    append("  ·  keyboard read it as ")
                    append(record.trace.verdict.name)
                    append("  ·  ")
                    append("%.0f".format(record.pathLength))
                    append("px in ")
                    append(record.durationMs)
                    append("ms")
                }
                index++
                showCurrent()
                refreshBankLine()
            }
        }
    }

    // --- bank ------------------------------------------------------------------------------

    private fun refreshBankLine() {
        val context = applicationContext
        io.execute {
            val summary = GestureBank.summarise(GestureBank.readAll(context))
            runOnUiThread {
                bankLine.text = buildString {
                    append("bank ${summary.total}")
                    append("  (${summary.breakdown})")
                    if (summary.decided > 0) {
                        append("   ·   current heuristic ")
                        append("%.0f%%".format(summary.accuracy * 100))
                        append(" of ${summary.decided}")
                    }
                    if (sessionRecorded > 0) {
                        append("\nthis session ${sessionRecorded}")
                        if (sessionDecided > 0) {
                            append(" · $sessionAgreed/$sessionDecided read correctly")
                        }
                    }
                }
            }
        }
    }

    private fun undoLast() {
        val context = applicationContext
        io.execute {
            val removed = GestureBank.removeLast(context)
            runOnUiThread {
                if (removed == null) {
                    toast("Nothing to undo")
                } else {
                    if (index > 0) index--
                    sessionRecorded = (sessionRecorded - 1).coerceAtLeast(0)
                    toast("Removed one ${removed.intent.name.lowercase()} sample")
                    showCurrent()
                    refreshBankLine()
                }
            }
        }
    }

    private fun export() {
        val context = applicationContext
        io.execute {
            val file = GestureBank.export(context)
            runOnUiThread {
                toast(if (file == null) "Bank is empty" else "Exported to ${file.absolutePath}")
            }
        }
    }

    // --- ui ---------------------------------------------------------------------------------

    private fun buildUi(): View {
        chip = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            letterSpacing = 0.12f
        }
        instruction = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        }
        why = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(muted)
        }
        progress = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(muted)
        }
        feedback = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(muted)
        }
        bankLine = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(muted)
        }
        imeWarning = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.WHITE)
            setBackgroundColor(badHue)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            setOnClickListener {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            }
        }
        field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(ink)
            hint = "gesture here"
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(10).toFloat()
            }
        }
        repsButton = flatButton("Reps $reps") {
            reps = when (reps) {
                2 -> 4
                4 -> 8
                else -> 2
            }
            repsButton.text = "Reps $reps"
            startSession()
        }

        val prompt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(14).toFloat()
            }
            addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(instruction, marginTop(dp(10)))
            addView(why, marginTop(dp(6)))
            addView(progress, marginTop(dp(10)))
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(flatButton("Skip") { index++; showCurrent() })
            addView(flatButton("Undo") { undoLast() })
            addView(flatButton("Export") { export() })
            addView(repsButton)
        }

        // Only the prompt scrolls. The keyboard takes half the screen, so anything below it in
        // a single scrolling column gets pushed off -- and the buttons and the field being
        // reachable at all times is the difference between a usable rig and a frustrating one.
        val scroller = ScrollView(this).apply {
            isFillViewport = true
            addView(
                LinearLayout(this@GestureLabActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(imeWarning)
                    addView(prompt, marginTop(dp(12)))
                    addView(feedback, marginTop(dp(12)))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(scroller)
            addView(bankLine, marginTop(dp(8)))
            addView(buttons, marginTop(dp(8)))
            addView(field, marginTop(dp(8)))
        }

        // The keyboard is the instrument here, so the window must resize around it rather than
        // let it cover the prompt the person is reading.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                dp(14) + bars.left,
                dp(10) + bars.top,
                dp(14) + bars.right,
                dp(10) + maxOf(bars.bottom, ime.bottom),
            )
            insets
        }
        return root
    }

    private fun refreshImeWarning() {
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ours = current?.startsWith(packageName) == true
        imeWarning.visibility = if (ours) View.GONE else View.VISIBLE
        if (!ours) {
            imeWarning.text = "Offline Keyboard is not the current keyboard, so nothing will " +
                "be recorded. Tap to switch."
        }
    }

    private fun showKeyboard() {
        field.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun flatButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ink)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) }
        }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20).toFloat()
    }

    private fun marginTop(px: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = px }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
