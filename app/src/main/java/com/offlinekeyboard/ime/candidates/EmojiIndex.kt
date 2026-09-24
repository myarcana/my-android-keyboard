package com.offlinekeyboard.ime.candidates

import java.io.InputStream

/**
 * The emoji the suggestion bar offers for a word being typed.
 *
 * Requirement 9 is that the bar never suggests English *words* -- nothing typed is ever
 * rewritten into a different word. Offering an emoji for a word is the opposite: it only ever
 * fires when the user reaches for it, and the letters stay exactly as typed until they do.
 *
 * Backed by `assets/emoji_en.tsv`, generated from Unicode's emoji-test.txt and CLDR's English
 * annotations by `tools/build_emoji_index.py`. The data is committed rather than fetched -- the
 * app has no INTERNET permission and could not download it even if it wanted to.
 */
class EmojiIndex private constructor(
    private val entries: List<Entry>,
    /** Every searchable term, sorted, so a prefix is a contiguous range found by binary search. */
    private val terms: List<String>,
    /** Parallel to [terms]: the entries each term points at, in canonical order. */
    private val postings: List<IntArray>,
    private val namesToEntry: Map<String, Int>,
) {

    private class Entry(val emoji: String, val name: String)

    /**
     * One emoji offered for a query, and the words that made it match.
     *
     * [terms] are what the query was matched *against*: the query itself for an exact match, or
     * the longer names and keywords it is a prefix of -- `mark` and `magic` for `ma`. They are
     * kept because a prefix match is only as good as the chance that the word being typed really
     * is one of them, and only the caller has the English frequencies to say how good that is.
     * See [UnifiedCandidates.suggest].
     */
    class Match(val emoji: String, val exact: Boolean, val terms: List<String>)

    /**
     * Emoji for [query], best first, or empty if the query is too short to be meaningful.
     *
     * Ranked in tiers rather than by a score, because the tiers are what the eye expects:
     * the emoji actually *called* "pizza" must come before every emoji merely tagged with it,
     * and a whole-word match must beat a prefix.
     *
     * Ties break on file order, which the generator has already sorted by Unicode's measured
     * emoji frequency. That is the whole ranking: no weights to tune here, and re-ranking means
     * regenerating the asset rather than editing this.
     */
    fun search(query: String, limit: Int = MAX_RESULTS): List<String> =
        matches(query, limit).map { it.emoji }

    /** [search], with what each emoji was matched against. */
    fun matches(query: String, limit: Int = MAX_RESULTS): List<Match> {
        val q = query.lowercase().trim()
        if (q.length < MIN_QUERY) return emptyList()

        val tiers = LinkedHashMap<Int, Int>()
        val matched = HashMap<Int, MutableList<String>>()
        fun offer(entry: Int, tier: Int, term: String) {
            val existing = tiers[entry]
            if (existing == null || tier < existing) tiers[entry] = tier
            val list = matched.getOrPut(entry) { ArrayList(2) }
            if (term !in list) list += term
        }

        namesToEntry[q]?.let { offer(it, TIER_EXACT_NAME, q) }
        indexOfTerm(q)?.let { i -> postings[i].forEach { offer(it, TIER_EXACT_TERM, q) } }
        for (i in prefixRange(q)) {
            if (terms[i] == q) continue
            postings[i].forEach { offer(it, TIER_PREFIX, terms[i]) }
        }
        for ((name, entry) in namesToEntry) {
            if (name.startsWith(q)) offer(entry, TIER_NAME_PREFIX, name)
        }

        return tiers.entries
            .sortedWith(compareBy({ it.value }, { it.key }))
            .take(limit)
            .map { (entry, tier) ->
                val exact = tier <= TIER_EXACT_TERM
                // An exact match stands for the query itself, whatever longer terms it also
                // prefixes: the evidence for it is the typed word, not a guess at a longer one.
                Match(entries[entry].emoji, exact, if (exact) listOf(q) else matched.getValue(entry))
            }
    }

    private fun indexOfTerm(term: String): Int? =
        terms.binarySearch(term).takeIf { it >= 0 }

    /** Indices of every term starting with [prefix]; empty when none do. */
    private fun prefixRange(prefix: String): IntRange {
        var lo = terms.binarySearch(prefix)
        if (lo < 0) lo = -lo - 1
        var hi = lo
        while (hi < terms.size && terms[hi].startsWith(prefix)) hi++
        return lo until hi
    }

    companion object {
        /**
         * The shortest query the bar will look up.
         *
         * One letter does match a great many emoji, which is why this was 2. What makes it
         * tolerable at 1 is the ranking rather than the cutoff: the tiers put exact names first
         * and bare prefixes last, so "a" leads with the emoji actually *called* "a" -- and the
         * alternative at one letter is not a cleaner bar but the default set below, which is not
         * about the letter being typed at all.
         */
        const val MIN_QUERY = 1
        const val MAX_RESULTS = 12

        /**
         * What the bar offers when there is no word to look emoji up by -- an empty field, just
         * after a space, or straight after punctuation.
         *
         * The strip is the one row that is always on screen, and leaving it blank whenever a word
         * is not half-typed wasted it for most of the time the keyboard is up. These are the
         * common ones in rough order of use, and they are a *fallback*: the moment a letter is
         * typed, the word's own matches replace them.
         *
         * Committed here rather than read from the asset because they are a UI decision about
         * which emoji a person reaches for unprompted, not a fact about the Unicode data -- the
         * asset's own frequency order leads with emoji that are common in text overall, which is
         * not the same question.
         */
        val DEFAULTS = listOf("😀", "😂", "❤️", "👍", "🙏", "🎉", "🔥", "😍")

        private const val TIER_EXACT_NAME = 0
        private const val TIER_EXACT_TERM = 1
        private const val TIER_PREFIX = 2
        private const val TIER_NAME_PREFIX = 3

        fun load(stream: InputStream): EmojiIndex {
            val entries = mutableListOf<Entry>()
            val namesToEntry = HashMap<String, Int>()
            val byTerm = HashMap<String, MutableList<Int>>()

            stream.bufferedReader().forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val parts = line.split('\t')
                if (parts.size < 2) return@forEachLine
                val index = entries.size
                entries += Entry(parts[0], parts[1])
                // First emoji wins a name collision, which canonical order makes the better one.
                namesToEntry.putIfAbsent(parts[1], index)
                val terms = parts.getOrNull(2)?.split('|').orEmpty() + parts[1]
                terms.forEach { term ->
                    if (term.isNotEmpty()) byTerm.getOrPut(term) { mutableListOf() } += index
                }
            }

            val sorted = byTerm.keys.sorted()
            return EmojiIndex(
                entries = entries,
                terms = sorted,
                postings = sorted.map { byTerm.getValue(it).toIntArray() },
                namesToEntry = namesToEntry,
            )
        }
    }
}
