package bml.pianoroll;

import bitwig.obf.bii; // a note editor
import bitwig.obf.fy; // an editor's beat grid
import bitwig.obf.grm; // the grid's settings
import bitwig.obf.pBi; // a clip
import bml.pianoroll.internal.NoteEditors;
import bml.pianoroll.internal.Notices;
import com.bitwig.flt.app.CHR; // the application window controller (shows notifications)
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Reading the selected notes, finding overlaps and starting edits. */
public final class PianoRoll {
    static final double OVERLAP_TOLERANCE = 1.0E-9;

    private PianoRoll() {
    }

    /**
     * The selected notes. bitwigSelection: the selection a menu was opened on (ActionContext.selection()),
     * the Note Inspector panel an Inspector block is built in (BlockContext.panel()) or a note editor's
     * selection from a mod's own hook. null: the one note editor that has only notes selected. Throws
     * {@link Refused} when none or several do.
     */
    public static NoteSelection selection(Object bitwigSelection) {
        return new NoteSelection(NoteEditors.items(bitwigSelection));
    }

    /**
     * The editor's musical grid step, in beats: its fixed size with its subdivision, or the size the adaptive
     * grid shows at the current zoom, straight. bitwigSelection as in {@link #selection}, and Refused in the same
     * cases. 0 when the editor has no grid.
     */
    public static double gridStep(Object bitwigSelection) {
        bii editor = NoteEditors.editor(bitwigSelection);
        fy grid = editor != null ? editor.mR_().dvk() : null; // the editor's grid
        if (grid == null) {
            return 0;
        }
        grm settings = grid.iCS();
        if (settings == null) {
            double value = grid.UNj(); // the grid's current step, which in a note editor can depend on the zoom
            return value >= 1.0 / 128.0 ? value : 0;
        }
        return settings.wGc() // adaptive
                ? grid.Ucl() // the adaptive size, straight
                : grid.cfM(settings.qoU(), settings.hbP()); // the step for the fixed size and subdivision
    }

    /** A clip from Bitwig's own object, for a mod with hooks of its own. null when it isn't a clip. */
    public static Clip clip(Object bitwigClip) {
        return bitwigClip instanceof pBi clip ? new Clip(clip) : null;
    }

    /** A new, empty plan of edits. undoLabel names its Undo step. */
    public static Edit edit(String undoLabel) {
        return new Edit(undoLabel);
    }

    /**
     * Shows the text in Bitwig's on-screen notification, as Bitwig does for its own messages. Does nothing when
     * Bitwig has no window to show it in.
     *
     * @deprecated Telling the user is Framework: Notifications' job: use {@code bml.notifications.Notifications.toast},
     *     which also follows that framework's options (such as showing pop-up messages in the notification centre).
     *     This keeps working.
     */
    @Deprecated
    public static void notification(String text) {
        CHR app = CHR.Grk(); // the application window controller
        if (app != null) {
            app.hUR(text == null ? "" : text); // show the transient notification
        }
    }

    /**
     * Runs task on Bitwig's UI thread once the event being handled is finished: for work that must not run inside
     * Bitwig's own handling of a click or a value, such as showing a preview or applying an edit.
     */
    public static void later(Runnable task) {
        Notices.later(task);
    }

    /**
     * Calls listener after the selected notes of a note editor become a different set, with that editor's selection
     * as it is then: notes selected, deselected, or a note editor opening with notes selected. Bitwig re-creating its
     * selection of the same notes isn't a change. It runs on Bitwig's UI thread after the event that changed the
     * selection, at most once per editor and event. Nothing is watched until a mod first listens.
     */
    public static Listening onSelectionChange(Consumer<NoteSelection> listener) {
        Consumer<List<?>> forward = items -> listener.accept(new NoteSelection(items));
        NoteEditors.listenToSelections(forward);
        return new Listening(() -> NoteEditors.stopListening(forward));
    }

    /**
     * Calls listener after a change to anything Undo can undo, and after Undo and Redo, in any open project: an edit
     * of notes, and anything else that can be undone. Moving the view, playing and previews aren't changes of that
     * kind. Changes made by {@link Edit#apply()} aren't reported: a mod knows when it applies a plan. It runs on
     * Bitwig's UI thread after the event, once per event.
     */
    public static Listening onChange(Runnable listener) {
        Runnable forward = listener::run;
        Notices.listen(forward);
        return new Listening(() -> Notices.stop(forward));
    }

    /**
     * Every pair of these notes that overlaps, whatever their keys: the later one starts more than
     * 0.000000001 beats before the earlier one ends. Sorted by the earlier note's start, then the later
     * note's; notes starting together keep the order given. Times are compared as they are, so the notes
     * should be from one clip.
     */
    public static List<Overlap> overlaps(List<Note> notes) {
        List<Note> sorted = new ArrayList<>(notes);
        sorted.sort(Comparator.comparingDouble(Note::start));
        List<Overlap> found = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Note earlier = sorted.get(i);
            for (int j = i + 1; j < sorted.size() && sorted.get(j).start() + OVERLAP_TOLERANCE < earlier.end(); j++) {
                found.add(new Overlap(earlier, sorted.get(j)));
            }
        }
        return List.copyOf(found);
    }
}
