package com.offlinekeyboard.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.PopupWindow
import com.offlinekeyboard.ime.view.CursorIndicatorView
import kotlin.math.abs
import kotlin.math.roundToInt
import android.view.inputmethod.InputMethodManager
import com.offlinekeyboard.ime.asr.Dictation
import com.offlinekeyboard.ime.asr.MicrophonePermissionActivity
import com.offlinekeyboard.ime.asr.SpokenPunctuation
import com.offlinekeyboard.ime.candidates.EmojiIndex
import com.offlinekeyboard.ime.candidates.TypedWord
import com.offlinekeyboard.ime.capture.GestureCapture
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

/**
 * Auto-scroll rate while the marker is parked past an edge of the visible text, in milliseconds
 * per line. Proportional to how far past the edge the marker is: just over the line creeps, a
 * long way past moves quickly, which is how dragging a selection to the edge of a window behaves
 * everywhere else.
 */
private const val EDGE_SCROLL_SLOWEST_MS = 260L
private const val EDGE_SCROLL_FASTEST_MS = 45L
/** Distance past the edge, in pixels, at which the fastest rate is reached. */
private const val EDGE_SCROLL_FULL_SPEED_PX = 420f

/**
 * Held backspace. It deletes characters at first, then whole words -- the same acceleration
 * iOS has, and the reason it exists is that a fixed character rate is either too slow to clear
 * a sentence or too fast to stop on the word you meant.
 */
private const val BACKSPACE_CHAR_INTERVAL_MS = 55L
private const val BACKSPACE_WORD_INTERVAL_MS = 140L
/** Repeats at the character rate before words take over: about a second of holding. */
private const val BACKSPACE_REPEATS_BEFORE_WORDS = 18

/**
 * Text read backwards in one go when clearing a line. A line longer than this is cleared by
 * repeating, so the number only trades IPC calls against the rare very long line.
 */
private const val BULK_DELETE_CHUNK = 2048
/** Bounds the clearing loop, so a misbehaving editor cannot spin it forever. */
private const val BULK_DELETE_MAX_CHUNKS = 64

private const val EMOJI_ASSET = "emoji_en.tsv"

/**
 * Debug tooling: logs every gesture output, and registers a broadcast receiver that stands in
 * for the second finger of the selection gesture, which adb cannot send. Tied to the build type
 * so the receiver -- which is necessarily exported -- never exists in a release build.
 */
private val DEBUG_GESTURES = BuildConfig.DEBUG

class KeyboardService : InputMethodService() {

    private var keyboardView: KeyboardView? = null

    // --- suggestion bar ---
    /**
     * Requirement 9: the bar offers emoji, never English words. Loaded off the main thread
     * because it is 1900 entries read from an asset and the keyboard must appear instantly.
     */
    private var emoji: EmojiIndex? = null
    private var emojiLoading = false
    /** How many characters a tapped suggestion replaces: the word that produced it. */
    private var candidateReplaceLength = 0

    /** Repeats while backspace is held; counts its own repeats to know when to switch to words. */
    private var backspaceRepeats = 0

    // --- dictation ---
    private var dictation: Dictation? = null

    /**
     * Set while dictating so a segment can be committed without a space in front of it if the
     * field is empty or already ends in one. SenseVoice returns a bare clause per pause, and
     * pasting them end to end would run the sentence together.
     */
    private var dictatedAnything = false

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
    /** How long we have waited for that reflection, so an impossible move cannot wedge us. */
    private var chaseWaitTicks = 0
    /** Caret offset when the outstanding vertical arrows were sent, to tell moved from stuck. */
    private var pendingFromOffset = -1

    /**
     * True once a vertical step moved the caret in the text but not on screen, which is the
     * signature of the editor scrolling to keep a pinned caret in view.
     */
    private var scrollPinned = false

    private val handler = Handler(Looper.getMainLooper())
    /** Repeats a single line step while the marker is held past an edge of the visible text. */
    private val edgeScrollTick = object : Runnable {
        override fun run() {
            val dir = edgeScrollDirection()
            if (!trackpadActive || dir == 0) return
            scrollOneLine(dir)
            handler.postDelayed(this, edgeScrollInterval())
        }
    }
    /**
     * Which way the caret has run out of text: +1 cannot go further down, -1 cannot go further
     * up, 0 free. Directional and sticky, so the marker can be stopped from travelling further
     * that way -- distance it accumulates beyond the end of the text has to be un-travelled
     * before anything responds again, which reads as the cursor freezing and then snapping.
     */
    private var verticalStuckDir = 0

    /** Horizontal extent of the editor on screen, for spotting the end of a wrapped row. */
    private var editorLeft = 0f
    private var editorRight = Float.NaN

    /**
     * Where rows actually wrap, learned by watching one wrap happen.
     *
     * editorBoundsInfo is not published by every editor -- the test pad reports none at all --
     * and even when it is, rows wrap at a word boundary well short of the editor's edge. Traced
     * on device the caret wrapped at x=931 while the editor was 1080 wide, so an edge-based
     * guess never fires and the caret walks off the row every time.
     */
    private var rowRightEdge = Float.NaN
    /** The row it was learned on. Rows wrap at word boundaries, so it is valid for that row only. */
    private var rowRightEdgeTop = Float.NaN

