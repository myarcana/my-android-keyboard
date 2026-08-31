package com.offlinekeyboard.ime.gesture

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * What a pre-v6 lab wrote down, at the moment of the gesture, about what it thought was meant.
 *
 * Kept only to read old lines. Nothing writes it any more, and the reason is that it was never
 * knowable at the time: whether a downward stroke on `i` was a flick for `8` or the first leg of
 * a glide for `I'm` depends on what the person meant, and the lab asking itself that question in
 * the moment is what produced four separate ways of being confidently wrong -- space presses
 * filed under the letter that happened to be due, a label that drifted one word behind for the
 * rest of a session after a single uncorrected miss, a whole class of prose taps recorded as the
 * word they sat inside, and a threshold sweep scoring against all of it.
 *
 * What the bank records now is what was observed: the passage, the path, and the text that went
 * into the field. Anything anyone wants to conclude from those is concluded afterwards, by
 * whoever is asking, from data that cannot have drifted.
 */
enum class GestureIntent {
    /** A swipe down for the symbol behind the key. */
    SYMBOL,

    /** A glide that spells a word. */
    WORD,

    /** A plain tap for the letter on the key. */
    LETTER,
}

/** What the state machine actually decided, at the moment the finger lifted. */
enum class GestureVerdict { TAP, FLICK, GLIDE, ACCENT, TRACKPAD, NONE }

/**
 * The numbers that decided flick-versus-glide while a run was being recorded.
 *
 * Written once on the [GestureSession] rather than on every gesture. They are a property of the
 * build, not of the finger, and they do not change inside a run -- 2670 records in the first
 * bank carried four distinct sets between them, one of which accounted for 2382. Recovering
 * them from git by timestamp instead would break in the one case that matters, which is someone
 * tuning against a locally edited [GestureConfig].
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
 * One finger-down to finger-up, exactly as the state machine saw it.
 *
 * The path is the observation and the rest is the frame needed to read it: which layout was on
 * screen, and how wide, since the coordinates are in that layout's own pixels. Without the
 * frame the path is a list of numbers with no units.
 */
data class GestureTrace(
    val startKeyId: String,
    val layoutId: String,
    val widthPx: Float,
    val keyUnitPx: Float,
    val keyHeightPx: Float,
    val path: List<PathPoint>,
    /**
     * Indices into [path] at which the finger came back down.
     *
     * An observation, not a conclusion: the digitiser reported the finger up, and the recorder
     * wrote down where in the path that happened. It reads like something derivable from a gap
     * in the timestamps and it is not -- a mid-glide lift can be shorter than the resume window
     * allows, 40ms say, which is only two or three sampling intervals and indistinguishable from
     * a slow frame. Deriving it would need a threshold, and a threshold here would quietly
     * reclassify the very gestures the resume window exists to handle.
     *
     * How long each lift lasted and how far the finger moved across it are read from the samples
     * either side, which is why *those* are not stored. That is what lets a bank collected under
     * one resume window be rescored under any other.
     */
    val strokeStarts: List<Int> = emptyList(),
    /**
     * What the shipped heuristic made of this path, when the record came from a build that
     * wrote it down. Null on every line written since v6, where it is recomputed by replaying
     * the path -- the classification is a function of the path and the thresholds, and storing
     * a function of two stored things is a third thing to fall out of step.
     */
    val verdict: GestureVerdict? = null,
    /** The thresholds live at the time. Carried on the session line since v6. */
    val thresholds: GestureThresholds? = null,
)

/**
 * The labels a pre-v6 lab wrote at the moment of the gesture.
 *
 * Present on old lines and on no new ones. See [GestureIntent] for why they stopped being
 * written; the short version is that every one of them is a claim about what was *meant*, made
 * by a machine that could not know, and each was wrong in its own way. A reader that wants a
 * label derives one from the session transcript instead, where a mistake shows up as a mistake
 * rather than as a confident mislabel.
 */
