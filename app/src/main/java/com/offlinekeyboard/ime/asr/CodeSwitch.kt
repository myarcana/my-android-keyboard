package com.offlinekeyboard.ime.asr

import com.offlinekeyboard.ime.pinyin.SyllableTable
import java.io.InputStream

/**
 * Repairs the one failure mode SenseVoice has on code-switched speech.
 *
 * SenseVoice is not per-word multilingual. It makes **one** language decision per segment --
 * `auto` prepends a single detected language token, and everything after it is decoded
 * conditioned on that one choice. `language = ""` and `language = "auto"` are the same setting;
 * the native library says so itself:
 *
 *     Invalid sense-voice-language: '%s'. Valid values are: auto, zh, en, ja, ko, yue.
 *     Or you can leave it empty to use 'auto'
 *
 * So a sentence that is mostly English with Mandarin in it detects as `en`, and the Mandarin is
 * then forced through an English-conditioned decoder. It does not come back as Chinese and it
 * does not come back as nothing -- it comes back **romanised**:
 *
 *     "what's your favorite taiwanese food 牛肉麵嗎" -> "... food ne roium ma"
 *     "the best 豆花 in the world"                   -> "the best dohua in the world"
 *
 * The repair is to decode the audio a second time forced to `zh` and keep the better answer.
 * Deciding when to retry, which audio to retry, and which answer to keep is what this file does,
 * and it is pure text and numbers so it is testable on the JVM without a model.
 *
 * ## Only the garbled span is retried, not the segment
 *
 * The first version re-decoded the whole segment forced to `zh`. The language token is only the
 * first thing the decoder sees, and a segment that is mostly English keeps pulling it back to
 * English, so the romanised word usually came back romanised again:
 *
 *     "I think 螺蛳粉 is the best food in the world"
 *       auto      -> "i think rociphon is the best food in the world"
 *       forced zh -> "i think rocien is the best food in the world"
 *
 * That repaired 1 of 6 voices on this sentence. Cutting out just the audio under the unknown
 * words ([suspectRuns], with the token timings the decode already returns) and decoding *that*
 * forced to `zh` leaves the decoder no English to lean on, and 4 of 6 came back as 螺蛳粉. The
 * English around it is never re-decoded, so it cannot be rewritten.
 *
 * ## Why an English dictionary, and not a pinyin-shape detector
 *
 * The first version of this recognised romanisation by its *shape* -- pinyin-like syllables,
 * letter clusters English does not make -- and demanded two such words before retrying. Replayed
 * over 148 real SenseVoice decodes of synthesised mixed speech (`asrlab/` beside the repo) it
 * fired on 6 of 72 code-switched segments. The misses were not edge cases:
 *
 *  - **One Chinese word is the common case.** "the best 豆花 in the world" has a single
 *    romanised word, and two-word evidence can never fire on it.
 *  - **The decoder does not write pinyin.** It writes English-looking spellings of the sounds:
 *    "dohua", "hogu", "littleo", "xmaning". They match no syllable table.
 *
 * What every one of them has in common is simpler: **it is not an English word.** The shipped
 * glide lexicon (40,000 words) is the test. A segment detected as English that contains a Latin
 * word outside it is worth a second look; that fired on 55 of 72 mixed segments.
 *
 * The price is that English proper nouns ("kubernetes", "reykjavik", "quinoa") also trigger a
 * retry. That costs one extra decode of that segment and nothing else, because [choose] is what
 * guards the text, and it refused every one of them (0 of 56 English segments changed).
 */
object CodeSwitch {

    /** The language codes SenseVoice is configured with. */
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    /**
     * The language code from [com.k2fsa.sherpa.onnx.OfflineRecognizerResult.lang].
     *
     * sherpa-onnx reports the model's raw token, `<|zh|>`, not the `zh` it was configured with.
     * The previous comparison against the bare code could never match, so a segment that
     * `auto` had *already* decoded as Chinese was still retried as Chinese -- the same decode twice.
     */
    fun normaliseLang(lang: String): String = lang.trim().removePrefix("<|").removeSuffix("|>")

