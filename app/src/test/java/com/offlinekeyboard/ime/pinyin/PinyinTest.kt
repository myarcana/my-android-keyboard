package com.offlinekeyboard.ime.pinyin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Run against the real `pinyin.bin`, not a fixture, for the same reason EmojiIndexTest does: the
 * engine is only ever as good as the generated data, and a fixture would let a rebuild that
 * broke the dictionary pass every test.
 *
 * These are written as statements about what typing produces, because that is the only thing
 * anyone can check by using the keyboard.
 */
class PinyinTest {

    private val dict =
        File("src/main/assets/pinyin.bin").inputStream().use(PinyinDict::load)

    private val decoder = Decoder(dict)

    private val script = Script(dict.conversionChars, dict.conversionPhrases)

    private fun top(input: String, n: Int = 1): List<String> =
        decoder.candidates(input, 20).take(n).map { it.text }

    private fun first(input: String): String? = top(input).firstOrNull()

    // --- segmentation ------------------------------------------------------------------------

    @Test
    fun `the generated syllable table matches the shipped dictionary`() {
        // If these drift, a word in the dictionary becomes untypeable and nothing else fails.
        assertEquals(dict.syllableSpellings.toSortedSet(), SyllableTable.ALL.toSortedSet())
    }

    @Test
    fun `a run of letters splits into syllables`() {
        val reading = Syllables.readings("nihao").first()
        assertEquals(listOf("ni", "hao"), reading.ids.map(Syllables::spelling))
    }

    @Test
    fun `an ambiguous run offers both splits`() {
        // xian is 先 as one syllable and 西安 as two; only the decoder can tell which was meant,
        // so the segmenter has to offer both.
        val splits = Syllables.readings("xian").map { it.ids.map(Syllables::spelling) }
        assertTrue("xian missing", splits.contains(listOf("xian")))
        assertTrue("xi an missing", splits.contains(listOf("xi", "an")))
    }

    @Test
    fun `the longest split is offered first`() {
        assertEquals(
            listOf("jin", "tian"),
            Syllables.readings("jintian").first().ids.map(Syllables::spelling),
        )
    }

    @Test
    fun `a syllable spelled exactly is never outranked by a fuzzy reading of it`() {
        // Both `lan` and `nan` are real syllables, so `lan` must mean lan first.
        val reading = Syllables.readings("lan").first()
        assertEquals(listOf("lan"), reading.ids.map(Syllables::spelling))
        assertEquals(0, reading.fuzzyCount)
    }

    // --- words -------------------------------------------------------------------------------

    @Test
    fun `typing a word produces that word`() {
        assertEquals("你好", first("nihao"))
        assertEquals("中国", first("zhongguo"))
        assertEquals("我们", first("women"))
        assertEquals("北京", first("beijing"))
        assertEquals("软件", first("ruanjian"))
    }

    @Test
    fun `a common word beats its homophones`() {
        // 天气 and 天启 share a reading; the common one has to come first.
        assertEquals("天气", first("tianqi"))
        assertEquals("时间", first("shijian"))
    }

    // --- sentences ---------------------------------------------------------------------------

    @Test
    fun `a whole phrase decodes as one sentence`() {
        // The thing a per-syllable lookup cannot do. Every character here has homophones and
        // only the sentence as a whole picks the right ones.
        assertEquals("今天天气很好", first("jintiantianqihenhao"))
    }

    @Test
    fun `a sentence of several words decodes`() {
        assertEquals("我们是中国人", first("womenshizhongguoren"))
    }

    @Test
    fun `a decoding covers everything that was typed`() {
        val best = decoder.candidates("jintiantianqihenhao", 5).first()
        assertEquals(6, best.syllables)
    }

    // --- abbreviations and fuzzy -------------------------------------------------------------

    @Test
    fun `initials alone find a familiar word`() {
        assertTrue("北京 not offered for bj", top("bj", 20).contains("北京"))
    }

    @Test
    fun `a fuzzy speller still gets the word`() {
        // zong for zhong: a southern speaker's confusion, and one the keyboard must absorb.
        assertTrue("中国 not offered for zongguo", top("zongguo", 20).contains("中国"))
    }

    @Test
    fun `there is always a candidate for a syllable`() {
        // The floor: even input no word covers offers its characters.
        assertTrue(top("zhei", 20).isNotEmpty())
        assertTrue(top("a", 20).isNotEmpty())
    }

    // --- traditional -------------------------------------------------------------------------

    @Test
    fun `simplified converts to traditional`() {
        assertEquals("中國", script.toTraditional("中国"))
        assertEquals("我們", script.toTraditional("我们"))
        assertEquals("學習", script.toTraditional("学习"))
    }

