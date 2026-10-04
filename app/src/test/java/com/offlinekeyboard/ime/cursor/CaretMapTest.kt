package com.offlinekeyboard.ime.cursor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fake monospaced editor: 20px per character, 60px rows starting at y=100, text from x=10.
 * Lines wrap at [wrapAt] characters on a space when there is one, the way a TextView does.
 */
private class FakeEditor(
    val text: String,
    val wrapAt: Int = 1000,
    val charW: Float = 20f,
    val rowH: Float = 60f,
    val left: Float = 10f,
    val top: Float = 100f,
    /** Report each character's line, as TextBoundsInfo does; else geometry only. */
    val withLines: Boolean = false,
) {
    /** For each offset, (row, column). */
    val pos = ArrayList<Pair<Int, Int>>()
    /** Offset each row starts at. */
    val rowStarts = ArrayList<Int>()

    init {
        var row = 0
        var col = 0
        var lineStart = 0
        rowStarts.add(0)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                pos.add(row to col)
                row++
                col = 0
                lineStart = i + 1
                rowStarts.add(i + 1)
                i++
                continue
            }
            if (col >= wrapAt) {
                // Wrap at the last space on this row, if any.
                val rowStart = rowStarts.last()
                val lastSpace = text.lastIndexOf(' ', i - 1).takeIf { it >= rowStart }
                val breakAt = if (lastSpace != null && lastSpace + 1 > rowStart) lastSpace + 1 else i
                // Re-lay the characters from breakAt onto the new row.
                while (pos.size > breakAt) pos.removeAt(pos.size - 1)
                row++
                col = 0
                rowStarts.add(breakAt)
                i = breakAt
                continue
            }
            pos.add(row to col)
            col++
            i++
        }
        @Suppress("UNUSED_VARIABLE") val unused = lineStart
    }

    fun box(i: Int): Box {
        val (r, c) = pos[i]
        val x = left + c * charW
        val y = top + r * rowH
        // A newline is drawn as a zero-width box at the end of its row.
        return if (text[i] == '\n') Box(x, y, x, y + rowH) else Box(x, y, x + charW, y + rowH)
    }

    fun boxes(from: Int = 0, to: Int = text.length) = (from until to).map { i ->
        CharBox(
            offset = i,
            box = box(i),
            newline = text[i] == '\n',
            boundary = CaretMapBuilder.isCaretBoundary(text, i),
            line = if (withLines) rowStarts.indexOfLast { it <= i } else -1,
            collapsible = text[i] == ' ',
        )
    }

    fun map(from: Int = 0, to: Int = text.length, trim: Boolean = false) = CaretMapBuilder.build(
        boxes(from, to),
        endOffset = to,
        atTextStart = from == 0,
        atTextEnd = to == text.length,
        startsAtLineStart = from == 0 || text[from - 1] == '\n',
        trimPartialRows = trim,
    )

    fun rowY(r: Int) = top + r * rowH + rowH / 2f
    fun colX(c: Int) = left + c * charW
}

class CaretMapTest {

