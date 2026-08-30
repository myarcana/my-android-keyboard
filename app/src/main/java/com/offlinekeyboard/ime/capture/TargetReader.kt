package com.offlinekeyboard.ime.capture

import com.offlinekeyboard.ime.gesture.GestureIntent
import com.offlinekeyboard.ime.gesture.GestureTrace
import com.offlinekeyboard.ime.gesture.GestureVerdict

/**
 * What one gesture means for the target currently being typed.
 *
 * The lab used to assume a target was a gesture: one word, one glide, one label. That assumption
 * threw away most of what a passage could tell it. **The passage knows the intended letters at
 * every point, whatever the thumb chooses to do**, so a word being tapped instead of glided is
 * not a failure to record -- it is a run of taps whose ground truth is already on the screen.
 * Under the old rule the first tap of "sorry" was filed as a *glide of the whole word*, with a
 * single-tap path, and the remaining four were rejected for starting on the wrong key. That is
 * worse than collecting nothing: a bank of confidently mislabelled lines is one that will go on
 * to tune the real thing.
 *
 * So a prose word accepts either reading, and says which one it got:
 *
 * - **A glide, before any letter has been tapped**, is the word. One [GestureIntent.WORD] record,
 *   exactly as before.
 * - **A tap on the letter now due** is that letter. One [GestureIntent.LETTER] record carrying
 *   the word it came from and where in it, so a run of taps can be reassembled into the word
 *   afterwards by anything scoring the tap decoder.
 * - **A flick on the letter now due** is also that letter, and is the most valuable line the lab
 *   can collect. The passage asked for a letter and the heuristic produced a symbol; recording it
 *   as a letter with a FLICK verdict is what lets [GestureRecord.verdictIntent] show the
 *   disagreement. Rejecting it -- which is what happened before -- quietly deleted the evidence
 *   that the flick threshold is too loose.
 *
 * Drill targets keep the strict rule and must. The collision passage exists to ask for one
 * specific gesture on one specific key, and a reader that accepted anything there would collect
 * the ambiguity it was built to resolve.
 */
class TargetReader(val target: Target) {

    /** Letters of this target already tapped. Zero for a target that has not been started. */
    var tapped: Int = 0
        private set

    /** True when this target asks for a word that may be glided *or* tapped out. */
    val isWord: Boolean get() = target.letters.isNotEmpty()

    /** The letter now due, or null when this target is not being tapped out. */
    val nextLetter: Char? get() = target.letters.getOrNull(tapped)

    /** The key a gesture must start on to count, which moves through a word as it is tapped. */
    val expectedKeyId: String get() = nextLetter?.toString() ?: target.startKeyId

    sealed interface Reading {

        /** Keep this gesture, under this label. */
        data class Keep(
            val intent: GestureIntent,
            val expected: String,
            /** The word this letter came from, or null when the record is the whole target. */
            val word: String?,
            /** Where in that word, or -1. */
            val letterIndex: Int,
            /** Whether the target is finished and the passage should move on. */
            val complete: Boolean,
        ) : Reading

        /** Started somewhere else. [expectedKeyId] is the key that was due, not the word's first. */
        data class WrongKey(val expectedKeyId: String) : Reading

        /** Asked for a movement and got none. Never returned for a letter. */
        data object TooSmall : Reading

        /**
         * A glide part-way through a word that is being tapped.
         *
         * Not scoreable either way round: it spells the rest of the word rather than the whole
         * one, so filing it under this target's label would claim a gesture that was not made.
         * The thumb has almost always dragged rather than meant it.
         */
        data object MidWordGlide : Reading
    }

    fun read(trace: GestureTrace): Reading =
        if (isWord) readWord(trace) else readStrict(trace)

    /** The collision drill: one named gesture, on one named key, or nothing. */
    private fun readStrict(trace: GestureTrace): Reading {
        if (trace.startKeyId != target.startKeyId) return Reading.WrongKey(target.startKeyId)
        if (target.intent != GestureIntent.LETTER && isNegligible(trace)) return Reading.TooSmall
        return Reading.Keep(target.intent, target.expected, null, -1, complete = true)
    }

    private fun readWord(trace: GestureTrace): Reading {
        val letters = target.letters
        if (trace.verdict == GestureVerdict.GLIDE && tapped == 0) {
            if (trace.startKeyId != target.startKeyId) return Reading.WrongKey(target.startKeyId)
            if (isNegligible(trace)) return Reading.TooSmall
            return Reading.Keep(GestureIntent.WORD, target.expected, null, -1, complete = true)
        }
        val letter = letters.getOrNull(tapped) ?: return Reading.WrongKey(target.startKeyId)
        if (trace.startKeyId != letter.toString()) return Reading.WrongKey(letter.toString())
        if (trace.verdict == GestureVerdict.GLIDE) return Reading.MidWordGlide

        // No negligible guard: a tap is *asking* to be a gesture that barely moves, and the guard
        // would throw away every letter it was meant to collect.
        val index = tapped
        tapped++
        return Reading.Keep(
            intent = GestureIntent.LETTER,
            expected = letter.toString(),
            word = target.expected,
            letterIndex = index,
            complete = tapped >= letters.length,
        )
    }

    /** Undoes the last accepted letter, for the lab's Undo and Void buttons. */
    fun stepBack() {
        if (tapped > 0) tapped--
    }

    private fun isNegligible(trace: GestureTrace): Boolean {
        if (trace.verdict != GestureVerdict.TAP) return false
        val first = trace.path.firstOrNull() ?: return true
        val last = trace.path.lastOrNull() ?: return true
        val dx = last.x - first.x
        val dy = last.y - first.y
        val limit = MIN_DISPLACEMENT_RATIO * trace.keyUnitPx
        return dx * dx + dy * dy < limit * limit
    }

    companion object {
        /**
         * Movement below this fraction of a key width is not an attempt at anything.
         * Deliberately well under the flick threshold: an under-travelled flick that the keyboard
         * read as a tap is a genuine failure and has to be recorded, not filtered out for being
         * inconvenient.
         */
        const val MIN_DISPLACEMENT_RATIO = 0.12f
    }
}
