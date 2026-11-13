package bml.notifications.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The watchers mods declared, all told in the order they were declared (mod ID order). No Bitwig names,
 * so it can be tested without Bitwig. A watcher that throws is logged once and not called again in this
 * session; the others go on.
 *
 * @param <W> a watcher
 */
public final class Watchers<W> {
    private record Declared<W>(String modId, W watcher, boolean[] off) {
    }

    private final List<Declared<W>> declared = new ArrayList<>();

    /** A mod's watcher. Several from one mod are all kept. */
    public synchronized void declare(String modId, W watcher) {
        if (watcher != null) declared.add(new Declared<>(modId, watcher, new boolean[1]));
    }

    public synchronized boolean isEmpty() {
        return declared.isEmpty();
    }

    /** How many are declared (for the log and tests). */
    public synchronized int size() {
        return declared.size();
    }

    /** Calls every watcher still on with {@code value}; a failure turns that watcher off and is logged. */
    public <V> void tell(V value, BiConsumer<W, V> call, BiConsumer<String, Throwable> log) {
        List<Declared<W>> now;
        synchronized (this) {
            now = List.copyOf(declared);
        }
        for (Declared<W> d : now) {
            if (d.off()[0]) continue;
            try {
                call.accept(d.watcher(), value);
            } catch (Throwable t) {
                d.off()[0] = true;
                try {
                    log.accept("the watcher of " + d.modId() + " failed; it isn't told again", t);
                } catch (Throwable ignored) {
                    // logging must never reach Bitwig
                }
            }
        }
    }
}
