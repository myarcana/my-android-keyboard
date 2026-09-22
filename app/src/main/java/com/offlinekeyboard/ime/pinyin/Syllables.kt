package com.offlinekeyboard.ime.pinyin

/**
 * Turns a run of letters into the syllable sequences it could be.
 *
 * This is the half of pinyin input that has nothing to do with Chinese: `xian` is either `xian`
 * (先) or `xi'an` (西安), `nver` is `nv er` (女儿), and nothing downstream can recover a split the
 * segmenter did not offer. So it offers all of them, and lets the decoder's language model pick
 * -- which is the only place the evidence to choose actually exists.
 *
 * **Segmentation is greedy-longest *per path*, not globally.** A single longest-match pass gets
 * `xian` wrong the moment the intended word was 西安, and a pass that always prefers short
 * syllables gets `xian` wrong the other way. Both readings are produced and ranked later.
 *
 * Three input styles are accepted, because all three are things people actually type:
 *
 *  - **Full**: `nihao` -> `ni hao`.
 *  - **Abbreviated**: `nh`, where a bare consonant stands for any syllable starting with it.
 *    `bjdx` -> 北京大学. This is how experienced typists enter familiar words, and an IME without
 *    it feels slow to them.
 *  - **Mixed**: `beijingdx`, the common real case -- spell out what is ambiguous, abbreviate the
 *    rest.
 *
 * Fuzzy pinyin (zh/z, an/ang, n/l...) is applied here too, as *alternative spellings of a
 * syllable* rather than as a separate matching mode, so a fuzzy syllable costs nothing extra
 * downstream: by the time the decoder sees it, it is an ordinary syllable id.
 */
internal object Syllables {

    /** A syllable id, or [INITIAL_BASE] + a letter index for a bare-consonant abbreviation. */
    const val INITIAL_BASE = 10_000

    /**
     * One way to read the input: the syllable ids, and where each one ended.
     *
     * [ends] is what lets the caller map a candidate back onto the raw letters -- committing
     * 北京 out of `beijingdx` has to consume exactly `beijing` and leave `dx` composing, and the
     * only record of where that boundary fell is here.
     */
    class Reading(
        val ids: IntArray,
        val ends: IntArray,
        /** How many syllables in this reading were matched fuzzily rather than exactly. */
        val fuzzyCount: Int = 0,
    ) {
        val size: Int get() = ids.size
    }

    /**
     * Fuzzy pinyin pairs, applied symmetrically.
     *
     * These are the confusions of Mandarin speakers whose own dialect does not make the
     * distinction -- southern speakers typing `zhongguo` as `zongguo`, `chi` as `ci`.
     *
     * **The set is the one Gboard ships enabled, and that is not an aesthetic choice.** The
     * previous list applied every pair it could think of, on the reasoning that "a fuzzy match is
     * only ever *added* as a lower-ranked alternative, so a typist who makes no such confusion
     * sees the same first candidate either way". That premise is false, and `danta` is the proof:
     * 当他 (`dang ta`) is 27x commoner than 蛋挞 (`dan ta`), so the `ang`/`an` rule let a word the
     * user did not spell outrank every word they did. A fixed penalty cannot fix this in general
     * because the frequency gap it must cover is unbounded -- the only reliable lever is not
     * generating the confusion at hand.
     *
     * Read off a real device (`dumpsys input_method`, Gboard's live Pinyin config), the
     * reference implementation enables `z/zh`, `c/ch`, `s/sh`, `an/ang`, `en/eng`, `in/ing` and
     * disables `l/n`, `f/h`, `r/l`, `k/g`, `ian/iang`, `uan/uang` -- behind a master switch that
     * itself defaults to off. Three of the rules dropped here (`n`/`l`, `f`/`h`, `r`/`l`) were
     * ones Gboard ships disabled: they are the widest of the confusions, roughly doubling the
     * lattice, and they are what made `lan` mean 南 and `fu` mean 湖 for every typist rather
     * than for the minority who conflate them.
     *
     * The six kept are the retroflex trio and the three nasal finals, which are the genuinely
     * common southern confusions. They stay on unconditionally rather than behind a setting,
     * because this keyboard has no settings screen for them yet; [Decoder.FUZZY_PENALTY] prices
     * them, and the pricing is now doing a job sized to fit.
     *
     * Written as initial and final rewrites because that is how they apply: `zh`->`z` is only a
     * confusion at the start of a syllable, `ang`->`an` only at the end.
     */
    private val FUZZY_INITIALS = arrayOf(
        "zh" to "z", "ch" to "c", "sh" to "s",
    )

