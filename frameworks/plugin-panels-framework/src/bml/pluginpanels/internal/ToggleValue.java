package bml.pluginpanels.internal;

import bitwig.obf.AQw; // an on/off value a widget can show and set
import bitwig.obf.foz; // a listener
import bitwig.obf.uUk; // a plug-in parameter

/**
 * The value behind a toggle for a two-step plug-in parameter: on when the normalised value is at
 * least 0.5; Bitwig's own toggle button sets it through {@link Gesture}.
 */
final class ToggleValue implements AQw {
    private final uUk parameter;

    ToggleValue(uUk parameter) {
        this.parameter = parameter;
    }

    @Override
    public boolean getBoolValue() {
        return Plugin.normalized(parameter) >= 0.5;
    }

    @Override
    public void setBoolValue(boolean on) {
        if (on == getBoolValue()) return;
        try {
            Gesture.setNormalized(parameter, on ? 1 : 0);
        } catch (Throwable t) {
            Panels.report("toggle-set", "setting " + Plugin.paramName(parameter) + " failed", t);
        }
    }

    @Override
    public Object getValue() {
        return getBoolValue();
    }

    @Override
    public void setValue(Object value) {
        if (value instanceof Boolean on) setBoolValue(on);
    }

    @Override
    public boolean isSettable() {
        return true;
    }

    @Override
    public boolean isObservable() {
        return true;
    }

    @Override
    public void addListener(foz listener) {
        parameter.cfM(listener); // value listener
    }

    @Override
    public void removeListener(foz listener) {
        parameter.MRY(listener);
    }
}
