package com.offlinekeyboard.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LayoutGeometryTest {

    private fun geometry(width: Float = IosMetrics.REFERENCE_WIDTH) =
        LayoutGeometry(IosLayouts.QWERTY_LOWER, width)

    @Test
    fun `top row is the ten qwerty letters`() {
        val ids = IosLayouts.QWERTY_LOWER.rows[0].keys.map { it.id }
        assertEquals("qwertyuiop".map { it.toString() }, ids)
    }

    @Test
    fun `top row spans the full width at the reference size`() {
        val g = geometry()
        val row = g.keyRects.take(10)
        assertEquals(g.margin, row.first().left, 0.01f)
        assertEquals(IosMetrics.REFERENCE_WIDTH - g.margin, row.last().right, 0.01f)
    }

    @Test
    fun `home row is inset and centred, as on iOS`() {
        val g = geometry()
        val topRow = g.keyRects.take(10)
        val homeRow = g.keyRects.drop(10).take(9)
        assertTrue("home row starts inside the top row", homeRow.first().left > topRow.first().left)
        val leftInset = homeRow.first().left - g.margin
        val rightInset = (IosMetrics.REFERENCE_WIDTH - g.margin) - homeRow.last().right
        assertEquals("insets must be symmetric", leftInset, rightInset, 0.01f)
    }

    @Test
    fun `key aspect ratio matches iOS at any width`() {
        for (width in listOf(320f, 360f, 390f, 412f, 480f)) {
            val g = geometry(width)
            val q = g.keyRects.first()
            val aspect = q.height / q.width
            assertEquals(
                "aspect must hold at width=$width",
                IosMetrics.KEY_ASPECT.toDouble(),
                aspect.toDouble(),
                0.001,
            )
        }
    }

    @Test
    fun `geometry scales proportionally with width`() {
        val a = geometry(390f)
        val b = geometry(780f)
        assertEquals(a.keyUnit * 2f, b.keyUnit, 0.01f)
        assertEquals(a.heightPx * 2f, b.heightPx, 0.01f)
    }

    @Test
    fun `reference keyboard is the familiar iOS height`() {
        val g = geometry()
        // 4 rows at 54pt pitch plus outer padding.
        assertTrue("height was ${g.heightPx}", abs(g.heightPx - 228f) < 1f)
    }

    @Test
    fun `every key is hit-testable at its own centre`() {
        val g = geometry()
        for (rect in g.keyRects) {
            val hit = g.keyAt(rect.centerX, rect.centerY)
            assertNotNull("no hit for ${rect.key.id}", hit)
            assertEquals(rect.key.id, hit!!.key.id)
        }
    }

    @Test
    fun `keys never overlap within a row`() {
        val g = geometry()
        g.keyRects.groupBy { it.top }.forEach { (_, row) ->
            row.sortedBy { it.left }.zipWithNext { a, b ->
                assertTrue("${a.key.id} overlaps ${b.key.id}", a.right <= b.left + 0.001f)
            }
        }
    }

    @Test
    fun `touch outside the keyboard hits nothing`() {
        val g = geometry()
        assertNull(g.keyAt(-1f, 10f))
        assertNull(g.keyAt(10f, -1f))
        assertNull(g.keyAt(10f, g.heightPx + 100f))
    }

    @Test
    fun `nearest key resolves points that fall in the gaps`() {
        val g = geometry()
        val q = g.keyRects.first { it.key.id == "q" }
        val w = g.keyRects.first { it.key.id == "w" }
        val inGap = (q.right + w.left) / 2f
        assertNull("the gap is genuinely not inside a key", g.keyAt(inGap, q.centerY))
        assertNotNull(g.nearestKey(inGap, q.centerY))
    }

    @Test
    fun `flick secondaries follow the iPadOS digit row`() {
        val top = IosLayouts.QWERTY_LOWER.rows[0].keys
        assertEquals("1234567890".map { it.toString() }, top.map { it.secondary })
    }

    @Test
    fun `shift produces uppercase letters and uppercase accents`() {
        val lowerE = IosLayouts.QWERTY_LOWER.rows[0].keys.first { it.id == "e" }
        val upperE = IosLayouts.QWERTY_UPPER.rows[0].keys.first { it.id == "e" }
        assertEquals("E", upperE.primary)
        assertEquals(lowerE.accents.map { it.uppercase() }, upperE.accents)
    }

    @Test
    fun `shift preserves flick secondaries and non-character keys`() {
        val upper = IosLayouts.QWERTY_UPPER.rows[0].keys.first { it.id == "q" }
        assertEquals("1", upper.secondary)
        val shift = IosLayouts.QWERTY_UPPER.rows[2].keys.first()
        assertEquals(KeyType.SHIFT, shift.type)
        assertEquals("", shift.primary)
    }

    @Test
    fun `bottom row has the mode, globe, mic, space and return keys`() {
        val bottom = IosLayouts.QWERTY_LOWER.rows[3].keys.map { it.type }
        assertEquals(
            listOf(
                KeyType.MODE_SWITCH,
                KeyType.GLOBE,
                KeyType.MIC,
                KeyType.SPACE,
                KeyType.RETURN,
            ),
            bottom,
        )
    }
}
