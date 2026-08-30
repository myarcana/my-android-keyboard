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
 * How quickly a tapped key shrinks back out of its flash, as an exponential time constant in
 * milliseconds.
 *
 * The growth itself has no clock: the key is at full size on the frame the finger lands, because
 * a quick tap can be over in forty milliseconds and anything that ramps up would still be on its
 * way in when the finger has already gone. Only the shrink is timed, and it starts from wherever
 * the key was, so a key tapped twice in quick succession jumps back up rather than continuing
 * whatever it was doing.
 */
private const val POP_SETTLE_MS = 90f

/**
 * How much bigger a key gets at the peak of its flash, in key widths.
 *
 * Spent as *pixels* rather than as a scale factor -- see [KeyboardView.drawPoppedKey] -- so
 * every key swells by the same visible amount whatever its size.
 */
private const val POP_GROWTH = 0.30f

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
     * Keys mid-flick, by key id -- rather than by pointer, so a key still settling back after the
     * finger has lifted keeps its place with no pointer left to hold it.
     */
    private val flicks = mutableMapOf<String, Flick>()

    /**
     * Keys mid-flash, by key id -- for the same reason as [flicks]: a key shrinking back after
     * the finger has lifted has no pointer left to hold its place.
     */
    private val pops = mutableMapOf<String, Pop>()
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
        advanceAnimations()
        canvas.drawColor(t.background)

        if (trackpadActive) {
            drawTrackpadHint(canvas, g, t)
            return
        }

        val radius = g.cornerRadius
        drawCandidates(canvas, g, t, radius)
        // Flashing keys are drawn after the rest, so a key grown past its neighbours is not
        // painted over by the ones that come later in the row.
        g.keyRects.forEach { rect -> if (popOf(rect) == 0f) drawKey(canvas, rect, radius, t, g) }
        g.keyRects.forEach { rect ->
            val pop = popOf(rect)
            if (pop > 0f) drawPoppedKey(canvas, rect, radius, t, g, pop)
        }

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
        val top = g.stripHeight * 0.12f
        val bottom = g.stripHeight * 0.88f
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
        if (y >= g.stripHeight || candidates.isEmpty() || status != null) return -1
        val i = ((x - g.margin) / candidateCellWidth(g)).toInt()
        return if (i in 0 until minOf(candidates.size, visibleCandidateCount(g))) i else -1
    }

    private fun drawKey(canvas: Canvas, rect: KeyRect, radius: Float, t: Theme, g: LayoutGeometry) {
        val isSpecial = rect.key.type != KeyType.CHARACTER && rect.key.type != KeyType.SPACE
        val base = if (isSpecial) t.specialKey else t.key
        // The pressed tint fades out along with the flash rather than being dropped the instant
        // the finger leaves, so the key does one thing on release instead of two.
        fill.color = when {
            rect.key.id == highlightedKeyId -> t.keyPressed
            else -> blend(base, t.keyPressed, popOf(rect))
        }
        canvas.drawRoundRect(
            RectF(rect.left, rect.top, rect.right, rect.bottom),
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

        // How far this key is through the flick, 0 at rest. Everything below reduces to the
        // resting layout at 0, so a key nobody is touching is drawn exactly as it always was.
        val flick = flicks[rect.key.id]?.progress ?: 0f
        // The departing letter leaves through the bottom of the key; without this it would be
        // drawn over the key below.
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

    // --- the iPadOS flick animation -------------------------------------------------------

    /** One key's flick: how far its glyphs have been pulled, and whether a finger is doing it. */
    private class Flick {
        var progress = 0f

        /**
         * True while a finger is on the key. A held key is not animating -- it is being moved --
         * so it neither eases toward anything nor asks for frames of its own.
         */
        var held = false
    }

    /**
     * Puts every key's glyphs exactly where the fingers currently have them.
     *
     * The pull comes from the state machines rather than from the raw coordinates here, because
     * the rules for what counts as a flick in progress -- downward, vertically dominant on the way
     * in, on a key that has a secondary, not yet promoted to a glide -- already live there, and a
     * second copy of them in the renderer would be a second thing to keep in step.
     *
     * Assigning [Flick.progress] directly, rather than easing toward it, is the point: the symbol
     * travels the same number of pixels the thumb does, so a slow pull is slow, a fast one is
     * fast, and a pull that turns round comes back up under the finger that is lifting it. Every
     * key with no finger on it is released to settle home, which covers the lift, a cancel, and a
     * flick escaping into a glide without any of the three being handled separately.
     */
    /** Re-aims everything a finger holds in place: the flick glyphs and the tap flash. */
    private fun syncPressAnimations() {
        syncFlickTargets()
        syncPops()
    }

    private fun syncFlickTargets() {
        var changed = false
        flicks.values.forEach {
            if (it.held) {
                it.held = false
                changed = true
            }
        }
        pointers.values.forEach { fsm ->
            val keyId = fsm.originKeyId ?: return@forEach
            val pull = fsm.flickProgress
            if (pull <= 0f) return@forEach
            val flick = flicks.getOrPut(keyId) { Flick() }
            if (flick.progress != pull || !flick.held) changed = true
            flick.progress = pull
            flick.held = true
        }
        if (changed) invalidate()
    }

    /**
     * Settles every released key one frame further home, and asks for another frame while any is
     * still moving. Keys under a finger are skipped: [syncFlickTargets] is already placing those,
     * and a touch event repaints them.
     *
     * Exponential rather than a fixed-duration tween because a key can be let go from anywhere --
     * fully pulled after a flick, or a tenth of the way down after a change of mind -- and the
     * return should take its length from how far there is to go. It also cannot overshoot: an
     * iPadOS key slides back, it does not bounce.
     */
    private fun advanceFlicks(dtMs: Float): Boolean {
        val step = 1f - exp(-dtMs / FLICK_SETTLE_MS)

        var settling = false
        val entries = flicks.entries.iterator()
        while (entries.hasNext()) {
            val flick = entries.next().value
            if (flick.held) continue
            flick.progress -= flick.progress * step
            // An exponential only ever approaches zero, so it is landed by hand -- otherwise a key
            // at rest would keep asking for frames forever.
            if (flick.progress < 0.004f) flick.progress = 0f
            if (flick.progress == 0f) entries.remove() else settling = true
        }

        return settling
    }

    /**
     * Steps every clocked animation one frame and asks for another while any of them is moving.
     *
     * They share a single clock because they share a frame: reading the time twice would hand
     * the second one a zero-length interval and leave it frozen for as long as the first was
     * running.
     */
    private fun advanceAnimations() {
        if (flicks.isEmpty() && pops.isEmpty()) return
        val now = System.nanoTime()
        // A dropped frame must not teleport the glyphs, and the first frame after a lift has no
        // previous one to measure from.
        val dtMs =
            if (lastFrameNanos == 0L) 0f
            else ((now - lastFrameNanos) / 1_000_000f).coerceIn(0f, 64f)
        lastFrameNanos = now
        // `or`, not `||`: both have to be stepped, whatever the first one answers.
        val settling = advanceFlicks(dtMs) or advancePops(dtMs)
        if (settling) postInvalidateOnAnimation() else lastFrameNanos = 0L
    }

    // --- the tap flash ---------------------------------------------------------------------

    /** One key's flash: how far it is grown, and whether a finger is still holding it there. */
    private class Pop {
        var progress = 0f
        var held = false
    }

    private fun popOf(rect: KeyRect): Float = pops[rect.key.id]?.progress ?: 0f

    /**
     * Grows the key each finger is pressing, and lets go of every key no finger is.
     *
     * Read from the state machines for the same reason the flick is: they already know what a
     * finger is doing, and only two of the things it can be doing are still a keypress. A press
     * that has become a glide, the trackpad, an accent popup or a backspace repeat has taken the
     * finger somewhere else entirely, and the key it started on settles back while the gesture it
     * turned into carries on -- which also means no key can be left standing up with nothing
     * holding it, however the gesture ends.
     */
    private fun syncPops() {
        var changed = false
        pops.values.forEach {
            if (it.held) {
                it.held = false
                changed = true
            }
        }
        pointers.values.forEach { fsm ->
            if (fsm.state != GestureState.PRESSED && fsm.state != GestureState.FLICK) {
                return@forEach
            }
            val keyId = fsm.originKeyId ?: return@forEach
            val pop = pops.getOrPut(keyId) { Pop() }
            if (pop.progress != 1f || !pop.held) changed = true
            pop.progress = 1f
            pop.held = true
        }
        if (changed) invalidate()
    }

    /** Shrinks every released key one frame, exactly as [advanceFlicks] settles the glyphs. */
    private fun advancePops(dtMs: Float): Boolean {
        var settling = false
        val step = 1f - exp(-dtMs / POP_SETTLE_MS)
        val entries = pops.entries.iterator()
        while (entries.hasNext()) {
            val pop = entries.next().value
            if (pop.held) continue
            pop.progress -= pop.progress * step
            // An exponential only approaches zero; the last sliver is not worth a frame.
            if (pop.progress < 0.01f) pop.progress = 0f
            if (pop.progress == 0f) entries.remove() else settling = true
        }
        return settling
    }

    /**
     * Draws a key grown out of the keyboard under the finger that is on it.
     *
     * The size is chosen in pixels and only then turned back into a scale factor. A flat scale
     * would be wrong on the wide keys: the same 25% that reads as a flash on a letter throws the
     * space bar a third of a row sideways. Growing every key by the same number of pixels along
     * its longest side makes the gesture feel identical wherever it lands.
     *
     * The pivot sits below the key's centre so most of the growth goes upward, away from the
     * thumb that is covering the key and toward the part of it the user can actually see.
     */
    private fun drawPoppedKey(
        canvas: Canvas,
        rect: KeyRect,
        radius: Float,
        t: Theme,
        g: LayoutGeometry,
        pop: Float,
    ) {
        val scale = 1f + POP_GROWTH * g.keyUnit * pop / maxOf(rect.width, rect.height)
        canvas.save()
        canvas.scale(scale, scale, rect.centerX, rect.centerY + rect.height * 0.3f)
        drawKey(canvas, rect, radius, t, g)
        canvas.restore()
    }

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        handleTouch(event)
        // Every event can start, move or end a flick, so the glyphs are re-aimed after all of
        // them rather than at each of the several places a gesture can change course.
        syncPressAnimations()
        return true
    }

    private fun handleTouch(event: MotionEvent) {
        val g = geometry()
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
                    consumedPointers += id
                    emit(trackpad.onSecondaryTap())
                } else if (candidate >= 0) {
                    candidatePointers[id] = candidate
                    pressedCandidate = candidate
                    invalidate()
                } else if (resumeGlide(id, event.getX(i), event.getY(i), event.eventTime)) {
                    // The finger came back: it is still the same word.
                } else {
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
                candidatePointers.remove(id)?.let { pressed ->
                    val committed = pressedCandidate == pressed
                    pressedCandidate = -1
                    invalidate()
                    if (committed) onCandidate(pressed)
                    return
                }
                if (consumedPointers.remove(id)) return
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
            // A press that becomes an accent popup or a trackpad is no longer a possible flick
            // and no longer a key standing up, and no touch event is coming to notice that.
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
