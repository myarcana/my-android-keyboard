package com.offlinekeyboard.ime.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.offlinekeyboard.ime.gesture.GestureRecord
import com.offlinekeyboard.ime.gesture.GestureSession
import com.offlinekeyboard.ime.gesture.GestureThresholds
import com.offlinekeyboard.ime.gesture.GestureTrace
import java.util.UUID

/**
 * The bridge between the keyboard and the lab.
 *
 * The keyboard hands it every completed gesture along with **what that gesture did to the text**,
 * and it writes both down. Nothing is collected while the lab is not running a passage -- not
 * because a gesture needs a label to be worth having (most of the bank's readers use no labels at
 * all) but because a gesture needs a *passage* to be worth having: without one there is nothing
 * saying what was being aimed at, so nothing can be asked of it afterwards. Silently recording
 * what someone types outside the lab would also be a strange thing for a keyboard that exists to
 * be incapable of talking to the network.
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

    /**
     * Whether gestures are being kept right now.
     *
     * Separate from having a run, because leaving the lab and coming back must not start a new
     * one. It used to: every resume began a fresh [PassageRun] while the field kept the text
     * already typed into it, so the transcript restarted at empty and disagreed with the screen
     * from then on. Recording stops when the lab goes away and picks the same run back up when it
     * returns.
     */
    @Volatile
    private var recording = false

    @Volatile
    private var sessionId: String = ""

    private var sessionAt: Long = 0

    /**
     * The frame the run was typed in, taken from the gestures as they arrive.
     *
     * On the session rather than on every gesture because neither changes inside a run, and
     * repeating six numbers across two hundred lines to say one thing is how the file got to
     * 650 bytes a gesture. Not known when the run opens -- nothing has been touched yet -- so
     * the opening line goes without them and the closing one carries them.
     */
    @Volatile
    private var layoutId: String = ""

    @Volatile
    private var thresholds: GestureThresholds? = null

    /** Set by the lab while it is in the foreground. Always called on the main thread. */
    @Volatile
    var onRecorded: ((GestureRecord, PassageRun) -> Unit)? = null

    val isArmed: Boolean get() = recording && run != null

    /** The run in progress, for the lab to draw. */
    val current: PassageRun? get() = run

    /**
     * Continues the run for this passage, or starts one if there is none.
     *
     * Called when the lab comes back to the foreground, where the passage on screen and the text
     * in the field have both survived. Beginning unconditionally here is what broke the
     * transcript, and it broke it silently: the gestures went on being recorded, under a new
     * session, against a run that thought nothing had been typed.
     */
    fun resume(context: Context, passage: Passage) {
        val existing = run
        if (existing != null && existing.passageId == passage.id) {
            recording = true
        } else {
            begin(context, passage)
        }
    }

    /** Stops recording and writes down what the run has produced so far. */
    fun pause(context: Context) {
        val paused = run ?: return
        recording = false
        writeSession(context, paused)
    }

    /**
     * Starts recording a passage, from nothing.
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
        layoutId = ""
        thresholds = null
        run = started
        recording = true
        writeSession(context, started)
    }

    /** Closes the run for good, writing what it finally produced. */
    fun end(context: Context) {
        pause(context)
        run = null
    }

    fun disarm() {
        recording = false
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
                layoutId = layoutId,
                thresholds = thresholds,
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
     * Nothing here says what the gesture was *for*. The passage is on the session line and the
     * path is on this one; what the gesture meant is a question about the two together, and one
     * that cannot be answered until the typist has had the chance to correct it.
     */
    fun onGesture(
        context: Context,
        trace: GestureTrace,
        typed: String = "",
        deleted: Int = 0,
    ) {
        if (!recording) return
        val run = this.run ?: return

        layoutId = trace.layoutId
        trace.thresholds?.let { thresholds = it }

        val seq = run.apply(PassageRun.Edit(typed, deleted))

        val record = GestureRecord(
            id = UUID.randomUUID().toString().substring(0, 8),
            at = System.currentTimeMillis(),
            trace = trace,
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
