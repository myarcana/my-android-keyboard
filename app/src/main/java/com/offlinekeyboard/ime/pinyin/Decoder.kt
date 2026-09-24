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
     * Which language model(s) to score against.
     *
     * Not a display setting: the dictionary holds a mainland Simplified model and a native
     * Taiwan Traditional one side by side, and this chooses which corpus's frequencies the
     * Viterbi pass uses. A Taiwan user typing `niuroumian` gets 牛肉麵 because that word has a
     * Taiwan weight and 牛肉面 has none, not because the output was converted afterwards.
     *
     * [ScriptMode.BOTH] reads both corpora and normalises each by its own total, so the two
     * scripts compete instead of one being filtered away. That is the English bar's mode, where
     * nothing has told the keyboard which Chinese the user writes; a Chinese subtype still picks
     * one model, because there the user has said.
     */
    var mode: ScriptMode = ScriptMode.SIMPLIFIED,
    /**
     * Charged for spelling a span out character by character instead of using a word.
     *
     * A parameter rather than a constant so it can be swept in tests: it is the one number in
     * here whose right value is an empirical question, and it is now load-bearing in a way it
     * was not when single-character edges were escaping it entirely.
     */
    private val backoffPenalty: Float = BACKOFF,
    /**
     * Charged per fuzzily-matched syllable. A parameter for the same reason
     * [backoffPenalty] is: its right value is an empirical question about how often a
     * typist means a respelling rather than the syllable they actually typed.
     */
    private val fuzzyPenalty: Float = FUZZY_PENALTY,
) {

    /**
     * One decoding of the input: the text, and exactly which part of the input it stands for.
     *
     * [consumed] and [ids] are recorded by the phase that produced the candidate, from the
     * reading it actually came from. They used to be reconstructed afterwards by guessing a
     * reading from the syllable count, and every caller guessed separately -- which is how the
     * English bar came to count a prefix's letters off the wrong end of the word.
     */
    class Candidate(
        val text: String,
        val syllables: Int,
        val score: Float,
        /** True if this came from the user's own history rather than the shipped dictionary. */
        val learned: Boolean = false,
        /**
         * Letters of the input this candidate stands for, always counted from the **start**.
         * Committing it replaces those letters and leaves the rest to be typed on -- 他的 out of
         * `tadebaba` is `tade`, and `baba` is what remains.
         */
        val consumed: Int = 0,
        /** The syllable ids of the consumed letters, as the reading that produced it split them. */
        val ids: IntArray = IntArray(0),
    )

    /**
     * The best full-length decodings of [reading], best first.
     *
     * "Full-length" is the important half: the first candidate is a reading of *everything*
     * typed, which is what makes typing a sentence and pressing space work. Shorter prefixes are
     * offered too (see [candidates]) because committing a piece at a time is also normal, but
     * they never outrank a complete sentence.
     */
    fun decode(reading: Syllables.Reading, limit: Int = 12): List<Candidate> =
        decode(reading, limit, SpanTable())

    private fun decode(reading: Syllables.Reading, limit: Int, spans: SpanTable): List<Candidate> {
        val n = reading.size
        if (n == 0) return emptyList()

        // best[i] = the highest-scoring ways to cover syllables 0 until i.
        // Each cell keeps several paths, not one, because the single best prefix is not always
        // the prefix of the best whole sentence -- a slightly worse 北 can still be the start of
        // the only good reading. Keeping BEAM of them is what stops that being lost.
        val best = arrayOfNulls<MutableList<Path>>(n + 1)
        best[0] = mutableListOf(Path(word = null, previous = null, score = 0f, learned = false))

        // A reading of one syllable has no word to back off *from*: every word spans two or
        // more, so the character is the reading, not a fallback for it. Charging [BACKOFF] here
        // anyway put every single-syllable input 6 nats below what the dictionary says, and it
        // did so only on this reading -- the abbreviation reading of the same letters (`m a`
        // for `ma`, `h e n` for `hen`) spells two-character words and pays nothing. So `ma`
        // led with 买啊 and 毛啊 ahead of 马 and 妈, `hen` with 河南 ahead of 很, `zhe` with 综合
        // ahead of 这: the commonest characters in the language, losing to readings nobody types.
        // The English bar then read the deflated score as "these letters are not very Chinese".
        val chargeBackoff = n > 1

        for (i in 0 until n) {
            val prefixes = best[i] ?: continue
            // Words starting at i, over every length that fits. Longer words are preferred by
            // their weight rather than by rule; a long word is simply more evidence.
            val maxSpan = minOf(MAX_WORD_SYLLABLES, n - i)
            for (span in 1..maxSpan) {
                val lookup = spans.at(reading.ids, i, span)
                for (word in lookup.words) {
                    // A character used as a fallback is charged a backoff penalty, exactly as a
                    // smoothed n-gram model charges for dropping to a shorter context. Without
                    // it, two very common characters outscore the single word they spell -- 天起
                    // beat 天气 and 是件 beat 时间, because a word pays one unigram cost and a
                    // pair of characters pays two small ones. The dictionary's word entry is
                    // strictly better evidence than assembling the same text character by
                    // character, and the penalty is what says so.
                    val wordScore = word.logProb -
                        if (word.backoff && chargeBackoff) backoffPenalty else 0f
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
                // No key in either dictionary is longer than this run and begins with it, so no
                // longer span from here can hold a word. Without this stop every position asked
                // the dictionary about all twelve spans, most of them syllable runs nothing could
                // ever spell -- which is what made a long English word cost most of a second per
                // keystroke once the English bar started reading it as pinyin.
                if (!lookup.extendable) break
            }
        }

        val consumed = reading.ends[n - 1]
        return best[n].orEmpty()
            .take(limit)
            .map { Candidate(it.text(), n, it.score, it.learned, consumed, reading.ids) }
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

        // One table for every reading: they are segmentations of the same letters, so most of
        // their spans are the same syllables at shifted positions. See [SpanTable].
        val spans = SpanTable()

        // 1. Full decodings, over the few best readings.
        if (mode == ScriptMode.BOTH) {
            bothScripts(readings, limit).forEach(::offer)
        } else {
            wholeInput(readings, limit, spans).forEach(::offer)
        }

        // 2. Prefix words: every proper prefix of the best reading, longest first.
        val primary = readings.first()
        for (span in minOf(primary.size, MAX_WORD_SYLLABLES) downTo 1) {
            if (span == primary.size) continue
            val ids = primary.ids.copyOfRange(0, span)
            for (word in spans.at(primary.ids, 0, span).words.take(PREFIX_WORDS)) {
                // The learned bonus has to be applied here too, not only inside the Viterbi
                // pass. A one-syllable correction -- the commonest kind, choosing between
                // homophones of `ta` -- arrives as a prefix candidate, and scoring it without
                // the bonus meant the keyboard recorded the choice and then ignored it.
                val score = word.logProb +
                    (if (word.learned) LEARNED_BONUS else 0f) -
                    (if (word.backoff) backoffPenalty else 0f)
                offer(Candidate(word.text, span, score, word.learned, primary.ends[span - 1], ids))
            }
        }

        // 3. Single characters for the first syllable, as the guaranteed floor.
        val firstId = primary.ids.firstOrNull()
        if (firstId != null) {
            for (entry in charactersFor(firstId).take(CHAR_FLOOR)) {
                // Scored against whichever corpus this mode reads, so a Traditional-only
                // character reaches the floor in [ScriptMode.BOTH] at its Taiwan probability
                // rather than being dropped for having no mainland weight.
                val logProb = mode.logProbOf(entry.cn, entry.tw) ?: continue
                offer(Candidate(entry.text, 1, logProb, false, primary.ends[0], intArrayOf(firstId)))
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
     * Whole-input decodings for [ScriptMode.BOTH]: each script decoded on its own, then
     * interleaved, so the English bar always offers a Traditional sentence beside the
     * Simplified one.
     *
     * **Why not one Viterbi pass over both corpora**, which is what this replaced. Scoring each
     * edge by whichever corpus likes it better lets a path change script mid-sentence, and it
     * does: `wojinwankeyishangkema` came out as 我今晚可以上课麼 and 我今晚可以上課吗, sentences
     * no writer of either script produces. It also meant no pure Traditional sentence ever
     * reached the bar. The mainland corpus is 2.5x larger and far denser in multi-word phrases,
     * so over a sentence its advantage compounds -- 1 to 3 nats a syllable, 14 nats behind for
     * 我很喜歡你 -- and all [FULL_DECODINGS] slots went to Simplified readings and their mixed
     * variants. Short words hid the problem, because over two syllables the gap is small enough
     * that 麵條 still made the cut beside 面条.
     *
     * Comparing the two scripts' sentences on score is therefore not a fair contest, and it is
     * also the wrong question: nothing has told the English bar which script the user writes,
     * so its best Traditional reading is worth showing however it scores against the best
     * Simplified one. The lists alternate, led by whichever head scores better.
     *
     * **The Traditional list has two sources**, pooled before the budget is applied:
     *
     *  - the Taiwan model's own decodings, which carry Taiwan-only words (師大, 蛋塔, 華爾滋);
     *  - the mainland decodings converted to Taiwan Traditional by [Script], kept at their
     *    mainland scores. For sentences these are often the better reading: the Taiwan model is
     *    sparse enough that it decodes `wodeshoujimeidianle` as 我的收集美的安樂, while
     *    converting 我的手机没电了 gives 我的手機沒電了. The conversion is phrase-aware and in
     *    the Taiwan standard, so 软件 becomes 軟體 and not 軟件.
     *
     * Pooled and then put through [budget], rather than merged afterwards, so a converted
     * *respelling* is held behind the literal readings exactly as it is in a single-script bar.
     * Merging two finished lists on score skipped that rule: 當他 -- a conversion of the `dang ta`
     * reading of `danta` -- outscored the native 蛋塔 and pushed it off the bar.
     *
     * A conversion that leaves the text unchanged (你好, 十大) is not added: that text is already
     * in the Simplified pool, and it is not a second hypothesis.
     */
    private fun bothScripts(readings: List<Syllables.Reading>, limit: Int): List<Candidate> {
        // A table per mode: [SpanTable] memoises [wordsFor], whose answer depends on the mode.
        val mainland = inMode(ScriptMode.SIMPLIFIED) { decodeAll(readings, limit, SpanTable()) }
        val taiwan = inMode(ScriptMode.TRADITIONAL) { decodeAll(readings, limit, SpanTable()) }
        val simplified = budget(mainland).distinctBy { it.text }

        // Best score per text across both sources. A text counts as a respelling if any fuzzy
        // reading produced it, the same test [decodeAll] applies within one model.
        val pooled = LinkedHashMap<String, Candidate>()
        val fuzzy = HashSet<String>(taiwan.fuzzy)
        fun pool(candidate: Candidate) {
            val prior = pooled[candidate.text]
            if (prior == null || candidate.score > prior.score) pooled[candidate.text] = candidate
        }
        taiwan.full.forEach(::pool)
        // Only the head of the mainland pool is converted: [budget] keeps at most
        // [FULL_DECODINGS] of it, so converting the whole beam of every reading would be work
        // spent on candidates that cannot reach the bar.
        for (candidate in mainland.full.take(CONVERTED_POOL)) {
            val text = script.toTraditional(candidate.text)
            if (text == candidate.text) continue
            if (candidate.text in mainland.fuzzy) fuzzy.add(text)
            pool(
                Candidate(
                    text,
                    candidate.syllables,
                    candidate.score,
                    candidate.learned,
                    candidate.consumed,
                    candidate.ids,
                ),
            )
        }
        val traditional = budget(
            Pool(pooled.values.sortedByDescending { it.score }, fuzzy),
        ).distinctBy { it.text }

        val (lead, follow) = when {
            traditional.isEmpty() -> simplified to traditional
            simplified.isEmpty() -> traditional to simplified
            simplified.first().score >= traditional.first().score -> simplified to traditional
            else -> traditional to simplified
        }
        val out = ArrayList<Candidate>(FULL_DECODINGS)
        val seen = HashSet<String>()
        var i = 0
        var j = 0
        var fromLead = true
        while (out.size < FULL_DECODINGS && (i < lead.size || j < follow.size)) {
            val next = when {
                i >= lead.size -> follow[j++]
                j >= follow.size -> lead[i++]
                fromLead -> lead[i++]
                else -> follow[j++]
            }
            // Only a candidate actually added hands the turn over, so a duplicate does not cost
            // its script a slot.
            if (seen.add(next.text)) {
                out.add(next)
                fromLead = !fromLead
            }
        }
        return out
    }

    /**
     * Runs [block] with [mode] temporarily set to [temporary].
     *
     * [mode] is read throughout the lattice code, so switching it is how one call decodes under
     * a single corpus. Restored in `finally` so an exception cannot leave the decoder in the
     * wrong mode for the next caller.
     */
    private inline fun <T> inMode(temporary: ScriptMode, block: () -> T): T {
        val saved = mode
        mode = temporary
        try {
            return block()
        } finally {
            mode = saved
        }
    }

    /** Simplified-to-Taiwan conversion, for [bothScripts]. Built on first use from the asset. */
    private val script: Script by lazy { Script(dict.conversionChars, dict.conversionPhrases) }

    /**
     * Phase 1 of [candidates] for a single-corpus mode: the best readings of the whole input,
     * at most [FULL_DECODINGS] of them, with respellings held behind the literal readings.
     */
    private fun wholeInput(
        readings: List<Syllables.Reading>,
        limit: Int,
        spans: SpanTable,
    ): List<Candidate> = budget(decodeAll(readings, limit, spans))

    /**
     * Every whole-input decoding of every reading, best first, before any budget is applied.
     *
     * [fuzzy] names the texts a respelled reading produced; it travels with the list because
     * which reading produced a candidate is not recoverable from the candidate itself.
     */
    private class Pool(val full: List<Candidate>, val fuzzy: Set<String>)

    /** Decodes each reading in the current [mode] and pools the results. See [Pool]. */
    private fun decodeAll(
        readings: List<Syllables.Reading>,
        limit: Int,
        spans: SpanTable,
    ): Pool {
        // A fuzzy reading is charged here rather than in the segmenter so that it competes on
        // the same scale as everything else.
        val full = ArrayList<Candidate>()
        // Tracked alongside, because which reading produced a candidate is not recoverable from
        // the candidate: the budget below spends exact and fuzzy decodings differently.
        val fromFuzzy = HashSet<String>()
        val considered = readings.take(MAX_READINGS)
        // A one-syllable reading decodes to single characters, which the character floor offers
        // anyway; left uncapped they fill every whole-input slot, and `xian` lost 西安 to a sixth
        // homophone of 先. Capped only when a real multi-syllable reading is there to use the
        // room: for `ma` the only rival is the abbreviation `m a`, whose 买啊 and 毛啊 are worse
        // than the next homophone, so there the characters keep every slot.
        val rivalReading = considered.any { r ->
            r.size > 1 && r.fuzzyCount == 0 && r.ids.none(Syllables::isInitial)
        }
        for (reading in considered) {
            val penalty = reading.fuzzyCount * fuzzyPenalty
            val take = if (reading.size == 1 && rivalReading) SINGLE_SYLLABLE_DECODINGS else limit
            for (candidate in decode(reading, minOf(limit, take), spans)) {
                if (reading.fuzzyCount > 0) fromFuzzy.add(candidate.text)
                full.add(
                    Candidate(
                        candidate.text,
                        candidate.syllables,
                        candidate.score - penalty,
                        candidate.learned,
                        candidate.consumed,
                        candidate.ids,
                    ),
                )
            }
        }
        full.sortByDescending { it.score }
        return Pool(full, fromFuzzy)
    }

    /**
     * Picks the whole-input decodings worth showing out of [pool]: at most [FULL_DECODINGS],
     * with respellings held behind the literal readings while those are any good.
     */
    private fun budget(pool: Pool): List<Candidate> {
        val full = pool.full
        val fromFuzzy = pool.fuzzy
        // Bounded, so the bar is not filled end to end with variations on one sentence.
        //
        // Sixteen readings each contributing a full beam is far more whole-input decodings than
        // the strip can show, and offering them all crowded out the prefix words entirely:
        // `beijingdaxue` filled the bar with 北京大学, 北京大雪, 北京大削, 北极难过大学 and so on,
        // with no 北京 anywhere -- so a phrase could be typed whole or not at all, and committing
        // it a word at a time was impossible. The alternatives past the first few are near
        // duplicates of each other anyway; what a user wants next is a shorter commit.
        //
        // **A respelling is held behind the literal readings while those are any good.**
        //
        // The reference keyboards are why. Asked for `danta`, iOS offers 蛋塔 蛋撻 蛋鴨 淡雅 三亞
        // 反壓 and Gboard offers 但他 蛋塔 蛋撻 但她 但它: neither shows 当他 anywhere, though it
        // reads `dang ta` and is 27x commoner than 蛋挞, so on frequency alone it would lead.
        // They are not weighing it and finding it wanting -- they are not offering it, because
        // the user did not type `dang`. A fixed penalty cannot express that: the gap it must
        // cover is unbounded.
        //
        // But ordering fuzzy *strictly* last is wrong in the other direction, and `zongguo` is
        // the proof -- its exact readings are 总过, 总国, 宗过, all junk, and 中国 is reachable
        // only by respelling `zong` as `zhong`. Demoting it unconditionally buries the one good
        // answer.
        //
        // The distinction is not exact-versus-fuzzy but *whether the input reads acceptably as
        // typed*, and the test is the best literal reading against the best respelling. If the
        // literal one is already competitive, the respellings are demoted wholesale: the user
        // spelled something real and does not need to be second-guessed. If it is far behind,
        // the letters do not parse and the respelling is the answer, so nothing is demoted.
        //
        // The margin is what makes this a measurement rather than a rule of thumb, and the
        // inputs separate cleanly on it:
        //
        //     danta     best exact 但他 -13.2  vs best fuzzy 当他 -14.0   -> literal wins, hold
        //     zhidao    best exact 知道  -9.3  vs        自导 -17.8       -> literal wins, hold
        //     zongguo   best exact 总过 -27.0  vs        中国 -13.3       -> 13.7 behind, allow
        //     sengri    best exact 色女工日 -23.6 vs     生日 -13.5       -> 10.1 behind, allow
        //     sanghai   best exact 桑海 -15.3  vs        上海 -13.3       ->  2.0 behind, allow
        //
        // Note what this is *not*: a test of how tightly the literal readings cluster. `zongguo`
        // has three of them within 1.24 nats of each other and every one is junk, so cohesion
        // says "the input parses" exactly when it does not.
        val exact = ArrayList<Candidate>(FULL_DECODINGS)
        val deferred = ArrayList<Candidate>(FULL_DECODINGS)
        for (candidate in full) {
            if (candidate.text in fromFuzzy) deferred.add(candidate) else exact.add(candidate)
        }
        val bestExact = exact.firstOrNull()?.score
        val bestFuzzy = deferred.firstOrNull()?.score
        val literalIsEnough = bestExact != null &&
            (bestFuzzy == null || bestExact >= bestFuzzy - LITERAL_MARGIN)
        if (literalIsEnough) {
            // Respellings go behind the literal readings that are *worth having*, and no
            // further. Demoting them behind the whole literal list was too blunt: `cifan` reads
            // exactly as 此番 (-12.1) and then as nothing -- 此方案, 次方案, 此法案 are
            // assembled junk at -19 and worse -- so pushing the respelling 吃饭 (-13.5) behind
            // all of them spent the budget on rubbish and dropped the second-best answer in the
            // bar entirely.
            //
            // A literal reading blocks a respelling only while it is within [LITERAL_GOOD] nats
            // of the best literal one; past that it is an artefact of the lattice rather than a
            // reading anybody wants, and it competes on score like anything else. `danta` keeps
            // 但他 蛋挞 但她 但它 ahead of 当他 because all four are real words within 2.2 nats;
            // `cifan` yields second place to 吃饭 because its literal tail is 7 nats down.
            val lead = exact.first().score
            val (good, weak) = exact.partition { it.score >= lead - LITERAL_GOOD }
            val rest = (weak + deferred).sortedByDescending { it.score }
            return (good + rest).take(FULL_DECODINGS)
        }
        return full.take(FULL_DECODINGS)
    }

    /**
     * Words covering exactly these syllable ids, from the user's history first.
     *
     * An abbreviation (a bare consonant) is a prefix query over every syllable with that
     * initial, which is why it is handled here rather than in the dictionary: the dictionary
     * deals in resolved syllables only.
     */
    private fun wordsFor(
        ids: IntArray,
        abbreviation: () -> List<PinyinDict.Word> = { expandAbbreviation(ids) },
    ): List<Entry> {
        val out = ArrayList<Entry>(8)
        userDict?.wordsFor(ids)?.forEach {
            // A learned word has no corpus behind it, so it is normalised against the mainland
            // total simply to put it on the same scale as everything else; [LEARNED_BONUS] is
            // what actually carries its weight.
            out.add(Entry(it.text, unigram(it.weight), learned = true, rankWeight = it.weight))
        }
        val words = if (ids.any(Syllables::isInitial)) abbreviation() else dict.wordsFor(ids)
        for (word in words) {
            // Null means no corpus this mode reads has the word, which is what drops 牛肉面 from a
            // Taiwan user's bar and 牛肉麵 from a mainland user's, now that both live on the same
            // syllable key. In [ScriptMode.BOTH] nothing is dropped for being the other script:
            // each word is scored against its own corpus and the two compete.
            val logProb = mode.logProbOf(word.cn, word.tw) ?: continue
            out.add(Entry(word.text, logProb, false, rankWeight = word.rankWeightIn(mode)))
        }
        // Learned entries aside, everything here now exists in a corpus this mode reads.
        out.retainAll { it.learned || it.rankWeight > 0 }
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
                val logProb = mode.logProbOf(entry.cn, entry.tw)
                if (entry.text.length == 1 && logProb != null) {
                    out.add(
                        Entry(
                            entry.text,
                            logProb,
                            false,
                            backoff = true,
                            rankWeight = entry.rankWeightIn(mode),
                        ),
                    )
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
                    out[i] = Entry(
                        entry.text,
                        entry.logProb,
                        false,
                        backoff = true,
                        rankWeight = entry.rankWeight,
                    )
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
                    (entry.learned == prior.learned && entry.logProb > prior.logProb)
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
     * [wordsFor] and the stopping test, memoised per syllable run for one [candidates] call.
     *
     * The readings of one input are segmentations of the same letters, so they share most of
     * their runs: sixteen readings of a long word asked the dictionary the same questions sixteen
     * times, each one a binary search over varint-encoded keys plus a scan of the user
     * dictionary. Scoped to a single call because [mode] and the user dictionary can change
     * between calls, and either one changes the answer.
     */
    private inner class SpanTable {
        private val memo = HashMap<List<Int>, Lookup>()
        private val abbreviations = HashMap<List<Int>, List<IntArray>>()

        fun at(ids: IntArray, from: Int, span: Int): Lookup {
            val key = ids.asList().subList(from, from + span)
            memo[key]?.let { return it }
            val run = ids.copyOfRange(from, from + span)
            val lookup = if (run.any(Syllables::isInitial)) {
                // Built on the shorter run's prefixes rather than from nothing: resolving a bare
                // consonant is hundreds of prefix scans, and restarting that for every span
                // length at every position was most of the cost of a long consonant-heavy word.
                val prefixes = abbreviationPrefixes(ids, from, span)
                Lookup(
                    wordsFor(run) { abbreviationWords(prefixes) },
                    // Every longer run is a one-syllable extension of one of these prefixes, and
                    // an extension survives only if some key is longer than its prefix.
                    prefixes.any(dict::hasLongerKey),
                )
            } else {
                Lookup(
                    wordsFor(run),
                    dict.hasLongerKey(run) || userDict?.hasLongerKey(run) == true,
                )
            }
            memo[key.toList()] = lookup
            return lookup
        }

        /** [expandAbbreviation]'s surviving prefixes after [span] syllables, one step at a time. */
        private fun abbreviationPrefixes(ids: IntArray, from: Int, span: Int): List<IntArray> {
            if (span == 0) return ROOT_PREFIXES
            val key = ids.asList().subList(from, from + span)
            abbreviations[key]?.let { return it }
            val shorter = abbreviationPrefixes(ids, from, span - 1)
            val prefixes = if (shorter.isEmpty()) {
                emptyList()
            } else {
                abbreviationStep(shorter, ids[from + span - 1])
            }
            abbreviations[key.toList()] = prefixes
            return prefixes
        }
    }

    private class Lookup(val words: List<Entry>, val extendable: Boolean)

    /**
     * Words matching an abbreviated key like `b j` (北京).
     *
     * Only the fully-abbreviated and mixed forms people actually type are supported, and the
     * search is bounded: an abbreviation is by nature a wide query, and the useful answers are
     * the common words, which is exactly what the weight ordering gives.
     */
    private fun expandAbbreviation(ids: IntArray): List<PinyinDict.Word> {
        var prefixes = ROOT_PREFIXES
        for (id in ids) {
            prefixes = abbreviationStep(prefixes, id)
            if (prefixes.isEmpty()) return emptyList()
        }
        return abbreviationWords(prefixes)
    }

    /**
     * One syllable of [expandAbbreviation]: extends every prefix by [id] -- or, for a bare
     * consonant, by every syllable with that initial -- and keeps the strongest survivors.
     *
     * Resolved one initial at a time against the keys the dictionary actually holds. Doing it as
     * a prefix walk keeps this proportional to the matches rather than to the number of
     * syllables sharing an initial.
     */
    private fun abbreviationStep(prefixes: List<IntArray>, id: Int): List<IntArray> {
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
        return next.asSequence()
            .map { prefix ->
                prefix to (
                    dict.wordsWithPrefix(prefix, ABBREVIATION_PROBE, mode)
                        .firstOrNull()?.rankWeightIn(mode) ?: 0
                    )
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(ABBREVIATION_BRANCHES)
            .map { it.first }
            .toList()
    }

    /** The words under fully resolved abbreviation [prefixes], best first. */
    private fun abbreviationWords(prefixes: List<IntArray>): List<PinyinDict.Word> {
        // wordsFor, not wordsWithPrefix: the key is now fully resolved and the word must cover
        // exactly it. A prefix query here is what let 婀娜 answer a one-syllable `n`.
        val out = ArrayList<PinyinDict.Word>()
        for (key in prefixes) {
            out.addAll(dict.wordsFor(key, 4))
        }
        out.retainAll { it.rankWeightIn(mode) > 0 }
        out.sortByDescending { it.rankWeightIn(mode) }
        return out.take(ABBREVIATION_RESULTS)
    }

    private fun charactersFor(id: Int): List<PinyinDict.CharEntry> =
        if (Syllables.isInitial(id)) {
            val letter = Syllables.initialLetter(id)
            dict.syllableSpellings.withIndex()
                .filter { it.value[0] == letter }
                .flatMap { dict.charsFor(it.index) }
                .filter { it.rankWeightIn(mode) > 0 }
                .sortedByDescending { it.rankWeightIn(mode) }
        } else {
            dict.charsFor(id)
                .filter { it.rankWeightIn(mode) > 0 }
                .sortedByDescending { it.rankWeightIn(mode) }
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
     * of ~5.6e9 costs about -9.3; the four junk words cost far more. The sentence with fewer,
     * commoner words wins for the same reason it is more probable.
     *
     * **Only for weights that have no corpus of their own** -- the user dictionary. Dictionary
     * entries are normalised by [ScriptMode] against the corpus they actually came from, because
     * with two corpora in play a single shared denominator silently favours one of them; see
     * [ScriptMode.CN_TOTAL].
     */
    private fun unigram(weight: Int): Float =
        ln((weight + 1).toFloat() / ScriptMode.CN_TOTAL)

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

    /**
     * One lattice edge: some text, and what it costs.
     *
     * Carries a **log-probability rather than a raw weight**, which is the structural half of
     * cross-script ranking. While one corpus was ever read, a weight was enough -- every score
     * divided by the same denominator, so ordering by weight and ordering by log-probability were
     * the same ordering. Reading two corpora breaks that: `cn=51,915` and `tw=30,263` are
     * normalised by totals 2.5x apart, so the comparison is only meaningful *after* division.
     * Normalising at construction means no later code can compare two entries on the wrong scale.
     *
     * [rankWeight] survives alongside it for the places that genuinely want a raw-ish magnitude
     * (deduplication prefers the heavier of two identical texts); it is already scaled across
     * corpora by [ScriptMode.rankWeightOf].
     */
    private class Entry(
        val text: String,
        val logProb: Float,
        val learned: Boolean,
        /** True for a single character used as a fallback rather than a dictionary word. */
        val backoff: Boolean = false,
        val rankWeight: Int = 0,
    )

    companion object {
        /**
         * Paths kept per lattice position. The cost is linear in this number times the words at
         * each position, so it buys candidates at a very cheap rate on inputs this short.
         *
         * **Four was silently a cap on homophones, not just on sentences.** The beam is meant to
         * stop an unpromising *start* from being pruned before the sentence that needs it, and
         * four is ample for that. But every reading of one span lands in the same cell: the five
         * words on the `dan ta` key -- 但他, 蛋挞, 但她, 但它, 蛋塔 -- are five paths to the same
         * position, so a fifth homophone could not survive whatever its score. 蛋塔 was being
         * discarded here, before any cap or ranking ran, which is why raising [FULL_DECODINGS]
         * alone did nothing for it: the candidate had never existed to be cut.
         *
         * Eight covers the homophone families that actually occur -- the crowded two-syllable
         * keys run to five or six words a mainland corpus and a Taiwan one can contribute to
         * together -- while staying far below the point where decoding is noticeable.
         */
        private const val BEAM = 8

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
         * Charged per fuzzily-matched syllable, in nats.
         *
         * This is the price of assuming the user did *not* type what they meant. It was 2.3
         * ("ten times less likely"), which is far too cheap once the frequency gap between two
         * real words exceeds 10x -- and in a corpus this skewed that is common. `danta` was the
         * case that exposed it: 当他 (`dang ta`, weight 256000) is 27x commoner than 蛋挞
         * (`dan ta`, 9235), so at 2.3 the respelling won by about a nat and the top two
         * candidates for an exactly-spelled input were both misspellings of it. A typist who
         * spells a real syllable correctly should not have to scroll past the keyboard's guess
         * that they meant a different one.
         *
         * Swept over inputs of both kinds (see FuzzySpellingTest). The exact readings of
         * `danta` clear 当他 at 3.5 and hold from there up. The inputs fuzzy matching exists to
         * serve -- `zongguo`, `cifan`, `sengri`, `yingwen`, `zhidao` -- keep their answer at
         * rank 1 across the whole range, because those win on frequency rather than on a thin
         * margin; that is what makes raising this safe. The ceiling is around 6, where the
         * penalty starts crowding fuzzy readings out of the *bar* rather than off the top:
         * `cifan` drops 吃饭 below 次方, and `shanghai` and `xianzai` begin admitting junk
         * like 闪光和蔼 and 贤哉 in the freed slots. 4.0 is the middle of 3.5-5.0, with margin
         * on both sides.
         */
        private const val FUZZY_PENALTY = 4.0f

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
         *
         * Six rather than five because a two-syllable input can have more real readings than
         * that: `danta` has four exact ones (但他, 蛋挞, 但她, 但它) plus the Taiwan 蛋塔, and at
         * five the last of them fell off the end while a respelling held a slot. The extra slot
         * is spent on a reading of what was typed, never on another guess that it was mistyped
         * -- respellings are ordered behind every literal reading -- and the ranker's own filler
         * floor now trims the tail, so this cannot re-open the `beijingdaxue` crowding the cap
         * was introduced to stop.
         */
        private const val FULL_DECODINGS = 6

        /**
         * How many of the mainland decodings, best first, [bothScripts] converts to Traditional.
         *
         * Four times [FULL_DECODINGS]: enough that the literal readings the budget would keep
         * are all converted even when respellings and near-duplicates from other segmentations
         * sit between them, without converting every path of every reading.
         */
        private const val CONVERTED_POOL = FULL_DECODINGS * 4

        /**
         * How far behind the best respelling the best literal reading may fall and still
         * suppress it, in nats.
         *
         * Zero: the literal reading must be at least as good. Measured rather than chosen --
         * across the inputs that exercise both directions (`danta`, `zhidao`, `yingwen`,
         * `cifan` on one side; `zongguo`, `sengri`, `sanghai` on the other) the margin that
         * classifies every one correctly is 0 or 1, and anything from 2 up starts letting
         * respellings be suppressed on inputs that only read sensibly as a respelling. Zero is
         * the principled end of that range: "what you typed is at least as likely as what I
         * think you meant" is exactly when second-guessing is unwarranted.
         */
        private const val LITERAL_MARGIN = 0f

        /**
         * How far below the best literal reading another literal reading still outranks a
         * respelling, in nats.
         *
         * Four, from the middle of the 3-to-6 band that classifies every probed input
         * correctly. Below 3 a genuine homophone family starts to break up; at 8 the junk tail
         * of `cifan` (此方案, 次方案 at -19) begins outranking 吃饭 again, which is the failure
         * this bound exists to prevent. Four is comfortably inside.
         */
        private const val LITERAL_GOOD = 4f

        private const val PREFIX_WORDS = 6
        private const val CHAR_FLOOR = 8

        /**
         * Whole-input decodings a one-syllable reading may contribute; the rest of its
         * characters arrive through [CHAR_FLOOR]. Half of [FULL_DECODINGS], so the other
         * readings of the same letters (西安 for `xian`) keep a place ahead of the floor.
         */
        private const val SINGLE_SYLLABLE_DECODINGS = FULL_DECODINGS / 2
        private const val ABBREVIATION_BRANCHES = 24

        /**
         * Entries read per prefix when scoring how promising it is.
         *
         * Enough that a prefix whose commonest words belong to the *other* language model is
         * still scored by its best word in this one. One was the bug: see [expandAbbreviation].
         */
        private const val ABBREVIATION_PROBE = 16
        private const val ABBREVIATION_RESULTS = 12

        /** Where every abbreviation walk starts: one empty prefix. */
        private val ROOT_PREFIXES = listOf(IntArray(0))
    }
}
