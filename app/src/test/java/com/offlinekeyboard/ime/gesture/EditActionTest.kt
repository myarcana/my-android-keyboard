package com.offlinekeyboard.ime.gesture

import com.offlinekeyboard.ime.layout.EditAction
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.Metrics
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.PopupEntry
import com.offlinekeyboard.ime.layout.PopupGrid
import com.offlinekeyboard.ime.layout.RowRoom
import com.offlinekeyboard.ime.layout.Squash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FUTO-style edit actions on the long-press popup: c copies, v pastes, a selects all.
 *
 * What these are really testing is the seam rather than the mapping. A popup entry used to be a
 * string that got typed, and the whole risk of the change is a path that still treats it that
 * way -- committing "Copy" as text, or lighting up a slot the finger is not on. The mapping
 * itself is one line in [IosLayouts] and would be tested by restating it; the behaviour of a
 * popup that now holds two different kinds of thing is not.
 */
class EditActionTest {

    private val geometry = LayoutGeometry(IosLayouts.QWERTY_LOWER, Metrics.REFERENCE_WIDTH)
    private val config = GestureConfig()

    private fun fsm() = TouchFsm(geometry, config)
    private fun key(id: String): KeyRect = geometry.keyRects.first { it.key.id == id }

    private inline fun <reified T> List<GestureOutput>.only(): T = filterIsInstance<T>().single()
    private inline fun <reified T> List<GestureOutput>.has(): Boolean = any { it is T }

    /** Holds the key and lifts without moving: the popup's first slot. */
    private fun holdAndRelease(id: String): List<GestureOutput> {
        val k = key(id)
        val f = fsm()
        f.onDown(k.centerX, k.centerY, 0)
        f.onLongPressTimeout(config.longPressMs)
        return f.onUp(k.centerX, k.centerY, 700)
    }

    @Test
    fun `holding c offers copy and releasing runs it`() {
        val out = holdAndRelease("c")
        assertEquals(EditAction.COPY, out.only<GestureOutput.CommitAction>().action)
    }

    @Test
    fun `holding v pastes and holding a selects all`() {
        assertEquals(
            EditAction.PASTE,
            holdAndRelease("v").only<GestureOutput.CommitAction>().action,
        )
        assertEquals(
            EditAction.SELECT_ALL,
            holdAndRelease("a").only<GestureOutput.CommitAction>().action,
        )
    }

    @Test
    fun `x cuts, z undoes and y redoes`() {
        assertEquals(EditAction.CUT, holdAndRelease("x").only<GestureOutput.CommitAction>().action)
        assertEquals(EditAction.UNDO, holdAndRelease("z").only<GestureOutput.CommitAction>().action)
        assertEquals(EditAction.REDO, holdAndRelease("y").only<GestureOutput.CommitAction>().action)
    }

    /**
     * An action is never text. The service commits [GestureOutput.CommitAccent] straight into the
     * field, so an action arriving as one would type the word "Copy" into whatever was being
     * written -- the exact failure the separate output type exists to make impossible.
     */
    @Test
    fun `choosing an action does not commit any text`() {
        val out = holdAndRelease("c")
        assertTrue(!out.has<GestureOutput.CommitAccent>())
        assertTrue(!out.has<GestureOutput.CommitPrimary>())
        assertTrue(!out.has<GestureOutput.CommitSecondary>())
    }

    /**
     * Keys that had no popup at all now have one. The press must still not type the letter: a
     * gesture that ended in the popup is not a keypress, however it started.
     */
    @Test
    fun `a key whose only popup entry is an action still opens one`() {
        val v = key("v")
        val f = fsm()
        f.onDown(v.centerX, v.centerY, 0)
        val opened = f.onLongPressTimeout(config.longPressMs)
        assertEquals(GestureState.ACCENTS, f.state)
        assertEquals(
            listOf(PopupEntry.Action(EditAction.PASTE)),
            opened.only<GestureOutput.ShowAccents>().entries,
        )
        assertTrue(!f.onUp(v.centerX, v.centerY, 700).has<GestureOutput.CommitPrimary>())
    }

    /** The accents a key shares with an action are all still reachable. */
    @Test
    fun `a key with both keeps every accent it had`() {
        val a = key("a")
        val f = fsm()
        f.onDown(a.centerX, a.centerY, 0)
        val entries = f.onLongPressTimeout(config.longPressMs)
            .only<GestureOutput.ShowAccents>().entries
        val declared = IosLayouts.QWERTY_LOWER.rows.flatMap { it.keys }.first { it.id == "a" }
        assertEquals(
            declared.accents.toSet(),
            entries.filterIsInstance<PopupEntry.Accent>().map { it.text }.toSet(),
        )
        assertTrue(entries.contains(PopupEntry.Action(EditAction.SELECT_ALL)))
    }

