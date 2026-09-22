package com.offlinekeyboard.ime.layout

import com.offlinekeyboard.ime.tap.SpatialModel

/**
 * Layout model. Deliberately free of Android imports so geometry is unit-testable on the JVM.
 */

enum class KeyType { CHARACTER, SHIFT, BACKSPACE, MODE_SWITCH, GLOBE, MIC, SPACE, RETURN }

data class Key(
    val id: String,
    /** Committed on a plain tap. */
    val primary: String,
    /** Committed on an iPadOS-style downward flick. Null means the key has no flick action. */
    val secondary: String? = null,
    /** Shown in the long-press popup, iOS-style. */
    val accents: List<String> = emptyList(),
    /**
     * Editing commands offered by the long press.
     *
     * Separate from [accents] rather than merged into it because the two are different in kind --
     * see [PopupEntry] -- and because the layout is the wrong place to fix their order. Where
     * they land is [popup]'s single decision rather than something re-spelled at every key.
     */
    val actions: List<EditAction> = emptyList(),
    /**
     * Languages offered by a long press, for the globe key.
     *
     * Not declared in [IosLayouts] with everything else, because unlike accents and actions these
     * are not a property of the layout at all: they are whatever keyboards and subtypes are
     * installed and enabled on the device right now, which can change while the keyboard is
     * running. [com.offlinekeyboard.ime.LanguageMenu] reads them from the system and the service
     * injects them into this key as the layout is built.
     */
    val languages: List<PopupEntry.Language> = emptyList(),
    /** Width as a multiple of one standard letter key. */
    val widthUnits: Float = 1f,
    val type: KeyType = KeyType.CHARACTER,
) {
    /**
     * What a long press on this key offers, in the order it is laid out.
     *
     * The single action a key carries goes in the *middle*, not at the front, and the accents
     * part around it. The popup is positioned to put the middle entry under the thumb -- see
     * [PopupGrid] -- so this is what makes holding `c` and letting go copy, with nothing to aim
     * at and no slide.
     *
     * Putting it first would work equally well for that one gesture and is worse for every other
     * one: the popup would then hang off to one side of the key it belongs to, and the accents
     * would all be reachable in one direction only. Centring the primary keeps the popup over its
     * own key, which is where a popup should be, and leaves the accents split either side of the
     * thumb -- a shorter reach to the far ones than a single row starting under the finger.
     *
     * A key with no action puts its middle accent under the thumb, which is exactly what
     * centring the popup on the key has always done, so accent popups are unchanged.
     */
    val popup: List<PopupEntry> get() = popupLayout.entries

    /**
     * The entry the popup opens on, before the grid knows where the key is.
     *
     * Its index rather than its value, because that is what positions the popup and what the
     * opening highlight is compared against. [PopupGrid.of] may move it into a different column
     * to keep it under the thumb near the ends of the board, which is why both travel together
     * as a [PopupLayout] from there on.
     */
    val popupPrimary: Int get() = popupLayout.primary

    /**
     * What an upward flick from this key does, or null when it does nothing.
     *
     * The same entry a long press opens on and releasing commits -- deliberately *derived* from
     * [popupLayout] rather than declared per key. The two gestures are one promise ("this key
     * also does that") offered at two speeds: hold if you want to look first, flick if you
     * already know. Declaring it twice is how they would come to disagree, and the disagreement
     * would be silent, because nothing downstream compares them.
     *
     * The language menu is excluded. Its primary is the *next* language rather than a fixed
     * destination -- see [languagePopup] -- so a flick would mean something different every time
     * it was made, which is the one thing a gesture meant to be learned by the hand must not do.
     */
    val flickUp: PopupEntry? get() {
        if (languages.isNotEmpty()) return null
        val layout = popupLayout
        return layout.entries.getOrNull(layout.primary)?.takeIf { it != PopupEntry.Blank }
    }

    /**
     * The popup's entries and which of them is the primary, decided together.
     *
     * One computation returning both, because they are two halves of one arrangement and
     * deriving them separately is how they came apart: the order was built to put the primary in
     * the middle, then the primary was *re-derived* from the finished list by a rule that
     * disagreed whenever the bottom row was short, and the popup opened on a neighbour.
     *
     * This is the arrangement in *reading* order, with no grid applied. Wrapping it into rows
     * needs the key's position on screen -- see [PopupGrid.of] -- which a Key does not know.
     */
    val popupLayout: PopupLayout get() {
        // Languages take the key whole rather than sharing it. A key that offered both would be
        // asking the thumb to distinguish "type é" from "switch to English" in one column of
        // cells, and the globe key has no accents to share it with in any case.
        if (languages.isNotEmpty()) return languagePopup()

        val accentEntries = accents.map { PopupEntry.Accent(it) }
        val actionEntries = actions.map { PopupEntry.Action(it) }
        val flat = when {
            actionEntries.isEmpty() -> accentEntries
            // Several actions -- the edit menu on Enter -- stay in their stated order: it is a
            // menu to be read, not one command with alternatives arranged around it.
            actionEntries.size > 1 -> actionEntries + accentEntries
            else -> {
                val before = accentEntries.size / 2
                accentEntries.take(before) + actionEntries + accentEntries.drop(before)
            }
        }
        if (flat.isEmpty()) return PopupLayout(flat, 0)
        val primary = if (actionEntries.size == 1) {
            flat.indexOfFirst { it is PopupEntry.Action }
        } else {
            (flat.size - 1) / 2
        }
        return PopupLayout(flat, primary)
    }

    /**
     * The globe key's menu: one language per row, the thumb's end of the list nearest the thumb.
     *
     * The order arrives already decided -- other keyboards first, our own subtypes last -- and is
     * *not* re-sorted here. It is reversed by the column being built upward instead: entry 0 is
     * the top row and the last entry is the bottom one, nearest the finger. So the caller states
     * the list in reading order, top to bottom, and gets exactly that on screen.
     *
     * The primary is the entry below the current language rather than the current language
     * itself, so releasing without sliding *changes* something. Releasing on the language you are
     * already using is the one outcome a person holding the globe key cannot have wanted, and it
     * is what a menu centred on the current entry would give them for free. Where the current
     * language is last -- our own subtypes sit at the bottom -- it wraps back up the list rather
     * than running off the end.
     */
    private fun languagePopup(): PopupLayout {
        val current = languages.indexOfFirst { it.current }
        val primary = when {
            languages.size <= 1 -> 0
            current < 0 -> languages.lastIndex
            // Down the list one, wrapping. "Down" is toward the thumb, which is the direction a
            // finger already resting on the globe key can move furthest.
            else -> (current + 1) % languages.size
        }
        return PopupLayout(languages, primary, PopupShape.COLUMN)
    }
}

