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

    private val LAYOUTS = listOf(
        IosLayouts.QWERTY_LOWER,
        IosLayouts.QWERTY_UPPER,
        IosLayouts.NUMBERS,
        IosLayouts.SYMBOLS,
    )

    fun layoutFor(id: String): Layout? = LAYOUTS.firstOrNull { it.id == id }

    /**
     * The verdict [config] would reach for this gesture, or null if it was recorded on a layout
     * this build no longer has.
     */
    fun replay(record: GestureRecord, config: GestureConfig): GestureVerdict? {
        val layout = layoutFor(record.trace.layoutId) ?: return null
        val geometry = LayoutGeometry(layout, record.trace.widthPx)
        val fsm = TouchFsm(geometry, config)
        val path = record.path
        val down = path.firstOrNull() ?: return null

        fsm.onDown(down.x, down.y, down.t)
        var longPressFired = false
        val rest = path.drop(1)

        rest.forEachIndexed { i, p ->
            // The host schedules the long-press callback off a clock; here it is inferred from
            // the timestamps, so a gesture that dwelled long enough opens accents in replay
            // exactly as it did on the phone.
            if (!longPressFired && p.t - down.t >= config.longPressMs) {
                fsm.onLongPressTimeout(down.t + config.longPressMs)
                longPressFired = true
            }
            if (i < rest.size - 1) {
                fsm.onMove(p.x, p.y, p.t)
                // GLIDE is a terminal state -- onMove has no transition out of it -- so once it
                // is reached the remaining samples cannot change the answer. Skipping them turns
                // a sweep from quadratic into linear, because every glide move copies the whole
                // path so far for the trail overlay. `glide is terminal` in TouchFsmTest guards
                // this shortcut.
                if (fsm.state == GestureState.GLIDE) return GestureVerdict.GLIDE
            }
        }

        val last = rest.lastOrNull() ?: down
        return fsm.onUp(last.x, last.y, last.t)
            .filterIsInstance<GestureOutput.GestureCaptured>()
            .firstOrNull()
            ?.trace
            ?.verdict
    }

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

    fun load(file: File): List<GestureRecord> =
        file.readLines().mapNotNull { line ->
            if (line.isBlank()) null else GestureRecordCodec.decode(line)
        }

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
         * A drill session is never evenly split -- the catalogue has more flicks in it than
         * words, because more keys have a symbol than have a word hanging below them. Plain
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

    fun score(records: List<GestureRecord>, config: GestureConfig): Score {
        val correct = mutableMapOf<GestureIntent, Int>()
        val total = mutableMapOf<GestureIntent, Int>()
        records.forEach { record ->
            total[record.intent] = (total[record.intent] ?: 0) + 1
            if (intentOf(replay(record, config)) == record.intent) {
                correct[record.intent] = (correct[record.intent] ?: 0) + 1
            }
        }
        return Score(correct, total)
    }
}
