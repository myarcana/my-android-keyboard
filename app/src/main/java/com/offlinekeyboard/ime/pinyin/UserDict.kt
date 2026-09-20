package com.offlinekeyboard.ime.pinyin

import java.io.File

/**
 * What this user picks, remembered.
 *
 * A shipped dictionary is a model of how people write in general, and the gap between that and
 * how *one* person writes is the difference a keyboard is judged on. Names, a workplace's
 * jargon, and which homophone someone means by `ta` are all learned from use and by nothing
 * else. Every mature IME does this; without it the keyboard makes the same wrong guess forever.
 *
 * Deliberately small and dumb: a map from syllable key to chosen words, with a count. It is read
 * once at startup and rewritten when it changes, which at a few hundred entries is cheaper than
 * any incremental format would be.
 *
 * **Stays on the device.** It is written to the app's own files directory and never leaves it;
 * this is a keyboard with no network permission, and the most privacy-sensitive data it has is
 * exactly this file.
 */
internal class UserDict(private val file: File?) {

    /** A learned word: the text, and how often it has been chosen. */
    class Learned(val text: String, val weight: Int)

    /** Syllable key ("ni hao") -> words chosen for it, most-used first. */
    private val entries = HashMap<String, MutableList<Learned>>()

    private var dirty = false

    /** Words this user has chosen for exactly these syllables. */
    fun wordsFor(ids: IntArray): List<Learned> = entries[keyOf(ids)].orEmpty()

    /**
     * Records that [text] was chosen for [ids].
     *
     * The count is the whole model: choosing a word repeatedly raises it, and one choice is
     * enough to put it in front of the dictionary's answer for that key (see
     * `Decoder.LEARNED_BONUS`). That is the behaviour people expect -- correct the keyboard once
     * and it should stop making that mistake.
     */
    fun learn(ids: IntArray, text: String) {
        if (ids.isEmpty() || text.isEmpty()) return
        // An abbreviation is not a stable key: `b j` means whatever was on screen at the time,
        // and learning against it would attach the word to every future abbreviation alike.
        if (ids.any(Syllables::isInitial)) return
        val key = keyOf(ids)
        val list = entries.getOrPut(key) { ArrayList(2) }
        val existing = list.indexOfFirst { it.text == text }
        if (existing >= 0) {
            list[existing] = Learned(text, list[existing].weight + 1)
        } else {
            if (list.size >= MAX_PER_KEY) list.removeAt(list.size - 1)
            list.add(Learned(text, 1))
        }
        list.sortByDescending { it.weight }
        dirty = true
    }

    private fun keyOf(ids: IntArray): String =
        ids.joinToString(" ") { Syllables.spelling(it) }

    /** Reads the file if it exists. A corrupt or partial file is discarded, not repaired. */
    fun load() {
        val source = file ?: return
        if (!source.exists()) return
        runCatching {
            entries.clear()
            source.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size >= 3) {
                    val weight = parts[2].toIntOrNull() ?: return@forEachLine
                    entries.getOrPut(parts[0]) { ArrayList(2) }.add(Learned(parts[1], weight))
                }
            }
            entries.values.forEach { it.sortByDescending { entry -> entry.weight } }
        }.onFailure { entries.clear() }
        dirty = false
    }

    /** Writes the file if anything changed. Cheap enough to call on every commit. */
    fun save() {
        val target = file ?: return
        if (!dirty) return
        runCatching {
            target.parentFile?.mkdirs()
            target.bufferedWriter().use { out ->
                for ((key, words) in entries) {
                    for (word in words) {
                        out.write(key)
                        out.write("\t")
                        out.write(word.text)
                        out.write("\t")
                        out.write(word.weight.toString())
                        out.newLine()
                    }
                }
            }
            dirty = false
        }
    }

    private companion object {
        /** Alternatives kept per key. Beyond a handful the tail is never chosen again. */
        const val MAX_PER_KEY = 8
    }
}