    @Test
    fun `one line has a stop before every character and one at the end`() {
        val e = FakeEditor("hello")
        val m = e.map()
        assertEquals(1, m.rows.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), m.rows[0].offsets.toList())
        assertEquals(e.colX(0), m.rows[0].xs[0], 0.01f)
        assertEquals(e.colX(5), m.rows[0].xs[5], 0.01f)
    }

    @Test
    fun `the marker picks the nearest boundary`() {
        val e = FakeEditor("hello")
        val m = e.map()
        // Just right of the boundary between 'e' and 'l' (offset 2).
        val h = m.hit(e.colX(2) + 4f, e.rowY(0), prev = null)!!
        assertEquals(2, m.offsetOf(h))
        // Past the end of the line: the end.
        assertEquals(5, m.offsetOf(m.hit(e.colX(9), e.rowY(0), null)!!))
        // Before the start: the start.
        assertEquals(0, m.offsetOf(m.hit(-50f, e.rowY(0), null)!!))
    }

    @Test
    fun `hard line breaks make rows, the break's stop ends its row`() {
        val e = FakeEditor("ab\ncd")
        val m = e.map()
        assertEquals(2, m.rows.size)
        assertEquals(listOf(0, 1, 2), m.rows[0].offsets.toList())
        assertEquals(listOf(3, 4, 5), m.rows[1].offsets.toList())
        // The caret before the newline is drawn after 'b'.
        assertEquals(e.colX(2), m.rows[0].xs[2], 0.01f)
        // Far right on row 0 is the end of row 0, not the start of row 1.
        assertEquals(2, m.offsetOf(m.hit(500f, e.rowY(0), null)!!))
        assertEquals(5, m.offsetOf(m.hit(500f, e.rowY(1), null)!!))
    }

    @Test
    fun `empty lines get a row of their own, interpolated`() {
        val e = FakeEditor("ab\n\ncd")
        val m = e.map()
        assertEquals(3, m.rows.size)
        assertEquals(listOf(3), m.rows[1].offsets.toList())
        assertEquals(e.rowY(1), m.rows[1].centerY, 0.5f)
        assertEquals(e.colX(0), m.rows[1].xs[0], 0.5f)
        assertEquals(3, m.offsetOf(m.hit(200f, e.rowY(1), null)!!))
    }

    @Test
    fun `a trailing newline opens an empty last row`() {
        val e = FakeEditor("ab\n")
        val m = e.map()
        assertEquals(2, m.rows.size)
        assertEquals(listOf(3), m.rows[1].offsets.toList())
        assertEquals(e.rowY(1), m.rows[1].centerY, 0.5f)
    }

    @Test
    fun `an empty field is one row at offset 0 when there is somewhere to put it`() {
        val m = CaretMapBuilder.build(emptyList(), 0, atTextStart = true, atTextEnd = true)
        // No geometry at all: nothing to place, so no rows, and the caller handles it.
        assertTrue(m.isEmpty)
    }

    @Test
    fun `soft wraps put the wrap offset on the row it is drawn on`() {
        // "aaaa bbbb" wrapping after 6 columns: "aaaa " / "bbbb".
        val e = FakeEditor("aaaa bbbb", wrapAt = 6)
        val m = e.map()
        assertEquals(2, m.rows.size)
        assertEquals(5, e.rowStarts[1])
        // Row 0 owns 0..4 (the stop before the space is at 4); offset 5 is on row 1 only.
        assertEquals(listOf(0, 1, 2, 3, 4), m.rows[0].offsets.toList())
        assertEquals(listOf(5, 6, 7, 8, 9), m.rows[1].offsets.toList())
        assertTrue(m.rows[1].softStart)
        assertFalse(m.rows[0].softStart)
        // Far right on row 0 is offset 4, never 5 -- 5 would be drawn on row 1.
        assertEquals(4, m.offsetOf(m.hit(900f, e.rowY(0), null)!!))
    }

    @Test
    fun `soft wraps are found from geometry alone, and from line indices`() {
        val text = "the quick brown fox jumps over"
        val geo = FakeEditor(text, wrapAt = 11).map()
        val lines = FakeEditor(text, wrapAt = 11, withLines = true).map()
        assertEquals(geo.rows.map { it.offsets.toList() }, lines.rows.map { it.offsets.toList() })
        assertTrue(geo.rows.size >= 3)
    }

    @Test
    fun `every offset in the text is on exactly one row`() {
        val text = "one two three\nfour five six seven\n\neight"
        val m = FakeEditor(text, wrapAt = 9).map()
        val all = m.rows.flatMap { it.offsets.toList() }
        assertEquals(all.size, all.toSet().size)
        for (o in 0..text.length) assertNotNull("offset $o", m.locate(o))
    }

    @Test
    fun `surrogate pairs and joined emoji are one stop`() {
        val text = "a\uD83D\uDE00b\uD83D\uDC68\u200D\uD83D\uDC69c"
        val e = FakeEditor(text)
        val m = e.map()
        val offsets = m.rows[0].offsets.toList()
        // a | 😀 (2) | b | 👨‍👩 (5) | c | end
        assertEquals(listOf(0, 1, 3, 4, 9, 10), offsets)
    }

    @Test
    fun `hysteresis holds a stop until the marker is clearly nearer another`() {
        val e = FakeEditor("abcdef")
        val m = e.map()
        val at2 = m.hit(e.colX(2), e.rowY(0), null)!!
        assertEquals(2, m.offsetOf(at2))
        // Just past the midpoint toward 3: still 2.
        val mid = (e.colX(2) + e.colX(3)) / 2f
        assertEquals(2, m.offsetOf(m.hit(mid + 2f, e.rowY(0), at2)!!))
        // Without the previous position it would have been 3.
        assertEquals(3, m.offsetOf(m.hit(mid + 2f, e.rowY(0), null)!!))
        // Well past: 3.
        assertEquals(3, m.offsetOf(m.hit(mid + 8f, e.rowY(0), at2)!!))
    }

    @Test
    fun `row hysteresis holds a row across its boundary`() {
        val e = FakeEditor("abc\ndef")
        val m = e.map()
        val onRow0 = m.hit(e.colX(1), e.rowY(0), null)!!
        val boundary = e.top + e.rowH
        // A little below the boundary: still row 0.
        assertEquals(0, m.hit(e.colX(1), boundary + 5f, onRow0)!!.row)
        // Clearly on row 1.
        assertEquals(1, m.hit(e.colX(1), boundary + 20f, onRow0)!!.row)
    }

    @Test
    fun `moving across the text never skips a reachable offset`() {
        // Sweeping the marker along a row visits every stop in order -- no jumps.
        val e = FakeEditor("abcdefghij")
        val m = e.map()
        var prev: Hit? = null
        val seen = ArrayList<Int>()
        var x = e.colX(0) - 5f
        while (x < e.colX(10) + 5f) {
            prev = m.hit(x, e.rowY(0), prev)
            val o = m.offsetOf(prev!!)
            if (seen.lastOrNull() != o) seen.add(o)
            x += 1f
        }
        assertEquals((0..10).toList(), seen)
    }

    @Test
    fun `a window cut mid-row drops the fragments at either end`() {
        val text = "aaaa bbbb cccc dddd eeee"
        val e = FakeEditor(text, wrapAt = 10)
        // Rows: "aaaa bbbb " (0..9), "cccc dddd " (10..19), "eeee" (20..23).
        assertEquals(listOf(0, 10, 20), e.rowStarts)
        // A window from the middle of row 0 to the middle of row 2.
        val m = e.map(from = 3, to = 22, trim = true)
        assertEquals(1, m.rows.size)
        assertEquals(10, m.rows[0].offsets.first())
        assertFalse(m.atTextStart)
        assertFalse(m.atTextEnd)
    }

    @Test
    fun `a window starting on a hard line start keeps its first row`() {
        val text = "xx\naaaa bbbb cccc"
        val e = FakeEditor(text, wrapAt = 10)
        val m = e.map(from = 3, to = text.length, trim = true)
        assertEquals(3, m.rows.first().offsets.first())
        assertTrue(m.atTextEnd)
    }

    @Test
    fun `locate finds an offset inside a cluster on its row`() {
        val text = "a\uD83D\uDE00b"
        val m = FakeEditor(text).map()
        val h = m.locate(2)!!
        assertEquals(0, h.row)
    }

    @Test
    fun `characters with no box do not pile up at the end of the row`() {
        // A space a wrap swallowed has no box: its stop is the end of the row before.
        val boxes = listOf(
            CharBox(0, Box(0f, 0f, 10f, 20f)),
            CharBox(1, Box(10f, 0f, 20f, 20f)),
            CharBox(2, null, collapsible = true),
            CharBox(3, Box(0f, 30f, 10f, 50f)),
        )
        val m = CaretMapBuilder.build(boxes, 4, atTextStart = true, atTextEnd = true)
        assertEquals(2, m.rows.size)
        assertEquals(listOf(0, 1, 2), m.rows[0].offsets.toList())
        assertEquals(20f, m.rows[0].xs[2], 0.01f)
        // An unplaceable non-space character is skipped rather than given a made-up position.
        val m2 = CaretMapBuilder.build(
            listOf(CharBox(0, Box(0f, 0f, 10f, 20f)), CharBox(1, null), CharBox(2, Box(20f, 0f, 30f, 20f))),
            3, atTextStart = true, atTextEnd = true,
        )
        assertNull(m2.rows[0].offsets.toList().find { it == 1 })
    }

    @Test
    fun `right to left characters put the caret at their right edge`() {
        val boxes = listOf(
            CharBox(0, Box(80f, 0f, 100f, 20f), rtl = true),
            CharBox(1, Box(60f, 0f, 80f, 20f), rtl = true),
        )
        val m = CaretMapBuilder.build(boxes, 2, atTextStart = true, atTextEnd = true)
        assertEquals(listOf(100f, 80f, 60f), m.rows[0].xs.toList())
        assertEquals(1, m.offsetOf(m.hit(79f, 10f, null)!!))
    }

    @Test
    fun `translating the map moves every row and stop`() {
        val e = FakeEditor("ab\ncd")
        val m = e.map().translated(5f, -60f)
        assertEquals(e.colX(0) + 5f, m.rows[1].xs[0], 0.01f)
        assertEquals(e.rowY(0), m.rows[1].centerY, 0.01f)
    }

    @Test
    fun `boundary rules`() {
        assertFalse(CaretMapBuilder.isCaretBoundary("\uD83D\uDE00", 1))
        assertFalse(CaretMapBuilder.isCaretBoundary("e\u0301", 1)) // combining acute
        assertFalse(CaretMapBuilder.isCaretBoundary("\uD83D\uDC4D\uD83C\uDFFD", 2)) // skin tone
        assertFalse(CaretMapBuilder.isCaretBoundary("\r\n", 1))
        // Flags pair up: 🇯🇵🇺🇸 has a boundary between the two flags only.
        val flags = "\uD83C\uDDEF\uD83C\uDDF5\uD83C\uDDFA\uD83C\uDDF8"
        assertFalse(CaretMapBuilder.isCaretBoundary(flags, 2))
        assertTrue(CaretMapBuilder.isCaretBoundary(flags, 4))
        assertFalse(CaretMapBuilder.isCaretBoundary(flags, 6))
        assertTrue(CaretMapBuilder.isCaretBoundary("ab", 1))
    }
}
