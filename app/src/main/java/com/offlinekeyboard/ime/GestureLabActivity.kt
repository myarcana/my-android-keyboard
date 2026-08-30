package com.offlinekeyboard.ime

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
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
import com.offlinekeyboard.ime.capture.GestureBank
import com.offlinekeyboard.ime.capture.GestureCapture
import com.offlinekeyboard.ime.capture.Passage
import com.offlinekeyboard.ime.capture.Passages
import com.offlinekeyboard.ime.capture.Target
import com.offlinekeyboard.ime.gesture.GestureIntent
import com.offlinekeyboard.ime.gesture.GestureRecord
import java.util.concurrent.Executors

/**
 * The Gesture Lab: a typing test that keeps the paper.
 *
 * It shows a passage, you type it, and every gesture is filed under the token it was aimed at.
 * The label is the whole point -- a recording of a swipe is worth very little on its own, because
 * the question it answers, did that person mean the symbol behind the key or the first letter of
 * a word, lives in their head and nowhere in the touch data.
 *
 * Asking one gesture at a time was the obvious way to get that label and it was subtly the wrong
 * one. An instruction has to be read, the key has to be found, and the named movement has to be
 * performed -- three deliberate acts that produce a slow, careful, aimed gesture. Real typing is
 * none of those things. A passage gets the same label for free, because the passage says what
 * comes next before it is typed, while leaving the thumb to do what it normally does: read ahead,
 * move without being told where, and arrive sloppily at speed.
 *
 * The bank it fills is read back by GestureBankReplayTest, which replays every sample through the
 * real state machine and sweeps the thresholds against it.
 */
class GestureLabActivity : Activity() {

    private var passages: List<Passage> = emptyList()
    private var passageIndex = 0
    private var targetIndex = 0

    private var sessionRecorded = 0
    private var sessionAgreed = 0
    private var sessionDecided = 0
    private var wordsTyped = 0
    private var wordsRight = 0

    private lateinit var title: TextView
    private lateinit var note: TextView
    private lateinit var passageView: TextView
    private lateinit var passageScroller: ScrollView
    private lateinit var progress: TextView
    private lateinit var feedback: TextView
    private lateinit var bankLine: TextView
    private lateinit var imeWarning: TextView
    private lateinit var field: EditText

    /** Where each target's text sits in [passageView], so the current one can be highlighted. */
    private var spans: List<IntRange> = emptyList()
    /** What the keyboard made of each target already typed: null while untyped. */
    private val outcomes = mutableMapOf<Int, Boolean>()

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
    /** Not yet typed: present enough to read ahead in, quiet enough not to compete. */
    private val ahead get() = if (night) Color.parseColor("#565669") else Color.parseColor("#A7A7B8")
    private val symbolHue = Color.parseColor("#2F6FED")
    private val wordHue = Color.parseColor("#D97706")
    private val letterHue = Color.parseColor("#6B4FBB")
    private val goodHue = Color.parseColor("#0F8A4F")
    private val badHue = Color.parseColor("#C62828")

    private fun hueFor(intent: GestureIntent) = when (intent) {
        GestureIntent.SYMBOL -> symbolHue
        GestureIntent.WORD -> wordHue
        GestureIntent.LETTER -> letterHue
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        passages = Passages.all()
        startPassage(0)
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
        // emitting gestures, and with no target armed they are dropped on the floor.
        GestureCapture.disarm()
        GestureCapture.onResult = null
    }

    // --- the passage -------------------------------------------------------------------------

    private fun startPassage(index: Int) {
        passageIndex = ((index % passages.size) + passages.size) % passages.size
        targetIndex = 0
        outcomes.clear()
        field.setText("")
        val passage = passages[passageIndex]
        title.text = passage.title
        note.text = passage.note
        feedback.setTextColor(muted)
        feedback.text = "Type it straight through. Every gesture is filed under the token it " +
            "was aimed at -- the ones read wrongly are the most useful of all."
        renderPassage()
        armCurrent()
    }

    private val passage: Passage get() = passages[passageIndex]
    private val current: Target? get() = passage.targets.getOrNull(targetIndex)

