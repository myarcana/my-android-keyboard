package com.offlinekeyboard.ime.gesture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scores the collected gesture bank against the flick-versus-glide thresholds, and sweeps for
 * better ones.
 *
 * This is a report, not a gate: it prints and passes, because the bank is data about a person's
 * hand and a build should not fail because that hand changed. The one thing it does assert is
 * that replaying a gesture reproduces what the phone decided at the time -- if that ever stops
 * being true, the harness is measuring something other than the shipping keyboard and every
 * number below it is fiction.
 *
 *     tools/gestures.sh analyse
 */
class GestureBankReplayTest {

    private val defaults = GestureConfig()

    @Test
    fun `replay the bank and sweep the thresholds`() {
        val file = GestureReplay.findBank()
        if (file == null) {
            println(
                "\nNo gesture bank yet. Collect some with the Gesture Lab app, then\n" +
                    "  tools/gestures.sh pull\n" +
                    "and run this again.\n",
            )
            return
        }

        val records = GestureReplay.load(file)
        if (records.isEmpty()) {
            println("\nGesture bank at $file is empty.\n")
            return
        }

        println("\n" + "=".repeat(78))
        println("Gesture bank: ${records.size} samples from $file")
        println("  " + GestureIntent.entries.joinToString("    ") { intent ->
            "$intent ${records.count { it.intent == intent }}"
        })
        println("  keys: " + records.groupingBy { it.trace.startKeyId }.eachCount()
            .toList().sortedByDescending { it.second }
            .joinToString(" ") { "${it.first}=${it.second}" })

        // Checked here but asserted at the very end: if the harness has drifted, the report
        // below is the thing that shows how, and aborting before printing it would hide that.
        val harness = checkReplayMatchesTheDevice(records)
        reportPerKey(records)

        val base = GestureReplay.score(records, defaults)
        println()
        println("Current thresholds:")
        println("  " + describe(defaults))
        println("  " + describe(base))

        val best = sweep(records)
        println()
        println("Best of ${best.evaluated} candidate threshold sets:")
        println("  " + describe(best.config))
        println("  " + describe(best.score))
        println("  gain: %+.1f points of balanced accuracy"
            .format((best.score.balanced - base.balanced) * 100))
        reportPlateau(best.tied)

        sensitivity(records, best.config)
        listFailures(records, best.config)
        println("=".repeat(78) + "\n")

        assertTrue(harness.message, harness.ok)
    }

    private data class Harness(val ok: Boolean, val message: String)

    /**
     * The harness check. Every record carries the thresholds that were live when it was made, so
     * a replay under those same thresholds must land on the same verdict the phone reached.
     */
    private fun checkReplayMatchesTheDevice(records: List<GestureRecord>): Harness {
        val mismatches = records.mapNotNull { record ->
            val config = defaults.copy(
                flickDistanceRatio = record.trace.thresholds.flickDistanceRatio,
                verticalDominance = record.trace.thresholds.verticalDominance,
                glideDistanceRatio = record.trace.thresholds.glideDistanceRatio,
                flickToGlideRatio = record.trace.thresholds.flickToGlideRatio,
            )
            val replayed = GestureReplay.replay(record, config)
            if (replayed == record.trace.verdict) null else Triple(record, record.trace.verdict, replayed)
        }
        if (mismatches.isEmpty()) {
            println("  replay agrees with the device on all ${records.size} samples")
            return Harness(true, "")
        }
        println("  REPLAY DISAGREES with the device on ${mismatches.size} samples:")
        println("    (a gesture sitting exactly on a threshold can disagree through the 0.1px")
        println("     coordinate rounding alone -- check the numbers before assuming drift)")
        mismatches.take(10).forEach { (record, onDevice, replayed) ->
            println("    ${record.id}  ${record.promptId}  device=$onDevice replay=$replayed")
        }
        val rate = mismatches.size.toFloat() / records.size
        return Harness(
            ok = rate <= 0.02f,
            message = "Replay reproduced only ${"%.0f".format((1 - rate) * 100)}% of the " +
                "verdicts the device recorded; the harness no longer matches the keyboard, so " +
                "the sweep above is measuring something else.",
        )
    }

    /** Where the collisions actually are, which is the part worth reading before tuning. */
    private fun reportPerKey(records: List<GestureRecord>) {
        println()
        println("  key  intent  n   read as (current build)")
        records.groupBy { it.trace.startKeyId to it.intent }
            .toList()
            .sortedBy { it.first.first }
            .forEach { (group, rows) ->
                val (key, intent) = group
                val reads = rows.groupingBy { it.trace.verdict.name }.eachCount()
                    .toList().sortedByDescending { it.second }
                    .joinToString(" ") { "${it.first}=${it.second}" }
                println("  %-4s %-7s %-3d %s".format(key, intent.name.lowercase(), rows.size, reads))
            }
    }

    // --- sweep ------------------------------------------------------------------------------

    private data class Best(
        val config: GestureConfig,
        val score: GestureReplay.Score,
        val evaluated: Int,
        /** Every threshold set that scored exactly as well, which is what the plateau is. */
        val tied: List<GestureConfig>,
    )

