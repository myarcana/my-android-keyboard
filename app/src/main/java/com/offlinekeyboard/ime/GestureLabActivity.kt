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
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.Window
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
import android.content.Intent
import android.net.Uri
import com.offlinekeyboard.ime.capture.GestureBank
import com.offlinekeyboard.ime.capture.GestureCapture
import com.offlinekeyboard.ime.capture.LabDeck
import com.offlinekeyboard.ime.capture.LabProgress
import com.offlinekeyboard.ime.capture.Passage
import com.offlinekeyboard.ime.capture.PassageRun
import com.offlinekeyboard.ime.capture.Passages
import com.offlinekeyboard.ime.capture.Target
import com.offlinekeyboard.ime.gesture.GestureIntent
import com.offlinekeyboard.ime.gesture.GestureRecord
import com.offlinekeyboard.ime.glide.FutoSwipe
import com.offlinekeyboard.ime.glide.GlideEngine
import com.offlinekeyboard.ime.glide.GlideScoreboard
import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import com.offlinekeyboard.ime.glide.Lexicon
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
 *
 * Everything about it that is not the passage exists so that it can be used away from the
 * machine that reads the bank: it remembers where it got to, so four minutes in a queue
 * continues the corpus instead of retyping the first passage of it, and it deals its own
 * passages out of a shuffled deck that takes weeks to come round. What it does not do is take
 * the bank out of the sandbox -- the file is fsynced per line where it is written, and comes
 * off over adb when there is a machine to pull it.
 */
class GestureLabActivity : Activity() {

