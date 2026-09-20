package com.offlinekeyboard.ime.tap

import com.offlinekeyboard.ime.layout.LayoutGeometry

/**
 * Reads a run of taps as a word, using where the finger landed and what English looks like.
 *
 * This is deliberately *not* autocorrect, and the difference is a constraint rather than a
 * setting. The reading it returns has exactly one letter per tap, and every letter is one the
 * finger could plausibly have been aiming at. Nothing is inserted, nothing is deleted, no word is
 * substituted for another. The output is always a re-reading of the keys that were actually hit,
 * so a word the lexicon has never heard of is typed by hitting its keys accurately -- and hitting
 * them accurately is the *only* thing needed, because an accurate tap is pinned by
 * [SpatialModel.candidates] and has no other reading available to lose to.
 *
 * What the deferral buys is context that does not exist yet at the moment of the tap. Whether the
 * second letter of a word was `t` or `r` is often not decidable from its own touch point, and is
 * obvious three letters later. Committing at the tap throws that away; holding the word as
 * composing text until it ends keeps it.
 *
 * Scoring is one comparison with no thumb on the scale: ln P(touch | letters) from
 * [SpatialModel], plus ln P(letters) from [WordIndex], both in nats, both real. The literal
 * reading is not given a bonus and does not need one -- it has the best touch score by
 * construction, so it wins every tie and every case where the language model has nothing much to
 * say.
 *
 * ## Context runs both ways
 *
 * The beam above reads left to right, but it is not a left-to-right *decision*: nothing is
 * committed until the word ends, so a letter's reading is settled by the taps after it as much
 * as by the taps before it. That is what re-reads `rhe` into `the` -- the `r` is revised by an
 * `h` and an `e` that had not been typed when it was pressed.
 *
 * What that alone cannot do is fix `teavhers`. The `v` there is not a borderline tap; the finger
 * hit `v` squarely, so [SpatialModel.candidates] prunes `c` as unaffordable and the beam never
 * sees it. The pruning happens per tap, before any context exists, and it has to be strict
 * because in isolation a well-aimed tap really is unambiguous.
 *
 * [rescue] is the second pass that reopens exactly those taps. It asks a question the first pass
 * could not: given that every *other* letter of this word is now pinned and certain, is there a
 * letter here that the lexicon wants badly enough to outbid the touch evidence? The budget it
 * may spend is not invented -- it is the prior gap between the two readings, measured in the same
 * nats as the touch score, so a rescue happens only when the language model is more confident
 * than the finger was, and by a margin set by [RESCUE_MARGIN_NATS] rather than by taste.
 *
 * The asymmetry with the first pass is the safety property. A rescue needs the neighbours to be
 * certain, needs the replacement to be a real and much commoner word, and needs the whole word
 * to be one the lexicon knows -- so `rhys`, `kade` and `qwertz` are never touched, because there
 * is no commoner word for them to become.
 */
