package com.offlinekeyboard.ime.gesture

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scores the collected gesture bank against the flick-versus-glide thresholds, and sweeps for
 * better ones.
 *
 * This is one reader of the bank, not the reader. The lab collects for the whole typing
 * experience -- the tap decoder's spatial model, glide decoding and the resume window are all
 * fitted from the same file -- and these four thresholds are simply the part that needs a
 * replay of the state machine to score. `tools/fit_spatial.py` is the other established
 * consumer, and it reads the taps this test sets aside. A saturated sweep here says nothing
 * about whether the bank still has something to give.
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

        val all = GestureReplay.load(file)
        if (all.isEmpty()) {
            println("\nGesture bank at $file is empty.\n")
            return
        }

        // A withdrawn sample keeps its line in the file but is not evidence of its label, so it
        // is set aside here instead of scored. Listed rather than dropped in silence: a report
        // that quietly measures fewer gestures than the file holds is one nobody can check.
        val (withdrawn, records) = all.partition { it.voidReason != null }
        if (records.isEmpty()) {
            println("\nEvery line in $file has been withdrawn. Nothing to score.\n")
            return
        }

        val scorable = GestureReplay.scorable(records)
        println("\n" + "=".repeat(78))
        println("Gesture bank: ${records.size} samples from $file")
        // Only gestures somebody was *told* to make can be scored for flick-versus-glide, and
        // saying so out loud is the point: the previous report scored all of them, so 1972 prose
        // words tapped out one letter at a time counted as failed glides and the sweep spent two
        // thirds of its objective on a class that could not be got right.
        println("  ${scorable.size} were asked for by name and can be scored; " +
            "${records.size - scorable.size} are typing, kept for everything else")
        println("  " + GestureIntent.entries.joinToString("    ") { intent ->
            "$intent ${scorable.count { it.legacy?.intent == intent }}"
        })
        println("  keys: " + records.groupingBy { it.trace.startKeyId }.eachCount()
            .toList().sortedByDescending { it.second }
            .joinToString(" ") { "${it.first}=${it.second}" })
        if (withdrawn.isNotEmpty()) {
            println("  ${withdrawn.size} withdrawn, kept in the file but not scored:")
            withdrawn.forEach { println("    ${it.id}  ${it.label()}  ${it.voidReason}") }
        }

        // Checked here but asserted at the very end: if the harness has drifted, the report
        // below is the thing that shows how, and aborting before printing it would hide that.
        val harness = checkReplayMatchesTheDevice(records)
        reportPerKey(records)
        reportGlides(records)

        val base = GestureReplay.score(records, defaults)
        println()
        println("Current thresholds:")
        println("  " + describe(defaults))
        println("  " + describe(base))

        reportFlickPrior(records, base)
        reportFlickCone(records)

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
        // A pre-v6 line wrote down the verdict the phone reached, so replay can be held to it
        // exactly. A v6 line does not, and is checked against the thing it does carry: the text
        // that actually reached the field. That is the weaker test and the more honest one --
        // it asks whether the whole path from touch to characters still behaves, rather than
        // whether an intermediate label matches an intermediate label.
        val checkable = records.filter { it.trace.thresholds != null }
        // Every threshold the record says was live, including the flick cone -- which a record
        // made before the cone existed says was off. Copying only the four original numbers onto
        // today's defaults would replay every old gesture under a cone no phone ever ran.
        fun configFor(record: GestureRecord) = record.trace.thresholds!!.applyTo(defaults)
        val exact = checkable.filter { it.trace.verdict != null }
        val mismatches = exact.mapNotNull { record ->
            val replayed = GestureReplay.replay(record, configFor(record))
            if (replayed == record.trace.verdict) null
            else Triple(record, record.trace.verdict, replayed)
        }
        val unchecked = records.size - exact.size
        if (unchecked > 0) {
            println("  $unchecked samples carry no recorded verdict (v6 and later); " +
                "they are checked against what they typed instead")
        }
        if (mismatches.isEmpty()) {
            println("  replay agrees with the device on all ${exact.size} samples that say")
            return Harness(true, "")
        }
        println("  REPLAY DISAGREES with the device on ${mismatches.size} of ${exact.size}:")
        println("    (a gesture sitting exactly on a threshold can disagree through the 0.1px")
        println("     coordinate rounding alone -- check the numbers before assuming drift)")
        mismatches.take(10).forEach { (record, onDevice, replayed) ->
            println("    ${record.id}  ${record.label()}  device=$onDevice replay=$replayed")
        }
        val rate = mismatches.size.toFloat() / exact.size.coerceAtLeast(1)
        return Harness(
            ok = rate <= 0.02f,
            message = "Replay reproduced only ${"%.0f".format((1 - rate) * 100)}% of the " +
                "verdicts the device recorded; the harness no longer matches the keyboard, so " +
                "the sweep above is measuring something else.",
        )
    }

    /** What a record can be called in a report: its old prompt, or the text it produced. */
    private fun GestureRecord.label(): String =
        legacy?.promptId ?: sessionId?.let { "$it#$seq" } ?: id

    /**
     * What [FlickPrior] is worth, on the same bank and the same thresholds.
     *
     * The comparison is deliberately one-sided in what it can show. A replayed path carries no
     * editor context -- the caret it was typed at is simply not in the file -- so only the
     * *layout* half of the prior can be scored here: how much room there is below the key. The
     * contextual half, which is the larger idea, is invisible to this bank and will stay
     * invisible until a session is collected that records what the caret was sitting after.
     *
     * Saying that out loud in the report matters more than the number does. A reader who sees a
     * gain here and assumes it covers the mid-word rule would be drawing a conclusion the data
     * cannot support, which is the exact failure `docs/GESTURE_BANK.md` describes version 6 as
     * having been built to stop.
     */
    private fun reportFlickPrior(records: List<GestureRecord>, base: GestureReplay.Score) {
        val withPrior = GestureReplay.score(records, defaults, FlickPrior())
        println()
        println("With FlickPrior (shipped weights; the bank records no caret, so only the")
        println("layout terms can move anything here -- and they ship at zero):")
        println("  " + describe(withPrior))
        println("  change: %+.1f points of balanced accuracy"
            .format((withPrior.balanced - base.balanced) * 100))

        // Which keys moved, and in which direction. A single total can hide a term that fixes
        // one key by breaking another, and on a bank this small that is a real possibility
        // rather than a hypothetical one.
        val moved = GestureReplay.scorable(records).mapNotNull { record ->
            val intent = record.legacy?.intent ?: return@mapNotNull null
            val before = GestureReplay.intentOf(
                GestureReplay.replay(record, defaults, GestureReplay.NO_PRIOR),
            )
            val after = GestureReplay.intentOf(
                GestureReplay.replay(record, defaults, FlickPrior()),
            )
            if (before == after) null else Triple(record, before, after)
        }
        if (moved.isEmpty()) {
            println("  no recorded gesture changes verdict")
            return
        }
        val fixed = moved.count { (r, _, after) -> after == r.legacy?.intent }
        val broken = moved.count { (r, before, _) -> before == r.legacy?.intent }
        println("  ${moved.size} gestures change verdict: $fixed newly correct, $broken newly wrong")
        moved.groupBy { (r, _, _) -> r.trace.startKeyId }.toSortedMap().forEach { (key, rows) ->
            println("    $key: " + rows.joinToString(", ") { (r, before, after) ->
                "${r.legacy?.intent?.name?.lowercase()} $before->$after"
            })
        }
    }

    /**
     * What the per-key flick cone changes, measured over *every* gesture whose outcome is known.
     *
     * Not only the drill. The cone is about keys like `h` and the bottom row, and the drill has
     * almost no flicks from those. Ordinary typing is where the risk sits: a tap that rolled
     * downward and would now read as a symbol, or a glide that now would not start. For a v6
     * line the evidence is what it typed. A lone letter is a tap, the key's secondary is a flick,
     * and a word of two or more letters is a glide. Anything else is not counted.
     */
    private fun reportFlickCone(records: List<GestureRecord>) {
        val off = defaults.copy(flickConeMaxDegrees = 0f)
        val moved = records.mapNotNull { record ->
            val wanted = outcomeOf(record) ?: return@mapNotNull null
            val before = GestureReplay.intentOf(GestureReplay.replay(record, off))
            val after = GestureReplay.intentOf(GestureReplay.replay(record, defaults))
            if (before == after) null else Triple(record, wanted, before to after)
        }
        println()
        println("With the per-key flick cone (FlickCone), over every gesture whose outcome is known:")
        if (moved.isEmpty()) {
            println("  no recorded gesture changes verdict")
            return
        }
        val fixed = moved.count { (_, wanted, change) -> change.second == wanted }
        val broken = moved.count { (_, wanted, change) -> change.first == wanted }
        println("  ${moved.size} gestures change verdict: $fixed newly correct, $broken newly wrong")
        moved.forEach { (r, wanted, change) ->
            println("    ${r.trace.startKeyId}  ${r.label()}  wanted $wanted: " +
                "${change.first} -> ${change.second}")
        }
    }

    /** What a gesture was for, from its label or from what it typed. Null when unknowable. */
    private fun outcomeOf(record: GestureRecord): GestureIntent? {
        if (record.voidReason != null) return null
        record.legacy?.let { legacy ->
            return if (legacy.promptId.startsWith("word:")) null else legacy.intent
        }
        val key = GestureReplay.layoutFor(record.trace.layoutId)?.rows
            ?.flatMap { it.keys }?.firstOrNull { it.id == record.trace.startKeyId } ?: return null
        val typed = record.typed.trim()
        return when {
            key.secondary != null && record.typed == key.secondary -> GestureIntent.SYMBOL
            typed.equals(key.primary, ignoreCase = true) -> GestureIntent.LETTER
            typed.length >= 2 && typed.all { it.isLetter() || it == '\'' } -> GestureIntent.WORD
            else -> null
        }
    }

    /** Where the collisions actually are, which is the part worth reading before tuning. */
    private fun reportPerKey(records: List<GestureRecord>) {
        println()
        println("  key  intent  n   read as (current build)")
        GestureReplay.scorable(records)
            .mapNotNull { r -> r.legacy?.intent?.let { r to it } }
            .groupBy { (r, intent) -> r.trace.startKeyId to intent }
            .mapValues { (_, pairs) -> pairs.map { it.first } }
            .toList()
            .sortedBy { it.first.first }
            .forEach { (group, rows) ->
                val (key, intent) = group
                // What this build makes of them now, not what some earlier one did. The stored
                // verdict is gone from new lines and was only ever a snapshot of a build anyway.
                val reads = rows.mapNotNull { GestureReplay.replay(it, defaults)?.name }
                    .groupingBy { it }.eachCount()
                    .toList().sortedByDescending { it.second }
                    .joinToString(" ") { "${it.first}=${it.second}" }
                println("  %-4s %-7s %-3d %s".format(key, intent.name.lowercase(), rows.size, reads))
            }
    }

    /**
     * What the glide passages collected: how well words decoded, and what the finger lifts in
     * the middle of them actually looked like.
     *
     * The second half is the whole reason strokes are recorded. The resume window cannot be
     * derived -- how long a thumb is off the glass when it skips is a fact about a hand and a
     * screen, not something to be reasoned out -- so it is measured here and set from what comes
     * back. The number to read is not the mean: it is the largest gap that was a genuine skip,
     * because the window has to clear that, and the smallest gap between two words deliberately
     * glided in succession, because it must not.
     */
    private fun reportGlides(records: List<GestureRecord>) {
        // Decoding can only be scored where something said which word was wanted, which since v6
        // is nothing: the passage says it, on the session line, and reading it back is an
        // alignment job for whoever wants one rather than a number this report can honestly
        // print. What stays unconditional is the lift distribution underneath, which needs no
        // label at all.
        // Scorable, not every record with a WORD label: a v5 bank filed each tap of a tapped-out
        // prose word under the whole word, so the unfiltered count says 2079 glides in a bank
        // holding 82.
        val glides = GestureReplay.scorable(records).filter { it.legacy?.intent == GestureIntent.WORD }
        val decoded = glides.filter { it.legacy?.decoded != null }
        println()
        if (glides.isNotEmpty()) {
            println("  glides: ${glides.size}, ${decoded.size} with a decoded word")
        }
        if (decoded.isNotEmpty()) {
            fun right(rows: List<GestureRecord>) = rows.count { r ->
                r.legacy!!.decoded!!.lowercase().filter(Char::isLetter) ==
                    r.legacy.expected.lowercase().filter(Char::isLetter)
            }
            val whole = right(decoded)
            println("    decoded correctly: $whole of ${decoded.size} " +
                "(${"%.0f%%".format(whole * 100f / decoded.size)})")

            // The comparison the leniency lives or dies by. A glide that was interrupted and
            // rejoined should decode about as well as one that was never interrupted; if it
            // decodes markedly worse, the window is joining things it should not.
            val (interrupted, clean) = decoded.partition { it.gaps.isNotEmpty() }
            if (interrupted.isNotEmpty()) {
                println("      uninterrupted ${right(clean)}/${clean.size}" +
                    "   ·   rejoined after a lift ${right(interrupted)}/${interrupted.size}")
            }
            decoded.filterNot { r ->
                r.legacy!!.decoded!!.lowercase().filter(Char::isLetter) ==
                    r.legacy.expected.lowercase().filter(Char::isLetter)
            }.take(12).forEach {
                println("      wanted ${it.legacy!!.expected}, typed ${it.legacy.decoded}")
            }
            println("      (pre-v6 labels: one uncorrected miss shifted `expected` a word behind")
            println("       for the rest of its session, so some of these are the label, not the")
            println("       decode. Nothing written since v6 can drift this way.)")
        }

        // Every record, not just the labelled ones: a finger lift is an observation.
        val gaps = records.flatMap { it.gaps }
        if (gaps.isEmpty()) {
            println("    no finger lifts recorded mid-glide yet")
            return
        }
        val ms = gaps.map { it.ms }.sorted()
        val px = gaps.map { it.px }.sorted()
        fun <T : Comparable<T>> at(values: List<T>, fraction: Float) =
            values[((values.size - 1) * fraction).toInt()]
        println("    ${gaps.size} mid-glide lifts were rejoined")
        println("      duration ms: min ${ms.first()}  median ${at(ms, 0.5f)}  " +
            "90th ${at(ms, 0.9f)}  max ${ms.last()}   (window ${defaults.glideResumeMs})")
        println("      distance px: min ${"%.0f".format(px.first())}  " +
            "median ${"%.0f".format(at(px, 0.5f))}  90th ${"%.0f".format(at(px, 0.9f))}  " +
            "max ${"%.0f".format(px.last())}")
        // A gap that only just fitted is a gap the next one like it will not.
        val marginal = ms.count { it > defaults.glideResumeMs * 0.8 }
        if (marginal > 0) {
            println("      $marginal of them were within a fifth of the window -- the next " +
                "skip like that splits the word")
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
     * The floor was 0.20 for a long time, on the grounds that it is Android's touch slop and a
     * keyboard deciding you flicked while the platform still calls the finger stationary is
     * broken rather than badly tuned. It held up until the bank collected flicks of 15.1px and
     * 23.0px, which fell underneath it and were read as taps, and until it became clear that
     * slop is not a statement about whether the finger moved at all. See
     * [GestureConfig.flickDistanceRatio], where the argument is set out properly.
     *
     * The grid now runs down to 0.02 so the data can pick the number rather than the bound. It
     * declines to: every value from 0.02 to 0.12 scores identically, because all 94 taps in the
     * bank travel exactly zero pixels and there is nothing down there for a threshold to be
     * wrong about. That is a finding and not a search that stopped early, which is why the floor
     * is still marked justified below -- but it does mean the shipped value has to be argued for
     * in the config rather than read off this sweep's midpoint.
     */
    private val flickGrid = steps(0.02f, 0.85f, 0.01f)
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
    private val JUSTIFIED_FLOOR = "no lower bound in the data; the value is argued, not swept"

    private fun reportPlateau(tied: List<GestureConfig>) {
        if (tied.size <= 1) return
        println("  ${tied.size} threshold sets score identically. Room on each axis:")
        val warnings = mutableListOf<String>()
        fun span(
            name: String,
            grid: List<Float>,
            justified: String? = null,
            get: (GestureConfig) -> Float,
        ) {
            val values = tied.map(get)
            val lo = values.min()
            val hi = values.max()
            val atBottom = lo <= grid.first()
            val atTop = hi >= grid.last()
            val edge = when {
                atBottom && atTop -> " <- spans the whole grid: no signal"
                atBottom && justified != null -> " <- at the floor ($justified)"
                atBottom -> " <- AT THE BOTTOM OF THE GRID"
                atTop -> " <- AT THE TOP OF THE GRID"
                else -> ""
            }
            // A bound with a reason behind it is a decision, not an unfinished search, so it is
            // reported without being nagged about.
            if (edge.isNotEmpty() && !(atBottom && !atTop && justified != null)) warnings += name
            println("    %-20s %.2f .. %.2f%s".format(name, lo, hi, edge))
        }
        span("flickDistanceRatio", flickGrid, JUSTIFIED_FLOOR) { it.flickDistanceRatio }
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
        val failures = GestureReplay.scorable(records).mapNotNull { record ->
            val intent = record.legacy?.intent ?: return@mapNotNull null
            val read = GestureReplay.replay(record, config)
            if (GestureReplay.intentOf(read) == intent) null else record to read
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
                    record.label(),
                    record.legacy!!.intent.name.lowercase(),
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
        // Three places on the flick distance, one more than the rest: it is the only one whose
        // shipped value is finer than the grid, and %.2f rounded 0.025 to 0.03 in the report.
        "flickDistanceRatio=%.3f verticalDominance=%.2f glideDistanceRatio=%.2f flickToGlideRatio=%.2f"
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
