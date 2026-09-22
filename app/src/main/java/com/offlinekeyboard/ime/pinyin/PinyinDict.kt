package com.offlinekeyboard.ime.pinyin

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The shipped pinyin dictionary: words keyed by syllable, characters, bigrams, and the
 * Simplified-to-Traditional tables.
 *
 * Built by `tools/build_pinyin_dict.py` (see that file for the format) and read here without
 * being parsed. The sections stay as bytes in a [ByteBuffer] and entries are decoded only when a
 * lookup reaches them -- 300,000 words as Kotlin objects would be tens of megabytes of heap and
 * seconds of startup, on a keyboard that must appear instantly. What is built eagerly is only
 * the key index: one [IntArray] of offsets, so a prefix query is a binary search rather than a
 * scan.
 *
 * Everything here is read-only after [load] and safe to share across threads.
 */
internal class PinyinDict private constructor(
    private val syllables: Array<String>,
    private val entries: ByteBuffer,
    /** Start of each key's record in the index section, sorted by the key's syllable ids. */
    private val keyOffsets: IntArray,
    private val index: ByteBuffer,
    private val charsBySyllable: Map<Int, List<CharEntry>>,
    private val bigrams: Map<Long, Int>,
    private val stChars: Map<String, String>,
    private val stPhrases: Map<String, String>,
) {

    /**
     * A word and its corpus weight in each language model.
     *
     * Two weights rather than one because the dictionary now holds two models: the mainland
     * Simplified one and a native Taiwan Traditional one. A word usually exists in only one --
     * 牛肉面 is mainland, 牛肉麵 Taiwanese -- and the absent side is 0, which [weightFor] reads
     * as "this model does not have this word".
     */
    class Word(val text: String, val cn: Int, val tw: Int) {
        fun weightFor(traditional: Boolean): Int = if (traditional) tw else cn

        /**
         * The weight to rank by in [mode], with the two corpora put on one scale.
         *
         * Zero means "no corpus this mode reads contains this word", which is the same signal
         * [weightFor] gives for a single model -- so the callers that drop zero-weight entries
         * keep working unchanged when the mode reads both.
         */
        fun rankWeightIn(mode: ScriptMode): Int = mode.rankWeightOf(cn, tw)
    }

    /** A single character reading and its weight in each model. */
    class CharEntry(val text: String, val cn: Int, val tw: Int) {
        fun weightFor(traditional: Boolean): Int = if (traditional) tw else cn

        /** See [Word.rankWeightIn]. */
        fun rankWeightIn(mode: ScriptMode): Int = mode.rankWeightOf(cn, tw)
    }

    /** The Simplified-to-Traditional tables, handed to [Script]. */
    val conversionChars: Map<String, String> get() = stChars
    val conversionPhrases: Map<String, String> get() = stPhrases

    /** Total number of distinct syllable keys; used by tests to assert the asset loaded. */
    val keyCount: Int get() = keyOffsets.size

    /** Every syllable spelling in the asset, for the generated-table agreement test. */
    val syllableSpellings: Array<String> get() = syllables

    // --- lookup ------------------------------------------------------------------------------

    /**
     * Words whose syllable key is exactly [ids], best-first.
     *
     * Exactness matters: this is the query behind "the user has typed a complete word", and a
     * prefix match here would offer 中国人 for `zhongguo` as though it were the same length.
     */
    fun wordsFor(ids: IntArray, limit: Int = 32): List<Word> {
        val at = findKey(ids) ?: return emptyList()
        return readMembers(at, limit)
    }

    /**
     * Words whose key *begins with* [ids], best-first, for the sentence decoder.
     *
     * Used when deciding what a run of syllables could start with: typing `beijingdaxue`, the
     * decoder asks what words begin at syllable 0 and gets 北京 among others. The keys are sorted
     * by syllable id, so every key sharing a prefix is one contiguous run and this is two binary
     * searches -- the same trick [com.offlinekeyboard.ime.tap.WordIndex] uses over letters.
     */
    fun wordsWithPrefix(
        ids: IntArray,
        limit: Int = 32,
        mode: ScriptMode = ScriptMode.SIMPLIFIED,
    ): List<Word> {
        if (ids.isEmpty()) return emptyList()
        val lo = lowerBound(ids)
        val out = ArrayList<Word>(limit)
        var i = lo
        // Counted in entries *of the requested model*, not in entries read. Two models share this
        // index, so a run can be dozens of words the caller cannot use -- every single-character
        // Traditional reading of `bei` sits ahead of `bei jing`, and filling the budget with them
        // made `bj` score the `bei` branch at zero and drop it, so 北京 became unreachable by
        // abbreviation. Reading past them costs a few varints and is bounded by [PREFIX_SCAN].
        var scanned = 0
        while (i < keyOffsets.size && out.size < limit && scanned < PREFIX_SCAN) {
            val keyIds = readKeyIds(keyOffsets[i])
            if (!startsWith(keyIds, ids)) break
            for (word in readMembers(keyOffsets[i], limit)) {
                scanned++
                if (word.rankWeightIn(mode) > 0) out.add(word)
                if (out.size >= limit) break
            }
            i++
        }
        // Ranked in the model that is actually in play, or a Taiwan user's abbreviation would be
        // resolved by mainland frequencies. In [ScriptMode.BOTH] the two columns are put on one
        // scale first, so a Taiwan-only word is ranked by how common it is in Taiwanese writing
        // rather than by a raw count from a corpus 2.5x smaller.
        out.sortByDescending { it.rankWeightIn(mode) }
        return out
    }

    /** Single characters that can be read as [syllableId], best-first. */
    fun charsFor(syllableId: Int): List<CharEntry> = charsBySyllable[syllableId].orEmpty()

    /**
     * How often [second] followed [first] in the phrase table, or 0.
     *
     * Packed into a long key rather than a Pair so the lookup allocates nothing: this runs
     * inside the Viterbi inner loop, once per edge considered.
     */
    fun bigram(first: Char, second: Char): Int =
        bigrams[(first.code.toLong() shl 16) or second.code.toLong()] ?: 0

    // --- index mechanics ---------------------------------------------------------------------

    private fun readKeyIds(offset: Int): IntArray {
        val p = Cursor(index, offset)
        val n = p.varint()
        return IntArray(n) { p.varint() }
    }

    /** Reads the words under a key record, which follow that key's syllable ids. */
    private fun readMembers(offset: Int, limit: Int): List<Word> {
        val p = Cursor(index, offset)
        val n = p.varint()
        repeat(n) { p.varint() }
        val count = p.varint()
        val out = ArrayList<Word>(minOf(count, limit))
        for (i in 0 until count) {
            val entryOffset = p.varint()
            if (out.size >= limit) continue
            out.add(readEntry(entryOffset))
        }
        return out
    }

    /** Entry offsets are relative to the start of the entries section, header included. */
    private fun readEntry(offset: Int): Word {
        val p = Cursor(entries, offset)
        val text = p.string()
        return Word(text, p.varint(), p.varint())
    }

    private fun findKey(ids: IntArray): Int? {
        var lo = 0
        var hi = keyOffsets.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compare(readKeyIds(keyOffsets[mid]), ids)
            when {
                cmp < 0 -> lo = mid + 1
                cmp > 0 -> hi = mid - 1
                else -> return keyOffsets[mid]
            }
        }
        return null
    }

    /** First key that is not ordered before [ids], the start of its prefix run. */
    private fun lowerBound(ids: IntArray): Int {
        var lo = 0
        var hi = keyOffsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(readKeyIds(keyOffsets[mid]), ids) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun compare(a: IntArray, b: IntArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            if (a[i] != b[i]) return a[i] - b[i]
        }
        return a.size - b.size
    }

    private fun startsWith(key: IntArray, prefix: IntArray): Boolean {
        if (key.size < prefix.size) return false
        for (i in prefix.indices) if (key[i] != prefix[i]) return false
        return true
    }

    /** A position in a buffer. Not thread-confined state: each lookup makes its own. */
    private class Cursor(private val buf: ByteBuffer, private var pos: Int) {
        /** Where the cursor currently sits, used when recording record offsets. */
        fun position(): Int = pos

        fun varint(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = buf.get(pos++).toInt()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun string(): String {
            val len = varint()
            val bytes = ByteArray(len)
            for (i in 0 until len) bytes[i] = buf.get(pos + i)
            pos += len
            return String(bytes, Charsets.UTF_8)
        }
    }

    companion object {
        /**
         * Entries [wordsWithPrefix] will read before giving up on filling its limit.
         *
         * A bound on the cost of skipping entries that belong to the other language model, so a
         * prefix whose run is entirely Traditional cannot make a mainland lookup walk the index.
         */
        private const val PREFIX_SCAN = 512

        private const val MAGIC = 0x33445950 // "PYD3" little-endian
        private const val SEC_SYLLABLES = 1
        private const val SEC_ENTRIES = 2
        private const val SEC_INDEX = 3
        private const val SEC_CHARS = 4
        private const val SEC_BIGRAM = 5
        private const val SEC_ST_CHARS = 6
        private const val SEC_ST_PHRASES = 7

        /**
         * Reads the asset.
         *
         * Takes the whole file into one array because that is what `AssetManager` can give us --
         * an asset is not a file with a path, so it cannot be mapped. 8 MB read once on a
         * background thread is the price; the win is that nothing is parsed into objects.
         */
        fun load(input: InputStream): PinyinDict {
            val bytes = input.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(buf.getInt(0) == MAGIC) { "not a pinyin dictionary" }
            val sectionCount = buf.getInt(8)
            val sections = HashMap<Int, Pair<Int, Int>>(sectionCount)
            for (i in 0 until sectionCount) {
                val at = 12 + i * 12
                sections[buf.getInt(at)] = buf.getInt(at + 4) to buf.getInt(at + 8)
            }

            fun slice(id: Int): ByteBuffer {
                val (offset, length) = sections[id] ?: error("missing section $id")
                return ByteBuffer.wrap(bytes, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN)
            }

            val syllableBuf = slice(SEC_SYLLABLES)
            val syllableCount = syllableBuf.getInt(0)
            val syllableCursor = Cursor(syllableBuf, 4)
            val syllables = Array(syllableCount) { syllableCursor.string() }

            val entries = slice(SEC_ENTRIES)
            val index = slice(SEC_INDEX)

            // The key offsets are the one thing built eagerly: a walk of the index section
            // recording where each record starts, so later lookups can binary-search it.
            val keyCount = index.getInt(0)
            val keyOffsets = IntArray(keyCount)
            val walker = Cursor(index, 4)
            for (i in 0 until keyCount) {
                keyOffsets[i] = walker.position()
                val n = walker.varint()
                repeat(n) { walker.varint() }
                val members = walker.varint()
                repeat(members) { walker.varint() }
            }

            val charsBuf = slice(SEC_CHARS)
            val charGroups = charsBuf.getInt(0)
            val charCursor = Cursor(charsBuf, 4)
            val chars = HashMap<Int, List<CharEntry>>(charGroups)
            repeat(charGroups) {
                val sid = charCursor.varint()
                val n = charCursor.varint()
                val list = ArrayList<CharEntry>(n)
                repeat(n) {
                    val text = charCursor.string()
                    list.add(CharEntry(text, charCursor.varint(), charCursor.varint()))
                }
                chars[sid] = list
            }

            val bigramBuf = slice(SEC_BIGRAM)
            val bigramCount = bigramBuf.getInt(0)
            val bigramCursor = Cursor(bigramBuf, 4)
            val bigrams = HashMap<Long, Int>(bigramCount * 2)
            repeat(bigramCount) {
                val a = bigramCursor.string()
                val b = bigramCursor.string()
                val count = bigramCursor.varint()
                if (a.length == 1 && b.length == 1) {
                    bigrams[(a[0].code.toLong() shl 16) or b[0].code.toLong()] = count
                }
            }

            fun table(id: Int): Map<String, String> {
                val buffer = slice(id)
                val n = buffer.getInt(0)
                val cursor = Cursor(buffer, 4)
                val out = HashMap<String, String>(n * 2)
                repeat(n) {
                    val k = cursor.string()
                    out[k] = cursor.string()
                }
                return out
            }

            return PinyinDict(
                syllables = syllables,
                entries = entries,
                keyOffsets = keyOffsets,
                index = index,
                charsBySyllable = chars,
                bigrams = bigrams,
                stChars = table(SEC_ST_CHARS),
                stPhrases = table(SEC_ST_PHRASES),
            )
        }
    }
}
