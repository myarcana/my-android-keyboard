package com.offlinekeyboard.ime.candidates

import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import com.offlinekeyboard.ime.glide.Lexicon
import com.offlinekeyboard.ime.pinyin.Decoder
import com.offlinekeyboard.ime.pinyin.PinyinDict
import com.offlinekeyboard.ime.pinyin.Script
import com.offlinekeyboard.ime.pinyin.ScriptMode
import com.offlinekeyboard.ime.pinyin.Syllables
import com.offlinekeyboard.ime.pinyin.UserDict
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suggestion quality for input that is spelled correctly.
 *
 * `danta` (蛋挞, egg tart) is the case all of this was written for, and it failed in four
 * independent ways at once -- which is why it is worth keeping as a test rather than a constant:
 *
 *  1. [Syllables] applied every fuzzy rule it could think of, so `dan` also meant `dang`.
 *  2. [Decoder]'s fuzzy penalty was 2.3 nats against a frequency gap of 3.3, so the respelling
 *     当他 outranked every word the user had actually spelled.
 *  3. `BEAM` was 4, and the `dan ta` key holds five words, so 蛋塔 was pruned inside the Viterbi
 *     pass before any ranking could see it.
 *  4. [UnifiedCandidates] had no quality floor, so the bar padded itself to twelve with single
 *     characters explaining three of five letters.
 *
 * Each is asserted below against the real shipped dictionary.
 */
class FuzzySpellingTest {

    private val dict = File("src/main/assets/pinyin.bin").inputStream().use(PinyinDict::load)
    private val emoji = File("src/main/assets/emoji_en.tsv").inputStream().use(EmojiIndex::load)
    private val english = File("src/main/assets/$LEXICON_ASSET").inputStream().use(Lexicon::load)

    private val script = Script(dict.conversionChars, dict.conversionPhrases)

    private val decoder = Decoder(dict, UserDict(null), ScriptMode.SIMPLIFIED)

    private fun bar(input: String, n: Int = 20): List<String> =
        decoder.candidates(input, n).map { it.text }

    /** The English-mode strip, by the path KeyboardService.rankedFor takes. */
    private fun englishBar(q: String): List<UnifiedCandidates.Suggestion> {
        val engine = Decoder(dict, UserDict(null), ScriptMode.BOTH)
        val seen = HashSet<String>()
        val scored = ArrayList<UnifiedCandidates.Scored>()
        // PinyinSession.scoredFor: the Chinese subtype's list, same limit, same order.
        for (c in engine.candidates(q, 20)) {
            if (!seen.add(c.text)) continue
            scored += UnifiedCandidates.Scored(c.text, c.score, c.consumed, c.ids)
        }
        return UnifiedCandidates.rank(
            q,
            emoji.search(q),
            scored,
            english.logProbability(q) ?: UnifiedCandidates.NOT_ENGLISH,
        )
    }

    // --- what the user typed wins ----------------------------------------------------------

    @Test
    fun `danta offers the egg tart near the top`() {
        val bar = bar("danta")
        assertTrue("蛋挞 missing from `danta`: $bar", "蛋挞" in bar)
        // Second, behind 但他 only. That is not a concession: rime-ice weights 但他 at 9999 and
        // 蛋挞 at 9235, and "but he" genuinely is commoner in running text. What matters is that
        // nothing the user did *not* type sits in front of it.
        assertEquals("蛋挞", bar[1])
    }

    @Test
    fun `no respelling outranks the word actually spelled`() {
        val bar = bar("danta")
        val egg = bar.indexOf("蛋挞")
        val guess = bar.indexOf("当他")
        assertTrue("当他 (a `dang ta` reading) outranks 蛋挞: $bar", guess < 0 || egg < guess)
    }

    @Test
    fun `a dropped fuzzy rule stops inventing readings`() {
        // `n`/`l` was applied unconditionally and is one of the three Gboard ships disabled.
        // With it gone, `lanse` is 蓝色 and nothing reads it as `nanse`.
        assertEquals("蓝色", bar("lanse").first())
    }

    // --- the readings fuzzy matching exists for still work ----------------------------------

    @Test
    fun `the confusions that are kept still resolve`() {
        // Retroflex and nasal-final confusions -- the six rules Gboard enables -- must still
        // reach the intended word, or fuzzy pinyin has been removed rather than tightened.
        assertEquals("中国", bar("zongguo").first())
        assertEquals("生日", bar("sengri").first())
        assertEquals("英文", bar("yingwen").first())
        assertEquals("知道", bar("zhidao").first())
        assertEquals("上海", bar("sanghai").first())
        // `cifan` is also the exact spelling of 此番, which is commoner, so 吃饭 cannot lead --
        // but it must stay near the top rather than be pushed out.
        assertTrue("cifan: ${bar("cifan")}", bar("cifan").indexOf("吃饭") in 0..2)
    }

    // --- the bar is not padded --------------------------------------------------------------

