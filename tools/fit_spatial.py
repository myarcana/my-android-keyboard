#!/usr/bin/env python3
"""Fits the tap decoder's spatial model to the gesture bank.

Every constant in `SpatialModel.kt` comes out of here. The bank records, for each plain
keypress, the point the finger went down on and the letter the passage had asked for --
which is exactly the pair "where does a thumb aiming at this key actually land?" needs.

    tools/fit_spatial.py [data/gesture-bank.jsonl]

What it reports, and why each number is wanted:

  offset   Where the thumb lands relative to the drawn key centre. Subtracting it is the
           single cheapest accuracy win available, and it is not small.
  sigma    Scatter about that landing point. This is what decides how wide the band is in
           which a tap has more than one possible reading, and so how often the decoder
           has anything to do at all.
  rms      Scatter about the *drawn* centre -- what an uncorrected model would have to
           use. The gap between this and sigma is what the offset is worth.
  per-key  The same, per letter. A per-key offset table is the right shape and needs the
           bank to cover the alphabet first; until then the global pair ships and this
           column is how you tell whether that has stopped being good enough.

The geometry is duplicated from `layout/KeyboardLayout.kt` rather than shared, because
this runs on a laptop with no JVM in the loop. `LayoutGeometryTest` is what keeps the two
honest; if a key size changes there and not here, its numbers move and this one's do not.
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


def geometry(width):
    """Key rectangles in view pixels, and the key unit and height they are measured in."""
    s = width / REFERENCE_WIDTH
    margin, gap = SIDE_MARGIN * s, KEY_GAP * s
    unit, height = KEY_WIDTH * s, KEY_HEIGHT * s
    row_gap, strip = ROW_GAP * s, STRIP_HEIGHT * s
    usable = width - 2 * margin
    rects = {}
    for row_index, row in enumerate(ROWS):
        content = sum(u for _, u in row) * unit + (len(row) - 1) * gap
        x = margin + (usable - content) / 2
        top = strip + row_index * (height + row_gap)
        for key, u in row:
            rects[key] = (x, top, x + u * unit, top + height)
            x += u * unit + gap
    return rects, unit, height


def offsets(path):
    """(letter, dx, dy) per recorded tap, in key widths and key heights."""
    out = []
    with open(path) as bank:
        for line in bank:
            line = line.strip()
            if not line:
                continue
            record = json.loads(line)
            # Voided records are ones whose label the typist withdrew on the spot; they are
            # kept in the bank and must not be fitted to.
            if record.get("intent") != "LETTER" or record.get("void"):
                continue
            letter = record["expected"]
            rects, unit, height = geometry(record["widthPx"])
            if letter not in rects:
                continue
            left, top, right, bottom = rects[letter]
            # path[0] is where the finger went down. For a tap that is the whole gesture:
            # every plain tap in the bank travels zero pixels (see NOTES.md).
            x, y, _ = record["path"][0]
            out.append(
                (
                    letter,
                    (x - (left + right) / 2) / unit,
                    (y - (top + bottom) / 2) / height,
                )
            )
    return out


def rms(values):
    return math.sqrt(sum(v * v for v in values) / len(values))


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "data/gesture-bank.jsonl"
    taps = offsets(path)
    if not taps:
        print(f"no letter taps in {path}")
        return 1

    xs = [dx for _, dx, _ in taps]
    ys = [dy for _, _, dy in taps]
    by_key = collections.defaultdict(list)
    for letter, dx, dy in taps:
        by_key[letter].append((dx, dy))

    print(f"{len(taps)} letter taps over {len(by_key)} keys, from {path}")
    print()
    print("  MEASURED_OFFSET_X = %+.3ff   MEASURED_OFFSET_Y = %+.3ff"
          % (statistics.mean(xs), statistics.mean(ys)))
    print("  MEASURED_SIGMA_X  = %.3ff    MEASURED_SIGMA_Y  = %.3ff"
          % (statistics.pstdev(xs), statistics.pstdev(ys)))
    print()
    print("  about the drawn centre instead: rms %.3f, %.3f"
          % (rms(xs), rms(ys)))
    print("  -- the vertical figure is what the offset is worth; rows are 1.26 key heights apart")
    print()
    print("  per key (a table needs all 26; until then this is a warning light)")
    for letter in sorted(by_key):
        pairs = by_key[letter]
        kx = [dx for dx, _ in pairs]
        ky = [dy for _, dy in pairs]
        error = statistics.pstdev(ky) / math.sqrt(len(ky)) if len(ky) > 1 else float("nan")
        print("    %s  n=%3d  offset=(%+.3f, %+.3f)  sigma=(%.3f, %.3f)  se(dy)=%.3f"
              % (letter, len(pairs), statistics.mean(kx), statistics.mean(ky),
                 statistics.pstdev(kx), statistics.pstdev(ky), error))
    return 0


if __name__ == "__main__":
    sys.exit(main())
