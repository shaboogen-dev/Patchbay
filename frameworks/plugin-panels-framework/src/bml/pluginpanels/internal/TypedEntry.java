package bml.pluginpanels.internal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import bitwig.obf.anX; // Bitwig's value-text converter for a plug-in knob
import bitwig.obf.dNe; // a knob's converter between values and texts (typed entry)
import bitwig.obf.uUk; // a plug-in parameter
import com.bitwig.flt.document.core.master.device.dKO; // a plug-in parameter, as stored (asks the plug-in for value texts)

/**
 * A knob's converter for typed values (Ctrl-click opens Bitwig's value entry; on Enter it hands the
 * text here and applies the normalised value this returns, as one undo step), for a control whose
 * layout says {@code "typing": "mapping"} (the text in display units, converted by the control's
 * {@link ValueMap}) or {@code "normalised"} (a number from 0 to 1). Nothing is asked of the plug-in.
 * {@code "typing": "plugin"} keeps Bitwig's own converter, which asks the plug-in.
 */
final class TypedEntry implements dNe {
    private final uUk parameter;
    private final anX bitwig; // Bitwig's own converter, for the value texts
    private final ValueMap values; // null: normalised

    TypedEntry(uUk parameter, dKO stored, ValueMap values) {
        this.parameter = parameter;
        this.bitwig = new anX(stored);
        this.values = values;
    }

    @Override
    public CompletionStage cfM(double value) {
        return bitwig.cfM(value); // the value's text
    }

    @Override
    public CompletionStage cfM(double value, boolean a, boolean b) {
        return bitwig.cfM(value, a, b);
    }

    @Override
    public CompletionStage cfM(String text, double current) {
        String name = Plugin.paramName(parameter);
        Double n = values != null ? values.typed(text) : normalised(text);
        if (n == null) {
            Panels.report("typed:" + name + ":" + text, "typed '" + text + "' for " + name + ": not understood (expected "
                    + (values == null ? "a number from 0 to 1" : "a number" + (values.unit.isEmpty() ? "" : " in " + values.unit)) + ")", null);
            return CompletableFuture.completedFuture(Double.NaN); // the knob drops it
        }
        return CompletableFuture.completedFuture(n); // the knob applies it, one undo step
    }

    /** A bare number from 0 to 1, or null. */
    private static Double normalised(String text) {
        try {
            double v = Double.parseDouble(text.trim().replace(',', '.'));
            return v >= 0 && v <= 1 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
