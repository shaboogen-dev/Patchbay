package bml.actions.internal;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import bitwig.obf.bmf; // the right-click menu builder of one kind of selection

/** Bitwig's kinds of selection, each with its own right-click menu builder. */
final class Selections {
    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<>());

    private Selections() {
    }

    /** The ID of the kind of selection a builder serves (Bitwig's own, not obfuscated). */
    static String kind(bmf builder) {
        String id = builder.lYc(); // the kind's ID
        if (SEEN.add(id)) Registry.LOG.log("selection menu opened: " + id + " (" + builder.md_() + ", " + builder.getClass().getName() + ")");
        return id;
    }
}
