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
    fun `pinyin with no emoji is all chinese`() {
        val bar = englishBar("niuroumian")
        assertEquals("牛肉面", bar.first().text)
        assertTrue(bar.all { it.kind == UnifiedCandidates.Kind.CHINESE })
    }
}
