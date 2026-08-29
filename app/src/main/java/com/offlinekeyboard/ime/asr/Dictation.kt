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
 * middle of a clause costs accuracy on both halves. Half a second is longer than the gap between
 * words and shorter than the pause between sentences, which is the boundary worth cutting on.
 * The cap only exists so that speaking without pause still produces text eventually.
 */
private const val MIN_SILENCE_SECONDS = 0.5f
private const val MAX_SPEECH_SECONDS = 25f

/**
 * Offline dictation: microphone in, text out, nothing leaves the process.
 *
 * SenseVoice runs with **automatic language detection** rather than being told the keyboard's
 * mode. That is what `docs/ASR_BENCHMARK.md` measured -- 7.9% on code-switched Mandarin against
 * Apple's 58.9% -- and forcing a language is exactly what breaks a sentence that changes
 * language halfway through, which is the case this keyboard exists for. Shipping a different
 * configuration from the one that was scored would mean shipping something never measured.
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
        if (recognizer != null && vad != null) return true
        return try {
            val assets = context.assets
            recognizer = OfflineRecognizer(
                assetManager = assets,
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = "asr/sensevoice/model.int8.onnx",
                            // Empty means detect. See the class comment: this is the measured
                            // configuration, and the one that survives code-switching.
                            language = "",
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
            vad = null
            false
        }
    }

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
            val text = engine.createStream().let { stream ->
                stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                engine.decode(stream)
                val result = engine.getResult(stream).text
                stream.release()
                result
            }
            if (text.isNotBlank()) post { listener.onText(text) }
            if (recording) setState(State.LISTENING)
        }
    }

    private fun setState(next: State) {
        if (state == next) return
        state = next
        post { listener?.onStateChanged(next) }
    }

    private fun post(block: () -> Unit) = main.post(block)
}
