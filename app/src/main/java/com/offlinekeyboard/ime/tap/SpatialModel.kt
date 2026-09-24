package com.offlinekeyboard.ime.tap

import com.offlinekeyboard.ime.layout.KeyType
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
    /** The scatter the rescue pass measures against. See [rescueLogLikelihood]. */
    val rescueSigmaX: Float = REFIT_SIGMA_X,
    val rescueSigmaY: Float = REFIT_SIGMA_Y,
) {

    /** One letter a tap could have been, and what the touch point says about it. */
    data class Candidate(val letter: Char, val logLikelihood: Float)

    /**
     * ln P(touch | letter), up to a constant every letter shares.
     *
     * Constant-free because only differences are ever used: this is compared against the same
     * quantity for a competing letter, and against a prior measured in the same nats.
     */
    fun logLikelihood(x: Float, y: Float, letter: Char, geometry: LayoutGeometry): Float =
        logLikelihood(x, y, letter, geometry, sigmaX, sigmaY)

    /** The same, against an explicit scatter. See [rescueLogLikelihood] for why there are two. */
    fun logLikelihood(
        x: Float,
        y: Float,
        letter: Char,
        geometry: LayoutGeometry,
        sx: Float,
        sy: Float,
    ): Float {
        val rect = geometry.letterKeys.getOrNull(letter - 'a') ?: return NEVER
        val dx = (x - rect.centerX) / geometry.keyUnit - offsetX
        val dy = (y - rect.centerY) / geometry.keyHeight - offsetY
        return logLikelihood(dx, dy, sx, sy)
    }

    /**
     * The same, from an offset [TapDecoder.Tap] already resolved against the keys the finger saw.
     *
     * This is the form the decoder uses. The pixels and the key rectangles were consumed at the
     * moment of the press, so a held word can no longer be re-scored against a grid that has
     * since moved -- see [TapDecoder.Tap].
     */
    fun logLikelihood(tap: TapDecoder.Tap, letter: Char, sx: Float, sy: Float): Float {
        val d = tap.offsets.getOrNull(letter - 'a') ?: return NEVER
        return logLikelihood(d[0], d[1], sx, sy)
    }

    private fun logLikelihood(dx: Float, dy: Float, sx: Float, sy: Float): Float {
        val zx = dx / sx
        val zy = dy / sy
        return -0.5f * (zx * zx + zy * zy)
    }

    /**
     * ln P(touch | letter) as the rescue pass measures it, on the better-sampled scatter.
     *
     * Two sigmas is not two opinions about thumbs. It is one measurement used for two different
     * questions, and the questions can afford different error budgets.
     *
     * [logLikelihood] answers "is this tap ambiguous *on its own*". It has no context and cannot
     * acquire any, so it must not overstate ambiguity -- an overstated one hands the language
     * model a letter the user typed deliberately. The tight [MEASURED_SIGMA_X] is the
     * conservative choice there, and the pinning guarantee is built on it.
     *
     * This answers a question that only arises once the rest of the word is known and certain:
     * given that this one letter is the only thing wrong, could the finger have missed? That is
     * a question about how far thumbs genuinely scatter, and the honest answer is [REFIT_SIGMA_X]
     * -- fitted over 2031 taps rather than 94, and documented on [MEASURED_SIGMA_X] as the better
     * measurement that could not be shipped globally because it collapses the pinning band.
     *
     * Using it *only* here is what makes it safe. It never widens the first pass, so accurate
     * typing is pinned exactly as it was, and this is unreachable unless the surrounding letters
     * are already certain and a real, commoner word is waiting. The strictness that protected the
     * user has not been given up; it has moved to where the evidence to afford it exists.
     */
    fun rescueLogLikelihood(x: Float, y: Float, letter: Char, geometry: LayoutGeometry): Float =
        logLikelihood(x, y, letter, geometry, rescueSigmaX, rescueSigmaY)

    /** The same, from a [TapDecoder.Tap] already resolved against the keys the finger saw. */
    fun rescueLogLikelihood(tap: TapDecoder.Tap, letter: Char): Float =
        logLikelihood(tap, letter, rescueSigmaX, rescueSigmaY)

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
     * On the measured sigmas, and the shipped lexicon's range of 18.6 nats, that band reaches
     * 0.34 of a key width and 0.38 of a key height from where the thumb is expected to land --
     * still past the edge of the drawn key in both directions. Only genuinely borderline taps,
     * on the edge of a key or in the channel between two rows, come back with anything to decide.
     * A dead-centre press holds 45 nats over its sideways neighbour and 47 over the row above,
     * so it is pinned with room to spare.
     */
    fun candidates(tap: TapDecoder.Tap, priorRange: Float): List<Candidate> =
        band(tap, priorRange, sigmaX, sigmaY)

    /**
     * The letters a tap could have meant if its *neighbours* vouch for it, best first.
     *
     * This is [candidates] with a wider band, and the width is the entire idea. [candidates]
     * asks a question about one touch point in isolation, so it has to be conservative: it
     * cannot know whether the letters around this one will turn out to be certain or themselves
     * a mess, so it assumes the worst and pins anything it can. That assumption is what makes
     * accurate typing stable, and it is also what makes `teavhers` permanent -- the `v` tap is
     * *accurate*, the finger really did hit `v`, so `c` is pruned before the `h`, `e`, `r`, `s`
     * that would have argued for it are ever looked at.
     *
     * The wider band is affordable exactly when that assumption is not being made. [extraNats]
     * is the slack the caller has already proved is available: evidence from letters other than
     * this one, which [candidates] necessarily ignored. Spending it here is not a loosening of
     * the model, it is the same budget accounted for honestly -- a letter is kept when the touch
     * evidence against it is less than the lexicon evidence for it, and both sides of that
     * comparison are in nats.
     *
     * Ordering by touch likelihood, best first, keeps the literal reading at the head: the drawn
     * key is the best explanation of its own touch point by construction, so a caller that takes
     * the first candidate on a tie gets the letter the user actually pressed.
     */
    fun rescueCandidates(
        tap: TapDecoder.Tap,
        priorRange: Float,
        extraNats: Float,
    ): List<Candidate> =
        band(tap, priorRange + extraNats.coerceAtLeast(0f), rescueSigmaX, rescueSigmaY)

    /** The letters within [width] nats of the best explanation of [tap], best first. */
    private fun band(
        tap: TapDecoder.Tap,
        width: Float,
        sx: Float,
        sy: Float,
    ): List<Candidate> {
        val all = ArrayList<Candidate>(6)
        var best = NEVER
        for (i in 0 until 26) {
            if (tap.offsets[i] == null) continue
            val letter = 'a' + i
            val score = logLikelihood(tap, letter, sx, sy)
            if (score > best) best = score
            all += Candidate(letter, score)
        }
        val floor = best - width
        all.retainAll { it.logLikelihood >= floor }
        all.sortByDescending { it.logLikelihood }
        return all
    }

    /**
     * How far above its expected landing point a press sits, for the nearest letter key, in
     * standard deviations. Negative means below.
     *
     * This is the same measurement [logLikelihood] is built on, reported as a signed distance
     * instead of a score, and it exists for the one question the letter-versus-letter comparison
     * cannot answer: whether a touch in the suggestion strip was a press aimed high at the top
     * row, or a tap on the strip itself.
     *
     * That question needs a *scale*, not a ranking. Comparing an emoji cell against a letter as
     * a 27th candidate is not possible honestly -- the strip has no measured landing distribution
     * and [WordIndex]'s prior is about English spelling, so any likelihood assigned to "the user
     * meant the emoji" would be invented. What is measured, and is exactly what is needed here,
     * is how thumbs scatter around a key they are aiming at: sigma. A press one sigma above the
     * expected landing point is an ordinary press; one five sigma above it is not a press at that
     * key at all, whatever is drawn under it.
     *
     * The vertical offset is what gives this its power. Thumbs land [MEASURED_OFFSET_Y] *low* --
     * a fifth of a key height below the drawn centre -- so a touch arriving above a key's centre
     * is already unusual before it has left the key, and the strip above is several sigma further
     * again. The separation is large enough that the boundary does not have to be guessed.
     *
     * Horizontal distance is deliberately excluded. A press is assigned to the column it is over
     * and the question here is only about height: a strip tap at the far left is as much a strip
     * tap as one in the middle, and folding dx in would make the answer depend on how well
     * centred the touch happened to be over whichever key sits below it.
     */
    fun sigmasAboveLetterRow(x: Float, y: Float, geometry: LayoutGeometry): Float {
        // The number and symbol planes have no letters, and "no letter nearby" used to fall
        // through to 0 sigma -- an ordinary press -- which handed the *entire* strip to the digit
        // and symbol row beneath it: every emoji tap typed the key underneath instead. On those
        // planes the row under the strip is the character keys, and a thumb aiming at a digit
        // scatters exactly as one aiming at a letter does, so measure against those.
        val targets = geometry.letterKeys.filterNotNull().ifEmpty {
            geometry.keyRects.filter { it.key.type == KeyType.CHARACTER }
        }
        val rect = targets.minByOrNull { r ->
            val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            dy * dy + dx * dx
        } ?: return 0f
        val expected = rect.centerY + offsetY * geometry.keyHeight
        return (expected - y) / (sigmaY * geometry.keyHeight)
    }

    companion object {
        /** A letter this layout does not have; never a candidate, never the best. */
        private const val NEVER = -Float.MAX_VALUE

        /**
         * How far above a key's expected landing point a touch stops being a press at that key,
         * in standard deviations.
         *
         * This replaces a dead strip of screen at the bottom of the suggestion bar, which is what
         * used to keep a high press on `q`-`p` from eating a word. The band was a blunt instrument
         * -- it protected the letters by making a fixed slice of the bar refuse taps, so the emoji
         * lost real estate whether or not anyone was typing, and the line was drawn by assertion
         * where the underlying evidence is a smooth, already-measured distribution.
         *
         * **2.5 was chosen to be right under either spatial fit, which is the whole difficulty.**
         * The two in play disagree by nearly a factor of two -- the shipped sigma_y of 0.130 key
         * heights, and the honest refit of 0.227 documented on [MEASURED_SIGMA_X] -- and a
         * threshold tuned to one behaves quite differently under the other. On the 34dp strip:
         *
         *  - at sigma 0.130 the entire strip already lies beyond 5.4 sigma, so any threshold
         *    below that leaves all of it tappable, as it should be;
         *  - at sigma 0.227 the strip bottom is 2.64 sigma, so a threshold of 3 or more would
         *    start eating into the bar and 4 would claim a third of it for the letters.
         *
         * 2.5 is below both strip bottoms, so the emoji keep the whole bar either way, and it
         * still puts the recorded press that turned "book" into a book emoji (1.82 sigma under
         * the honest fit) on the letter side where it belongs. Two and a half sigma is also the
         * right order of magnitude on its own terms: a press that high is not ordinary scatter.
         *
         * The reason this is not simply "the strip is safe now, delete the test" is that the
         * threshold has to keep holding when the geometry moves. A taller strip, a shorter one,
         * or a refit that widens sigma all change where the line falls in pixels, and this keeps
         * the line attached to the evidence rather than to a number of dp that was true once.
         */
        const val LETTER_REACH_SIGMAS = 2.5f

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

        /**
         * The refit scatter, over 2031 taps with the misfiled space presses removed.
         *
         * These are the numbers the paragraph on [MEASURED_SIGMA_X] calls honest and declines to
         * ship: `tools/fit_spatial.py` prints them, they are fitted on twenty times the data, and
         * as a *global* replacement they would end pinning -- at this width a perfectly centred
         * tap holds only about 10 nats over its sideways neighbour against a lexicon range of
         * 13.0, so the language model would get a vote on every letter typed.
         *
         * They are used in exactly one place, [rescueLogLikelihood], where that objection does
         * not apply because the question being asked is no longer "is this tap ambiguous" but
         * "could this finger have missed, given that everything around it is right". The
         * pinning band still comes from the tight fit, so nothing about accurate typing changes.
         *
         * This is the narrow form of the design decision that comment asks for, and it is worth
         * being clear about which part is settled: the rescue path now has a measured basis, and
         * the global fit is still open. Shipping these everywhere remains a separate change, and
         * still needs the band to be re-derived from the gap between the two readings actually in
         * contention rather than from the corpus-wide worst case.
         */
        const val REFIT_SIGMA_X = 0.227f
        const val REFIT_SIGMA_Y = 0.208f
    }
}
