package com.offlinekeyboard.ime.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.offlinekeyboard.ime.gesture.GestureIntent
import com.offlinekeyboard.ime.gesture.GestureRecord
import com.offlinekeyboard.ime.gesture.GestureTrace
import com.offlinekeyboard.ime.gesture.GestureVerdict
import java.util.UUID

/**
 * The bridge between the keyboard and the lab.
 *
 * The keyboard hands it every completed gesture. It keeps one only while a drill is armed, and
 * labels it with what that drill asked for. Nothing is collected during ordinary typing: an
 * unlabelled gesture is not evidence, and silently recording what someone types would be a
 * strange thing for a keyboard that exists to be incapable of talking to the network.
 *
 * Held in a singleton because the input method and the lab activity are the same process but
 * have no reference to each other -- the IME belongs to the system, and its view is created and
 * destroyed on the system's schedule, not the activity's.
 */
object GestureCapture {

    /** Why a gesture was or was not kept, so the lab can say so rather than just ignoring it. */
    enum class Outcome {
        RECORDED,

        /** Went down on some other key -- backspace, space, a mistyped neighbour. */
        WRONG_KEY,

        /** Barely moved. A stray tap while getting into position is not an attempt. */
        TOO_SMALL,
    }

    data class Result(val outcome: Outcome, val drill: Drill, val record: GestureRecord?)

    /**
     * Movement below this fraction of a key width is not treated as an attempt at anything.
     * Deliberately well under the flick threshold: an under-travelled flick that the keyboard
     * read as a tap is a genuine failure and has to be recorded, not filtered out for being
     * inconvenient.
     */
    private const val MIN_DISPLACEMENT_RATIO = 0.12f

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var armed: Drill? = null

    /** Set by the lab while it is in the foreground. Always called on the main thread. */
    @Volatile
    var onResult: ((Result) -> Unit)? = null

    val isArmed: Boolean get() = armed != null

    fun arm(drill: Drill) {
        armed = drill
    }

    fun disarm() {
        armed = null
    }

    /** Called from the keyboard for every gesture that ends. Cheap and silent when disarmed. */
    fun onGesture(context: Context, trace: GestureTrace) {
        val drill = armed ?: return

        if (trace.startKeyId != drill.startKeyId) {
            publish(Result(Outcome.WRONG_KEY, drill, null))
            return
        }
        // A tap drill is *asking* for a gesture that barely moves, so the guard below would
        // throw away every sample it collected.
        if (drill.intent != GestureIntent.LETTER && isNegligible(trace)) {
            publish(Result(Outcome.TOO_SMALL, drill, null))
            return
        }

        val record = GestureRecord(
            id = UUID.randomUUID().toString().substring(0, 8),
            at = System.currentTimeMillis(),
            intent = drill.intent,
            promptId = drill.id,
            expected = drill.expected,
            trace = trace,
        )
        GestureBank.append(context.applicationContext, record)
        publish(Result(Outcome.RECORDED, drill, record))
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

    private fun publish(result: Result) {
        val listener = onResult ?: return
        main.post { listener(result) }
    }
}
