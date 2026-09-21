package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.LayoutGeometry

/**
 * How much a downward stroke *from this key, in this context* should be believed to be a flick.
 *
 * The thresholds in [GestureConfig] arbitrate one question -- symbol or word -- with one set of
 * numbers for every key on the board and every moment of a sentence. The second half of that is
 * a stronger claim than the evidence supports: a downward stroke made after a space and the same
 * stroke made in the middle of a word are not equally likely to have meant a digit, and nothing
 * in the machine has ever known the difference.
 *
 * ## What the bank refused to support, and why it is written down here
 *
 * The first version of this class also varied the thresholds *by key*, on the reasoning that a
 * swipe down from `m` cannot be the first leg of a glide -- there is no keyboard underneath it --
 * while a swipe down from `i` very much can, because `I'm` and `in` both live there. The
 * reasoning is still sound. It was simply not what the bank was seeing:
 *
 *  - Real flicks travel 0.13 to 1.05 key heights. [GestureConfig.flickDistanceRatio] asks for
 *    0.025. Distance is not the binding constraint on *any* key, so making it laxer on `m`
 *    rescued nothing and making it stricter on `i` only refused good flicks.
 *  - The six flicks `docs/GESTURE_BANK.md` records as going missing on `m` went missing at the
 *    old 0.45 ratio, eighteen times the current one, and were fixed by lowering it.
 *  - Scored against the bank, the per-key terms moved five gestures and made five of them wrong.
 *
 * Those terms are still here, as [Weights.noRoomBelow] and [Weights.roomBelow], defaulted to
 * zero with the measurement beside them. Keeping a disproved term visible and inert is cheaper
 * than rediscovering the argument later and having to collect the evidence again.
 *
 * What is left is the contextual half, which the bank cannot score at all: a recorded path has
 * no caret behind it. That is an honest limitation rather than a hidden one -- see
 * `GestureBankReplayTest.reportFlickPrior`, which says so in the report rather than quietly
 * scoring a term it has no data for.
 *
 * So this is not a new gate in front of the old one. Nothing here can veto a flick or force one.
 * It returns a **log-odds bias in nats**, positive meaning "a flick is more plausible here than
 * the neutral case", and [TouchFsm] spends it by moving its own thresholds within a clamped band.
 * The geometry still decides; the prior only says how much geometry to insist on. That matters
 * because it keeps one arbiter rather than two, and because every escape hatch the machine
 * already has -- pulling back up above [GestureConfig.flickDistanceRatio], a flick growing into a
 * glide past [GestureConfig.flickToGlideRatio] -- keeps working untouched no matter what is
 * returned here.
 *
 * Nats, and not some fresh 0..1 "confidence", because the rest of this keyboard already reasons
 * in them: [com.offlinekeyboard.ime.tap.SpatialModel] scores touch in nats and
 * [com.offlinekeyboard.ime.tap.WordIndex] scores priors in the same nats so that
 * [com.offlinekeyboard.ime.tap.TapDecoder] can subtract one from the other and have the
 * difference mean something. A second, differently-scaled notion of confidence in the same
 * keyboard is how two numbers that look comparable quietly stop being so.
 *
 * ## Why every term is a prior and not a rule
 *
 * Each term below could be written as a special case -- "bottom row always flicks", "never flick
 * mid-word". Written that way they stack into a decision tree nobody can score, where the order
 * of the branches is load-bearing and no single sample can be attributed to a cause. Added as
 * log-odds they compose commutatively, any one of them can be set to zero to measure what it was
 * worth, and the total is bounded by construction. That is the difference between something the
 * gesture bank can settle and something it can only be consulted about.
 *
 * Pure and Android-free, like [TouchFsm] itself: the context arrives as a plain [Context] value
 * built by the service, so every rule here is decided on the JVM by `FlickPriorTest` rather than
 * by swiping at a phone.
 */
class FlickPrior(private val weights: Weights = Weights()) {

