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
    const val BOTTOM_PADDING = 7.7f
    const val CORNER_RADIUS = 8f
    const val ROW_COUNT = 4

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
    val stripHeight = Metrics.STRIP_HEIGHT * scale
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
}