/**
 * How a popup's cells are arranged.
 *
 * Two shapes because the popups hold two different things. Accents are single glyphs read at a
 * glance and crossed with one sideways slide, which is a row. Languages are *words* -- "繁體拼音",
 * "English" -- which cannot be told apart at a glance in a one-key-wide cell, so they need a cell
 * wide enough to read and a column to stack in. A finger on the globe key also has far more room
 * above it than beside it, the globe being pinned near the left edge of the bottom row.
 */
enum class PopupShape {
    /** Wraps into rows as wide as the room beside the key allows, each cell one key wide. Accents. */
    GRID,

    /** A single column of wide cells, selected by sliding up and down. The language menu. */
    COLUMN,
}

/**
 * How much room a popup row has above a given key, in whole cells.
 *
 * Measured once from the key's position and then used for both halves of the layout -- how many
 * entries share the primary's row, and where the primary sits in it. See [PopupGrid.rowRoom].
 */
data class RowRoom(
    /** Cells that fit in one row, the primary's own included. */
    val perRow: Int,
    /** How many of them sit left of the primary, which is zero on the leftmost keys. */
    val leftOfPrimary: Int,
)

/** A popup's entries in the order they are laid out, and which one sits under the thumb. */
data class PopupLayout(
    val entries: List<PopupEntry>,
    val primary: Int,
    val shape: PopupShape = PopupShape.GRID,
    /**
     * How many cells wide the arrangement is, where it has already been decided.
     *
     * Null until the entries have been fitted to a key's surroundings -- a bare reading order has
     * no width. [PopupGrid.arrangeForThumb] fills it in, and [PopupGrid.of] uses it rather than
     * re-deriving the width from the entry count, which is how the two once disagreed.
     */
    val columns: Int? = null,
)

