package com.offlinekeyboard.ime.asr

import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every auto/forced pair here is a real SenseVoice output (int8, sherpa-onnx 1.13.6) on
 * synthesised speech -- zh-TW and multilingual voices reading mixed sentences -- not an invented
 * string. The judgement is made against the shipped English lexicon, the same asset
 * [Dictation] loads, so these tests fail if the lexicon changes in a way that matters.
 *
 * The case that prompted the rewrite, reported verbatim:
 *
 *     spoken:  "The best 豆花 in the world"
 *     written: "The best dohua in the world"
 *
 * The old detector wanted two pinyin-shaped words; this has one, and "dohua" is not pinyin.
 */
class CodeSwitchTest {

    private val english: Set<String> = run {
        var dir: File? = File("").absoluteFile
        var found: File? = null
        repeat(5) {
            val candidate = dir?.resolve("app/src/main/assets/$LEXICON_ASSET")
            if (found == null && candidate != null && candidate.isFile) found = candidate
            dir = dir?.parentFile
        }
        assertNotNull("lexicon asset not found", found)
        found!!.inputStream().use(CodeSwitch::readEnglishWords)
    }

    private val isEnglish: (String) -> Boolean = { it.lowercase() in english }

    private fun suspects(text: String, lang: String = "<|en|>") =
        CodeSwitch.suspectsMissedChinese(text, lang, isEnglish)

    private fun choose(auto: String, forced: String) = CodeSwitch.choose(auto, forced, isEnglish)

    // --- the reported failures -----------------------------------------------------------

    @Test
    fun `the best dohua is repaired`() {
        val auto = "the best dohua in the world"
        val forced = "the best豆花 in the world"
        assertTrue(suspects(auto))
        assertEquals(forced, choose(auto, forced))
    }

    @Test
    fun `a romanised tail of pinyin syllables is repaired`() {
        // "ne" and "ma" are in the lexicon; they are also the romanisation being replaced.
        val auto = "what's your favorite taiwanese food ne ro mian ma"
        val forced = "what's your favorite taiwanese food牛肉面吗"
        assertTrue(suspects(auto))
        assertEquals(forced, choose(auto, forced))
    }

    @Test
    fun `single romanised words that are not pinyin are repaired`() {
        for ((auto, forced) in listOf(
            "we ordered hogu and it was really good" to "we ordered火锅 and it was really good",
            "this littleo fan is too salty" to "this卤肉饭 is too salty",
            "she lives near xmaning" to "she lives near西门庭",
            "let's get zhenz奶茶 after work" to "let's get珍珠奶茶 after work",
        )) {
            assertTrue(auto, suspects(auto))
            assertEquals(auto, forced, choose(auto, forced))
        }
    }

    // --- only the garbled span is retried -------------------------------------------------

    /** The real auto decode of "I think 螺蛳粉 is the best food in the world" (zh-TW voice). */
    private val rociphon = CommandMerge.Transcript(
        text = "i think rociphon is the best food in the world",
        tokens = listOf("i", " think", " ro", "ci", "phon", " is", " the", " best", " food", " in", " the", " world"),
        timestamps = listOf(0.12f, 0.30f, 0.54f, 0.78f, 1.02f, 1.14f, 1.32f, 1.44f, 1.68f, 1.86f, 1.98f, 2.10f),
    )

    @Test
    fun `the garbled word is found with the audio it was spoken in`() {
        val run = CodeSwitch.suspectRuns(rociphon, 3.24f, isEnglish).single()
        assertEquals("rociphon", run.text)
        assertEquals(2, run.firstToken)
        assertEquals(4, run.lastToken)
        // From its first token to the start of "is": the span the retry decodes, nothing more.
        assertEquals(0.54f, run.startSeconds)
        assertEquals(1.14f, run.endSeconds)
    }

    @Test
    fun `the span retry's Han is spliced in and the English is untouched`() {
        val run = CodeSwitch.suspectRuns(rociphon, 3.24f, isEnglish).single()
        // The real forced-zh decode of 0.44..1.24 s of that audio.
        val repaired = CodeSwitch.choose(run.text, "螺蛳粉", isEnglish)
        assertEquals("螺蛳粉", repaired)
        val out = CodeSwitch.splice(rociphon, listOf(run to repaired))
        assertEquals("i think螺蛳粉 is the best food in the world", out.text)
        assertEquals(out.tokens.size, out.timestamps.size)
        assertEquals(0.54f, out.timestamps[2])
        assertEquals(1.14f, out.timestamps[3])
    }

    @Test
    fun `adjacent unknown words are one span`() {
        val t = CommandMerge.Transcript(
            text = "i think lu sien is good",
            tokens = listOf("i", " think", " lu", " si", "en", " is", " good"),
            timestamps = listOf(0.1f, 0.3f, 0.72f, 0.9f, 1.1f, 1.44f, 1.6f),
        )
        val run = CodeSwitch.suspectRuns(t, 2f, isEnglish).single()
        assertEquals("lu sien", run.text)
        assertEquals(0.72f, run.startSeconds)
        assertEquals(1.44f, run.endSeconds)
    }

