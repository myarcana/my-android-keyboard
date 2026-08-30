package com.offlinekeyboard.ime

import android.view.inputmethod.EditorInfo

/**
 * What the return key means in the field that currently has focus.
 *
 * A keyboard cannot just type a newline and hope. An address bar, a search box or a chat
 * composer asks for an *action* -- Go, Search, Send -- and hears about it only through
 * [android.view.inputmethod.InputConnection.performEditorAction]. Committing "\n" into one of
 * those is silently discarded, which is what Firefox's address bar was doing with it.
 */
internal object ReturnKey {

    /**
     * The editor action the field declared, or null if the return key should send a plain
     * Enter instead.
     *
     * `IME_FLAG_NO_ENTER_ACTION` is the editor saying "an action is declared so you can label
     * the key with it, but pressing the key must still break the line" -- multi-line fields set
     * it, and honouring it is the difference between writing a paragraph and sending a
     * half-finished message.
     */
    fun actionFor(imeOptions: Int): Int? {
        if (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        return when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_NONE, EditorInfo.IME_ACTION_UNSPECIFIED -> null
            else -> action
        }
    }
}
