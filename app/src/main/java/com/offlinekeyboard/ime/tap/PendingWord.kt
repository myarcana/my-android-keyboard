package com.offlinekeyboard.ime.tap

import com.offlinekeyboard.ime.layout.LayoutGeometry

/**
 * The run of letter taps that has not been settled yet, and the text it currently reads as.
 *
 * It exists because [TapDecoder] needs a word to work on and a tap only becomes part of one in
 * retrospect. Each entry keeps where the finger landed -- the whole input to the spatial model --
 * alongside the case that was in force, so a re-reading swaps *which* letter a tap was without
 * disturbing whether it was capitalised.
 *
 * Holding state about the field at all is the thing this keyboard has otherwise refused to do, and
 * for a good reason that still applies: the cursor trackpad can move the caret anywhere at any
 * moment, and a buffer that outlives the caret it described is worse than no buffer. The rule that
 * makes it safe is that every path out of here is the same path -- the caller flushes, which
 * commits the current reading and empties this. Nothing needs to decide whether the buffer is
 * still valid, because nothing is ever asked to keep one that might not be.
 */
class PendingWord {

    private data class Entry(val tap: TapDecoder.Tap, val upper: Boolean)

    private val entries = mutableListOf<Entry>()

    /** The reading currently on screen, so an unchanged one is not written to the field twice. */
    private var shown: String = ""

    val isEmpty: Boolean get() = entries.isEmpty()

    /** Characters currently held as composing text. */
    val length: Int get() = entries.size

    val taps: List<TapDecoder.Tap> get() = entries.map { it.tap }

    /**
     * Adds a letter tap, resolved against the keys that were on screen when it was pressed.
     *
     * The geometry is consumed here and not kept. A pending word can outlive the key grid it was
     * typed on -- a rotation, a split-screen drag, the navigation bar arriving, a one-handed
     * squash -- and a buffer holding raw pixels would be silently re-scored against whatever grid
     * happened to be current at the next keystroke. See [TapDecoder.Tap].
     */
    fun add(
        x: Float,
        y: Float,
        literal: Char,
        upper: Boolean,
        geometry: LayoutGeometry,
        model: SpatialModel = SpatialModel(),
    ) {
        entries += Entry(TapDecoder.Tap.of(x, y, literal, geometry, model), upper)
    }

    /**
     * The text for a reading, or for the literal when [letters] is null.
     *
     * Case comes from the tap rather than from the reading, which is what keeps "Rhys" capitalised
     * when its second letter is re-read. A reading of the wrong length is ignored rather than
     * trusted: [TapDecoder] guarantees one letter per tap, and this is the assertion of it that
     * costs nothing.
     */
    fun textFor(letters: String?): String {
        val reading = letters?.takeIf { it.length == entries.size }
        return buildString(entries.size) {
            entries.forEachIndexed { i, entry ->
                val c = reading?.get(i) ?: entry.tap.literal
                append(if (entry.upper) c.uppercaseChar() else c)
            }
        }
    }

    /** How many characters are currently on screen for this word. */
    val shownLength: Int get() = shown.length

    /** True when [text] differs from what was last handed to [markShown]. */
    fun hasChanged(text: String): Boolean = text != shown

    fun markShown(text: String) {
        shown = text
    }

    fun clear() {
        entries.clear()
        shown = ""
    }
}
