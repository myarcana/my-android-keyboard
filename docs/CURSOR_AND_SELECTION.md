# How the granular cursor and selection work

The spacebar trackpad moves a *granular cursor* — a marker that can sit between characters and
travel freely in two dimensions — and the editor's own caret is set to wherever it points. This
document records how, and why. Rules that exist because of a specific, non-obvious platform
behaviour are marked **[platform]**.

**There is no fallback.** If an editor cannot be mapped (§1), the trackpad does nothing in it.
An approximate cursor was tried and is worse than none (§2).

---

## 1. Absolute placement from a map of the text (`cursor/`)

Steering the caret toward the marker (§2) lagged and hunted, and both problems come from the
loop itself. So there is no loop.

When the hold takes, the keyboard asks the editor where every character in and around the
visible text is drawn, and builds a **`CaretMap`**: rows of caret stops, each an offset and a
screen x. After that, every finger movement is handled locally:

1. The marker moves by the finger's travel, in pixels.
2. `CaretMap.hit` turns the marker into an offset, with hysteresis.
3. If the offset changed, one `setSelection` is sent.

Nothing waits on the app, so the caret lands where the marker points as soon as the app redraws.
Nothing is estimated, so there is nothing to drift. The overlay draws two marks:

- **The marker**: a solid bar, continuous, at the finger's position.
- **The landing**: a faint bar at the stop the caret is being set to. It comes from the same
  map, so it moves in the same frame as the marker, and the app's caret then appears underneath
  it.

### Where the map comes from **[platform]**

The keyboard tries these sources in order:

| source | editors | how |
|---|---|---|
| `requestTextBoundsInfo` (API 34) | every platform `TextView`/`EditText`: Instagram, Notes-type apps, Firefox's address bar | Exact bounds for whole lines, with no side effects. The request rectangle extends a screen above and below, so the map covers some text off screen. |
| composing-region probe | Chrome/WebView, Compose, anything that reports composing bounds | `setComposingRegion` over about 700 characters each side of the caret (cut to whole lines). Read `CursorAnchorInfo.getCharacterBounds`, then `finishComposingText`. No text changes. |
| replacement probe | Firefox `<input>`/`<textarea>` | See below. |
| none | terminals, games, custom views | The trackpad is inert. |

**Firefox (GeckoView)** reports character bounds only for a composition that *Gecko* started
(`GeckoEditableSupport::UpdateCompositionRects` queries `TextComposition`). It never starts one
from a bare composing region; it needs composing *text*. So the text either side of the caret is
re-set as composing text identical to itself, as a `SpannableString` so that no underline span
is added. This is done in two halves, before and after the caret, with `newCursorPosition` set
so the caret never moves. The two halves are aligned using the caret report that comes with
each.

This is never done in a `contenteditable`: replacing rich text with a plain string would drop
its formatting. GeckoView gives itself away there by offering `contentMimeTypes`, which it does
only for `contenteditable`. In those the trackpad is inert.

### Keeping the map in register while the text scrolls

The map is in screen coordinates, and editors scroll. Every caret report says where the caret
at some offset is *now*; the map says where that offset *was*. Any difference is the text moving,
so the map moves by that amount. The marker does not move. That is the only use made of the
app's reports.

A per-source baseline is subtracted first. It is measured once, while the map is known to be
fresh, so that a caret drawn a pixel off its glyph edge does not read as a scroll. Soft-wrap
stops are skipped as references, because editors disagree about which row they draw them on.

GeckoView names no offset in its reports, so each report is matched to the single request
outstanding, and reports during a selection are ignored. It answers only while a composing
region exists, so a one-character region is held at whichever end of the text is far from the
caret. Gecko will not move its caret *into* a region it did not start itself, so a region the
caret could reach would stop the caret there.

### The visible band, and edges