    @Test
    fun `whole readings lead and partial readings follow them`() {
        val bar = englishBar("danta").filter { it.kind == UnifiedCandidates.Kind.CHINESE }
        // What iOS shows first for `danta` is all whole-input readings (蛋塔 蛋撻 蛋鴨 淡雅 ...),
        // so every candidate that explains all five letters comes before any that reads only
        // `dan`. The partial ones are still offered behind them, as on the Chinese bar, since
        // committing 但 and typing on is a legitimate way to enter the phrase.
        val firstPartial = bar.indexOfFirst { it.consumes < "danta".length }
        val lastWhole = bar.indexOfLast { it.consumes == "danta".length }
        assertTrue("a partial reading leads a whole one: ${bar.map { it.text }}",
            firstPartial < 0 || lastWhole < firstPartial)
    }

    @Test
    fun `both egg tarts reach the english bar`() {
        // BOTH mode reads the mainland and Taiwan corpora against each other, so the Simplified
        // 蛋挞 and the Taiwan 蛋塔 are competing hypotheses about the same letters and both
        // belong. 蛋塔 was absent entirely: it is the fifth word on the `dan ta` key and the
        // Viterbi beam only kept four.
        val texts = englishBar("danta").map { it.text }
        assertTrue("蛋挞 missing from the english bar: $texts", "蛋挞" in texts)
        assertTrue("蛋塔 missing from the english bar: $texts", "蛋塔" in texts)
    }

    // --- the Traditional bar ----------------------------------------------------------------

    /**
     * PinyinSession.decoded + addScriptVariants for a zh_TW subtype.
     *
     * Replicated rather than called: [com.offlinekeyboard.ime.pinyin.PinyinSession] takes a
     * Context and cannot be built in a unit test. Kept in step with it by hand.
     */
    private fun twBar(q: String, limit: Int = 12): List<String> {
        val native = Decoder(dict, UserDict(null), ScriptMode.TRADITIONAL).candidates(q, limit)
        val out = LinkedHashSet<String>()
        for (c in native) out.add(script.toTraditional(c.text))
        val best = native.firstOrNull() ?: return out.toList()

        val cn = Decoder(dict, UserDict(null), ScriptMode.SIMPLIFIED)
        val literal = Syllables.readings(q).firstOrNull { it.fuzzyCount == 0 }
        val literalTexts = literal?.let { cn.decode(it, limit).mapTo(HashSet()) { d -> d.text } }
            ?: emptySet<String>()
        var added = 0
        for (c in cn.candidates(q, limit)) {
            if (added >= 2) break
            if (c.text.length < 2) continue
            if (c.text !in literalTexts) continue
            if (c.score < best.score) continue
            val display = script.toTraditional(c.text)
            if (display == c.text) continue
            if (out.add(display)) added++
        }
        return out.toList()
    }

    @Test
    fun `a taiwan bar offers both traditional spellings of the egg tart`() {
        // McBopomofo has 蛋塔 and nothing else, so 蛋撻 is reachable only by converting the
        // mainland 蛋挞 -- and no OpenCC table links the two forms. Both references show the
        // pair: iOS gives 蛋塔 蛋撻 ... and Gboard 但他 蛋塔 蛋撻 ...
        val bar = twBar("danta")
        assertTrue("蛋塔 missing: $bar", "蛋塔" in bar)
        assertTrue("蛋撻 missing: $bar", "蛋撻" in bar)
        // Native Taiwan vocabulary still leads; a converted spelling is an alternative, not the
        // answer.
        assertEquals("蛋塔", bar.first())
    }

    @Test
    fun `converting for taiwan does not import respellings`() {
        // The mainland decoder applies fuzzy pinyin too. Converting its output wholesale put
        // 當他 and 當她 -- readings of `dang ta` -- into a Taiwan bar that never had them.
        val bar = twBar("danta")
        assertFalse("當他 leaked into the taiwan bar: $bar", "當他" in bar)
        assertFalse("當她 leaked into the taiwan bar: $bar", "當她" in bar)
    }

    @Test
    fun `taiwan vocabulary still wins over a converted mainland spelling`() {
        // The hazard the conversion backstop has always guarded against: 软件 converts to 軟件,
        // which is not the word a Taiwanese user wants. 軟體 must come first.
        val bar = twBar("ruanjian")
        val tw = bar.indexOf("軟體")
        val cn = bar.indexOf("軟件")
        assertTrue("軟體 missing from `ruanjian`: $bar", tw >= 0)
        assertTrue("軟件 outranks 軟體: $bar", cn < 0 || tw < cn)
    }

    @Test
    fun `a bar with no chinese reading is still emoji`() {
        // The filler floor must not touch the emoji half: `happy` is not pinyin at all and the
        // bar is emoji-only by design.
        val bar = englishBar("happy")
        assertTrue("happy lost its emoji", bar.any { it.kind == UnifiedCandidates.Kind.EMOJI })
    }
}
