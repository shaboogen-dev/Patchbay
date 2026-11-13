package bml.preferences;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A declared checkbox. */
public interface Checkbox {
    /** Required. The section it appears in. */
    Checkbox in(Section section);

    /** Required. Reads and writes the mod's value; the setter is called when the user changes it. */
    Checkbox value(BooleanSupplier get, Consumer<Boolean> set);
}
