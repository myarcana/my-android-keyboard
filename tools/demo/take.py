#!/usr/bin/env python3
"""A take: the recording and everything that happened in it, in one file.

    tools/demo/take.py pack <take-dir> <out.take.mkv>   raw.mp4 + touches.jsonl + ... -> one file
    tools/demo/take.py show <file.take.mkv>             what is in a take
    tools/demo/take.py json <file.take.mkv>             the touch data, on stdout, for other tools

A take is a Matroska file holding the screen recording exactly as it came off the device (copied,
not re-encoded) and a JSON attachment, `take.json`, describing every touch *in the video's own
clock*. It is the master a demo is rendered from: any number of finger styles can be drawn over
the same take, and none of them need to know how it was recorded.

Matroska rather than MP4 because it has attachments as a first-class thing; MP4 would mean hiding
the JSON in a metadata atom. The cost is that QuickTime will not play a take -- which is fine,
because nobody watches a take, they render it. IINA, VLC and ffmpeg all read it.

**Lining the clocks up happens here, once.** The touch log is in milliseconds since the plan
began, and the video knows nothing about that. They are matched by correlating the whole gesture
sequence against how much the keyboard changes from frame to frame: presses, lifts and glides all
move pixels, and forty of them at known spacings match in exactly one place. Clock arithmetic
across adb was out by 100 ms and more, which is two frames of a circle arriving after the key it
pressed. Doing it at pack time means the answer is stored, and every style draws against the same
one.

take.json, version 1:

    format, version       "offline-keyboard-demo-take", 1
    scenario, title       which scenario was recorded
    packed                ISO time the take was packed
    screen                {width, height} of the recording, in pixels
    sync                  {planZeroMs, score}: the video time at which the plan's t=0 fell
    geometry              DemoGeometry's measurement: keyUnit, keyHeight, keyboard box, keys
    touches               every injected sample: {t, v, x, y, action, tag}
                            t   ms since the plan began, as logged on the device
                            v   ms into the video -- t + planZeroMs; this is the one to draw with
    captions              {start, end, text} in video ms
    tailMs                how long the scenario wants to be held after its last touch

Coordinates are screen pixels of the recording. A gesture is the samples from a `down` to the
next `up`; the first one is tagged `sync` and exists only to be lined up against.
"""

import datetime
import json
import os
import subprocess
import sys
import tempfile

import numpy as np

FORMAT = "offline-keyboard-demo-take"
VERSION = 1
SYNC_FPS = 60


# ---------------------------------------------------------------- video

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
    """Frames out of ffmpeg, as raw bytes on a pipe, at a constant frame rate.

    screenrecord writes a variable frame rate -- a frame only when something changed -- so
    resampling to a constant rate here is what makes frame index a clock.
    """
    scale = f"scale={width}:-1," if width else ""
    fmt = "gray" if gray else "rgb24"
    cmd = ["ffmpeg", "-v", "error", "-i", path, "-map", "0:v:0"]
    if seconds:
        cmd += ["-t", str(seconds)]
    cmd += ["-vf", f"{scale}format={fmt}", "-r", str(fps),
            "-f", "rawvideo", "-pix_fmt", fmt, "-"]
    return subprocess.Popen(cmd, stdout=subprocess.PIPE, bufsize=10 ** 8)


# ---------------------------------------------------------------- touches

def gestures(touches, clock="v"):
    """Touch samples grouped into gestures, each {tag, start, end, points: [(ms, x, y)]}."""
    out, current = [], None
    for s in touches:
        if s["action"] == "down":
            current = {"tag": s.get("tag", ""), "points": []}
            out.append(current)
        if current is None:
            continue
        current["points"].append((s[clock], s["x"], s["y"]))
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


# ---------------------------------------------------------------- lining up the clocks

def motion(video, geometry, fps):
    """How much the keyboard changed in each frame, against the frame before it."""
    w, h, _ = probe(video)
    small_w = 270
    scale = small_w / w
    small_h = int(round(h * scale / 2) * 2)
    left, top, right, bottom = [v * scale for v in geometry["keyboard"]]
    box = (slice(int(top), int(bottom)), slice(int(left), int(right)))

    proc = decode(video, fps, width=small_w, gray=True)
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


