# Framework: Piano Roll

Lets a mod read the selected notes, the editor's grid step and the key signature, find where notes overlap and add, copy, move, edit and select notes as one Undo step, show a plan as Bitwig's own preview notes before applying it, mark notes with Bitwig's eraser stripes, and hear when the selection or the project changes, in plain Java with no Bitwig names. A mod using only this API has no hooks, no opened members and nothing for Patchbay to translate.

It is a mechanism. It adds no menu entries, decides no musical rules and shows nothing to the user unless a mod asks: what to do with the notes, and which of them to refuse, is the mod's.

- Mod ID: `piano-roll-framework`
- Built against Bitwig 6.1.4.

## Using it

**1. Require the framework in `mod.json`.** There is nothing to register:

```json
"requires": [
  { "id": "piano-roll-framework", "min_version": "<version>" }
]
```

**2. Compile against its helper jar**, in `build.json`:

```json
"classpath_mods": ["piano-roll-framework@<version>"]
```

**3. Call it.** All of it is in the package `bml.pianoroll`. This makes each later overlapping note start where the earlier one ends:

```java
static void makeLegato(Object menuSelection) {
    try {
        List<Note> notes = PianoRoll.selection(menuSelection).notes();
        Edit edit = PianoRoll.edit("Make Legato");
        for (Overlap overlap : PianoRoll.overlaps(notes)) {
            if (overlap.later().end() <= overlap.earlier().end()) {
                throw new Refused("a note lies inside another"); // this mod's rule
            }
            edit.trimStart(overlap.later(), overlap.end());
        }
        edit.apply();
    } catch (Refused e) {
        MOD.log("nothing changed: " + e.getMessage());
    }
}
```

The framework doesn't need Action and Menu. From an action, pass `context.selection()` as the selection; a mod with no menus passes null or a selection of its own.

## Reading notes

`PianoRoll.selection(bitwigSelection)` returns a `NoteSelection`.

| `bitwigSelection` | What it reads |
|---|---|
| The selection a menu was opened on (`ActionContext.selection()`), or a note editor's selection from a mod's own hook | That selection |
| The Note Inspector panel a block is built in (`BlockContext.panel()`) | The notes the panel shows. When it shows none, as for null |
| null (a shortcut, the Commander, a toolbar dropdown) | The one note editor whose selection is all notes. `Refused` when there are none or several |

| `NoteSelection` | |
|---|---|
| `itemCount()` | Everything selected, notes or not |
| `hasOtherItems()` | Something selected isn't a note |
| `notes()` | One `Note` per selected note item, in Bitwig's order. A note can appear twice |

A `Note` is the note as it was when read. Editing doesn't change it: read the selection again after `apply()`. Two `Note`s are equal when they are the same Bitwig note.

| `Note` | |
|---|---|
| `key()` | 0-127 |
| `channel()` | The note's channel |
| `start()`, `end()` | In the clip's beats |
| `velocity()`, `releaseVelocity()` | 0 to 1, as Bitwig holds them: Bitwig's 127 is 1 |
| `clip()` | The note's `Clip`. Note times can only be compared within one clip. Null when Bitwig reports no clip |
| `curveTime(clipTime)` | A clip time as a time on this note's pitch curve |
| `pitchCurve()` | A list of `PitchPoint`, in Bitwig's order; empty when the note has none. Read the first time it's asked for. `Refused` when the note holds something that can't be read as points |
| `bitwigNote()` | Bitwig's own object, for a mod that goes deeper |

| `PitchPoint` | |
|---|---|
| `PitchPoint.of(time, semitones)` | A new point, with a straight line to the next |
| `time()` | In curve time: `curveTime` converts a clip time to it. A trim moves a note's start and its curve time together, so a curve time stays valid across `trimStart` |
| `semitones()` | The pitch offset |
| `straight()` | A straight line to the next point, with no curve shape |

## Reading the grid