    @Test
    fun `taiwan vocabulary is used, not just taiwan glyphs`() {
        // The distinction that separates a real Traditional mode from a glyph swap: Taiwan says
        // 軟體, not the character-wise 軟件.
        assertEquals("軟體", script.toTraditional("软件"))
        assertEquals("網路", script.toTraditional("网络"))
        assertEquals("滑鼠", script.toTraditional("鼠标"))
    }

    @Test
    fun `a phrase decides between two traditional forms of one character`() {
        // 发 is 發 or 髮 depending on the word. Character-wise conversion cannot know.
        assertEquals("頭髮", script.toTraditional("头发"))
        assertEquals("發現", script.toTraditional("发现"))
    }

    @Test
    fun `the same typing produces both scripts`() {
        // The whole request, end to end: one dictionary, one set of keystrokes, and the subtype
        // decides which script comes out.
        val sentences = mapOf(
            "jintiantianqihenhao" to "今天天气很好",
            "womenshizhongguoren" to "我们是中国人",
            "beijingdaxue" to "北京大学",
        )
        for ((typed, simplified) in sentences) {
            assertEquals(simplified, first(typed))
        }
        assertEquals("今天天氣很好", script.toTraditional(first("jintiantianqihenhao")!!))
        assertEquals("我們是中國人", script.toTraditional(first("womenshizhongguoren")!!))
        assertEquals("北京大學", script.toTraditional(first("beijingdaxue")!!))
    }

    @Test
    fun `text with no simplified characters is unchanged`() {
        assertEquals("hello", script.toTraditional("hello"))
        assertEquals("123", script.toTraditional("123"))
    }

    // --- learning ----------------------------------------------------------------------------

    @Test
    fun `a chosen word is offered first next time`() {
        // The behaviour people judge a keyboard on: correct it once, and it stops making that
        // mistake. Done over a single syllable with many homophones, which is the case where the
        // shipped frequencies are least likely to match one person's intent.
        val user = UserDict(null)
        val learning = Decoder(dict, user)
        val ids = Syllables.readings("ta").first().ids

        // The correction has to be a word for *this* key: one syllable, so one character. A
        // two-character candidate like 天啊 is a reading of `t`+`a`, a different segmentation
        // entirely, and learning it against the single-syllable `ta` would be recording
        // something the user did not choose.
        val before = learning.candidates("ta", 20).first { it.syllables == 1 }.text
        val other = learning.candidates("ta", 20)
            .filter { it.syllables == 1 }
            .map { it.text }
            .first { it != before }

        user.learn(ids, other)

        assertEquals(other, learning.candidates("ta", 20).first().text)
    }

    @Test
    fun `an abbreviation is not learned against`() {
        // `b j` means whatever was on screen; attaching a word to it would poison every future
        // abbreviation alike.
        val user = UserDict(null)
        val ids = Syllables.readings("bj").first().ids
        user.learn(ids, "北京")
        assertTrue(user.wordsFor(ids).isEmpty())
    }

    // --- session -----------------------------------------------------------------------------

    @Test
    fun `committing part of the input leaves the rest composing`() {
        // Entering a phrase a word at a time is normal, so a candidate that covers only the
        // start of the input has to consume exactly its own letters and leave the others.
        // This is the arithmetic PinyinSession.commit does; getting it wrong either eats letters
        // the user still needs or repeats ones already committed.
        val input = "beijingdaxue"
        val reading = Syllables.readings(input).first()
        assertEquals(listOf("bei", "jing", "da", "xue"), reading.ids.map(Syllables::spelling))

        // 北京 is two syllables, so it consumes through the end of the second one.
        val consumed = reading.ends[1]
        assertEquals("beijing", input.substring(0, consumed))
        assertEquals("daxue", input.substring(consumed))
    }

    @Test
    fun `a partial candidate is offered for a phrase`() {
        // The candidate bar has to contain the word to commit, or there is nothing to tap.
        val offered = decoder.candidates("beijingdaxue", 20).map { it.text }
        assertTrue("北京 not offered: $offered", offered.contains("北京"))
    }

    @Test
    fun `a phrase typed in one go decodes whole`() {
        assertEquals("北京大学", first("beijingdaxue"))
    }

    @Test
    fun `an empty input has no candidates`() {
        assertTrue(decoder.candidates("", 20).isEmpty())
    }

    @Test
    fun `input that is not letters is rejected`() {
        assertFalse(Syllables.isPinyinText("ni3hao"))
        assertFalse(Syllables.isPinyinText(""))
        assertTrue(Syllables.isPinyinText("nihao"))
    }

    @Test
    fun `the dictionary actually loaded`() {
        assertNotNull(dict)
        assertTrue("suspiciously few keys: ${dict.keyCount}", dict.keyCount > 100_000)
    }
}
