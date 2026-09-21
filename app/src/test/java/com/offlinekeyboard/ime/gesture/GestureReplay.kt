package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.Layout
import com.offlinekeyboard.ime.layout.LayoutGeometry
import java.io.File

/**
 * Runs a recorded gesture back through the real state machine.
 *
 * This is the whole reason the bank stores raw paths and the geometry they were made on, rather
 * than storing features someone thought were relevant at the time. Any change to the heuristic
 * can be scored against every gesture ever collected, on a laptop, in seconds -- instead of by
 * flashing a build and swiping until it feels better, which is how a threshold ends up tuned to
 * the last twenty minutes of somebody's thumb.
 */
object GestureReplay {

    fun layoutFor(id: String): Layout? = IosLayouts.byId(id)

    /**
     * A prior with every weight at zero: the state machine as it behaved before [FlickPrior]
     * existed.
     *
     * Needed because the bank is a record of what *older builds* decided, and the harness check
     * holds replay to exactly that. A recorded path carries no editor context and never could --
     * the caret it was made at is not in the file -- so the only honest way to reproduce an old
     * verdict is to replay it under a prior that has no opinion at all. Scoring the same bank
     * with [FlickPrior] then measures the change, which is the comparison the report wants.
     */
    val NO_PRIOR = FlickPrior(
        FlickPrior.Weights(
            noRoomBelow = 0f,
            roomBelow = 0f,
            midWord = 0f,
            afterDigit = 0f,
            boundaryDigit = 0f,
            wordInternalSymbol = 0f,
        ),
    )

    /**
     * The verdict [config] would reach for this gesture, or null if it was recorded on a layout
     * this build no longer has.
     *
     * [prior] defaults to [NO_PRIOR] rather than to the shipping one, and the asymmetry is
     * deliberate: every existing caller is asking "what did the phone decide", which is a
     * question about a build that had no prior. A caller that wants to know what the *current*
     * keyboard would decide passes one in and says so.
     */
    fun replay(
        record: GestureRecord,
        config: GestureConfig,
        prior: FlickPrior = NO_PRIOR,
    ): GestureVerdict? {
        val layout = layoutFor(record.trace.layoutId) ?: return null
        val geometry = LayoutGeometry(layout, record.trace.widthPx)
        // No context: a recorded path has no editor behind it. See FlickPrior.Context.UNKNOWN,
        // which is exactly zero for that reason.
        val fsm = TouchFsm(geometry, config, prior)
        val path = record.path
        val down = path.firstOrNull() ?: return null
        val boundaries = record.trace.strokeStarts.toSet()

        fsm.onDown(down.x, down.y, down.t)
        var longPressFired = false

        for (i in 1 until path.size) {
            val p = path[i]
            // The host schedules the long-press callback off a clock; here it is inferred from
            // the timestamps, so a gesture that dwelled long enough opens accents in replay
            // exactly as it did on the phone.
            if (!longPressFired && p.t - down.t >= config.longPressMs) {
                fsm.onLongPressTimeout(down.t + config.longPressMs)
                longPressFired = true
            }
            if (i in boundaries) {
                // A recorded finger lift. Whether it ends the gesture is the question the resume
                // thresholds answer, and it is asked here rather than assumed -- which is what
                // makes a bank recorded under one resume window scorable under another.
                val lifted = path[i - 1]
                fsm.onUp(lifted.x, lifted.y, lifted.t)
                if (fsm.canResume(p.x, p.y, p.t)) {
                    fsm.onResume(p.x, p.y, p.t)
                    continue
                }
                return verdictOf(fsm.onGlideResumeTimeout())
            }
            if (i < path.size - 1) {
                fsm.onMove(p.x, p.y, p.t)
                // GLIDE cannot be left by moving, so once it is reached and there are no more
                // lifts to come, the remaining samples cannot change the answer. Skipping them
                // turns a sweep from quadratic into linear, because every glide move copies the
                // whole path so far for the trail overlay. `glide is terminal` in
                // GestureCaptureTest guards this shortcut.
                if (fsm.state == GestureState.GLIDE && boundaries.none { it > i }) {
                    return GestureVerdict.GLIDE
                }
            }
        }

        val last = path.last()
        val out = fsm.onUp(last.x, last.y, last.t)
        if (fsm.isSuspended) return verdictOf(fsm.onGlideResumeTimeout())
        return verdictOf(out)
    }

    private fun verdictOf(outputs: List<GestureOutput>): GestureVerdict? = outputs
        .filterIsInstance<GestureOutput.GestureCaptured>()
        .firstOrNull()
        ?.trace
        ?.verdict

    /** The intent a verdict amounts to, or null when the gesture typed none of the three. */
    fun intentOf(verdict: GestureVerdict?): GestureIntent? = when (verdict) {
        GestureVerdict.FLICK -> GestureIntent.SYMBOL
        GestureVerdict.GLIDE -> GestureIntent.WORD
        GestureVerdict.TAP -> GestureIntent.LETTER
        else -> null
    }

