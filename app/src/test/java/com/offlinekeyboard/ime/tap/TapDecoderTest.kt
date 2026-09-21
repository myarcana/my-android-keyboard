package com.offlinekeyboard.ime.tap

import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import com.offlinekeyboard.ime.glide.Lexicon
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Squash
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The promises the tap decoder makes, against the lexicon that actually ships.
 *
 * The reason these are written against the real asset rather than a handful of invented words is
 * that every threshold in the model is derived from the corpus -- the prior's dynamic range, the
 * value of an unknown spelling, and through them the width of the band in which a tap is
 * ambiguous at all. A toy lexicon would exercise the code and measure nothing.
 *
 * Three of these are the feature's whole contract, and are the tests to be most suspicious of a
 * change to: an accurate tap is never re-read, a word the lexicon has never seen can still be
 * typed, and a reading always has exactly one letter per tap.
 */
class TapDecoderTest {

    /** Unit tests run with the module directory as their cwd, or the repo root, or neither. */
    private fun asset(name: String): File? {
        var dir: File? = File("").absoluteFile
        var found: File? = null
        repeat(5) {
            val candidate = dir?.resolve("app/src/main/assets/$name")
            if (found == null && candidate != null && candidate.isFile) found = candidate
            dir = dir?.parentFile
        }
        return found
    }

