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

### Borrowing a platform constant is not the same as having a reason

The flick threshold was pinned at 0.20 of a key height for months because that is Android's touch
slop, and the argument sounded airtight: a keyboard that decides you flicked while the platform
still calls your finger stationary is broken rather than badly tuned.

It was wrong twice over, and both errors are the kind that only a bank catches.

Touch slop is not a filter on coordinates. It is how far a child view may move before a scrolling
parent may steal the gesture -- a question about *arbitration between views*, which has nothing to
say about whether a finger moved. Nothing was ever suppressing those events. Meanwhile the thing
that genuinely does decide the finger is still is the digitiser, which reports a resting thumb at
one unchanging coordinate: all 94 taps in the bank travel exactly zero pixels, across five to
eleven move events each, to a tenth of a pixel. So the margin was already being taken once before
this code saw an event, and taking 8dp again on top of it was paying twice.

The cost was real and invisible until the passages got fast enough to produce it: flicks of 15.1px
and 23.0px, both read as plain letters. The number is now 0.025 -- one dp, three pixels -- and the
bank cannot argue with any value from 0.02 to 0.12, because the two classes have a gap between
them with literally nothing in it.

**What made three pixels safe is that distance stopped carrying the decision alone.** A flick has
to be downward *and* four and a quarter times more vertical than horizontal; a resting thumb does
not drift three pixels straight down. The lesson generalises past this number: a threshold that is
one clause of three can be set where the evidence actually is, and it is the single-clause tests
that have to be timid. Guarding the same gesture on an axis nobody disputes buys more than padding
the one everybody does.

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

### The rig that needs the cable gets used once a week

Everything above about how a gesture is *asked for* was settled while the phone was plugged into
the machine that reads the bank. That left a second question unasked for a long time, and it
turns out to bound the data harder than any of the labelling decisions: **how often does anyone
sit down and do this?**

Started from a terminal, the answer is a few half-hour sittings a month, by a hand that knows it
is being watched for the whole thirty minutes. Carried around as an app that opens on a home
screen, it is several minutes a day, several days a week, by a thumb that has stopped paying
attention -- which is the thumb the shipped keyboard actually has to read. The second bank is
larger and it is also better, and neither of those came from a change to what the lab asks for.

Four things were in the way, and none of them was the passage:

- **It opened on passage zero every time.** Fine for a session that is a visit; for four minutes
  in a queue it means collecting the first passage of a corpus, several hundred times. The
  position now lives in `SharedPreferences` and the deck advances when a passage is *finished*,
  not when Next is pressed, because being abandoned after the last word is the ordinary way a
  session ends.
- **There were five prose passages.** A thumb that types the same two hundred words every
  evening gets better at those two hundred words, and every number on the screen improves while
  the keyboard does not. A hundred and fifty ordinary sentences in an asset deal out about fifty
  passages, which is weeks before a word comes round again.
- **The drill was a choice.** The collision stream is the least pleasant passage to type and the
  only one that answers the question the bank exists for. Offered beside forty passages of
  ordinary English it stops being chosen, and the bank goes on growing while the flick-versus-
  glide boundary gains nothing. It is dealt in before every fifth passage instead.
- **A fumbled label could only be withdrawn a week later, on a laptop.** A gesture the keyboard
  reads wrongly is the most valuable line in the bank; a gesture whose *label* is untrue is worth
  less than nothing, and the only person who can tell them apart is the one who just made it, for
  about two seconds. Void does it on the spot and keeps the recording.

The bank itself stays in internal storage, fsynced per line, and comes off over adb between
sessions. Shared storage was tried and taken back out: it survives an uninstall and it is also
readable by every app on the phone with storage access, which is not a trade a keyboard built to
be incapable of sending what it sees anywhere gets to make casually.

The general form: for anything that collects data from a person over months, the collection rate
is a parameter of the design, and it is usually the one with the most leverage. It is also the
one that never appears in a test.

### A file that is copied more than one way needs a rule about which copy wins

The bank exists in up to three places -- internal storage, the `Android/data` export, and the
repository -- and the merge rule that had been fine while only one was ever read turned out to be
actively destructive as soon as two were. It was *incoming wins*, which is the obvious rule and
the wrong one, because it assumes the copy being read is the newer one.

A record is written once and never changes, with a single exception: its label can be *withdrawn*
afterwards, by hand in the archive or from the lab's Void button. So the archive wins, and the
only thing an incoming copy may add to an id that already exists is a `void` the archive lacks.
The first pull under the new rule quietly reverted two withdrawals that had been made by hand a
week earlier, and it reverted them by reading a phone that had simply never heard of them --
which is what an *incoming wins* rule means in a system where every copy is behind in some
different way.

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

