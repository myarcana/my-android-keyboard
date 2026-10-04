package com.offlinekeyboard.ime.cursor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A document of numbered lines, "line 00" .. "line NN", 20px per character and 60px rows from
 * y=0, laid out as if scrolled by [scrollY].
 */
private fun document(lines: Int, scrollY: Float = 0f): Pair<String, CaretMap> {
    val text = (0 until lines).joinToString("\n") { "line %02d".format(it) }
    val boxes = ArrayList<CharBox>()
    var row = 0
    var col = 0
    for (i in text.indices) {
        val x = 10f + col * 20f
        val y = row * 60f - scrollY
        boxes.add(
            CharBox(
                i,
                if (text[i] == '\n') Box(x, y, x, y + 60f) else Box(x, y, x + 20f, y + 60f),
                newline = text[i] == '\n',
            ),
        )
        if (text[i] == '\n') {
            row++
            col = 0
        } else {
            col++
        }
    }
    return text to CaretMapBuilder.build(boxes, text.length, atTextStart = true, atTextEnd = true)
}

class CaretSteerTest {

    private fun steerOn(map: CaretMap, caret: Int, bandBottom: Float = 600f): CaretSteer {
        val s = CaretSteer()
        s.install(map, caret)
        s.outerTop = 0f
        s.outerBottom = bandBottom
        s.screenRight = 1080f
        s.screenBottom = 2400f
        s.seed(caret, Float.NaN, Float.NaN, 0f, 0f)
        return s
    }

    @Test
    fun `the marker seeds on the caret and the caret stays put until it moves`() {
        val (_, map) = document(5)
        val s = steerOn(map, caret = 10)
        val h = s.target()!!
        assertEquals(10, map.offsetOf(h))
    }

    @Test
    fun `travel made before the map arrived is kept`() {
        val (_, map) = document(5)
        val s = CaretSteer()
        s.install(map, 3)
        s.outerBottom = 600f
        s.screenRight = 1080f
        s.screenBottom = 2400f
        s.seed(3, Float.NaN, Float.NaN, dx = 40f, dy = 60f)
        // Line 0 offset 3 is x=70; +40 is x=110 = column 5, one row down: line 1 starts at 8.
        assertEquals(8 + 5, map.offsetOf(s.target()!!))
    }

    @Test
    fun `panning moves the caret absolutely, every offset reachable`() {
        val (_, map) = document(5)
        val s = steerOn(map, caret = 0)
        val seen = LinkedHashSet<Int>()
        repeat(100) {
            s.pan(1.5f, 0f)
            seen.add(map.offsetOf(s.target()!!))
        }
        assertEquals((0..7).toList(), seen.toList())
    }

    @Test
    fun `vertical pans move a row at a time, keeping the column`() {
        val (_, map) = document(5)
        val s = steerOn(map, caret = 3)
        s.pan(0f, 60f)
        assertEquals(8 + 3, map.offsetOf(s.target()!!))
        s.pan(0f, 60f)
        assertEquals(16 + 3, map.offsetOf(s.target()!!))
    }

    @Test
    fun `the marker cannot run away past the text`() {
        val (_, map) = document(3)
        val s = steerOn(map, caret = 0)
        s.pan(0f, -5000f)
        // Pinned just above the first row: coming back responds immediately.
        assertTrue(s.markerY >= map.rows[0].top - map.rows[0].height)
        s.pan(0f, 5000f)
        assertTrue(s.markerY <= map.rows.last().bottom + map.rows.last().height)
        s.pan(5000f, 0f)
        assertTrue(s.markerX <= map.maxX + map.typicalGap + 0.01f)
    }

    @Test
    fun `rows below the band are not chosen, the marker parks and asks to scroll`() {
        // 20 lines, 60px each, but only 0..300 is visible (5 rows).
        val (_, map) = document(20)
        val s = steerOn(map, caret = 0, bandBottom = 300f)
        s.edgeScrollAllowed = true
        s.pan(0f, 290f) // centre of row 4 is 270; this is past it
        val h = s.target()!!
        assertTrue("row ${h.row}", h.row <= 4)
        s.pan(0f, 60f)
        assertEquals(1, s.verticalEdge())
        val step = s.edgeStep()
        assertNotNull(step)
        assertEquals(5, step!!.row)
    }

    @Test
    fun `without edge scrolling the marker keeps to the visible rows`() {
        val (_, map) = document(20)
        val s = steerOn(map, caret = 0, bandBottom = 300f)
        s.edgeScrollAllowed = false
        s.pan(0f, 2000f)
        assertEquals(0, s.verticalEdge())
        assertTrue(s.target()!!.row <= 4)
    }

    @Test
    fun `scrolling moves the map under a still marker`() {
        val (_, map) = document(20)
        val s = steerOn(map, caret = 0, bandBottom = 300f)
        s.edgeScrollAllowed = true
        s.pan(0f, 120f) // row 2
        val before = map.offsetOf(s.target()!!)
        assertEquals(2, s.target()!!.row)
        // The text scrolls up by one row; the marker has not moved, so it is now over row 3.
        s.scrolled(0f, -60f)
        val after = s.map.offsetOf(s.target()!!)
        assertEquals(3, s.target()!!.row)
        assertTrue(after > before)
    }

    @Test
    fun `a scroll caused by placing a caret teaches where the visible edge is`() {
        val (_, map) = document(20)
        val s = steerOn(map, caret = 0, bandBottom = 600f)
        val offsetOnRow8 = map.rows[8].offsets[0]
        // The editor revealed row 8 by scrolling up 120px: row 8 is now the last visible one.
        s.scrolled(0f, -120f)
        s.learnEdgeFromScroll(0f, -120f, offsetOnRow8)
        assertEquals(s.map.rows[8].bottom + 1f, s.bandBottom, 0.01f)
        assertEquals(8, s.visibleRows()!!.last)
    }

    @Test
    fun `at the end of the text there is no edge to scroll`() {
        val (_, map) = document(5)
        val s = steerOn(map, caret = 0, bandBottom = 2000f)
        s.edgeScrollAllowed = true
        s.pan(0f, 5000f)
        assertEquals(0, s.verticalEdge())
        assertNull(s.edgeStep())
    }

    @Test
    fun `a single-line field scrolled sideways steps to the hidden part`() {
        // One row of 40 characters, visible from x=0 to 400 only.
        val boxes = (0 until 40).map { CharBox(it, Box(it * 20f, 0f, it * 20f + 20f, 60f)) }
        val map = CaretMapBuilder.build(boxes, 40, atTextStart = true, atTextEnd = true)
        val s = CaretSteer()
        s.install(map, 5)
        s.outerTop = 0f
        s.outerBottom = 600f
        s.outerLeft = 0f
        s.outerRight = 400f
        s.screenRight = 1080f
        s.screenBottom = 2400f
        s.edgeScrollAllowed = true
        s.seed(5, Float.NaN, Float.NaN, 0f, 0f)
        s.target()
        s.pan(1000f, 0f)
        s.target()
        assertEquals(1, s.horizontalEdge())
        val step = s.edgeStep()!!
        assertEquals(21, map.offsetOf(step))
    }

    @Test
    fun `installing a new map keeps the caret by offset`() {
        val (_, map) = document(10)
        val s = steerOn(map, caret = 20)
        val (_, scrolled) = document(10, scrollY = 120f)
        s.install(scrolled, 20)
        assertEquals(20, scrolled.offsetOf(s.hit!!))
    }
}
