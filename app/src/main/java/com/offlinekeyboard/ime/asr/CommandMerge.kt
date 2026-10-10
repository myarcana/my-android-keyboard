package com.offlinekeyboard.ime.asr

/**
 * Merges the transcript with the punctuation commands the keyword spotter heard.
 *
 * Two recognisers listen to the same audio. SenseVoice writes the words; the spotter reports
 * which punctuation commands fired and when. Neither alone is enough:
 *
 *  - The spotter knows a command was spoken but not where it belongs in the sentence.
 *  - The transcript has a position for every word but cannot tell a command from a word.
 *
 * What joins them is time. SenseVoice returns a timestamp per token, the spotter returns one per
 * detection, and a detection belongs wherever the clock says it was spoken.
 *
 * The part that is easy to miss: a detection is not only an *insertion*. When the speaker says
 * "comma", SenseVoice still writes something there -- "comma", "coma", "comm" -- so each
 * detection is a **replacement**: delete the word the recogniser wrote, put the mark in its
 * place. Getting only the first half right is what would leave "hello coma, world".
 *
 * No Android imports: every rule here is decided on strings and numbers, and the awkward cases
 * (a detection whose word the recogniser spelled differently, two commands in a row, a command
 * at the very start) are exactly the ones worth a test.
 */
object CommandMerge {

    /** One command the spotter heard, at the time it finished being spoken. */
    data class Detection(val id: String, val seconds: Float)

    /**
     * A transcript as SenseVoice returns it: the text, plus a timestamp for each token.
     *
     * Tokens are model units rather than words -- SenseVoice emits word pieces, and for Chinese
     * one per character -- so [words] reassembles them into whitespace-delimited words carrying
     * the timestamp of their first token. That is the granularity a detection is matched at.
     */
    data class Transcript(val text: String, val tokens: List<String>, val timestamps: List<Float>)

    /**
     * A word of the transcript with the time it started.
     *
     * [detached] marks a word built from a token that *continued* the word before it -- no
     * leading space -- but only after a pause no single word contains. See [WORD_GAP_SECONDS].
     */
    data class TimedWord(val text: String, val start: Float, val detached: Boolean = false)

    /**
     * The longest silence a word can contain, between two of its own tokens.
     *
     * SenseVoice marks a word boundary with a leading space, and usually that is the whole story.
     * It is not when the speaker pauses before a command: the decoder can drop the command's
     * onset and glue what is left of it -- the final "a" of "comma" -- onto the word *before the
     * pause*, with no space. The token timings still tell the truth:
     *
     *     tokens  "well" "a"  " i" ...      "hel" "lo" "a"  " how" ...
     *     times   0.12   0.90 1.20          0.06  0.24 0.84 1.14
     *
     * Read as one word, "wella" matches no spelling of "comma", the mark goes in beside it, and
     * the user sees "wella, I think so" -- a stray "a" before every such comma.
     *
     * Measured over every intra-word token gap in `asrlab/` (148 corpus decodes plus 300 spoken-
     * comma decodes), real words top out at 0.42 s, and every gap of 0.48 s or more was this
     * residue. 0.45 s sits between the two.
     */
    private const val WORD_GAP_SECONDS = 0.45f

    /**
     * How far from a detection to look for the word the recogniser wrote for it.
     *
     * The two models do not agree on timing to the millisecond: they use different frontends and
     * the spotter reports when a keyword *completed* while SenseVoice reports when a token
     * *began*. Half a second is wide enough to cover that skew and narrow enough that the next
     * word along is not a candidate at normal speech rates.
     */
    private const val MATCH_WINDOW_SECONDS = 0.5f

