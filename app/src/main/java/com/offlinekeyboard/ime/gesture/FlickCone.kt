package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.LayoutGeometry
import kotlin.math.atan2
import kotlin.math.pow

/**
 * How often a word glided from one letter heads for another as its second letter.
 *
 * The one fact about English that [FlickCone] needs: whether a downward stroke leaving a key
 * could be the first leg of a word. `in` makes a stroke down from `i` ambiguous. Nothing makes
 * a stroke down from `h` ambiguous, because English has no `hb`, `hn` or `hv` words worth the
 * name. The layout alone cannot tell those two cases apart. They look the same on the keyboard;
 * the difference is in the words.
 *
 * Pure data, built once from the lexicon by the service and handed to the view, so the state
 * machine stays free of assets and Android and still replays on a laptop.
 */
class WordStarts private constructor(
    /** `share[a][b]`: the fraction of words beginning with letter a whose second letter is b. */
    private val share: Array<FloatArray>?,
) {

    /**
     * The fraction of words starting with [first] that continue to [second], in 0..1.
     *
     * With no lexicon every pair answers 1, meaning "assume a word could go there". Without word
     * statistics the only safe reading of a letter below a key is that a word might head for it.
     * That still leaves the bottom row open, since nothing is below it, and leaves the rest of
     * the board exactly as strict as it was.
     */
    fun share(first: Char, second: Char): Float {
        val table = share ?: return 1f
        val a = first.lowercaseChar() - 'a'
        val b = second.lowercaseChar() - 'a'
        if (a !in 0..25 || b !in 0..25) return 0f
        return table[a][b]
    }

    companion object {
        /** No lexicon: every letter below a key counts as somewhere a word could go. */
        val UNKNOWN = WordStarts(null)

        /**
         * Builds the table from a lexicon's glided forms and their frequencies.
         *
         * [logFrequency] is the lexicon's own unit, `100 * log10(count)`, so a word's weight is
         * its corpus count. Weighting by count rather than counting word types matters: `in` is
         * one word and outweighs hundreds of rare `ib-` words put together.
         */
        fun of(letters: List<String>, logFrequency: IntArray): WordStarts {
            val mass = Array(26) { DoubleArray(26) }
            val total = DoubleArray(26)
            letters.forEachIndexed { i, word ->
                if (word.length < 2) return@forEachIndexed
                val a = word[0] - 'a'
                val b = word[1] - 'a'
                if (a !in 0..25 || b !in 0..25) return@forEachIndexed
                val count = 10.0.pow(logFrequency[i] / 100.0)
                mass[a][b] += count
                total[a] += count
            }
            return WordStarts(
                Array(26) { a ->
                    FloatArray(26) { b ->
                        if (total[a] > 0.0) (mass[a][b] / total[a]).toFloat() else 0f
                    }
                },
            )
        }
    }
}

/**
 * How far off vertical a downward stroke from a key may lean and still be its flick.
 *
 * ## The problem this fixes
 *
 * [GestureConfig.verticalDominance] allows a flick about 13 degrees either side of straight
 * down on every key. That number was fitted on `o`, where it has to be that tight: the glide
 * `ok` leaves `o` about 25 degrees off vertical, and nothing else separates the two. But the
 * same 13 degrees applied everywhere. On `h`, where no English word leaves downward, a flick
 * that leaned 20 degrees became a glide, and the decoder then had to name a word for a path no
 * word makes. That produced `in`, `hub`, `iv` and `on` in place of `(`. On the bottom row no
 * key has letters below it at all, so there was nothing to separate the flick from, and it
 * still had to be as straight as a flick on `o`.
 *
 * The bank shows the lean is normal. Recorded flicks leave up to 16 degrees off vertical on the
 * top row, 20 on the home row and 30 on the bottom row, where the thumb is nearest its own knuckle.
 *
 * ## The rule
 *
 * Each side of each key gets its own half-angle. It opens up to [GestureConfig.flickConeMaxDegrees]
 * and stops [GestureConfig.flickConeMarginDegrees] short of the nearest letter below that a
 * word could actually head for. "Could actually" means at least
 * [GestureConfig.flickConeMinShare] of the words starting with this key go there next.
 * The configured 13 degrees is a floor, not something this replaces, so a key with a word
 * right underneath it, like `i` over `n`, is exactly as strict as before.
 *
 * What comes out on QWERTY:
 *
 *  - `i`, `o`, `e`, `k`: unchanged, because `in`, `on`, `ex` and `kn-` sit straight below them.
 *  - `h`, `j`, `f`, `l`, `y`, and the whole bottom row: the full cone both ways. No word leaves
 *    them downward.
 *  - `t`, `u`, `w`, `p`, `d`, `s`: open on one side, and exactly as strict as before on the
 *    side where the word is, e.g. `th`, `un`, `wa`, `pl`.
 *
 * The margin is measured, not guessed. A glide's first leg does not point at its second letter.
 * In the bank, `un` leaves up to 19 degrees off the line from `u` to `n`, and `th` up to 9. So
 * 20 degrees short of a letter is the nearest a flick can safely lean toward it.
 *
 * ## It changes the escape as well as the entry
 *
 * A flick becomes a glide once it has travelled [GestureConfig.flickToGlideRatio] key widths.
 * That was the other half of the complaint: a long, confident flick down from `h` ran past the
 * distance and turned into a word. Travel alone is no evidence of a word when every word
 * leaving the key goes somewhere else. So inside a side that was opened here, the stroke has
 * to *turn*, leave the cone, before it can be a glide. On the strict side it still escapes on
 * distance, as before, because there the continuing stroke really could be `in`.
 */