data class Row(val keys: List<Key>)

data class Layout(val id: String, val rows: List<Row>)

/**
 * Keyboard proportions, in dp, measured off Gboard on the target device (1080px / 360dp wide)
 * by scanning screenshot pixel runs for key edges.
 *
 * The *arrangement* of keys is iOS's (see [IosLayouts]); the sizing and spacing are Gboard's,
 * which is what was asked for. Measured values, all divided by the device's 3x density:
 *   key 92px wide, 120px tall; 14.9px gaps; 151px row pitch; 150px suggestion strip.
 *
 * Everything downstream derives from these as ratios of the keyboard width, so the proportions
 * hold at any screen size rather than only matching this one phone. This is the only place
 * sizes are declared -- adjust here when recalibrating.
 */
object Metrics {
    const val REFERENCE_WIDTH = 360f
    const val SIDE_MARGIN = 4.33f
    const val KEY_GAP = 4.96f
    const val KEY_WIDTH = 30.67f
    const val KEY_HEIGHT = 40f
    const val ROW_GAP = 10.33f
    /**
     * Suggestion bar: emoji in English, candidates in Chinese. Never English word suggestions.
     *
     * Sized to the emoji rather than to Gboard's 50dp strip. Gboard's is tall because it holds
     * *text* suggestions, which need room for descenders and a comfortable tap target on a word
     * several characters wide. Ours holds one square glyph per cell, so the extra height was
     * empty space above and below the emoji -- most of it below, where the drawing stopped at
     * 70% of the strip and the remainder was the buffer protecting the top letter row.
     *
     * Shortening it costs nothing in protection: a high press on `q`-`p` is caught by
     * [LayoutGeometry.isLetterReach], which measures the touch against where thumbs actually
     * land rather than against a slice of the bar, and so does not shrink with the strip.
     */
    const val STRIP_HEIGHT = 34f

    /**
     * How much of the strip an *external* overlay may cover, measured from its top.
     *
     * No longer a touch boundary. This used to be one: the bottom slice of the strip refused
     * taps outright, as a buffer protecting `q`-`p` from presses that came in high. The strip
     * sits directly above the top letter row, and tapping an emoji *replaces the word being
     * typed*, so a miss of a few pixels destroyed a whole word rather than costing a character.
     * A recorded passage caught one: a press 26px above `e`, horizontally dead centre of `e`'s
     * column, turned "book" into a book emoji.
     *
     * That is now decided by evidence rather than by a fixed band -- see
     * [LayoutGeometry.isLetterReach], which asks how many standard deviations above a thumb's
     * measured landing point the touch sits. The whole strip takes taps again.
     *
     * What survives here is the one case that cannot be arbitrated per-touch: the autofill
     * overlay is a stack of real Views from another process, so it claims its area geometrically
     * and in advance, before any finger exists to measure. It is given the part of the strip
     * that is unambiguous -- see [com.offlinekeyboard.ime.autofill.InlineSuggestionStrip].
     */
    const val STRIP_OVERLAY_FRACTION = 0.68f

    /**
     * Space below the bottom row, on top of whatever [view.KeyboardView] reserves for the
     * navigation bar. Together those two are the whole distance from the space bar to the
     * bottom of the screen.
     *
     * Measured against Gboard rather than chosen: on the target device under *gesture*
     * navigation, Gboard's bottom row ends 162px (54dp) above the screen bottom, and ours ended
     * at 71px (23.67dp) -- the keyboard sat 91px, a little over 30dp, too low to rest a thumb
     * under comfortably.
     *
     * The gap is this constant and not the navigation reserve because the reserve is already
     * doing the only job it can do honestly. It is the display's navigation-bar height and the
     * system reports 16dp under gesture navigation -- enough to clear the gesture handle, which
     * is all a nav reserve is *for*. The remaining distance is Gboard declining to put keys
     * where a thumb rests, which is a layout choice, so it belongs with the layout constants.
     *
     * Note this is not the 7.7dp that the horizontal metrics were scanned from. That number came
     * off a three-button-navigation screenshot, where a 44dp reserve underneath it made the total
     * come out right by accident; the same constant under gesture navigation left the keyboard
     * 30dp low. 38dp + the 16dp gesture reserve = Gboard's 54dp.
     */
    const val BOTTOM_PADDING = 38f
    const val CORNER_RADIUS = 8f
    const val ROW_COUNT = 4