### The frame is the three letter rows, and nothing checks it for you

A layout-agnostic swipe decoder is handed the key centres and the finger's path in one [0,1]
square and has no other way to know where anything is. Get that square wrong and there is no
error -- only quietly worse words, which is the hardest kind of bug to notice in something whose
whole job is guessing.

Two things are tempting to include and both are wrong. Our suggestion strip, because it is part
of the view; and the bottom row of space and return, because it is part of the keys. Neither is
somewhere a word gesture can go, and either one stretches the square so every key lands somewhere
the decoder does not expect. The frame is the top of the first letter row to the bottom of the
third, and `LayoutGeometry` owns it so there is exactly one definition.

FUTO's own reference layout puts its rows at 1/6, 1/2 and 5/6 -- three contiguous rows filling
the square. Ours land at 0.142, 0.5 and 0.858, because we draw visible gaps between rows and they
do not. That difference is real and is *not* corrected for: these models take the key centres as
an input precisely so a layout can be itself, and faking a grid we do not have would misplace the
path relative to the keys rather than fix anything.

### Which decoder is better was measured, not argued

Both engines, over the same 64 recorded glides, with the same geometry and the same vocabulary --
the beam search's dictionary is generated from the same lexicon file, so what is left between
them is decoding:

| | first choice | offered |
|---|---|---|
| a hand-written Kotlin decoder | 79% | 100% |
| FUTO Swipe -- encoder + decoder + context LM | **95%** | 100% |

So the keyboard uses FUTO's models, and the hand-written decoder was deleted rather than kept as
a fallback. Two things about that number are worth
keeping in view. It is measured on the collision words -- `I'm`, `in`, `ok`, `on`, `um`, `ex` --
which are the shortest and most ambiguous glides that exist, so both engines will do better on
prose; and the misses are different in kind. Ours loses `I'm` to `in` and `um` to `un`, which is
frequency beating a shape it cannot separate. FUTO loses `ex` to `ed`. Ours has no context at
all; theirs has a 1.5M-parameter model of what word usually follows what.

Keeping it as a fallback was the first instinct and it does not survive being asked what the
fallback is *for*. The app does not compile without swipe-library's Kotlin binding anyway, so the
path was unreachable except on an ABI whose native library had not been built -- and both are
built now. What it would really have bought is the ability to silently decode worse without
anyone being told. A keyboard that types nothing and logs why is the better of those two.

What survives is the measurement and the shape that made it possible: `GlideEngine`, and the
Gesture Lab's Score button, which still scores whatever engines it is handed against the whole
recorded bank. With one engine it is not a comparison, and it is still worth having -- a decoder
reading this thumb at 95% and one reading it at 40% look identical from the outside until
something asks, and the likeliest cause of the second is not the model but the frame it was
handed.

### Three traps in the integration, all silent

Worth writing down because each cost a build-install-test cycle and none of them announced itself.

**The Kotlin binding cannot load a dictionary.** `SwipeEngine` takes its dictionaries as `ITrie`
pointers and `SwipeDecoder.kt` passes them through as longs -- but nothing in the shipped
bindings can *produce* one. `tools/patches/swipe-library-trie-jni.patch` adds three calls that
wrap `load_trie_simple`. Kept as a patch rather than a fork so that moving the pin shows up as a
conflict rather than as a silent revert.

**`System.loadLibrary` lives in the wrong class.** It is in `SwipeDecoder`'s companion, and the
dictionary is loaded before the engine that will use it -- so the first call into our own natives
came before anything had touched that class. `SwipeTrie` loads the library itself now; depending
on another class having been reached first is not a load order, it is a coincidence.

**Hugging Face stores the weights in git-lfs.** A plain clone produces 132-byte pointer files
that are perfectly well-formed, non-empty, and named exactly like models. The fetch script pulls
each file over https instead. The staging code learned the same lesson from the other end: it
compares a staged file against the asset's *size*, because "it exists and is not empty" cannot
tell a pointer from a model, and could not tell an updated model from a stale copy either.

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

### A short word hides inside a long gesture

Both the crawl and web2 are full of three-letter entries nobody writes -- "wud", "hie", "ait",
"phe", "tk", "nr" -- and in a glide dictionary each one does not merely compete with the intended
word, it *beats* it, because the gesture passes through everything it asks for and then keeps
going. So a short word has to earn its place much harder than a long one: two letters must be in
the crawl's first 2,500, three in the first 10,000.

This survived the decoder that first ran into it. The lexicon is now the dictionary that
constrains the neural beam search, and a junk short word costs that exactly what it cost the
old one.

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

### A mode key is a destination, not a step in a ring

