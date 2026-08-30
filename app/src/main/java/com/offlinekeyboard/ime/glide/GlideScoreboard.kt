package com.offlinekeyboard.ime.glide

import com.offlinekeyboard.ime.gesture.GestureIntent
import com.offlinekeyboard.ime.gesture.GestureRecord
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.LayoutGeometry

/**
 * Scores glide decoders against recorded gestures.
 *
 * This is how the choice between the two engines gets settled, and it is the same discipline the
 * dictation model was chosen by: not "which sounds better" but which one reads *this* thumb.
 * Every recorded glide carries the path, the geometry it was made on, and the word the passage
 * asked for, so any decoder that exists now or later can be run over the whole history at once.
 *
 * It has to run on the phone rather than in a unit test, and that is not a compromise -- one of
 * the two engines is a native library that only exists on Android. Scoring them anywhere else
 * would mean scoring one of them by proxy.
 */
object GlideScoreboard {

    data class Row(
        val engine: String,
        /** Glides this engine could be run over: the ones whose layout this build still has. */
        val scored: Int,
        /** Where the word the passage asked for came first. */
        val top1: Int,
        /** Where it appeared at all. */
        val offered: Int,
        /** Glides that had a mid-stroke finger lift, and how many of those came first. */
        val rejoined: Int,
        val rejoinedTop1: Int,
    ) {
        fun percent(part: Int): Int = if (scored == 0) 0 else part * 100 / scored
    }

    /** One engine's answer for one gesture, for the list of what it still gets wrong. */
    data class Miss(val expected: String, val got: String, val engine: String)

    data class Report(val rows: List<Row>, val misses: List<Miss>)

    /**
     * Letters only, and case-insensitively.
     *
     * A glide has no way to express an apostrophe or a capital -- both are added afterwards by
     * whatever commits the word -- so counting them here would score the keyboard's spelling
     * rules rather than the decoder's reading of a path.
     */
    private fun same(a: String, b: String): Boolean =
        a.lowercase().filter(Char::isLetter) == b.lowercase().filter(Char::isLetter)

    fun score(records: List<GestureRecord>, engines: List<GlideEngine>): Report {
        val glides = records.filter { it.intent == GestureIntent.WORD && it.path.size >= 2 }
        val rows = mutableListOf<Row>()
        val misses = mutableListOf<Miss>()

        engines.forEach { engine ->
            var scored = 0
            var top1 = 0
            var offered = 0
            var rejoined = 0
            var rejoinedTop1 = 0
            glides.forEach { record ->
                val layout = IosLayouts.byId(record.trace.layoutId) ?: return@forEach
                val geometry = LayoutGeometry(layout, record.trace.widthPx)
                val words = engine.decode(record.path, geometry)
                scored++
                val hit = words.firstOrNull()?.let { same(it, record.expected) } == true
                if (hit) top1++ else misses += Miss(record.expected, words.firstOrNull() ?: "-", engine.name)
                if (words.any { same(it, record.expected) }) offered++
                if (record.gaps.isNotEmpty()) {
                    rejoined++
                    if (hit) rejoinedTop1++
                }
            }
            rows += Row(engine.name, scored, top1, offered, rejoined, rejoinedTop1)
        }
        return Report(rows, misses)
    }
}