`PianoRoll.gridStep(bitwigSelection)` returns the editor's musical grid step, in beats: its fixed size with its subdivision, or the size the adaptive grid shows at the current zoom, straight. `bitwigSelection` works as for `PianoRoll.selection`, with the same `Refused`. It is 0 when the editor has no grid.

Grid lines are multiples of the step from the clip's beat 0. Whether to snap to them is the mod's decision.

## Reading a clip

`note.clip()` gives a note's clip. A mod with hooks of its own, holding Bitwig's clip object, uses `PianoRoll.clip(bitwigClip)`, which returns null for anything else. Two `Clip`s are equal when they are the same clip.

| `Clip` | |
|---|---|
| `notesBetween(start, end)` | The clip's notes sounding in part of (start, end), in the clip's beats, whatever their keys, sorted by start. A note that only touches the range isn't one, as in `overlaps` |
| `keySignatureAt(beat)` | Bitwig's global key at this beat of the clip, as a `KeySignature`: `root()` is its root as a pitch class, 0 for C to 11 for B, and `inScale(semitonesAboveRoot)` says whether the scale holds that note. `Refused` when there is none to read |
| `bitwigClip()` | Bitwig's own object, for a mod that goes deeper |

## Finding overlaps

`PianoRoll.overlaps(notes)` returns every pair of the given notes that overlaps, whatever their keys, as a list of `Overlap`. Two notes overlap when the later one starts more than 0.000000001 beats before the earlier one ends, so notes that only touch don't.

| `Overlap` | |
|---|---|
| `earlier()`, `later()` | The two notes. Of notes starting together, the one given first is `earlier()` |
| `start()` | Where the overlap starts: the later note's start |
| `end()` | Where it ends: whichever note ends first |

How long it lasts is `end() - start()`.

The list is sorted by the earlier note's start, then the later note's, and the notes can be given in any order. Three notes sounding together are three pairs, and a note inside another is a pair like any other. It refuses nothing: whether a pair is acceptable is the mod's decision.

## Editing notes

`PianoRoll.edit(undoLabel)` returns an `Edit`: a plan. Nothing changes until `apply()`, so a mod can throw `Refused` at any point while planning and nothing has changed. Edits run in the order they were added.

| `Edit` | |
|---|---|
| `add(clip, channel, key, start, duration, velocity, bitwigSelection)` | Adds a note and returns a `Note` naming it, so later edits of the same plan can name it too. The note exists once this edit has run. It goes in as Bitwig's own insert does: it cuts notes of the same key and channel in its range. `bitwigSelection` is the note editor's selection, which then holds only this note; null leaves it alone. `Refused` when the clip can't be edited or the note isn't valid |
| `trimStart(note, start)` | Moves the start and keeps the end. The pitch points stay where they are in time |
| `setEnd(note, end)` | Moves the end. The note's start is read when the edit runs, so a plan can trim and set the end of one note |
| `move(note, start)` | Moves the note to `start`, keeping its length, with its expressions moving with it. It doesn't cut or move notes it lands on. `Refused` for a start that isn't valid |
| `copy(note, start, end)` | Adds a copy of the note, with everything it holds, from `start` to `end` in the note's lane, and returns a `Note` naming it, so later edits of the same plan can name it too. The copy is made from the note as it is when this edit runs. Copies go into their lanes after all the plan's other edits, so notes the plan shortens, moves or removes are out of the way first; a note still there where a copy lands is cut there, as Bitwig's own insert cuts. `Refused` for a note this plan adds or copies, one not on a note lane, or a range that isn't valid |
| `copy(note, key, start, end)` | The same on another key of the note's channel |
| `copyIfAbsent(note, key, start, end)` | The same, but when the edit runs and the key's lane already has a note from `start` to `end`, there is no copy. Returns the `Edit`, as later edits can't name this copy |
| `setKey(note, key)` | Moves the note to another key of its channel. It stays the same note, with everything it holds and its place in the selection. A note already on that key where it lands is cut there, as Bitwig's own insert cuts. `Refused` for a key that isn't 0 to 127 |
| `setVelocity(note, velocity)`, `setReleaseVelocity(note, velocity)` | 0 to 1. `Refused` for anything else |
| `selectLike(note, like)` | Adds `note` to the selection in every place `like` is selected. Runs after all the plan's other edits, in the same Undo step. If Bitwig won't take a note, that selection stays as it was and the reason is logged; the edits stand. `Refused` when `like` wasn't read from a selection |
| `setPitchCurve(note, points)` | Replaces the whole pitch curve. Straight lines only: `Refused` for a point with a curve shape, so a curve is never flattened |
| `remove(note)` | Removes the note. `Refused` when it isn't on a note lane |
| `apply()` | All of it, as one Undo step named `undoLabel`. A plan applies once; one with no edits does nothing. A preview on screen ends first |
| `preview()` | Shows what the plan would leave, without changing anything: see Previewing a plan |

