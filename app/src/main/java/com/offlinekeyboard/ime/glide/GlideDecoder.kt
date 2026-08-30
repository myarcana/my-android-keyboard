package com.offlinekeyboard.ime.glide

import com.offlinekeyboard.ime.gesture.PathPoint
import com.offlinekeyboard.ime.layout.KeyRect
import com.offlinekeyboard.ime.layout.LayoutGeometry
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * What the decoder weighs. Ratios of a key, never pixels, so the numbers mean the same thing on
 * any screen -- and so they can be swept against recorded gestures the way the gesture
 * thresholds are.
 */
data class GlideConfig(
    /**
     * How many points both the gesture and each candidate word are reduced to before they are
     * compared.
     *
     * Resampling by arc length is what makes the comparison fair: a finger that dawdles over one
     * key and races across the next produces the same samples as one that moves evenly, because
     * the timing is thrown away and only the shape is kept. Thirty-two is the usual figure and
     * the cost is linear in it; the ceiling on useful detail is the touchscreen's own, which is
     * far coarser than this.
     */
    val samples: Int = 32,
    /**
     * How many points the gesture is reduced to for the visit test below.
     *
     * Deliberately four times [samples]. The comparison that pits whole paths against each other
     * is happy with a coarse grid, because both sides are coarse in the same places. Asking how
     * close the finger came to one key is not: at 32 samples a long word puts a quarter of a key
     * width between neighbouring samples, so a finger that went straight over the key centre can
     * still be recorded as having missed it by that much -- and it was, uniformly, which made
     * every long word look badly executed.
     */
    val visitSamples: Int = 128,
    /**
     * How far a word's first or last key may sit from where the finger started or stopped, in
     * key widths.
     *
     * This is the pruning rule, and it is aggressive on purpose: the endpoints are the two parts
     * of a glide a person is deliberate about. Too small and a word is unreachable however well
     * its middle matches; a shade over one key width admits the immediate neighbours, which
     * covers a thumb that landed on the gap.
     */
    val endpointRadiusRatio: Float = 1.15f,
    /**
     * How many words survive the cheap first pass and are aligned properly.
     *
     * Decoding is two passes because the two costs have wildly different prices. Comparing two
     * resampled paths point by point is a few dozen operations and can be run over every word in
     * the bucket; aligning a gesture to a word's keys properly is a small dynamic program and
     * cannot. The first pass only has to keep the right word *somewhere* in its top few dozen,
     * which is a much weaker thing to ask of it than getting the order right.
     */
    val shortlist: Int = 48,
    /** Weight on where the gesture actually was, against where the word's keys are. */
    val locationWeight: Float = 1f,
    /**
     * Weight on the gesture's shape once position and size are normalised away.
     *
     * The two channels fail differently, which is why both exist. Location alone cannot tell a
     * word from its neighbour-shifted twin when the finger drifts bodily off-course; shape alone
     * happily matches a gesture on the wrong side of the keyboard, because a rescaled "hello"
     * and a rescaled "gekko" are the same drawing.
     */
    val shapeWeight: Float = 0.3f,
    /**
     * Weight on the visit distance -- how close the finger came to each of the word's keys, in
     * the order the word needs them.
     *
     * This is the channel that knows a word has to be *travelled through*, and it is the one
     * that separates the cases a resampled comparison cannot. "how" and "house" start on the
     * same key, sweep right and then far left, and cover almost the same distance doing it, so
     * point-for-point they are equally good matches for each other's gesture. This channel asks
     * instead where the finger was when it should have been on `w`, and only one of them has an
     * answer.
     */
    val visitWeight: Float = 1.2f,
    /**
     * Weight on the ratio between how far the finger travelled and how far the word asks for,
     * as a natural log so that half and double cost the same.
     *
     * Cheap, and it catches the failure the other channels are worst at: a short word hiding
     * inside a long gesture. "jd" fits comfortably along the first third of a glide of
     * "keyboard" and is scored on that third alone by every distance measure that does not know
     * the gesture kept going.
     */
    val lengthWeight: Float = 2.5f,
    /**
     * How much combined error, in key widths, a match may carry before it stops counting as one.
     *
     * The cost is squared over this, so it is the width of a tolerance rather than a cliff: half
     * a key of error costs little, two keys of error costs sixteen times as much.
     */
    val toleranceRatio: Float = 1.0f,
    /**
     * How much a decade of word frequency is worth, against that squared distance.
     *
     * This is the only number here that is a value judgement rather than a measurement. At zero
     * the decoder returns whatever the finger literally drew, which for an ambiguous shape is a
     * word nobody has written since 1890; too high and it returns "the" for everything.
     *
     * The tolerance above and this trade against each other directly -- only their ratio changes
     * an answer -- so the tolerance is pinned at one key width and this is the dial. Swept over
     * synthetic glides the whole region from 1.2 to 2.5 scores within noise of itself, which is
     * the honest reading: the value below is the middle of a plateau, not a peak, and the real
     * one will be set by recorded glides from the Gesture Lab rather than by drawn ones.
     */
    val frequencyWeight: Float = 1.8f,
    /** Below this much travel, in key widths, there is not enough gesture to decode. */
    val minLengthRatio: Float = 1.0f,
    /** How many words to return. Only the first is typed; the rest are for the lab. */
    val results: Int = 5,
)

