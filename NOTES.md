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

### A gesture made to order is not the gesture

The Gesture Lab used to print an instruction -- *"Swipe down on O to type 9"* -- wait, record, and
move on. The labels were unimpeachable, because it asked before it recorded. The gestures were
not.

Reading an instruction, finding the named key and performing the named movement is a different
motor task from typing. It is slower, more deliberate, and aimed at a key the eye has just
located rather than one the thumb already knows. Every threshold fitted to those was fitted to
somebody doing an exercise, and the keyboard will never see one of those again.

The fix keeps the label and throws away the instruction: show a **passage** and let it be typed.
The passage still says what each gesture is meant to be before it is made, so nothing about the
ground truth changes -- but the eye reads ahead, the thumb moves without being told where, and
the gestures come out at speed with typing's sloppiness in them.

The two passage kinds answer different questions and neither could do the other's job. Collision
streams -- `u u 7 um u 7 on 9 o` -- exist because the flick-versus-glide boundary lives on six
keys and nowhere else, so prose would spend a hundred gestures to collect three useful ones.
Prose exists because glide decoding is only half geometry: the other half is which words exist
and how often they are written, and a passage of random words would measure the shape matching
alone -- and flatter it, because random words sit further apart than real ones do.

### A lifted finger is not a finished word

A glide is one continuous stroke in theory and often is not in practice. A thumb crossing the
keyboard skips, catches on a screen protector, or leaves the glass for a frame going over a ridge
in it.

The naive handling of that is much worse than a wrong word. The fragment already drawn gets
decoded and **typed** -- into the field, while the finger is still travelling toward the rest of
the word -- and then the remainder types a second word beside it. One skip produces two wrong
words and a correction, for something the user never asked to be committed at all.

So a lift suspends the glide rather than ending it, and nothing is shown while it is suspended.
Not a preview, not a candidate: a word displayed and then replaced is the same flicker in a
quieter costume.

**The cost of that is 120ms of latency on every glided word**, paid by every word to protect the
one in fifty that was interrupted, and it is worth stating rather than hiding. The alternative --
type the word immediately, then delete and replace it if the finger comes back -- pays nothing on
the common case and pays the actual failure on the rare one: the wrong word really does get
committed, briefly, into a field that may have an undo stack or a listener watching it. A delay
short enough to sit inside the time it takes to look at what you typed is the cheaper of the
two.

What decides whether the finger came back is mostly **distance**, not time. A thumb that skipped
never meant to leave and returns within a key of where it went; a thumb starting the next word
has *travelled*, to the first letter of something else. Duration is the cheap first test rather
than the real one. The third condition is the one that is easy to leave out: a finger coming back
down on backspace or the space bar has plainly finished the word however fast it got there, and
reading that as a continuation would swallow the very keypress meant to correct it.

Neither number can be derived -- how long a thumb is off the glass when it skips is a fact about
a hand and a screen. So both are recorded with every gesture, along with the indices where the
strokes join, and the whole bank can be rescored under any other pair. The defaults below are a
starting point and are expected to move.

### The keyboard is already on Android's grid

Worth knowing before reaching for any of the below: the *arrangement* of this layout is iOS's,
but the *proportions* were measured off Gboard on the device, and Gboard is AOSP's grid. Every
letter lands within **0.054 of a key width** of where `rows_qwerty.xml` puts it -- five pixels on
the phone, a twentieth of the key being talked about -- with the residual coming entirely from our
visible gaps and side margin.

Nobody aimed at that; it fell out of measuring rather than guessing. `AospGridTest` asserts it
now, because it is the compatibility surface with every swipe decoder that exists, and it would
otherwise be rediscovered by someone who had already assumed the opposite. `LayoutGeometry`
exposes the same grid in the [0,1] key-area frame those decoders take their input in -- the key
area, not the whole view, because our suggestion strip is not somewhere a glide can go and
including it would push every key a fifth of the way down the square.

`docs/PLAN.md` carried a risk saying our geometry was *not* Android's and that a decoder would
need coordinate normalisation. It was wrong twice over -- see that file -- and believing it is
why the decoder below was written by hand.

### Two channels, and the second one asks the question backwards on purpose

Comparing a glide to a candidate word by resampling both to 32 points and measuring point against
point is the obvious method, and on its own it is remarkably weak. "how" and "house" leave the
same key, sweep right and then far left, and cover nearly the same distance doing it; point for
point they are equally good matches for each other's gesture, and "how" is the commoner word, so
it wins. Measured over synthetic glides, that comparison alone got 61% of words right.