    private lateinit var deck: LabDeck
    private lateinit var passage: Passage
    /** Set when finishing a passage already moved the deck on, so Next does not skip one. */
    private var advanced = false

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
        // No title bar: it names an activity that is already the only thing on screen, and the
        // line it costs is a line of passage.
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildUi())
        deck = LabDeck(LabDeck.prefs(this), library())
        start(deck.current())
    }

    /**
     * The passages the deck deals from: the corpus first, then the five hand-written ones.
     *
     * A corpus that fails to load leaves the curated passages, which is a worse lab but a
     * working one -- and the alternative, a lab that will not open, loses a session over a
     * missing asset.
     */
    private fun library(): List<Passage> {
        val corpus = runCatching {
            assets.open(Passages.CORPUS_ASSET).use { it.readBytes().decodeToString() }
        }.getOrNull()
        return (corpus?.let { Passages.corpus(Passages.corpusLines(it)) } ?: emptyList()) +
            Passages.prose()
    }

    override fun onResume() {
        super.onResume()
        GestureCapture.onRecorded = ::onRecorded
        armCurrent()
        refreshBankLine()
        refreshImeWarning()
        field.requestFocus()
        showKeyboard()
    }

    override fun onPause() {
        super.onPause()
        // Disarming here is what keeps ordinary typing out of the bank: the keyboard goes on
        // emitting gestures, and with no run in progress they are dropped on the floor. The
        // session line is written on the way out, so a run abandoned by leaving the app -- which
        // is the ordinary way one ends -- still says what it produced.
        GestureCapture.end(applicationContext)
        GestureCapture.onRecorded = null
    }

    // --- the passage -------------------------------------------------------------------------

    private fun start(next: Passage) {
        advanced = false
        replay(next)
    }

    /**
     * Puts a passage back to its beginning without touching the deck's position.
     *
     * The split matters for exactly one sequence and it is a common one: finish a passage, which
     * moves the deck on, then press Again. Resetting the position there would leave the deck one
     * short, and pressing Next afterwards would step over the passage that had been waiting.
     */
    private fun replay(next: Passage) {
        passage = next
        field.setText("")
        title.text = passage.title
        note.text = passage.note
        feedback.setTextColor(muted)
        feedback.text = "Type it straight through -- the ones read wrongly are the most useful."
        renderPassage()
        armCurrent()
    }

    /** The next passage in the deck, unless finishing this one already moved it on. */
    private fun nextPassage() {
        if (!advanced) deck.advance()
        start(deck.current())
    }

    /**
     * Draws the passage against what has actually been typed.
     *
     * Per character rather than per token, which is the change of model made visible. The lab no
     * longer has an opinion about which token you are "on" -- it has a transcript, and the honest
     * thing to show is how far that transcript still agrees with the passage. Green is agreement,
     * red is what was typed after it stopped agreeing, and the caret sits at the end of what
     * exists. Nothing here waits for anything, and nothing is refused.
     *
     * Rebuilt whole on every gesture rather than patched. It is a few hundred characters on a
     * screen that changes once per gesture, so the cost is invisible, and the alternative --
     * tracking which spans need removing as the caret moves -- is the kind of bookkeeping that
     * goes wrong quietly and leaves the passage lying about what was typed.
     */
    private fun renderPassage() {
        val run = GestureCapture.current
        val text = run?.intended ?: PassageRun.textOf(passage)
        val correct = run?.correctPrefix ?: 0
        val typed = run?.position ?: 0
        val builder = SpannableStringBuilder(text)
        spans = (run ?: PassageRun(passage.id, text, passage.targets)).tokenRanges()

        fun paint(from: Int, to: Int, colour: Int, background: Boolean = false) {
            if (to <= from) return
            val a = from.coerceIn(0, text.length)
            val b = to.coerceIn(0, text.length)
            if (b <= a) return
            builder.setSpan(ForegroundColorSpan(colour), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (background) {
                builder.setSpan(
                    BackgroundColorSpan(colour), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                builder.setSpan(
                    ForegroundColorSpan(Color.WHITE), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                builder.setSpan(StyleSpan(Typeface.BOLD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        paint(0, correct, goodHue)
        // Typed, but no longer what the passage says. Not an error to be corrected before the lab
        // will go on -- it goes on regardless -- just the point the two stopped matching.
        paint(correct, typed, badHue, background = true)
        paint(typed, text.length, ahead)
        // The next character to type, marked so the eye can find it without counting.
        paint(typed, (typed + 1).coerceAtMost(text.length), hueFor(GestureIntent.LETTER), background = true)
        passageView.text = builder

        progress.text = buildString {
            append("$typed of ${text.length} characters")
            if (typed > correct) append("   ·   ${typed - correct} adrift")
            append("   ·   passage ${deck.position}/${deck.total}")
            append("   ·   ${run?.count ?: 0} gestures")
        }
        scrollToCurrent()
    }

    /**
     * Keeps the place being typed on screen.
     *
     * Posted rather than done inline because the layout that decides which line an offset is on
     * has not happened yet at the moment the text is set, and asking a TextView for a line
     * number before it has been measured returns an answer about the previous passage.
     */
    private fun scrollToCurrent() {
        val at = GestureCapture.current?.position ?: 0
        passageView.post {
            val layout = passageView.layout ?: return@post
            val line = layout.getLineForOffset(at.coerceIn(0, passageView.text.length))
            val y = layout.getLineTop(line) - passageScroller.height / 3
            passageScroller.smoothScrollTo(0, y.coerceAtLeast(0))
        }
    }

    /**
     * Starts recording this passage.
     *
     * There is nothing to "arm" any more. The lab does not decide whether a gesture counts, so
     * there is no target to hold it against -- it records the passage, and every gesture made
     * while that passage is on screen goes into the bank with what it typed.
     */
    private fun armCurrent() {
        GestureCapture.begin(applicationContext, passage)
    }

    /**
     * A gesture has been recorded. Every gesture is; there is no other case.
     *
     * What is shown is what it *did* -- the verdict the heuristic reached, and the text that went
     * into the field because of it. Deliberately not a verdict on whether it was right: that
     * depends on what gets typed next, and the lab guessing at it in the moment is the habit that
     * cost the bank its mistakes.
     */
    private fun onRecorded(record: GestureRecord, run: PassageRun) {
        sessionRecorded++
        LabProgress.record(LabDeck.prefs(this))
        record.verdictIntent?.let {
            sessionDecided++
            if (it == record.intent) sessionAgreed++
        }
        if (record.intent == GestureIntent.WORD && record.decoded != null) {
            wordsTyped++
            if (sameWord(record.decoded, record.expected)) wordsRight++
        }
        val onTrack = run.correctPrefix == run.position
        feedback.setTextColor(if (onTrack) goodHue else badHue)
        feedback.text = describe(record)
        renderPassage()
        if (run.isComplete) finishPassage()
        refreshBankLine()
    }

    /** Case and apostrophes are the keyboard's business, not the decoder's. */
    private fun sameWord(a: String?, b: String): Boolean =
        a != null && a.lowercase().filter(Char::isLetter) == b.lowercase().filter(Char::isLetter)

    private fun describe(record: GestureRecord): String = buildString {
        append("read as ")
        append(record.trace.verdict.name)
        if (record.typed.isNotEmpty()) append("  ·  typed \"${record.typed}\"")
        if (record.deleted > 0) append("  ·  deleted ${record.deleted}")
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

    /**
     * The deck moves on here rather than when Next is pressed.
     *
     * A passage typed to the end and then abandoned -- the bus arrives, the screen goes off --
     * is the ordinary way a session ends, and if the position only advanced on a button press
     * the next launch would open on the passage that was just finished. Doing it here means the
     * only passage ever repeated is one that was genuinely left half typed.
     */
    private fun finishPassage() {
        renderPassage()
        GestureCapture.disarm()
        if (!advanced) {
            deck.advance()
            advanced = true
        }
        progress.text = "done   ·   $sessionRecorded gestures this session"
        feedback.setTextColor(goodHue)
        val snapshot = LabProgress.snapshot(LabDeck.prefs(this))
        feedback.text = if (snapshot.today >= LabProgress.DAILY_GOAL) {
            "Passage complete -- ${snapshot.today} today, past the goal. Next for another."
        } else {
            "Passage complete -- ${LabProgress.DAILY_GOAL - snapshot.today} more for today's goal."
        }
    }

    // --- bank ------------------------------------------------------------------------------

    private fun refreshBankLine() {
        val context = applicationContext
        io.execute {
            val summary = GestureBank.summarise(GestureBank.readAll(context))
            runOnUiThread {
                val day = LabProgress.snapshot(LabDeck.prefs(this@GestureLabActivity))
                bankLine.text = buildString {
                    append("today ${day.today}/${LabProgress.DAILY_GOAL}")
                    if (day.streak > 0) append("   ·   ${day.streak} day streak")
                    append("   ·   bank ${summary.total}")
                    append("  (${summary.breakdown})")
                    if (summary.decided > 0) {
                        append("   ·   current heuristic ")
                        append("%.0f%%".format(summary.accuracy * 100))
                        append(" of ${summary.decided}")
                    }
                    if (sessionRecorded > 0) {
                        append("   ·   session $sessionRecorded")
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
                    // The bank line goes; the text does not. Undo here means "that gesture was
                    // not worth recording", which is a different act from correcting what it
                    // typed -- backspace does that, and is itself a gesture worth recording.
                    sessionRecorded = (sessionRecorded - 1).coerceAtLeast(0)
                    toast("Removed one ${removed.intent.name.lowercase()} sample")
                    renderPassage()
                    armCurrent()
                    refreshBankLine()
                }
            }
        }
    }

    /**
     * Runs every recorded glide through both decoders and says which read this thumb better.
     *
     * It has to happen here rather than in a unit test, and that is not a compromise: the engine
     * is a native library that exists only on Android, so scoring it anywhere else would be
     * scoring it by proxy.
     *
     * With one engine this is a measurement rather than a comparison, and it is still the thing
     * worth having. A decoder reading this thumb at 95% and one reading it at 40% look identical
     * from the outside until something asks -- and the most likely cause of the second is not the
     * model at all, it is the coordinate frame it was handed.
     */
    private fun scoreEngines() {
        val context = applicationContext
        toast("Scoring the bank…")
        io.execute {
            val lexicon = runCatching { assets.open(LEXICON_ASSET).use(Lexicon::load) }.getOrNull()
            if (lexicon == null) {
                runOnUiThread { toast("No lexicon to score against") }
                return@execute
            }
            val futo = FutoSwipe.open(context, lexicon)
            val engines = listOfNotNull<GlideEngine>(futo)
            val records = GestureBank.readAll(context)
            val report = GlideScoreboard.score(records, engines)
            futo?.close()

            val text = buildString {
                if (futo == null) {
                    append("No glide engine in this build. Run tools/fetch_swipe_runtime.sh ")
                    append("and reinstall.")
                } else if (report.rows.all { it.scored == 0 }) {
                    append("No glides recorded yet. Type one of the prose passages first.")
                } else {
                    report.rows.forEach { row ->
                        append(row.engine)
                        append(":  first choice ${row.percent(row.top1)}%")
                        append("   offered ${row.percent(row.offered)}%")
                        append("   (${row.top1}/${row.scored})")
                        if (row.rejoined > 0) {
                            append("\n   after a finger lift: ")
                            append("${row.rejoinedTop1}/${row.rejoined}")
                        }
                        append("\n\n")
                    }

                    val worst = report.misses.groupBy { it.engine }
                    worst.forEach { (engine, misses) ->
                        append("$engine missed: ")
                        append(misses.take(8).joinToString(", ") { "${it.expected}->${it.got}" })
                        append("\n")
                    }
                }
            }
            runOnUiThread {
                android.app.AlertDialog.Builder(this@GestureLabActivity)
                    .setTitle("Glide decoders on ${report.rows.firstOrNull()?.scored ?: 0} glides")
                    .setMessage(text)
                    .setPositiveButton("ok", null)
                    .show()
            }
        }
    }

    /**
     * Copies the bank to the app's external files directory, where `adb pull` reaches it without
     * run-as. For a release build or a ROM where run-as is blocked; the internal file is the one
     * that matters.
     */
    private fun export() {
        val context = applicationContext
        io.execute {
            val file = GestureBank.export(context)
            runOnUiThread {
                toast(if (file == null) "Bank is empty" else "Exported to ${file.absolutePath}")
            }
        }
    }

    /**
     * Reads another bank file in and merges it, keyed on record id.
     *
     * Internal storage does not survive an uninstall, so the archive that does is the one in
     * the repository. This is how it gets home again without run-as or a shell: hand the file
     * back through the system picker, which needs no permission and no network.
     */
    private fun importBank() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQUEST_IMPORT) }
            .onFailure { toast("No file picker on this phone") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val context = applicationContext
        io.execute {
            val added = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    GestureBank.merge(context, reader.lineSequence())
                }
            }.getOrNull()
            runOnUiThread {
                when (added) {
                    null -> toast("Could not read that file")
                    0 -> toast("Nothing new in it -- every record was already in the bank")
                    else -> toast("Merged $added gestures")
                }
                refreshBankLine()
            }
        }
    }

    /**
     * The things that are not typing, behind one button.
     *
     * Six buttons across a phone are already two too many, and each of these is used once a
     * session at most -- while Next, Again, Skip, Undo and Void are used with a thumb that is in
     * the middle of typing.
     */
    private fun showMenu() {
        val items = arrayOf(
            "Export a copy for adb",
            "Import a bank file",
            "Score the glide decoders",
        )
        android.app.AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> export()
                    1 -> importBank()
                    else -> scoreEngines()
                }
            }
            .show()
    }

    /**
     * Withdraws the label on the gesture just recorded, keeping the recording.
     *
     * Undo deletes; this does not. The difference matters for the one case it exists for: a
     * flick begun, abandoned and turned back from is the only evidence in the bank of what
     * abandoning a flick looks like, and it is worthless under the label the passage gave it.
     */
    private fun voidLast() {
        val context = applicationContext
        io.execute {
            val record = GestureBank.voidLast(context, "fumbled: the label is not what the hand did")
            runOnUiThread {
                if (record == null) {
                    toast("Nothing to withdraw")
                } else {
                    // The recording stays: only the claim about that one gesture is withdrawn.
                    toast("Label withdrawn -- the path is still in the bank")
                    renderPassage()
                    refreshBankLine()
                }
            }
        }
    }

    // --- ui ---------------------------------------------------------------------------------

    private fun buildUi(): View {
        // Every line of chrome here is a line of passage the reader does not get, so the
        // header, the progress line, the feedback and the bank are all held to one line each
        // and truncated rather than allowed to wrap. Reading ahead is the whole task.
        title = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        note = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        passageView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(ahead)
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        progress = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // Capped, because it is the one line here whose length is not under this file's control:
        // a long word plus a decoded word plus a lift report can run to three lines, and every
        // one of them comes out of the passage's height, which is the thing being read.
        feedback = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        // The counts are ordered most useful first, because only the front of this line
        // survives on a narrow screen.
        bankLine = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
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
            maxLines = 1
            setPadding(dp(14), dp(7), dp(14), dp(7))
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

        // Title and note ride on one line together: they name the passage, and naming it is
        // worth a line only once.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(note, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { marginStart = dp(8) })
        }

        val cardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(14).toFloat()
            }
            addView(header)
            addView(passageScroller, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(6) })
            addView(progress, marginTop(dp(4)))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(flatButton("Next") { nextPassage() })
            addView(flatButton("Again") { replay(passage) })
            addView(flatButton("Skip") { skip() })
            addView(flatButton("Undo") { undoLast() })
            addView(flatButton("Void") { voidLast() })
            addView(flatButton("More") { showMenu() })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(imeWarning)
            addView(cardBox, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(4) })
            addView(feedback, marginTop(dp(5)))
            addView(bankLine, marginTop(dp(2)))
            addView(buttons, marginTop(dp(4)))
            addView(field, marginTop(dp(4)))
        }

        // The keyboard is the instrument here, so the window must resize around it rather than
        // let it cover the passage the person is reading.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                dp(10) + bars.left,
                dp(6) + bars.top,
                dp(10) + bars.right,
                dp(6) + maxOf(bars.bottom, ime.bottom),
            )
            insets
        }
        return root
    }

    /**
     * Gives up on this passage and takes the next.
     *
     * It used to skip one *token*, which was a thing to do only because a token could refuse to
     * be typed. Nothing refuses now -- a gesture the lab cannot make sense of is recorded anyway
     * and sorted out later -- so the only reason left to press this is that the passage itself is
     * not worth finishing.
     */
    private fun skip() {
        finishPassage()
        nextPassage()
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
            minHeight = 0
            minimumHeight = 0
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
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

    private companion object {
        const val REQUEST_IMPORT = 1
    }
}