/**
 * Turns a glide path into a word.
 *
 * The method is the standard one -- resample both the gesture and each candidate word to the
 * same number of points and compare them position by position -- and the reason it is worth
 * writing rather than vendoring is that everything it needs is already here: the layout knows
 * where the keys are, the state machine already hands over the raw path, and the lexicon is a
 * text file. A vendored decoder would arrive trained on somebody else's key geometry, which is
 * precisely the thing this keyboard does differently.
 *
 * Nothing in here is stateful and nothing allocates per candidate beyond two small arrays, so a
 * decode is a few hundred microseconds and can run on the UI thread at the lift.
 */
class GlideDecoder(
    private val lexicon: Lexicon,
    private val config: GlideConfig = GlideConfig(),
) {

    data class Result(
        val word: String,
        /** Higher is better. Log-odds-ish: a squared distance plus a log frequency. */
        val score: Float,
        /** How close the finger came to the word's keys, in key widths. Lower is better. */
        val distance: Float,
    )

    /**
     * Best words for [path], best first.
     *
     * [path] is the raw touch samples, including any bridged across a lifted finger. A bridge is
     * simply a longer straight segment: it needs no special handling here, because resampling
     * treats the path as a polyline and a straight line through two keys is what a finger that
     * kept going would have drawn anyway.
     */
    fun decode(path: List<PathPoint>, geometry: LayoutGeometry): List<Result> {
        val points = dedupe(path)
        if (points.size < 2) return emptyList()
        val travelled = length(points)
        if (travelled < config.minLengthRatio * geometry.keyUnit) return emptyList()

        val gesture = resample(points, config.samples)
        val fine = resample(points, config.visitSamples)
        val gestureShape = normalise(gesture)
        val starts = letterKeysNear(points.first(), geometry)
        val ends = letterKeysNear(points.last(), geometry)
        if (starts.isEmpty() || ends.isEmpty()) return emptyList()

        val centres = letterCentres(geometry) ?: return emptyList()
        val ideal = FloatArray(config.samples * 2)
        val keyX = FloatArray(MAX_WORD)
        val keyY = FloatArray(MAX_WORD)

        // Pass one: every word in the bucket, scored by the cheap point-for-point comparison.
        val shortlist = TopN(config.shortlist)
        starts.forEach { first ->
            ends.forEach { last ->
                lexicon.bucket(first, last).forEach { index ->
                    val letters = lexicon.letters[index]
                    if (letters.length > MAX_WORD) return@forEach
                    if (!idealPath(letters, centres, ideal, keyX, keyY)) return@forEach
                    val location = meanDistance(gesture, ideal) / geometry.keyUnit
                    shortlist.offer(index, rank(location, index), location)
                }
            }
        }

        // Pass two: check each survivor against the keys it actually needs, in order.
        val best = TopN(config.results)
        shortlist.forEach { index, _, location ->
            val letters = lexicon.letters[index]
            val keys = idealKeys(letters, centres, keyX, keyY)
            if (keys < 2) return@forEach
            resampleInto(keyX, keyY, keys, ideal, config.samples)
            val visit = visitDistance(fine, keyX, keyY, keys) / geometry.keyUnit
            val shape = if (gestureShape == null) 0f else shapeDistance(gestureShape, ideal)
            val stretch = abs(ln(travelled / polylineLength(keyX, keyY, keys)))
            val cost = config.locationWeight * location +
                config.visitWeight * visit +
                config.shapeWeight * shape +
                config.lengthWeight * stretch
            best.offer(index, rank(cost, index), visit)
        }
        return best.drain { index, score, distance ->
            Result(lexicon.words[index], score, distance)
        }
    }

    /**
     * Turns an error, in key widths, into something a frequency can be added to.
     *
     * Squaring is what keeps the two comparable. A linear cost lets frequency buy an unbounded
     * amount of sloppiness -- and it did: the first version of the shortlist added a raw distance
     * to a log frequency, and so filled its forty-eight places with the commonest words in
     * English regardless of what had been drawn. Squared, a word three key widths off pays nine
     * times what a word one key width off pays, which no realistic frequency can make up.
     */
    private fun rank(cost: Float, index: Int): Float {
        val tolerance = config.toleranceRatio
        return -(cost * cost) / (2f * tolerance * tolerance) +
            config.frequencyWeight * lexicon.logFrequency[index] / 100f
    }

    /**
     * How close the finger came to each of the word's keys, in the order the word needs them.
     *
     * A monotonic alignment, pinned at both ends: the first key is answered for by the first
     * sample and the last by the last, which is the endpoint discipline a glide already has. In
     * between, each key takes the best sample at or after the one its predecessor took.
     *
     * The direction matters, and the first version of this had it backwards. Asking "was the
     * finger always near some key of this word" is a question every word can answer well, because
     * a glide spends most of its time in the gaps between keys and there is always *a* key
     * nearby; the correct word scored no better than a wrong one and often worse. Asking "was the
     * finger ever near this key, and then near the next one" is the question with an answer only
     * the right word has -- a glide of "house" has nothing to offer when "how" asks where the
     * finger was for `w`.
     *
     * Normalised by the number of keys, so a long word is not penalised for having more of them.
     */
    private fun visitDistance(gesture: FloatArray, kx: FloatArray, ky: FloatArray, keys: Int): Float {
        val n = gesture.size / 2
        var previous = FloatArray(n) { Float.MAX_VALUE }
        previous[0] = hypot(gesture[0] - kx[0], gesture[1] - ky[0])
        var current = FloatArray(n)

        for (j in 1 until keys) {
            var reachable = Float.MAX_VALUE
            for (i in 0 until n) {
                // Everything the previous key could have used at or before this sample.
                if (previous[i] < reachable) reachable = previous[i]
                current[i] =
                    if (reachable == Float.MAX_VALUE) Float.MAX_VALUE
                    else reachable + hypot(gesture[i * 2] - kx[j], gesture[i * 2 + 1] - ky[j])
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[n - 1] / keys
    }

    private fun polylineLength(xs: FloatArray, ys: FloatArray, n: Int): Float {
        var total = 0f
        for (i in 1 until n) total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        // A word whose keys all sit on one point cannot be glided; a zero here would divide by 0.
        return total.coerceAtLeast(1e-3f)
    }

    // --- candidate geometry -------------------------------------------------------------------

    /**
     * The layout's letter keys, or null if it has none -- which is what a numeric or symbol
     * plane looks like, and a glide on one of those decodes to nothing rather than to nonsense.
     */
    private fun letterCentres(geometry: LayoutGeometry): Array<KeyRect?>? =
        geometry.letterKeys.takeIf { keys -> keys.any { it != null } }

    /**
     * The path a perfectly-executed glide of [word] would draw, resampled into [out].
     *
     * Repeated letters collapse to one point. A finger cannot travel to a key it is already on,
     * so "hello" draws the same line as "helo" -- and pretending otherwise would put two of the
     * ideal path's samples on top of each other and quietly weight that key twice.
     */
    private fun idealPath(
        word: String,
        centres: Array<KeyRect?>,
        out: FloatArray,
        keyX: FloatArray,
        keyY: FloatArray,
    ): Boolean {
        val n = idealKeys(word, centres, keyX, keyY)
        if (n < 2) return false
        resampleInto(keyX, keyY, n, out, config.samples)
        return true
    }

    /**
     * The key centres a glide of [word] would pass through, written into [keyX] and [keyY].
     * Returns how many there are, or 0 for a word the layout cannot spell.
     *
     * Repeated letters collapse to one point. A finger cannot travel to a key it is already on,
     * so "hello" draws the same line as "helo" -- and pretending otherwise would put two of the
     * ideal path's samples on top of each other and quietly weight that key twice.
     */
    private fun idealKeys(
        word: String,
        centres: Array<KeyRect?>,
        keyX: FloatArray,
        keyY: FloatArray,
    ): Int {
        var n = 0
        word.forEach { c ->
            val rect = centres[c - 'a'] ?: return 0
            if (n == 0 || keyX[n - 1] != rect.centerX || keyY[n - 1] != rect.centerY) {
                keyX[n] = rect.centerX
                keyY[n] = rect.centerY
                n++
            }
        }
        return n
    }

    /**
     * Letter keys the finger could plausibly have meant at [p].
     *
     * The nearest key is always included even when it is further away than the radius allows: a
     * gesture that started in the gap above the top row still started *somewhere*, and returning
     * nothing at all for it would be worse than returning the obvious answer.
     */
    private fun letterKeysNear(p: PathPoint, geometry: LayoutGeometry): List<Char> {
        val radius = config.endpointRadiusRatio * geometry.keyUnit
        val near = ArrayList<Char>(4)
        var nearest: Char? = null
        var nearestDistance = Float.MAX_VALUE
        geometry.letterKeys.forEachIndexed { index, rect ->
            if (rect == null) return@forEachIndexed
            val letter = 'a' + index
            val d = hypot(p.x - rect.centerX, p.y - rect.centerY)
            if (d < nearestDistance) {
                nearestDistance = d
                nearest = letter
            }
            if (d <= radius) near += letter
        }
        if (near.isEmpty()) nearest?.let { near += it }
        return near
    }

    // --- the two distance channels --------------------------------------------------------------

    private fun meanDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices step 2) sum += hypot(a[i] - b[i], a[i + 1] - b[i + 1])
        return sum / (a.size / 2)
    }

    /**
     * Distance after both paths are moved to the origin and scaled to the same size.
     *
     * Scale is taken from the root-mean-square radius rather than a bounding box: a bounding box
     * is decided by two extreme samples, and one thumb overshooting the edge of the keyboard
     * would rescale the whole gesture. The RMS radius moves by a fraction of that for the same
     * outlier.
     */
    private fun shapeDistance(normalisedGesture: FloatArray, candidate: FloatArray): Float {
        val other = normalise(candidate) ?: return 0f
        return meanDistance(normalisedGesture, other)
    }

    private fun normalise(points: FloatArray): FloatArray? {
        val n = points.size / 2
        var cx = 0f
        var cy = 0f
        for (i in points.indices step 2) {
            cx += points[i]
            cy += points[i + 1]
        }
        cx /= n
        cy /= n
        var sum = 0f
        for (i in points.indices step 2) {
            val dx = points[i] - cx
            val dy = points[i + 1] - cy
            sum += dx * dx + dy * dy
        }
        val radius = sqrt(sum / n)
        // A gesture with no extent at all -- every sample on one spot -- has no shape to compare.
        if (radius < 1e-3f) return null
        val out = FloatArray(points.size)
        for (i in points.indices step 2) {
            out[i] = (points[i] - cx) / radius
            out[i + 1] = (points[i + 1] - cy) / radius
        }
        return out
    }

    // --- resampling -----------------------------------------------------------------------------

    private fun dedupe(path: List<PathPoint>): List<PathPoint> {
        val out = ArrayList<PathPoint>(path.size)
        path.forEach { p ->
            val last = out.lastOrNull()
            if (last == null || abs(last.x - p.x) > 1e-4f || abs(last.y - p.y) > 1e-4f) out += p
        }
        return out
    }

    private fun length(points: List<PathPoint>): Float {
        var total = 0f
        for (i in 1 until points.size) {
            total += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return total
    }

    private fun resample(points: List<PathPoint>, count: Int): FloatArray {
        val xs = FloatArray(points.size)
        val ys = FloatArray(points.size)
        points.forEachIndexed { i, p ->
            xs[i] = p.x
            ys[i] = p.y
        }
        val out = FloatArray(count * 2)
        resampleInto(xs, ys, points.size, out, count)
        return out
    }

    /**
     * Places [count] points evenly along the polyline, by arc length, into [out].
     *
     * Written against raw arrays and a caller-owned output because this runs once per candidate
     * word -- a thousand times per lift -- and a list of allocated points there is the difference
     * between a decode nobody notices and a stutter every time a word is finished.
     */
    private fun resampleInto(xs: FloatArray, ys: FloatArray, n: Int, out: FloatArray, count: Int) {
        var total = 0f
        for (i in 1 until n) total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        if (total <= 0f) {
            for (i in 0 until count) {
                out[i * 2] = xs[0]
                out[i * 2 + 1] = ys[0]
            }
            return
        }
        val step = total / (count - 1)
        out[0] = xs[0]
        out[1] = ys[0]
        var segment = 1
        var segmentStart = 0f
        var segmentLength = hypot(xs[1] - xs[0], ys[1] - ys[0])
        for (i in 1 until count - 1) {
            val target = i * step
            while (segment < n - 1 && segmentStart + segmentLength < target) {
                segmentStart += segmentLength
                segment++
                segmentLength = hypot(xs[segment] - xs[segment - 1], ys[segment] - ys[segment - 1])
            }
            val f = if (segmentLength <= 0f) 0f else (target - segmentStart) / segmentLength
            out[i * 2] = xs[segment - 1] + (xs[segment] - xs[segment - 1]) * f
            out[i * 2 + 1] = ys[segment - 1] + (ys[segment] - ys[segment - 1]) * f
        }
        out[(count - 1) * 2] = xs[n - 1]
        out[(count - 1) * 2 + 1] = ys[n - 1]
    }

    private companion object {
        /** Longest word the key buffers hold. The lexicon generator caps entries at 18. */
        const val MAX_WORD = 24
    }

    /** A tiny fixed-size max-heap-by-hand. Keeping the best few of a thousand needs no more. */
    private class TopN(private val capacity: Int) {
        private val indices = IntArray(capacity)
        private val scores = FloatArray(capacity)
        private val distances = FloatArray(capacity)
        private var size = 0

        fun offer(index: Int, score: Float, distance: Float) {
            if (size == capacity && score <= scores[size - 1]) return
            var at = if (size < capacity) size++ else capacity - 1
            while (at > 0 && scores[at - 1] < score) {
                indices[at] = indices[at - 1]
                scores[at] = scores[at - 1]
                distances[at] = distances[at - 1]
                at--
            }
            indices[at] = index
            scores[at] = score
            distances[at] = distance
        }

        fun <T> drain(build: (Int, Float, Float) -> T): List<T> =
            (0 until size).map { build(indices[it], scores[it], distances[it]) }

        inline fun forEach(action: (Int, Float, Float) -> Unit) {
            for (i in 0 until size) action(indices[i], scores[i], distances[i])
        }
    }
}