class TapDecoder(
    private val index: WordIndex,
    private val spatial: SpatialModel = SpatialModel(),
    /**
     * Partial readings kept between letters. Generous: a tap usually contributes one candidate
     * and rarely more than three, so this is a ceiling that ordinary typing never approaches.
     */
    private val beamWidth: Int = 24,
) {

    /** Where a finger went down, and the key that was drawn under it. */
    data class Tap(val x: Float, val y: Float, val literal: Char)

    private data class Hypothesis(
        val span: WordIndex.Span,
        val letters: String,
        val touchScore: Float,
    ) {
        var score: Float = 0f
    }

    /**
     * The letters [taps] most likely meant, or null when the literal reading stands.
     *
     * Null rather than the literal string so the caller can tell "nothing to do" from "this is
     * what I think you meant", and skip re-writing composing text that has not changed.
     */
    fun read(taps: List<Tap>, geometry: LayoutGeometry): String? {
        if (taps.size < MIN_TAPS || taps.size > MAX_TAPS) return null

        val perTap = taps.map { candidatesFor(it, geometry) }
        val literal = taps.joinToString("") { it.literal.toString() }

        // The overwhelmingly common case: every tap landed somewhere only one letter can explain.
        // There is nothing for the beam to decide, but that is exactly the state in which a
        // single mis-hit key is invisible to it, so the rescue pass still gets a look.
        val reading = if (perTap.all { it.size == 1 }) {
            perTap.joinToString("") { it.single().letter.toString() }
        } else {
            decode(perTap) ?: return null
        }

        val settled = rescue(reading, taps, geometry)
        return if (settled == literal) null else settled
    }

    /** The best reading the beam can build from [perTap], or null if it collapsed. */
    private fun decode(perTap: List<List<SpatialModel.Candidate>>): String? {
        var beam = listOf(Hypothesis(index.root, "", 0f).also { it.score = 0f })
        perTap.forEachIndexed { depth, candidates ->
            val next = ArrayList<Hypothesis>(beam.size * candidates.size)
            beam.forEach { hypothesis ->
                candidates.forEach { candidate ->
                    val span = index.extend(hypothesis.span, depth, candidate.letter)
                    val touch = hypothesis.touchScore + candidate.logLikelihood
                    next += Hypothesis(span, hypothesis.letters + candidate.letter, touch).also {
                        it.score = touch + index.logPrior(span)
                    }
                }
            }
            next.sortByDescending { it.score }
            beam = if (next.size > beamWidth) next.subList(0, beamWidth).toList() else next
        }
        return beam.firstOrNull()?.letters
    }

    /**
     * Re-opens single letters of [reading] that the first pass pinned, using the letters on both
     * sides of them as the evidence the first pass did not have.
     *
     * One position is reconsidered at a time, and that is a claim about typing rather than a
     * limitation. A tap firm enough to have been pinned is a tap that went where it was aimed;
     * two of those being wrong in the same short word is not a typo, it is a different word, and
     * a pass willing to rewrite two pinned letters at once is autocorrect by another name. So
     * each position is tested against a context in which every *other* letter is held fixed --
     * which is what makes the look-ahead and look-behind real, because the letters after the
     * suspect one are as much a part of that fixed context as the letters before it.
     *
     * A replacement has to clear three bars, and each one removes a different way of being wrong:
     *
     *  - the result must be a spelling the lexicon can still complete, so a rescue never invents
     *    letters that lead nowhere;
     *  - it must beat the current reading on prior by [RESCUE_MARGIN_NATS] *more* than the touch
     *    evidence says it should lose by, so a near-tie never moves;
     *  - the substituted letter must be spatially affordable, which on the measured scatter
     *    means the tap has to have drifted most of the way to the key being proposed. That is
     *    what confines a rescue to a plausible slip rather than an arbitrary swap, and it is
     *    enforced by the same nat comparison rather than by a separate distance rule.
     *
     * The loop repeats while anything changed, but in practice a second rescue almost never
     * fires, and the reason is worth stating because it looks like a bug. Each step is scored
     * against the *current* reading, and a word with two slips in it is not a word either way:
     * `teavhets` and the half-fixed `teachets` both fall to [WordIndex.oovLogPrior], so the prior
     * gain from fixing one of them is zero and the first step cannot even start. Two mistakes in
     * one word therefore come back untouched rather than half-corrected, which is the better of
     * the two failures -- the keyboard says "I don't know what you meant" instead of inventing a
     * confident answer from a word it cannot read.
     */
    private fun rescue(reading: String, taps: List<Tap>, geometry: LayoutGeometry): String {
        var current = reading
        repeat(MAX_RESCUES) {
            val improved = rescueOnce(current, taps, geometry) ?: return current
            current = improved
        }
        return current
    }

    /** One substitution, or null when no position has a case strong enough to make. */
    private fun rescueOnce(reading: String, taps: List<Tap>, geometry: LayoutGeometry): String? {
        val currentPrior = index.logPrior(spanOf(reading))
        var bestWord: String? = null
        var bestGain = RESCUE_MARGIN_NATS

        reading.indices.forEach { i ->
            val tap = taps[i]
            // What the touch says about the letter currently read here, on the same refit
            // scatter the alternatives are scored against -- both sides of the subtraction have
            // to be in one measurement for the difference to mean anything.
            val held = spatial.rescueLogLikelihood(tap.x, tap.y, reading[i], geometry)
            val alternatives = spatial.rescueCandidates(
                tap.x, tap.y, geometry, index.priorRange, RESCUE_REACH_NATS,
            )
            alternatives.forEach { candidate ->
                if (candidate.letter == reading[i]) return@forEach
                val swapped = reading.substring(0, i) + candidate.letter + reading.substring(i + 1)
                val span = spanOf(swapped)
                // The lexicon has to know this spelling as the start of *something*, so a rescue
                // can never invent letters that lead nowhere. It is deliberately a prefix test
                // rather than a whole-word one: the word is still being typed, and demanding a
                // complete word here would refuse to fix `teavh` until the `ers` arrived, which
                // is the flicker the prefix-mass prior exists to avoid.
                if (span.isEmpty) return@forEach
                // Prior won, minus touch given up. Both in nats, so the subtraction is meaningful.
                val gain = (index.logPrior(span) - currentPrior) - (held - candidate.logLikelihood)
                if (gain > bestGain) {
                    bestGain = gain
                    bestWord = swapped
                }
            }
        }
        return bestWord
    }

    /** The lexicon run for a complete spelling, empty when nothing begins with it. */
    private fun spanOf(word: String): WordIndex.Span {
        var span = index.root
        word.forEachIndexed { depth, c ->
            span = index.extend(span, depth, c)
            if (span.isEmpty) return span
        }
        return span
    }

    /**
     * What this tap could have been, with the drawn key always among them.
     *
     * The forced inclusion matters. [SpatialModel] scores against where the thumb is *measured*
     * to land, which sits below the drawn centre, so a tap on the very top edge of a key can
     * score badly for that key -- badly enough to be pruned. Pruning it would leave the keyboard
     * unable to return the letter whose key the user visibly pressed, which is the one reading
     * that must never become unavailable.
     */
    private fun candidatesFor(tap: Tap, geometry: LayoutGeometry): List<SpatialModel.Candidate> {
        val found = spatial.candidates(tap.x, tap.y, geometry, index.priorRange)
        if (found.any { it.letter == tap.literal }) return found
        val literal = SpatialModel.Candidate(
            tap.literal,
            spatial.logLikelihood(tap.x, tap.y, tap.literal, geometry),
        )
        return found + literal
    }

    companion object {
        /**
         * Taps needed before the language model is allowed an opinion.
         *
         * At one letter the prior is not about a word, it is about which letters English words
         * start with -- a fact about the dictionary rather than about what this person is typing,
         * and one that would apply to every first keystroke ever made. Two letters is where the
         * prior starts describing a word shape instead of an alphabet.
         */
        const val MIN_TAPS = 2

        /**
         * Length past which a run of taps stops being treated as a word. Long enough for any
         * English word; a bound on work for a field being pasted into or typed at by a script.
         */
        const val MAX_TAPS = 32

        /**
         * How much further than the pinning band the rescue pass may look, in nats.
         *
         * This is a reach, not a licence: it decides which letters are *considered* at a pinned
         * position, and every one of them still has to win on the full prior-versus-touch
         * comparison before it can replace anything. Widening it therefore cannot make a rescue
         * happen that the lexicon did not ask for; it can only stop one being missed because the
         * letter was never on the list.
         *
         * Adjacent letter keys sit about 1.16 key units apart on this layout. On the refit
         * scatter this pass uses ([SpatialModel.REFIT_SIGMA_X]) that puts a dead-centre press
         * 13.1 nats from its sideways neighbour, and the row above 15.6 nats away on sigma_y.
         *
         * 20 nats of reach sits above both, which is deliberate: the reach decides only which
         * letters get *listed*, and a reach that quietly excluded the row above would be a second
         * gate enforcing a rule the comparison is already responsible for. Keeping the listing
         * generous leaves exactly one place where a rescue can be refused -- prior gained against
         * touch surrendered -- which is the property that makes the pass auditable.
         *
         * It is bounded rather than unbounded because listing all 26 letters at every position
         * would be work with no possible effect: past about 20 nats no prefix-mass gap in this
         * lexicon can pay the touch cost, so the candidates are being generated only to be
         * rejected arithmetically.
         */
        const val RESCUE_REACH_NATS = 20f

        /**
         * How much better a rescued reading must be than the one it replaces, in nats.
         *
         * The comparison it gates is already fair -- prior gained against touch surrendered, both
         * measured, neither weighted -- so this is not a correction factor. It is the answer to a
         * different question: how much better is *worth changing text the user is looking at*.
         * A rescue that wins by a hair is a coin toss the user did not ask to have taken, and it
         * will lose the next toss just as easily.
         *
         * The size of this is dictated by the prior, and the prior's gaps are small by
         * construction. A non-word does not score zero; it scores [WordIndex.oovLogPrior], the
         * deliberately generous "an unknown spelling is an ordinary word" floor that is what lets
         * `rhys` and `zamil` be typed at all. Measured against the shipped lexicon, `teachers`
         * beats the non-word `teavhers` by 3.7 nats, `word` beats `wprd` by 5.1, and `it` beats
         * `ot` by 1.4. Those gaps are the entire budget a correction has to spend, whatever the
         * touch evidence does, so a margin of 6 would silently switch the feature off -- which is
         * how this number was arrived at, rather than by preference.
         *
         * 1.0 nat is a prior ratio of about 2.7:1: enough that a coin-flip never rewrites text,
         * small enough to fit inside gaps this size. The real protection against over-correction
         * was never going to come from here. It comes from the touch term, which is 13 nats for a
         * squarely-hit key even on the refit scatter, and from requiring a real word on the other
         * side -- which is why `cad` and `bat` are safe, and why they would still be safe if this
         * were zero.
         */
        const val RESCUE_MARGIN_NATS = 1.0f

        /**
         * How many letters of one word a rescue may replace, one at a time.
         *
         * This is a bound on compounding rather than a target. It is small because the evidence
         * for a rescue is the *certainty of the surrounding letters*, and every substitution
         * spends some of it -- a third would be resting on a context that is substantially this
         * pass's own opinion rather than the user's typing.
         *
         * In practice the prior reaches this bound first: a word still carrying a second mistake
         * scores at the unknown-spelling floor whether or not the first is fixed, so the gain for
         * fixing either one alone is zero and neither fires. See [rescue]. The constant is the
         * belt to that braces, and the place to start if a sentence-level prior ever makes
         * stepwise correction possible.
         */
        const val MAX_RESCUES = 2
    }
}
