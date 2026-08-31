package com.offlinekeyboard.ime.autofill

import android.content.Context
import android.os.Build
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestion
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.inline.InlineContentView
import androidx.annotation.RequiresApi
import com.offlinekeyboard.ime.layout.Metrics

/**
 * The overlay that holds a password manager's chips, sitting exactly over the emoji strip.
 *
 * It covers the *tappable* part of the strip and no more. The few pixels below that -- the buffer
 * that catches presses aimed high at the top letter row and snaps them into it -- stay with
 * KeyboardView, because a chip swallowing them would turn a slightly high `p` into nothing at
 * all. See [Metrics.STRIP_TOUCH_FRACTION].
 */
@RequiresApi(Build.VERSION_CODES.R)
class InlineSuggestionStrip(context: Context) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        isHorizontalScrollBarEnabled = false
        addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
        visibility = GONE
    }

    /**
     * Sized from its own width, the way [com.offlinekeyboard.ime.view.KeyboardView] is, so the
     * overlay tracks the strip through a rotation without either being told about the other.
     */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = Metrics.stripTouchHeightPx(w.toFloat()).toInt()
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    /**
     * Puts a response's chips on screen. Returns whether anything is being shown.
     *
     * Inflation is asynchronous and finishes out of order, so each chip gets an empty slot in
     * the row first and fills it when it arrives. Without the slots a slow chip would land to
     * the right of a fast one and the manager's ranking -- which login it thinks you want --
     * would be quietly scrambled.
     */
    fun show(suggestions: List<InlineSuggestion>, keyboardWidthPx: Int): Boolean {
        clear()
        val chipHeight = InlineAutofill.chipHeightPx(keyboardWidthPx)
        if (suggestions.isEmpty() || chipHeight <= 0) return false

        val margin = Metrics.sideMarginPx(keyboardWidthPx.toFloat()).toInt()
        setPadding(margin, 0, margin, 0)
        val gap = margin

        suggestions.forEach { suggestion ->
            val slot = FrameLayout(context)
            row.addView(
                slot,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    chipHeight,
                ).apply { if (row.childCount > 0) marginStart = gap },
            )
            // WRAP_CONTENT width: the manager sizes the chip to its own text, within the bounds
            // the request already fixed. Height is pinned, because that is the one dimension the
            // strip cannot negotiate.
            val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, chipHeight)
            suggestion.inflate(context, size, context.mainExecutor) { view ->
                fill(slot, view)
            }
        }

        // The manager ranks its chips, most likely first, so a new field's suggestions have to
        // start at the left rather than wherever the last field was scrolled to.
        scrollX = 0
        visibility = VISIBLE
        return true
    }

    /**
     * Drops one inflated chip into the slot held for it, or drops the slot if the manager could
     * not render one -- an empty gap in the row reads as a chip that failed to load.
     */
    private fun fill(slot: FrameLayout, view: InlineContentView?) {
        // The response can outlive the field it was for: the strip may already have been cleared
        // by the time a chip finishes inflating, and re-attaching the slot would resurrect it.
        if (slot.parent !== row) return
        if (view == null) {
            row.removeView(slot)
            if (row.childCount == 0) clear()
            return
        }
        // The chip is another process's surface. Left to itself it composites *behind* this
        // window, where KeyboardView's opaque background hides it completely -- the keyboard
        // paints its whole canvas, strip included, so there is no hole for it to show through.
        view.setZOrderedOnTop(true)
        // Added with the layout params it arrived with, never with our own. Inflating at
        // WRAP_CONTENT hands the measuring to the manager's process, and the width it comes back
        // with is carried on the view -- an InlineContentView has no intrinsic size of its own to
        // fall back on, so replacing those params with another WRAP_CONTENT measures it to zero
        // and the chip is laid out, drawn, and completely invisible.
        slot.addView(view)
    }

    /** Takes the strip back. Releases every chip's surface with it. */
    fun clear() {
        row.removeAllViews()
        visibility = GONE
    }
}
