package com.offlinekeyboard.ime.glide

/**
 * Remembers which words a glide at a given place in the text has already had rejected, so
 * re-drawing it offers the next candidate instead of the same wrong one again.
 *
 * The decoder ranks its candidates and [GlideEngine.decode] hands them over best first. Taking
 * the first one is right almost always, and when it is wrong the user says so in the only way
 * available: they delete the word. Before this existed the next glide re-ran the same path
 * through the same models and got the same answer, so a word the decoder was confident and wrong
 * about could not be typed by gliding at all -- the user had to give up and tap it. The delete is
 * already an unambiguous statement that this word is not the wanted one; all that was missing was
 * somewhere to write it down.
 *
 * **Rejections are keyed on where in the text the word was typed**, not on the path that drew it.
 * Two readings of "the same place" were available and this is the one that matches what a user is
 * doing: they are fixing *this word*, here, in this sentence, and they will happily redraw it a
 * little differently each time -- a path-shape key would treat each sloppier retry as a new
 * question and answer it with the same rejected word. The caret does not move while a word is
 * being retyped in place, which makes the position a stable name for the attempt. It also scopes
 * the memory correctly by construction: the same word glided later in the sentence is a different
 * position and starts from the top, because nobody has said anything about *that* one.
 *
 * ## What is stored, and why it is a list rather than a flag
 *
 * Each position accumulates the words rejected at it, in order, and [next] returns the best
 * candidate not in that set. So deleting three times walks first to second to third choice rather
 * than sticking at the second -- the user who keeps deleting is being *more* emphatic, not
 * repeating themselves, and the rejections compose.
 *
 * ## Unless the decoder is sure
 *
 * A refusal only steers a glide the decoder was unsure about. When the redraw decodes to a word
 * with probability [SURE] or more, that word is typed even if it was refused here: a path that
 * clean is the user deliberately drawing the same word again, and the keyboard second-guessing
 * them would be the same dead end this class removes, pointed the other way.
 *
 * Running off the end wraps back to the best candidate and clears the position. The alternative
 * -- a glide that types nothing because every candidate has been refused -- makes the keyboard
 * appear broken at the exact moment the user is already frustrated, and by then the cycle has
 * shown them everything the decoder knows, so starting it over is both the most useful thing left
 * and what a cycling UI is expected to do.
 *
 * ## Lifetime
 *
 * Purely in memory, and deliberately short-lived. It is cleared when a glide is *accepted* --
 * whatever the user does next that is not deleting it settles the question, see
 * [acceptedAt] -- and when the focus leaves the field. Nothing is written to disk: a permanent
 * "this path is never that word" would eventually blacklist a word on the strength of one
 * mis-swipe, and no gesture here is a strong enough statement to earn that.
 *
 * Not thread-safe; every caller is the IME's main thread, which is also the only thread that
 * touches the editor.
 */
class GlideRejections {

    /**
     * Words refused at a caret position, oldest first.
     *
     * Keyed by the offset the word *starts* at rather than where it ends, because that is the
     * one of the two that a retype does not move: the word that replaces it is a different
     * length, so its end lands somewhere else while its start is exactly where the last one
     * began. See [WordSite.start].
     */
    private val refused = HashMap<Int, MutableList<String>>()

    /**
     * The glide that was committed most recently and could still be taken back.
     *
     * Held because the deletion arrives as a plain edit -- a backspace, a swipe on the backspace
     * key, a selection replaced, the app's own undo -- and none of them announce that the thing
     * they removed was a glided word. What identifies the deletion is comparing the field
     * against this, which is why the exact text and place are kept rather than a bare flag.
     */
    private var last: Commit? = null