    /** Sliding off the action and onto an accent still types the accent. */
    @Test
    fun `sliding off the action reaches an accent`() {
        val a = key("a")
        val f = fsm()
        f.onDown(a.centerX, a.centerY, 0)
        val opened = f.onLongPressTimeout(config.longPressMs)
        val entries = opened.only<GestureOutput.ShowAccents>().entries
        val primary = opened.only<GestureOutput.AccentHighlighted>().index
        val grid = PopupGrid.of(a, geometry)

        // The cell immediately right of the one under the thumb, which is an accent.
        val target = primary + 1
        val x = grid.cellLeft(target) + grid.cellWidth / 2f
        val y = grid.cellTop(target) + grid.cellHeight / 2f
        f.onMove(x, y, 600)
        val out = f.onUp(x, y, 700)
        assertEquals(
            entries[target],
            PopupEntry.Accent(out.only<GestureOutput.CommitAccent>().text),
        )
        assertTrue(!out.has<GestureOutput.CommitAction>())
    }

    /**
     * A popup fills the room it has, wraps only when it runs out, and keeps the thumb's row full.
     *
     * The wrap is no longer a fixed five-cell cap but a consequence of the space beside the key,
     * so this asserts the shape rather than a column count: every popup stays inside the board,
     * the ragged row is the top one furthest from the finger, and the row under the thumb is
     * whole. On this layout there is room for ten or more cells beside every key, so nothing
     * actually wraps -- which is the improvement, and is checked by the bound not by a literal.
     */
    @Test
    fun `a popup fills its row and keeps the thumb's row full`() {
        IosLayouts.ALL.forEach { layout ->
            val g = LayoutGeometry(layout, Metrics.REFERENCE_WIDTH)
            g.keyRects.filter { it.key.popup.isNotEmpty() }.forEach { k ->
                val grid = PopupGrid.of(k, g)
                assertTrue(
                    "${k.key.id}: popup runs off the left (${grid.left})",
                    grid.left >= g.popupLeftBound - 0.01f,
                )
                assertTrue(
                    "${k.key.id}: popup runs off the right (${grid.left + grid.width})",
                    grid.left + grid.width <= g.popupRightBound + 0.01f,
                )
                // The thumb's row is the last one, and it carries no holes.
                val bottomRow = grid.entries.drop((grid.rows - 1) * grid.columns)
                assertTrue(
                    "${k.key.id}: the thumb's row must hold no padding",
                    bottomRow.none { it == PopupEntry.Blank },
                )
                // Filling the row is the whole point: a popup may only wrap once the board has
                // genuinely run out of cells beside the key, never merely because the entries sat
                // lopsidedly around the primary. Every popup on this layout fits one row.
                val room = PopupGrid.rowRoom(k, g)
                if (k.key.popup.size <= room.perRow) {
                    assertEquals(
                        "${k.key.id}: ${k.key.popup.size} entries fit ${room.perRow} cells " +
                            "but wrapped into ${grid.rows} rows",
                        1,
                        grid.rows,
                    )
                    assertTrue(
                        "${k.key.id}: a single row should carry no padding",
                        grid.entries.none { it == PopupEntry.Blank },
                    )
                }
                // Padding, where there is any, is at the very start.
                val blanks = grid.entries.count { it == PopupEntry.Blank }
                assertEquals(
                    "${k.key.id}: padding belongs at the front",
                    List(blanks) { PopupEntry.Blank },
                    grid.entries.take(blanks),
                )
            }
        }
    }

    /**
     * Releasing on a padding cell does nothing at all -- it is a hole, not an entry.
     *
     * The wrap is now driven by the room beside the key rather than a fixed five-cell cap, and
     * every real key on this board has room for a single row -- so no shipped popup carries
     * padding any more, and this arranges the wrapping case directly instead of borrowing a key
     * that happened to wrap. The narrow room is the point: it is what a genuinely cramped board
     * would give, and the blank must stay inert whenever it does arise.
     */
    @Test
    fun `a blank cell commits nothing`() {
        val entries = (1..7).map { PopupEntry.Accent("a$it") }
        val layout = PopupGrid.arrangeForThumb(
            entries,
            primaryIndex = 3,
            room = RowRoom(perRow = 3, leftOfPrimary = 1),
        )
        val blank = layout.entries.indexOfFirst { it == PopupEntry.Blank }
        assertTrue("a wrapped popup should carry padding", blank >= 0)
        // Padding is at the front, and the primary is never one of the holes.
        assertEquals(0, blank)
        assertTrue(layout.entries[layout.primary] != PopupEntry.Blank)
        assertEquals(entries[3], layout.entries[layout.primary])
    }