def find_sync(video, geometry, gs, fps=SYNC_FPS):
    """The video time, in ms, at which the plan's clock started, and how sure that is.

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
    return best / fps * 1000, best_score


# ---------------------------------------------------------------- packing

def pack(take_dir, out, scenario=None):
    raw = os.path.join(take_dir, "raw.mp4")
    touches = [json.loads(line) for line in open(os.path.join(take_dir, "touches.jsonl"))
               if line.strip()]
    plan = json.load(open(os.path.join(take_dir, "plan.json")))
    geometry = json.load(open(os.path.join(take_dir, "geometry.json")))
    if not touches:
        raise SystemExit("no touches in the log")
    scenario = scenario or os.path.basename(os.path.normpath(take_dir))
    title = ""
    scenario_file = os.path.join(os.path.dirname(__file__), "scenarios", scenario + ".json")
    if os.path.exists(scenario_file):
        title = json.load(open(scenario_file)).get("title", "")

    with tempfile.TemporaryDirectory() as tmp:
        # The video goes into its new container first and is lined up from there, so the sync is
        # measured against exactly the timestamps every later render will decode. Only the
        # picture is kept: screenrecord adds two metadata tracks Matroska has no slot for.
        video = os.path.join(tmp, "video.mkv")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", raw, "-map", "0:v:0", "-c", "copy",
                        video], check=True)

        sync_ms, score = find_sync(video, geometry, gestures(touches, clock="t"))
        width, height, _ = probe(video)

        for s in touches:
            s["v"] = round(s["t"] + sync_ms, 2)
        take = {
            "format": FORMAT,
            "version": VERSION,
            "scenario": scenario,
            "title": title,
            "packed": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
            "screen": {"width": width, "height": height},
            "sync": {"planZeroMs": round(sync_ms, 2), "score": round(score, 3)},
            "geometry": geometry,
            "touches": touches,
            "captions": [{"start": round(c["start"] + sync_ms, 2),
                          "end": round(c["end"] + sync_ms, 2),
                          "text": c["text"]} for c in plan.get("captions", [])],
            "tailMs": plan.get("tailMs", 1200),
        }
        meta = os.path.join(tmp, "take.json")
        with open(meta, "w") as f:
            json.dump(take, f, separators=(",", ":"))

        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", video, "-map", "0", "-c", "copy",
                        "-attach", meta,
                        "-metadata:s:t:0", "mimetype=application/json",
                        "-metadata:s:t:0", "filename=take.json",
                        "-metadata", f"title={scenario}",
                        out], check=True)
    return take


def load(path):
    """The take.json out of a take file, with gestures built in the video's clock."""
    with tempfile.TemporaryDirectory() as tmp:
        meta = os.path.join(tmp, "take.json")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-dump_attachment:t:0", meta,
                        "-i", path, "-t", "0", "-f", "null", "-"], check=True)
        take = json.load(open(meta))
    if take.get("format") != FORMAT:
        raise SystemExit(f"{path} is not a demo take")
    if take.get("version", 0) > VERSION:
        raise SystemExit(f"{path} is take version {take['version']}; this reads up to {VERSION}")
    take["gestures"] = gestures(take["touches"])
    return take


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    command = sys.argv[1]
    if command == "pack" and len(sys.argv) >= 4:
        take = pack(sys.argv[2], sys.argv[3])
        print(f"touches line up at {take['sync']['planZeroMs'] / 1000:.2f}s "
              f"(score {take['sync']['score']:.2f})")
    elif command == "show":
        take = load(sys.argv[2])
        gs = take["gestures"]
        w, h, duration = probe(sys.argv[2])
        print(f"{take['scenario']}: {take['title']}")
        print(f"  video     {w}x{h}, {duration:.1f}s")
        print(f"  touches   {len(take['touches'])} samples in {len(gs)} gestures, "
              f"{gs[0]['start'] / 1000:.2f}s to {gs[-1]['end'] / 1000:.2f}s")
        print(f"  sync      plan t=0 at {take['sync']['planZeroMs'] / 1000:.2f}s "
              f"(score {take['sync']['score']:.2f})")
        print(f"  captions  {len(take['captions'])}")
        print(f"  packed    {take['packed']}")
    elif command == "json":
        take = load(sys.argv[2])
        del take["gestures"]
        json.dump(take, sys.stdout, indent=1)
        print()
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
