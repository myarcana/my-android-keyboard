#!/usr/bin/env python3
"""Draws the fingers onto a take.

    tools/demo/render.py <take.mkv> <out.mp4> [options]

      --fingers circles     how the fingers are drawn: a style in fingers/  (default: circles)
      --style framed|bare   a device on a backdrop, or the bare screen      (default: framed)
      --zoom keyboard|none  crop to the keyboard and the line being typed   (default: none)
      --fps 60              output frame rate                               (default: 60)
      --width 1080          output width, scaled at encode time            (default: unscaled)
      --no-captions         leave the scenario's captions off
      --offset-ms 0         nudge the fingers earlier (-) or later (+)

The fingers are not detected from the video -- they are the touches the device was *told* to
make, drawn at the pixel and the millisecond they were injected. That is the whole reason this
pipeline exists in this order: a recording alone would need the touch overlay switched on, which
draws a small grey dot and looks like a debugging aid.

The take already has the touches in the video's clock (see take.py), so this does no lining up of
its own: it decodes, hands each frame to the finger style, frames it and captions it.
"""

import json
import os
import subprocess
import sys
from types import SimpleNamespace

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fingers  # noqa: E402
import take as takes  # noqa: E402

BACKDROP_TOP = np.array([18.0, 21.0, 30.0])
BACKDROP_BOTTOM = np.array([9.0, 11.0, 17.0])

# Captions are drawn here rather than by ffmpeg's drawtext, which is absent from the ffmpeg
# Homebrew installs by default -- the first render died on "No such filter: 'drawtext'". Pillow
# carries its own FreeType, so the pipeline depends on nothing but what pip put in .venv.
FONTS = [
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/System/Library/Fonts/SFNS.ttf",
    "/System/Library/Fonts/Supplemental/Arial.ttf",
]
CAPTION_FADE_MS = 220


def backdrop(width, height):
    """A quiet vertical gradient, so the device has something to sit on."""
    t = np.linspace(0, 1, height)[:, None, None]
    return (BACKDROP_TOP * (1 - t) + BACKDROP_BOTTOM * t) * np.ones((1, width, 1))


def rounded_mask(width, height, radius, feather=1.0):
    ys, xs = np.mgrid[0:height, 0:width]
    dx = np.maximum(radius - xs, xs - (width - 1 - radius))
    dy = np.maximum(radius - ys, ys - (height - 1 - radius))
    dx = np.maximum(dx, 0)
    dy = np.maximum(dy, 0)
    d = np.hypot(dx, dy)
    return np.clip((radius - d) / feather + 0.5, 0, 1)


def box_blur(a, r):
    """A couple of box passes, which is close enough to a Gaussian for a shadow."""
    for _ in range(2):
        pad = np.pad(a, r, mode="edge")
        c = np.cumsum(np.cumsum(pad, axis=0), axis=1)
        c = np.pad(c, ((1, 0), (1, 0)))
        size = 2 * r + 1
        a = (c[size:, size:] - c[:-size, size:] - c[size:, :-size] + c[:-size, :-size]) / size ** 2
    return a


def stage(screen_w, screen_h, style):
    """The canvas the screen is composited onto, and where in it the screen goes."""
    if style == "bare":
        return None, (0, 0, screen_w, screen_h), None

    margin_x = int(screen_w * 0.085 / 2) * 2
    margin_y = int(screen_h * 0.035 / 2) * 2
    width = screen_w + margin_x * 2
    height = screen_h + margin_y * 2
    canvas = backdrop(width, height)

    radius = int(screen_w * 0.052)
    mask = rounded_mask(screen_w, screen_h, radius)

    # A shadow under the device: the same rounded shape, blurred, offset down, darkening what is
    # behind it rather than painting grey on it.
    shadow = np.zeros((height, width))
    shadow[margin_y:margin_y + screen_h, margin_x:margin_x + screen_w] = mask
    shadow = box_blur(shadow, max(4, int(screen_w * 0.028)))
    shadow = np.roll(shadow, int(screen_h * 0.012), axis=0)
    canvas *= 1 - 0.55 * shadow[..., None]

    return canvas, (margin_x, margin_y, screen_w, screen_h), mask


