# Implementation notes

Why this keyboard works the way it does, and what every tuned number is. Deeper mechanics for
the cursor system are in `docs/CURSOR_AND_SELECTION.md`; this is the shorter account of the
ideas that made it good, and the parameters worth defending.

Working-environment traps (adb quirks, device confirmation screens, coordinates) deliberately
do **not** live here.

---

## The ideas that made it work

### The marker leads and the caret follows, not the reverse

The first design moved the *caret* in character steps and drew the marker relative to it. Every
visual defect the feature had came from that: caret jitter, asynchronous position reports and
character-width error all landed in the thing the user is watching.

Inverting it fixed a whole class of bugs at once. The finger drives the marker in screen pixels
and nothing else can move it, so it cannot glitch. The caret is steered toward it afterwards.
Free roaming past the end of a line and staying in sync over distance both became consequences
of the design rather than features needing their own code.

### Never integrate a quantity you cannot measure

Every loop re-derives its error from the position the app reports, then acts. A wrong
character-width estimate costs one extra round instead of accumulating. The earlier open-loop
version — bank finger travel, divide by an estimate — drifted linearly with distance, which is
exactly what "the further it goes the less accurate it gets" was.

### Prefer a stateless constraint to a stateful correction

Three separate bugs were latches. Detecting that something went too far and undoing it needs a
flag to stop it happening again, and *whatever clears that flag* becomes the new bug: vertical
jitter cleared one, a vertical step cleared another, and both produced rapid flicker.

Clamping instead — refusing to go past a line's bounds, refusing to let the marker travel where
the caret cannot follow, refusing a step that would leave the visual row — has nothing to get
stuck or released at the wrong moment.

### Round to nearest, and compare strictly

Stepping only after a *whole* unit of travel lets the marker lead the caret by a full character
before it follows, and the caret then lands past it. Stepping once past *half* keeps them within
half a unit. The comparison must be strict: at exactly half a unit, `>=` steps one way, lands on
the opposite half boundary, and oscillates forever — an infinite loop in a touch handler.

### Two tricks that are invisible to the user

Both come from the same observation: Android draws a selection as `min..max`, so the *order* of
its span is free to be used for something else.

- Storing the selection **reversed** makes the app report the end being dragged rather than the
  fixed one, which is the only way to get a feedback signal for selection at all.
- **Swapping** that order rather than collapsing lets an arrow key act on the dragged end
  without the highlight ever disappearing, which is what removed the per-line flicker.

---

## Why the UI reads well

- **The geometry is measured, not guessed.** Key sizes, gaps and the palette were taken from
  Gboard on the device by scanning screenshot pixel runs for colour changes. Horizontal geometry
  reproduces it exactly — our key edges land on the same pixels.
- **iOS arrangement, Gboard sizing.** The layout is the one that was asked for; the proportions
  are the platform's, so it does not feel foreign next to other Android keyboards.
- **The granular cursor is a bare marker.** It started as a pill with a percentage readout and
  read as misplaced, because the eye lands on the pill body rather than the point it indicates.
  A cursor should look like a cursor.
- **Vector glyphs, never colour emoji.** The globe and microphone were emoji at first and were
  the single ugliest detail against Gboard's flat monochrome icons.

---

## Tuned parameters

### Keyboard geometry — `layout/Metrics`

Measured from Gboard at 1080px / 360dp wide, expressed as ratios so the proportions hold at any
screen size.

| | dp |
|---|---|
| key width / height | 30.67 / 40 |
| key gap / side margin | 4.96 / 4.33 |
| row gap | 10.33 |
| suggestion strip | 50 |
| corner radius | 8 |
| total height | 249 |

Palette: `#ECEDFB` ground, white keys, `#E2DFFF` special keys, `#181B25` text.

### Gesture thresholds — `gesture/GestureConfig`

| parameter | value | why |
|---|---|---|
| `longPressMs` | 500 | matches the platform |
| `flickDistanceRatio` | 0.45 × key height | far enough not to trigger on a sloppy tap |
| `verticalDominance` | 1.5 | a flick must be clearly vertical, or it is a glide |
| `glideDistanceRatio` | 1.2 × key width | when a press becomes a glide |
| `flickToGlideRatio` | 2.0 × key width | a longer path promotes a flick to a glide |

### Trackpad gain and acceleration

Base gains are the multiplier the acceleration curve leaves untouched at low speed, so they are
what precise positioning actually feels like. Both were raised from their first values because
covering distance was tiring even though accuracy was good.

| | gain | accel starts | full accel at | ceiling |
|---|---|---|---|---|
| horizontal | 1.17 | 0.15 px/ms | 2.2 px/ms | 4× |
| vertical | 1.45 | 0.10 px/ms | 1.1 px/ms | 7× |

The curve is `1 + (max − 1) · ramp²`. **Squared, not linear** — that keeps the multiplier near 1
through the whole slow range, so reach is bought without spending precision. Speed is smoothed
with an exponential moving average (0.4) because a single move event is a noisy estimate.

The axes have **separate curves on purpose**. They began shared, so acceleration could only scale
a gesture and never bend it, but a keyboard-sized trackpad has far less vertical room than
horizontal and vertical has to cover a whole document. At 0.6 px/ms vertical is already at 2.5×
while horizontal is at 1.14×. The accepted cost is that a fast diagonal drag is steeper than the
finger's own path.

### Cursor and selection

| parameter | value | why |
|---|---|---|
| trackpad step rounding | nearest, strict `>` | see above |
| character width fallback | 0.33 × line height | average lowercase advance; measured thereafter |
| row-edge margin | 1.2 characters | a wrapped row reaches the editor edge by definition |
| selection horizontal cap | ±24 characters per round | bounds a correction, the loop does the rest |
| edge scroll rate | 260 → 45 ms per line | proportional to distance past the edge |
