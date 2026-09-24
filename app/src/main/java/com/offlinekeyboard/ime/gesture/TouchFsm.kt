package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.EditAction
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.PopupEntry
import com.offlinekeyboard.ime.layout.PopupGrid
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

data class PathPoint(val x: Float, val y: Float, val t: Long)

/** Which way a space-bar squash flick went. */
enum class SquashDirection { LEFT, RIGHT }

/**
 * Gesture thresholds. Ratios rather than pixels so behaviour is identical on any screen
 * density; the debug overlay tunes these by feel on a real device.
 */
data class GestureConfig(
    /**
     * How long a still finger must hold before the press becomes a hold: the accent popup, the
     * backspace repeat, the spacebar trackpad.
     *
     * 175ms, by request: first halved from the platform's 500ms to 250ms, then cut a further 30%.
     * The platform number is a *default* for views that have nothing better to do while waiting,
     * not a perceptual constant, and a keyboard does have something better: every key here
     * offers a hold, so the wait is paid on purpose rather than discovered by accident.
     *
     * It is not free, twice over. First, it now sits *inside* the tap distribution: the bank's
     * slowest tap lifts at 190ms, so a lingering tap on a key with a popup can open it instead of
     * typing the letter. Second, this is the window in which a slow glide can be mistaken for a
     * hold -- the recorded "on" that dawdled 447ms before accelerating is well past the deadline,
     * and only [longPressSlopRatio] keeps it from opening a popup. That guard is what makes a
     * short timeout safe; do not loosen both at once.
     */
    val longPressMs: Long = 175L,
    /**
     * Downward travel needed to read as a flick, as a fraction of key height.
     *
     * Measured, not guessed: see docs/GESTURE_BANK.md. At the original 0.45 -- 54px on the
     * target phone -- ten of sixty-four recorded flicks never registered at all, six of them on
     * m, where the bottom row leaves nowhere to swipe to.
     *
     * It then sat at 0.20 for a while, on the grounds that 8dp of touch slop over a 40dp key is
     * 0.20 and Android calls a finger stationary below that. Two things were wrong with it. The
     * smaller: the bank has since collected flicks of 15.1px and 23.0px, both read as taps, so
     * the claim the number rested on -- that nothing lives between the two classes -- is no
     * longer true. The larger: touch slop is not a filter on coordinates. It is how far a child
     * view may move before a scrolling parent is entitled to steal the gesture, and it says
     * nothing at all about whether a finger moved.
     *
     * What does call the finger stationary is the digitiser, and it does so at zero: all 94 taps
     * in the bank report the identical coordinate across five to eleven move events, to a tenth
     * of a pixel. That filter runs before this code sees anything, so deducting another 8dp here
     * was subtracting the same margin twice.
     *
     * Which leaves the bank with no lower bound to give -- every value from 0.02 to 0.12 scores
     * identically on all 286 samples -- so the number comes from the mechanism instead. 0.025 is
     * one dp of a forty-dp key: three pixels, the smallest travel that is unambiguously travel
     * on a panel which reports a still finger as perfectly still.
     *
     * It can be this small because distance is not deciding alone. A flick also has to be
     * downward, and [verticalDominance] times more vertical than horizontal. A resting thumb
     * does not drift three pixels straight down. An isotropic threshold here would be reckless;
     * this one is fenced on two other axes, and only the third had to be generous.
     */
    val flickDistanceRatio: Float = 0.025f,
    /**
     * How far the finger drags the key's symbol, as a fraction of key height.
     *
     * Not a threshold -- nothing is decided by it. It is the distance the symbol itself has to
     * cover to get from its resting slot to the letter's place, so setting the finger's travel
     * equal to it makes the two move as one: the symbol sits under the thumb and is dragged
     * down, rather than being played back at some speed of its own. That is the whole difference
     * between an animation and a manoeuvre, and the number is therefore the view's glyph geometry
     * (0.74 - 0.28 of key height) rather than anything measured off a hand.
     *
     * Deliberately not [flickDistanceRatio], and now a long way from it. A flick commits after
     * three pixels, where the symbol is barely 5% of the way home, so the point of no return
     * sits very near the top of a pull that runs on for another fifty. The letter therefore goes
     * out almost as soon as the thumb does, and the symbol keeps sliding under it afterwards.
     * That is honest rather than hasty -- the commit really did happen that early -- and coming
     * back up above it still puts the letter back and gives the letter.
     */
    val flickTravelRatio: Float = 0.46f,
    /**
     * |dy| must exceed this multiple of |dx| for a flick; otherwise it reads as a glide.
     *
     * This is what separates a flick from "ok". On the o key, flicks leave at a ratio of 6.6 or
     * more, while gliding "ok" leaves at 1.6 to 4.3 -- because k is half a key to the left, so
     * the word departs about 25 degrees off vertical and the flick does not. Nothing else
     * separates those two: "ok" is in fact the *straightest* gesture in the bank, straighter
     * than the average flick, several of which hook through 80 degrees at the lift.
     */
    val verticalDominance: Float = 4.25f,
    /**
     * The widest a key's flick cone may open, in degrees either side of straight down.
     *
     * [verticalDominance] is about 13 degrees, and that is what `o` needs, with `ok` leaving 25
     * degrees off vertical. It is far stricter than `h` or `m` need, where no word leaves
     * downward at all. [FlickCone] opens each side of each key toward this limit, and stops short
     * of any letter a word could actually be heading for.
     *
     * 45 rather than more: recorded flicks lean at most 30 degrees (bottom row), and past 45 the
     * stroke is more sideways than down, which is the start of a glide along the row. Set to 0 to
     * get the old machine back exactly.
     */
    val flickConeMaxDegrees: Float = 45f,
    /**
     * How far short of a contested letter a cone stops, in degrees.
     *
     * A glide's first leg does not point straight at its second letter. In the bank, `un` leaves
     * up to 19 degrees off the line from `u` to `n`, and `th` up to 9. 20 keeps the cone clear of
     * the worst of those.
     */
    val flickConeMarginDegrees: Float = 20f,
    /**
     * How common a second letter must be, as a share of the words starting with this key, before
     * it bounds that key's cone.
     *
     * 1%: `ib` (0.15% of i-words) does not narrow `i`, but `in` (55%) does. So does `wa` (17%)
     * on `w`, and `kn` (24%) on `k`. A rare pair is not worth losing every leaning flick for,
     * because a rare word glided through the widened cone still has a way out. It can start
     * sideways, or be tapped.
     */
    val flickConeMinShare: Float = 0.01f,
    /**
     * Downward travel a flick needs when it leans past [verticalDominance] into the widened cone,
     * as a fraction of key height.
     *
     * Larger than [flickDistanceRatio]'s 3 pixels because that number is safe only inside a
     * 13-degree fence: a resting thumb does not drift three pixels straight down. A thumb rolling
     * off a tap does drift three pixels at 30 degrees. The bank has one: a tap on `h` that
     * moved 14px down and 4px across. 0.15 of a key clears it and every other recorded tap, and
     * is still below the shortest recorded flick (0.23 key heights on `m`).
     */
    val flickConeMinTravelRatio: Float = 0.15f,
    /**
     * How far the finger may drift and still count as holding still, as a fraction of key
     * height. Beyond this the accent popup will not open, however long the press lasts.
     *
     * A long press means held *still*, which is what it means everywhere else on the platform.
     * Without this, starting a glide slowly opens the accent popup instead: a recorded glide of
     * "on" dawdled for 447ms before picking up speed, and the popup fired at 500ms while the
     * finger was already 24px down the key.
     *
     * This one *is* Android's touch slop, 8dp over a 40dp key, and it shared the number with
     * [flickDistanceRatio] until that one moved. They could part company because they ask
     * different questions. Whether the finger has moved downward on purpose is answered by the
     * panel and by the direction, and needs no margin of its own. Whether it has stayed put long
     * enough to mean a hold is drift in any direction over time, which is exactly the question
     * touch slop was written to answer, so here the platform's number is the right one.
     */
    val longPressSlopRatio: Float = 0.20f,
    /**
     * Path length that turns a press into a glide, as a fraction of key width.
     *
     * Briefly 1.4 on a 128-gesture bank, then back to 1.2 when 80 more arrived: a recorded "ok"
     * travelled 122px against the 129px that 1.4 demanded and was read as a plain tap. Short
     * words that stop one row down have very little path to offer.
     */
    val glideDistanceRatio: Float = 1.2f,
    /**
     * Longer path length that promotes an in-progress flick into a glide.
     *
     * The midpoint of the winning range rather than either end of it: an "ex" glide that had
     * already been read as a flick needed 246px to escape, and 2.8 asked for 258px.
     */
    val flickToGlideRatio: Float = 2.5f,
    /**
     * Upward travel that runs the key's [com.offlinekeyboard.ime.layout.Key.flickUp], as a
     * fraction of key height.
     *
     * Deliberately far stricter than [flickDistanceRatio] -- three quarters of a key against
     * three pixels -- and the asymmetry is the whole design. Downward has nothing to compete
     * with: below the letters is the bottom of the board, so three pixels of deliberate downward
     * movement can only mean the secondary. Upward is where *words* go. Every glide that starts
     * on the bottom row leaves its first key heading up, so an upward gesture read as generously
     * as the downward one would fire copy in the middle of gliding "cat".
     *
     * 0.75 is a starting point chosen for the shape of the gesture, *not* fitted to the bank the
     * way [flickDistanceRatio] was -- the bank holds no upward flicks yet, because until now
     * there was no such gesture to record. It is deliberately on the demanding side for that
     * reason: the cost of asking too much is a flick that has to be repeated, and the cost of
     * asking too little is an action fired in the middle of a word. Once
     * `docs/GESTURE_BANK.md` has upward strokes in it, this is the number to revisit, and
     * `GestureBankReplayTest` is where the evidence should be made to speak.
     *
     * The real separation is not this number alone -- see [upFlickToGlideRatio], which lets a
     * stroke that keeps going take the word after all.
     */
    val upFlickDistanceRatio: Float = 0.75f,
    /**
     * Path length that abandons an armed upward flick and takes the glide instead, as a fraction
     * of key width.
     *
     * The upward mirror of [flickToGlideRatio], and smaller than it on purpose. A downward flick
     * is the likelier reading of a downward stroke and gets the benefit of the doubt for 2.5 key
     * widths; an upward stroke that has already travelled a key and a half is a word being
     * written, because the actions are single commands and no hand needs a three-key run-up to
     * ask for one.
     *
     * This is what makes the gesture safe to offer on every key that has a popup. The distance
     * above decides when the action becomes *possible*; this decides when the word wins anyway,
     * and nothing is committed in between -- so a glide is never interrupted by an action it
     * merely passed through.
     */
    val upFlickToGlideRatio: Float = 1.5f,
    /**
     * Vertical travel on backspace that deletes in bulk -- the line above the cursor on an
     * upward stroke, the word behind it on a downward one -- as a fraction of key height.
     * Larger than [flickDistanceRatio] because these gestures destroy text: a thumb drifting
     * off the key must not trigger them, and there is nothing to undo them with.
     */
    val bulkDeleteDistanceRatio: Float = 0.8f,
    /**
     * Downward travel on the space bar that closes the keyboard, as a fraction of key height.
     *
     * Far larger than [flickDistanceRatio] -- three quarters of a key against three pixels --
     * and the reason is that the space bar is the one key with nothing below it. A flick down
     * from any letter has the rest of the keyboard to travel through; a flick down from space
     * leaves the board almost immediately, so the gesture is made mostly of the part where the
     * finger is already past the bottom row and heading for the screen edge. Asking for a
     * deliberate, full-key drag is what keeps the keyboard from vanishing when a thumb rolls
     * off the bottom of the space bar at the end of a sentence.
     *
     * It is also the cheapest possible mistake to make in the other direction: a user whose
     * dismissal did not register flicks again, while one whose keyboard vanished mid-sentence
     * has to find the text field and tap it. The asymmetry argues for the larger number.
     */
    val spaceDismissDistanceRatio: Float = 0.75f,
    /**
     * Sideways travel along the space bar that squashes the board, as a fraction of key *width*.
     *
     * In key widths rather than key heights because the gesture is horizontal and the space bar
     * is wide: this is a journey along the key the finger is already on, and a key width of it
     * is a little over a fifth of that bar.
     *
     * Distance alone does not separate this from the trackpad, which is why
     * [spaceSquashMinSpeed] exists. A finger may cross the space bar slowly on its way into the
     * trackpad -- that is the documented way in, and it is still PRESSED while it does so.
     */
    val spaceSquashDistanceRatio: Float = 1.0f,
    /**
     * How fast a sideways stroke must be to be a squash flick, in pixels per millisecond.
     *
     * This, not the distance, is what keeps the squash and the trackpad apart. Both start as a
     * sideways drag on the space bar while PRESSED, and both can cover a key width; what
     * differs is the manner. A flick is thrown -- the recorded flicks elsewhere in this file
     * leave at speeds far above this -- while a finger heading for the trackpad is being placed,
     * and creeps, because the user is waiting for the hold to take and watching for the cursor.
     *
     * 0.5 px/ms is 60px in 120ms on the target device: a deliberate throw, and roughly four
     * times the speed [trackpadSlowSpeed] already calls "slow, leave the gain alone".
     *
     * The measurement is over the whole gesture rather than the last sample, so it asks whether
     * the finger has been moving fast *since it went down*. A slow drift that speeds up at the
     * end -- a thumb settling into the trackpad and then panning -- never qualifies, because the
     * dawdle at the start is still in the average. The trackpad's own sampling, which does want
     * instantaneous speed, keeps its separate smoothed estimate.
     */
    val spaceSquashMinSpeed: Float = 0.5f,
    /**
     * |dx| must exceed this multiple of |dy| for a space-bar flick to count as horizontal.
     *
     * The mirror of [verticalDominance] and deliberately gentler. A sideways flick along the
     * space bar has nothing to be confused with: there is no glide from space and no secondary
     * to pull down, so the only competing reading is the downward dismissal, which this keeps
     * clear of. The dismissal's own guard is its much longer travel.
     */
    val horizontalDominance: Float = 1.5f,
    /**
     * Trackpad gain: pixels the granular cursor travels per pixel of finger movement.
     * Vertical is deliberately higher -- a line is a much longer journey than a character, and
     * there is less room to move vertically on a keyboard than horizontally.
     */
    val trackpadGainX: Float = 1.17f,
    val trackpadGainY: Float = 1.45f,

    /**
     * Pointer acceleration. Below the slow speed the gain is untouched, so slow movement keeps
     * its fine-grained feel exactly; from there it ramps up to the maximum at the fast speed,
     * letting a quick flick cross a long line. Speeds are in pixels of finger travel per
     * millisecond.
     *
     * The two axes have separate curves on purpose. There is far less vertical room on a
     * keyboard-sized trackpad than horizontal, so vertical has to reach its multiplier sooner
     * and go further to cover a document. The cost is that a fast diagonal drag is steeper than
     * the finger's own path -- acceleration bends the direction rather than only scaling it.
     */
    val trackpadSlowSpeed: Float = 0.15f,
    val trackpadFastSpeed: Float = 2.2f,
    val trackpadMaxAccel: Float = 4f,
    val trackpadSlowSpeedY: Float = 0.10f,
    val trackpadFastSpeedY: Float = 1.1f,
    val trackpadMaxAccelY: Float = 7f,
    /** A single move event is a noisy speed estimate, so it is smoothed. 1 = no smoothing. */
    val trackpadSpeedSmoothing: Float = 0.4f,
    /**
     * How long the finger may leave the glass in the middle of a glide and still be gliding.
     *
     * A glide is one continuous stroke in theory and very often is not in practice: a thumb
     * crossing the width of the keyboard skips, catches on a screen protector, or lifts for a
     * frame or two over a ridge in the glass. Without this, each of those ends the word early
     * and types whatever the first fragment happened to spell -- a word the user did not intend,
     * inserted while their finger was still moving toward the rest of it.
     *
     * The cost of being generous here is the opposite mistake: two deliberately separate glides
     * run together into one nonsense word. What separates the two cases is not really the pause
     * -- it is the distance, below -- but the pause is what makes it cheap to decide, because a
     * deliberate second word starts with a reach and a reach takes time.
     *
     * The value is a starting point and is expected to move. It is recorded with every gesture
     * so that a bank collected under one value still scores honestly under another, and any
     * Gesture Lab prose passage that gets glided collects the evidence that will set it -- no
     * instruction needed, since a lift either happened mid-word or it did not.
     */
    val glideResumeMs: Long = 120L,
    /**
     * How far from the lift the finger may come back down and still be the same glide, in key
     * widths.
     *
     * This is the discriminating half. A finger that skipped is a finger that never meant to
     * leave: it comes back within a key of where it went, usually much less. A finger starting
     * the next word has *travelled* -- to the first letter of something else, which on a
     * keyboard is a key width or more away far more often than not.
     */
    val glideResumeRadiusRatio: Float = 1.25f,
    /**
     * How far one nat of [FlickPrior] bias may move the flick thresholds, as a fraction.
     *
     * The prior is a belief about which reading is likelier *before* the finger has moved; the
     * thresholds are how much movement is demanded as proof. This is the exchange rate between
     * them, and it is one number rather than one per threshold so that the two axes cannot drift
     * into disagreeing about how much a nat is worth.
     *
     * 0.22 makes the [FlickPrior.Weights.noRoomBelow] case -- a bottom-row key with nothing under
     * it -- ask for roughly half the usual downward travel, and the mid-word penalty ask for
     * about a fifth more. Both are inside the range the bank scored flat across (every value from
     * 0.02 to 0.12 of [flickDistanceRatio] classified all 286 samples identically), which is the
     * point: the prior is moving the threshold within the region the evidence could not separate,
     * not overruling a boundary the evidence drew.
     */
    val flickPriorGain: Float = 0.22f,
    /**
     * The band the prior may move the thresholds inside, as multiples of their configured value.
     *
     * A clamp, not a preference, and it is the safety property of this whole mechanism. Whatever
     * the prior believes, a flick still has to be a real downward stroke and a glide still has to
     * be able to escape: no context can drive the required travel to zero, where a resting thumb
     * would type symbols, nor to infinity, where the gesture would stop existing. The bounds are
     * asymmetric because the failures are: a flick that does not register is retried, while a
     * flick that fires on a drifting thumb corrupts text the user was not looking at.
     */
    val flickPriorMinScale: Float = 0.45f,
    val flickPriorMaxScale: Float = 2.2f,
)

