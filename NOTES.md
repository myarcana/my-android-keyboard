# Implementation notes

Why this keyboard works the way it does, and what every tuned number is. Deeper mechanics for
the cursor system are in `docs/CURSOR_AND_SELECTION.md`, and the Gesture Lab -- the rig that
collects the evidence behind most of the numbers here, for tapping and gliding and flicking
alike -- in `docs/GESTURE_BANK.md`; this is the shorter account of the ideas that made it good,
and the parameters worth defending.

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

So it is asked for instead -- but only where asking is honest, which is the drill. The Gesture Lab
shows a collision stream, records the paths and what they typed, and `tools/gestures.sh analyse`
replays every sample through the real state machine and sweeps the four thresholds against them.
Prose gets no such label and is not given one: a passage says which word is due, not whether the
thumb will glide it or tap it out, and both are correct. Raw paths, not extracted
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
  only one that can answer flick-versus-glide at all -- every other question the bank answers is
  fed by any passage whatever. Offered beside forty passages of ordinary English it stops being
  chosen, and the bank goes on growing while that one boundary gains nothing. It is dealt in
  before every fifth passage instead.
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

### The thumb does not aim at the middle of the key

Flick-versus-glide is what the bank was first built to settle, and the question it turned out to
answer best is a different one nobody had thought to ask it: every plain tap in the file carries
the point the finger landed on and the letter the passage was asking for. `tools/fit_spatial.py`
reads them, needs no labels to do it, and so gets better with every session regardless of what
was typed. It is now the largest reader the bank has, and the reason a sitting spent entirely on
tapping is a good sitting.

**The thumb lands a fifth of a key height low, on every key measured and in all three rows.** The
mean offset is +0.204 key heights down and 0.063 key widths left; the per-key means run from
+0.118 on `u` to +0.348 on `k`, and not one of them is near zero. That is not noise -- the
standard error on each is about 0.035.

What makes it worth acting on is the size of it against the scatter it hides in. Measured about
the *drawn* key centre, the vertical spread of a tap is 0.242 key heights. Measured about where
the thumb actually aims, it is 0.130. Nearly half the apparent sloppiness of typing on this
keyboard was never sloppiness; it was a constant that had never been subtracted.

The practical consequence is sharp. Rows sit 1.26 key heights apart, so under the uncorrected
figure a neighbouring row is only about five sigma away and *no tap is ever unambiguous
vertically*. Under the corrected one the same tap is nine sigma clear. Correcting the offset is
what makes it possible to say that a tap has exactly one possible reading -- and everything the
tap decoder does rests on being able to say that.

It ships as one global pair rather than a table per key, and that is a statement about the bank
rather than about thumbs. Eight keys is not twenty-six, and the per-key spread is real and far
outside its standard error, so a table is the right shape and the wrong thing to fit today. The
numbers are printed per key so the day the bank covers the alphabet, it is a data change.

### A word can be held open without being guessed at

Autocorrect is the thing this keyboard refuses to do: a tapped key produces exactly that
character. The refusal is worth keeping and it was costing something real, because the letter a
tap meant is often not decidable from that tap and is obvious three letters later. Committing at
the moment of the tap throws that away.

What separates the two is a **constraint, not a confidence threshold**. A reading has exactly one
letter per tap, and every letter is one the finger could plausibly have been aiming at. Nothing is
inserted, deleted or substituted; the output is always a re-reading of keys that were actually
hit. So a name, a handle or an abbreviation is typed by hitting its keys, and hitting them
accurately is the *whole* requirement -- an accurate tap has no second reading available to lose
to. "Rhys" typed accurately comes out as "Rhys" no matter how much likelier "This" is.

**A tap is pinned when the touch evidence against every other letter exceeds the entire dynamic
range of the language model.** That range is not a tuned number: it is the whole corpus against
the rarest thing the lexicon can say, read off the lexicon at 18.6 nats. Past it, no word in the
dictionary, however common, could buy the alternative back. On the measured sigmas that band
reaches 0.34 of a key width and 0.38 of a key height from the aim point -- still past the edge of
the drawn key in both directions. Ordinary typing pins every tap and the decoder never runs at
all. A dead-centre press holds 45 nats over its sideways neighbour and 47 over the row above.

The scoring underneath has no thumb on the scale. `ln P(touch | letters)` from the spatial model,
plus `ln P(letters)` from the lexicon, both in nats, both real. The literal reading gets no bonus
and needs none: it has the best touch score by construction, so it wins every tie and every case
where the language model has nothing much to say.

**The prior is prefix mass, not word frequency, and that is what stops the display flickering.**
Scoring complete words only, a literal "rhe" loses to "the" at three letters and wins again at
four, because neither "rhet" nor "thet" is a word and both fall back to the unknown-spelling
floor. The reading would appear and then be taken away while the finger was still moving -- the
same flicker a suspended glide exists to avoid, in yet another costume. Summing the corpus counts
of every word a prefix can still become removes it outright: adding a letter can only narrow that
set, so a reading that is ahead stays ahead for the reason it was ahead. It is also just the right
quantity. P(prefix) is the probability the word starts this way, which is the question being asked
at every keystroke but the last.

Holding state about the field is the other thing this keyboard had refused to do, for a reason
that still applies -- the cursor trackpad can put the caret anywhere at any moment, and a buffer
that outlives the caret it described is worse than none. What makes it safe is that every way out
is the same way: the word is flushed, and flushing commits the reading that is already on screen.
Nothing has to judge whether a held word is still valid, because nothing is ever asked to keep one
that might not be. Backspace, a glide, the trackpad, a mode change, a focus change, and any caret
move the keyboard did not make itself all flush. So do passwords, addresses and any field asking
for no suggestions, which never hold a word at all.

### Look-ahead cannot help a tap that was never in doubt

The beam above already reads context in both directions -- nothing commits until the word ends, so
`rhe` becomes `the` on the strength of two letters typed after the `r`. What it cannot do is fix
`teavhers`. The pruning in `SpatialModel.candidates` runs *per tap, before any context exists*, and
a squarely-hit `v` leaves one candidate standing. The beam never sees `c` at all. Look-ahead was
never the missing ingredient; the tap had already been decided.

