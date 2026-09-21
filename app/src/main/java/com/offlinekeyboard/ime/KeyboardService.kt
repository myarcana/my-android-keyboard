package com.offlinekeyboard.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.PopupWindow
import com.offlinekeyboard.ime.view.CursorIndicatorView
import kotlin.math.abs
import kotlin.math.roundToInt
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import com.offlinekeyboard.ime.autofill.InlineAutofill
import com.offlinekeyboard.ime.autofill.InlineSuggestionStrip
import com.offlinekeyboard.ime.asr.Dictation
import com.offlinekeyboard.ime.asr.MicrophonePermissionActivity
import com.offlinekeyboard.ime.asr.SpokenPunctuation
import com.offlinekeyboard.ime.candidates.EmojiIndex
import com.offlinekeyboard.ime.candidates.TypedWord
import com.offlinekeyboard.ime.candidates.UnifiedCandidates
import com.offlinekeyboard.ime.capture.GestureCapture
import com.offlinekeyboard.ime.text.GraphemeCluster
import com.offlinekeyboard.ime.gesture.GestureOutput
import com.offlinekeyboard.ime.glide.FutoSwipe
import com.offlinekeyboard.ime.glide.GlideEngine
import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import com.offlinekeyboard.ime.glide.Lexicon
import com.offlinekeyboard.ime.layout.EditAction
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.Layout
import com.offlinekeyboard.ime.layout.Squash
import com.offlinekeyboard.ime.pinyin.PinyinSession
import com.offlinekeyboard.ime.tap.PendingWord
import com.offlinekeyboard.ime.tap.TapDecoder
import com.offlinekeyboard.ime.tap.WordIndex
import com.offlinekeyboard.ime.view.KeyboardView

/**
 * Translates gesture outputs into edits on the focused text field.
 *
 * Deliberately absent: autocorrect. A tapped key produces exactly that character, always. Word
 * decoding exists only to turn a glide gesture into a word -- a gesture that has no letters of
 * its own to preserve, and so is the one place where guessing is the whole point.
 */
private const val TAG = "OfflineKeyboard"

/** Where [Squash] is remembered between input views. */
private const val PREF_SQUASH = "squash"

/**
 * Auto-scroll rate while the marker is parked past an edge of the visible text, in milliseconds
 * per line. Proportional to how far past the edge the marker is: just over the line creeps, a
 * long way past moves quickly, which is how dragging a selection to the edge of a window behaves
 * everywhere else.
 */
/** How long a trackpad start waits for a caret report before holding a composition for one. */
private const val ANCHOR_PROBE_MS = 60L
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

/**
 * Text read back to size a single backspace. A grapheme cluster is bounded in practice -- the
 * longest emoji in common use is a seven-person ZWJ sequence -- and this leaves ample room for
 * one while keeping the read cheap enough to do on every repeat of a held backspace.
 */
private const val GRAPHEME_LOOKBEHIND = 32

private const val EMOJI_ASSET = "emoji_en.tsv"

/**
 * Characters after which a glided word takes no space in front of it. Everything else that is
 * not already whitespace does, which is the common case: the end of the previous word.
 */
private const val OPENERS = "([{\u201c\u2018\u00ab"

/**
 * Debug tooling: logs every gesture output, and registers a broadcast receiver that stands in
 * for the second finger of the selection gesture, which adb cannot send. Tied to the build type
 * so the receiver -- which is necessarily exported -- never exists in a release build.
 */
private val DEBUG_GESTURES = BuildConfig.DEBUG

class KeyboardService : InputMethodService() {

    private var keyboardView: KeyboardView? = null

    /**
     * The password-manager chips, or null below API 30 where inline suggestions do not exist.
     * Only ever non-null alongside [keyboardView]: the two are built together.
     */
    private var inlineStrip: InlineSuggestionStrip? = null

    // --- suggestion bar ---
    /**
     * Requirement 9: the bar offers emoji, never English words. Loaded off the main thread
     * because it is 1900 entries read from an asset and the keyboard must appear instantly.
     */
    private var emoji: EmojiIndex? = null
    private var emojiLoading = false

    // --- glide typing ---
    /** Loaded off the main thread: 40,000 words is a fifth of a second the keyboard cannot wait. */
    private var glide: GlideEngine? = null
    private var glideLoading = false
    /** How many characters a tapped suggestion replaces: the word that produced it. */
    private var candidateReplaceLength = 0

    /**
     * How many characters each suggestion replaces, parallel to the bar.
     *
     * The single [candidateReplaceLength] was enough while the bar held only emoji, which always
     * stand for the whole word that found them. A Chinese suggestion need not: 牛肉 is a good
     * answer for `niuroumian` and replaces six of its ten letters, leaving `mian` to be typed
     * on. Empty when the bar is uniform, in which case [candidateReplaceLength] applies to all.
     */
    private var candidateConsumes: List<Int> = emptyList()

    // --- tap decoding ---
    /**
     * Re-reads a run of letter taps once there is enough of a word to read. Shares the lexicon
     * with the glide engine and is built beside it, off the main thread.
     */
    private var tapDecoder: TapDecoder? = null

    /**
     * The English lexicon, for judging whether typed letters are already an English word.
     *
     * The same object the glide and tap decoders use, held here because the suggestion bar needs
     * a third thing from it: `ln P(word)`, which is the evidence that keeps 有 off the bar when
     * `you` was typed. Null until [loadGlideEngine] finishes, and the bar simply offers Chinese
     * un-discounted until then.
     */
    private var englishWords: Lexicon? = null

    /** Letter taps held as composing text, waiting for the word to end. */
    private val pending = PendingWord()

    // --- chinese ---
    /**
     * Pinyin input, live only while a Chinese subtype is selected.
     *
     * Created lazily and loaded off the main thread the first time Chinese is chosen: an
     * English-only session should not read 8 MB of dictionary for a language it never uses.
     * Holding the raw letters here rather than in [pending] is what lets a half-typed Chinese
     * word survive a language switch, which is requirement 10.
     */
    private var pinyin: PinyinSession? = null

    /** True while the selected subtype is one of the Chinese ones. */
    private var chineseMode = false

    /**
     * What the gesture currently being processed did to the field.
     *
     * The lab needs the mapping from gesture to output, and this is the only place it exists.
     * Nothing downstream can recover it: a glide types a word and sometimes a space in front of
     * it, an emoji replaces a run of characters, a flick produces a digit, and by the time the
     * text has landed there is no way to tell which gesture put which part of it there. So it is
     * counted here, as it happens, and handed over with the trace.
     */
    private val typedThisGesture = StringBuilder()
    private var deletedThisGesture = 0

