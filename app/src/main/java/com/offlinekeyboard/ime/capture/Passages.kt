package com.offlinekeyboard.ime.capture

import com.offlinekeyboard.ime.gesture.GestureIntent

/** One thing a passage asks for, once, in the order it is read. */
data class Target(
    val id: String,
    val intent: GestureIntent,
    val startKeyId: String,
    /** What the keyboard should produce if it reads the gesture the way it was meant. */
    val expected: String,
    /** What is printed in the passage. Usually [expected]; the symbol targets differ. */
    val display: String = expected,
)

data class Passage(
    val id: String,
    val title: String,
    /** Why this passage is worth someone's thumb. Shown under the title. */
    val note: String,
    val targets: List<Target>,
) {
    val kinds: Set<GestureIntent> get() = targets.map { it.intent }.toSet()
}

/**
 * What the Gesture Lab asks for, as passages to be typed rather than instructions to be obeyed.
 *
 * The first version of the lab asked for one gesture at a time -- "Swipe down on O to type 9",
 * then "Glide the word ok", then "Just tap O" -- and the labels it collected were unimpeachable,
 * because it asked before it recorded. What it could not do is collect a *gesture someone would
 * actually make*. Reading an instruction, finding the key, and performing the named movement is
 * a different motor task from typing, and it produces a different movement: slower, more
 * deliberate, aimed at a key the eye has just located rather than one the thumb already knows.
 * Thresholds fitted to those are thresholds fitted to a person doing an exercise.
 *
 * A passage restores the thing the instructions destroyed, which is *flow*. The eye reads ahead,
 * the thumb moves without being told where, and the gestures come out at typing speed with
 * typing's sloppiness in them -- which is the only kind the shipped keyboard will ever see. The
 * labels survive the change intact, because the passage still says what each gesture is meant to
 * be before it is made.
 *
 * Two kinds of passage, for two different questions:
 *
 * - **Collisions** are token streams -- `u u 7 um u 7 on 9 o` -- and they exist because the
 *   flick-versus-glide boundary lives on a handful of keys and nowhere else. Prose would spend
 *   a hundred gestures to collect three useful ones. Every token here is on the boundary.
 * - **Prose** is real English, because glide decoding is only half geometry: the other half is
 *   which words exist and how often they are written, and a decoder is only ever as good as the
 *   word distribution it is scored against. A passage of random words would measure the shape
 *   matching and nothing else, and would flatter it, because random words are further apart than
 *   real ones.
 */
object Passages {

    /** A symbol flick, and the word glides that leave the same key the same way and stop there. */
    data class Collision(
        val startKeyId: String,
        val symbol: String,
        val words: List<String>,
        val why: String,
    )

    /**
     * Every pair here is a genuine collision: the symbol and the word start on the same key and
     * set off the same way, so the two gestures are hard to tell apart.
     *
     * The test for one is narrower than it first looks. It is not enough for a word to start
     * with a downward stroke, nor even to end below where it started. "was" does both -- w sits
     * half a key right of a, and s sits directly below w -- and it is still not confusable,
     * because the path turns a corner at a and comes back rightward. Within a few dozen pixels
     * it no longer resembles a flick at all.
     *
     * The dangerous words are **two keys long, with the second below the first**. Two keys means
     * the whole glide is one stroke with no corner in it, so there is never a later part to give
     * the game away; below means the stroke points where a flick points.
     *
     * In key-width units across the keyboard, the top row sits at 0.5, 1.5, 2.5 ... and the home
     * row is inset by half a key, so it sits at 1.0, 2.0, 3.0 ...
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
    val COLLISIONS = listOf(
        Collision(
            startKeyId = "i",
            symbol = "8",
            words = listOf("I'm", "in"),
            why = "i sits directly above k, m and n: the swipe down is identical for two rows",
        ),
        Collision(
            startKeyId = "o",
            symbol = "9",
            words = listOf("ok", "on"),
            why = "o sits directly above k, and just right of n",
        ),
        Collision(
            startKeyId = "u",
            symbol = "7",
            words = listOf("um"),
            why = "u drops onto m two rows below, and the word ends there",
        ),
        Collision(
            startKeyId = "e",
            symbol = "3",
            words = listOf("ex"),
            why = "e sits directly above x, two rows down and half a key across",
        ),
    )

    /**
     * Flicks with no word hanging below them. Still worth collecting: half of telling a symbol
     * from a word is knowing what an unhurried, uncontested flick looks like, and the apostrophe
     * on k is the one this keyboard's owner reaches for most.
     */
    val SYMBOL_ONLY = listOf(
        Triple("k", "'", "the apostrophe -- the symbol most often wanted mid-word"),
        Triple("a", "@", "home row, with nothing below it to be confused with"),
        Triple("d", "$", "home row"),
        Triple("m", ":", "bottom row: there is no room to keep travelling downward"),
    )

