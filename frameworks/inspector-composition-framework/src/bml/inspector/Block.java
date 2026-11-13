package bml.inspector;

import java.util.function.Function;

/** A block a mod adds to the Inspector. */
public interface Block {
    /** The Inspector contexts the block appears in. At least one. */
    Block in(Context... contexts);

    /** Places the block before Bitwig's blocks. */
    Block top();

    /** Places the block after Bitwig's blocks (the default). */
    Block bottom();

    /**
     * Required. Builds the block's content each time Bitwig builds the Inspector panel: returns a
     * Bitwig widget, which the framework puts below the block's header.
     */
    Block content(Function<BlockContext, Object> content);
}
