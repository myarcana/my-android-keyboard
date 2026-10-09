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
import android.util.Log
import android.view.Gravity
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
import com.offlinekeyboard.ime.cursor.PreciseCursor
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import com.offlinekeyboard.ime.autofill.InlineAutofill
import com.offlinekeyboard.ime.autofill.InlineSuggestionStrip
import com.offlinekeyboard.ime.asr.CommandMerge
import com.offlinekeyboard.ime.asr.Dictation
import com.offlinekeyboard.ime.asr.DictationSpacing
import com.offlinekeyboard.ime.asr.MicrophonePermissionActivity
import com.offlinekeyboard.ime.asr.SpokenPunctuation
import com.offlinekeyboard.ime.candidates.EmojiIndex
import com.offlinekeyboard.ime.candidates.TypedWord
import com.offlinekeyboard.ime.candidates.UnifiedCandidates
import com.offlinekeyboard.ime.capture.GestureCapture
import com.offlinekeyboard.ime.text.DeletionHistory
import com.offlinekeyboard.ime.text.FieldState
import com.offlinekeyboard.ime.text.GraphemeCluster
import com.offlinekeyboard.ime.text.HistoryField
import com.offlinekeyboard.ime.text.WordBoundary
import com.offlinekeyboard.ime.gesture.FlickPrior
import com.offlinekeyboard.ime.gesture.WordStarts
import com.offlinekeyboard.ime.gesture.GestureOutput
import com.offlinekeyboard.ime.glide.FutoSwipe
import com.offlinekeyboard.ime.glide.GlideContext
import com.offlinekeyboard.ime.glide.GlideEngine
import com.offlinekeyboard.ime.glide.GlideRejections
import com.offlinekeyboard.ime.glide.GlideSpacing
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
 * Held backspace. It deletes characters at first, then whole words -- the same acceleration
 * iOS has, and the reason it exists is that a fixed character rate is either too slow to clear
 * a sentence or too fast to stop on the word you meant.
 */
private const val BACKSPACE_CHAR_INTERVAL_MS = 55L
private const val BACKSPACE_WORD_INTERVAL_MS = 140L
/** Repeats at the character rate before words take over: about a second of holding. */
private const val BACKSPACE_REPEATS_BEFORE_WORDS = 18

/**
 * Text read back to size a single backspace. A grapheme cluster is bounded in practice -- the
 * longest emoji in common use is a seven-person ZWJ sequence -- and this leaves ample room for
 * one while keeping the read cheap enough to do on every repeat of a held backspace.
 */
private const val GRAPHEME_LOOKBEHIND = 32

/**
 * Text read back to size a swipe-up line delete.
 *
 * Far larger than the word and grapheme lookbehinds because a "line" here is a run of text
 * between newlines, not a visual row, and in a field that soft-wraps -- a message box, a note --
 * a paragraph someone typed in one go can run to several hundred characters with no break in
 * it. Reading short would silently clear only part of the line and leave the rest, which looks
 * like the gesture misfired.
 *
 * Bounded rather than unbounded because `getTextBeforeCursor` copies across an IPC boundary and
 * the editor is free to be slow about it. 1024 covers any line a person types by hand; beyond
 * that the flick clears what it can reach and a second flick takes the rest.
 */
private const val LINE_LOOKBEHIND = 1024

/**
 * Text read either side of the caret to tell whether the field is still the one a deletion was
 * recorded against. See [com.offlinekeyboard.ime.text.DeletionHistory].
 *
 * It only has to notice a change, not describe one, and the caret offsets carry most of that on
 * their own, since any edit that changes the length moves them. The window is there for the
 * edits that keep the length the same, such as one word replaced by another of equal length.
 * Those land next to the caret, so a short window catches them.
 */
