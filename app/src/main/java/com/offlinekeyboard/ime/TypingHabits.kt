package com.offlinekeyboard.ime

/**
 * Which characters send the number and symbol planes back to the letters, as iOS does.
 *
 * This is a *navigation* habit and nothing else. It changes which keys are on screen and never
 * changes a single character of what was typed -- which is the line this keyboard does not
 * cross. There is no auto-punctuation and no auto-capitalisation here, and a plane switch is
 * safe in a way those are not: being wrong costs one tap on "123", not a word the hand did not
 * write and did not watch itself not write.
 *
 * Kept free of Android imports, like [ReturnKey], so the rule is decidable on the JVM and the
 * tests can state it as a sentence rather than drive an emulator.
 */
internal object TypingHabits {

    /**
     * Whether typing [text] on the number or symbol plane should return to the letters.
     *
     * The rule is *not* "punctuation returns to letters" -- the `.` `,` `?` `!` on the
     * punctuation row stay put, which is what makes a phone number, a price or a decimal
     * typeable without the plane flickering underneath the thumb. It is these two, and the
     * reason each one is on the list is different.
     *
     * **Space** is on it because a space in the middle of digits almost always ends the number:
     * "$40 for" leaves the numbers plane at the space and never comes back. The case where it is
     * wrong -- "1 2 3" spaced out, a matrix, a formula -- costs one tap on "123", and iOS has
     * shipped this for eighteen years against a handful of forum complaints.
     *
     * **Apostrophe** is on it for a stronger reason. It sits on the number plane's punctuation
     * row, and there is no English word in which a digit follows one: it is "don't", "it's",
     * "Rhys's" -- always a letter, always immediately. A hand that typed `'` has already
     * committed to a word, so the plane it wants next is not in question. This is the one people
     * notice is missing, because reaching for `'` is a detour made mid-word and iOS hands the
     * letters back before they look up.
     *
     * The double quote is deliberately absent, though it sits beside the apostrophe on the same
     * row. A quote opens a passage whose first character is as likely to be a digit as a letter
     * -- `"3 of them"` -- and unlike the apostrophe it comes in pairs, so the closing one follows
     * text that has already taken the plane wherever it needed to go.
     *
     * **Space only counts once something has been typed on the plane.** [typedOnPlane] is
     * whether any non-space character has been committed since the plane was switched to. A
     * space pressed straight after "123" ends nothing -- there is no number to end -- and the
     * hand that switched planes plainly still wants the symbols it switched for, as in ", " or
     * a space before "$40". Returning then would throw away the tap that was just made on "123".
     * This is also what iOS does: it only leaves the plane at a space after a symbol was typed.
     */
    fun returnsToLetters(text: String, typedOnPlane: Boolean): Boolean = when (text) {
        " " -> typedOnPlane
        "'" -> true
        else -> false
    }
}
