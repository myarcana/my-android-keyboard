package com.offlinekeyboard.ime.glide

import com.offlinekeyboard.ime.gesture.PathPoint
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The decoder, driven with synthetic glides over the real layout.
 *
 * Synthetic paths are not a substitute for recorded ones -- the whole point of the Gesture Lab
 * is that a real thumb does not go where these do -- but they pin down the things that must be
 * true before a recording is worth collecting: that a clean glide decodes to its own word, that
 * frequency breaks ties the way a person would expect, and that noise and corner-cutting of the
 * size a hand actually produces do not change the answer.
 */
class GlideDecoderTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, 1080f)

    /** The shipped lexicon if it has been generated, so the tests below score the real thing. */
    private val lexicon: Lexicon = run {
        var dir: File? = File("").absoluteFile
        var asset: File? = null
        repeat(5) {
            val candidate = dir?.resolve("app/src/main/assets/$LEXICON_ASSET")
            if (asset == null && candidate != null && candidate.isFile) asset = candidate
            dir = dir?.parentFile
        }
        asset?.inputStream()?.use(Lexicon::load) ?: Lexicon.of(
            "the" to 1036, "hello" to 752, "keyboard" to 600, "in" to 993, "on" to 957,
        )
    }

    private val decoder = GlideDecoder(lexicon)

    private fun centre(c: Char) = geometry.keyRects.first { it.key.id == c.toString() }

    /**
     * A glide straight through the centre of every letter, sampled the way a screen would.
     *
     * [jitter] displaces each sample, and [corner] pulls the path across the inside of each turn
     * -- both are what separates a person's glide from this ideal, and both are what the decoder
     * has to survive.
     */
    private fun glide(
        word: String,
        jitter: Float = 0f,
        corner: Float = 0f,
        random: Random = Random(1),
    ): List<PathPoint> {
        val keys = word.lowercase().filter { it in 'a'..'z' }
        val stops = keys.map { centre(it) }.let { rects ->
            rects.mapIndexed { i, rect ->
                // Corner-cutting: each waypoint is pulled toward the average of its neighbours,
                // which is what a finger that does not stop at a key looks like.
                val before = rects.getOrNull(i - 1)
                val after = rects.getOrNull(i + 1)
                // Only the turns are cut. A finger starts on the first key and stops on the
                // last one; sliding those two would be modelling a different mistake, and an
                // earlier version of this did exactly that -- displacing the start of
                // "keyboard" three quarters of a key toward `e` and then blaming the decoder
                // for not finding a word beginning with `k`.
                if (before == null || after == null) return@mapIndexed rect.centerX to rect.centerY
                val mx = (before.centerX + after.centerX) / 2f
                val my = (before.centerY + after.centerY) / 2f
                (rect.centerX + (mx - rect.centerX) * corner) to
                    (rect.centerY + (my - rect.centerY) * corner)
            }
        }
        val path = ArrayList<PathPoint>()
        var t = 0L
        for (i in 1 until stops.size) {
            val (x0, y0) = stops[i - 1]
            val (x1, y1) = stops[i]
            val steps = (hypot(x1 - x0, y1 - y0) / 12f).toInt().coerceAtLeast(2)
            for (s in 0..steps) {
                val f = s.toFloat() / steps
                if (i > 1 && s == 0) continue
                path += PathPoint(
                    x0 + (x1 - x0) * f + (random.nextFloat() - 0.5f) * 2f * jitter,
                    y0 + (y1 - y0) * f + (random.nextFloat() - 0.5f) * 2f * jitter,
                    t,
                )
                t += 8
            }
        }
        return path
    }

    private fun decode(word: String, jitter: Float = 0f, corner: Float = 0f): List<String> =
        decoder.decode(glide(word, jitter, corner), geometry).map { it.word }

    /**
     * The corpus these tests score against. Ordinary words, a few of the two-letter ones that
     * collide with a flick, and a contraction -- not a list chosen for being easy.
     */
    private val corpus = listOf(
        "hello", "keyboard", "morning", "thanks", "tonight", "something", "offline", "phone",
        "tomorrow", "meeting", "because", "please", "people", "would", "which", "world",
        "water", "house", "think", "great", "every", "never", "about", "under", "other",
        "message", "sorry", "always", "before", "little", "friend", "number", "office",
        "in", "on", "ok", "um", "I'm",
    )

    /** Every combination of hand steadiness the tests below score over. */
    private val hands = listOf(
        Triple("straight through the centres", 0f, 0f),
        Triple("a steady hand", geometry.keyUnit * 0.18f, 0.18f),
        Triple("an ordinary thumb", geometry.keyUnit * 0.28f, 0.30f),
        Triple("a hurried thumb", geometry.keyUnit * 0.40f, 0.45f),
    )

    private fun rates(): List<Triple<String, Int, Int>> = hands.map { (name, jitter, corner) ->
        var top1 = 0
        var offered = 0
        (1..3).forEach { seed ->
            corpus.forEach { word ->
                val got = decoder.decode(glide(word, jitter, corner, Random(seed)), geometry)
                    .map { it.word }
                if (got.firstOrNull() == word) top1++
                if (got.contains(word)) offered++
            }
        }
        Triple(name, top1, offered)
    }

    /**
     * The headline number, printed rather than only asserted: a rate is the kind of thing that
     * has to be *read*, because a change of two points either way is the difference between a
     * tuning worth keeping and one that regressed a whole class of words.
     *
     * The bar is deliberately set at what a steady hand should reach, not at what the cleanest
     * possible input reaches. Synthetic glides are not the evidence this decoder will eventually
     * be tuned on -- recorded ones are -- and treating them as such is exactly the mistake the
     * gesture bank exists to avoid. What they can do is fail loudly when a change breaks
     * something, which is what this is for.
     */
    @Test
    fun `the decoder finds the intended word`() {
        val trials = corpus.size * 3
        println()
        println("glide decoding over ${lexicon.size} words, $trials glides per hand:")
        val measured = rates()
        measured.forEach { (hand, top1, offered) ->
            println(
                "  %-30s first choice %3d%%   offered %3d%%"
                    .format(hand, top1 * 100 / trials, offered * 100 / trials),
            )
        }
        val steady = measured[1]
        assertTrue(
            "a steady hand only decoded ${steady.second} of $trials",
            steady.second >= trials * 85 / 100,
        )
        val ideal = measured[0]
        assertTrue(
            "even a perfect glide only decoded ${ideal.second} of $trials",
            ideal.second >= trials * 90 / 100,
        )
    }

    /**
     * Frequency is what settles a shape two words share. "in" and "un" leave the same key and
     * land on the same key; nothing in the path distinguishes them, and one of them is a word
     * people write.
     */
    @Test
    fun `frequency decides between words with the same shape`() {
        assertEquals("in", decode("in").firstOrNull())
        assertEquals("the", decode("the").firstOrNull())

        // "it's" and "its" are the same three keys in the same order, so the path cannot choose
        // between them and the lexicon has to. It says "it's", which is the commoner spelling of
        // that shape -- see PREFER_CONTRACTION in tools/build_lexicon.py.
        val spellings = decode("its").filter { it.lowercase().filter(Char::isLetter) == "its" }
        assertEquals(listOf("it's", "its"), spellings.take(2))
    }

    /**
     * The channel that knows a word has to be travelled *through*. "how" and "house" leave the
     * same key, sweep right and then far left, and cover almost the same ground doing it, so a
     * point-for-point comparison rates them equally -- and "how" is the commoner word, so it
     * wins. Only asking where the finger was when it should have been on `w` separates them.
     */
    @Test
    fun `a longer word is not beaten by a short one hiding inside its gesture`() {
        assertEquals("house", decode("house").firstOrNull())
        assertEquals("keyboard", decode("keyboard").firstOrNull())
        assertEquals("tomorrow", decode("tomorrow").firstOrNull())
    }

    /** A tap, or a hand resting: there is no gesture here and nothing should be typed. */
    @Test
    fun `too little movement decodes to nothing`() {
        val q = centre('q')
        val stationary = (0..8).map { PathPoint(q.centerX, q.centerY + it * 0.4f, it * 10L) }
        assertTrue(decoder.decode(stationary, geometry).isEmpty())
    }

    /** A glide that starts on a key the word does not use must not still find that word. */
    @Test
    fun `the endpoints prune the search`() {
        val results = decoder.decode(glide("hello"), geometry)
        assertTrue(results.isNotEmpty())
        results.forEach { result ->
            val letters = result.word.lowercase().filter { it in 'a'..'z' }
            assertTrue(
                "'${result.word}' does not start near h",
                hypot(centre(letters.first()).centerX - centre('h').centerX,
                    centre(letters.first()).centerY - centre('h').centerY) <= geometry.keyUnit * 1.2f,
            )
        }
    }

    /**
     * Decoding happens on the UI thread at the moment the finger lifts, so it has one frame to
     * finish in. This is a floor, not a benchmark -- a JVM on a laptop is not the phone -- but a
     * regression that makes it ten times slower will show up here.
     */
    @Test
    fun `decoding a word is fast enough to do at the lift`() {
        val paths = listOf("hello", "keyboard", "tomorrow", "something", "in")
            .map { glide(it, jitter = 4f) }
        repeat(20) { paths.forEach { decoder.decode(it, geometry) } }
        val started = System.nanoTime()
        repeat(20) { paths.forEach { decoder.decode(it, geometry) } }
        val perDecode = (System.nanoTime() - started) / 1_000_000.0 / (20 * paths.size)
        println("glide decode: %.2f ms per word over a %d-word lexicon".format(perDecode, lexicon.size))
        assertTrue("a decode took %.1f ms".format(perDecode), perDecode < 16.0)
    }
}
