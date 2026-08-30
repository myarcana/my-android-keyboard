package com.offlinekeyboard.ime.capture

import com.offlinekeyboard.ime.gesture.GestureIntent
import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import com.offlinekeyboard.ime.glide.Lexicon
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * What the Gesture Lab is allowed to ask for.
 *
 * A passage that asks for something impossible -- a flick on a key with no secondary, a glide of
 * a word the decoder cannot know, a token starting on a key that does not exist -- does not fail
 * loudly. It fails by quietly filing mislabelled samples into a bank that will go on to tune the
 * real thing, which is the worst outcome available here.
 */
class PassagesTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
    private val keys = geometry.keyRects.associateBy { it.key.id }

    private val lexicon: Lexicon? = run {
        var dir: File? = File("").absoluteFile
        var found: File? = null
        repeat(5) {
            val candidate = dir?.resolve("app/src/main/assets/$LEXICON_ASSET")
            if (found == null && candidate != null && candidate.isFile) found = candidate
            dir = dir?.parentFile
        }
        found?.inputStream()?.use(Lexicon::load)
    }

    private fun everyTarget(): List<Target> = Passages.all(reps = 1).flatMap { it.targets }

    // --- every passage ------------------------------------------------------------------------

    @Test
    fun `every target starts on a key that exists`() {
        everyTarget().forEach { target ->
            assertNotNull(
                "no key '${target.startKeyId}' for ${target.id}",
                keys[target.startKeyId],
            )
        }
    }

    @Test
    fun `symbol targets ask for the secondary the key actually has`() {
        everyTarget().filter { it.intent == GestureIntent.SYMBOL }.forEach { target ->
            assertEquals(
                "${target.id} asks for the wrong symbol",
                keys[target.startKeyId]!!.key.secondary,
                target.expected,
            )
        }
    }

    /**
     * A one-letter word has no glide, because a glide needs two keys to exist. "a" and "I" are
     * taps, and a passage that asked for them as glides would be asking for something nobody can
     * do -- and would record whatever they did instead under the label WORD.
     */
    @Test
    fun `word targets are words that can be glided, and short ones are taps`() {
        everyTarget().forEach { target ->
            val letters = target.expected.lowercase().filter { it in 'a'..'z' }
            when (target.intent) {
                GestureIntent.WORD -> assertTrue(
                    "${target.id} is a one-key word, which cannot be glided",
                    letters.length >= 2,
                )
                GestureIntent.LETTER -> assertTrue(
                    "${target.id} is a tap for ${letters.length} keys",
                    letters.length <= 1,
                )
                GestureIntent.SYMBOL -> Unit
            }
        }
    }

    /**
     * Every word a passage asks for has to be one the decoder could return. A passage word that
     * is missing from the lexicon is unwinnable: the recording is still a real glide, but the
     * decoded column beside it is guaranteed wrong, and a run of those reads as a broken decoder
     * rather than as a gap in the word list.
     */
    @Test
    fun `every glided word is in the lexicon`() {
        val lexicon = lexicon ?: return
        val known = lexicon.letters.toHashSet()
        val missing = everyTarget()
            .filter { it.intent == GestureIntent.WORD }
            .map { it.expected.lowercase().filter { c -> c in 'a'..'z' } }
            .filter { it !in known }
            .distinct()
        assertTrue("passages ask for words the decoder cannot know: $missing", missing.isEmpty())
    }

    // --- the collision passage ------------------------------------------------------------------

    /**
     * The criterion that makes a collision word worth collecting: two letters, the second below
     * the first. Both halves matter, and neither is obvious.
     *
     * *Two letters*, because that makes the whole glide a single stroke with no corner in it. A
     * longer word gives itself away as soon as it changes direction: "was" leaves w heading down
     * and half a key left, which looks exactly like a flick -- for about forty pixels, after
     * which the path turns back rightward towards s and stops resembling one. Note that "was"
     * passes the below-the-start test perfectly well, since s does sit below w. Only the corner
     * rules it out, which is why counting the letters is the part that does the work.
     *
     * *Below*, because a flick only ever goes downward, so a word leaving sideways or upward was
     * never in the running.
     *
     * This test exists because the first version of the catalogue got both halves wrong.
     */
    @Test
    fun `collision words are two-key words that hang below their start key`() {
        val pitch = geometry.keyUnit + geometry.gap
        Passages.collisions(reps = 1).targets
            .filter { it.intent == GestureIntent.WORD }
            .forEach { target ->
                // Glide typing goes letter to letter; punctuation such as the apostrophe in
                // "I'm" is filled in afterwards and is never part of the path.
                val letters = target.expected.lowercase().filter { it.isLetter() }
                assertEquals(
                    "${target.id}: '${target.expected}' is ${letters.length} keys, so its glide " +
                        "has a corner in it and stops looking like a flick",
                    2,
                    letters.length,
                )
                val start = keys[target.startKeyId]!!
                val end = keys[letters.last().toString()]
                assertNotNull("no key '${letters.last()}' for ${target.id}", end)
                assertTrue(
                    "${target.id}: '${target.expected}' ends on '${letters.last()}', which is " +
                        "not below '${target.startKeyId}'",
                    end!!.centerY - start.centerY >= geometry.keyHeight,
                )
                assertTrue(
                    "${target.id}: '${target.expected}' ends %.1f keys sideways -- too far across "
                        .format(abs(end.centerX - start.centerX) / pitch) +
                        "to be mistaken for a swipe down",
                    abs(end.centerX - start.centerX) <= 1.6f * pitch,
                )
            }
    }

    /** Collision words exist to collide with the flick, so they must start on the flick's key. */
    @Test
    fun `collision words spell words that start on their key`() {
        Passages.collisions(reps = 1).targets
            .filter { it.intent == GestureIntent.WORD }
            .forEach { target ->
                assertEquals(
                    "${target.id} spells a word that does not start on its key",
                    target.startKeyId,
                    target.expected.first().lowercase(),
                )
            }
    }

    /**
     * The anti-rhythm property. Two tokens of the same kind back to back on the same key would be
     * sampling a habit rather than a gesture.
     */
    @Test
    fun `the collision passage never asks for the same kind twice in a row on one key`() {
        Passages.collisions(reps = 3).targets
            .windowed(2)
            .forEach { (a, b) ->
                if (a.startKeyId == b.startKeyId) {
                    assertTrue(
                        "two ${a.intent} tokens in a row on ${a.startKeyId}",
                        a.intent != b.intent,
                    )
                }
            }
    }

    /**
     * Without taps in the bank, nothing at all penalises a sweep for lowering the flick
     * threshold, so it lowers it until sloppy taps start flicking. Every key that gets flick
     * tokens needs tap tokens too, on that same key.
     */
    @Test
    fun `keys asked for as taps are also asked for as flicks`() {
        val targets = Passages.collisions(reps = 1).targets
        val tapped = targets.filter { it.intent == GestureIntent.LETTER }.map { it.startKeyId }.toSet()
        val flicked = targets.filter { it.intent == GestureIntent.SYMBOL }.map { it.startKeyId }.toSet()
        assertTrue("no tap tokens at all -- the flick threshold has no floor", tapped.isNotEmpty())
        assertTrue(
            "tap tokens on keys that are never flicked: ${tapped - flicked}",
            (tapped - flicked).isEmpty(),
        )
    }

    @Test
    fun `every kind of label is represented`() {
        assertEquals(GestureIntent.entries.toSet(), everyTarget().map { it.intent }.toSet())
    }

    // --- the prose passages ----------------------------------------------------------------------

    /**
     * Prose exists to be typed at speed, so it has to be long enough for a hand to settle into.
     * A passage of a dozen words collects a dozen careful gestures, which is the failure the
     * whole rewrite was meant to fix.
     */
    @Test
    fun `prose passages are long enough to stop performing`() {
        Passages.prose().forEach { passage ->
            assertTrue(
                "${passage.id} is only ${passage.targets.size} tokens",
                passage.targets.size >= 25,
            )
            assertTrue(
                "${passage.id} has no glides in it",
                passage.targets.count { it.intent == GestureIntent.WORD } >= 20,
            )
        }
    }

    /**
     * The reach passage earns its name or it is just more prose. Long travel is where a thumb
     * skips, catches and lifts, and the finger-lift leniency has nothing to be measured against
     * unless some passage reliably produces those.
     */
    @Test
    fun `the reach passage really does reach`() {
        val reach = Passages.prose().first { it.id == "prose:reach" }
        val long = reach.targets.count {
            it.expected.count { c -> c in 'a'..'z' } >= 8
        }
        assertTrue("only $long long words in the reach passage", long >= 8)
    }
}
