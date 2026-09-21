package com.offlinekeyboard.ime.pinyin

import kotlin.math.ln

/**
 * Turns a syllable reading into ranked Chinese text.
 *
 * **This is the difference between a toy pinyin keyboard and a usable one.** Looking each
 * syllable up on its own and concatenating the commonest character gives 今天天气很好 as
 * 今天天气很号 about as often as not -- every homophone decided in isolation, with nothing to
 * prefer the reading that makes a sentence. What is done instead is a Viterbi pass over the
 * whole input: a lattice whose edges are dictionary words covering a span of syllables, scored
 * by how common the word is *and* how well it follows what came before, with the best path
 * recovered at the end. That is the same shape as every serious IME, and it is why typing a
 * whole phrase in one go works here.
 *
 * Scores are log-probabilities, summed. Everything is a weight from the dictionary turned into
 * `ln(weight)`, so a word ten times commoner is worth `ln 10` more, and the arithmetic stays in
 * a range where nothing underflows.
 */
internal class Decoder(
    private val dict: PinyinDict,
    private val userDict: UserDict? = null,
    /**
     * Which language model to score against.
     *
     * Not a display setting: the dictionary holds a mainland Simplified model and a native
     * Taiwan Traditional one side by side, and this chooses which corpus's frequencies the
     * Viterbi pass uses. A Taiwan user typing `niuroumian` gets 牛肉麵 because that word has a
     * Taiwan weight and 牛肉面 has none, not because the output was converted afterwards.
     */
    var traditional: Boolean = false,
    /**
     * Charged for spelling a span out character by character instead of using a word.
     *
     * A parameter rather than a constant so it can be swept in tests: it is the one number in
     * here whose right value is an empirical question, and it is now load-bearing in a way it
     * was not when single-character edges were escaping it entirely.
     */
    private val backoffPenalty: Float = BACKOFF,
) {

    /** One decoding of the input: the text, and how many syllables it consumed. */
    class Candidate(
        val text: String,
        val syllables: Int,
        val score: Float,
        /** True if this came from the user's own history rather than the shipped dictionary. */
        val learned: Boolean = false,
    )

    /**
     * The best full-length decodings of [reading], best first.
     *
     * "Full-length" is the important half: the first candidate is a reading of *everything*
     * typed, which is what makes typing a sentence and pressing space work. Shorter prefixes are
     * offered too (see [candidates]) because committing a piece at a time is also normal, but
     * they never outrank a complete sentence.
     */
    fun decode(reading: Syllables.Reading, limit: Int = 12): List<Candidate> {
        val n = reading.size
        if (n == 0) return emptyList()

        // best[i] = the highest-scoring ways to cover syllables 0 until i.
        // Each cell keeps several paths, not one, because the single best prefix is not always
        // the prefix of the best whole sentence -- a slightly worse 北 can still be the start of
        // the only good reading. Keeping BEAM of them is what stops that being lost.
        val best = arrayOfNulls<MutableList<Path>>(n + 1)
        best[0] = mutableListOf(Path(word = null, previous = null, score = 0f, learned = false))

        for (i in 0 until n) {
            val prefixes = best[i] ?: continue
            // Words starting at i, over every length that fits. Longer words are preferred by
            // their weight rather than by rule; a long word is simply more evidence.
            val maxSpan = minOf(MAX_WORD_SYLLABLES, n - i)
            for (span in 1..maxSpan) {
                val ids = reading.ids.copyOfRange(i, i + span)
                for (word in wordsFor(ids)) {
                    // A character used as a fallback is charged a backoff penalty, exactly as a
                    // smoothed n-gram model charges for dropping to a shorter context. Without
                    // it, two very common characters outscore the single word they spell -- 天起
                    // beat 天气 and 是件 beat 时间, because a word pays one unigram cost and a
                    // pair of characters pays two small ones. The dictionary's word entry is
                    // strictly better evidence than assembling the same text character by
                    // character, and the penalty is what says so.
                    val wordScore = unigram(word.weight) - if (word.backoff) backoffPenalty else 0f
                    val target = i + span
                    for (path in prefixes) {
                        val score = path.score + wordScore +
                            contextBonus(path.lastChar, word.text.first()) +
                            if (word.learned) LEARNED_BONUS else 0f
                        val list = best[target] ?: mutableListOf<Path>().also { best[target] = it }
                        insert(
                            list,
                            Path(
                                word = word,
                                previous = path,
                                score = score,
                                learned = path.learned || word.learned,
                            ),
                        )
                    }
                }
            }
        }

        return best[n].orEmpty()
            .take(limit)
            .map { Candidate(it.text(), n, it.score, it.learned) }
    }

    /**
     * What to show in the candidate bar for [input].
     *
     * Three things are merged, in this order of preference:
     *
     *  1. **Whole-input decodings** -- what the user typed, read as a sentence.
     *  2. **Prefix words**, longest first -- the normal way a phrase is entered, one word at a
     *     time, where the rest of the input is the next word and not part of this one.
     *  3. **Single characters** for the first syllable -- the floor. There is always something
     *     to pick, even for input no word covers, which is what keeps the keyboard usable for
     *     names and rare words.
     *
     * Duplicates are dropped keeping the first (best-ranked) occurrence, so a word that is both
     * a full decoding and a prefix appears once, at its better rank.
     */
    fun candidates(input: String, limit: Int = 20): List<Candidate> {
        val readings = Syllables.readings(input)
        if (readings.isEmpty()) return emptyList()

        val out = ArrayList<Candidate>(limit * 2)
        val seen = HashSet<String>(limit * 2)

        fun offer(candidate: Candidate) {
            if (seen.add(candidate.text)) out.add(candidate)
        }

        // 1. Full decodings, over the few best readings. A fuzzy reading is charged here rather
        // than in the segmenter so that it competes on the same scale as everything else.
        val full = ArrayList<Candidate>()
        for (reading in readings.take(MAX_READINGS)) {
            val penalty = reading.fuzzyCount * FUZZY_PENALTY
            for (candidate in decode(reading, limit)) {
                full.add(
                    Candidate(
                        candidate.text,
                        candidate.syllables,
                        candidate.score - penalty,
                        candidate.learned,
                    ),
                )
            }
        }
        full.sortByDescending { it.score }
        // Bounded, so the bar is not filled end to end with variations on one sentence.
        //
        // Sixteen readings each contributing a full beam is far more whole-input decodings than
        // the strip can show, and offering them all crowded out the prefix words entirely:
        // `beijingdaxue` filled the bar with 北京大学, 北京大雪, 北京大削, 北极难过大学 and so on,
        // with no 北京 anywhere -- so a phrase could be typed whole or not at all, and committing
        // it a word at a time was impossible. The alternatives past the first few are near
        // duplicates of each other anyway; what a user wants next is a shorter commit.
        full.take(FULL_DECODINGS).forEach(::offer)

        // 2. Prefix words: every proper prefix of the best reading, longest first.
        val primary = readings.first()
        for (span in minOf(primary.size, MAX_WORD_SYLLABLES) downTo 1) {
            if (span == primary.size) continue
            val ids = primary.ids.copyOfRange(0, span)
            for (word in wordsFor(ids).take(PREFIX_WORDS)) {
                // The learned bonus has to be applied here too, not only inside the Viterbi
                // pass. A one-syllable correction -- the commonest kind, choosing between
                // homophones of `ta` -- arrives as a prefix candidate, and scoring it without
                // the bonus meant the keyboard recorded the choice and then ignored it.
                val score = unigram(word.weight) +
                    (if (word.learned) LEARNED_BONUS else 0f) -
                    (if (word.backoff) backoffPenalty else 0f)
                offer(Candidate(word.text, span, score, word.learned))
            }
        }

        // 3. Single characters for the first syllable, as the guaranteed floor.
        val firstId = primary.ids.firstOrNull()
        if (firstId != null) {
            for (entry in charactersFor(firstId).take(CHAR_FLOOR)) {
                offer(Candidate(entry.text, 1, unigram(entry.weightFor(traditional))))
            }
        }

        // A learned choice goes to the front, whichever phase produced it.
        //
        // The phases are ordered by *kind* -- whole-input decodings, then prefixes, then the
        // character floor -- which is right in general but silently outranks a correction: a
        // one-syllable choice like 他 for `ta` is a prefix candidate and sat behind every full
        // decoding regardless of its score. Learning is only worth anything if the next
        // keystroke shows it, so it is hoisted here rather than left to phase order. Stable, so
        // everything else keeps the ordering the phases gave it.
        val learned = out.filter { it.learned }.sortedByDescending { it.score }
        if (learned.isNotEmpty()) {
            val rest = out.filterNot { it.learned }
            return (learned + rest).take(limit)
        }
        return out.take(limit)
    }

    /**
     * Words covering exactly these syllable ids, from the user's history first.
     *
     * An abbreviation (a bare consonant) is a prefix query over every syllable with that
     * initial, which is why it is handled here rather than in the dictionary: the dictionary
     * deals in resolved syllables only.
     */
    private fun wordsFor(ids: IntArray): List<Entry> {
        val out = ArrayList<Entry>(8)
        userDict?.wordsFor(ids)?.forEach { out.add(Entry(it.text, it.weight, learned = true)) }
        if (ids.any(Syllables::isInitial)) {
            for (word in expandAbbreviation(ids)) {
                out.add(Entry(word.text, word.weightFor(traditional), false))
            }
        } else {
            for (word in dict.wordsFor(ids)) {
                out.add(Entry(word.text, word.weightFor(traditional), false))
            }
        }
        // A word the model in play does not have is not a candidate in it. This is the line that
        // keeps 牛肉面 out of a Taiwan user's bar and 牛肉麵 out of a mainland user's, now that
        // both live on the same syllable key.
        out.retainAll { it.learned || it.weight > 0 }
        // A word occupying a span of n syllables must be n characters long. The dictionary only
        // holds entries where those agree, but an abbreviation is a *prefix* query and will
        // happily return 婀娜 for a single `n`, which then covers one syllable with two
        // characters. The lattice cannot represent that: the path arrives at the next position
        // carrying text longer than the input it consumed, and the decoding comes out with
        // characters nobody typed -- 我们婀娜 for `women`. Enforced here, once, rather than at
        // each of the three call sites that could reintroduce it.
        out.retainAll { it.text.length == ids.size }

        // Single characters, so the lattice is connected at every position.
        //
        // A span of one syllable often finds nothing in the word index -- `tian` alone returns no
        // word. Without a single-character edge the decoder cannot pass through that position at
        // all, and a sentence containing one standalone character has no complete path:
        // `womenshizhongguoren` could not reach 人 at the end and fell back to whatever
        // multi-character junk did connect. These edges are what make an arbitrary sentence
        // decodable.
        if (ids.size == 1 && !Syllables.isInitial(ids[0])) {
            var added = 0
            for (entry in dict.charsFor(ids[0])) {
                if (added >= CHAR_EDGES) break
                val weight = entry.weightFor(traditional)
                if (entry.text.length == 1 && weight > 0) {
                    out.add(Entry(entry.text, weight, false, backoff = true))
                    added++
                }
            }
        }

        // Spelling one syllable with one character is a backoff *however the entry was found*.
        //
        // [BACKOFF] is what stops a pair of very common characters outscoring the single word they
        // spell, and the word index was letting entries past it. That index is not exclusively
        // multi-character: the Taiwan model keeps single-character frequencies there too (是, 時,
        // 十 all sit under the key `shi`), so those characters arrived from the block above as
        // ordinary *words*, carrying no penalty, and the unpenalised copy is the one that won. In
        // Traditional mode `shida` decoded to 是大 at -6.63 while the real word 師大 -- weight
        // 30263, the commonest `shi da` entry in that model -- scored -11.10 and never appeared at
        // all. The mainland model hid the bug entirely, because its single characters have no
        // weight in the word index and the region filter dropped them.
        //
        // Swept over the whole list rather than fixed at each source, so no present or future
        // caller can slip an unpenalised single-character edge past it, and the penalty is charged
        // exactly once. A learned entry is exempt: the user's own correction is evidence in its own
        // right, not a fallback.
        if (ids.size == 1) {
            for (i in out.indices) {
                val entry = out[i]
                if (!entry.learned && !entry.backoff && entry.text.length == 1) {
                    out[i] = Entry(entry.text, entry.weight, false, backoff = true)
                }
            }
        }

        // The two sources overlap, so the same character can now be present twice. Keep one copy
        // of each: a duplicate edge is not merely wasted work, it crowds the beam with paths that
        // decode to identical text.
        //
        // A learned entry always wins, whatever its weight. It carries the [LEARNED_BONUS] and the
        // flag that hoists a correction to the front of the bar, so choosing by weight alone threw
        // the correction away -- the user's own choice of 她 for `ta` came back as the plain
        // dictionary entry and the keyboard ignored what it had just been taught.
        if (out.size > 1) {
            val best = LinkedHashMap<String, Entry>(out.size)
            for (entry in out) {
                val prior = best[entry.text]
                val better = prior == null ||
                    (entry.learned && !prior.learned) ||
                    (entry.learned == prior.learned && entry.weight > prior.weight)
                if (better) best[entry.text] = entry
            }
            if (best.size != out.size) {
                out.clear()
                out.addAll(best.values)
            }
        }
        return out
    }

    /**
     * Words matching an abbreviated key like `b j` (北京).
     *
     * Only the fully-abbreviated and mixed forms people actually type are supported, and the
     * search is bounded: an abbreviation is by nature a wide query, and the useful answers are
     * the common words, which is exactly what the weight ordering gives.
     */
    private fun expandAbbreviation(ids: IntArray): List<PinyinDict.Word> {
        // Resolve one initial at a time against the keys the dictionary actually holds. Doing it
        // as a prefix walk keeps this proportional to the matches rather than to the number of
        // syllables sharing an initial.
        var prefixes = listOf(IntArray(0))
        for (id in ids) {
            val next = ArrayList<IntArray>()
            if (Syllables.isInitial(id)) {
                val letter = Syllables.initialLetter(id)
                for (prefix in prefixes) {
                    for ((sid, spelling) in dict.syllableSpellings.withIndex()) {
                        if (spelling[0] == letter) next.add(prefix + sid)
                    }
                }
            } else {
                for (prefix in prefixes) next.add(prefix + id)
            }
            // Keep only prefixes the dictionary can still extend, or this explodes -- and keep
            // the *strongest* ones, not the first ones. Truncating in syllable-id order means
            // alphabetical order, which cut `bei` long before it was reached and left `bj`
            // unable to find 北京. Ranking by the best word under each prefix is what makes an
            // abbreviation resolve to the word people actually meant.
            // Several words are fetched per prefix, not one. `wordsWithPrefix` reads a bounded
            // number of entries and *then* ranks them for the region in play, so asking for one
            // returns the region's best only if the globally-first entry happens to be in that
            // region. It often is not -- a Traditional-only word can head the list for a
            // mainland user -- and scoring the prefix at 0 dropped it from the search entirely,
            // which is how `bj` stopped finding 北京.
            prefixes = next.asSequence()
                .map { prefix ->
                    prefix to (
                        dict.wordsWithPrefix(prefix, ABBREVIATION_PROBE, traditional)
                            .firstOrNull()?.weightFor(traditional) ?: 0
                        )
                }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(ABBREVIATION_BRANCHES)
                .map { it.first }
                .toList()
            if (prefixes.isEmpty()) return emptyList()
        }
        // wordsFor, not wordsWithPrefix: the key is now fully resolved and the word must cover
        // exactly it. A prefix query here is what let 婀娜 answer a one-syllable `n`.
        val out = ArrayList<PinyinDict.Word>()
        for (key in prefixes) {
            out.addAll(dict.wordsFor(key, 4))
        }
        out.retainAll { it.weightFor(traditional) > 0 }
        out.sortByDescending { it.weightFor(traditional) }
        return out.take(ABBREVIATION_RESULTS)
    }

    private fun charactersFor(id: Int): List<PinyinDict.CharEntry> =
        if (Syllables.isInitial(id)) {
            val letter = Syllables.initialLetter(id)
            dict.syllableSpellings.withIndex()
                .filter { it.value[0] == letter }
                .flatMap { dict.charsFor(it.index) }
                .filter { it.weightFor(traditional) > 0 }
                .sortedByDescending { it.weightFor(traditional) }
        } else {
            dict.charsFor(id)
                .filter { it.weightFor(traditional) > 0 }
                .sortedByDescending { it.weightFor(traditional) }
        }

    /**
     * A word's cost, as a log-probability: `ln(weight / CORPUS)`.
     *
     * **Normalising is what makes decodings of different lengths comparable**, and getting it
     * wrong was the decoder's worst bug. Scoring a word as bare `ln(weight)` makes every word
     * worth a *positive* amount, so a path always improves by containing more words: `shijian`
     * decoded to 十几啊你 (four words, 8.7) over 时间 (one word, 5.1), and no amount of tuning a
     * flat per-word penalty fixes it, because the two quantities do not even have the same sign.
     *
     * As a probability the arithmetic comes out right on its own. Every word costs something
     * (all weights are below the corpus total, so every term is negative), a common word costs
     * little and a rare one a lot, and a path is charged for each word it uses. 时间 at ~5e5 out
     * of ~2e9 costs about -8.3; the four junk words cost -13 each. The sentence with fewer,
     * commoner words wins for the same reason it is more probable.
     */
    private fun unigram(weight: Int): Float =
        ln((weight + 1).toFloat() / CORPUS_TOTAL)

    /**
     * How much the pairing of two adjacent characters is worth.
     *
     * The bigram counts come from the weighted phrase table, so a pair that occurs inside common
     * words scores well. Scaled down relative to word frequency deliberately: context should
     * break ties between comparable readings, not overturn a much commoner word. The cap stops a
     * single very frequent pair from dominating a long sentence.
     */
    private fun contextBonus(previous: Char, next: Char): Float {
        if (previous == ' ') return 0f
        val count = dict.bigram(previous, next)
        if (count <= 0) return 0f
        return minOf(ln((count + 1).toFloat()) * CONTEXT_WEIGHT, CONTEXT_CAP)
    }

    /** Keeps [list] sorted best-first and bounded to [BEAM]. */
    private fun insert(list: MutableList<Path>, path: Path) {
        var i = 0
        while (i < list.size && list[i].score >= path.score) i++
        if (i >= BEAM) return
        list.add(i, path)
        while (list.size > BEAM) list.removeAt(list.size - 1)
    }

    /**
     * One path through the lattice, as a link back to the path it extends.
     *
     * The text used to be carried as a `String` built with `path.text + word.text` at every edge,
     * which allocated a fresh copy of the whole prefix for every one of BEAM x spans x words
     * edges at every position -- thousands of throwaway strings per pass, times [MAX_READINGS]
     * passes, on the UI thread. Nearly all of them were immediately discarded by [insert], which
     * keeps only BEAM paths per cell, so the copying was done for candidates that never survived
     * to be read. A back-pointer costs one small object per edge instead, and [text] then builds
     * the string once for each of the few paths actually returned.
     *
     * [lastChar] is the one thing the scoring loop needs eagerly -- [contextBonus] asks for it on
     * every edge -- so it is read off this path's own word rather than by walking the chain.
     */
    private class Path(
        val word: Entry?,
        val previous: Path?,
        val score: Float,
        val learned: Boolean,
    ) {
        /** The last character decoded so far, or a space at the start of the sentence. */
        val lastChar: Char get() = word?.text?.last() ?: ' '

        /** Walks the chain back to the root and assembles the decoded text. */
        fun text(): String {
            val parts = ArrayList<String>(8)
            var node: Path? = this
            while (node != null) {
                node.word?.let { parts.add(it.text) }
                node = node.previous
            }
            val builder = StringBuilder()
            for (i in parts.indices.reversed()) builder.append(parts[i])
            return builder.toString()
        }
    }

    private class Entry(
        val text: String,
        val weight: Int,
        val learned: Boolean,
        /** True for a single character used as a fallback rather than a dictionary word. */
        val backoff: Boolean = false,
    )

    companion object {
        /**
         * Paths kept per lattice position. Four is enough that the right sentence survives an
         * unpromising start and small enough that decoding stays instant on a phone; the cost is
         * linear in this number times the words at each position.
         */
        private const val BEAM = 4

        /** Longest dictionary word, in syllables. Matches the builder's MAX_WORD_LEN. */
        private const val MAX_WORD_SYLLABLES = 12

        /**
         * How many segmentations to decode.
         *
         * Generous because the readings are sorted with exact ones first, so the fuzzy reading
         * of a word -- `zongguo` meaning 中国 -- can sit well down the list behind a pile of
         * exact-but-nonsensical splits like `z- o n- g- guo`. Cutting at 4 dropped it entirely
         * and fuzzy pinyin silently stopped working for the words that need it most.
         */
        private const val MAX_READINGS = 16

        /**
         * Denominator for [unigram], in the weights' own units.
         *
         * The sum of the dictionary's weights, near enough: the exact figure does not matter
         * because a constant factor shifts every full-length decoding equally. What it must be
         * is *larger than any single weight*, so that every word's log-probability is negative
         * and a path is always charged for adding one.
         */
        private const val CORPUS_TOTAL = 2e9f

        /** Charged per fuzzily-matched syllable, in nats. Roughly "ten times less likely". */
        private const val FUZZY_PENALTY = 2.3f

        /** A word the user has chosen before is worth this much extra. */
        /**
         * What a word the user has chosen before is worth, in nats.
         *
         * Sized against the scale [unigram] actually produces: common words sit around -8 and
         * the gap to a plausible rival is a few nats, so a bonus of 3 was not enough to put a
         * corrected choice in front -- the keyboard "learned" and then made the same mistake.
         * 12 reliably promotes a learned word over an unlearned rival without letting one stray
         * tap bury a far commoner word forever, because the rival's own frequency still counts.
         */
        private const val LEARNED_BONUS = 12.0f

        private const val CONTEXT_WEIGHT = 0.35f
        private const val CONTEXT_CAP = 4.0f

        /**
         * Single-character edges offered per syllable inside the lattice.
         *
         * Enough that the right character is among them for any common syllable; small enough
         * that a long sentence does not multiply them out. The beam does the rest of the work.
         */
        private const val CHAR_EDGES = 12

        /**
         * Charged for spelling a span out character by character instead of using a word.
         *
         * Large enough that a genuine two-character word beats the best pair of characters that
         * spells it, small enough that a name or rare compound the dictionary lacks is still
         * reachable -- which is the whole reason the character edges exist.
         *
         * The value is unchanged, but it now applies to edges that were escaping it (see
         * [wordsFor]), so it was re-swept against both models rather than assumed. 師大 holds the
         * top spot for `shida` down to 2.5 and loses to the character pair 是大 at 2.0; the
         * sentences that motivated the penalty are stable from 3.5 up. 6.0 sits above that range
         * with margin on both sides, so it is kept.
         */
        private const val BACKOFF = 6.0f

        /**
         * Whole-input decodings offered before the prefix words get their turn.
         *
         * The first is what space commits and is nearly always the answer; the next few are the
         * genuine alternative readings. Everything past that is a variation on one of them and
         * is worth less than the ability to commit a prefix.
         */
        private const val FULL_DECODINGS = 5

        private const val PREFIX_WORDS = 6
        private const val CHAR_FLOOR = 8
        private const val ABBREVIATION_BRANCHES = 24

        /**
         * Entries read per prefix when scoring how promising it is.
         *
         * Enough that a prefix whose commonest words belong to the *other* language model is
         * still scored by its best word in this one. One was the bug: see [expandAbbreviation].
         */
        private const val ABBREVIATION_PROBE = 16
        private const val ABBREVIATION_RESULTS = 12
    }
}
