package bml.preferences;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/** A declared number field or slider. */
public interface NumberField {
    /** Required. The section it appears in. */
    NumberField in(Section section);

    /** Required. Reads and writes the mod's value, which stays between the declared min and max. */
    NumberField value(DoubleSupplier get, DoubleConsumer set);

    /** Optional. Only whole numbers: the value is rounded and shown without decimals. */
    NumberField wholeNumbers();

    /** Optional. The value is in decibels and shows as Bitwig's own decibel fields do, for example {@code -10.0 dB}. */
    NumberField decibels();
}
