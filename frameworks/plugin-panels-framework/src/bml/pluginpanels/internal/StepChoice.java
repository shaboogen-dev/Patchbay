package bml.pluginpanels.internal;

import java.util.List;

import bitwig.obf.foz; // a listener
import bitwig.obf.rMe; // a value a widget can show and set
import bitwig.obf.uUk; // a plug-in parameter
import com.bitwig.flt.document.core.master.device.dKO; // a plug-in parameter, as stored (asks the plug-in for value texts)

/**
 * The value behind a dropdown on a plug-in parameter: Bitwig's own dropdown shows one of
 * {@link #items()} and sets another, through {@link Gesture} (one undo step). The items are the
 * parameter's steps (named by the plug-in's step names, or, when it gives none, by its own text for
 * each step, asked for in the background) or a layout's presets (names for chosen values). The
 * current item is the one nearest the parameter's value.
 */
final class StepChoice implements rMe {
    /** One entry in the dropdown; shown by its name. */
    static final class Item {
        final double value; // normalised
        volatile String name;

        Item(double value, String name) {
            this.value = value;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final uUk parameter;
    private final Item[] items;

    private StepChoice(uUk parameter, Item[] items) {
        this.parameter = parameter;
        this.items = items;
    }

    /** The parameter's steps, by its step names. */
    static StepChoice steps(uUk parameter, String[] names) {
        Item[] items = new Item[names.length];
        for (int i = 0; i < names.length; i++) items[i] = new Item(names.length < 2 ? 0 : i / (double) (names.length - 1), names[i]);
        return new StepChoice(parameter, items);
    }

    /**
     * The parameter's {@code count} steps, named by the plug-in's own text for each step (asked in
     * the background; "1", "2", ... until it answers). {@code refresh} runs on Bitwig's UI thread
     * once a name has arrived.
     */
    static StepChoice pluginTexts(uUk parameter, int count, Runnable refresh) {
        Item[] items = new Item[count];
        for (int i = 0; i < count; i++) items[i] = new Item(i / (double) (count - 1), Integer.toString(i + 1));
        if (parameter instanceof dKO stored) {
            for (Item item : items) {
                stored.nVd(item.value).thenAccept(answer -> { // the plug-in's text for that value
                    String text = answer == null ? "" : answer.toString();
                    if (text.isBlank()) return;
                    item.name = text;
                    bitwig.obf.dpN.TVZ().asyncExec(refresh); // Bitwig's UI thread
                });
            }
        }
        return new StepChoice(parameter, items);
    }

    /** A layout's presets: names for chosen values. */
    static StepChoice presets(uUk parameter, List<Layouts.Choice> choices) {
        Item[] items = new Item[choices.size()];
        for (int i = 0; i < items.length; i++) items[i] = new Item(choices.get(i).value(), choices.get(i).name());
        return new StepChoice(parameter, items);
    }

    Item[] items() {
        return items;
    }

    private Item current() {
        double v = Plugin.normalized(parameter);
        Item best = items[0];
        for (Item item : items) if (Math.abs(item.value - v) < Math.abs(best.value - v)) best = item;
        return best;
    }

    @Override
    public Object getValue() {
        return items.length == 0 ? null : current();
    }

    @Override
    public void setValue(Object value) {
        if (!(value instanceof Item item) || Math.abs(Plugin.normalized(parameter) - item.value) < 1e-6) return;
        try {
            Gesture.setNormalized(parameter, item.value);
        } catch (Throwable t) {
            Panels.report("dropdown-set", "setting " + Plugin.paramName(parameter) + " failed", t);
        }
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