    /**
     * What the editor looks like at the moment the finger goes down.
     *
     * Deliberately tiny, and deliberately not an `InputConnection`. The state machine cannot be
     * allowed to reach into the editor -- it has no clock and no Android types precisely so that
     * a recorded gesture replays identically on a laptop -- so the host reads the caret once, at
     * the press, and hands over the three facts that bear on this decision.
     *
     * "At the press" rather than "at the move" is itself a choice: the context cannot change
     * under a finger that is already down, so reading it later would only add a chance of reading
     * it *differently* part-way through one gesture.
     */
    data class Context(
        /**
         * The character immediately before the caret, or null at the very start of a field.
         *
         * One character rather than the whole word because that is all any rule here asks about,
         * and a keyboard that pulls more text out of the editor than it needs is a keyboard with
         * a larger answer to give when somebody asks what it can see.
         */
        val before: Char?,
        /**
         * Whether a word is being composed right now -- letters held as composing text that have
         * not been committed.
         *
         * Distinct from [before] being a letter, and the difference is the one that matters: a
         * caret sitting after `cat` at the end of a finished sentence has a letter behind it and
         * no word in progress, while `hel` mid-composition has both. The second is much stronger
         * evidence that the next gesture continues a word.
         */
        val composing: Boolean = false,
    ) {
        companion object {
            /** No editor, or nothing known about it: every term that needs context abstains. */
            val UNKNOWN = Context(before = null, composing = false)
        }
    }