    /**
     * Reads the English word set out of `lexicon_en.tsv`: the first column of each line,
     * lowercased. The same asset the glide decoder uses, so there is one idea of "English" in the
     * keyboard rather than two that drift.
     */
    fun readEnglishWords(input: InputStream): Set<String> {
        val out = HashSet<String>(48_000)
        input.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.isEmpty() || line[0] == '#') return@forEach
                val tab = line.indexOf('\t')
                if (tab > 0) out += line.substring(0, tab).lowercase()
            }
        }
        return out
    }

    /**
     * Pinyin syllables that are also English lexicon entries: "ne", "ma", "long", "fan", "ming".
     *
     * These are the only English words a forced-`zh` retry is allowed to remove. When a decoder
     * spells 牛肉麵嗎 as "ne ro mian ma", the "ne" and "ma" are real lexicon entries -- the lexicon
     * is built from web text and carries them -- but here they are romanisation, and the retry
     * replacing them with Han is the repair, not a loss.
     */
    private val SYLLABLES: Set<String> = SyllableTable.ALL.toHashSet()

    /** True when a segment has Latin words that are not English, and is worth a forced-`zh` decode. */
    fun suspectsMissedChinese(text: String, lang: String, isEnglish: (String) -> Boolean): Boolean {
        if (text.isBlank()) return false
        // Already decoded as Chinese: a forced-zh retry would reproduce this exact decode.
        if (normaliseLang(lang) == LANG_ZH) return false
        return latinWords(text).any { !isEnglish(it) }
    }

    fun isHan(c: Char): Boolean =
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    /**
     * A run of consecutive non-English Latin words in a transcript: tokens
     * [firstToken]..[lastToken] inclusive, spoken from [startSeconds] until [endSeconds] (the
     * start of the next token, or the end of the segment).
     */
    data class Run(
        val firstToken: Int,
        val lastToken: Int,
        val startSeconds: Float,
        val endSeconds: Float,
        val text: String,
    )

    /**
     * The stretches of [transcript] that look like romanised Chinese: maximal runs of Latin words
     * outside the English lexicon. "lu sien" is one run, not two, because splitting one Chinese
     * word's audio in half would give each half too little to decode.
     *
     * Words are rebuilt from tokens the way [CommandMerge.words] does it -- a token starting with
     * a space starts a word -- but keeping token indices, which is what [splice] replaces.
     */
    fun suspectRuns(
        transcript: CommandMerge.Transcript,
        segmentSeconds: Float,
        isEnglish: (String) -> Boolean,
    ): List<Run> {
        val tokens = transcript.tokens
        val times = transcript.timestamps
        if (tokens.isEmpty() || times.size != tokens.size) return emptyList()

        // [first, last] token index of each word, and its text.
        class Word(val first: Int, var last: Int, var text: String)
        val words = mutableListOf<Word>()
        tokens.forEachIndexed { i, raw ->
            val body = raw.removePrefix(" ").removePrefix("\u2581")
            if (body.isEmpty()) return@forEachIndexed
            val prev = words.lastOrNull()
            val continues = prev != null && raw == body && isLatinWord(body) && isLatinWord(prev.text)
            if (continues) {
                prev!!.last = i
                prev.text += body
            } else {
                words += Word(i, i, body)
            }
        }

        fun suspect(w: Word) = isLatinWord(w.text) && !isEnglish(w.text.trim('\''))
        val runs = mutableListOf<Run>()
        var i = 0
        while (i < words.size) {
            if (!suspect(words[i])) { i++; continue }
            var j = i
            while (j + 1 < words.size && suspect(words[j + 1])) j++
            val first = words[i].first
            val last = words[j].last
            runs += Run(
                firstToken = first,
                lastToken = last,
                startSeconds = times[first],
                endSeconds = times.getOrElse(last + 1) { segmentSeconds },
                text = words.subList(i, j + 1).joinToString(" ") { it.text },
            )
            i = j + 1
        }
        return runs
    }

    /**
     * [transcript] with each run's tokens replaced by its repair, text rebuilt from the tokens.
     *
     * The replacement becomes a single token timed at the run's start, so the punctuation merge
     * still finds every other word where it was. A Latin replacement starts with a space so it
     * stays a separate word; Han does not need one, and [SpokenPunctuation] puts the space
     * between Latin and Han back on the side that touches.
     */
    fun splice(
        transcript: CommandMerge.Transcript,
        repairs: List<Pair<Run, String>>,
    ): CommandMerge.Transcript {
        if (repairs.isEmpty()) return transcript
        val tokens = transcript.tokens.toMutableList()
        val times = transcript.timestamps.toMutableList()
        for ((run, text) in repairs.sortedByDescending { it.first.firstToken }) {
            val token = if (text.firstOrNull()?.let(::isHan) == true) text else " $text"
            val range = run.firstToken..run.lastToken
            repeat(range.count()) {
                tokens.removeAt(run.firstToken)
                times.removeAt(run.firstToken)
            }
            tokens.add(run.firstToken, token)
            times.add(run.firstToken, run.startSeconds)
        }
        return CommandMerge.Transcript(tokens.joinToString("").trim(), tokens, times)
    }

    private fun isLatinWord(s: String) = s.isNotEmpty() && LATIN_WORD.matches(s)

    private val LATIN_WORD = Regex("[A-Za-z']+")

    /**
     * Picks between the `auto` decode and a decode forced to Chinese.
     *
     * The forced pass is conditioned on Chinese, and on English audio it does its own damage --
     * "crowded" becomes "quiet", "4 thirty" becomes "at4 thirty0", "want" becomes 忘. So the
     * forced answer is only taken when the change it makes is purely the repair:
     *
     *  1. **It adds Han.** Otherwise it did not recover any Chinese.
     *  2. **It leaves fewer non-words.** The romanised word has to actually go.
     *  3. **Every English word survives, and none is invented.** The only English words it may
     *     remove are pinyin syllables ("ne", "ma"), which in this position were romanisation.
     *
     * Rule 3 is the one that keeps English safe. It is deliberately strict: when the retry
     * repairs the Chinese but also rewrites one English word, the `auto` answer ships. A wrong
     * English word the user did not say is worse than a romanised word they can see is wrong.
     */
    fun choose(auto: String, forcedZh: String, isEnglish: (String) -> Boolean): String {
        if (forcedZh.count(::isHan) <= auto.count(::isHan)) return auto

        val autoWords = latinWords(auto)
        val forcedWords = latinWords(forcedZh)
        if (forcedWords.count { !isEnglish(it) } >= autoWords.count { !isEnglish(it) }) return auto

        val autoEnglish = autoWords.filter(isEnglish).map { it.lowercase() }.groupingBy { it }.eachCount()
        val forcedEnglish = forcedWords.filter(isEnglish).map { it.lowercase() }.groupingBy { it }.eachCount()
        for ((word, count) in autoEnglish) {
            val lost = count - (forcedEnglish[word] ?: 0)
            if (lost > 0 && word !in SYLLABLES) return auto
        }
        for ((word, count) in forcedEnglish) {
            if (count > (autoEnglish[word] ?: 0)) return auto
        }
        return forcedZh
    }

    /**
     * The Latin words of [text], apostrophes kept so "what's" is looked up as itself. Han, digits
     * and punctuation are separators: "best豆花" yields "best", and "430" yields nothing.
     */
    fun latinWords(text: String): List<String> =
        LATIN_SPLIT.split(text).map { it.trim('\'') }.filter { it.isNotEmpty() }

    private val LATIN_SPLIT = Regex("[^A-Za-z']+")
}