    /**
     * Rebuilds words from model tokens.
     *
     * SenseVoice marks a word boundary by prefixing the token that starts a word with a space
     * (the usual sentencepiece convention), so a token without one continues the word before it.
     * Han characters are their own words and never take a joining space.
     *
     * A continuation token that arrives after more than [WORD_GAP_SECONDS] of silence is not
     * part of the word before it, whatever its spacing says, and starts a [TimedWord.detached]
     * word of its own.
     */
    fun words(tokens: List<String>, timestamps: List<Float>): List<TimedWord> {
        val out = mutableListOf<TimedWord>()
        var previousTime = 0f
        tokens.forEachIndexed { i, rawToken ->
            val time = timestamps.getOrElse(i) { timestamps.lastOrNull() ?: 0f }
            val startsWord = rawToken.startsWith(" ") || rawToken.startsWith("\u2581")
            val token = rawToken.removePrefix("\u2581").removePrefix(" ")
            if (token.isEmpty()) return@forEachIndexed
            val han = token.any { isHan(it) }
            val gap = time - previousTime
            previousTime = time
            when {
                out.isEmpty() || startsWord || han || out.last().text.any { isHan(it) } ->
                    out += TimedWord(token, time)
                gap > WORD_GAP_SECONDS ->
                    out += TimedWord(token, time, detached = true)
                else ->
                    out[out.lastIndex] = out.last().copy(text = out.last().text + token)
            }
        }
        return out
    }

    private fun isHan(c: Char): Boolean =
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    /**
     * Applies [detections] to [transcript], returning text with the marks in place of the words
     * that spelled them.
     *
     * Each detection is resolved independently against the word list, then all of them are
     * applied at once, so two commands close together cannot shift each other's positions.
     *
     * A detection whose word cannot be identified is still honoured: the mark is inserted at the
     * detection's time rather than dropped, because the speaker did ask for it. That is the
     * conservative direction -- a stray mark is visible and easy to delete, whereas a missing one
     * means the command silently did nothing, which is the failure being fixed.
     */
    fun merge(transcript: Transcript, detections: List<Detection>, script: SpokenPunctuation.Script): String {
        if (detections.isEmpty()) return transcript.text
        val words = words(transcript.tokens, transcript.timestamps)
        if (words.isEmpty()) return transcript.text

        // The words each detection consumed, so none is eaten twice when two commands land near
        // each other. A command can span several words -- "full stop", or 逗 号 as two Han
        // tokens -- so the whole run maps to the id, and only its first index emits the mark.
        val consumed = mutableMapOf<Int, String>()
        val swallowed = mutableSetOf<Int>()
        val orphans = mutableListOf<Detection>()

        // Two passes: every detection gets first claim on an exact spelling before any of them
        // is allowed a fuzzy one. Otherwise an early detection resolved loosely could take the
        // very word a later one spells exactly -- "comment comma" with two COMMA fires.
        val unresolved = mutableListOf<Detection>()
        for (detection in detections.sortedBy { it.seconds }) {
            val range = findSpelling(words, detection, swallowed)
            if (range == null) {
                unresolved += detection
            } else {
                consumed[range.first] = detection.id
                swallowed += range
            }
        }
        for (detection in unresolved) {
            val range = findResidue(words, detection, swallowed)
                ?: findResemblance(words, detection, swallowed)
            if (range == null) {
                orphans += detection
            } else {
                consumed[range.first] = detection.id
                swallowed += range
            }
        }

        // Each piece remembers which word produced it. Consuming a multi-word command makes the
        // piece list shorter than the word list, so an orphan's position -- which is known in
        // *word* indices -- cannot be used against the pieces directly.
        val pieces = mutableListOf<Pair<Int, String>>()
        words.forEachIndexed { i, word ->
            when {
                consumed.containsKey(i) -> pieces += i to SpokenPunctuation.markFor(consumed[i]!!, script)
                i in swallowed -> Unit // a later word of a multi-word command
                // A detached word that turned out not to be a command's residue goes back where
                // the recogniser put it, joined to its word, so the split costs nothing when it
                // was wrong.
                word.detached && pieces.lastOrNull()?.first == i - 1 && (i - 1) !in consumed -> {
                    val (at, text) = pieces.removeAt(pieces.lastIndex)
                    pieces += at to text + word.text
                }
                else -> pieces += i to word.text
            }
        }

        // Orphans go in by time, after the last word that started before them. Placing them
        // last, and from the end backwards, means no insertion shifts a position not yet used.
        for (orphan in orphans.sortedByDescending { it.seconds }) {
            val afterWord = words.indexOfLast { it.start <= orphan.seconds }
            val at = pieces.indexOfLast { it.first <= afterWord } + 1
            pieces.add(at.coerceIn(0, pieces.size), afterWord to SpokenPunctuation.markFor(orphan.id, script))
        }

        return join(pieces.map { it.second })
    }

