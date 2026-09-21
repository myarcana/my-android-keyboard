package com.offlinekeyboard.ime.asr

/**
 * The spoken-punctuation command vocabulary, shared by the keyword spotter and the text pass.
 *
 * Spoken punctuation is a *command language multiplexed into a dictation stream*, and nothing in
 * the audio separates the two channels. A general recogniser therefore has to rank "comma"
 * against "come" and "calm" using a text prior in which the punctuation sense of the word is
 * rare -- and because a command is spoken unstressed in a prosodic gap rather than as part of a
 * sentence, the acoustics are reduced exactly where that prior is weakest. That is why "comma"
 * comes back as "coma" or "comm": not a weak model (SenseVoice scores 4.0% CER on English in
 * `docs/ASR_BENCHMARK.md`, better than Apple's 5.1%) but the wrong question being asked of it.
 *
 * The keyword spotter asks the right one. It does not rank the word against a vocabulary; it
 * asks whether a phoneme sequence fired above [KeywordSpotting.THRESHOLD], which degrades
 * gracefully as the word is reduced. So detection is the spotter's job, and this object is the
 * table both sides agree on:
 *
 *  - `tools/build_punctuation_keywords.py` writes the ids into the keywords asset.
 *  - [Dictation] reports the ids the spotter fired.
 *  - [SpokenPunctuation] turns an id into the mark, in the right script.
 *
 * [ids] is checked against the generated asset by `PunctuationCommandsTest`, so an id added on
 * one side and not the other fails the build rather than silently never firing.
 *
 * No Android imports: the whole table is testable on the JVM.
 */
object PunctuationCommands {

    private const val NEWLINE = "\n"

    /**
     * Keyword id to the mark it inserts, in Latin script.
     *
     * Chinese ids map to the full-width mark directly. [SpokenPunctuation.markFor] handles the
     * Traditional/Simplified quote difference, so this table stays script-agnostic beyond the
     * half-width/full-width split the spoken language already implies.
     */
    private val MARKS: Map<String, String> = mapOf(
        "COMMA" to ",",
        "PERIOD" to ".",
        "FULL_STOP" to ".",
        "QUESTION_MARK" to "?",
        "EXCLAMATION_MARK" to "!",
        "EXCLAMATION_POINT" to "!",
        "SEMICOLON" to ";",
        "COLON" to ":",
        "NEW_LINE" to NEWLINE,
        "NEW_PARAGRAPH" to "\n\n",
        "OPEN_QUOTE" to "\u201C",
        "CLOSE_QUOTE" to "\u201D",
        "DASH" to "\u2014",
        "HYPHEN" to "-",
        "COMMA_ZH" to "，",
        "PERIOD_ZH" to "。",
        "QUESTION_MARK_ZH" to "？",
        "EXCLAMATION_MARK_ZH" to "！",
        "SEMICOLON_ZH" to "；",
        "COLON_ZH" to "：",
        "ENUMERATION_ZH" to "、",
        "NEW_LINE_ZH" to NEWLINE,
    )

    /**
     * The words that *spell* each command, used to remove it from the transcript.
     *
     * This is the half of the problem the spotter does not solve. When the speaker says "comma",
     * SenseVoice still transcribes something -- "comma", "coma", "comm" -- into the text, so a
     * detection has to be paired with deleting whatever the recogniser wrote at that position.
     * Listing the spellings is not a fallback detector: [SpokenPunctuation] only consults them
     * at a position the spotter already fired on, so a sentence genuinely containing the word
     * "comma" is untouched unless it was also spoken as a command.
     *
     * The near-misses are here deliberately. They are what the recogniser actually produces for
     * a reduced command word, and at a confirmed detection site they are the likeliest spelling
     * rather than a guess.
     */
    private val SPELLINGS: Map<String, List<String>> = mapOf(
        "COMMA" to listOf("comma", "coma", "commas", "comm", "karma", "calmer"),
        "PERIOD" to listOf("period", "pyrrhic", "peered"),
        "FULL_STOP" to listOf("full stop", "full stopped", "fullstop"),
        "QUESTION_MARK" to listOf("question mark", "question marks"),
        "EXCLAMATION_MARK" to listOf("exclamation mark", "exclamation marks"),
        "EXCLAMATION_POINT" to listOf("exclamation point"),
        "SEMICOLON" to listOf("semicolon", "semi colon", "semi-colon"),
        "COLON" to listOf("colon", "cologne", "colons"),
        "NEW_LINE" to listOf("new line", "newline", "nueline"),
        "NEW_PARAGRAPH" to listOf("new paragraph"),
        "OPEN_QUOTE" to listOf("open quote", "open quotes"),
        "CLOSE_QUOTE" to listOf("close quote", "close quotes"),
        "DASH" to listOf("dash"),
        "HYPHEN" to listOf("hyphen", "hyphens"),
        "COMMA_ZH" to listOf("逗号", "逗號"),
        "PERIOD_ZH" to listOf("句号", "句號"),
        "QUESTION_MARK_ZH" to listOf("问号", "問號"),
        "EXCLAMATION_MARK_ZH" to listOf("感叹号", "感嘆號", "惊叹号", "驚嘆號"),
        "SEMICOLON_ZH" to listOf("分号", "分號"),
        "COLON_ZH" to listOf("冒号", "冒號"),
        "ENUMERATION_ZH" to listOf("顿号", "頓號"),
        "NEW_LINE_ZH" to listOf("换行", "換行"),
    )

    /** Every id the table knows, for cross-checking against the generated keywords asset. */
    val ids: Set<String> get() = MARKS.keys

    /** The mark an id inserts, or null if the id is not one of ours. */
    fun markFor(id: String): String? = MARKS[id]

    /** Whether the id writes a full-width mark, which takes no space on either side. */
    fun isChinese(id: String): Boolean = id.endsWith("_ZH")

    /**
     * Spellings to look for when removing a fired command from the transcript, longest first so
     * that "full stop" is tried before a bare "stop" could match half of it.
     */
    fun spellingsFor(id: String): List<String> =
        SPELLINGS[id]?.sortedByDescending { it.length } ?: emptyList()
}
