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
 * The keyboard hands it every completed gesture. It keeps one only while the lab has a target
 * armed, and labels it with what that target asked for. Nothing is collected during ordinary
 * typing: an unlabelled gesture is not evidence, and silently recording what someone types would
 * be a strange thing for a keyboard that exists to be incapable of talking to the network.
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

        /** A glide part-way through a word already being tapped. See [TargetReader]. */
        MID_WORD_GLIDE,
    }

    data class Result(
        val outcome: Outcome,
        val target: Target,
        val record: GestureRecord?,
        /** Whether the target is finished, so the passage should move on. */
        val complete: Boolean = false,
        /** The key that was actually due, which moves through a word as it is tapped. */
        val expectedKeyId: String = target.startKeyId,
    )

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var reader: TargetReader? = null

    /** Set by the lab while it is in the foreground. Always called on the main thread. */
    @Volatile
    var onResult: ((Result) -> Unit)? = null

    val isArmed: Boolean get() = reader != null

    /** Letters of the armed target already tapped, so the lab can show progress through a word. */
    val tappedInTarget: Int get() = reader?.tapped ?: 0

    /** The key a gesture must start on right now. Moves through a word being tapped out. */
    val expectedKeyId: String? get() = reader?.expectedKeyId

    fun arm(target: Target) {
        reader = TargetReader(target)
    }

    fun disarm() {
        reader = null
    }

    /** Forgets the last accepted letter of the armed target, for Undo and Void. */
    fun stepBack() {
        reader?.stepBack()
    }

    /**
     * Called from the keyboard for every gesture that ends. Cheap and silent when disarmed.
     *
     * [decoded] is what the glide decoder made of the path, when anything did. It is passed in
     * rather than computed here because the keyboard has already done it -- and recording a
     * second, separately-computed answer would eventually record one the user never saw.
     */
    fun onGesture(context: Context, trace: GestureTrace, decoded: String? = null) {
        val reader = this.reader ?: return
        val target = reader.target

        when (val reading = reader.read(trace)) {
            is TargetReader.Reading.WrongKey ->
                publish(Result(Outcome.WRONG_KEY, target, null, expectedKeyId = reading.expectedKeyId))

            TargetReader.Reading.TooSmall ->
                publish(Result(Outcome.TOO_SMALL, target, null))

            TargetReader.Reading.MidWordGlide ->
                publish(
                    Result(
                        Outcome.MID_WORD_GLIDE, target, null,
                        expectedKeyId = reader.expectedKeyId,
                    ),
                )

            is TargetReader.Reading.Keep -> {
                val record = GestureRecord(
                    id = UUID.randomUUID().toString().substring(0, 8),
                    at = System.currentTimeMillis(),
                    intent = reading.intent,
                    promptId = target.id,
                    expected = reading.expected,
                    trace = trace,
                    // Only a glide has a decoded word. A letter's "decoded" would be the word the
                    // glide engine made of a single tap, which is not an answer to any question.
                    decoded = decoded.takeIf { reading.intent == GestureIntent.WORD },
                    word = reading.word,
                    letterIndex = reading.letterIndex,
                )
                GestureBank.append(context.applicationContext, record)
                publish(
                    Result(
                        Outcome.RECORDED, target, record,
                        complete = reading.complete,
                        expectedKeyId = reader.expectedKeyId,
                    ),
                )
            }
        }
    }

    private fun publish(result: Result) {
        val listener = onResult ?: return
        main.post { listener(result) }
    }
}