    /**
     * Joins the pieces with a space only where one belongs.
     *
     * Latin words need separating; Han characters do not, and a space between them is not a
     * cosmetic flaw but visibly broken Chinese. The rule is the same one [KeyboardService] uses
     * between segments: no space where either side of the join is Han, and none against a mark,
     * which `SpokenPunctuation.tidySpacing` then positions properly for its script.
     */
    private fun join(pieces: List<String>): String {
        val out = StringBuilder()
        for (piece in pieces) {
            if (piece.isEmpty()) continue
            val previous = out.lastOrNull()
            val needsSpace = previous != null &&
                !previous.isWhitespace() &&
                !isHan(previous) &&
                !isHan(piece.first()) &&
                previous != '\n' &&
                !piece.first().isWhitespace()
            if (needsSpace) out.append(' ')
            out.append(piece)
        }
        return out.toString()
    }

    /**
     * Finds the words a detection refers to: the closest run of words, in time, whose text is
     * one of the spellings the recogniser produces for that command.
     *
     * A **run** rather than a single word, because a command is not always one. "full stop" is
     * two English words, and a Chinese command is one per character -- SenseVoice emits 逗 and 号
     * as separate tokens, so matching a single word would never find 逗号 and the mark would be
     * inserted *inside* the word instead of replacing it. The span is capped at
     * [MAX_COMMAND_WORDS], which is the longest spelling any command has.
     *
     * Matching on the spelling list rather than on time alone is what keeps a real word from
     * being eaten. If the speaker says "put a comma there" as prose, the spotter may well fire,
     * but the word at that moment is still "comma" -- and there is no way to tell those apart
     * acoustically, which is why the mark replaces the word in both cases. What this does
     * prevent is a detection whose timing is slightly off consuming the *neighbouring* word.
     */
    private fun findSpelling(
        words: List<TimedWord>,
        detection: Detection,
        taken: Set<Int>,
    ): IntRange? {
        val spellings = PunctuationCommands.spellingsFor(detection.id)
        if (spellings.isEmpty()) return null
        var best: IntRange? = null
        var bestDistance = Float.MAX_VALUE
        for (start in words.indices) {
            if (start in taken) continue
            val distance = kotlin.math.abs(words[start].start - detection.seconds)
            if (distance > MATCH_WINDOW_SECONDS) continue
            for (length in 1..MAX_COMMAND_WORDS) {
                val end = start + length - 1
                if (end >= words.size) break
                if ((start..end).any { it in taken }) break
                if (!matches(words, start, end, spellings)) continue
                // A longer match at the same distance wins: "full stop" must beat a bare
                // "stop" that happens to be part of it.
                if (distance < bestDistance || (distance == bestDistance && best != null && length > best!!.count())) {
                    bestDistance = distance
                    best = start..end
                }
            }
        }
        return best
    }

    /** The longest command spelling, in words: "exclamation mark", 感 嘆 號. */
    private const val MAX_COMMAND_WORDS = 3

    /**
     * How closely a run of words must resemble a command to be taken as its misspelling, as
     * 1 minus edit distance over the longer length.
     *
     * The spelling list cannot be complete: every new reduction of a command word is a new
     * spelling -- "question mug", "comment" -- and each one left the misheard word in the text
     * next to the mark the spotter correctly placed. This is the fallback for those, and it is
     * deliberately looser than it would be anywhere else, because it is consulted only where the
     * spotter has already fired. The question it answers is not "is this word a command" but
     * "which word here did the recogniser write for the command we know was spoken".
     *
     * 0.5 accepts "comment" for comma (0.57), "common" (0.67), "column" for colon (0.67),
     * "question mug" (0.75); it rejects unrelated neighbours like "now" (0.2) or "okay".
     */
    private const val RESEMBLANCE_THRESHOLD = 0.5f