    private val FUZZY_FINALS = arrayOf(
        "ang" to "an", "eng" to "en", "ing" to "in",
    )

    /** [SyllableTable.ALL] as a set, for membership tests during segmentation. */
    private val VALID: Set<String> = SyllableTable.ALL.toHashSet()

    /** Syllable spelling -> its index in [SyllableTable.ALL]. */
    private val IDS: Map<String, Int> =
        SyllableTable.ALL.withIndex().associate { (i, s) -> s to i }

    /**
     * Every spelling that should resolve to a given syllable, fuzzy forms included.
     *
     * Built once by *generating* each syllable's fuzzy variants and inverting the mapping, so a
     * variant can never be listed for a syllable that does not exist.
     *
     * **A fuzzy form is kept even when it spells a real syllable, and this is the whole point.**
     * The valuable pairs are exactly the ones where both sides exist -- `zong`/`zhong`,
     * `nan`/`lan`, `fu`/`hu`. Dropping those on the grounds that the typed form "is already a
     * word" would discard 394 of them and leave fuzzy matching working only for strings nobody
     * types. What must not happen is the fuzzy reading *outranking* the literal one, and that is
     * handled by order rather than by exclusion: the exact syllable is always first in the list,
     * and [isFuzzyAt] lets the decoder charge the rest a penalty. So `lan` still means 蓝 first
     * and 南 only if the sentence wants it.
     */
    private val SPELLINGS: Map<String, Match> = buildSpellings()

    /**
     * What a spelling can mean: the syllable ids, exact ones first.
     *
     * [exactCount] is a count rather than a flag because a spelling can be the exact form of one
     * syllable and the fuzzy form of several others. Both tables are produced together and held
     * in one object -- built as two separate properties they had an initialisation-order
     * dependency between them, which is exactly the kind of bug that only shows up at runtime.
     */
    class Match(val ids: IntArray, val exactCount: Int)

    private fun buildSpellings(): Map<String, Match> {
        val exact = HashMap<String, MutableList<Int>>(SyllableTable.ALL.size * 3)
        val fuzzy = HashMap<String, MutableList<Int>>(SyllableTable.ALL.size * 3)
        for ((i, syllable) in SyllableTable.ALL.withIndex()) {
            exact.getOrPut(syllable) { ArrayList(2) }.add(i)
        }
        for ((i, syllable) in SyllableTable.ALL.withIndex()) {
            for (variant in fuzzyVariants(syllable)) {
                if (variant == syllable) continue
                if (exact[variant]?.contains(i) == true) continue
                val list = fuzzy.getOrPut(variant) { ArrayList(2) }
                if (i !in list) list.add(i)
            }
        }
        val out = HashMap<String, Match>(exact.size + fuzzy.size)
        for (spelling in exact.keys + fuzzy.keys) {
            val head = exact[spelling].orEmpty()
            val tail = fuzzy[spelling].orEmpty().filter { it !in head }
            out[spelling] = Match((head + tail).toIntArray(), head.size)
        }
        return out
    }

    /**
     * Whether the id at [position] of [spelling]'s match list is a fuzzy reading.
     *
     * The decoder uses this to charge fuzzy edges a fixed penalty, which is what keeps an exact
     * reading ahead of a fuzzy one of equal frequency while still letting a much commoner fuzzy
     * word win. Without the penalty, `lan` would offer 南 beside 蓝 on equal terms.
     */
    fun isFuzzyAt(spelling: String, position: Int): Boolean =
        position >= (SPELLINGS[spelling]?.exactCount ?: 0)

    /** The spellings a typist might use for [syllable], by rewriting its initial and final. */
    private fun fuzzyVariants(syllable: String): List<String> {
        val forms = ArrayList<String>(8)
        forms.add(syllable)
        for ((a, b) in FUZZY_INITIALS) {
            if (syllable.startsWith(a)) forms.add(b + syllable.substring(a.length))
            if (syllable.startsWith(b)) forms.add(a + syllable.substring(b.length))
        }
        // Finals apply to every initial-variant produced above, so `zhang` also reaches `zan`.
        val withFinals = ArrayList<String>(forms.size * 2)
        for (form in forms) {
            withFinals.add(form)
            for ((a, b) in FUZZY_FINALS) {
                if (form.endsWith(a)) withFinals.add(form.dropLast(a.length) + b)
                if (form.endsWith(b)) withFinals.add(form.dropLast(b.length) + a)
            }
        }
        return withFinals
    }