    /**
     * The strength of each term, in nats.
     *
     * Named and injectable rather than inlined so `tools/gestures.sh analyse` can sweep them the
     * way it sweeps [GestureConfig], and so a term can be zeroed to find out whether it was
     * paying for itself. The defaults below are deliberately modest: this is a thumb on the
     * scale, and a prior that can dominate the geometry has stopped being a prior.
     */
    data class Weights(
        /**
         * Awarded when there is no room below the key for a word to continue into.
         *
         * **Zero, because the bank says the idea is right and the mechanism is wrong.**
         *
         * The argument for it was strong and is still true: a glide is a path across letters, a
         * bottom-row key has no letters beneath it, so a downward stroke from `m` cannot be the
         * first leg of a word. `docs/GESTURE_BANK.md` even names `m` as where flicks went
         * missing. What the argument gets wrong is *which threshold* was stopping them.
         *
         * Every `m` flick in the bank travels at least 0.23 key heights, and the flick threshold
         * is 0.025 -- so distance was never what refused them. The six that went missing did so
         * at the old 0.45 ratio, which is 18 times the current one and was lowered years before
         * this term was written. Scoring the bank with this term at 2.5 moves five gestures and
         * makes all five *wrong*: nothing on `m` is rescued, because nothing on `m` needed
         * rescuing, while `a`, `e` and `o` lose flicks to the matching penalty below.
         *
         * Kept as a named, zeroed weight rather than deleted because the reasoning is sound and
         * the day a threshold binds on the bottom row again -- a denser layout, a taller key,
         * a phone that reports travel differently -- this is the term to turn on, with a
         * measurement already written down to check it against.
         */
        val noRoomBelow: Float = 0f,
        /**
         * Awarded when there *is* room below, so a word could run downward through this key.
         *
         * Zero for the same measurement as [noRoomBelow], and this is the half that actually did
         * the damage. Real flicks in the bank leave at 0.13 to 1.05 key heights, which is five to
         * forty times the 0.025 the machine asks for, so *raising* the demand on the keys with
         * words below them cannot separate a flick from a glide -- it only starts refusing
         * flicks that were never ambiguous. All five regressions the bank reported came from
         * here.
         *
         * The thing that genuinely separates `9` from `ok` on this bank is
         * [GestureConfig.verticalDominance], not distance. See NOTES.md.
         */
        val roomBelow: Float = 0f,
        /**
         * Awarded when the caret is mid-word: letters behind it, a word being composed.
         *
         * The user's own rule, and the one with the clearest mechanism. Digits and `$` do not
         * appear in the middle of words; letters do. So a downward stroke made while a word is
         * open is more likely to be that word continuing than a symbol interrupting it.
         *
         * Smaller in magnitude than [noRoomBelow] because it is a tendency rather than a fact
         * about the layout, and because it is the term most able to do harm: it fires on every
         * key rather than on a handful, and the gesture it discourages is one the user may
         * genuinely be making.
         */
        val midWord: Float = -0.9f,
        /**
         * Awarded when the character behind the caret is itself a digit.
         *
         * The strongest contextual signal available, and the cheapest to justify: somebody who
         * has just typed `1` and swipes down on `w` is typing `12`, not starting a word. Numbers
         * arrive in runs far more reliably than letters do.
         */
        val afterDigit: Float = 1.6f,
        /**
         * Awarded at a word boundary -- after a space, after punctuation, at the start of a
         * field -- when the secondary is a digit.
         *
         * A digit at a word boundary is an ordinary thing to want; a digit welded onto the end of
         * a word is not. This is the positive counterpart of [midWord] rather than a duplicate of
         * it: the two fire in disjoint contexts, so a stroke is never both encouraged and
         * discouraged for the same reason.
         */
        val boundaryDigit: Float = 0.7f,
        /**
         * Cancels [midWord] for the secondaries that genuinely belong inside words.
         *
         * Without this the mid-word rule would break the single most common flick in English.
         * The apostrophe on `k` exists for `don't`, `it's` and `I'm`, all of which are typed
         * *mid-word by definition* -- `Passages.SYMBOL_ONLY` calls it "the symbol most often
         * wanted mid-word" -- so the context that makes `$` implausible is the exact context an
         * apostrophe is for. A rule that ignored this would be a regression sold as an
         * improvement, and it would not show up in the bank because the drill types `'` from a
         * standing start.
         *
         * Hyphen is here for the same reason, one step weaker in practice but identical in kind.
         */
        val wordInternalSymbol: Float = 1.1f,
        /**
         * The ceiling on the total, in either direction.
         *
         * A clamp rather than a hope. Every weight above is a judgement, and judgements compose:
         * three of them agreeing must not be able to push the machine somewhere no single piece
         * of evidence would justify. Bounding the sum is what lets each weight be argued about
         * on its own merits without having to reason about every combination of the rest.
         */
        val limit: Float = 3.0f,
    )

    /**
     * The bias for a stroke leaving [key], in nats. Positive favours the flick.
     *
     * Returns 0 -- perfect neutrality, the machine's own unmodified thresholds -- for every key
     * that has no secondary to flick to, and for the space bar, whose downward gesture is
     * dismissal and is governed by [GestureConfig.spaceDismissDistanceRatio] instead.
     */
    fun bias(key: KeyRect, geometry: LayoutGeometry, context: Context): Float {
        val secondary = key.key.secondary ?: return 0f
        if (key.key.type != KeyType.CHARACTER) return 0f

        var nats = roomTerm(key, geometry)
        nats += contextTerm(secondary, context)
        return nats.coerceIn(-weights.limit, weights.limit)
    }