    /**
     * The flick threshold, as a fraction of key height.
     *
     * The floor is not arbitrary and is not a grid artifact: Android's own touch slop is 8dp,
     * and a key is 40dp tall, so 0.20 *is* touch slop. Below that the platform still considers
     * the finger stationary, and a keyboard that has already decided you flicked while the OS
     * says you have not yet moved is not tunable, it is broken.
     *
     * This matters because the drilled taps do not push back as hard as real typing does. A tap
     * made while a drill is watching is a careful tap; the ones that will actually collide with
     * this threshold are the sloppy ones in the middle of a sentence, and no bank collected by
     * asking gets those. The platform's number stands in for the evidence that is hard to
     * collect, which is exactly what a principled bound is for.
     */
    private val flickGrid = steps(0.20f, 0.85f, 0.05f)
    /**
     * How much more vertical than horizontal a flick has to be.
     *
     * The upper bound was 3.0 and that was too low to contain the answer. On the o key the
     * flicks run from 6.6 to 74, and "ok" -- which leaves for a key half a width to the left --
     * runs 1.6 to 4.3, so the separating value is somewhere around 5 and the sweep could not
     * reach it. There is no platform limit to appeal to here, so the grid simply has to be wide
     * enough that the data, and not the grid, picks the number.
     */
    private val dominanceGrid = steps(0.75f, 9.0f, 0.25f)
    private val glideGrid = steps(0.8f, 2.6f, 0.2f)
    private val promoteGrid = steps(1.2f, 4.2f, 0.2f)

    private fun sweep(records: List<GestureRecord>): Best {
        var bestScore = GestureReplay.score(records, defaults)
        var winners = mutableListOf(defaults)
        var evaluated = 0

        for (flick in flickGrid) {
            for (dominance in dominanceGrid) {
                for (glide in glideGrid) {
                    for (promote in promoteGrid) {
                        // A flick already under way should need *more* travel to become a glide
                        // than a plain press does; the other way round the promotion rule could
                        // never fire, and the search would waste most of its time there.
                        if (promote < glide) continue
                        val config = defaults.copy(
                            flickDistanceRatio = flick,
                            verticalDominance = dominance,
                            glideDistanceRatio = glide,
                            flickToGlideRatio = promote,
                        )
                        val score = GestureReplay.score(records, config)
                        evaluated++
                        when {
                            isBetter(score, bestScore) -> {
                                bestScore = score
                                winners = mutableListOf(config)
                            }
                            isTied(score, bestScore) -> winners += config
                        }
                    }
                }
            }
        }
        return Best(mostCentral(winners), bestScore, evaluated, winners)
    }

    /**
     * Picks the middle of the winning plateau rather than the first point on it.
     *
     * On a bank of any realistic size hundreds of threshold sets score identically, and they are
     * not equally good: one sitting on the edge of that region is one unusual swipe away from
     * being wrong, while one in the middle has room on every side. Taking whichever the loops
     * reached first would be choosing by iteration order, which is choosing by accident.
     */
    private fun mostCentral(winners: List<GestureConfig>): GestureConfig {
        if (winners.size == 1) return winners.single()
        val axes = listOf<Pair<(GestureConfig) -> Float, List<Float>>>(
            { c: GestureConfig -> c.flickDistanceRatio } to flickGrid,
            { c: GestureConfig -> c.verticalDominance } to dominanceGrid,
            { c: GestureConfig -> c.glideDistanceRatio } to glideGrid,
            { c: GestureConfig -> c.flickToGlideRatio } to promoteGrid,
        )
        // Each axis is normalised by its own span first, or the parameter with the largest
        // numbers would decide what "central" means for all of them.
        val centre = axes.map { (get, _) -> winners.map(get).average().toFloat() }
        return winners.minByOrNull { config ->
            axes.withIndex().sumOf { (i, axis) ->
                val (get, grid) = axis
                val span = (grid.last() - grid.first()).takeIf { it > 0f } ?: 1f
                val d = (get(config) - centre[i]) / span
                (d * d).toDouble()
            }
        }!!
    }