So the fix is a **second pass that reopens pinned letters**, one at a time, asking a question the
first pass could not: given that every *other* letter is now settled, is there a letter here the
lexicon wants badly enough to outbid the touch evidence? The letters after the suspect one are as
much a part of that fixed context as the letters before it, which is what makes this bidirectional
in the sense that matters.

**Building it turned up a hard numeric wall, and the wall is the interesting part.** The prior gaps
available to argue for a correction are tiny, because a non-word does not score zero -- it scores
`oovLogPrior`, the floor that is what lets `rhys` be typed. Measured against the shipped lexicon:

| correction | prior gain |
|---|---|
| `teavhers` → `teachers` | 9.3 nats |
| `wprd` → `word` | 10.8 nats |
| `ot` → `it` | 1.4 nats |

The first two are wider than they were, and the reason is a bug that sat in the floor itself.
`oovLogPrior` was the median word *count*, but `logPrior` returns prefix *mass* -- the summed count
of every word starting with a prefix. Two different quantities on two different scales, compared as
though they were one, and the floor landed above the prefix mass of **18,355 of the 40,028 shipped
spellings**. A made-up spelling therefore beat 45.9% of the real lexicon on prior alone, which is
the one direction that actively hurts: it paid the rescue pass to move a rare real word *toward*
nonsense. `aback` lost to an invented neighbour by 1.5 nats, clearing `RESCUE_MARGIN_NATS` without
the touch term having any say. The floor is now the rarest mass the lexicon can report, so an
unknown spelling ties with the least likely known one and never wins; `WordIndexTest` asserts that
no real spelling scores below an invented one. Note what did *not* move: `ot` → `it` is a gap
between two *real* readings, and those are exactly the cases the margin is sized against.

Against that, at the shipped `sigma_x` of 0.122 the touch cost of moving a letter one key is 45.3
nats dead-centre, and it crosses zero at drift 0.445 -- which is *also* where `keyForPress` flips
the literal to the neighbour. Sweeping finely across that boundary, the best gain available while
the tap still reads as the wrong key was **-1.28, -3.58 and +0.13 nats**. The window in which a
letter is both wrong and recoverable did not exist. Any margin at all left the feature inert, and
that is how the constraint was found rather than assumed.

What separates the two is `sigma_x`, and `SpatialModel` had already written down that the shipped
0.122 is a known-wrong fit over 94 taps, with an honest refit of 0.227 over 2031. Shipping the
refit globally collapses the pinning band to nothing -- the autocorrect this keyboard exists not to
do. **So it is used in exactly one place.** The tight fit still decides what is pinned, so accurate
typing is untouched; the refit decides only whether an already-suspect letter could have been a
miss. One measurement, two questions, two error budgets -- and the strictness that protected the
user moved to where the evidence to afford it exists.

Two cases deliberately do *not* fire, and both are asserted so that a later change has to argue
with them rather than quietly remove them.

`ot` → `it` is the first. Its 1.4 nats is less than the ~1.6 the move costs even with the tap on
the key edge, so the comparison is negative before any margin is consulted. Two letters carry too
little signal for a prefix-mass prior, and when the evidence is that thin the keys actually
pressed are the better guess.

A word with *two* slips in it is the second, and it is the more interesting one because the
obvious expectation is wrong. The pass does not fix them one at a time. Each step is scored
against the current reading, and `teavhets` and the half-fixed `teachets` both fall to the
unknown-spelling floor -- so fixing one letter wins a prior gain of exactly zero and the first
step never starts. The word comes back untouched rather than half-corrected, which is the better
of the two available failures: `teachets` would have been a confident answer invented out of a
word the keyboard could not read.

Both limits have the same root, which is that the prior knows about spellings and not about
sentences. That is the thing to change if either matters enough, and it is a much larger feature
than this one.

### A recorder that rejects is a recorder that deletes its mistakes

The lab was built as a gatekeeper. One target armed at a time, each gesture judged against it,
kept or thrown away, and the passage advanced only on a gesture it accepted. Every rejection was a
hole in the bank -- and the holes were not random. They were exactly the mistakes.

The number that made it undeniable: the first prose session recorded **276 taps and zero misses**.
Not a thumb that never slipped. A recorder in which a slip was defined as a non-event, because a
tap that started on the wrong key was refused for starting on the wrong key. The one sample worth
having, for a decoder whose whole purpose is recovering a letter a thumb missed, was the one thing
guaranteed not to be in the file.

So the lab stops judging and keeps a transcript: the **intended** string, the **actual** string,
the **gestures** in order with their raw paths, and the mapping between them. Nothing is refused;
there is no outcome in which a gesture is not recorded.

**The two mappings are different in kind and are obtained differently, and conflating them is the
trap.** Gesture to output is *recorded* -- each gesture carries the exact edit it made, the text
it typed and the characters it deleted, taken from the keyboard at the moment it made them.
Rebuilding that afterwards by aligning strings would be guessing at something that was certain at
the time, and it would guess wrong on precisely the interesting cases: a glide that types a word
and a space in front of it, an emoji that replaces a run of characters, a backspace. Intended
against actual is *compared afterwards*, because it cannot be known before -- whether a letter was
a mistake depends on what the typist does next, and the correction is the ground truth. Pretending
that verdict was available at the instant a finger lifted is what cost the bank its mistakes.

What falls out is that the typist's own corrections become the labels. A backspace is direct
evidence that what came before it was wrong, and it is stronger evidence than anything the lab
could have inferred, because it comes from the only person who knows what was meant.

The lab therefore never blocks and never waits. The passage is drawn against what has actually
been typed -- green where the two still agree, red for what was typed after they stopped agreeing
-- and the typing may drift, or run past the end, or be corrected, and the recording just
continues. `Skip` used to skip one token, which was only a thing to do because a token could
refuse to be typed; there is nothing to skip past now, so it takes the next passage instead.

### The passage knows the letters whatever the thumb does

The lab assumed a target was a gesture: one word, one glide, one label. Every prose word of two
letters or more was a `WORD` target, and only "a" and "i" were ever taps -- so a passage typed by
*tapping* collected nothing usable. The first tap of "sorry" was filed as a glide of the whole
word, carrying a single-tap path, and the remaining four were rejected for starting on the wrong
key. That is worse than collecting nothing: a bank of confidently mislabelled lines is one that
goes on to tune the real thing.

