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
 *  - A segment whose text carries the romanised signature is decoded a second time forced to
 *    `zh`, and [CodeSwitch.choose] keeps whichever answer is better. The `auto` result is still
 *    what ships unless the retry demonstrably beats it.
 */
class Dictation(private val context: Context) {

    enum class State { IDLE, LOADING, LISTENING, TRANSCRIBING }

    interface Listener {
        fun onStateChanged(state: State)

        /** A finished segment. Called on the main thread, once per pause in speech. */
        fun onText(text: String)
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
        // LOADING until the models are up; on a warm start run() moves straight to LISTENING.
        setState(if (recognizer == null) State.LOADING else State.LISTENING)
        worker = thread(name = "dictation") { run(listener) }
    }

    fun stop() {
        recording = false
    }

    fun release() {
        stop()
        worker?.join(1000)
        worker = null
    }

    private fun run(listener: Listener) {
        if (!load()) {
            recording = false
            post { listener.onUnavailable(Reason.MODEL_FAILED) }
            setState(State.IDLE)
            return
        }
        val detector = vad ?: return
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
            setState(State.LISTENING)
            val pcm = ShortArray(VAD_WINDOW)
            val samples = FloatArray(VAD_WINDOW)
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
            val text = transcribe(engine, segment.samples)
            if (text.isNotBlank()) post { listener.onText(text) }
            if (recording) setState(State.LISTENING)
        }
    }

    /**
     * Decodes one segment, repairing the code-switch failure when it shows.
     *
     * The automatic pass is authoritative. The forced-Chinese pass only runs when the automatic
     * text carries the romanised signature, and only replaces it when it is actually better --
     * so a segment the model already got right costs exactly one decode, as before.
     */
    private fun transcribe(engine: OfflineRecognizer, samples: FloatArray): String {
        val auto = decode(engine, samples)
        if (!CodeSwitch.suspectsMissedChinese(auto.text, auto.lang)) return auto.text

        val zh = zhRecognizer ?: return auto.text
        val forced = try {
            decode(zh, samples)
        } catch (t: Throwable) {
            Log.e(TAG, "forced-zh retry failed", t)
            return auto.text
        }
        val chosen = CodeSwitch.choose(auto.text, forced.text)
        if (chosen != auto.text) {
            Log.i(TAG, "code-switch repair applied (auto lang=${auto.lang})")
        }
        return chosen
    }

    private class Decoded(val text: String, val lang: String)

    private fun decode(engine: OfflineRecognizer, samples: FloatArray): Decoded {
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            engine.decode(stream)
            val result = engine.getResult(stream)
            Decoded(result.text, result.lang)
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
