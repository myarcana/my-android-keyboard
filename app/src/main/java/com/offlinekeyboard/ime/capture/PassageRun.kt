package com.offlinekeyboard.ime.capture

/**
 * One attempt at one passage: what was asked for, what actually came out, and where in the first
 * the second has got to.
 *
 * This replaces the lab's old idea of itself. It used to be a *gatekeeper*: one target armed at a
 * time, each gesture judged against it, accepted or thrown away, and the passage advanced only on
 * an accepted gesture. Every rejection was a hole in the bank, and the holes were not random --
 * they were exactly the mistakes. The first prose session collected 276 taps and **zero** misses,
 * because a miss was defined as a non-event and dropped on the floor.
 *
 * So the lab stops judging and starts recording. It keeps four things and nothing else:
 *
 * - the **intended** string, which is the passage;
 * - the **actual** string, which is what the typing produced;
 * - the **gestures**, in order, each with its raw path;
 * - the **mapping** between them -- what each gesture did to the text.
 *
 * The two mappings are not the same kind of thing and are not obtained the same way.
 *
 * **Gesture to output is recorded, never inferred.** Each gesture carries the exact edit it made
 * -- the text it typed, the characters it deleted -- captured at the moment it was made, from the
 * keyboard that made it. Reconstructing that afterwards by lining strings up would be guessing at
 * something that was known for certain at the time, and it would guess wrong on precisely the
 * interesting cases: a glide that typed a word with a space in front of it, a backspace, a flick
 * that produced a digit.
 *
 * **Intended against actual is compared afterwards, because it cannot be known before.** Whether
 * a letter was a mistake depends on what the typist does next -- backspace it and it was a
 * mis-hit, leave it and it may be a passage read loosely. That is not a verdict available at the
 * instant a finger lifts, and the old lab's habit of pretending otherwise is what made it throw
 * the mistakes away. The typist's own corrections are the ground truth, and they only exist in
 * hindsight.
 *
 * Nothing here rejects anything. There is no outcome in which a gesture is not recorded.
 */
class PassageRun(
    val passageId: String,
    /** What the passage asks to be typed, as one string. */
    val intended: String,
    /** The tokens of the passage, only so a gesture can be tagged with what it was aimed at. */
    val targets: List<Target> = emptyList(),
) {

    /** One thing a gesture did to the text. */
    data class Edit(val typed: String, val deleted: Int) {
        val isEmpty: Boolean get() = typed.isEmpty() && deleted == 0
    }

    private val out = StringBuilder()

    /** Gestures recorded so far, which is also the sequence number of the next one. */
    var count: Int = 0
        private set

    /** What the typing has actually produced. */
    val actual: String get() = out.toString()

    /** How far through, in characters. The caret, in other words. */
    val position: Int get() = out.length

    /**
     * Characters of [actual] that match [intended] from the start.
     *
     * A prefix rather than an alignment on purpose. This is only for the live display, where the
     * useful thing to show is "you and the passage agree up to here"; the real alignment costs an
     * edit-distance table and belongs offline, where it can be redone and argued with.
     */
    val correctPrefix: Int
        get() {
            var i = 0
            while (i < out.length && i < intended.length && out[i] == intended[i]) i++
            return i
        }

    val isComplete: Boolean get() = out.length >= intended.length

    /** Applies what a gesture did, and returns the sequence number it was given. */
    fun apply(edit: Edit): Int {
        val seq = count
        count++
        repeat(edit.deleted.coerceAtMost(out.length)) { out.deleteCharAt(out.length - 1) }
        out.append(edit.typed)
        return seq
    }

    /**
     * The passage token sitting at [position], for tagging a gesture with what it was aimed at.
     *
     * A hint and explicitly not a verdict. It says which word the caret was inside when the
     * gesture happened, which is worth storing because it is free and it is what the old bank
     * called `prompt`; it is not evidence that the gesture was a correct attempt at anything.
     * Once the typing has drifted from the passage this will be wrong, and that is fine -- the
     * offline alignment does not consult it.
     */
    fun targetAt(at: Int = position): Target? {
        if (targets.isEmpty()) return null
        var start = 0
        targets.forEachIndexed { i, target ->
            val end = start + target.display.length
            if (at < end || i == targets.lastIndex) return target
            start = end + 1
        }
        return targets.lastOrNull()
    }

    /** Where in [intended] each token starts, for drawing the passage. */
    fun tokenRanges(): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = 0
        targets.forEach { target ->
            ranges += start until (start + target.display.length)
            start += target.display.length + 1
        }
        return ranges
    }

    companion object {
        /** The passage as one typeable string: its tokens, space separated. */
        fun textOf(passage: Passage): String = passage.targets.joinToString(" ") { it.display }
    }
}