data class LegacyLabels(
    val intent: GestureIntent,
    val promptId: String,
    val expected: String,
    val word: String? = null,
    val letterIndex: Int = -1,
    /**
     * The word the glide decoder produced.
     *
     * Kept for old lines and not written any more, because it was never a second fact: of the
     * 59 v5 records that carried it, every one that also had a [GestureRecord.typed] repeated
     * it exactly. What the decoder produced is what went into the field.
     */
    val decoded: String? = null,
)

/**
 * One gesture: what the finger did, and what that put into the field.
 *
 * Three things and no more, because three things are what a recorder can honestly know. The
 * passage on the [GestureSession] says what the typist was trying to type; [trace] says what
 * they did; [typed] and [deleted] say what came out. Every question the lab used to answer in
 * the moment -- was that a mis-hit, was that word right, was this a flick or a glide -- is a
 * question about the relationship between those three, and belongs to whoever is asking, later,
 * with the correction the typist went on to make already visible.
 */
data class GestureRecord(
    val id: String,
    val at: Long,
    val trace: GestureTrace,
    /**
     * Why this sample is not evidence of anything, or null when it is.
     *
     * The one judgement still made at record time, and the only one that can be: it is made by
     * the person who made the gesture, about the gesture they just made, within a second or two
     * of making it. A hand that starts a flick, thinks better of it and comes back has produced
     * a path that means nothing, and nobody but that hand will ever know.
     *
     * The line itself stays. Deleting it would throw away a real recording of a real thing a
     * hand did, and a file that quietly loses its awkward lines is one nobody can audit.
     */
    val voidReason: String? = null,
    /**
     * The run of typing this gesture belongs to, and its place in that run.
     *
     * The session says what was asked for and what came out; the sequence number says which
     * gesture is which; [typed] and [deleted] say what this one did to the text. Applied in
     * order they rebuild the run's `actual` exactly, so every character traces to the gesture
     * that produced it -- which is the mapping that cannot be reconstructed afterwards by
     * lining strings up, and the reason it is recorded rather than inferred.
     */
    val sessionId: String? = null,
    val seq: Int = -1,
    /** The text this gesture put into the field. Empty for a backspace or for nothing at all. */
    val typed: String = "",
    /** Characters this gesture removed from the end of the field. */
    val deleted: Int = 0,
    /** Labels from a pre-v6 line. Null on everything written since. */
    val legacy: LegacyLabels? = null,
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
     * Every lift in the middle of this gesture: how long it lasted, and how far the finger moved.
     *
     * Derived from [GestureTrace.strokeStarts] and the samples either side, rather than stored,
     * because storing them as well would be two ways to say one thing and so one way to be
     * wrong. This is what the resume window is scored against.
     */
    val gaps: List<Gap>
        get() = trace.strokeStarts.mapNotNull { at ->
            val before = path.getOrNull(at - 1) ?: return@mapNotNull null
            val after = path.getOrNull(at) ?: return@mapNotNull null
            Gap(after.t - before.t, hypot(after.x - before.x, after.y - before.y))
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
     * 3 added [GestureRecord.voidReason].
     * 4 added `word` and `letterIndex`, when prose passages began collecting tapped words.
     * 5 added the transcript: a [GestureSession] line per run, and `session`, `seq`, `typed`
     *   and `deleted` on every gesture. This is the version at which the lab stopped rejecting
     *   gestures it could not label -- up to 4 a bank held only the gestures that went well,
     *   because a mis-hit was refused and left no line at all.
     * 6 removed everything v5 had made redundant and had not deleted. Gone from the written
     *   line: `intent`, `prompt`, `expected`, `word`, `letterIndex` -- guesses at what was
     *   meant, made when it could not be known; `decoded` -- a copy of `typed`; `verdict` and
     *   `thresholds` -- a classification and its inputs, recomputable by replay, with the
     *   inputs now on the session line. `strokes` stays, written only when there are any: a
     *   finger lift is something the digitiser reported, not something a reader can infer, and
     *   a 40ms lift is indistinguishable from a slow frame. What is left is the passage, the
     *   path, and the text that reached the field.
     *
     * Every one of those still *reads*. Version 1 lines were recorded before a glide could be
     * interrupted at all, so a missing `strokes` genuinely means one stroke and a missing resume
     * threshold genuinely means the build had none. Bumping the number is not about refusing old
     * data -- the bank is the one thing here that must never be invalidated by a change to the
     * code that reads it -- it is so a reader can tell which absences are real.
     *
     * 3 is the version that matters in the other direction. A missing `void` means the same
     * thing at every version, so reading an old line is never ambiguous; what the number is for
     * is a reader going the other way, since anything that scores a v3 bank without honouring
     * `void` quietly counts samples whose labels were withdrawn.
     */
    const val SCHEMA = 6

    fun encode(record: GestureRecord): String {
        val t0 = record.path.firstOrNull()?.t ?: 0L
        val fields = linkedMapOf<String, Any?>(
            "v" to SCHEMA,
            "id" to record.id,
            "at" to record.at,
        )
        // Written only when there is one. A sample that is evidence should not have to say so on
        // every line, and almost every line is evidence.
        record.voidReason?.let { fields["void"] = it }
        record.sessionId?.let {
            fields["session"] = it
            fields["seq"] = record.seq
            fields["typed"] = record.typed
            if (record.deleted > 0) fields["deleted"] = record.deleted
        }
        return Json.write(
            fields + linkedMapOf(
                "startKey" to record.trace.startKeyId,
                "layout" to record.trace.layoutId,
                "widthPx" to round1(record.trace.widthPx),
                "keyUnitPx" to round1(record.trace.keyUnitPx),
                "keyHeightPx" to round1(record.trace.keyHeightPx),
                // [x, y, ms since the finger went down]
                "path" to record.path.map {
                    listOf(round1(it.x), round1(it.y), (it.t - t0).toInt())
                },
            ) + if (record.trace.strokeStarts.isEmpty()) emptyMap()
            else linkedMapOf("strokes" to record.trace.strokeStarts),
        )
    }

    /** Returns null for a line this build cannot read, so one bad line never costs the bank. */
    fun decode(line: String): GestureRecord? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val o = Json.parse(line) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val path = (o["path"] as List<List<Any?>>).map {
            PathPoint(num(it[0]), num(it[1]), num(it[2]).toLong())
        }
        @Suppress("UNCHECKED_CAST")
        val strokes = (o["strokes"] as? List<Any?>)?.map { num(it).toInt() } ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val thresholds = (o["thresholds"] as? Map<String, Any?>)?.let {
            GestureThresholds(
                flickDistanceRatio = num(it["flickDistanceRatio"]),
                verticalDominance = num(it["verticalDominance"]),
                glideDistanceRatio = num(it["glideDistanceRatio"]),
                flickToGlideRatio = num(it["flickToGlideRatio"]),
                glideResumeMs = it["glideResumeMs"]?.let { v -> num(v).toLong() }
                    ?: GestureConfig().glideResumeMs,
                glideResumeRadiusRatio = it["glideResumeRadiusRatio"]?.let { v -> num(v) }
                    ?: GestureConfig().glideResumeRadiusRatio,
            )
        }
        // Pre-v6 lines carry a label written at the moment of the gesture. It is read so the
        // old records stay scorable -- 144 of the bank's 146 symbol samples are among them, and
        // nothing else says what they were aimed at -- and it is never written again.
        val legacy = (o["intent"] as? String)?.let {
            LegacyLabels(
                intent = GestureIntent.valueOf(it),
                promptId = o["prompt"] as? String ?: "",
                expected = o["expected"] as? String ?: "",
                word = o["word"] as? String,
                letterIndex = (o["letterIndex"] as? Number)?.toInt() ?: -1,
                decoded = o["decoded"] as? String,
            )
        }
        GestureRecord(
            id = o["id"] as String,
            at = (o["at"] as Number).toLong(),
            voidReason = o["void"] as? String,
            sessionId = o["session"] as? String,
            seq = (o["seq"] as? Number)?.toInt() ?: -1,
            typed = o["typed"] as? String ?: "",
            deleted = (o["deleted"] as? Number)?.toInt() ?: 0,
            legacy = legacy,
            trace = GestureTrace(
                startKeyId = o["startKey"] as String,
                layoutId = o["layout"] as String,
                widthPx = num(o["widthPx"]),
                keyUnitPx = num(o["keyUnitPx"]),
                keyHeightPx = num(o["keyHeightPx"]),
                path = path,
                strokeStarts = strokes,
                verdict = (o["verdict"] as? String)?.let { GestureVerdict.valueOf(it) },
                thresholds = thresholds,
            ),
        )
    }.getOrNull()

    private fun num(value: Any?): Float = (value as Number).toFloat()

    private fun round1(value: Float): Float = (value * 10f).roundToInt() / 10f
}

