package com.offlinekeyboard.ime.glide

import com.offlinekeyboard.ime.gesture.PathPoint
import com.offlinekeyboard.ime.layout.LayoutGeometry
import kotlin.math.exp

/**
 * Something that turns a glide path into words, best first.
 *
 * One implements it -- [FutoSwipe] -- and the interface is what is left of a second. A Kotlin
 * decoder was written first and then scored against FUTO's models on the same recorded glides;
 * it lost 79% to 95% and was deleted rather than kept as a fallback nobody would ever want to
 * fall back to. What survives is the shape that made the comparison possible, because the next
 * question of this kind will be answered the same way.
 *
 * It deliberately returns **words and a probability, never a raw score**. Any two engines produce
 * a score, and those scores mean entirely different things -- a squared distance plus a log
 * frequency, say, against a beam-search log-likelihood. Putting them in a shared type would invite
 * comparing them. A probability is the one number every engine can be asked for in the same
 * units, and it is needed: [GlideRejections] has to tell a redraw the decoder is sure about from
 * one it is guessing at, and nothing else here can say which is which.
 */
interface GlideEngine {

    /** For reports and for the lab's engine switch. */
    val name: String

    /**
     * Words for this path, best first, or empty when the path is not a word gesture.
     *
     * [path] is the raw touch samples in view pixels, gaps and all. Bridging a lifted finger is
     * the caller's business and neither engine is told that one happened: a glide with a skip in
     * it is a glide with a long straight segment, which is what a finger that kept going would
     * have drawn anyway.
     */
    fun decode(path: List<PathPoint>, geometry: LayoutGeometry): List<GlideCandidate>
}

/**
 * One word a path could have meant.
 *
 * [confidence] is the engine's probability, from 0 to 1, that this is the word the path
 * spelled. Spellings of one glided form -- `its` and `it's` -- carry the same number, because the
 * path cannot tell them apart and the engine was never asked to.
 */
data class GlideCandidate(val word: String, val confidence: Float)

/**
 * Log-domain scores turned into probabilities that sum to one.
 *
 * Normalised over the candidates the engine returned rather than over every word it could have
 * returned, so the numbers are a little generous -- the tail beyond the top few is missing from
 * the denominator. That is the direction to be wrong in for what they are used for: a word only
 * ever counts as "sure" when it has out-scored everything that came close.
 */
internal fun softmax(scores: List<Float>): FloatArray {
    if (scores.isEmpty()) return FloatArray(0)
    val best = scores.max()
    // Shifted by the best score so exp never overflows; the shift cancels in the division.
    val weights = FloatArray(scores.size) { exp((scores[it] - best).toDouble()).toFloat() }
    val total = weights.sum()
    for (i in weights.indices) weights[i] /= total
    return weights
}