    @Test
    fun `a span retry that drags in a neighbouring English word is refused`() {
        // Too much padding decodes the next word too; "is" would appear twice.
        assertEquals("wifan", CodeSwitch.choose("wifan", "拼螺蛳粉 is", isEnglish))
        assertEquals("wassufu", CodeSwitch.choose("wassufu", "", isEnglish))
    }

    @Test
    fun `a trailing span runs to the end of the segment`() {
        val t = CommandMerge.Transcript(
            text = "she lives near xmaning",
            tokens = listOf("she", " lives", " near", " xman", "ing"),
            timestamps = listOf(0.1f, 0.3f, 0.6f, 0.9f, 1.2f),
        )
        val run = CodeSwitch.suspectRuns(t, 1.8f, isEnglish).single()
        assertEquals(1.8f, run.endSeconds)
        assertEquals("she lives near西门町", CodeSwitch.splice(t, listOf(run to "西门町")).text)
    }

    @Test
    fun `two spans are both spliced`() {
        val t = CommandMerge.Transcript(
            text = "the xmaning and dohua",
            tokens = listOf("the", " xman", "ing", " and", " do", "hua"),
            timestamps = listOf(0.1f, 0.3f, 0.5f, 0.8f, 1.0f, 1.2f),
        )
        val runs = CodeSwitch.suspectRuns(t, 1.6f, isEnglish)
        assertEquals(listOf("xmaning", "dohua"), runs.map { it.text })
        val out = CodeSwitch.splice(t, listOf(runs[0] to "西门町", runs[1] to "豆花"))
        assertEquals("the西门町 and豆花", out.text)
        assertEquals(listOf(0.1f, 0.3f, 0.8f, 1.0f), out.timestamps)
    }

    @Test
    fun `English and Han produce no spans`() {
        val t = CommandMerge.Transcript(
            text = "the best豆花 in the world",
            tokens = listOf("the", " best", "豆", "花", " in", " the", " world"),
            timestamps = listOf(0.1f, 0.3f, 0.6f, 0.7f, 0.9f, 1.0f, 1.1f),
        )
        assertTrue(CodeSwitch.suspectRuns(t, 1.5f, isEnglish).isEmpty())
    }

    // --- the forced pass must not damage English ----------------------------------------

    @Test
    fun `a retry that rewrites an English word loses`() {
        // Real forced-zh outputs that fixed the Chinese and broke something else.
        for ((auto, forced) in listOf(
            "the jieyun was so crowded this morning" to "the捷i运 was so quiet this morning",
            "do you want xian fuji tonight" to "do you忘弦苏记 tonight",
            "i think manng koing is the perfect summer dessert" to
                "i think芒ango冰 is the perfect summer dessert dessert",
            "we went to costco and bought some tinhua" to "we went to costco and bought some听hu",
        )) {
            assertEquals(forced, auto, choose(auto, forced))
        }
    }

    @Test
    fun `a retry that adds no Han loses`() {
        val auto = "the kubernetes deployment failed again on tuesday"
        assertEquals(auto, choose(auto, auto))
        assertEquals(auto, choose(auto, ""))
        val dohua = "the best dohua in the world"
        assertEquals(dohua, choose(dohua, "the best do hua in the world"))
    }

    @Test
    fun `a retry that swallows the English loses`() {
        val auto = "what's your favorite taiwanese food ne ro mian ma"
        assertEquals(auto, choose(auto, "牛肉面吗"))
    }

    @Test
    fun `English proper nouns are retried but never changed`() {
        // A retry costs a decode; the text is what matters, and it is left alone.
        for (t in listOf(
            "the kubernetes deployment failed again on tuesday",
            "my friend shavoon is flying to reykjavik",
            "we went to costco and bought some quinoa",
        )) {
            assertTrue(t, suspects(t))
            assertEquals(t, choose(t, t))
        }
    }

    // --- when not to retry ---------------------------------------------------------------

    @Test
    fun `plain English is not retried`() {
        for (t in listOf(
            "can you pick up the kids at 430",
            "send the invoice to accounts by friday please",
            "i'll be about 15 minutes late the train is delayed again",
            "the premium account has various serious issues",
            "we went to hong kong last year for a holiday",
        )) {
            assertFalse(t, suspects(t))
        }
    }

    @Test
    fun `a segment auto already decoded as Chinese is not retried`() {
        // sherpa-onnx reports the raw token. The old check compared against bare "zh" and never
        // matched, so these were decoded twice for an identical answer.
        assertFalse(suspects("这个包裹已经 fix 好了", "<|zh|>"))
        assertFalse(suspects("do you want显速机 tonight", "<|zh|>"))
    }

    @Test
    fun `lang tokens are normalised`() {
        assertEquals("zh", CodeSwitch.normaliseLang("<|zh|>"))
        assertEquals("en", CodeSwitch.normaliseLang("en"))
    }

    @Test
    fun `blank text is never suspected`() {
        assertFalse(suspects(""))
        assertFalse(suspects("   "))
    }

    @Test
    fun `latin words ignore Han, digits and edge apostrophes`() {
        assertEquals(listOf("the", "best", "in"), CodeSwitch.latinWords("the best豆花 in 430"))
        assertEquals(listOf("what's"), CodeSwitch.latinWords("'what's'"))
    }
}