Each call returns the `Edit`, except `apply()`.

Editing in place keeps everything else a note holds (velocity, chance, other expressions and so on).

A `Note` that `copy` returns is equal only to itself. Before `apply()` it reads as the note copied, at its new times, and `bitwigNote()` is null.

### Copies and expressions

A copy holds the note's whole expressions, and which part of them it plays is the mod's choice:

```java
// Plays them from their beginning: a slide plays again in this piece.
Note piece = edit.copy(note, line, note.end());

// Continues them: this piece plays what the note played from that line on.
Note piece = edit.copy(note, note.start(), note.end());
edit.trimStart(piece, line);
```

To cut a note into pieces, copy before shortening it, as a copy takes what the note holds when its edit runs:

```java
Note piece = edit.copy(note, line, note.end());
edit.setEnd(note, line);
edit.selectLike(piece, note);
edit.apply();
```

## Previewing a plan

`edit.preview()` shows what the plan would leave, without changing anything, and returns a `Preview`. Every note the plan adds or copies, and every note it changes as it would end up, is drawn as one of Bitwig's own preview notes: the kind Bitwig draws under the pencil while you drag. Preview notes look like notes and can't be selected. They play only in a heard preview (see Hearing a preview). They aren't saved, aren't recorded for Undo and don't mark the project as changed.

By default the notes the plan removes or changes stay where they are, as they are. `edit.preview(hideChanged, hideRemoved)` hides them while the preview shows, as Bitwig hides the notes under a drag: `hideChanged` hides each note the plan changes, which then shows only as it would end up, and `hideRemoved` hides each note the plan removes. Hidden notes come back when the preview ends. With both on, what is visible is exactly what `apply()` leaves: a removed note is hidden over its own time in its lane, and any other note of that lane reached there, which the plan keeps, is drawn again in its place as a plain preview note. The one exception is a note the plan doesn't touch that an added, copied or re-keyed note cuts where it lands: it is drawn whole, and for `add` it is listed in `changed()`. To preview exactly, plan that cut as edits of its own (shorten, split or remove the note first), so nothing is under the note when it lands. Which notes to hide, and which to mark with `Stripes`, is the mod's choice. For the result to match what is applied, apply the same `Edit` that was previewed. A note the plan leaves exactly as it was is never hidden, drawn or listed.

| `Preview` | |
|---|---|
| `changed()` | The notes the plan would remove or change, as they are now, in the plan's order, and those a note it adds would cut |
| `removed()` | Of those, the notes the plan removes |
| `drawn()` | The preview notes on screen, as Bitwig's own note objects: every note added or copied, and every changed note as it would end up. Pass them to `Stripes.show` to mark the outcome |
| `isShown()` | Still on screen |
| `isHeard()` | The preview plays in place of the notes it changes: it was asked to be heard, and is shown |
| `audition()` | Plays the preview notes once, together, on each clip's track, as Bitwig auditions a chord: see Hearing a preview |
| `end()` | Takes the preview notes out. The project is as it was. Ending it again does nothing |
| `onEnded(listener)` | Runs once if anything other than `end()` ends the preview |