sealed interface GestureOutput {
    data class KeyHighlighted(val keyId: String?) : GestureOutput

    /**
     * A plain keypress. Carries the point the finger went *down* on as well as the key it
     * resolved to, because the tap decoder scores that point against every nearby letter and
     * "which key was nearest" has already thrown away most of what it needs. The down point
     * rather than the lift: they are the same point for an ordinary tap -- all 94 in the bank
     * travel zero pixels -- and where the thumb first landed is the aim, where it left is a
     * roll off the glass.
     */
    data class CommitPrimary(
        val keyId: String,
        val text: String,
        val x: Float,
        val y: Float,
    ) : GestureOutput
    data class CommitSecondary(val keyId: String, val text: String) : GestureOutput

    /** Flick is previewed but NOT committed, so it can still become a glide. */
    data class FlickPreview(val keyId: String, val text: String) : GestureOutput
    data object FlickPreviewCleared : GestureOutput

    /**
     * An upward flick is armed: releasing now would run [entry]. Nothing has happened yet.
     *
     * Its own case rather than a reuse of [FlickPreview], which carries the *text* of a secondary
     * and exists to drive the iPadOS glyph slide. What is armed here may be an action, which has
     * no text to slide and is drawn as an icon, and the distinction has to survive as far as the
     * renderer or it becomes a string to parse again -- the thing [PopupEntry] was introduced to
     * stop. The host shows it however that kind of entry is shown; the machine only says which.
     */
    data class UpFlickArmed(val keyId: String, val entry: PopupEntry) : GestureOutput
    data object UpFlickDisarmed : GestureOutput

