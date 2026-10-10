package com.offlinekeyboard.ime.pinyin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typing slips in pinyin, against the real `pinyin.bin`.
 *
 * `lupbogao` is the input that started it: one key off `luobogao`, and the bar led with
 * 路拼搏港澳 because the stray `p` could only be read as an abbreviation.
 */
class TyposTest {

    private val dict = File("src/main/assets/pinyin.bin").inputStream().use(PinyinDict::load)

    private fun bar(input: String, mode: ScriptMode = ScriptMode.SIMPLIFIED): List<Decoder.Candidate> =
        Decoder(dict, null, mode).candidates(input, 20)

    private fun first(input: String, mode: ScriptMode = ScriptMode.SIMPLIFIED): String? =
        bar(input, mode).firstOrNull()?.text

    // --- slips are corrected ------------------------------------------------------------------

    @Test
    fun `a neighbouring key in the middle of a word is corrected`() {
        assertEquals("萝卜糕", first("lupbogao"))
        assertEquals("蘿蔔糕", first("lupbogao", ScriptMode.TRADITIONAL))
        val both = bar("lupbogao", ScriptMode.BOTH).take(2).map { it.text }.toSet()
        assertEquals(setOf("萝卜糕", "蘿蔔糕"), both)
    }

    @Test
    fun `the other kinds of slip are corrected`() {
        assertEquals("谢谢", first("xiexei")) // two letters swapped
        assertEquals("我们", first("wpmen")) // neighbouring key, early in the word
        assertEquals("什么", first("sjenme")) // a stray letter
        assertEquals("什么", first("shrnme")) // neighbouring key, stranding a run of consonants
        assertEquals("我的手机没电了", first("wodeshojimeidianle")) // a dropped letter
        assertEquals("今天天气很好", first("jintiantianqihrnhao")) // inside a sentence
    }

    @Test
    fun `a corrected candidate consumes the letters as typed`() {
        // Committing it must replace every letter on screen, including the slip, or the stray
        // `p` would be left composing behind 萝卜糕.
        val best = bar("lupbogao").first()
        assertEquals("lupbogao".length, best.consumed)
        // A correction that shortens the word still consumes the letters actually typed.
        assertEquals("sjenme".length, bar("sjenme").first().consumed)
        assertEquals("wodeshojimeidianle".length, bar("wodeshojimeidianle").first().consumed)
    }

    // --- nothing else changes ----------------------------------------------------------------

    @Test
    fun `input that parses is never corrected`() {
        // Clean pinyin is evidence the user meant those syllables, however unlikely its reading.
        for (input in listOf("danta", "nihao", "luobogao", "zhongguo", "cifan", "nihap")) {
            val literal = Syllables.readings(input)
            assertTrue("`$input` was corrected", Typos.readings(input, literal).isEmpty())
        }
    }

    @Test
    fun `abbreviations are not mistaken for slips`() {
        // Trailing consonants are an abbreviation or a syllable still being typed, never a slip.
        for (input in listOf("bjdx", "beijingdx", "luob", "zhongguor")) {
            val literal = Syllables.readings(input)
            assertTrue("`$input` was corrected", Typos.readings(input, literal).isEmpty())
        }
    }

    @Test
    fun `a mixed abbreviation keeps its reading`() {
        // `bjdaxue` abbreviates first and spells after, which looks like a slip to the trigger
        // and does get corrections tried. They must lose: the literal reading is a good one, and
        // a guess is held behind it.
        val plain = Decoder(dict, null, ScriptMode.SIMPLIFIED, correctTypos = false)
        for (input in listOf("bjdaxue", "zgren", "nhmen")) {
            assertEquals("`$input`", plain.candidates(input, 20).first().text, first(input))
        }
        assertEquals("北京大学", first("bjdaxue"))
    }

    @Test
    fun `letters with no pinyin reading are left to english`() {
        // These do not segment at all. Correcting them put 起来了 and 新的 under `will` and
        // `find` in the English bar.
        for (input in listOf("will", "find", "have", "very", "going")) {
            assertTrue("`$input` got a chinese reading: ${bar(input).map { it.text }}", bar(input).isEmpty())
        }
    }

    @Test
    fun `neighbours follow the drawn layout`() {
        assertEquals("ol".toSet(), Typos.neighbours('p').toSet())
        assertEquals("iplk".toSet(), Typos.neighbours('o').toSet())
        assertEquals("asdx".toSet(), Typos.neighbours('z').toSet())
    }
}
