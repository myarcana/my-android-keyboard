package com.offlinekeyboard.ime.layout

import kotlin.math.ceil
import kotlin.math.min

/**
 * Where a long-press popup's entries sit on screen, and which one a finger is on.
 *
 * One object owns both answers because they are the same answer. While the state machine
 * computed the slot under the finger and the renderer separately computed where to draw, the two
 * could disagree -- and did: the popup highlighted one entry and released another wherever the
 * clamp had shifted it. Everything about the popup's shape now lives here, and both callers ask.
 *
 * Two rules give the layout its shape:
 *
 * **The primary sits under the thumb.** Holding `c` should copy on release, with nothing to aim
 * at, so the popup is positioned to put [primaryIndex]'s cell over the key's centre rather than
 * to centre the popup as a whole. For a key with no action the primary is the middle entry, which
 * is the same thing as centring -- so accent popups keep the placement they have always had.
 *
 * **The finger decides, not the anchor.** Against the screen edge the popup has to be pushed back
 * inwards, and the primary then is not under the thumb any more; there is nowhere for it to be.
 * What must not happen is the popup claiming otherwise, so the opening selection is always
 * [entryAt] of the finger's real position. The anchor decides where the popup is drawn; the
 * finger alone decides what is selected.
 */
data class PopupGrid(
    val entries: List<PopupEntry>,
    /** The entry placed under the thumb, which the popup opens on when nothing is clamped. */
    val primary: Int,
    val columns: Int,
    val rows: Int,
    val left: Float,
    val top: Float,
    val cellWidth: Float,
    val cellHeight: Float,
    /**
     * Which arrangement produced this, for the renderer.
     *
     * Nothing about hit-testing needs it -- [entryAt] is the same division either way -- but
     * drawing does: a column of language names is left-aligned text in a wide cell, an accent is
     * a glyph centred in a square one. The alternative is the renderer inferring the shape from
     * the entry types it finds, which is the kind of guess that goes wrong the first time a
     * popup mixes them.
     */
    val shape: PopupShape = PopupShape.GRID,
) {
    val width: Float get() = columns * cellWidth
    val height: Float get() = rows * cellHeight

    /** Where an entry's cell sits, as (left, top). Entries fill left to right, top to bottom. */
    fun cellLeft(index: Int): Float = left + (index % columns) * cellWidth

    fun cellTop(index: Int): Float = top + (index / columns) * cellHeight

    /**
     * The entry under a point, clamped so a finger that slides past the edge keeps the last
     * entry rather than falling off into nothing.
     *
     * The clamp is per-axis and deliberate: sliding below a two-row grid should hold the bottom
     * row, not wrap around to the top of the next column. A short final row is clamped to its own
     * last entry, so the empty space beside it belongs to the entry next to it rather than
     * selecting nothing.
     */
    fun entryAt(x: Float, y: Float): Int {
        if (entries.isEmpty()) return 0
        val col = ((x - left) / cellWidth).toInt().coerceIn(0, columns - 1)
        val row = ((y - top) / cellHeight).toInt().coerceIn(0, rows - 1)
        return (row * columns + col).coerceIn(0, entries.size - 1)
    }

    companion object {
        /**
         * How far a pressed key's contents stand above its own top edge, in key heights.
         *
         * Lives here rather than in the renderer because the popup opens level with the raised
         * key and so needs the same number: a press and a long press are one gesture arriving at
         * two depths. The renderer owns how the key is *drawn* rising; where that puts its top
         * edge is geometry, and the state machine has to agree with it to hit-test the popup.
         */
        const val PRESS_LIFT = 1.45f

        /**
         * How far above its own top edge a raised key's contents stand, in pixels.
         *
         * Clamped at the top of the keyboard, which only ever binds on the top row: there is
         * nowhere above the view to draw, and a key with its rounded top sliced off by the window
         * edge looks broken in a way that being twenty pixels lower does not.
         */
        fun liftAbove(key: KeyRect, geometry: LayoutGeometry): Float =
            min(PRESS_LIFT * geometry.keyHeight, key.top)

        /**
         * The gap between the top of the pressed key and the bottom of the popup, in key heights.
         *
         * Deliberately *not* [PRESS_LIFT], though it used to be. The two look like the same
         * measurement and are not. A pressed key rises by [PRESS_LIFT] as one continuous shape:
         * the body stretches, so the 1.45 key heights between the glyph and the finger are filled
         * by the key itself, and the eye reads it as one object. The popup is a detached panel,
         * so the identical number becomes 1.45 key heights of *empty board* between the key and
         * the thing the key just produced -- which is what put the accents so far above the
         * letter that they read as belonging to the row above, and what pushed them off the top
         * of the view.
         *
         * A quarter of a key clears the fingertip and still reads as attached. The popup does not
         * need the full lift for clearance the way the key's own glyph does, because it is offset
         * sideways as well: the entries span several columns, so most of them are never under the
         * thumb at all.
         */
        const val POPUP_GAP = 0.25f

        /**
         * The lowest the popup's *bottom* edge may sit above the key it belongs to, in pixels.
         *
         * Separate from [liftAbove] so that shortening the gap cannot silently re-introduce the
         * overlap the lift was avoiding: whatever else moves, the popup stays clear of the key.
         */
        fun popupGap(geometry: LayoutGeometry): Float = POPUP_GAP * geometry.keyHeight

        /**
         * How many entries may sit in one row before the popup wraps into a grid.
         *
         * A row of accents is read at a glance and crossed with one slide, which is why iOS uses
         * one and why this stays a row for the common case. It stops working when the row gets
         * long: eight accents on `o` span most of the keyboard, so the popup has to be shoved
         * away from the key it belongs to, and the far end is an awkward reach across the hand
         * that is holding the key down. Wrapping keeps the popup near its key and puts every
         * entry within a short move in some direction.
         *
         * Five is the widest row that still fits beside a key pressed at either end of the board
         * without clamping -- ten key units wide overall, against the twelve the layout has.
         */
        const val MAX_COLUMNS = 5

        /**
         * Arranges entries into rows with the primary in the *bottom* row, nearest the thumb,
         * and reports where the primary ended up.
         *
         * Built outward from the primary rather than by chunking the list and shuffling rows.
         * Chunking first fixes which entries share a row before anything knows where the primary
         * is, so every attempt to move it afterwards changed its column as well as its row and
         * the popup opened on a neighbour. Here the bottom row is laid out first -- the primary
         * with as many of its neighbours as fit around it -- and whatever is left stacks above in
         * order. The primary's position is then known by construction instead of recovered by
         * arithmetic.
         *
         * Returning the new index rather than letting the caller work it out again is the point:
         * it only means anything relative to this arrangement.
         *
         * A popup that fits on one row is returned untouched, which is every accent popup of five
         * or fewer and every single-action key.
         */
        fun arrangeForThumb(
            entries: List<PopupEntry>,
            primaryIndex: Int,
            /**
             * Which column the popup can actually put under the thumb, if the caller knows.
             *
             * Near the ends of the keyboard the popup has to be pushed back on screen, and the
             * column the primary would like -- the middle -- is then somewhere else entirely. On
             * `a`, the leftmost key, only column 0 ever lands under the finger. Choosing the
             * column here, where the entries are still being arranged, is what lets the primary
             * reach the thumb anyway; positioning alone cannot fix it, because the popup has
             * nowhere left to move.
             */
            preferredColumn: Int? = null,
        ): PopupLayout {
            val columns = min(entries.size.coerceAtLeast(1), MAX_COLUMNS)
            if (entries.size <= columns) return PopupLayout(entries, primaryIndex)

            // The bottom row takes the primary and the entries either side of it, keeping it as
            // near the wanted column as the ends of the list allow.
            val half = preferredColumn ?: ((columns - 1) / 2)
            val start = (primaryIndex - half).coerceIn(0, entries.size - columns)
            val bottom = entries.subList(start, start + columns)
            val above = entries.filterIndexed { i, _ -> i < start || i >= start + columns }

            // Cells are indexed row-major from a full grid, so a row that is not full has to be
            // the *first* one for every later row to line up -- and the bottom row, the one the
            // thumb is on, must be full. Padding the top row keeps the arithmetic in [cellLeft]
            // and [entryAt] a plain division while leaving the short row where it shows least.
            val padding = (columns - above.size % columns) % columns
            return PopupLayout(
                List(padding) { PopupEntry.Blank } + above + bottom,
                padding + above.size + (primaryIndex - start),
            )
        }

        /**
         * The column that will end up under the key's centre, once clamping has had its say.
         *
         * For a key with room either side this is the middle column, and the popup is simply
         * centred. Against the ends of the keyboard the popup cannot be centred -- it would hang
         * off the screen -- so it gets pushed inward, and the column over the thumb is whichever
         * one the push leaves there. Asking the question in that order, rather than picking a
         * column and hoping, is what keeps the primary reachable on `a` and `p`.
         */
        fun thumbColumn(key: KeyRect, count: Int, geometry: LayoutGeometry): Int {
            val columns = min(count.coerceAtLeast(1), MAX_COLUMNS)
            val width = columns * geometry.keyUnit
            val middle = (columns - 1) / 2
            val ideal = key.centerX - (middle + 0.5f) * geometry.keyUnit
            val maxLeft = geometry.popupRightBound - width
            if (maxLeft < geometry.popupLeftBound) return middle
            val left = ideal.coerceIn(geometry.popupLeftBound, maxLeft)
            return ((key.centerX - left) / geometry.keyUnit).toInt().coerceIn(0, columns - 1)
        }

        /**
         * The whole popup for a key: its entries arranged into rows and placed above the key.
         *
         * The single entry point, taking the key rather than a pre-arranged list, because the
         * arrangement depends on where the key is -- which column can be got under the thumb --
         * and a caller that arranged first and placed afterwards would be deciding half of it
         * without the information the other half needs.
         */
        /**
         * How wide a language menu's cells are, as a multiple of one key unit.
         *
         * Wide enough for a short language name at the popup's text size without measuring one:
         * the grid is pure geometry and has no font, and threading a Paint through it to fit the
         * longest label would make the state machine's hit test depend on the renderer's
         * typeface. A label longer than this is ellipsised by the renderer instead, which keeps
         * the cell a stable target whatever is written in it.
         *
         * Clamped against the keyboard width at use, so a narrow screen gets a narrower menu
         * rather than one hanging off both edges.
         */
        const val COLUMN_WIDTH_UNITS = 3.6f

        /** How tall a language menu's rows are, as a multiple of key height. */
        const val COLUMN_ROW_HEIGHT = 0.92f

        /**
         * The shortest a menu row may be squeezed, as a multiple of key height.
         *
         * A floor rather than unlimited compression, because a row is a *target*: the finger
         * stops on it and holds there, and rows thinner than this cannot be landed on reliably
         * by a thumb that is already at the bottom of the screen. Half a key is the smallest
         * this keyboard asks anyone to hit anywhere.
         *
         * It is the floor that makes [columnsFor] necessary -- once rows stop shrinking, a long
         * enough menu has to grow sideways instead.
         */
        const val COLUMN_MIN_ROW_HEIGHT = 0.5f

        /**
         * How many columns a menu of [count] entries needs to fit in the room above the key.
         *
         * The menu grows sideways only when it must. One column is what the gesture is *for* --
         * slide up, slide down, release -- so it is kept for as long as the rows can stay a
         * usable height, which on the target phone is up to nine languages. Beyond that the
         * choice is between columns and a menu whose far end is off the screen, and an entry you
         * cannot reach is worse than one you have to move sideways for.
         */
        fun columnsFor(count: Int, key: KeyRect, geometry: LayoutGeometry): Int {
            if (count <= 1) return 1
            val room = key.top - popupGap(geometry)
            val minRow = COLUMN_MIN_ROW_HEIGHT * geometry.keyHeight
            val perColumn = (room / minRow).toInt().coerceAtLeast(1)
            return ceil(count / perColumn.toFloat()).toInt().coerceAtLeast(1)
        }

        /**
         * A single column of wide cells, stacked upward from just above the key.
         *
         * Placed by the same rules as the grid -- primary under the thumb, clamped inside the
         * view, never drawn above it -- but with the column pinned over the key's centre rather
         * than offset by a cell, because with one column there is no sideways choice to make.
         *
         * Fitting the menu into the room above the key is the interesting part, and it is done in
         * two stages because one was not enough. Rows are *compressed* first: a menu taller than
         * the space above the globe key shortens its rows rather than stacking off the top. That
         * alone was the first implementation and it was wrong -- compression has to stop at a row
         * height a thumb can still land on, and past that point the column simply kept growing,
         * so from ten languages the menu ran *below* the key, under the keys drawn over it and
         * off the bottom of the view. Entries there could be highlighted and never seen.
         *
         * So past the floor the menu grows *sideways* instead; see [columnsFor]. One column is
         * kept for as long as the rows stay usable, because sliding up and down is the whole
         * gesture, and a realistic number of languages never leaves it. Beyond that the choice is
         * between a second column and entries nobody can reach, which is not really a choice.
         */
        private fun column(key: KeyRect, layout: PopupLayout, geometry: LayoutGeometry): PopupGrid {
            val count = layout.entries.size.coerceAtLeast(1)
            val room = key.top - popupGap(geometry)

            // Sideways only when the rows would otherwise be too short to hit. See [columnsFor].
            val usable = geometry.popupRightBound - geometry.popupLeftBound
            val idealWidth = COLUMN_WIDTH_UNITS * geometry.keyUnit
            val columns = columnsFor(count, key, geometry)
                .coerceAtMost((usable / (idealWidth * 0.55f)).toInt().coerceAtLeast(1))
            val rows = ceil(count / columns.toFloat()).toInt().coerceAtLeast(1)

            val cellWidth = min(idealWidth, usable / columns)

            // Rows take their full height when the column fits and are squeezed toward the floor
            // only as far as the room demands. With [columns] chosen above so that `rows` rounds
            // down to something that fits, this no longer has to clamp *upward* -- the menu is
            // guaranteed to sit in the room it has, which is what keeps every entry on screen.
            val wanted = COLUMN_ROW_HEIGHT * geometry.keyHeight
            val cellHeight = min(wanted, room / rows)
                .coerceAtLeast(COLUMN_MIN_ROW_HEIGHT * geometry.keyHeight)
                // The last word: whatever the floor asks for, the menu must still fit above the
                // key. A row shorter than the floor is a poor target; a row *below the key* is
                // not a target at all, because the keys are drawn over it.
                .coerceAtMost(room / rows)

            // Entries are padded at the *front* so the last one lands in the bottom-right cell,
            // nearest a right thumb, and the ragged edge sits in the top row furthest from it --
            // exactly what [arrangeForThumb] does for accents, and for the same reason: the grid
            // is indexed row-major, so a short row has to be the first one.
            val padding = (columns - count % columns) % columns
            val entries =
                if (padding == 0) layout.entries
                else List(padding) { PopupEntry.Blank } + layout.entries

            val gridWidth = columns * cellWidth
            val idealLeft = key.centerX - gridWidth / 2f
            val maxLeft = geometry.popupRightBound - gridWidth
            val left = if (maxLeft < geometry.popupLeftBound) {
                geometry.popupLeftBound
            } else {
                idealLeft.coerceIn(geometry.popupLeftBound, maxLeft)
            }

            // The bottom of the menu sits just above the key, and the rows stack up from there.
            val top = (room - rows * cellHeight).coerceAtLeast(0f)

            return PopupGrid(
                entries = entries,
                primary = layout.primary + padding,
                columns = columns,
                rows = rows,
                left = left,
                top = top,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                shape = PopupShape.COLUMN,
            )
        }

        fun of(key: KeyRect, geometry: LayoutGeometry): PopupGrid {
            val flat = key.key.popupLayout
            if (flat.shape == PopupShape.COLUMN) return column(key, flat, geometry)
            val wanted = thumbColumn(key, flat.entries.size, geometry)
            val (entries, primaryIndex) =
                arrangeForThumb(flat.entries, flat.primary, wanted)

            val count = entries.size.coerceAtLeast(1)
            val columns = min(count, MAX_COLUMNS)
            val rows = ceil(count / columns.toFloat()).toInt()
            val cellWidth = geometry.keyUnit
            val cellHeight = geometry.keyHeight * 1.15f

            val anchorCol = primaryIndex % columns
            val anchorRow = primaryIndex / columns
            val width = columns * cellWidth
            val idealLeft = key.centerX - (anchorCol + 0.5f) * cellWidth
            val maxLeft = geometry.popupRightBound - width
            val left = if (maxLeft < geometry.popupLeftBound) {
                geometry.popupLeftBound
            } else {
                idealLeft.coerceIn(geometry.popupLeftBound, maxLeft)
            }

            // The anchor row sits just above the key, and any further rows stack upward from
            // there, so adding rows never pushes the primary away from the thumb.
            val anchorTop = key.top - popupGap(geometry) - cellHeight
            val idealTop = anchorTop - anchorRow * cellHeight

            // Nothing may be drawn above the view, so a grid that would overflow the top is
            // pushed down bodily until it fits. This clamps the *whole* grid rather than the
            // anchor row: the previous clamp looked only at the key's own top edge, which said
            // nothing about how many rows were stacked above it, so a popup that wrapped went
            // off the top of the keyboard however much room the key itself had. Moving the grid
            // as a unit is what keeps [cellTop] and [entryAt] agreeing -- the state machine
            // hit-tests this same object, so a clamp applied to only some rows would light one
            // entry and commit another.
            //
            // Pushing down can put the popup over the key on the top row, where there is genuinely
            // nowhere else for it to go. Overlapping the key it came from is the better failure:
            // a covered key is still readable as the source, whereas a row sliced off by the
            // window edge is not readable at all.
            val top = idealTop.coerceAtLeast(0f)

            return PopupGrid(
                entries = entries,
                primary = primaryIndex,
                columns = columns,
                rows = rows,
                left = left,
                top = top,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
            )
        }
    }
}
