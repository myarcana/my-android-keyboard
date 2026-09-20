package com.offlinekeyboard.ime.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The case this exists for is a real one, reported verbatim:
 *
 *     spoken:    "what's your favorite taiwanese food 牛肉麵嗎"
 *     written:   "what's your favorite taiwanese food ne roium ma"
 *
 * SenseVoice decided the segment was English and decoded the Mandarin tail through an
 * English-conditioned decoder. The tail is Latin letters that are not English words.
 */
class CodeSwitchTest {

    private val reported = "what's your favorite taiwanese food ne roium ma"

    // --- the reported failure ------------------------------------------------------------

    @Test
    fun `the reported sentence is flagged as missed Chinese`() {
        assertTrue(CodeSwitch.suspectsMissedChinese(reported, CodeSwitch.LANG_EN))
    }

    @Test
    fun `the forced Chinese decode wins on the reported sentence`() {
        val forced = "what's your favorite taiwanese food 牛肉麵嗎"
        assertEquals(forced, CodeSwitch.choose(reported, forced))
    }

    // --- ordinary English must never be touched ------------------------------------------

    @Test
    fun `plain English is not flagged`() {
        assertFalse(
            CodeSwitch.suspectsMissedChinese(
                "what's your favorite taiwanese food",
                CodeSwitch.LANG_EN,
            ),
        )
        assertFalse(
            CodeSwitch.suspectsMissedChinese(
                "can you bring the report to the meeting tomorrow",
                CodeSwitch.LANG_EN,
            ),
        )
        assertFalse(
            CodeSwitch.suspectsMissedChinese(
                "I think we should go there and see if they are open",
                CodeSwitch.LANG_EN,
            ),
        )
    }

    @Test
    fun `short English words that look like pinyin are not romanised`() {
        // Every one of these matches a pinyin syllable shape and is ordinary English.
        for (w in listOf("he", "she", "to", "do", "no", "so", "the", "we", "you", "her", "men")) {
            assertFalse(w, CodeSwitch.looksRomanised(w))
        }
    }

    @Test
    fun `a correct code-switched result is left alone`() {
        // Already has Han and was decoded as Chinese: nothing to repair.
        assertFalse(
            CodeSwitch.suspectsMissedChinese("這個 bug 我已經 fix 好了", CodeSwitch.LANG_ZH),
        )
    }

    // --- the arbitration must not make things worse --------------------------------------

    @Test
    fun `a retry with no Han characters loses`() {
        assertEquals(reported, CodeSwitch.choose(reported, "wo de ni hao ma"))
    }

    @Test
    fun `a blank retry loses`() {
        assertEquals(reported, CodeSwitch.choose(reported, ""))
    }

    @Test
    fun `a retry that swallows the English loses`() {
        // Forced zh threw away the English half instead of fixing the Chinese tail.
        assertEquals(reported, CodeSwitch.choose(reported, "牛肉麵嗎"))
    }

    @Test
    fun `a retry that keeps the English and fixes the Chinese wins`() {
        val auto = "I want to eat niu rou mian ma"
        val forced = "I want to eat 牛肉麵嗎"
        assertEquals(forced, CodeSwitch.choose(auto, forced))
    }

    // --- the detector itself --------------------------------------------------------------

    @Test
    fun `romanised mandarin syllables are detected`() {
        for (w in listOf("zhong", "xiang", "qing", "jiao", "cheng", "shuo", "niu", "rou", "mian")) {
            assertTrue(w, CodeSwitch.looksRomanised(w))
        }
    }

    /**
     * Ordinary English that the first draft of the detector flagged. Each of these cost a
     * needless second decode, and "serious"/"premium" made the -ious/-ium suffix rule untenable.
     */
    @Test
    fun `English that previously false-positived is clean`() {
        for (t in listOf(
            "the premium account has various serious issues",
            "we went to hong kong last year for a holiday",
            "the team found a bug in the account system around noon",
            "please send me the medium size in blue",
        )) {
            assertFalse(t, CodeSwitch.suspectsMissedChinese(t, CodeSwitch.LANG_EN))
        }
    }

    @Test
    fun `a single odd word is not enough to trigger a retry`() {
        // One unusual token (a name, say) must not cost a second decode.
        assertFalse(
            CodeSwitch.suspectsMissedChinese("I met Xiang at the office today", CodeSwitch.LANG_EN),
        )
    }

    @Test
    fun `blank text is never suspected`() {
        assertFalse(CodeSwitch.suspectsMissedChinese("", CodeSwitch.LANG_EN))
        assertFalse(CodeSwitch.suspectsMissedChinese("   ", CodeSwitch.LANG_EN))
    }
}
