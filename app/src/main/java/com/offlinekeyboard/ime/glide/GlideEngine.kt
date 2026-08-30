package com.offlinekeyboard.ime.glide

import com.offlinekeyboard.ime.gesture.PathPoint
import com.offlinekeyboard.ime.layout.LayoutGeometry

/**
 * Something that turns a glide path into words, best first.
 *
 * One implements it -- [FutoSwipe] -- and the interface is what is left of a second. A Kotlin
 * decoder was written first and then scored against FUTO's models on the same recorded glides;
 * it lost 79% to 95% and was deleted rather than kept as a fallback nobody would ever want to
 * fall back to. What survives is the shape that made the comparison possible, because the next
 * question of this kind will be answered the same way.
 *
 * It deliberately returns **words and nothing else**. Any two engines produce a score, and those
 * scores mean entirely different things -- a squared distance plus a log frequency, say, against
 * a beam-search log-likelihood. Putting them in a shared type would invite comparing them, and
 * the only honest comparison is whether the word that came out is the word that was wanted.
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
    fun decode(path: List<PathPoint>, geometry: LayoutGeometry): List<String>
}
