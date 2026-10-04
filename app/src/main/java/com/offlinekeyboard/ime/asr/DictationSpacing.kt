package com.offlinekeyboard.ime.asr

/**
 * Whether a dictated segment needs a space in front of it, given the text already in the field.
 *
 * The model returns a clause per pause and never a leading space, so the join between what is
 * already there and what was just heard is decided here. Latin words take a space between them,
 * Han beside Han takes none, and Latin beside Han takes one ("the best 豆花").
 *
 * Nothing goes after a mark that *opens* something -- "(", "[", "“", "「" and the like -- since
 * the dictated text belongs inside it: "(hello", not "( hello". A straight quote cannot say
 * whether it opens or closes, so it counts as an opener only when it does not follow a word:
 * `say "` opens, `he said "hi"` closes.
 *
 * Kept free of Android imports so the rule can be stated as tests on the JVM.
 */
internal object DictationSpacing {

    /** Marks after which a dictated word starts with no space, half- and full-width. */
    private const val OPENERS = "([{\u201c\u2018\u00ab\u00bf\u00a1" +
        "\uff08\uff3b\uff5b\u300c\u300e\u3010\u3008\u300a\u3014\u3016"

    /** Quotes that are an opener or a closer depending on what is before them. */
    private const val STRAIGHT_QUOTES = "\"'"

    /** Full-width marks fill their own cell and never take a space beside them. */
    private const val FULL_WIDTH = "，。？！；：、「」『』“”（）…—"

    /**
     * @param before the text immediately before the caret; only its last two characters matter.
     *   Empty when the caret is at the start of the field or the field would not say.
     * @param text the segment about to be committed.
     */
    fun needsSpace(before: CharSequence, text: String): Boolean {
        val first = text.firstOrNull() ?: return false
        val last = before.lastOrNull() ?: return false
        if (last.isWhitespace() || !first.isLetterOrDigit()) return false
        if (last in OPENERS) return false
        if (last in STRAIGHT_QUOTES) {
            val prior = before.getOrNull(before.length - 2)
            if (prior == null || prior.isWhitespace() || prior in OPENERS) return false
        }
        return when {
            isHan(last) && isHan(first) -> false
            isHan(first) -> last.isLetterOrDigit()
            else -> last !in FULL_WIDTH
        }
    }

    private fun isHan(c: Char): Boolean =
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
}
