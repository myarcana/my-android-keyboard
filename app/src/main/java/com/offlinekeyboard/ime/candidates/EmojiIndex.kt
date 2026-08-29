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
    fun search(query: String, limit: Int = MAX_RESULTS): List<String> {
        val q = query.lowercase().trim()
        if (q.length < MIN_QUERY) return emptyList()

        val ranked = LinkedHashMap<Int, Int>()
        fun offer(entry: Int, tier: Int) {
            val existing = ranked[entry]
            if (existing == null || tier < existing) ranked[entry] = tier
        }

        namesToEntry[q]?.let { offer(it, TIER_EXACT_NAME) }
        indexOfTerm(q)?.let { i -> postings[i].forEach { offer(it, TIER_EXACT_TERM) } }
        for (i in prefixRange(q)) {
            if (terms[i] == q) continue
            postings[i].forEach { offer(it, TIER_PREFIX) }
        }
        for ((name, entry) in namesToEntry) {
            if (name.startsWith(q)) offer(entry, TIER_NAME_PREFIX)
        }

        return ranked.entries
            .sortedWith(compareBy({ it.value }, { it.key }))
            .take(limit)
            .map { entries[it.key].emoji }
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
        /** One letter matches almost everything, so the bar would be noise rather than help. */
        const val MIN_QUERY = 2
        const val MAX_RESULTS = 12

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
