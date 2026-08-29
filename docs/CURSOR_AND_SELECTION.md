# How the granular cursor and selection work

The spacebar trackpad moves a *granular cursor* — a marker that can sit between characters and
travel freely in two dimensions — and the editor's own caret follows it. This document records
the techniques that make that work, and the several designs that had to be discarded first.
Most of them exist because of a specific, non-obvious platform behaviour; those are marked
**[platform]** and also appear in `NOTES.md`.

---

## 1. The central inversion: the marker leads, the caret follows

The first design had the trackpad move the *caret* in discrete character steps, and drew the
marker relative to wherever the caret had ended up. That is backwards, and it is the root cause
of every visual defect the feature had: caret jitter, asynchronous position reports and
character-width error all landed directly in the thing the user is watching.

Now the finger drives the marker in **screen pixels**, and nothing else can move it:

- `TouchFsm` knows nothing about characters or lines. It emits `TrackpadPan(dx, dy)` — raw
  movement scaled by a gain — and that is all.
- The marker's position is never derived from the caret, so no caret behaviour can make it jump.
- The caret is steered toward the marker afterwards, as closely as the text allows.

Two behaviours that previously needed special-case code now fall out for free: the marker roams
past the end of a short line because nothing ties it to the text, and it cannot drift away from
the caret because the caret is always steering toward it.

## 2. Closed-loop steering, never dead reckoning

Both the caret chase (`chaseCaret`) and the selection steer (`steerSelection`) work the same way:

1. Read where the app says its caret is.
2. Compute the pixel error against the marker.
3. Convert to a number of characters or lines using the current estimate.
4. Apply it, and re-measure next round.

The estimate is therefore never trusted. A wrong character width costs **one extra round**, not
a permanent offset. The earlier open-loop version — bank up finger travel, divide by an
estimated width — accumulated error linearly with distance, which is exactly what "the further
it goes the less accurate it gets" was.

The rule of thumb: **any quantity you cannot measure must not be integrated.**

## 3. Getting a usable feedback signal for selections **[platform]**

`CursorAnchorInfo`'s insertion marker reports the position of `getSelectionStart()`. With a
selection in place that is the **left** end — so while extending rightwards the reported
position never moves, and a loop steering on it runs away. Measured on the device:

```
setSelection(204, 211) -> insH = 409.6   (position of 204)
setSelection(211, 204) -> insH = 548.6   (position of 211)
```

So the selection is deliberately stored **reversed**: `setSelection(movingEnd, anchor)`. Android
draws `min..max`, so the highlight is identical, but the reported marker now follows the end
being dragged. That single trick is what lets selection use the same closed loop as plain
cursor movement.

## 4. Swapping span order instead of collapsing **[platform]**

An arrow key moves `SELECTION_END`, so for an arrow to change the line of the dragged end, that
end must *be* `SELECTION_END` — the opposite of the arrangement in §3.

Collapsing the selection to arrange that works, but the highlight visibly disappears and returns
on every line change. Swapping the order is invisible instead, because both orders describe the
same range:

```
setSelection(anchor, movingEnd)   // natural: arrow moves the dragged end
setSelection(movingEnd, anchor)   // reversed: reports the dragged end
```

A vertical drag now reports only growth, never a collapse:

```
[19,21] -> [21,19] -> [19,21] -> [19,24] -> [24,19] -> ...
```

Arrow keys only extend a selection when a genuine `KEYCODE_SHIFT_LEFT` is held —
`META_SHIFT_ON` on the arrow event alone is ignored, because `ArrowKeyMovementMethod.isSelecting`
reads the *text buffer's* meta state via `MetaKeyKeyListener`, which only a real key press sets.
So shift is pressed for the duration of the drag and released when it ends, including on cancel.

## 5. Asking for cursor updates correctly **[platform]**

`requestCursorUpdates(CURSOR_UPDATE_MONITOR)` delivers nothing until the cursor next *moves*. Two
distinct failures come from that:

- **No seed.** A gesture beginning with no editing in between never receives a starting
  position, so the marker is never placed and every path guarded on it silently does nothing.
  The symptom is peculiar and easy to misdiagnose: the feature works once, then needs a tap in
  the text before it will work again — the tap being what finally moves the cursor.
- **Deadlock.** A loop driven only by `onUpdateCursorAnchorInfo` never starts: no movement, no
  update, no movement.

The fixes are to request `CURSOR_UPDATE_IMMEDIATE or CURSOR_UPDATE_MONITOR`, and to run the
steering on **every pan** as well as on every update, using the last reported position.

## 6. Ordering hazards between setSelection and key events **[platform]**

