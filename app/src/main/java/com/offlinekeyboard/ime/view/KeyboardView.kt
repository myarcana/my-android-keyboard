package com.offlinekeyboard.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import com.offlinekeyboard.ime.candidates.UnifiedCandidates
import com.offlinekeyboard.ime.capture.TouchTrace
import com.offlinekeyboard.ime.gesture.GestureConfig
import com.offlinekeyboard.ime.gesture.GestureOutput
import com.offlinekeyboard.ime.gesture.GestureState
import com.offlinekeyboard.ime.gesture.PathPoint
import com.offlinekeyboard.ime.gesture.SquashDirection
import com.offlinekeyboard.ime.gesture.TouchFsm
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.Metrics
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.Layout
import com.offlinekeyboard.ime.layout.LayoutGeometry
import com.offlinekeyboard.ime.layout.PopupEntry
import com.offlinekeyboard.ime.layout.PopupGrid
import com.offlinekeyboard.ime.layout.PopupShape
import com.offlinekeyboard.ime.layout.Squash
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Palette sampled pixel-by-pixel from Gboard on the target device, so the keyboard sits in the
 * same visual language as the rest of the system rather than approximating it by eye.
 *
 * The dark values are estimates -- resample them against Gboard in dark mode when tuning.
 */
internal data class Theme(
    val background: Int,
    val key: Int,
    val specialKey: Int,
    val keyPressed: Int,
    val text: Int,
    val secondaryText: Int,
    val popup: Int,
    val popupSelected: Int,
    val glideTrail: Int,
) {
    companion object {
        val LIGHT = Theme(
            background = Color.parseColor("#ECEDFB"),
            key = Color.WHITE,
            specialKey = Color.parseColor("#E2DFFF"),
            keyPressed = Color.parseColor("#CFCBEE"),
            text = Color.parseColor("#181B25"),
            secondaryText = Color.parseColor("#6B6B7B"),
            popup = Color.WHITE,
            popupSelected = Color.parseColor("#6750A4"),
            glideTrail = Color.parseColor("#996750A4"),
        )
        val DARK = Theme(
            background = Color.parseColor("#1B1B1F"),
            key = Color.parseColor("#303034"),
            specialKey = Color.parseColor("#45464F"),
            keyPressed = Color.parseColor("#5A5A63"),
            text = Color.parseColor("#E5E1E6"),
            secondaryText = Color.parseColor("#A0A0AC"),
            popup = Color.parseColor("#303034"),
            popupSelected = Color.parseColor("#6750A4"),
            glideTrail = Color.parseColor("#99B0A0E8"),
        )
    }
}

/**
 * Which palette is in force, from the system's night mode.
 *
 * Free of the view on purpose: the autofill chips are styled to match these colours, and the
 * system asks for that styling before there is a KeyboardView to ask. See
 * [com.offlinekeyboard.ime.autofill.InlineAutofill].
 */
internal fun keyboardTheme(resources: Resources): Theme = if (
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
    Configuration.UI_MODE_NIGHT_YES
) Theme.DARK else Theme.LIGHT

/**
 * Draws the keyboard and turns touches into [GestureOutput]s.
 *
 * One [TouchFsm] per pointer, so two-thumb typing works: each finger runs its own independent
 * tap/flick/glide/trackpad state machine.
 */
private val ICON_KEYS = setOf(
    KeyType.SHIFT,
    KeyType.BACKSPACE,
    KeyType.GLOBE,
    KeyType.MIC,
    KeyType.RETURN,
)

/**
 * How quickly a released key's glyphs settle back, as an exponential time constant in
 * milliseconds.
 *
 * This is the *only* part of the flick that is animated on a clock. While the finger is down the
 * glyphs are pinned to it and move exactly as far as it does -- no easing, no lag, no duration --
 * because the symbol is meant to be the thing being dragged rather than a clip being played. A
 * timed curve is needed for one moment only: after the lift, when there is no finger left to
 * follow and the key has to put itself back together.
 */
private const val FLICK_SETTLE_MS = 40f

/**
 * How far a pressed key's contents stand above the key's own top edge, in key heights.
 *
 * The key does not get a popup; it becomes one. Pressing stretches the key itself up out of the
 * board and carries its contents to the top of the taller shape, which puts the glyph clear of
 * the thumb while leaving it attached to the key it belongs to -- the finger is holding the
 * bottom of the same object it is reading the top of.
 *
 * The popup does *not* use this number -- it has its own, smaller [PopupGrid.POPUP_GAP] -- and the
 * difference is the point. This lift is filled by the stretched key itself, so it reads as one
 * object however large it gets; a detached popup held the same distance away is separated from its
 * key by that much empty board, which is what used to put the accents nearer the row above than
 * the letter they belong to. Both numbers live in [PopupGrid] because the state machine hit-tests
 * the popup against the geometry they produce, and a renderer disagreeing with it would light one
 * entry and commit another.
 *
 * The change is instant in both directions, never eased. The rise is not a movement the key makes
 * -- it is the shape a key has while a finger is on it, and the finger arrives and leaves at a
 * definite moment. Growing into it would also lose the race on every fast tap: a tap can be over
 * in forty milliseconds, and a key still on its way up when the finger has gone has shown nothing.
 */
private const val PRESS_LIFT = PopupGrid.PRESS_LIFT

/**
 * How many suggestions fit across the strip.
 *
 * Eight rather than the ten-odd that a square cell used to produce, because the cell is now as
 * wide as the strip is tall only by coincidence. Eight over a 360dp board is a ~44dp cell, which
 * is wider than a letter key -- a comfortable target for something that, when mistapped,
 * replaces a whole word rather than a character.
 */
private const val CANDIDATE_COLUMNS = 8

/**
 * Chinese candidate text height, as a fraction of the strip.
 *
 * Larger than the emoji's share of the same band because Hanzi carry their meaning in strokes
 * that disappear at small sizes -- 情 and 晴 differ by one radical, and a bar too small to tell
 * them apart is a bar that has to be read twice.
 */
private const val CHINESE_CANDIDATE_TEXT = 0.46f

/** Space either side of a candidate, as a fraction of the strip height. */
private const val CHINESE_CANDIDATE_PADDING = 0.22f

/**
 * Emoji height on a strip that also holds Chinese, as a fraction of it.
 *
 * Larger than [CHINESE_CANDIDATE_TEXT] because a glyph has no ascenders or descenders to spare:
 * text set at 0.46 of the band fills it, while an emoji at the same nominal size looks small
 * beside it. Sized so the two read as the same visual weight rather than the same number.
 */
private const val EMOJI_CANDIDATE_TEXT = 0.60f

