package bml.preferences;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** A declared choice (buttons or dropdown). */
public interface Choice {
    /** Required. The section it appears in. */
    Choice in(Section section);

    /**
     * Required. Reads and writes the mod's value: the position of the selected option, counting
     * from 0 in the order the options were declared.
     */
    Choice value(IntSupplier get, IntConsumer set);
}