The channel that fixes it asks about the word's keys rather than about the gesture's samples:
*was the finger ever near `h`, and then near `o`, and then near `w`* -- a monotonic alignment,
pinned at both ends. A glide of "house" has no answer to offer for `w`.

Getting the direction wrong is subtle and costs everything. The first version asked whether the
finger was always near *some* key of the candidate, which every word answers well, because a
glide spends most of its time in the gaps between keys and there is always a key nearby. The
correct word scored no better than a wrong one and frequently worse.

The other thing that channel needs is a **finer grid than the comparison does**. At 32 samples a
long word puts a quarter of a key width between neighbouring samples, so a finger that went
straight over a key centre is still recorded as having missed it by that much -- uniformly, which
made every long word look badly executed. Whole-path comparison is happy to be coarse because
both sides are coarse in the same places; asking about one key is not.

### Squaring is what lets a distance and a frequency be added

The shortlist that feeds the second pass originally scored `-distance + frequency`, and duly
filled its forty-eight places with the commonest words in English regardless of what had been
drawn. A linear cost lets frequency buy an unbounded amount of sloppiness. Squared over a
tolerance, a word three key widths off pays nine times what a word one key width off pays, which
no realistic frequency can make up.

The same form is used in both passes for a second reason: the cheap pass only has to keep the
right word *somewhere* in its top few dozen, and a shortlist scored on different terms from the
final ranking will discard words the final ranking would have liked.

### A short word hides inside a long gesture

"jd" fits comfortably along the first third of a glide of "keyboard", and every distance measure
that does not know the gesture kept going scores it on that third alone. The cheap fix is the
ratio of travelled length to the length the word asks for, as a log so that half and double cost
the same.

The expensive version of the same problem is in the lexicon rather than the decoder. Both the
crawl and web2 are full of three-letter entries nobody writes -- "wud", "hie", "ait", "phe",
"tk", "nr" -- and each one does not merely compete with the intended word, it *beats* it, because
the gesture passes through everything it asks for and then keeps going. A short word now has to
earn its place much harder than a long one: two letters must be in the crawl's first 2,500, three
in the first 10,000.

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

### Measure the overlap; do not ask the host where the navigation bar is

From targetSdk 35 the IME window is laid out edge to edge, so the keyboard has to reserve the
navigation bar's space itself. The obvious source for the size of it is the inset dispatched to
the input view -- and it is wrong often enough to matter: Firefox's address bar leaves the
window running to the bottom of the display while reporting a navigation-bar inset of zero, so
the bottom key row rendered underneath the back and home buttons in the browser and nowhere
else. A keyboard that is only as correct as the app it happens to be typing into is not correct.

So `KeyboardView` measures the overlap instead of being told it: how far its own bottom edge
reaches past the top of the navigation bar, from `maximumWindowMetrics` and
`getLocationOnScreen`. Nothing about the host enters into it.

Feeding a measurement back into layout usually oscillates. It does not here, and the reason is
worth keeping: the IME window is anchored to the bottom of the display, so making it taller
moves its top edge and never its bottom. The quantity being measured is therefore not a function
of the reserve it produces, and the second layout pass agrees with the first. It is the same
shape of argument as *prefer a stateless constraint to a stateful correction*, above.

### The return key is not a character

Return carries `"\n"` as its primary in the layout, but committing that string is nearly always
the wrong thing. An address bar, a search field and a chat composer each declare an *editor
action* -- Go, Search, Send -- and nothing happens until `performEditorAction` fires it; text
that merely appears in the field is ignored. A text box inside a web page has no editor action
at all, and submits its form because a key went *down*, so it wants a real `KEYCODE_ENTER`
rather than either of the other two.

That is three behaviours from one key, and only the field can say which. So the FSM stops
calling return a character key and hands the service a `SpecialKey`, and `ReturnKey.actionFor`
turns the field's `imeOptions` into an action or into nothing. `IME_FLAG_NO_ENTER_ACTION` is the
case that is easy to miss: a multi-line field declares an action so the key can be *labelled*
with it while still wanting the line break, and ignoring the flag turns every paragraph break in
a message app into a sent message.

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
- **The flick is a manoeuvre, not an animation.** Swiping down on a key drags its symbol out of
  the small grey slot and into the letter's own place — position, size and colour — while the
  letter drops out of the bottom of the key. It tracks the thumb pixel for pixel in both
  directions, so it can be done slowly, stopped halfway, and taken back.

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
| `longPressSlopRatio` | 0.20 × key height | past this the finger is not holding still |
| `glideDistanceRatio` | 1.2 × key width | when a press becomes a glide |
| `flickToGlideRatio` | 2.5 × key width | a longer path promotes a flick to a glide |

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

