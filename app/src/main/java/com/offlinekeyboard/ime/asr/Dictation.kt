package com.offlinekeyboard.ime.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import kotlin.concurrent.thread

private const val TAG = "Dictation"

private const val SAMPLE_RATE = 16000

/** Silero works on fixed windows; 512 samples at 16 kHz is the size it was trained for. */
private const val VAD_WINDOW = 512

/**
 * How long a pause has to be before it ends a segment, and how long one segment may run.
 *
 * SenseVoice is not a streaming model: it sees a whole segment at once, so a segment cut in the
 * middle of a clause costs accuracy on both halves. The cap only exists so that speaking without
 * pause still produces text eventually.
 *
 * 0.35 s rather than 0.5 s, because of how the model handles language. SenseVoice makes **one**
 * language decision per segment (see [CodeSwitch]), so every segment boundary is also an
 * opportunity to change language. A speaker switching from English to Mandarin mid-sentence
 * almost always leaves a short hesitation at the switch -- shorter than a sentence break, longer
 * than the gap between words in one language. Cutting there gives the Mandarin its own language
 * decision instead of forcing it through the English one.
 */
private const val MIN_SILENCE_SECONDS = 0.35f
private const val MAX_SPEECH_SECONDS = 25f

/**
 * How much audio either side of a garbled span goes into its forced-Chinese retry.
 *
 * Token timestamps are where a token *starts*, at 60 ms frame resolution, so the span is widened
 * a little to catch the onset of its first syllable and the tail of its last. Much wider and the
 * neighbouring English words come back into the retry ("拼螺蛳粉 is" at 0.25 s), which is
 * exactly the context that stopped the whole-segment retry from working.
 */
private const val SPAN_PAD_SECONDS = 0.1f

/**
 * How long to read and throw away before trusting the microphone, and before telling the user it
 * is listening.
 *
 * `AudioRecord.startRecording()` is asynchronous: it returns once the request is lodged, not once
 * the input path is carrying audio. Until it is, `read()` returns immediately with buffered junk.
 * 120 ms covers the gap on the hardware this was tested against while staying under the time it
 * takes to move a thumb off the key and draw breath, so nothing a speaker could physically have
 * said yet lands inside it.
 */
private const val WARMUP_DISCARD_NANOS = 120_000_000L

/**
 * Offline dictation: microphone in, text out, nothing leaves the process.
 *
 * SenseVoice runs with **automatic language detection** rather than being told the keyboard's
 * mode. That is what `docs/ASR_BENCHMARK.md` measured -- 7.9% on code-switched Mandarin against
 * Apple's 58.9% -- and pinning the keyboard's mode as the language is exactly what breaks a
 * sentence that changes language halfway through, which is the case this keyboard exists for.
 *
 * What `auto` does **not** do is switch language within a segment. It prepends one detected
 * language token and decodes everything after it conditioned on that single choice, so a mostly
 * English sentence ending in Mandarin renders the Mandarin as romanised mush ("牛肉麵嗎" ->
 * "ne roium ma"). Two things address that here, and neither changes the measured `auto` default:
 *
 *  - Segments are cut on a shorter pause, so a mid-sentence language switch tends to get its own
 *    segment and therefore its own language decision.
 *  - In a segment detected as anything but Chinese, each run of Latin words outside the English
 *    lexicon has its own audio decoded again forced to `zh`, and [CodeSwitch.choose] keeps the
 *    retry only when it turns the run into Han without inventing English. The `auto` result is
 *    still what ships everywhere else.
 */
class Dictation(private val context: Context) {

    /**
     * [STARTING] and [LOADING] are both "not listening yet"; they differ only in whether the wait
     * is long enough to be worth naming. LOADING means the model is still being read off disk and
     * can take seconds. STARTING means only the microphone is being opened -- a fraction of a
     * second, and deliberately silent in the UI.
     *
     * Neither is [LISTENING], and that distinction is the point: LISTENING is a promise that
     * audio is being captured, which the user acts on by beginning to speak.
     */
    enum class State { IDLE, LOADING, STARTING, LISTENING, TRANSCRIBING }

    interface Listener {
        fun onStateChanged(state: State)