The plane switch used to advance a cycle -- letters, numbers, symbols, back to letters -- which
gives the right answer from the letter plane and the wrong one everywhere else. The number and
symbol planes each show *two* mode keys at once, "#+=" or "123" on the third row and "ABC" on the
bottom, and a ring cannot tell them apart: pressing ABC from the numbers plane advanced to
symbols, which looks exactly like the key having done nothing, and only a second press reached
the letters. The key ids already said where each one goes; nothing was reading them.

The fix is `IosLayouts.planeFor(keyId)`, and it lives beside the rows that spell those ids rather
than in the service, because a mode key is only meaningful as a label paired with a destination
and splitting the two across files is how they drifted apart in the first place. A test walks
every mode key on every plane and asserts none of them leads nowhere.

### The third row's shoulders must not move between planes

The third row is the only row whose key *count* changes between planes: nine on the letters
plane, seven on the number and symbol ones. Laying both out by the same centring rule put the
mode key 126px inboard of where shift had been, so shift and backspace jumped sideways on every
plane switch -- and backspace is exactly the key a thumb is most likely to be already travelling
towards when it does.

iOS does not do this, and measuring the screenshots says how it avoids it. At 3x on a 1170px
screen the two shoulder keys occupy the identical rectangle in all three planes (x 9-141 and
1029-1161), and the band between them spans the same 802px whether it holds seven letters of 99px
or five punctuation keys of 145px. The slack goes into the middle keys, not into the margins.

That is one equation rather than a table of widths. Setting the two rows equal,

    2 shoulders + 5 punctuation + 6 gaps  ==  2 shoulders + 7 letters + 8 gaps

the shoulders cancel and leave `5w == 7u + 2g`, so a punctuation key is 1.4 key units plus two
fifths of a gap -- about 1.465 units at our metrics. It is derived in `IosLayouts` rather than
written down as a literal, because a literal would quietly stop lining the shoulders up the next
time `Metrics` is recalibrated, and nothing would fail loudly.

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
- **A pressed key does not get a popup; it becomes one.** Pressing stretches the key itself up out
  of the board, tints it, and carries its contents to the top of the taller shape -- so the glyph
  is clear of the thumb while staying attached to the key it belongs to. The finger holds the
  bottom of the same object it is reading the top of. The click that goes with it is at the
  keystroke, not the touch.
- **A press in the gap between keys is still a press.** Keys are drawn with a 31px channel
  between rows on this phone, against a 120px key, and `keyAt` answered "no key" for anything
  landing in it -- the press was discarded silently. A recorded passage typed at speed showed 205
  finger-downs reaching the view and 8 producing nothing at all, six of the eight in the channel
  between the top row and the home row. That is what "the keyboard doesn't register my
  keypresses" is. Presses now resolve through `keyForPress`, which falls back to the nearest key
  by distance *to the rectangle* -- centre distance picks the horizontally closer key in the
  wrong row, because the rows are inset differently. The snap stops at the top of the key area so
  a tap on the suggestion strip stays a tap on the strip. `nearestKey` had already been written
  for the glide decoder with a comment saying the gaps swallow samples; the lesson just had not
  been carried across to taps.
- **The emoji strip must not touch the top letter row.** The strip sits directly above `q`-`p`,
  it took taps across its whole height, and tapping an emoji *replaces the word being typed*. So
  a press aimed at a top-row letter that came in slightly high did not cost a character, it cost
  the word: a recorded press 26px above `e`, horizontally dead centre of `e`'s column, turned
  "book" into 📖. The strip now stops taking taps at `STRIP_TOUCH_FRACTION` (0.78) of its height
  and the emoji are drawn smaller and higher to match, so the picture and the touch area say the
  same thing. The freed band is not dead -- `keyForPress` snaps it into the top row -- because a
  buffer that swallowed presses would just be the gap bug again in a new place.
- **In the Gesture Lab, every line of chrome is a line of passage.** Reading ahead is the whole
  reason the lab produces natural gestures rather than aimed ones, and the screen it has to do it
  in is what the keyboard leaves over -- roughly a third of the display. So the header, the
  progress line, the feedback and the bank counts are each held to one line and truncated rather
  than allowed to wrap, the title bar is off, and the button row is given an explicit 34dp
  instead of the platform Button's 48. The passage card takes the rest. Truncated lines are
  ordered most-useful-first, because only the front of them survives on a narrow screen.
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

### The Gesture Lab as a daily habit -- `capture/LabDeck`, `capture/LabProgress`