private const val HISTORY_WINDOW = 256

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
    /** Loaded off the main thread: parsing 70,000 words is time the keyboard cannot wait for. */
    private var glide: GlideEngine? = null
    private var glideLoading = false

    /**
     * Words a glide has had rejected, so re-gliding in the same place offers the next candidate.
     *
     * Lives here rather than inside the engine because the evidence it runs on is not available
     * there: what the decoder returned is one half, and what the user then did to the field is
     * the other. See [GlideRejections].
     */
    private val glideRejections = GlideRejections()

    /**
     * Where the last glided word ended, while the next keypress could still be the start of a
     * new word after it; null otherwise. -1 when the field would not say where the caret was.
     *
     * A glide leaves the caret against its last letter rather than after a space (see
     * [commitGlide]), so a tapped letter that follows it needs the space put in first -- see
     * [spaceAfterGlide]. Cleared by anything that is not merely on the way to that letter.
     */
    private var glideEnd: Int? = null

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

    /**
     * The suggestions behind the bar, parallel to it; empty for the default emoji.
     *
     * Kept so a tap can tell a Chinese pick -- which stands for the *start* of the typed word
     * and leaves the rest -- from an emoji, which stands for all of it.
     */
    private var candidateSuggestions: List<UnifiedCandidates.Suggestion> = emptyList()

    /** The typed word the bar was answering, exactly as it stood in the field. */
    private var candidateWord: String = ""

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

    /**
     * Held here as well as on the view because the system rebuilds the view whenever it pleases,
     * and a fresh view must not fall back to [WordStarts.UNKNOWN] for the rest of the session.
     */
    private var wordStarts: WordStarts = WordStarts.UNKNOWN

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

    // --- granular cursor ---
    private var indicator: CursorIndicatorView? = null
    private var indicatorPopup: PopupWindow? = null
    private var trackpadActive = false

    /** The trackpad: maps the visible text and sets the caret absolutely. See [PreciseCursor]. */
    private val precise: PreciseCursor by lazy { PreciseCursor(preciseHost) }

    private val preciseHost = object : PreciseCursor.Host {
        override val inputConnection: InputConnection? get() = currentInputConnection
        override val editorInfo: EditorInfo? get() = currentInputEditorInfo
        override val selectionStart: Int get() = editorSelStart
        override val selectionEnd: Int get() = editorSelEnd
        override fun keyboardTop(): Float {
            val kv = keyboardView ?: return resources.displayMetrics.heightPixels.toFloat()
            val loc = IntArray(2)
            kv.getLocationOnScreen(loc)
            return loc[1].toFloat().takeIf { it > 0f } ?: resources.displayMetrics.heightPixels.toFloat()
        }
        override fun screenWidth(): Float = resources.displayMetrics.widthPixels.toFloat()
        override fun screenHeight(): Float = resources.displayMetrics.heightPixels.toFloat()
        override fun mainExecutor(): java.util.concurrent.Executor = mainExecutor
        override fun redraw() = updateIndicator()
        override fun trace(message: String) = this@KeyboardService.trace(message)
    }

    private val handler = Handler(Looper.getMainLooper())

    /** The selection as onUpdateSelection last gave it, for apps whose anchor info omits it. */
    private var editorSelStart = -1
    private var editorSelEnd = -1

    /** Debug only: stands in for the second finger, which adb cannot send. */
    private val debugSelectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.d(TAG, "debug broadcast: ${intent?.action}")
            keyboardView?.debugStartSelection()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG_GESTURES) {
            registerReceiver(
                debugSelectReceiver,
                IntentFilter().apply {
                    addAction("com.offlinekeyboard.ime.DEBUG_SELECT")
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
                // Opening the microphone takes a moment but not a nameable one: leave whatever
                // the strip is showing alone rather than flashing a message on its way past.
                Dictation.State.STARTING -> keyboardView?.status
                Dictation.State.LOADING -> getString(R.string.dictation_loading)
                Dictation.State.LISTENING -> getString(R.string.dictation_listening)
                Dictation.State.TRANSCRIBING -> getString(R.string.dictation_transcribing)
            })
            if (state == Dictation.State.IDLE) refreshCandidates()
        }

        override fun onText(
            transcript: CommandMerge.Transcript,
            detections: List<CommandMerge.Detection>,
        ) = commitDictated(transcript, detections)

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

    /**
     * Starts loading the recogniser before the microphone is ever tapped.
     *
     * [Dictation.warmUp] existed for exactly this and nothing called it, so every first tap paid
     * for a 239 MB model on the spot -- several seconds of a key that looks broken rather than
     * busy. Called alongside [loadGlideEngine] for the same reason it is: the keyboard coming up
     * is the last moment that is still free, and both guard themselves against a second call.
     *
     * Constructing [Dictation] does not touch the microphone or ask for permission, so warming
     * an engine the user never invokes costs a load that would otherwise have happened later.
     */
    private fun warmUpDictation() {
        val engine = dictation ?: Dictation(this).also { dictation = it }
        engine.warmUp()
    }

    private fun toggleDictation() {
        val engine = dictation ?: Dictation(this).also { dictation = it }
        if (engine.state != Dictation.State.IDLE) engine.stop() else engine.start(dictationListener)
    }

    /**
     * Commits one recognised segment, with the punctuation commands heard in the same audio.
     *
     * The model returns a clause per pause with no punctuation the speaker did not say, so the
     * spacing between segments is ours to get right: a space between them in Latin script, and
     * none in Chinese, where words do not take one.
     *
     * [detections] comes from the keyword spotter rather than the transcript, and carries the
     * marks the speaker asked for. It is empty when the spotter is unavailable, in which case
     * [SpokenPunctuation.applyMerged] is exactly the text-only path that shipped before it.
     */
    private fun commitDictated(
        transcript: CommandMerge.Transcript,
        detections: List<CommandMerge.Detection>,
    ) {
        val raw = transcript.text
        val text = SpokenPunctuation.applyMerged(transcript, detections, scriptFor(raw))
        logDictated(raw, text)
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: return
        // Two characters, so a straight quote can be told apart as opening or closing.
        val before = ic.getTextBeforeCursor(2, 0) ?: ""
        val needsSpace = DictationSpacing.needsSpace(before, text)
        ic.beginBatchEdit()
        ic.commitText(if (needsSpace) " $text" else text, 1)
        ic.endBatchEdit()
        dictatedAnything = true
        refreshCandidates()
    }

    /**
     * Logs what the recogniser said against what was committed.
     *
     * The pair is the point. [Dictation] logs the segment it decoded; this logs whether
     * [SpokenPunctuation] then recognised any of it as a spoken mark. A raw segment containing
     * "coma" that commits unchanged is a *near-miss* -- the speaker asked for punctuation and
     * the table did not match -- and those are the lines to collect, because they are what an
     * alias table would have to cover.
     *
     * `marks` counts punctuation in the committed text, which after `stripModelPunctuation` can
     * only have come from a word the speaker said. Zero marks beside a suspicious raw word is
     * the signature being hunted.
     *
     * Debug-only, and dictated text is private, so this stays off unless explicitly enabled:
     *
     *     adb shell setprop log.tag.OfflineKeyboard DEBUG
     */
    private fun logDictated(raw: String, committed: String) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val marks = committed.count { it in ",.?!;:\u2014\u2026，。？！；：、" }
        Log.d(TAG, "raw=[$raw] committed=[$committed] marks=$marks")
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
        // Which language model the decoder scores against.
        //
        // In a Chinese subtype the user has declared a script and gets exactly that one. In
        // English nothing has been declared, so the bar ranks both scripts against each other
        // instead of guessing one -- see [PinyinSession.englishMode].
        //
        // That guess used to be `preferTraditional()`, and it could not return true on a default
        // install: it reports a preference only when Traditional is enabled and Simplified is
        // not, but both Chinese subtypes are declared in `method.xml` and are implicitly enabled
        // together, so the English bar was permanently Simplified-only. Ranking both removes the
        // guess rather than correcting it.
        session.englishMode = !chineseMode
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
        view.wordStarts = wordStarts
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
        // Rejections are about a word in a place in *this* field. The next field has its own
        // text at the same offsets, and carrying refusals into it would demote a candidate on
        // the strength of something the user said about a different document.
        glideRejections.clear()
        glideEnd = null
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
        // Nor may a rejection recorded against an offset in the last field, where the same
        // offset now names entirely different text. See [onFinishInputView].
        if (!restarting) glideRejections.clear()
        // The same for deletions kept for undo: their offsets name text in the last field.
        if (!restarting) deletionHistory.clear()
        glideEnd = null
        // Nor may a login offered for the last one. `restarting` means the same field is still
        // focused -- the app changed something about it -- and the chips on screen are still
        // that field's, so only a genuinely new field clears them.
        if (!restarting) clearInlineSuggestions()
        editorSelStart = info?.initialSelStart ?: -1
        editorSelEnd = info?.initialSelEnd ?: -1
        tapDecodingAllowed = allowsTapDecoding(info)
        shift = ShiftState.OFF
        syncChineseMode()
        applyLayout()
        loadEmojiIndex()
        loadGlideEngine()
        warmUpDictation()
        refreshFlickContext()
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
        // After the selection fields are up to date, because it reads the field relative to
        // where the caret now is. This is the one place that sees the result of every edit,
        // whoever made it, which is why the delete that takes a glided word back is recognised
        // here rather than in the code that performs each kind of delete.
        noticeGlideEdit()
        if (trackpadActive) precise.onUpdateSelection(newSelStart, newSelEnd)
        // After the caret bookkeeping above, so the flick prior reads the new position rather
        // than the one the caret just left.
        refreshFlickContext()
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

        // Nothing worked. Where the user asked for one of *our* languages, the recovery stays
        // inside this keyboard: cycling out to Gboard because a subtype switch was refused is
        // further from the request than changing nothing, not nearer it. Only a request that was
        // already "leave for another keyboard" is allowed to fall back to leaving.
        if (subtype != null) cycleOwnSubtype() else switchToNextInputMethod(false)
    }

    /**
     * A tap on the globe: the next language *of this keyboard*, wrapping at the end.
     *
     * Deliberately not `switchToNextInputMethod`. That call asks the system for the next
     * destination and the system counts other keyboards among them, so on a phone with Gboard
     * installed the very first tap left this IME entirely -- the one outcome a tap on the globe
     * should never have. Even `switchToNextInputMethod(true)`, which claims to stay within the
     * current IME, returns false and falls through to another keyboard once it believes there is
     * no next subtype, which is exactly the state a device is in when only one of our subtypes
     * has been enabled in Settings.
     *
     * So the ring is walked here instead, over the enabled subtypes this keyboard declares.
     * Leaving for another keyboard remains available, but only through the deliberate act of
     * holding the key and sliding to it -- never by a tap.
     *
     * With a single enabled subtype there is nowhere to go and the tap does nothing, which is
     * honest: the fix for that is enabling another language, not silently changing keyboard.
     */
    private fun cycleOwnSubtype() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val info = runCatching { imm.enabledInputMethodList }.getOrNull().orEmpty()
            .firstOrNull { it.packageName == packageName } ?: return
        val subtypes = runCatching { imm.getEnabledInputMethodSubtypeList(info, true) }
            .getOrNull().orEmpty()
        if (subtypes.size < 2) return

        val current = runCatching { imm.currentInputMethodSubtype }.getOrNull()
        val index = subtypes.indexOfFirst { it.hashCode() == current?.hashCode() }
        val next = subtypes[(index + 1).mod(subtypes.size)]

        // Same call the menu prefers, and for the same reason: it needs no window token and no
        // permission that has been tightened since.
        runCatching { switchInputMethod(info.id, next) }
    }

    private fun handleOutputs(outputs: List<GestureOutput>) {
        if (DEBUG_GESTURES) outputs.forEach { android.util.Log.d(TAG, "gesture: $it") }
        typedThisGesture.setLength(0)
        deletedThisGesture = 0
        outputs.forEach { out ->
            if (glideEnd != null && !keepsGlideOpen(out)) {
                when (out) {
                    is GestureOutput.CommitPrimary -> spaceAfterGlide(out.text)
                    is GestureOutput.CommitSecondary -> spaceAfterGlide(out.text)
                    is GestureOutput.CommitAccent -> spaceAfterGlide(out.text)
                    else -> Unit
                }
                glideEnd = null
            }
            when (out) {
                is GestureOutput.CommitPrimary -> if (!pendLetter(out)) commit(out.text)
                is GestureOutput.CommitSecondary -> commit(out.text)
                is GestureOutput.CommitAccent -> commit(out.text)
                is GestureOutput.CommitAction -> runEditAction(out.action)
                is GestureOutput.CommitLanguage -> switchLanguage(out.languageId)
                GestureOutput.SelectionStarted -> if (trackpadActive) precise.beginSelection()
                GestureOutput.TrackpadStarted -> startTrackpad()
                is GestureOutput.TrackpadPan -> {
                    trace("PAN dx=${out.dx} dy=${out.dy}")
                    if (trackpadActive) precise.pan(out.dx, out.dy)
                }
                GestureOutput.TrackpadEnded -> stopTrackpad()
                GestureOutput.BackspaceRepeatStarted -> startBackspaceRepeat()
                GestureOutput.BackspaceRepeatEnded -> stopBackspaceRepeat()
                GestureOutput.BulkDelete -> bulkDelete()
                GestureOutput.DeleteLine -> deleteLine()
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
        // Every gesture that types anything has just changed what the caret sits after, and the
        // next press may arrive before the editor gets round to calling onUpdateSelection --
        // which on a fast thumb it routinely does. Refreshing here rather than waiting for that
        // callback is what makes the mid-word rule true of the word actually being typed instead
        // of the one before it.
        refreshFlickContext()
    }

    /**
     * Outputs that can come between a glide and the letter that starts the next word without
     * being an edit of their own: what the finger shows on the way to a key, shift, and hopping
     * between the letter and symbol planes. Anything else settles the glide, so a letter typed
     * after it is not treated as the start of a new word.
     */
    private fun keepsGlideOpen(out: GestureOutput): Boolean = when (out) {
        is GestureOutput.KeyHighlighted,
        is GestureOutput.FlickPreview,
        GestureOutput.FlickPreviewCleared,
        is GestureOutput.UpFlickArmed,
        GestureOutput.UpFlickDisarmed,
        is GestureOutput.ShowAccents,
        is GestureOutput.AccentHighlighted,
        GestureOutput.HideAccents,
        is GestureOutput.GestureCaptured,
        -> true
        is GestureOutput.SpecialKey -> out.type == KeyType.SHIFT || out.type == KeyType.MODE_SWITCH
        else -> false
    }

    /**
     * Puts a space between the glided word just typed and the letter about to be typed, which
     * starts a new word. A glide leaves the caret against its last letter so that punctuation
     * and another glide both land correctly; a tapped letter is the one case that needs the
     * space it deliberately withheld. See [GlideSpacing].
     */
    private fun spaceAfterGlide(text: String) {
        val end = glideEnd ?: return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
        if (!GlideSpacing.needsSpace(text, end, currentCaret(), before)) return
        ic.commitText(" ", 1)
        typed(" ")
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
        if (TypingHabits.returnsToLetters(text, typedOnPlane)) {
            switchPlane("mode_abc")
            return
        }
        if (text.isNotBlank()) typedOnPlane = true
    }

    /**
     * Whether anything other than a space has been typed since leaving the letters for the
     * number and symbol planes. A space only sends those planes back to the letters once this is
     * true; see [TypingHabits.returnsToLetters]. Reset by [switchPlane] on entering or leaving
     * the letters.
     */
    private var typedOnPlane = false

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

        // Resolved against the keys that are on screen *now*, and the geometry is not kept past
        // this line. A held word can outlive the grid it was typed on -- a rotation, a
        // split-screen drag, the navigation bar arriving, a one-handed squash -- and re-scoring
        // stored pixels against a grid that has since moved is what turned an accurately typed
        // word into a different one. See [TapDecoder.Tap].
        pending.add(out.x, out.y, id[0], upper = shift != ShiftState.OFF, geometry = geometry)
        // iOS one-shot shift: the next letter is capitalised, then shift releases.
        if (shift == ShiftState.ONE_SHOT) {
            shift = ShiftState.OFF
            applyLayout()
        }

        val text = pending.textFor(decoder.read(pending.taps))
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
     *
     * The word taken is the best candidate **that has not already been rejected here**. Deleting
     * a glided word is how the user says the decoder guessed wrong, and the next glide in that
     * place moves down the ranking rather than repeating the guess -- see [GlideRejections],
     * which owns that memory and the wrap-around when the candidates run out.
     */
    private fun commitGlide(completed: GestureOutput.GlideCompleted): String? {
        // A glide writes a whole word of its own; the tapped one before it is finished.
        flushPending()
        val decoder = glide ?: return null
        val view = keyboardView ?: return null
        val ic = currentInputConnection ?: return null
        // What was typed before this word, so the context model can choose between shapes the
        // glide alone cannot separate: `so good`, not `so god`.
        val context = GlideContext.words(ic.getTextBeforeCursor(GlideContext.LOOKBEHIND, 0) ?: "")
        val candidates = decoder.decode(completed.path, view.currentGeometry, context)

        val before = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
        val needsSpace = before != null && !before.isWhitespace() && before !in OPENERS
        // Where the word itself will start, which is past the space when one is going in. This
        // is the position the rejection memory is keyed on, and it is read *before* the edit so
        // it names the same place on every retry: a retyped word replaces the last one exactly,
        // so its start is fixed while its end moves with the length of whatever was chosen.
        //
        // Asked of the editor rather than taken from [editorSelStart], which is only as fresh as
        // the last onUpdateSelection -- and the delete that precedes a retry is followed
        // immediately by the retry itself, so on a quick thumb the callback has not arrived and
        // the cached offset still describes the text *before* the deletion. Keying on that would
        // file the rejection under a position the word was never at, and the cycle would stall
        // on the second candidate.
        val caret = currentCaret()
        val start = if (caret < 0) -1 else if (needsSpace) caret + 1 else caret
        val word = glideRejections.next(start, candidates) ?: return null

        val cased = if (shift == ShiftState.OFF) word else word.replaceFirstChar { it.uppercase() }
        val written = if (needsSpace) " $cased" else cased
        ic.beginBatchEdit()
        ic.commitText(written, 1)
        ic.endBatchEdit()
        typed(written)
        // Recorded as committed, not as accepted: whether this was the wanted word is decided by
        // what the user does next, and a delete arriving shortly is exactly the case this exists
        // for. A position we could not locate (-1) is still tracked so the *word* can be matched
        // when it is deleted; it simply shares one bucket with every other unlocatable glide.
        // Both spellings: `cased` is what the field now contains and what a delete has to be
        // measured against, `word` is how the ranking spells it and so what gets struck off.
        glideRejections.committed(start, written = cased, candidate = word)
        // A letter tapped next starts another word and needs a space first; see [spaceAfterGlide].
        // Worked out from where the word started rather than read back from the editor, which
        // may not have caught up with the commit yet.
        glideEnd = if (start < 0) -1 else start + cased.length
        if (shift == ShiftState.ONE_SHOT) {
            shift = ShiftState.OFF
            applyLayout()
        }
        refreshCandidates()
        return cased
    }

    /**
     * The caret offset right now, or -1 if the field will not say.
     *
     * Asks the editor first and falls back to the cached selection, which is the opposite order
     * from most reads here and is deliberate: the callers are the glide-rejection pair, and both
     * run immediately after an edit that the asynchronous callbacks have not yet reported. A
     * collapsed caret only; a selection has no single position and neither caller means anything
     * against one.
     */
    private fun currentCaret(): Int {
        val ic = currentInputConnection ?: return -1
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted != null) {
            val start = extracted.startOffset.coerceAtLeast(0) + extracted.selectionStart
            val end = extracted.startOffset.coerceAtLeast(0) + extracted.selectionEnd
            if (extracted.selectionStart >= 0 && start == end) return start
        }
        return if (editorSelStart >= 0 && editorSelStart == editorSelEnd) editorSelStart else -1
    }

    /**
     * Decides whether the glided word that was just typed is still in the field, and tells
     * [GlideRejections] which it was.
     *
     * Called after every edit rather than from inside the delete paths, and that is the point:
     * "by any method" is the requirement, and there are many methods. A plain backspace, a held
     * backspace that has accelerated into whole words, the swipe-down and swipe-up bulk deletes,
     * a selection typed over, the app's own undo, a delete performed by a hardware keyboard or by
     * another IME sharing the field -- instrumenting each one would mean finding each one, and
     * the ones reached from outside this class could not be instrumented at all. Reading the
     * field is the single check that covers all of them, because it asks about the outcome
     * instead of the cause.
     *
     * Three outcomes, and the middle one is the reason this is not a one-line check:
     *
     *  - **The word is still there.** The guess was right and the user has moved on -- typed
     *    more, added a space, tapped elsewhere. The question asked at that spot is settled, so
     *    [GlideRejections.acceptedAt] drops the refusals recorded against it; leaving them would
     *    demote a candidate for some later sentence that reuses the offset.
     *  - **Part of it is still there.** A character-at-a-time backspace is mid-flight. Nothing is
     *    decided yet -- the user may stop and retype the tail by hand, which is not a complaint
     *    about the decoder -- so this waits and says nothing.
     *  - **It is gone.** That is the rejection.
     */
    private fun noticeGlideEdit() {
        val (start, word) = glideRejections.pendingCommit() ?: return
        val ic = currentInputConnection ?: return
        val caret = editorSelStart.takeIf { it >= 0 } ?: return
        if (start < 0) return
        if (caret < start + word.length) {
            // The caret is inside or before the word's span, so the field cannot still hold all
            // of it. Read what survives between the word's start and the caret.
            val kept = if (caret <= start) "" else ic.getTextBeforeCursor(caret - start, 0) ?: return
            // A prefix of the word is a delete in progress; wait for it to land somewhere.
            if (kept.isNotEmpty() && word.startsWith(kept)) return
            glideRejections.rejectLast()
            return
        }
        // The caret is past where the word ends, so the whole span is readable. Ask for exactly
        // it -- offset by however far the caret has since moved on -- and compare.
        val span = ic.getTextBeforeCursor(caret - start, 0) ?: return
        if (!(span.length >= word.length && span.startsWith(word))) {
            // The span was overwritten rather than deleted back through: a selection replaced,
            // an autocorrect, an undo that swapped the text. The word is gone all the same.
            glideRejections.rejectLast()
            return
        }
        // The word survives. That is only *acceptance* once the user has done something further
        // -- the caret has moved beyond the word, so more text has been typed or the caret was
        // put elsewhere. The moment the glide itself lands the caret sits exactly at the word's
        // end, and calling this acceptance there would clear the very rejections being
        // accumulated: every delete-and-retry would reset to the first candidate and the chain
        // the user is walking could never get past the second word.
        if (caret > start + word.length) glideRejections.acceptedAt(start)
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
        when (action) {
            // Undo and redo go through the keyboard's own record of its bulk deletes, which asks
            // the editor first and restores the text itself only where the editor did nothing --
            // Chrome, WebView and Compose all ignore these two ids. See [DeletionHistory].
            EditAction.UNDO -> deletionHistory.undo(historyField)
            EditAction.REDO -> deletionHistory.redo(historyField)
            EditAction.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
            EditAction.CUT -> ic.performContextMenuAction(android.R.id.cut)
            EditAction.COPY -> ic.performContextMenuAction(android.R.id.copy)
            EditAction.PASTE -> ic.performContextMenuAction(android.R.id.paste)
        }
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
            KeyType.GLOBE -> cycleOwnSubtype()
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
        val from = plane
        plane = IosLayouts.planeFor(keyId) ?: return
        // Only a fresh arrival from the letters starts the count again. Hopping between "123"
        // and "#+=" is still the same excursion: "1" then "#+=" then space has typed something.
        if (from.id.startsWith("en_qwerty") || plane.id.startsWith("en_qwerty")) typedOnPlane = false
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
    private fun deleteWordBackwards(
        boundary: (CharSequence) -> Int = WordBoundary::deleteLength,
    ): CharSequence {
        flushPending()
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(TypedWord.LOOKBEHIND, 0)
        if (before.isNullOrEmpty()) return ""
        // Coerced to 1 so a cursor sitting directly after a line break still makes progress:
        // the scan stops at the break and would otherwise return 0, leaving a held backspace
        // spinning against it forever.
        val units = boundary(before).coerceAtLeast(1).coerceAtMost(before.length)
        ic.deleteSurroundingText(units, 0)
        refreshCandidates()
        // What went, so the swipe that called this can offer it back to undo.
        return before.subSequence(before.length - units, before.length)
    }

    // --- undo for the bulk deletes ----------------------------------------------------------

    /**
     * What the swipes on backspace deleted, so undo can put it back in editors whose own undo
     * does nothing. See [DeletionHistory].
     */
    private val deletionHistory = DeletionHistory()

    /** [DeletionHistory]'s view of the field, over whatever input connection is current. */
    private val historyField = object : HistoryField {
        override fun state(): FieldState? {
            val ic = currentInputConnection ?: return null
            val before = ic.getTextBeforeCursor(HISTORY_WINDOW, 0) ?: return null
            val after = ic.getTextAfterCursor(HISTORY_WINDOW, 0) ?: return null
            val selected = ic.getSelectedText(0)?.toString().orEmpty()
            // Asked of the editor rather than taken from [editorSelStart], which lags an edit
            // made a moment ago by however long onUpdateSelection takes to arrive -- and the
            // whole point of this read is to see the edit that just happened. A field that will
            // not report offsets gets -1 for both, which still compares fairly against itself.
            val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
            val base = extracted?.startOffset?.coerceAtLeast(0) ?: 0
            val start = extracted?.selectionStart?.takeIf { it >= 0 }?.let { base + it } ?: -1
            val end = extracted?.selectionEnd?.takeIf { it >= 0 }?.let { base + it } ?: -1
            return FieldState(start, end, before.toString(), selected, after.toString())
        }

        override fun nativeUndo() {
            currentInputConnection?.performContextMenuAction(android.R.id.undo)
        }

        override fun nativeRedo() {
            currentInputConnection?.performContextMenuAction(android.R.id.redo)
        }

        override fun insert(text: String, at: Int, select: Boolean) {
            val ic = currentInputConnection ?: return
            ic.beginBatchEdit()
            ic.commitText(text, 1)
            if (select && at >= 0) ic.setSelection(at, at + text.length)
            ic.endBatchEdit()
        }

        override fun remove(text: String, selected: Boolean) {
            val ic = currentInputConnection ?: return
            if (selected) ic.commitText("", 1) else ic.deleteSurroundingText(text.length, 0)
        }
    }

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

    /**
     * Requirement 11: swipe *down* on backspace to delete the word before the cursor.
     *
     * The smaller of the two bulk deletes; [deleteLine] is the upward one. "The word" is what a
     * long press on the character behind the cursor would select
     * ([WordBoundary.longPressSelectionLength]): a word stops at punctuation, a punctuation run
     * or a run of spaces goes by itself, and whitespace is never crossed, so a flick at the start
     * of a soft-wrapped line cannot reach back onto the line above. Repeating the flick walks
     * back one unit at a time. The held backspace's acceleration keeps the coarser
     * [WordBoundary.deleteLength], because that one is meant to cover ground.
     *
     * A selection is what the user pointed at, so it wins over the word behind the cursor -- the
     * same precedence a plain backspace uses. Inside a pinyin buffer the word is the syllable
     * being spelled, so the whole buffer goes rather than committed text behind it.
     */
    private fun bulkDelete() {
        // An open pinyin buffer is the word in progress: drop it and stop, so the flick never
        // reaches past it into text that is already committed.
        val session = pinyin
        if (chineseMode && session != null && !session.isEmpty) {
            session.clear()
            currentInputConnection?.finishComposingText()
            refreshCandidates()
            return
        }

        flushPending()
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.finishComposingText()

        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            ic.endBatchEdit()
            deletionHistory.recorded(selected, selected = true, field = historyField)
            refreshCandidates()
            return
        }

        val gone = deleteWordBackwards(WordBoundary::longPressSelectionLength)
        ic.endBatchEdit()
        deletionHistory.recorded(gone, selected = false, field = historyField)
    }

    /**
     * Swipe up on backspace: delete the line before the cursor.
     *
     * The same precedence as [bulkDelete], for the same reasons -- an open pinyin buffer is the
     * text in progress and goes first, then a selection, because both are narrower than the
     * line and both are what the user is actually pointing at. Only once neither is there does
     * the gesture reach committed text, and then it takes everything back to the line break
     * without crossing it: [WordBoundary.lineDeleteLength] leaves the newline in place so the
     * cursor stays on a line of its own rather than being pulled up onto the previous one.
     *
     * Deliberately *not* coerced to a minimum of 1 the way [deleteWordBackwards] is. That
     * coercion exists so a held backspace cannot spin forever against a line break; this
     * gesture is one flick that the user repeats by hand, so a cursor on an empty line simply
     * does nothing, and the line break above survives until a plain backspace is used on it.
     */
    private fun deleteLine() {
        val session = pinyin
        if (chineseMode && session != null && !session.isEmpty) {
            session.clear()
            currentInputConnection?.finishComposingText()
            refreshCandidates()
            return
        }

        flushPending()
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.finishComposingText()

        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            ic.endBatchEdit()
            deletionHistory.recorded(selected, selected = true, field = historyField)
            refreshCandidates()
            return
        }

        val before = ic.getTextBeforeCursor(LINE_LOOKBEHIND, 0)
        var gone: CharSequence = ""
        if (!before.isNullOrEmpty()) {
            val units = WordBoundary.lineDeleteLength(before)
            if (units > 0) {
                ic.deleteSurroundingText(units, 0)
                deleted(units)
                gone = before.subSequence(before.length - units, before.length)
            }
        }
        ic.endBatchEdit()
        // After the batch closes, so the record is made against the field as the delete left it.
        // Undo has to be able to put the line back: this is the largest thing one flick destroys.
        deletionHistory.recorded(gone, selected = false, field = historyField)
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
            // on its own thread is deliberate: it is a sort of the same 70,000 strings that were
            // just parsed, and doing it twice over would be two loads of the asset.
            val engine: GlideEngine? = lexicon?.let { FutoSwipe.open(applicationContext, it) }
            val index = lexicon?.let { TapDecoder(WordIndex.of(it)) }
            // Warmed here, off the main thread: the suggestion bar's first prefix query would
            // otherwise sort forty thousand words on a keystroke.
            lexicon?.logPrefixProbability("a")
            // Which second letters words head for, for the per-key flick cones. Built here with
            // everything else read from the lexicon, since it is one pass over the same array.
            val starts = lexicon?.let { WordStarts.of(it.letters, it.logFrequency) }
            handler.post {
                starts?.let { wordStarts = it; keyboardView?.wordStarts = it }
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
        candidateSuggestions = emptyList()
        candidateWord = ""
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
    /**
     * Tells the view what the caret is sitting after, for [FlickPrior].
     *
     * One character and one boolean, refreshed whenever the caret may have moved. It is
     * deliberately separate from [refreshCandidates] even though both are driven by the same
     * events: that one returns early in several places -- no emoji index, a live selection,
     * Chinese mode -- and each of those returns would silently leave the flick thresholds
     * reading a caret from some earlier sentence. A gesture threshold going stale is invisible
     * until it types the wrong thing, so this gets its own path with no early exits.
     *
     * A failure to read the editor sets [FlickPrior.Context.UNKNOWN] rather than keeping the
     * last good answer, for the same reason: an editor that will not say is not evidence that
     * nothing has changed.
     */
    private fun refreshFlickContext() {
        val view = keyboardView ?: return
        val ic = currentInputConnection
        if (ic == null) {
            view.flickContext = FlickPrior.Context.UNKNOWN
            return
        }
        // The word this keyboard is holding as composing text counts as being mid-word even
        // though the editor may not have it yet -- it is the strongest evidence available that a
        // word is in progress, and it is evidence only this side knows about.
        val composing = !pending.isEmpty
        val before = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
        view.flickContext = FlickPrior.Context(before = before, composing = composing)
    }

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
            candidateConsumes = emptyList()
            candidateSuggestions = emptyList()
            candidateWord = ""
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
                candidateSuggestions = ranked
                candidateWord = before.subSequence(before.length - word.length, before.length)
                    .toString()
                candidateReplaceLength = word.length
                return
            }
        }
        offerDefaultCandidates()
    }

    /**
     * The bar for one candidate word: the Chinese candidates in exactly the order the Chinese
     * subtypes show them, with emoji placed around them by [UnifiedCandidates.rank].
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
        val session = pinyin
        val chinese = if (session != null && session.isReady && !query.contains(' ')) {
            // Lowercased because the field holds what was typed, and `Tadebaba` at the start
            // of a sentence is still pinyin; the lengths, and so every `consumes`, are unchanged.
            session.scoredFor(query.lowercase())
        } else {
            emptyList()
        }
        return UnifiedCandidates.suggest(query, index, chinese, englishWords)
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
        candidateSuggestions = emptyList()
        candidateWord = ""
    }

    /** Replaces the typed word with the emoji, the way the iOS emoji suggestion does. */
    private fun commitCandidate(position: Int) {
        // A suggestion replaces the glided word, so the next letter is no longer following it.
        glideEnd = null
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
        val suggestion = candidateSuggestions.getOrNull(position)
        val word = candidateWord
        ic.beginBatchEdit()
        if (suggestion != null && suggestion.kind == UnifiedCandidates.Kind.CHINESE &&
            word.length == candidateReplaceLength && suggestion.consumes < word.length
        ) {
            // A Chinese suggestion may stand for only the *start* of the word: picking 他的 out
            // of `tadebabahentaoyanwo` means `tade`, and `babahentaoyanwo` is still being typed.
            // The whole word is replaced by the characters plus the letters they did not use,
            // so the field reads 他的babahentaoyanwo and the next suggestions answer the rest --
            // the way iOS and the Chinese subtypes both behave. Deleting `consumes` letters
            // back from the caret instead ate the *end* of the word: tadebabahentaoy他的.
            val replacement = UnifiedCandidates.replacementFor(word, text, suggestion.consumes)
            ic.deleteSurroundingText(word.length, 0)
            deleted(word.length)
            ic.commitText(replacement, 1)
            typed(replacement)
        } else {
            // Emoji, whole-word Chinese, and every entry on a uniform bar replace the whole word.
            val replace = candidateConsumes.getOrNull(position) ?: candidateReplaceLength
            if (replace > 0) {
                ic.deleteSurroundingText(replace, 0)
                deleted(replace)
            }
            ic.commitText(text, 1)
            typed(text)
        }
        ic.endBatchEdit()
        // A choice made here is learned exactly as one made on the Chinese bar, so the two
        // bars cannot drift apart as the user corrects them.
        suggestion?.source?.let { pinyin?.learn(it) }
        refreshCandidates()
    }

    // --- the trackpad ----------------------------------------------------------------------

    /** One line per event, so a whole gesture can be reconstructed from the trace file. */
    private fun trace(message: String) {
        if (DEBUG_GESTURES) android.util.Log.d("TP", message)
        com.offlinekeyboard.ime.capture.TouchTrace.log(this, "TP $message")
    }

    private fun startTrackpad() {
        // The caret is about to go somewhere else entirely. Settle the word where it was typed.
        flushPending()
        trace("=== TRACKPAD START ===")
        trackpadActive = true
        staleReports.reset()
        // Everything the cursor needs is asked for here, before the finger has moved: a map of
        // the visible text arrives within a frame or two.
        precise.start(editorSelEnd.takeIf { it >= 0 } ?: editorSelStart)
        updateIndicator()
    }

    private fun stopTrackpad() {
        if (!trackpadActive) return
        trace("=== TRACKPAD END ===")
        precise.stop()
        trackpadActive = false
        staleReports.reset()
        currentInputConnection?.requestCursorUpdates(0)
        indicator?.clear()
        indicatorPopup?.takeIf { it.isShowing }?.let { runCatching { it.dismiss() } }
    }

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        if (!trackpadActive) return
        // Chromium's first report after a caret move has the new offset and the old position.
        // See StaleReportFilter.
        staleReports.offer(info) { precise.onCursorAnchorInfo(it) }
    }

    private val staleReports by lazy { com.offlinekeyboard.ime.cursor.StaleReportFilter(handler) }

    /**
     * Draws the marker wherever the finger has put it, and where the caret will land.
     *
     * The overlay covers the screen and is shown once per gesture; each move after that is only
     * an invalidate, not a window relayout.
     */
    private fun updateIndicator() {
        if (!trackpadActive) return
        val kv = keyboardView ?: return
        val view = indicator ?: CursorIndicatorView(this).also { indicator = it }
        val m = precise.marker()
        if (m == null) {
            view.clear()
        } else {
            view.setMarker(m[0], m[1], m[2].takeIf { it > 1f } ?: (20f * resources.displayMetrics.density))
            val landing = precise.landing()
            if (landing != null) view.setLanding(landing[0], landing[1], landing[2])
            else view.setLanding(Float.NaN, Float.NaN, 0f)
        }

        val popup = indicatorPopup ?: PopupWindow(view).apply {
            isTouchable = false
            isFocusable = false
            isClippingEnabled = false
            setBackgroundDrawable(null)
            indicatorPopup = this
        }
        if (popup.isShowing || m == null) return
        // showAtLocation positions relative to the parent's *window*, which for an IME starts at
        // the top of the keyboard; place the popup at the screen origin explicitly. The view
        // maps screen coordinates through its own on-screen position when it draws, so a window
        // manager that nudges the popup does not move the marks.
        val metrics = resources.displayMetrics
        popup.width = metrics.widthPixels
        popup.height = metrics.heightPixels
        val onScreen = IntArray(2)
        val inWindow = IntArray(2)
        kv.getLocationOnScreen(onScreen)
        kv.getLocationInWindow(inWindow)
        runCatching {
            popup.showAtLocation(
                kv, Gravity.NO_GRAVITY, -(onScreen[0] - inWindow[0]), -(onScreen[1] - inWindow[1]),
            )
        }.onFailure {
            if (DEBUG_GESTURES) android.util.Log.d(TAG, "indicator failed: $it")
        }
    }
}
