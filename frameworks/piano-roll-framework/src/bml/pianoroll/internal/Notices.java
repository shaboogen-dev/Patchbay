package bml.pianoroll.internal;

import bitwig.obf.dpN; // the application: Bitwig's UI event loop
import bml.api.Bml;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What the framework hears of changes: anything Undo can undo changing, Undo and Redo. Shown previews end at once, as
 * Bitwig ends its own before it changes anything, and mods listening are told after the event.
 *
 * With no preview shown and no mod listening, each hook returns after two checks.
 */
public final class Notices {
    private static final Bml.Mod MOD = Bml.mod("piano-roll-framework");

    // Each ends one shown preview; given what the change belongs to, when known (so far always null).
    private static final CopyOnWriteArrayList<Consumer<Object>> previews = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static int applying; // Edit.apply is running: its own change isn't reported
    private static boolean posted;

    private Notices() {
    }

    /** Runs on Bitwig's UI thread once the current event is handled. */
    public static void later(Runnable task) {
        dpN.TVZ().asyncExec(task);
    }

    public static void previewShown(Consumer<Object> ender) {
        previews.addIfAbsent(ender);
    }

    public static void previewEnded(Consumer<Object> ender) {
        previews.remove(ender);
    }

    public static void listen(Runnable listener) {
        listeners.addIfAbsent(listener);
    }

    public static void stop(Runnable listener) {
        listeners.remove(listener);
    }

    /** Around Edit.apply: true at its start, false at its end. A preview on screen ends before the change starts. */
    public static void applying(boolean starting) {
        if (starting) {
            changed(null, false);
        }
        applying += starting ? 1 : -1;
    }

    /** Something Undo can undo has just changed, in any document with Undo. */
    static void undoableChange() {
        changed(null, applying == 0);
    }

    /** Undo or Redo is about to run. */
    static void undoOrRedo() {
        changed(null, true);
    }

    private static void changed(Object owner, boolean report) {
        if (previews.isEmpty() && listeners.isEmpty()) {
            return;
        }
        for (Consumer<Object> ender : previews) {
            try {
                ender.accept(owner);
            } catch (Throwable t) {
                MOD.log("a preview could not be ended before a change", t);
            }
        }
        if (report && !listeners.isEmpty() && !posted) {
            posted = true;
            later(Notices::tell);
        }
    }

    private static void tell() {
        posted = false;
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                MOD.log("a change listener failed", t);
            }
        }
    }
}