| | value | why |
|---|---|---|
| corpus passage length | ~30 words | Long enough to stop performing (`PassagesTest` holds every passage to 25 tokens and 20 glides), short enough to finish standing up. |
| corpus size | 155 sentences, ~50 passages | Weeks of daily collecting before a word comes round again. |
| drill cadence | 1 in every 5 passages | The collision stream is ~40 tokens at `reps = 2`; more often and the lab is a drill with prose in it, less often and the boundary stops gaining evidence. |
| drill reps | 2 | At 4 the drill is 120 tokens, which is a sitting rather than a break -- and a drill abandoned halfway collects only the keys shuffled to the front of it. |
| daily goal | 120 gestures | A few minutes. A number to pass, not a quota: the streak counts days with any gesture in them, because a rule that demanded the goal would punish a short session more than no session. |

### Glide decoding -- `glide/FutoSwipe.kt`

FUTO Swipe, through the vendored `swipe-library`: a 635K-parameter layout-agnostic encoder, a
304K English/QWERTY decoder, and a 1.5M context language model, with dictionary-constrained beam
search over our own lexicon. About 10 MB of weights, ~2 MB of native library, and roughly 3 ms a
word on a phone.

Nothing here is tuned by us and that is the point: the numbers that matter were fitted to over a
million real swipes rather than to anything anyone could reason out. What this side owes the
models is only the part they cannot check -- the coordinate frame, above -- and the dictionary.

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

### The pressed key and its click — `view/KeyboardView`

| parameter | value |
|---|---|
| rise | 0.70 key heights, upward only, character keys only |
| contents | carried up bodily; both glyphs, at their resting sizes |
| colour | `keyPressed`, taken and given back with the rise |
| timing | instant both ways — the rise is never eased, in or out |
| still on a clock | only a released flick's glyphs, at the same 40 ms as before |
| haptic | `KEYBOARD_TAP`, at the commit, following the system touch-feedback setting |

**The key becomes the popup rather than getting one.** This is FUTO's answer and it is better than
the three that were tried before it. Growing the key in place puts the confirmation under the
thumb that is covering it. A separate bubble above the key clears the thumb but is a second object
that has to be given rules about when to exist -- and each of those rules is a place to be wrong:
on the finger-down it appears for touches that turn out to be glides, and at the commit it appears
after the finger has gone and needs a clock to take it away again. Stretching the key upward has
none of that. It is the same object, so there is nothing to place, nothing to clamp to the edge of
the keyboard, and nothing to decide about: it is up exactly while a finger is on the key.

**One number does the whole thing.** The body is drawn from `top - lift`; the contents are drawn in
the key's own coordinates and the canvas is translated up by the same `lift`. So the glyphs land at
the top of the stretched shape by construction, both of them together, and the empty stem below is
where the thumb is. At `lift = 0` every line of it reduces to the resting keyboard, which is why a
key nobody is touching is drawn exactly as it always was.

**The flick then plays out up there for nothing.** It moves the glyphs within the key's own
coordinates, and the rise moves the key: neither knows about the other, and the flick clips to the
key's outline rather than to the stretched body, so the letter still leaves through the bottom edge
of the key's own shape and the stem stays clear. That the flick animation now happens in the raised
part of a pressed key took no code at all -- it is what carrying the contents up already means.

**The rise is not a movement; it is a shape.** It is what a key looks like while a finger is on
it, and a finger arrives and leaves at a definite moment -- so the change is instant in both
directions and never eased. Easing it would have the renderer inventing a state the hand is not in,
and on the way up it would lose the race outright: a tap can be over in forty milliseconds, and a
key still growing when the finger has gone has shown nothing at all.

The only thing left on a clock is a released flick's glyphs finding their slot, which is unchanged
and still 40 ms. Both live in one `KeyMotion` because they are one finger's effect on one key, but
`press` is a fact -- 0 or 1, never anything between -- and only `pull` is ever interpolated. A key
let go mid-flick is therefore down on the next frame with its symbol still sliding home inside it,
which is what the flick did before any of this was added.

**Character keys only.** The space bar has nothing to lift, and shift or backspace would be raising
an icon over nothing. They tint, as they always did.

**The click is at the commit**, not the touch. It is the only place a tap and a flick can share one
rule: a flick is not a flick until the finger lifts, so ticking on the way down would buzz for
presses that went on to type nothing and tick twice for the ones that did. Every entered key ticks
-- letters, flicked symbols, accents and the special keys -- and gestures that type nothing stay
silent.

### Cursor and selection

| parameter | value | why |
|---|---|---|
| trackpad step rounding | nearest, strict `>` | see above |
| character width fallback | 0.33 × line height | average lowercase advance; measured thereafter |
| row-edge margin | 1.2 characters | a wrapped row reaches the editor edge by definition |
| selection horizontal cap | ±24 characters per round | bounds a correction, the loop does the rest |
| edge scroll rate | 260 → 45 ms per line | proportional to distance past the edge |
