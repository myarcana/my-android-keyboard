#!/usr/bin/env python3
"""Turns a demo scenario into a plan of pointer samples the device can replay.

    tools/demo/plan.py scenario.json geometry.json out/plan.json

A *scenario* says what the demo does, in the keyboard's own vocabulary: type this, glide that,
hold this key, tap the second suggestion. A *plan* is the flat result -- every pointer sample
with the millisecond it is due and the pixel it lands on. `DemoPlayer` on the device replays it
and nothing else, which is deliberate: how a glide curves and how a thumb scatters are the parts
worth iterating on, and iterating here costs a file save rather than a Gradle build and an
install.

Everything about the shaping is in one place, below, and all of it is trying to answer the same
question: what does a demo need in order to exercise the same code a thumb does?

  - **Taps scatter.** The landing points come from `SpatialModel`'s fitted offset and sigma --
    a fifth of a key low, an eighth of a key of spread -- at a fraction of the real scatter by
    default. Dead-centre taps would look mechanical, and, worse, would never put the tap decoder
    in the position it exists for.
  - **Glides slow at the letters.** A hand decelerates where it changes direction, and that dip
    is the signal a glide decoder uses to find the letters. A constant-speed sweep along the same
    path is a different gesture in every way that matters.
  - **Nothing is on a metronome.** Intervals vary by a third either way, because typing does.

The sync tap at the start is not decoration: it is how the video and the touch log are married.
See `render.py`, which looks for the first frame in which the backspace key changes.
"""

import json
import math
import random
import sys

# The fitted thumb, from app/src/main/java/com/offlinekeyboard/ime/tap/SpatialModel.kt. Offsets
# are in key widths and key heights, as they are there.
OFFSET_X, OFFSET_Y = -0.063, 0.204
SIGMA_X, SIGMA_Y = 0.122, 0.130

# How much of that scatter a demo gets. The full measured spread is what the decoder is built for
# and it does mistype occasionally -- which is honest, and is not what you want happening halfway
# through a take that is otherwise good. Half of it still lands off-centre in every frame.
DEFAULT_SCATTER = 0.5

# Pointer sampling. The digitiser reports at about 120 Hz and every path in the gesture bank is
# that dense; a sparser injection decodes unlike anything a person has produced on this keyboard.
SAMPLE_MS = 8