        /**
         * A finished segment. Called on the main thread, once per pause in speech.
         *
         * [transcript] carries the token timings alongside the text, and [detections] the
         * punctuation commands the keyword spotter heard in the same audio. The two are
         * delivered together because they only mean anything joined: a detection knows a command
         * was spoken but not where, and the transcript knows where every word is but cannot tell
         * a command from a word. [SpokenPunctuation.applyMerged] puts them together.
         *
         * [detections] is empty when the spotter is unavailable, which makes the merged path
         * degrade to exactly the text-only behaviour that shipped before it existed.
         */
        fun onText(
            transcript: CommandMerge.Transcript,
            detections: List<CommandMerge.Detection>,
        )

        fun onUnavailable(reason: Reason)
    }

    enum class Reason { NO_PERMISSION, NO_MICROPHONE, MODEL_FAILED }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var recognizer: OfflineRecognizer? = null

    /**
     * A second recognizer over the *same* weights, pinned to Chinese, used only to re-decode a
     * segment that came back as romanised mush. Two `OfflineRecognizer` instances mean two
     * sessions over one 239 MB model file; the alternative is rebuilding the recognizer per
     * retry, which would stall dictation for seconds.
     */
    @Volatile private var zhRecognizer: OfflineRecognizer? = null

    /**
     * What [CodeSwitch] counts as an English word: the glide lexicon's 40,000 words. Loaded with
     * the models and optional in the same way [zhRecognizer] is -- without it there is no way to
     * tell romanised Chinese from English, so the repair simply does not run.
     */
    @Volatile private var englishWords: Set<String>? = null

    /**
     * The command channel. Optional in the same way [zhRecognizer] is: a repair layered on a
     * working recogniser, so a device where it fails to load still dictates, just with spoken
     * punctuation left to word matching.
     */
    @Volatile private var spotting: KeywordSpotting? = null
    @Volatile private var vad: Vad? = null
    @Volatile private var recording = false
    private var worker: Thread? = null
    private var listener: Listener? = null

    var state: State = State.IDLE
        private set

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Loads the models. Safe to call more than once and safe to call early -- the first tap on
     * the microphone otherwise waits several seconds on a 239 MB model, which reads as the key
     * being broken rather than busy.
     */
    fun warmUp() {
        if (recognizer != null) return
        thread(name = "dictation-load") { load() }
    }

