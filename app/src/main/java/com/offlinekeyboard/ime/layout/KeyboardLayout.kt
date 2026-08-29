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
 * iOS portrait keyboard proportions, in points as measured on a 390pt-wide iPhone.
 *
 * Everything downstream is derived from these as ratios, so the layout keeps iOS's exact
 * proportions at any screen width rather than only matching on one device. The four rows at
 * 54pt pitch (42pt key + 12pt gap) reproduce the familiar 216pt iOS keyboard height.
 *
 * These are the numbers to adjust when calibrating against a real iOS screenshot; nothing
 * else should hardcode sizes.
 */
object IosMetrics {
    const val REFERENCE_WIDTH = 390f
    const val SIDE_MARGIN = 3f
    const val KEY_GAP = 6f
    /** (390 - 2*3 - 9*6) / 10 */
    const val KEY_WIDTH = 33f
    const val KEY_HEIGHT = 42f
    const val ROW_GAP = 12f
    const val ROW_COUNT = 4

    /** Key aspect ratio; preserved at every width so the keys never look squashed. */
    const val KEY_ASPECT = KEY_HEIGHT / KEY_WIDTH

    /** Total keyboard height as a multiple of the width of one key unit. */
    const val HEIGHT_IN_KEY_WIDTHS =
        (ROW_COUNT * KEY_HEIGHT + (ROW_COUNT - 1) * ROW_GAP + 2 * ROW_GAP) / KEY_WIDTH
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

    private val scale = widthPx / IosMetrics.REFERENCE_WIDTH
    val margin = IosMetrics.SIDE_MARGIN * scale
    val gap = IosMetrics.KEY_GAP * scale
    val keyUnit = IosMetrics.KEY_WIDTH * scale
    val keyHeight = IosMetrics.KEY_HEIGHT * scale
    val rowGap = IosMetrics.ROW_GAP * scale
    val heightPx = IosMetrics.HEIGHT_IN_KEY_WIDTHS * keyUnit

    val keyRects: List<KeyRect> = buildList {
        val usable = widthPx - 2 * margin
        layout.rows.forEachIndexed { rowIndex, row ->
            val contentWidth =
                row.keys.sumOf { it.widthUnits.toDouble() }.toFloat() * keyUnit +
                    (row.keys.size - 1) * gap
            var x = margin + (usable - contentWidth) / 2f
            val top = rowGap + rowIndex * (keyHeight + rowGap)
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
