package com.offlinekeyboard.ime.candidates

/**
 * What the user has just typed, as a thing to look emoji up by.
 *
 * Read out of the editor rather than accumulated as keys are pressed. The keyboard has a cursor
 * trackpad, so the caret can move anywhere at any time; a buffer of our own would go stale the
 * moment it did, and the bar would be suggesting for a word somewhere else on screen.
 */
data class TypedWord(val query: String, val length: Int) {
    companion object {
        /** Enough for the two-word form; no emoji name is longer. */
        const val LOOKBEHIND = 48

        /**
         * Han characters are letters to [Char.isLetter] but never part of the word being typed:
         * after picking 他的 out of `tadebabahentaoyanwo` the field reads `他的babahentaoyanwo`,
         * and the next suggestions have to answer `babahentaoyanwo` -- which a query of the whole
         * mixed run, not being pinyin, could not.
         */
        private fun isWordChar(c: Char) =
            (c.isLetter() && !Character.isIdeographic(c.code)) || c == '\'' || c == '-'

        /**
         * The one- and two-word candidates ending at the caret, longest first.
         *
         * Two forms because emoji are named in both shapes: "pizza" is one word and "thumbs up"
         * is two, and only trying both finds each of them. Longest first, so a two-word name
         * wins where it matches -- and [length] is then how much a chosen emoji replaces.
         */
        fun endingAt(before: CharSequence): List<TypedWord> {
            val word = before.takeLastWhile(::isWordChar).toString()
            if (word.isEmpty()) return emptyList()

            val rest = before.subSequence(0, before.length - word.length)
            val previous = if (rest.endsWith(" ")) {
                rest.subSequence(0, rest.length - 1).takeLastWhile(::isWordChar).toString()
            } else {
                ""
            }

            return buildList {
                if (previous.isNotEmpty()) {
                    add(TypedWord("$previous $word", previous.length + 1 + word.length))
                }
                add(TypedWord(word, word.length))
            }
        }
    }
}
