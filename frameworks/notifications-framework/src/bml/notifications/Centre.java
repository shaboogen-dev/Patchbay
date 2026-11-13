package bml.notifications;

/**
 * Where a {@link CentreProvider} declares what it does with the notification centre. The row layouts
 * ({@link Rows}) and the toast handler are one of a kind: the last mod in order of mod ID wins. Every
 * watcher is told.
 */
public interface Centre extends Rows {
    /**
     * Tells {@code watcher} about every entry the centre shows, from every mod and from Bitwig, and when
     * it ends. A second call from the same mod adds a second watcher.
     */
    void watch(CentreWatcher watcher);

    /**
     * Hands every toast (Bitwig's transient notification, from Bitwig, a controller script or a mod) to
     * {@code handler} before Bitwig shows it. A second call from the same mod replaces the first.
     */
    void toasts(ToastHandler handler);
}