    @Synchronized
    private fun load(): Boolean {
        // zhRecognizer is deliberately not part of this guard: it is optional, and a failure to
        // build it must not cause the whole load to be retried on every segment.
        if (recognizer != null && vad != null) return true
        return try {
            val assets = context.assets
            recognizer = buildRecognizer(language = "")
            // Built eagerly: the retry it serves happens inside a segment's decode, and loading
            // a session there would show up as dictation hanging mid-sentence. It is an
            // improvement on top of a working recognizer, not a requirement -- if this second
            // session cannot be created, dictation still runs, just without the repair.
            zhRecognizer = try {
                buildRecognizer(language = CodeSwitch.LANG_ZH)
            } catch (t: Throwable) {
                Log.e(TAG, "no forced-zh recognizer; code-switch repair disabled", t)
                null
            }
            englishWords = try {
                assets.open(LEXICON_ASSET).use(CodeSwitch::readEnglishWords)
            } catch (t: Throwable) {
                Log.e(TAG, "no English lexicon; code-switch repair disabled", t)
                null
            }
            // Loaded here for the same reason: it runs inside a segment's processing, so paying
            // for it there would stall dictation mid-sentence. 5 MB against the 239 MB above.
            spotting = KeywordSpotting(assets).takeIf { it.load() }
            vad = Vad(
                assetManager = assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "asr/silero_vad.onnx",
                        minSilenceDuration = MIN_SILENCE_SECONDS,
                        maxSpeechDuration = MAX_SPEECH_SECONDS,
                        windowSize = VAD_WINDOW,
                    ),
                    sampleRate = SAMPLE_RATE,
                ),
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to load dictation models", t)
            recognizer = null
            zhRecognizer = null
            spotting?.release()
            spotting = null
            vad = null
            false
        }
    }

    /**
     * One recognizer over the shipped SenseVoice weights.
     *
     * [language] is "" for the measured automatic detection, or a SenseVoice language code to
     * pin it. The native layer accepts `auto, zh, en, ja, ko, yue`, and treats "" as `auto`.
     */
    private fun buildRecognizer(language: String) = OfflineRecognizer(
        assetManager = context.assets,
        config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "asr/sensevoice/model.int8.onnx",
                    language = language,
                    // The benchmark scored it off, and the user wants no text the
                    // speaker did not say -- ITN rewrites what was said into digits.
                    useInverseTextNormalization = false,
                ),
                tokens = "asr/sensevoice/tokens.txt",
                numThreads = 2,
                modelType = "sense_voice",
            ),
        ),
    )

    fun start(listener: Listener) {
        if (recording) return
        this.listener = listener
        if (!hasPermission()) {
            listener.onUnavailable(Reason.NO_PERMISSION)
            return
        }
        recording = true
        // Never LISTENING here: the microphone is not open yet, and only run() knows when it is.
        // Saying so early is what made the first words go missing -- the user reads "Listening",
        // speaks, and is talking to a stream that has not started.
        //
        // STARTING rather than LOADING when the model is already warm, because all that remains
        // then is opening the microphone. That is brief enough that "Loading dictation..." would
        // flash and vanish, which looks like a glitch; STARTING carries no message at all and
        // leaves the strip as it was until LISTENING replaces it.
        setState(if (recognizer == null) State.LOADING else State.STARTING)
        worker = thread(name = "dictation") { run(listener) }
    }

    fun stop() {
        recording = false
    }

    fun release() {
        stop()
        worker?.join(1000)
        worker = null
        spotting?.release()
        spotting = null
    }

    private fun run(listener: Listener) {
        if (!load()) {
            recording = false
            post { listener.onUnavailable(Reason.MODEL_FAILED) }
            setState(State.IDLE)
            return
        }
        // load() returning true means this is non-null; the branch is unreachable in practice,
        // but bailing out without clearing `recording` would wedge the engine in a state where
        // the microphone key does nothing at all, so it clears up after itself like the rest.
        val detector = vad ?: run {
            recording = false
            post { listener.onUnavailable(Reason.MODEL_FAILED) }
            setState(State.IDLE)
            return
        }
        detector.reset()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, VAD_WINDOW * 4) * 2,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord failed", t)
            null
        }
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            recording = false
            post { listener.onUnavailable(Reason.NO_MICROPHONE) }
            setState(State.IDLE)
            return
        }

        try {
            recorder.startRecording()
            val pcm = ShortArray(VAD_WINDOW)
            val samples = FloatArray(VAD_WINDOW)
            // The stream is not live when startRecording() returns. The first reads come back
            // with whatever the capture pipeline had lying in the buffer -- often a burst of
            // stale or near-silent samples delivered instantly -- and the hardware needs a
            // moment before it is really carrying the room. Feeding that to the VAD is what
            // clipped the opening words: it either took the junk for speech and cut a segment
            // before the sentence started, or took it for silence and armed the gate late.
            //
            // So: drop it, and only then say LISTENING. The prompt now means the microphone is
            // actually open, which is what a user speaking the instant they read it relies on.
            val primeUntil = System.nanoTime() + WARMUP_DISCARD_NANOS
            while (recording && System.nanoTime() < primeUntil) {
                if (recorder.read(pcm, 0, pcm.size) <= 0) break
            }
            if (!recording) return
            setState(State.LISTENING)
            while (recording) {
                val read = recorder.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                for (i in 0 until read) samples[i] = pcm[i] / 32768f
                detector.acceptWaveform(if (read == VAD_WINDOW) samples else samples.copyOf(read))
                drainSegments(listener)
            }
            // Whatever was still being spoken when the key was released is the end of the
            // sentence, and dropping it would silently lose the last few words.
            detector.flush()
            drainSegments(listener)
        } catch (t: Throwable) {
            Log.e(TAG, "dictation failed", t)
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            recording = false
            setState(State.IDLE)
        }
    }

    private fun drainSegments(listener: Listener) {
        val detector = vad ?: return
        val engine = recognizer ?: return
        while (!detector.empty()) {
            val segment = detector.front()
            detector.pop()
            if (segment.samples.isEmpty()) continue
            setState(State.TRANSCRIBING)
            val transcript = transcribe(engine, segment.samples)
            // The same audio, through the other recogniser. Runs after the transcription rather
            // than on another thread: the spotter is a 3M-parameter model against SenseVoice's
            // 239 MB, so the cost is noise next to the decode that just happened, and keeping
            // them sequential means no lock is needed around a segment's two results.
            val detections = spotting?.detect(segment.samples, SAMPLE_RATE).orEmpty()
            logSegment(segment.samples.size, transcript.text, detections)
            if (transcript.text.isNotBlank() || detections.isNotEmpty()) {
                post { listener.onText(transcript, detections) }
            }
            if (recording) setState(State.LISTENING)
        }
    }

    /**
     * Records what the VAD actually cut and what the model made of it.
     *
     * This exists to settle one specific question: whether a spoken punctuation command ends up
     * alone in its own segment. [MIN_SILENCE_SECONDS] is 0.35 s so that a mid-sentence language
     * switch gets its own language decision, and a punctuation command is bracketed by the same
     * kind of hesitation -- so the same cut may be isolating commands too. SenseVoice is not a
     * streaming model and decodes each segment whole, so a command alone in a short segment is
     * decoded with none of the surrounding sentence as context, which is the condition under
     * which "comma" is most likely to come back as "coma" or "comm".
     *
     * That is a hypothesis about the user's speech, not something readable from the source, and
     * it decides where the fix belongs: a short segment holding one near-miss word argues for
     * changing segmentation, while a near-miss inside a long segment argues for an alias table
     * in [SpokenPunctuation] instead. `wordCount` is what separates the two.
     *
     * Logged at debug, so it is off unless asked for:
     *
     *     adb shell setprop log.tag.Dictation DEBUG
     *     adb logcat -s Dictation
     */
    private fun logSegment(
        sampleCount: Int,
        text: String,
        detections: List<CommandMerge.Detection>,
    ) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) return
        val seconds = sampleCount.toFloat() / SAMPLE_RATE
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val fired = detections.joinToString(",") { "%s@%.2f".format(it.id, it.seconds) }
        Log.d(
            TAG,
            "segment %.2fs wordCount=%d keywords=[%s] text=%s"
                .format(seconds, words.size, fired, text.trim()),
        )
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Decodes one segment, repairing the code-switch failure when it shows.
     *
     * The automatic pass is authoritative. When it contains Latin words that are not English,
     * the audio under each run of them -- and only that audio -- is decoded again forced to
     * Chinese, and the run is replaced only when [CodeSwitch.choose] judges the result a pure
     * repair. A segment of ordinary English costs exactly one decode, as before.
     */
    private fun transcribe(engine: OfflineRecognizer, samples: FloatArray): CommandMerge.Transcript {
        val auto = decode(engine, samples)
        val words = englishWords ?: return auto.transcript
        val isEnglish: (String) -> Boolean = { it.lowercase() in words }
        if (!CodeSwitch.suspectsMissedChinese(auto.text, auto.lang, isEnglish)) return auto.transcript

        val zh = zhRecognizer ?: return auto.transcript
        val runs = CodeSwitch.suspectRuns(
            auto.transcript,
            segmentSeconds = samples.size.toFloat() / SAMPLE_RATE,
            isEnglish = isEnglish,
        )
        val repairs = runs.mapNotNull { run ->
            val from = ((run.startSeconds - SPAN_PAD_SECONDS) * SAMPLE_RATE).toInt().coerceAtLeast(0)
            val to = ((run.endSeconds + SPAN_PAD_SECONDS) * SAMPLE_RATE).toInt().coerceAtMost(samples.size)
            if (to <= from) return@mapNotNull null
            val forced = try {
                decode(zh, samples.copyOfRange(from, to)).text.trim()
            } catch (t: Throwable) {
                Log.e(TAG, "forced-zh retry failed", t)
                return@mapNotNull null
            }
            val chosen = CodeSwitch.choose(run.text, forced, isEnglish)
            if (chosen == run.text) {
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "code-switch retry refused: run=[${run.text}] zh=[$forced]")
                }
                null
            } else {
                run to chosen
            }
        }
        if (repairs.isEmpty()) return auto.transcript
        Log.i(TAG, "code-switch repair applied to ${repairs.size} of ${runs.size} spans")
        // The repair is spliced into the auto decode's own tokens, so every other word keeps
        // the timing the punctuation merge matches detections against.
        return CodeSwitch.splice(auto.transcript, repairs)
    }

    private class Decoded(val transcript: CommandMerge.Transcript, val lang: String) {
        val text: String get() = transcript.text
    }

    private fun decode(engine: OfflineRecognizer, samples: FloatArray): Decoded {
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            engine.decode(stream)
            val result = engine.getResult(stream)
            Decoded(
                CommandMerge.Transcript(
                    text = result.text,
                    tokens = result.tokens.toList(),
                    timestamps = result.timestamps.toList(),
                ),
                result.lang,
            )
        } finally {
            stream.release()
        }
    }

    private fun setState(next: State) {
        if (state == next) return
        state = next
        post { listener?.onStateChanged(next) }
    }

    private fun post(block: () -> Unit) = main.post(block)
}
