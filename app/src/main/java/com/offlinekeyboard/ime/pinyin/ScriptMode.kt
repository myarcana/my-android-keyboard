package com.offlinekeyboard.ime.pinyin

import kotlin.math.ln

/**
 * Which language model(s) a decode draws on, and how their weights become comparable scores.
 *
 * The dictionary holds two corpora side by side -- mainland Simplified (`cn`) and Taiwan
 * Traditional (`tw`) -- and until now the decoder picked exactly one with a boolean and *filtered
 * the other away*. That is right for a Chinese subtype, where the user has declared which script
 * they write and a bar mixing the two would be noise. It is wrong for the English bar, which has
 * no such declaration: there the two scripts are competing hypotheses about what the letters mean,
 * and the bar should rank them against each other.
 *
 * ## Why a mode rather than a second boolean
 *
 * The filter and the scale are the same decision. Reading one corpus means normalising by that
 * corpus's total; reading both means normalising each by *its own* total and comparing the
 * results. Bundling them here is what stops a caller from selecting both corpora and then scoring
 * them on one denominator, which is the subtly wrong version of this feature -- see [CN_TOTAL].
 */
internal enum class ScriptMode {
    /** Mainland Simplified only. What the `zh_CN` subtype uses. */
    SIMPLIFIED,

    /** Taiwan Traditional only. What the `zh_TW` subtype uses. */
    TRADITIONAL,

    /**
     * Both corpora at once, each normalised by its own total.
     *
     * The English bar's mode. A word present in only one corpus competes on that corpus's terms
     * rather than being dropped, so `shida` can offer 師大 (Taiwan) beside 十大 (mainland) and
     * `miantiao` can offer both 麵條 and 面条.
     *
     * Whole-input sentences are the exception: the decoder does not run one lattice over both
     * corpora for those, because a path could then switch script mid-sentence and the larger
     * mainland corpus won every slot. It decodes each script separately and interleaves them;
     * see `Decoder.bothScripts`. Prefix words and the character floor still use this mode.
     */
    BOTH,
    ;

    /** Whether this mode reads the mainland column at all. */
    val readsSimplified: Boolean get() = this != TRADITIONAL

    /** Whether this mode reads the Taiwan column at all. */
    val readsTraditional: Boolean get() = this != SIMPLIFIED

    /**
     * The single corpus this mode reads, for the call sites that still need one.
     *
     * [BOTH] has no single answer and reports `false`; every caller that matters asks
     * [weightsOf] or [logProbOf] instead, which handle two corpora properly.
     */
    val traditional: Boolean get() = this == TRADITIONAL

    /**
     * Every (weight, corpus-total) pair this mode can see for an entry, skipping absent ones.
     *
     * Returned as pairs rather than as a summed weight because **summing is the bug this type
     * exists to prevent**: 面 has `cn=4,532,962` and `tw=2,961,990`, and adding them would both
     * invent a frequency no corpus observed and double-count a character common in both. Each
     * weight belongs to its own corpus and is normalised by that corpus's total; the caller then
     * takes the best, because "how likely is this text" is a max over the hypotheses that produce
     * it, not a sum over bookkeeping columns.
     */
    fun weightsOf(cn: Int, tw: Int): List<Pair<Int, Float>> {
        val out = ArrayList<Pair<Int, Float>>(2)
        if (readsSimplified && cn > 0) out.add(cn to CN_TOTAL)
        if (readsTraditional && tw > 0) out.add(tw to TW_TOTAL)
        return out
    }

    /**
     * The best log-probability this mode assigns to an entry, or null when it has no weight here.
     *
     * Null rather than a floor value: "this corpus does not contain this word" and "this corpus
     * contains it rarely" are different claims, and collapsing them is what would put a
     * Traditional-only word into a Simplified bar at a very low score instead of leaving it out.
     */
    fun logProbOf(cn: Int, tw: Int): Float? =
        weightsOf(cn, tw).maxOfOrNull { (weight, total) -> ln((weight + 1f) / total) }

    /** Whether an entry exists at all in the corpora this mode reads. */
    fun has(cn: Int, tw: Int): Boolean =
        (readsSimplified && cn > 0) || (readsTraditional && tw > 0)

    /**
     * The weight to *rank* an entry by within this mode, when a raw comparable number is needed.
     *
     * Scaled onto the mainland corpus so the two columns are commensurable: a Taiwan weight is
     * multiplied by [CN_TOTAL]/[TW_TOTAL] before being compared with a mainland one. Used where
     * the code needs an ordering rather than a score -- prefix probing, the character floor --
     * so those orderings agree with the log-probabilities the decoder finally assigns.
     */
    fun rankWeightOf(cn: Int, tw: Int): Int {
        var best = 0
        if (readsSimplified && cn > 0) best = cn
        if (readsTraditional && tw > 0) {
            val scaled = (tw * (CN_TOTAL / TW_TOTAL)).toInt()
            if (scaled > best) best = scaled
        }
        return best
    }

    companion object {
        /**
         * Sum of the mainland weights in the shipped dictionary, and of the Taiwan ones.
         *
         * **These are measured from the asset, not chosen**, and the ratio between them is the
         * whole of cross-script ranking. The two corpora are wildly different sizes -- 386,089
         * mainland word entries totalling 5.63e9 against 99,657 Taiwan ones totalling 2.28e9 --
         * so a raw weight means something different in each. 師大 at `tw=30,263` is a commoner
         * word *in Taiwanese writing* than 师大 at `cn=51,915` is in mainland writing, and only
         * dividing each by its own total says so.
         *
         * The previous single `CORPUS_TOTAL = 2e9` stood in for both and was below either true
         * total, which was harmless while exactly one corpus was ever read -- a constant factor
         * shifts every candidate equally and cancels in the ordering. It stops being harmless the
         * moment two corpora are compared, because then the factor no longer cancels: it becomes
         * a 2.47x thumb on the scale for whichever column is over-credited.
         *
         * Regenerate with `tools/corpus_totals.py` if the dictionary is rebuilt.
         */
        const val CN_TOTAL = 5.629e9f
        const val TW_TOTAL = 2.281e9f
    }
}
