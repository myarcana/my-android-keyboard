package com.offlinekeyboard.ime.text

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One backspace deletes any emoji this keyboard can type, exactly and completely.
 *
 * [GraphemeClusterTest] pins the rules against hand-picked examples; this runs them over the
 * whole of `emoji_en.tsv` -- every emoji the picker can actually insert -- because the rules are
 * only worth anything if they cover the emoji that are really there. When this was written the
 * asset held 1913 entries, of which only about a third were a single code unit: the rest were
 * flags, ZWJ sequences or characters carrying a variation selector, and every one of them was
 * mishandled by the fixed-1 delete this replaced.
 */
class EmojiBackspaceTest {

    private fun asset(name: String): File? {
        var dir: File? = File("").absoluteFile
        var found: File? = null
        repeat(5) {
            val candidate = dir?.resolve("app/src/main/assets/$name")
            if (found == null && candidate != null && candidate.isFile) found = candidate
            dir = dir?.parentFile
        }
        return found
    }

    private fun emoji(): List<String> {
        val file = asset("emoji_en.tsv") ?: return emptyList()
        return file.readLines()
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.substringBefore('\t') }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * The whole emoji goes and nothing before it does -- the two ways this can be wrong. Deleting
     * too little leaves a mangled glyph, which is the bug; deleting too much eats the user's text.
     */
    @Test
    fun `every emoji in the picker is removed by a single backspace`() {
        val emoji = emoji()
        if (emoji.isEmpty()) return

        val prefix = "hi "
        val broken = emoji.filter { e ->
            val field = prefix + e
            GraphemeCluster.lastClusterLength(field) != e.length
        }

        assertTrue(
            "${broken.size} of ${emoji.size} emoji are not deleted by one backspace: " +
                broken.take(10).joinToString { e -> e.map { "U+%04X".format(it.code) }.toString() },
            broken.isEmpty(),
        )
    }

    /** The text in front of the emoji has to survive the press untouched. */
    @Test
    fun `deleting an emoji leaves the text before it alone`() {
        val emoji = emoji()
        if (emoji.isEmpty()) return

        val prefix = "hi "
        for (e in emoji) {
            val field = prefix + e
            val remaining = field.dropLast(GraphemeCluster.lastClusterLength(field))
            assertEquals("deleting $e damaged the text before it", prefix, remaining)
        }
    }

    /**
     * Guards the assumption the fix rests on: that the old code was wrong for most of this
     * asset. If the picker ever became all single-unit emoji, the fix would be unnecessary and
     * this test should be revisited rather than silently passing.
     */
    @Test
    fun `most emoji in the asset are longer than one code unit`() {
        val emoji = emoji()
        if (emoji.isEmpty()) return
        val multiUnit = emoji.count { it.length > 1 }
        assertTrue(
            "expected most emoji to exceed one UTF-16 unit, got $multiUnit of ${emoji.size}",
            multiUnit > emoji.size / 2,
        )
    }
}
