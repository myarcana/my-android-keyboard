package com.offlinekeyboard.ime.layout

/**
 * An editing command that can sit in a long-press popup beside the accents.
 *
 * The popup was built to offer *alternative letters*, and every part of it -- the state machine,
 * the renderer, the commit path -- assumed its entries were text to type. These are not: they act
 * on the text that is already there. Modelling them as a separate kind of entry rather than as
 * magic strings is what keeps that distinction honest end to end; a popup slot holding
 * [PopupEntry.Accent] types something, a slot holding [PopupEntry.Action] does something, and
 * nothing downstream has to guess which by inspecting a label.
 *
 * The set is FUTO's, and so is the placement: the letter that names the shortcut on every desktop
 * keyboard carries it here. That is the whole reason this is discoverable without being taught --
 * ctrl+C has meant copy for forty years, so holding `c` is a guess the hand makes on its own.
 */
enum class EditAction(
    /** Shown under the icon in the popup, and read out by accessibility services. */
    val label: String,
) {
    SELECT_ALL("Select all"),
    CUT("Cut"),
    COPY("Copy"),
    PASTE("Paste"),
    UNDO("Undo"),
    REDO("Redo"),
}

/**
 * One slot in a long-press popup: either a character to type or a command to run.
 *
 * Deliberately a sealed hierarchy over a string with a flag. The popup's two jobs -- "type é" and
 * "copy the selection" -- have nothing in common past occupying a rectangle, and the compiler
 * enforcing that every reader handles both is what stopped an action from being committed as
 * literal text during this change.
 */
sealed interface PopupEntry {
    data class Accent(val text: String) : PopupEntry
    data class Action(val action: EditAction) : PopupEntry

    /**
     * An input language to switch to, offered by holding the globe key.
     *
     * The third kind, and the one that justifies the hierarchy existing rather than a string with
     * a flag. An accent is text to type and an action is a command to run; this is neither -- it
     * names a *destination*, and what identifies it is [id], not [label]. The label is a display
     * string chosen by whoever declared the keyboard and is routinely ambiguous (two installed
     * IMEs both calling themselves "English"), so committing by label would switch to whichever
     * one happened to be listed first.
     *
     * [id] is opaque here on purpose. The layout has no business knowing whether a destination is
     * one of our own subtypes or another app's keyboard entirely -- see
     * [com.offlinekeyboard.ime.LanguageMenu], which is the only thing that parses it.
     *
     * [current] is carried so the menu can mark where you already are. It affects nothing but the
     * drawing: releasing on the current language is allowed and is simply a no-op, which is what
     * changing your mind halfway through a slide should cost.
     */
    data class Language(
        val id: String,
        val label: String,
        val current: Boolean = false,
    ) : PopupEntry

    /**
     * An empty cell, used to pad a grid's top row so the rows below it line up.
     *
     * The grid is indexed row-major, which needs every row to be full; a popup whose entries do
     * not divide evenly has to put the gap somewhere. It goes at the start, so the ragged edge is
     * in the row furthest from the thumb and the row under the finger is whole. Nothing is drawn
     * for it and choosing it does nothing -- it is a hole in the popup, and behaves like one.
     */
    data object Blank : PopupEntry
}
