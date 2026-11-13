package bml.pianoroll.internal;

import bitwig.obf.TrQ; // a selected note in the note editor
import bitwig.obf.aLn; // the Note Inspector panel
import bitwig.obf.beB; // a list's listener
import bitwig.obf.big; // a selection in an editor (what a selection menu is opened on)
import bitwig.obf.bie; // a note editor's selection
import bitwig.obf.bii; // what a note editor's selection is made for
import bitwig.obf.n1; // a note
import bitwig.obf.pPb; // a list value: a selection's items
import bitwig.obf.vA2; // the model of a Note Inspector panel
import bml.pianoroll.Refused;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Finds what is selected: in the selection given, or in the one note editor that has notes selected. */
public final class NoteEditors {
    private static final List<WeakReference<bie>> noteSelections = new ArrayList<>();

    // Listening for a different set of selected notes. Nothing is watched until a mod first listens.
    private static final CopyOnWriteArrayList<Consumer<List<?>>> selectionListeners = new CopyOnWriteArrayList<>();
    private static final List<Watched> watched = new ArrayList<>(); // on Bitwig's UI thread only
    private static boolean watching;
    private static boolean checkPosted;
    private static final beB SELECTION_CHANGED = new beB() { // one for every watched selection: it holds nothing
        @Override
        public void addedAsListenerTo(pPb list) {
        }

        @Override
        public void aboutToRemoveAsListenerFrom(pPb list) {
        }

        @Override
        public void insertedItems(pPb list, int from, int count) {
            selectionChanged();
        }

        @Override
        public void aboutToRemoveItems(pPb list, int from, int count) {
            selectionChanged();
        }

        @Override
        public void aboutToChangeItems(pPb list, int from, int count) {
        }

        @Override
        public void changedItems(pPb list, int from, int count) {
            selectionChanged();
        }
    };

    /** A note editor's selection, and its selected notes as listeners last heard of them. */
    private static final class Watched {
        final WeakReference<bie> selection;
        Set<Object> notes;

        Watched(bie selection) {
            this.selection = new WeakReference<>(selection);
            notes = notesOf(selection);
        }
    }

    private NoteEditors() {
    }

    /**
     * A note editor's selection was made: remembered for callers with no selection to hand. Held weakly, so the list
     * holds the editors still alive plus those not yet collected; cleared entries go at each new editor.
     */
    static void rememberNoteSelection(bie selection) {
        if (selection.getClass() != bie.class) {
            return; // a subclass: not a note editor's
        }
        synchronized (noteSelections) {
            noteSelections.removeIf(ref -> ref.get() == null);
            noteSelections.add(new WeakReference<>(selection));
        }
        if (watching) {
            Notices.later(() -> watch(selection, false)); // once it is built
        }
    }

    /**
     * Calls listener, on Bitwig's UI thread after the event that caused it, with the items of a note editor's
     * selection whose set of selected notes has become a different one. Bitwig re-creating the items of the
     * same notes isn't a change.
     */
    public static void listenToSelections(Consumer<List<?>> listener) {
        if (!watching) {
            watching = true;
            for (bie selection : live()) {
                watch(selection, true);
            }
        }
        if (selectionListeners.isEmpty()) { // unheard changes since the last listener stopped don't count
            for (Watched each : watched) {
                bie selection = each.selection.get();
                if (selection != null) {
                    each.notes = notesOf(selection);
                }
            }
        }
        selectionListeners.addIfAbsent(listener);
    }

    public static void stopListening(Consumer<List<?>> listener) {
        selectionListeners.remove(listener);
    }

    private static void watch(bie selection, boolean asItIs) {
        try {
            for (Watched each : watched) {
                if (each.selection.get() == selection) {
                    return;
                }
            }
            watched.removeIf(each -> each.selection.get() == null);
            Watched entry = new Watched(selection);
            if (!asItIs) {
                entry.notes = Set.of(); // a new editor: notes it opens with count as a change
            }
            watched.add(entry);
            selection.TgI().cfM(SELECTION_CHANGED); // listen to its list of items
            if (!asItIs) {
                selectionChanged();
            }
        } catch (RuntimeException e) {
            bml.api.Bml.mod("piano-roll-framework").log("a note editor's selection can't be watched", e);
        }
    }

    private static void selectionChanged() {
        if (selectionListeners.isEmpty() || checkPosted) {
            return;
        }
        checkPosted = true;
        Notices.later(NoteEditors::checkSelections);
    }

    private static void checkSelections() {
        checkPosted = false;
        List<List<?>> changed = new ArrayList<>();
        watched.removeIf(each -> each.selection.get() == null);
        for (Watched each : watched) {
            bie selection = each.selection.get();
            if (selection == null) {
                continue;
            }
            Set<Object> now = notesOf(selection);
            if (!now.equals(each.notes)) {
                each.notes = now;
                changed.add(itemsOf(selection));
            }
        }
        for (List<?> items : changed) {
            for (Consumer<List<?>> listener : selectionListeners) {
                try {
                    listener.accept(items);
                } catch (Throwable t) {
                    bml.api.Bml.mod("piano-roll-framework").log("a selection listener failed", t);
                }
            }
        }
    }

    /** The selection's notes, by identity. */
    private static Set<Object> notesOf(bie selection) {
        Set<Object> notes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object item : itemsOf(selection)) {
            if (isNote(item)) {
                notes.add(((TrQ) item).cfM()); // the item's note
            }
        }
        return notes;
    }

    private static List<bie> live() {
        List<bie> live = new ArrayList<>();
        synchronized (noteSelections) {
            noteSelections.removeIf(ref -> ref.get() == null);
            for (WeakReference<bie> ref : noteSelections) {
                bie selection = ref.get();
                if (selection != null) {
                    live.add(selection);
                }
            }
        }
        return live;
    }

    /**
     * The items of {@code bitwigSelection}, of the notes a Note Inspector panel shows, or without either, of the
     * one note editor whose selection is all notes.
     */
    public static List<?> items(Object bitwigSelection) {
        if (bitwigSelection instanceof aLn panel) {
            if (panel.Vr() instanceof vA2 model && model.eC_().stream().anyMatch(NoteEditors::isNote)) { // its selected items
                return model.eC_();
            }
            bitwigSelection = null;
        }
        return itemsOf(bitwigSelection instanceof big selection ? selection : theOneWithNotes());
    }

    /** The note editor of {@code bitwigSelection}, or without one, of the one whose selection is all notes. Null when it has none. */
    public static bii editor(Object bitwigSelection) {
        if (bitwigSelection instanceof aLn) {
            bitwigSelection = null; // a panel holds notes, not an editor
        }
        return (bitwigSelection instanceof big selection ? selection : theOneWithNotes()).lU_();
    }

    public static boolean isNote(Object item) {
        return item != null && item.getClass() == TrQ.class && ((TrQ) item).cfM() != null
                && ((TrQ) item).cfM().getClass() == n1.class;
    }

    private static List<?> itemsOf(big selection) {
        Object value = selection.TgI().getValue(); // the selected items
        return value instanceof List<?> items ? items : List.of();
    }

    /** Refused when there are none or several. */
    private static big theOneWithNotes() {
        big chosen = null;
        int editors = 0;
        for (bie selection : live()) {
            List<?> items = itemsOf(selection);
            if (!items.isEmpty() && items.stream().allMatch(NoteEditors::isNote)) {
                editors++;
                chosen = selection;
            }
        }
        if (editors == 0) {
            throw new Refused("no note editor has only notes selected");
        }
        if (editors > 1) {
            throw new Refused(editors + " note editors have notes selected");
        }
        return chosen;
    }
}
