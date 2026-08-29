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
        return tidySpacing(spoken).trim()
    }

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
