package com.offlinekeyboard.ime.tap

import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import com.offlinekeyboard.ime.glide.Lexicon
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.LayoutGeometry
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
        return TapDecoder.Tap(x, y, id[0])
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
            assertNull("$word was re-read despite being typed accurately", decoder.read(taps(word), geometry))
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
            assertNull("$word was re-read", decoder.read(taps(word), geometry))
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
        assertEquals("the", decoder.read(typed, geometry))
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
            val reading = decoder.read(typed, geometry)
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
                    val reading = decoder.read(typed, geometry) ?: continue
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
            assertNull(decoder.read(listOf(tap('r', dx = dx)), geometry))
        }
    }
}
