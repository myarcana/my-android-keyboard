package com.offlinekeyboard.ime.candidates

import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import com.offlinekeyboard.ime.glide.Lexicon
import com.offlinekeyboard.ime.pinyin.Decoder
import com.offlinekeyboard.ime.pinyin.PinyinDict
import com.offlinekeyboard.ime.pinyin.ScriptMode
import com.offlinekeyboard.ime.pinyin.UserDict
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Short pinyin on the English bar, against the real shipped assets.
 *
 * `ma` showed twelve emoji -- ‼️ ✨ 🤷 ❣️ ❗ 💋 ... -- and not one Chinese character, because three
 * separate errors all pushed the same way:
 *
 *  1. Every emoji matched by *prefix* was scored as if the user had typed its name. ‼️ matches
 *     `ma` only because `mark` starts with `ma`, and `mark` is about 1% of the English that does.
 *  2. The decoder charged its 6-nat character backoff on a one-syllable input, where there is no
 *     word to back off from, so 吗 scored -12.4 against a dictionary value of -6.4.
 *  3. That same deflation let the abbreviation reading `m a` -- 买啊, 毛啊 -- lead the Chinese
 *     list ahead of 马 and 妈.
 */
class ShortPinyinBarTest {

    private val dict = File("src/main/assets/pinyin.bin").inputStream().use(PinyinDict::load)
    private val emoji = File("src/main/assets/emoji_en.tsv").inputStream().use(EmojiIndex::load)
    private val english = File("src/main/assets/$LEXICON_ASSET").inputStream().use(Lexicon::load)
    private val decoder = Decoder(dict, UserDict(null), ScriptMode.BOTH)

    /** PinyinSession.scoredFor + KeyboardService.rankedFor, minus the Android parts. */
    private fun englishBar(q: String): List<UnifiedCandidates.Suggestion> {
        val seen = HashSet<String>()
        val scored = decoder.candidates(q, 20)
            .filter { seen.add(it.text) }
            .map { UnifiedCandidates.Scored(it.text, it.score, it.consumed, it.ids) }
        return UnifiedCandidates.suggest(q, emoji, scored, english)
    }

    /** What fits on the strip before scrolling: eight emoji-width cells. */
    private fun visible(q: String) = englishBar(q).take(8)

    @Test
    fun `ma shows the ma characters on the visible strip`() {
        val shown = visible("ma").map { it.text }
        for (c in listOf("吗", "马", "妈")) {
            assertTrue("$c not on the visible strip for `ma`: $shown", c in shown)
        }
    }

    @Test
    fun `common one-syllable pinyin leads with its character`() {
        val cases = mapOf("ni" to "你", "wo" to "我", "hao" to "好", "shi" to "是", "de" to "的")
        for ((q, c) in cases) {
            assertEquals("`$q`: ${visible(q).map { it.text }}", c, englishBar(q).first().text)
        }
    }

    @Test
    fun `a single syllable is its characters, not an abbreviation`() {
        // `m a` read as two abbreviated syllables spells 买啊 and 毛啊; neither should lead.
        val zh = decoder.candidates("ma", 20).map { it.text }
        assertEquals("吗", zh.first())
        assertTrue("马 behind the abbreviations: $zh", zh.indexOf("马") < zh.indexOf("买啊").let { if (it < 0) Int.MAX_VALUE else it })
        val hen = decoder.candidates("hen", 20).map { it.text }
        assertEquals("很", hen.first())
    }

    @Test
    fun `an ambiguous split still offers both readings`() {
        // Removing the backoff lifts 先 and friends; they must not crowd 西安 off the bar.
        val zh = Decoder(dict, UserDict(null), ScriptMode.SIMPLIFIED).candidates("xian", 20).map { it.text }
        assertEquals("先", zh.first())
        assertTrue("西安 missing for `xian`: $zh", "西安" in zh.take(8))
    }

    @Test
    fun `emoji the letters clearly spell still lead`() {
        // The completion discount must not cost these: the letters leave little doubt.
        mapOf("piz" to "🍕", "lov" to "❤️", "hap" to "😂", "pizza" to "🍕", "happy" to "😂")
            .forEach { (q, e) -> assertEquals("`$q`: ${visible(q).map { it.text }}", e, englishBar(q).first().text) }
    }

    @Test
    fun `an english word stays an english bar`() {
        // `you` is 有 in pinyin, but it is one of the commonest English words; emoji lead.
        assertEquals(UnifiedCandidates.Kind.EMOJI, englishBar("you").first().kind)
        assertEquals(UnifiedCandidates.Kind.EMOJI, englishBar("the").first().kind)
    }

    @Test
    fun `a prefix match is discounted by how unlikely its word is`() {
        val ma = emoji.matches("ma")
        val fit = UnifiedCandidates.emojiFit(
            "ma", ma, english::logProbability, english::logPrefixProbability,
            english.floorLogProbability,
        )
        val mark = fit[ma.indexOfFirst { it.emoji == "‼️" }]
        assertTrue("‼️ via `mark` should be a long shot for `ma`, got $mark", mark < -3f)
        val piz = emoji.matches("piz")
        val pizFit = UnifiedCandidates.emojiFit(
            "piz", piz, english::logProbability, english::logPrefixProbability,
            english.floorLogProbability,
        )
        assertTrue("🍕 via `pizza` should be near-certain for `piz`, got ${pizFit[0]}", pizFit[0] > -0.5f)
    }
}
