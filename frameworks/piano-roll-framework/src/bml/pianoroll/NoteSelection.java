package bml.pianoroll;

import bitwig.obf.TrQ; // a selected note in the note editor
import bml.pianoroll.internal.NoteEditors;
import java.util.ArrayList;
import java.util.List;

/** What is selected, as it was when read. */
public final class NoteSelection {
    private final int itemCount;
    private final boolean hasOtherItems;
    private final List<Note> notes;

    NoteSelection(List<?> items) {
        List<Note> read = new ArrayList<>();
        boolean others = false;
        for (Object item : items) {
            if (NoteEditors.isNote(item)) {
                read.add(new Note((TrQ) item));
            } else {
                others = true;
            }
        }
        itemCount = items.size();
        hasOtherItems = others;
        notes = List.copyOf(read);
    }

    /** Everything selected, notes or not. */
    public int itemCount() {
        return itemCount;
    }

    /** Something selected isn't a note. */
    public boolean hasOtherItems() {
        return hasOtherItems;
    }

    /** One per selected note item, in Bitwig's order. A note can appear twice. */
    public List<Note> notes() {
        return notes;
    }
}
