#!/usr/bin/env python3
"""Fits the tap decoder's spatial model to the gesture bank.

Every constant in `SpatialModel.kt` comes out of here.

    tools/fit_spatial.py [data/gesture-bank.jsonl]

**It uses no labels.** Each tap is assigned to the key centre it landed nearest, under the
offset being estimated, and the offset is re-estimated from the assignment until it stops
moving. Two or three rounds is always enough.

That is not a compromise forced by the format, it is the better estimator, and the bank says
so. Fitted against labels recovered by aligning each passage with what was actually typed, the
two agree to 0.020 key widths and 0.014 key heights per key -- under a tenth of the scatter
they are measuring. The unlabelled fit also sees more: it needs no session line, so it reads
the 803 records written before transcripts existed, which the labelled fit cannot.

The one thing labels buy, stated so nobody has to rediscover it: assigning every tap to its
nearest key silently counts the 2.2% that missed as good taps on the neighbour, which truncates
the tails. That understates sigma_x by about 12% and sigma_y not at all, since vertical
neighbours sit further away in sigma units. It changed no conclusion the first time it mattered.

What it reports, and why each number is wanted:

  offset   Where the thumb lands relative to the drawn key centre. Subtracting it is the
           cheapest accuracy win available.
  sigma    Scatter about that landing point. This decides the band in which a tap has more
           than one possible reading, and so how often the decoder has anything to do.
  per-key  The same, per letter. A per-key offset table is the right shape; on this bank it
           removes 6% of the horizontal variance and 11% of the vertical, which is why the
           global pair still ships.

The geometry is duplicated from `layout/KeyboardLayout.kt` rather than shared, because this
runs on a laptop with no JVM in the loop. `LayoutGeometryTest` is what keeps the two honest.
"""

import collections
import json
import math
import statistics
import sys

# Metrics, in dp, from layout/KeyboardLayout.kt.
REFERENCE_WIDTH = 360.0
SIDE_MARGIN = 4.33
KEY_GAP = 4.96
KEY_WIDTH = 30.67
KEY_HEIGHT = 40.0
ROW_GAP = 10.33
STRIP_HEIGHT = 50.0

# IosLayouts.QWERTY_LOWER, as (id, width in key units).
ROWS = [
    [(c, 1) for c in "qwertyuiop"],
    [(c, 1) for c in "asdfghjkl"],
    [("shift", 1.5)] + [(c, 1) for c in "zxcvbnm"] + [("backspace", 1.5)],
    [("mode_123", 1.5), ("globe", 1.25), ("mic", 1.25), ("space", 4.4), ("return", 2.5)],
]

_CACHE = {}


def geometry(width):
    """Key centres in view pixels, and the key unit and height they are measured in."""
    if width in _CACHE:
        return _CACHE[width]
    s = width / REFERENCE_WIDTH
    margin, gap = SIDE_MARGIN * s, KEY_GAP * s
    unit, height = KEY_WIDTH * s, KEY_HEIGHT * s
    row_gap, strip = ROW_GAP * s, STRIP_HEIGHT * s
    usable = width - 2 * margin
    centres = {}
    for row_index, row in enumerate(ROWS):
        content = sum(u for _, u in row) * unit + (len(row) - 1) * gap
        x = margin + (usable - content) / 2
        top = strip + row_index * (height + row_gap)
        for key, u in row:
            centres[key] = (x + u * unit / 2, top + height / 2)
            x += u * unit + gap
    letters = {k: v for k, v in centres.items() if len(k) == 1 and k.isalpha()}
    _CACHE[width] = (letters, unit, height)
    return _CACHE[width]


def taps(path):
    """(x, y, width) for every gesture the keyboard read as a plain tap on a letter key."""
    out = []
    for line in open(path):
        line = line.strip()
        if not line:
            continue
        record = json.loads(line)
        if record.get("kind") == "session":
            continue
        # Withdrawn on the spot by the person who made it: the path is real and is not an
        # example of anything.
        if record.get("void"):
            continue
        key = record.get("startKey", "")
        if not (len(key) == 1 and key.isalpha()):
            continue
        # Pre-v6 lines say what the build decided; v6 lines do not, and a tap is recognisable
        # without being told -- it is a gesture that went nowhere.
        verdict = record.get("verdict")
        if verdict is not None and verdict != "TAP":
            continue
        if verdict is None and record.get("typed", "") not in (key, key.upper()):
            continue
        x, y, _ = record["path"][0]
        out.append((x, y, record["widthPx"]))
    return out


