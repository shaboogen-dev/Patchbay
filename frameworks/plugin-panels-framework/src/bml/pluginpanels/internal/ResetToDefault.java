package bml.pluginpanels.internal;

import bitwig.obf.foz; // a listener
import bitwig.obf.uUk; // a plug-in parameter

/**
 * A knob's set-to-default listener. Bitwig turns a double-click on a knob into "set to default" only
 * when the knob has such a listener (otherwise it opens value entry), and its plug-in knob doesn't
 * get one outside Bitwig's own parameter list. This one resets the parameter to the plug-in's default
 * inside one adjusting gesture: one undo step, and automation sees a touch.
 * Adapted from adoxe (Daniil)'s "Design: Device Panels" plug-in cards (his Reset).
 */
final class ResetToDefault implements foz {
    private final uUk parameter;

    ResetToDefault(uUk parameter) {
        this.parameter = parameter;
    }

    @Override
    public void eventOccurred(Object event) {
        try {
            parameter.cfM(true, false); // gesture starts
            try {
                parameter.cJ_(); // the plug-in's default value
            } finally {
                parameter.MRY(true, false); // gesture ends
            }
        } catch (Throwable t) {
            Panels.report("reset", "resetting " + Plugin.paramName(parameter) + " failed", t);
        }
    }
}