/**
 * One run at one passage: what the typist was trying to type, and what the typing produced.
 *
 * Written as its own line, keyed by [id], which every gesture of that run carries. Separate
 * rather than repeated on each gesture because a passage is a couple of hundred characters and
 * a run is a couple of hundred gestures, and storing one inside the other would triple the file
 * to say the same thing three hundred times.
 *
 * It is rewritten as the run proceeds rather than only at the end. A session abandoned halfway
 * -- which is the ordinary way a session ends -- must still say what was typed before it
 * stopped.
 */
data class GestureSession(
    val id: String,
    val at: Long,
    val passageId: String,
    /** What the passage asked to be typed. */
    val intended: String,
    /** What the typing produced, folded from every gesture's edit in order. */
    val actual: String,
    /** The layout that was on screen, and the thresholds the build was running. */
    val layoutId: String = "",
    val thresholds: GestureThresholds? = null,
)

/** Reads and writes a session line. Distinguished from a gesture line by its `kind`. */
object GestureSessionCodec {

    const val KIND = "session"

    fun encode(session: GestureSession): String = Json.write(
        linkedMapOf<String, Any?>(
            "v" to GestureRecordCodec.SCHEMA,
            "kind" to KIND,
            "id" to session.id,
            "at" to session.at,
            "passage" to session.passageId,
            "intended" to session.intended,
            "actual" to session.actual,
            "layout" to session.layoutId,
        ).also { fields ->
            session.thresholds?.let {
                fields["thresholds"] = linkedMapOf(
                    "flickDistanceRatio" to it.flickDistanceRatio,
                    "verticalDominance" to it.verticalDominance,
                    "glideDistanceRatio" to it.glideDistanceRatio,
                    "flickToGlideRatio" to it.flickToGlideRatio,
                    "glideResumeMs" to it.glideResumeMs,
                    "glideResumeRadiusRatio" to it.glideResumeRadiusRatio,
                )
            }
        },
    )

