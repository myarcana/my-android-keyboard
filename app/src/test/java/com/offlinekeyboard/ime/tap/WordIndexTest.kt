package com.offlinekeyboard.ime.tap

import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import com.offlinekeyboard.ime.glide.Lexicon
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** That the sorted-array prefix search answers what a trie would have. */
class WordIndexTest {

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

    private fun WordIndex.spanOf(prefix: String): WordIndex.Span {
        var span = root
        prefix.forEachIndexed { depth, c -> span = extend(span, depth, c) }
        return span
    }

    /**
     * The span for a prefix is exactly the words that begin with it. Checked against a scan of
     * the lexicon rather than against a number, so it is the definition being tested.
     */
    @Test
    fun `a prefix span is exactly the words beginning with that prefix`() {
        val lexicon = lexicon ?: return
        val index = WordIndex.of(lexicon)
        listOf("th", "the", "rhe", "qu", "z", "keyboa", "zzzz").forEach { prefix ->
            val span = index.spanOf(prefix)
            val expected = lexicon.letters.count { it.startsWith(prefix) }
            assertEquals("wrong span size for \"$prefix\"", expected, span.hi - span.lo)
        }
    }

    /**
     * A word already ended is not a word continuing. Inside the run for "an" there are entries of
     * exactly two letters, and they must not be handed back as continuations of it.
     */
    @Test
    fun `a word that has ended is not a continuation of itself`() {
        val lexicon = lexicon ?: return
        val index = WordIndex.of(lexicon)
        val an = index.spanOf("an")
        val ant = index.extend(an, 2, 't')
        assertTrue(ant.hi - ant.lo < an.hi - an.lo)
        assertEquals(lexicon.letters.count { it.startsWith("ant") }, ant.hi - ant.lo)
    }

    /** Prefix mass orders the way English does, and an unknown spelling sits below both. */
    @Test
    fun `a commoner prefix carries more mass`() {
        val lexicon = lexicon ?: return
        val index = WordIndex.of(lexicon)
        val the = index.logPrior(index.spanOf("the"))
        val rhe = index.logPrior(index.spanOf("rhe"))
        val unknown = index.logPrior(index.spanOf("zqx"))
        assertTrue("the ($the) should carry more mass than rhe ($rhe)", the > rhe)
        assertTrue("rhe ($rhe) should carry more mass than an unknown spelling", rhe > unknown)
        assertEquals(index.oovLogPrior, unknown, 0.0001f)
    }

    /**
     * The bound the whole pinning argument rests on: nothing the prior can say is worth more than
     * [WordIndex.priorRange] nats, because the widest gap it has is the whole corpus against one
     * ordinary word.
     */
    @Test
    fun `no prefix is worth more than the stated dynamic range`() {
        val lexicon = lexicon ?: return
        val index = WordIndex.of(lexicon)
        assertTrue(index.priorRange > 0f)
        val widest = ('a'..'z').maxOf { index.logPrior(index.extend(index.root, 0, it)) }
        assertTrue(
            "a single letter is worth $widest, over the claimed ceiling of ${index.priorRange}",
            widest - index.oovLogPrior <= index.priorRange + 0.0001f,
        )
    }
}
