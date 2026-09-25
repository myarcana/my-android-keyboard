"""Drawing primitives the finger styles share."""

import numpy as np

WHITE = np.array([255.0, 255.0, 255.0])


def disc(canvas, cx, cy, radius, colour, alpha, ring=0.0, ring_colour=WHITE, ring_alpha=1.0):
    """An anti-aliased filled circle, and optionally a ring around it.

    Only the bounding box is touched. The whole frame is 2.6 megapixels and there can be a
    couple of dozen of these in it once a glide has a trail.
    """
    h, w, _ = canvas.shape
    reach = radius + ring + 2
    x0, x1 = max(0, int(cx - reach)), min(w, int(cx + reach) + 1)
    y0, y1 = max(0, int(cy - reach)), min(h, int(cy + reach) + 1)
    if x0 >= x1 or y0 >= y1:
        return
    ys, xs = np.mgrid[y0:y1, x0:x1]
    d = np.hypot(xs - cx, ys - cy)

    # One pixel of feather at each edge is all the anti-aliasing a circle this size needs.
    mask = np.clip(radius - d + 0.5, 0, 1) * alpha
    patch = canvas[y0:y1, x0:x1]
    m = mask[..., None]
    patch *= 1 - m
    patch += colour * m

    if ring > 0:
        edge = np.clip(ring / 2 + 0.5 - np.abs(d - radius), 0, 1) * ring_alpha
        m = edge[..., None]
        patch *= 1 - m
        patch += ring_colour * m
