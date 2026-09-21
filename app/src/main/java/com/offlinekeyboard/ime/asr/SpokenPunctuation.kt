package com.offlinekeyboard.ime.asr

/**
 * Turns dictated speech into text with the punctuation the speaker actually said, and none that
 * they did not.
 *
 * Two halves, and the order matters. SenseVoice punctuates on its own -- it will happily return
 * "今天下午三點開會，你記得把報告帶上。" -- so its marks come out first; then the spoken words are
 * converted. Doing it the other way round would strip the marks the speaker asked for.
 *
 * No Android imports: every rule here is testable on the JVM, and the awkward ones (an
 * apostrophe inside a word, a mark that lands next to a mark) are exactly the ones worth a test.
 */
object SpokenPunctuation {

    /**
     * Which script the marks take. Chinese punctuation is full-width and sits on its own
     * ideographic square, so "，" is right in Chinese and ", " is right in English -- and in a
     * code-switched sentence, which one is right changes partway through.
     */
    enum class Script { LATIN, SIMPLIFIED, TRADITIONAL }

    private const val NEWLINE = "\n"

    /** Spoken English. Longest first, so "exclamation mark" is not read as "mark". */
    private val ENGLISH = listOf(
        "new paragraph" to "\n\n",
        "new line" to NEWLINE,
        "question mark" to "?",
        "exclamation mark" to "!",
        "exclamation point" to "!",
        "full stop" to ".",
        "open quote" to "\u201C",
        "close quote" to "\u201D",
        "quote unquote" to "\u201C\u201D",
        "semicolon" to ";",
        "ellipsis" to "\u2026",
        "comma" to ",",
        "period" to ".",
        "colon" to ":",
        "dash" to "\u2014",
        "hyphen" to "-",
    )

    /**
     * Spoken Chinese, in both scripts, because what the speaker says is independent of the
     * script the model happened to answer in.
     */
    private val CHINESE = listOf(
        "新段落" to "\n\n",
        "換行" to NEWLINE, "换行" to NEWLINE,
        "問號" to "？", "问号" to "？",
        "驚嘆號" to "！", "惊叹号" to "！", "感嘆號" to "！", "感叹号" to "！",
        "刪節號" to "……", "删节号" to "……", "省略號" to "……", "省略号" to "……",
        "破折號" to "——", "破折号" to "——",
        // Before the bare 引號 below, which is a substring of both.
        "開引號" to "\u201C", "开引号" to "\u201C",
        "關引號" to "\u201D", "关引号" to "\u201D",
        "閉引號" to "\u201D", "闭引号" to "\u201D",
        "引號" to "\u201C\u201D", "引号" to "\u201C\u201D",
        "分號" to "；", "分号" to "；",
        "冒號" to "：", "冒号" to "：",
        "頓號" to "、", "顿号" to "、",
        "句號" to "。", "句号" to "。",
        "逗號" to "，", "逗号" to "，",
    )

    /** Marks the model inserts by itself. Stripped before anything spoken is honoured. */
    private const val MODEL_PUNCTUATION = ",.?!;:，。？！；：、\u2026\u201C\u201D\u2018\u2019「」『』（）()"

    /** Full-width marks never take a space beside them; half-width ones take one after. */
    private const val FULL_WIDTH = "，。？！；：、「」“”（）"
    private const val HALF_WIDTH = ",.?!;:"

    fun apply(raw: String, script: Script): String {
        val stripped = stripModelPunctuation(raw)
        val spoken = replaceSpokenWords(stripped, script)
        return capitaliseI(tidySpacing(spoken).trim())
    }

    /**
     * The mark a keyword-spotter command inserts, in the right script.
     *
     * [PunctuationCommands] holds the half-width/full-width choice, which the spoken language
     * already decides; the only thing left is the Traditional quote form, which depends on the
     * keyboard subtype rather than on what was said. Unknown ids yield an empty string, so a
     * command present in the asset but not in the table adds nothing rather than crashing.
     */
    fun markFor(id: String, script: Script): String {
        val mark = PunctuationCommands.markFor(id) ?: return ""
        return quoted(mark, script)
    }

    /**
     * Applies the transcript and the spotter's detections together.
     *
     * This is [apply] with the merge spliced into the middle of it, and the order is the whole
     * design. [stripModelPunctuation] runs *first*, while the text is still nothing but words,
     * and the detections are applied after. That way the strip only ever sees marks the model
     * invented, and every mark the merge places is one the speaker asked for. Merging first
     * would hand the strip a text in which the two are indistinguishable, and it would delete
     * the speaker's marks along with the model's.
     *
     * With no detections this is exactly [apply], so a segment holding no commands -- or a
     * device where the spotter failed to load -- takes the path that shipped before.
     */
    fun applyMerged(
        transcript: CommandMerge.Transcript,
        detections: List<CommandMerge.Detection>,
        script: Script,
    ): String {
        if (detections.isEmpty()) return apply(transcript.text, script)

        // Strip the model's invented punctuation *first*, while the text is still only words,
        // and merge after. Doing it in this order is what keeps the distinction the whole
        // feature rests on: once the merge has placed a mark, nothing downstream can tell it
        // from one the model invented, so the strip has to have already run.
        //
        // The words are stripped rather than the joined text so that token timings stay aligned
        // with the words the merge matches against.
        val cleanTokens = transcript.tokens.map(::stripModelPunctuation)
        val cleaned = CommandMerge.Transcript(
            text = stripModelPunctuation(transcript.text),
            tokens = cleanTokens,
            timestamps = transcript.timestamps,
        )
        val merged = CommandMerge.merge(cleaned, detections, script)
        // Spoken words are still honoured for any command the spotter missed.
        val spoken = replaceSpokenWords(merged, script)
        return capitaliseI(tidySpacing(spoken).trim())
    }

