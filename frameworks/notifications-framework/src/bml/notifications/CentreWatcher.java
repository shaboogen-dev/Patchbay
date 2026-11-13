package bml.notifications;

/**
 * Told about the entries of Bitwig's notification centre: messages and tasks, from every mod and from
 * Bitwig. Called on Bitwig's UI thread; keep it short. Whatever a watcher throws is caught and logged,
 * and that watcher isn't called again in this session.
 */
public interface CentreWatcher {
    /**
     * {@code entry} appeared in the centre for the first time. Entries the centre never showed (a task
     * that ended within Bitwig's first 300 ms, a background task Bitwig keeps behind the spinner next to
     * the dot) aren't reported.
     */
    default void shown(CentreEntry entry) {
    }

    /**
     * {@code entry}, reported to {@link #shown} before, has gone: closed, dismissed itself, finished,
     * cancelled, removed by its mod, or replaced ({@link CentreEntry#replaced()}). The same object as
     * {@link #shown} was given.
     */
    default void ended(CentreEntry entry) {
    }
}
