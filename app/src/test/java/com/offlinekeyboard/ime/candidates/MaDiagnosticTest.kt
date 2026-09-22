package com.offlinekeyboard.ime.candidates

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
        val decoder = Decoder(dict, UserDict(null), ScriptMode.SIMPLIFIED)

        for (q in listOf("ma", "hao", "ni", "wo", "niuroumian")) {
            val hits = index.search(q)
            val chinese = decoder.candidates(q, 60)
                .map { UnifiedCandidates.Scored(it.text, it.score, q.length) }
            println("=== query '$q' ===")
            println("  emoji hits: ${hits.size} -> ${hits.take(5)}")
            println("  chinese   : ${chinese.size} -> ${chinese.take(5).map { "${it.text}:${"%.2f".format(it.score)}" }}")
            val ranked = UnifiedCandidates.rank(q, hits, chinese)
            println("  BAR: " + ranked.joinToString(" ") { "${it.text}(${it.kind.name.take(1)},${"%.1f".format(it.score)})" })
        }
    }
}
