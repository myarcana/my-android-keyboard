package com.offlinekeyboard.ime.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the seam between the generated keywords asset and the Kotlin table.
 *
 * The keyword spotter matches on ids that `tools/build_punctuation_keywords.py` writes into the
 * asset, and [PunctuationCommands] turns those ids into marks. Nothing at runtime checks that
 * the two agree: an id in the asset with no entry in the table fires and inserts nothing, and an
 * entry in the table with no line in the asset can never fire. Both failures are silent, which
 * is exactly the kind this project has been bitten by before -- so they are made loud here.
 */
class PunctuationKeywordsTest {

    private val asset = File("src/main/assets/asr/punctuation_keywords.txt")

    private fun idsInAsset(): Set<String> =
        asset.readLines()
            .filterNot { it.isBlank() || it.startsWith("#") }
            .mapNotNull { line -> line.substringAfterLast("@", "").takeIf { it.isNotEmpty() } }
            .toSet()

    @Test
    fun `the generated asset exists`() {
        assertTrue(
            "missing ${asset.path} -- run tools/build_punctuation_keywords.py",
            asset.isFile,
        )
    }

    @Test
    fun `asset ids and table ids are the same set`() {
        assertEquals(PunctuationCommands.ids, idsInAsset())
    }

    @Test
    fun `every keyword line has tokens before its id`() {
        for (line in asset.readLines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val tokens = line.substringBeforeLast("@").trim()
            assertTrue("no tokens on line: $line", tokens.isNotEmpty())
        }
    }

    @Test
    fun `comma is spelled with the phonemes the model expects`() {
        // The one entry this whole feature was built for, pinned so a dictionary change that
        // silently drops it is caught here rather than on the phone.
        val line = asset.readLines().first { it.endsWith("@COMMA") }
        assertEquals("K AA1 M AH0", line.substringBeforeLast("@").trim())
    }
}