    // --- loading ----------------------------------------------------------------------------

    /**
     * Finds the bank. Unit tests run with the module directory as the working directory, and the
     * pulled bank lives at the repo root, so the search walks upward rather than hard-coding
     * either. `-Dgesture.bank=...` overrides it for a one-off file.
     */
    fun findBank(): File? {
        System.getProperty("gesture.bank")?.takeIf { it.isNotBlank() }?.let { path ->
            return File(path).takeIf { it.isFile }
        }
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val candidate = dir?.resolve("data/gesture-bank.jsonl")
            if (candidate != null && candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    /**
     * Every gesture in the file, with its session's thresholds attached.
     *
     * Since v6 the thresholds live once on the session line rather than on all two hundred of
     * its gestures, so reading a bank means joining the two back together. A gesture whose
     * session line never landed -- a run whose opening write was lost -- keeps a null and is
     * skipped by anything that needs to know what the build was doing.
     */
    fun load(file: File): List<GestureRecord> {
        val lines = file.readLines().filter { it.isNotBlank() }
        val sessions = lines.mapNotNull(GestureSessionCodec::decode).associateBy { it.id }
        return lines.mapNotNull { GestureRecordCodec.decode(it) }.map { record ->
            if (record.trace.thresholds != null) record
            else {
                val from = sessions[record.sessionId]?.thresholds ?: return@map record
                record.copy(trace = record.trace.copy(thresholds = from))
            }
        }
    }

    /** The sessions in the file, which is where the passage and the thresholds live. */
    fun loadSessions(file: File): List<GestureSession> =
        file.readLines().filter { it.isNotBlank() }.mapNotNull(GestureSessionCodec::decode)

    // --- scoring ----------------------------------------------------------------------------

    data class Score(
        val correct: Map<GestureIntent, Int>,
        val total: Map<GestureIntent, Int>,
    ) {
        fun recall(intent: GestureIntent): Float {
            val n = total[intent] ?: 0
            return if (n == 0) 0f else (correct[intent] ?: 0).toFloat() / n
        }

        /** Labels this bank actually contains, so an unrecorded one cannot drag the mean down. */
        val labels: List<GestureIntent> get() = GestureIntent.entries.filter { (total[it] ?: 0) > 0 }

        /**
         * The mean of the per-label recalls, not plain accuracy.
         *
         * A session is never evenly split -- the collision passage has more flicks in it than
         * words, because more keys have a symbol than have a word hanging below them, and prose
         * is almost all glides. Plain
         * accuracy on that rewards a heuristic which simply favours whichever label is commoner.
         * Averaging the recalls makes every kind of mistake cost the same, which is the actual
         * requirement: "my symbol turned into a word", "my word turned into a symbol" and "my
         * tap turned into a symbol" are all equally unacceptable.
         */
        val balanced: Float
            get() = labels.takeIf { it.isNotEmpty() }?.map { recall(it) }?.average()?.toFloat() ?: 0f

        val totalCount get() = total.values.sum()
        val correctCount get() = correct.values.sum()
    }

    /**
     * The gestures that can honestly be scored **for flick-versus-glide**: the ones somebody was
     * *told* to make.
     *
     * This is not a filter on what is useful, only on what this one question can use. The taps it
     * discards are the whole input to `tools/fit_spatial.py`, and the glides it discards are what
     * decoding accuracy is measured on -- both read the bank without needing a label, which is
     * why they can use sessions this function returns nothing from.
     *
     * Flick-versus-glide is the one question the touch data cannot answer about itself, so the
     * only ground truth that exists for it is an instruction given before the gesture. The
     * collision drill gives one -- "flick u for 7" -- and prose does not: a prose passage says
     * which word is due, not whether the thumb will glide it or tap it out, and the two produce
     * different gestures that are both correct.
     *
     * Recorded before v6, this label sits on the line. Recorded since, it does not exist and is
     * not invented. Scoring prose against it is what produced a bank of 2666 samples reporting
     * "word 103/2079 (5%)" -- 1972 of that class being prose words tapped out one letter at a
     * time, read correctly as taps, and counted as failures. The sweep dutifully optimised
     * against it and recommended quadrupling the flick threshold.
     */
    fun scorable(records: List<GestureRecord>): List<GestureRecord> =
        records.filter { it.voidReason == null && it.legacy?.promptId?.startsWith("word:") == false }

    fun score(
        records: List<GestureRecord>,
        config: GestureConfig,
        prior: FlickPrior = NO_PRIOR,
    ): Score {
        val correct = mutableMapOf<GestureIntent, Int>()
        val total = mutableMapOf<GestureIntent, Int>()
        scorable(records).forEach { record ->
            val intent = record.legacy?.intent ?: return@forEach
            total[intent] = (total[intent] ?: 0) + 1
            if (intentOf(replay(record, config, prior)) == intent) {
                correct[intent] = (correct[intent] ?: 0) + 1
            }
        }
        return Score(correct, total)
    }
}
