package com.offlinekeyboard.ime.gesture

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * What the user was *asked* to do. This is the ground truth the bank exists to capture, and the
 * only thing in a record that cannot be recomputed later.
 */
enum class GestureIntent {
    /** A swipe down for the symbol behind the key. */
    SYMBOL,

    /** A glide that spells a word. */
    WORD,

    /**
     * A plain tap for the letter on the key.
     *
     * Not a distraction from the symbol-versus-word question: it is the other side of it. The
     * flick threshold trades off against taps, not against glides, so a bank with no taps in it
     * gives a sweep no reason at all not to drive that threshold to zero -- and it will, because
     * every sample it can see is improved by doing so. The first session had exactly this hole,
     * and the sweep duly recommended halving the threshold with no evidence about what that
     * would do to ordinary typing.
     */
    LETTER,
}

/** What the state machine actually decided, at the moment the finger lifted. */
enum class GestureVerdict { TAP, FLICK, GLIDE, ACCENT, TRACKPAD, NONE }

/**
 * The four numbers that decide flick-versus-glide, stored with every record.
 *
 * Without them a recorded [GestureVerdict] is uninterpretable a month later: it would say what
 * some unknown build thought, which is worse than saying nothing. With them, a record made
 * under one set of thresholds is still honest evidence after the thresholds move.
 */
data class GestureThresholds(
    val flickDistanceRatio: Float,
    val verticalDominance: Float,
    val glideDistanceRatio: Float,
    val flickToGlideRatio: Float,
    /** How long a mid-glide lift may last. See [GestureConfig.glideResumeMs]. */
    val glideResumeMs: Long = GestureConfig().glideResumeMs,
    /** How far from the lift the finger may return. See [GestureConfig.glideResumeRadiusRatio]. */
    val glideResumeRadiusRatio: Float = GestureConfig().glideResumeRadiusRatio,
) {
    companion object {
        fun of(config: GestureConfig) = GestureThresholds(
            flickDistanceRatio = config.flickDistanceRatio,
            verticalDominance = config.verticalDominance,
            glideDistanceRatio = config.glideDistanceRatio,
            flickToGlideRatio = config.flickToGlideRatio,
            glideResumeMs = config.glideResumeMs,
            glideResumeRadiusRatio = config.glideResumeRadiusRatio,
        )
    }
}

/**
 * One finger-down to finger-up, exactly as the state machine saw it, with no interpretation
 * applied beyond the verdict it reached.
 */
data class GestureTrace(
    val startKeyId: String,
    val verdict: GestureVerdict,
    val layoutId: String,
    val widthPx: Float,
    val keyUnitPx: Float,
    val keyHeightPx: Float,
    val thresholds: GestureThresholds,
    val path: List<PathPoint>,
    /**
     * Indices into [path] at which a new stroke begins, so a glide that was interrupted by a
     * lifted finger can be told from one that was not.
     *
     * This is the whole reason the leniency is recordable rather than only tunable by feel. The
     * gap's length and how far the finger moved across it are both derivable from the samples on
     * either side of each index, which means a bank collected under one resume window can be
     * rescored under any other.
     */
    val strokeStarts: List<Int> = emptyList(),
)

/** A trace plus the label it was collected under. One line of the bank. */
data class GestureRecord(
    val id: String,
    val at: Long,
    val intent: GestureIntent,
    val promptId: String,
    val expected: String,
    val trace: GestureTrace,
    /**
     * The word the glide decoder produced for this path, or null when nothing decoded it.
     *
     * Stored because it is the answer, and the answer is not recoverable later: it depends on
     * the lexicon and the weights that were live at the time, and both will change. Keeping it
     * next to the path turns "the decoder got this wrong" from an impression into a line in a
     * file that can be counted.
     */
    val decoded: String? = null,
) {
    val path get() = trace.path

    /** Straight-line displacement from the finger's first sample to its last. */
    val displacement: Float
        get() {
            val a = path.firstOrNull() ?: return 0f
            val b = path.lastOrNull() ?: return 0f
            return hypot(b.x - a.x, b.y - a.y)
        }

    /** Total distance travelled, which is what the glide threshold is measured against. */
    val pathLength: Float
        get() = path.zipWithNext().sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y).toDouble() }
            .toFloat()

    val durationMs: Long
        get() = (path.lastOrNull()?.t ?: 0L) - (path.firstOrNull()?.t ?: 0L)

    /** One mid-glide finger lift: how long it lasted and how far the finger moved across it. */
    data class Gap(val ms: Long, val px: Float)

    /**
     * Every lift in the middle of this gesture.
     *
     * Derived rather than stored, because both numbers come from the samples either side of a
     * stroke boundary and storing them as well would be two ways to be wrong. This is what the
     * resume window is scored against: a bank of these says how long a real skip lasts, and how
     * far the thumb really travels while it is off the glass.
     */
    val gaps: List<Gap>
        get() = trace.strokeStarts.mapNotNull { at ->
            val before = path.getOrNull(at - 1) ?: return@mapNotNull null
            val after = path.getOrNull(at) ?: return@mapNotNull null
            Gap(after.t - before.t, hypot(after.x - before.x, after.y - before.y))
        }

    /** The verdict the shipped heuristic reached, reduced to the question the bank asks. */
    val verdictIntent: GestureIntent?
        get() = when (trace.verdict) {
            GestureVerdict.FLICK -> GestureIntent.SYMBOL
            GestureVerdict.GLIDE -> GestureIntent.WORD
            GestureVerdict.TAP -> GestureIntent.LETTER
            else -> null
        }
}

