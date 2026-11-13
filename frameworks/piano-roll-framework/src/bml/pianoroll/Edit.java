package bml.pianoroll;

import bitwig.obf.Bz; // where a new note's selection is kept
import bitwig.obf.PSn; // a note lane
import bitwig.obf.Jsq; // something with a start and a length on a timeline
import bitwig.obf.Rke; // a note lane of one channel and key
import bitwig.obf.YeP; // a clip's note lanes
import bitwig.obf.anL; // Bitwig's rounding of times
import bitwig.obf.TrQ; // a selected note in the note editor
import bitwig.obf.cFh; // where a note lives (its lane)
import bitwig.obf.iGx; // the selection commands of a note editor
import bitwig.obf.n1; // a note
import bitwig.obf.yT2; // what decides whether an item can join a selection
import bml.api.Bml;
import bml.pianoroll.internal.Heard;
import bml.pianoroll.internal.Notices;
import bml.pianoroll.internal.PreviewRule;
import com.bitwig.extension.api.project.parameter.Unit;
import com.bitwig.extension.api.project.timeline.NoteExpression;
import com.bitwig.flt.document.api.document.ApiAutomationEvents;
import com.bitwig.flt.document.api.document.ApiInstrumentNoteEvent;
import com.bitwig.flt.document.api.document.ApiModificationProcessor;
import com.bitwig.flt.document.core.master.IOJ; // an undoable change
import com.bitwig.flt.document.core.master.g2L; // a project
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A plan of edits: nothing changes until {@link #apply()}. Edits run in the order they were added, and a
 * plan applies once. An edit that can't be carried out throws {@link Refused} when it is added. Before it is
 * applied, a plan can be shown as it would turn out with {@link #preview()}.
 */
public final class Edit {
    private static final Bml.Mod MOD = Bml.mod("piano-roll-framework");
    private static final double SAME_TIME = 2.0 / 1048576.0; // twice Bitwig's rounding of note times

    private final String undoLabel;
    private final List<Runnable> steps = new ArrayList<>();
    // Copies made while the steps run, put in their lanes once every step has run: see insertCopies.
    private final List<Pending> pending = new ArrayList<>();
    private final Map<n1, Pending> pendingByEvent = new IdentityHashMap<>();
    private final List<Runnable> afterInsert = new ArrayList<>();
    private final List<Consumer<Outcome>> sketch = new ArrayList<>(); // the same edits, for a preview
    private final Set<Note> added = new HashSet<>();
    private final List<Note[]> selecting = new ArrayList<>(); // {note, the note whose places it takes}
    private final List<Note> relocated = new ArrayList<>(); // notes that left their lane, and may have left the selection with it
    private Note owner; // the plan's earliest note: the Undo step needs a document object to belong to
    private Clip addedTo; // the Undo step's owner when the plan adds a note, which belongs to nothing until then
    private boolean applied;

    Edit(String undoLabel) {
        this.undoLabel = undoLabel;
    }

    /**
     * Adds a note, cutting notes of the same key and channel in its range as Bitwig's own insert does. The
     * Note returned is what later edits of this plan name it by; the note exists once this edit has run.
     * bitwigSelection: the note editor's selection, which then holds only this note; null leaves it alone.
     * Refused when the clip can't be edited or the note isn't valid.
     */
    public Note add(Clip clip, int channel, int key, double start, double duration, double velocity, Object bitwigSelection) {
        if (applied) {
            throw new IllegalStateException("this plan has been applied");
        }
        if (!clip.clip.qFI()) { // Bitwig's own check before it inserts
            throw new Refused("the clip can't be edited");
        }
        if (key < 0 || key > 127 || channel < 0 || channel > 15 || !(duration > 0) || !Double.isFinite(start)) {
            throw new Refused("the new note isn't valid");
        }
        n1 event = n1.cfM(start, duration, velocity); // a new note, in no lane yet
        Note note = new Note(event, clip, channel, key, start, start + duration);
        Bz selection = bitwigSelection instanceof Bz given ? given : null;
        added.add(note);
        addedTo = clip;
        steps.add(() -> {
            if (selection != null) {
                selection.dnz().evx(); // clear it
            }
            clip.clip.cfM(event, channel, key, selection); // Bitwig's insert: cuts, adds, selects
        });
        sketch.add(outcome -> outcome.add(note, clip, channel, key, start, duration, velocity));
        return note;
    }

    /**
     * A copy of the note, with everything it holds, placed from start to end in the note's lane. The Note
     * returned is what later edits of this plan name it by. The copy is made from the note as it is when this
     * edit runs, so copy before shortening the note. It goes into its lane after all the plan's other edits, so the
     * notes they shorten, move or remove are out of its way; a note still there where it lands is cut there, as
     * Bitwig's own insert cuts. Its expressions
     * play from their beginning; for a copy that continues them, copy at the note's own start and end, then
     * trimStart and setEnd the copy. Refused for a note this plan adds, one not on a note lane, or a range
     * that isn't valid.
     */
    public Note copy(Note note, double start, double end) {
        return copy(note, note.key(), start, end);
    }

    /**
     * As {@link #copy(Note, double, double)}, on another key of the note's channel. Refused also for a key that
     * isn't 0 to 127.
     */
    public Note copy(Note note, int key, double start, double end) {
        planned(note);
        if (key < 0 || key > 127) {
            throw new Refused("the copy's key isn't valid");
        }
        if (added.contains(note)) {
            throw new Refused("a note this plan adds or copies can't be copied");
        }
        if (!(end > start) || !Double.isFinite(start) || !Double.isFinite(end)) {
            throw new Refused("the copy's range isn't valid");
        }
        if (!onLane(note)) {
            throw new Refused("a note is not on a note lane");
        }
        Note copy = new Note(note, key, start, end);
        added.add(copy);
        steps.add(() -> {
            n1 event = note.event.dvk(); // a copy of the note, with all it holds
            event.wGc(start); // start, keeping the length
            event.eLY(end); // end
            pend(event, note.event, key);
            copy.event = event;
        });
        sketch.add(outcome -> outcome.copy(note, copy, key, start, end));
        return copy;
    }

    /**
     * As {@link #copy(Note, int, double, double)}, but when the edit runs and the key's lane already has a note from
     * start to end, there is no copy. Later edits of the plan can't name this copy.
     */
    public Edit copyIfAbsent(Note note, int key, double start, double end) {
        planned(note);
        if (key < 0 || key > 127) {
            throw new Refused("the copy's key isn't valid");
        }
        if (added.contains(note)) {
            throw new Refused("a note this plan adds or copies can't be copied");
        }
        if (!(end > start) || !Double.isFinite(start) || !Double.isFinite(end)) {
            throw new Refused("the copy's range isn't valid");
        }
        if (!onLane(note)) {
            throw new Refused("a note is not on a note lane");
        }
        steps.add(() -> {
            YeP timeline = note.event.sut().nfq(); // the note's lane's clip lanes
            int channel = note.event.UZr(); // its channel
            Rke lane = timeline.TMF(channel, key, false); // the played lane for this channel and key, if any
            if (lane != null) {
                for (Object other : ((PSn) lane).Hnt(start, end)) { // the lane's notes in this range
                    if (other instanceof n1 there && sameTimes(there, start, end)) {
                        return;
                    }
                }
            }
            for (Pending copy : pending) { // and the copies waiting to go in there
                if (!copy.dropped && copy.timeline == timeline && copy.channel == channel && copy.key == key
                        && sameTimes(copy.event, start, end)) {
                    return;
                }
            }
            n1 event = note.event.dvk(); // a copy of the note, with all it holds
            event.wGc(start); // start, keeping the length
            event.eLY(end); // end
            pend(event, note.event, key);
        });
        sketch.add(outcome -> outcome.copyIfAbsent(note, key, start, end));
        return this;
    }

    /**
     * Moves the note to start, keeping its length and with its expressions moving with it. It doesn't cut or
     * move notes it lands on. Refused for a start that isn't valid.
     */
    public Edit move(Note note, double start) {
        planned(note);
        if (!Double.isFinite(start)) {
            throw new Refused("the new start isn't valid");
        }
        steps.add(() -> {
            n1 event = note.event;
            double length = event.QWG() - event.x3t(); // its end and start
            event.wGc(start); // start, keeping the length
            event.eLY(start + length); // end
        });
        sketch.add(outcome -> outcome.move(note, start));
        return this;
    }

    /**
     * Moves the note to another key of its channel. It stays the same note: everything it holds, and its place in
     * the selection. A note already on that key where it lands is cut there, as Bitwig's own insert cuts. On a copy
     * of this plan, the copy goes in on that key. Refused for a key that isn't 0 to 127, or a note
     * not on a note lane.
     */
    public Edit setKey(Note note, int key) {
        planned(note);
        if (key < 0 || key > 127) {
            throw new Refused("the new key isn't valid");
        }
        if (!added.contains(note) && !onLane(note)) {
            throw new Refused("a note is not on a note lane");
        }
        steps.add(() -> {
            n1 event = note.event;
            Pending copy = pendingByEvent.get(event);
            if (copy != null) {
                copy.key = key; // a copy waiting to go in: it goes in on this key
            } else if (event.cfM() != key) { // its key
                moveToLane(event, laneFor(event, key));
                relocated.add(note);
            }
        });
        sketch.add(outcome -> outcome.setKey(note, key));
        return this;
    }

    /** The note's velocity, 0 to 1. Refused for anything else. */
    public Edit setVelocity(Note note, double velocity) {
        planned(note);
        if (!(velocity >= 0 && velocity <= 1)) {
            throw new Refused("the velocity isn't valid");
        }
        steps.add(() -> {
            note.event.cfM(velocity); // its velocity
        });
        sketch.add(outcome -> outcome.setVelocity(note, velocity));
        return this;
    }

    /** The note's release velocity, 0 to 1. Refused for anything else. */
    public Edit setReleaseVelocity(Note note, double velocity) {
        planned(note);
        if (!(velocity >= 0 && velocity <= 1)) {
            throw new Refused("the release velocity isn't valid");
        }
        steps.add(() -> {
            note.event.MRY(velocity); // its release velocity
        });
        sketch.add(outcome -> outcome.setReleaseVelocity(note, velocity));
        return this;
    }

    /**
     * Adds note to the selection, in every place like is selected: a note can be selected more than once, for
     * example in each repeat of a clip. Runs after all of the plan's other edits, whichever order they were
     * added in, and joins the same Undo step. If Bitwig won't take a note, that selection stays as it was and
     * the reason is logged: the edits stand. Refused when like wasn't read from a selection.
     */
    public Edit selectLike(Note note, Note like) {
        if (applied) {
            throw new IllegalStateException("this plan has been applied");
        }
        if (like.selected == null) {
            throw new Refused("a note wasn't read from a selection");
        }
        selecting.add(new Note[] {note, like});
        return this;
    }

    /** Moves the note's start, keeping its end. Its pitch points stay where they are in time. */
    public Edit trimStart(Note note, double start) {
        planned(note);
        steps.add(() -> {
            note.event.cfM(start, true, null); // Bitwig's trim of a note's start
        });
        sketch.add(outcome -> outcome.trimStart(note, start));
        return this;
    }

    /** Moves the note's end. Its start is read when this edit runs, so a plan may also trim the note. */
    public Edit setEnd(Note note, double end) {
        planned(note);
        steps.add(() -> {
            if (pendingByEvent.containsKey(note.event)) { // a copy waiting to go in: Bitwig's API setter's own call
                ((Jsq) note.event).lYc(anL.UNj(end - note.event.x3t())); // its length, rounded as the API rounds it
            } else {
                new ApiInstrumentNoteEvent(note.event).setDuration(end - note.event.x3t());
            }
        });
        sketch.add(outcome -> outcome.setEnd(note, end));
        return this;
    }

    /** Replaces the note's pitch curve. Straight lines only: Refused for a point that has a curve shape. */
    public Edit setPitchCurve(Note note, List<PitchPoint> points) {
        planned(note);
        ApiAutomationEvents curve = new ApiAutomationEvents(Unit.SEMITONES);
        for (PitchPoint point : points) {
            if (!point.straight()) {
                throw new Refused("a pitch curve can only be written with straight lines");
            }
            curve.addPoint(point.time(), point.semitones());
        }
        steps.add(() -> {
            Runnable write = () -> {
                NoteExpression expression = new ApiInstrumentNoteEvent(note.event).getTranspose();
                expression.setEvents(curve, Unit.SEMITONES);
            };
            if (pendingByEvent.containsKey(note.event)) {
                afterInsert.add(write); // a copy waiting to go in: written once it is in its lane
            } else {
                write.run();
            }
        });
        sketch.add(outcome -> outcome.setPitchCurve(note));
        return this;
    }

    /** Removes the note. Refused when it isn't on a note lane. */
    public Edit remove(Note note) {
        planned(note);
        if (!added.contains(note) && !onLane(note)) {
            throw new Refused("a note is not on a note lane");
        }
        steps.add(() -> {
            n1 event = note.event;
            Pending copy = pendingByEvent.get(event);
            if (copy != null) {
                copy.dropped = true; // a copy waiting to go in: it doesn't
                return;
            }
            cFh lane = event.wYK(); // its lane
            if (!(lane instanceof PSn noteLane)) {
                throw new IllegalStateException("a note is not on a note lane");
            }
            noteLane.Hnt(List.of(event)); // remove these notes
        });
        sketch.add(outcome -> outcome.remove(note));
        return this;
    }

    /** A copy made while the plan runs, and where it goes in. */
    private static final class Pending {
        final n1 event;
        final YeP timeline;
        final int channel;
        int key;
        boolean dropped;

        Pending(n1 event, YeP timeline, int channel, int key) {
            this.event = event;
            this.timeline = timeline;
            this.channel = channel;
            this.key = key;
        }
    }

    private void pend(n1 copy, n1 of, int key) {
        Pending entry = new Pending(copy, of.sut().nfq(), of.UZr(), key); // the note's clip lanes, its channel
        pending.add(entry);
        pendingByEvent.put(copy, entry);
    }

    /**
     * The copies go in once every other edit has run. Bitwig's insert clears the new note's time from the notes of its
     * lane (`Euh.cfM(YvI, int)`: it cuts them there, and a note it lands inside becomes two), so a copy put in while
     * the note it was copied from was still long, before the plan shortened it, would split that note and leave its
     * tail running on behind the copy.
     */
    private void insertCopies() {
        for (Pending copy : pending) {
            if (!copy.dropped) {
                Rke lane = copy.timeline.TMF(copy.channel, copy.key, true); // the played lane, made when there is none
                lane.Hnt(copy.event, lane.Hnt(copy.event.x3t(), false)); // insert, at the place for its start
            }
        }
    }

    private static boolean sameTimes(n1 note, double start, double end) {
        return Math.abs(note.x3t() - start) <= SAME_TIME && Math.abs(note.QWG() - end) <= SAME_TIME;
    }

    /** On a note lane. */
    private static boolean onLane(Note note) {
        return note.event.wYK() instanceof PSn; // its lane
    }

    /**
     * The note's lane for another key of its channel, made when there is none, as Bitwig's own note insert finds it.
     * A timeline also keeps preview lanes, which are drawn but neither played nor selectable: a lane made there
     * would hold notes nobody can use.
     */
    static Rke laneFor(n1 event, int key) {
        return event.sut().nfq().TMF(event.UZr(), key, true); // its lane's timeline: the played lane for this channel and key
    }

    /** Takes the note out of its lane and puts the same note in another. */
    static void moveToLane(n1 event, PSn to) {
        PSn from = event.zpW();
        from.Hnt(List.of(event)); // remove
        to.Hnt(event, to.Hnt(event.x3t(), false)); // insert, at the place for its start
    }

    private void planned(Note note) {
        if (applied) {
            throw new IllegalStateException("this plan has been applied");
        }
        if (!added.contains(note) && (owner == null || note.start() < owner.start())) {
            owner = note;
        }
    }

    /** Notes read from a selection that left it when they left their lane go back to where they were in it. */
    private void keepSelected(List<Note> notes) {
        for (Note note : notes) {
            try {
                if (note.selected == null || note.selected.fFs() == null || note.event.zpW() == null) {
                    continue;
                }
                iGx commands = note.selected.fFs().dnz(); // its editor's selection commands
                List<Object> current = new ArrayList<>(commands.FzB()); // the selected items
                boolean there = false;
                for (Object item : current) {
                    there |= item instanceof TrQ source && source.UZr() == note.event; // UZr: the item's note
                }
                if (!there) {
                    TrQ again = TrQ.cfM(note.event, note.selected.Vr(), note.selected.E3(), note.selected.fFs());
                    yT2 handler = commands.TMF(again, false);
                    if (handler != null && handler.Hnt(commands, again)) {
                        current.add(again);
                        commands.cfM((Collection<?>) current); // select these
                        commands.wZs(true); // and show it
                    }
                }
            } catch (RuntimeException e) {
                MOD.log(undoLabel + ": a note could not be kept in the selection", e);
            }
        }
    }

    /** Each selection takes all its new notes at once, or none of them. */
    private void applySelections() {
        Map<iGx, List<Note[]>> byCommands = new IdentityHashMap<>();
        for (Note[] pair : selecting) {
            iGx commands = pair[1].selected.fFs() != null ? pair[1].selected.fFs().dnz() : null; // its editor's selection commands
            if (commands == null) {
                MOD.log(undoLabel + ": a selection can't be reached; it stays as it was");
            } else {
                byCommands.computeIfAbsent(commands, c -> new ArrayList<>()).add(pair);
            }
        }
        byCommands.forEach(this::select);
    }

    private void select(iGx commands, List<Note[]> pairs) {
        try {
            List<Object> current = new ArrayList<>(commands.FzB()); // the selected items
            List<Object> result = new ArrayList<>(current);
            for (Note[] pair : pairs) {
                for (Object item : current) {
                    if (!(item instanceof TrQ source) || source.UZr() != pair[1].event) { // UZr: the item's note
                        continue;
                    }
                    TrQ added = TrQ.cfM(pair[0].event, source.Vr(), source.E3(), source.fFs()); // the same place and editor
                    yT2 handler = commands.TMF(added, false);
                    if (handler == null || !handler.Hnt(commands, added)) {
                        MOD.log(undoLabel + ": Bitwig won't select a note; the selection stays as it was");
                        return;
                    }
                    result.add(added);
                }
            }
            commands.cfM((Collection<?>) result); // select these
            commands.wZs(true); // and show it
        } catch (RuntimeException e) {
            MOD.log(undoLabel + ": the notes could not be selected", e);
        }
    }

    /**
     * All of it, as one Undo step. A plan with no edits does nothing. A preview on screen ends first, as Bitwig ends
     * its own before an edit. Listeners of {@link PianoRoll#onChange} aren't told of this change.
     */
    public void apply() {
        if (applied) {
            throw new IllegalStateException("this plan has been applied");
        }
        applied = true;
        if (!steps.isEmpty()) {
            Notices.applying(true);
            try {
                run(addedTo != null ? addedTo.clip : owner.event);
            } finally {
                Notices.applying(false);
            }
        }
    }

    /**
     * Shows what the plan would leave, as Bitwig's own preview notes, without changing anything: every note it adds or
     * copies, and every note it changes as it would end up. Notes it removes or changes stay as they are, listed by
     * {@link Preview#changed()}. Pitch curves are drawn as they are now. The project's preview before this one,
     * Bitwig's or a mod's, ends. The plan can still be applied afterwards, or previewed again.
     *
     * Call it from an event of its own ({@link PianoRoll#later}), not from inside Bitwig's handling of a widget:
     * Bitwig doesn't take a preview while it is changing the project. A plan with no edits shows nothing. Refused when
     * a note's clip can't be read.
     */
    public Preview preview() {
        return preview(false, false);
    }

    /**
     * As {@link #preview()}, and while it is shown, hides notes as Bitwig hides the notes under a drag: hideChanged
     * hides each note the plan changes, which then shows only as it would end up; hideRemoved hides each note the plan
     * removes. Hidden notes come back when the preview ends. With both, what is visible is exactly what Apply leaves:
     * a removed note is hidden over its own time in its lane, and any other note of that lane reached there is drawn
     * again in its place as a plain preview note.
     */
    public Preview preview(boolean hideChanged, boolean hideRemoved) {
        return preview(hideChanged, hideRemoved, false);
    }

    /**
     * As {@link #preview(boolean, boolean)}, and when heard is true, the preview also plays: whenever a clip it changes
     * plays (transport, loop or launcher), it plays as Apply would leave it, in place of its real notes, until the
     * preview ends. Nothing is saved, recorded for Undo or counted as a change. What plays is the outcome, whatever is
     * hidden: a note an added note would cut plays whole, as it is drawn. Exporting or bouncing while a heard preview
     * is shown may include it, so end the preview first. Pitch curves play as they are now, as they are drawn. On a
     * Bitwig where previews can't be heard, it is shown silent: see {@link Preview#isHeard()}.
     */
    public Preview preview(boolean hideChanged, boolean hideRemoved, boolean heard) {
        if (applied) {
            throw new IllegalStateException("this plan has been applied");
        }
        if (sketch.isEmpty()) {
            return new Preview(List.of(), List.of(), List.of(), null, List.of());
        }
        Outcome outcome = new Outcome();
        for (Consumer<Outcome> edit : sketch) {
            edit.accept(outcome);
        }
        g2L project = null;
        for (bitwig.obf.pBi clip : outcome.clips()) {
            if (clip.UNj(g2L.class) instanceof g2L found) { // the project the clip is in
                project = found;
                break;
            }
        }
        if (project == null) {
            throw new Refused("the notes' project can't be read");
        }
        if (project.E3() || project.iSC()) { // a change, or Undo or Redo, is running
            throw new IllegalStateException("a preview can't be shown while Bitwig is changing the project");
        }
        List<Note> changed = outcome.changed();
        List<Note> removed = outcome.removed();
        List<Rke> told = new ArrayList<>();
        for (Note note : changed) {
            if (note.event.wYK() instanceof Rke lane && !told.contains(lane)) { // its lane
                told.add(lane);
            }
        }
        Map<Rke, List<double[]>> hidden = new IdentityHashMap<>();
        List<Object[]> standIns = new ArrayList<>(); // {lane, note}: kept notes a hiding range reaches, drawn again plain
        if (hideRemoved) {
            for (Note note : removed) {
                if (note.event.wYK() instanceof Rke lane) { // its lane
                    hidden.computeIfAbsent(lane, l -> new ArrayList<>()).add(new double[] {note.event.x3t(), note.event.QWG()});
                }
            }
            hidden.replaceAll((lane, ranges) -> PreviewRule.ranges(ranges));
            Set<Object> gone = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            removed.forEach(note -> gone.add(note.event));
            if (hideChanged) {
                changed.forEach(note -> gone.add(note.event)); // hidden already, and drawn as they end up
            }
            hidden.forEach((lane, ranges) -> {
                for (Object item : lane.cfM()) { // the lane's notes
                    if (item instanceof n1 kept && !gone.contains(kept) && PreviewRule.reached(kept.x3t(), kept.QWG(), ranges)) {
                        standIns.add(new Object[] {lane, kept.dvk()}); // a plain copy in its place, in no lane yet
                    }
                }
            });
        }
        List<Outcome.Placed> placed = outcome.placed();
        List<Object> drawn = new ArrayList<>();
        placed.forEach(note -> drawn.add(note.form()));
        Preview preview = new Preview(changed, removed, List.copyOf(drawn), project, List.copyOf(placed));
        Heard.Feed feed = null;
        if (heard && Heard.available()) {
            feed = new Heard.Feed(preview::stillShown);
            for (Note note : removed) {
                feed.gone(note.event, note.event.wYK()); // its lane: it doesn't play
            }
            for (Outcome.Placed note : placed) {
                if (note.original() != null) {
                    feed.gone(note.original(), note.original().wYK()); // plays as it would end up instead
                }
            }
            outcome.clips().forEach(feed::clip);
        }
        preview.show(new PreviewNotes(project, preview, placed, hideChanged, hidden, standIns, told, feed), feed);
        return preview;
    }

    private void run(com.bitwig.ramona.core.oDc changeOwner) {
        IOJ change = new IOJ();
        if (!change.cfM(changeOwner, undoLabel)) { // begin
            throw new IllegalStateException("Bitwig refused to start the Undo step");
        }
        try {
            RuntimeException[] failure = {null};
            ApiModificationProcessor.modify(() -> {
                try {
                    steps.forEach(Runnable::run);
                    insertCopies();
                    afterInsert.forEach(Runnable::run);
                    applySelections(); // inside the change: Bitwig refuses a new note once it has finished
                    keepSelected(relocated);
                } catch (RuntimeException e) {
                    failure[0] = e; // rethrown after the modification has ended cleanly
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
        } finally {
            if (!change.MRY()) { // finish
                MOD.log(undoLabel + ": Bitwig refused to finish the Undo step");
            }
        }
    }
}