A project shows one preview at a time, Bitwig's or a mod's, so a new one replaces the one before. Bitwig ends a preview by itself before its own previews and edits (the pencil, dragging notes, pasting), and the framework ends it as soon as anything Undo can undo changes, before `Edit.apply()`, and before Undo or Redo. Moving the view and playing don't end it. `onEnded` runs during whatever ended it, which may be Bitwig's own change, so it should only clear what the mod shows and post anything more with `PianoRoll.later`.

Call `preview()` from an event of its own, with `PianoRoll.later`, not from inside Bitwig's handling of a click or a value: Bitwig doesn't take a preview while it is changing the project, and the framework refuses then with an `IllegalStateException`. The plan isn't used up: it can be applied afterwards, or previewed again. Pitch curves are drawn as the notes have them now; a planned `setPitchCurve` shows only in the note's place.

```java
static Preview shown;

static void showOutcome(Object panel) {
    PianoRoll.later(() -> {
        if (shown != null) {
            shown.end(); // replacing our own preview: onEnded doesn't run
        }
        Edit edit = plan(PianoRoll.selection(panel).notes());
        shown = edit.preview();
        shown.onEnded(() -> shown = null);
    });
}
```

Each new preview costs what planning costs, plus a copy of each note it draws and a notice to each lane it touches, which Bitwig answers with a repaint (and, in a clip with take lanes, by rebuilding its comp). Ending one tells the same lanes again. With `hideChanged`, each repaint of the clip asks, for every note, whether a preview note stands in for it, which Bitwig answers by going through the clip's preview notes: a preview of a few hundred notes over a clip of a few thousand costs that many comparisons per repaint.

### Hearing a preview

A preview is silent unless the mod asks for it to be heard. Whether it is, and when, is the mod's choice.

`edit.preview(hideChanged, hideRemoved, heard)` with `heard` true shows the preview as `preview(hideChanged, hideRemoved)` does, and the clips it changes also play as `apply()` would leave them, in place of their real notes, whenever they play: with the transport, in a loop or from the launcher, in time and with every expression, because it is Bitwig's own playback. When the preview ends, by `end()`, `apply()`, a change, Undo, Redo or Bitwig's own preview, the clips play their real notes again. Nothing is saved, recorded for Undo or counted as a change to the project.

What plays is the outcome, whatever is hidden: the notes the plan removes or changes are silent, and the preview notes play in their place. As in the drawing, a note an added note would cut plays whole, and pitch curves play as the notes have them now. A clip whose content is shared plays the preview everywhere it is used, as `apply()` would change it everywhere. Exporting or bouncing while a heard preview is shown may include it: end the preview first.

`isHeard()` says whether the preview plays. It is false for a silent preview, and on a Bitwig where previews can't be heard, which then shows them silent.

`audition()` plays the preview notes once, all together, on each clip's track, through Bitwig's own audition (the one that plays a note you click): each note's key at its velocity, for the notes' average length, up to 500 ms, Bitwig's own limit. It needs no transport and works for a silent preview too. A key Bitwig is auditioning already isn't started again, and Bitwig's next audition stops these notes. It does nothing once the preview has ended.

```java
shown = edit.preview(true, true, playPreview); // the mod's own option
shown.audition(); // and a short blip of the outcome now
```

A heard preview asks Bitwig to rebuild the playback of each clip it changes when it starts and when it ends: the same work Bitwig does after an edit of those clips, once each. In between, rebuilding a clip that a heard preview changes costs one pass over each changed lane's notes. With no heard preview, the playback build makes one extra check per lane, and it runs only when notes change, never when painting.

## Stripes

`new Stripes()` is a set of notes drawn with Bitwig's eraser stripes, the look Bitwig gives a note the eraser is about to delete. Nothing in the project changes. Each `Stripes` is separate, so mods don't replace each other's.

| `Stripes` | |
|---|---|
| `show(notes)` | Stripes on these notes from now on, in place of the ones before. Each is a `Note` or Bitwig's own note object. A planned copy that doesn't exist yet is passed over |
| `clear()` | No stripes from this `Stripes` |
| `isShown()` | Any note has these stripes |

