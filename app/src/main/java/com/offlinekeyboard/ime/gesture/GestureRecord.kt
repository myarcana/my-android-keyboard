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
) {
    companion object {
        fun of(config: GestureConfig) = GestureThresholds(
            flickDistanceRatio = config.flickDistanceRatio,
            verticalDominance = config.verticalDominance,
            glideDistanceRatio = config.glideDistanceRatio,
            flickToGlideRatio = config.flickToGlideRatio,
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
)

/** A trace plus the label it was collected under. One line of the bank. */
data class GestureRecord(
    val id: String,
    val at: Long,
    val intent: GestureIntent,
    val promptId: String,
    val expected: String,
    val trace: GestureTrace,
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

    const val SCHEMA = 1

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
                ),
                // [x, y, ms since the finger went down]
                "path" to record.path.map {
                    listOf(round1(it.x), round1(it.y), (it.t - t0).toInt())
                },
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
        GestureRecord(
            id = o["id"] as String,
            at = (o["at"] as Number).toLong(),
            intent = GestureIntent.valueOf(o["intent"] as String),
            promptId = o["prompt"] as String,
            expected = o["expected"] as String,
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
                ),
                path = path,
            ),
        )
    }.getOrNull()

    private fun num(value: Any?): Float = (value as Number).toFloat()

    private fun round1(value: Float): Float = (value * 10f).roundToInt() / 10f
}
