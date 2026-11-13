package bml.inspector;

/** Where a block's content is being built. */
public final class BlockContext {
    private final Context context;
    private final Object panel;
    /** What reads this block's rows and conditions again: see {@link Rows#refresh}. */
    final java.util.List<Runnable> refreshers = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** What runs after the user changes a row of this block: see {@link Rows#onUserChange}. */
    final java.util.List<Runnable> userChanges = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** For the framework. */
    public BlockContext(Context context, Object panel) {
        this.context = context;
        this.panel = panel;
    }

    /** The context the panel shows ({@link Context#DEVICE} for device rows). */
    public Context context() {
        return context;
    }

    /** Bitwig's Inspector panel being built (Bitwig's object). */
    public Object panel() {
        return panel;
    }
}