Bitwig repaints a note editor when its notes change, so stripes changed in the same event as a preview or an edit show at once. Stripes changed on their own show at the editor's next repaint. Painting asks one question per note: with no stripes shown anywhere, it finds nothing to look at.

## Listening

| | |
|---|---|
| `PianoRoll.later(task)` | Runs `task` on Bitwig's UI thread once the event being handled is finished |
| `PianoRoll.onSelectionChange(listener)` | Calls `listener` with a note editor's `NoteSelection` after its selected notes become a different set: notes selected or deselected, or an editor opening with notes selected. Bitwig re-creating its selection of the same notes isn't a change. At most once per editor and event, after the event |
| `PianoRoll.onChange(listener)` | Calls `listener` after any change to something Undo can undo, and after Undo and Redo, in any open project, once per event, after the event. Moving the view, playing and previews aren't such changes. Changes made by `Edit.apply()` aren't reported: a mod knows when it applies a plan |

Both return a `Listening`; `stop()` ends it. Nothing is watched until a mod first listens to the selection, and a stopped listener costs nothing.

## Notifications

`PianoRoll.notification(text)` shows text in Bitwig's on-screen notification, as Bitwig does for its own messages. It is deprecated: use Framework: Notifications (`Notifications.toast(text)`), which also follows that framework's options. It keeps working.

## `Refused`

`Refused(reason)` means the notes can't be edited as asked. The framework throws it, always before anything has changed, and mods throw it too. Catch it around the whole operation and log the reason.

## Other versions

The framework's Bitwig names are translated by Patchbay like any mod's. If a future Bitwig has no counterpart for one of them, the framework isn't available on that version, and neither are the mods that require it.

## What it does in Bitwig

Seven hooks, none of which changes anything in the project:

- `bie.<init>(bii)`, at return: remembers note editors' selections, held weakly, for reading the selection when none is passed, and listens to their selected items once a mod listens to the selection.
- `bro.cfM(SYo,Ibv,kPd,...)`, the note painter, replacing its call to `Ibv.EUG()` (the eraser's "about to be deleted"): also yes for a note with `Stripes`.
- `dfI.BNq()`, at entry: an empty method Bitwig's Undo recorder calls after each change it hears to something Undo can undo.
- `dhu.lYc()` and `dhu.wGc()`, at entry: Undo and Redo.
- `HEL.cfM(lSj)`, which encodes a note clip's notes for the audio engine, replacing its call to `YeP.wGc()` (the clip's played lanes): adds a heard preview's own lanes.
- `HEL.cfM(lSj)`, replacing its call to `Rke.cfM()` (a lane's notes): gives a lane a heard preview changes as `apply()` would leave it, sorted by start.

A preview is a subclass of Bitwig's own project preview (`WWV`), set with the project's own call (`g2L.MRY(WWV)`) and cleared with its own (`g2L.hQm()`). Its notes go into the lanes' preview lists, as the pencil's note does. A changed note is hidden by its preview note standing in for it (`wMV.cfM(lh)`, as Bitwig's Note Inspector previews do); Bitwig looks for stand-ins in played lanes' preview notes and in preview lanes' own notes, so a preview note on a key with no lane goes into a new preview lane as its own note. A removed note is hidden by a time range in its lane's preview clear ranges (`xO1.a_(sZK)`).

The engine plays a copy of each clip's notes, which Bitwig builds in Java from the lanes' real notes. Preview notes aren't in it, so a silent preview is never heard. A heard preview asks Bitwig to rebuild that copy with Bitwig's own call (`lSj.qm()`, which rebuilds it at the end of the event), and the two encoder hooks give it the outcome. The copy isn't saved, recorded for Undo or counted as a change. The audition uses the track's own audition player (`o1U`, from `xJo.nfq()`), with its timed play of one key.

Four members are opened: `IOJ`, `IOJ.<init>`, `ApiModificationProcessor` and `PSn.Hnt(Collection)`.