def plan(scenario, geometry, seed=None):
    """The scenario as pointer samples, marks and captions."""
    rng = random.Random(seed if seed is not None else scenario.get("seed", 20260923))
    keys = geometry["keys"]
    unit = geometry["keyUnit"]
    height = geometry["keyHeight"]
    scatter = scenario.get("scatter", DEFAULT_SCATTER)

    samples = []
    marks = []
    captions = []
    now = 0.0

    def emit(t, x, y, action, tag=""):
        samples.append({"t": round(t), "x": round(x, 2), "y": round(y, 2),
                        "action": action, "tag": tag})

    def centre(key):
        if key not in keys:
            raise SystemExit(f"no key '{key}' in the layout -- have {sorted(keys)[:12]}…")
        return keys[key]

    def aim(key):
        """Where a thumb aiming at this key actually lands."""
        x, y = centre(key)
        return (x + (OFFSET_X + rng.gauss(0, SIGMA_X) * scatter) * unit,
                y + (OFFSET_Y + rng.gauss(0, SIGMA_Y) * scatter) * height)

    def tap(x, y, press_ms, tag=""):
        nonlocal now
        emit(now, x, y, "down", tag)
        emit(now + press_ms, x, y, "up", tag)
        now += press_ms

    # The sync tap: one press on backspace, with the field empty so it changes nothing. Every
    # other key would leave a mark -- a letter types, shift relayouts the board into capitals,
    # space commits whatever the decoder is holding.
    marks.append({"t": 0, "label": "sync"})
    tap(*centre("backspace"), press_ms=110, tag="sync")
    now += 700

    for step in scenario.get("steps", []):
        what = step.get("do")
        start = now

        if what == "type":
            interval = step.get("interval", 200)
            for ch in step["text"]:
                if ch == " ":
                    key = "space"
                elif ch == "\n":
                    key = "return"
                elif ch.isupper():
                    tap(*aim("shift"), press_ms=rng.uniform(55, 90), tag="shift")
                    now += 170
                    key = ch.lower()
                else:
                    key = ch
                tap(*aim(key), press_ms=rng.uniform(55, 95), tag=key)
                now += interval * rng.uniform(0.75, 1.3)

        elif what == "tap":
            tap(*aim(step["key"]), press_ms=step.get("pressMs", 70), tag=step["key"])

        elif what == "glide":
            now = glide(emit, now, step["word"], keys, unit, rng,
                        speed=step.get("speed", 1.0))

        elif what == "hold":
            ms = step.get("ms", 700)
            x, y = aim(step["key"])
            emit(now, x, y, "down", step["key"])
            t = 16
            while t < ms:
                # A held thumb is not still. Without this the popup opens under a pointer that
                # looks painted on, and the shot reads as a screenshot with a circle over it.
                emit(now + t, x + math.sin(t / 260) * 1.6, y + math.sin(t / 310) * 1.1, "move")
                t += 16
            # Lifting where it was held, unless the scenario wants a pick from the popup.
            dx = step.get("dx", 0) * unit
            dy = step.get("dy", 0) * unit
            if dx or dy:
                for t2 in range(0, 200, SAMPLE_MS):
                    f = ease(t2 / 200)
                    emit(now + ms + t2, x + dx * f, y + dy * f, "move")
                ms += 200
            emit(now + ms, x + dx, y + dy, "up")
            now += ms

        elif what == "flick":
            dx, dy = {"up": (0, -0.85), "down": (0, 0.85),
                      "left": (-0.85, 0), "right": (0.85, 0)}[step.get("dir", "up")]
            dx, dy = dx * unit, dy * unit
            x, y = aim(step["key"])
            emit(now, x, y, "down", step["key"])
            span = step.get("ms", 90)
            t = SAMPLE_MS
            while t < span:
                # Decelerating, because a flick is thrown rather than dragged.
                f = 1 - (1 - t / span) ** 2
                emit(now + t, x + dx * f, y + dy * f, "move")
                t += SAMPLE_MS
            emit(now + span, x + dx, y + dy, "up")
            now += span

        elif what == "drag":
            # For the space-bar trackpad and anything else measured from a key in key units.
            x, y = centre(step["key"])
            dx, dy = step.get("dx", 0) * unit, step.get("dy", 0) * unit
            span = step.get("ms", 600)
            hold_ms = step.get("holdMs", 0)
            emit(now, x, y, "down", step["key"])
            for t in range(0, int(hold_ms), 16):
                emit(now + t, x, y, "move")
            for t in range(0, int(span) + SAMPLE_MS, SAMPLE_MS):
                f = ease(min(1.0, t / span))
                emit(now + hold_ms + t, x + dx * f, y + dy * f, "move")
            emit(now + hold_ms + span, x + dx, y + dy, "up")
            now += hold_ms + span

        elif what == "suggestion":
            # The strip is three slots wide; the middle one is the default. Aimed by position
            # because the demo cannot know what the decoder will put there.
            left, top, right, _ = geometry["keyboard"]
            slot = (right - left) / 3
            x = left + slot * (step.get("index", 0) + 0.5)
            y = top + geometry["stripHeight"] / 2
            tap(x, y, press_ms=80, tag="suggestion")

        elif what in ("pause", "caption"):
            now += step.get("ms", 1200)

        else:
            raise SystemExit(f"unknown step '{what}'")

        if step.get("caption"):
            captions.append({"start": start, "end": now, "text": step["caption"]})
        if what == "caption":
            captions.append({"start": start, "end": now, "text": step["text"]})
        if step.get("label"):
            marks.append({"t": round(start), "label": step["label"]})

        now += step.get("after", 250)

    return {
        # The player reads these two: which field to type into, and how long to let the keyboard
        # settle before the first touch. It raises the keyboard itself -- see DemoPlayer.
        "field": scenario.get("field", 0),
        "settleMs": scenario.get("settleMs", 1200),
        "samples": sorted(samples, key=lambda s: s["t"]),
        "marks": marks,
        "captions": captions,
        "tailMs": scenario.get("tailMs", 1400),
        "durationMs": round(now + scenario.get("tailMs", 1400)),
    }