    /**
     * Draws the passage with the caret on the current token.
     *
     * Rebuilt whole on every advance rather than patched. It is a hundred short spans on a
     * screen that changes once per gesture, so the cost is invisible, and the alternative --
     * tracking which spans need removing as the caret moves over them -- is the kind of
     * bookkeeping that goes wrong quietly and leaves the passage lying about what was typed.
     */
    private fun renderPassage() {
        val builder = SpannableStringBuilder()
        val ranges = mutableListOf<IntRange>()
        passage.targets.forEachIndexed { i, target ->
            if (i > 0) builder.append(' ')
            val from = builder.length
            builder.append(target.display)
            ranges += from until builder.length
        }
        spans = ranges

        ranges.forEachIndexed { i, range ->
            val start = range.first
            val end = range.last + 1
            val target = passage.targets[i]
            when {
                i == targetIndex -> {
                    builder.setSpan(
                        BackgroundColorSpan(hueFor(target.intent)),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    builder.setSpan(
                        ForegroundColorSpan(Color.WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    builder.setSpan(
                        StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                // Typed already: green or red says what the keyboard made of it, which is the
                // only score that matters here. Whether the *letters* arrived is not the
                // question -- a glide that typed the wrong word still recorded a real glide.
                outcomes.containsKey(i) -> builder.setSpan(
                    ForegroundColorSpan(if (outcomes[i] == true) goodHue else badHue),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                else -> builder.setSpan(
                    ForegroundColorSpan(ahead), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        passageView.text = builder

        val done = targetIndex.coerceAtMost(passage.targets.size)
        progress.text = buildString {
            append("$done of ${passage.targets.size}")
            current?.let { target ->
                val why = Passages.why(target.startKeyId)
                if (why.isNotEmpty()) append("   ·   $why")
            }
        }
        scrollToCurrent()
    }

    /**
     * Keeps the token being typed on screen.
     *
     * Posted rather than done inline because the layout that decides which line a token is on
     * has not happened yet at the moment the text is set, and asking a TextView for a line
     * number before it has been measured returns an answer about the previous passage.
     */
    private fun scrollToCurrent() {
        val range = spans.getOrNull(targetIndex) ?: return
        passageView.post {
            val layout = passageView.layout ?: return@post
            val line = layout.getLineForOffset(range.first)
            val y = layout.getLineTop(line) - passageScroller.height / 3
            passageScroller.smoothScrollTo(0, y.coerceAtLeast(0))
        }
    }

    private fun armCurrent() {
        current?.let { GestureCapture.arm(it) } ?: GestureCapture.disarm()
    }

    private fun onCaptureResult(result: GestureCapture.Result) {
        when (result.outcome) {
            GestureCapture.Outcome.WRONG_KEY -> {
                feedback.setTextColor(muted)
                feedback.text = "That started on another key -- not recorded. " +
                    "'${result.target.display}' begins on ${result.target.startKeyId.uppercase()}."
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
                outcomes[targetIndex] = agreed
                if (record.intent == GestureIntent.WORD) {
                    wordsTyped++
                    if (sameWord(record.decoded, record.expected)) wordsRight++
                }
                feedback.setTextColor(if (agreed) goodHue else badHue)
                feedback.text = describe(record, agreed)
                targetIndex++
                if (targetIndex >= passage.targets.size) finishPassage() else renderPassage()
                armCurrent()
                refreshBankLine()
            }
        }
    }

    /** Case and apostrophes are the keyboard's business, not the decoder's. */
    private fun sameWord(a: String?, b: String): Boolean =
        a != null && a.lowercase().filter(Char::isLetter) == b.lowercase().filter(Char::isLetter)

    private fun describe(record: GestureRecord, agreed: Boolean): String = buildString {
        append(if (agreed) "correct" else "WRONG")
        append("  ·  read as ")
        append(record.trace.verdict.name)
        if (record.intent == GestureIntent.WORD) {
            append("  ·  typed ")
            append(record.decoded ?: "nothing")
        }
        append("  ·  ")
        append("%.0f".format(record.pathLength))
        append("px in ")
        append(record.durationMs)
        append("ms")
        // The lifts are the point of the glide passages, so they are always said out loud --
        // a leniency that fired is invisible otherwise, and one that fired wrongly doubly so.
        record.gaps.forEach { gap ->
            append("  ·  resumed after ${gap.ms}ms, ${"%.0f".format(gap.px)}px away")
        }
    }

    private fun finishPassage() {
        renderPassage()
        GestureCapture.disarm()
        progress.text = "done   ·   $sessionRecorded gestures this session"
        feedback.setTextColor(goodHue)
        feedback.text = "Passage complete. Take another, or pull the bank with " +
            "tools/gestures.sh pull."
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
                        append("\nthis session $sessionRecorded")
                        if (sessionDecided > 0) {
                            append(" · $sessionAgreed/$sessionDecided read correctly")
                        }
                        if (wordsTyped > 0) {
                            append(" · $wordsRight/$wordsTyped words decoded correctly")
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
                    if (targetIndex > 0) targetIndex--
                    outcomes.remove(targetIndex)
                    sessionRecorded = (sessionRecorded - 1).coerceAtLeast(0)
                    toast("Removed one ${removed.intent.name.lowercase()} sample")
                    renderPassage()
                    armCurrent()
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
        title = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        }
        note = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(muted)
        }
        passageView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTextColor(ahead)
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        progress = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(muted)
        }
        feedback = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ink)
            hint = "type here"
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(10).toFloat()
            }
        }

        // Only the passage scrolls, and it is given a fixed share of the screen. The keyboard
        // takes half of what is left, so anything sharing one scrolling column with it gets
        // pushed off -- and the buttons and the field being reachable at all times is the
        // difference between a usable rig and a frustrating one.
        passageScroller = ScrollView(this).apply {
            isFillViewport = true
            addView(
                passageView,
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

        val cardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(14).toFloat()
            }
            addView(title)
            addView(note, marginTop(dp(2)))
            addView(passageScroller, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(10) })
            addView(progress, marginTop(dp(8)))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(flatButton("Passage") { startPassage(passageIndex + 1) })
            addView(flatButton("Restart") { startPassage(passageIndex) })
            addView(flatButton("Skip") { skip() })
            addView(flatButton("Undo") { undoLast() })
            addView(flatButton("Export") { export() })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(imeWarning)
            addView(cardBox, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(8) })
            addView(feedback, marginTop(dp(10)))
            addView(bankLine, marginTop(dp(6)))
            addView(buttons, marginTop(dp(6)))
            addView(field, marginTop(dp(6)))
        }

        // The keyboard is the instrument here, so the window must resize around it rather than
        // let it cover the passage the person is reading.
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

    private fun skip() {
        if (targetIndex >= passage.targets.size) return
        targetIndex++
        if (targetIndex >= passage.targets.size) finishPassage() else renderPassage()
        armCurrent()
    }

    private fun refreshImeWarning() {
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ours = selected?.startsWith(packageName) == true
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ink)
            isAllCaps = false
            setPadding(dp(2), 0, dp(2), 0)
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(4) }
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