    // --- prose ---------------------------------------------------------------------------

    /**
     * The prose passages, as plain text.
     *
     * Chosen for what they put under the thumb rather than for what they say:
     *
     * - **plain** is the register this keyboard is actually for -- short everyday messages, the
     *   words a phone types all day. It is the passage whose accuracy number means the most.
     * - **reach** is stacked with words that cross the keyboard, because travel is what causes
     *   the problem the leniency exists for: a long sweep is where a thumb catches, skips or
     *   lifts, and a passage of short words would never produce one.
     * - **twos** buries the collision words in ordinary sentences, which is the only way to see
     *   what "on" looks like when it is not being asked for.
     * - **austen** and **carroll** are out of copyright and are here for length and rhythm: a
     *   long stretch of unremarkable English is what makes a hand stop performing.
     */
    private val PROSE = listOf(
        Triple(
            "plain",
            "Everyday",
            "sorry i am running late the meeting went long " +
                "can you send me the address before you leave " +
                "thanks for waiting i will call you tonight " +
                "let me know if that time works for you",
        ),
        Triple(
            "reach",
            "Long reaches",
            "people would question whether typewriter keyboards were properly equipped " +
                "our population requires powerful equipment to prepare proper reports " +
                "you were quite right about the temperature yesterday",
        ),
        Triple(
            "twos",
            "Two-letter words",
            "ok i am on my way in about an hour " +
                "um it is on the table in the other room " +
                "in my experience it is ok to ask " +
                "on second thoughts i am in",
        ),
        Triple(
            "austen",
            "Austen",
            "it is a truth universally acknowledged that a single man in possession " +
                "of a good fortune must be in want of a wife however little known " +
                "the feelings or views of such a man may be on his first entering a neighbourhood",
        ),
        Triple(
            "carroll",
            "Carroll",
            "alice was beginning to get very tired of sitting by her sister on the bank " +
                "and of having nothing to do once or twice she had looked into the book " +
                "her sister was reading but it had no pictures or conversations in it",
        ),
    )

    /**
     * Words of one letter are tapped, not glided.
     *
     * Not a simplification: a glide needs two keys to exist at all, so "a" and "i" have no
     * gesture of their own. Leaving them in as taps is what keeps a prose passage typeable
     * straight through, and the taps it collects are the same taps that stop the flick threshold
     * being tuned into ordinary typing.
     */
    private fun targetFor(word: String, index: Int): Target {
        val letters = word.lowercase().filter { it in 'a'..'z' }
        val glidable = letters.length >= 2
        return Target(
            id = if (glidable) "glide:${word.lowercase()}" else "tap:$letters",
            intent = if (glidable) GestureIntent.WORD else GestureIntent.LETTER,
            startKeyId = letters.take(1),
            expected = word,
            display = word,
        )
    }

    // --- the corpus ------------------------------------------------------------------------

    /**
     * The everyday-English corpus, one sentence per line. See the header of the file itself.
     *
     * Five hand-written passages were enough to prove the rig and are nowhere near enough to
     * live with. A bank collected from them is a bank of the same two hundred words typed over
     * and over: the thumb learns them, the gestures get tidier every session, and the numbers
     * improve without the keyboard improving at all. Data collected daily has to keep putting
     * words under the thumb that it has not just typed.
     */
    const val CORPUS_ASSET = "passages_en.txt"

    /** Blank lines and `#` comments are structure for the reader, not content. */
    fun corpusLines(text: String): List<String> =
        text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()