def caption_image(text, width, height):
    """A caption as an RGB patch and its alpha, ready to composite.

    Rendered once per caption rather than per frame: the text does not change, only how much of
    it is faded in.
    """
    font_path = next((f for f in FONTS if os.path.exists(f)), None)
    dummy = ImageDraw.Draw(Image.new("RGB", (1, 1)))

    # Sized to the frame, then shrunk until it fits across it. A caption wider than the canvas
    # is not a clipping problem to solve at composite time -- it is a caption nobody can read.
    size = max(18, int(height / 46))
    while size > 12:
        font = ImageFont.truetype(font_path, size) if font_path else ImageFont.load_default()
        pad_x, pad_y = int(size * 0.85), int(size * 0.5)
        left, top, right, bottom = dummy.textbbox((0, 0), text, font=font)
        box_w = right - left + pad_x * 2
        box_h = bottom - top + pad_y * 2
        if box_w <= width * 0.8 or font_path is None:
            break
        size = int(size * 0.92)

    image = Image.new("RGBA", (box_w, box_h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # A pill rather than a rectangle, and translucent rather than solid: it has to sit over a
    # screenshot without looking like part of the app being demonstrated.
    draw.rounded_rectangle([0, 0, box_w - 1, box_h - 1], radius=box_h // 2,
                           fill=(10, 12, 20, 168))
    draw.text((pad_x - left, pad_y - top), text, font=font, fill=(255, 255, 255, 250))

    rgba = np.asarray(image).astype(np.float32)
    return rgba[..., :3], rgba[..., 3:4] / 255.0


def captions(take, offset, width, height):
    """Every caption with the output-time window it belongs in."""
    out = []
    for caption in take["captions"]:
        start = (caption["start"] - offset)
        end = (caption["end"] - offset)
        if end <= 0:
            continue
        rgb, alpha = caption_image(caption["text"], width, height)
        # Held a beat past the action it describes, and faded rather than cut, because a caption
        # that blinks out on the last frame of a gesture reads as a glitch.
        out.append({"rgb": rgb, "alpha": alpha, "start": max(0, start), "end": end + 600})
    return out


def draw_caption(canvas, caption, t):
    """Composites one caption, faded in and out at its edges."""
    fade = min(1.0,
               (t - caption["start"]) / CAPTION_FADE_MS,
               (caption["end"] - t) / CAPTION_FADE_MS)
    if fade <= 0:
        return
    rgb, alpha = caption["rgb"], caption["alpha"] * fade
    h, w = rgb.shape[:2]
    x = (canvas.shape[1] - w) // 2
    # Low on the screen, in the empty part of the page above the keyboard. Across the top it sat
    # over the status bar and read as a system notification rather than a subtitle.
    y = int(canvas.shape[0] * 0.56)
    patch = canvas[y:y + h, x:x + w]
    patch *= 1 - alpha
    patch += rgb * alpha


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    source, out = sys.argv[1:3]
    argv = sys.argv[3:]

    def option(name, default=None):
        return argv[argv.index(name) + 1] if name in argv else default

    style = option("--style", "framed")
    zoom = option("--zoom", "none")
    fps = int(option("--fps", 60))
    width = option("--width")
    nudge = float(option("--offset-ms", 0))
    finger = fingers.load(option("--fingers", "circles"))

    take = takes.load(source)
    geometry = take["geometry"]
    gs = [g for g in take["gestures"] if g["tag"] != "sync"]
    if not gs:
        raise SystemExit("no touches in the take")
    screen_w, screen_h = take["screen"]["width"], take["screen"]["height"]

    # Everything below is in video milliseconds. The demo proper starts after the sync tap; the
    # first second is not worth showing.
    start_ms = max(0.0, gs[0]["start"] - 700)
    # A scenario that opens with a caption over a pause means the caption to be the first thing
    # seen, so the trim starts from it rather than from the first touch.
    opening = min((c["start"] for c in take["captions"]), default=start_ms)
    start_ms = max(0.0, min(start_ms, opening - 250))
    end_ms = gs[-1]["end"] + take.get("tailMs", 1200)

    # Crop, for a close-up on the keyboard and the line being typed.
    if zoom == "keyboard":
        top = max(0, int(geometry["keyboard"][1] - geometry["keyHeight"] * 3.2))
        crop = (0, top, screen_w, screen_h - top)
    else:
        crop = (0, 0, screen_w, screen_h)
    crop_x, crop_y, crop_w, crop_h = crop
    ctx = SimpleNamespace(unit=geometry["keyUnit"], origin=(crop_x, crop_y), at=takes.at)

    canvas0, (dx, dy, _, _), mask = stage(crop_w, crop_h, style)
    if canvas0 is None:
        out_w, out_h = crop_w, crop_h
    else:
        out_h, out_w = canvas0.shape[:2]
    mask3 = None if mask is None else mask[..., None]

    labels = [] if "--no-captions" in argv else captions(take, start_ms, out_w, out_h)
    filters = [f"scale={int(width)}:-2:flags=lanczos"] if width else []

    reader = takes.decode(source, fps)
    encoder = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-y",
         "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{out_w}x{out_h}", "-r", str(fps), "-i", "-",
         *(["-vf", ",".join(filters)] if filters else []),
         "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p",
         "-movflags", "+faststart", out],
        stdin=subprocess.PIPE)

    frame_bytes = screen_w * screen_h * 3
    index = 0
    written = 0
    while True:
        raw = reader.stdout.read(frame_bytes)
        if len(raw) < frame_bytes:
            break
        # A positive nudge draws the fingers later: this frame shows the touches from before it.
        t = index / fps * 1000 - nudge
        index += 1
        if t < start_ms:
            continue
        if t > end_ms:
            break

        frame = np.frombuffer(raw, np.uint8).reshape(screen_h, screen_w, 3)
        frame = frame[crop_y:crop_y + crop_h, crop_x:crop_x + crop_w].astype(np.float32)
        finger.draw(frame, gs, t, ctx)

        if canvas0 is None:
            canvas = frame
        else:
            canvas = canvas0.copy()
            region = canvas[dy:dy + crop_h, dx:dx + crop_w]
            region *= 1 - mask3
            region += frame * mask3

        for caption in labels:
            if caption["start"] <= t - start_ms <= caption["end"]:
                draw_caption(canvas, caption, t - start_ms)

        encoder.stdin.write(np.clip(canvas, 0, 255).astype(np.uint8).tobytes())
        written += 1

    # Killed rather than drained: the trim usually stops reading well before the video ends, and
    # left to finish, ffmpeg reports the closed pipe as an error.
    reader.kill()
    reader.stdout.close()
    reader.wait()
    encoder.stdin.close()
    encoder.wait()
    if written == 0:
        raise SystemExit("nothing was written -- the take's touches and its video do not overlap")
    print(f"{written} frames, {written / fps:.1f}s, {out_w}x{out_h}")


if __name__ == "__main__":
    main()
