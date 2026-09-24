package com.offlinekeyboard.ime.asr

import com.offlinekeyboard.ime.asr.SpokenPunctuation.Script
import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenPunctuationTest {

    private fun en(raw: String) = SpokenPunctuation.apply(raw, Script.LATIN)
    private fun cn(raw: String) = SpokenPunctuation.apply(raw, Script.SIMPLIFIED)
    private fun tw(raw: String) = SpokenPunctuation.apply(raw, Script.TRADITIONAL)

    // --- the requirement: spoken punctuation only ---------------------------------------

    @Test
    fun `the model's own punctuation is removed`() {
        assertEquals("Hello world", en("Hello, world."))
        assertEquals("今天下午三點開會你記得把報告帶上", cn("今天下午三點開會，你記得把報告帶上。"))
    }

    @Test
    fun `spoken punctuation is inserted`() {
        assertEquals("hello, world", en("hello comma world"))
        assertEquals("meet me there.", en("meet me there period"))
        assertEquals("are you coming?", en("are you coming question mark"))
    }

    @Test
    fun `a sentence the model punctuated and the speaker did not keeps neither`() {
        // The strip has to run first, or the speaker's own comma would be stripped with it.
        assertEquals("So I rescheduled, the dentist", en("So, I rescheduled comma the dentist."))
    }

    @Test
    fun `spoken Chinese punctuation is full width`() {
        assertEquals("請問這附近有沒有停車場？", cn("請問這附近有沒有停車場問號"))
        assertEquals("你把地址發給我，我打車過去。", cn("你把地址發給我逗號我打車過去句號"))
        assertEquals("蘋果、香蕉、橘子", cn("蘋果頓號香蕉頓號橘子"))
    }

    @Test
    fun `both scripts of the spoken word are understood`() {
        // The model answers in whichever script it likes; what was said is the same word.
        assertEquals("好，", cn("好逗號"))
        assertEquals("好，", cn("好逗号"))
    }

    @Test
    fun `new line is a line break, not the words`() {
        assertEquals("first\nsecond", en("first new line second"))
        assertEquals("第一行\n第二行", cn("第一行換行第二行"))
    }

    // --- the cases that are easy to get wrong -------------------------------------------

    @Test
    fun `an apostrophe inside a word survives the strip`() {
        assertEquals("I'll be about fifteen minutes late", en("I'll be about fifteen minutes late."))
        assertEquals("don't", en("don't"))
    }

    @Test
    fun `a hyphen inside a word survives the strip`() {
        assertEquals("a t-shirt", en("a t-shirt"))
    }

    @Test
    fun `a punctuation word inside another word is left alone`() {
        assertEquals("check the dashboard", en("check the dashboard"))
        assertEquals("a periodic check", en("a periodic check"))
        assertEquals("the colonel arrived", en("the colonel arrived"))
    }

    @Test
    fun `the longest spoken phrase wins`() {
        assertEquals("really?", en("really question mark"))
        assertEquals("stop!", en("stop exclamation mark"))
        assertEquals("done.", en("done full stop"))
    }

    @Test
    fun `half width marks take a space after but not before`() {
        assertEquals("one, two, three", en("one comma two comma three"))
    }

    @Test
    fun `full width marks take no space on either side`() {
        assertEquals("好的，謝謝", cn("好的 逗號 謝謝"))
    }

    @Test
    fun `traditional gets corner quotes and simplified gets curly ones`() {
        assertEquals("他說「", tw("他說開引號"))
        assertEquals("他說\u201C", cn("他說開引號"))
        // The bare word is a substring of the directional ones and must not shadow them.
        assertEquals("他說「」", tw("他說引號"))
    }

    @Test
    fun `case does not matter`() {
        assertEquals("yes, please", en("yes Comma please"))
    }

    // --- the pronoun "I" ------------------------------------------------------------------

    @Test
    fun `a standalone i is capitalised`() {
        assertEquals("I think so", en("i think so"))
        assertEquals("what I said", en("what i said"))
        assertEquals("me and I", en("me and i"))
    }

    @Test
    fun `a contracted i is capitalised`() {
        assertEquals("I'll be late", en("i'll be late"))
        assertEquals("I'm here", en("i'm here"))
        assertEquals("I've done it", en("i've done it"))
        assertEquals("I'd rather not", en("i'd rather not"))
    }

    @Test
    fun `an i inside a word is left alone`() {
        // The whole reason the rule is anchored to word boundaries.
        assertEquals("naive is a word", en("naive is a word"))
        assertEquals("it is convenient", en("it is convenient"))
        assertEquals("brilliant", en("brilliant"))
    }

    @Test
    fun `an already capital I is unchanged`() {
        assertEquals("I know", en("I know"))
    }

    @Test
    fun `the pronoun is capitalised next to spoken punctuation`() {
        // The rule runs after the mark is inserted, so the "i" is standalone by then.
        assertEquals("yes, I agree", en("yes comma i agree"))
        assertEquals("wait. I forgot", en("wait period i forgot"))
    }

    @Test
    fun `a Latin word beside Han is separated by a space`() {
        // The forced-zh decode writes "the best豆花"; the space is ours to put back.
        assertEquals("the best 豆花 in the world", cn("the best豆花 in the world"))
        assertEquals("这个 bug 我已经 fix 好了", cn("这个 bug我已经 fix 好了"))
        assertEquals("what's your food 牛肉面吗", cn("what's your food牛肉面吗"))
        // Han against Han, and Han against a full-width mark, still take none.
        assertEquals("好的，谢谢", cn("好的逗号谢谢"))
        assertEquals("我在 costco，你要不要", cn("我在 costco逗号你要不要"))
    }

    @Test
    fun `a lone i is capitalised beside Han text`() {
        // Auto language detection code-switches mid-segment; the Latin word is still a word.
        assertEquals("我說 I know", cn("我說 i know"))
    }

    @Test
    fun `a non-contraction after i is left alone`() {
        // Only the four real contractions are pronoun forms.
        assertEquals("I", en("i"))
    }

    @Test
    fun `nothing said means nothing added`() {
        assertEquals("", en(""))
        assertEquals("just words", en("just words"))
    }
}
