package com.offlinekeyboard.ime.capture

import com.offlinekeyboard.ime.gesture.GestureIntent

/** One thing the lab asks for, once. */
data class Drill(
    val id: String,
    val startKeyId: String,
    val intent: GestureIntent,
    /** What the keyboard should end up producing, if it reads the gesture correctly. */
    val expected: String,
    val instruction: String,
    val why: String,
)

/**
 * The drill catalogue.
 *
 * Every pair here is a *collision*: the symbol and the word start on the same key and set off the
 * same way, so the two gestures are genuinely hard to tell apart. Easy examples would fill the
 * bank quickly and teach the heuristic nothing -- the only samples worth a person's time are the
 * ones sitting on the boundary.
 *
 * The test for a real collision is narrower than it first looks, and an early version of this
 * file got it wrong. It is not enough for a word to start with a downward stroke, nor even to
 * end below where it started. "was" does both -- w sits half a key right of a, and s sits
 * directly below w -- and it is still not confusable, because the path turns a corner at a and
 * comes back rightward. Within a few dozen pixels it no longer resembles a flick at all.
 *
 * The dangerous words are **two keys long, with the second below the first**. Two keys means the
 * whole glide is one stroke with no corner in it, so there is never a later part to give the
 * game away; below means the stroke points where a flick points. Both halves are required, and
 * it is the length that does most of the work.
 *
 * In key-width units across the keyboard, the top row sits at 0.5, 1.5, 2.5 … and the home row is
 * inset by half a key, so it sits at 1.0, 2.0, 3.0 …
 *
 *     q    w    e    r    t    y    u    i    o    p
 *       a    s    d    f    g    h    j    k    l
 *          z    x    c    v    b    n    m
 *
 * which leaves exactly these two-key words hanging below a letter with a flick on it:
 *
 *     i (7.5) -> m (8.0)   "I'm"    two rows down, half a key across
 *     i (7.5) -> n (6.9)   "in"     two rows down, half a key across
 *     o (8.5) -> k (8.0)   "ok"     one row down, half a key across
 *     o (8.5) -> n (6.9)   "on"     two rows down, one and a half across
 *     u (6.5) -> m (8.0)   "um"     two rows down, one and a half across
 *     e (2.5) -> x (3.0)   "ex"     two rows down, half a key across
 */
object Drills {

    /** A symbol flick, and the word glides that leave the same key the same way and stop there. */
    data class Pair(
        val startKeyId: String,
        val symbol: String,
        val words: List<String>,
        val why: String,
    )

    val PAIRS = listOf(
        Pair(
            startKeyId = "i",
            symbol = "8",
            words = listOf("I'm", "in"),
            why = "i sits directly above k, m and n: the swipe down is identical for two rows",
        ),
        Pair(
            startKeyId = "o",
            symbol = "9",
            words = listOf("ok", "on"),
            why = "o sits directly above k, and just right of n",
        ),
        Pair(
            startKeyId = "u",
            symbol = "7",
            words = listOf("um"),
            why = "u drops onto m two rows below, and the word ends there",
        ),
        Pair(
            startKeyId = "e",
            symbol = "3",
            words = listOf("ex"),
            why = "e sits directly above x, two rows down and half a key across",
        ),
    )

    /**
     * Flicks with no word hanging below them. They are still worth collecting: half of telling a
     * symbol from a word is knowing what an unhurried, uncontested flick looks like, and the
     * apostrophe on k is the one this keyboard's owner reaches for most.
     */
    val SYMBOL_ONLY = listOf(
        Triple("k", "'", "the apostrophe -- the symbol most often wanted mid-word"),
        Triple("a", "@", "home row, with nothing below it to be confused with"),
        Triple("d", "$", "home row"),
        Triple("m", ":", "bottom row: there is no room to keep travelling downward"),
    )

    /**
     * Builds a session queue.
     *
     * The three kinds alternate *within a key* rather than being blocked together, and this is
     * the part that most affects whether the bank is worth anything. A run of eight flicks is not
     * eight samples of a flick, it is one sample of a rhythm the hand falls into, and a threshold
     * fitted to that works only for someone doing drills. Interleaving also means every key
     * carries taps, flicks and glides from the same minute of the same hand, so the boundary
     * between them is fitted to one column of evidence instead of three unrelated ones.
     *
     * Every key gets tap drills, including the ones with no word hanging below them. The flick
     * threshold trades off against taps rather than against glides, so a bank without taps gives
     * a sweep no reason not to drive that threshold to zero -- and it will, since every sample it
     * can see is improved by doing so. The first collected session had exactly this hole.
     */
    fun session(reps: Int, random: kotlin.random.Random = kotlin.random.Random.Default): List<Drill> =
        buildList {
            PAIRS.shuffled(random).forEach { pair ->
                repeat(reps) { rep ->
                    add(symbolDrill(pair.startKeyId, pair.symbol, pair.why))
                    val word = pair.words[rep % pair.words.size]
                    add(
                        Drill(
                            id = "${pair.startKeyId}:word:$word",
                            startKeyId = pair.startKeyId,
                            intent = GestureIntent.WORD,
                            expected = word,
                            instruction = "Glide the word  $word",
                            why = pair.why,
                        ),
                    )
                    add(tapDrill(pair.startKeyId))
                }
            }
            SYMBOL_ONLY.shuffled(random).forEach { (key, symbol, why) ->
                repeat(reps) {
                    add(symbolDrill(key, symbol, why))
                    add(tapDrill(key))
                }
            }
        }

    private fun symbolDrill(key: String, symbol: String, why: String) = Drill(
        id = "$key:symbol",
        startKeyId = key,
        intent = GestureIntent.SYMBOL,
        expected = symbol,
        instruction = "Swipe down on ${key.uppercase()} to type  $symbol",
        why = why,
    )

    private fun tapDrill(key: String) = Drill(
        id = "$key:letter",
        startKeyId = key,
        intent = GestureIntent.LETTER,
        expected = key,
        instruction = "Just tap ${key.uppercase()}",
        why = "an ordinary tap at your ordinary speed -- this is what stops the flick threshold " +
            "being tuned down into your normal typing",
    )
}
