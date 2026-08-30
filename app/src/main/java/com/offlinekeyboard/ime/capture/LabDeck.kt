package com.offlinekeyboard.ime.capture

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

/**
 * What to type next, remembered across launches.
 *
 * The lab used to start at passage zero every time it opened. For a rig you sit down at with a
 * cable attached that is fine -- the session is the visit. For something carried around and
 * opened in a queue for four minutes it is the difference between collecting a corpus and
 * collecting the first passage of a corpus, several hundred times.
 *
 * So the deck is a shuffled pass over the whole library that survives being closed, and the
 * position in it is the only thing worth persisting: it is small, it is cheap to write on every
 * advance, and if it is ever lost the cost is one repeated passage.
 *
 * The collision drill is dealt into the pass rather than left to be chosen. It is the passage
 * that answers the question the bank exists for, and it is also the least pleasant to type --
 * left as a menu entry beside forty passages of ordinary English it would quietly stop being
 * typed at all, and the flick-versus-glide boundary would stop gaining evidence while the
 * bank went on growing.
 */
class LabDeck(
    private val prefs: SharedPreferences,
    private val library: List<Passage>,
) {

    private var seed: Long
        get() {
            val existing = prefs.getLong(KEY_SEED, 0L)
            if (existing != 0L) return existing
            val fresh = Random.nextLong()
            prefs.edit().putLong(KEY_SEED, fresh).apply()
            return fresh
        }
        set(value) = prefs.edit().putLong(KEY_SEED, value).apply()

    private var pass: Int
        get() = prefs.getInt(KEY_PASS, 0)
        set(value) = prefs.edit().putInt(KEY_PASS, value).apply()

    private var cursor: Int
        get() = prefs.getInt(KEY_CURSOR, 0)
        set(value) = prefs.edit().putInt(KEY_CURSOR, value).apply()

    /**
     * One pass over the library, in an order that changes every pass, with a drill dealt in
     * before every [DRILL_EVERY] passages.
     *
     * Derived from the seed and the pass number rather than stored, because a stored order is a
     * list that can disagree with the library it indexes -- and the library changes whenever a
     * line is added to the corpus.
     */
    private fun slots(pass: Int): List<Int> = slots(library.size, seed, pass)

    /**
     * Built once and held, because the activity asks for the current passage on every keystroke
     * and the drill is randomly generated -- rebuilding it per frame would reshuffle the
     * passage out from under the finger typing it.
     */
    private var built: Passage? = null

    fun current(): Passage = built ?: build().also { built = it }

    private fun build(): Passage {
        val slots = slots(pass)
        val slot = slots.getOrElse(cursor.coerceIn(0, slots.size - 1)) { DRILL }
        return if (slot == DRILL) {
            // Two reps rather than four: at four the drill is a hundred and twenty tokens, which
            // is a sitting rather than a break, and a drill that gets abandoned halfway collects
            // only the keys that happened to be shuffled to the front.
            Passages.collisions(reps = 2, random = Random(seed + pass * 1_000L + cursor))
        } else {
            library[slot]
        }
    }

    /** The same passage again, freshly shuffled if it is the drill. */
    fun again() {
        built = null
    }

    fun advance() {
        val slots = slots(pass)
        if (cursor + 1 >= slots.size) {
            pass += 1
            cursor = 0
        } else {
            cursor += 1
        }
        built = null
    }

    /** "3 of 48 this round", for the line under the passage. */
    val position: Int get() = cursor + 1
    val total: Int get() = slots(pass).size
    val round: Int get() = pass + 1

    companion object {

        /** A slot holding the drill rather than an index into the library. */
        const val DRILL = -1

        /**
         * The pure form of the deal, so the two properties that matter can be checked without a
         * phone: every passage in the library appears exactly once in a pass, and a drill is
         * never more than [DRILL_EVERY] passages away.
         */
        fun slots(librarySize: Int, seed: Long, pass: Int): List<Int> {
            if (librarySize <= 0) return listOf(DRILL)
            val order = (0 until librarySize).shuffled(Random(seed + pass))
            return buildList {
                order.forEachIndexed { i, index ->
                    if (i % DRILL_EVERY == 0) add(DRILL)
                    add(index)
                }
            }
        }

        private const val KEY_SEED = "deck.seed"
        private const val KEY_PASS = "deck.pass"
        private const val KEY_CURSOR = "deck.cursor"

        /** A collision drill before every fifth passage of prose. */
        const val DRILL_EVERY = 5

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences("gesture-lab", Context.MODE_PRIVATE)
    }
}
