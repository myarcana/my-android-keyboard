package com.offlinekeyboard.ime.pinyin

/**
 * Readings of letters that do not parse as pinyin, on the assumption that one key was slipped.
 *
 * `lupbogao` is the case this exists for. It is one key away from `luobogao` (萝卜糕) -- `p` sits
 * right beside `o` -- and nothing else about it is ambiguous, but as typed it does not segment
 * into syllables: the `p` can only be read as a bare abbreviation consonant, `lu p bo gao`, and the
 * lattice dutifully builds 路拼搏港澳 out of it. Fuzzy pinyin cannot help, because it models a
 * *speaker's* confusion (z/zh, an/ang), not a *finger's*.
 *
 * **Only input that fails to parse is second-guessed.** A string that reads as clean syllables is
 * left alone however unlikely its decoding, because a well-formed pinyin string is evidence the
 * user meant those syllables -- and correcting syllables that parse is what would turn this into
 * an autocorrect that rewrites `danta` into whatever is commoner nearby. The trigger is a bare
 * consonant with a spelled-out syllable after it: trailing consonants are an abbreviation
 * (`beijingdx`) or a syllable still being typed (`luob` on the way to `luobo`), while one in the
 * middle of spelled syllables is not something anyone abbreviates on purpose.
 *
 * **Letters with no reading at all are not corrected either.** That sounds like the clearest case
 * of a slip, but in a keyboard whose English bar also reads pinyin it is overwhelmingly an English
 * word: `will`, `find`, `have` do not segment, and correcting them put 起来了, 新的 and 哈个 under
 * ordinary English. A slip in pinyin nearly always still parses, just badly -- the wrong letter
 * becomes a stranded consonant -- and that is the signature looked for.
 *
 * **One slip, and only near where the parse broke.** The edits are the four a thumb makes -- the
 * neighbouring key, two letters swapped, a letter doubled or stray, a letter dropped -- applied
 * within one letter of each break. A correction must then read as clean syllables throughout, or
 * it has not explained the break. Two slips in one word are not attempted: by then the letters are
 * as likely to be a different word as a damaged one, and the candidates would be guesses.
 *
 * The corrections are scored by the decoder like any reading, charged [Decoder]'s typo penalty,
 * and held behind the literal readings whenever those are any good -- the same rule that keeps a
 * fuzzy respelling from outranking what the user actually spelled.
 */
internal object Typos {