    /**
     * The room a threshold has before the score changes: the useful half of a sweep result.
     *
     * It also calls out any axis whose winning range runs into the end of its own grid. That is
     * not a result, it is a boundary -- the data was still asking for more when the search
     * stopped -- and it is easy to read straight past. This report has already hidden the answer
     * twice that way: once on the flick distance, once on the vertical dominance, where the true
     * separating value sat at about 5 and the grid stopped at 3.
     */
    private fun reportPlateau(tied: List<GestureConfig>) {
        if (tied.size <= 1) return
        println("  ${tied.size} threshold sets score identically. Room on each axis:")
        val warnings = mutableListOf<String>()
        fun span(name: String, grid: List<Float>, get: (GestureConfig) -> Float) {
            val values = tied.map(get)
            val lo = values.min()
            val hi = values.max()
            val edge = when {
                lo <= grid.first() && hi >= grid.last() -> " <- spans the whole grid: no signal"
                lo <= grid.first() -> " <- AT THE BOTTOM OF THE GRID"
                hi >= grid.last() -> " <- AT THE TOP OF THE GRID"
                else -> ""
            }
            if (edge.isNotEmpty()) warnings += name
            println("    %-20s %.2f .. %.2f%s".format(name, lo, hi, edge))
        }
        span("flickDistanceRatio", flickGrid) { it.flickDistanceRatio }
        span("verticalDominance", dominanceGrid) { it.verticalDominance }
        span("glideDistanceRatio", glideGrid) { it.glideDistanceRatio }
        span("flickToGlideRatio", promoteGrid) { it.flickToGlideRatio }
        if (warnings.isNotEmpty()) {
            println()
            println("  The winning range reaches the end of the grid for: ${warnings.joinToString()}.")
            println("  Widen that grid and re-run, or justify the bound -- a sweep that stops at")
            println("  its own edge has found the edge and not an optimum.")
        }
    }

    /**
     * Balanced accuracy first, then raw correct count as a tie-break. Both matter: balanced
     * accuracy is the goal, but between two sets that reach it, the one that also gets more
     * individual gestures right is the better bet on the next hand that uses this keyboard.
     */
    private fun isBetter(a: GestureReplay.Score, b: GestureReplay.Score): Boolean = when {
        a.balanced > b.balanced + 1e-6f -> true
        a.balanced < b.balanced - 1e-6f -> false
        else -> a.correctCount > b.correctCount
    }

    private fun isTied(a: GestureReplay.Score, b: GestureReplay.Score): Boolean =
        kotlin.math.abs(a.balanced - b.balanced) <= 1e-6f && a.correctCount == b.correctCount

    /**
     * How much each threshold matters, one at a time around the winner. A parameter whose column
     * is flat is not doing any work and should be left where it is rather than moved to a number
     * the sweep picked arbitrarily.
     */
    private fun sensitivity(records: List<GestureRecord>, best: GestureConfig) {
        println()
        println("One at a time around the best set (balanced accuracy):")
        fun row(name: String, values: List<Float>, apply: (Float) -> GestureConfig) {
            val cells = values.joinToString("  ") { v ->
                "%.2f:%.0f%%".format(v, GestureReplay.score(records, apply(v)).balanced * 100)
            }
            println("  %-20s %s".format(name, cells))
        }
        row("flickDistanceRatio", steps(0.20f, 0.85f, 0.1f)) { best.copy(flickDistanceRatio = it) }
        row("verticalDominance", steps(0.75f, 9.0f, 1.0f)) { best.copy(verticalDominance = it) }
        row("glideDistanceRatio", steps(0.8f, 2.6f, 0.3f)) { best.copy(glideDistanceRatio = it) }
        row("flickToGlideRatio", steps(1.2f, 4.2f, 0.5f)) { best.copy(flickToGlideRatio = it) }
    }

    /** The gestures the best thresholds still get wrong: the next thing to think about. */
    private fun listFailures(records: List<GestureRecord>, config: GestureConfig) {
        val failures = records.mapNotNull { record ->
            val read = GestureReplay.replay(record, config)
            if (GestureReplay.intentOf(read) == record.intent) null else record to read
        }
        println()
        if (failures.isEmpty()) {
            println("Nothing misread under the best thresholds.")
            return
        }
        println("Still misread (${failures.size}):")
        println("  id        prompt              wanted  read    len    dy/dx   ms")
        failures.take(30).forEach { (record, read) ->
            val a = record.path.first()
            val b = record.path.last()
            val dx = b.x - a.x
            val dy = b.y - a.y
            println(
                "  %-9s %-19s %-7s %-7s %-6.0f %-7s %d".format(
                    record.id,
                    record.promptId,
                    record.intent.name.lowercase(),
                    (read?.name ?: "none").lowercase(),
                    record.pathLength,
                    if (dx == 0f) "inf" else "%.1f".format(dy / kotlin.math.abs(dx)),
                    record.durationMs,
                ),
            )
        }
    }

    // --- formatting -------------------------------------------------------------------------

    private fun describe(c: GestureConfig) =
        "flickDistanceRatio=%.2f verticalDominance=%.2f glideDistanceRatio=%.2f flickToGlideRatio=%.2f"
            .format(c.flickDistanceRatio, c.verticalDominance, c.glideDistanceRatio, c.flickToGlideRatio)

    private fun describe(s: GestureReplay.Score) =
        s.labels.joinToString("   ") { intent ->
            "%s %d/%d (%.0f%%)".format(
                intent.name.lowercase(),
                s.correct[intent] ?: 0,
                s.total[intent] ?: 0,
                s.recall(intent) * 100,
            )
        } + "   balanced %.1f%%".format(s.balanced * 100)

    private fun steps(from: Float, to: Float, step: Float): List<Float> =
        generateSequence(from) { it + step }
            .takeWhile { it <= to + step / 2f }
            .map { (it * 1000).toInt() / 1000f }
            .toList()
}
