package com.offlinekeyboard.ime.asr

import com.offlinekeyboard.ime.asr.SpokenPunctuation.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge is where the two recognisers meet, so these tests are written against the shapes
 * sherpa-onnx actually returns: sentencepiece tokens with a leading space on the token that
 * starts a word, one timestamp per token, and detections timed independently by a second model.
 */
class CommandMergeTest {

    /** Builds a transcript from whole words at one-second spacing, the way speech is paced. */
    private fun transcript(vararg words: String): CommandMerge.Transcript {
        val tokens = words.mapIndexed { i, w -> if (i == 0) w else " $w" }
        val times = words.indices.map { it.toFloat() }
        return CommandMerge.Transcript(words.joinToString(" "), tokens, times)
    }

    private fun merge(
        t: CommandMerge.Transcript,
        vararg d: CommandMerge.Detection,
        script: Script = Script.LATIN,
    ) = CommandMerge.merge(t, d.toList(), script)

    // --- the case this whole feature exists for -------------------------------------------

    @Test
    fun `a misheard command word is replaced by its mark`() {
        // SenseVoice heard "coma"; the spotter heard COMMA at the same moment.
        val result = merge(
            transcript("hello", "coma", "world"),
            CommandMerge.Detection("COMMA", 1.0f),
        )
        assertEquals("hello , world", result)
    }

    @Test
    fun `every near miss spelling is replaced`() {
        for (misheard in listOf("coma", "comm", "karma", "commas")) {
            val result = merge(
                transcript("yes", misheard, "please"),
                CommandMerge.Detection("COMMA", 1.0f),
            )
            assertEquals("replacing $misheard", "yes , please", result)
        }
    }

    @Test
    fun `the correctly heard word is replaced too`() {
        val result = merge(
            transcript("hello", "comma", "world"),
            CommandMerge.Detection("COMMA", 1.0f),
        )
        assertEquals("hello , world", result)
    }

    // --- placement ------------------------------------------------------------------------

    @Test
    fun `the closest word in time wins`() {
        // Two identical candidate spellings, at 0 s and 2 s. The detection at 2 s must take the
        // second and leave the first as the word the speaker actually said.
        val result = merge(
            transcript("coma", "and", "coma"),
            CommandMerge.Detection("COMMA", 2.0f),
        )
        assertEquals("coma and ,", result)
    }

    @Test
    fun `two commands in a row each consume their own word`() {
        val result = merge(
            transcript("one", "coma", "two", "period"),
            CommandMerge.Detection("COMMA", 1.0f),
            CommandMerge.Detection("PERIOD", 3.0f),
        )
        assertEquals("one , two .", result)
    }

    @Test
    fun `a detection with no matching word is still inserted`() {
        // The speaker asked for it, so a stray mark beats a silently dropped command.
        val result = merge(
            transcript("hello", "world"),
            CommandMerge.Detection("COMMA", 1.5f),
        )
        assertTrue("expected a comma somewhere in $result", result.contains(","))
    }

    @Test
    fun `a detection far from any spelling does not eat a neighbouring word`() {
        val result = merge(
            transcript("hello", "world"),
            CommandMerge.Detection("COMMA", 9.0f),
        )
        assertTrue("hello must survive", result.contains("hello"))
        assertTrue("world must survive", result.contains("world"))
    }

    // --- scripts --------------------------------------------------------------------------

    @Test
    fun `chinese commands produce full width marks`() {
        val result = merge(
            transcript("好的", "逗号", "谢谢"),
            CommandMerge.Detection("COMMA_ZH", 1.0f),
            script = Script.SIMPLIFIED,
        )
        assertTrue("expected a full-width comma in $result", result.contains("，"))
    }

    @Test
    fun `traditional script gets corner quotes`() {
        assertEquals("\u300C", SpokenPunctuation.markFor("OPEN_QUOTE", Script.TRADITIONAL))
        assertEquals("\u201C", SpokenPunctuation.markFor("OPEN_QUOTE", Script.SIMPLIFIED))
    }

    // --- tokenisation ---------------------------------------------------------------------

    @Test
    fun `word pieces are reassembled into words`() {
        // Sentencepiece splits "comma" across pieces; the merge matches on whole words.
        val t = CommandMerge.Transcript(
            text = "hello comma",
            tokens = listOf("hello", " com", "ma"),
            timestamps = listOf(0.0f, 1.0f, 1.2f),
        )
        val words = CommandMerge.words(t.tokens, t.timestamps)
        assertEquals(listOf("hello", "comma"), words.map { it.text })
        assertEquals(1.0f, words[1].start, 0.001f)
    }

    @Test
    fun `han characters are their own words`() {
        val words = CommandMerge.words(listOf("好", "的", "逗", "号"), listOf(0f, 1f, 2f, 3f))
        assertEquals(4, words.size)
    }

    @Test
    fun `no detections leaves the text alone`() {
        val t = transcript("nothing", "to", "do", "here")
        assertEquals(t.text, CommandMerge.merge(t, emptyList(), Script.LATIN))
    }

    // --- the contract with the generated asset ---------------------------------------------

    @Test
    fun `every keyword id has a mark`() {
        for (id in PunctuationCommands.ids) {
            assertTrue("no mark for $id", SpokenPunctuation.markFor(id, Script.LATIN).isNotEmpty())
        }
    }

    @Test
    fun `every keyword id has at least one spelling to remove`() {
        for (id in PunctuationCommands.ids) {
            assertTrue("no spellings for $id", PunctuationCommands.spellingsFor(id).isNotEmpty())
        }
    }

