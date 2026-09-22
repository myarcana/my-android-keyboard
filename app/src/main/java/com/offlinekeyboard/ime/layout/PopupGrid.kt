package com.offlinekeyboard.ime.layout

import kotlin.math.ceil
import kotlin.math.max
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
 * **The primary is pinned over its key.** Holding `c` should copy on release with nothing to aim
 * at, so the primary's cell is placed on the key's centre and the rest of the row is filled
 * outward from it. The width of the popup is therefore an *output* of where the key is, not an
 * input -- see [rowRoom] and [arrangeForThumb].
 *
 * That ordering is the whole design, and the alternative is what used to be here: a row of a
 * fixed five cells, slid sideways until it fit on screen, after which something had to work out
 * which cell had happened to end up over the key. Near the ends of the board the answer was
 * decided by which side of a cell boundary the key's centre fell on -- so on `a` the popup put
 * `ä` under the left 42% of the key, and a long press there selected the accent instead of
 * select-all. Pinning first means the question never arises: the primary covers the key by
 * construction, for the key's whole width.
 *
 * **The finger decides.** The opening selection is always [entryAt] of the finger's real
 * position, never an assumed slot. Placement decides where the popup is drawn; the finger alone
 * decides what is selected, and because the primary now covers the whole key the two agree
 * wherever on the key the press began.
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
         * How many cells fit in one popup row, and how many of them sit left of the primary.
         *
         * This is the whole layout decision, and it is made in the only order that does not need
         * a search: the primary's cell is *pinned* directly over the key, and the row then fills
         * outward into whatever space the screen actually has. Width is an output.
         *
         * The previous version fixed the width first -- always five cells -- then slid that rigid
         * block sideways until it fit on screen and tried to work out which cell luck had left
         * over the key. That is what produced the `ä`-under-`a` bug: on the leftmost key the
         * block could not be centred, and the cell that ended up over the key was decided by
         * which side of a cell boundary the key's centre happened to fall on. Nothing needs
         * discovering here, because nothing was ever moved: the primary is over the key by
         * construction, so [entryAt] over the key is the primary for the full width of the key.
         *
         * Both counts are returned together because they are one fact. The caller needs the row
         * width to wrap and the left count to know where the primary sits in it, and deriving
         * either from the other is how they came apart before.
         */
        fun rowRoom(key: KeyRect, geometry: LayoutGeometry): RowRoom {
            val cell = geometry.keyUnit
            // The primary's cell, centred on the key and then nudged inside the bounds. The nudge
            // only ever binds on a key whose own centre is within half a cell of the wall, where
            // the cell would otherwise hang off the board.
            val primaryLeft = (key.centerX - cell / 2f)
                .coerceIn(geometry.popupLeftBound, geometry.popupRightBound - cell)
            // Whole cells that fit each side of it. Truncation is the point: a partial cell is
            // not a target, so it is not offered.
            val left = ((primaryLeft - geometry.popupLeftBound) / cell).toInt().coerceAtLeast(0)
            val right = ((geometry.popupRightBound - (primaryLeft + cell)) / cell)
                .toInt().coerceAtLeast(0)
            return RowRoom(perRow = left + right + 1, leftOfPrimary = left)
        }

        /**
         * Arranges entries into rows, the primary pinned in the bottom row over its own key.
         *
         * The rule is: put the primary above the key, fill the rest of the row outward from it
         * for as long as there are cells on the board, and only start a second row once the row
         * is genuinely full. Reading order is preserved -- entries declared before the primary
         * sit to its left, those after it to its right -- so a slide goes where the eye expects.
         *
         * Where one side of the key is walled the other absorbs the surplus rather than forcing
         * a row that is not needed: on this layout every key has ten or more cells beside it, so
         * every popup the keyboard ships is a single row. That is the improvement over the
         * previous fixed five-cell row, which wrapped `o` and `i` and Enter into grids while
         * most of the board sat empty, and had to hunt for which cell had ended up over the key.
         *
         * When a row really does fill, the *short* row is the top one, furthest from the thumb:
         * cells are indexed row-major, so a ragged row has to be first for the plain division in
         * [cellLeft] and [entryAt] to keep agreeing.
         */
        fun arrangeForThumb(
            entries: List<PopupEntry>,
            primaryIndex: Int,
            room: RowRoom,
        ): PopupLayout {
            if (entries.isEmpty()) return PopupLayout(entries, 0)
            val perRow = room.perRow.coerceAtLeast(1)

            // How many of the primary's row-mates sit each side of it.
            //
            // Each side has its own hard ceiling: the cells that physically exist that side of
            // the key, and the entries that fall that side of the primary in the declared order.
            // Bounding the two separately is the point -- [RowRoom.perRow] is the total across
            // both, so spending it without asking which side the room is on is what ran `i`'s
            // popup off the right edge while eight unusable cells sat to its left.
            val roomLeft = room.leftOfPrimary
            val roomRight = room.perRow - 1 - roomLeft
            val before = primaryIndex
            val after = entries.size - 1 - primaryIndex

            // Each side takes its own entries first, then absorbs whatever the other could not
            // place -- which moves the primary along the row instead of starting a new one.
            //
            // The absorption is symmetric, and both directions are needed. `a` sits at the left
            // wall with no cells to its left and nine free to its right, so its four leading
            // accents follow the primary and all nine entries fit the one row they have room for.
            // `o` is the mirror at the right wall, and pulls its trailing accents to the left.
            // Without this a popup wrapped whenever the entries sat lopsidedly around the
            // primary, stacking rows while cells stood empty beside the key. Wrapping is what is
            // left only once the far side has genuinely run out too.
            val leftOwn = min(roomLeft, before)
            val rightOwn = min(roomRight, after)
            val rightFill = min(roomRight, min(after + (before - leftOwn), perRow - 1 - leftOwn))
            val leftFill = min(roomLeft, min(before + (after - rightOwn), perRow - 1 - rightFill))

            // The row as a list of *indices* into [entries], so that working out what is left
            // over afterwards is exact. Comparing entries by value would confuse two equal
            // accents, and by identity would rely on boxing -- indices are the only honest key.
            //
            // Reading order is preserved within each group. The primary keeps its place, the
            // entries nearest it on each side fill that side, and an overflowing side's surplus
            // continues on the other -- the leading ones after the primary, the trailing ones
            // before it -- so the row still reads left to right and only the split point moves.
            val takenBefore = min(before, leftFill)
            val takenAfter = min(after, rightFill)
            val wrapAfter = rightFill - takenAfter   // leading entries pushed right
            val wrapBefore = leftFill - takenBefore  // trailing entries pulled left
            val rowIndices =
                (primaryIndex + 1 + takenAfter until primaryIndex + 1 + takenAfter + wrapBefore) +
                    (primaryIndex - takenBefore until primaryIndex) +
                    listOf(primaryIndex) +
                    (primaryIndex + 1..primaryIndex + takenAfter) +
                    (0 until wrapAfter)
            val inRow = rowIndices.toSet()

            val bottom = rowIndices.map { entries[it] }
            // Anything the row could not take stacks above, in declared order.
            val above = entries.indices.filter { it !in inRow }.map { entries[it] }

            // The width every row is indexed by. The primary's row is the widest that fits, so it
            // sets the grid's width; [cellLeft] and [entryAt] are a plain division by this, so a
            // row above may not be wider. Reported rather than recomputed by the caller, because
            // it is decided here and disagreeing about it is how the highlight and the commit
            // came apart before.
            val width = bottom.size

            // Where the primary landed in the row it was built into. Counted from the groups that
            // precede it rather than from [leftFill], which is the room it was *offered* -- the
            // two differ whenever a side absorbed the other's surplus, and taking the wrong one
            // is how the popup opened on a neighbour.
            val primaryInRow = wrapBefore + takenBefore

            if (above.isEmpty()) return PopupLayout(bottom, primaryInRow, columns = width)

            // The ragged row is the top one, so pad the front.
            val padding = (width - above.size % width) % width
            return PopupLayout(
                List(padding) { PopupEntry.Blank } + above + bottom,
                padding + above.size + primaryInRow,
                columns = width,
            )
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
            val room = rowRoom(key, geometry)
            val arranged = arrangeForThumb(flat.entries, flat.primary, room)
            val entries = arranged.entries
            val primaryIndex = arranged.primary

            val count = entries.size.coerceAtLeast(1)
            val cellWidth = geometry.keyUnit
            val cellHeight = geometry.keyHeight * 1.15f
            // The width the arrangement actually used, not the room it was offered: where one
            // side of the key is walled the row is narrower than [RowRoom.perRow], and indexing
            // by the larger number would place cells past the edge of the board.
            val columns = (arranged.columns ?: count).coerceAtLeast(1)
            val rows = ceil(count / columns.toFloat()).toInt()

            val anchorCol = primaryIndex % columns
            val anchorRow = primaryIndex / columns

            // The primary's cell is pinned over the key, and the grid's origin follows from it.
            // No horizontal clamp: [rowRoom] already counted only cells that fit, so a grid built
            // from that count is inside the bounds by construction. Clamping here is what used to
            // slide the popup out from under its own primary.
            val primaryLeft = (key.centerX - cellWidth / 2f)
                .coerceIn(geometry.popupLeftBound, geometry.popupRightBound - cellWidth)
            val left = primaryLeft - anchorCol * cellWidth

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
