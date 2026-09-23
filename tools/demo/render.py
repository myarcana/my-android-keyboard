#!/usr/bin/env python3
"""Draws the fingers back onto a screen recording.

    tools/demo/render.py raw.mp4 touches.jsonl plan.json geometry.json out.mp4 [options]

      --style framed|bare   a device on a backdrop, or the bare screen      (default: framed)
      --zoom keyboard|none  crop to the keyboard and the line being typed   (default: none)
      --fps 60              output frame rate                               (default: 60)
      --width 1080          output width, scaled at encode time            (default: unscaled)
      --no-captions         leave the scenario's captions off
      --offset-ms 0         nudge the circles earlier (-) or later (+)

The circles are not detected from the video -- they are the touches the device was *told* to
make, drawn at the pixel and the millisecond they were injected. That is the whole reason this
pipeline exists in this order: a recording alone would need the touch overlay switched on, which
draws a small grey dot and looks like a debugging aid.

**Marrying the two clocks.** The touch log is in milliseconds since the plan began; the video
knows nothing about that. They are lined up by correlating the whole gesture sequence against how
much the keyboard changes from frame to frame: presses, lifts and glides all move pixels, and
forty of them at known spacings match in exactly one place. It is worth the trouble -- clock
arithmetic across adb was out by 100 ms and more, which is two frames of a circle arriving after
the key it pressed.
"""

import json
import math
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# The pointer, in key widths, so it stays the same size on any screen.
RADIUS = 0.46
PRESS_SHRINK = 0.86          # how far the circle shrinks while it is down
PRESS_MS = 90                # how long that takes
RIPPLE_MS = 340              # the ring that expands out of a lift
TRAIL_MS = 340               # how much of a glide stays visible behind the finger
TRAIL_STEP_MS = 9            # close enough together to read as one stroke, not a row of dots

FILL = np.array([24.0, 28.0, 44.0])       # slate, dark enough to read on white keys
RING = np.array([255.0, 255.0, 255.0])
FILL_ALPHA = 0.34
RING_ALPHA = 0.95
HALO_ALPHA = 0.16            # a wider, fainter disc under the circle, for edges on white keys
TRAIL_ALPHA = 0.22

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


def probe(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=width,height", "-show_entries", "format=duration",
         "-of", "json", path],
        capture_output=True, text=True, check=True).stdout
    info = json.loads(out)
    stream = info["streams"][0]
    return stream["width"], stream["height"], float(info["format"]["duration"])


def decode(path, fps, width=None, gray=False, seconds=None):
    """Frames out of ffmpeg, as numpy arrays, at a constant frame rate."""
    scale = f"scale={width}:-1," if width else ""
    fmt = "gray" if gray else "rgb24"
    cmd = ["ffmpeg", "-v", "error", "-i", path]
    if seconds:
        cmd += ["-t", str(seconds)]
    cmd += ["-vf", f"{scale}format={fmt}", "-r", str(fps),
            "-f", "rawvideo", "-pix_fmt", fmt, "-"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, bufsize=10 ** 8)
    return proc


def motion(video, geometry, fps, seconds=None):
    """How much the keyboard changed in each frame, against the frame before it."""
    w, h, _ = probe(video)
    small_w = 270
    scale = small_w / w
    small_h = int(round(h * scale / 2) * 2)
    left, top, right, bottom = [v * scale for v in geometry["keyboard"]]
    box = (slice(int(top), int(bottom)), slice(int(left), int(right)))

    proc = decode(video, fps, width=small_w, gray=True, seconds=seconds)
    signal, previous = [], None
    while True:
        raw = proc.stdout.read(small_w * small_h)
        if len(raw) < small_w * small_h:
            break
        frame = np.frombuffer(raw, np.uint8).reshape(small_h, small_w).astype(np.float32)[box]
        if previous is not None:
            signal.append(np.abs(frame - previous).mean())
        previous = frame
    proc.stdout.close()
    proc.wait()
    return np.array(signal)


def expected_motion(gs, fps, frames):
    """What that signal should look like, predicted from the touch log alone.

    A key changes the screen when it is pressed and again when it is released -- the pop, the
    letter appearing, the suggestion strip rewriting itself -- and a glide changes it for as long
    as it lasts. So: an impulse at every down and up, and a low plateau in between.
    """
    signal = np.zeros(frames)
    for g in gs:
        for t in (g["start"], g["end"]):
            i = int(t / 1000 * fps)
            if 0 <= i < frames:
                # A couple of frames wide, because a press is drawn over more than one.
                lo, hi = max(0, i - 2), min(frames, i + 3)
                signal[lo:hi] += np.exp(-np.linspace(-2, 2, hi - lo) ** 2)
        if g["end"] - g["start"] > 200:
            lo, hi = int(g["start"] / 1000 * fps), int(g["end"] / 1000 * fps)
            signal[max(0, lo):min(frames, hi)] += 0.55
    return signal