    /**
     * The long-press popup opened. Entries are accents, edit actions, or both -- see [PopupEntry].
     */
    data class ShowAccents(val keyId: String, val entries: List<PopupEntry>) : GestureOutput {
        /** The accent labels only, for readers that predate actions sharing this popup. */
        val accents: List<String>
            get() = entries.filterIsInstance<PopupEntry.Accent>().map { it.text }
    }
    data class AccentHighlighted(val index: Int) : GestureOutput
    data class CommitAccent(val keyId: String, val text: String) : GestureOutput

    /**
     * An edit action was chosen from the popup.
     *
     * Deliberately not folded into [CommitAccent] with a magic string. What the service does with
     * the two could hardly be more different -- one inserts text, the other reaches into the
     * editor -- and a single case carrying both would put that decision in the service's string
     * parsing rather than in the type.
     */
    data class CommitAction(val keyId: String, val action: EditAction) : GestureOutput

    /**
     * A language was chosen from the globe key's menu.
     *
     * Its own case for the same reason [CommitAction] is: what the service does with it -- ask
     * the system to change input method -- has nothing to do with typing text, and folding it
     * into [CommitAccent] would mean the service deciding by inspecting a string whether to type
     * "English" or switch to it.
     */
    data class CommitLanguage(val keyId: String, val languageId: String) : GestureOutput
    data object HideAccents : GestureOutput

    data object GlideStarted : GestureOutput
    data class GlideUpdated(val path: List<PathPoint>) : GestureOutput

    /**
     * The finger left the glass mid-glide and the word has *not* been decided yet.
     *
     * Emitted instead of [GlideCompleted] at every lift, and followed by one or the other once
     * the resume window closes. The host keeps the trail on screen and types nothing: a word
     * shown and then replaced is exactly the flicker this window exists to prevent.
     */
    data object GlideSuspended : GestureOutput
    data class GlideCompleted(val path: List<PathPoint>, val strokeStarts: List<Int>) : GestureOutput

    /** Backspace was held: the host should start repeating deletions until it is released. */
    data object BackspaceRepeatStarted : GestureOutput
    data object BackspaceRepeatEnded : GestureOutput

    /** Swipe down on backspace: delete the word before the cursor. */
    data object BulkDelete : GestureOutput

    /**
     * Swipe up on backspace: delete the whole line before the cursor.
     *
     * The larger of the two destructive backspace gestures, and upward because that is the
     * direction that takes the finger *off* the board rather than further into it: a downward
     * stroke from backspace ends near the screen edge where a thumb naturally lands, so the
     * cheaper delete is the one that lives there.
     */
    data object DeleteLine : GestureOutput

    /** Swipe down on the space bar: put the keyboard away. */
    data object DismissKeyboard : GestureOutput

    /**
     * Swipe sideways along the space bar: squash the board toward that edge, or unsquash it.
     *
     * Carries the *direction of the flick* rather than the state it should land in. Which state
     * that is depends on the state the board is in now, which the machine deliberately does not
     * know: it sees one gesture on one geometry and has no business holding the board's mode
     * across the gestures that change it. [com.offlinekeyboard.ime.layout.Squash.flicked] owns
     * that transition, in one place, where the view keeps the state.
     */
    data class SquashFlick(val toward: SquashDirection) : GestureOutput

    data object TrackpadStarted : GestureOutput

    /**
     * Move the granular cursor by this many pixels. The gain is already applied.
     *
     * The trackpad drives the *marker*, not the caret: the marker is the thing the finger is
     * directly controlling, so it moves smoothly and continuously in screen space. The caret
     * follows it afterwards, as closely as the text allows.
     */
    data class TrackpadPan(val dx: Float, val dy: Float) : GestureOutput

    /** Selection began: the anchor is dropped wherever the caret currently sits. */
    data object SelectionStarted : GestureOutput
    data object TrackpadEnded : GestureOutput

    data class SpecialKey(val type: KeyType, val keyId: String) : GestureOutput

    /**
     * Every completed gesture, whatever it turned into, with the raw path attached.
     *
     * Emitted unconditionally rather than only while collecting: the state machine is the only
     * place that sees a whole gesture and the verdict it reached in the same breath, and it must
     * not have a second, differently-behaving code path that only runs during data collection.
     * Deciding whether a gesture is worth keeping belongs to whoever is listening.
     */
    data class GestureCaptured(val trace: GestureTrace) : GestureOutput
}

enum class GestureState {
    IDLE,
    PRESSED,
    FLICK,

    /**
     * An upward stroke long enough to run the key's popup primary, still able to become a glide.
     *
     * Separate from [FLICK] rather than a direction flag on it, because the two commit different
     * kinds of thing -- a secondary character against a [PopupEntry] that may be an action -- and
     * escape into a glide at different distances. Sharing the state would mean every branch in
     * the machine asking which way the finger went, which is the shape the space bar's three
     * gestures already showed to be unreadable.
     */
    UP_FLICK,

    /**
     * An upward flick that was armed and then pulled back: a keypress with a long stroke in it.
     *
     * Types the letter on release like [PRESSED], but cannot become a glide, because the path
     * that would start one was spent going up and coming back rather than travelling toward
     * another key. Without it, changing your mind about an action would type a word.
     */
    RECALLED,
    GLIDE,

