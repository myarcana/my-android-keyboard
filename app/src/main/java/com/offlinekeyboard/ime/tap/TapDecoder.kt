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
        // The overwhelmingly common case: every tap landed somewhere only one letter can explain,
        // so there is nothing to decide and no reason to have built a beam to decide it.
        if (perTap.all { it.size == 1 }) return null

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

        val best = beam.firstOrNull() ?: return null
        val literal = taps.joinToString("") { it.literal.toString() }
        return if (best.letters == literal) null else best.letters
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
    }
}