    private fun typed(text: String) {
        typedThisGesture.append(text)
    }

    private fun deleted(count: Int) {
        deletedThisGesture += count
    }

    /**
     * Whether this field wants a word held open at all.
     *
     * Off for passwords and nothing else.
     */
    private var tapDecodingAllowed = false

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
    /**
     * Distance between one line and the next, measured from real vertical steps. Not the same
     * as [lineHeight], which is the caret's own height: with line spacing -- a web textarea is
     * 59px of caret on a 72px pitch -- a marker half a caret below one line is still more than
     * half a caret above the next, both lines claim it, and the caret flips between them.
     */
    private var linePitch = 0f
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

    /** The selection as onUpdateSelection last gave it, for apps whose anchor info omits it. */
    private var editorSelStart = -1
    private var editorSelEnd = -1

    /**
     * The focused editor only reports its caret while text is being composed, so the trackpad
     * has to hold a composing region open to see where the caret is. Firefox is the case in
     * point: it answers requestCursorUpdates with true and then sends nothing, not even for
     * CURSOR_UPDATE_IMMEDIATE, unless a composition exists -- with one, every IMMEDIATE request
     * comes back within a few milliseconds with the exact caret position. Learned per field,
     * so only the first drag in it pays the probe.
     */
    private var anchorNeedsComposition = false
    /** The composing region is ours, held only so the caret gets reported; release it after. */
    private var composingForAnchor = false