    /**
     * Groups whole sentences into passages of roughly [words] words.
     *
     * Sentences are never split across a passage boundary, because half a sentence cannot be
     * read ahead in -- and reading ahead is the entire mechanism by which a passage collects a
     * typed gesture rather than a performed one.
     */
    fun corpus(lines: List<String>, words: Int = 30): List<Passage> {
        val out = mutableListOf<Passage>()
        var batch = mutableListOf<String>()
        var count = 0
        fun flush() {
            if (batch.isEmpty()) return
            out += passageOf("corpus:${out.size}", "Everyday ${out.size + 1}", batch.joinToString(" "))
            batch = mutableListOf()
            count = 0
        }
        lines.forEach { line ->
            batch += line
            count += line.split(" ").size
            if (count >= words) flush()
        }
        // A tail shorter than a passage is dropped rather than shipped short: PassagesTest holds
        // every passage to a length a hand can settle into, and a runt would fail it honestly.
        if (count >= words / 2) flush()
        return out
    }

    fun prose(): List<Passage> = PROSE.map { (id, title, text) ->
        passageOf("prose:$id", title, text)
    }

    /** One passage from a run of words, with each word labelled by what it can be typed as. */
    private fun passageOf(id: String, title: String, text: String): Passage {
        val words = text.split(" ").filter { it.isNotBlank() }
        return Passage(
            id = id,
            title = title,
            note = "${words.count { it.length > 1 }} glides, typed straight through",
            targets = words.mapIndexed { i, word -> targetFor(word, i) },
        )
    }

    // --- collisions ----------------------------------------------------------------------

    /**
     * The collision passage, as a stream of tokens to be typed in order.
     *
     * The three kinds alternate *within a key* rather than being blocked together, and this is
     * the part that most affects whether the bank is worth anything. A run of eight flicks is not
     * eight samples of a flick, it is one sample of a rhythm the hand falls into, and a threshold
     * fitted to that works only for someone doing drills. Interleaving also means every key
     * carries taps, flicks and glides from the same minute of the same hand, so the boundary
     * between them is fitted to one column of evidence instead of three unrelated ones.
     *
     * Every key gets taps, including the ones with no word hanging below them. The flick
     * threshold trades off against taps rather than against glides, so a stream with no taps in
     * it gives a sweep no reason not to drive that threshold to zero -- and it will, since every
     * sample it can see is improved by doing so.
     */
    fun collisions(
        reps: Int = 4,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): Passage = Passage(
        id = "collisions",
        title = "Collisions",
        note = "the flick-versus-glide boundary, and nothing else",
        targets = buildList {
            COLLISIONS.shuffled(random).forEach { pair ->
                repeat(reps) { rep ->
                    add(symbolTarget(pair.startKeyId, pair.symbol))
                    val word = pair.words[rep % pair.words.size]
                    add(
                        Target(
                            id = "glide:${word.lowercase()}",
                            intent = GestureIntent.WORD,
                            startKeyId = pair.startKeyId,
                            expected = word,
                        ),
                    )
                    add(tapTarget(pair.startKeyId))
                }
            }
            SYMBOL_ONLY.shuffled(random).forEach { (key, symbol, _) ->
                repeat(reps) {
                    add(symbolTarget(key, symbol))
                    add(tapTarget(key))
                }
            }
        },
    )

    private fun symbolTarget(key: String, symbol: String) = Target(
        id = "flick:$key:$symbol",
        intent = GestureIntent.SYMBOL,
        startKeyId = key,
        expected = symbol,
        display = symbol,
    )

    private fun tapTarget(key: String) = Target(
        id = "tap:$key",
        intent = GestureIntent.LETTER,
        startKeyId = key,
        expected = key,
        display = key,
    )

    /** Why a key is in the collision passage at all, for the line under the passage. */
    fun why(startKeyId: String): String =
        COLLISIONS.firstOrNull { it.startKeyId == startKeyId }?.why
            ?: SYMBOL_ONLY.firstOrNull { it.first == startKeyId }?.third
            ?: ""

    fun all(reps: Int = 4, random: kotlin.random.Random = kotlin.random.Random.Default):
        List<Passage> = listOf(collisions(reps, random)) + prose()
}