class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onOutput: (List<GestureOutput>) -> Unit = {}

    /** Tapped a suggestion. The index is into [candidates]. */
    var onCandidate: (Int) -> Unit = {}

    /**
     * What the suggestion strip shows: emoji in English, Chinese candidates in the CJK modes.
     * Never English words -- see [com.offlinekeyboard.ime.candidates.EmojiIndex].
     */
    /**
     * Replaces the suggestions while dictation is running. The strip is the only spare row on a
     * keyboard, and a microphone that is listening has to say so somewhere the eye already is.
     */
    var status: String? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Set while a password manager's chips are occupying the strip.
     *
     * The chips are real Views from another process, so they cannot be drawn on this canvas and
     * live in an overlay above it instead. This flag is how the two stay out of each other's
     * way: the emoji are neither drawn nor tappable underneath the thing covering them. Only the
     * overlay's own band is handed over -- see [Metrics.STRIP_OVERLAY_FRACTION] -- so a press
     * aimed high at the top letter row still snaps to the letter.
     */
    var stripHandedOver: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            pressedCandidate = -1
            invalidate()
        }

    var candidates: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            pressedCandidate = -1
            invalidateSlots()
            invalidate()
        }

    /**
     * What each suggestion is, parallel to [candidates]; empty when they are all alike.
     *
     * The English bar now holds emoji and Chinese at once, and the two cannot share a column
     * width: an emoji is one square glyph and 牛肉麵 is three characters of text. Empty means the
     * old uniform behaviour -- every entry an emoji in English, every entry Chinese in the CJK
     * modes -- which is still what [chineseMode] and the default bar produce.
     */
    var candidateKinds: List<UnifiedCandidates.Kind> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            invalidateSlots()
            invalidate()
        }

    /**
     * True when the strip holds text candidates and must measure each one to place it.
     *
     * The question is "is there text on this bar", not "is there more than one kind on it".
     * Asking the narrower question was a bug with a visible symptom: as letters were added the
     * emoji stopped matching, the bar became all-Chinese, "more than one kind" went false, and a
     * strip of Chinese text fell into the emoji's fixed 8-column grid -- which is sized for one
     * square glyph, so 牛肉麵 was drawn crushed into a 44dp cell with the rest of the bar empty.
     * The variable-width layout is the right rendering for Chinese whether or not an emoji
     * happens to be standing next to it, so that is the condition, and the transition from mixed
     * to Chinese-only now changes nothing about how the Chinese is drawn.
     *
     * [chineseMode] does not need to be tested alongside this: the CJK bar leaves
     * [candidateKinds] empty, so the kinds are absent rather than uniform, and it is
     * `chineseMode || textStrip` at the two call sites that selects the text renderer.
     */
    private val textStrip: Boolean
        get() = candidateKinds.size == candidates.size &&
            candidateKinds.any { it != UnifiedCandidates.Kind.EMOJI }

    /**
     * Whether the strip is showing Chinese candidates rather than emoji.
     *
     * The two are drawn differently and have to be: an emoji is one square glyph that fits a
     * fixed column, while a candidate is one to several characters of text whose width nobody
     * can know in advance. Sharing the emoji's fixed grid would clip 今天天气很好 to its first
     * character and leave the rest of the bar empty.
     */
    var chineseMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            pressedCandidate = -1
            invalidateSlots()
            invalidate()
        }

    var layout: Layout = IosLayouts.QWERTY_LOWER
        set(value) {
            field = value
            geometry = null
            requestLayout()
            invalidate()
        }

    /**
     * How far the board is squashed to one side, for one-handed reach.
     *
     * Kept here rather than in the service because it is a property of this view's geometry and
     * nothing else: it survives a plane switch, a shift, and a language change, all of which
     * replace [layout], and none of which should move the keys back under a hand that asked for
     * them to be over here. The service is told when it changes only so it can save it.
     */
    var squash: Squash = Squash.NONE
        set(value) {
            if (field == value) return
            field = value
            geometry = null
            // No requestLayout: the squash changes no *measurement* of this view, only where the
            // keys sit inside it. Asking for a layout pass would resize the IME window for a
            // height that has not changed.
            invalidate()
        }

    /** Told when a space-bar flick changed [squash], so the service can persist it. */
    var onSquashChanged: (Squash) -> Unit = {}

    private var geometry: LayoutGeometry? = null
    private val config = GestureConfig()
    private val uiHandler = Handler(Looper.getMainLooper())

    private val pointers = mutableMapOf<Int, TouchFsm>()

    /**
     * A glide whose finger has lifted but which is not finished yet.
     *
     * It sits outside [pointers] because it belongs to no pointer any more: the finger that made
     * it is gone, and the one that may continue it has not arrived. Keeping the state machine
     * itself alive rather than a copy of its path is what lets the finger simply carry on --
     * there is nothing to restore, and the gesture the bank records is one gesture.
     */
    private var suspendedGlide: TouchFsm? = null
    private var glideResumeRunnable: Runnable? = null
    private val longPressRunnables = mutableMapOf<Int, Runnable>()

    /**
     * Pointers that were swallowed by an active trackpad rather than starting their own
     * gesture, so their eventual UP does not type anything.
     */
    private val consumedPointers = mutableSetOf<Int>()

    private var highlightedKeyId: String? = null

    /**
     * Keys that are raised or mid-flick, by key id -- rather than by pointer, so a key still
     * settling back after the finger has lifted keeps its place with no pointer left to hold it.
     */
    private val motions = mutableMapOf<String, KeyMotion>()
    private var lastFrameNanos = 0L
    /**
     * The key whose popup is open, and which cell is lit.
     *
     * The entries are not kept here: they are derived from the key by [PopupGrid], which is the
     * same call the state machine hit-tests against. Holding a copy would be a second version of
     * the popup that could disagree with the one deciding what a release commits.
     */
    private var accentPopup: Pair<KeyRect, Int>? = null

    private var glidePath: List<PathPoint> = emptyList()
    private var trackpadActive = false
    private var selecting = false

    /** Index of the suggestion under a finger, for the pressed highlight. */
    private var pressedCandidate = -1
    /** Pointers that went down on the strip, so their UP commits a suggestion, not a key. */
    private val candidatePointers = mutableMapOf<Int, Int>()

    /**
     * Space reserved below the keys for the system navigation bar -- reserved *unconditionally*,
     * whether or not a navigation bar is currently there.
     *
     * From targetSdk 35 the IME window is laid out edge to edge, so without this the bottom row
     * sits underneath the nav buttons. The obvious implementation reserves only what the bar
     * actually covers right now, and that is what this used to do: measure how far the view's
     * bottom edge reaches past the top of the nav bar, falling back to the dispatched inset.
     *
     * The problem is that the keys are laid out from the *top* of this view while the window is
     * anchored to its *bottom*. Total height is `keyboardHeight + navBarInset`, so every pixel
     * of reserve pushes the whole key grid up by that much. A reserve that varies -- 0 under
     * gesture navigation, ~48dp under three-button, zero-then-corrected across the first layout,
     * and whatever a misreporting host like Firefox claims -- moves every letter on the screen
     * with it. Keys landing in a different absolute position depending on which app is being
     * typed into defeats the muscle memory the layout exists to serve, and it silently
     * invalidates the tap model: [com.offlinekeyboard.ime.tap.SpatialModel] scores a touch
     * against key centres, so a grid shifted underneath the finger biases every correction.
     *
     * So the reserve is the display's navigation-bar height *ignoring visibility* -- a constant
     * for the device in its current orientation, independent of host, of gesture-versus-button
     * navigation, and of when in the layout pass it is asked. Under gesture navigation this
     * spends a small strip of empty space below the keys; that is the price of the keys being
     * nailed to one position, which is what was asked for.
     */
    private var navBarInset = 0

    /**
     * What the host last said the navigation-bar inset was.
     *
     * Only a fallback, for API < 30 and for the case where the display's metrics are
     * unavailable. Above that it is deliberately *not* consulted: it is the per-host,
     * time-varying number whose use made the keys move. See [navBarInset].
     */
    private var dispatchedNavInset = 0

    private val theme: Theme get() = keyboardTheme(resources)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /**
     * Measurement only, never drawn with.
     *
     * The Chinese candidate slots are measured from the touch path as well as the draw path, and
     * a paint carries its text size until something changes it -- so measuring with [label]
     * would leave the strip's text size on it after a tap, to be inherited by whatever drew
     * next. Keeping a second paint is cheaper than the discipline of restoring the first.
     */
    private val measure = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The language menu's left-aligned text.
     *
     * A [TextPaint] rather than a [Paint] because [TextUtils.ellipsize] takes one, and its own
     * paint rather than [label] for the reason [measure] has one: [label] is centred and shared
     * with every key face, so a popup that flipped it to LEFT and forgot to flip it back would
     * shift the letters on the whole board. Owning the alignment here makes that unrepresentable
     * instead of a discipline.
     */
    private val menuLabel = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun geometry(): LayoutGeometry =
        geometry ?: LayoutGeometry(layout, width.toFloat(), squash = squash).also { geometry = it }

    /** Where the keys are, for the service's glide decoding. */
    val currentGeometry: LayoutGeometry get() = geometry()

    /** Bottom of the key area, above the reserved navigation-bar space. */
    private val keyAreaBottom: Float get() = (height - navBarInset).toFloat()

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        dispatchedNavInset = WindowInsetsCompat.toWindowInsetsCompat(insets)
            .getInsets(WindowInsetsCompat.Type.navigationBars())
            .bottom
        updateNavBarInset()
        return super.onApplyWindowInsets(insets)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The only moment the view's position on screen is real. A host that never dispatches a
        // change of insets still gets checked here, because being shown over a new app is
        // itself a layout.
        updateNavBarInset()
    }

    private fun updateNavBarInset() {
        val wanted = requiredNavBarInset()
        if (wanted != navBarInset) {
            navBarInset = wanted
            requestLayout()
        }
    }

    /**
     * How much space to reserve below the keys for the navigation bar. See [navBarInset].
     *
     * Deliberately a property of the *display*, not of this view's current position or of the
     * host's reported insets: `getInsetsIgnoringVisibility` answers how tall the bar is when
     * shown, even while it is hidden, so the answer does not change when the bar comes and goes
     * or when a host lies about it. Nothing here reads [height] or the on-screen location, which
     * is what keeps the result identical on every layout pass, including the first.
     */
    private fun requiredNavBarInset(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return dispatchedNavInset
        val display = context.getSystemService(WindowManager::class.java)
            ?.maximumWindowMetrics ?: return dispatchedNavInset
        return WindowInsetsCompat.toWindowInsetsCompat(display.windowInsets)
            .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            .bottom
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = Metrics.HEIGHT_IN_KEY_WIDTHS *
            (w / Metrics.REFERENCE_WIDTH * Metrics.KEY_WIDTH)
        // Keys occupy the top; the inset is empty space reserved below them, which keeps
        // drawing and touch coordinates identical (both measured from the top).
        setMeasuredDimension(w, h.toInt() + navBarInset)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        geometry = null
    }

    // --- drawing --------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val g = geometry()
        val t = theme
        advanceMotions()
        canvas.drawColor(t.background)

        if (trackpadActive) {
            drawTrackpadHint(canvas, g, t)
            return
        }

        val radius = g.cornerRadius
        drawCandidates(canvas, g, t, radius)
        // Raised keys last: one grows into the row above it, and a neighbour drawn afterwards
        // would paint over the key standing in front of it.
        g.keyRects.forEach { rect -> if (pressOf(rect) == 0f) drawKey(canvas, rect, radius, t, g) }
        g.keyRects.forEach { rect -> if (pressOf(rect) > 0f) drawKey(canvas, rect, radius, t, g) }

        if (glidePath.size > 1) drawGlideTrail(canvas, g, t)
        accentPopup?.let { (anchor, selected) ->
            drawAccentPopup(canvas, g, t, anchor, selected, radius)
        }
    }

    /**
     * The suggestion strip. Cells are a fixed width rather than stretched to fill the space the
     * current suggestions happen to need: a suggestion must not jump sideways as the list behind
     * it grows or shrinks with each letter typed, because it is being aimed at while it changes.
     */
    private fun drawCandidates(canvas: Canvas, g: LayoutGeometry, t: Theme, radius: Float) {
        if (stripHandedOver) return
        status?.let { message ->
            label.color = t.secondaryText
            label.textSize = g.stripHeight * 0.34f
            canvas.drawText(
                message,
                width / 2f,
                g.stripHeight / 2f - (label.descent() + label.ascent()) / 2f,
                label,
            )
            return
        }
        if (candidates.isEmpty()) return
        if (chineseMode || textStrip) return drawTextCandidates(canvas, g, t, radius)
        val cell = candidateCellWidth(g)
        // The emoji use the whole strip, evenly. They used to sit in its top 10%-70%, which put a
        // thin gap above them and a large empty one below: the lower part was a dead band that
        // refused taps, and drawing into it would have advertised a target that did nothing. That
        // band is gone -- a high press is now identified by how far above a thumb's measured
        // landing point it sits, not by which slice of the bar it is in -- so the padding can be
        // symmetric and the glyphs can have the height back.
        val inset = g.stripHeight * 0.12f
        val top = inset
        val bottom = g.stripHeight - inset
        // Sized off the cell as well as the band, so a glyph can never be wider than the cell it
        // sits in however the two are retuned.
        label.textSize = minOf((bottom - top) * 0.80f, cell * 0.62f)
        candidates.take(CANDIDATE_COLUMNS).forEachIndexed { i, candidate ->
            val left = g.stripMargin + i * cell
            if (i == pressedCandidate) {
                fill.color = t.keyPressed
                canvas.drawRoundRect(
                    RectF(left + cell * 0.06f, top, left + cell * 0.94f, bottom),
                    radius,
                    radius,
                    fill,
                )
            }
            label.color = t.text
            canvas.drawText(
                candidate,
                left + cell / 2f,
                (top + bottom) / 2f - (label.descent() + label.ascent()) / 2f,
                label,
            )
        }
    }

    /**
     * Where each text candidate sits: a left edge and a width, in view pixels.
     *
     * Variable width, unlike the emoji grid, because a candidate is text: 好 and 今天天气很好
     * cannot share a column size without either clipping the long one or stranding the short one
     * in whitespace. Computed in one place and used by both the drawing and the hit test, so the
     * thing under the finger is always the thing that was drawn -- splitting that calculation in
     * two is how a bar comes to commit the candidate beside the one that was tapped.
     *
     * Candidates that do not fit are dropped rather than scrolled. A bar that scrolls invites a
     * horizontal drag, and this strip already belongs to the glide and trackpad gestures.
     *
     * Measuring needs a text size set on a paint, and this runs from the hit test as well as
     * from `onDraw` -- so it uses [measure], its own paint, rather than borrowing `label`.
     * Sharing `label` would leave a text size behind on a touch, and the next thing to draw with
     * it would silently come out at the candidate bar's size.
     */
    private fun textCandidateSlots(g: LayoutGeometry): List<Slot> {
        // Measuring is the expensive half, and this runs from ACTION_MOVE -- which is nine tenths
        // of the touch events -- so the answer is cached rather than recomputed per call. The
        // inputs are the candidate list, the kinds, and the strip metrics, so the cache is
        // dropped by the setters for the first two (see [invalidateSlots]) and keyed on the last:
        // a rotation or a height change reaches here without passing through any setter.
        cachedSlots?.let { if (it.width == width && it.stripHeight == g.stripHeight) return it.slots }

        val slots = ArrayList<Slot>(candidates.size)
        val padding = g.stripHeight * CHINESE_CANDIDATE_PADDING
        val available = width - g.stripMargin * 2f
        var x = g.stripMargin
        for ((i, candidate) in candidates.withIndex()) {
            // An emoji on a mixed strip is measured at its own size, not the text size: the two
            // are drawn at different sizes (a glyph reads badly at text height) and measuring
            // one while drawing the other is how a bar comes to commit the neighbour of what was
            // tapped.
            measure.textSize = g.stripHeight * textScaleFor(i)
            val cellWidth = measure.measureText(candidate) + padding * 2f
            if (x - g.stripMargin + cellWidth > available) break
            slots.add(Slot(x, cellWidth))
            x += cellWidth
        }
        cachedSlots = CachedSlots(width, g.stripHeight, slots)
        return slots
    }

    /** One candidate's place on the strip: a left edge and a width, in view pixels. */
    private class Slot(val left: Float, val width: Float)

    private class CachedSlots(
        val width: Int,
        val stripHeight: Float,
        val slots: List<Slot>,
    )

    private var cachedSlots: CachedSlots? = null

    /** Drops the measured slots, for when the thing they were measured from changed. */
    private fun invalidateSlots() {
        cachedSlots = null
    }

    /** The text size, as a fraction of strip height, for the suggestion at [index]. */
    private fun textScaleFor(index: Int): Float =
        if (candidateKinds.getOrNull(index) == UnifiedCandidates.Kind.EMOJI) {
            EMOJI_CANDIDATE_TEXT
        } else {
            CHINESE_CANDIDATE_TEXT
        }

    /**
     * The strip wherever it holds text: candidates as text, each as wide as it needs to be.
     *
     * The one renderer for Chinese candidates, in the CJK modes and on the English bar alike,
     * mixed with emoji or not. There is no second layout to disagree with it.
     */
    private fun drawTextCandidates(
        canvas: Canvas,
        g: LayoutGeometry,
        t: Theme,
        radius: Float,
    ) {
        val inset = g.stripHeight * 0.12f
        val top = inset
        val bottom = g.stripHeight - inset
        val slots = textCandidateSlots(g)
        slots.forEachIndexed { i, slot ->
            val left = slot.left
            val cellWidth = slot.width
            label.textSize = g.stripHeight * textScaleFor(i)
            if (i == pressedCandidate) {
                fill.color = t.keyPressed
                canvas.drawRoundRect(
                    RectF(left + cellWidth * 0.04f, top, left + cellWidth * 0.96f, bottom),
                    radius,
                    radius,
                    fill,
                )
            }
            // The first candidate is what space commits, so it is the one the eye should land
            // on; the rest are alternatives and are drawn a shade back. An emoji is drawn at
            // full strength wherever it sits: dimming a glyph reads as "unavailable" rather than
            // as "second choice", which is what the shade means for text.
            label.color = when {
                candidateKinds.getOrNull(i) == UnifiedCandidates.Kind.EMOJI -> t.text
                i == 0 -> t.text
                else -> t.secondaryText
            }
            canvas.drawText(
                candidates[i],
                left + cellWidth / 2f,
                (top + bottom) / 2f - (label.descent() + label.ascent()) / 2f,
                label,
            )
        }
    }

    /**
     * Progress at which the letter has faded out entirely -- which is exactly the point the flick
     * arms at, so the fade is not decoration. A key showing no letter is a key that will type its
     * symbol if you let go, and one showing a letter again is one that will type the letter. The
     * commit point can then be felt without being explained, and the two cannot drift apart
     * because both come from the same pair of ratios.
     *
     * Since the flick threshold came down to three pixels this is very early -- around 5% of the
     * pull -- so the letter goes almost the moment the thumb does. That is the intended reading
     * rather than a regression: the commit point moved, and this fade is only ever a report of
     * where it is. Decoupling them to keep a more leisurely fade would buy a prettier animation
     * by lying about what releasing would type.
     */
    private val letterGoneAt: Float
        get() = config.flickDistanceRatio / config.flickTravelRatio

    /**
     * How many suggestions the strip holds across. Fixed at a count rather than derived from a
     * square cell, which is what it used to be: the cell was the strip's own height, so shortening
     * the strip silently widened the row to ten-plus cramped cells. The number of emoji on offer
     * is a thing to decide, not a side effect of how tall the bar is.
     */
    private fun candidateCellWidth(g: LayoutGeometry): Float =
        (width - 2 * g.stripMargin) / CANDIDATE_COLUMNS

    /** Which suggestion a touch landed on, or -1 for none. */
    private fun candidateAt(x: Float, y: Float, g: LayoutGeometry): Int {
        // The strip reaches its full height now. What keeps a press aimed high at q-p off it is
        // not a dead band at the bottom of the bar but the same test keyForPress uses, so the
        // two cannot disagree about who owns a touch.
        if (y >= g.stripHeight || g.isLetterReach(x, y)) return -1
        if (candidates.isEmpty() || status != null) return -1
        if (stripHandedOver) return -1
        if (chineseMode || textStrip) {
            // The same slots the drawing used, so the candidate under the finger is the one on
            // screen. Widths vary per candidate, so there is no arithmetic shortcut here.
            return textCandidateSlots(g)
                .indexOfFirst { x >= it.left && x < it.left + it.width }
        }
        val i = ((x - g.stripMargin) / candidateCellWidth(g)).toInt()
        // Bounded by the list as well as the row: the cells are a fixed grid now, so the ones
        // past the end of a short list are empty and a tap there must land on nothing.
        return if (i in 0 until minOf(candidates.size, CANDIDATE_COLUMNS)) i else -1
    }

    private fun drawKey(canvas: Canvas, rect: KeyRect, radius: Float, t: Theme, g: LayoutGeometry) {
        val isSpecial = rect.key.type != KeyType.CHARACTER && rect.key.type != KeyType.SPACE
        val motion = motions[rect.key.id]
        val press = motion?.press ?: 0f
        // How far this key has grown up out of the board, in pixels. 0 for a key nobody is on,
        // which is what makes everything below reduce to the resting keyboard.
        val lift = press * liftAbove(rect, g)
        val base = if (isSpecial) t.specialKey else t.key
        // A key that rises takes its colour from the rise, and gives it back on the way down, so
        // the two are one movement. The keys that do not rise -- shift, backspace, the space bar
        // -- still tint, from the highlight alone.
        val tint = if (press == 0f && rect.key.id == highlightedKeyId) 1f else press
        fill.color = blend(base, t.keyPressed, tint)
        canvas.drawRoundRect(
            RectF(rect.left, rect.top - lift, rect.right, rect.bottom),
            radius,
            radius,
            fill,
        )

        if (rect.key.type in ICON_KEYS) {
            icon.color = t.text
            icon.strokeWidth = g.keyUnit * 0.055f
            KeyIcons.draw(canvas, rect.key.type, rect.centerX, rect.centerY, g.keyUnit * 0.46f, icon)
            return
        }

        val text = rect.key.primary
        val secondary = rect.key.secondary
        if (text.isBlank() && secondary == null) return

        // How far this key is through the flick, 0 at rest.
        val flick = motion?.pull ?: 0f
        // Everything from here is drawn in the key's own coordinates and then carried up bodily
        // with the rise. The flick therefore plays out wherever the contents now are, in the top
        // of the raised key, without either animation knowing about the other: one moves the
        // glyphs within the key, the other moves the key.
        if (lift > 0f) {
            canvas.save()
            canvas.translate(0f, -lift)
        }
        // The departing letter leaves through the bottom of the key's own outline -- the raised
        // contents, not the stretched body, so it goes at the same place on the key it always
        // did and the stem below is left clear.
        if (flick > 0f) {
            canvas.save()
            canvas.clipRect(rect.left, rect.top, rect.right, rect.bottom)
        }

        val primarySize = if (rect.key.type == KeyType.CHARACTER) {
            g.keyUnit * 0.62f
        } else {
            g.keyUnit * 0.42f
        }
        label.textSize = primarySize
        // A key with a flick secondary above it carries its primary low; a key without one
        // centres it.
        val primaryBaseline = if (secondary != null) {
            rect.centerY + g.keyHeight * 0.24f
        } else {
            rect.centerY - (label.descent() + label.ascent()) / 2f
        }

        if (text.isNotBlank()) {
            label.color = t.text
            label.textSize = primarySize * (1f - 0.26f * flick)
            label.alpha = (255f * (1f - flick / letterGoneAt).coerceIn(0f, 1f)).toInt()
            canvas.drawText(
                text,
                rect.centerX,
                primaryBaseline + g.keyHeight * 0.46f * flick,
                label,
            )
            label.alpha = 255
        }

        // The iPadOS flick secondary: small, grey and tucked above the primary at rest, and it
        // slides down into the primary's own place -- position, size and colour -- as the finger
        // pulls it there. The letter is on its way out underneath it, which is what makes the
        // gesture legible before it commits: the key is visibly becoming the symbol.
        secondary?.let {
            label.color = blend(t.secondaryText, t.text, flick)
            label.textSize = g.keyUnit * (0.30f + 0.32f * flick)
            val restBaseline = rect.top + g.keyHeight * 0.28f
            canvas.drawText(
                it,
                rect.centerX,
                restBaseline + (primaryBaseline - restBaseline) * flick,
                label,
            )
        }

        if (flick > 0f) canvas.restore()
        if (lift > 0f) canvas.restore()
    }

    private fun drawGlideTrail(canvas: Canvas, g: LayoutGeometry, t: Theme) {
        trail.color = t.glideTrail
        trail.strokeWidth = g.keyUnit * 0.18f
        val path = Path().apply {
            moveTo(glidePath.first().x, glidePath.first().y)
            glidePath.drop(1).forEach { lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, trail)
    }

    private fun drawAccentPopup(
        canvas: Canvas,
        g: LayoutGeometry,
        t: Theme,
        anchor: KeyRect,
        selected: Int,
        radius: Float,
    ) {
        // Built by the same function the state machine hit-tests against, so what is lit is what
        // releasing commits. See PopupGrid.
        val grid = PopupGrid.of(anchor, g)
        val entries = grid.entries

        fill.color = t.popup

        // A menu that is a single unbroken column is drawn as one panel rather than as a stack
        // of rounded rows: per-row drawing would round every row's corners and leave seams down
        // the list, which reads as a pile of separate buttons, where the language menu is one
        // object you run a thumb down.
        //
        // Only when there is no padding to work around. A menu forced into several columns by a
        // long list has blank cells in its first row, and those must stay holes -- the per-row
        // path below is what knows how to leave them empty.
        val solid = grid.shape == PopupShape.COLUMN && entries.none { it == PopupEntry.Blank }
        if (solid) {
            canvas.drawRoundRect(
                RectF(grid.left, grid.top, grid.left + grid.width, grid.top + grid.height),
                radius * 2,
                radius * 2,
                fill,
            )
        }

        // Drawn per row, and only across the cells that hold something: a padded top row would
        // otherwise show a panel of empty popup floating beside its entries.
        for (row in 0 until if (solid) 0 else grid.rows) {
            val first = row * grid.columns
            val last = minOf(first + grid.columns, entries.size) - 1
            val realFirst = (first..last).firstOrNull { entries[it] != PopupEntry.Blank } ?: continue
            val realLast = (first..last).last { entries[it] != PopupEntry.Blank }
            canvas.drawRoundRect(
                RectF(
                    grid.cellLeft(realFirst),
                    grid.cellTop(realFirst),
                    grid.cellLeft(realLast) + grid.cellWidth,
                    grid.cellTop(realLast) + grid.cellHeight,
                ),
                radius * 2,
                radius * 2,
                fill,
            )
        }

        entries.forEachIndexed { i, entry ->
            if (entry == PopupEntry.Blank) return@forEachIndexed
            val cellLeft = grid.cellLeft(i)
            val cellTop = grid.cellTop(i)
            val cx = cellLeft + grid.cellWidth / 2f
            val cy = cellTop + grid.cellHeight / 2f
            if (i == selected) {
                fill.color = t.popupSelected
                canvas.drawRoundRect(
                    RectF(cellLeft, cellTop, cellLeft + grid.cellWidth, cellTop + grid.cellHeight),
                    radius * 2,
                    radius * 2,
                    fill,
                )
            }
            val ink = if (i == selected) Color.WHITE else t.text
            when (entry) {
                is PopupEntry.Accent -> {
                    label.color = ink
                    label.textSize = g.keyUnit * 0.6f
                    canvas.drawText(
                        entry.text,
                        cx,
                        cy - (label.descent() + label.ascent()) / 2f,
                        label,
                    )
                }
                is PopupEntry.Action -> {
                    icon.color = ink
                    icon.strokeWidth = g.keyUnit * 0.06f
                    KeyIcons.drawAction(canvas, entry.action, cx, cy, g.keyUnit * 0.52f, icon)
                }
                is PopupEntry.Language -> drawLanguageCell(
                    canvas, g, entry, ink, cellLeft, cy, grid.cellWidth, grid.cellHeight,
                )
                // Filtered out above; the branch is here so adding an entry kind is a compile
                // error rather than an invisible cell.
                PopupEntry.Blank -> Unit
            }
        }
    }

    /**
     * One language in the globe key's menu: its name, and a tick if it is the one in use.
     *
     * Left-aligned rather than centred, which is the one place this popup departs from the accent
     * one. A column of centred names has a ragged edge on both sides and reads as a pile of
     * unrelated words; aligning them gives the list a spine, and the eye finds the one it wants by
     * running down a single edge. It is also what every language list on the phone does.
     *
     * The text is ellipsised to the cell rather than the cell being sized to the text, because
     * the cell's width is geometry -- the state machine hit-tests against it and has no font.
     * See [PopupGrid.COLUMN_WIDTH_UNITS].
     */
    private fun drawLanguageCell(
        canvas: Canvas,
        g: LayoutGeometry,
        entry: PopupEntry.Language,
        ink: Int,
        cellLeft: Float,
        centerY: Float,
        cellWidth: Float,
        cellHeight: Float,
    ) {
        val padding = g.keyUnit * 0.30f
        // The tick's column is reserved whether or not this row has one, so every name in the
        // menu starts at the same x and the list keeps its spine.
        val tickWidth = g.keyUnit * 0.42f
        val textLeft = cellLeft + padding + tickWidth
        val available = cellWidth - padding * 2 - tickWidth

        menuLabel.color = ink
        // Sized against the row rather than the key: the rows compress when many languages are
        // enabled, and text that ignored that would overflow its own cell.
        menuLabel.textSize = min(g.keyUnit * 0.46f, cellHeight * 0.44f)
        val text = TextUtils.ellipsize(
            entry.label,
            menuLabel,
            available,
            TextUtils.TruncateAt.END,
        ).toString()
        canvas.drawText(
            text,
            textLeft,
            centerY - (menuLabel.descent() + menuLabel.ascent()) / 2f,
            menuLabel,
        )

        if (!entry.current) return
        icon.color = ink
        icon.strokeWidth = g.keyUnit * 0.055f
        val tickX = cellLeft + padding + tickWidth / 2f
        val r = tickWidth * 0.26f
        val check = Path().apply {
            moveTo(tickX - r, centerY)
            lineTo(tickX - r * 0.2f, centerY + r * 0.8f)
            lineTo(tickX + r, centerY - r * 0.8f)
        }
        canvas.drawPath(check, icon)
    }

    private fun drawTrackpadHint(canvas: Canvas, g: LayoutGeometry, t: Theme) {
        label.color = t.secondaryText
        label.textSize = g.keyUnit * 0.5f
        canvas.drawText(
            if (selecting) "◀  select  ▶" else "←   ↑   ↓   →",
            width / 2f,
            keyAreaBottom / 2f,
            label,
        )
    }

    // --- the press and the iPadOS flick ----------------------------------------------------

    /**
     * What a finger is doing to one key: how far it has raised it, how far it has pulled its
     * glyphs, and whether it is still there.
     *
     * The two live together because they are one finger's effect on one key, and because a lifted
     * finger ends both at once. Neither is eased: [press] is a fact about whether a finger is on
     * the key, and [pull] is a distance the thumb has dragged. The only thing on a clock here is
     * a released [pull] finding its way home, and it takes the entry's lifetime with it.
     */
    private class KeyMotion {
        /** 1 while a finger is on the key, 0 otherwise. Never anything in between. */
        var press = 0f
        var pull = 0f

        /**
         * True while a finger is on the key. A held key is not animating -- it is being moved --
         * so it neither eases toward anything nor asks for frames of its own.
         */
        var held = false
    }

    private fun pressOf(rect: KeyRect): Float = motions[rect.key.id]?.press ?: 0f

    /**
     * Puts every key exactly where the fingers currently have it.
     *
     * Both halves come from the state machines rather than from the raw coordinates here, because
     * the rules -- which key a pointer owns, what counts as a flick in progress, when a press has
     * stopped being a press -- already live there, and a second copy of them in the renderer
     * would be a second thing to keep in step.
     *
     * A key stands up while its finger is still making a keypress, which is to say in PRESSED or
     * FLICK. The moment the gesture becomes a glide, the trackpad, an accent popup or a backspace
     * repeat, the finger has gone somewhere the key cannot follow, and the key sits back down
     * while the gesture carries on. Character keys only: the space bar has nothing to lift and
     * shift and backspace would be raising an icon over nothing.
     *
     * Assigning [KeyMotion.pull] directly, rather than easing toward it, is the point of the
     * flick: the symbol travels the same number of pixels the thumb does, so a slow pull is slow,
     * a fast one is fast, and a pull that turns round comes back up under the finger that is
     * lifting it. Every key with no finger on it is released to settle home, which covers the
     * lift, a cancel, and a flick escaping into a glide without any of the three being handled
     * separately.
     */
    private fun syncPressAnimations() {
        var changed = false
        motions.values.forEach {
            if (it.held) {
                it.held = false
                // The rise has no clock in either direction. A key is up because a finger is on
                // it, so it comes down when that stops being true and not a moment afterwards --
                // easing it down would be the renderer inventing a state the hand is not in.
                it.press = 0f
                changed = true
            }
        }
        pointers.values.forEach { fsm ->
            if (fsm.state != GestureState.PRESSED && fsm.state != GestureState.FLICK) {
                return@forEach
            }
            val rect = keyRectOf(fsm.originKeyId) ?: return@forEach
            if (rect.key.type != KeyType.CHARACTER) return@forEach
            val motion = motions.getOrPut(rect.key.id) { KeyMotion() }
            val pull = fsm.flickProgress
            if (motion.pull != pull || motion.press != 1f || !motion.held) changed = true
            motion.press = 1f
            motion.pull = pull
            motion.held = true
        }
        if (changed) invalidate()
    }

    /**
     * Settles every released key's glyphs one frame further home, and asks for another frame while
     * any is still moving. Keys under a finger are skipped: [syncPressAnimations] is already
     * placing those, and a touch event repaints them.
     *
     * Only the flick's pull is here. The rise is not animated at all, so a key that was let go
     * is already down by the time this runs, and what is left settling is the symbol finding its
     * slot inside it.
     *
     * Exponential rather than a fixed-duration tween because a flick can be let go from anywhere
     * -- fully pulled, or a tenth of the way down after a change of mind -- and the return should
     * take its length from how far there is to go. It also cannot overshoot: an iPadOS key slides
     * back, it does not bounce.
     */
    private fun advanceMotions() {
        if (motions.isEmpty()) return
        val now = System.nanoTime()
        // A dropped frame must not teleport the glyphs, and the first frame after a lift has no
        // previous one to measure from.
        val dtMs =
            if (lastFrameNanos == 0L) 0f
            else ((now - lastFrameNanos) / 1_000_000f).coerceIn(0f, 64f)
        lastFrameNanos = now
        val step = 1f - exp(-dtMs / FLICK_SETTLE_MS)

        var settling = false
        val entries = motions.entries.iterator()
        while (entries.hasNext()) {
            val motion = entries.next().value
            if (motion.held) continue
            motion.pull -= motion.pull * step
            // An exponential only ever approaches zero, so it is landed by hand -- otherwise a key
            // at rest would keep asking for frames forever.
            if (motion.pull < 0.004f) motion.pull = 0f
            if (motion.pull == 0f) entries.remove() else settling = true
        }

        if (settling) postInvalidateOnAnimation() else lastFrameNanos = 0L
    }

    /**
     * How far above its own top edge this key's contents stand once it is raised, in pixels.
     *
     * Clamped at the top of the keyboard, which only ever binds on the top row: there is nowhere
     * above the view to draw, and a key with its rounded top sliced off by the window edge looks
     * broken in a way that being twenty pixels lower does not.
     */
    private fun liftAbove(rect: KeyRect, g: LayoutGeometry): Float =
        PopupGrid.liftAbove(rect, g)

    private fun keyRectOf(keyId: String?): KeyRect? =
        keyId?.let { id -> geometry().keyRects.firstOrNull { it.key.id == id } }

    /** Straight per-channel interpolation, for the secondary taking on the primary's colour. */
    private fun blend(from: Int, to: Int, f: Float): Int {
        if (f <= 0f) return from
        if (f >= 1f) return to
        fun channel(a: Int, b: Int) = (a + (b - a) * f).toInt()
        return Color.argb(
            channel(Color.alpha(from), Color.alpha(to)),
            channel(Color.red(from), Color.red(to)),
            channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to)),
        )
    }

    // --- touch ----------------------------------------------------------------------------

    private fun trace(message: String) {
        if (TouchTrace.enabled) TouchTrace.log(context, message)
    }

    private fun actionName(action: Int) = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        else -> "action$action"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        handleTouch(event)
        // Every event can raise a key, lower it or move its glyphs, so they are all re-aimed
        // after all of them rather than at each of the several places a gesture can change
        // course.
        syncPressAnimations()
        return true
    }

    private fun handleTouch(event: MotionEvent) {
        val g = geometry()
        // Moves are left out: they are nine tenths of the events and none of the questions this
        // trace answers, and building a line for each of them on the UI thread would slow down
        // the very typing it is here to measure.
        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            trace(
                "event ${actionName(event.actionMasked)} pointers=${event.pointerCount} " +
                    "id=${event.getPointerId(event.actionIndex)} t=${event.eventTime}",
            )
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                // A second finger while the spacebar trackpad is live starts a selection
                // rather than pressing a key.
                val trackpad = pointers.values.firstOrNull {
                    it.state == GestureState.TRACKPAD
                }
                val candidate =
                    if (trackpad == null) candidateAt(event.getX(i), event.getY(i), g) else -1
                if (trackpad != null) {
                    trace("  down id=$id -> eaten by trackpad secondary tap")
                    consumedPointers += id
                    emit(trackpad.onSecondaryTap())
                } else if (candidate >= 0) {
                    trace("  down id=$id -> suggestion strip, candidate=$candidate")
                    candidatePointers[id] = candidate
                    pressedCandidate = candidate
                    invalidate()
                } else if (resumeGlide(id, event.getX(i), event.getY(i), event.eventTime)) {
                    // The finger came back: it is still the same word.
                    trace("  down id=$id -> resumed the suspended glide")
                } else {
                    trace(
                        "  down id=$id -> new press on " +
                            "${g.keyForPress(event.getX(i), event.getY(i))?.key?.id}",
                    )
                    val fsm = TouchFsm(g, config)
                    pointers[id] = fsm
                    emit(fsm.onDown(event.getX(i), event.getY(i), event.eventTime))
                    scheduleLongPress(id, fsm)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    candidatePointers[id]?.let { pressed ->
                        // Sliding off the suggestion cancels it, as sliding off a key does.
                        val still = candidateAt(event.getX(i), event.getY(i), g) == pressed
                        val shown = if (still) pressed else -1
                        if (shown != pressedCandidate) {
                            pressedCandidate = shown
                            invalidate()
                        }
                    }
                    pointers[id]?.let { fsm ->
                        if (fsm.state == GestureState.TRACKPAD ||
                            fsm.state == GestureState.SELECTING
                        ) {
                            android.util.Log.d(
                                "TP",
                                "FINGER t=${event.eventTime} p=$id x=${event.getX(i)} y=${event.getY(i)}",
                            )
                        }
                        emit(fsm.onMove(event.getX(i), event.getY(i), event.eventTime))
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                val id = event.getPointerId(i)
                cancelLongPress(id)
                trace("  up id=$id state=${pointers[id]?.state}")
                candidatePointers.remove(id)?.let { pressed ->
                    val committed = pressedCandidate == pressed
                    pressedCandidate = -1
                    invalidate()
                    if (committed) onCandidate(pressed)
                    return
                }
                if (consumedPointers.remove(id)) {
                    trace("  up id=$id -> was consumed, nothing emitted")
                    return
                }
                if (!pointers.containsKey(id)) trace("  up id=$id -> NO FSM, press was lost")
                pointers.remove(id)?.let { fsm ->
                    emit(fsm.onUp(event.getX(i), event.getY(i), event.eventTime))
                    if (fsm.isSuspended) suspendGlide(fsm)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                pointers.keys.toList().forEach { cancelLongPress(it) }
                cancelGlideResume()
                suspendedGlide?.let { emit(it.onCancel()) }
                suspendedGlide = null
                pointers.values.forEach { emit(it.onCancel()) }
                pointers.clear()
                consumedPointers.clear()
                candidatePointers.clear()
                pressedCandidate = -1
            }
        }
    }

    /**
     * Holds a lifted glide open for the resume window, and finishes it if nothing comes back.
     *
     * The timeout runs off the UI handler for the same reason the long press does: it is a
     * decision made by the *absence* of a touch event, and nothing else in this class can notice
     * that something did not happen.
     */
    private fun suspendGlide(fsm: TouchFsm) {
        cancelGlideResume()
        suspendedGlide = fsm
        val runnable = Runnable {
            glideResumeRunnable = null
            suspendedGlide = null
            emit(fsm.onGlideResumeTimeout())
        }
        glideResumeRunnable = runnable
        uiHandler.postDelayed(runnable, config.glideResumeMs)
    }

    /**
     * Whether this finger going down continues the suspended glide, and does so if it does.
     *
     * A press that does *not* qualify closes the suspended glide first, in order: the word the
     * user finished is typed before the key they went on to press. Leaving it to the timeout
     * would insert it after, which reorders what they wrote.
     */
    private fun resumeGlide(id: Int, x: Float, y: Float, t: Long): Boolean {
        val fsm = suspendedGlide ?: return false
        if (!fsm.canResume(x, y, t)) {
            cancelGlideResume()
            suspendedGlide = null
            emit(fsm.onGlideResumeTimeout())
            return false
        }
        cancelGlideResume()
        suspendedGlide = null
        pointers[id] = fsm
        emit(fsm.onResume(x, y, t))
        return true
    }

    private fun cancelGlideResume() {
        glideResumeRunnable?.let { uiHandler.removeCallbacks(it) }
        glideResumeRunnable = null
    }

    /** Types whatever a lifted glide had drawn, now, because the keyboard is going away. */
    fun finishPendingGlide() {
        val fsm = suspendedGlide ?: return
        cancelGlideResume()
        suspendedGlide = null
        emit(fsm.onGlideResumeTimeout())
    }

    private fun scheduleLongPress(id: Int, fsm: TouchFsm) {
        val runnable = Runnable {
            emit(fsm.onLongPressTimeout(System.currentTimeMillis()))
            // A press that becomes an accent popup or a trackpad is no longer a keypress and no
            // longer a possible flick, and no touch event is coming to notice that.
            syncPressAnimations()
        }
        longPressRunnables[id] = runnable
        uiHandler.postDelayed(runnable, config.longPressMs)
    }

    private fun cancelLongPress(id: Int) {
        longPressRunnables.remove(id)?.let { uiHandler.removeCallbacks(it) }
    }

    /** Applies the visual half of each output, then forwards everything to the service. */
    private fun emit(outputs: List<GestureOutput>) {
        outputs.forEach { trace("    out $it") }
        if (outputs.isEmpty()) return
        var repaint = false
        outputs.forEach { out ->
            when (out) {
                is GestureOutput.KeyHighlighted -> { highlightedKeyId = out.keyId; repaint = true }
                is GestureOutput.CommitPrimary -> tick()
                is GestureOutput.CommitSecondary -> tick()
                is GestureOutput.CommitAccent -> tick()
                is GestureOutput.CommitAction -> tick()
                is GestureOutput.CommitLanguage -> tick()
                is GestureOutput.SpecialKey -> tick()
                GestureOutput.FlickPreviewCleared -> repaint = true
                is GestureOutput.FlickPreview -> repaint = true
                is GestureOutput.ShowAccents -> {
                    geometry().keyRects.firstOrNull { it.key.id == out.keyId }?.let {
                        accentPopup = it to 0
                    }
                    // The popup opened with no touch event to announce it -- the finger has been
                    // still, and what changed is that half a second passed. A buzz is the only
                    // signal that does not require looking at a popup the thumb is covering, and
                    // it is the moment the gesture stopped being a keypress: lifting now no
                    // longer types the letter.
                    longPressTick()
                    repaint = true
                }
                is GestureOutput.AccentHighlighted ->
                    accentPopup?.let {
                        // Not on the opening highlight: ShowAccents has already buzzed for that
                        // same moment, and the two arriving together read as one doubled tick.
                        if (it.second != out.index) tick()
                        accentPopup = it.copy(second = out.index)
                        repaint = true
                    }
                GestureOutput.HideAccents -> { accentPopup = null; repaint = true }
                GestureOutput.GlideStarted -> { glidePath = emptyList(); repaint = true }
                is GestureOutput.GlideUpdated -> { glidePath = out.path; repaint = true }
                // Suspended deliberately leaves the trail on screen: the gesture is not over,
                // and clearing it would tell the user their word had been taken when it has not.
                GestureOutput.GlideSuspended -> Unit
                is GestureOutput.GlideCompleted -> { glidePath = emptyList(); repaint = true }
                // The board moves here rather than in the service, because the state machine
                // reported a *direction* and the state it applies to lives on this view. The
                // service still sees the output and saves the result.
                is GestureOutput.SquashFlick -> {
                    val next = squash.flicked(
                        when (out.toward) {
                            SquashDirection.LEFT -> Squash.LEFT
                            SquashDirection.RIGHT -> Squash.RIGHT
                        },
                    )
                    if (next != squash) {
                        squash = next
                        onSquashChanged(next)
                        // The keys have just moved a long way under a finger that is still down.
                        // A buzz is the only acknowledgement available: the hand asking for a
                        // one-handed reach is covering the half of the screen that changed.
                        longPressTick()
                    }
                }
                GestureOutput.TrackpadStarted -> {
                    // Same reason as the popup: the spacebar became a trackpad because time
                    // passed, and the screen it is about to clear is behind the hand.
                    longPressTick()
                    trackpadActive = true
                    repaint = true
                }
                // The first deletion is the long press itself, so the hold is already announced
                // by the repeat that follows it. Buzzing here as well would double the first one.
                GestureOutput.BackspaceRepeatStarted -> longPressTick()
                GestureOutput.SelectionStarted -> { selecting = true; repaint = true }
                GestureOutput.TrackpadEnded -> {
                    trackpadActive = false; selecting = false; repaint = true
                }
                else -> Unit
            }
        }
        if (repaint) invalidate()
        onOutput(outputs)
    }

    /**
     * The click a key makes.
     *
     * Fired where the key is *entered* rather than where it is pressed, which is the only place
     * a tap and a flick can share: a flick is not a flick until the finger lifts, so ticking on
     * the way down would either buzz for gestures that went on to type nothing or tick twice for
     * the ones that did. It follows the system's touch-feedback setting, as a keyboard should.
     */
    private fun tick() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * The heavier buzz that says a hold has taken effect.
     *
     * Distinct from [tick] because the two answer different questions. A tick confirms something
     * the finger just did; this one announces something that happened *while the finger did
     * nothing* -- the moment a press became a popup, a trackpad or a backspace repeat. That
     * moment has no other signal: the finger is still, and on the popup and the trackpad the
     * thing that appeared is underneath the hand that summoned it.
     *
     * LONG_PRESS is the platform's own constant for exactly this, so it follows whatever the
     * device and the user's haptic settings have decided a long press should feel like, rather
     * than this keyboard inventing a vibration of its own. IGNORE_GLOBAL_SETTING is deliberately
     * not passed: a user who has turned haptics off has said what they want.
     */
    private fun longPressTick() {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * Debug only. adb can drive a single pointer, so the two-finger selection gesture cannot be
     * scripted; this lets a broadcast stand in for the second finger while testing.
     */
    fun debugStartSelection() {
        val trackpad = pointers.values.firstOrNull {
            it.state == GestureState.TRACKPAD
        } ?: return
        emit(trackpad.onSecondaryTap())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        finishPendingGlide()
        uiHandler.removeCallbacksAndMessages(null)
    }
}
