# The gesture bank

Telling a swipe-down-for-the-symbol from the start of a glided word is not a problem you can
reason your way to a threshold for. The two gestures are the same gesture for the first two key
heights, and which one it is depends on what the person meant, which is not in the touch data.

So it is settled with evidence instead. The Gesture Lab shows a passage, records every gesture
made against it, and writes down what each one put into the field. Once there are enough, every
proposed threshold can be scored against all of them in seconds.

The lab records and does not judge. It used to do both, and every way it went wrong went wrong
the same way: it decided at the moment of the gesture what the gesture had been *for*, which is
the one thing that is not knowable then. What was meant only becomes visible in what the typist
does next — a backspace is the ground truth, and it arrives afterwards. So the file holds the
passage, the paths and the text, and every question about them is asked later. See
[What version 6 deleted](#what-version-6-deleted-and-why) for the four ways the old habit failed.

## Why "I'm" is the hard case

Key centres, in key-width units across the keyboard. The top row sits at 0.5, 1.5, 2.5 … and the
home row is inset by half a key, so it sits at 1.0, 2.0, 3.0 …

```
     q    w    e    r    t    y    u    i    o    p        0.5 .. 9.5
       a    s    d    f    g    h    j    k    l           1.0 .. 9.0
          z    x    c    v    b    n    m                  2.0 .. 8.0
```

`i` is at 7.5. `k` and `m` are both at 8.0. Gliding **I'm** means dragging from `i` through `k`
to `m`: two rows straight down, and half a key to the right. Flicking `i` for its secondary `8`
means dragging from `i` straight down. On the target phone that is 302 pixels down and 53 across
for the word, against 54 or more pixels down for the flick — and the vertical-dominance rule does
not separate them at all, because both are overwhelmingly vertical.

### What makes a word actually confusable

Two conditions, and the narrower one is easy to miss.

**Two keys long.** That makes the whole glide a single stroke with no corner in it. A longer word
gives itself away the moment it changes direction — "was" leaves `w` heading down and half a key
left, which looks exactly like a flick for about forty pixels, and then turns back rightward
towards `s`. Note that "was" *does* end below where it started, so an end-position test would
wave it through; only counting the keys rules it out.

**The second key below the first.** A flick only ever goes down, so a word leaving sideways or
upward was never in the running.

That leaves a short list, and it is genuinely short:

| key | flick gives | words that collide |
|---|---|---|
| `i` | `8` | I'm, in |
| `o` | `9` | ok, on |
| `u` | `7` | um |
| `e` | `3` | ex |

Plus flicks on `k` (the apostrophe), `a`, `d` and `m`, which have nothing hanging below them —
half of telling a symbol from a word is knowing what an uncontested flick looks like.

`GestureCaptureTest` asserts both conditions against the catalogue, so a plausible-looking but
harmless word cannot drift back in.

### Why plain taps are drilled too

The third label, `LETTER`, is not a distraction from the symbol-versus-word question — it is the
other side of it. **The flick threshold trades off against taps, not against glides.** A bank with
no taps in it gives the sweep no reason at all not to drive that threshold to zero, because every
sample it can see is improved by doing so.

The first collected session had exactly this hole, and the sweep duly recommended more than
halving the threshold — down to 12 pixels, below Android's own touch slop — on the strength of
evidence that contained not one ordinary keypress. Every key that gets flick tokens now gets tap
tokens on that same key, in the same minute of the same hand, and a test enforces it.

## Collecting

The lab is an app on the phone with its own launcher icon, and collecting with it needs nothing
else: no cable, no network, no computer. That is the whole design brief for everything in this
section. A rig that has to be started from a terminal is a rig used in half-hour sittings a few
times a month, and the bank it fills is a bank of a few hundred gestures made by a hand that knew
it was being watched. The same rig carried around and opened in a queue collects several times as
much, from a thumb that has stopped paying attention — which is the thumb the keyboard actually
has to read. Getting the data *off* is still a cable and `adb`, and that is fine: it happens
between sessions rather than during them.

```
tools/gestures.sh lab      # build, install, open the lab with our keyboard selected
```

is still how it gets onto the phone, and is not how it gets used afterwards.

The lab shows a passage and you type it. The passage says what you were trying to type and the
recording says what you did, so what any gesture was aimed at is answerable afterwards from the
two together — and answerable with the correction you went on to make already in view, which is
the part the lab could never have had.

**This replaced asking one at a time, and the reason is worth stating.** The first lab printed an
instruction — *"Swipe down on O to type 9"* — waited, recorded, and moved on. The labels were
unimpeachable. The gestures were not: reading an instruction, finding the named key and
performing the named movement is a different motor task from typing, and it produces a different
movement. Slower, more deliberate, aimed at a key the eye has just located rather than one the
thumb already knows. Thresholds fitted to those are thresholds fitted to somebody doing an
exercise, and the keyboard will never see one again.

A passage restores the missing thing, which is flow. The eye reads ahead, the thumb moves without
being told where, and the gestures arrive at typing speed with typing's sloppiness in them.

Three kinds of passage, for three different questions:

- **Collisions** are token streams — `u u 7 um u 7 on 9 o` — because the flick-versus-glide
  boundary lives on a handful of keys and nowhere else. Prose would spend a hundred gestures to
  collect three useful ones. Every token here is on the boundary, and the three kinds still
  alternate within a key: eight flicks in a row are not eight samples of a flick, they are one
  sample of a rhythm the hand falls into.
- **Prose** is real English, because glide decoding is only half geometry. The other half is
  which words exist and how often they are written, and a decoder is only as good as the word
  distribution it is scored against. Random words would measure the shape matching alone — and
  would flatter it, because random words sit further apart than real ones do.
- **The corpus** — `app/src/main/assets/passages_en.txt` — is the same argument applied to
  quantity. Five hand-written prose passages are enough to prove the rig and not enough to live
  with: a thumb that types the same two hundred words every evening gets better at those two
  hundred words, and every number on the screen improves without the keyboard improving at all.
  A hundred and fifty ordinary sentences deal out around fifty passages, which is a few weeks of
  daily collecting before a word comes round again. `PassagesTest` checks every word in it
  against the lexicon, because a word the decoder cannot know records a perfectly good glide
  with a guaranteed-wrong decode beside it, and a run of those reads as a broken decoder rather
  than as a typo in an asset.

### The deck

The passages are dealt, not chosen. One pass over the whole library in an order that changes
every pass, with the collision drill dealt in before every fifth passage, and the position kept
in `SharedPreferences` so closing the app continues the corpus instead of restarting it.

Both halves of that are there for a reason that only shows up over weeks. Opening on passage
zero every time meant collecting the first passage of a corpus several hundred times. And
leaving the drill as something to be picked meant it stopped being picked: it is the least
pleasant passage to type and the only one that answers the question the bank was built for, so
beside forty passages of ordinary English it would quietly starve while the total at the bottom
of the screen went up.

The deck advances when a passage is **finished**, not when Next is pressed — a passage typed to
the end and then abandoned, because the bus came, is the ordinary way a session ends. The only
passage ever repeated is one that was genuinely left half typed.

### The day

`today 43/120 · 5 day streak`, under the passage. Not decoration: the bank's real risk is not
that the data is bad but that it stops arriving, and collection is something a person does
voluntarily, in gaps, with nobody watching. A count that only exists in a terminal on another
machine is a count nobody sees.

The streak counts days with **at least one gesture** in them, deliberately, rather than days that
reached the goal. A rule that demanded the goal would punish a short session more than no
session, and the bank would much rather have the short session.

Every attempt is kept, including the ones the keyboard reads wrongly — those are the most useful
samples in the file. The passage colours each token by what the keyboard made of it, and the line
underneath says what was read, what was typed, and whether the finger lifted on the way.

Nothing is recorded outside the lab. The keyboard emits every completed gesture, but with no
target armed they are dropped, so ordinary typing never reaches the file.

### Withdrawing a label

A gesture the keyboard read wrongly is evidence. A gesture whose *label* is untrue is not, and
the two are easy to confuse because both show up as a mistake in the report.

The second kind happens: the hand starts a flick on `d`, thinks better of it and comes back, and
the passage was asking for a plain tap the whole time. Filed as a tap, that path is 218px of
travel over 1.4 seconds wearing a label that says the finger did not move — and a sweep can only
score it correctly by dragging the glide thresholds somewhere they should not go. One line like
that is worth more than a hundred good ones, in the wrong direction.

So such a line carries a `void` field saying why it is not evidence, and `analyse` sets it aside
and prints it rather than scoring it. **The line stays in the file.** Deleting it would throw away
a real recording of a real thing a hand did — the abandoned flick above is the only record in the
bank of what abandoning one looks like — and a file that quietly loses its awkward lines is one
nobody can audit afterwards.

The lab's **Undo** button is the cheaper fix when the fumble is noticed as it happens: it drops
the last sample and re-arms the same token, so the passage can simply be retyped.

**Void** is the other one, and it is the one that gets used. It withdraws the label on the
gesture just made and keeps the recording, which is the outcome the `void` field exists for.
Before it existed, saying so meant remembering a particular fumble for a week and then finding
its line in a JSONL file on a laptop — which meant it was never said, and the file quietly
accumulated exactly the lines that do the most damage to a sweep.

## Glide typing, and the finger lift

A glide is one continuous stroke in theory and very often is not in practice. A thumb crossing
the width of the keyboard skips, catches on a screen protector, or leaves the glass for a frame
or two going over a ridge in it.

Handled naively, each of those ends the word early: the fragment already drawn is decoded and
**typed**, into the field, while the finger is still travelling toward the rest of the word — and
then the remainder of the gesture types a second word beside it. One skip produces two wrong
words and a correction, which is a far worse failure than a misdecoded word, because the user
never asked for anything to be committed at all.

So a lift does not finish a glide. It suspends it: `GLIDE_LIFTED`, a window of
`glideResumeMs`, and either the finger comes back — within `glideResumeRadiusRatio` of where it
left, onto a letter key — or the window closes and the word is typed. Nothing is shown and
nothing is committed in between, because a word displayed and then replaced is exactly the
flicker the window exists to prevent.

The opposite mistake is just as real: two words deliberately glided in succession must not run
together into one. What separates the cases is mostly the distance — a finger that skipped never
meant to leave and comes back within a key of where it went, while a finger starting the next
word has *travelled* — with the duration as the cheap first test, since a deliberate reach takes
time.

**Neither number can be derived.** How long a thumb is off the glass when it skips is a fact
about a hand and a screen. So both are recorded with every gesture, the boundaries between
strokes are recorded in the path, and `analyse` reports what the lifts actually looked like: the
largest gap that was a genuine skip, which the window has to clear, and the smallest gap between
two deliberate words, which it must not.

## Storing

The phone writes `files/gesture-bank.jsonl` in internal storage, fsynced per line — data
collection sessions end the way phone sessions end, and a page-cached line that never landed is a
gesture that will not be performed again.

It stays there. Not for want of somewhere more durable: shared storage would survive an
uninstall, and it is also readable by every app on the phone with storage access, which is a
strange place for a keyboard built to be structurally incapable of sending what it sees anywhere.
The file is inside the sandbox, and it comes out over adb.

That copy does not survive an uninstall, and this app deliberately has no cloud backup. So:

```
tools/gestures.sh pull     # merge into data/gesture-bank.jsonl, and commit it
```

which reads both copies on the phone — internal storage, and whatever the lab's **More → Export a
copy for adb** last wrote under `Android/data`, for a release build or a ROM where `run-as` is
blocked.

The merge is keyed on each record's id, and the **archived copy wins**. A record never
legitimately changes after it is written, with one exception: its label can be withdrawn later,
here or in the lab. So the only thing an incoming copy is allowed to add to an existing id is a
`void` the archive is missing. Letting incoming win outright — which it did, once — silently
reverts every withdrawal the moment a phone that has never seen them is read again.

**More → Import a bank file** is the way back after an uninstall: it merges a bank handed to it
through the system file picker, so the archive can be put back on a phone without `run-as` or a
shell.

**The pull commits.** Not as a courtesy — a pull that is not committed has saved nothing, since
an untracked file in the working tree is one `git clean` from gone, and this is data that cannot
be regenerated. It commits `data/gesture-bank.jsonl` by pathspec, so it is safe to run with other
work in progress: unrelated staged changes are left staged and untouched.

`tools/gestures.sh lab` also reports, on launch, how many gestures are sitting on the phone that
the repository has never seen. The way this data gets lost is a session that was recorded,
enjoyed, and never pulled.

`data/gesture-bank.jsonl` is the archive of record. Roughly 650 bytes per gesture, so a few
thousand samples is a couple of megabytes -- small enough to keep forever, which is the point.

## The format

One JSON object per line. Written and read by `gesture/GestureRecord.kt`, which both the app and
the JVM tests use, so the two ends cannot disagree.

The bank records three things: **what the typist was trying to type**, **what they did**, and
**what that put into the field**. Nothing else, and in particular nothing about what any gesture
was *for*.

```json
{"v":6,"kind":"session","id":"7c31aa02","at":1756000003000,"passage":"corpus:12",
 "intended":"ok i am","actual":"oi i am","layout":"en_qwerty_lower",
 "thresholds":{"flickDistanceRatio":0.025,"verticalDominance":4.25,
               "glideDistanceRatio":1.2,"flickToGlideRatio":2.5,
               "glideResumeMs":120,"glideResumeRadiusRatio":1.25}}

{"v":6,"id":"892f902b","at":1756000004000,"session":"7c31aa02","seq":3,"typed":"morning",
 "startKey":"m","layout":"en_qwerty_lower",
 "widthPx":1080.0,"keyUnitPx":92.0,"keyHeightPx":120.0,
 "path":[[807.2,210.0,0],[806.1,241.3,18],[808.4,299.7,44]]}
```

`path` is `[x, y, milliseconds since the finger went down]`, in the keyboard's own pixels, at the
width in `widthPx` — which is all the geometry needed to rebuild the exact layout it was made on.
`typed` and `deleted` are what the gesture did to the field, taken from the keyboard at the moment
it did it; applied in sequence they rebuild `actual` exactly, so every character traces to the
gesture that produced it. That mapping is **recorded, never inferred** — rebuilding it afterwards
by aligning strings would be guessing at something that was certain at the time, and it would
guess wrong on exactly the interesting cases: a glide that types a word and a space in front of
it, an emoji that replaces a run of characters, a backspace.

`strokes`, when present, are the indices at which the finger came back down. It reads like
something a reader could derive from a gap in the timestamps and it is not: a mid-glide lift can
be 40ms, which is two or three sampling intervals and indistinguishable from a slow frame.
Deriving it would need a threshold, and a threshold there would quietly reclassify the very
gestures the resume window exists to handle. How long each lift lasted and how far the finger
moved across it *are* derived, from the samples either side — which is what lets a bank collected
under one resume window be rescored under any other.

### What version 6 deleted, and why

Every version up to 5 wrote down what the lab believed, at the moment of the gesture, about what
the gesture was for: `intent`, `prompt`, `expected`, `word`, `letterIndex`. Version 5 had already
established that this could not be known then — it added the transcript for exactly that reason,
and its own source said so — but it kept writing them anyway, and everything downstream kept
reading them as ground truth. Four separate failures came out of that one habit:

- **Space presses filed under a letter.** 23 records claimed to be taps on `a` or `i` — the
  one-letter words — and were the space bar, 6.5 key widths away. They alone moved the fitted
  scatter from 0.23 to 0.80 key widths.
- **A label that drifted a word behind.** One uncorrected glide miss desynchronised `expected`
  for the rest of the session, so `sorry for the late reply it has been` decoded correctly and
  was recorded as seven consecutive failures.
- **A phantom class.** Every tap inside a prose word was filed under the whole word with no
  letter index — 1972 of 2670 records — so the spatial fit could not see them and the threshold
  sweep counted them as failed glides.
- **A degenerate objective.** With a third of its balanced accuracy pinned near 5% by that
  phantom class, the sweep recommended quadrupling `flickDistanceRatio`, undoing a threshold
  this document argues for at length.

Also gone: `decoded`, which repeated `typed` exactly on every line that had both; and `verdict`
with its per-gesture `thresholds`, which are a classification and its inputs — recomputable by
replaying the path, with the inputs now written once on the session line instead of once per
gesture.

**Every one of them still reads.** 803 records predate transcripts entirely and 144 of the
bank's 146 symbol samples are among them, so `intent` is the only thing that says what those
were aimed at. `LegacyLabels` holds them, nothing writes them, and the version number is how a
reader tells a real absence from a missing one. The bank is the one thing here that must never
be invalidated by a change to the code that reads it.

### What a label is for, and who makes it

Flick-versus-glide is the one question the touch data cannot answer about itself, so the only
ground truth for it is an instruction given *before* the gesture. The collision drill gives one;
prose does not, because a passage says which word is due and not whether the thumb will glide it
or tap it out, and both are correct. So the sweep now scores only the gestures somebody was told
to make, and says how many that is.

Everything else a reader might want — which letter a tap was aiming at, whether a word decoded
correctly — is recoverable from the session's `intended` and the text the gestures produced, by
whoever wants it, at the time they want it, with the typist's own corrections already visible.
The lab does not do it, and the harness does not do it either: it turns out the main consumer
never needed a label at all. `tools/fit_spatial.py` assigns each tap to the key it landed nearest
and iterates, and agrees with the aligned fit to within a tenth of a sigma per key while reading
1.5× as many taps.

## Scoring and tuning

```
tools/gestures.sh stats           # what is in the bank
tools/gestures.sh analyse         # replay everything, sweep the thresholds
tools/gestures.sh analyse f.jsonl # score some other bank
```

`analyse` runs `GestureBankReplayTest`, which feeds every recorded path back through the real
`TouchFsm` — not a model of it — so a change to the state machine is scored by the state machine.
It prints:

- **a harness check**, replaying each record under the thresholds it was recorded with. Those
  must reproduce the verdict the phone reached; if they do not, the harness has drifted from the
  keyboard and every other number in the report is fiction. This is the one thing the test
  asserts, and it asserts it *after* printing, so a drift can be read rather than merely failing.
- **the current thresholds' score**, as a recall per label and the mean of them.
  Balanced rather than plain accuracy: a drill session is never evenly split, and plain accuracy
  rewards a heuristic that simply favours whichever label is commoner. Every kind of mistake has
  to cost the same, because none of them is acceptable.
- **the best of ~21,000 threshold sets**, and how many tie with it. The winner reported is the
  *middle* of the tying region, not the first point in it — a threshold on the edge of a plateau
  is one unusual swipe from being wrong.
- **room on each axis**, and a one-at-a-time sensitivity table. A parameter whose row is flat is
  not doing any work, and should be left where it is rather than moved to whatever the sweep
  happened to pick. If a plateau runs to the edge of a grid, the grid was the constraint and not
  the data — widen it and re-run before believing the number.
- **the glide report**: how many glides decoded to the word that was asked for, split by
  whether the finger lifted on the way. That split is the leniency's own scoreboard — a rejoined
  glide should decode about as well as an uninterrupted one, and if it decodes markedly worse the
  window is joining things it should not. Underneath it, the distribution of the lifts themselves.
- **the gestures still misread**, which is the next thing to think about.

Then move the numbers in `GestureConfig` and re-run. The sweep proposes; it does not decide,
because it optimises the bank it has and the bank is a sample of one hand on one phone.

## When the sweep stops helping

The four thresholds are a straight-line boundary through a space that may not have one. If the
report plateaus well below 100% and the failures listed are a mix in both directions, the answer
is not a better number — it is a better feature. The bank is already the right shape for that:
every path is intact, so a new discriminator (curvature after the first key height, dwell before
the direction change, speed profile) can be tried against the whole history without collecting
anything again. That is the reason for storing paths instead of the four numbers that happened to
matter in 2026.