class FlickCone private constructor(
    /** Half-angle open to the left of straight down, in degrees. 0 when not widened. */
    val leftDegrees: Float,
    /** Half-angle open to the right of straight down, in degrees. 0 when not widened. */
    val rightDegrees: Float,
) {

    /** The half-angle on the side [dx] points to, in degrees. */
    fun degreesToward(dx: Float): Float = if (dx < 0f) leftDegrees else rightDegrees

    /**
     * Whether a displacement of ([dx], [dy]) lies inside this cone's opened part.
     *
     * Strictly the part *this* class opened. The configured narrow cone is tested by the machine
     * as it always was, so that test's exact inequality, and every gesture recorded under it,
     * is untouched.
     */
    fun contains(dx: Float, dy: Float): Boolean {
        if (dy <= 0f) return false
        val half = degreesToward(dx)
        if (half <= 0f) return false
        val angle = Math.toDegrees(atan2(kotlin.math.abs(dx), dy).toDouble())
        return angle <= half
    }

    companion object {
        /** Opens nothing: the machine as it was before cones existed. */
        val CLOSED = FlickCone(0f, 0f)

        /**
         * The cone for [key] on [geometry].
         *
         * Angles are measured between key centres in the geometry's own pixels, so a squashed
         * board, where the keys are closer together sideways, narrows the cones by exactly as
         * much as it moves the letters. A key that is not one of the 26 letters has no word
         * statistics and gets [CLOSED].
         */
        fun of(
            key: KeyRect,
            geometry: LayoutGeometry,
            words: WordStarts,
            config: GestureConfig,
        ): FlickCone {
            val cap = config.flickConeMaxDegrees
            if (cap <= 0f) return CLOSED
            val id = key.key.id
            if (id.length != 1 || id[0] !in 'a'..'z') return CLOSED
            val margin = config.flickConeMarginDegrees

            var left = cap
            var right = cap
            geometry.letterKeys.forEachIndexed { index, other ->
                if (other == null || other === key) return@forEachIndexed
                // Below, by at least half a row: a neighbour on the same row is sideways, and a
                // flick is never sideways.
                val dy = other.centerY - key.centerY
                if (dy < geometry.keyHeight * 0.5f) return@forEachIndexed
                val dx = other.centerX - key.centerX
                val phi = Math.toDegrees(atan2(dx, dy).toDouble()).toFloat()
                // Too far round to bound a cone that stops at the cap anyway.
                if (kotlin.math.abs(phi) >= cap + margin) return@forEachIndexed
                if (words.share(id[0], 'a' + index) < config.flickConeMinShare) {
                    return@forEachIndexed
                }
                if (phi >= 0f) right = minOf(right, phi - margin)
                if (phi <= 0f) left = minOf(left, -phi - margin)
            }
            // A side that would open no wider than the configured narrow cone has opened nothing,
            // and must be 0 rather than that small angle. It is not only redundant: an open
            // side also stops distance promoting a flick into a glide, and on `o`, whose left
            // side would come out at 8 degrees because of `ok`, that would trap gliding `ol`.
            val narrow = Math.toDegrees(atan2(1.0, config.verticalDominance.toDouble())).toFloat()
            fun opened(side: Float) = if (side > narrow) side else 0f
            return FlickCone(opened(left), opened(right))
        }
    }
}
