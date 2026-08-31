package com.offlinekeyboard.ime.layout

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
    /** Width as a multiple of one standard letter key. */
    val widthUnits: Float = 1f,
    val type: KeyType = KeyType.CHARACTER,
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
    /** Suggestion bar: emoji in English, candidates in Chinese. Never English word suggestions. */
    const val STRIP_HEIGHT = 50f

    /**
     * How much of the strip's height actually accepts a tap, measured from its top.
     *
     * The remainder is a buffer above the top letter row. It is here because the strip sits
     * directly above `q`-`p` and a press aimed at a top-row letter that comes in slightly high
     * used to land on a suggestion -- and tapping an emoji suggestion *replaces the word being
     * typed*, so a miss of a few pixels destroyed a whole word rather than costing a character.
     * A recorded passage caught one: a press 26px above `e`, horizontally dead centre of `e`'s
     * column, turned "book" into a book emoji.
     *
     * The buffer is not dead space -- LayoutGeometry.keyForPress snaps it into the top row --
     * so the near-miss becomes the letter that was meant.
     */
    const val STRIP_TOUCH_FRACTION = 0.78f
    const val BOTTOM_PADDING = 7.7f
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

    /** How tall the tappable part of the strip is -- what the autofill overlay may cover. */
    fun stripTouchHeightPx(widthPx: Float): Float = stripHeightPx(widthPx) * STRIP_TOUCH_FRACTION

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
class LayoutGeometry(val layout: Layout, val widthPx: Float) {

    private val scale = widthPx / Metrics.REFERENCE_WIDTH
    val margin = Metrics.SIDE_MARGIN * scale
    val gap = Metrics.KEY_GAP * scale
    val keyUnit = Metrics.KEY_WIDTH * scale
    val keyHeight = Metrics.KEY_HEIGHT * scale
    val rowGap = Metrics.ROW_GAP * scale
    val cornerRadius = Metrics.CORNER_RADIUS * scale
    /** Reserved above the keys for the suggestion bar. */
    val stripHeight = Metrics.stripHeightPx(widthPx)
    /** Where the strip stops taking taps; below this is the buffer above the top row. */
    val stripTouchBottom = stripHeight * Metrics.STRIP_TOUCH_FRACTION
    val heightPx = Metrics.HEIGHT_IN_KEY_WIDTHS * keyUnit

    val keyRects: List<KeyRect> = buildList {
        val usable = widthPx - 2 * margin
        layout.rows.forEachIndexed { rowIndex, row ->
            val contentWidth =
                row.keys.sumOf { it.widthUnits.toDouble() }.toFloat() * keyUnit +
                    (row.keys.size - 1) * gap
            var x = margin + (usable - contentWidth) / 2f
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
    fun normalisedX(x: Float): Float = x / widthPx

    fun normalisedY(y: Float): Float = (y - letterAreaTop) / (letterAreaBottom - letterAreaTop)

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
     * The snap reaches up to [stripTouchBottom] rather than to the top row: the last slice of the
     * strip does not take taps, precisely so a high press on a top-row letter arrives here and
     * becomes that letter. Above that line the strip owns the touch, and a tap there must stay a
     * tap on the strip rather than silently becoming a letter.
     */
    fun keyForPress(x: Float, y: Float): KeyRect? {
        keyAt(x, y)?.let { return it }
        if (y < stripTouchBottom || y > keyAreaBottom + Metrics.BOTTOM_PADDING * scale) return null
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
