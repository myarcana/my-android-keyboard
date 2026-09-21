package com.offlinekeyboard.ime.tap

import com.offlinekeyboard.ime.glide.Lexicon
import kotlin.math.ln
import kotlin.math.pow

/**
 * Prefix lookup over a [Lexicon], and the language model half of the tap decoder.
 *
 * There is no trie here and deliberately is not one. Sorting the lexicon's glided spellings puts
 * every word sharing a prefix in one contiguous run, so descending a prefix is two binary searches
 * and carrying a prefix around is two ints. A trie over 40,000 words is about 100,000 nodes and
 * ten megabytes of children arrays to answer the same question; this is an [IntArray] of 40,000
 * and a [DoubleArray] beside it.
 *
 * **The prior is prefix mass, not word frequency, and that is what stops the display flickering.**
 * Scoring only complete words means "the" beats a literal "rhe" at three letters and then loses
 * again at four, because neither "rhet" nor "thet" is a word -- the reading flips, and flips back,
 * while the finger is still typing. Summing the corpus counts of *every word beginning with the
 * prefix* removes the flip: adding a letter can only narrow the set a prefix covers, so a reading
 * that is ahead stays ahead for the reason it was ahead. It is also simply the right quantity.
 * P(prefix) is the probability the word being typed starts this way, which is the question being
 * asked at every keystroke but the last.
 */
class WordIndex private constructor(
    private val letters: List<String>,
    /** Indices into [letters], sorted by the spelling they name. */
    private val order: IntArray,
    /** Running total of corpus counts over [order]; `cumulative[i]` excludes `order[i]`. */
    private val cumulative: DoubleArray,
    /**
     * ln of the mass given to a spelling the lexicon has never heard of.
     *
     * The rarest mass the lexicon itself can report, so an unknown spelling is as likely as the
     * least likely known one and never more likely than a real one. Measured in prefix mass,
     * which is the quantity [logPrior] returns and therefore the only one it can honestly be
     * compared against.
     */
    val oovLogPrior: Float,
    /**
     * The widest gap in nats the prior can open between any two readings.
     *
     * Derived, not tuned: the most any prefix can be worth is the whole corpus, the least is
     * [oovLogPrior], and nothing can lie outside that. Its use is [SpatialModel.candidates],
     * which drops a letter from consideration once the touch evidence against it exceeds this --
     * at which point no word in this lexicon, however common, could buy it back. That is what
     * makes an accurate tap *provably* unrereadable rather than merely unlikely to be re-read.
     */
    val priorRange: Float,
) {

    /** A half-open run of [order]: every word beginning with some prefix. */
    data class Span(val lo: Int, val hi: Int) {
        val isEmpty: Boolean get() = lo >= hi
    }

    /** Every word in the lexicon; the prefix before any letter has been typed. */
    val root: Span = Span(0, order.size)

    /**
     * The words in [span] whose letter at [depth] is [c].
     *
     * [span] must be the run for a prefix of exactly [depth] letters, which is what makes the
     * answer contiguous: its members already agree on everything before [depth], so they are
     * ordered by what comes next.
     */
    fun extend(span: Span, depth: Int, c: Char): Span {
        val lo = lowerBound(span.lo, span.hi, depth, c)
        val hi = lowerBound(lo, span.hi, depth, c + 1)
        return Span(lo, hi)
    }

    /**
     * ln of the corpus count of everything [span] covers, or [oovLogPrior] when it covers nothing.
     *
     * The floor is what lets a name be typed. An unknown spelling is not impossible -- people
     * type names, handles and abbreviations constantly -- so it is scored as the rarest thing
     * the lexicon can say rather than as an impossibility. That keeps `rhys` and `zamil`
     * typable while ensuring no real spelling is ever outbid by an invented one, which is the
     * direction the old median-word floor had backwards.
     */
    fun logPrior(span: Span): Float =
        if (span.isEmpty) oovLogPrior else ln(cumulative[span.hi] - cumulative[span.lo]).toFloat()

    /** First index in `[from, until)` whose letter at [depth] is at least [c]. */
    private fun lowerBound(from: Int, until: Int, depth: Int, c: Char): Int {
        var lo = from
        var hi = until
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (charAt(mid, depth) < c) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * The letter at [depth] of the word at [i], or a space for a word that has already ended.
     *
     * A space rather than a special case because it is what the sort already assumed: "ab" orders
     * before "abc", so inside the run for "ab" the two-letter word comes first, and any character
     * below 'a' is what keeps the binary search agreeing with that.
     */
    private fun charAt(i: Int, depth: Int): Char {
        val word = letters[order[i]]
        return if (depth < word.length) word[depth] else ' '
    }

    companion object {
        /**
         * Builds the index. About 40,000 strings to sort: tens of milliseconds, on the same
         * background thread that parsed the lexicon in the first place.
         */
        fun of(lexicon: Lexicon): WordIndex {
            val letters = lexicon.letters
            val order = IntArray(letters.size) { it }
                .sortedBy { letters[it] }
                .toIntArray()

            // logFrequency is 100 x log10(count), so this undoes the asset's encoding. Doubles
            // because the commonest word's count is around 2e10 and they are being summed.
            val counts = DoubleArray(order.size) {
                10.0.pow(lexicon.logFrequency[order[it]] / 100.0)
            }
            val cumulative = DoubleArray(order.size + 1)
            for (i in order.indices) cumulative[i + 1] = cumulative[i] + counts[i]

            // The value of an unknown spelling, and it has to be measured in the quantity it
            // will be *compared against*. That quantity is prefix mass, not a word count.
            //
            // The median word count was the wrong measurement for the right idea. A word's own
            // count and the summed count of everything starting with a prefix are different
            // numbers on different scales, and the median word sat above the prefix mass of 45.9%
            // of the shipped lexicon -- so a made-up spelling outscored nearly half of all real
            // ones on prior alone. That is backwards, and it is exactly the direction that hurts:
            // it paid the rescue pass to move a rare real word toward nonsense. `aback` lost to
            // an invented spelling by 1.5 nats, clearing RESCUE_MARGIN_NATS on its own.
            //
            // The floor is the smallest thing the lexicon can actually say instead. An unknown
            // spelling is then exactly as likely as the rarest known one -- still "ordinary
            // enough to be typed", which is what lets `rhys` and `zamil` through, but never
            // better than a real spelling. No word in the lexicon now loses to nonsense.
            val smallestCount = counts.minOrNull() ?: 1.0
            val oov = if (counts.isEmpty()) 1.0 else smallestCount
            val total = cumulative.last()

            return WordIndex(
                letters = letters,
                order = order,
                cumulative = cumulative,
                oovLogPrior = ln(oov).toFloat(),
                priorRange = if (total > 0) (ln(total) - ln(oov)).toFloat() else 0f,
            )
        }
    }
}