def find_sync(video, geometry, gs, fps):
    """The video time, in seconds, at which the plan's clock started.

    Found by lining the whole touch log up against the recording rather than by spotting one
    frame. The first attempt looked for the sync tap alone -- the first localised change in the
    backspace key -- and locked onto the app's own launch, several seconds before the keyboard
    existed, because that is also a change that happens in one place.

    Correlating the entire gesture sequence has no such failure mode: a launch animation matches
    one impulse, and this is matching forty of them, spaced the way only this demo is spaced.
    """
    measured = motion(video, geometry, fps)
    if measured.size < 30:
        raise SystemExit("the recording is too short to line up against the touch log")
    # Compressed before correlating, so that one very large change -- the keyboard sliding into
    # frame, the app launching -- cannot outweigh the pattern of forty small ones. Compressed
    # rather than clipped at a percentile: most frames of a screen recording are identical to the
    # one before, so the 97th percentile of this signal is zero and clipping to it erases
    # everything.
    measured = np.log1p(measured)
    measured = measured - measured.mean()

    span = int((max(g["end"] for g in gs) / 1000 + 1) * fps)
    if span >= measured.size:
        raise SystemExit("the recording is shorter than the demo it is supposed to contain")
    template = expected_motion(gs, fps, span)
    template = template - template.mean()

    best, best_score = 0, -1e30
    norm = np.linalg.norm(template)
    for offset in range(0, measured.size - span):
        window = measured[offset:offset + span]
        denominator = np.linalg.norm(window) * norm
        if denominator <= 0:
            continue
        score = float(window @ template) / denominator
        if score > best_score:
            best, best_score = offset, score
    if best_score < 0.25:
        raise SystemExit(
            f"could not line the touch log up with the recording (best score {best_score:.2f})")
    return best / fps, best_score


def gestures(touches):
    """The touch log as a list of gestures, each a list of (t_ms, x, y)."""
    out, current = [], None
    for line in open(touches):
        line = line.strip()
        if not line:
            continue
        s = json.loads(line)
        if s["action"] == "down":
            current = {"tag": s.get("tag", ""), "points": []}
            out.append(current)
        if current is None:
            continue
        current["points"].append((s["t"], s["x"], s["y"]))
        if s["action"] == "up":
            current = None
    for g in out:
        g["start"] = g["points"][0][0]
        g["end"] = g["points"][-1][0]
    return out


def at(points, t):
    """Where the finger was at time t, interpolating between samples."""
    if t <= points[0][0]:
        return points[0][1], points[0][2]
    if t >= points[-1][0]:
        return points[-1][1], points[-1][2]
    lo, hi = 0, len(points) - 1
    while lo < hi - 1:
        mid = (lo + hi) // 2
        if points[mid][0] <= t:
            lo = mid
        else:
            hi = mid
    t0, x0, y0 = points[lo]
    t1, x1, y1 = points[hi]
    f = 0 if t1 == t0 else (t - t0) / (t1 - t0)
    return x0 + (x1 - x0) * f, y0 + (y1 - y0) * f


def disc(canvas, cx, cy, radius, colour, alpha, ring=0.0, ring_colour=RING, ring_alpha=1.0):
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


