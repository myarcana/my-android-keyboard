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

    /** What became of one character of [intended]. */
    object Mark {
        const val UNTYPED = 0
        const val CORRECT = 1
        const val WRONG = 2
    }

    /**
     * How [actual] lines up against [intended], one verdict per intended character.
     *
     * This was a common prefix to begin with, and a prefix is wrong in a way that shows on screen
     * immediately: one mistyped letter turns **the entire rest of the passage red**, because the
     * two strings never agree again from that point. It also puts the caret in the wrong place
     * the moment a character is inserted or dropped, so the passage stops saying what to type
     * next. Both were reported from a real session, and both are the same error -- treating two
     * strings that have drifted as though they were still index for index.
     *
     * An edit-distance alignment costs a table the size of the passage squared, which for a
     * couple of hundred characters is nothing, and it gets both right: a substituted letter is
     * one red letter, a dropped one leaves a gap, and the caret sits where the typist actually
     * is.
     *
     * It is deliberately the plain unweighted alignment. A better one -- charging less for
     * confusing two keys that are neighbours than for two on opposite sides of the keyboard --
     * belongs to whatever scores the bank offline, where it can be changed and the whole history
     * re-read under it. What is wanted here is only that the screen tell the truth.
     */
    data class Alignment(
        /** One [Mark] per character of [intended]. */
        val marks: IntArray,
        /** How far through [intended] the typing has reached. */
        val caret: Int,
        /** Characters typed that belong nowhere in the passage. */
        val inserted: Int,
    ) {
        val wrong: Int get() = marks.count { it == Mark.WRONG }

        // Arrays do not compare by value, and a data class with one in it will otherwise claim
        // two identical alignments are different.
        override fun equals(other: Any?): Boolean =
            other is Alignment && marks.contentEquals(other.marks) &&
                caret == other.caret && inserted == other.inserted

        override fun hashCode(): Int =
            (marks.contentHashCode() * 31 + caret) * 31 + inserted
    }

    fun alignment(): Alignment {
        val want = intended
        val got = out.toString()
        val n = want.length
        val m = got.length
        val marks = IntArray(n) { Mark.UNTYPED }
        if (m == 0) return Alignment(marks, 0, 0)

        // Cost of turning the first i of the passage into the first j of what was typed.
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val swap = d[i - 1][j - 1] + if (want[i - 1] == got[j - 1]) 0 else 1
                val skip = d[i - 1][j] + 1
                val extra = d[i][j - 1] + 1
                d[i][j] = minOf(swap, skip, extra)
            }
        }

        // Align what was typed against a *prefix* of the passage, not against all of it. The
        // passage carries on past where the typist has got to, and those characters are not
        // mistakes, they are the future -- so the run of them at the end costs nothing and the
        // alignment ends wherever it is cheapest to stop.
        //
        // Getting this wrong is not subtle. Charging for them makes every incomplete passage
        // equally expensive to align anywhere, which leaves the table full of ties, and the
        // backtrace then picks one at random: nine characters into a hundred-and-sixty-character
        // passage the caret was reported at 161 of 161.
        // Ties go to the reading that has got *further* through the passage. "ok thn" against
        // "ok then" costs one either way -- a dropped `e`, or an `e` mistyped as `n` with the `n`
        // still to come -- and the first is both the truer account and the one that leaves the
        // caret where the thumb actually is.
        var best = 0
        for (i in 1..n) if (d[i][m] <= d[best][m]) best = i

        var i = best
        var j = m
        val caret = best
        var inserted = 0
        while (i > 0 || j > 0) {
            val swap = if (i > 0 && j > 0) {
                d[i - 1][j - 1] + if (want[i - 1] == got[j - 1]) 0 else 1
            } else {
                Int.MAX_VALUE
            }
            when {
                i > 0 && j > 0 && d[i][j] == swap -> {
                    marks[i - 1] = if (want[i - 1] == got[j - 1]) Mark.CORRECT else Mark.WRONG
                    i--
                    j--
                }
                // A character of the passage that was never typed. Left UNTYPED rather than
                // marked wrong: it is not a mistake yet, it is somewhere the thumb has not been.
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> i--
                else -> {
                    inserted++
                    j--
                }
            }
        }
        return Alignment(marks, caret, inserted)
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
