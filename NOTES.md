# Implementation notes

Why this keyboard works the way it does, and what every tuned number is. Deeper mechanics for
the cursor system are in `docs/CURSOR_AND_SELECTION.md`, and the flick-versus-glide data
collection in `docs/GESTURE_BANK.md`; this is the shorter account of the ideas that made it
good, and the parameters worth defending.

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

### A threshold nobody can derive has to be measured

Flick-down-for-the-symbol and the first stroke of a glided word are the *same gesture* for the
first two key heights. `i` sits at 7.5 key widths across; `k` and `m` both sit at 8.0. So gliding
"I'm" leaves the i key going down and very slightly right -- which is exactly what flicking i for
its `8` looks like. The vertical-dominance rule separates them not at all: both are
overwhelmingly vertical.

Only a narrow class of words does this, and picking that class out took two attempts. A word has
to be **two keys long with the second below the first**: two keys so the glide is a single stroke
with no corner to give it away, below so the stroke points where a flick points. "was" satisfies
every looser version of that rule -- it starts downward, and it even ends below where it started
-- and it is still perfectly safe, because the path turns at a and comes back. The whole list is
I'm, in, ok, on, um, ex.

Which one it is depends on what the person meant, and that is not in the touch data. No amount of
thinking about the paths produces the threshold, because the information needed to pick it is not
in the paths -- it is in the head of whoever made them.

So it is asked for instead. The Gesture Lab names one gesture, watches it happen, and files the
raw path under what it asked for; `tools/gestures.sh analyse` then replays every sample through
the real state machine and sweeps the four thresholds against them. Raw paths, not extracted
features: a feature is a guess about what matters, and the whole premise is that nobody knows yet.
The bank is the durable part -- thresholds will be replaced, and the recordings will still score
whatever replaces them.

Three details keep it honest. Drills alternate symbol, word and plain tap within a key, because
eight flicks in a row are one sample of a rhythm rather than eight samples of a flick. The sweep
reports the *middle* of the tying region rather than the first point in it -- thousands of
threshold sets score identically on any real bank, and one on the edge of that region is a single
unusual swipe from being wrong. And plain taps are drilled at all, which is the one that was
missed first time round: **the flick threshold trades off against taps, not against glides**, so a
bank without them lets the sweep drive that threshold to zero unopposed. It did exactly that --
recommending 12 pixels, under Android's own touch slop -- on 48 samples containing no ordinary
keypress at all.

### Where a row ends can only be learned by watching it wrap

Nothing tells you where a soft wrap falls. `editorBoundsInfo` is not published by every editor,
and even when it is, it gives the editor's edge -- rows wrap at a word boundary well short of
it. Traced on device, the caret wrapped at x=931 inside an editor 1080 wide, so every
edge-based guess was 150px too late and never fired once.

The wrap teaches it instead: a rightward step that lands on a lower row means the previous
position was the row's end. Measured values on the test pad are 832 and 856 -- word-dependent,
as expected, and nowhere near the editor's edge.

That number belongs to **the row it was learned on**, and only that row. Applying it document
wide was a regression: every row running past the learned value became impossible to move
through, because the block fired part way along it. It is scoped by the row's top coordinate,
so arriving on another row simply has nothing learned yet.

The wrap that teaches it is also undone immediately with a single step back, rather than left
for the vertical correction to drag the caret to the row's start and walk it out again.

### Never act on a stale reading more than once

Every arrow in a burst is computed from a single reading of the caret's position, so a long
burst is dead reckoning: a row edge reached part way through it is not noticed until the whole
burst has been sent. Tracing a real gesture showed **837 arrow keys for 73 touch events** — the
caret leaving a row mid-burst, landing on the far side of the wrap, and restarting an enormous
error from there, forever.

Bursts are capped at 4 steps. Converging over several short rounds costs nothing, because each
arrow produces its own position report to steer from, and it means no single decision can carry
the caret past a boundary it cannot see.

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

### Canonical Unicode order is a taxonomy, not a ranking

The emoji bar ranks in tiers -- named for the word, then tagged with it, then a prefix of
either -- and something has to break the ties inside a tier, because most queries are a tag
match with several plausible answers.

Canonical order was the obvious tiebreak and it is quietly wrong. It groups by *kind*, so
whichever member of a tie happens to sit in an earlier group wins: "car" found the railway
car, "water" the water buffalo, "drink" the baby bottle, "light" the police car light. Each
one is arguable on its own; together they made the bar feel like it did not know English.

