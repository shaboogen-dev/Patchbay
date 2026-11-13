package bml.browser;

/**
 * One side browser's results slot ({@link View#results}): what a view gets to place the results
 * column. Call on Bitwig's UI thread.
 */
public interface ResultsSlot {
    /** The view's ID, as declared. */
    String viewId();

    /** Bitwig's side browser being built (a {@code cFV}, as the mod kit names it). */
    Object browser();

    /** The side browser's search session (a {@code Jp}): its source, search and filters. */
    Object session();

    /** Bitwig's results column (a {@code dve}), to place in the widget the factory returns. */
    Object results();

    /**
     * Calls {@code listener} after any of the session's filters gets or loses a value, or is
     * offered or no longer offered for the source, for as long as the side browser lives.
     */
    void onFiltersChanged(Runnable listener);

    /**
     * The view can't run in this browser: its claims lapse and the browser is rebuilt without its
     * slot (logged with {@code reason}).
     */
    void fail(String reason);
}
