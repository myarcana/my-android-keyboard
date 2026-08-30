package com.offlinekeyboard.ime

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReturnKeyTest {

    @Test
    fun `a browser address bar gets its Go action`() {
        assertEquals(
            EditorInfo.IME_ACTION_GO,
            ReturnKey.actionFor(EditorInfo.IME_ACTION_GO),
        )
    }

    @Test
    fun `a search field gets its Search action`() {
        assertEquals(
            EditorInfo.IME_ACTION_SEARCH,
            ReturnKey.actionFor(EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_FULLSCREEN),
        )
    }

    @Test
    fun `a field declaring no action gets a plain Enter`() {
        assertNull(ReturnKey.actionFor(EditorInfo.IME_ACTION_NONE))
        assertNull(ReturnKey.actionFor(EditorInfo.IME_ACTION_UNSPECIFIED))
        assertNull(ReturnKey.actionFor(0))
    }

    /** A multi-line field labels the key but still wants the line break. */
    @Test
    fun `NO_ENTER_ACTION beats the declared action`() {
        assertNull(
            ReturnKey.actionFor(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION),
        )
    }
}
