package com.offlinekeyboard.ime.tap

import com.offlinekeyboard.ime.layout.LayoutGeometry

/**
 * How likely a touch point is, given the letter that was aimed at.
 *
 * Every number here was measured off `data/gesture-bank.jsonl` rather than chosen. 94 recorded
 * plain taps carry both the point the finger landed on and the letter the passage had asked for,
 * which is exactly the pair this model is about; `tools/fit_spatial.py` reads them.
 *
 * **The thumb does not aim at the middle of the key.** It lands a fifth of a key height low and a
 * little left, on every one of the eight keys the bank covers and in all three letter rows:
 * dy runs from +0.118 on `u` to +0.348 on `k`, and is positive everywhere. That bias is larger
 * than the scatter around it -- +0.204 against a standard deviation of 0.130 -- so a model centred
 * on the drawn key spends most of its error budget on an offset it could simply have subtracted.
 * Correcting it is worth more than anything the language model does, and costs nothing.
 *
 * The offset is one global pair rather than a table per key, and that is a statement about the
 * bank rather than about thumbs. Eight keys is not twenty-six, and the per-key means genuinely do
 * differ -- 0.118 to 0.348 is far outside the standard error of about 0.035 on each. A per-key
 * table is the right shape and the wrong thing to fit today; the measured values are written down
 * in NOTES.md so that the day the bank covers the alphabet, it is a data change.
 *
 * Sigma is deliberately taken from the whole letter grid rather than from each key's own
 * rectangle. Every letter key is the same size, so a shared sigma makes the normalising constant
 * shared too, and [logLikelihood] can drop it and still be comparable across letters.
 */
class SpatialModel(
    /** Where the finger actually lands, relative to the drawn centre, in key widths. */
    val offsetX: Float = MEASURED_OFFSET_X,
    /** The same, in key heights. Positive is down the screen. */
    val offsetY: Float = MEASURED_OFFSET_Y,
    val sigmaX: Float = MEASURED_SIGMA_X,
    val sigmaY: Float = MEASURED_SIGMA_Y,
) {

    /** One letter a tap could have been, and what the touch point says about it. */
    data class Candidate(val letter: Char, val logLikelihood: Float)

    /**
     * ln P(touch | letter), up to a constant every letter shares.
     *
     * Constant-free because only differences are ever used: this is compared against the same
     * quantity for a competing letter, and against a prior measured in the same nats.
     */
    fun logLikelihood(x: Float, y: Float, letter: Char, geometry: LayoutGeometry): Float {
        val rect = geometry.letterKeys.getOrNull(letter - 'a') ?: return NEVER
        val dx = (x - rect.centerX) / geometry.keyUnit - offsetX
        val dy = (y - rect.centerY) / geometry.keyHeight - offsetY
        val zx = dx / sigmaX
        val zy = dy / sigmaY
        return -0.5f * (zx * zx + zy * zy)
    }

    /**
     * The letters this tap could plausibly have meant, best first.
     *
     * [priorRange] is the whole dynamic range of the language model, from [WordIndex.priorRange].
     * A letter worse than the best by more than that is dropped, and dropping it is safe in the
     * strong sense: no word in the lexicon, however common, could make up the difference, so no
     * possible continuation of the sentence would have chosen it. A tap that leaves one candidate
     * standing is therefore *pinned* -- it has no alternative reading and cannot be revised, which
     * is what keeps accurate typing from ever visibly changing under the finger.
     *
     * On the measured sigmas, and the shipped lexicon's range of 13.0 nats, that band reaches
     * 0.41 of a key width and 0.45 of a key height from where the thumb is expected to land --
     * past the far edge of the drawn key in both directions. Only genuinely borderline taps, on
     * the edge of a key or in the channel between two rows, come back with anything to decide.
     */
    fun candidates(
        x: Float,
        y: Float,
        geometry: LayoutGeometry,
        priorRange: Float,
    ): List<Candidate> {
        val all = ArrayList<Candidate>(4)
        var best = NEVER
        for (i in 0 until 26) {
            if (geometry.letterKeys[i] == null) continue
            val letter = 'a' + i
            val score = logLikelihood(x, y, letter, geometry)
            if (score > best) best = score
            all += Candidate(letter, score)
        }
        val floor = best - priorRange
        all.retainAll { it.logLikelihood >= floor }
        all.sortByDescending { it.logLikelihood }
        return all
    }

    companion object {
        /** A letter this layout does not have; never a candidate, never the best. */
        private const val NEVER = -Float.MAX_VALUE

        /**
         * Mean landing point relative to the drawn key centre, over the bank's 94 plain taps.
         * Left is negative, down is positive.
         *
         * **These four numbers are known to be wrong and are still here on purpose.** Refitted
         * on 2031 taps rather than 94, and with the space presses that had been filed under the
         * one-letter words `a` and `i` removed, the bank says (+0.000, +0.099) and sigma
         * (0.227, 0.208) -- the offset is half what was measured and the scatter is nearly
         * double. `tools/fit_spatial.py` prints them.
         *
         * Shipping them is not a data change, which is why it has not been done here. Sigma
         * decides the pinning band, and the band is what guarantees that accurately typed text
         * is never revised. At sigma 0.122 a tap is pinned until it lands 0.41 key widths from
         * where it was aimed; at 0.227 the band is 0.003, which is to say there is none, and a
         * perfectly centred tap gives at most 10.0 nats against its sideways neighbour against
         * a lexicon range of 13.0. Under the honest sigma the language model would get a say in
         * every letter typed, which is the autocorrect this keyboard exists not to do.
         *
         * So the measurement and the rule have to move together: either the band stops being
         * derived from the corpus-wide worst case and starts being derived from the gap between
         * the two readings actually in contention, or horizontal pinning is given up and said
         * so out loud. That is a design decision, and it is not one to make as a side effect of
         * a refit.
         */
        const val MEASURED_OFFSET_X = -0.063f
        const val MEASURED_OFFSET_Y = 0.204f

        /**
         * Scatter about that landing point, in key widths and key heights.
         *
         * These are about the *corrected* centre, which is the whole reason they are this small.
         * Measured about the drawn centre they are 0.137 and 0.242, and the vertical figure in
         * particular is nearly double: rows are 1.26 key heights apart, so an uncorrected model
         * puts a neighbouring row within about five sigma and can never pin a tap vertically at
         * all. Correcting the offset is what buys the pin.
         */
        const val MEASURED_SIGMA_X = 0.122f
        const val MEASURED_SIGMA_Y = 0.130f
    }
}
