---
name: demo-video
description: Record and render product demo videos of the keyboard in an emulator, with finger circles drawn over the gestures. Use when asked for a demo video, a promo clip, a screen recording of the keyboard, or a GIF of a feature - and when adding or editing a demo scenario.
---

# Demo videos of the keyboard

Records the real keyboard in an emulator, typed by a script, and draws the fingers back on.
Nothing is mocked: the touches go through the same decoding a thumb does, and the frames are the
keyboard's own pixels.

```sh
tools/demo/demo.sh list              # the scenarios that exist
tools/demo/demo.sh make glide        # record and render -> build/demo/glide/glide.mp4
tools/demo/demo.sh record glide      # record only
tools/demo/demo.sh render glide      # render again from what was recorded (cheap to iterate)
tools/demo/demo.sh install           # rebuild and reinstall the four APKs
tools/demo/demo.sh shutdown          # stop the emulator
```

A take costs about a minute; rendering about as long again. Everything lands in
`build/demo/<scenario>/`, which is git-ignored: `raw.mp4` off the device, `touches.jsonl`,
`plan.json`, and the finished `<scenario>.mp4`.

## Writing a scenario

`tools/demo/scenarios/<name>.json`. Steps are in the keyboard's own vocabulary:

```json
{
  "title": "Glide a word, tap the rest",
  "field": 0,
  "steps": [
    {"do": "pause", "ms": 700, "caption": "Tap or glide -- the same keyboard"},
    {"do": "type", "text": "meet me at ", "interval": 195, "after": 350},
    {"do": "glide", "word": "sunset", "after": 900},
    {"do": "flick", "key": "t", "dir": "up"},
    {"do": "hold", "key": "e", "ms": 800},
    {"do": "drag", "key": "space", "dx": -2.6, "ms": 900, "holdMs": 420},
    {"do": "suggestion", "index": 0}
  ]
}
```

- `field` is which test-pad field to type into, counted down the screen: 0 is the word field
  where tap decoding and suggestions run, 1 the username field, 2 the cursor pad.
- `caption` on any step puts a caption on screen for as long as that step lasts. `after` is the
  pause that follows a step; `interval` is the gap between keystrokes.
- `dx`/`dy` are in key widths, so they mean the same thing on any screen.
- `scatter` (0-1, default 0.5) scales how far taps land from the key centre. The offsets come
  from `SpatialModel`'s fitted thumb; at 1.0 the demo mistypes as often as a person does.

Render options: `--style bare` drops the device frame, `--zoom keyboard` crops to the keyboard
and the line being typed, `--width 1080` scales the output, `--no-captions` leaves the text off,
`--offset-ms N` nudges the circles later or earlier.

## How it fits together

1. **`DemoGeometry`** (instrumentation on the keyboard) measures where every key is, from the
   keyboard's own `LayoutGeometry`. Cached per screen size in `build/demo/`; a take reuses it.
2. **`tools/demo/plan.py`** turns the scenario into pointer samples with millisecond timings --
   curved glide paths, speed dips at the letters, scattered taps, varied rhythm.
3. **`tools/demo/demo.sh`** raises the keyboard, starts `screenrecord`, and runs
4. **`DemoPlayer`** (instrumentation in `demodriver`) which injects the samples and logs them.
5. **`tools/demo/render.py`** lines the log up against the video and draws the circles, trails,
   ripples, device frame and captions.

## What this cost to get right, so it is not rediscovered

- **The emulator must be headless.** macOS throttles its GPU the moment the window is not
  frontmost: a windowed take captured 59 frames in 44 seconds. `-gpu swiftshader_indirect`
  manages about 10 fps. Headless with `-gpu host` renders at ~55 fps and cannot be occluded.
- **`am instrument` kills the app it targets**, on the way in and on the way out. That is why the
  injector lives in `demodriver`, an empty app that owns nothing on screen: driving it from the
  keyboard's package closed the keyboard, and from the test pad's it restarted the test pad and
  landed the first gestures on the launcher.
- **Measuring kills the keyboard**, so `demo.sh` warms it again afterwards. A take that followed
  the measurement immediately spent its first four seconds typing into a keyboard that was still
  starting, and ended up holding one word out of six.
- **Raising the keyboard is the fragile step.** It is done by the test pad itself, from
  `onCreate`, on `am start ... --ei demoField <n>`; a tap raises a keyboard only when it *changes*
  which view has focus, and a warm relaunch is dropped silently.
- **The video and the touch log are lined up by correlation**, not by clock arithmetic across
  adb, which was out by more than 100 ms. `render.py` matches the whole gesture sequence against
  how much the keyboard changes frame to frame.
- **A glide must leave its first key inside ~100 ms** or the keyboard reads a long press: an
  earlier easing curve turned a glide for "sunset" into the accent popup over the s.

## Checking a take before shipping it

The keyboard really decodes what the script types, so watch the last frame: a glide can come out
as a different word (`sunset` has decoded as `summer`). That is honest, and it is also a reason to
choose demo words that decode cleanly, or to re-record.