    /**
     * A glide whose finger has lifted, waiting to see whether it comes straight back.
     *
     * The state exists so that the leniency is one transition in the machine rather than a timer
     * bolted onto the view. Everything downstream -- the replay harness, the sweep, the bank --
     * then sees the same gesture the phone saw, including the gap in the middle of it.
     */
    GLIDE_LIFTED,
    ACCENTS,
    TRACKPAD,
    SELECTING,

    /**
     * Backspace held down, deleting repeatedly. A swipe from here deletes in bulk: up takes the
     * line above the cursor, down takes the word behind it.
     */
    BACKSPACE,

    /**
     * The gesture has already done its work and is waiting for the finger to lift. Without it a
     * swipe that clears the line would also delete a character when released.
     */
    SPENT,
}

/**
 * One state machine per pointer. Pure logic: no Android types, an injected timestamp on every
 * event, and no internal clock, so every transition is deterministically testable on the JVM.
 *
 * The transition that matters most is FLICK -> GLIDE. A downward flick is never committed while
 * the finger is still down, so a swipe that starts downward and keeps going becomes a glide
 * rather than typing a stray symbol.
 */
class TouchFsm(
    private val geometry: LayoutGeometry,
    private val config: GestureConfig = GestureConfig(),
    /**
     * Scores how plausible a flick is from a given key in a given context. See [FlickPrior].
     *
     * Injected with a default so every existing caller -- and every replay of the bank -- keeps
     * working unchanged, and so a test can hand in a prior with all its weights at zero to get
     * exactly the old machine back.
     */
    private val prior: FlickPrior = FlickPrior(),
    /**
     * What the editor looked like when the finger went down.
     *
     * Passed at construction rather than read during the gesture because the machine has no way
     * to reach an editor and must not grow one: it is pure so that a recorded path replays to
     * the same verdict on a laptop years later. The host builds this once per press.
     *
     * Defaults to [FlickPrior.Context.UNKNOWN], under which every context-dependent term
     * abstains -- so a host that has not been taught to supply it behaves exactly as before
     * rather than under some half-informed guess.
     */
    private val context: FlickPrior.Context = FlickPrior.Context.UNKNOWN,
    /**
     * Which letters words head for after each first letter, for [FlickCone].
     *
     * Defaults to [WordStarts.UNKNOWN], which treats every letter below a key as contested.
     * That still opens the bottom row, which has no letters below it, and leaves the rest of
     * the board as it was. The service passes the real table once the lexicon has loaded.
     */
    private val words: WordStarts = WordStarts.UNKNOWN,
) {
    var state: GestureState = GestureState.IDLE
        private set

    private var origin: KeyRect? = null
    private var down: PathPoint? = null
    private val path = mutableListOf<PathPoint>()
    private var pathLength = 0f
    private var accentIndex = 0
    private var trackpadAnchor: PathPoint? = null
    /** Smoothed finger speed in px/ms, for pointer acceleration. */
    private var trackpadSpeed = 0f

    /** Indices into [path] where each stroke after the first begins. Empty for one stroke. */
    private val strokeStarts = mutableListOf<Int>()
    /** Where and when the finger left the glass, while a glide is suspended. */
    private var lift: PathPoint? = null

    /**
     * The downward travel this gesture must show to be a flick, in pixels.
     *
     * Not a constant any more: it is [GestureConfig.flickDistanceRatio] scaled by what
     * [FlickPrior] makes of the key the finger is on and the caret it is typing at. A stroke
     * down from the bottom row, where no word can follow, is asked for less proof; a stroke made
     * in the middle of a word is asked for more.
     *
     * Everything downstream reads this rather than the raw ratio, so the arming test, the
     * animation and the release check all move together. Two of those reading a scaled threshold
     * and the third reading the configured one is how a gesture comes to commit a symbol the
     * animation never showed.
     */
    private val flickDistance get() = config.flickDistanceRatio * geometry.keyHeight * flickScale

    /**
     * How much more vertical than horizontal a stroke must be to read as a flick.
     *
     * Scaled by the same multiplier as the distance, because it is the same question asked on the
     * other axis: [GestureConfig.verticalDominance] is what separates a flick on `o` from gliding
     * `ok`, and on a key where no `ok` is possible there is nothing for it to separate. A
     * bottom-row flick that hooks sideways at the lift -- which the bank says real flicks do,
     * several through 80 degrees -- should not be refused for want of a competing reading.
     *
     * It moves in the same direction as the distance, not the opposite one. Both are demands for
     * proof, so a prior that says "less proof needed" must relax both; relaxing one while
     * tightening the other would leave the total difficulty roughly unchanged and make the whole
     * mechanism a no-op that looks like it is working.
     */
    private val verticalDominance get() = config.verticalDominance * flickScale

    /**
     * The glyph travel, deliberately *not* scaled.
     *
     * [GestureConfig.flickTravelRatio] is not a threshold and decides nothing -- it is the
     * distance the symbol has to slide to reach the letter's place, which is a fact about where
     * the view draws glyphs. Scaling it by a belief about the user's intent would make the
     * animation run at different speeds in different sentences, which is the one thing a direct
     * manipulation must never do: the symbol is supposed to sit under the thumb and be dragged.
     */
    private val flickTravel get() = config.flickTravelRatio * geometry.keyHeight

    /**
     * How much harder or easier this key's flick is, as a multiple of the configured thresholds.
     *
     * Below 1 means the prior favours a flick, so less travel is demanded. Above 1 means it
     * favours a word or a tap, so more is. Computed once per gesture at the press, and cached:
     * it depends on the origin key and the context, neither of which can change while a finger
     * is down, so recomputing it per move sample would burn work to get the same answer.
     */
    private var flickScale = 1f

    /**
     * How far off vertical this key's flick may lean, beyond [verticalDominance]. See [FlickCone].
     *
     * Computed once at the press, like [flickScale], and for the same reason: it depends only on
     * the key and the words, neither of which change while the finger is down.
     */
    private var cone = FlickCone.CLOSED

    /**
     * Whether ([dx], [dy]) is far enough down the widened part of the cone to arm a flick.
     *
     * Asks for more travel than [flickDistance], as [GestureConfig.flickConeMinTravelRatio]
     * explains. It is scaled by [flickScale] like every other demand for proof, so the mid-word
     * penalty still applies here.
     */
    private fun inWideCone(dx: Float, dy: Float): Boolean =
        cone.contains(dx, dy) &&
            dy > config.flickConeMinTravelRatio * geometry.keyHeight * flickScale

    /**
     * Turns nats of bias into a threshold multiplier.
     *
     * Exponential rather than linear, and that is what makes the nats mean something: a bias is a
     * log-odds, so adding a constant number of nats should multiply the demanded evidence by a
     * constant factor, whatever the starting point. A linear map would make the same nat worth
     * more at one end of the range than the other and would need a separate rule to stop it going
     * negative.
     */
    private fun scaleFor(bias: Float): Float =
        exp(-bias * config.flickPriorGain)
            .coerceIn(config.flickPriorMinScale, config.flickPriorMaxScale)
    private val glideDistance get() = config.glideDistanceRatio * geometry.keyUnit
    private val flickToGlideDistance get() = config.flickToGlideRatio * geometry.keyUnit

    /**
     * How far up the finger must travel to arm the key's popup primary.
     *
     * Not scaled by [FlickPrior], deliberately. That prior was fitted to the downward gesture --
     * its terms are about symbols, words hanging below a key, and the bottom row having no room
     * underneath -- and none of them are evidence about an upward stroke. Feeding it the wrong
     * direction would move this threshold by a belief that was never measured against it, and
     * the failure would be silent: the gesture would simply grow unreliable on some keys.
     */
    private val upFlickDistance get() = config.upFlickDistanceRatio * geometry.keyHeight
    private val upFlickToGlideDistance get() = config.upFlickToGlideRatio * geometry.keyUnit
    private val bulkDeleteDistance get() = config.bulkDeleteDistanceRatio * geometry.keyHeight
    private val glideResumeRadius get() = config.glideResumeRadiusRatio * geometry.keyUnit
    private val spaceDismissDistance get() = config.spaceDismissDistanceRatio * geometry.keyHeight

    /**
     * How far sideways a space-bar flick must travel to squash the board.
     *
     * Measured in *unsquashed* key widths, so the gesture asks for the same physical distance
     * whichever state the board is in. Reading [LayoutGeometry.keyUnit] would shrink the
     * threshold along with the keys, making the already-squashed board a third easier to flick
     * than the full-width one -- so a thumb travelling to the far side of a narrow space bar
     * would unsquash it by accident.
     */
    private val spaceSquashDistance
        get() = config.spaceSquashDistanceRatio * geometry.unsquashedKeyUnit

    /** Deadline the host should schedule a [onLongPressTimeout] callback for, or null. */
    val longPressDeadline: Long? get() = down?.let { it.t + config.longPressMs }

    /** True while a lifted glide is waiting to see whether the finger comes back. */
    val isSuspended: Boolean get() = state == GestureState.GLIDE_LIFTED

    /** The key the finger came down on, or null between gestures. */
    val originKeyId: String? get() = origin?.key?.id

    /**
     * How far the origin key's glyphs should have slid toward their flicked positions, 0..1.
     *
     * The iPadOS animation follows the finger, so the view needs the progress of a flick that has
     * not happened yet -- not just the verdict once it has. It is a property rather than a
     * [GestureOutput] because it changes on every touch sample and means nothing to anyone but
     * the renderer: emitting it would push a per-sample event through the service and into the
     * gesture bank.
     *
     * The conditions are exactly [onMoveWhilePressed]'s, minus the distance that one is testing,
     * so the glyphs move only while a flick is genuinely still possible. A sideways drag or a key
     * with no secondary leaves them at rest, and a flick that grows into a glide drops back to 0
     * and lets them fall home.
     */
    val flickProgress: Float get() = (flickPull / flickTravel).coerceIn(0f, 1f)

    /**
     * True while releasing would commit the secondary. Derived from where the finger is now, not
     * latched when it first crossed: pulling back up disarms it again, which is what makes the
     * gesture something you can change your mind about halfway through.
     *
     * Safe against the bank: of 208 recorded gestures, none crossed 0.20 of a key height and then
     * lifted back above it. Real flicks retract 0.0px at the lift by median and 0.5px at the 90th
     * percentile, and the shortest one recorded still ended 33.7px down against the 24px this
     * asks for -- so no gesture anyone has actually made changes its verdict by being read here
     * instead of at the crossing.
     */
    val flickArmed: Boolean get() = flickPull > flickDistance

    /** Downward travel currently being read as a flick, in pixels. 0 when none is. */
    private val flickPull: Float
        get() {
            if (state != GestureState.PRESSED && state != GestureState.FLICK) return 0f
            val key = origin ?: return 0f
            if (key.key.secondary == null || key.key.type == KeyType.BACKSPACE) return 0f
            val start = down ?: return 0f
            val now = path.lastOrNull() ?: return 0f
            val dy = now.y - start.y
            if (dy <= 0f) return 0f
            // Dominance gates getting into a flick, not staying in one: several recorded flicks
            // hook through 80 degrees at the lift, and the symbol must not fly home because the
            // thumb rolled sideways on its way off the glass.
            // The same two tests arming uses, so the symbol only slides while the stroke could
            // still arm, and does slide on a key whose widened cone this stroke is inside.
            val dx = now.x - start.x
            if (state == GestureState.PRESSED &&
                dy <= verticalDominance * abs(dx) &&
                !cone.contains(dx, dy)
            ) {
                return 0f
            }
            return dy
        }

    fun onDown(x: Float, y: Float, t: Long): List<GestureOutput> {
        reset()
        // Nearest rather than exact: a press that lands in the gap between two keys is a press
        // the person meant, not one to throw away. See LayoutGeometry.keyForPress.
        val key = geometry.keyForPress(x, y) ?: return emptyList()
        origin = key
        // Once per press, for the whole gesture. The key is now known and the context cannot
        // change under a finger that is already down, so this is the moment the answer exists
        // and the last moment it can change.
        flickScale = scaleFor(prior.bias(key, geometry, context))
        cone = if (key.key.secondary != null) {
            FlickCone.of(key, geometry, words, config)
        } else {
            FlickCone.CLOSED
        }
        val p = PathPoint(x, y, t)
        down = p
        path += p
        state = GestureState.PRESSED
        return listOf(GestureOutput.KeyHighlighted(key.key.id))
    }

    fun onLongPressTimeout(t: Long): List<GestureOutput> {
        if (state != GestureState.PRESSED) return emptyList()
        val key = origin ?: return emptyList()
        return when {
            key.key.type == KeyType.SPACE -> {
                state = GestureState.TRACKPAD
                trackpadAnchor = path.last()
                trackpadSpeed = 0f
                listOf(GestureOutput.TrackpadStarted)
            }
            key.key.type == KeyType.BACKSPACE -> {
                state = GestureState.BACKSPACE
                listOf(GestureOutput.BackspaceRepeatStarted)
            }
            // Deliberately not applied to the space bar above: holding space and starting to
            // move before the timeout is the normal way into the trackpad, and there is no
            // glide competing for that gesture. The conflict is only ever accents versus a
            // slow-starting glide.
            key.key.popup.isNotEmpty() && !hasDrifted() -> {
                state = GestureState.ACCENTS
                val grid = popupGrid(key)
                // The slot the finger is already over, measured, not assumed to be slot 0.
                //
                // The popup opens under a thumb that has not moved, so the entry it opens on must
                // be the one that thumb is on. Hardcoding 0 here meant the first move event -- a
                // pixel of tremor, arriving before any deliberate movement -- recomputed the slot
                // and the highlight jumped. Where that slot *is* is [popupLeft]'s business: it
                // anchors a single action under the key so this lands on it. Asking the same
                // function the moves ask is what keeps the two from drifting apart again.
                accentIndex = grid.entryAt(path.last().x, path.last().y)
                listOf(
                    // The arranged entries, in cell order, so anyone drawing or asserting on
                    // them is looking at the same popup the finger is moving over.
                    GestureOutput.ShowAccents(key.key.id, grid.entries),
                    GestureOutput.AccentHighlighted(accentIndex),
                )
            }
            else -> emptyList()
        }
    }

    /** True once the finger has travelled far enough that it is no longer holding still. */
    private fun hasDrifted(): Boolean {
        val start = down ?: return false
        val now = path.lastOrNull() ?: return false
        val limit = config.longPressSlopRatio * geometry.keyHeight
        return hypot(now.x - start.x, now.y - start.y) > limit
    }

    fun onMove(x: Float, y: Float, t: Long): List<GestureOutput> {
        if (state == GestureState.IDLE) return emptyList()
        val previous = path.lastOrNull() ?: return emptyList()
        val p = PathPoint(x, y, t)
        pathLength += hypot(x - previous.x, y - previous.y)
        path += p

        val start = down ?: return emptyList()
        val dx = x - start.x
        val dy = y - start.y

        return when (state) {
            GestureState.PRESSED -> onMoveWhilePressed(dx, dy)
            GestureState.FLICK -> onMoveWhileFlicking(dx, dy)
            GestureState.UP_FLICK -> onMoveWhileUpFlicking(dx, dy)
            // A recalled flick can still be armed again -- the finger is on the key and may go
            // back up -- but it can no longer become a glide. Re-using the arming test alone
            // gives exactly that, since the glide test lives after it in onMoveWhilePressed and
            // is never reached from here.
            GestureState.RECALLED -> onMoveWhileRecalled(dx, dy)
            GestureState.GLIDE -> listOf(GestureOutput.GlideUpdated(path.toList()))
            GestureState.ACCENTS -> onMoveWhileShowingAccents(x, y)
            GestureState.TRACKPAD, GestureState.SELECTING -> onMoveWhileTrackpad(x, y, t)
            GestureState.BACKSPACE -> bulkDeleteIfSwiped(dy)
            GestureState.GLIDE_LIFTED, GestureState.IDLE, GestureState.SPENT -> emptyList()
        }
    }

    private fun onMoveWhilePressed(dx: Float, dy: Float): List<GestureOutput> {
        val key = origin ?: return emptyList()
        val isDownward = dy > 0
        val verticallyDominant = abs(dy) > verticalDominance * abs(dx)

        if (key.key.type == KeyType.BACKSPACE) {
            // The requirement is "hold, then swipe", but a swipe without the hold means the same
            // thing and there is nothing else a vertical swipe from backspace could be.
            return bulkDeleteIfSwiped(dy)
        }
        if (key.key.type == KeyType.SPACE) return spaceFlick(dx, dy)
        // Either the configured narrow test, exactly as it always was, or the widened cone this
        // key earns from having no word below it on that side. See [FlickCone].
        val flicked = isDownward && (
            (verticallyDominant && abs(dy) > flickDistance) || inWideCone(dx, dy)
            )
        if (flicked && key.key.secondary != null) {
            state = GestureState.FLICK
            return listOf(GestureOutput.FlickPreview(key.key.id, key.key.secondary))
        }
        // Upward, and far enough to mean it. Tested before the glide below even though a stroke
        // this long has already passed the glide distance: arming is not committing, and
        // [onMoveWhileUpFlicking] hands the gesture straight back to the glide if the finger
        // keeps going. Letting the glide claim it first would make the action unreachable on
        // every letter key, since the glide fires at 1.2 key widths and this needs 0.75 key
        // heights of travel to arm at all.
        val upFlick = key.key.flickUp
        if (!isDownward && verticallyDominant && abs(dy) > upFlickDistance && upFlick != null) {
            state = GestureState.UP_FLICK
            return listOf(GestureOutput.UpFlickArmed(key.key.id, upFlick))
        }
        // Only letters can start a word: a swipe off shift or 123 is a mis-hit, not a glide.
        if (pathLength > glideDistance && key.key.type == KeyType.CHARACTER) {
            state = GestureState.GLIDE
            return listOf(GestureOutput.GlideStarted, GestureOutput.GlideUpdated(path.toList()))
        }
        return emptyList()
    }

    /**
     * Deletes the line above the cursor on an upward swipe, the word behind it on a downward
     * one, once. The gesture goes SPENT rather than back to PRESSED so that lifting the finger
     * afterwards does not also delete a character, and so a wobbling finger that crosses the
     * threshold repeatedly cannot eat word after word.
     *
     * Both directions share [bulkDeleteDistance]: they are the same stroke on the same key and
     * carry the same risk, so they ask the same commitment of the finger. Which one fires is
     * decided by the sign of [dy] alone -- backspace has no secondary and no popup, so there is
     * nothing else a vertical stroke from it could be competing with.
     */
    private fun bulkDeleteIfSwiped(dy: Float): List<GestureOutput> {
        val output = when {
            dy <= -bulkDeleteDistance -> GestureOutput.DeleteLine
            dy >= bulkDeleteDistance -> GestureOutput.BulkDelete
            else -> return emptyList()
        }
        val wasRepeating = state == GestureState.BACKSPACE
        state = GestureState.SPENT
        return buildList {
            if (wasRepeating) add(GestureOutput.BackspaceRepeatEnded)
            add(output)
        }
    }

    /**
     * The three things a drag from the space bar can mean, decided in one place.
     *
     * Down puts the keyboard away; left and right squash the board toward that edge. All three
     * go SPENT rather than back to PRESSED, for the reason [bulkDeleteIfSwiped] does: the
     * gesture has already done its work, and lifting afterwards must not also type a space.
     * SPENT is what makes each of these happen once however much the finger wobbles across the
     * threshold on its way off the glass.
     *
     * Only reachable while PRESSED, so a live trackpad is never disturbed: once the hold has
     * taken, the machine is in TRACKPAD and this is not consulted at all. The case that needs
     * care is the one *before* that -- a finger sliding along the space bar while it waits for
     * the hold -- and what separates the two there is speed, not distance. See
     * [GestureConfig.spaceSquashMinSpeed]; a creeping finger keeps its trackpad, a thrown one
     * squashes the board.
     *
     * Downward is tested first. It is the gesture with the longer reach and the stricter
     * dominance, so the order only matters for a diagonal that satisfies both, and of those two
     * readings the destructive-feeling one -- the board disappearing -- should be the one that
     * has to be asked for unambiguously. Testing it first and requiring [verticalDominance] of
     * it means a diagonal drag squashes rather than dismisses.
     */
    private fun spaceFlick(dx: Float, dy: Float): List<GestureOutput> {
        // Both readings need the throw, for the same reason: a finger creeping away from the
        // space bar is a finger on its way into the trackpad, whichever direction it creeps.
        if (!isThrown()) return emptyList()
        if (dy > spaceDismissDistance && abs(dy) > config.verticalDominance * abs(dx)) {
            state = GestureState.SPENT
            return listOf(GestureOutput.DismissKeyboard)
        }
        if (abs(dx) > spaceSquashDistance &&
            abs(dx) > config.horizontalDominance * abs(dy)
        ) {
            state = GestureState.SPENT
            val toward = if (dx > 0f) SquashDirection.RIGHT else SquashDirection.LEFT
            return listOf(GestureOutput.SquashFlick(toward))
        }
        return emptyList()
    }

    /**
     * Whether the finger has been moving fast enough, since it went down, to be a flick.
     *
     * Straight-line speed from the down point rather than path speed: a squash flick is a stroke
     * in one direction, so the distance that matters is how far it got, and a finger that
     * wandered about the space bar before ending up a key to the left has not thrown anything.
     */
    private fun isThrown(): Boolean {
        val start = down ?: return false
        val now = path.lastOrNull() ?: return false
        val dt = now.t - start.t
        // A device that reports no elapsed time cannot support a claim about speed. Refusing is
        // the safe answer: the gesture simply stays a press, and the next sample decides.
        if (dt <= 0L) return false
        return hypot(now.x - start.x, now.y - start.y) / dt > config.spaceSquashMinSpeed
    }

    /**
     * Requirement 4: a flick that keeps travelling becomes a glide. The flick was only ever a
     * preview, so nothing needs to be undone in the editor -- just clear the preview.
     */
    private fun onMoveWhileFlicking(dx: Float, dy: Float): List<GestureOutput> {
        // Inside a side of the cone that was opened because no word goes that way, distance is
        // not evidence of a word. A long, confident flick down from `h` is still a flick. The
        // stroke has to turn out of the cone first, toward somewhere a word could be going.
        if (cone.contains(dx, dy)) return emptyList()
        if (pathLength > flickToGlideDistance) {
            state = GestureState.GLIDE
            return listOf(
                GestureOutput.FlickPreviewCleared,
                GestureOutput.GlideStarted,
                GestureOutput.GlideUpdated(path.toList()),
            )
        }
        return emptyList()
    }

    /**
     * An armed upward flick, which can still lose the gesture two ways.
     *
     * A finger that keeps travelling is writing a word, and one that comes back down below the
     * threshold has changed its mind. Both hand the stroke back rather than committing anything,
     * which is what makes the gesture something a hand can start and abandon -- the same promise
     * the downward flick makes by only ever previewing.
     *
     * Only a key that can start a word goes to GLIDE. Elsewhere -- shift, the mode switch --
     * there is no word to escape into, so a long stroke simply disarms and the release does
     * nothing, rather than beginning a glide from a key that cannot spell.
     */
    /**
     * A finger that armed an upward flick, pulled back, and may yet go up again.
     *
     * Only the arming test, deliberately. The glide is gone for the rest of this gesture -- see
     * [GestureState.RECALLED] -- and the downward flick is too: a finger that has been a key
     * height above the key and come back down to it is not making the short, deliberate downward
     * stroke that commits a secondary, and reading it as one would type a symbol on the way back
     * from an action the user decided against.
     */
    private fun onMoveWhileRecalled(dx: Float, dy: Float): List<GestureOutput> {
        val key = origin ?: return emptyList()
        val upFlick = key.key.flickUp
        if (upFlick != null &&
            dy < 0 && abs(dy) > verticalDominance * abs(dx) && abs(dy) > upFlickDistance
        ) {
            state = GestureState.UP_FLICK
            return listOf(GestureOutput.UpFlickArmed(key.key.id, upFlick))
        }
        // A finger that has left the key entirely is writing a word after all, and the glide is
        // allowed again. What RECALLED withholds is the *spent stroke*, not the glide itself: a
        // finger still on the key it pressed has gone up and come back and should type a letter,
        // but one now over a different key has plainly gone somewhere, and refusing it here
        // would strand a word that happened to begin with a moment's hesitation.
        if (key.key.type == KeyType.CHARACTER && geometry.keyAt(lastX(), lastY()) != key) {
            state = GestureState.GLIDE
            return listOf(GestureOutput.GlideStarted, GestureOutput.GlideUpdated(path.toList()))
        }
        return emptyList()
    }

    private fun lastX(): Float = path.lastOrNull()?.x ?: 0f
    private fun lastY(): Float = path.lastOrNull()?.y ?: 0f

    private fun onMoveWhileUpFlicking(dx: Float, dy: Float): List<GestureOutput> {
        val key = origin ?: return emptyList()
        // Pulled back toward the key: read from where the finger is now rather than latched at
        // the crossing, for the reason [flickArmed] is -- changing your mind halfway through is
        // part of the gesture, not a failure of it.
        //
        // Tested before the escape below, and that order is load-bearing. A finger that goes up
        // a key height and comes back has *travelled* more than the escape distance without ever
        // heading for another key, so a path-length test reached first would read a retreat as a
        // word -- and the retreat is precisely the gesture that means "not that after all".
        //
        // RECALLED rather than PRESSED for the same reason: the stroke it already spent must not
        // be handed to the glide test the moment the machine goes back to being a press.
        //
        // Only for a finger still on the key it pressed. A stroke that has drifted sideways onto
        // a *different* key is a word leaving -- "ca" departs `c` up and to the left, which is
        // exactly the shape this test would otherwise catch on dominance alone -- so it falls
        // through to the escape below and becomes the glide it is.
        val onOwnKey = geometry.keyAt(lastX(), lastY()) == key
        if (onOwnKey && (-dy <= upFlickDistance || abs(dy) <= verticalDominance * abs(dx))) {
            state = GestureState.RECALLED
            return listOf(GestureOutput.UpFlickDisarmed)
        }
        if (pathLength > upFlickToGlideDistance) {
            if (key.key.type != KeyType.CHARACTER) {
                state = GestureState.SPENT
                return listOf(GestureOutput.UpFlickDisarmed)
            }
            state = GestureState.GLIDE
            return listOf(
                GestureOutput.UpFlickDisarmed,
                GestureOutput.GlideStarted,
                GestureOutput.GlideUpdated(path.toList()),
            )
        }
        return emptyList()
    }

    private fun onMoveWhileShowingAccents(x: Float, y: Float): List<GestureOutput> {
        val key = origin ?: return emptyList()
        if (key.key.popup.isEmpty()) return emptyList()
        val index = popupGrid(key).entryAt(x, y)
        if (index == accentIndex) return emptyList()
        accentIndex = index
        return listOf(GestureOutput.AccentHighlighted(index))
    }

    /**
     * Where this key's popup sits and what is in each cell.
     *
     * The state machine and the renderer both build it from the same function with the same
     * inputs, rather than each computing a layout of its own. Two copies of this arithmetic is
     * exactly how the popup came to highlight one entry and commit another.
     */
    private fun popupGrid(key: KeyRect) = PopupGrid.of(key, geometry)

    /**
     * Requirement 5: two-dimensional cursor movement. Vertical steps are emitted as their own
     * events so the host can send DPAD_UP/DOWN -- only the text view knows where lines wrap.
     */
    /**
     * A second finger tapped while the trackpad is active. The caret stops being a caret and
     * becomes one end of a selection: the anchor stays where it is and subsequent movement
     * drags the other end.
     */
    fun onSecondaryTap(): List<GestureOutput> {
        if (state != GestureState.TRACKPAD) return emptyList()
        state = GestureState.SELECTING
        return listOf(GestureOutput.SelectionStarted)
    }

    /**
     * Requirement 5. Pans the granular cursor by the finger's movement, scaled by the gain.
     *
     * Nothing here knows about characters or lines: this is pure screen-space motion, which is
     * what keeps the marker smooth. Turning that position into a caret position is the
     * service's job, and it does it by watching where the app reports the caret to be.
     */
    private fun onMoveWhileTrackpad(x: Float, y: Float, t: Long): List<GestureOutput> {
        val anchor = trackpadAnchor ?: return emptyList()
        val dx = x - anchor.x
        val dy = y - anchor.y
        val dt = (t - anchor.t).coerceAtLeast(1L)
        trackpadAnchor = PathPoint(x, y, t)
        if (dx == 0f && dy == 0f) return emptyList()

        val instant = hypot(dx, dy) / dt
        trackpadSpeed += (instant - trackpadSpeed) * config.trackpadSpeedSmoothing

        return listOf(
            GestureOutput.TrackpadPan(
                dx * config.trackpadGainX * accelerationFor(
                    trackpadSpeed,
                    config.trackpadSlowSpeed,
                    config.trackpadFastSpeed,
                    config.trackpadMaxAccel,
                ),
                dy * config.trackpadGainY * accelerationFor(
                    trackpadSpeed,
                    config.trackpadSlowSpeedY,
                    config.trackpadFastSpeedY,
                    config.trackpadMaxAccelY,
                ),
            ),
        )
    }

    /**
     * Maps finger speed to a gain multiplier.
     *
     * Squared rather than linear so the curve leaves slow movement alone: precise positioning
     * should feel exactly as it did before acceleration existed, and only deliberate fast
     * movement should cover ground.
     */
    private fun accelerationFor(speed: Float, lo: Float, hi: Float, max: Float): Float {
        if (hi <= lo) return 1f
        val ramp = ((speed - lo) / (hi - lo)).coerceIn(0f, 1f)
        return 1f + (max - 1f) * ramp * ramp
    }

    fun onUp(x: Float, y: Float, t: Long): List<GestureOutput> {
        val key = origin
        // Read from the lift point itself, not from the last move: a device does not always send
        // a move at the position the finger left from, and those last few pixels are exactly the
        // part of the pull that decides it.
        val armedAtRelease = down?.let { y - it.y > flickDistance } ?: false
        // The same question asked upward. Kept separate from [armedAtRelease] rather than folded
        // into an absolute distance, because the two thresholds differ by a factor of thirty --
        // see [GestureConfig.upFlickDistanceRatio] -- and one variable meaning "far enough in
        // whichever direction" would quietly hold the upward gesture to the downward number.
        val upArmedAtRelease = down?.let { it.y - y > upFlickDistance } ?: false
        val result: List<GestureOutput> = when (state) {
            // A recalled flick types the letter, exactly as the press it went back to being.
            GestureState.PRESSED, GestureState.RECALLED -> tapOutput(key)
            GestureState.FLICK -> {
                val secondary = key?.key?.secondary
                when {
                    secondary == null -> emptyList()
                    // Pulled down and then brought back up: the symbol never landed, so this was
                    // a keypress with a wobble in it.
                    !armedAtRelease -> listOf(GestureOutput.FlickPreviewCleared) + tapOutput(key)
                    else -> listOf(
                        GestureOutput.FlickPreviewCleared,
                        GestureOutput.CommitSecondary(key.key.id, secondary),
                    )
                }
            }
            GestureState.UP_FLICK -> {
                // Re-read from the lift point, as the downward flick does: the device does not
                // always send a move at the position the finger left from, and on a gesture
                // that commits without a popup those last pixels are the whole verdict.
                when (val entry = if (upArmedAtRelease) key?.key?.flickUp else null) {
                    is PopupEntry.Accent ->
                        listOf(GestureOutput.CommitAccent(key!!.key.id, entry.text))
                    is PopupEntry.Action ->
                        listOf(GestureOutput.CommitAction(key!!.key.id, entry.action))
                    // A language is never offered here -- Key.flickUp refuses the globe key --
                    // and a blank is a hole. Both fall through to the disarm below.
                    is PopupEntry.Language, PopupEntry.Blank, null ->
                        listOf(GestureOutput.UpFlickDisarmed)
                }
            }
            GestureState.GLIDE -> return suspendGlide(x, y, t)
            GestureState.ACCENTS -> {
                // The grid's entries, not the key's declared ones: the index counts cells in the
                // arrangement the finger was moving over, and the two differ by the row wrapping
                // and its padding.
                val entries = key?.let { popupGrid(it).entries }.orEmpty()
                buildList {
                    add(GestureOutput.HideAccents)
                    when (val chosen = entries.getOrNull(accentIndex)) {
                        is PopupEntry.Accent ->
                            add(GestureOutput.CommitAccent(key!!.key.id, chosen.text))
                        is PopupEntry.Action ->
                            add(GestureOutput.CommitAction(key!!.key.id, chosen.action))
                        // Releasing on the language already in use is deliberately still a
                        // commit rather than nothing: the service treats switching to the
                        // current language as a no-op, and deciding that here would put a
                        // second opinion about what is current inside the state machine.
                        is PopupEntry.Language ->
                            add(GestureOutput.CommitLanguage(key!!.key.id, chosen.id))
                        // A padding cell, or a popup that vanished under the finger: releasing
                        // on a hole does nothing, which is what a hole should do.
                        PopupEntry.Blank, null -> Unit
                    }
                }
            }
            GestureState.TRACKPAD, GestureState.SELECTING -> listOf(GestureOutput.TrackpadEnded)
            // The repeat already deleted; releasing must not delete once more.
            GestureState.BACKSPACE -> listOf(GestureOutput.BackspaceRepeatEnded)
            GestureState.GLIDE_LIFTED, GestureState.IDLE, GestureState.SPENT -> emptyList()
        }
        // Whichever direction this gesture went, [capture] is asked the question that matches
        // it. Passing the downward flag from an upward gesture would record every up-flick as
        // NONE, and the bank would show the feature being used as though it never fired.
        val captured = capture(
            PathPoint(x, y, t),
            if (state == GestureState.UP_FLICK) upArmedAtRelease else armedAtRelease,
        )
        reset()
        return result + listOfNotNull(captured) + GestureOutput.KeyHighlighted(null)
    }

    /**
     * A glide whose finger has lifted. The word is not decided here: the machine waits, and the
     * host either brings the finger back with [onResume] or closes the window with
     * [onGlideResumeTimeout].
     *
     * The lift point joins the path like any other sample. It is the last thing the finger did
     * before the gap, so a decoder bridging that gap starts from where the finger actually was
     * rather than from the last sample the device happened to send.
     */
    private fun suspendGlide(x: Float, y: Float, t: Long): List<GestureOutput> {
        val up = PathPoint(x, y, t)
        if (path.lastOrNull() != up) path += up
        lift = up
        state = GestureState.GLIDE_LIFTED
        return listOf(GestureOutput.GlideSuspended)
    }

    /** When the host should call [onGlideResumeTimeout], or null when nothing is suspended. */
    val glideResumeDeadline: Long? get() = lift?.let { it.t + config.glideResumeMs }

    /**
     * Whether a finger going down at this point continues the suspended glide.
     *
     * Three conditions, and the third is the one that is easy to leave out. Time and distance
     * both describe a finger that never meant to leave, but a finger coming back down on
     * backspace or the space bar has plainly finished the word however quickly it got there --
     * and reading that as a continuation would swallow the very keypress meant to correct it.
     */
    fun canResume(x: Float, y: Float, t: Long): Boolean {
        val from = lift ?: return false
        if (state != GestureState.GLIDE_LIFTED) return false
        if (t - from.t > config.glideResumeMs) return false
        if (hypot(x - from.x, y - from.y) > glideResumeRadius) return false
        return geometry.keyAt(x, y)?.key?.type == KeyType.CHARACTER
    }

    /**
     * The finger came back. The gap is left in the path as a gap -- nothing is interpolated
     * here -- and the index it resumes at is recorded, so a reader can tell a stroke boundary
     * from an ordinary long sample and score the leniency afterwards.
     */
    fun onResume(x: Float, y: Float, t: Long): List<GestureOutput> {
        if (state != GestureState.GLIDE_LIFTED) return emptyList()
        val previous = path.lastOrNull()
        strokeStarts += path.size
        val p = PathPoint(x, y, t)
        if (previous != null) pathLength += hypot(x - previous.x, y - previous.y)
        path += p
        lift = null
        state = GestureState.GLIDE
        return listOf(GestureOutput.GlideUpdated(path.toList()))
    }

    /**
     * The window closed with no finger back on the glass: the word is whatever was drawn.
     *
     * Alone among the entry points here it takes no timestamp, and deliberately so. Everything
     * else is an event with a time; this is the *absence* of one, and the moment the window
     * happened to close is not part of the gesture. The gesture ended when the finger left, and
     * that sample is already in the path -- stamping it with the timeout's clock instead would
     * stretch every interrupted glide by the length of the window and, on Android, mix two
     * different clocks while doing it.
     */
    fun onGlideResumeTimeout(): List<GestureOutput> {
        if (state != GestureState.GLIDE_LIFTED) return emptyList()
        val up = lift ?: return emptyList()
        val completed = GestureOutput.GlideCompleted(path.toList(), strokeStarts.toList())
        val captured = capture(up, armed = false, verdict = GestureVerdict.GLIDE)
        reset()
        return listOfNotNull(completed, captured, GestureOutput.KeyHighlighted(null))
    }

    /**
     * What releasing a key with no gesture on it does.
     *
     * Return is not a character key even though it carries "\n" as its primary: what it does
     * depends on the field being typed into, which only the service can see.
     */
    private fun tapOutput(key: KeyRect?): List<GestureOutput> = when {
        key == null -> emptyList()
        key.key.type == KeyType.CHARACTER || key.key.type == KeyType.SPACE -> {
            val at = down ?: PathPoint(key.centerX, key.centerY, 0L)
            listOf(GestureOutput.CommitPrimary(key.key.id, key.key.primary, at.x, at.y))
        }
        else -> listOf(GestureOutput.SpecialKey(key.key.type, key.key.id))
    }

    /**
     * Snapshots the gesture that just ended, for [GestureOutput.GestureCaptured].
     *
     * The up point is appended unless the device already sent that exact sample. Android usually
     * sends a move at the lift position first, but not always, and the last few pixels before
     * the lift are exactly the part of a downward swipe that decides what it was. The timestamp
     * counts as part of "exact": a tap that never moved still has to record when it ended, or a
     * replay of it has no duration and cannot tell a tap from a long press.
     */
    private fun capture(
        up: PathPoint,
        armed: Boolean,
        /** Set when the gesture is being closed out from a state that is no longer live. */
        verdict: GestureVerdict? = null,
    ): GestureOutput.GestureCaptured? {
        val key = origin ?: return null
        val last = path.lastOrNull() ?: return null
        val full = if (last == up) path.toList() else path + up
        return GestureOutput.GestureCaptured(
            GestureTrace(
                startKeyId = key.key.id,
                verdict = verdict ?: when (state) {
                    // RECALLED typed the letter, so it records as the tap it turned out to be.
                    GestureState.PRESSED, GestureState.RECALLED -> GestureVerdict.TAP
                    GestureState.FLICK -> if (armed) GestureVerdict.FLICK else GestureVerdict.TAP
                    GestureState.GLIDE -> GestureVerdict.GLIDE
                    // An armed upward flick that was still armed at the lift committed the
                    // popup's primary entry, so it records as ACCENT -- the same verdict the
                    // long press that commits the same entry produces. Not a new name, for the
                    // reason [GestureVerdict] gives: the bank reads these back with valueOf, and
                    // a name invented here would crash every build and every stored run that
                    // predates it. One that disarmed committed nothing and is NONE.
                    GestureState.UP_FLICK ->
                        if (armed) GestureVerdict.ACCENT else GestureVerdict.NONE
                    GestureState.ACCENTS -> GestureVerdict.ACCENT
                    GestureState.TRACKPAD, GestureState.SELECTING -> GestureVerdict.TRACKPAD
                    GestureState.GLIDE_LIFTED, GestureState.BACKSPACE, GestureState.SPENT,
                    GestureState.IDLE -> GestureVerdict.NONE
                },
                layoutId = geometry.layout.id,
                widthPx = geometry.widthPx,
                keyUnitPx = geometry.keyUnit,
                keyHeightPx = geometry.keyHeight,
                thresholds = GestureThresholds.of(config),
                path = full,
                strokeStarts = strokeStarts.toList(),
            ),
        )
    }

    /**
     * The touch stream was taken away -- another view claimed it, or the window went. A
     * suspended glide is dropped rather than committed: a gesture the system interrupted is not
     * evidence that the user finished a word.
     */
    fun onCancel(): List<GestureOutput> {
        val wasGlide = state == GestureState.GLIDE || state == GestureState.GLIDE_LIFTED
        // Must still emit TrackpadEnded: the service holds a physical shift key down for the
        // duration of a selection, and would otherwise never release it.
        val wasTrackpad = state == GestureState.TRACKPAD || state == GestureState.SELECTING
        val wasRepeating = state == GestureState.BACKSPACE
        reset()
        return buildList {
            if (wasGlide) add(GestureOutput.FlickPreviewCleared)
            if (wasTrackpad) add(GestureOutput.TrackpadEnded)
            if (wasRepeating) add(GestureOutput.BackspaceRepeatEnded)
            add(GestureOutput.KeyHighlighted(null))
        }
    }

    private fun reset() {
        state = GestureState.IDLE
        origin = null
        down = null
        path.clear()
        pathLength = 0f
        accentIndex = 0
        trackpadAnchor = null
        trackpadSpeed = 0f
        // Back to neutral rather than to the last gesture's value: between gestures there is no
        // origin key to have an opinion about, and a stale multiplier would be applied to the
        // next press for the few events before onDown recomputes it.
        flickScale = 1f
        cone = FlickCone.CLOSED
        strokeStarts.clear()
        lift = null
    }

    /** Keys the glide path passed through, nearest-centre per sample, de-duplicated. */
    fun pathKeys(points: List<PathPoint>): List<String> =
        points.mapNotNull { geometry.nearestKey(it.x, it.y)?.key?.id }
            .fold(mutableListOf<String>()) { acc, id ->
                if (acc.lastOrNull() != id) acc.add(id)
                acc
            }

    @Suppress("unused")
    private fun stepsFor(distance: Float, step: Float): Int = (distance / step).roundToInt()
}