    @Test
    fun `an unknown id yields no mark rather than crashing`() {
        assertEquals("", SpokenPunctuation.markFor("NOT_A_COMMAND", Script.LATIN))
    }

    // --- the whole pipeline, through applyMerged --------------------------------------------
    //
    // CommandMerge alone does not prove the feature works: the ordering of the strip against the
    // merge lives in SpokenPunctuation.applyMerged, and getting it backwards would delete the
    // very marks the spotter placed. These go through the real entry point.

    private fun applied(
        t: CommandMerge.Transcript,
        vararg d: CommandMerge.Detection,
        script: Script = Script.LATIN,
    ) = SpokenPunctuation.applyMerged(t, d.toList(), script)

    @Test
    fun `the spotter's mark survives the strip that removes the model's`() {
        // The model punctuated on its own ("hello, coma world.") *and* the speaker said "comma".
        // The invented marks must go; the asked-for one must stay.
        val t = CommandMerge.Transcript(
            text = "hello, coma world.",
            tokens = listOf("hello,", " coma", " world."),
            timestamps = listOf(0.0f, 1.0f, 2.0f),
        )
        assertEquals("hello, world", applied(t, CommandMerge.Detection("COMMA", 1.0f)))
    }

    @Test
    fun `spacing is tidied around a merged mark`() {
        // Half-width marks take no space before and one after, the same rule spoken words get.
        val t = CommandMerge.Transcript(
            text = "one coma two",
            tokens = listOf("one", " coma", " two"),
            timestamps = listOf(0.0f, 1.0f, 2.0f),
        )
        assertEquals("one, two", applied(t, CommandMerge.Detection("COMMA", 1.0f)))
    }

    @Test
    fun `the pronoun is still capitalised on the merged path`() {
        val t = CommandMerge.Transcript(
            text = "yes coma i agree",
            tokens = listOf("yes", " coma", " i", " agree"),
            timestamps = listOf(0.0f, 1.0f, 2.0f, 3.0f),
        )
        assertEquals("yes, I agree", applied(t, CommandMerge.Detection("COMMA", 1.0f)))
    }

    @Test
    fun `a command the spotter missed is still caught by the words`() {
        // Belt and braces: the spotter fired for the first comma only, and the second was said
        // clearly enough that SenseVoice spelled it correctly. Both must appear.
        val t = CommandMerge.Transcript(
            text = "one coma two comma three",
            tokens = listOf("one", " coma", " two", " comma", " three"),
            timestamps = listOf(0.0f, 1.0f, 2.0f, 3.0f, 4.0f),
        )
        assertEquals("one, two, three", applied(t, CommandMerge.Detection("COMMA", 1.0f)))
    }

    @Test
    fun `no detections is exactly the text-only path`() {
        // The degradation guarantee: a device whose spotter failed to load must behave as it did
        // before the spotter existed.
        val raw = "So, I rescheduled comma the dentist."
        val t = CommandMerge.Transcript(raw, listOf(raw), listOf(0.0f))
        assertEquals(
            SpokenPunctuation.apply(raw, Script.LATIN),
            SpokenPunctuation.applyMerged(t, emptyList(), Script.LATIN),
        )
    }

    @Test
    fun `han characters are not separated by spaces`() {
        // Regression: the merge joined every piece with a space, which is correct for Latin
        // words and visibly broken Chinese -- "好 的 谢 谢" rather than "好的谢谢".
        val t = CommandMerge.Transcript(
            text = "好的逗号谢谢",
            tokens = listOf("好", "的", "逗", "号", "谢", "谢"),
            timestamps = listOf(0.0f, 0.5f, 1.0f, 1.2f, 2.0f, 2.2f),
        )
        val result = applied(t, CommandMerge.Detection("COMMA_ZH", 1.0f), script = Script.SIMPLIFIED)
        assertTrue("no spaces between Han, got: $result", !result.contains(" "))
    }

    @Test
    fun `a chinese command spanning two tokens is consumed whole`() {
        // Regression: 逗号 arrives as two one-character words, so matching a single word never
        // found it and the mark was inserted *inside* the word -- "逗，号".
        val t = CommandMerge.Transcript(
            text = "好的逗号谢谢",
            tokens = listOf("好", "的", "逗", "号", "谢", "谢"),
            timestamps = listOf(0.0f, 0.5f, 1.0f, 1.2f, 2.0f, 2.2f),
        )
        assertEquals(
            "好的，谢谢",
            applied(t, CommandMerge.Detection("COMMA_ZH", 1.0f), script = Script.SIMPLIFIED),
        )
    }

    @Test
    fun `a two word english command is consumed whole`() {
        val t = CommandMerge.Transcript(
            text = "done full stop",
            tokens = listOf("done", " full", " stop"),
            timestamps = listOf(0.0f, 1.0f, 1.3f),
        )
        assertEquals("done.", applied(t, CommandMerge.Detection("FULL_STOP", 1.0f)))
    }

    @Test
    fun `chinese runs the full pipeline with full width marks`() {
        val t = CommandMerge.Transcript(
            text = "好的，逗号谢谢",
            tokens = listOf("好", "的", "，", "逗", "号", "谢", "谢"),
            timestamps = listOf(0.0f, 0.5f, 0.9f, 1.0f, 1.2f, 2.0f, 2.2f),
        )
        val result = applied(t, CommandMerge.Detection("COMMA_ZH", 1.0f), script = Script.SIMPLIFIED)
        assertTrue("expected a full-width comma in $result", result.contains("，"))
        assertTrue("expected the words to survive in $result", result.contains("谢谢"))
    }
}