    /**
     * While extending a selection, the finger's travel is banked here until it amounts to a
     * whole character or line. See [extendSelection] for why this is not the closed loop that
     * plain cursor movement uses.
     */
    private var selectionBankY = 0f

    /** Caret offset in the text, tracked from CursorAnchorInfo while the caret is collapsed. */
    private var caretOffset = -1

    /** The selection's fixed end, and the end being dragged. */
    private var selectionAnchor = -1
    private var selectionMovingEnd = -1
    private var selectionPrevMovingEnd = -1
    /** Top of the line the moving end is on, to detect it wrapping onto another line. */
    private var selectionLineTop = Float.NaN
    private var selectionAppliedChars = 0
    private var selectionPrevInsH = Float.NaN
    /**
     * A snapshot of the field's text, taken when the drag begins, used to find line boundaries
     * so the moving end can be clamped to its own line. No editing happens during a drag, so it
     * cannot go stale.
     */
    private var selectionText: CharSequence? = null
    private var selectionTextStart = 0
    /** A line change is in flight; wait for the app to report it before steering again. */
    private var selectionAwaitingLine = false
    /** Position collapsed onto for a line change, to tell the arrow's result from its echo. */
    private var selectionPreStepEnd = -1
    private var selectionWaitTicks = 0
    /** Last reported selection spans, so steering can run on a pan as well as on an update. */
    private var lastSelStart = -1
    private var lastSelEnd = -1

