package com.offlinekeyboard.ime.text

import java.text.BreakIterator

/**
 * How much text one bulk deletion should remove -- a word, or a whole line.
 *
 * [deleteLength] is used by the held backspace once it speeds up past single characters, and
 * it is deliberately coarse: whitespace-delimited, with the trailing spaces included, so each
 * step removes a lot. [flickDeleteLength] is the swipe *down* on backspace. That gesture
 * is one deliberate flick, so it removes one word and whatever trails it, and nothing more.
 * [lineDeleteLength] answers the largest version of the question, for the swipe *up*.
 *
 * `java.text.BreakIterator` rather than `android.icu`: it is on the JVM test classpath, and on
 * Android it is backed by ICU anyway.
 *
 * Lives in `text/` with [GraphemeCluster] and for the same reason: it is the part of deleting
 * that is worth testing, and keeping it out of the service is what makes it testable on the
 * JVM at all.
 */
object WordBoundary {

    /**
     * Code units to delete backwards from the end of [before] to remove one word.
     *
     * Trailing spaces go first, then the run of non-whitespace behind them, so deleting after
     * `"hello world "` takes `"world "` and leaves `"hello "` -- the space the next word will
     * need is already there. Both halves are optional: mid-word there are no trailing spaces to
     * skip, and a lone run of spaces has no word behind it.
     *
     * Stops at a line break rather than stepping over it. A word delete should pause at the
     * start of each line instead of silently joining it to the line above, which is the one
     * outcome here that destroys structure the user cannot retype by typing the word again.
     *
     * Returns 0 for empty input -- there is nothing behind the cursor to delete. Callers that
     * must make progress regardless should coerce, but a caller that deletes 0 has correctly
     * done nothing.
     */
    fun deleteLength(before: CharSequence): Int {
        var n = 0
        // Any run of spaces immediately behind the cursor belongs to the word being removed.
        while (n < before.length && before[before.length - 1 - n] == ' ') n++
        // Then the word itself, up to whitespace -- which includes the newline that stops us.
        while (n < before.length && !before[before.length - 1 - n].isWhitespace()) n++
        return n
    }

    /**
     * Code units to delete backwards from the end of [before] to remove one word together with
     * whatever trails it. This is the swipe *down* on backspace.
     *
     * The unit is read backwards from the cursor in three parts, each optional:
     *
     *  1. **Spaces** behind the cursor, never a hard line break.
     *  2. **Punctuation**, such as `"."` or `"!"`, behind those spaces.
     *  3. **The word** behind that punctuation, bounded the way the platform's word selection
     *     bounds it: by [BreakIterator]. Punctuation and symbols end a word (`"hello-world"`
     *     gives `"world"`) but word-internal ones do not (`"don't"`, `"3.14"`, `"foo_bar"` stay
     *     whole). On a device the iterator is ICU, so Chinese splits into dictionary words too.
     *     Something that is not a word -- a symbol, an emoji -- is one visible character, as
     *     [GraphemeCluster] counts it.
     *
     * One space or one punctuation mark is what ends a word, so it goes with the word: the
     * cursor after `"hello."`, `"hello "` or `"hello. "` takes all of it in one flick. A *run*
     * of two or more is something typed on purpose -- an ellipsis, `"?!"`, padding -- so it is a
     * unit of its own and stops there: `"One Two    "` gives `"One Two"`, `"Okay..."` gives
     * `"Okay"`, and the next flick takes the word. Punctuation *before* a word is not the
     * word's: `"(aside"` gives `"("`.
     *
     * Taking the spaces does mean a flick at the start of a soft-wrapped line reaches the word
     * at the end of the line above -- the keyboard cannot see the wrap, and it is always at a
     * space. A hard line break is still never part of a unit; with one directly behind the
     * cursor this returns 0, as [deleteLength] does.
     */
    fun flickDeleteLength(before: CharSequence): Int {
        val end = before.length
        var start = end
        var spaces = 0
        while (start > 0) {
            val cp = Character.codePointBefore(before, start)
            if (isLineBreak(cp) || !isSpace(cp)) break
            start -= Character.charCount(cp)
            spaces++
        }
        if (spaces > 1) return end - start
        var marks = 0
        while (start > 0) {
            val cp = Character.codePointBefore(before, start)
            if (!isPunctuation(cp)) break
            start -= Character.charCount(cp)
            marks++
        }
        if (marks > 1) return end - start
        if (start > 0) {
            val cp = Character.codePointBefore(before, start)
            if (!isSpace(cp) && !isLineBreak(cp)) start = wordStart(before.subSequence(0, start))
        }
        return end - start
    }