The fix is a change of assumption rather than of mechanism. **The passage knows the intended
letters at every point, whatever the thumb chooses to do.** So a prose word carries its letters
and accepts either reading: a glide before any letter is down is the word, and a tap on the
letter now due is that letter, recorded with the word it came from and where in it. A run of taps
can then be reassembled afterwards, which is the only way anything can ask whether reading them
together would have got the word right.

The third case is the one that was silently costing the most. **A flick where a letter was wanted
is recorded as that letter**, with its FLICK verdict intact. The passage asked for `i`, the
heuristic produced `8`, and that disagreement is the most valuable line the bank can hold -- it
is direct evidence that the flick threshold is too loose for ordinary typing, which is exactly
the question the drill exists to answer and could only ever answer from drill gestures. Under the
old rule it was thrown away for starting on the wrong key.

The drill keeps the strict rule and must. It exists to ask for one named gesture on one named
key, and a reader that accepted anything there would collect the ambiguity it was built to
resolve. That split -- permissive for prose, strict for the drill -- is the whole design, and it
lives in `TargetReader` rather than in the lab activity so it can be tested without a phone.

One consequence worth stating: the key a gesture must start on now *moves through a word*. Telling
someone "sorry begins on S" when four of its letters are already down is worse than saying
nothing, so the lab names the letter that is actually due, and dims the letters already tapped
inside the highlighted word so the next one to press is the first bright one.

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

### Deleting a word or a line, never the field

Backspace carries both bulk deletes, told apart by the direction of the swipe: **down** takes the
word before the cursor, **up** clears back to the start of the line. Repeating either walks
backwards a unit at a time.

The word delete takes any run of spaces immediately behind the cursor, then the non-whitespace
behind them. The line delete takes everything back to the newline and leaves the newline itself.

The history matters, because the line-clear was removed once and has now come back. Deleting the
*entire field* was the first design and is a trap: it is the only gesture here that can destroy
text the user cannot currently see, and there is no undo. Clearing to the *start of the line* was
the second, and was rejected as the same trap wearing a hat -- in a single-line field, the common
case, there is no line break to stop at, so "the line" is once again the whole field. The third
design kept only the word.

The fourth, current design restores the line-clear as a *separate direction* rather than as a
replacement, which is what answers the old objection. The argument against it was never that
clearing a line is not worth doing -- it is that it should not be the thing a single easy flick
does by accident when a word was meant. Giving each unit its own direction means the cheap,
frequent delete and the expensive, rare one can no longer be confused for one another, and the
one that destroys more is the one that requires deliberately stroking *up*, away from the board.
The single-line-field concern is unchanged and accepted: in such a field the up-swipe does clear
everything, which is now what the user asked for by choosing that direction over the other.

Details that are easy to get wrong:

- **Up and down share one distance threshold.** Same key, same stroke, same risk; only the sign
  of the travel differs. Backspace has no secondary and no popup, so nothing else competes for a
  vertical swipe from it.
- **One delete per gesture, either kind.** The state machine goes SPENT the moment either fires,
  so a finger that crosses up and swings back down does not also trigger the other, and a wobble
  cannot eat word after word.
- **The line delete keeps the newline.** Taking it too would pull the cursor onto the line above
  and join two lines nobody asked to join. A cursor already on an empty line therefore deletes
  nothing -- and unlike the held backspace, it is *not* coerced to make progress, because this
  gesture is repeated by hand rather than by a timer that would spin forever against the break.

- **It stops at a line break.** A word delete pauses at the start of each line rather than
  joining it to the line above. Structure is the one thing here that retyping the word does not
  put back.
- **Spaces behind the deleted word survive.** `"hello   world   "` leaves `"hello   "` -- those
  spaces were typed deliberately and are not part of the word.
- **A selection wins, and an open pinyin buffer wins first.** Same precedence a plain backspace
  uses: the selection is what the user pointed at, and inside a pinyin buffer the "word" is the
  syllable being spelled, so the buffer goes rather than committed text behind it.

The scan lives in `text/WordBoundary.kt` rather than in the service, for the reason
`GraphemeCluster` does: it is the part worth testing, and `KeyboardService` cannot be
instantiated in a JVM unit test. The held-backspace acceleration shares it, so the two gestures
that delete a word cannot drift apart.

The threshold stays deliberately larger than the flick threshold (0.8 vs 0.45 key heights) -- a
thumb drifting up off the key must not fire it.

### The bar reads the editor, it does not remember what was typed

Suggestions are for the word the caret sits at the end of, found by reading back through
`getTextBeforeCursor` on every change. Keeping our own buffer of keystrokes would be cheaper
and would be wrong the instant the spacebar trackpad moved the caret somewhere else -- the bar
would be offering emoji for a word elsewhere on screen. Tuned numbers: 48 characters of
lookbehind (no emoji name is longer), two-word queries tried before one-word ones so "thumbs
up" beats "up", and a two-character minimum, below which the bar is noise.

### Held backspace accelerates

175ms to start (the shared long-press timeout), then a character every 55ms, then whole words
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

### A chip from another process has no size of its own

Password managers reach the suggestion strip through inline autofill, which the IME opts into
with `supportsInlineSuggestions` in `method.xml` and two `InputMethodService` overrides. Absent
either, the system never asks for an `InlineSuggestionsRequest` at all and the manager falls back
to its own dropdown over the field -- which looks exactly like a keyboard that has no autofill
support, because it is one.

The chips themselves are `InlineContentView`s: surfaces rendered in the manager's process. Three
things follow from that, and all three are silent when got wrong.

They cannot be drawn on `KeyboardView`'s canvas the way the emoji are, so the input view is now
a `FrameLayout` holding the keyboard and an overlay lying on the strip. The overlay covers the
upper part of the strip and no more, at `STRIP_OVERLAY_FRACTION`: a chip swallowing a press
aimed high at `q`-`p` would turn a slightly high `p` into nothing at all. The chips are the one
thing on the strip whose area cannot be settled per touch -- they are Views from another process,
measured and placed before any finger exists -- so they take the band no letter press could
plausibly reach, while the emoji keep the whole strip and arbitrate each touch as it arrives.

