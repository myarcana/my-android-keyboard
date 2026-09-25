"""Finger styles: the different ways a take's touches can be drawn over its video.

A style is a module in this directory with one function:

    def draw(frame, gestures, t, ctx):

      frame     the screen, float32 RGB, H x W x 3, 0-255; draw into it in place
      gestures  every gesture in the take, the sync tap already removed, each
                  {tag, start, end, points: [(ms, x, y), ...]} in the video's clock
      t         the video time of this frame, in ms
      ctx       .unit    one key width in pixels -- size things in these, not in pixels
                .origin  (x, y) of the frame's top-left in screen pixels; subtract it
                .at      at(points, t) -> (x, y), interpolated

A style decides for itself how long a gesture stays visible after it ends (a ripple, a fading
trail), so it is handed all of them and skips the ones it has nothing to draw for. `paint` has
an anti-aliased disc and ring to build from.

Adding one is adding a file: `render.py --fingers <file name>` picks it up.
"""

import importlib
import os


def available():
    here = os.path.dirname(__file__)
    return sorted(f[:-3] for f in os.listdir(here)
                  if f.endswith(".py") and not f.startswith("_") and f != "paint.py")


def load(name):
    if name not in available():
        raise SystemExit(f"no finger style '{name}'; there are: {', '.join(available())}")
    return importlib.import_module(f"fingers.{name}")
