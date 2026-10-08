package com.offlinekeyboard.ime.glide

/**
 * The words before the caret that a glide is decoded in the light of.
 *
 * FUTO's engine ships with a context language model, and we load it -- but the engine only
 * consults it when it is handed context words (`use_lm` in `SwipeEngine::recognize_multi` requires
 * a non-empty context), and for a long time it was never handed any. Every glide was decoded as
 * if it were the first word of the document, ranked on shape and unigram frequency alone, which
 * is how `so good` came out as `so god`: the two shapes differ by one doubled letter a glide
 * cannot show, and `god` is common enough to win a coin toss that context would have settled.
 *
 * Stops at the start of the sentence. The model has no beginning-of-sentence token, so a sentence
 * start is best said by saying nothing, and the words of the sentence before are evidence about
 * a different sentence. A line break counts as a sentence end for the same reason.
 *
 * Kept free of Android imports so the rule can be stated as tests on the JVM.
 */
internal object GlideContext {

    /** How much text to ask the editor for. Enough for [MAX_WORDS] of ordinary prose. */
    const val LOOKBEHIND = 120

    /**
     * More than the model reads. It keeps only its last few positions anyway, so this bounds
     * the work here rather than deciding anything.
     */
    const val MAX_WORDS = 12

    private const val SENTENCE_END = ".!?\n\u2026\u3002\uff01\uff1f"

    /**
     * The words in [before] -- the text immediately before the caret -- oldest first.
     *
     * A word is a run of letters, digits and in-word apostrophes, so `don't` and `I'm` survive
     * whole (both are in the model's vocabulary) while quotes, brackets and commas fall away.
     * Case is left alone; the decoder lowercases.
     *
     * The first word may be cut off by the lookbehind window, and is dropped when it could be:
     * a fragment like `ther` would be an unknown word rather than the `weather` it came from.
     */
    fun words(before: CharSequence): List<String> {
        var start = before.length
        while (start > 0 && before[start - 1] !in SENTENCE_END) start--
        // A decimal point or an abbreviation's dot is not a sentence end, but telling those
        // apart is guesswork; the cost of guessing wrong is only a shorter context.
        val sentence = before.subSequence(start, before.length)

        val words = ArrayList<String>()
        val word = StringBuilder()
        fun flush() {
            val w = word.toString().trim('\'', '\u2019')
            if (w.isNotEmpty()) words += w.replace('\u2019', '\'')
            word.clear()
        }
        for (c in sentence) {
            if (c.isLetterOrDigit() || c == '\'' || c == '\u2019') word.append(c) else flush()
        }
        flush()

        // Truncated by the window rather than by a sentence end: the oldest word may be partial.
        val truncated = start == 0 && before.length >= LOOKBEHIND &&
            sentence.firstOrNull()?.isLetterOrDigit() == true
        if (truncated && words.isNotEmpty()) words.removeAt(0)
        return if (words.size > MAX_WORDS) words.subList(words.size - MAX_WORDS, words.size) else words
    }
}