    /** A word this glide engine typed, and the span of text it occupies. */
    private data class Commit(
        /** The offset the word itself starts at, excluding any space glide put in front of it. */
        val start: Int,
        /**
         * The word as the *field* received it, which after a shift is `Hello` rather than `hello`.
         * This is what the delete-watcher compares against the text, so it has to match the
         * field exactly.
         */
        val written: String,
        /**
         * The same word as the *candidate list* spells it, always lower case.
         *
         * Kept apart from [written] because the two are matched against different things, and
         * conflating them silently broke every word glided with shift armed: the rejection went
         * in as `Hello`, the next decode offered `hello`, no candidate ever compared equal, and
         * a capitalised word could never advance past the decoder's first guess.
         */
        val candidate: String,
    )

    /**
     * The best candidate not yet refused at [start], or null when [candidates] is empty.
     *
     * Wrapping is handled by forgetting: once every candidate has been refused the position is
     * cleared and the first is offered again, so the next delete starts a fresh lap rather than
     * finding an exhausted list.
     *
     * A refusal is overridden when the decoder is sure: see [SURE].
     */
    fun next(start: Int, candidates: List<GlideCandidate>): String? {
        val best = candidates.firstOrNull() ?: return null
        val rejected = refused[start] ?: return best.word
        // Deleting a word and drawing it again, cleanly enough that the decoder has no doubt, is
        // the user insisting on it -- they deleted it for some other reason, or want it twice.
        // The refusal stays recorded, so a sloppier redraw after this still moves on.
        if (best.confidence >= SURE) return best.word
        val fresh = candidates.firstOrNull { it.word !in rejected }
        if (fresh != null) return fresh.word
        // Every candidate has been through. Forget this position so the cycle restarts clean;
        // leaving the list in place would make the *next* delete a no-op.
        refused.remove(start)
        return best.word
    }

    /**
     * Records that a glide wrote [written] at [start] for the candidate [candidate].
     *
     * The two differ whenever shift was armed. [written] is what to look for in the field;
     * [candidate] is what to strike off the ranking if it turns out to have been deleted.
     */
    fun committed(start: Int, written: String, candidate: String = written) {
        last = Commit(start, written, candidate)
    }

    /**
     * The word committed at [start] was deleted: remember it as refused there.
     *
     * Returns true when this was a glided word being taken back, which is the caller's signal
     * that the rejection was recorded and the next glide here will move on.
     */
    fun rejectLast(): Boolean {
        val commit = last ?: return false
        val list = refused.getOrPut(commit.start) { mutableListOf() }
        // The candidate spelling, so it compares equal to what the next decode returns.
        if (commit.candidate !in list) list += commit.candidate
        // The word is gone from the field, so there is nothing left to take back. Without this
        // a second delete -- of whatever the user typed *instead* -- would be read as a second
        // rejection of a word that is no longer there.
        last = null
        return true
    }

    /**
     * The glide committed at [start] survived: the user did something other than delete it.
     *
     * Clears the rejections for the position, because the question that was being asked there
     * has been answered. Leaving them would mean a later glide at the same offset -- a new
     * sentence, the same field -- inherited refusals aimed at a word that is no longer in play.
     */
    fun acceptedAt(start: Int) {
        refused.remove(start)
        last = null
    }

    /**
     * Where the last glide wrote and the exact text it put there, for the watcher that decides
     * whether it is still in the field. Null when there is no glide left to take back.
     */
    fun pendingCommit(): Pair<Int, String>? = last?.let { it.start to it.written }

    /** Focus left the field, or the field's text changed under us. Nothing here still applies. */
    fun clear() {
        refused.clear()
        last = null
    }

    companion object {
        /**
         * How sure the decoder has to be of its best word to type it despite a refusal here.
         *
         * A delete says the word was wrong, but it does not say the *path* was ambiguous, and the
         * two cases look different to the decoder. When the user redraws a word they really
         * want, they draw it carefully and it decodes with nothing close behind it; when the
         * decoder is guessing, the second word is within a nat or two and the probability sits
         * well below this. Skipping the confident case made a deliberately repeated word -- and
         * a word deleted for some reason of the user's own -- impossible to glide.
         *
         * High on purpose, because the softmax is taken over the top few candidates only and so
         * runs generous: below this the refusal wins, which is the behaviour the rejection
         * memory exists for.
         */
        const val SURE = 0.9f
    }
}