/**
 * Reads and writes one bank line.
 *
 * Deliberately schema-versioned and flat: this file is meant to outlive several rewrites of the
 * heuristic it exists to inform, so a reader years from now must be able to tell what it is
 * looking at. Coordinates are rounded to a tenth of a pixel -- a touchscreen does not resolve
 * finer than that, and full float precision triples the file size for nothing.
 */
object GestureRecordCodec {

    /**
     * 2 added the two glide-resume thresholds and the stroke boundaries within a path.
     *
     * Version 1 lines still read: they were recorded before a glide could be interrupted at all,
     * so a missing `strokes` genuinely means one stroke, and a missing resume threshold genuinely
     * means the build had none. Bumping the number is not about refusing old data -- the bank is
     * the one thing here that must never be invalidated by a change to the code that reads it --
     * it is so that a reader can tell which absences are real.
     */
    const val SCHEMA = 2

    fun encode(record: GestureRecord): String {
        val t0 = record.path.firstOrNull()?.t ?: 0L
        return Json.write(
            linkedMapOf(
                "v" to SCHEMA,
                "id" to record.id,
                "at" to record.at,
                "intent" to record.intent,
                "prompt" to record.promptId,
                "expected" to record.expected,
                "decoded" to record.decoded,
                "startKey" to record.trace.startKeyId,
                "layout" to record.trace.layoutId,
                "widthPx" to round1(record.trace.widthPx),
                "keyUnitPx" to round1(record.trace.keyUnitPx),
                "keyHeightPx" to round1(record.trace.keyHeightPx),
                "verdict" to record.trace.verdict,
                "thresholds" to linkedMapOf(
                    "flickDistanceRatio" to record.trace.thresholds.flickDistanceRatio,
                    "verticalDominance" to record.trace.thresholds.verticalDominance,
                    "glideDistanceRatio" to record.trace.thresholds.glideDistanceRatio,
                    "flickToGlideRatio" to record.trace.thresholds.flickToGlideRatio,
                    "glideResumeMs" to record.trace.thresholds.glideResumeMs,
                    "glideResumeRadiusRatio" to record.trace.thresholds.glideResumeRadiusRatio,
                ),
                // [x, y, ms since the finger went down]
                "path" to record.path.map {
                    listOf(round1(it.x), round1(it.y), (it.t - t0).toInt())
                },
                "strokes" to record.trace.strokeStarts,
            ),
        )
    }

    /** Returns null for a line this build cannot read, so one bad line never costs the bank. */
    fun decode(line: String): GestureRecord? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val o = Json.parse(line) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val thresholds = o["thresholds"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val path = (o["path"] as List<List<Any?>>).map {
            PathPoint(num(it[0]), num(it[1]), num(it[2]).toLong())
        }
        @Suppress("UNCHECKED_CAST")
        val strokes = (o["strokes"] as? List<Any?>)?.map { num(it).toInt() } ?: emptyList()
        GestureRecord(
            id = o["id"] as String,
            at = (o["at"] as Number).toLong(),
            intent = GestureIntent.valueOf(o["intent"] as String),
            promptId = o["prompt"] as String,
            expected = o["expected"] as String,
            decoded = o["decoded"] as? String,
            trace = GestureTrace(
                startKeyId = o["startKey"] as String,
                verdict = GestureVerdict.valueOf(o["verdict"] as String),
                layoutId = o["layout"] as String,
                widthPx = num(o["widthPx"]),
                keyUnitPx = num(o["keyUnitPx"]),
                keyHeightPx = num(o["keyHeightPx"]),
                thresholds = GestureThresholds(
                    flickDistanceRatio = num(thresholds["flickDistanceRatio"]),
                    verticalDominance = num(thresholds["verticalDominance"]),
                    glideDistanceRatio = num(thresholds["glideDistanceRatio"]),
                    flickToGlideRatio = num(thresholds["flickToGlideRatio"]),
                    glideResumeMs = thresholds["glideResumeMs"]?.let { num(it).toLong() }
                        ?: GestureConfig().glideResumeMs,
                    glideResumeRadiusRatio = thresholds["glideResumeRadiusRatio"]?.let { num(it) }
                        ?: GestureConfig().glideResumeRadiusRatio,
                ),
                path = path,
                strokeStarts = strokes,
            ),
        )
    }.getOrNull()

    private fun num(value: Any?): Float = (value as Number).toFloat()

    private fun round1(value: Float): Float = (value * 10f).roundToInt() / 10f
}
