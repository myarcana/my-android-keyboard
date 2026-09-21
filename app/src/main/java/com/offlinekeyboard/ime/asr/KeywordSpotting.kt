package com.offlinekeyboard.ime.asr

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

private const val TAG = "KeywordSpotting"

/**
 * The second recogniser: a small streaming model that listens only for spoken punctuation.
 *
 * SenseVoice decides what words were said by ranking candidates against a text prior, and in
 * that prior the punctuation sense of "comma" is rare -- it is a word that appears in writing
 * *about* punctuation, far less often than "come" or "calm". A command is also spoken unstressed
 * in a prosodic gap, so its acoustics are reduced exactly where the prior is weakest. Those two
 * facts together are why "comma" comes back as "coma" or "comm", and why a better transcription
 * model would not fix it: the question "which word is this" has no good answer when the audio is
 * a command rather than prose.
 *
 * This model is asked a different question -- did a known phoneme sequence fire above
 * [THRESHOLD] -- which has no competing vocabulary to lose against and degrades gracefully as
 * the word is reduced. It is 5 MB against SenseVoice's 239 MB and runs on the same audio.
 *
 * Everything here is optional. The spotter is a repair on top of a working recogniser: if the
 * model is missing or fails to load, [detect] returns nothing and dictation behaves exactly as
 * it did before, with spoken punctuation handled by [SpokenPunctuation]'s word matching alone.
 */
class KeywordSpotting(private val assets: AssetManager) {

    /**
     * How confident a detection must be.
     *
     * The upstream default is 0.25, which is tuned for wake words, where a false fire costs the
     * user a spurious activation. Here a false fire costs a punctuation mark in the middle of a
     * sentence, and a miss costs the word "coma" appearing instead -- both visible, both easy to
     * fix, neither catastrophic. Left at the default until the logs say which way it errs.
     */
    private companion object {
        const val THRESHOLD = 0.25f
        const val MODEL_DIR = "asr/kws"
        const val KEYWORDS = "asr/punctuation_keywords.txt"
    }

    @Volatile private var spotter: KeywordSpotter? = null

    /** Whether the spotter loaded. False means dictation runs without command detection. */
    val available: Boolean get() = spotter != null

    /**
     * Loads the model. Returns false if it is unavailable, which is not an error: the caller
     * carries on without it.
     */
    @Synchronized
    fun load(): Boolean {
        spotter?.let { return true }
        return try {
            spotter = KeywordSpotter(
                assetManager = assets,
                config = KeywordSpotterConfig(
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = "$MODEL_DIR/encoder.onnx",
                            decoder = "$MODEL_DIR/decoder.onnx",
                            joiner = "$MODEL_DIR/joiner.onnx",
                        ),
                        tokens = "$MODEL_DIR/tokens.txt",
                        numThreads = 1,
                        modelType = "zipformer2",
                        // The model's own configuration: Chinese keywords are written as pinyin
                        // initial plus tone-marked final, which is what "cjkchar" selects.
                        modelingUnit = "cjkchar",
                    ),
                    keywordsFile = KEYWORDS,
                    keywordsThreshold = THRESHOLD,
                ),
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "keyword spotter unavailable; spoken punctuation falls back to word matching", t)
            spotter = null
            false
        }
    }

    /**
     * Runs the spotter over one VAD segment and returns the commands it heard.
     *
     * A fresh stream per segment, deliberately. The spotter is a streaming model with internal
     * state, and a segment is already the unit SenseVoice decodes, so resetting at the same
     * boundary keeps the two recognisers' views of the audio aligned -- a detection's timestamp
     * means the same thing as a transcript timestamp, which is what makes the merge possible.
     *
     * Timestamps come back relative to the start of the stream, which is the start of the
     * segment, so they need no adjustment before [CommandMerge] sees them.
     */
    fun detect(samples: FloatArray, sampleRate: Int): List<CommandMerge.Detection> {
        val engine = spotter ?: return emptyList()
        return try {
            val detections = mutableListOf<CommandMerge.Detection>()
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                // Tail padding: the decoder needs trailing frames to emit a keyword that ends
                // at the very end of the segment, which is exactly where a sentence-final
                // "period" or "question mark" lands.
                stream.acceptWaveform(FloatArray(sampleRate / 2), sampleRate)
                stream.inputFinished()
                while (engine.isReady(stream)) {
                    engine.decode(stream)
                    val result = engine.getResult(stream)
                    if (result.keyword.isNotEmpty()) {
                        detections += CommandMerge.Detection(
                            id = result.keyword.removePrefix("@"),
                            seconds = result.timestamps.firstOrNull() ?: 0f,
                        )
                        // Reset after a fire, or the same keyword keeps being reported.
                        engine.reset(stream)
                    }
                }
            } finally {
                stream.release()
            }
            detections
        } catch (t: Throwable) {
            Log.e(TAG, "keyword detection failed for one segment", t)
            emptyList()
        }
    }

    fun release() {
        spotter?.release()
        spotter = null
    }
}
