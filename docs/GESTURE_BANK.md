# The gesture bank

Telling a swipe-down-for-the-symbol from the start of a glided word is not a problem you can
reason your way to a threshold for. The two gestures are the same gesture for the first two key
heights, and which one it is depends on what the person meant, which is not in the touch data.

So it is settled with evidence instead. The Gesture Lab asks for one specific gesture, watches
you make it, and files the raw path under what it asked for. Once there are enough, every
proposed threshold can be scored against all of them in seconds.

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

```
tools/gestures.sh lab      # build, install, open the lab with our keyboard selected
```

The lab shows a passage and you type it. Every gesture is filed under the token it was aimed at,
so the label is still asked for before the gesture is made — which was always the point — but the
gesture itself is now made the way gestures are actually made.

**This replaced asking one at a time, and the reason is worth stating.** The first lab printed an
instruction — *"Swipe down on O to type 9"* — waited, recorded, and moved on. The labels were
unimpeachable. The gestures were not: reading an instruction, finding the named key and
performing the named movement is a different motor task from typing, and it produces a different
movement. Slower, more deliberate, aimed at a key the eye has just located rather than one the
thumb already knows. Thresholds fitted to those are thresholds fitted to somebody doing an
exercise, and the keyboard will never see one again.

A passage restores the missing thing, which is flow. The eye reads ahead, the thumb moves without
being told where, and the gestures arrive at typing speed with typing's sloppiness in them.

Two kinds of passage, for two different questions:

- **Collisions** are token streams — `u u 7 um u 7 on 9 o` — because the flick-versus-glide
  boundary lives on a handful of keys and nowhere else. Prose would spend a hundred gestures to
  collect three useful ones. Every token here is on the boundary, and the three kinds still
  alternate within a key: eight flicks in a row are not eight samples of a flick, they are one
  sample of a rhythm the hand falls into.
- **Prose** is real English, because glide decoding is only half geometry. The other half is
  which words exist and how often they are written, and a decoder is only as good as the word
  distribution it is scored against. Random words would measure the shape matching alone — and
  would flatter it, because random words sit further apart than real ones do.

Every attempt is kept, including the ones the keyboard reads wrongly — those are the most useful
samples in the file. The passage colours each token by what the keyboard made of it, and the line
underneath says what was read, what was typed, and whether the finger lifted on the way.

Nothing is recorded outside the lab. The keyboard emits every completed gesture, but with no
target armed they are dropped, so ordinary typing never reaches the file.

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

That copy does not survive an uninstall, and this app deliberately has no cloud backup. So:

```
tools/gestures.sh pull     # merge into data/gesture-bank.jsonl, and commit it
```

The merge is keyed on each record's id and keeps both sides, because neither copy is the master:
the phone has the newest sessions, the repo has the ones that survive an uninstall.

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

```json
{"v":2,"id":"892f902b","at":1756000004000,
 "intent":"WORD","prompt":"glide:morning","expected":"morning","decoded":"morning",
 "startKey":"m","layout":"en_qwerty_lower",
 "widthPx":1080.0,"keyUnitPx":92.0,"keyHeightPx":120.0,
 "verdict":"GLIDE",
 "thresholds":{"flickDistanceRatio":0.2,"verticalDominance":4.25,
               "glideDistanceRatio":1.2,"flickToGlideRatio":2.5,
               "glideResumeMs":120,"glideResumeRadiusRatio":1.25},
 "path":[[807.2,210.0,0],[806.1,241.3,18],[808.4,299.7,44]],
 "strokes":[17]}
```

`path` is `[x, y, milliseconds since the finger went down]`, in the keyboard's own pixels, at the
width in `widthPx` — which is all the geometry needed to rebuild the exact layout it was made on.

Version 1 lines still read. They were recorded before a glide could be interrupted at all, so a
missing `strokes` genuinely means one stroke and a missing resume threshold genuinely means the
build had none — the number exists so a reader can tell which absences are real, not so old data
can be refused. The bank is the one thing here that must never be invalidated by a change to the
code that reads it.

Four fields are worth defending:

- **`intent`** is the label, and the only thing in the record that cannot be recomputed. It is
  what makes the file worth keeping.
- **`thresholds`** is what was live at the time. Without it, `verdict` would say what some
  unknown build once thought, which is worse than saying nothing. With it, a record made under
  one set of thresholds is still honest evidence after they move.
- **`decoded`** is the word the glide decoder produced, and it is not recoverable later: it
  depends on the lexicon and the weights that were live at the time, and both will change.
  Keeping it beside the path turns "the decoder got this one wrong" from an impression into a
  line in a file that can be counted.
- **`strokes`** are the indices at which the finger came back down. How long each lift lasted and
  how far the finger moved across it are both derived from the samples either side, which is why
  they are not stored: two ways to say the same thing is one way to be wrong. This is what makes
  a bank collected under one resume window scorable under any other.

Raw paths are stored rather than features, because a feature is a guess about what matters, and
the point of the exercise is that nobody knows yet.

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