    /**
     * Capitalises the English pronoun "I" when it stands alone as a word.
     *
     * SenseVoice returns lower-case text, so the pronoun arrives as "i" and reads as a typo in
     * the one language where it is always a capital. This is deliberately the *only* casing rule
     * here: sentence-initial capitalisation would need to know where sentences begin, and after
     * [stripModelPunctuation] has removed the marks the speaker did not say, that is exactly the
     * information no longer present.
     *
     * The boundaries are the point. "i" is a word only when no letter, digit or apostrophe
     * touches it -- so "i'll" and "i'm" are caught by the contraction rule below rather than
     * here, while the "i" in "naive" and the variable "i" in "i2c" are left alone. Han text is
     * unaffected: a Latin "i" adjacent to Han characters is still a standalone Latin word.
     */
    private fun capitaliseI(text: String): String {
        var result = STANDALONE_I.replace(text, "I")
        result = CONTRACTED_I.replace(result) { m -> "I" + m.groupValues[1] }
        return result
    }

    /**
     * A bare "i": no letter, digit or apostrophe on either side.
     *
     * The apostrophe guards matter in both directions. Without the lookbehind, the "i" in
     * "Sarah'i" would be rewritten; without the lookahead, "i'll" would become "I'll" here *and*
     * again in [CONTRACTED_I], which is harmless only by luck. Keeping the two rules disjoint
     * means each one is testable on its own.
     */
    private val STANDALONE_I =
        Regex("(?<![\\p{L}\\p{N}'\\u2019])i(?![\\p{L}\\p{N}'\\u2019])")

    /**
     * "i" carrying a contraction: i'll, i'm, i've, i'd.
     *
     * Listed rather than matched as "apostrophe plus any letters", so that a transcription like
     * "i'the" -- which is not a contraction of the pronoun -- is left as the model heard it.
     * Both apostrophe forms are accepted because the model emits the typographic one and
     * [stripModelPunctuation] preserves whichever arrived.
     */
    private val CONTRACTED_I =
        Regex("(?<![\\p{L}\\p{N}'\\u2019])i(['\\u2019](?:ll|m|ve|d))(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE)

    /**
     * Removes the model's own punctuation, keeping the two marks that are part of words rather
     * than between them: the apostrophe in "I'll" and the hyphen in "t-shirt". Both are only
     * word-internal when letters sit on each side, which is the whole rule.
     */
    fun stripModelPunctuation(text: String): String = buildString {
        text.forEachIndexed { i, c ->
            val insideWord = i > 0 && i < text.length - 1 &&
                text[i - 1].isLetter() && text[i + 1].isLetter()
            when {
                (c == '\'' || c == '\u2019' || c == '-') && insideWord -> append(c)
                c in MODEL_PUNCTUATION -> Unit
                else -> append(c)
            }
        }
    }

    private fun replaceSpokenWords(text: String, script: Script): String {
        var result = text
        // Chinese first and by plain substring: there are no word boundaries to anchor to, and
        // these are compounds no ordinary sentence contains by accident.
        for ((spoken, mark) in CHINESE) {
            result = result.replace(spoken, quoted(mark, script))
        }
        for ((spoken, mark) in ENGLISH) {
            result = ENGLISH_WORD[spoken]!!.replace(result, quoted(mark, script))
        }
        return result
    }

    /**
     * Whole words only, case-insensitive. Without the boundaries "dash" would fire inside
     * "dashboard" and "period" inside "periodic".
     */
    private val ENGLISH_WORD: Map<String, Regex> =
        ENGLISH.associate { (spoken, _) -> spoken to Regex("(?<![\\p{L}])$spoken(?![\\p{L}])", RegexOption.IGNORE_CASE) }

    /** Traditional Chinese quotes are 「」; Simplified uses “”. */
    private fun quoted(mark: String, script: Script): String = when {
        script != Script.TRADITIONAL -> mark
        mark == "\u201C" -> "\u300C"
        mark == "\u201D" -> "\u300D"
        mark == "\u201C\u201D" -> "\u300C\u300D"
        else -> mark
    }

    /**
     * Puts the spaces back where a mark belongs and takes them out where it does not.
     *
     * The replacements above leave "hello , world" and "今天 ， 下午", because the spoken word
     * had spaces around it and the mark does not want them in the same places. Half-width marks
     * want nothing before and one space after; full-width marks want nothing on either side,
     * since the mark already occupies a full character cell.
     */
    private fun tidySpacing(text: String): String {
        var result = text
        result = Regex("[ \\t]+([$HALF_WIDTH$FULL_WIDTH])").replace(result, "$1")
        result = Regex("([$FULL_WIDTH])[ \\t]+").replace(result, "$1")
        result = Regex("([$HALF_WIDTH])(?=[\\p{L}\\p{N}])").replace(result, "$1 ")
        result = Regex("[ \\t]*\n[ \\t]*").replace(result, "\n")
        result = Regex("[ \\t]{2,}").replace(result, " ")
        return result
    }
}
