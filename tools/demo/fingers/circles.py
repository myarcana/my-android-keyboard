"""A translucent fingertip: settles inward on the press, leaves a trail on a glide, and lifts
with a ripple. The default, and the look the first demos shipped with."""

import numpy as np

from fingers.paint import disc

RADIUS = 0.46                # key widths, so it stays the same size on any screen
PRESS_SHRINK = 0.86          # how far the circle shrinks while it is down
PRESS_MS = 90                # how long that takes
RIPPLE_MS = 340              # the ring that expands out of a lift
TRAIL_MS = 340               # how much of a glide stays visible behind the finger
TRAIL_STEP_MS = 9            # close enough together to read as one stroke, not a row of dots

FILL = np.array([24.0, 28.0, 44.0])       # slate, dark enough to read on white keys
FILL_ALPHA = 0.34
RING_ALPHA = 0.95
HALO_ALPHA = 0.16            # a wider, fainter disc under the circle, for edges on white keys
TRAIL_ALPHA = 0.22


def draw(frame, gestures, t, ctx):
    ox, oy = ctx.origin
    unit = ctx.unit
    radius = RADIUS * unit
    for g in gestures:
        if t < g["start"] or t > g["end"] + RIPPLE_MS:
            continue
        x, y = ctx.at(g["points"], min(t, g["end"]))
        x -= ox
        y -= oy

        if t <= g["end"]:
            # The trail: a glide leaves one, a tap has nothing to leave. Stamped every nine
            # milliseconds so the discs overlap into a stroke; at one every twenty-six it read
            # as a row of separate dots chasing the finger.
            for t_ago in range(TRAIL_STEP_MS, TRAIL_MS, TRAIL_STEP_MS):
                s = t - t_ago
                if s < g["start"]:
                    break
                tx, ty = ctx.at(g["points"], s)
                fade = (1 - t_ago / TRAIL_MS) ** 1.4
                disc(frame, tx - ox, ty - oy, radius * (0.34 + 0.40 * fade),
                     FILL, TRAIL_ALPHA * fade)

            # The press: the circle settles inwards over the first few frames, the way a
            # fingertip flattens, then holds. The halo under it is what keeps the edge readable
            # where the keys are white and the fill is not.
            press = min(1.0, (t - g["start"]) / PRESS_MS)
            r = radius * (1 + (PRESS_SHRINK - 1) * press)
            disc(frame, x, y, r * 1.34, FILL, HALO_ALPHA)
            disc(frame, x, y, r, FILL, FILL_ALPHA, ring=max(2.0, unit * 0.038),
                 ring_alpha=RING_ALPHA)
        else:
            # The lift: a ring expands and fades where the finger was.
            f = (t - g["end"]) / RIPPLE_MS
            disc(frame, x, y, radius * (1 + 1.1 * f), FILL, 0.0,
                 ring=max(2.0, unit * 0.035) * (1 - f * 0.5),
                 ring_alpha=RING_ALPHA * (1 - f) ** 1.5)
