package bml.pluginpanels.internal;

import java.util.Locale;

import bitwig.obf.LJt; // a text value a label can show
import bitwig.obf.foz; // a listener
import bitwig.obf.uUk; // a plug-in parameter

/**
 * A parameter's value as the layout formats it, for a label to show (Bitwig's label bound to it
 * redraws when the parameter changes). Display only: the plug-in keeps its own values, and
 * Bitwig's own popups still show the plug-in's text.
 */
final class FormattedValue implements LJt {
    private final uUk parameter;
    private final Layouts.Format format;

    FormattedValue(uUk parameter, Layouts.Format format) {
        this.parameter = parameter;
        this.format = format;
    }

    @Override
    public Object getValue() {
        double n = Plugin.normalized(parameter);
        switch (format.kind()) {
            case "map": {
                Layouts.Choice best = format.map().get(0);
                for (Layouts.Choice c : format.map()) if (Math.abs(c.value() - n) < Math.abs(best.value() - n)) best = c;
                return best.name();
            }
            default: { // "percent" and "range"
                double v = format.lo() + n * (format.hi() - format.lo());
                String number = String.format(Locale.ROOT, "%." + format.decimals() + "f", v);
                return format.unit().isEmpty() ? number : format.unit().equals("%") ? number + "%" : number + " " + format.unit();
            }
        }
    }

    @Override
    public void setValue(Object value) {
        // display only
    }

    @Override
    public boolean isSettable() {
        return false;
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
