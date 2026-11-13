package bml.pluginpanels.internal;

import bitwig.obf.uUk; // a plug-in parameter
import com.bitwig.flt.control_surface.values.ParameterTarget; // a parameter as Bitwig's controllers change it
import com.bitwig.flt.control_surface.values.RangedValueTarget; // its value, with conversions
import com.bitwig.flt.control_surface.values.ValueAtomParameterTarget; // makes the target for a parameter

/**
 * Sets a plug-in parameter the way Bitwig's controllers do: one adjusting gesture around the change
 * (one undo step, automation touch), the value set on the parameter object itself, so automation
 * and modulation keep working.
 */
final class Gesture {
    private Gesture() {
    }

    static void setNormalized(uUk parameter, double normalized) {
        ParameterTarget target = ValueAtomParameterTarget.createForAtom(parameter);
        if (target == null) throw new IllegalStateException("no parameter target for " + Plugin.paramName(parameter));
        RangedValueTarget value = target.getValueTarget();
        double n = Math.max(0, Math.min(1, normalized));
        target.startedAdjusting(false, true);
        try {
            value.setCurrentValue(value.normalizedValueToValue(n));
        } finally {
            target.finishedAdjusting(false, true);
        }
    }
}