def captions(plan, offset, width, height):
    """Every caption with the output-time window it belongs in."""
    out = []
    for caption in plan.get("captions", []):
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
    if len(sys.argv) < 6:
        raise SystemExit(__doc__)
    video, touches, plan_path, geometry_path, out = sys.argv[1:6]
    argv = sys.argv[6:]

    def option(name, default=None):
        return argv[argv.index(name) + 1] if name in argv else default

    style = option("--style", "framed")
    zoom = option("--zoom", "none")
    fps = int(option("--fps", 60))
    width = option("--width")
    nudge = float(option("--offset-ms", 0))

    plan = json.load(open(plan_path))
    geometry = json.load(open(geometry_path))
    gs = gestures(touches)
    if not gs:
        raise SystemExit("no touches in the log")

    unit = geometry["keyUnit"]
    radius = RADIUS * unit
    screen_w, screen_h, _ = probe(video)

    sync, score = find_sync(video, geometry, gs, fps)
    print(f"touches line up at {sync:.2f}s (score {score:.2f})")
    # The sync tap is at t=0 in the plan by construction, so the video time of any touch is
    # sync + t/1000. The demo proper starts after it; the first second is not worth showing.
    real = [g for g in gs if g["tag"] != "sync"]
    first = real[0]["start"] if real else 0
    start_ms = max(0.0, first - 700)
    # A scenario that opens with a caption over a pause means the caption to be the first thing
    # seen, so the trim starts from it rather than from the first touch.
    opening = min((c["start"] for c in plan.get("captions", [])), default=start_ms)
    start_ms = max(0.0, min(start_ms, opening - 250))
    end_ms = (real[-1]["end"] if real else gs[-1]["end"]) + plan.get("tailMs", 1200)

    # Crop, for a close-up on the keyboard and the line being typed.
    if zoom == "keyboard":
        top = max(0, int(geometry["keyboard"][1] - geometry["keyHeight"] * 3.2))
        crop = (0, top, screen_w, screen_h - top)
    else:
        crop = (0, 0, screen_w, screen_h)
    crop_x, crop_y, crop_w, crop_h = crop

    canvas0, (dx, dy, _, _), mask = stage(crop_w, crop_h, style)
    if canvas0 is None:
        out_w, out_h = crop_w, crop_h
    else:
        out_h, out_w = canvas0.shape[:2]
    mask3 = None if mask is None else mask[..., None]

    labels = [] if "--no-captions" in argv else captions(plan, start_ms, out_w, out_h)
    filters = [f"scale={int(width)}:-2:flags=lanczos"] if width else []

    reader = decode(video, fps)
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
        video_ms = index / fps * 1000
        index += 1
        touch_ms = video_ms - sync * 1000 + nudge
        if touch_ms < start_ms:
            continue
        if touch_ms > end_ms:
            break

        frame = np.frombuffer(raw, np.uint8).reshape(screen_h, screen_w, 3)
        frame = frame[crop_y:crop_y + crop_h, crop_x:crop_x + crop_w].astype(np.float32)

        for g in gs:
            if g["tag"] == "sync":
                continue
            if touch_ms < g["start"] or touch_ms > g["end"] + RIPPLE_MS:
                continue
            x, y = at(g["points"], min(touch_ms, g["end"]))
            x -= crop_x
            y -= crop_y

            if touch_ms <= g["end"]:
                # The trail: a glide leaves one, a tap has nothing to leave. Stamped every nine
                # milliseconds so the discs overlap into a stroke; at one every twenty-six it
                # read as a row of separate dots chasing the finger.
                for t_ago in range(TRAIL_STEP_MS, TRAIL_MS, TRAIL_STEP_MS):
                    t = touch_ms - t_ago
                    if t < g["start"]:
                        break
                    tx, ty = at(g["points"], t)
                    fade = (1 - t_ago / TRAIL_MS) ** 1.4
                    disc(frame, tx - crop_x, ty - crop_y, radius * (0.34 + 0.40 * fade),
                         FILL, TRAIL_ALPHA * fade)

                # The press: the circle settles inwards over the first few frames, the way a
                # fingertip flattens, then holds. The halo under it is what keeps the edge
                # readable where the keys are white and the fill is not.
                press = min(1.0, (touch_ms - g["start"]) / PRESS_MS)
                r = radius * (1 + (PRESS_SHRINK - 1) * press)
                disc(frame, x, y, r * 1.34, FILL, HALO_ALPHA)
                disc(frame, x, y, r, FILL, FILL_ALPHA, ring=max(2.0, unit * 0.038),
                     ring_alpha=RING_ALPHA)
            else:
                # The lift: a ring expands and fades where the finger was.
                f = (touch_ms - g["end"]) / RIPPLE_MS
                disc(frame, x, y, radius * (1 + 1.1 * f), FILL, 0.0,
                     ring=max(2.0, unit * 0.035) * (1 - f * 0.5),
                     ring_alpha=RING_ALPHA * (1 - f) ** 1.5)

        if canvas0 is None:
            canvas = frame
        else:
            canvas = canvas0.copy()
            region = canvas[dy:dy + crop_h, dx:dx + crop_w]
            region *= 1 - mask3
            region += frame * mask3

        for caption in labels:
            if caption["start"] <= touch_ms - start_ms <= caption["end"]:
                draw_caption(canvas, caption, touch_ms - start_ms)

        encoder.stdin.write(np.clip(canvas, 0, 255).astype(np.uint8).tobytes())
        written += 1

    reader.stdout.close()
    reader.wait()
    encoder.stdin.close()
    encoder.wait()
    if written == 0:
        raise SystemExit("nothing was written -- the sync point and the log disagree")
    print(f"{written} frames, {written / fps:.1f}s, {out_w}x{out_h}")


if __name__ == "__main__":
    main()
