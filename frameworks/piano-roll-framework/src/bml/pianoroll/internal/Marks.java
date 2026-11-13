package bml.pianoroll.internal;

import bitwig.obf.Ibv; // a note, as the painter sees it
import java.util.Set;

/**
 * Bitwig's eraser stripes on the notes mods name. The painter asks each note whether the eraser is about to
 * delete it, and draws the stripes when it is: the answer is also yes for a named note. Nothing in the project
 * changes.
 */
public final class Marks {
    private static final Object lock = new Object();
    // The named notes of each Stripes that has any, by identity. Replaced, never changed, so painting reads it freely.
    private static volatile Set<?>[] named = new Set<?>[0];

    private Marks() {
    }

    /** One owner's notes, by identity, or an empty set for none. owner's previous set is replaced. */
    public static void set(Set<?> previous, Set<?> notes) {
        synchronized (lock) {
            Set<?>[] now = named;
            int keep = 0;
            Set<?>[] next = new Set<?>[now.length + 1];
            for (Set<?> set : now) {
                if (set != previous) {
                    next[keep++] = set;
                }
            }
            if (!notes.isEmpty()) {
                next[keep++] = notes;
            }
            named = java.util.Arrays.copyOf(next, keep);
        }
    }

    /** The note painter's "is this note about to be erased", which draws the stripes: also yes for a named note. */
    static boolean noteStripes(Ibv note) {
        if (note.EUG()) {
            return true;
        }
        for (Set<?> set : named) { // per note, per repaint: empty with nothing named
            if (set.contains(note)) {
                return true;
            }
        }
        return false;
    }
}