    /**
     * What the layout underneath this key says.
     *
     * Measured from the geometry rather than read off a list of row indices, and that is the
     * whole point of doing it here. A hardcoded "bottom row" is wrong the moment the board is
     * squashed, wrong on the symbol planes, and wrong on any layout added later -- and it would
     * be wrong *silently*, because the flick would simply get harder to make and nobody would
     * know which line to blame. Asking the geometry how much keyboard is left below the key is
     * the same question, answered in a way that stays true.
     *
     * The measurement is the vertical gap between this key's bottom and the lowest letter on the
     * board, in key heights. Letters rather than all keys: a glide runs through letters, so the
     * space bar and the mode switches below the bottom row are not somewhere a word can go.
     */
    private fun roomTerm(key: KeyRect, geometry: LayoutGeometry): Float {
        val lettersBottom = geometry.letterAreaBottom
        val room = (lettersBottom - key.bottom) / geometry.keyHeight

        // Less than a third of a key of letters below: nothing can be glided into, so a downward
        // stroke has no competing reading. The third is slack for the row gap and for a key whose
        // rectangle sits a hair above the lowest one, not a tunable -- at zero this would fail to
        // fire on a layout whose bottom row is a pixel short.
        if (room < NO_ROOM_KEY_HEIGHTS) return weights.noRoomBelow

        // Room below, and a word may well run through it. The penalty grows with the room
        // available but saturates: two rows below is not twice as ambiguous as one, because the
        // words that collide are two keys long and stop at the first of them.
        val depth = (room / SATURATION_KEY_HEIGHTS).coerceAtMost(1f)
        return weights.roomBelow * depth
    }

    /** What the caret says. */
    private fun contextTerm(secondary: String, context: Context): Float {
        val before = context.before
        val isDigit = secondary.length == 1 && secondary[0].isDigit()
        val wordInternal = secondary in WORD_INTERNAL

        // Nothing known, and nothing being composed: abstain outright.
        //
        // This is the case that has to be exactly zero rather than merely small, and it took a
        // bug to see why. Treating "no character behind the caret" as a word boundary is a
        // perfectly good reading of an empty field -- but it is the *same* value the host sends
        // when it could not read the editor at all, and it is what every replay of the gesture
        // bank sends, since a recorded path has no editor behind it. Rewarding digits here would
        // therefore have scored the whole bank under thresholds no phone ever used, and the
        // harness check in GestureBankReplayTest would have started failing for a reason that
        // had nothing to do with the state machine.
        //
        // An empty field genuinely is a word boundary, and the lost encouragement is worth
        // roughly a fifth of a pixel of travel on the first keystroke of a field. That is a real
        // cost and a tiny one, and it buys the property that an uninformed host is never quietly
        // typing under different rules from an informed one.
        if (before == null && !context.composing) return 0f

        if (before != null && before.isDigit()) return weights.afterDigit

        val midWord = context.composing || (before != null && before.isLetter())
        if (!midWord) {
            // A boundary: space, punctuation, the start of a field. Digits are ordinary here.
            return if (isDigit) weights.boundaryDigit else 0f
        }

        // Mid-word. The apostrophe and the hyphen are what mid-word flicks are *for*, so the
        // penalty is cancelled for them rather than applied and then apologised for.
        if (wordInternal) return weights.midWord + weights.wordInternalSymbol
        return weights.midWord
    }

    companion object {
        /**
         * Below this much letter-keyboard beneath a key, there is nowhere for a glide to go.
         *
         * A third of a key height: comfortably more than the row gap (a quarter of a key on the
         * target phone) so that a bottom-row key reads as having no room even though the gap
         * below it is not exactly zero, and comfortably less than a full row so that a genuine
         * row underneath is never mistaken for absence.
         */
        const val NO_ROOM_KEY_HEIGHTS = 0.33f

        /**
         * Room at which the ambiguity penalty stops growing, in key heights.
         *
         * One row and a bit. The confusable words in `Passages.COLLISIONS` are all two keys
         * long -- that is what makes them confusable, since a longer word turns a corner and
         * gives itself away -- so what matters is whether *a* letter sits below, not how many.
         */
        const val SATURATION_KEY_HEIGHTS = 1.5f

        /**
         * Secondaries that belong inside words rather than between them.
         *
         * Kept as a set of the characters themselves rather than as a list of key ids, so it
         * stays true when a layout moves the apostrophe somewhere else -- which the Chinese and
         * symbol planes already do.
         */
        val WORD_INTERNAL = setOf("'", "-", "\u2019")
    }
}
