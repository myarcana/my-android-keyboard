package com.offlinekeyboard.ime.candidates

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Run against the real asset, not a fixture. The index is only ever as good as the generated
 * data, and a fixture would let a regeneration that broke the data pass every test.
 */
class EmojiIndexTest {

    private val index = File("src/main/assets/emoji_en.tsv").inputStream().use(EmojiIndex::load)

    private fun first(query: String): String? = index.search(query).firstOrNull()

    @Test
    fun `the obvious word offers the obvious emoji first`() {
        assertEquals("🍕", first("pizza"))
        assertEquals("🐈", first("cat"))
        assertEquals("👻", first("ghost"))
        assertEquals("🚀", first("rocket"))
    }

    @Test
    fun `a two-word name is found as a whole`() {
        assertEquals("👍", first("thumbs up"))
        assertEquals("🎉", first("party popper"))
    }

    @Test
    fun `an emoji named for the word beats one merely tagged with it`() {
        // "fire" tags 🚒 and 🧯 too; the one actually called fire has to come first.
        assertEquals("🔥", first("fire"))
        assertEquals("❤️", first("red heart"))
    }

    @Test
    fun `frequency decides between emoji the words cannot separate`() {
        // Every one of these is a tag match with nothing but frequency to rank it, and
        // canonical Unicode order gets all four wrong: it offers the heart *suit*, the water
        // buffalo, the railway car and the baby bottle.
        assertEquals("❤️", first("heart"))
        assertEquals("❤️", first("love"))
        assertEquals("💦", first("water"))
        assertTrue(index.search("car").contains("🚗"))
        assertEquals("☕", first("drink"))
    }

    @Test
    fun `an emoji is offered even when a more frequent one shares the word`() {
        // 🎉 is tagged "birthday" and used far more, so the cake does not lead -- but a bar
        // that did not offer it at all for the word it is named after would be broken.
        assertTrue(index.search("birthday").contains("🎂"))
    }

    @Test
    fun `a prefix matches while the word is still being typed`() {
        assertTrue(index.search("piz").contains("🍕"))
        assertTrue(index.search("laugh").isNotEmpty())
    }

    @Test
    fun `a single letter suggests nothing`() {
        assertTrue(index.search("p").isEmpty())
        assertTrue(index.search("").isEmpty())
    }

    @Test
    fun `case and surrounding space do not matter`() {
        assertEquals(index.search("pizza"), index.search("  PiZZa "))
    }

    @Test
    fun `results are distinct and capped`() {
        val hits = index.search("face")
        assertEquals(hits.size, hits.distinct().size)
        assertTrue(hits.size <= EmojiIndex.MAX_RESULTS)
    }

    @Test
    fun `a word with no emoji offers nothing rather than something irrelevant`() {
        assertTrue(index.search("qwertyuiop").isEmpty())
    }
}