    /** True if [text] is a letter run this segmenter can work on. */
    fun isPinyinText(text: String): Boolean =
        text.isNotEmpty() && text.all { it in 'a'..'z' }

    /**
     * Every reading of [input], best-first, capped at [limit].
     *
     * The cap exists because an all-consonant string like `zhzhzh` is exponentially ambiguous and
     * the tail of that list is worthless. Readings are generated depth-first preferring longer
     * syllables, so the cap truncates the least plausible ones.
     */
    fun readings(input: String, limit: Int = 24): List<Reading> {
        if (!isPinyinText(input)) return emptyList()
        val out = ArrayList<Reading>(limit)
        val ids = IntArray(input.length)
        val ends = IntArray(input.length)
        walk(input, 0, ids, ends, 0, 0, out, limit)
        // Exact readings first. Depth-first longest-match already produces a sensible order, but
        // it interleaves fuzzy branches with exact ones; a typist who spelled the syllable
        // correctly should never see a confusion of it ranked above their own input.
        out.sortBy { it.fuzzyCount }
        return out
    }

    /**
     * Depth-first over split points, longest syllable first.
     *
     * Longest-first is what makes the *first* reading the one a person usually meant: pinyin is
     * written without spaces precisely because the longest parse is normally right, and `xi'an`
     * is the exception that earns an apostrophe in careful writing. Producing the greedy reading
     * first also means a truncated list still contains the likely answer.
     */
    private fun walk(
        input: String,
        pos: Int,
        ids: IntArray,
        ends: IntArray,
        depth: Int,
        fuzzy: Int,
        out: MutableList<Reading>,
        limit: Int,
    ) {
        if (out.size >= limit) return
        if (pos == input.length) {
            out.add(Reading(ids.copyOf(depth), ends.copyOf(depth), fuzzy))
            return
        }
        val maxLen = minOf(SyllableTable.MAX_LENGTH, input.length - pos)
        for (len in maxLen downTo 1) {
            val piece = input.substring(pos, pos + len)
            val matches = SPELLINGS[piece]
            if (matches != null) {
                // All syllables this spelling can mean share a split point, so they are
                // alternatives at the same lattice position rather than separate readings --
                // but a Reading carries one id per position, so each gets its own branch.
                for ((position, id) in matches.ids.withIndex()) {
                    ids[depth] = id
                    ends[depth] = pos + len
                    val cost = if (position >= matches.exactCount) 1 else 0
                    walk(input, pos + len, ids, ends, depth + 1, fuzzy + cost, out, limit)
                    if (out.size >= limit) return
                }
            } else if (len == 1 && piece[0].isInitialLetter()) {
                // A bare consonant: an abbreviation standing for any syllable with that initial.
                ids[depth] = INITIAL_BASE + (piece[0] - 'a')
                ends[depth] = pos + len
                walk(input, pos + len, ids, ends, depth + 1, fuzzy, out, limit)
                if (out.size >= limit) return
            }
        }
    }

    /**
     * Whether a bare letter can stand for a syllable's initial.
     *
     * Vowels are excluded: `a`, `e` and `o` are syllables in their own right and already matched
     * above, and treating them as abbreviations too would make almost every string ambiguous
     * without ever being what someone meant.
     */
    private fun Char.isInitialLetter(): Boolean = this !in "aeiouv"

    /** True if [id] is an abbreviation rather than a resolved syllable. */
    fun isInitial(id: Int): Boolean = id >= INITIAL_BASE

    /** The letter an abbreviation id stands for. */
    fun initialLetter(id: Int): Char = ('a' + (id - INITIAL_BASE))

    /** The spelling of a resolved syllable id. */
    fun spelling(id: Int): String =
        if (isInitial(id)) initialLetter(id).toString() else SyllableTable.ALL[id]

    /** The id of an exactly-spelled syllable, or -1. */
    fun idOf(syllable: String): Int = IDS[syllable] ?: -1
}