    /**
     * Where the word -- or, failing that, the one visible character -- that ends [before]
     * begins. [before] must be non-empty and must not end in whitespace.
     */
    private fun wordStart(before: CharSequence): Int {
        val end = before.length
        // A letter followed by combining marks ("e" + U+0301) is still a letter. Classify the
        // last visible character by the code point it is built on, not by its final mark.
        val cluster = GraphemeCluster.lastClusterLength(before).coerceIn(1, end)
        val base = Character.codePointAt(before, end - cluster)
        if (!Character.isLetterOrDigit(base)) return end - cluster

        // The word is found in two steps. The explicit rules below run first, and they are what
        // stops a word at a hyphen, a slash or a bracket the same way on every runtime. The JDK's
        // own word iterator keeps "hello-world" together and ICU splits it, so neither can be
        // used alone. BreakIterator may then only *narrow* the result. On a device that is ICU,
        // and its dictionaries are the only way to find word breaks inside a run of Chinese or
        // Thai that has no spaces or punctuation in it.
        var start = end - cluster
        while (start > 0) {
            val cp = Character.codePointBefore(before, start)
            val prev = start - Character.charCount(cp)
            start = when {
                isWordPart(cp) -> prev
                // A joiner counts only with word characters on both sides: the "'" in "don't",
                // the "." in "3.14". The character after it is already in the word.
                prev > 0 && joins(cp, Character.codePointBefore(before, prev), Character.codePointAt(before, start)) -> prev
                else -> break
            }
        }

        val words = BreakIterator.getWordInstance()
        words.setText(before.toString())
        val iteratorStart = words.preceding(end)
        if (iteratorStart != BreakIterator.DONE && iteratorStart > start) start = iteratorStart
        // Never less than the cluster itself, so a word break the iterator places inside the
        // last character cannot split it.
        return minOf(start, end - cluster)
    }

    /** Letters, digits, and the marks and joiners that attach to them. */
    private fun isWordPart(cp: Int): Boolean {
        if (Character.isLetterOrDigit(cp)) return true
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK,
            Character.FORMAT, Character.CONNECTOR_PUNCTUATION -> true // "_" in foo_bar, ZWJ
            else -> false
        }
    }

    /**
     * Whether [mid] between [left] and [right] stays inside one word. These are UAX #29's
     * MidLetter, MidNum and MidNumLet: an apostrophe or dot joins letters ("don't", "e.g") and
     * a dot, comma or apostrophe joins digits ("3.14", "1,000").
     */
    private fun joins(mid: Int, left: Int, right: Int): Boolean {
        val letters = Character.isLetter(left) && Character.isLetter(right)
        val digits = Character.isDigit(left) && Character.isDigit(right)
        return when (mid) {
            '\''.code, 0x2019, 0x2018, '.'.code, 0x2024, 0xFE52, 0xFF07, 0xFF0E -> letters || digits
            0x00B7, 0x0387, 0x05F4, 0x2027 -> letters
            ','.code, 0x066C, 0xFE50, 0xFF0C -> digits
            else -> false
        }
    }

    private fun isSpace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.isSpaceChar(cp)

    /** Hard line breaks: the separators a field draws as a new line whatever its width. */
    private fun isLineBreak(cp: Int): Boolean =
        cp == '\n'.code || cp == '\r'.code || cp == 0x0B || cp == 0x0C ||
            cp == 0x85 || cp == 0x2028 || cp == 0x2029

    /** The general categories Android's own word selection treats as punctuation. */
    private fun isPunctuation(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    /**
     * Code units to delete backwards from the end of [before] to remove the rest of the line.
     *
     * Everything back to the line break, and not the break itself: deleting after
     * `"one\ntwo three"` takes `"two three"` and leaves `"one\n"`, so the cursor ends where a
     * fresh line starts rather than joined onto the line above. Structure the user cannot
     * retype by typing the words again is the one thing this must not destroy, which is the
     * same rule [deleteLength] and [flickDeleteLength] follow for the same reason.
     *
     * A cursor already sitting on an empty line returns 0 -- the line is empty, there is
     * nothing on it to clear. A caller that wants the flick to keep making progress past that
     * point has to decide so itself; this function reports what the line holds and no more.
     *
     * Counts code units rather than characters because that is what `deleteSurroundingText`
     * takes, and it is unaffected by surrogate pairs here: a line is delimited by `\n`, which
     * is never part of one.
     */
    fun lineDeleteLength(before: CharSequence): Int {
        var n = 0
        while (n < before.length && before[before.length - 1 - n] != '\n') n++
        return n
    }
}