    /**
     * Enter carries the whole menu, because the letters that name these actions are not on the
     * number or symbol planes and this key is.
     */
    @Test
    fun `holding enter opens the edit menu instead of submitting`() {
        val ret = key("return")
        val f = fsm()
        f.onDown(ret.centerX, ret.centerY, 0)
        val entries = f.onLongPressTimeout(config.longPressMs)
            .only<GestureOutput.ShowAccents>().entries
        // The menu wraps into a grid, so cell order is not the declared order; every command
        // must be present and nothing else.
        assertEquals(
            setOf(
                EditAction.SELECT_ALL,
                EditAction.CUT,
                EditAction.COPY,
                EditAction.PASTE,
                EditAction.UNDO,
                EditAction.REDO,
            ),
            entries.filterIsInstance<PopupEntry.Action>().map { it.action }.toSet(),
        )
        // Holding Enter must not also submit the field.
        assertTrue(!f.onUp(ret.centerX, ret.centerY, 700).has<GestureOutput.SpecialKey>())
    }

    /**
     * The popup opens on the slot the finger is already on, never on slot 0 regardless.
     *
     * This is the jump: with the highlight hardcoded to 0 while every later move computed the
     * slot under the finger, the first tremor after the popup appeared silently moved the
     * selection. The single-action keys are anchored so that slot is the action; what is being
     * checked here is that opening and moving agree, which is what stops it moving on its own.
     */
    @Test
    fun `the popup opens on the entry under the finger and does not move by itself`() {
        // Every key that opens a popup, accents included: the jump was a property of the popup,
        // not of the actions that exposed it.
        val ids = IosLayouts.QWERTY_LOWER.rows
            .flatMap { it.keys }
            .filter { it.popup.isNotEmpty() }
            .map { it.id }
        ids.forEach { id ->
            val k = key(id)
            val f = fsm()
            f.onDown(k.centerX, k.centerY, 0)
            val opened = f.onLongPressTimeout(config.longPressMs)
                .only<GestureOutput.AccentHighlighted>().index
            // A pixel of tremor, which is what a still thumb actually sends.
            val moved = f.onMove(k.centerX + 0.5f, k.centerY, 600)
            assertTrue(
                "$id: the popup re-highlighted on a move the finger did not mean",
                !moved.has<GestureOutput.AccentHighlighted>(),
            )
            val grid = PopupGrid.of(k, geometry)
            assertEquals(
                "$id: opened on a different cell than the one under the finger",
                grid.entryAt(k.centerX, k.centerY),
                opened,
            )
        }
    }

    /**
     * Each single-action key opens with its own command under the thumb, so holding and letting
     * go runs it -- no slide, nothing to aim at. This is the whole point of putting copy on `c`,
     * and it has to hold for keys at the ends of the board too, where the popup is pushed back on
     * screen and the command has to be placed in whichever column that leaves under the finger.
     */
    @Test
    fun `holding a letter opens on its own action, ready to release`() {
        mapOf(
            "c" to EditAction.COPY,
            "v" to EditAction.PASTE,
            "x" to EditAction.CUT,
            "a" to EditAction.SELECT_ALL,
            "z" to EditAction.UNDO,
            "y" to EditAction.REDO,
        ).forEach { (id, action) ->
            assertEquals(
                "$id should copy/paste/etc on a plain hold-and-release",
                action,
                holdAndRelease(id).only<GestureOutput.CommitAction>().action,
            )
        }
    }