The surface composites *behind* its window unless told otherwise, and `KeyboardView` paints its
whole canvas including the strip, so there is no hole for it to show through. `setZOrderedOnTop(true)`
before attaching is what makes the chip visible rather than merely present.

And a chip inflated at `WRAP_CONTENT` measures to zero. It has no intrinsic size to fall back on
-- the width is decided in the other process and arrives on the view's own layout params -- so
`addView(view)` is correct and `addView(view, LayoutParams(WRAP_CONTENT, ...))` throws the answer
away. The failure is total and completely quiet: `dumpsys autofill` reports the response as
`INLINE_SHOWN`, the inflate callback delivers a real view, and `dumpsys SurfaceFlinger --list`
shows no surface at all, because a zero-width view never gets one.

On this phone the payoff stops at username fields. ColorOS binds `com.oplus.securitykeyboard` to
any field whose `inputType` carries a password variation, so the password half of a login is not
served by this keyboard and cannot show its strip -- see the device notes, not this file.

### Chinese is decoded as a sentence, not looked up a syllable at a time

The difference between a pinyin keyboard people use and one they uninstall is almost entirely
here. `tianqi` has three dictionary readings and `jintiantianqihenhao` has thousands; choosing
per syllable, by frequency, gives 今天天气很号 about as often as the right answer, because
each character's commonest reading is not the one the sentence wants. The engine (`ime/pinyin/`)
runs a Viterbi pass over a lattice whose edges are dictionary words, so the whole input is
decided together and context does the disambiguating.

Four scoring facts were each found by getting them wrong first, and each one produced garbage
that looked like a different bug:

- **Scores must be log-*probabilities*, not log-weights.** Scoring a word as `ln(weight)` makes
  every word worth a positive amount, so a path always improves by containing more words:
  `shijian` decoded to 十几啊你 (four words) over 时间 (one), and `women` to 我们婀娜. No
  per-word penalty fixes it, because the two quantities do not have the same sign. Dividing by a
  corpus total makes every term negative and the arithmetic comes out right on its own.
- **Single characters need a backoff penalty.** With characters as ordinary lattice edges, two
  very common ones outscore the single word that spells them — 天起 beat 天气, 是件 beat 时间.
  A word entry is better evidence than assembling the same text character by character, and
  `BACKOFF` is what says so. Without the character edges at all, though, a sentence containing
  one standalone character has *no* complete path, so they cannot simply be removed.
- **Fuzzy spellings must not be dropped where both forms are real syllables.** The first cut
  discarded any fuzzy variant that collided with a real syllable, which threw away 394 of them —
  every pair worth having, since `zong`/`zhong` and `nan`/`lan` are exactly the confusions fuzzy
  pinyin exists for. They are kept and ordered instead: exact first, fuzzy charged a penalty.
- **The candidate bar has to leave room for prefixes.** Offering every whole-input decoding
  filled the strip with 北京大学, 北京大雪, 北京大削, 北极难过大学 and no 北京 — so a phrase could
  be typed whole or not at all, and committing it a word at a time was impossible.

### Traditional was a conversion, and that was not Traditional support

The original design was one Simplified dictionary plus OpenCC's `s2twp` chain, pre-composed into
the asset at build time — 软件 → 軟件 → 軟體, the ordering being the subtlety, because the TW
tables are keyed on *Traditional* and so must be a second pass over the output of the first.

That is a good conversion and it was still the wrong thing. What it produces is **mainland
Chinese written in Traditional characters**, which is not what a Taiwanese person types. Three
facts, each checkable against the sources in `zhwork/`, say why:

- **The vocabulary is absent, not merely spelled differently.** rime-ice has no 蚵仔煎 at all. It
  carries an explicit normalisation comment — `「蚵仔煎」→「蚝仔煎」` — and stores the mainland
  form read `hao zai jian`. No conversion recovers a word the dictionary does not have, so
  `ezijian` could not produce 蚵仔煎 by any amount of table work.
- **The readings differ, not just the glyphs.** 軟體 is read `ruan ti` and 網路 `wang lu`. A
  dictionary keyed by `ruan jian` and `wang luo` cannot be reached by those keystrokes however
  its *output* is rewritten, because conversion happens after the lookup.
- **The frequencies are the wrong corpus.** 牛肉麵 should outrank 牛肉面 for a Taiwan user and the
  reverse for a mainland one. One weight per word cannot express that.

So Taiwan is now its own language model, merged into the same asset rather than layered over it:
McBopomofo's 140k Traditional words, their Taiwan readings converted from bopomofo to this
dictionary's pinyin spellings at build time (`tools/bopomofo.py`, verified to land 406 of 407
syllables on spellings the shipped table already knows), weighted by McBopomofo's own Taiwan
corpus counts (`phrase.occ`) rescaled onto the mainland corpus's scale so one `CORPUS_TOTAL`
normalises both. The asset format is `PYD3`: every entry carries **two** weights, and zero means
"this model does not have this word" rather than "this word is rare".

The measured result, from `tools/dump_pinyin_dict.py`:

| key | entry | cn weight | tw weight |
|---|---|---|---|
| `niu rou mian` | 牛肉面 | 35230 | 0 |
| `niu rou mian` | 牛肉麵 | 0 | 11326 |
| `e zi jian` | 蚵仔煎 | 0 | 707 |
| `ruan ti` | 軟體 | 0 | 188127 |
| `ruan jian` | 软件 | 502289 | 0 |

Conversion survives, but demoted to a backstop: in Traditional mode the candidates already *are*
Traditional, so `Script` only repairs text the Taiwan model could not supply — a learned word, or
a character-floor entry that exists solely in the mainland tables. Running it over everything
would reintroduce exactly the behaviour this replaced.

Two things had to change to keep this honest. The decoder drops any word whose weight in the
active model is zero, or both dictionaries would show up in both languages. And pruning ranks by
the *stronger* of an entry's two weights, never their sum or average — averaging would have
dropped 蚵仔煎 for being unknown in China, which is the exact failure the work existed to fix.

### One suggestion bar, one probability scale

The strip used to be two strips wearing the same paint: emoji in English, Chinese in the CJK
subtypes, chosen by which subtype was active rather than by what the letters could mean. But
`niuroumian` is not ambiguous — no emoji is named anything like it — while `ha` genuinely is, and
`happy` genuinely is not. Only a score separates those three cases, so `UnifiedCandidates` puts
all of them on one scale and sorts. It is not a merge of two ranked lists: merging can only
decide how to interleave, which cannot express "this emoji is a better answer than that Chinese
word".