    /**
     * Finds the run of words that most resembles the detected command, near its time.
     *
     * Highest similarity wins rather than the closest in time, because a partial run must lose to
     * the full one: "question" alone scores 0.67 against "question mark", "question mug" 0.75,
     * and taking the shorter would leave "mug" behind. Ties go to the nearer run.
     */
    private fun findResemblance(
        words: List<TimedWord>,
        detection: Detection,
        taken: Set<Int>,
    ): IntRange? {
        val spellings = PunctuationCommands.spellingsFor(detection.id).map(::letters)
        if (spellings.isEmpty()) return null
        var best: IntRange? = null
        var bestScore = RESEMBLANCE_THRESHOLD
        var bestDistance = Float.MAX_VALUE
        for (start in words.indices) {
            if (start in taken) continue
            val distance = kotlin.math.abs(words[start].start - detection.seconds)
            if (distance > MATCH_WINDOW_SECONDS) continue
            for (length in 1..MAX_COMMAND_WORDS) {
                val end = start + length - 1
                if (end >= words.size) break
                if ((start..end).any { it in taken }) break
                val run = letters((start..end).joinToString("") { words[it].text })
                if (run.isEmpty()) continue
                val score = spellings.maxOf { similarity(run, it) }
                if (score > bestScore || (score == bestScore && best != null && distance < bestDistance)) {
                    bestScore = score
                    bestDistance = distance
                    best = start..end
                }
            }
        }
        return best
    }

    /**
     * Finds the leftover tail of a command word the recogniser cut short: the "a" of "comma",
     * split off by [words] as [TimedWord.detached].
     *
     * Edit distance cannot find it -- "a" against "comma" scores 0.2 -- but it needs no
     * resemblance threshold, because the evidence is structural. The token carried no leading
     * space, so the recogniser did not write it as a word of its own; it came after a pause no
     * word contains; the spotter heard this command right there; and its letters are how the
     * command ends. A genuine article "a" fails the first test: it arrives as " a".
     */
    private fun findResidue(
        words: List<TimedWord>,
        detection: Detection,
        taken: Set<Int>,
    ): IntRange? {
        val spellings = PunctuationCommands.spellingsFor(detection.id).map(::letters)
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        words.forEachIndexed { i, word ->
            if (!word.detached || i in taken) return@forEachIndexed
            val distance = kotlin.math.abs(word.start - detection.seconds)
            if (distance > MATCH_WINDOW_SECONDS || distance >= bestDistance) return@forEachIndexed
            val tail = letters(word.text)
            if (tail.isEmpty() || spellings.none { it.length > tail.length && it.endsWith(tail) }) {
                return@forEachIndexed
            }
            best = i
            bestDistance = distance
        }
        return best?.let { it..it }
    }

    /** Lower-cased letters only, so spacing and clinging punctuation do not count as edits. */
    private fun letters(text: String): String =
        text.lowercase().filter { it.isLetter() }

    /** 1 - Levenshtein distance / longer length: 1 for identical, 0 for nothing in common. */
    internal fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous; previous = current; current = swap
        }
        return 1f - previous[b.length].toFloat() / maxOf(a.length, b.length)
    }

    /**
     * Whether words [start]..[end] spell one of [spellings], joined the way each script writes.
     *
     * Both joins are tried because the word list mixes them: Latin words are separated by
     * spaces, Han characters are not, and a code-switched segment can put one next to the other.
     */
    private fun matches(
        words: List<TimedWord>,
        start: Int,
        end: Int,
        spellings: List<String>,
    ): Boolean {
        val slice = (start..end).map { words[it].text }
        val spaced = slice.joinToString(" ").trim().lowercase().trim(*TRIMMED)
        val tight = slice.joinToString("").trim().lowercase().trim(*TRIMMED)
        return spellings.any { it == spaced || it == tight }
    }

    /** Punctuation that may still cling to a word when the model wrote its own. */
    private val TRIMMED = charArrayOf('.', ',', '?', '!', ';', ':', '，', '。', '？', '！')
}
