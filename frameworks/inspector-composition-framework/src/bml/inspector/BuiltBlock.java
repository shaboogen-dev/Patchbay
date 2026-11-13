package bml.inspector;

/**
 * A block the Inspector has just built, Bitwig's own or a mod's, as {@link Blocks#onBlockBuilt} hands it to a
 * listener: for a mod that changes how blocks look or behave, not only its own.
 */
public final class BuiltBlock {
    private final Context context;
    private final String title;
    private final String modId;
    private final String blockId;
    private final Object panel;
    private final Object widget;

    /** For the framework. */
    public BuiltBlock(Context context, String title, String modId, String blockId, Object panel, Object widget) {
        this.context = context;
        this.title = title;
        this.modId = modId;
        this.blockId = blockId;
        this.panel = panel;
        this.widget = widget;
    }

    /**
     * The context the panel shows; for a mod's block in a note context, the one it was placed with. Null for one of
     * Bitwig's blocks in a panel the framework has no context for.
     */
    public Context context() {
        return context;
    }

    /** The title in the block's header, as built. */
    public String title() {
        return title;
    }

    /** True for one of Bitwig's own blocks, false for a mod's. */
    public boolean bitwigBlock() {
        return modId == null;
    }

    /** The mod that declared the block; null for Bitwig's. */
    public String modId() {
        return modId;
    }

    /** The block's ID within its mod; null for Bitwig's. */
    public String blockId() {
        return blockId;
    }

    /** Bitwig's Inspector panel being built (Bitwig's object). */
    public Object panel() {
        return panel;
    }

    /**
     * The block itself (Bitwig's widget group): its header first, then the widgets of its body. A listener may change
     * it, as Bitwig's own builder is still building the block.
     */
    public Object widget() {
        return widget;
    }
}
