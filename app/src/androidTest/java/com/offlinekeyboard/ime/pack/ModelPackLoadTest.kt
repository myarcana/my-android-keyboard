package com.offlinekeyboard.ime.pack

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.offlinekeyboard.ime.glide.FutoSwipe
import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import com.offlinekeyboard.ime.glide.Lexicon
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The payload loads, wherever it lives: dictation's recogniser and VAD, and the glide decoder.
 *
 * Both used to fail silently in a `-Ppack` build -- the keyboard came up, the microphone key did
 * nothing and a glide typed nothing -- because the pack stores its `.so`s inside the APK rather
 * than extracting them, and the loader only looked for extracted files. Runs in the keyboard's
 * own process, so it exercises the real pack lookup; on a self-contained build it checks the
 * same thing against this APK's own copy.
 *
 *     ./gradlew :app:connectedDebugAndroidTest -Ppack \
 *         -Pandroid.testInstrumentationRunnerArguments.class=com.offlinekeyboard.ime.pack.ModelPackLoadTest
 */
@RunWith(AndroidJUnit4::class)
class ModelPackLoadTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun dictationModelsLoad() {
        Log.i(TAG, ModelPack.describe(context))
        val assets = ModelPack.payloadAssets(context)
        val recognizer = OfflineRecognizer(
            assetManager = assets,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(model = "asr/sensevoice/model.int8.onnx"),
                    tokens = "asr/sensevoice/tokens.txt",
                    numThreads = 1,
                    modelType = "sense_voice",
                ),
            ),
        )
        val vad = Vad(
            assetManager = assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(model = "asr/silero_vad.onnx"),
                sampleRate = 16000,
            ),
        )
        vad.release()
        recognizer.release()
    }

    @Test
    fun glideEngineLoads() {
        val lexicon = context.assets.open(LEXICON_ASSET).use(Lexicon::load)
        assertNotNull("FutoSwipe did not load; see logcat tag FutoSwipe", FutoSwipe.open(context, lexicon))
    }

    private companion object {
        const val TAG = "ModelPackLoadTest"
    }
}
