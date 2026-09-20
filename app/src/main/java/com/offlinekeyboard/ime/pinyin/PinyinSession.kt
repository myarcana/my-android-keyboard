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
     */
    var traditional: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            // The decoder scores against the Taiwan model rather than the mainland one, so this
            // changes which *words exist*, not merely how they are drawn.
            decoder?.traditional = value
            cached = null
            scoredCache = null
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
                    decoder = Decoder(loaded, users, traditional)
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
    fun scoredFor(letters: String, limit: Int = 12): List<UnifiedCandidates.Scored> {
        val engine = decoder ?: return emptyList()
        if (letters.isEmpty()) return emptyList()
        // Memoised on the exact query, for the reason [decoded] is: a full Viterbi pass over
        // every segmentation runs here, and the English bar asks for the same letters repeatedly.
        // `refreshCandidates` fires from around twenty places -- every keystroke, every caret
        // move, every selection change, the dictionary landing -- and most of those do not change
        // the word behind the caret at all, so without this the keyboard re-decoded identical
        // input several times per keystroke.
        scoredCache?.let { if (it.letters == letters && it.limit == limit) return it.items }
        val converter = script
        val reading = Syllables.readings(letters).firstOrNull()
        val seen = HashSet<String>(limit * 2)
        val out = ArrayList<UnifiedCandidates.Scored>(limit)
        // Deliberately over-fetched. The decoder ranks by its own score, in which dozens of
        // single characters -- each explaining one letter of seven and none of them the answer --
        // outrank a word that explains all seven: 蚵仔煎 sits below forty of them for `ezijian`.
        // The ranker's coverage penalty is what sorts that out, so everything it needs to see has
        // to survive to it. Cutting to `limit` here would discard the right answer before the
        // thing that recognises it ever ran.
        for (candidate in engine.candidates(letters, limit * DECODER_OVERFETCH)) {
            val display = if (traditional && converter != null) {
                converter.toTraditional(candidate.text)
            } else {
                candidate.text
            }
            if (!seen.add(display)) continue
            // Letters consumed, by the same reckoning commit() uses: the syllable boundary the
            // candidate reached. This is what the ranker charges unexplained input against.
            val consumed = when {
                reading == null -> letters.length
                candidate.syllables >= reading.size -> letters.length
                else -> reading.ends.getOrElse(candidate.syllables - 1) { letters.length }
            }
            out.add(UnifiedCandidates.Scored(display, candidate.score, consumed))
        }
        scoredCache = ScoredCache(letters, limit, out)
        return out
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
        cached = Cached(input, limit, items)
        return items
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

        // The reading the candidate actually came from, not merely the best one.
        //
        // Candidates are drawn from several segmentations, so the first reading need not be the
        // one that produced this text -- for `ta`, 他 comes from [ta] while 天啊 comes from
        // [t][a]. Learning against the wrong reading files a word under a key that will never
        // produce it, and consuming letters by the wrong reading eats the wrong number of them.
        // A candidate whose length matches its syllable count identifies its reading well enough
        // here, since every dictionary entry has one character per syllable.
        val readings = Syllables.readings(input)
        val reading = readings.firstOrNull { it.size >= chosen.syllables }
            ?: readings.firstOrNull()

        val consumedLetters = when {
            reading == null -> input.length
            chosen.syllables >= reading.size -> input.length
            else -> reading.ends.getOrElse(chosen.syllables - 1) { input.length }
        }

        // Learn against the syllables actually consumed, so 北京 is learned for `bei jing` and
        // not for the whole `beijingdaxue` key that will never be typed again.
        if (reading != null && chosen.syllables <= reading.size &&
            chosen.text.length == chosen.syllables
        ) {
            userDict?.learn(reading.ids.copyOfRange(0, chosen.syllables), chosen.text)
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

        const val CANDIDATE_LIMIT = 20

        /**
         * How many more candidates [scoredFor] asks the decoder for than it will return.
         *
         * The unified ranker re-sorts on a quantity the decoder does not know about (coverage),
         * so the answer can be well down the decoder's own list. Five times is enough to clear
         * the character floor for the longest input a suggestion bar sees, and the cost is a
         * longer list to sort, not a second decode.
         */
        const val DECODER_OVERFETCH = 5
    }
}
