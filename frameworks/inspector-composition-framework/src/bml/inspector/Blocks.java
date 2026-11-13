package bml.inspector;

import java.util.function.Consumer;

/** What a provider declares its Inspector blocks with. */
public interface Blocks {
    /**
     * Declares a block of the mod's own: Bitwig's block header with {@code title}, and below it the content the mod
     * builds. {@code blockId} only needs to be unique within the mod.
     */
    Block block(String blockId, String title);

    /**
     * Declares rows of the mod's own at the end of Bitwig's Device block in the Device Inspector.
     * {@code rowsId} only needs to be unique within the mod.
     */
    DeviceRows deviceRows(String rowsId);

    /**
     * Calls {@code listener} for every block the Inspector builds, Bitwig's own and the mods', right after the block's
     * body is filled and while Bitwig is still building it. A mod's block is passed only when its content was built.
     * Listeners run in the order of their mods' IDs, on Bitwig's UI thread, each time Bitwig builds a panel.
     */
    void onBlockBuilt(Consumer<BuiltBlock> listener);
}