**A held-out session settled it.** The third session was the first recorded *under* these
thresholds, so it never informed them, and the shipped build got 77 of 80 right live on the
phone -- including `m` at 4/4, where six of the first eight had failed. Fitting and testing on
the same data would have proved nothing; this is the number worth quoting.

The three it missed were each a different near-miss, and only one was a threshold:

- an "ok" glide 122px long against the 129px that `glideDistanceRatio` 1.4 demanded, read as a
  plain tap. Short words that stop one row down have very little path to offer, so that value
  went back to 1.2 -- it had been 1.2 originally, and 1.4 was fitted to a smaller bank.
- an "ex" glide that had already been read as a flick and needed 246px to escape it, against the
  258px `flickToGlideRatio` 2.8 asked for. Now 2.5, the midpoint of the winning range.
- an "on" glide that **opened the accent popup**. Not a threshold at all: the finger dawdled for
  447ms before picking up speed, and the long-press fired at 500ms. A long press should mean held
  *still*, which is what it means everywhere else on the platform, so it now cancels once the
  finger has drifted past touch slop. The space bar is exempt -- holding space and starting to
  move before the timeout is the normal way into the trackpad, and no glide competes for it.

One gesture in 208 is still misread: an "ok" that left at a ratio of 4.3, right against the 4.25
threshold. That is the honest state of it -- see docs/GESTURE_BANK.md on why "ok" probably wants
the lexicon at decode time rather than another number.

Note that the harness check cannot tell an intentional change from drift. The long-press fix made
one recorded ACCENT replay as GLIDE, which is the fix working; a threshold moving under your feet
would look identical in that report. Read the disagreements, do not just count them.

### Glide typing -- `glide/GlideConfig`, `gesture/GestureConfig`

Decoding is two passes: every word in the bucket scored by a cheap point-for-point comparison,
then the best forty-eight aligned to their own keys properly. Both passes score
`-cost^2 / 2 tolerance^2 + frequency`, and the cost is a weighted sum of channels measured in key
widths.

| parameter | value | what it is |
|---|---|---|
| `samples` | 32 | points both paths are resampled to for the whole-path comparison |
| `visitSamples` | 128 | finer grid for the per-key test; see above for why it must be finer |
| `endpointRadiusRatio` | 1.15 | how far a word's first/last key may sit from the gesture's ends |
| `shortlist` | 48 | words the cheap pass hands to the expensive one |
| `locationWeight` | 1.0 | whole-path distance, the reference weight |
| `visitWeight` | 1.2 | how close the finger came to each key, in order |
| `shapeWeight` | 0.3 | the same paths with position and size normalised away |
| `lengthWeight` | 2.5 | log ratio of travelled length to the word's own length |
| `toleranceRatio` | 1.0 | error, in key widths, at which a match stops counting |
| `frequencyWeight` | 1.8 | what one decade of word frequency is worth |

**Only the ratio of the last two matters**, so the tolerance is pinned at one key width and the
frequency weight is the dial. Swept over synthetic glides the whole region from 1.2 to 2.5 scores
within noise of itself: the value above is the middle of a plateau, not a peak.

Measured over 38 words x 3 seeds, straight through the key centres and then with progressively
worse hands:

| | first choice | offered in five |
|---|---|---|
| straight through the centres | 97% | 100% |
| a steady hand (0.18 key jitter, corners cut 18%) | 92% | 100% |
| an ordinary thumb (0.28, 30%) | 85% | 100% |
| a hurried thumb (0.40, 45%) | 57% | 88% |

**These are drawn glides, not recorded ones, and the difference matters.** They are the reason
the decoder can be changed without flashing a build, and they will fail loudly if a change breaks
a class of words -- but a synthetic thumb wobbles the way its author imagined a thumb wobbles.
The numbers that will actually set these weights come from prose passages in the Gesture Lab, the
same way the flick thresholds came from the collision drills.

Decoding takes **0.23 ms** per word over 40,000 words on a laptop JVM, which is what makes it
affordable at the lift rather than on a background thread with a result to reconcile afterwards.

### The lexicon -- `assets/lexicon_en.tsv`

40,000 words, 500 KB, generated by `tools/build_lexicon.py` from Norvig's `count_1w.txt` (the
ranking) and macOS's web2 (the "is this a word at all" test). Committed, not fetched: the app has
no INTERNET permission and could not download it if it wanted to.

Four rules do the work, and each one was a bug first:

- **The dictionary check applies only past rank 15,000.** The head of the crawl is where the
  modern vocabulary lives -- "blog", "iphone", "website" are all absent from web2 -- and the tail
  is where the typos and product codes live. One rule for both either admits the junk or rejects
  the vocabulary.
- **Inflections count as known if their stem is.** web2 lists headwords, so it has "peep" and
  "message" but not "peeped" or "messaged", and an inflected form is exactly what a phone types.
- **Short words need a much better rank**, for the reason above: their shapes are subsets rather
  than rivals.
- **Contractions are scored from their bare form, but only when the bare form is not a word.**
  "we'll" strips to "well", and taking that count scored the contraction as if every use of
  "well" were one -- which tied them exactly, competing for a shape only one of them can win. The
  rest are floored at a fixed share of their leading word, because "theyve" is written by almost
  nobody while "they've" is written constantly. Where the two spellings share a shape and the
  contraction is the commoner one -- "it's", "I'll", "I'd", "let's" -- an explicit list says so.

### The finger-lift window -- `gesture/GestureConfig`

| parameter | value | why |
|---|---|---|
| `glideResumeMs` | 120 | long enough for a skip, short enough that a deliberate reach beats it |
| `glideResumeRadiusRatio` | 1.25 x key width | a finger that skipped comes back where it left |

Both are placeholders with a mechanism behind them rather than measurements. They are recorded
with every gesture, the stroke boundaries are recorded in the path, and `tools/gestures.sh
analyse` prints what the lifts actually looked like -- duration and distance, min to max, against
the window -- plus the statistic that decides whether the leniency is helping at all: how well
rejoined glides decode compared with uninterrupted ones.

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

### The flick — `view/KeyboardView`, `gesture/TouchFsm`

| parameter | value |
|---|---|
| symbol travel | 0.46 key heights, 1:1 with the finger |
| commit point | 0.20 key heights — 44% of the way down |
| symbol size / colour | 0.30 → 0.62 key units, `#6B6B7B` → `#181B25` |
| letter | ×0.74, 0.46 key heights down, clipped at the key edge, gone at the commit point |
| settle after release | 40 ms time constant |

**The symbol is dragged, not played.** It moves exactly as many pixels as the thumb does — the
travel ratio is the view's own glyph geometry, the 0.46 key heights between the symbol's resting
slot and the letter's baseline — so a slow pull is slow, a fast one is fast, and one that turns
round comes back up under the finger. Nothing is eased, tweened or timed while a finger is down.
The only thing animated on a clock is the settle *after* the lift, when there is no finger left
to follow.

An earlier version chased the finger exponentially instead, on the reasoning that a flick commits
after 24px and a fast thumb crosses that in three or four samples. That reasoning was about the
wrong distance: the glyph's own travel is 55px, and real flicks in the bank pull a median of
112px. There was never a shortage of samples to move across, and the lag it added was the whole
difference between dragging something and watching a clip of it being dragged.

**The pull has a detent, and the letter's fade is where it is.** The commit point stays at 0.20
key heights — Android's touch slop, measured, and raising it is what lost ten of sixty-four flicks
the first time round — so the symbol is only 44% home when the gesture arms. The letter is
therefore faded to nothing at exactly that fraction, computed from the two ratios so they cannot
drift apart. A key showing no letter will type its symbol if released; one showing a letter will
type the letter. The detent can be felt without being explained.

**Arming is read from the finger's position at release, never latched at the crossing.** That is
what makes the pull something you can change your mind about halfway through, and it is the
stateless version of the constraint — no flag to be set, and so no wrong moment for it to be
cleared. It leaves the FLICK state alone rather than dropping back to PRESSED, because the glide
promotion is far more forgiving from FLICK (2.5 key widths against 1.2) and a pull-and-return
would otherwise start gliding a word.

Safe against the bank, which is why it was done at all: of 208 recorded gestures, **none** crossed
its own threshold and then lifted back above it. Real flicks retract 0.0px at the lift by median
and 0.5px at the 90th percentile, and the shortest recorded flick still ended 33.7px down against
the 24px asked for. Replaying the whole bank under the new rule reproduces every verdict the
device reached, to the sample.

### Cursor and selection

| parameter | value | why |
|---|---|---|
| trackpad step rounding | nearest, strict `>` | see above |
| character width fallback | 0.33 × line height | average lowercase advance; measured thereafter |
| row-edge margin | 1.2 characters | a wrapped row reaches the editor edge by definition |
| selection horizontal cap | ±24 characters per round | bounds a correction, the loop does the rest |
| edge scroll rate | 260 → 45 ms per line | proportional to distance past the edge |