The scale is log-probability in nats, which is what the pinyin decoder already produced. Getting
emoji onto it is the whole trick, and the constant is the part that is easy to get wrong: an
emoji must be scored as the probability that *a token of text is this emoji*, not as its share of
emoji. Those differ by about 5.7 nats, and using the wrong one pins emoji near 0 against Chinese
near −8, so the bar is emoji-only forever regardless of what was typed. Emoji are roughly 1/300
of tokens and Zipf-distributed — which is also exactly how `emoji_en.tsv` is ordered, so rank is
the only frequency signal available and it is the right one.

Two further terms are needed, and both are evidence rather than tuning:

- **Coverage.** A reading that explains every letter typed is worth far more than one explaining
  two letters of seven. Without it `happy` offers 哈 for its leading `ha`.
- **Whether the letters are already an English word.** This is the one that matters, because
  pinyin is written in the same 26 letters: `you`, `take`, `like`, `women` and `wo` are all
  ordinary English *and* valid pinyin, and the Chinese reading is often the commoner string in
  isolation — 有 beats 牛肉面 by six nats, because one common character beats a three-character
  dish. Frequency alone therefore gets this backwards, and the missing evidence is not about
  Chinese at all. The penalty is the English word's own `ln P` (from the lexicon the glide and
  tap decoders already hold), so `the` costs a Chinese reading a great deal, `beijing` — a rare
  English loanword — costs 北京 only 3.4 nats and it still wins, and letters that are not English
  at all are charged nothing.

The orderings asked for then fall out of the arithmetic instead of out of a rule. From
`tools/rank_preview.py`, which prints the same scores the app computes:

| typed | bar |
|---|---|
| `niuroumian` | 牛肉面 −10.95, then 牛肉 — no emoji match at all |
| `niuroumian` (TW) | **牛肉麵 −12.08**, then 牛肉面 −17.53 |
| `ezijian` (TW) | **蚵仔煎 −14.85**, well clear of the character floor |
| `ha` | **哈 −7.73, 😂 −8.30, 🤣 −8.99** — genuinely mixed |
| `happy` | 😂 −7.80 and nine more; no Chinese, `happy` is not pinyin |
| `you` | 😘 −7.80 and emoji only; 有 charged 9.16 for being English |

One consequence worth recording because it cost a debugging cycle: the decoder's own ranking is
not the bar's ranking, so `scoredFor` must over-fetch. For `ezijian` the right answer sits below
forty single characters that each explain one letter of seven, and cutting to the display limit
before the coverage penalty runs discards it before the thing that recognises it ever sees it.

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
  "book" into 📖. The first fix was a dead band: the strip stopped taking taps below
  `STRIP_TOUCH_FRACTION` of its height. That worked and cost too much -- it spent a fixed slice
  of the bar, permanently and whether or not anyone was typing, to catch a press that arrives
  occasionally, and it drew the line by assertion when the evidence is a measured distribution.

  It is now decided per touch by `LayoutGeometry.isLetterReach`, which asks `SpatialModel` how
  many standard deviations above a thumb's *measured* landing point the touch sits. This is the
  same fuzzy-hitbox machinery the tap decoder uses, extended over the strip. It works because of
  the offset rather than in spite of it: thumbs land ~0.2 key heights *below* the drawn centre,
  so a touch arriving above a key's centre is already unusual, and the strip above is further
  again. Both `candidateAt` and `keyForPress` call the one function, so no pixel is claimed twice
  or left unclaimed, and crossing the line gives the touch to the *letter* -- a misread letter
  costs a character, a misread emoji costs the whole word.

  Threshold is 2.5 sigma, and the constraint that fixes it is the disagreement between the two
  spatial fits. On the 34dp strip the shipped sigma (0.130) puts the whole bar beyond 5.4 sigma,
  while the honest refit (0.227) puts its bottom edge at 2.64. Anything at 3 or above would start
  eating the bar under the refit; 2.5 keeps the emoji the whole strip under both and still reads
  the recorded press as `e`. `KeyForPressTest` pins both fits for that reason.
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
| bottom padding | 38 |
| total height | 279 |

Palette: `#ECEDFB` ground, white keys, `#E2DFFF` special keys, `#181B25` text.

**Bottom padding is measured against Gboard, and the first measurement was taken wrong.** The
horizontal metrics were scanned from a screenshot taken under *three-button* navigation, and the
7.7dp that came off it looked right there only because `KeyboardView` was reserving 44dp for the
button bar underneath it. Under gesture navigation the system reserves 16dp -- enough for the
gesture handle, which is all a navigation reserve is for -- and the same constant put our space
bar 71px (23.67dp) above the screen bottom against Gboard's 162px (54dp). The keyboard sat 91px,
a little over 30dp, too low.

38dp + the 16dp gesture reserve reproduces Gboard's 54dp. The distance lives in the layout
constant rather than the navigation reserve because the reserve is already honest: the leftover
is Gboard choosing not to put keys where a thumb rests, which is a layout decision.

The lesson worth keeping is that a screenshot records the navigation mode it was taken in. Both
keyboards must be captured back to back on the same device in the same mode before their
geometry can be compared at all.

### Gesture thresholds — `gesture/GestureConfig`