    /**
     * The strip's height for a given keyboard width.
     *
     * [LayoutGeometry] has this as a field already, but the autofill overlay needs the answer
     * before there is a geometry to ask -- the system requests an InlineSuggestionsRequest
     * before the input view has been measured, sometimes before it has been created. Both go
     * through here so the overlay cannot drift from the strip it is covering.
     */
    fun stripHeightPx(widthPx: Float): Float = STRIP_HEIGHT / REFERENCE_WIDTH * widthPx

    /** How much of the strip the autofill overlay may cover. */
    fun stripOverlayHeightPx(widthPx: Float): Float =
        stripHeightPx(widthPx) * STRIP_OVERLAY_FRACTION

    /** Side margin for a given keyboard width, so the overlay lines up with the emoji strip. */
    fun sideMarginPx(widthPx: Float): Float = SIDE_MARGIN / REFERENCE_WIDTH * widthPx

    /** Key aspect ratio; preserved at every width so keys never look squashed. */
    const val KEY_ASPECT = KEY_HEIGHT / KEY_WIDTH

    /** Total keyboard height as a multiple of the width of one key unit. */
    const val HEIGHT_IN_KEY_WIDTHS =
        (
            STRIP_HEIGHT + ROW_COUNT * KEY_HEIGHT +
                (ROW_COUNT - 1) * ROW_GAP + BOTTOM_PADDING
            ) / KEY_WIDTH
}

/**
 * How far the board is squashed horizontally, and which edge it is squashed against.
 *
 * One-handed reach, driven by a flick along the space bar: right shrinks the keys toward the
 * right edge, left toward the left, and the middle state is the ordinary full-width board.
 * The *keys* narrow and the rows keep their height -- this is a horizontal squash, not a scale,
 * so the bottom row stays where the thumb expects it and only the horizontal journey shortens.
 *
 * The scale is not a matter of taste. [SQUASHED_SCALE] is what makes `a` land where `f` normally
 * sits while `p` stays put, which is the reach the gesture was asked for; see [Squash.scale].
 */
enum class Squash {
    LEFT,
    NONE,
    RIGHT,
    ;

    /** How much of its full width the board keeps in this state. */
    val scale: Float get() = if (this == NONE) 1f else SQUASHED_SCALE

    /**
     * The next state when the space bar is flicked this way, stopping at the ends.
     *
     * Deliberately not a wrapping cycle. The three states are a line with the full-width board in
     * the middle, so a flick always moves the keys in the direction of the flick: right from LEFT
     * restores, right again squashes right. A cycle would make the same gesture jump the board
     * across the screen once every three flicks, which is the one thing a reach gesture must not
     * do -- the hand that flicked right would find the keys under its other thumb.
     */
    fun flicked(toward: Squash): Squash = when {
        toward == RIGHT && this == LEFT -> NONE
        toward == RIGHT -> RIGHT
        toward == LEFT && this == RIGHT -> NONE
        else -> LEFT
    }

    companion object {
        /**
         * The squashed board's width, as a fraction of the full one.
         *
         * Derived from the reach that was asked for rather than chosen: squashing about the right
         * edge must leave `p` where it is and bring `a` to where `f` normally sits. With the
         * anchor at p's centre that is one equation in one unknown,
         *
         *     f_centre - p_centre  ==  scale * (a_centre - p_centre)
         *
         * and at the reference width -- p at 340.34dp, a at 37.48dp, f at 144.37dp -- it gives
         * 0.647. [LayoutGeometryTest] recomputes it from the geometry rather than trusting this
         * comment, so a recalibration of [Metrics] that moved the letters would fail there.
         *
         * It is a large squash: a 30.67dp key becomes 19.8dp. That is the cost of the reach being
         * real, and it is why this is a mode the user asks for a finger at a time rather than
         * anything the keyboard does on its own.
         */
        const val SQUASHED_SCALE = 0.647f
    }
}

