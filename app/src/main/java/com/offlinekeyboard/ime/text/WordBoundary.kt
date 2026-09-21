package com.offlinekeyboard.ime.text

/**
 * How much text one bulk deletion should remove -- a word, or a whole line.
 *
 * [deleteLength] is shared by the two gestures that delete a word rather than a character: the
 * swipe *down* on backspace, and the held backspace once it accelerates past single characters.
 * Both ask the same question -- given the text before the cursor, how many UTF-16 code units
 * make up "the word behind me" -- so both get the same answer from here rather than each
 * scanning its own way and drifting apart. [lineDeleteLength] answers the larger version of
 * that question for the swipe *up*, and lives here beside it because the two differ only in
 * where they agree to stop.
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
     * Code units to delete backwards from the end of [before] to remove the rest of the line.
     *
     * Everything back to the line break, and not the break itself: deleting after
     * `"one\ntwo three"` takes `"two three"` and leaves `"one\n"`, so the cursor ends where a
     * fresh line starts rather than joined onto the line above. Structure the user cannot
     * retype by typing the words again is the one thing this must not destroy, which is the
     * same rule [deleteLength] follows for the same reason.
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
