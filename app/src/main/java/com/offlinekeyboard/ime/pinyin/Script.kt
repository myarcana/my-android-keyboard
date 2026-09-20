package com.offlinekeyboard.ime.pinyin

/**
 * Simplified to Traditional, in the Taiwan standard.
 *
 * The dictionary is Simplified only, and Traditional is produced by converting it. That is not a
 * shortcut -- it is how OpenCC-based IMEs do it, and shipping a second dictionary would double
 * the asset for a mapping that is almost entirely mechanical.
 *
 * **Almost.** Two things make this more than a character swap, and both are handled by the table
 * the builder composed:
 *
 *  - **One Simplified character, several Traditional ones.** 发 is 發 (to send) or 髮 (hair);
 *    只 is 只 or 隻. Character-by-character conversion has to pick one and will sometimes be
 *    wrong, so phrases are matched first and longest-first: 头发 is in the phrase table as 頭髮,
 *    and only text no phrase covers falls through to the character table.
 *  - **Taiwan uses different words, not just different glyphs.** 软件 is 軟體 in Taiwan, not the
 *    character-wise 軟件; 网络 is 網路; 鼠标 is 滑鼠. A keyboard that produced 軟件 would be
 *    writing mainland Chinese in Traditional characters, which is not what a Taiwanese user
 *    asked for.
 *
 * Both tables arrive pre-composed from `tools/build_pinyin_dict.py`, so the work here is one
 * longest-match walk rather than OpenCC's multi-stage chain.
 */
internal class Script(
    private val chars: Map<String, String>,
    private val phrases: Map<String, String>,
) {

    /** Longest phrase key, so the walk knows how far ahead to look. */
    private val maxPhrase: Int = phrases.keys.maxOfOrNull { it.length } ?: 0

    /**
     * Converts [text] to Taiwan Traditional.
     *
     * Longest-match-first at each position. Greedy rather than optimal: a longer phrase match is
     * always taken, which is the behaviour OpenCC's mmseg segmentation approximates and is right
     * essentially always, because phrase entries exist precisely where the character-wise result
     * would be wrong.
     */
    fun toTraditional(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            var matched = false
            val longest = minOf(maxPhrase, text.length - i)
            for (len in longest downTo 2) {
                val piece = text.substring(i, i + len)
                val replacement = phrases[piece]
                if (replacement != null) {
                    out.append(replacement)
                    i += len
                    matched = true
                    break
                }
            }
            if (!matched) {
                val ch = text.substring(i, i + 1)
                out.append(chars[ch] ?: ch)
                i++
            }
        }
        return out.toString()
    }

    /** Applies [toTraditional] only when [traditional] is set, so callers need no branch. */
    fun render(text: String, traditional: Boolean): String =
        if (traditional) toTraditional(text) else text
}