    /** Debug only: stands in for the second finger, which adb cannot send. */
    private val debugSelectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d(TAG, "debug broadcast: ${intent?.action}")
            when (intent?.action) {
                "com.offlinekeyboard.ime.DEBUG_REVSEL" -> {
                    // Does a reversed selection make the app report the *moving* end?
                    val a = intent.getIntExtra("a", 204)
                    val b = intent.getIntExtra("b", 211)
                    currentInputConnection?.requestCursorUpdates(
                        InputConnection.CURSOR_UPDATE_MONITOR,
                    )
                    currentInputConnection?.setSelection(a, b)
                    android.util.Log.d(TAG, "debug setSelection($a, $b)")
                }
                else -> keyboardView?.debugStartSelection()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG_GESTURES) {
            registerReceiver(
                debugSelectReceiver,
                IntentFilter().apply {
                    addAction("com.offlinekeyboard.ime.DEBUG_SELECT")
                    addAction("com.offlinekeyboard.ime.DEBUG_REVSEL")
                },
                Context.RECEIVER_EXPORTED,
            )
        }
    }

    override fun onDestroy() {
        if (DEBUG_GESTURES) runCatching { unregisterReceiver(debugSelectReceiver) }
        dictation?.release()
        dictation = null
        super.onDestroy()
    }

    // --- dictation ------------------------------------------------------------------------

    private val dictationListener = object : Dictation.Listener {
        override fun onStateChanged(state: Dictation.State) {
            keyboardView?.status = when (state) {
                Dictation.State.IDLE -> null
                Dictation.State.LOADING -> getString(R.string.dictation_loading)
                Dictation.State.LISTENING -> getString(R.string.dictation_listening)
                Dictation.State.TRANSCRIBING -> getString(R.string.dictation_transcribing)
            }
            if (state == Dictation.State.IDLE) refreshCandidates()
        }

        override fun onText(text: String) = commitDictated(text)

        override fun onUnavailable(reason: Dictation.Reason) {
            keyboardView?.status = null
            when (reason) {
                Dictation.Reason.NO_PERMISSION -> MicrophonePermissionActivity.launchFrom(this@KeyboardService)
                Dictation.Reason.NO_MICROPHONE ->
                    showBriefly(getString(R.string.dictation_no_microphone))
                Dictation.Reason.MODEL_FAILED ->
                    showBriefly(getString(R.string.dictation_failed))
            }
        }
    }

    private fun toggleDictation() {
        val engine = dictation ?: Dictation(this).also { dictation = it }
        if (engine.state != Dictation.State.IDLE) engine.stop() else engine.start(dictationListener)
    }

    /**
     * Commits one recognised segment.
     *
     * The model returns a clause per pause with no punctuation the speaker did not say, so the
     * spacing between segments is ours to get right: a space between them in Latin script, and
     * none in Chinese, where words do not take one.
     */
    private fun commitDictated(raw: String) {
        val text = SpokenPunctuation.apply(raw, scriptFor(raw))
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
        val needsSpace = before != null && !before.isWhitespace() &&
            !isHan(before) && !isHan(text.first()) && text.first().isLetterOrDigit()
        ic.beginBatchEdit()
        ic.commitText(if (needsSpace) " $text" else text, 1)
        ic.endBatchEdit()
        dictatedAnything = true
        refreshCandidates()
    }

    private fun isHan(c: Char): Boolean =
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    /**
     * Which script the punctuation should take, decided from the segment itself rather than from
     * a keyboard mode. Dictation runs with automatic language detection, so a segment's language
     * is not known until it comes back -- and in a code-switched sentence it can differ from the
     * one before it.
     */
    private fun scriptFor(text: String): SpokenPunctuation.Script = when {
        text.none(::isHan) -> SpokenPunctuation.Script.LATIN
        isTraditionalSubtype() -> SpokenPunctuation.Script.TRADITIONAL
        else -> SpokenPunctuation.Script.SIMPLIFIED
    }

    private fun isTraditionalSubtype(): Boolean {
        val subtype = getSystemService(InputMethodManager::class.java)
            ?.currentInputMethodSubtype ?: return false
        val tag = subtype.languageTag.ifEmpty { @Suppress("DEPRECATION") subtype.locale }
        return tag.startsWith("zh_TW", ignoreCase = true) ||
            tag.startsWith("zh-TW", ignoreCase = true)
    }

    /** A message in the suggestion strip that clears itself. There is nowhere else to put one. */
    private fun showBriefly(message: String) {
        keyboardView?.status = message
        handler.postDelayed({
            if (dictation?.state == Dictation.State.IDLE) {
                keyboardView?.status = null
                refreshCandidates()
            }
        }, 2500)
    }

    override fun onCreateInputView(): View =
        KeyboardView(this).also { view ->
            keyboardView = view
            view.onOutput = ::handleOutputs
            view.onCandidate = ::commitCandidate
            applyLayout()
            loadEmojiIndex()
        }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        endSelection()
        stopTrackpad()
        stopBackspaceRepeat()
        clearCandidates()
        // The microphone must never outlive the keyboard being on screen.
        dictation?.stop()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shift = ShiftState.OFF
        applyLayout()
        loadEmojiIndex()
        refreshCandidates()
    }

    /**
     * The caret moved, in the editor's own reckoning. The bar follows it: suggestions are for
     * the word the caret is in, so tapping into another word must re-offer that word's emoji
     * rather than leave the previous word's on screen.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        if (!trackpadActive) refreshCandidates()
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
                is GestureOutput.TrackpadPan -> {
                    trace("PAN dx=${out.dx} dy=${out.dy}")
                    panMarker(out.dx, out.dy)
                }
                GestureOutput.TrackpadEnded -> {
                    endSelection()
                    stopTrackpad()
                }
                GestureOutput.BackspaceRepeatStarted -> startBackspaceRepeat()
                GestureOutput.BackspaceRepeatEnded -> stopBackspaceRepeat()
                GestureOutput.BulkDelete -> bulkDelete()
                is GestureOutput.SpecialKey -> handleSpecialKey(out.type)
                is GestureOutput.GlideCompleted -> Unit // Phase 2: decode the path into a word
                // Kept only while the gesture lab has a drill armed; a no-op otherwise.
                is GestureOutput.GestureCaptured -> GestureCapture.onGesture(this, out.trace)
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
        refreshCandidates()
    }

    private fun handleSpecialKey(type: KeyType) {
        when (type) {
            KeyType.SHIFT -> toggleShift()
            KeyType.BACKSPACE -> backspace()
            KeyType.MODE_SWITCH -> cycleplane()
            KeyType.GLOBE -> switchToNextInputMethod(false)
            KeyType.MIC -> toggleDictation()
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
        refreshCandidates()
    }

    // --- held backspace -------------------------------------------------------------------

    private val backspaceRepeat = object : Runnable {
        override fun run() {
            backspaceRepeats++
            if (backspaceRepeats > BACKSPACE_REPEATS_BEFORE_WORDS) {
                deleteWordBackwards()
                handler.postDelayed(this, BACKSPACE_WORD_INTERVAL_MS)
            } else {
                backspace()
                handler.postDelayed(this, BACKSPACE_CHAR_INTERVAL_MS)
            }
        }
    }

    /** The long press itself is the first deletion, so the hold feels immediate. */
    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        backspaceRepeats = 0
        handler.post(backspaceRepeat)
    }

    private fun stopBackspaceRepeat() {
        handler.removeCallbacks(backspaceRepeat)
        backspaceRepeats = 0
    }

    /**
     * Deletes back over any run of spaces and then the word before them, stopping at a line
     * break: a held backspace should pause at the start of each line rather than run past it.
     */
    private fun deleteWordBackwards() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(TypedWord.LOOKBEHIND, 0)
        if (before.isNullOrEmpty()) return
        var n = 0
        while (n < before.length && before[before.length - 1 - n] == ' ') n++
        while (n < before.length && !before[before.length - 1 - n].isWhitespace()) n++
        ic.deleteSurroundingText(n.coerceAtLeast(1), 0)
        refreshCandidates()
    }

    /**
     * Requirement 11: hold backspace and swipe up to clear what was typed.
     *
     * Clears back to the start of the line -- which in a single-line field, the common case, is
     * the whole field, since there is no line break to stop at. Starting from the beginning of a
     * line there is nothing on it to clear, so the gesture takes the line above instead, and
     * repeating it walks a paragraph away a line at a time. Deleting the entire field outright
     * from anywhere would be the one gesture on this keyboard that can destroy text the user
     * cannot see, and there is no undo to answer for it.
     */
    private fun bulkDelete() {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.finishComposingText()

        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            ic.endBatchEdit()
            refreshCandidates()
            return
        }

        var clearedSomething = false
        for (chunk in 0 until BULK_DELETE_MAX_CHUNKS) {
            val before = ic.getTextBeforeCursor(BULK_DELETE_CHUNK, 0)
            if (before.isNullOrEmpty()) break
            val lineBreak = before.lastIndexOf('\n')
            val onThisLine = if (lineBreak >= 0) before.length - 1 - lineBreak else before.length
            if (onThisLine > 0) {
                ic.deleteSurroundingText(onThisLine, 0)
                clearedSomething = true
                // A line break in view means the line's start has been reached; stop there.
                // Without one the chunk was all one line, so more of it may lie further back.
                if (lineBreak >= 0) break
            } else {
                if (clearedSomething) break
                // Started at the beginning of a line: step over the break and take the line above.
                ic.deleteSurroundingText(1, 0)
            }
        }

        ic.endBatchEdit()
        refreshCandidates()
    }

    // --- suggestion bar -------------------------------------------------------------------

    private fun loadEmojiIndex() {
        if (emoji != null || emojiLoading) return
        emojiLoading = true
        Thread {
            val loaded = runCatching { assets.open(EMOJI_ASSET).use(EmojiIndex::load) }
                .onFailure { android.util.Log.w(TAG, "emoji index failed to load", it) }
                .getOrNull()
            handler.post {
                emoji = loaded
                emojiLoading = false
                refreshCandidates()
            }
        }.start()
    }

    private fun clearCandidates() {
        keyboardView?.candidates = emptyList()
        candidateReplaceLength = 0
    }

    /**
     * Offers emoji for the word the caret sits at the end of.
     *
     * The two-word form is tried first and the first form that matches wins, so "thumbs up"
     * beats "up" where both would match, and the length that produced the match is remembered:
     * that is exactly what a tapped emoji replaces.
     */
    private fun refreshCandidates() {
        val view = keyboardView ?: return
        val index = emoji
        val ic = currentInputConnection
        if (index == null || ic == null) return clearCandidates()
        if (!ic.getSelectedText(0).isNullOrEmpty()) return clearCandidates()

        val before = ic.getTextBeforeCursor(TypedWord.LOOKBEHIND, 0) ?: return clearCandidates()
        for (word in TypedWord.endingAt(before)) {
            val hits = index.search(word.query)
            if (hits.isNotEmpty()) {
                view.candidates = hits
                candidateReplaceLength = word.length
                return
            }
        }
        clearCandidates()
    }

    /** Replaces the typed word with the emoji, the way the iOS emoji suggestion does. */
    private fun commitCandidate(position: Int) {
        val text = keyboardView?.candidates?.getOrNull(position) ?: return
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (candidateReplaceLength > 0) ic.deleteSurroundingText(candidateReplaceLength, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        refreshCandidates()
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

    /** One line per event, so a whole gesture can be reconstructed exactly from logcat. */
    private fun trace(message: String) {
        if (DEBUG_GESTURES) android.util.Log.d("TP", message)
    }

    private fun startTrackpad() {
        trace("=== TRACKPAD START selecting=$extendingSelection ===")
        trackpadActive = true
        // IMMEDIATE as well as MONITOR. MONITOR alone only delivers when the cursor *moves*,
        // so a second trackpad gesture with no editing in between would never receive a seed
        // position, leaving the marker unplaced and the whole gesture inert. That is why it
        // previously took a tap in the text to "wake up" between drags.
        val ok = currentInputConnection?.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
        )
        if (DEBUG_GESTURES) android.util.Log.d(TAG, "requestCursorUpdates -> $ok")
        markerX = Float.NaN
        markerCenterY = Float.NaN
        caretX = Float.NaN
        caretTop = Float.NaN
        pendingHorizontal = 0
        pendingVertical = 0
        verticalStuckDir = 0
        updateIndicator()
    }

    private fun stopTrackpad() {
        trace("=== TRACKPAD END ===")
        trackpadActive = false
        scrollPinned = false
        handler.removeCallbacks(edgeScrollTick)
        markerX = Float.NaN
        markerCenterY = Float.NaN
        currentInputConnection?.requestCursorUpdates(0)
        indicatorPopup?.takeIf { it.isShowing }?.let { runCatching { it.dismiss() } }
    }

    /** The finger moves the marker, freely, in screen space. Nothing constrains it to the text. */
    private fun panMarker(dx: Float, dy: Float) {
        if (!trackpadActive || markerX.isNaN()) return
        val metrics = resources.displayMetrics
        // Movement back the other way frees it again.
        if (verticalStuckDir != 0 && dy != 0f && (dy > 0f) != (verticalStuckDir > 0)) {
            verticalStuckDir = 0
        }
        // Do not let the marker travel past where the caret can actually follow.
        val effectiveDy = if (verticalStuckDir != 0 && (dy > 0f) == (verticalStuckDir > 0)) 0f else dy
        markerX = (markerX + dx).coerceIn(0f, metrics.widthPixels.toFloat())
        markerCenterY = (markerCenterY + effectiveDy).coerceIn(0f, metrics.heightPixels.toFloat())
        trace("MARK x=$markerX y=$markerCenterY vStuck=$verticalStuckDir")
        updateIndicator()
        updateEdgeScroll()
        if (extendingSelection) {
            // Steer on the pan as well as on cursor updates: CURSOR_UPDATE_MONITOR only fires
            // when the cursor actually moves, so waiting for one would deadlock -- no movement,
            // no update, no movement.
            //
            // steerSelection must be reached even while a line change is outstanding, because
            // that is where the wait is timed out. Gating it here meant an arrow that moved
            // nothing -- at the top or bottom of the text -- left the latch set forever, and
            // the selection stopped responding entirely.
            if (!extendSelection(effectiveDy)) steerSelection()
        } else {
            chaseCaret()
        }
    }

    /**
     * Vertical half of dragging a selection. Horizontal is handled by the closed loop in
     * [steerSelection]; only line changes need a keystroke, because an offset cannot express
     * "one visual line down" when lines soft-wrap.
     *
     * To move a line the selection is briefly collapsed onto the moving end so a plain arrow
     * key acts on it, and is re-applied once the new position is reported.
     */
    private fun extendSelection(dy: Float): Boolean {
        val lh = lineHeight.takeIf { it > 1f } ?: return false
        selectionBankY += dy
        // One line change at a time: the next arrow must act on the position the previous one
        // produced, which is not known until the app reports it.
        if (selectionAwaitingLine) return false

        var moved = false
        // Round to the nearest line rather than waiting for a whole one, so the marker never
        // leads the selection by more than half a line. Strict, because at exactly half a line
        // a non-strict test would step back and forth forever.
        while (abs(selectionBankY) > lh / 2f) {
            val step = if (selectionBankY > 0) 1 else -1
            selectionBankY -= step * lh
            if (!moved) {
                selectionPreStepEnd = selectionMovingEnd
                // Put the span into its natural order so that the arrow key, which moves
                // SELECTION_END, moves the end we are dragging. The highlighted range is
                // unchanged by this -- only which end Android calls the start -- so unlike
                // collapsing the selection it produces no visible flicker.
                currentInputConnection?.setSelection(selectionAnchor, selectionMovingEnd)
                moved = true
            }
            sendArrow(
                if (step > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
            )
        }
        if (moved) {
            selectionAwaitingLine = true
            selectionWaitTicks = 0
        }
        return moved
    }

    /**
     * Bounds of the line containing [offset], as absolute text offsets. Falls back to the
     * offset itself when no snapshot is available, which simply disables clamping.
     */
    private fun lineBounds(offset: Int): IntRange? {
        val text = selectionText ?: return null
        val local = offset - selectionTextStart
        if (local < 0 || local > text.length) return null
        var start = 0
        for (i in local - 1 downTo 0) {
            if (text[i] == '\n') {
                start = i + 1
                break
            }
        }
        var end = text.length
        for (i in local until text.length) {
            if (text[i] == '\n') {
                end = i
                break
            }
        }
        return (start + selectionTextStart)..(end + selectionTextStart)
    }

    /**
     * Moves the dragged end of the selection toward the marker, closed-loop.
     *
     * The selection is deliberately stored *reversed* -- setSelection(movingEnd, anchor). The
     * highlight is identical either way, but the insertion marker follows the selection's start
     * span, so reversing it makes the app report the end being dragged instead of the fixed
     * one. Measured on device: setSelection(204, 211) reports x=409.6, the position of 204,
     * while setSelection(211, 204) reports x=548.6, the position of 211.
     *
     * That report is the feedback signal. Each round re-derives the error from it, so an
     * inaccurate character width costs one extra round instead of accumulating into drift.
     *
     * The target is clamped to the moving end's own line. Changing line is what vertical
     * movement is for; letting a horizontal correction wrap would send the loop chasing down
     * the document. Clamping rather than detecting the wrap and reverting matters: the revert
     * made the selection visibly jump back, and any vertical jitter released the block that
     * suppressed it, so dragging past the end of a line flickered rapidly between the two.
     */
    private fun steerSelection() {
        val ic = currentInputConnection ?: return
        val selStart = lastSelStart
        val selEnd = lastSelEnd
        val insH = caretX
        if (selStart < 0 || insH.isNaN()) return

        // Which span is the end we are dragging depends on the order the selection is stored
        // in: reversed while steering horizontally, natural while an arrow key changes line.
        val collapsed = selStart == selEnd
        val naturalOrder = !collapsed && selStart == selectionAnchor
        val reportedMovingEnd = if (naturalOrder) selEnd else selStart

        if (selectionAwaitingLine) {
            // setSelection and sendKeyEvent take different routes to the editor, so the span
            // swap is echoed back before the arrow has been applied. Acting on that echo would
            // undo the line change. Wait for a position that is actually different.
            if (reportedMovingEnd == selectionPreStepEnd && selectionWaitTicks < 4) {
                selectionWaitTicks++
                return
            }
            selectionAwaitingLine = false
        }
        selectionMovingEnd = reportedMovingEnd

        if (naturalOrder) {
            // Restore the reversed order so the reported insertion marker follows the dragged
            // end again. Same range, so invisible. insH currently describes the anchor, so
            // there is nothing useful to steer on until the next report.
            ic.setSelection(selectionMovingEnd, selectionAnchor)
            selectionAppliedChars = 0
            return
        }

        if (collapsed) {
            // A purely vertical drag produces no horizontal error, so the selection must be
            // applied here rather than waiting for a correction to need it.
            if (selectionMovingEnd != selectionAnchor) {
                ic.setSelection(selectionMovingEnd, selectionAnchor)
            }
        } else {
            // Learn the real character advance from what the last correction actually moved.
            if (selectionAppliedChars != 0 && !selectionPrevInsH.isNaN()) {
                val advance = abs(insH - selectionPrevInsH) / abs(selectionAppliedChars)
                if (advance > 1f && advance < 200f) charWidth = advance
            }
        }

        val error = markerX - insH
        val chars = (error / effectiveCharWidth()).roundToInt().coerceIn(-24, 24)
        if (chars == 0) {
            selectionAppliedChars = 0
            return
        }

        val bounds = lineBounds(selectionMovingEnd)
        val target = (selectionMovingEnd + chars).let {
            if (bounds != null) it.coerceIn(bounds.first, bounds.last) else it.coerceAtLeast(0)
        }
        if (target == selectionMovingEnd) {
            // Already at the edge of the line; the marker is free to carry on without us.
            selectionAppliedChars = 0
            return
        }
        selectionPrevInsH = insH
        selectionAppliedChars = target - selectionMovingEnd
        selectionMovingEnd = target
        ic.setSelection(selectionMovingEnd, selectionAnchor)
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        if (DEBUG_GESTURES) android.util.Log.d(
            TAG,
            "anchor sel=[${info.selectionStart},${info.selectionEnd}] " +
                "insH=${info.insertionMarkerHorizontal} insT=${info.insertionMarkerTop} " +
                "markerY=$markerCenterY",
        )
        val point = caretPoint(info) ?: return

        // A rightward step that landed on a lower row wrapped: wherever the caret was before it
        // is where this text wraps. Remember it, so no further step tries to cross.
        if (pendingHorizontal > 0 && pendingVertical == 0 &&
            !caretX.isNaN() && !caretTop.isNaN() && point[1] > caretTop + 1f
        ) {
            rowRightEdge = caretX
            rowRightEdgeTop = caretTop
            trace("LEARN rowRightEdge=$rowRightEdge onRowTop=$rowRightEdgeTop")
            // Step back onto the row we just left, rather than waiting for the vertical
            // correction to drag the caret all the way back to that row's start.
            sendArrow(KeyEvent.KEYCODE_DPAD_LEFT, 0)
        }

        trace(
            "CARET sel=[${info.selectionStart},${info.selectionEnd}] " +
                "screenX=${point[0]} screenTop=${point[1]} " +
                "markX=$markerX markY=$markerCenterY " +
                "lh=$lineHeight cw=$charWidth editorL=$editorLeft editorR=$editorRight " +
                "pendH=$pendingHorizontal pendV=$pendingVertical",
        )
        val previousX = caretX
        val previousTop = caretTop
        if (info.selectionStart == info.selectionEnd) caretOffset = info.selectionStart

        (point[3] - point[1]).takeIf { it > 1f }?.let { lineHeight = it }

        if (!previousX.isNaN()) {
            // Measure the caret's real advance per step, from purely horizontal moves only: a
            // change of line moves x arbitrarily and would poison the estimate.
            if (pendingHorizontal != 0 && pendingVertical == 0) {
                val advance = abs(point[0] - previousX) / abs(pendingHorizontal)
                if (advance > 1f && advance < 200f) charWidth = advance
            }
            // Whether a vertical push achieved anything must be judged by the caret's *offset*,
            // not its position on screen. When the view scrolls it deliberately holds the caret
            // still on screen, so screen position says "did not move" for the one case where it
            // moved the most -- which latched vertical movement off during every scroll.
            if (pendingVertical != 0 && pendingFromOffset >= 0) {
                val movedInText = info.selectionStart != pendingFromOffset
                verticalStuckDir = if (movedInText) 0 else if (pendingVertical > 0) 1 else -1
                // Moved in the text but not on screen: the editor is scrolling underneath a
                // pinned caret. Its reported position will not close the error, so vertical
                // movement has to be handed to the rate-limited scroll instead of chased.
                scrollPinned = movedInText && abs(point[1] - previousTop) < 1f
            }

            // Deliberately no attempt to move the marker with the scrolling text. Doing so
            // changes the very error that caused the scroll, and the two fight: measured on
            // device the view scrolled up and down by one line repeatedly, with the caret
            // offset unchanged. Leaving the marker fixed on screen gives the behaviour that is
            // actually wanted -- the caret moves within the visible text, and only pushes the
            // view when it reaches the edge.
        }
        pendingHorizontal = 0
        pendingVertical = 0
        pendingFromOffset = -1

        info.editorBoundsInfo?.editorBounds?.let { bounds ->
            val corners = floatArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
            info.matrix.mapPoints(corners)
            editorLeft = corners[0]
            editorRight = corners[2]
        }

        caretX = point[0]
        caretTop = point[1]
        caretBottom = point[3]

        // Seed the marker on the caret at the start of the drag; free thereafter.
        if (trackpadActive && markerX.isNaN()) {
            markerX = caretX
            markerCenterY = caretTop + lineHeight / 2f
        }

        lastSelStart = info.selectionStart
        lastSelEnd = info.selectionEnd

        updateIndicator()
        updateEdgeScroll()
        if (extendingSelection) steerSelection() else chaseCaret()
    }

    /**
     * +1 when the marker is held below the visible text, -1 above it, 0 within it.
     *
     * The visible text ends where the keyboard begins; anything below that is the user pushing
     * past the bottom of what they can see.
     */
    /**
     * +1 to keep scrolling down, -1 up, 0 to leave it to the ordinary chase.
     *
     * Only active once the editor has been seen scrolling under a pinned caret. Until then the
     * caret is moving normally within the visible text and the chase closes the error properly.
     */
    private fun edgeScrollDirection(): Int {
        if (!scrollPinned || !trackpadActive) return 0
        if (markerCenterY.isNaN() || caretTop.isNaN()) return 0
        val lh = lineHeight.takeIf { it > 1f } ?: return 0
        val lines = (markerCenterY - (caretTop + lh / 2f)) / lh
        return when {
            lines > 0.5f -> 1
            lines < -0.5f -> -1
            else -> 0
        }
    }

    /**
     * Steps the caret one line so the editor scrolls to keep it in view.
     *
     * Deliberately one line per tick rather than closing the whole error at once. Once the caret
     * is pushed past the edge the editor pins it there and scrolls the text instead, so its
     * reported position stops changing and the error never resolves -- an error-driven chase
     * therefore runs away, and measured on device it reached the end of the document in a single
     * short drag. A fixed rate turns that into a steady scroll.
     */
    private fun scrollOneLine(direction: Int) {
        if (extendingSelection) {
            extendSelection(direction * (lineHeight.takeIf { it > 1f } ?: 60f))
            return
        }
        if (pendingFromOffset < 0) pendingFromOffset = caretOffset
        sendArrow(
            if (direction > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP,
            0,
        )
        pendingVertical += direction
    }

    /** Milliseconds until the next line, from how far the marker is beyond the caret. */
    private fun edgeScrollInterval(): Long {
        val lh = lineHeight.takeIf { it > 1f } ?: return EDGE_SCROLL_SLOWEST_MS
        val past = abs(markerCenterY - (caretTop + lh / 2f))
        val ramp = (past / EDGE_SCROLL_FULL_SPEED_PX).coerceIn(0f, 1f)
        return (EDGE_SCROLL_SLOWEST_MS - (EDGE_SCROLL_SLOWEST_MS - EDGE_SCROLL_FASTEST_MS) * ramp)
            .toLong()
    }

    private fun updateEdgeScroll() {
        handler.removeCallbacks(edgeScrollTick)
        if (trackpadActive && edgeScrollDirection() != 0) handler.post(edgeScrollTick)
    }

    private fun chaseCaret() {
        if (!trackpadActive || caretX.isNaN() || markerX.isNaN()) return
        // Selections are steered by steerSelection, which stores them reversed so the reported
        // marker follows the dragged end rather than the fixed one.
        if (extendingSelection) return
        // Await the previous round's result -- but not forever. An arrow at the very top or
        // bottom of the text moves nothing, so no cursor update is ever delivered, and waiting
        // unconditionally wedges the chase permanently: the caret simply stops following the
        // marker from then on.
        if (pendingHorizontal != 0 || pendingVertical != 0) {
            if (chaseWaitTicks < 3) {
                chaseWaitTicks++
                return
            }
            // Timing out *is* the signal that the arrows achieved nothing: a move that changes
            // the caret always reports back. Nothing reported means the caret is against the
            // start or end of the text, which is the only way to learn it -- waiting for an
            // update that will never come would leave the marker free to keep travelling
            // beyond the text, and every pixel of that has to be un-travelled before the caret
            // responds again.
            if (pendingVertical != 0) {
                verticalStuckDir = if (pendingVertical > 0) 1 else -1
            }
            pendingHorizontal = 0
            pendingVertical = 0
        }
        chaseWaitTicks = 0
        val meta = 0

        val lh = lineHeight.takeIf { it > 1f } ?: return
        val lines = ((markerCenterY - (caretTop + lh / 2f)) / lh).roundToInt().coerceIn(-12, 12)
        if (lines != 0) {
            trace(
                "VWANT lines=$lines vStuck=$verticalStuckDir " +
                    "edgeScroll=${edgeScrollDirection()} scrollPinned=$scrollPinned",
            )
        }
        // While parked past an edge the repeating scroll owns vertical movement; an
        // error-driven step here would race it and overshoot.
        val stuckThisWay = verticalStuckDir != 0 && (lines > 0) == (verticalStuckDir > 0)
        if (lines != 0 && !stuckThisWay && edgeScrollDirection() == 0) {
            val step = if (lines > 0) 1 else -1
            if (pendingFromOffset < 0) pendingFromOffset = caretOffset
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
        // Deliberately a small cap. Every arrow in a burst is computed from one reading of
        // caretX, so a long burst is dead reckoning across a stale position -- and a row edge
        // reached part way through it is not noticed until the whole burst has been sent. A
        // trace of the real gesture showed 837 arrows for 73 touch events, the caret crossing
        // wraps mid-burst and restarting the error from the far side each time. Converging over
        // several short rounds costs nothing, because each arrow produces its own position
        // report to steer from.
        var chars = ((markerX - caretX) / advance).roundToInt().coerceIn(-4, 4)

        // Clamp the whole burst to what fits before the edge of the visual row.
        //
        // caretX is only refreshed between rounds, so checking it per arrow only ever guards
        // the first of them: the rest of a 24-step burst sail across the wrap on a stale
        // position. Measured from a trace of the real gesture -- 837 arrows for 73 touch events
        // -- the caret was leaving a row at x=642 with 19 steps of ~25px queued behind it,
        // landing past the editor's 1080px edge and restarting the error from the far left of
        // the next row. That is the thrash.
        if (chars > 0 && !editorRight.isNaN()) {
            chars = chars.coerceAtMost(((editorRight - caretX) / advance).toInt())
        } else if (chars < 0) {
            chars = chars.coerceAtLeast(-((caretX - editorLeft) / advance).toInt())
        }
        trace(
            "DECIDE lines=$lines chars=$chars vStuck=$verticalStuckDir " +
                "caretX=$caretX caretTop=$caretTop " +
                "rowEdgeR=${atRowEdge(true)} rowEdgeL=${atRowEdge(false)}",
        )
        if (chars == 0) return
        val step = if (chars > 0) 1 else -1
        repeat(abs(chars)) {
            // Never cross a line break sideways: up and down is what changes line.
            if (atLineEdge(forward = step > 0)) {
                trace("HBLOCK lineEdge dir=$step")
                return
            }
            // Nor a soft wrap, which has no character to detect. A wrapped row by definition
            // reaches the editor's edge, so a caret within a character of it is at the end of
            // its row; stepping past would drop the caret to the far left of the next row and
            // flip the vertical and horizontal errors at once, which is what made the caret
            // thrash. This is deliberately stateless -- a latch released on the next vertical
            // move and crossed straight back over.
            if (atRowEdge(step > 0)) {
                trace("HBLOCK rowEdge dir=$step caretX=$caretX editorR=$editorRight")
                return
            }
            sendArrow(
                if (step > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT,
                meta,
            )
            pendingHorizontal += step
        }
    }

    /**
     * Measured from the caret's real movement once the caret has moved horizontally at all;
     * until then, a third of the line height, which is about the average advance of lowercase
     * text in a proportional font. The measurement persists for the life of the service, so
     * this fallback only applies before the very first cursor move in a field.
     */
    /** True when the caret sits within a character of the editor's left or right edge. */
    private fun atRowEdge(forward: Boolean): Boolean {
        if (caretX.isNaN()) return false
        val margin = effectiveCharWidth()
        if (!forward) return caretX - margin <= editorLeft
        // Only on the row it was learned on. Applying one row's wrap point to every row was a
        // regression: rows wrap at word boundaries, so any row running past that value became
        // impossible to move through.
        val learnedHere = !rowRightEdge.isNaN() && !rowRightEdgeTop.isNaN() &&
            abs(caretTop - rowRightEdgeTop) < 1f
        val right = if (learnedHere) rowRightEdge else editorRight
        return !right.isNaN() && caretX + margin >= right
    }

    private fun effectiveCharWidth(): Float =
        charWidth.takeIf { it > 0f } ?: (lineHeight.takeIf { it > 1f } ?: 40f) * 0.33f

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
        trace(
            "ARROW " + when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
                KeyEvent.KEYCODE_DPAD_UP -> "UP"
                KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
                else -> "$keyCode"
            } + if (meta != 0) " +shift" else "",
        )
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
     * Begins a selection at the current caret position.
     *
     * No shift key is held. Selection is applied directly with setSelection, which is what lets
     * it be stored reversed so the app reports the dragged end -- see [steerSelection].
     */
    private fun beginSelection() {
        trace("=== SELECTION START ===")
        if (extendingSelection) return
        // CursorAnchorInfo is asynchronous and may not have arrived yet, so fall back to
        // asking the editor directly rather than refusing to start.
        val start = caretOffset.takeIf { it >= 0 }
            ?: currentInputConnection
                ?.getExtractedText(ExtractedTextRequest(), 0)
                ?.let { it.startOffset + it.selectionStart }
            ?: return
        if (start < 0) return
        extendingSelection = true
        selectionAnchor = start
        selectionMovingEnd = start
        selectionPrevMovingEnd = start
        selectionLineTop = caretTop
        selectionBankY = 0f
        selectionAppliedChars = 0
        selectionPrevInsH = Float.NaN
        // Hold a real shift key for the duration. Arrow keys only extend a selection when the
        // text buffer's meta state is set, and only a genuine KEYCODE_SHIFT_LEFT press does
        // that -- META_SHIFT_ON on the arrow event alone is ignored.
        currentInputConnection?.let { ic ->
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(
                KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0, 0),
            )
        }
        currentInputConnection
            ?.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 1 shl 16 }, 0)
            ?.let {
                selectionText = it.text
                selectionTextStart = it.startOffset.coerceAtLeast(0)
            }
        selectionAwaitingLine = false
    }

    /** Leaves the selection in place, normalised to the conventional order. */
    private fun endSelection() {
        if (!extendingSelection) return
        extendingSelection = false
        currentInputConnection?.let { ic ->
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(
                KeyEvent(
                    now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0,
                    KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
                ),
            )
        }
        if (selectionAnchor >= 0 && selectionMovingEnd >= 0) {
            currentInputConnection?.setSelection(
                minOf(selectionAnchor, selectionMovingEnd),
                maxOf(selectionAnchor, selectionMovingEnd),
            )
        }
        selectionAnchor = -1
        selectionMovingEnd = -1
    }
}