def glide(emit, now, word, keys, unit, rng, speed=1.0):
    """One continuous gesture through a word's letters. Returns the time it ends."""
    points = [keys[ch] for ch in word.lower()]
    if len(points) < 2:
        emit(now, *points[0], "down", word)
        emit(now + 70, *points[0], "up", word)
        return now + 70

    # A Catmull-Rom spline: the cheapest curve that passes through every point it is given and
    # leaves no corner for the decoder to read as a deliberate stop. Ends are duplicated so the
    # path starts and finishes exactly on the first and last letter.
    control = [points[0]] + points + [points[-1]]
    path = []
    for i in range(len(control) - 3):
        p0, p1, p2, p3 = control[i:i + 4]
        for s in range(24):
            path.append(catmull_rom(p0, p1, p2, p3, s / 24))
    path.append(control[-2])

    # Arc length, so time can be spent by distance rather than by spline parameter -- the two are
    # badly different around a tight turn, which is exactly where the timing matters.
    lengths = [0.0]
    for a, b in zip(path, path[1:]):
        lengths.append(lengths[-1] + math.dist(a, b))
    total = lengths[-1]

    # Where along the path each letter sits, so the speed can dip there.
    letter_at = []
    for p in points:
        best = min(range(len(path)), key=lambda i: math.dist(path[i], p))
        letter_at.append(lengths[best])

    # 1.5 key widths per 100 ms is the middle of what the glides in data/gesture-bank.jsonl run
    # at; the dips below take time out of that, so the base is set a little faster.
    base = unit * 1.7 / 100 * speed

    def rate(s):
        slow = 1.0
        for at in letter_at:
            slow -= 0.42 * math.exp(-((s - at) / (0.5 * unit)) ** 2)
        # Ends are approached and left slowly, the way a thumb lands and lifts -- but not as
        # slowly as they once were. A glide that loitered on its first key for 300 ms was read as
        # a long press, and the demo that was supposed to write "sunset" opened the accent popup
        # over the s and typed "š". The thumb has to be off the first key inside about 100 ms,
        # which is what these floors are for.
        edge = min(1.0, 0.55 + s / (0.5 * unit), 0.55 + (total - s) / (0.5 * unit))
        return base * max(0.45, slow) * min(1.0, edge)

    # March along the path at the local speed, emitting a sample every SAMPLE_MS.
    emit(now, *path[0], "down", word)
    s, t = 0.0, 0.0
    i = 1
    while s < total:
        s += rate(s) * SAMPLE_MS
        t += SAMPLE_MS
        while i < len(lengths) - 1 and lengths[i] < s:
            i += 1
        span = lengths[i] - lengths[i - 1]
        f = 0 if span <= 0 else (s - lengths[i - 1]) / span
        x = path[i - 1][0] + (path[i][0] - path[i - 1][0]) * f
        y = path[i - 1][1] + (path[i][1] - path[i - 1][1]) * f
        jitter = unit * 0.012
        emit(now + t, x + rng.uniform(-jitter, jitter), y + rng.uniform(-jitter, jitter), "move")
        if t > 6000:
            break
    emit(now + t + SAMPLE_MS, *path[-1], "up", word)
    return now + t + SAMPLE_MS


def catmull_rom(p0, p1, p2, p3, t):
    t2, t3 = t * t, t * t * t

    def axis(a, b, c, d):
        return 0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 +
                      (-a + 3 * b - 3 * c + d) * t3)

    return (axis(p0[0], p1[0], p2[0], p3[0]), axis(p0[1], p1[1], p2[1], p3[1]))


def ease(t):
    """Smoothstep: starts and ends at rest."""
    return t * t * (3 - 2 * t)


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    scenario = json.load(open(sys.argv[1]))
    geometry = json.load(open(sys.argv[2]))
    out = plan(scenario, geometry)
    json.dump(out, open(sys.argv[3], "w"), indent=1)
    print(f"{len(out['samples'])} samples, {out['durationMs'] / 1000:.1f}s")


if __name__ == "__main__":
    main()
