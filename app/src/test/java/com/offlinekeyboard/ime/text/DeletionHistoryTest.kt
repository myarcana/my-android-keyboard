package com.offlinekeyboard.ime.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Undo after the swipe deletes on backspace.
 *
 * Every case runs against two kinds of field, because the history has to be correct in both.
 * [nativeUndo] set models a standard EditText, whose own undo already reverses the delete. There
 * the history must stay out of the way, or the text comes back twice. Unset models Chrome,
 * WebView or Compose, which ignore the undo menu action, and there the history is the only thing
 * that can bring the line back.
 */
class DeletionHistoryTest {

    /**
     * A text field with a caret or selection, plus optional EditText-style undo that records
     * every edit as its own step. That is coarser than the real thing, and enough here: the
     * history never inspects the editor's stack, it only checks whether anything changed.
     */
    private class Field(text: String, caret: Int = text.length, val nativeUndo: Boolean) : HistoryField {
        var text = text
        var selStart = caret
        var selEnd = caret
        private val undoStack = ArrayDeque<Pair<String, Pair<Int, Int>>>()
        private val redoStack = ArrayDeque<Pair<String, Pair<Int, Int>>>()

        private fun snapshot() = text to (selStart to selEnd)

        private fun restore(s: Pair<String, Pair<Int, Int>>) {
            text = s.first
            selStart = s.second.first
            selEnd = s.second.second
        }

        /** An edit, recorded by the editor's own undo when it has one. */
        private fun edit(block: () -> Unit) {
            if (nativeUndo) {
                undoStack.addLast(snapshot())
                redoStack.clear()
            }
            block()
        }

        fun deleteBefore(n: Int) = edit {
            text = text.substring(0, selStart - n) + text.substring(selStart)
            selStart -= n
            selEnd = selStart
        }

        fun commit(s: String) = edit {
            text = text.substring(0, selStart) + s + text.substring(selEnd)
            selStart += s.length
            selEnd = selStart
        }

        fun select(start: Int, end: Int) {
            selStart = start
            selEnd = end
        }

        override fun state() = FieldState(
            selStart,
            selEnd,
            text.substring(0, selStart),
            text.substring(selStart, selEnd),
            text.substring(selEnd),
        )

        override fun nativeUndo() {
            if (!nativeUndo) return
            val s = undoStack.removeLastOrNull() ?: return
            redoStack.addLast(snapshot())
            restore(s)
        }

        override fun nativeRedo() {
            if (!nativeUndo) return
            val s = redoStack.removeLastOrNull() ?: return
            undoStack.addLast(snapshot())
            restore(s)
        }

        override fun insert(text: String, at: Int, select: Boolean) {
            commit(text)
            if (select) select(at, at + text.length)
        }

        override fun remove(text: String, selected: Boolean) {
            if (selected) commit("") else deleteBefore(text.length)
        }
    }

    /** What the service does on a swipe up: delete back to the line break, then record it. */
    private fun deleteLine(field: Field, history: DeletionHistory) {
        val before = field.text.substring(0, field.selStart)
        val n = WordBoundary.lineDeleteLength(before)
        if (n == 0) return
        val gone = before.takeLast(n)
        field.deleteBefore(n)
        history.recorded(gone, selected = false, field = field)
    }

    private fun bothKinds(block: (nativeUndo: Boolean) -> Unit) {
        block(true)
        block(false)
    }

    @Test
    fun `undo brings back a line cleared by the swipe up`() = bothKinds { native ->
        val field = Field("first line\nsecond line", nativeUndo = native)
        val history = DeletionHistory()
        deleteLine(field, history)
        assertEquals("first line\n", field.text)

        history.undo(field)
        assertEquals("native=$native", "first line\nsecond line", field.text)
        assertEquals(field.text.length, field.selStart)
    }

    @Test
    fun `a second undo does not put the line in twice`() = bothKinds { native ->
        val field = Field("keep\nthis line", nativeUndo = native)
        val history = DeletionHistory()
        deleteLine(field, history)
        history.undo(field)
        history.undo(field)
        // In the editor with its own undo, the second press unwinds whatever came before the
        // delete in that editor's stack, and here nothing did. In the other there is nothing
        // left to restore. Either way the line is in the field exactly once.
        assertEquals("native=$native", "keep\nthis line", field.text)
    }

    @Test
    fun `redo takes the line back out, and undo restores it again`() = bothKinds { native ->
        val field = Field("a\nbc def", nativeUndo = native)
        val history = DeletionHistory()
        deleteLine(field, history)
        history.undo(field)
        assertEquals("a\nbc def", field.text)
        history.redo(field)
        assertEquals("native=$native", "a\n", field.text)
        history.undo(field)
        assertEquals("native=$native", "a\nbc def", field.text)
    }

    @Test
    fun `repeated swipes are undone one line at a time`() = bothKinds { native ->
        val field = Field("one\ntwo\nthree", nativeUndo = native)
        val history = DeletionHistory()
        deleteLine(field, history) // "three"
        field.deleteBefore(1) // the newline, with a plain backspace -- not recorded
        deleteLine(field, history) // "two"
        assertEquals("one\n", field.text)

        history.undo(field)
        assertEquals("native=$native", "one\ntwo", field.text)
    }

    @Test
    fun `typing after the delete makes the record stale, so undo cannot paste it somewhere else`() {
        // Only the editor without its own undo is interesting here: the other one owns undo,
        // and what it does after typing is its business.
        val field = Field("hi\nsecret line", nativeUndo = false)
        val history = DeletionHistory()
        deleteLine(field, history)
        field.commit("new")
        history.undo(field)
        assertEquals("hi\nnew", field.text)
    }

    @Test
    fun `moving the caret away makes the record stale`() {
        val field = Field("abc\ndef", nativeUndo = false)
        val history = DeletionHistory()
        deleteLine(field, history)
        field.select(1, 1)
        history.undo(field)
        assertEquals("abc\n", field.text)
    }

    @Test
    fun `a deleted selection comes back selected`() = bothKinds { native ->
        val field = Field("hello brave world", nativeUndo = native)
        val history = DeletionHistory()
        field.select(6, 12)
        val gone = field.text.substring(6, 12)
        field.commit("")
        history.recorded(gone, selected = true, field = field)
        assertEquals("hello world", field.text)

        history.undo(field)
        assertEquals("native=$native", "hello brave world", field.text)
        assertEquals(6, field.selStart)
        assertEquals(12, field.selEnd)
    }

    @Test
    fun `undo with no record still asks the editor`() {
        val field = Field("abc", nativeUndo = true)
        field.commit("d")
        DeletionHistory().undo(field)
        assertEquals("abc", field.text)
    }
}