    private val lexicon: Lexicon? = asset(LEXICON_ASSET)?.inputStream()?.use(Lexicon::load)
    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, 1080f)
    private val model = SpatialModel()

    private fun decoder(): TapDecoder? = lexicon?.let { TapDecoder(WordIndex.of(it)) }

    /**
     * A tap, placed relative to where the bank says a thumb aiming at [letter] actually lands --
     * which is not the middle of the key. Offsets are in key widths and key heights.
     *
     * The literal is taken from [LayoutGeometry.keyForPress] rather than from [letter], so these
     * are the same two facts the keyboard itself has at a keypress: where the finger went down,
     * and which key is drawn there.
     */
    private fun tap(letter: Char, dx: Float = 0f, dy: Float = 0f): TapDecoder.Tap {
        val rect = geometry.letterKeys[letter - 'a']!!
        val x = rect.centerX + (model.offsetX + dx) * geometry.keyUnit
        val y = rect.centerY + (model.offsetY + dy) * geometry.keyHeight
        val id = geometry.keyForPress(x, y)!!.key.id
        return TapDecoder.Tap.of(x, y, id[0], geometry, model)
    }

    private fun taps(word: String): List<TapDecoder.Tap> = word.map { tap(it) }

    private fun literalOf(taps: List<TapDecoder.Tap>) = taps.joinToString("") { it.literal.toString() }

    // --- the contract ---------------------------------------------------------------------

    /**
     * The property everything else rests on. A tap that landed where a thumb aiming at that key
     * lands has no second reading available at all -- [SpatialModel.candidates] has already
     * discarded every alternative as unaffordable -- so there is nothing for the language model
     * to act on and nothing on screen can change under the finger.
     */
    @Test
    fun `a word typed accurately is never re-read`() {
        val decoder = decoder() ?: return
        listOf("the", "keyboard", "typing", "and", "hello").forEach { word ->
            assertNull("$word was re-read despite being typed accurately", decoder.read(taps(word)))
        }
    }

    /**
     * The claim that this is not autocorrect. "rhys" is not in the lexicon and every letter of it
     * competes with a commoner one; typed accurately it comes back untouched.
     */
    @Test
    fun `a word the lexicon has never seen survives being typed accurately`() {
        val decoder = decoder() ?: return
        listOf("rhys", "kade", "zamil", "qwertz").forEach { word ->
            assertEquals(word, literalOf(taps(word)))
            assertNull("$word was re-read", decoder.read(taps(word)))
        }
    }

    /**
     * A press near the right edge of `r`, where `t` is a live alternative, followed by two
     * accurate letters. On its own that first tap says "r, but only just"; three letters later
     * the language model has something to say about it and "the" wins.
     *
     * 0.52 key widths right of where a thumb aiming at `r` lands is still inside the drawn `r`
     * key -- the literal really is "rhe" -- and leaves `t` about 4.8 nats behind on touch, which
     * the 8.1 nats between the prefixes "the" and "rhe" covers.
     */
    @Test
    fun `a tap on the edge of a key is re-read toward the word it completes`() {
        val decoder = decoder() ?: return
        val typed = listOf(tap('r', dx = 0.52f), tap('h'), tap('e'))
        assertEquals("rhe", literalOf(typed))
        assertEquals("the", decoder.read(typed))
    }

    /**
     * The flicker test, and the reason the prior is prefix mass rather than word frequency.
     *
     * Scoring complete words only, "the" beats "rhe" at three letters and then loses at four --
     * neither "rhet" nor "thet" is a word, so both fall back to the unknown-spelling floor and
     * the literal wins again. The reading would appear, and then be taken away, while the finger
     * is still moving. Summing the mass of everything a prefix can still become removes it: "the"
     * is ahead because of what it can become, and adding a letter it can still become does not
     * change that.
     */
    @Test
    fun `a reading does not flip back as the word grows`() {
        val decoder = decoder() ?: return
        val edge = tap('r', dx = 0.52f)
        val grown = listOf(
            listOf(edge, tap('h'), tap('e')),
            listOf(edge, tap('h'), tap('e'), tap('r')),
            listOf(edge, tap('h'), tap('e'), tap('r'), tap('e')),
        )
        grown.forEach { typed ->
            val reading = decoder.read(typed)
            assertNotNull("the reading was given up at ${typed.size} letters", reading)
            assertTrue(
                "expected a reading starting \"the\", got $reading",
                reading!!.startsWith("the"),
            )
        }
    }

    /**
     * One letter out for every letter in, everywhere on the keyboard.
     *
     * This is the constraint that separates a re-reading from a correction, and it is asserted
     * over a sweep rather than a case or two because it has to hold for touch points nobody
     * thought to write down -- the corner of a key, the channel between two rows, the strip
     * buffer above the top row.
     */
    @Test
    fun `a reading always has exactly one letter per tap`() {
        val decoder = decoder() ?: return
        val offsets = listOf(-0.45f, -0.2f, 0f, 0.2f, 0.45f)
        var checked = 0
        for (first in "abcdefghijklmnopqrstuvwxyz") {
            for (dx in offsets) {
                for (dy in offsets) {
                    val typed = listOf(tap(first, dx, dy), tap('e'), tap('r'))
                    val reading = decoder.read(typed) ?: continue
                    assertEquals("wrong length for $reading", typed.size, reading.length)
                    assertTrue("$reading is not letters", reading.all { it in 'a'..'z' })
                    checked++
                }
            }
        }
        assertTrue("the sweep never produced a re-reading, so it tested nothing", checked > 0)
    }

    /**
     * At one letter the prior is about which letters English words begin with, which is a fact
     * about the dictionary rather than about the person typing. It applies to every first
     * keystroke ever made, so it is not allowed to move any of them.
     */
    @Test
    fun `a single tap is never re-read`() {
        val decoder = decoder() ?: return
        listOf(-0.45f, 0f, 0.45f).forEach { dx ->
            assertNull(decoder.read(listOf(tap('r', dx = dx))))
        }
    }

    // --- context in both directions -------------------------------------------------------

    /**
     * A typo tap: the finger was travelling toward [intended] and came down on the key next to
     * it, [hit], without reaching the middle of it. [drift] is how far past `hit`'s aim point it
     * carried, as a fraction of the distance between the two keys -- so 0 is a dead-centre press
     * on the wrong key and 1 would be a clean press on the right one.
     *
     * Modelling a mistake this way rather than as a dead-centre press on the wrong key is the
     * difference between testing a typo and testing a decision. A press in the exact middle of
     * `v` is not a slip toward `c`; it is someone typing `v`, and a keyboard that rewrites it
     * has overridden them. Real mis-hits land between the two keys, which is precisely why the
     * touch evidence against them is affordable: 45 nats dead-centre, but around 6 at the point
     * a sliding thumb actually clips the neighbouring key.
     */
    private fun typo(hit: Char, intended: Char, drift: Float = 0.44f): TapDecoder.Tap {
        val from = geometry.letterKeys[hit - 'a']!!
        val to = geometry.letterKeys[intended - 'a']!!
        val span = (to.centerX - from.centerX) / geometry.keyUnit
        return tap(hit, dx = span * drift)
    }

    /**
     * The look-ahead case, and the one the first pass structurally cannot reach.
     *
     * The `v` here is a slip toward `c` -- the finger was heading for `c` and clipped `v` on the
     * way -- but it is still inside the drawn `v` key, so the literal really is `teavhers` and
     * the first pass pins it. Nothing about that tap *on its own* says it was a mistake. What
     * overturns it is the five letters after it: `teachers` is a word the corpus knows well and
     * `teavhers` is not a word at all.
     */
    @Test
    fun `a slipped key is fixed by the letters that follow it`() {
        val decoder = decoder() ?: return
        val typed = listOf(
            tap('t'), tap('e'), tap('a'), typo('v', 'c'),
            tap('h'), tap('e'), tap('r'), tap('s'),
        )
        assertEquals("teavhers", literalOf(typed))
        assertEquals("teachers", decoder.read(typed))
    }

    /**
     * The limit of what one letter of context can buy, asserted because it is a real boundary
     * of the feature rather than a gap in it.
     *
     * `ot` is the case that looks easiest and is hardest. Two letters means a single tap of
     * context, and more importantly it means the prior has almost nothing to say: `it` outscores
     * `ot` by 1.4 nats, against 3.7 for `teachers` and 5.1 for `word`. That is smaller than the
     * touch cost of moving the letter at all -- about 1.6 nats even with the tap on the very
     * edge of the key -- so the comparison comes out negative before the margin is consulted.
     *
     * The keyboard declining here is the model working. `ot` is not obviously a typo for `it`
     * to something that only knows prefix mass; two letters simply do not carry enough signal,
     * and when the evidence is this thin the keys that were actually pressed are the better
     * guess. Fixing this would mean a prior that knows about sentences, which is a different
     * feature and a much larger one.
     */
    @Test
    fun `a two-letter word has too little context to be rescued`() {
        val decoder = decoder() ?: return
        val typed = listOf(typo('o', 'i'), tap('t'))
        assertEquals("ot", literalOf(typed))
        assertNull("two letters should not be enough to overrule the keys pressed", decoder.read(typed))
    }

    /**
     * Look-behind, to show the pass is not quietly left-to-right. The mistake is on the *last*
     * letter, so everything arguing for the fix was typed before it.
     */
    @Test
    fun `a mistake on the final letter is fixed by the letters before it`() {
        val decoder = decoder() ?: return
        val typed = listOf(tap('w'), typo('p', 'o'), tap('r'), tap('d'))
        assertEquals("wprd", literalOf(typed))
        assertEquals("word", decoder.read(typed))
    }

    /**
     * The other half of the same rule, and the reason [typo] takes a drift at all: a press in
     * the *middle* of the wrong key is left alone. At that point the touch evidence is some 45
     * nats against the neighbour and no prior gap in this lexicon comes close to buying it --
     * which is the model declining to override a deliberate keystroke, not a threshold.
     */
    @Test
    fun `a dead-centre press on a wrong key is left alone`() {
        val decoder = decoder() ?: return
        val typed = listOf(
            tap('t'), tap('e'), tap('a'), typo('v', 'c', drift = 0f),
            tap('h'), tap('e'), tap('r'), tap('s'),
        )
        assertEquals("teavhers", literalOf(typed))
        assertNull("a squarely-pressed key was overridden", decoder.read(typed))
    }

    // --- what the rescue pass is still not allowed to do ------------------------------------

    /**
     * The contract test from above, restated against the new pass because it is the thing most
     * at risk from it. A rescue needs a commoner *word* to move toward, and an unknown spelling
     * has none, so widening the spatial reach cannot reach these.
     */
    @Test
    fun `the rescue pass still leaves unknown words alone`() {
        val decoder = decoder() ?: return
        listOf("rhys", "kade", "zamil", "qwertz", "xyzzy").forEach { word ->
            assertNull("$word was rescued into something else", decoder.read(taps(word)))
        }
    }

    /**
     * A real word is not traded for a commoner real word. This is the line between fixing a
     * mis-hit and overriding a choice: both spellings are words, the user hit the keys for one
     * of them accurately, and the keyboard has no business preferring the other however much
     * commoner it is.
     */
    @Test
    fun `a correctly typed word is never swapped for a commoner one`() {
        val decoder = decoder() ?: return
        listOf("cad", "bat", "pin", "hot", "vane", "cot").forEach { word ->
            assertNull("$word was swapped for a commoner word", decoder.read(taps(word)))
        }
    }

    /**
     * A rescue may move a letter sideways, where a mis-hit explains it, but not vertically.
     * `RESCUE_REACH_NATS` is set below the distance to the row above for this reason: a finger
     * a whole row out is not the typo this pass models.
     */
    @Test
    fun `a rescue does not reach the row above`() {
        val decoder = decoder() ?: return
        // A slip sideways from `f` onto `g`: "fine" is reachable, and so is "gone" or "mine"
        // only by moving a letter a whole row, which the reach is set below.
        val typed = listOf(typo('g', 'f'), tap('i'), tap('n'), tap('e'))
        assertEquals("gine", literalOf(typed))
        val reading = decoder.read(typed)
        assertTrue(
            "expected a sideways rescue or none, got $reading",
            reading == null || reading == "fine",
        )
    }

    /**
     * Two slips in one word come back untouched, and this is asserted because the obvious
     * expectation -- that the pass fixes them one at a time -- is wrong for a reason worth
     * pinning down.
     *
     * Each step is scored against the current reading, and with two mistakes in it the word is
     * not in the lexicon either way: `teavhets` and the half-fixed `teachets` both fall to the
     * unknown-spelling floor, so fixing one letter wins a prior gain of exactly zero and the
     * first step never starts. The result is the literal, not a half-correction.
     *
     * That is the better of the two available failures. A keyboard that produced `teachets`
     * here would have invented a confident answer out of a word it could not read; returning
     * the keys that were actually pressed says "I don't know what you meant", which is true.
     */
    @Test
    fun `two slips in one word are left alone rather than half-corrected`() {
        val decoder = decoder() ?: return
        val typed = listOf(
            tap('t'), tap('e'), tap('a'), typo('v', 'c'),
            tap('h'), tap('e'), typo('t', 'r'), tap('s'),
        )
        assertEquals("teavhets", literalOf(typed))
        assertNull("a two-slip word was half-corrected", decoder.read(typed))
    }

    /** The one-letter-per-tap guarantee, restated over taps the rescue pass actually moves. */
    @Test
    fun `a rescued reading still has exactly one letter per tap`() {
        val decoder = decoder() ?: return
        val words = listOf(
            listOf(tap('t'), tap('e'), tap('a'), typo('v', 'c'), tap('h'), tap('e'), tap('r'), tap('s')),
            listOf(typo('o', 'i'), tap('t')),
            listOf(tap('w'), typo('p', 'o'), tap('r'), tap('d')),
            listOf(tap('t'), tap('h'), tap('i'), typo('m', 'n'), tap('g'), tap('s')),
        )
        words.forEach { typed ->
            val reading = decoder.read(typed) ?: return@forEach
            assertEquals("wrong length for $reading", typed.size, reading.length)
            assertTrue("$reading is not letters", reading.all { it in 'a'..'z' })
        }
    }

    // --- the keys may move while a word is being typed ---------------------------------------

    /**
     * A word survives the keyboard being resized underneath it, which is the bug this pins.
     *
     * A pending word outlives the key grid it was typed on: a rotation, a split-screen drag, the
     * navigation bar arriving, a one-handed squash flick. The taps were once stored as raw
     * pixels and re-scored against whatever geometry was current at the *next* keystroke, so a
     * resize mid-word shifted every letter sideways and the decoder returned the best word for
     * keys nobody had pressed -- "code" typed accurately came back as "vodr", flipping in one go
     * because the composing region is rewritten whole.
     *
     * [TapDecoder.Tap] now resolves the geometry at the moment of the press, so there is no later
     * geometry for the decoder to consult and the failure cannot be expressed. Typing on one
     * board and reading on another has to be indistinguishable from never having resized.
     */
    @Test
    fun `a word is not re-read when the keyboard is resized mid-word`() {
        val decoder = decoder() ?: return
        // Every width a phone plausibly hands an IME: rotation, split screen, a resizable window.
        listOf(1080f, 1008f, 960f, 900f, 840f, 800f, 720f, 640f).forEach { width ->
            val resized = LayoutGeometry(IosLayouts.QWERTY_LOWER, width)
            listOf("code", "the", "hello", "keyboard", "name").forEach { word ->
                // Typed accurately on the board that was on screen at the time.
                val typed = taps(word)
                assertEquals(word, literalOf(typed))
                // The last letter arrives after the window changed size. The earlier taps are
                // the ones already held, and they must not be re-interpreted.
                val afterResize = typed.dropLast(1) + run {
                    val c = word.last()
                    val rect = resized.letterKeys[c - 'a']!!
                    val x = rect.centerX + model.offsetX * resized.keyUnit
                    val y = rect.centerY + model.offsetY * resized.keyHeight
                    TapDecoder.Tap.of(x, y, c, resized, model)
                }
                assertNull(
                    "$word was re-read after a resize to ${width}px",
                    decoder.read(afterResize),
                )
            }
        }
    }

    /**
     * The same for a one-handed squash, which moves the keys sideways without changing the width
     * and was the other way a held word could be scored against a grid it was never typed on.
     */
    @Test
    fun `a word is not re-read when the board is squashed mid-word`() {
        val decoder = decoder() ?: return
        listOf(Squash.LEFT, Squash.RIGHT).forEach { squash ->
            val moved = LayoutGeometry(IosLayouts.QWERTY_LOWER, 1080f, squash = squash)
            listOf("code", "the", "hello", "name").forEach { word ->
                val typed = taps(word)
                val afterFlick = typed.dropLast(1) + run {
                    val c = word.last()
                    val rect = moved.letterKeys[c - 'a']!!
                    val x = rect.centerX + model.offsetX * moved.keyUnit
                    val y = rect.centerY + model.offsetY * moved.keyHeight
                    TapDecoder.Tap.of(x, y, c, moved, model)
                }
                assertNull(
                    "$word was re-read after a $squash squash",
                    decoder.read(afterFlick),
                )
            }
        }
    }
}
