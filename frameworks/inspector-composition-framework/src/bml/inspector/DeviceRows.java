package bml.inspector;

import java.util.function.Function;

/** Rows a mod adds to the end of Bitwig's Device block. */
public interface DeviceRows {
    /**
     * Required. Builds the rows each time Bitwig builds the Device block: returns a Bitwig widget,
     * which the framework adds to the end of the block.
     */
    DeviceRows content(Function<BlockContext, Object> content);
}
