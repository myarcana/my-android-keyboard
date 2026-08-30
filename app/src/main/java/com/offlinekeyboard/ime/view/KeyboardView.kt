package com.offlinekeyboard.ime.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import com.offlinekeyboard.ime.capture.TouchTrace
import com.offlinekeyboard.ime.gesture.GestureConfig
import com.offlinekeyboard.ime.gesture.GestureOutput
import com.offlinekeyboard.ime.gesture.GestureState
import com.offlinekeyboard.ime.gesture.PathPoint
import com.offlinekeyboard.ime.gesture.TouchFsm
import com.offlinekeyboard.ime.layout.IosLayouts
import com.offlinekeyboard.ime.layout.Metrics
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.Layout
import com.offlinekeyboard.ime.layout.LayoutGeometry
import kotlin.math.abs
import kotlin.math.exp

/**
 * Palette sampled pixel-by-pixel from Gboard on the target device, so the keyboard sits in the
 * same visual language as the rest of the system rather than approximating it by eye.
 *
 * The dark values are estimates -- resample them against Gboard in dark mode when tuning.
 */
private data class Theme(
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
 * How far a pressed key grows upward, in key heights.
 *
 * The key does not get a popup; it becomes one. Pressing stretches the key itself up out of the
 * board and carries its contents to the top of the taller shape, which puts the glyph clear of
 * the thumb while leaving it attached to the key it belongs to -- the finger is holding the
 * bottom of the same object it is reading the top of. 0.70 lifts the contents a little over half
 * a key above where they sit at rest, which clears a fingertip without reaching the row above's
 * own glyphs.
 */
private const val PRESS_LIFT = 0.70f

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

    var candidates: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            pressedCandidate = -1
            invalidate()
        }

    var layout: Layout = IosLayouts.QWERTY_LOWER
        set(value) {
            field = value
            geometry = null
            requestLayout()
            invalidate()
        }

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
    private var accentPopup: Triple<KeyRect, List<String>, Int>? = null

    private var glidePath: List<PathPoint> = emptyList()
    private var trackpadActive = false
    private var selecting = false

    /** Index of the suggestion under a finger, for the pressed highlight. */
    private var pressedCandidate = -1
    /** Pointers that went down on the strip, so their UP commits a suggestion, not a key. */
    private val candidatePointers = mutableMapOf<Int, Int>()

    /**
     * Space reserved below the keys for the system navigation bar.
     *
     * From targetSdk 35 the IME window is laid out edge to edge, so without this the bottom row
     * sits underneath the nav buttons. The insets dispatched to this view are not a trustworthy
     * source for the size of it: some hosts -- Firefox's address bar among them -- leave the
     * window running to the bottom of the display while reporting a navigation-bar inset of
     * zero, and the keyboard is then only as wrong as the host it happens to be typing into. So
     * the overlap is measured instead: how far this view's own bottom edge reaches past the top
     * of the navigation bar. The dispatched inset stays as the answer before the first layout,
     * when there is no position to measure yet.
     *
     * Feeding a measurement back into layout is safe here only because the IME window is
     * anchored to the bottom of the display: making it taller moves its top edge and never its
     * bottom, so the overlap being read does not move in response to the reserve it produces,
     * and the second pass agrees with the first.
     */
    private var navBarInset = 0

    /** What the host last said the navigation-bar inset was. See [navBarInset]. */
    private var dispatchedNavInset = 0

    private val theme: Theme
        get() = if (
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        ) Theme.DARK else Theme.LIGHT

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
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
        geometry ?: LayoutGeometry(layout, width.toFloat()).also { geometry = it }

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

    /** How much of this view the navigation bar covers, in pixels. See [navBarInset]. */
    private fun requiredNavBarInset(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return dispatchedNavInset
        if (!isAttachedToWindow || height == 0) return dispatchedNavInset
        val display = context.getSystemService(WindowManager::class.java)
            ?.maximumWindowMetrics ?: return dispatchedNavInset
        val navBar = WindowInsetsCompat.toWindowInsetsCompat(display.windowInsets)
            .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            .bottom
        if (navBar == 0) return 0
        val onScreen = IntArray(2)
        getLocationOnScreen(onScreen)
        // Clamped rather than trusted outright: while the window is still sliding into place its
        // bottom edge is below the display's, which would otherwise ask for a reserve taller
        // than the bar it is reserving for.
        return (onScreen[1] + height - (display.bounds.bottom - navBar)).coerceIn(0, navBar)
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
        accentPopup?.let { (anchor, accents, selected) ->
            drawAccentPopup(canvas, g, t, anchor, accents, selected, radius)
        }
    }

    /**
     * The suggestion strip. Cells are square and left-aligned rather than stretched to fill the
     * width: emoji are square, and a fixed cell means a suggestion does not jump sideways as
     * the list behind it grows or shrinks with each letter typed.
     */
    private fun drawCandidates(canvas: Canvas, g: LayoutGeometry, t: Theme, radius: Float) {
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
        val cell = candidateCellWidth(g)
        // Drawn smaller than the strip and sitting high in it, so the emoji read as their own
        // band rather than as a row above the letters, and so the picture matches the touch
        // area -- which stops at stripTouchBottom to keep high presses on q-p off the strip.
        val top = g.stripHeight * 0.10f
        val bottom = g.stripHeight * 0.70f
        label.textSize = (bottom - top) * 0.74f
        candidates.take(visibleCandidateCount(g)).forEachIndexed { i, candidate ->
            val left = g.margin + i * cell
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

    private fun candidateCellWidth(g: LayoutGeometry): Float = g.stripHeight

    private fun visibleCandidateCount(g: LayoutGeometry): Int =
        ((width - 2 * g.margin) / candidateCellWidth(g)).toInt().coerceAtLeast(1)

    /** Which suggestion a touch landed on, or -1 for none. */
    private fun candidateAt(x: Float, y: Float, g: LayoutGeometry): Int {
        if (y >= g.stripTouchBottom || candidates.isEmpty() || status != null) return -1
        val i = ((x - g.margin) / candidateCellWidth(g)).toInt()
        return if (i in 0 until minOf(candidates.size, visibleCandidateCount(g))) i else -1
    }

    private fun drawKey(canvas: Canvas, rect: KeyRect, radius: Float, t: Theme, g: LayoutGeometry) {
        val isSpecial = rect.key.type != KeyType.CHARACTER && rect.key.type != KeyType.SPACE
        val motion = motions[rect.key.id]
        val press = motion?.press ?: 0f
        // How far this key has grown up out of the board, in pixels. 0 for a key nobody is on,
        // which is what makes everything below reduce to the resting keyboard.
        val lift = press * PRESS_LIFT * g.keyHeight
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
        accents: List<String>,
        selected: Int,
        radius: Float,
    ) {
        val w = accents.size * g.keyUnit
        val left = (anchor.centerX - w / 2f).coerceIn(g.margin, width - g.margin - w)
        val bottom = anchor.top - g.rowGap * 0.4f
        val top = bottom - g.keyHeight * 1.15f

        fill.color = t.popup
        canvas.drawRoundRect(RectF(left, top, left + w, bottom), radius * 2, radius * 2, fill)

        accents.forEachIndexed { i, accent ->
            val cx = left + (i + 0.5f) * g.keyUnit
            if (i == selected) {
                fill.color = t.popupSelected
                canvas.drawRoundRect(
                    RectF(left + i * g.keyUnit, top, left + (i + 1) * g.keyUnit, bottom),
                    radius * 2,
                    radius * 2,
                    fill,
                )
            }
            label.color = if (i == selected) Color.WHITE else t.text
            label.textSize = g.keyUnit * 0.6f
            canvas.drawText(
                accent,
                cx,
                (top + bottom) / 2f - (label.descent() + label.ascent()) / 2f,
                label,
            )
        }
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
     * The two live together because they end together. A lifted finger leaves a key that is both
     * standing up and part-way through a flick, and settling them on one clock at one rate is
     * what makes the key come home as a single object rather than as a body and its contents.
     */
    private class KeyMotion {
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
            // No ramp on the way up. A tap can be over in forty milliseconds, so a key that grew
            // over any duration at all would still be growing when the finger had gone.
            motion.press = 1f
            motion.pull = pull
            motion.held = true
        }
        if (changed) invalidate()
    }

    /**
     * Settles every released key one frame further home, and asks for another frame while any is
     * still moving. Keys under a finger are skipped: [syncPressAnimations] is already placing
     * those, and a touch event repaints them.
     *
     * Exponential rather than a fixed-duration tween because a key can be let go from anywhere --
     * fully raised and fully pulled, or a tenth of the way down after a change of mind -- and the
     * return should take its length from how far there is to go. It also cannot overshoot: an
     * iPadOS key slides back, it does not bounce.
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
            motion.press -= motion.press * step
            motion.pull -= motion.pull * step
            // An exponential only ever approaches zero, so it is landed by hand -- otherwise a key
            // at rest would keep asking for frames forever.
            if (motion.press < 0.004f) motion.press = 0f
            if (motion.pull < 0.004f) motion.pull = 0f
            if (motion.press == 0f && motion.pull == 0f) entries.remove() else settling = true
        }

        if (settling) postInvalidateOnAnimation() else lastFrameNanos = 0L
    }

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
                is GestureOutput.SpecialKey -> tick()
                GestureOutput.FlickPreviewCleared -> repaint = true
                is GestureOutput.FlickPreview -> repaint = true
                is GestureOutput.ShowAccents -> {
                    geometry().keyRects.firstOrNull { it.key.id == out.keyId }?.let {
                        accentPopup = Triple(it, out.accents, 0)
                    }
                    repaint = true
                }
                is GestureOutput.AccentHighlighted ->
                    accentPopup?.let { accentPopup = it.copy(third = out.index); repaint = true }
                GestureOutput.HideAccents -> { accentPopup = null; repaint = true }
                GestureOutput.GlideStarted -> { glidePath = emptyList(); repaint = true }
                is GestureOutput.GlideUpdated -> { glidePath = out.path; repaint = true }
                // Suspended deliberately leaves the trail on screen: the gesture is not over,
                // and clearing it would tell the user their word had been taken when it has not.
                GestureOutput.GlideSuspended -> Unit
                is GestureOutput.GlideCompleted -> { glidePath = emptyList(); repaint = true }
                GestureOutput.TrackpadStarted -> { trackpadActive = true; repaint = true }
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
