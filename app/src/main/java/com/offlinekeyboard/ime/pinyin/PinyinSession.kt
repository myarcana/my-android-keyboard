package com.offlinekeyboard.ime.pinyin

import android.content.Context
import com.offlinekeyboard.ime.candidates.UnifiedCandidates
import java.io.File

/**
 * The state of one in-progress Chinese word: the letters typed so far, the candidates they
 * produce, and what committing one does.
 *
 * This is the object [com.offlinekeyboard.ime.KeyboardService] talks to, and it exists so the
 * service does not have to know anything about pinyin. It holds the *raw letters*, which matters
 * for requirement 10 -- switching language must not destroy an unfinalised buffer, and the only
 * representation that survives such a switch is the literal keystrokes.
 *
 * Loading is asynchronous and the session is usable before it finishes: [candidates] is simply
 * empty until the dictionary arrives, which for the first moments after boot is the difference
 * between a keyboard that appears instantly and one that does not.
 */
internal class PinyinSession(private val context: Context) {

    /** Raw letters typed since the last commit -- the composing text shown to the editor. */
    private val buffer = StringBuilder()

    private var dict: PinyinDict? = null
    private var decoder: Decoder? = null
    private var script: Script? = null
    private var userDict: UserDict? = null
    private var loading = false

    /**
     * Set when the current subtype wants Traditional output.
     *
     * Switching this invalidates the cache: the same letters produce different characters, and
     * the globe key can change it with a buffer already open.
     *
     * Only meaningful for the Chinese subtypes, which are the callers that commit from
     * [candidates].
     * The English bar reads [scoredFor], which always ranks both scripts -- see [englishMode].
     */
    var traditional: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            cached = null
            scoredCache = null
        }

    /**
     * Whether the caller is the English suggestion bar rather than a Chinese subtype.
     *
     * This is the switch that decides **which question the decoder is being asked**. A Chinese
     * subtype has been told which script the user writes, so it asks for one model and a bar
     * mixing 麵條 with 面条 would be noise. The English bar has been told nothing: the letters
     * might be English, might be pinyin, and if pinyin might be either script -- so all of those
     * are competing hypotheses and the bar ranks them together.
     *
     * Kept separate from [traditional] rather than folded into a three-valued setting because the
     * two answer different questions: [traditional] is "which script does this user write", which
     * survives into commit and conversion, while this is "may the other script compete right now".
     */
    var englishMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            cached = null
            scoredCache = null
        }

    /**
     * The model the decoder should use for the caller currently asking.
     *
     * [ScriptMode.BOTH] only in English mode, per the scope of cross-script ranking: the Chinese
     * subtypes keep single-script bars.
     */
    private fun modeFor(english: Boolean): ScriptMode = when {
        english -> ScriptMode.BOTH
        traditional -> ScriptMode.TRADITIONAL
        else -> ScriptMode.SIMPLIFIED
    }

    /** The letters currently composing; empty when nothing is in progress. */
    val composing: String get() = buffer.toString()

    val isEmpty: Boolean get() = buffer.isEmpty()

    /** True once the dictionary is in memory and candidates can be produced. */
    val isReady: Boolean get() = decoder != null

    /**
     * Loads the dictionary off the main thread.
     *
     * Called when a Chinese subtype is first selected rather than at service start: an
     * English-only session should not pay 8 MB of I/O for a language it is not using.
     */
    fun ensureLoaded(onReady: () -> Unit) {
        if (dict != null || loading) return
        loading = true
        Thread {
            val loaded = runCatching {
                context.assets.open(ASSET).use(PinyinDict::load)
            }.onFailure {
                android.util.Log.w(TAG, "pinyin dictionary failed to load", it)
            }.getOrNull()
            val users = UserDict(runCatching { File(context.filesDir, USER_FILE) }.getOrNull())
            users.load()
            android.os.Handler(context.mainLooper).post {
                if (loaded != null) {
                    dict = loaded
                    userDict = users
                    decoder = Decoder(loaded, users, modeFor(englishMode))
                    script = Script(loaded.conversionChars, loaded.conversionPhrases)
                }
                loading = false
                onReady()
            }
        }.apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    /** Appends a typed letter. Returns false if it is not one this session accepts. */
    fun append(ch: Char): Boolean {
        if (ch !in 'a'..'z' && ch !in 'A'..'Z') return false
        if (buffer.length >= MAX_INPUT) return true
        buffer.append(ch.lowercaseChar())
        cached = null
        return true
    }

    /** Removes the last letter. Returns true if there was one to remove. */
    fun backspace(): Boolean {
        if (buffer.isEmpty()) return false
        buffer.setLength(buffer.length - 1)
        cached = null
        return true
    }

    fun clear() {
        buffer.setLength(0)
        cached = null
    }

    /**
     * The candidate list for what has been typed, already in the right script.
     *
     * Conversion happens here rather than at commit time so that what the user picks is exactly
     * what they saw -- in Traditional mode the bar shows Traditional, and tapping it inserts
     * those same characters.
     */
    fun candidates(limit: Int = 20): List<String> = decoded(limit).map { it.display }

    /**
     * Candidates for arbitrary letters, scored, for the unified English-mode bar.
     *
     * Separate from [candidates] because the caller is different in kind: in Chinese mode the
     * session *owns* a composing buffer, while in English mode the letters are already in the
     * text field and the bar is only offering to reinterpret them. So this takes the letters as
     * an argument, holds no state, and reports how many of them each candidate would consume.
     *
     * Returns an empty list rather than a floor of single characters when the letters do not
     * read as pinyin: this feeds a ranked bar that English words must be able to win outright,
     * and a guaranteed floor would put a Chinese character under every word typed.
     */
    fun scoredFor(letters: String, limit: Int = CANDIDATE_LIMIT): List<UnifiedCandidates.Scored> {
        val engine = decoder ?: return emptyList()
        if (letters.isEmpty()) return emptyList()
        // Memoised on the exact query, for the reason [decoded] is: a full Viterbi pass over
        // every segmentation runs here, and the English bar asks for the same letters repeatedly.
        // `refreshCandidates` fires from around twenty places -- every keystroke, every caret
        // move, every selection change, the dictionary landing -- and most of those do not change
        // the word behind the caret at all, so without this the keyboard re-decoded identical
        // input several times per keystroke.
        scoredCache?.let { if (it.letters == letters && it.limit == limit) return it.items }
        // The English bar ranks both scripts against each other; see [englishMode]. That is a
        // choice of *model*, not of ranking: the list below is the one [Decoder.candidates]
        // gives a Chinese subtype, in the same order, with the same limit.
        engine.mode = ScriptMode.BOTH
        val seen = HashSet<String>(limit * 2)
        val out = ArrayList<UnifiedCandidates.Scored>(limit)
        for (candidate in engine.candidates(letters, limit)) {
            // No conversion here, deliberately. In [ScriptMode.BOTH] every candidate already is
            // the script its corpus wrote it in -- 麵條 came from the Taiwan model and 面条 from
            // the mainland one -- so converting would rewrite the Simplified half into
            // Traditional and collapse the two hypotheses the bar exists to show.
            val display = candidate.text
            if (!seen.add(display)) continue
            out.add(
                UnifiedCandidates.Scored(
                    display,
                    candidate.score,
                    candidate.consumed.coerceIn(1, letters.length),
                    candidate.ids,
                ),
            )
        }
        scoredCache = ScoredCache(letters, limit, out)
        return out
    }

    /**
     * Records that [chosen] was picked from the English bar, exactly as [commit] records a pick
     * from the Chinese one -- so a correction made in either language changes both bars alike.
     */
    fun learn(chosen: UnifiedCandidates.Scored) {
        val users = userDict ?: return
        if (chosen.ids.isEmpty() || chosen.text.length != chosen.ids.size) return
        users.learn(chosen.ids, chosen.text)
        users.save()
        cached = null
        scoredCache = null
    }

    private class ScoredCache(
        val letters: String,
        val limit: Int,
        val items: List<UnifiedCandidates.Scored>,
    )

    /**
     * The last [scoredFor] answer.
     *
     * One entry is enough: the queries arrive one per keystroke and the previous one is never
     * asked for again. Invalidated wherever [cached] is -- the two are the same decoder's output
     * and `traditional` changes which words exist in it, not merely how they are drawn.
     */
    private var scoredCache: ScoredCache? = null

    /**
     * The decoding of the current buffer, cached for as long as the buffer is unchanged.
     *
     * Two reasons, and the second is a correctness one:
     *
     *  - **Cost.** A full Viterbi pass runs on every keystroke to refresh the bar. Committing
     *    then ran a second, identical one to find out what was tapped, doubling the work at the
     *    moment the user is waiting on the keyboard.
     *  - **Agreement.** The bar shows converted text in Traditional mode while the commit path
     *    indexed the *unconverted* list, so the two were only guaranteed to line up as long as
     *    conversion was one-to-one. It is not: two Simplified candidates can share a Traditional
     *    form, and the entry that collapses shifts every index after it. Both now read the same
     *    list, so the candidate that was tapped is the candidate that is committed.
     */
    private fun decoded(limit: Int): List<Decoded> {
        val engine = decoder ?: return emptyList()
        val input = buffer.toString()
        if (input.isEmpty()) return emptyList()
        cached?.let { if (it.input == input && it.limit >= limit) return it.items.take(limit) }

        // Set per call rather than once at construction: one decoder serves both the Chinese
        // subtypes and the English bar, and they want different models. [scoredFor] sets
        // [ScriptMode.BOTH] for the same reason.
        engine.mode = modeFor(englishMode)
        val converter = script
        val seen = HashSet<String>(limit * 2)
        val items = ArrayList<Decoded>(limit)
        for (candidate in engine.candidates(input, limit)) {
            // In Traditional mode the candidates already *are* Traditional: they come from the
            // Taiwan model, which stores 牛肉麵 as itself rather than as a converted 牛肉面.
            // Conversion is kept only as a backstop for text that model could not supply -- a
            // learned word, or a character floor entry that exists solely in the mainland
            // tables -- so it repairs a gap instead of rewriting a correct answer. Running it
            // over everything would be the "re-skinned Simplified" behaviour this replaced.
            val display = if (traditional && converter != null) {
                converter.toTraditional(candidate.text)
            } else {
                candidate.text
            }
            // Two Simplified candidates converging on one Traditional form are one choice on
            // screen, so they must be one entry here too.
            if (seen.add(display)) items.add(Decoded(candidate, display))
        }
        if (traditional && converter != null) {
            addScriptVariants(input, limit, converter, seen, items)
        }
        cached = Cached(input, limit, items)
        return items
    }

    /**
     * Adds Traditional spellings of a word only the mainland model happens to hold.
     *
     * A Taiwan subtype reads the Taiwan corpus and drops mainland-only words, which is right
     * and is what keeps 軟件 out of a bar that should say 軟體. But it is too strong for one
     * case: a word Taiwan genuinely uses whose *Traditional spelling* is only reachable through
     * the mainland entry. `danta` is it -- McBopomofo has 蛋塔 and nothing else, so 蛋撻 could
     * not appear at all, while both iOS (蛋塔 蛋撻 蛋鴨 淡雅 ...) and Gboard (但他 蛋塔 蛋撻 ...)
     * offer the two side by side. No OpenCC table links 蛋塔 to 蛋撻; the only route to it is
     * converting the mainland 蛋挞.
     *
     * The rule is deliberately narrow, because the general version is the "re-skinned
     * Simplified" behaviour this file exists to avoid -- converting 软件 yields 軟件, which is
     * not the word a Taiwanese user wants. Three things keep it contained:
     *
     *  - It runs **only when the Taiwan model already answered** ([items] is non-empty), so the
     *    conversion supplies an alternative spelling beside a native candidate rather than
     *    inventing a bar out of mainland vocabulary.
     *  - At most [SCRIPT_VARIANTS] are added, and only multi-character readings.
     *  - Only readings of the letters **as spelled**: a fuzzy respelling is a different word,
     *    not another spelling of this one, and importing those put 當他/當她 into a Taiwan bar.
     *  - They are **appended**, so every native Taiwan candidate outranks every converted one.
     *
     * 軟件 can therefore still appear for `ruanjian`, but only behind 軟體 -- which is the
     * honest ordering, since a Taiwan user who typed those letters wanted 軟體 and the mainland
     * spelling is a legitimate second reading rather than a wrong answer.
     */
    private fun addScriptVariants(
        input: String,
        limit: Int,
        converter: Script,
        seen: MutableSet<String>,
        items: MutableList<Decoded>,
    ) {
        val engine = decoder ?: return
        val best = items.firstOrNull() ?: return
        engine.mode = ScriptMode.SIMPLIFIED
        // Only readings of the letters as actually spelled. The mainland decoder applies fuzzy
        // pinyin too, and without this `danta` imports 當他 and 當她 -- readings of `dang ta`,
        // converted and appended to a Taiwan bar that had correctly never contained them. A
        // script variant is a different *spelling of the same word*; a respelling is a different
        // word, and it has already been ruled out once on the mainland side.
        val literal = Syllables.readings(input).firstOrNull { it.fuzzyCount == 0 }
        val literalTexts = if (literal == null) {
            emptySet()
        } else {
            engine.decode(literal, limit).mapTo(HashSet()) { it.text }
        }
        val extra = ArrayList<Decoded>(SCRIPT_VARIANTS)
        for (candidate in engine.candidates(input, limit)) {
            if (extra.size >= SCRIPT_VARIANTS) break
            if (candidate.text.length < 2) continue
            if (candidate.text !in literalTexts) continue
            // At least as good as what the Taiwan model itself found. This one test does all
            // the filtering, because the two cases separate cleanly on it:
            //
            //   danta     蛋挞 -13.3 against native 蛋塔 -16.4  -> ahead, a real second spelling
            //   ruanjian  软件 -9.3  against native 如案件 -23.1 -> far ahead, the word Taiwan
            //                                                     wants and its model lacked
            //   shida     是哒 -11.3 against native 師大 -11.2  -> behind, mainland noise
            //   nihao     拟好 -14.2 against native 你好 -12.3  -> behind, mainland noise
            //
            // A mainland reading that cannot even match the native bar is the decoder guessing
            // at input Taiwan has a perfectly good word for, and converting it adds nothing.
            if (candidate.score < best.candidate.score) continue
            val display = converter.toTraditional(candidate.text)
            // Unconverted means the word is already Traditional, so the Taiwan model would have
            // had it if Taiwan used it; nothing is learned by adding it.
            if (display == candidate.text) continue
            if (!seen.add(display)) continue
            extra.add(Decoded(candidate, display))
        }
        engine.mode = modeFor(englishMode)
        items.addAll(extra)
    }

    private class Decoded(val candidate: Decoder.Candidate, val display: String)

    private class Cached(val input: String, val limit: Int, val items: List<Decoded>)

    private var cached: Cached? = null

    /**
     * Commits the candidate at [position]: the text to insert, and what remains composing.
     *
     * A candidate may cover only part of the input -- picking 北京 out of `beijingdaxue` leaves
     * `daxue` still being typed, which is the ordinary way a phrase is entered. The leftover is
     * computed from the syllables the candidate consumed, mapped back to letters through the
     * reading that produced it.
     */
    fun commit(position: Int): Commit? {
        val input = buffer.toString()
        if (input.isEmpty()) return null
        // The same list the bar was drawn from, so position means what the finger meant.
        val entry = decoded(CANDIDATE_LIMIT).getOrNull(position) ?: return null
        val chosen = entry.candidate

        // The span the candidate actually stands for, recorded by the decoder from the reading
        // that produced it -- not reconstructed here from a guessed reading. Candidates come
        // from several segmentations (for `ta`, 他 is [ta] while 天啊 is [t][a]), and consuming
        // or learning by the wrong one eats the wrong letters or files the word under a key that
        // will never produce it. The English bar reads the same two fields, so a pick consumes
        // and learns identically in either language.
        val consumedLetters = chosen.consumed.takeIf { it > 0 } ?: input.length

        // Learn against the syllables actually consumed, so 北京 is learned for `bei jing` and
        // not for the whole `beijingdaxue` key that will never be typed again.
        if (chosen.ids.isNotEmpty() && chosen.text.length == chosen.ids.size) {
            userDict?.learn(chosen.ids, chosen.text)
            userDict?.save()
            // Learning changed what the decoder will say about these letters, so the memoised
            // answer for them is now wrong. Dropped here rather than only in `clear`, because the
            // English bar can be asked for the very letters just learned against -- and a
            // correction the keyboard records but then serves from a stale cache is a correction
            // the user sees ignored.
            scoredCache = null
        }

        buffer.delete(0, minOf(consumedLetters, buffer.length))
        cached = null
        // Exactly the text that was on the bar, already converted.
        return Commit(entry.display, buffer.toString())
    }

    /**
     * Commits the raw letters as typed, for when no candidate is wanted.
     *
     * This is what space does with unconvertible input, and what a Latin word typed in Chinese
     * mode becomes. Returning the letters rather than dropping them means nothing a user typed
     * is ever silently lost.
     */
    fun commitRaw(): String {
        val text = buffer.toString()
        buffer.setLength(0)
        return text
    }

    /** The result of committing: what to insert, and the letters still composing. */
    class Commit(val text: String, val remaining: String)

    private companion object {
        const val TAG = "PinyinSession"
        const val ASSET = "pinyin.bin"
        const val USER_FILE = "pinyin_user.tsv"

        /** Longest run of letters treated as one composition. */
        const val MAX_INPUT = 48

        /** Shared by both bars, so the English one can never show a different Chinese list. */
        const val CANDIDATE_LIMIT = 20

        /**
         * How many converted mainland spellings a Traditional bar may gain. See
         * [addScriptVariants].
         *
         * Two, because this is a spelling alternative rather than a source of vocabulary: it
         * exists so 蛋撻 can sit beside 蛋塔, not so the mainland dictionary can leak into a
         * Taiwan bar. Anything past the first couple is mainland vocabulary wearing Traditional
         * characters, which is precisely what the Taiwan model was built to avoid.
         */
        const val SCRIPT_VARIANTS = 2
    }
}