/** A key placed in pixel space. */
data class KeyRect(
    val key: Key,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/**
 * Resolves a [Layout] into pixel rectangles for a given keyboard width, preserving iOS
 * proportions. Rows narrower than the full width are centred, which is how iOS insets the
 * home row.
 */
class LayoutGeometry(
    val layout: Layout,
    val widthPx: Float,
    /**
     * Where thumbs land, for deciding whether a touch in the strip was really aimed at the
     * letters below it. See [isLetterReach].
     *
     * Injectable so a test can state the scatter it is reasoning about instead of importing the
     * shipped constants by reference, which would make every such test silently re-tune itself
     * the day the model is refitted.
     */
    private val pressModel: SpatialModel = SpatialModel(),
    /**
     * Whether the board is squashed to one side for one-handed reach. See [Squash].
     *
     * A constructor parameter rather than a mutable field, and the squash is baked into every
     * derived measurement below, so there is no such thing as a geometry that is squashed for
     * drawing and unsquashed for hit-testing. The view rebuilds the geometry when the state
     * changes, exactly as it already does when the width or the layout does.
     */
    val squash: Squash = Squash.NONE,
) {

    private val scale = widthPx / Metrics.REFERENCE_WIDTH

    /**
     * The horizontal squeeze applied to everything that has a width.
     *
     * Vertical measurements deliberately do not take it: the rows keep their height and their
     * pitch, so the space bar stays exactly where the thumb left it and only the sideways
     * journey shortens. That is what makes this a reach gesture rather than a shrunken keyboard.
     */
    private val squashScale = squash.scale

    /**
     * The side margin of the full-width board, which the squash does not touch.
     *
     * The suggestion strip uses this rather than [margin]. The strip is not a reach target the
     * way the keys are -- it holds emoji and Chinese candidates, which are tapped deliberately
     * and read at a glance -- and squashing it would waste the width it needs for candidates
     * while making the board look like it had lost a row. It stays where it is, full width,
     * above a key area that has moved.
     */
    val stripMargin = Metrics.SIDE_MARGIN * scale

    val margin = stripMargin * squashScale
    val gap = Metrics.KEY_GAP * scale * squashScale

    /**
     * The width of one key unit, already squashed.
     *
     * Everything that reasons in key widths -- the glide's distance threshold, the tap model's
     * normalisation, the popup grid's cells -- reads this rather than the metric, so they all
     * follow the keys in automatically. A squashed board whose glide threshold was still a
     * full-width key would need a third again as much travel to start a word.
     */
    val keyUnit = Metrics.KEY_WIDTH * scale * squashScale

    /**
     * What one key unit would be if the board were not squashed.
     *
     * For the one caller that must *not* follow the keys in: the space-bar squash gesture
     * itself, whose threshold has to mean the same physical distance in every state or the
     * board becomes progressively easier to flick the smaller it gets.
     */
    val unsquashedKeyUnit = Metrics.KEY_WIDTH * scale
    val keyHeight = Metrics.KEY_HEIGHT * scale
    val rowGap = Metrics.ROW_GAP * scale
    val cornerRadius = Metrics.CORNER_RADIUS * scale
    /** Reserved above the keys for the suggestion bar. */
    val stripHeight = Metrics.stripHeightPx(widthPx)
    /** How much of the strip the autofill overlay may cover. Not a touch boundary. */
    val stripOverlayBottom = stripHeight * Metrics.STRIP_OVERLAY_FRACTION

    /**
     * The keyboard's height, which the squash does not change.
     *
     * Derived from the *unsquashed* key unit on purpose. Height is set by the rows, and the rows
     * keep theirs; taking it from [keyUnit] would make the whole keyboard a third shorter the
     * moment it was squashed, moving every key vertically as well and resizing the window under
     * the hand that asked only for a shorter reach.
     */
    val heightPx = Metrics.HEIGHT_IN_KEY_WIDTHS * Metrics.KEY_WIDTH * scale

    /**
     * The left edge of the squashed board: the whole thing, keys and margins alike, pushed to
     * one side. LEFT leaves it at zero, RIGHT pushes it by the width the squash freed.
     */
    private val squashOffset: Float = when (squash) {
        Squash.NONE, Squash.LEFT -> 0f
        Squash.RIGHT -> widthPx * (1f - squashScale)
    }

    /** The band of the view the keys actually occupy, for the renderer and for hit-testing. */
    val contentLeft: Float = squashOffset
    val contentRight: Float = squashOffset + widthPx * squashScale
    private val contentWidthPx: Float = contentRight - contentLeft

    /**
     * The band a popup may occupy: the key area's own edges, inside its margins.
     *
     * Not the view's edges. A popup is clamped so it cannot hang off the keyboard, and on a
     * squashed board "off the keyboard" starts at [contentLeft] rather than at zero -- the
     * freed band belongs to the hand holding the phone, and an accent popup drifting into it
     * would be both unreachable and the one thing on screen covering the space the gesture just
     * cleared.
     */
    val popupLeftBound: Float = contentLeft + margin
    val popupRightBound: Float = contentRight - margin

    val keyRects: List<KeyRect> = buildList {
        val usable = contentWidthPx - 2 * margin
        layout.rows.forEachIndexed { rowIndex, row ->
            val contentWidth =
                row.keys.sumOf { it.widthUnits.toDouble() }.toFloat() * keyUnit +
                    (row.keys.size - 1) * gap
            var x = contentLeft + margin + (usable - contentWidth) / 2f
            val top = stripHeight + rowIndex * (keyHeight + rowGap)
            row.keys.forEach { key ->
                val w = key.widthUnits * keyUnit
                add(KeyRect(key, x, top, x + w, top + keyHeight))
                x += w + gap
            }
        }
    }

    /** The bottom of the last row. */
    val keyAreaBottom: Float = keyRects.maxOfOrNull { it.bottom } ?: stripHeight

    /**
     * This layout's single-letter keys, in a-z order. Absent letters are null.
     *
     * The letters are the only keys a word gesture can be about, and every consumer of them --
     * the decoder, the normalised grid below, the tests that check that grid -- was otherwise
     * about to scan [keyRects] for `id.length == 1` on its own.
     */
    val letterKeys: Array<KeyRect?> = arrayOfNulls<KeyRect>(26).also { out ->
        keyRects.forEach { rect ->
            val id = rect.key.id
            if (rect.key.type == KeyType.CHARACTER && id.length == 1 && id[0] in 'a'..'z') {
                out[id[0] - 'a'] = rect
            }
        }
    }

    /** The band the letters live in: the top of the first row to the bottom of the third. */
    val letterAreaTop: Float = letterKeys.filterNotNull().minOfOrNull { it.top } ?: stripHeight
    val letterAreaBottom: Float = letterKeys.filterNotNull().maxOfOrNull { it.bottom } ?: stripHeight

    /**
     * A point in the [0,1] square a layout-agnostic swipe decoder expects: +X right, +Y down.
     *
     * The unit square is **the three letter rows**, and getting that wrong is the mistake those
     * decoders' own documentation warns about hardest -- there is no way for them to detect it,
     * and the result is not an error but quietly worse words. Two things are outside it and both
     * were tempting to include. The suggestion strip, because it is part of our view; and the
     * bottom row, because it is part of the keys. Neither is somewhere a word gesture can go, and
     * either one stretches the square so that every key sits somewhere the decoder does not
     * expect. The reference layout FUTO ships puts its rows at 1/6, 1/2 and 5/6, which is three
     * rows filling the square exactly.
     *
     * Ours land at 0.142, 0.5 and 0.858, and the difference is real rather than an error: we draw
     * visible gaps between rows and they do not, so our centres sit slightly further apart. The
     * honest thing is to hand over where our keys actually are -- these models take the key
     * centres as an input for exactly this reason -- rather than to claim a grid we do not have.
     *
     * Values outside [0,1] are meaningful and are not clamped: a finger that strays above the top
     * row or below the bottom one really did go there, and that is information.
     */
    /**
     * Measured across the *keys*, not the view, so a squashed board hands the decoder the same
     * unit square a full-width one does. A glide across a squashed keyboard traces the same
     * shape in the same normalised space; only the pixels it covers are fewer, which is exactly
     * what the squash changed and the only thing the decoder must not be told about.
     */
    fun normalisedX(x: Float): Float = (x - contentLeft) / (contentRight - contentLeft)

    fun normalisedY(y: Float): Float = (y - letterAreaTop) / (letterAreaBottom - letterAreaTop)

    /**
     * Whether a touch in the suggestion strip is better explained as a press aimed at the letter
     * row below it than as a tap on the strip.
     *
     * This is the replacement for the dead band that used to sit at the bottom of the strip. The
     * band worked by refusing taps in a fixed slice of the bar, which cost the emoji real estate
     * permanently in order to catch a press that happens occasionally, and drew the line by
     * assertion. The evidence it was standing in for is continuous and already measured: thumbs
     * land [tap.SpatialModel.MEASURED_OFFSET_Y] *below* the drawn key centre, with a scatter of
     * [tap.SpatialModel.MEASURED_SIGMA_Y], so "how high is this press for the key under it" has a
     * natural unit and a natural threshold. See [tap.SpatialModel.sigmasAboveLetterRow].
     *
     * Both sides of the decision call this one function, so the strip and the keys cannot
     * disagree about a touch and no pixel is claimed twice or left unclaimed.
     *
     * The asymmetry is deliberate and is the safety property: crossing the line gives the touch
     * to the *letter*. A press misread as a letter costs one character, which backspace fixes; a
     * press misread as an emoji replaces the entire word being typed. When the evidence is
     * ambiguous the cheaper mistake is the one to make.
     */
    fun isLetterReach(x: Float, y: Float): Boolean =
        pressModel.sigmasAboveLetterRow(x, y, this) < SpatialModel.LETTER_REACH_SIGMAS

    /** The key under a touch point, or null. */
    fun keyAt(x: Float, y: Float): KeyRect? = keyRects.firstOrNull { it.contains(x, y) }

    /**
     * Nearest key by centre distance. Used for glide decoding, where the path runs through the
     * gaps between keys and an exact hit test would drop samples.
     */
    fun nearestKey(x: Float, y: Float): KeyRect? = keyRects.minByOrNull { r ->
        val dx = x - r.centerX
        val dy = y - r.centerY
        dx * dx + dy * dy
    }

    /**
     * The key a press belongs to: the one under the finger, or the nearest one when the finger
     * lands in a gap.
     *
     * The gaps are not decoration. The row gap is a quarter of a key tall -- 31px against a
     * 120px key on this phone -- and a thumb moving at typing speed lands in it constantly,
     * most often between the top row and the home row. An exact hit test answers "no key" there
     * and the press is discarded without a sound, which is indistinguishable, to the person
     * typing, from the keyboard ignoring them. It is the same fact [nearestKey] was written for
     * on the glide side; presses need it just as much.
     *
     * Distance is measured to the rectangle, not to its centre. In a row gap every candidate is
     * equidistant-ish from the finger by centre distance and the rows are inset differently, so
     * centre distance picks the horizontally closer key in the wrong row. Distance to the edge
     * picks the row you were reaching for and then the column you were over.
     *
     * The snap reaches up into the strip as far as the touch still looks like a press at the top
     * row -- see [isLetterReach] -- rather than up to a fixed line. The strip itself takes taps
     * everywhere; what decides between them is how far above a thumb's measured landing point the
     * touch sits, not which slice of the bar it is in.
     */
    fun keyForPress(x: Float, y: Float): KeyRect? {
        keyAt(x, y)?.let { return it }
        if (y < stripHeight && !isLetterReach(x, y)) return null
        if (y > keyAreaBottom + Metrics.BOTTOM_PADDING * scale) return null
        // The empty band beside a squashed board is not a near miss: it is the space the user
        // deliberately cleared to make room for their hand. Snapping there would fire the edge
        // keys -- q, a, backspace -- whenever a palm or a resting thumb touched the gap, which
        // is precisely the part of the screen a one-handed grip rests on.
        if (x < contentLeft || x > contentRight) return null
        return keyRects.minByOrNull { r ->
            val dx = when {
                x < r.left -> r.left - x
                x > r.right -> x - r.right
                else -> 0f
            }
            val dy = when {
                y < r.top -> r.top - y
                y > r.bottom -> y - r.bottom
                else -> 0f
            }
            dx * dx + dy * dy
        }
    }
}