Only rows inside the **band** are offered to the marker. The band is the editor's bounds where
they are trustworthy (never Firefox's: see "Trackpad in Firefox" in `NOTES.md`), cut off at the top of the keyboard,
and narrowed whenever the editor is seen scrolling to reveal a caret that was just placed. That
scroll measures where its visible edge actually is. A caret placed in a row the user cannot see
makes the editor scroll, which moves every row under the marker. That is the thrash this rule
prevents.

Past the band's edge, the marker parks, and a timer moves the caret one row (or one stop
sideways, in a single-line field) beyond the edge. The rate scales with how far past the edge
the marker is. The editor scrolls to reveal it, the map follows, and the timer repeats. When the
map runs out but the text does not, the source is asked again for more.

An editor that does not scroll to reveal a caret set by offset is detected after two
unrevealed steps. Edge scrolling then switches off for the gesture, and the marker keeps to the
visible text. Arrow keys are never sent.

### Smaller rules that matter

- **Rows tile the text with no shared offsets.** The offset at a soft wrap belongs to the row
  the editor draws it on, which is the start of the next row. The end of the row above therefore
  stops one short. A map that put it on both rows would predict the caret on a row where the
  editor does not draw it.
- **Partial rows are dropped.** A probe window cut part-way through a soft-wrapped row would
  start with a fragment of that row, and the fragment would snap the caret to its first known
  stop.
- **Whitespace with no width has no stop of its own**; its caret is the end of the row before.
  Browsers collapse the space at a soft wrap this way.
- **One `setSelection` in flight.** While one is unacknowledged, only the newest target is kept.
  An editor slower than the touch rate would otherwise replay a queue of positions, which is lag.
- **Hysteresis** of a quarter of a character and a fifth of a row. It is small enough that the
  caret visibly tracks, and large enough that a resting finger never flickers between two stops.
- **Selections** are `setSelection(anchor, end)` with the dragged end second, so the editor
  scrolls to follow that end. No shift key and no arrows are involved. The order is normalised
  when the drag ends.
- **Stale reports are held back** **[platform]** (`StaleReportFilter`). Chromium (Chrome,
  WebView, and apps built on one, including ColorOS Notes) sends two reports per caret move:
  first the new offset with the *old* position, then the new position a frame later. Taken at
  face value the first says the text scrolled. A report whose offset changed but whose position
  did not is held for 60 ms and dropped if a moved one follows.

The pure geometry (`CaretMap`, `CaretMapBuilder`, `CaretSteer`) has no Android dependencies and
is covered by `CaretMapTest` and `CaretSteerTest`. `PreciseCursor` is the Android side.

---

## 2. Why not steer the caret with arrow keys

The first trackpad, and the one after it, moved the caret with arrow keys toward the marker,
closing the loop on the app's caret reports. Both were removed. The reasons, so nobody rebuilds
them:

- **It always lags.** Each correction waits a round trip to another process before the next can
  be computed. The caret trails the marker by that much at best.
- **It hunts.** The conversion from pixels to keypresses needs a character width, a line pitch
  and the wrap points of every row, none of which an IME is told. Every estimate was wrong
  somewhere, so the caret overshot, corrected, and overshot back.
- **It is fooled by the reports it steers on.** Chromium's stale first report (§1) read as "the
  caret did not move", so the loop sent the same arrows again on every report: runs of 30–80
  arrows each way were measured, the caret spraying across the line under the marker.
- **Arrow semantics vary.** Up on the first row goes to offset 0 in some editors and moves focus
  out of the field in others; a vertical step remembers a column nobody asked for.

A map has none of these: the offset is computed locally, and sent once.

## 3. Drawing outside the keyboard **[platform]**

An IME cannot draw inside the target app's text field, but it can place a `PopupWindow`
anywhere on screen. Two traps:

- `CursorAnchorInfo`'s matrix yields **screen** coordinates; `showAtLocation` positions relative
  to the parent's **window** origin, which for an IME is the top of the keyboard — roughly
  1400px down on this device. Convert explicitly with `getLocationOnScreen` minus
  `getLocationInWindow`.
- The info arrives **asynchronously**, so the popup must be shown lazily once a position is
  known. Treating a missing caret as a reason to dismiss it destroys it permanently, a few
  milliseconds before the position lands.