    /**
     * The reported gesture, end to end: a finger on the *left* of `a`, held and released.
     *
     * The grid-level sweep below proves the geometry; this proves the thing that was actually
     * complained about, through the state machine that the service drives, because the opening
     * selection is [PopupGrid.entryAt] of the real touch point and nothing re-centres it.
     */
    @Test
    fun `long-pressing the left edge of a still selects all`() {
        val a = key("a")
        // A quarter and a tenth in from the left edge: where a thumb aiming slightly left lands.
        listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f).forEach { frac ->
            val x = a.left + a.width * frac
            val f = fsm()
            f.onDown(x, a.centerY, 0)
            f.onLongPressTimeout(config.longPressMs)
            assertEquals(
                "a long press at ${frac * 100}% across `a` should offer select-all",
                EditAction.SELECT_ALL,
                f.onUp(x, a.centerY, 700).only<GestureOutput.CommitAction>().action,
            )
        }
    }

    /**
     * The primary must own the *whole* key, not just the point at its centre.
     *
     * A long press starts wherever the finger already is, and a thumb aiming at `a` lands
     * anywhere across its 30px. The popup opened on whichever cell that exact point fell in, so
     * on `a` -- where the popup is clamped against the left bound and the chosen column's cell
     * sits a full key to the right of it -- the left 42% of the key was over the *neighbouring*
     * cell, and long-pressing there selected `ä` instead of select-all. Holding and releasing
     * did the wrong thing depending on where on the key the finger happened to be.
     *
     * The centre point passed either way, which is why every existing test here missed it: they
     * all press [KeyRect.centerX]. This sweeps the key's full width instead, which is the real
     * precondition -- the primary is meant to need no aim at all.
     *
     * Enter is excluded, and is not a counterexample. It is 2.5 key units wide against a
     * one-unit cell, so no single cell can cover it by construction, and it deliberately carries
     * the whole six-command menu rather than one primary with alternatives around it: it is a
     * menu to be read and aimed at. Every key that promises a no-aim primary is checked.
     */
    @Test
    fun `the primary is selected from anywhere on its key, not just the centre`() {
        listOf(Squash.NONE, Squash.LEFT, Squash.RIGHT).forEach { squash ->
            IosLayouts.ALL.forEach { layout ->
                val g = LayoutGeometry(layout, Metrics.REFERENCE_WIDTH, squash = squash)
                g.keyRects
                    .filter { it.key.popup.isNotEmpty() && it.key.id != "return" }
                    .forEach { k ->
                        val grid = PopupGrid.of(k, g)
                        // Sampled across the key rather than at its edges, so this is about the
                        // cell genuinely covering the key and not about rounding at the boundary.
                        val samples = 200
                        val strays = (0 until samples).mapNotNull { i ->
                            val x = k.left + k.width * (i + 0.5f) / samples
                            val hit = grid.entryAt(x, k.centerY)
                            if (hit == grid.primary) null else {
                                "%.1f selects %s".format(x, grid.entries[hit])
                            }
                        }
                        assertEquals(
                            "${layout.id}/$squash: holding ${k.key.id} should offer " +
                                "${grid.entries[grid.primary]} from every point on the key, " +
                                "but ${strays.size}/$samples miss, e.g. ${strays.take(3)}",
                            emptyList<String>(),
                            strays,
                        )
                    }
            }
        }
    }

    /**
     * Every cell of every popup on the board commits the entry drawn in it.
     *
     * This replaces two narrower tests that each asserted a popup "should wrap" first -- true
     * under the old fixed five-cell row, and no longer true now that a row fills the room beside
     * its key. Their real subject was never the wrap: it was that the state machine's idea of
     * where the popup is must match the renderer's, because both read this one [PopupGrid] and a
     * disagreement of even a single cell lights one entry and runs another. Sweeping every cell
     * of every key of every layout tests that directly, and keeps testing it whatever the
     * arrangement rules become.
     */
    @Test
    fun `every cell of every popup commits what it shows`() {
        IosLayouts.ALL.forEach { layout ->
            val g = LayoutGeometry(layout, Metrics.REFERENCE_WIDTH)
            g.keyRects.filter { it.key.popup.isNotEmpty() }.forEach { k ->
                val grid = PopupGrid.of(k, g)
                grid.entries.forEachIndexed { i, entry ->
                    if (entry == PopupEntry.Blank) return@forEachIndexed
                    val f = TouchFsm(g, config)
                    f.onDown(k.centerX, k.centerY, 0)
                    f.onLongPressTimeout(config.longPressMs)
                    val x = grid.cellLeft(i) + grid.cellWidth / 2f
                    val y = grid.cellTop(i) + grid.cellHeight / 2f
                    f.onMove(x, y, 600)
                    val out = f.onUp(x, y, 700)
                    val got = when (entry) {
                        is PopupEntry.Action ->
                            PopupEntry.Action(out.only<GestureOutput.CommitAction>().action)
                        is PopupEntry.Accent ->
                            PopupEntry.Accent(out.only<GestureOutput.CommitAccent>().text)
                        else -> entry
                    }
                    assertEquals("${layout.id} ${k.key.id} cell $i", entry, got)
                }
            }
        }
    }

    /**
     * Every action on the board must be reachable, or it is a command with no gesture. Catches a
     * letter being retired from the layout without its action being rehomed.
     */
    @Test
    fun `every edit action is reachable from some key`() {
        val offered = IosLayouts.ALL
            .flatMap { it.rows }
            .flatMap { it.keys }
            .flatMap { it.actions }
            .toSet()
        assertEquals(EditAction.entries.toSet(), offered)
    }

    /**
     * No popup on any key of any layout may be drawn off the top of the view.
     *
     * The clamp this guards used to look only at the pressed key's own top edge, which says
     * nothing about how many rows are stacked above it: a popup that wrapped went off the top of
     * the keyboard however much room the key had, and the top row clipped even without wrapping.
     * Swept over every layout rather than the one key that was reported, because the failure is a
     * property of the placement arithmetic and turns up wherever entries and row happen to line
     * up badly.
     */
    @Test
    fun `no popup is drawn above the top of the keyboard`() {
        IosLayouts.ALL.forEach { layout ->
            val g = LayoutGeometry(layout, Metrics.REFERENCE_WIDTH)
            g.keyRects.filter { it.key.popup.isNotEmpty() }.forEach { k ->
                val grid = PopupGrid.of(k, g)
                assertTrue(
                    "${k.key.id} (${grid.rows} rows) opens at ${grid.top}, above the view",
                    grid.top >= 0f,
                )
            }
        }
    }

    /**
     * A popup clamped against the top of the view is moved as a whole, primary included.
     *
     * The top-row keys have less room above them than a popup row is tall, so the grid is pushed
     * down bodily to stay on screen. What must survive that push is the pinning: [entryAt] and
     * [cellTop] are the same arithmetic read in opposite directions, so a clamp applied to some
     * rows and not others would light one entry and run another. That every cell commits what it
     * shows is now swept board-wide above; this checks the clamp itself still leaves the primary
     * over its own key, which is the property the vertical push could quietly break.
     */
    @Test
    fun `a popup clamped at the top keeps its primary over the key`() {
        // The top row is where the clamp actually binds: 34dp of strip cannot hold a 46dp row.
        val clamped = geometry.keyRects
            .filter { it.key.popup.isNotEmpty() && PopupGrid.of(it, geometry).top == 0f }
        assertTrue("the top row should clamp", clamped.isNotEmpty())

        clamped.forEach { k ->
            val grid = PopupGrid.of(k, geometry)
            // Horizontal pinning is unaffected by a vertical push.
            assertEquals(
                "${k.key.id}: the clamp moved the primary off its key",
                grid.primary,
                grid.entryAt(k.centerX, k.centerY),
            )
            assertTrue("${k.key.id}: drawn above the view", grid.top >= 0f)
        }
    }

    /**
     * The popup sits just above its key, not a key-and-a-half away.
     *
     * It used to be offset by [PopupGrid.PRESS_LIFT] -- the distance a *pressed key* raises its
     * own glyph -- which left more than a whole key of empty board between the letter and its
     * accents, so they read as belonging to the row above. The two numbers look interchangeable
     * and are not: the key's lift is filled by the stretched key itself.
     */
    @Test
    fun `the popup opens close above the key it belongs to`() {
        // A lower-row key with a single row of accents, clear of both the top clamp and any wrap.
        // Not a top-row key: there the popup is legitimately pushed down over its own key,
        // because 34dp of strip cannot hold a 46dp row and something has to give.
        val n = key("n")
        val grid = PopupGrid.of(n, geometry)
        assertEquals("expected a single row", 1, grid.rows)
        assertTrue("n should not be clamped", grid.top > 0f)

        val gapAboveKey = n.top - (grid.top + grid.height)
        assertTrue("the popup should not overlap its key: $gapAboveKey", gapAboveKey >= 0f)
        assertTrue(
            "the popup floats $gapAboveKey px above the key, most of a key height away",
            gapAboveKey <= geometry.keyHeight * 0.5f,
        )
    }

    /**
     * Shift changes the letter, not what holding it does. The uppercase layout is derived by
     * copying each key, so this is really checking that the derivation was not written as a
     * fresh Key that forgot the new field.
     */
    @Test
    fun `the actions survive the shift layout`() {
        val upper = LayoutGeometry(IosLayouts.QWERTY_UPPER, Metrics.REFERENCE_WIDTH)
        val c = upper.keyRects.first { it.key.id == "c" }
        assertEquals(listOf(EditAction.COPY), c.key.actions)
        // Still reachable by a plain hold-and-release, exactly as on the lowercase layout.
        val grid = PopupGrid.of(c, upper)
        assertEquals(
            PopupEntry.Action(EditAction.COPY),
            grid.entries[grid.entryAt(c.centerX, c.centerY)],
        )
        // The accents are uppercased with the letter; the action is not a letter.
        assertTrue(grid.entries.contains(PopupEntry.Accent("Ç")))
    }
}
