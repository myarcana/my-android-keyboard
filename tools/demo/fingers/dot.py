"""A small solid dot and a thin line behind it -- Android's own "show taps" and "pointer
location", done properly. Reads as a precise record of where the finger went rather than as a
finger, which suits a glide more than a tap."""

import numpy as np

from fingers.paint import disc

COLOUR = np.array([0.0, 122.0, 255.0])
RADIUS = 0.15                # key widths
TRAIL_MS = 600
FADE_MS = 200


def draw(frame, gestures, t, ctx):
    ox, oy = ctx.origin
    r = RADIUS * ctx.unit
    for g in gestures:
        if t < g["start"] or t > g["end"] + FADE_MS:
            continue
        fade = 1.0 if t <= g["end"] else 1 - (t - g["end"]) / FADE_MS
        now = min(t, g["end"])
        for t_ago in range(0, TRAIL_MS, 2):
            s = now - t_ago
            if s < g["start"]:
                break
            x, y = ctx.at(g["points"], s)
            disc(frame, x - ox, y - oy, r * 0.28, COLOUR, 0.8 * (1 - t_ago / TRAIL_MS) * fade)
        x, y = ctx.at(g["points"], now)
        disc(frame, x - ox, y - oy, r, COLOUR, 0.9 * fade, ring=max(1.5, ctx.unit * 0.02),
             ring_alpha=0.9 * fade)