def fit(rows, rounds=5):
    """Assign to the nearest key under the current offset, re-estimate, repeat."""
    ox = oy = 0.0
    dxs = dys = assign = None
    for _ in range(rounds):
        dxs, dys, assign = [], [], []
        for x, y, width in rows:
            letters, unit, height = geometry(width)
            best = min(
                letters,
                key=lambda k: ((x - letters[k][0]) / unit - ox) ** 2
                + ((y - letters[k][1]) / height - oy) ** 2,
            )
            cx, cy = letters[best]
            dxs.append((x - cx) / unit)
            dys.append((y - cy) / height)
            assign.append(best)
        ox, oy = statistics.mean(dxs), statistics.mean(dys)
    return ox, oy, dxs, dys, assign


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "data/gesture-bank.jsonl"
    rows = taps(path)
    if not rows:
        print(f"no taps in {path}")
        return 1

    ox, oy, dxs, dys, assign = fit(rows)
    print(f"{len(rows)} taps from {path}, assigned by geometry rather than by label")
    print()
    print("  MEASURED_OFFSET_X = %+.3ff   MEASURED_OFFSET_Y = %+.3ff" % (ox, oy))
    print("  MEASURED_SIGMA_X  =  %.3ff    MEASURED_SIGMA_Y  =  %.3ff"
          % (statistics.pstdev(dxs), statistics.pstdev(dys)))
    print()
    print("  rows are 1.26 key heights apart and columns 1.16 key widths, so the neighbour is")
    print("  %.1f sigma away sideways and %.1f sigma away vertically -- sideways is the weak axis,"
          % (1.16 / statistics.pstdev(dxs), 1.26 / statistics.pstdev(dys)))
    print("  which is where the errors are: 32 of the first 42 substitutions were within the row.")
    print()

    by_key = collections.defaultdict(list)
    for key, dx, dy in zip(assign, dxs, dys):
        by_key[key].append((dx, dy))
    print("  per key (n >= 8), sorted by horizontal bias")
    table = []
    for key in sorted(by_key):
        pairs = by_key[key]
        if len(pairs) < 8:
            continue
        kx = [dx for dx, _ in pairs]
        ky = [dy for _, dy in pairs]
        table.append((key, len(pairs), statistics.mean(kx), statistics.mean(ky),
                      statistics.pstdev(kx), statistics.pstdev(ky)))
    for key, n, mx, my, sx, sy in sorted(table, key=lambda r: r[2]):
        print("    %s  n=%4d  offset=(%+.3f, %+.3f)  sigma=(%.3f, %.3f)"
              % (key, n, mx, my, sx, sy))

    # What a per-key table would actually be worth, so the decision is a number and not a taste.
    residual_x = [dx - statistics.mean([a for a, _ in by_key[k]]) for k, dx in zip(assign, dxs)]
    residual_y = [dy - statistics.mean([b for _, b in by_key[k]]) for k, dy in zip(assign, dys)]
    global_x = [dx - ox for dx in dxs]
    global_y = [dy - oy for dy in dys]
    print()
    print("  one global offset:  sigma (%.3f, %.3f)"
          % (statistics.pstdev(global_x), statistics.pstdev(global_y)))
    print("  per-key offsets:    sigma (%.3f, %.3f)"
          % (statistics.pstdev(residual_x), statistics.pstdev(residual_y)))
    print("  a per-key table removes %.0f%% of the horizontal variance and %.0f%% of the vertical"
          % (100 * (1 - statistics.pvariance(residual_x) / statistics.pvariance(global_x)),
             100 * (1 - statistics.pvariance(residual_y) / statistics.pvariance(global_y))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
