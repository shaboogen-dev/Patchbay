package bml.notifications.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The stylers of one kind (task rows or message rows) that mods declared, and the one in use. No Bitwig
 * names, so it can be tested without Bitwig.
 *
 * <ul>
 *   <li><b>Last wins:</b> of the declared stylers (in the order of {@link #declare}: mod ID order), the last
 *       one is used; the others are passed over and named in the log.</li>
 *   <li><b>A styler that throws</b> is turned off for the rest of the session: {@link #run} reports the
 *       failure, and the caller uses Bitwig's own row.</li>
 * </ul>
 */
public final class Stylers<S> {
    /** A styler and the mod that declared it. */
    public record Declared<S>(String modId, S styler) {
    }

    private final String kind;
    private final String noun;
    private final List<Declared<S>> declared = new ArrayList<>();
    private volatile Declared<S> chosen;
    private volatile boolean off;

    public Stylers(String kind) {
        this(kind, "layout");
    }

    /** {@code noun} names what a mod declared, in the log ("the layout of", "the handler of"). */
    public Stylers(String kind, String noun) {
        this.kind = kind;
        this.noun = noun;
    }

    /** A mod's styler. A second one from the same mod replaces its first, in its first place. */
    public synchronized void declare(String modId, S styler) {
        for (int i = 0; i < declared.size(); i++) {
            if (declared.get(i).modId().equals(modId)) {
                declared.set(i, new Declared<>(modId, styler));
                return;
            }
        }
        declared.add(new Declared<>(modId, styler));
    }

    /** Picks the styler to use (the last declared) and returns the log lines saying so; none when empty. */
    public synchronized List<String> choose() {
        List<String> lines = new ArrayList<>();
        if (declared.isEmpty()) {
            chosen = null;
            return lines;
        }
        Declared<S> last = declared.get(declared.size() - 1);
        chosen = last;
        lines.add(kind + ": using the " + noun + " of " + last.modId());
        for (int i = 0; i < declared.size() - 1; i++) {
            lines.add(kind + ": the " + noun + " of " + declared.get(i).modId() + " is passed over (" + last.modId() + " comes later)");
        }
        return lines;
    }

    /** The styler in use, or null when there is none or it failed. */
    public S current() {
        Declared<S> c = chosen;
        return c == null || off ? null : c.styler();
    }

    /** The mod whose styler is in use, or null. */
    public String currentMod() {
        Declared<S> c = chosen;
        return c == null || off ? null : c.modId();
    }

    /**
     * Runs the styler in use on {@code view}. True when it finished, or when there is none (nothing to
     * undo). False when it threw: it is then turned off for the session and {@code log} gets the failure;
     * the caller falls back to Bitwig's own row.
     */
    public <V> boolean run(V view, BiConsumer<S, V> call, BiConsumer<String, Throwable> log) {
        Declared<S> c = chosen;
        if (c == null || off) return true;
        try {
            call.accept(c.styler(), view);
            return true;
        } catch (Throwable t) {
            off = true;
            try {
                log.accept(kind + ": the " + noun + " of " + c.modId() + " failed; Bitwig's own from now on", t);
            } catch (Throwable ignored) {
                // logging must never reach Bitwig
            }
            return false;
        }
    }
}