    /** Null for any line that is not a session, which includes every line written before v5. */
    fun decode(line: String): GestureSession? = runCatching {
        @Suppress("UNCHECKED_CAST")
        val o = Json.parse(line) as Map<String, Any?>
        if (o["kind"] != KIND) return null
        @Suppress("UNCHECKED_CAST")
        val thresholds = (o["thresholds"] as? Map<String, Any?>)?.let {
            GestureThresholds(
                flickDistanceRatio = (it["flickDistanceRatio"] as Number).toFloat(),
                verticalDominance = (it["verticalDominance"] as Number).toFloat(),
                glideDistanceRatio = (it["glideDistanceRatio"] as Number).toFloat(),
                flickToGlideRatio = (it["flickToGlideRatio"] as Number).toFloat(),
                glideResumeMs = (it["glideResumeMs"] as? Number)?.toLong()
                    ?: GestureConfig().glideResumeMs,
                glideResumeRadiusRatio = (it["glideResumeRadiusRatio"] as? Number)?.toFloat()
                    ?: GestureConfig().glideResumeRadiusRatio,
            )
        }
        GestureSession(
            id = o["id"] as String,
            at = (o["at"] as Number).toLong(),
            passageId = o["passage"] as String,
            intended = o["intended"] as String,
            actual = o["actual"] as String,
            layoutId = o["layout"] as? String ?: "",
            thresholds = thresholds,
        )
    }.getOrNull()
}
