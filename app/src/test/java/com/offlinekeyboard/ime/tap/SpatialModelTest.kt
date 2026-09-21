package com.offlinekeyboard.ime.tap

import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.LayoutGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the pinned band ends: which taps have a second reading available and which do not.
 *
 * The numbers being asserted are consequences of two measurements meeting -- the scatter of a
 * thumb around its aim, from `data/gesture-bank.jsonl`, and the dynamic range of the prior, from
 * the lexicon. Neither was chosen to make these pass, and a change to either should move them.
 */
class SpatialModelTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, 1080f)
    private val model = SpatialModel()

    /**
     * The lexicon's real figure, from `WordIndex.of(lexicon).priorRange`.
     *
     * Wider than the 13.0 it used to be because [WordIndex.oovLogPrior] is now the rarest mass
     * the lexicon can report rather than the median word count -- a floor measured in the same
     * quantity it is compared against. A wider range makes this band *narrower*, so the pinning
     * guarantee is strictly stronger than it was, not weaker.
     */
    private val priorRange = 18.62f

    private fun at(letter: Char, dx: Float, dy: Float): List<SpatialModel.Candidate> {
        val rect = geometry.letterKeys[letter - 'a']!!
        val tap = TapDecoder.Tap.of(
            rect.centerX + (model.offsetX + dx) * geometry.keyUnit,
            rect.centerY + (model.offsetY + dy) * geometry.keyHeight,
            letter,
            geometry,
            model,
        )
        return model.candidates(tap, priorRange)
    }

    @Test
    fun `a tap where the thumb aims has exactly one reading`() {
        for (letter in 'a'..'z') {
            val found = at(letter, 0f, 0f)
            assertEquals("$letter is not pinned at its own aim point", 1, found.size)
            assertEquals(letter, found.single().letter)
        }
    }

    /**
     * The interesting half. A tap has to be a long way off its aim before anything else becomes
     * affordable -- and "a long way" is most of the key, which is why ordinary typing is stable.
     */
    @Test
    fun `the pinned band covers most of a key`() {
        // Bracketed rather than bounded, because the width of this band is the feature. A tap is
        // pinned out to 0.34 key widths and 0.38 key heights from its aim point -- still past the
        // edge of the drawn key in both directions -- and only past that is there a second
        // reading.
        assertEquals("pinned at 0.34 key widths out", 1, at('g', 0.34f, 0f).size)
        assertTrue("but not at 0.35", at('g', 0.35f, 0f).size > 1)
        assertEquals("pinned at 0.37 key heights out", 1, at('g', 0f, 0.37f).size)
        assertTrue("but not at 0.39", at('g', 0f, 0.39f).size > 1)
    }

    /**
     * The thumb lands low, so the drawn centre of a key is already slightly off-aim -- but only
     * slightly, and not enough to put the row above in play. If this ever fails, the offset and
     * the vertical scatter have drifted apart and the keyboard has started second-guessing taps
     * that landed exactly where they were drawn.
     */
    @Test
    fun `a tap on the drawn centre of a key is still pinned`() {
        for (letter in 'a'..'z') {
            val found = at(letter, -model.offsetX, -model.offsetY)
            assertEquals("$letter is not pinned at its drawn centre", 1, found.size)
            assertEquals(letter, found.single().letter)
        }
    }

    /** Candidates come back best first, which the beam relies on for nothing and reads better. */
    @Test
    fun `candidates are ordered by how well they explain the touch`() {
        val found = at('g', 0.55f, 0.45f)
        assertTrue(found.size > 1)
        found.zipWithNext { a, b -> assertTrue(a.logLikelihood >= b.logLikelihood) }
    }
}
