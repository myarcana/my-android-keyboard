package com.offlinekeyboard.ime.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.offlinekeyboard.ime.gesture.GestureIntent
import com.offlinekeyboard.ime.gesture.GestureRecord
import com.offlinekeyboard.ime.gesture.GestureSession
import com.offlinekeyboard.ime.gesture.GestureTrace
import java.util.UUID

/**
 * The bridge between the keyboard and the lab.
 *
 * The keyboard hands it every completed gesture along with **what that gesture did to the text**,
 * and it writes both down. Nothing is collected while the lab is not running a passage: an
 * unlabelled gesture is not evidence, and silently recording what someone types would be a strange
 * thing for a keyboard that exists to be incapable of talking to the network.
 *
 * It used to reject. A gesture that did not match the one target armed at that moment was thrown
 * away, and the passage waited for one that did -- so the bank filled with successes and nothing
 * else. Its first prose session held 276 taps and zero mistakes, which is not a keyboard that
 * never misses, it is a recorder that deletes the misses. Now every gesture is kept and the
 * judging happens later, against a transcript that can be re-read.
 *
 * The edit is passed in rather than worked out here. Only the keyboard knows what a gesture
 * committed -- a glide types a word and sometimes a space before it, a flick types a digit, a
 * backspace removes a character -- and reconstructing that afterwards from the text would be
 * guessing at something that was certain at the time.
 *
 * Held in a singleton because the input method and the lab activity are the same process but have
 * no reference to each other: the IME belongs to the system, and its view is created and destroyed
 * on the system's schedule, not the activity's.
 */
object GestureCapture {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var run: PassageRun? = null

    @Volatile
    private var sessionId: String = ""

    private var sessionAt: Long = 0

    /** Set by the lab while it is in the foreground. Always called on the main thread. */
    @Volatile
    var onRecorded: ((GestureRecord, PassageRun) -> Unit)? = null

    val isArmed: Boolean get() = run != null

    /** The run in progress, for the lab to draw. */
    val current: PassageRun? get() = run

    /**
     * Starts recording a passage.
     *
     * The session line goes in immediately, with an empty `actual`. Written at the start rather
     * than only at the end because the ordinary way a session ends is that it is abandoned, and a
     * run whose intended text was never written down is a heap of gestures nobody can score. What
     * was typed is recoverable regardless -- folding every gesture's edit in sequence rebuilds it
     * exactly -- so the closing line is a convenience and a checksum, not the record of truth.
     */
    fun begin(context: Context, passage: Passage) {
        val started = PassageRun(
            passageId = passage.id,
            intended = PassageRun.textOf(passage),
            targets = passage.targets,
        )
        sessionId = UUID.randomUUID().toString().substring(0, 8)
        sessionAt = System.currentTimeMillis()
        run = started
        writeSession(context, started)
    }

    /** Closes the run, writing what it finally produced. */
    fun end(context: Context) {
        val finished = run ?: return
        writeSession(context, finished)
        run = null
    }

    fun disarm() {
        run = null
    }

    private fun writeSession(context: Context, of: PassageRun) {
        GestureBank.appendSession(
            context.applicationContext,
            GestureSession(
                id = sessionId,
                at = sessionAt,
                passageId = of.passageId,
                intended = of.intended,
                actual = of.actual,
            ),
        )
    }

    /**
     * Called from the keyboard for every gesture that ends. Cheap and silent when disarmed.
     *
     * [typed] and [deleted] are what this gesture did to the field, and they are the mapping
     * between the gesture sequence and the text: applied in order they reproduce the output
     * exactly, so every character can be traced to the gesture that made it without any string
     * being lined up against any other.
     *
     * [decoded] is what the glide decoder made of the path, when anything did. It is passed in
     * rather than computed here because the keyboard has already done it, and recording a second,
     * separately-computed answer would eventually record one the user never saw.
     */
    fun onGesture(
        context: Context,
        trace: GestureTrace,
        typed: String = "",
        deleted: Int = 0,
        decoded: String? = null,
    ) {
        val run = this.run ?: return

        // Where the caret was *before* this gesture, so the hint names the token being aimed at
        // rather than the one the gesture landed in.
        val aimedAt = run.targetAt()
        val seq = run.apply(PassageRun.Edit(typed, deleted))

        val record = GestureRecord(
            id = UUID.randomUUID().toString().substring(0, 8),
            at = System.currentTimeMillis(),
            // A hint written down at the time, not a verdict. What the passage was asking for
            // where the caret stood; whether the gesture was a correct attempt at it is settled
            // later, against the transcript.
            intent = aimedAt?.intent ?: GestureIntent.LETTER,
            promptId = aimedAt?.id ?: run.passageId,
            expected = aimedAt?.expected ?: "",
            trace = trace,
            decoded = decoded,
            sessionId = sessionId,
            seq = seq,
            typed = typed,
            deleted = deleted,
        )
        GestureBank.append(context.applicationContext, record)
        publish(record, run)
    }

    private fun publish(record: GestureRecord, run: PassageRun) {
        val listener = onRecorded ?: return
        main.post { listener(record, run) }
    }
}
