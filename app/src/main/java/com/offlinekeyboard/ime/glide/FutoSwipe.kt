package com.offlinekeyboard.ime.glide

import android.content.Context
import android.util.Log
import com.offlinekeyboard.ime.gesture.PathPoint
import com.offlinekeyboard.ime.layout.LayoutGeometry
import org.futo.ml.inference.SwipeDecoder
import java.io.File

private const val TAG = "FutoSwipe"

/** Where the models live in the APK, put there by tools/fetch_swipe_runtime.sh. */
private const val ASSET_DIR = "swipe"

/**
 * Glide decoding through FUTO's neural models: a layout-agnostic encoder, an English decoder,
 * and a context language model, with dictionary-constrained beam search.
 *
 * Everything hard about swipe typing is in the models rather than here. What this class owes them
 * is the part their own documentation warns about hardest, and the part nothing can check: the
 * coordinates. The encoder is handed the key centres as a runtime tensor and the finger's path in
 * the same [0,1] frame, and if that frame is wrong -- measured over the wrong rows, or over the
 * view instead of the keys -- there is no error, only quietly worse words. [LayoutGeometry] owns
 * that frame and says why it is the three letter rows and nothing else.
 *
 * **What comes back is a glided form, not a spelling.** The beam search walks an `ITrie` whose
 * edges are the 26 letters of the layout, and the built-in trie reconstructs its answer by walking
 * that same parent chain -- so a word is only ever spelled in the alphabet it was traversed in.
 * The library anticipates this and leaves a hook for it (`ITrie::get_word` is documented as the
 * place to "include them here" for apostrophes not on the layout), but the hook is only reachable
 * from a custom trie: `load_trie_simple`, which is what our JNI patch calls, parses the surface
 * forms into a local `ParsedCombined` and then keeps only the alpha-forms, dropping the surfaces
 * when it returns. Restoring the spelling on this side costs a map lookup and needs no native
 * rebuild -- which matters, because the `.so` is prebuilt and pinned.
 *
 * The models and the native library are fetched and built rather than committed, so [open]
 * returning null is the ordinary state of a checkout that has not run
 * `tools/fetch_swipe_runtime.sh`. Nothing decodes glides in that state -- gliding types nothing,
 * and the log says why -- which is deliberately more obvious than the quieter alternative of a
 * worse decoder nobody was told they were using.
 */