The overlay covers the screen and is shown once per gesture; moving the marks is an invalidate.
Moving a bar-sized popup per touch event, as before, is a window relayout per event. The marks
are bare bars of the row's height, with no label: a cursor should read as a cursor.

## 4. Velocity sensitivity (pointer acceleration)

The gain from finger movement to marker movement is not constant: slow movement is left exactly
alone so fine positioning feels unchanged, and fast movement is multiplied so a flick can cross
a long line.

- Speed is measured per move event as `hypot(dx, dy) / dt` in **pixels per millisecond**. This
  required fixing a latent bug: the trackpad anchor was being rewritten as
  `PathPoint(x, y, anchor.t)`, preserving the *original* timestamp, so no per-event interval
  existed at all.
- A single event is a noisy estimate, so speed is smoothed with an exponential moving average
  (`trackpadSpeedSmoothing`, 0.4).
- The curve is **squared, not linear**: `1 + (max - 1) * ramp²` where `ramp` is the position
  between `trackpadSlowSpeed` (0.15 px/ms) and `trackpadFastSpeed` (2.2 px/ms). Squaring keeps
  the multiplier near 1 through the whole slow range, so precision is not traded away for reach.
  A linear ramp noticeably degrades fine control.
- **The axes have separate curves.** They began shared, so acceleration could only ever scale a
  gesture and never bend it — but there is far less vertical room on a keyboard-sized trackpad
  than horizontal, and vertical has to cover a whole document. Vertical therefore starts
  accelerating sooner (0.10 vs 0.15 px/ms), reaches its ceiling sooner (1.1 vs 2.2 px/ms) and
  goes further (7x vs 4x). At 0.6 px/ms vertical is already at 2.5x while horizontal is at 1.14x.
  The cost, accepted deliberately, is that a fast diagonal drag is steeper than the finger's own
  path.
- Speed resets when a drag begins, so one flick cannot leak acceleration into the next drag.

The base gains are `trackpadGainX` 1.17 and `trackpadGainY` 1.45 — the multiplier the curve
leaves untouched at low speed, and therefore what fine positioning actually feels like. They
were raised from 0.55 / 1.2 in two steps, both times because covering distance was tiring even
though precision was good.

Tuned values live in `GestureConfig`; measured on device, a 100px sample at ~100ms intervals
produces multipliers ramping 1.0 → 1.04 → 1.19 → 1.30 as the average builds.

Note that this is hard to exercise from `adb`: each `input motionevent` costs a round trip of
roughly 100ms, so scripted drags are always in the slow part of the curve. Acceleration has to
be verified from the logged pan magnitudes, or by hand.

---

## Testing techniques

The gesture cannot be driven the obvious ways, so:

- **`input motionevent`, not `input swipe`.** A swipe interpolates immediately, so the gesture
  becomes a glide before the long-press timer fires. `motionevent` sends `DOWN`, `MOVE` and `UP`
  as separate commands with real time in between.
- **A debug broadcast stands in for the second finger.** `adb` is single-pointer, so the
  two-finger selection gesture cannot be scripted at all. A receiver gated on `BuildConfig.DEBUG`
  substitutes for it — and note that implicit broadcasts are dropped for a backgrounded app, so
  it needs `-p <package>`.
- **Assert against text offsets, not pixels.** `adb shell dumpsys input_method` reports
  `mCursorSelStart` / `mCursorSelEnd`. Checking that a drag lands on offset 199 is a real
  assertion; squinting at a screenshot is not.
- **Read the trace.** Debug builds write every decision to `files/touch-trace.log` (pull it
  with `run-as`). `TP PC MAP source=…` says which source mapped the field, `PROBE seen` shows
  each report a probe received, `SCROLL` every movement of the text it followed, `FAIL` why a
  field could not be mapped.
- **Measure the reference, don't eyeball it.** Gboard's geometry and palette were taken by
  scanning screenshot pixel runs with ImageMagick for colour changes, which gives exact key
  widths, gaps and margins. The same scan verifies our own output.