| parameter | value | why |
|---|---|---|
| `longPressMs` | 175 | 250ms (half the platform's 500ms) cut a further 30%; every key here offers a hold, so the wait is paid on purpose. Below the bank's slowest tap (190ms) |
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
  `longPressMs` has since dropped to 175ms, which puts that same 447ms dawdle well past the
  deadline: the drift guard, not the clock, is now the only thing protecting a slow glide.

One gesture in 208 is still misread: an "ok" that left at a ratio of 4.3, right against the 4.25
threshold. That is the honest state of it -- see docs/GESTURE_BANK.md on why "ok" probably wants
the lexicon at decode time rather than another number.

Note that the harness check cannot tell an intentional change from drift. The long-press fix made
one recorded ACCENT replay as GLIDE, which is the fix working; a threshold moving under your feet
would look identical in that report. Read the disagreements, do not just count them.

### The flick threshold is not one number, it is one number per sentence — `gesture/FlickPrior`

The four thresholds above are the same on every key and at every moment. The second half of that
is the weaker claim: a downward stroke made after a space and the same stroke made in the middle
of a word are not equally likely to have been a digit, and nothing in the machine knew it.

`FlickPrior` returns a **log-odds bias in nats**, and `TouchFsm` spends it by scaling
`flickDistanceRatio` and `verticalDominance` together, clamped to 0.45×–2.2×. Nats because
`SpatialModel` and `WordIndex` already reason in them; a second, differently-scaled notion of
confidence in the same keyboard is how two numbers that look comparable quietly stop being so.
The scaling is exponential, so a constant number of nats multiplies the demanded evidence by a
constant factor wherever it starts from. Both axes move together: they are two demands for the
same proof, and relaxing one while tightening the other would leave the difficulty unchanged and
make the whole mechanism a no-op that looks like it is working.

| term | nats | when |
|---|---|---|
| `afterDigit` | +1.6 | the caret sits after a digit — typing `18` |
| `wordInternalSymbol` | +1.1 | `'` and `-`, which cancel the mid-word penalty |
| `boundaryDigit` | +0.7 | a digit wanted after a space or punctuation |
| `midWord` | −0.9 | letters behind the caret, or a word being composed |
| `noRoomBelow`, `roomBelow` | 0 | see below |

The apostrophe exemption is not a nicety. `'` is the symbol most often wanted mid-word — `don't`,
`it's`, `I'm` — so the context that makes `$` implausible is the exact context an apostrophe is
*for*. Without it the mid-word rule would make the commonest flick in English harder, and the
collision drill would never notice, because it types `'` from a standing start.

**The per-key terms were the original idea, and the bank threw them out.** The argument was good:
nothing can be glided downward out of `m`, so a stroke there has no competing reading, while `i`
has `I'm` and `in` hanging under it. The measurement disagreed. Real flicks in the bank travel
**0.13 to 1.05 key heights** against a threshold of 0.025 — distance is not the binding constraint
on any key, so loosening it on `m` rescued nothing and tightening it on `i` only refused good
flicks. Turned on, the two terms moved five recorded gestures and made **all five wrong** (`a`,
`e`, `o`), while `m` did not move at all.

The six missing `m` flicks this file cites above went missing at `flickDistanceRatio` **0.45**,
eighteen times the current value, and were fixed by lowering it. Four `m` records still replay as
taps, and they are not evidence of anything: their press coordinates land on `return` and `space`
in today's geometry, so they were recorded on a layout this build no longer has.

The terms are still in `Weights`, defaulted to zero, with the measurement beside them. A disproved
term kept visible and inert is cheaper than rediscovering the argument and re-collecting the
evidence — and the mechanism reads the room below a key from `LayoutGeometry` rather than from a
row index, so it stays correct on a squashed board and is ready if a threshold ever does bind
there.

**The bank cannot score the half that survived.** A recorded path has no caret behind it, so
`GestureReplay` replays under `NO_PRIOR` — every weight zero — which is the only honest way to
reproduce a verdict reached by a build that had no prior. `reportFlickPrior` prints the shipped
prior's score beside it and says in the report that the contextual terms are invisible to this
bank. Settling them needs a session that records what the caret was sitting after, which is a
change to the recording format and not to a threshold.

### A flick may lean where no word goes — `gesture/FlickCone`

The complaint: flicking `(` on `h` often glided `in`, `hub`, `iv` or `on` instead, and a downward
swipe on the bottom row could glide at all, which is meaningless. The cause was that
`verticalDominance` 4.25 (about 13° either side of straight down) applied to **every** key. That
number is fitted to `o`, where `ok` leaves 25° off vertical and nothing else separates the two.
On `h` no English word leaves downward (`hv`, `hm` are 0.03% of h-words together), so a flick
leaning 20° was refused and became a glide, and the decoder had to name some word for a path no
word makes. The bank shows the lean is normal: recorded flicks lean up to 16° on the top row, 20°
on the home row and 30° on the bottom row.

Each side of each key now gets its own half-angle, from the lexicon and the geometry: open to 45°,
stopping 20° short of the nearest letter below that at least 1% of this key's words go to next.
The 20° is measured: `un` leaves up to 19° off the line from `u` to `n`. The configured 13° stays
as a floor, so `i`, `e` and `k` are exactly as strict as before, and so is `o`, except for an 8°
side, which is too small to open anything. `h`, `j`, `f`, `g`, `l`, `y`, `q` and the whole
bottom row open fully. `t`, `u`, `w`, `p`, `r`, `s`, `d` and `a` open on the side away from their
words (`th`, `un`, `wa`, `pl`). With no lexicon loaded, every letter below counts as contested,
which still opens the bottom row.

Two further rules keep it safe:

- **Leaning costs more travel.** 0.15 key heights instead of 3px. A tap in the bank rolled 14px
  down at a lean on `h`, and 3px was only safe inside the 13° fence.
- **Inside an opened side, distance no longer promotes a flick to a glide.** That was the other
  half of the `h` complaint: a long, confident flick ran past `flickToGlideRatio` and became a
  word. The stroke now has to turn out of the cone first. On a strict side it escapes on distance
  as before, because there the continuation really could be `in`.

Replayed over every gesture in the bank whose outcome is known, including ordinary typing, not
just the drill: **no recorded gesture changes verdict.** That is expected, because the drill
flicks almost nothing from `h` or the bottom row. The tests in `FlickConeTest` pin the reported
cases. `flickConeMaxDegrees = 0` gives the old machine back, and sessions recorded before this
carry no cone fields, which decode as 0, so the harness check still replays them as they ran.

**Replay had been reading the pre-September bank 16dp low.** Finding this took the first run of
the report above: it claimed the cone broke `in`, `on`, `ok` and `ex`. It had not. The strip
shrank from 50dp to 34dp in 5987b26, which moved every key up by 16dp, while the bank stores view
coordinates. So every gesture recorded before then was replayed three quarters of a key low, and
a glide of `in` started on `k`. With one set of thresholds for every key, nobody noticed.
`GestureReplay.onTodaysKeys` now shifts those records back, which puts 3596 of 3600 letter presses
on their recorded key, against 2605 before. The current thresholds score **96.4% balanced**
(symbol 142/145, word 67/73) where the same report used to say 92.5%. Some of the old
"failures" were never the thresholds.

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

### The pinyin dictionary -- `assets/pinyin.bin`

12.1 MB, generated by `tools/build_pinyin_dict.py`, committed for the same reason the lexicon is:
there is no INTERNET permission to fetch it with. Sources are rime-ice (mainland words, readings
and usage weights), McBopomofo (Traditional words, Taiwan readings and Taiwan corpus counts) and
OpenCC (the conversion backstop). **The rime-ice data is GPL-3.0 and that licence reaches the app
if it is ever distributed** -- chosen knowingly, because the permissive alternative (CC-CEDICT)
ships no frequencies and ranking is most of what makes candidates feel right. McBopomofo's data
is MIT and adds no further obligation. See the README for how to undo the GPL choice.

It grew from 8.4 MB when the Taiwan model was added: 166k of its entries are Traditional words
that no conversion of the Simplified data could have produced, and every entry now carries a
weight per region rather than one. Both are what the section above is about.

A binary format rather than the upstream text: 880,000 lines of YAML parsed at startup would be
seconds and a large heap on a keyboard that has to appear instantly. Sections are read with one
`ByteBuffer` and binary-searched in place, and nothing is decoded until a lookup reaches it. Keys
are sorted by syllable id so a prefix is a contiguous run -- the same trick `WordIndex` uses over
letters.

| parameter | value | why |
|---|---|---|
| entries | 450,000 words | after a per-key cap of 60; the tail is never scrolled to |
| regions per entry | 2 weights | mainland and Taiwan; 0 means "not in this model", not "rare" |
| bigrams | 150,000 pairs | past this the counts are below the decoder's smoothing floor |
| beam width | 4 paths | the right sentence survives a weak start; 8 was not better |
| `CORPUS_TOTAL` | 2e9 | probability denominator; any value above the largest weight works |
| `BACKOFF` | 6.0 nats | a word beats the characters spelling it, a name is still reachable |
| `FUZZY_PENALTY` | 2.3 nats | "about ten times less likely" per fuzzily-matched syllable |
| `LEARNED_BONUS` | 12.0 nats | sized to the gaps `unigram` actually produces; 3 did nothing |
| `MAX_READINGS` | 16 | fuzzy readings sort last, and cutting at 4 dropped them entirely |
| `FULL_DECODINGS` | 5 | leaves room on the bar for prefix words |

### Tap decoding -- `tap/SpatialModel`, `tap/WordIndex`

| parameter | value | where it comes from |
|---|---|---|
| `MEASURED_OFFSET_X` | -0.063 key widths | mean landing point of 94 bank taps |
| `MEASURED_OFFSET_Y` | +0.204 key heights | the same; positive is down the screen |
| `MEASURED_SIGMA_X` | 0.122 key widths | scatter about that point, not about the drawn centre |
| `MEASURED_SIGMA_Y` | 0.130 key heights | 0.242 if measured about the drawn centre |
| `priorRange` | 18.6 nats | derived: `ln(whole corpus) - ln(rarest mass)` |
| `oovLogPrior` | rarest mass the lexicon can report | the value of a spelling it has never seen |
| `MIN_TAPS` | 2 | at one letter the prior is about the alphabet, not about a word |
| `beamWidth` | 24 | a ceiling; a tap usually contributes one candidate and rarely three |
| `REFIT_SIGMA_X` | 0.227 key widths | refit over 2031 taps; used **only** by the rescue pass |
| `REFIT_SIGMA_Y` | 0.208 key heights | the same |
| `RESCUE_REACH_NATS` | 20 nats | which letters get listed; the comparison does the refusing |
| `RESCUE_MARGIN_NATS` | 1.0 nats | ceilinged by the real-vs-real prior gaps, the smallest 1.4 nats |
| `MAX_RESCUES` | 2 | each substitution spends the certainty the next one rests on |

Rerun `tools/fit_spatial.py` after any sitting with the Gesture Lab; the first four move with the
bank. The next two are not tunable at all -- they are read off `assets/lexicon_en.tsv`, so
regenerating the lexicon moves them and the pinned band with them.

The two sigma pairs are the same measurement at two confidence levels, and which one applies is
decided by the question, not by the caller. `MEASURED_*` decides what is pinned and must stay
conservative; `REFIT_*` is better-sampled and is consulted only once a letter is already suspect
and every letter around it is settled. Shipping the refit globally is still an open decision and
still collapses the pinning band -- see the section above.

`RESCUE_MARGIN_NATS` is a ceiling, not a preference: a non-word scores `oovLogPrior` rather than
zero, so the whole budget a correction has to spend is a few nats. A margin of 6 switches the
feature off silently, which is how the number was arrived at. The binding gaps are the ones
between two *real* readings -- `ot` → `it` at 1.4 nats -- not the word-versus-non-word ones, which
widened when the floor was corrected.

**A tap is stored relative to the keys it was aimed at, never as pixels.** A pending word outlives
the key grid it was typed on: a rotation, a split-screen drag, the navigation bar arriving, a
one-handed squash flick. `TapDecoder.Tap` therefore resolves the geometry at the instant of the
press and keeps only the per-letter offsets, and `read()` takes no geometry at all -- there is no
later grid for it to consult. This is not a style choice; it is the fix for a real bug. The taps
used to be raw pixels re-scored against whatever geometry was current at the *next* keystroke, so a
resize mid-word shifted every letter sideways and the decoder returned the best word for keys
nobody had pressed. `code` typed accurately came back as `vodr` -- both changed letters exactly one
column right, the two that did not change being the ones whose right-neighbour swap spells nothing
-- and it flipped in a single edit because the composing region is rewritten whole. Flushing on
resize would have closed the one path anybody had thought of; making the geometry's lifetime end at
the press closes all of them. `TapDecoderTest` pins it across eight widths and both squash
directions.

`MIN_TAPS` is the one that looks arbitrary and is not. At a single letter the prior is not about
a word, it is about which letters English words begin with -- a fact about the dictionary rather
than about the person typing, and one that would otherwise apply to every first keystroke ever
made. Two letters is where it starts describing a word shape instead of an alphabet.

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

### Trackpad in Firefox: caret reports need a composition

Firefox (GeckoView) returns `true` from `requestCursorUpdates` and then sends no
`CursorAnchorInfo` at all -- not for `IMMEDIATE`, not while monitoring -- unless a composition
exists. The trackpad seeds its marker from the first report, so in every web textarea it was
completely inert. With any composing region in place, each `IMMEDIATE` request is answered in
about 5 ms with the exact caret position; the report does not arrive by itself when the caret
moves, only when asked.

So if nothing has reported 60 ms after the trackpad starts, one character is marked composing
(`setComposingRegion`, no text changes), a fresh `IMMEDIATE` request follows every
`onUpdateSelection`, and the region is finished when the drag ends. The need is remembered per
field. Its reports leave `selectionStart/End` at -1, so offsets come from `onUpdateSelection`.

Two things this exposed, both general:

- **Line pitch is not caret height.** Vertical steering divided by the caret's height (59px)
  while the textarea's lines are 72px apart, so a marker between two lines was claimed by both
  and the caret flipped up and down every few milliseconds. The pitch is now measured from real
  vertical steps.
- **Up on the first row goes to offset 0** in Firefox (and down on the last to the end). Moved in
  the text, same row -- which is what a scroll looks like, so it set off the repeating edge
  scroll. Landing on the end of the text is now read as the edge.
- **Its editor bounds are wrong, so they are ignored.** A textarea whose text ran from x=38 to
  990 reported `editorBounds` of 204..912 after the matrix; in another field the caret sat at
  x=67 inside reported bounds of 168..596. The row-edge guard stopped left arrows a character
  inside the phantom left edge, so the marker slid on to the start of the line while the
  caret stayed put -- the "marker moves, cursor doesn't follow" report. For an editor that
  needs the borrowed composition, the bounds are treated as absent (left edge 0, right edge
  learned from a wrap), the same as the test pad.

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
| rise | 1.45 key heights, upward only, character keys only |
| accent popup | opens level with the raised key's top, from the same number |
| ceiling | the top of the keyboard, which only binds on the top row |
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

**The alternatives open where the letter already is.** A press and a long press are one gesture
arriving at two depths, so the accent popup's top edge is the raised key's top edge, from the same
constant. Holding a key would otherwise jog its contents a second time for no reason the hand
could feel. It also fixes how high the rise should be, which was guesswork before: high enough to
reach where the alternatives have always opened. The one clamp is the top of the keyboard -- there
is nowhere above the view to draw, and on the top row a key would have its rounded top sliced off
by the window edge, which looks broken in a way that being twenty pixels lower does not.

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

### The long-press popup — `layout/PopupGrid`, `layout/EditAction`

The popup used to be a row of accents and is now a grid that may hold editing commands as well:
holding `c` offers copy, `v` paste, `x` cut, `a` select all, `z` undo, `y` redo, and Enter carries
the whole menu. The letters are the ones from the desktop shortcuts, which is the entire reason it
needs no teaching -- ctrl+C has meant copy for forty years. It is FUTO Keyboard's arrangement.

**An action is not text, and the type says so.** A popup cell is a `PopupEntry.Accent` or a
`PopupEntry.Action`, and they leave as different outputs (`CommitAccent`, `CommitAction`). The
alternative -- a string with a flag -- has one failure mode, which is committing the word "Copy"
into the field, and the sealed type makes it unrepresentable. Actions run through
`performContextMenuAction` with the platform's own `android.R.id.*`, not synthesised ctrl+key
events: a WebView or a Compose field honours the menu action and ignores a shortcut it never
registered.

**The primary sits under the thumb, and that is what positions the popup.** The popup is placed so
that the entry it opens on is the one the finger is already on -- so holding `c` and simply
letting go copies, with nothing to aim at. A key with no action has its middle accent there, which
is the same thing as centring the popup on its key, so accent popups kept the placement they had.
The single action a key carries therefore goes in the *middle* of the entry order rather than at
the front; leading with it would work for that one gesture and would hang the popup off to one
side of the key it belongs to.

**Where clamping wins, the arrangement moves rather than the popup.** Against the ends of the
board the popup has to be pushed back on screen and the column over the thumb is no longer the
middle one -- on `a`, the leftmost key, only column 0 ever lands under the finger. So the column is
decided *first*, from the geometry, and the entries are then arranged around it. Positioning alone
cannot fix this, because by then the popup has nowhere left to move.

**Long rows wrap, and the ragged edge goes at the top.** Beyond five entries the popup becomes a
grid, because eight accents in one row span most of the keyboard, drag the popup away from its own
key, and put the far end across the hand holding the key down. Cells are indexed row-major, so a
row that is not full has to be the first one; the bottom row -- the one the thumb is on -- is
always whole, and the gap is padded with `PopupEntry.Blank`, which draws nothing and commits
nothing.

**One object owns both the drawing and the hit test.** `PopupGrid.of(key, geometry)` is the only
way to build one, and the renderer and the state machine both call it. They used to compute the
layout separately, and the two drifted: the popup lit one entry and committed another wherever the
clamp had shifted it. Everything the popup's shape depends on -- including `PRESS_LIFT`, which
decides how high the pressed key stands -- lives in that one file for the same reason.

**The opening highlight is measured, never assumed.** It is `entryAt` of where the finger actually
is, not index 0. Hardcoding it meant the first move event -- a pixel of tremor, before any
deliberate movement -- recomputed the cell and the selection visibly jumped.

**A hold buzzes.** Opening the popup, starting the spacebar trackpad and beginning a backspace
repeat all fire `HapticFeedbackConstants.LONG_PRESS`, and moving between cells fires the ordinary
keyboard tick. These are the moments where something happens *while the finger is doing nothing*,
and on the popup and the trackpad the thing that appeared is underneath the hand that summoned it,
so a buzz is the only signal that does not require looking.

### Cursor and selection

| parameter | value | why |
|---|---|---|
| trackpad step rounding | nearest, strict `>` | see above |
| character width fallback | 0.33 × line height | average lowercase advance; measured thereafter |
| row-edge margin | 1.2 characters | a wrapped row reaches the editor edge by definition |
| selection horizontal cap | ±24 characters per round | bounds a correction, the loop does the rest |
| edge scroll rate | 260 → 45 ms per line | proportional to distance past the edge |