Unicode publishes an actual frequency ranking (home.unicode.org/emoji/emoji-frequency), a
table of ~1450 emoji ordered by measured median use. Sorting the generated asset by it fixes
all four, and the app needs no ranking data at all -- file order *is* the ranking, so the
runtime stays a dumb tier sort and re-ranking means regenerating the asset.

One refinement was tried and rejected: promoting, within a tier, the emoji whose *name* starts
with the query. It fixes "birthday" (the cake, not the party popper) and breaks "heart" and
"love", which both stop finding ❤️ and offer the heart *suit* instead. Two of the most-typed
words in the language outrank one; frequency alone is the better rule.

### Clearing the line, not the field

Swipe up on backspace clears back to the start of the line. In a single-line field -- the
common case -- there is no line break to stop at, so that is the whole field, which is what the
requirement asks for. Starting from the beginning of a line there is nothing on it to clear, so
it takes the line above instead, and repeating the gesture walks a paragraph away a line at a
time.

Deleting the entire field outright from anywhere was the first design and it is a trap: it is
the only gesture on this keyboard that can destroy text the user cannot currently see, and
there is no undo to answer for it. The threshold is also deliberately larger than the flick
threshold (0.8 vs 0.45 key heights) -- a thumb drifting up off the key must not fire it.

### The bar reads the editor, it does not remember what was typed

Suggestions are for the word the caret sits at the end of, found by reading back through
`getTextBeforeCursor` on every change. Keeping our own buffer of keystrokes would be cheaper
and would be wrong the instant the spacebar trackpad moved the caret somewhere else -- the bar
would be offering emoji for a word elsewhere on screen. Tuned numbers: 48 characters of
lookbehind (no emoji name is longer), two-word queries tried before one-word ones so "thumbs
up" beats "up", and a two-character minimum, below which the bar is noise.

### Held backspace accelerates

500ms to start (the shared long-press timeout), then a character every 55ms, then whole words
every 140ms after 18 repeats -- about a second in. A fixed character rate is either too slow to
clear a sentence or too fast to stop on the word you meant; both rates exist so neither has to
compromise. Word deletion stops at a line break rather than running past it.

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
| `flickDistanceRatio` | 0.20 × key height | Android's touch slop; below it the OS calls the finger still |
| `verticalDominance` | 4.25 | what separates a flick from gliding "ok" |
| `glideDistanceRatio` | 1.4 × key width | when a press becomes a glide |
| `flickToGlideRatio` | 2.8 × key width | a longer path promotes a flick to a glide |

The bottom four are the tuning surface for flick-versus-glide, and they are the four stored with
every gesture recording so an old verdict stays interpretable. They are no longer by-feel: these
are what 128 labelled gestures said, taking balanced accuracy from 89.1% to 99.0%. What each one
is actually doing, because the numbers alone do not say:

**`flickDistanceRatio` 0.45 → 0.20.** The old value lost ten of sixty-four flicks outright --
six on `m`, where the bottom row leaves nowhere to swipe to and the whole gesture fits in 38 to
46px against a 54px threshold. The measured classes do not overlap even slightly: taps travel
**zero** pixels (five to eleven move events at the identical coordinate) and the weakest real
flick is 27.8px. With an empty 27.8px gap, the data cannot pick a value inside it, so the
platform does: 8dp of touch slop over a 40dp key is 0.20, and below that Android still considers
the finger stationary. A keyboard that has decided you flicked while the OS says you have not
moved is broken rather than badly tuned.

**`verticalDominance` 1.5 → 4.25.** This one separates a flick from gliding "ok", and nothing
else does. On the o key, flicks leave at |dy|/|dx| of 6.6 and up; "ok" leaves at 1.6 to 4.3,
because k sits half a key left, so the word departs about 25 degrees off vertical. Curvature
looked like the obvious discriminator and is exactly wrong: "ok" is the *straightest* gesture in
the bank (straightness 0.999, bow 0.03 keys), straighter than the average flick, several of which
hook through 80 degrees as the finger lifts.

**`flickToGlideRatio` 2.0 → 2.8.** The original complaint -- a vigorous flick promoted into a
glide -- though it turned out to be the smaller half of the problem: two failures against ten
under-travelled flicks.

**`glideDistanceRatio` 1.2 → 1.4.** Barely earns its change; it was flat across most of its range
in every sweep so far.

One gesture in the bank is still misread: an "ok" that left at a ratio of 4.3, right against the
threshold. That is the honest state of it -- see docs/GESTURE_BANK.md on why "ok" probably wants
the lexicon at decode time rather than another number.

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
