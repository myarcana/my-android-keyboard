package com.offlinekeyboard.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LayoutGeometryTest {

    private fun geometry(width: Float = Metrics.REFERENCE_WIDTH) =
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
        assertEquals(Metrics.REFERENCE_WIDTH - g.margin, row.last().right, 0.01f)
    }

    @Test
    fun `home row is inset and centred, as on iOS`() {
        val g = geometry()
        val topRow = g.keyRects.take(10)
        val homeRow = g.keyRects.drop(10).take(9)
        assertTrue("home row starts inside the top row", homeRow.first().left > topRow.first().left)
        val leftInset = homeRow.first().left - g.margin
        val rightInset = (Metrics.REFERENCE_WIDTH - g.margin) - homeRow.last().right
        assertEquals("insets must be symmetric", leftInset, rightInset, 0.01f)
    }

    @Test
    fun `key aspect ratio is preserved at any width`() {
        for (width in listOf(320f, 360f, 390f, 412f, 480f)) {
            val g = geometry(width)
            val q = g.keyRects.first()
            val aspect = q.height / q.width
            assertEquals(
                "aspect must hold at width=$width",
                Metrics.KEY_ASPECT.toDouble(),
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
    fun `reference keyboard matches the measured Gboard height`() {
        val g = geometry()
        // 50 strip + 4x40 keys + 3x10.33 row gaps + 7.7 bottom = 248.7dp
        assertTrue("height was ${g.heightPx}", abs(g.heightPx - 248.7f) < 1f)
    }

    @Test
    fun `keys start below the suggestion strip`() {
        val g = geometry()
        assertTrue("strip must reserve space", g.stripHeight > 0f)
        assertEquals(g.stripHeight, g.keyRects.first().top, 0.01f)
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

    /**
     * The one thing a plane switch must not do is move the key the thumb is already going for.
     * Pressing "123" puts a mode key exactly where shift was and backspace exactly where
     * backspace was, so a shift-then-backspace reflex survives the switch; iOS achieves it by
     * widening the five punctuation keys between them rather than by centring a shorter row.
     */
    @Test
    fun `shift and backspace hold their rectangles across every plane`() {
        val letters = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
        val shift = letters.keyRects.first { it.key.type == KeyType.SHIFT }
        val backspace = letters.keyRects.first { it.key.type == KeyType.BACKSPACE }

        for (plane in listOf(IosLayouts.NUMBERS, IosLayouts.SYMBOLS)) {
            val g = LayoutGeometry(plane, Metrics.REFERENCE_WIDTH)
            val mode = g.keyRects.first { it.key.type == KeyType.MODE_SWITCH }
            val back = g.keyRects.first { it.key.type == KeyType.BACKSPACE }
            assertEquals("${plane.id} mode key left", shift.left, mode.left, 0.01f)
            assertEquals("${plane.id} mode key right", shift.right, mode.right, 0.01f)
            assertEquals("${plane.id} mode key top", shift.top, mode.top, 0.01f)
            assertEquals("${plane.id} backspace left", backspace.left, back.left, 0.01f)
            assertEquals("${plane.id} backspace right", backspace.right, back.right, 0.01f)
        }
    }

    /** The slack the shoulders no longer take goes into the punctuation keys, which get wider. */
    @Test
    fun `punctuation keys are wider than a letter key`() {
        val letters = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
        val z = letters.keyRects.first { it.key.id == "z" }
        val g = LayoutGeometry(IosLayouts.NUMBERS, Metrics.REFERENCE_WIDTH)
        val dot = g.keyRects.first { it.key.id == "." }
        assertTrue("'.' (${dot.width}) must be wider than 'z' (${z.width})", dot.width > z.width)
    }

    /**
     * ABC goes to the letters from wherever it is pressed. It used to advance a ring -- letters,
     * numbers, symbols, letters -- which reads the same as this from the letter plane and is
     * wrong everywhere else: ABC on the numbers plane landed on symbols, and the second press
     * that finally reached the letters looked like the first one having done nothing.
     */
    @Test
    fun `each mode key names the plane it leads to`() {
        assertEquals(IosLayouts.NUMBERS, IosLayouts.planeFor("mode_123"))
        assertEquals(IosLayouts.SYMBOLS, IosLayouts.planeFor("mode_symbols"))
        assertEquals(IosLayouts.NUMBERS, IosLayouts.planeFor("mode_numbers"))
        assertEquals(IosLayouts.QWERTY_LOWER, IosLayouts.planeFor("mode_abc"))
        assertNull(IosLayouts.planeFor("backspace"))
    }

    /** Every mode key on every plane must actually go somewhere. */
    @Test
    fun `no mode key is a dead end`() {
        IosLayouts.ALL.forEach { plane ->
            plane.rows.flatMap { it.keys }
                .filter { it.type == KeyType.MODE_SWITCH }
                .forEach { key ->
                    assertNotNull("${plane.id}/${key.id} leads nowhere", IosLayouts.planeFor(key.id))
                }
        }
    }

    @Test
    fun `every plane offers a way back to the letters`() {
        for (plane in listOf(IosLayouts.NUMBERS, IosLayouts.SYMBOLS)) {
            assertTrue(
                "${plane.id} must have an ABC key",
                plane.rows.any { row -> row.keys.any { it.id == "mode_abc" } },
            )
        }
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
