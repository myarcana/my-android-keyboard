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

## Collecting

```
tools/gestures.sh lab      # build, install, open the lab with our keyboard selected
```

The lab asks for one gesture at a time and alternates symbol and word within a key. It alternates
on purpose: eight flicks in a row are not eight samples of a flick, they are one sample of a
rhythm the hand falls into, and a heuristic tuned on that works only for people doing drills.

Every attempt is kept, including the ones the keyboard reads wrongly — those are the most useful
samples in the file. The lab shows what the current build decided, so it is obvious in the moment
when you are on the boundary.

Nothing is recorded outside the lab. The keyboard emits every completed gesture, but with no
drill armed they are dropped, so ordinary typing never reaches the file.

## Storing

The phone writes `files/gesture-bank.jsonl` in internal storage, fsynced per line — data
collection sessions end the way phone sessions end, and a page-cached line that never landed is a
gesture that will not be performed again.

That copy does not survive an uninstall, and this app deliberately has no cloud backup. So:

```
tools/gestures.sh pull     # merge the phone's copy into data/gesture-bank.jsonl
```

The merge is keyed on each record's id and keeps both sides, because neither is the master: the
phone has the newest sessions, the repo copy is the one that survives. `data/gesture-bank.jsonl`
is committed. It is the archive of record.

## The format

One JSON object per line. Written and read by `gesture/GestureRecord.kt`, which both the app and
the JVM tests use, so the two ends cannot disagree.

```json
{"v":1,"id":"892f902b","at":1756000004000,
 "intent":"SYMBOL","prompt":"i:symbol","expected":"8",
 "startKey":"i","layout":"en_qwerty_lower",
 "widthPx":1080.0,"keyUnitPx":92.0,"keyHeightPx":120.0,
 "verdict":"FLICK",
 "thresholds":{"flickDistanceRatio":0.45,"verticalDominance":1.5,
               "glideDistanceRatio":1.2,"flickToGlideRatio":2.0},
 "path":[[807.2,210.0,0],[806.1,241.3,18],[808.4,299.7,44]]}
```

`path` is `[x, y, milliseconds since the finger went down]`, in the keyboard's own pixels, at the
width in `widthPx` — which is all the geometry needed to rebuild the exact layout it was made on.

Two fields are worth defending:

- **`intent`** is the label, and the only thing in the record that cannot be recomputed. It is
  what makes the file worth keeping.
- **`thresholds`** is what was live at the time. Without it, `verdict` would say what some
  unknown build once thought, which is worse than saying nothing. With it, a record made under
  one set of thresholds is still honest evidence after they move.

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
- **the current thresholds' score**, as symbol recall, word recall and the mean of the two.
  Balanced rather than plain accuracy: a drill session is never perfectly balanced, and plain
  accuracy rewards a heuristic that simply favours whichever label is commoner. Both mistakes
  have to cost the same, because neither is acceptable.
- **the best of ~17,000 threshold sets**, and how many tie with it. The winner reported is the
  *middle* of the tying region, not the first point in it — a threshold on the edge of a plateau
  is one unusual swipe from being wrong.
- **room on each axis**, and a one-at-a-time sensitivity table. A parameter whose row is flat is
  not doing any work, and should be left where it is rather than moved to whatever the sweep
  happened to pick.
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
