package com.offlinekeyboard.ime.asr

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
 * So a sentence that is mostly English with Mandarin at the end detects as `en`, and the
 * Mandarin tail is then forced through an English-conditioned decoder. It does not come back
 * as Chinese and it does not come back as nothing -- it comes back as **romanised mush**:
 *
 *     "what's your favorite taiwanese food 牛肉麵嗎"
 *       -> "what's your favorite taiwanese food ne roium ma"
 *
 * That tail is the signature. They are Latin letters in English word shape but they are not
 * English words, and no English language model would ever produce them. `docs/ASR_BENCHMARK.md`
 * did not catch it because it scores character error rate with whitespace removed: a short
 * romanised tail on a long correct sentence is a handful of characters, which is exactly the
 * 7.9% "mixed" figure rather than a contradiction of it.
 *
 * The repair is to decode the same audio a second time forced to `zh` and keep the better
 * answer. Deciding which is better is what this file does, and it is pure text so it is
 * testable on the JVM without a model.
 */
object CodeSwitch {

    /** The language tokens SenseVoice reports back in [com.k2fsa.sherpa.onnx.OfflineRecognizerResult.lang]. */
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    /**
     * Vowel-free letter groups an English syllable cannot contain, and the pinyin-shaped endings
     * that show up when Mandarin is romanised by an English-conditioned decoder.
     *
     * These are deliberately narrow. The cost of a false positive is re-decoding audio that was
     * already right, and then picking between two answers -- not corrupting a good transcript.
     */
    private val PINYIN_SHAPED = Regex(
        "^(?:zh|ch|sh|[bpmfdtnlgkhjqxrzcsw])?" +
            "(?:iang|iong|uang|uai|uan|iao|ian|ang|eng|ong|ing|ia|ie|iu|in|" +
            "ua|uo|ui|un|ue|ai|ei|ao|ou|an|en|er|a|o|e|i|u)$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Letter shapes English does not produce, for the words that are not even clean pinyin --
     * an English-conditioned decoder spelling Mandarin invents letters rather than transliterating
     * it. Deliberately conservative: patterns like `[aeiou]{3}` were tried and removed because
     * they fire on the English suffixes -ious and -ium ("serious", "premium"), which measured as
     * a 2.1% false-positive rate against the shipped English lexicon. This set holds 0.63%, and
     * the two-signal gate in [suspectsMissedChinese] absorbs what is left.
     */
    private val NON_ENGLISH_SHAPE = Regex(
        "(?:^zh|^q(?![u])|^x(?![aeiou]?$)|ii|uu|^ng|iu[mn](?!$))",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Whether a Latin run looks like romanised Mandarin rather than English.
     *
     * A word counts when it is a plausible pinyin syllable *and* is not an ordinary English word.
     * "ma", "ne", "ma" are pinyin-shaped; so are "so" and "no", which is why the English stop
     * list matters more than the pattern does.
     */
    fun looksRomanised(word: String): Boolean {
        val w = word.lowercase().trim { !it.isLetter() }
        if (w.length < 2 || w.length > 7) return false
        if (!w.all { it.isLetter() && it.code < 128 }) return false
        if (w in COMMON_ENGLISH) return false
        // Either a clean pinyin syllable, or a letter shape English does not produce.
        return PINYIN_SHAPED.matches(w) || NON_ENGLISH_SHAPE.containsMatchIn(w)
    }

    /**
     * Words short enough and shaped enough to trip [PINYIN_SHAPED] while being perfectly ordinary
     * English. Without this list the detector fires on "he", "she", "to", "do", "no", "so".
     */
    private val COMMON_ENGLISH = setOf(
        "a", "an", "at", "as", "am", "and", "are", "be", "been", "but", "by", "can", "day",
        "do", "for", "go", "had", "has", "have", "he", "her", "here", "him", "his", "how",
        "i", "if", "in", "is", "it", "its", "just", "know", "like", "me", "my", "no", "not",
        "now", "of", "off", "on", "one", "or", "our", "out", "say", "see", "she", "so",
        "some", "than", "that", "the", "their", "them", "then", "there", "they", "this",
        "to", "too", "two", "up", "us", "was", "way", "we", "were", "what", "when", "where",
        "who", "why", "will", "with", "yes", "you", "your", "man", "men", "new", "old",
        "own", "put", "run", "she", "ten", "the", "use", "very", "want", "well", "went",
        "were", "hi", "ok", "okay", "time", "make", "come", "take", "good", "food", "long",
        "song", "king", "ring", "thing", "bring", "sing", "wing", "young", "along", "among",
        "wrong", "strong", "hang", "bang", "rang", "sang", "tea", "sea", "see", "sun", "son",
        "fun", "gun", "bun", "win", "wine", "fine", "nine", "line", "mine", "din", "pin",
        "tin", "bin", "sin", "shin", "chin", "thin", "than", "chan", "shan", "man", "can",
        "ban", "fan", "pan", "tan", "van", "ran", "plan", "hen", "pen", "ten", "men", "den",
        "when", "then", "open", "even", "seen", "been", "teen", "keen", "queen", "green",
        "her", "per", "were", "are", "ear", "near", "dear", "year", "hear", "fear", "bear",
        // Place names and loanwords that are ordinary English text, not a failed decode.
        "hong", "kong", "beijing", "taipei", "shanghai", "tofu", "kung", "feng", "chi",
        "tai", "wan", "yen", "yuan", "bao", "wok", "tofu", "china", "asia",
    )

    /**
     * How much of a transcript's Latin content looks romanised, as a fraction of its Latin words.
     *
     * Chinese characters are ignored: a segment the model got right is partly Han already, and
     * the question is only whether the *Latin* part is real English.
     */
    fun romanisedRatio(text: String): Double {
        val words = text.split(Regex("[^\\p{L}']+")).filter { w ->
            w.isNotEmpty() && w.all { it.code < 128 && it.isLetter() }
        }
        if (words.isEmpty()) return 0.0
        return words.count { looksRomanised(it) }.toDouble() / words.size
    }

    /** True when a segment shows the romanised-tail signature and is worth a second decode. */
    fun suspectsMissedChinese(text: String, lang: String): Boolean {
        if (text.isBlank()) return false
        // A result already containing Han was decoded as Chinese somewhere; nothing to repair.
        if (text.any(::isHan) && lang == LANG_ZH) return false
        val ratio = romanisedRatio(text)
        val romanisedWords = text.split(Regex("[^\\p{L}']+")).count { looksRomanised(it) }
        // Two independent signals: at least two suspicious words, and enough of the sentence to
        // not be a single odd proper noun.
        return romanisedWords >= 2 && ratio >= 0.15
    }

    fun isHan(c: Char): Boolean =
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    /**
     * Picks between the `auto` decode and a decode forced to Chinese.
     *
     * The forced-`zh` pass is only better when it actually produced Han characters *and* removed
     * the romanised words. A forced pass that returns its own Latin mush, or that throws away
     * English the speaker really said, loses -- which is why this compares both directions
     * rather than trusting the retry.
     */
    fun choose(auto: String, forcedZh: String): String {
        if (forcedZh.isBlank()) return auto
        if (!forcedZh.any(::isHan)) return auto

        val autoRomanised = romanisedRatio(auto)
        val forcedRomanised = romanisedRatio(forcedZh)

        // The forced pass has to actually reduce the mush to be worth taking.
        if (forcedRomanised >= autoRomanised) return auto

        // Guard against the forced pass eating English the speaker did say. Count the Latin words
        // each side kept that the other did not consider romanised.
        val autoEnglish = latinWords(auto).count { !looksRomanised(it) }
        val forcedEnglish = latinWords(forcedZh).count { !looksRomanised(it) }
        // Losing more than half the real English means `zh` swallowed the English half of a
        // code-switched sentence, which is the opposite failure and no better.
        if (autoEnglish > 0 && forcedEnglish * 2 < autoEnglish) return auto

        return forcedZh
    }

    private fun latinWords(text: String): List<String> =
        text.split(Regex("[^\\p{L}']+")).filter { w ->
            w.isNotEmpty() && w.all { it.code < 128 && it.isLetter() }
        }
}
