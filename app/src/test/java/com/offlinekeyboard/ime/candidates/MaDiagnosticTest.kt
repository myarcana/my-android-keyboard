package com.offlinekeyboard.ime.candidates

import com.offlinekeyboard.ime.glide.LEXICON_ASSET
import com.offlinekeyboard.ime.glide.Lexicon
import com.offlinekeyboard.ime.pinyin.Decoder
import com.offlinekeyboard.ime.pinyin.PinyinDict
import com.offlinekeyboard.ime.pinyin.ScriptMode
import com.offlinekeyboard.ime.pinyin.UserDict
import java.io.File
import org.junit.Test

class MaDiagnosticTest {
    @Test
    fun `what the bar actually shows for ma`() {
        val index = File("src/main/assets/emoji_en.tsv").inputStream().use(EmojiIndex::load)
        val dict = File("src/main/assets/pinyin.bin").inputStream().use(PinyinDict::load)
        val english = File("src/main/assets/$LEXICON_ASSET").inputStream().use(Lexicon::load)
        val decoder = Decoder(dict, UserDict(null), ScriptMode.BOTH)

        for (q in listOf("ma", "ha", "ni", "wo", "de", "a", "m", "hao", "hen", "zhe", "you", "piz", "lov", "hap", "happy", "the", "love", "pizza", "niuroumian")) {
            val hits = index.search(q)
            val seen = HashSet<String>()
            val chinese = decoder.candidates(q, 20).filter { seen.add(it.text) }
                .map { UnifiedCandidates.Scored(it.text, it.score, it.consumed, it.ids) }
            val en = english.logProbability(q) ?: UnifiedCandidates.NOT_ENGLISH
            println("=== query '$q' (english ${"%.1f".format(en)}) ===")
            println("  emoji hits: ${hits.size} -> ${hits.take(5)}")
            println("  chinese   : ${chinese.size} -> ${chinese.take(5).map { "${it.text}:${"%.2f".format(it.score)}" }}")
            val ranked = UnifiedCandidates.suggest(q, index, chinese, english)
            println("  BAR: " + ranked.take(14).joinToString(" ") { "${it.text}(${it.kind.name.take(1)},${"%.1f".format(it.score)})" })
        }
    }
}
