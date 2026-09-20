package com.offlinekeyboard.ime.text

/**
 * How much text one backspace should remove.
 *
 * `InputConnection.deleteSurroundingText` counts UTF-16 code units, and a user counts *things
 * they can see*. For ASCII those agree, which is why deleting a fixed 1 went unnoticed for so
 * long; for an emoji they never agree, and the gap is not off-by-one but off-by-whatever the
 * emoji happens to be built from:
 *
 *   - `a`          1 unit    -- deleting 1 is correct
 *   - `😀`          2 units   -- a surrogate pair; deleting 1 leaves half a character, which
 *                               renders as tofu and is not valid text
 *   - `👍🏽`          4 units   -- thumb plus a skin-tone modifier; deleting 1 leaves the thumb
 *                               with a broken modifier behind it
 *   - `👨‍👩‍👧`     8 units   -- three people joined by zero-width joiners; deleting from the
 *                               end unpicks the family one person at a time
 *   - `🇯🇵`          4 units   -- two regional indicators; deleting 1 turns a flag into a letter
 *
 * In every non-ASCII case the press *looks like it did nothing*: the glyph is still there, just
 * subtly different or replaced by a box. That is the bug this file exists to remove.
 *
 * ### Why this is hand-rolled rather than `BreakIterator`
 *
 * `android.icu.text.BreakIterator.getCharacterInstance()` is the authoritative implementation of
 * UAX #29 and would be the obvious choice, but it lives in the Android framework: it is not on
 * the classpath of the JVM unit tests this repo runs, so using it would make the one piece of
 * logic most worth testing the one piece that cannot be. The rules below cover the cases a
 * keyboard actually meets -- surrogate pairs, ZWJ sequences, skin tones, flags, variation
 * selectors, keycaps and combining marks -- and degrade to "one code point" for anything else,
 * which is the same answer the old code gave for everything.
 */
object GraphemeCluster {

    private const val ZWJ = '\u200D'

    /** U+FE0E and U+FE0F: force text or emoji presentation of the character before them. */
    private const val VARIATION_SELECTOR_FIRST = '\uFE0E'
    private const val VARIATION_SELECTOR_LAST = '\uFE0F'

    /** U+20E3, the enclosing keycap that turns `1` into `1️⃣`. */
    private const val COMBINING_KEYCAP = '\u20E3'

    private const val REGIONAL_INDICATOR_FIRST = 0x1F1E6
    private const val REGIONAL_INDICATOR_LAST = 0x1F1FF

    private const val SKIN_TONE_FIRST = 0x1F3FB
    private const val SKIN_TONE_LAST = 0x1F3FF

    /** U+E0020..U+E007E, the tag characters spelling out subdivision flags like 🏴󠁧󠁢󠁳󠁣󠁴󠁿. */
    private const val TAG_FIRST = 0xE0020
    private const val TAG_LAST = 0xE007E

    /** U+E007F, which ends a tag sequence. */
    private const val TAG_TERMINATOR = 0xE007F

    /**
     * The number of UTF-16 code units in the last user-visible character of [text].
     *
     * Returns 0 for empty input, so a caller can tell "nothing to delete" from "delete one".
     * Never returns more than `text.length`.
     */
    fun lastClusterLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0

        var start = lastCodePointStart(text, text.length)

        // Trailing combining marks, variation selectors and keycaps attach to whatever is in
        // front of them, so walk back over them before deciding what the base character is.
        while (start > 0 && isTrailingModifier(Character.codePointAt(text, start))) {
            start = lastCodePointStart(text, start)
        }

        // A tag sequence (subdivision flags) is a base emoji followed by tag characters and a
        // terminator. The loop above stops at the terminator; these consume the tags themselves.
        while (start > 0 && isTagCharacter(Character.codePointAt(text, start))) {
            start = lastCodePointStart(text, start)
        }

        // A skin-tone modifier binds to the person in front of it: 👍🏽 is one character.
        if (start > 0 && isSkinTone(Character.codePointAt(text, start))) {
            start = lastCodePointStart(text, start)
            while (start > 0 && isTrailingModifier(Character.codePointAt(text, start))) {
                start = lastCodePointStart(text, start)
            }
        }

        // Zero-width joiner sequences: 👨‍👩‍👧 is three people and two joiners, and all of it is
        // one character. Each step back must find a joiner *and* something before it to join.
        while (start > 0) {
            val beforeStart = lastCodePointStart(text, start)
            if (text[beforeStart] != ZWJ) break

            // The joiner cannot be the whole cluster: "‍x" with nothing before the joiner is a
            // stray joiner, and deleting it alone is the honest answer.
            if (beforeStart == 0) break

            var next = lastCodePointStart(text, beforeStart)
            while (next > 0 && isTrailingModifier(Character.codePointAt(text, next))) {
                next = lastCodePointStart(text, next)
            }
            if (next > 0 && isSkinTone(Character.codePointAt(text, next))) {
                next = lastCodePointStart(text, next)
            }
            start = next
        }

        // Flags are pairs of regional indicators. Consume them two at a time so that a run of
        // four (two flags) gives up one flag per press rather than collapsing into one.
        if (isRegionalIndicator(Character.codePointAt(text, start))) {
            val runStart = regionalIndicatorRunStart(text, start)
            val indicators = countCodePoints(text, runStart, text.length)
            // An even-length run ends in a complete flag, so take the final pair. An odd one has
            // a lone indicator at the end, which is its own character.
            if (indicators % 2 == 0) start = lastCodePointStart(text, start)
        }

        return text.length - start
    }

    /** Index where the code point ending at [end] begins: [end] - 2 for a surrogate pair. */
    private fun lastCodePointStart(text: CharSequence, end: Int): Int {
        if (end <= 0) return 0
        val low = text[end - 1]
        if (end >= 2 && Character.isLowSurrogate(low) && Character.isHighSurrogate(text[end - 2])) {
            return end - 2
        }
        return end - 1
    }

    /** Start of the unbroken run of regional indicators containing the one at [from]. */
    private fun regionalIndicatorRunStart(text: CharSequence, from: Int): Int {
        var start = from
        while (start > 0) {
            val previous = lastCodePointStart(text, start)
            if (!isRegionalIndicator(Character.codePointAt(text, previous))) break
            start = previous
        }
        return start
    }

    private fun countCodePoints(text: CharSequence, start: Int, end: Int): Int {
        var count = 0
        var i = start
        while (i < end) {
            i += Character.charCount(Character.codePointAt(text, i))
            count++
        }
        return count
    }

    /** Marks that have no width of their own and belong to the character before them. */
    private fun isTrailingModifier(codePoint: Int): Boolean {
        if (codePoint == COMBINING_KEYCAP.code) return true
        if (codePoint == TAG_TERMINATOR) return true
        if (codePoint >= VARIATION_SELECTOR_FIRST.code && codePoint <= VARIATION_SELECTOR_LAST.code) {
            return true
        }
        return when (Character.getType(codePoint).toByte()) {
            Character.NON_SPACING_MARK,
            Character.ENCLOSING_MARK,
            Character.COMBINING_SPACING_MARK,
            -> true
            else -> false
        }
    }

    private fun isTagCharacter(codePoint: Int) = codePoint in TAG_FIRST..TAG_LAST

    private fun isSkinTone(codePoint: Int) = codePoint in SKIN_TONE_FIRST..SKIN_TONE_LAST

    private fun isRegionalIndicator(codePoint: Int) =
        codePoint in REGIONAL_INDICATOR_FIRST..REGIONAL_INDICATOR_LAST
}
