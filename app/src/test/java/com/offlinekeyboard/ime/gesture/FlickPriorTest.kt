package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.Metrics
import com.offlinekeyboard.ime.layout.Squash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the prior believes, key by key and context by context.
 *
 * These are assertions about *ordering and sign*, almost never about exact values. The weights
 * are expected to move -- they are sweepable, and `tools/gestures.sh analyse` exists to move
 * them -- so a test pinning `-0.9` would fail the first time the bank had something to say and
 * would be deleted rather than understood. What must not change is which way round the beliefs
 * go: that a bottom-row flick is easier than a top-row one, that a mid-word `$` is harder than a
 * `$` after a space, and that the apostrophe is exempt from the rule that would otherwise break
 * it. Those are the claims, so those are the tests.
 */
class FlickPriorTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
    private val prior = FlickPrior()

    private fun key(id: String): KeyRect = geometry.keyRects.first { it.key.id == id }

    private fun bias(
        id: String,
        before: Char? = null,
        composing: Boolean = false,
        g: LayoutGeometry = geometry,
    ): Float = prior.bias(
        g.keyRects.first { it.key.id == id },
        g,
        FlickPrior.Context(before, composing),
    )

    // --- the layout terms, which the bank disproved --------------------------------------------

    /**
     * The per-key terms are off, and this test is why they stay off.
     *
     * The argument for them was good -- nothing can be glided downward out of `m`, so a downward
     * stroke there has no competing reading -- and the bank still refused it. Real flicks travel
     * 0.13 to 1.05 key heights against a threshold of 0.025, so distance was never what refused
     * a flick on `m`; the six that went missing did so at the old 0.45 ratio. Turned on, the
     * terms moved five recorded gestures and made all five wrong.
     *
     * Asserting the zero rather than deleting the weights keeps that result from being
     * rediscovered the expensive way. See `GestureBankReplayTest.reportFlickPrior`, which prints
     * the comparison on every run.
     */
    @Test
    fun `the layout terms are off, because the bank scored them as a regression`() {
        assertEquals(0f, FlickPrior.Weights().noRoomBelow, 0f)
        assertEquals(0f, FlickPrior.Weights().roomBelow, 0f)
        listOf("q", "i", "d", "k", "m", "z").forEach { id ->
            assertEquals("$id must owe nothing to its row", 0f, bias(id), 0.0001f)
        }
    }

    /**
     * The mechanism still works, and is still measured from the geometry rather than from a
     * hardcoded row index -- which is the part worth keeping alive for the day a threshold does
     * bind on the bottom row.
     *
     * Exercised with the weights turned up explicitly, so this test says what the term *would*
     * do without making any claim that it should be on.
     */
    @Test
    fun `with the layout terms turned on, the bottom row is the easiest to flick`() {
        val on = FlickPrior(FlickPrior.Weights(noRoomBelow = 2.5f, roomBelow = -1f))
        fun b(id: String, g: LayoutGeometry = geometry) =
            on.bias(g.keyRects.first { it.key.id == id }, g, FlickPrior.Context.UNKNOWN)

        listOf("z", "x", "c", "v", "b", "n", "m").forEach { low ->
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").forEach { high ->
                assertTrue("$low vs $high", b(low) > b(high))
            }
        }
        // The home row is scored between the other two rather than lumped in with either.
        assertTrue(b("d") > b("e"))
        assertTrue(b("d") < b("x"))

        // And it is geometry, not a row number: a squash moves keys sideways and leaves the rows
        // where they are, so what is underneath a key -- and the belief about it -- is unchanged.
        val squashed = LayoutGeometry(
            IosLayouts.QWERTY_LOWER,
            Metrics.REFERENCE_WIDTH,
            squash = Squash.RIGHT,
        )
        listOf("m", "i", "d").forEach { id ->
            assertEquals(id, b(id), b(id, squashed), 0.01f)
        }
    }

    // --- the caret --------------------------------------------------------------------------

    /**
     * The user's rule, stated as the comparison it actually makes: the same key, the same
     * stroke, two different moments of a sentence.
     */
    @Test
    fun `a digit mid-word is less plausible than the same digit after a space`() {
        assertTrue(bias("i", before = 'l') < bias("i", before = ' '))
    }

    @Test
    fun `composing a word counts as mid-word even when the caret is at the start of the field`() {
        assertTrue(bias("i", before = null, composing = true) < bias("i", before = null))
    }

    /**
     * The abstention that keeps the gesture bank honest.
     *
     * A replayed path has no editor behind it, so it arrives with [FlickPrior.Context.UNKNOWN] --
     * which is also what a live host sends when the editor declined to answer. If that case
     * carried any opinion at all, every gesture ever recorded would be rescored under thresholds
     * no phone ever ran, and the harness check in `GestureBankReplayTest` would fail for reasons
     * having nothing to do with the state machine. So it is exactly zero, and an empty field
     * simply forgoes the small encouragement a word boundary would otherwise give a digit.
     */
    @Test
    fun `an empty field contributes exactly nothing, so replay is unaffected`() {
        listOf("q", "i", "d", "m", "k").forEach { id ->
            assertEquals(
                "$id must abstain with no context",
                prior.bias(key(id), geometry, FlickPrior.Context.UNKNOWN),
                bias(id),
                0.0001f,
            )
        }
        // A digit key gets a boundary bonus only when a boundary was actually observed.
        assertTrue(bias("q", before = ' ') > bias("q"))
    }

    /**
     * The strongest contextual signal there is. Somebody who has typed `1` and swipes down on
     * `w` is typing `12`, and numbers arrive in runs far more reliably than letters do.
     */
    @Test
    fun `a digit behind the caret is the strongest argument for another one`() {
        assertTrue(bias("w", before = '4') > bias("w", before = ' '))
        assertTrue(bias("w", before = '4') > bias("w", before = 'a'))
    }

    @Test
    fun `punctuation behind the caret is a word boundary, not the middle of a word`() {
        listOf(' ', '.', ',', '!', '\n').forEach { c ->
            assertTrue("$c should read as a boundary", bias("i", before = c) > bias("i", before = 'l'))
        }
    }

    /**
     * The exemption that keeps the mid-word rule from being a regression.
     *
     * The apostrophe is the symbol most often wanted mid-word -- `don't`, `it's`, `I'm` -- so the
     * context that makes a `$` implausible is the exact context an apostrophe is *for*. Without
     * this the new rule would make the commonest flick in English harder, and the drill would
     * never notice because it types `'` from a standing start.
     */
    @Test
    fun `the apostrophe is not penalised mid-word, because that is where it belongs`() {
        val midWord = bias("k", before = 'n')
        val boundary = bias("k", before = ' ')
        assertTrue("apostrophe must survive mid-word", midWord >= boundary - 0.01f)
    }

    @Test
    fun `a dollar sign is penalised mid-word, unlike the apostrophe`() {
        assertTrue(bias("d", before = 'n') < bias("k", before = 'n'))
    }

    // --- abstention and bounds ----------------------------------------------------------------

    /**
     * A host that cannot read the editor must get the machine's own thresholds back, not some
     * half-informed guess. This is what lets the bank replay -- which has no editor at all --
     * score the geometry honestly.
     */
    @Test
    fun `an unknown context contributes nothing beyond the layout`() {
        listOf("m", "i", "d", "k").forEach { id ->
            val unknown = prior.bias(key(id), geometry, FlickPrior.Context.UNKNOWN)
            val roomOnly = prior.bias(key(id), geometry, FlickPrior.Context(before = null))
            assertEquals(unknown, roomOnly, 0.001f)
        }
    }

    /** Keys with no secondary have no flick to have an opinion about. */
    @Test
    fun `keys with nothing to flick to are neutral`() {
        listOf("space", "shift", "backspace", "return", "mode_123").forEach { id ->
            assertEquals("$id has no secondary", 0f, bias(id), 0.001f)
        }
    }

    /**
     * The clamp is the safety property: every weight is a judgement, and judgements compose. No
     * combination of contexts may push the total past the limit, or the bounds argued for in
     * [GestureConfig.flickPriorMinScale] would be resting on arithmetic nobody checked.
     */
    @Test
    fun `no combination of evidence escapes the limit`() {
        val limit = FlickPrior.Weights().limit
        val contexts = listOf<Char?>(null, ' ', 'a', '4', '.', '\'').flatMap { before ->
            listOf(true, false).map { FlickPrior.Context(before, it) }
        }
        geometry.keyRects.forEach { rect ->
            contexts.forEach { context ->
                val b = prior.bias(rect, geometry, context)
                assertTrue("${rect.key.id} $context = $b", b in -limit..limit)
            }
        }
    }

    /** Zeroed weights must give the old machine back exactly, which is how a term is measured. */
    @Test
    fun `weights at zero make the prior disappear`() {
        val off = FlickPrior(
            FlickPrior.Weights(
                noRoomBelow = 0f,
                roomBelow = 0f,
                midWord = 0f,
                afterDigit = 0f,
                boundaryDigit = 0f,
                wordInternalSymbol = 0f,
            ),
        )
        geometry.keyRects.forEach { rect ->
            assertEquals(
                rect.key.id,
                0f,
                off.bias(rect, geometry, FlickPrior.Context('a', composing = true)),
                0.001f,
            )
        }
    }

    // --- what it does to the state machine ----------------------------------------------------
    //
    // The tests above are about what the prior believes. These are about whether believing it
    // changes anything, which is a separate question and the one that matters: a prior wired in
    // such a way that the thresholds never actually move would pass every test above.

    private fun fsm(context: FlickPrior.Context) =
        TouchFsm(geometry, GestureConfig(), prior, context)

    /** Drags [dy] pixels straight down from the centre of [id] and says whether it flicked. */
    private fun flicks(id: String, dy: Float, context: FlickPrior.Context): Boolean {
        val k = key(id)
        val f = fsm(context)
        f.onDown(k.centerX, k.centerY, 0)
        f.onMove(k.centerX, k.centerY + dy, 30)
        return f.onUp(k.centerX, k.centerY + dy, 60)
            .any { it is GestureOutput.CommitSecondary }
    }

    /**
     * With the layout terms off, the key alone changes nothing: `m` and `i` are held to the same
     * travel, exactly as they were before this class existed.
     *
     * The inverse of the test it replaces, and it is here to keep the shipped default honest. A
     * reader coming to `FlickPrior` from `docs/GESTURE_BANK.md` will expect `m` to be special,
     * and on this bank it is not.
     */
    @Test
    fun `by default the key alone does not change what a flick costs`() {
        // UNKNOWN so that no contextual term fires either: this is about the key and nothing
        // else, and the threshold is therefore exactly the configured one.
        val ctx = FlickPrior.Context.UNKNOWN
        val justOver = GestureConfig().flickDistanceRatio * geometry.keyHeight * 1.2f
        val justUnder = GestureConfig().flickDistanceRatio * geometry.keyHeight * 0.8f
        listOf("m", "i", "d", "k", "q").forEach { id ->
            assertTrue("$id should flick just over the threshold", flicks(id, justOver, ctx))
            assertTrue("$id should not flick just under it", !flicks(id, justUnder, ctx))
        }
    }

    /**
     * The user's rule, end to end. The identical stroke on the identical key becomes a letter
     * mid-word and a symbol at a boundary.
     */
    @Test
    fun `the same stroke reads differently in the middle of a word`() {
        // Between the two thresholds the contexts produce -- 0.86 of the configured distance at
        // a boundary, 1.22 of it mid-word -- because that gap is the whole behaviour under test.
        // A pull outside it flicks in both contexts or neither, and would pass whatever the
        // prior did.
        val pull = GestureConfig().flickDistanceRatio * geometry.keyHeight * 1.05f
        assertTrue(
            "at a boundary this is a flick",
            flicks("i", pull, FlickPrior.Context(before = ' ')),
        )
        assertTrue(
            "mid-word the same stroke is not",
            !flicks("i", pull, FlickPrior.Context(before = 'l', composing = true)),
        )
    }

    /** Typing `18`: a digit behind the caret argues for another one. */
    @Test
    fun `a digit behind the caret makes the next digit easier to flick`() {
        val pull = GestureConfig().flickDistanceRatio * geometry.keyHeight * 1.05f
        assertTrue(flicks("i", pull, FlickPrior.Context(before = '1')))
        assertTrue(!flicks("i", pull, FlickPrior.Context(before = 'l', composing = true)))
    }

    /**
     * No context may make a flick free. The clamp in [GestureConfig.flickPriorMinScale] is what
     * stands between a strong prior and a resting thumb typing symbols, and a resting thumb is
     * reported by the digitiser as perfectly still -- so zero travel must stay zero flicks.
     */
    @Test
    fun `no context lets a motionless finger flick`() {
        listOf<Char?>(null, ' ', '4', 'a').forEach { before ->
            geometry.keyRects.filter { it.key.secondary != null }.forEach { rect ->
                assertTrue(
                    "${rect.key.id} flicked without moving",
                    !flicks(rect.key.id, 0f, FlickPrior.Context(before)),
                )
            }
        }
    }

    /**
     * And no context may take the escape hatch away: a stroke that keeps travelling is a word,
     * however strongly the prior favoured the symbol when it set out. This is the property that
     * makes the whole mechanism safe to tune -- the prior can only ever move the point at which
     * a flick is *provisionally* read, never trap a glide inside one.
     */
    @Test
    fun `even the strongest prior cannot stop a flick becoming a glide`() {
        // The most flick-favouring context there is, so the escape is tested against the loosest
        // thresholds the prior can produce rather than against the default ones.
        val strong = FlickPrior(
            FlickPrior.Weights(noRoomBelow = 2.5f, afterDigit = 1.6f),
        )
        val o = key("o")
        val f = TouchFsm(geometry, GestureConfig(), strong, FlickPrior.Context(before = '1'))
        f.onDown(o.centerX, o.centerY, 0)
        f.onMove(o.centerX, o.centerY + GestureConfig().flickDistanceRatio * geometry.keyHeight * 2f, 20)
        assertEquals("the prior should make this a flick first", GestureState.FLICK, f.state)
        // Keep going, far enough to pass flickToGlideRatio.
        val far = GestureConfig().flickToGlideRatio * geometry.keyUnit * 1.2f
        f.onMove(o.centerX + far, o.centerY + 20f, 120)
        assertEquals(GestureState.GLIDE, f.state)
    }
}
