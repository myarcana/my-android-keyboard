package com.offlinekeyboard.ime.candidates

import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import com.offlinekeyboard.ime.glide.Lexicon
import com.offlinekeyboard.ime.pinyin.Decoder
import com.offlinekeyboard.ime.pinyin.PinyinDict
import com.offlinekeyboard.ime.pinyin.ScriptMode
import com.offlinekeyboard.ime.pinyin.Syllables
import com.offlinekeyboard.ime.pinyin.UserDict
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sentence typed in one go, on the English bar, against the real shipped dictionary.
 *
 * `tadebabahentaoyanwo` (他的爸爸很讨厌我) failed twice over:
 *
 *  1. The bar showed only 他的 她的 它的. The ranker re-sorted Chinese on a score whose charge for
 *     unexplained letters was capped at 18 nats while an eight-syllable sentence costs ~39, so
 *     the four-letter prefix won, and the filler rule then deleted the sentence outright.
 *  2. Tapping 他的 left `tadebabahentaoy他的` in the field: the four letters it stands for were
 *     deleted back from the caret, i.e. from the *end* of the word rather than its start.
 */
class SentenceBarTest {

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
        return UnifiedCandidates.rank(
            q,
            emoji.search(q),
            scored,
            english.logProbability(q) ?: UnifiedCandidates.NOT_ENGLISH,
        )
    }

    private fun chinese(q: String) =
        englishBar(q).filter { it.kind == UnifiedCandidates.Kind.CHINESE }

    @Test
    fun `a whole sentence leads the english bar`() {
        val bar = chinese("tadebabahentaoyanwo")
        assertEquals("他的爸爸很讨厌我", bar.first().text)
        // The prefix is still offered -- committing a word at a time is normal -- just after
        // every reading of the whole input.
        val prefix = bar.indexOfFirst { it.text == "他的" }
        val lastWhole = bar.indexOfLast { it.consumes == "tadebabahentaoyanwo".length }
        assertTrue("他的 missing: ${bar.map { it.text }}", prefix >= 0)
        assertTrue("他的 outranks a whole reading: ${bar.map { it.text }}", lastWhole < prefix)
    }

    @Test
    fun `a longer sentence does not lose to its first words`() {
        assertEquals("我很喜欢你", chinese("wohenxihuanni").first().text)
    }

    @Test
    fun `the english bar orders chinese exactly as the chinese bar does`() {
        // One ranking, two bars: the English bar may decide whether Chinese appears and where
        // emoji sit, but never which Chinese reading comes first.
        for (q in listOf("tadebabahentaoyanwo", "danta", "niuroumian", "beijingdaxue", "ha", "ma")) {
            val zhBar = decoder.candidates(q, 20).map { it.text }.distinct()
            val shown = chinese(q).map { it.text }
            assertEquals("order differs for `$q`", zhBar.take(shown.size), shown)
        }
    }

    @Test
    fun `a prefix pick consumes the start of the word and keeps the rest`() {
        val word = "tadebabahentaoyanwo"
        val pick = chinese(word).first { it.text == "他的" }
        assertEquals("tade", word.substring(0, pick.consumes))
        assertEquals(
            "他的babahentaoyanwo",
            UnifiedCandidates.replacementFor(word, pick.text, pick.consumes),
        )
        // Learned against `ta de`, not the whole sentence's key.
        assertEquals(
            listOf("ta", "de"),
            pick.source!!.ids.map(Syllables::spelling),
        )
    }

    @Test
    fun `after a prefix pick the bar answers the rest`() {
        // The next query must be the letters after 他的, not the whole mixed run.
        val next = TypedWord.endingAt("他的babahentaoyanwo").map { it.query }
        assertEquals(listOf("babahentaoyanwo"), next)
        assertEquals("爸爸很讨厌我", chinese("babahentaoyanwo").first().text)
    }

    @Test
    fun `english words still show emoji first`() {
        for (q in listOf("happy", "pizza", "love")) {
            val bar = englishBar(q)
            assertEquals("`$q` did not lead with emoji", UnifiedCandidates.Kind.EMOJI, bar.first().kind)
        }
        // `happy` parses as 哈皮朋友 but is an English word; its Chinese is left off entirely.
        assertTrue(englishBar("happy").none { it.kind == UnifiedCandidates.Kind.CHINESE })
    }

    @Test
    fun `a sentence is offered in traditional as well as simplified`() {
        // The English bar has not been told which script the user writes, so a sentence must
        // reach it in both. It used to be Simplified only: one Viterbi pass over both corpora let
        // the larger mainland one win every slot as the sentence grew.
        val cases = mapOf(
            "nixiangchimianma" to ("你想吃面吗" to "你想吃麵嗎"),
            "wohenxihuanni" to ("我很喜欢你" to "我很喜歡你"),
            "wodeshoujimeidianle" to ("我的手机没电了" to "我的手機沒電了"),
            "tadebabahentaoyanwo" to ("他的爸爸很讨厌我" to "他的爸爸很討厭我"),
        )
        for ((q, pair) in cases) {
            val (simplified, traditional) = pair
            val whole = chinese(q).filter { it.consumes == q.length }.map { it.text }
            assertTrue("$simplified missing for `$q`: $whole", simplified in whole.take(2))
            assertTrue("$traditional missing for `$q`: $whole", traditional in whole.take(2))
        }
        val kema = chinese("wojinwankeyishangkema").map { it.text }.take(3)
        assertTrue("no Traditional sentence for `wojinwankeyishangkema`: $kema",
            kema.any { it.startsWith("我今晚可以上課") })
        assertTrue("no Simplified sentence for `wojinwankeyishangkema`: $kema",
            "我今晚可以上课吗" in kema)
    }

    @Test
    fun `a sentence does not switch script halfway`() {
        // 上课麼 and 上課吗 are what the mixed-corpus pass produced: each character picked from
        // whichever corpus liked it better, so no writer of either script would type them.
        val mixed = setOf("我今晚可以上课麼", "我今晚可以上課吗", "你想吃面麼", "你想吃麵吗")
        for (q in listOf("wojinwankeyishangkema", "nixiangchimianma")) {
            val shown = chinese(q).map { it.text }
            assertTrue("mixed-script sentence for `$q`: $shown", shown.none { it in mixed })
        }
    }

    @Test
    fun `traditional sentences use taiwan words`() {
        // Converted in the Taiwan standard, not character by character: 軟體, not 軟件.
        val bar = chinese("ruanjian").map { it.text }
        assertTrue("軟體 missing: $bar", "軟體" in bar.take(2))
    }

    @Test
    fun `pinyin with no emoji is all chinese`() {
        val bar = englishBar("niuroumian")
        assertEquals("牛肉面", bar.first().text)
        assertTrue(bar.all { it.kind == UnifiedCandidates.Kind.CHINESE })
    }
}