class FutoSwipe private constructor(
    private val decoder: SwipeDecoder,
    private val dictionary: SwipeTrie,
    private val lexicon: Lexicon,
) : GlideEngine, AutoCloseable {

    override val name = "futo"

    /** The geometry the engine is currently configured for, so a resize re-sends the layout. */
    private var appliedLayout: String? = null

    /** Microseconds the last decode spent inside the models. Reported by the lab. */
    var lastMicros: Float = 0f
        private set

    override fun decode(path: List<PathPoint>, geometry: LayoutGeometry): List<GlideCandidate> {
        if (path.size < 2) return emptyList()
        applyLayout(geometry)

        val n = path.size
        val x = FloatArray(n)
        val y = FloatArray(n)
        val t = FloatArray(n)
        val start = path[0].t
        for (i in 0 until n) {
            x[i] = geometry.normalisedX(path[i].x)
            y[i] = geometry.normalisedY(path[i].y)
            // Milliseconds since the finger went down. Required, not cosmetic: the models are
            // trained on 60 Hz temporally-resampled paths, so the timestamps drive resampling --
            // which also means a bridged finger lift is presented as exactly what it was, a
            // stretch of time in which the finger covered ground.
            t[i] = (path[i].t - start).toFloat()
        }

        return runCatching {
            val results = decoder.recognize(x, y, t, topK = 5)
            lastMicros = decoder.lastTiming().totalUs
            // `score` is the library's final score: the CTC log-probability, length-normalised,
            // plus the frequency, length and context-LM terms. All of it is log-domain, which
            // is what makes a softmax over it a probability rather than an arbitrary squashing.
            val confidence = softmax(results.map { it.score })
            results.flatMapIndexed { i, result ->
                lexicon.spellings(result.word).map { GlideCandidate(it, confidence[i]) }
            }.distinctBy { it.word }
        }.onFailure { Log.w(TAG, "decode failed", it) }.getOrDefault(emptyList())
    }

    /**
     * Hands the engine our key centres.
     *
     * Sent on the first decode rather than at construction because the geometry does not exist
     * until the keyboard has been measured, and re-sent whenever it changes -- a rotation or a
     * split-screen resize moves every key, and a decoder still holding the old grid would be
     * scoring against a keyboard that is no longer on screen.
     */
    private fun applyLayout(geometry: LayoutGeometry) {
        val key = "${geometry.layout.id}@${geometry.widthPx}"
        if (key == appliedLayout) return

        val letters = StringBuilder()
        val cx = ArrayList<Float>(26)
        val cy = ArrayList<Float>(26)
        geometry.letterKeys.forEachIndexed { index, rect ->
            if (rect == null) return@forEachIndexed
            letters.append('a' + index)
            cx += geometry.normalisedX(rect.centerX)
            cy += geometry.normalisedY(rect.centerY)
        }
        if (letters.isEmpty()) return

        val ok = decoder.setMode(
            letters = letters.toString(),
            cx = cx.toFloatArray(),
            cy = cy.toFloatArray(),
            tries = longArrayOf(dictionary.itrie),
        )
        if (ok) appliedLayout = key else Log.w(TAG, "setMode rejected the layout")
    }

    override fun close() {
        runCatching { decoder.close() }
        runCatching { dictionary.close() }
    }

    companion object {
        /**
         * Loads the engine, or returns null if anything it needs is missing.
         *
         * Null means the runtime has not been fetched and built, which is a state a checkout can
         * legitimately be in -- so it is reported rather than thrown, and the caller turns glide
         * typing off rather than crashing the keyboard.
         *
         * Everything is staged out of the APK into files the native side can open by path.
         * ExecuTorch takes a path, not an asset handle, so there is no way to avoid the copy --
         * it is about ten megabytes, once.
         */
        fun open(context: Context, lexicon: Lexicon): FutoSwipe? {
            val home = File(context.filesDir, "swipe")
            val encoder = stage(context, home, "encoder") ?: return null
            val decoderDir = stage(context, home, "decoder")
            val contextLm = stage(context, home, "contextlm")

            val dictionaryFile = File(home, "en.combined")
            if (!dictionaryFile.isFile) {
                runCatching { lexicon.writeCombined(dictionaryFile) }
                    .onFailure { Log.w(TAG, "could not write the dictionary", it); return null }
            }

            return runCatching {
                val trie = SwipeTrie.load(dictionaryFile) ?: error("dictionary did not load")
                val swipe = SwipeDecoder(
                    encoderPath = File(encoder, "model_fp32.pte").absolutePath,
                    decoderPath = decoderDir?.let { File(it, "model_fp32.pte").absolutePath },
                    lmModelPath = contextLm?.let { File(it, "context_lm.pte").absolutePath },
                    lmVocabPath = contextLm?.let { File(it, "vocab.txt").absolutePath },
                )
                Log.i(TAG, "loaded: decoder=${swipe.hasDecoder()} lm=${swipe.hasLm()}")
                FutoSwipe(swipe, trie, lexicon)
            }.onFailure {
                // UnsatisfiedLinkError included: a build with no native library is a build that
                // uses the other decoder, not a build that crashes.
                Log.i(TAG, "not available (${it.javaClass.simpleName}: ${it.message})")
            }.getOrNull()
        }

        /**
         * Copies one model directory out of the APK. Null if the APK has no such models.
         *
         * A staged file is re-copied whenever its size differs from the asset's, which is not
         * fussiness: a model updated by re-running the fetch script has to replace the copy, and
         * "it exists and is not empty" does not notice that it has changed. It failed exactly
         * that way once already -- an earlier fetch left git-lfs *pointer* files in the assets,
         * 132 bytes each and perfectly non-empty, and they went on being used after the real
         * weights arrived.
         *
         * The size comes from the asset's own descriptor, which works because .pte is in the
         * build's noCompress list. If that ever stops being true the descriptor throws, and the
         * fallback is the old weaker check rather than no staging at all.
         */
        private fun stage(context: Context, home: File, name: String): File? {
            val target = File(home, name)
            val names = runCatching { context.assets.list("$ASSET_DIR/$name") }.getOrNull()
            if (names.isNullOrEmpty()) return null
            target.mkdirs()
            names.forEach { file ->
                val asset = "$ASSET_DIR/$name/$file"
                val out = File(target, file)
                val expected = runCatching {
                    context.assets.openFd(asset).use { it.length }
                }.getOrNull()
                val current = if (out.isFile) out.length() else -1L
                val staged = if (expected != null) current == expected else current > 0
                if (staged) return@forEach
                runCatching {
                    context.assets.open(asset).use { input ->
                        out.outputStream().use(input::copyTo)
                    }
                    Log.i(TAG, "staged $name/$file (${out.length()} bytes)")
                }.onFailure { Log.w(TAG, "could not stage $name/$file", it) }
            }
            return target
        }
    }
}
