package com.offlinekeyboard.ime.text

/**
 * The part of a field the deletion history compares: where the selection is, and the text around
 * it. Read fresh from the editor every time and never cached, because its only job is to say
 * whether the field is still the one a record was made against.
 */
data class FieldState(
    val selStart: Int,
    val selEnd: Int,
    /** A window of text before the selection. */
    val before: String,
    val selected: String,
    /** A window of text after the selection. */
    val after: String,
)

/** What [DeletionHistory] needs from the editor. The service implements it over the connection. */
interface HistoryField {
    /** The field as it is now, or null when the editor will not say. */
    fun state(): FieldState?

    /** Asks the editor to undo, as its own menu would. May do nothing at all. */
    fun nativeUndo()

    /** Asks the editor to redo, as its own menu would. May do nothing at all. */
    fun nativeRedo()

    /** Puts [text] back at [at], selected when [select] is set, with the caret after it otherwise. */
    fun insert(text: String, at: Int, select: Boolean)

    /** Takes [text] out again: the selection when [selected] is set, the text before the caret otherwise. */
    fun remove(text: String, selected: Boolean)
}

/**
 * Undo and redo for the keyboard's own bulk deletes: the swipe up on backspace that clears a line,
 * and the swipe down that takes a word.
 *
 * Undo was already on `z`, and it goes through the editor's own undo
 * (`performContextMenuAction(android.R.id.undo)`). A standard `EditText` records these deletes like
 * any other edit and undoes them correctly. Plenty of editors do not, though. Chrome and WebView,
 * Compose text fields and apps that draw their own editor either ignore that menu action or never
 * saw the delete as something to record. In those editors a flick that cleared a line of text could
 * not be taken back at all, which is the worst gesture on the keyboard to have no undo for.
 *
 * So the keyboard records what it deleted and puts it back itself, but only as a fallback. The
 * editor is always asked first. The history takes over only when the editor did nothing, and it
 * checks that by comparing the field before and after asking. Asking first matters because of the
 * common case. If the keyboard restored the line itself in an `EditText`, the editor would log
 * the restore as a fresh insert. The next undo would then delete the line again, and repeated
 * presses would bounce the text in and out of the field.
 *
 * A record is honoured only while the field is exactly as the delete left it. That is the same
 * selection and the same text on either side, checked against a fresh read at the moment undo is
 * pressed. Typing, moving the caret or tapping elsewhere all change that state, so a stale record
 * can never paste text into a place it did not come from. The check looks at the result, not at
 * what caused it, so edits the keyboard never saw (another app, a hardware keyboard) are caught too.
 * It also means records chain: restoring the last delete puts the field back to how the delete
 * before it left it, so the next undo is valid again and walks back one more.
 */
class DeletionHistory(private val limit: Int = 16) {

    private data class Entry(val text: String, val selected: Boolean, val at: Int, val state: FieldState)

    private val undos = ArrayDeque<Entry>()
    private val redos = ArrayDeque<Entry>()

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    /**
     * A bulk delete has just removed [text]. Call after the edit has landed, so [HistoryField.state]
     * reads the field as the delete left it.
     *
     * [selected] says the text was a selection, so undo restores it selected rather than as
     * text with the caret after it.
     */
    fun recorded(text: CharSequence, selected: Boolean, field: HistoryField) {
        if (text.isEmpty()) return
        val state = field.state() ?: return
        undos.addLast(Entry(text.toString(), selected, state.selStart, state))
        while (undos.size > limit) undos.removeFirst()
        // A new edit forks the history, as it does in every editor: what was undone is gone.
        redos.clear()
    }

    /** Undo: the editor's own first, then this history's record if the editor did nothing. */
    fun undo(field: HistoryField) {
        val top = undos.lastOrNull()
        val now = top?.let { field.state() }
        field.nativeUndo()
        if (top == null) return
        if (now == null || now != top.state) {
            // The field has moved on since the delete, so every record is about text that is no
            // longer where it was. The older ones are older still, so they go too.
            clear()
            return
        }
        undos.removeLast()
        // The editor undid something itself -- in an EditText, the delete. Its own stack is now
        // in charge, and restoring the text again as well would put it in twice.
        if (field.state() != now) return
        field.insert(top.text, top.at, top.selected)
        val restored = field.state() ?: return
        redos.addLast(Entry(top.text, top.selected, top.at, restored))
    }

    /** Redo: the mirror of [undo], taking back out what [undo] restored. */
    fun redo(field: HistoryField) {
        val top = redos.lastOrNull()
        val now = top?.let { field.state() }
        field.nativeRedo()
        if (top == null) return
        if (now == null || now != top.state) {
            redos.clear()
            return
        }
        redos.removeLast()
        if (field.state() != now) return
        field.remove(top.text, top.selected)
        val removed = field.state() ?: return
        undos.addLast(Entry(top.text, top.selected, top.at, removed))
    }

    /** Forgets everything. For a new field, where the old offsets name different text. */
    fun clear() {
        undos.clear()
        redos.clear()
    }
}
