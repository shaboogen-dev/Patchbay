package bml.notifications;

/**
 * Where a {@link RowProvider} declares its stylers. One styler of each kind is used: when several mods
 * declare one, the last mod in order of mod ID wins, and the log names it and the ones passed over.
 */
public interface Rows {
    /** Lays out every task row. A second call from the same mod replaces the first. */
    void taskRows(TaskRowStyler styler);

    /** Lays out every message row. A second call from the same mod replaces the first. */
    void messageRows(MessageRowStyler styler);
}
