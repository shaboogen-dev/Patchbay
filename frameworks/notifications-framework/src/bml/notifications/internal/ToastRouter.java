package bml.notifications.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Tells a controller script's popup notification from every other toast. No Bitwig names, so it can be
 * tested without Bitwig.
 *
 * <p>A controller script's popup (a knob's value, on every turn) is marked by its text when the script
 * posts it, on the script's thread; the toast that arrives on the UI thread with that text takes the
 * mark and is reported as a controller popup. Marks expire after {@link #MARK_MS} (a toast that never
 * arrived, e.g. no active window) and are capped at {@link #MAX_MARKS}.
 */
public final class ToastRouter {
    /** A controller popup's mark lasts this long. */
    public static final long MARK_MS = 10_000;
    /** At most this many marks are kept; the oldest go first. */
    public static final int MAX_MARKS = 256;

    private final LongSupplier clock;
    private final Map<String, Deque<Long>> marks = new HashMap<>();
    private final Deque<String> order = new ArrayDeque<>(); // marked texts, oldest first (one per mark)

    public ToastRouter(LongSupplier clock) {
        this.clock = clock;
    }

    /** A controller script posted {@code text} as a popup notification. Any thread. */
    public synchronized void controller(String text) {
        if (text == null) return;
        long now = clock.getAsLong();
        expire(now);
        marks.computeIfAbsent(text, k -> new ArrayDeque<>()).addLast(now);
        order.addLast(text);
        while (order.size() > MAX_MARKS) take(order.peekFirst());
    }

    /**
     * Whether the toast {@code text}, arriving now on the UI thread, is a controller popup. Takes one
     * mark when it is, so each popup counts once.
     */
    public synchronized boolean fromController(String text) {
        expire(clock.getAsLong());
        return text != null && take(text);
    }

    /** How many controller marks are waiting (for tests). */
    public synchronized int marks() {
        return order.size();
    }

    private boolean take(String text) {
        Deque<Long> times = marks.get(text);
        if (times == null || times.isEmpty()) return false;
        times.removeFirst();
        if (times.isEmpty()) marks.remove(text);
        order.removeFirstOccurrence(text);
        return true;
    }

    private void expire(long now) {
        while (!order.isEmpty()) {
            String oldest = order.peekFirst();
            Deque<Long> times = marks.get(oldest);
            if (times == null || times.isEmpty()) { // can't happen; keeps the two in step
                order.removeFirst();
                continue;
            }
            if (now - times.peekFirst() <= MARK_MS) return;
            take(oldest);
        }
    }
}
