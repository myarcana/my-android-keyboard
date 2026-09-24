package com.offlinekeyboard.ime.glide

/**
 * Whether a keypress straight after a glided word should be preceded by a space.
 *
 * A glide puts its space in *front* of the word, so the caret is left against the last letter
 * (see `KeyboardService.commitGlide`). That is right for a following glide, which adds its own
 * leading space, and for punctuation, which belongs against the word. It is wrong for a tapped
 * letter: that starts the next word, and without this it ran straight onto the glided one.
 *
 * Kept free of Android imports so the rule can be stated as tests on the JVM.
 */
internal object GlideSpacing {

    /**
     * @param text what the key would type.
     * @param glideEnd the caret offset where the glided word ended, or -1 if the field would not
     *   say where that was.
     * @param caret the caret offset now, or -1 if the field will not say.
     * @param before the character immediately before the caret, or null if there is none.
     */
    fun needsSpace(text: String, glideEnd: Int, caret: Int, before: Char?): Boolean {
        // Only a letter starts a new word. Punctuation, digits and symbols go against the word.
        if (text.firstOrNull()?.isLetter() != true) return false
        // Nothing to separate from, or already separated.
        if (before == null || before.isWhitespace()) return false
        // The caret has gone somewhere else since the glide -- a delete, a tap into the text,
        // the trackpad. The letter then belongs to whatever is there, not to a new word.
        if (glideEnd >= 0 && caret >= 0 && caret != glideEnd) return false
        return true
    }
}
