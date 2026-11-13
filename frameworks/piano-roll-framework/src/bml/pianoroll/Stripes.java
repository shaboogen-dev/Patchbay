package bml.pianoroll;

import bml.pianoroll.internal.Marks;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Bitwig's eraser stripes, the look of a note the eraser is about to delete, on notes a mod names. Nothing in the
 * project changes: the painter is only told to draw these notes that way. Each Stripes is one set of notes, so
 * mods, and parts of one mod, don't replace each other's. Use it on Bitwig's UI thread.
 *
 * Bitwig repaints a note editor when its notes change, so stripes changed in the same event as a preview or an
 * edit show at once. Stripes changed on their own show at the editor's next repaint.
 */
public final class Stripes {
    private Set<Object> shown = Set.of();

    public Stripes() {
    }

    /**
     * Stripes on these notes from now on, in place of the ones before. Each is a {@link Note} or Bitwig's own note
     * ({@link Note#bitwigNote()}, or one a mod's hook holds). A planned copy that doesn't exist yet is passed over.
     */
    public void show(Collection<?> notes) {
        Set<Object> next = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object note : notes) {
            Object bitwig = note instanceof Note planned ? planned.event : note;
            if (bitwig != null) {
                next.add(bitwig);
            }
        }
        Set<Object> before = shown;
        shown = next;
        Marks.set(before, next);
    }

    /** No stripes from this Stripes. */
    public void clear() {
        if (!shown.isEmpty()) {
            show(Set.of());
        }
    }

    /** Whether any note has these stripes. */
    public boolean isShown() {
        return !shown.isEmpty();
    }
}