    /**
     * Run shortly after the trackpad starts. An app that reports at all answers the IMMEDIATE
     * request well within this; one that has not is taken to need a composition.
     */
    private val anchorProbe = Runnable {
        if (trackpadActive && markerX.isNaN() && !composingForAnchor) {
            trace("no caret report: holding a composition to get one")
            anchorNeedsComposition = true
            holdCompositionForAnchor()
        }
    }

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
            setStatus(when (state) {
                Dictation.State.IDLE -> null
                Dictation.State.LOADING -> getString(R.string.dictation_loading)
                Dictation.State.LISTENING -> getString(R.string.dictation_listening)
                Dictation.State.TRANSCRIBING -> getString(R.string.dictation_transcribing)
            })
            if (state == Dictation.State.IDLE) refreshCandidates()
        }

        override fun onText(text: String) = commitDictated(text)

        override fun onUnavailable(reason: Dictation.Reason) {
            setStatus(null)
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
     *
     * This reads the *repaired* text: a segment whose Mandarin came back romanised is decoded
     * again before it reaches here, so the Han characters that pick the script are present by
     * this point rather than having been spelled out in Latin letters. See `asr/CodeSwitch.kt`.
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

    /** Whether the selected subtype writes Chinese, in either script. */
    private fun isChineseSubtype(): Boolean {
        val subtype = getSystemService(InputMethodManager::class.java)
            ?.currentInputMethodSubtype ?: return false
        val tag = subtype.languageTag.ifEmpty { @Suppress("DEPRECATION") subtype.locale }
        return tag.startsWith("zh", ignoreCase = true)
    }

    /**
     * Brings the pinyin engine into line with the selected subtype.
     *
     * Called on every focus and every subtype change, because the globe key can switch language
     * while a word is half-typed. What is *not* done here is clearing the buffer: the letters
     * belong to the user, and requirement 10 says switching language must not destroy them. They
     * stay composing, and are committed as letters if the language they were meant for is gone.
     */
    private fun syncChineseMode() {
        val wasChinese = chineseMode
        chineseMode = isChineseSubtype()
        // The session is loaded in *both* modes now, because the English bar offers Chinese too.
        // Still lazily and still off the main thread: the first English keystroke starts the
        // read, and until it lands the bar is emoji-only rather than empty, which is exactly how
        // the keyboard behaved before this existed.
        val session = pinyin ?: PinyinSession(this).also { pinyin = it }
        // Which language model the decoder scores against. In English there is no Chinese
        // subtype to read it from, so the Traditional model is chosen by the same setting the
        // user would use to get Traditional in Chinese mode.
        session.traditional = if (chineseMode) isTraditionalSubtype() else preferTraditional()
        // refreshCandidates on arrival, so the bar fills as soon as the dictionary lands
        // rather than waiting for the next keystroke.
        session.ensureLoaded { refreshCandidates() }
        if (!chineseMode && wasChinese) {
            // Leaving Chinese with letters still composing: they are already in the field as
            // composing text, so finishing settles them exactly as typed.
            pinyin?.clear()
            currentInputConnection?.finishComposingText()
        }
        // The view is not told about the mode. How the strip draws follows from what is on it --
        // the candidate kinds -- not from which subtype is active; see [KeyboardView.textStrip].
        refreshCandidates()
    }

    /**
     * Whether the English bar's Chinese suggestions should come from the Taiwan model.
     *
     * Read from the enabled subtypes rather than from a setting of its own: a user who has added
     * the Traditional Chinese subtype has already said which Chinese they write, and asking them
     * again in a second place is how the two answers come to disagree. With both Chinese
     * subtypes enabled, or neither, this is false and the mainland model is used.
     */
    private fun preferTraditional(): Boolean {
        val manager = getSystemService(InputMethodManager::class.java) ?: return false
        val subtypes = runCatching {
            manager.getEnabledInputMethodSubtypeList(null, true)
        }.getOrNull().orEmpty()
        var traditional = false
        var simplified = false
        for (subtype in subtypes) {
            @Suppress("DEPRECATION")
            val tag = subtype.languageTag.ifEmpty { subtype.locale }
            if (!tag.startsWith("zh", ignoreCase = true)) continue
            if (tag.contains("TW", ignoreCase = true) ||
                tag.contains("Hant", ignoreCase = true) ||
                tag.contains("HK", ignoreCase = true)
            ) {
                traditional = true
            } else {
                simplified = true
            }
        }
        return traditional && !simplified
    }

    /**
     * Puts a message in the strip -- and takes the strip back from any password-manager chips
     * covering it, which are drawn by another process and would otherwise sit on top of it.
     *
     * The suggestion is not lost so much as declined: pressing the microphone is a deliberate
     * act, and the manager re-offers on the next focus. A chip overlapping "Listening" would be
     * the worse trade.
     */
    private fun setStatus(message: String?) {
        if (message != null) clearInlineSuggestions()
        keyboardView?.status = message
    }

    /** A message in the suggestion strip that clears itself. There is nowhere else to put one. */
    private fun showBriefly(message: String) {
        setStatus(message)
        handler.postDelayed({
            if (dictation?.state == Dictation.State.IDLE) {
                setStatus(null)
                refreshCandidates()
            }
        }, 2500)
    }

    override fun onCreateInputView(): View {
        val view = KeyboardView(this)
        keyboardView = view
        view.onOutput = ::handleOutputs
        view.onCandidate = ::commitCandidate
        // A one-handed grip is a property of how the phone is being held, which outlives the
        // input view: the system throws this view away and rebuilds it on a configuration
        // change and whenever it pleases, and a hand that squashed the board would have to do
        // it again every time if the state lived only here.
        view.squash = loadSquash()
        view.onSquashChanged = ::saveSquash
        applyLayout()
        loadEmojiIndex()
        inlineStrip = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return view

        // Inline autofill chips are Views rendered by the password manager's process, so they
        // cannot be drawn on KeyboardView's canvas the way the emoji are. The input view becomes
        // two layers instead: the keyboard, and an overlay lying on the strip that is empty and
        // GONE until a manager fills it.
        val strip = InlineSuggestionStrip(this)
        inlineStrip = strip
        return FrameLayout(this).apply {
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                strip,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ),
            )
        }
    }

    /**
     * The system asks this before showing the keyboard for a field; returning null -- which the
     * default implementation does, and which this keyboard used to inherit -- is what tells it
     * not to bother a password manager for suggestions at all.
     *
     * Called before the input view has been measured on the first show, hence the fallback to
     * the display's width: the request has to name the size chips will be drawn at, and the
     * keyboard is always the full width of the window.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        val width = keyboardView?.width?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        return InlineAutofill.request(this, width)
    }

    /**
     * A password manager has answered. Returning true claims the suggestions; returning false
     * lets the system fall back to the manager's own dropdown over the field, which is the right
     * answer when there is nothing to show or nowhere to show it.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val view = keyboardView ?: return false
        val strip = inlineStrip ?: return false
        val width = view.width.takeIf { it > 0 } ?: return false
        val shown = strip.show(response.inlineSuggestions, width)
        view.stripHandedOver = shown
        // The emoji were never cleared, only covered; if the chips did not appear they need to
        // be caught up with whatever was typed while the manager was thinking.
        if (!shown) refreshCandidates()
        return shown
    }

    /** Takes the strip back from the chips, if they had it. */
    private fun clearInlineSuggestions() {
        inlineStrip?.clear()
        keyboardView?.stripHandedOver = false
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // A glide waiting to see whether the finger comes back never will now, and the word it
        // drew belongs in the field it was drawn over rather than in whatever is focused next.
        keyboardView?.finishPendingGlide()
        flushPending()
        endSelection()
        stopTrackpad()
        stopBackspaceRepeat()
        clearCandidates()
        clearInlineSuggestions()
        // The microphone must never outlive the keyboard being on screen.
        dictation?.stop()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // A word held over from the last field must not follow the focus into this one.
        flushPending()
        // Nor may a login offered for the last one. `restarting` means the same field is still
        // focused -- the app changed something about it -- and the chips on screen are still
        // that field's, so only a genuinely new field clears them.
        if (!restarting) clearInlineSuggestions()
        if (!restarting) anchorNeedsComposition = false
        if (!restarting) linePitch = 0f
        editorSelStart = info?.initialSelStart ?: -1
        editorSelEnd = info?.initialSelEnd ?: -1
        tapDecodingAllowed = allowsTapDecoding(info)
        shift = ShiftState.OFF
        syncChineseMode()
        applyLayout()
        loadEmojiIndex()
        loadGlideEngine()
        refreshCandidates()
    }

    /**
     * The globe key, or the system switcher, chose another language.
     *
     * The composing buffer deliberately survives this: see [syncChineseMode].
     */
    override fun onCurrentInputMethodSubtypeChanged(newSubtype: android.view.inputmethod.InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        syncChineseMode()
        applyLayout()
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
        // The caret has moved for a reason this keyboard may not have had anything to do with:
        // a tap into the middle of the text, an autofill, the app's own editing. A word being
        // held is only meaningful where it was typed, so anything that is not "the caret is
        // sitting at the end of the region we are composing" ends it. Writing composing text
        // reports exactly that shape, so the ordinary case does not trip this.
        if (!pending.isEmpty &&
            (candidatesStart < 0 || newSelStart != newSelEnd || newSelEnd != candidatesEnd)
        ) {
            flushPending()
        }
        editorSelStart = newSelStart
        editorSelEnd = newSelEnd
        // Such an app reports nothing on its own when the caret moves; every move has to be
        // followed by asking.
        if (trackpadActive && composingForAnchor) {
            currentInputConnection?.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
            )
        }
        if (!trackpadActive) refreshCandidates()
    }

    private var plane: Layout = IosLayouts.QWERTY_LOWER

    private fun applyLayout() {
        val target = when {
            plane.id.startsWith("en_qwerty") && shift != ShiftState.OFF -> IosLayouts.QWERTY_UPPER
            plane.id.startsWith("en_qwerty") -> IosLayouts.QWERTY_LOWER
            else -> plane
        }
        // The globe key's menu is device state, not layout: it is whatever the user has enabled
        // right now. Injected here because this is already the one funnel every layout change
        // goes through -- focus, shift, plane switch and subtype change all end up here -- so a
        // language enabled in Settings shows up in the menu at the next keystroke, and the
        // check mark against the current language cannot go stale.
        keyboardView?.layout = LanguageMenu.attach(target, LanguageMenu.entries(this))
    }

    /**
     * A language was slid to and released on the globe key's menu.
     *
     * The composing buffer is flushed first, exactly as [handleSpecialKey] does for a tap on the
     * globe: the letters belong to the user and switching language must not destroy them
     * (requirement 10), but they were typed for the language being left, so they are settled into
     * the field rather than carried into a decoder that will read them differently.
     *
     * A failed switch falls back to the ring. [LanguageMenu.switchTo] can fail for reasons that
     * are nobody's fault -- the window token is gone, or the subtype was disabled in Settings
     * between the menu opening and the finger lifting -- and cycling to the next language is
     * closer to what was asked for than doing nothing at all.
     */
    private fun switchLanguage(languageId: String) {
        flushPending()

        // Switching within this keyboard is the common case and has an API meant for exactly it:
        // InputMethodService.switchInputMethod(id, subtype) needs no window token and none of
        // the permissions the InputMethodManager route has grown. Tried first rather than as a
        // fallback because it is the one path guaranteed to keep working.
        val subtype = LanguageMenu.ownSubtypeFor(this, languageId)
        if (subtype != null) {
            val id = LanguageMenu.imeIdOf(languageId)
            if (runCatching { switchInputMethod(id, subtype); true }.getOrDefault(false)) return
        }

        // Leaving for another keyboard, or the call above being refused. Needs the window token.
        val token = window?.window?.attributes?.token
        if (LanguageMenu.switchTo(this, token, languageId)) return

        // Nothing worked. Cycling is not what was asked for, but it is the one thing an IME can
        // always do, and it is nearer the request than leaving the language unchanged.
        switchToNextInputMethod(false)
    }

    private fun handleOutputs(outputs: List<GestureOutput>) {
        if (DEBUG_GESTURES) outputs.forEach { android.util.Log.d(TAG, "gesture: $it") }
        typedThisGesture.setLength(0)
        deletedThisGesture = 0
        outputs.forEach { out ->
            when (out) {
                is GestureOutput.CommitPrimary -> if (!pendLetter(out)) commit(out.text)
                is GestureOutput.CommitSecondary -> commit(out.text)
                is GestureOutput.CommitAccent -> commit(out.text)
                is GestureOutput.CommitAction -> runEditAction(out.action)
                is GestureOutput.CommitLanguage -> switchLanguage(out.languageId)
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
                GestureOutput.DismissKeyboard -> dismissKeyboard()
                // The board has already moved: KeyboardView owns the state because it owns the
                // geometry. Nothing to do here but settle the word the flick interrupted, since
                // the space bar that was pressed will now not be typing one.
                is GestureOutput.SquashFlick -> flushPending()
                is GestureOutput.SpecialKey -> handleSpecialKey(out.type, out.keyId)
                is GestureOutput.GlideCompleted -> commitGlide(out)
                // Kept only while the gesture lab is asking for something; a no-op otherwise.
                is GestureOutput.GestureCaptured -> GestureCapture.onGesture(
                    context = this,
                    trace = out.trace,
                    typed = typedThisGesture.toString(),
                    deleted = deletedThisGesture,
                )
                else -> Unit
            }
        }
    }

    private fun commit(text: String) {
        // Space with a pinyin buffer open chooses the first candidate instead of typing a
        // space. This is the convention every Chinese IME shares, and it is what lets a whole
        // sentence be typed without ever looking at the bar: letters, space, letters, space.
        // The space itself is swallowed -- Chinese is not written with spaces between words.
        if (chineseMode && text == " ") {
            val session = pinyin
            if (session != null && !session.isEmpty) {
                flushPinyin(takeFirstCandidate = true)
                return
            }
        }
        // Whatever this is, it is not another letter of the word being held, so that word is
        // over. Settling it first keeps the two edits in order: the word, then the thing that
        // ended it.
        flushPending()
        currentInputConnection?.commitText(text, 1)
        typed(text)
        // iOS one-shot shift: the next letter is capitalised, then shift releases.
        if (shift == ShiftState.ONE_SHOT && text.isNotBlank()) {
            shift = ShiftState.OFF
            applyLayout()
        }
        returnToLetters(text)
        refreshCandidates()
    }

    /**
     * Goes back to the letters after a character that all but guarantees one is coming, which is
     * the iOS behaviour a thumb stops noticing it relies on.
     *
     * Only from the number and symbol planes, and only for the characters [TypingHabits] names.
     * On the letter plane there is nothing to go back to, and switching would be a no-op that
     * still had to be reasoned about every time this ran.
     */
    private fun returnToLetters(text: String) {
        if (plane.id.startsWith("en_qwerty")) return
        if (!TypingHabits.returnsToLetters(text)) return
        switchPlane("mode_abc")
    }

    // --- tap decoding ---------------------------------------------------------------------

    /**
     * Takes a letter tap into the word being held, and shows what that word now reads as.
     *
     * Returns false for anything that is not a letter of a word -- the space bar, a field that
     * has opted out, a build whose lexicon has not finished loading -- leaving the caller to
     * commit it the old way. Letter keys are identified by their id rather than by the text they
     * would type, because that text is "Q" under shift and the decoder works in lowercase.
     *
     * The word goes into the field as composing text rather than being withheld. A word the user
     * cannot see until it is finished would be a far worse trade than the one this is making:
     * the letters are there, in order, from the moment they are typed, and the only thing
     * deferred is the keyboard's final opinion about which letters they were.
     */
    private fun pendLetter(out: GestureOutput.CommitPrimary): Boolean {
        // Chinese takes the letter first: in a Chinese subtype a letter is pinyin, not a word to
        // be re-read against an English lexicon. The tap decoder and the glide engine are both
        // English-only by construction, so they are simply not consulted here.
        if (chineseMode && pendPinyin(out)) return true
        if (!tapDecodingAllowed) return false
        val decoder = tapDecoder ?: return false
        val geometry = keyboardView?.currentGeometry ?: return false
        val ic = currentInputConnection ?: return false
        val id = out.keyId
        if (id.length != 1 || id[0] !in 'a'..'z') return false
        if (pending.length >= TapDecoder.MAX_TAPS) flushPending()

        pending.add(out.x, out.y, id[0], upper = shift != ShiftState.OFF)
        // iOS one-shot shift: the next letter is capitalised, then shift releases.
        if (shift == ShiftState.ONE_SHOT) {
            shift = ShiftState.OFF
            applyLayout()
        }

        val text = pending.textFor(decoder.read(pending.taps, geometry))
        if (pending.hasChanged(text)) {
            // What the field gained or lost, which for a re-reading is both: the composing region
            // is rewritten whole, so the honest account of this gesture is that it removed the
            // old reading and put back a new one.
            deleted(pending.shownLength)
            typed(text)
            pending.markShown(text)
            ic.setComposingText(text, 1)
        }
        refreshCandidates()
        return true
    }

    // --- chinese input ----------------------------------------------------------------------

    /**
     * Takes a letter into the pinyin buffer and shows the syllables as composing text.
     *
     * The letters go into the field rather than being withheld, the same bargain [pendLetter]
     * makes for English: what is on screen is always what was typed, and only the keyboard's
     * opinion about which characters they mean is deferred to the candidate bar.
     *
     * Returns false for anything that is not a pinyin letter -- a digit, a symbol, the space bar
     * -- so the caller commits it the ordinary way.
     */
    private fun pendPinyin(out: GestureOutput.CommitPrimary): Boolean {
        val session = pinyin ?: return false
        if (!session.isReady) return false
        val id = out.keyId
        if (id.length != 1 || id[0] !in 'a'..'z') return false
        val ic = currentInputConnection ?: return false

        if (!session.append(id[0])) return false
        // Shift has no meaning for pinyin, but leaving it armed would capitalise the next Latin
        // letter typed after the Chinese word ends, which nobody asked for.
        if (shift == ShiftState.ONE_SHOT) {
            shift = ShiftState.OFF
            applyLayout()
        }
        val composing = session.composing
        deleted(composing.length - 1)
        typed(composing)
        ic.setComposingText(composing, 1)
        refreshCandidates()
        return true
    }

    /**
     * Commits the chosen Chinese candidate.
     *
     * A candidate may cover only part of what was typed -- picking 北京 out of `beijingdaxue` --
     * so what remains goes straight back as composing text and the bar re-offers for it. That is
     * what makes entering a long phrase a few words at a time feel continuous.
     */
    private fun commitPinyin(position: Int): Boolean {
        val session = pinyin ?: return false
        val result = session.commit(position) ?: return false
        val ic = currentInputConnection ?: return false
        ic.beginBatchEdit()
        // The composing region currently holds the raw letters; committing over it replaces them
        // with the characters, which is exactly the edit the user asked for.
        ic.setComposingText(result.text, 1)
        ic.finishComposingText()
        typed(result.text)
        if (result.remaining.isNotEmpty()) ic.setComposingText(result.remaining, 1)
        ic.endBatchEdit()
        refreshCandidates()
        return true
    }

    /**
     * Backspace inside a pinyin buffer removes one letter, not one character of the field.
     *
     * Returns false once the buffer is empty so the ordinary backspace takes over and deletes
     * text that is already committed.
     */
    private fun backspacePinyin(): Boolean {
        val session = pinyin ?: return false
        if (session.isEmpty) return false
        session.backspace()
        val ic = currentInputConnection ?: return false
        if (session.isEmpty) ic.finishComposingText() else ic.setComposingText(session.composing, 1)
        refreshCandidates()
        return true
    }

    /**
     * Settles a pinyin buffer that is still open.
     *
     * Space takes the first candidate, which is the convention every Chinese IME shares and the
     * reason a sentence can be typed without looking at the bar. Anything else that ends the
     * word -- moving the caret, changing field, pressing return -- commits the letters as they
     * stand rather than guessing, because those are not acts of choosing a word.
     */
    private fun flushPinyin(takeFirstCandidate: Boolean) {
        val session = pinyin ?: return
        if (session.isEmpty) return
        if (takeFirstCandidate && commitPinyin(0)) return
        val letters = session.commitRaw()
        val ic = currentInputConnection ?: return
        ic.setComposingText(letters, 1)
        ic.finishComposingText()
        refreshCandidates()
    }

    /**
     * Whether a field is one where holding a word open is appropriate.
     *
     * **A password, and nothing else, ever.** A password is not a word, and a composing region
     * holding one is a way to leak it into the field's own autofill. That is a real reason and
     * it applies to exactly three variations.
     *
     * Everything else that used to be excluded was excluded on a guess about content, and every
     * guess was wrong in the same direction -- it withheld the feature from ordinary English
     * typing to protect against a harm that the design already prevents.
     *
     * `NO_SUGGESTIONS` was the worst of them. It means "the input method does not need to
     * display any dictionary-based candidates": it is about a candidates UI, and this keyboard
     * has no English candidates UI to suppress, since the suggestion bar shows emoji and Chinese
     * on purpose. Honouring it answered a question nobody asked by switching off a feature the
     * flag never mentioned -- and apps set it constantly and carelessly, on search boxes, chat
     * fields and notes, every one of them prose where re-reading a mis-hit tap is the whole
     * point. It also meant the gesture lab, whose field carried it, collected 2670 gestures with
     * the decoder switched off, so nothing in the bank had ever exercised it.
     *
     * Filters and emails and URLs were the same mistake in quieter clothes. A filter field is
     * ordinary text. An email's local part is usually a name, and a name typed accurately is
     * *pinned* -- the touch evidence against every other letter exceeds anything the lexicon can
     * say, so it cannot be revised. What the exclusion actually removed was the recovery of a
     * sloppy tap, which in a URL is no more welcome than anywhere else.
     */
    private fun allowsTapDecoding(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            -> false
            else -> true
        }
    }

    /**
     * Settles the word being held: whatever it reads as now becomes ordinary text.
     *
     * Every way out of a pending word is this one. There is no path that keeps a half-decided
     * word across a caret move, a focus change or a delete, which is what makes it safe to hold
     * one at all next to a trackpad that can put the caret anywhere.
     */
    private fun flushPending() {
        // A pinyin buffer is a held word too, and every caller of this means "settle whatever is
        // open before doing something else to the field". Committed as letters rather than as
        // the first candidate: a caret move or a focus change is not a choice of word, and
        // guessing one would put characters in the field that nobody selected.
        flushPinyin(takeFirstCandidate = false)
        if (pending.isEmpty) return
        pending.clear()
        currentInputConnection?.finishComposingText()
    }

    /**
     * Types the word a glide drew, and returns it so the gesture bank can record what was
     * decoded alongside the path that decoded to it.
     *
     * The space goes in *front* of the word rather than after it. Both conventions put one space
     * between two glided words; only this one leaves the caret against the last letter, where
     * backspace deletes a character of the word just typed instead of an invisible space, and
     * where the emoji bar is still looking at a word rather than at nothing.
     */
    private fun commitGlide(completed: GestureOutput.GlideCompleted): String? {
        // A glide writes a whole word of its own; the tapped one before it is finished.
        flushPending()
        val decoder = glide ?: return null
        val view = keyboardView ?: return null
        val word = decoder.decode(completed.path, view.currentGeometry).firstOrNull()
            ?: return null
        val ic = currentInputConnection ?: return null

        val cased = if (shift == ShiftState.OFF) word else word.replaceFirstChar { it.uppercase() }
        val before = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
        val needsSpace = before != null && !before.isWhitespace() && before !in OPENERS
        val written = if (needsSpace) " $cased" else cased
        ic.beginBatchEdit()
        ic.commitText(written, 1)
        ic.endBatchEdit()
        typed(written)
        if (shift == ShiftState.ONE_SHOT) {
            shift = ShiftState.OFF
            applyLayout()
        }
        refreshCandidates()
        return cased
    }

    /**
     * Runs an edit action chosen from a long-press popup.
     *
     * Everything goes through [InputConnection.performContextMenuAction] with the same
     * `android.R.id.*` the text selection toolbar uses, rather than through synthesised ctrl+key
     * events. Both reach the same handlers in a standard [android.widget.TextView], but only this
     * one reaches them in a field that is *not* one: a WebView, a Compose text field or a game's
     * own editor sees the menu action and does something sensible with it, where a ctrl+V it
     * never registered a shortcut for is silently dropped. It is also the honest description of
     * what the user asked for -- "paste" -- instead of a keystroke that usually means paste.
     *
     * The composing region is settled first, always. A held word is the keyboard's private
     * opinion about letters that are already in the field, and every one of these actions is
     * about to read, replace or move that text: copying with a composing region live copies the
     * underline along with the words in some fields, and undo unwinds *through* it in others,
     * leaving the keyboard holding a word the field no longer has.
     */
    private fun runEditAction(action: EditAction) {
        flushPending()
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        val id = when (action) {
            EditAction.SELECT_ALL -> android.R.id.selectAll
            EditAction.CUT -> android.R.id.cut
            EditAction.COPY -> android.R.id.copy
            EditAction.PASTE -> android.R.id.paste
            EditAction.UNDO -> android.R.id.undo
            EditAction.REDO -> android.R.id.redo
        }
        ic.performContextMenuAction(id)
        // A one-shot shift that was armed before the hold has nothing left to capitalise: the
        // gesture ended in an edit, not a letter. Leaving it armed would capitalise whatever was
        // typed next, which is the sort of stray capital nobody can trace back to its cause.
        if (shift == ShiftState.ONE_SHOT) {
            shift = ShiftState.OFF
            applyLayout()
        }
        refreshCandidates()
    }

    private fun handleSpecialKey(type: KeyType, keyId: String) {
        // Shift alone does not end a word -- it capitalises the next letter of one, and the
        // upper and lower layouts put their keys in identical places, so a word may span it.
        // Everything else here either moves the caret, changes the field or changes the plane.
        if (type != KeyType.SHIFT) flushPending()
        when (type) {
            KeyType.SHIFT -> toggleShift()
            KeyType.BACKSPACE -> backspace()
            KeyType.MODE_SWITCH -> switchPlane(keyId)
            KeyType.GLOBE -> switchToNextInputMethod(false)
            KeyType.MIC -> toggleDictation()
            KeyType.RETURN -> pressReturn()
            else -> Unit
        }
    }

    /**
     * Return either fires the field's editor action or sends a real Enter key. It never commits
     * a newline as text, which is what it used to do.
     *
     * Both halves matter in a browser. The address bar declares IME_ACTION_GO and loads nothing
     * until [android.view.inputmethod.InputConnection.performEditorAction] tells it to, and a
     * text box inside a page has no editor action at all but submits its form on a key going
     * down -- neither of them so much as notices text that merely appears in it. See
     * [ReturnKey].
     */
    private fun pressReturn() {
        val ic = currentInputConnection ?: return
        val action = ReturnKey.actionFor(currentInputEditorInfo?.imeOptions ?: 0)
        if (action != null) ic.performEditorAction(action) else sendDownUpKeyEvents(KEYCODE_ENTER)
        refreshCandidates()
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

    /**
     * Goes to the plane the key that was pressed names, rather than to the next one in a ring.
     *
     * There are two mode keys visible at once on the number and symbol planes -- "#+=" or "123"
     * on the third row and "ABC" on the bottom -- and a ring cannot tell them apart, so pressing
     * ABC from the numbers plane used to land on symbols and pressing it again came back to
     * letters. The key ids say where each one goes, and they are the only thing that does.
     */
    private fun switchPlane(keyId: String) {
        plane = IosLayouts.planeFor(keyId) ?: return
        shift = ShiftState.OFF
        applyLayout()
    }

    private fun backspace() {
        // Inside a pinyin buffer, backspace un-types a letter of the syllable being spelled --
        // it must not settle the buffer first, or the first backspace would commit the very
        // characters the user is trying to correct. Handled before flushPending for that reason.
        if (chineseMode && backspacePinyin()) return
        // Also reached by the held-backspace repeat, which never passes through
        // handleSpecialKey. A delete against composing text is the one edit that would make the
        // held word and the field disagree, so it is settled first, every time.
        flushPending()
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) {
            // One press removes one *visible* character, which is not one code unit: an emoji
            // is a surrogate pair at least and a ZWJ family is eight units. Deleting a fixed 1
            // left half a surrogate behind -- the glyph appeared to survive the press, or turned
            // into tofu. See [GraphemeCluster].
            val before = ic.getTextBeforeCursor(GRAPHEME_LOOKBEHIND, 0)
            // A field that refuses to report its text gets the old behaviour; one code unit is
            // the only safe guess when the text is unknown, and it is what happened before.
            val units = if (before.isNullOrEmpty()) 1 else GraphemeCluster.lastClusterLength(before)
            ic.deleteSurroundingText(units, 0)
            deleted(units)
        } else {
            deleted(selected.length)
            ic.commitText("", 1)
        }
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
        flushPending()
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
    /**
     * Puts the keyboard away, as a flick down the space bar asks.
     *
     * Deliberately only the request. Settling the word in progress is [onFinishInputView]'s job
     * and it already does it -- glide, pending letters, selection, trackpad, dictation -- for
     * every other way the keyboard goes away, and the system calls it for this one too. Doing
     * any of it again here would be a second teardown path to keep in step with the first, and
     * the composing text would be flushed twice.
     *
     * [requestHideSelf] rather than hiding the window directly: it is the IME's own way of
     * saying the user asked for this, so the system puts the keyboard back when the field next
     * takes focus rather than treating it as dismissed for good.
     */
    private fun dismissKeyboard() {
        requestHideSelf(0)
    }

    /** Which way the board is squashed, remembered across input views. See [KeyboardView.squash]. */
    private fun squashPrefs() = getSharedPreferences("keyboard", Context.MODE_PRIVATE)

    private fun loadSquash(): Squash {
        val name = squashPrefs().getString(PREF_SQUASH, null) ?: return Squash.NONE
        // Unknown values -- a downgrade, a hand-edited file -- fall back to the full-width board
        // rather than throwing. There is no state here worth crashing a keyboard over.
        return runCatching { Squash.valueOf(name) }.getOrDefault(Squash.NONE)
    }

    private fun saveSquash(squash: Squash) {
        squashPrefs().edit().putString(PREF_SQUASH, squash.name).apply()
    }

    private fun bulkDelete() {
        flushPending()
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

    /**
     * Loads the glide engine. Called alongside the emoji index, and off the main thread for a
     * stronger version of the same reason: half a megabyte of words to parse, and, when it is
     * present, ten megabytes of models to stage out of the APK and load.
     */
    private fun loadGlideEngine() {
        if (glide != null || glideLoading) return
        glideLoading = true
        Thread {
            val lexicon = runCatching { assets.open(LEXICON_ASSET).use(Lexicon::load) }
                .onFailure { android.util.Log.w(TAG, "glide lexicon failed to load", it) }
                .getOrNull()
            // The lexicon is what the beam search's dictionary is generated from, and it is
            // also the language model the tap decoder reads. Building the index here rather than
            // on its own thread is deliberate: it is a sort of the same 40,000 strings that were
            // just parsed, and doing it twice over would be two loads of the asset.
            val engine: GlideEngine? = lexicon?.let { FutoSwipe.open(applicationContext, it) }
            val index = lexicon?.let { TapDecoder(WordIndex.of(it)) }
            handler.post {
                glide = engine
                tapDecoder = index
                // Kept for the suggestion bar, which weighs "these letters are an English word"
                // against reading them as pinyin. Already in memory for the decoders, so this
                // costs a reference rather than a second copy.
                englishWords = lexicon
                glideLoading = false
                // The bar may already be showing a word whose ranking this changes.
                if (lexicon != null) refreshCandidates()
                if (engine == null) {
                    // Not a silent degradation: with nothing to decode a glide, gliding types
                    // nothing at all, and the reason belongs somewhere findable.
                    android.util.Log.w(TAG, "no glide engine -- run tools/fetch_swipe_runtime.sh")
                } else {
                    android.util.Log.i(TAG, "glide engine: ${engine.name}")
                }
            }
        }.start()
    }

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
        keyboardView?.candidateKinds = emptyList()
        candidateReplaceLength = 0
        candidateConsumes = emptyList()
    }

    /**
     * Offers suggestions for the word the caret sits at the end of, and a default set when there
     * is no such word.
     *
     * In English the bar is not an emoji bar: it holds emoji *and* Chinese, ranked against each
     * other by [UnifiedCandidates] on one log-probability scale. The letters `niuroumian` name
     * no emoji and read as a perfectly good Chinese word, so the bar fills with Chinese;
     * `happy` names several emoji and is not pinyin at all, so it fills with emoji; `ha` is
     * genuinely both and shows both. None of that is a rule here -- it is what comparing the
     * scores produces, and this function only supplies the two candidate sets and sorts them.
     *
     * The two-word form is tried first and the first form that matches wins, so "thumbs up"
     * beats "up" where both would match, and the length that produced the match is remembered:
     * that is exactly what a tapped suggestion replaces.
     *
     * Falling back to [EmojiIndex.DEFAULTS] rather than clearing is what keeps the bar populated
     * at all times. The one case that still clears is a *selection*: the strip's whole gesture is
     * to replace the text at the caret, and there is no sane reading of tapping a suggestion
     * while a range is highlighted.
     */
    private fun refreshCandidates() {
        val view = keyboardView ?: return
        // In Chinese the bar belongs to the pinyin buffer, not to the word behind the caret:
        // it shows what the letters being typed could mean. With nothing composing there is
        // nothing to offer, and the bar is left empty rather than filled with emoji, which
        // would put an English feature in front of someone writing Chinese.
        if (chineseMode) {
            val session = pinyin
            val texts = if (session == null || session.isEmpty) {
                emptyList()
            } else {
                session.candidates()
            }
            view.candidates = texts
            // Kinds are supplied here even though every entry is Chinese, because the kinds are
            // what select the text renderer -- see [KeyboardView.textStrip]. Leaving them empty
            // made the CJK bar depend on a second switch (`chineseMode`) that had to agree with
            // this one, which is exactly the disagreement that made a Chinese-only bar look
            // unlike the mixed bar showing the same characters.
            view.candidateKinds = texts.map { UnifiedCandidates.Kind.CHINESE }
            candidateReplaceLength = 0
            return
        }
        val index = emoji
        val ic = currentInputConnection
        if (index == null || ic == null) return clearCandidates()
        if (!ic.getSelectedText(0).isNullOrEmpty()) return clearCandidates()

        val before = ic.getTextBeforeCursor(TypedWord.LOOKBEHIND, 0)
            ?: return offerDefaultCandidates()
        for (word in TypedWord.endingAt(before)) {
            val ranked = rankedFor(word.query)
            if (ranked.isNotEmpty()) {
                view.candidates = ranked.map { it.text }
                view.candidateKinds = ranked.map { it.kind }
                // A Chinese suggestion may explain only part of the letters -- 牛肉 out of
                // `niuroumian` -- so what a tap replaces is carried per suggestion rather than
                // shared. Emoji always consume the whole matched word, as they always did.
                candidateConsumes = ranked.map {
                    if (it.kind == UnifiedCandidates.Kind.CHINESE) it.consumes else word.length
                }
                candidateReplaceLength = word.length
                return
            }
        }
        offerDefaultCandidates()
    }

    /**
     * The ranked bar for one candidate word: emoji and Chinese on a single scale.
     *
     * The Chinese half is only asked for when the dictionary is already in memory. It is loaded
     * in the background from [onStartInput] like the emoji index, so this is a "not yet" rather
     * than a "never" -- and the bar refreshes when it lands.
     *
     * It is also only asked for when the query *could* be pinyin. [TypedWord.endingAt] offers a
     * two-word form first, for emoji named like "thumbs up", and a pinyin syllable never spans a
     * space -- so a query containing one has no Chinese reading and the decoder would do sixteen
     * Viterbi passes over the longest input on the bar to prove it. That cost landed on exactly
     * the keystrokes that felt slowest: while emoji still matched, the cheap single-word form
     * answered first and the two-word form was never reached, but once the letters read only as
     * Chinese every keystroke paid for the full decode twice.
     */
    private fun rankedFor(query: String): List<UnifiedCandidates.Suggestion> {
        val index = emoji ?: return emptyList()
        val hits = index.search(query)
        val session = pinyin
        val chinese = if (session != null && session.isReady && !query.contains(' ')) {
            session.scoredFor(query)
        } else {
            emptyList()
        }
        if (hits.isEmpty() && chinese.isEmpty()) return emptyList()
        return UnifiedCandidates.rank(
            query = query,
            emojiHits = hits,
            chinese = chinese,
            englishScore = englishWords?.logProbability(query) ?: UnifiedCandidates.NOT_ENGLISH,
        )
    }

    /**
     * Fills the bar when no word is being typed, so the strip is never blank.
     *
     * The replace length is zero, and that is the entire difference between these and a matched
     * emoji: a suggestion for "piz" stands *for* those letters and consumes them, while these
     * stand for nothing on screen and must insert at the caret. Sharing [commitCandidate] is
     * safe only because it deletes `candidateReplaceLength` characters and no more -- so the
     * field has to be cleared here rather than left at whatever the last matched word set it to,
     * or tapping a default emoji would eat the word behind the caret.
     */
    private fun offerDefaultCandidates() {
        keyboardView?.candidates = EmojiIndex.DEFAULTS
        keyboardView?.candidateKinds = emptyList()
        candidateReplaceLength = 0
        candidateConsumes = emptyList()
    }

    /** Replaces the typed word with the emoji, the way the iOS emoji suggestion does. */
    private fun commitCandidate(position: Int) {
        // A Chinese candidate replaces the composing pinyin, which is the keyboard's own buffer
        // rather than a run of characters counted off the field -- and it may consume only part
        // of it. Handled before flushPending, which would otherwise settle the letters as Latin
        // text and leave the candidate with nothing to replace.
        if (chineseMode && commitPinyin(position)) return
        // The suggestion replaces a run of characters counted off the field, so the word being
        // held has to be in the field and not in this keyboard before that count is taken.
        flushPending()
        val text = keyboardView?.candidates?.getOrNull(position) ?: return
        val ic = currentInputConnection ?: return
        // A Chinese suggestion may stand for only part of the word: picking 牛肉 out of
        // `niuroumian` must eat exactly `niurou` and leave `mian` for the next suggestion to
        // answer. Emoji, and every entry on a uniform bar, still replace the whole word.
        val replace = candidateConsumes.getOrNull(position) ?: candidateReplaceLength
        ic.beginBatchEdit()
        if (replace > 0) {
            ic.deleteSurroundingText(replace, 0)
            deleted(replace)
        }
        ic.commitText(text, 1)
        typed(text)
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
        com.offlinekeyboard.ime.capture.TouchTrace.log(this, "TP $message")
    }

    private fun startTrackpad() {
        // The caret is about to go somewhere else entirely. Settle the word where it was typed.
        flushPending()
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
        trace("requestCursorUpdates -> $ok")
        markerX = Float.NaN
        markerCenterY = Float.NaN
        caretX = Float.NaN
        caretTop = Float.NaN
        pendingHorizontal = 0
        pendingVertical = 0
        verticalStuckDir = 0
        if (anchorNeedsComposition) holdCompositionForAnchor()
        else handler.postDelayed(anchorProbe, ANCHOR_PROBE_MS)
        updateIndicator()
    }

    /**
     * Marks one character as composing, without changing any text, so that an app which only
     * reports its caret during a composition reports it. The pending word was flushed when the
     * trackpad started, so no composition of ours is displaced.
     */
    private fun holdCompositionForAnchor() {
        val ic = currentInputConnection ?: return
        val caret = editorSelStart.takeIf { it >= 0 } ?: return
        // Any character will do: the report carries the caret position regardless of where the
        // composition is. One that exists is all that matters.
        val (from, to) = when {
            caret > 0 -> 0 to 1
            ic.getTextAfterCursor(1, 0)?.isNotEmpty() == true -> caret to caret + 1
            else -> return // an empty field has nowhere to move the caret to anyway
        }
        ic.setComposingRegion(from, to)
        composingForAnchor = true
        ic.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
        )
    }

    private fun stopTrackpad() {
        trace("=== TRACKPAD END ===")
        trackpadActive = false
        scrollPinned = false
        handler.removeCallbacks(edgeScrollTick)
        handler.removeCallbacks(anchorProbe)
        if (composingForAnchor) {
            composingForAnchor = false
            currentInputConnection?.finishComposingText()
        }
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
        val lh = pitch().takeIf { it > 1f } ?: return false
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
        trace(
            "ANCHOR sel=[${info.selectionStart},${info.selectionEnd}] " +
                "comp=[${info.composingTextStart},${info.composingText?.length}] " +
                "insH=${info.insertionMarkerHorizontal} insT=${info.insertionMarkerTop} " +
                "insB=${info.insertionMarkerBottom} " +
                "cb0=${info.getCharacterBounds(info.composingTextStart)}",
        )
        val point = caretPoint(info) ?: return
        // Firefox fills in the caret's position but leaves the selection at -1.
        val selStart = info.selectionStart.takeIf { it >= 0 } ?: editorSelStart
        val selEnd = info.selectionEnd.takeIf { it >= 0 } ?: editorSelEnd

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
        if (selStart == selEnd) caretOffset = selStart

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
                val movedInText = selStart != pendingFromOffset
                val sameRow = abs(point[1] - previousTop) < 1f
                // Firefox, like macOS, answers up on the first row by going to the start of the
                // text and down on the last by going to the end. That moves the caret in the text
                // but not between rows, which is exactly what a scroll looks like -- and taking
                // it for one sent a repeating scroll of arrows into a field with nowhere to go.
                // Landing on the very end of the text on the same row means the edge, not a scroll.
                val hitTextEdge = movedInText && sameRow && (
                    (pendingVertical < 0 && selStart == 0) ||
                        (pendingVertical > 0 &&
                            currentInputConnection?.getTextAfterCursor(1, 0).isNullOrEmpty())
                    )
                verticalStuckDir = if (movedInText && !hitTextEdge) 0 else if (pendingVertical > 0) 1 else -1
                // Moved in the text but not on screen: the editor is scrolling underneath a
                // pinned caret. Its reported position will not close the error, so vertical
                // movement has to be handed to the rate-limited scroll instead of chased.
                scrollPinned = movedInText && sameRow && !hitTextEdge
                if (movedInText && pendingHorizontal == 0) {
                    val pitch = abs(point[1] - previousTop) / abs(pendingVertical)
                    if (pitch > lineHeight * 0.8f && pitch < lineHeight * 3f) linePitch = pitch
                }
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

        lastSelStart = selStart
        lastSelEnd = selEnd

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
        val lines = (markerCenterY - (caretTop + lh / 2f)) / pitch()
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
        val lines = ((markerCenterY - (caretTop + lh / 2f)) / pitch()).roundToInt().coerceIn(-12, 12)
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

    /** Line-to-line distance: measured once a vertical step has shown it, the caret's height until then. */
    private fun pitch(): Float = linePitch.takeIf { it > 1f } ?: lineHeight

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
