package bml.pianoroll;

import bitwig.obf.Tyg; // a track's audition player
import bitwig.obf.n1; // a note
import bitwig.obf.pBi; // a clip
import bml.api.Bml;
import bml.pianoroll.internal.Heard;
import bml.pianoroll.internal.Notices;
import com.bitwig.flt.document.core.master.g2L; // a project
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A plan's outcome on screen, from {@link Edit#preview()}: Bitwig's own preview notes, which aren't selectable,
 * aren't saved and change nothing in the project. A project shows one preview at a time, Bitwig's or a mod's. Use it
 * on Bitwig's UI thread.
 *
 * A preview is silent unless it was asked to be heard ({@link Edit#preview(boolean, boolean, boolean)}). A heard
 * preview plays in place of the notes it changes whenever its clip plays (transport, loop or launcher), as Apply would
 * leave them; when it ends, the clip plays its real notes again. Either way, {@link #audition()} plays its notes once.
 *
 * It lasts until {@link #end()}, or until Bitwig ends it: Bitwig's own previews and edits (the pencil, dragging,
 * pasting) end it first, any change Undo can undo ends it as it happens, and Undo or Redo end it before they run.
 * Then {@link #onEnded} runs.
 */
public final class Preview {
    private static final Bml.Mod MOD = Bml.mod("piano-roll-framework");

    private final List<Note> changed;
    private final List<Note> removed;
    private final List<Object> drawn;
    private final g2L project;
    private final List<Outcome.Placed> placed; // the preview notes, with their clip, channel and key
    private final Consumer<Object> ender = this::changeComing;
    private PreviewNotes slot; // in the project's preview slot; null once ended
    private Runnable onEnded;
    private boolean endingHere;
    private boolean showing; // being put in: a change heard meanwhile isn't one to end it
    private Heard.Feed heard; // what the engine plays while it is shown; null when silent

    Preview(List<Note> changed, List<Note> removed, List<Object> drawn, g2L project, List<Outcome.Placed> placed) {
        this.changed = changed;
        this.removed = removed;
        this.drawn = drawn;
        this.project = project;
        this.placed = placed;
    }

    /** Shown from the start; for the framework. feed: what it plays, or null for a silent preview. */
    void show(PreviewNotes notes, Heard.Feed feed) {
        slot = notes;
        Notices.previewShown(ender);
        showing = true;
        try {
            project.MRY(notes); // the project's preview: takes out the one before, then puts this in
        } catch (RuntimeException e) {
            slot = null;
            Notices.previewEnded(ender);
            throw e;
        } finally {
            showing = false;
        }
        if (feed != null && slot == notes && Heard.start(feed)) { // still shown: heard from now
            heard = feed;
        }
    }

    /** The preview plays in place of the notes it changes: it was asked to be heard, and is shown. */
    public boolean isHeard() {
        return heard != null;
    }

    /**
     * Plays the preview notes once, all together, through Bitwig's own audition on each clip's track, as Bitwig
     * auditions a chord: each note's key at its velocity, for the notes' average length, up to 500 ms. A note on a key
     * Bitwig is auditioning already isn't started again, and Bitwig's next audition (a click on a note) stops these.
     * Works whether or not the preview is heard, and with the transport stopped. Does nothing once the preview has
     * ended, or when it shows no notes.
     */
    public void audition() {
        if (slot == null || placed.isEmpty()) {
            return;
        }
        try {
            double beats = 0;
            Map<pBi, List<Outcome.Placed>> byClip = new IdentityHashMap<>();
            for (Outcome.Placed note : placed) {
                beats += note.form().QWG() - note.form().x3t(); // its end and start
                byClip.computeIfAbsent(note.clip(), c -> new ArrayList<>()).add(note);
            }
            double tempo = project.tHK().qm(); // the project's tempo, in beats per minute
            double seconds = beats / placed.size() * 60.0 / (tempo > 0 ? tempo : 120.0);
            long millis = Math.max(1, Math.min(500, Math.round(seconds * 1000.0))); // Bitwig plays 500 ms at most
            byClip.forEach((clip, notes) -> {
                Tyg player = clip.mtS().DNo().nfq(); // the track's audition player
                if (player == null) {
                    return;
                }
                player.cfM(); // what it plays now stops, as before Bitwig's own chord audition
                for (Outcome.Placed note : notes) {
                    n1 form = note.form();
                    // A timed play: each one restarts the shared stop, which ends all of them together.
                    player.cfM(note.channel(), note.key(), (float) form.UNj(), (float) form.hUR(), millis);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            MOD.log("a preview could not be auditioned", e);
        }
    }

    /**
     * The notes the plan would remove or change, as they are now, in the plan's order, and those a note it adds would
     * cut. A note the plan leaves exactly as it is isn't one. These keep their place on screen: show them with
     * {@link Stripes} to say they go.
     */
    public List<Note> changed() {
        return changed;
    }

    /** Of {@link #changed()}, the notes the plan removes. */
    public List<Note> removed() {
        return removed;
    }

    /**
     * The preview notes on screen, as Bitwig's own note objects: every note the plan adds or copies, and every note it
     * changes, as it would end up. For {@link Stripes#show}, to mark the outcome.
     */
    public List<Object> drawn() {
        return drawn;
    }

    /** Still on screen. */
    public boolean isShown() {
        return slot != null;
    }

    /** Still the project's preview: false also for one whose project let it go without taking it out. */
    boolean stillShown() {
        PreviewNotes notes = slot;
        return notes != null && project.Sxo() == notes;
    }

    /**
     * Runs once if something other than {@link #end()} ends the preview. It runs during what ended it, which may be
     * Bitwig's own change or Undo, so it should only clear what the mod shows (its {@link Stripes}) and post anything
     * more with {@link PianoRoll#later}.
     */
    public Preview onEnded(Runnable listener) {
        onEnded = listener;
        return this;
    }

    /** Takes the preview notes out. The project is as it was. Ending it again does nothing; onEnded doesn't run. */
    public void end() {
        if (slot == null) {
            return;
        }
        endingHere = true;
        try {
            takeOut();
        } finally {
            endingHere = false;
        }
    }

    private void takeOut() {
        PreviewNotes notes = slot;
        if (project.Sxo() == notes) { // still the project's preview
            project.hQm(); // Bitwig's own way to clear it, which takes the notes out
        } else {
            notes.Hnt();
        }
    }

    /** A change Undo can undo, or Undo or Redo about to run. owner: what the change belongs to, when known. */
    private void changeComing(Object owner) {
        if (slot == null || showing) {
            return;
        }
        if (owner instanceof com.bitwig.ramona.core.oDc changing
                && changing.UNj(g2L.class) instanceof g2L other && other != project) {
            return; // another project's change
        }
        takeOut();
    }

    /** Its notes are out of the project. */
    void removed(PreviewNotes notes) {
        if (notes != slot) {
            return;
        }
        slot = null;
        Notices.previewEnded(ender);
        if (heard != null) { // the clip plays its real notes again
            Heard.Feed feed = heard;
            heard = null;
            try {
                Heard.stop(feed);
            } catch (Throwable t) {
                MOD.log("a heard preview could not give the real notes back to the engine", t);
            }
        }
        if (!endingHere && onEnded != null) {
            try {
                onEnded.run();
            } catch (Throwable t) {
                MOD.log("a preview's end listener failed", t);
            }
        }
    }
}