    /**
     * The letter rows as this keyboard draws them, with each row's offset in key widths.
     *
     * The offsets are the iOS layout's: the home row is inset half a key, and the bottom row sits
     * behind a shift key one and a half keys wide. Only relative positions matter here, so the
     * neighbours of `z` come out as `a s d x` and those of `p` as `o l`.
     */
    private val ROWS = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)

    /** Keys whose centres are close enough that a thumb aimed at one lands on the other. */
    private val NEIGHBOURS: Map<Char, String> = buildMap {
        val centre = HashMap<Char, Pair<Int, Float>>()
        for ((row, pair) in ROWS.withIndex()) {
            val (letters, offset) = pair
            letters.forEachIndexed { i, c -> centre[c] = row to offset + i + 0.5f }
        }
        for ((c, at) in centre) {
            val near = StringBuilder()
            for ((d, other) in centre) {
                if (d == c) continue
                val rows = kotlin.math.abs(at.first - other.first)
                val dx = kotlin.math.abs(at.second - other.second)
                // Same row: the key either side. Adjacent row: the keys touching it diagonally,
                // which on a staggered layout are the two whose centres are within a key width.
                if ((rows == 0 && dx <= 1f) || (rows == 1 && dx <= 1f)) near.append(d)
            }
            put(c, near.toString())
        }
    }

    /** The keys a thumb aimed at [c] could plausibly have hit instead. */
    fun neighbours(c: Char): String = NEIGHBOURS[c].orEmpty()

    /**
     * Corrected readings of [input], each with its [Syllables.Reading.ends] mapped back onto the
     * letters as typed and [Syllables.Reading.typos] set; empty when [input] needs none.
     *
     * [literal] is what [Syllables.readings] returned for the same input, passed in so it is not
     * segmented twice.
     */
    fun readings(input: String, literal: List<Syllables.Reading>): List<Syllables.Reading> {
        // No reading at all is not a slip in pinyin, it is almost always a word in another
        // language: `will`, `find`, `have` do not segment, and correcting them put 起来了 and 新的
        // under ordinary English in the English bar. Only letters that *do* parse, but badly, are
        // second-guessed.
        if (input.length < MIN_LENGTH || literal.isEmpty()) return emptyList()
        val breaks = breaksIn(input, literal) ?: return emptyList()

        val out = ArrayList<Syllables.Reading>()
        val tried = HashSet<String>()
        tried.add(input)
        for (edit in editsAround(input, breaks)) {
            if (!tried.add(edit.text)) continue
            var taken = 0
            for (reading in Syllables.readings(edit.text, READINGS_PER_EDIT * 4)) {
                if (taken >= READINGS_PER_EDIT) break
                if (reading.fuzzyCount > 0 || !isClean(reading)) continue
                out.add(
                    Syllables.Reading(
                        reading.ids,
                        IntArray(reading.ends.size) { edit.toTyped(reading.ends[it]) },
                        fuzzyCount = 0,
                        typos = 1,
                    ),
                )
                taken++
            }
        }
        // Fewest syllables first, stably, so slips of the same kind are tried in order of how
        // much of a word they make: `luo bo gao` is three syllables, while an inserted vowel that
        // merely makes the letters pronounceable -- `lu pa bo gao` -- costs one more. Longer
        // syllables are what dictionary words are made of, so this is the cheap proxy for "reads
        // as a word" that decides which corrections are worth a Viterbi pass.
        out.sortBy { it.size }
        return if (out.size > MAX_READINGS) out.subList(0, MAX_READINGS).toList() else out
    }

    /**
     * Where the input stops being pinyin, or null if it never does.
     *
     * Taken from the readings with the fewest stranded consonants, because the segmenter also
     * offers abbreviation splits of perfectly good syllables, and those place breaks where the
     * input has none.
     *
     * A slip can strand several letters at once -- `shrnme`, an `r` for `e`, leaves `s h r n`
     * with no vowel between them -- but they are always **one contiguous run**, because they are
     * the wreckage of one syllable. Stranded letters in two separate places are two slips, and
     * one edit cannot mend that.
     */
    private fun breaksIn(input: String, literal: List<Syllables.Reading>): Set<Int>? {
        var fewest = Int.MAX_VALUE
        val sites = HashSet<Int>()
        for (reading in literal) {
            val stranded = strandedConsonants(reading)
            if (stranded.isEmpty()) return null
            if (stranded.size < fewest) {
                fewest = stranded.size
                sites.clear()
            }
            if (stranded.size == fewest) sites.addAll(stranded)
        }
        if (fewest > MAX_RUN) return null
        return sites
    }

    /**
     * Letter positions of the bare consonants in [reading] that a full syllable follows.
     *
     * A consonant with only consonants after it is an abbreviation, which people type on purpose
     * -- `bjdx`, `beijingdx` -- or the start of a syllable not finished yet (`luob`). One with a
     * spelled-out syllable after it is neither: nobody abbreviates the middle of a word and then
     * spells the rest, so it is a letter the segmenter could find no syllable for.
     */
    private fun strandedConsonants(reading: Syllables.Reading): List<Int> {
        val out = ArrayList<Int>(2)
        var spelledAfter = false
        for (i in reading.ids.indices.reversed()) {
            if (!Syllables.isInitial(reading.ids[i])) {
                spelledAfter = true
                continue
            }
            if (spelledAfter) out.add(reading.ends[i] - 1)
        }
        if (out.size > 1) {
            out.reverse()
            // Not one run: two slips, or an abbreviation and a slip. Reported as more stranded
            // letters than any single run may hold, so the reading cannot pass as a candidate site.
            for (k in 1 until out.size) if (out[k] != out[k - 1] + 1) return List(MAX_RUN + 1) { out[0] }
        }
        return out
    }

    private fun isClean(reading: Syllables.Reading): Boolean =
        strandedConsonants(reading).isEmpty()

    /**
     * One corrected spelling, and how to find a position in it among the letters as typed.
     *
     * The decoder reports what a candidate *consumes* in typed letters, and committing deletes
     * exactly that many; a correction that adds or removes a letter shifts every position after
     * it, so the mapping travels with the edit.
     */
    private class Edit(val text: String, private val at: Int, private val shift: Int) {
        /** A syllable end in [text] as an end in the typed letters. */
        fun toTyped(end: Int): Int = if (end <= at) end else end + shift
    }

    /**
     * Every single-slip edit touching a letter within one of [breaks], commonest kind of slip
     * first -- so when [MAX_READINGS] cuts the list, it cuts the least likely explanations.
     */
    private fun editsAround(input: String, breaks: Set<Int>): List<Edit> {
        val positions = sortedSetOf<Int>()
        for (b in breaks) for (p in b - 1..b + 1) if (p in input.indices) positions.add(p)
        val out = ArrayList<Edit>()
        // The neighbouring key: the commonest slip there is, and the one `lupbogao` is.
        for (p in positions) {
            for (c in neighbours(input[p])) {
                out.add(Edit(input.substring(0, p) + c + input.substring(p + 1), input.length, 0))
            }
        }
        // Two letters swapped, which a fast thumb on either side of the keyboard does.
        for (p in positions) {
            if (p + 1 >= input.length || input[p] == input[p + 1]) continue
            val swapped = StringBuilder(input).apply {
                setCharAt(p, input[p + 1])
                setCharAt(p + 1, input[p])
            }.toString()
            out.add(Edit(swapped, input.length, 0))
        }
        // A stray letter at p. Ends before it are unchanged; an end at or after it is one letter
        // later in the typed text -- including an end exactly at p, so the syllable before the
        // stray letter swallows it rather than leaving it to start the next commit.
        for (p in positions) {
            out.add(Edit(input.removeRange(p, p + 1), p - 1, +1))
        }
        // A dropped letter, before or after one of these. Any letter, because what is dropped is
        // whatever the thumb skipped, not a neighbour of anything. An end just past the inserted
        // letter maps to the gap: the missing letter belonged to the syllable ending there.
        val gaps = sortedSetOf<Int>()
        for (p in positions) {
            gaps.add(p)
            gaps.add(p + 1)
        }
        for (gap in gaps) {
            for (c in 'a'..'z') {
                out.add(Edit(input.substring(0, gap) + c + input.substring(gap), gap, -1))
            }
        }
        return out
    }

    /** Below this there is too little context to call anything a slip. */
    private const val MIN_LENGTH = 4

    /**
     * Stranded consonants one slip can be asked to explain. Four is `s h r n` in `shrnme`; a
     * longer run of consonants is an abbreviation someone typed on purpose.
     */
    private const val MAX_RUN = 4

    /** Segmentations kept per corrected spelling; `xian` style ambiguity needs two. */
    private const val READINGS_PER_EDIT = 2

    /**
     * Corrected readings handed to the decoder, in all. Each one is a Viterbi pass, so this is
     * the bound on what a mistyped word can cost per keystroke.
     */
    private const val MAX_READINGS = 32
}