`setSelection` and `sendKeyEvent` reach the editor by different routes. The *operations* are
applied in order, but the positions echoed back to `onUpdateCursorAnchorInfo` interleave: the
echo of a `setSelection` arrives before the arrow that followed it has been applied.

Code that re-applies its own state on that echo silently overwrites the arrow. This is what
stopped selections crossing paragraph breaks — every vertical step was undone the instant it
was made, with no error anywhere and the symptom simply being "nothing moved".

The fix is to treat a report identical to the pre-step position as *not yet applied* and keep
waiting, with a small tick budget so a genuinely impossible move (the end of the text) cannot
wait forever. Line changes are also serialised — one in flight at a time — because a second
step computed from a stale position undoes the first.

## 7. Clamp, don't detect-and-undo

Correcting by whole characters overshoots the end of a short line. The first approach detected
the resulting wrap and reverted it, suppressed by a "blocked" latch. That fails twice over: the
revert is a visible jump, and anything that clears the latch — vertical jitter during a real
finger drag — restarts the cycle, producing a rapid flicker.

The target is now **clamped** into the bounds of the moving end's own line. Being stateless it
cannot be stuck or released at the wrong moment; the selection simply rests at the line end
while the marker carries on past it. Line bounds come from a snapshot of the text taken with
`getExtractedText` when the drag begins — no editing happens mid-drag, so it cannot go stale.

Generally: **prefer a stateless constraint over a stateful correction.** Latches acquire bugs
in whatever clears them.

## 8. Quantisation: round to nearest, and do it strictly

Movement is continuous; caret positions are not. Two rules:

- **Round to nearest, not truncate.** Stepping only after a whole unit of travel means the
  marker leads the caret by up to a full character or line before it follows, and the caret then
  lands past it. Stepping once past *half* a unit keeps them within half a unit of each other.
- **Compare strictly.** With `>=`, a residual sitting at exactly half a unit steps one way,
  lands on the opposite half boundary, and oscillates forever — an infinite loop in a touch
  handler. `TouchFsmTest` pins this.

`roundToInt` also supplies the deadband for free: an error under half a unit yields zero and
nothing is sent, so the caret does not dither around the marker.

## 9. Measuring the character width rather than assuming it

Android exposes exact character bounds only for *composing* text, which trackpad movement is
not. So the width is measured from the caret's own behaviour: the distance it actually travelled
divided by the number of steps that produced it.

Two details matter. It is measured only from **purely horizontal** moves, because changing line
moves the caret's x arbitrarily and would poison the estimate. And the fallback before any
measurement exists is a third of the line height, roughly the average advance of lowercase
proportional text — not the half that was assumed at first, which was visibly too wide.

## 10. Detecting that the caret cannot go further

Horizontal is deterministic: the character next to the caret is inspected, and a step that would
cross a hard line break is not sent. Vertical has no such tell — an arrow at the end of the text
is silently a no-op — so it is detected by observation: if a vertical push produces no movement,
the caret is at the end and the push is latched off, so that horizontal steering can still run
while the marker sits beyond it.

## 11. Drawing outside the keyboard **[platform]**

An IME cannot draw inside the target app's text field, but it can place a `PopupWindow`
anywhere on screen. Two traps:

- `CursorAnchorInfo`'s matrix yields **screen** coordinates; `showAtLocation` positions relative
  to the parent's **window** origin, which for an IME is the top of the keyboard — roughly
  1400px down on this device. Convert explicitly with `getLocationOnScreen` minus
  `getLocationInWindow`.
- The info arrives **asynchronously**, so the popup must be shown lazily once a position is
  known. Treating a missing caret as a reason to dismiss it destroys it permanently, a few
  milliseconds before the position lands.

The marker is a bare bar of the reported line height, with no label: a cursor should read as a
cursor. It is clamped only to the display, never to the text.

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
- **Count cursor updates as a flicker metric.** A drag past the end of a line producing two
  updates is settled; the same drag producing dozens is cycling.
- **Measure the reference, don't eyeball it.** Gboard's geometry and palette were taken by
  scanning screenshot pixel runs with ImageMagick for colour changes, which gives exact key
  widths, gaps and margins. The same scan verifies our own output.

## A note on editing this file's implementation

`KeyboardService.onUpdateCursorAnchorInfo` was twice deleted by accident, by edits that replaced
a *range* of the file spanning code that had not been re-read. Both times the entire feedback
loop vanished and every symptom became "nothing happens", which is very easy to misattribute to
the change actually being worked on. Anchor edits on unique strings, and check the handler still
exists after touching this file.
