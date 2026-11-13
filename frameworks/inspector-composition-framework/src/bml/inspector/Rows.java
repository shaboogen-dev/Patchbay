package bml.inspector;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import bitwig.obf.LJt; // a text value
import bitwig.obf.VGL; // a value's change event
import bitwig.obf.aLZ; // an Inspector panel, with its row and field helpers
import bitwig.obf.dAa; // a button
import bitwig.obf.dBX; // a dropdown
import bitwig.obf.dNN; // a number field
import bitwig.obf.dOu; // how a number field shows and reads its number
import bitwig.obf.dWb; // a row or column of widgets
import bitwig.obf.dve; // a widget
import bitwig.obf.foz; // a value listener
import bitwig.obf.iW2; // a dropdown's list of options
import bitwig.obf.kn; // a size that fills the width
import bitwig.obf.rMe; // a value
import bitwig.obf.ujq; // a number value
import bitwig.obf.w3; // Bitwig's Inspector widget styles
import bitwig.obf.xDN; // a whole-number value

/**
 * Bitwig's own Inspector rows, for a block's content, bound to values the mod keeps: a label with a number field, a
 * stepped field over an ordered list, or a dropdown, and a button. They look and work as Bitwig's Note Inspector fields do. The mod keeps its values: the
 * getter is asked whenever Bitwig shows a row, the setter runs when the user changes it.
 */
public final class Rows {
    private Rows() {
    }

    /** The rows, one under another, as Bitwig spaces its own. This is what a block's content returns. */
    public static Object column(Object... rows) {
        dWb column = dWb.UNj(3); // Bitwig's Inspector spacing
        for (Object row : rows) {
            column.HL((dve) row); // add
        }
        return column;
    }

    /**
     * A label and a number field showing whole numbers. Drag it, type a number, or double-click for resetTo.
     * suffix follows the number, as " %" does; signed shows a plus sign on numbers above zero. A range running from
     * below zero to above zero draws its value from zero, as Bitwig's own Pan and Timbre fields draw from the centre.
     */
    public static Object number(BlockContext context, String label, int min, int max, int resetTo, String suffix,
                                boolean signed, DoubleSupplier get, DoubleConsumer set) {
        return number(context, label, min, max, resetTo, suffix, signed, get, set, () -> true);
    }

    /**
     * As the other number row, greyed out and not changeable while enabled is false, as Bitwig's own disabled fields:
     * it keeps its value. enabled is asked when the row is made and again whenever a row of this block changes, as a
     * {@link #when} condition is; for a change from anywhere else, call {@link #refresh}.
     */
    public static Object number(BlockContext context, String label, int min, int max, int resetTo, String suffix,
                                boolean signed, DoubleSupplier get, DoubleConsumer set, BooleanSupplier enabled) {
        WholeValue value = new WholeValue(context, min, max, get, set);
        value.enabled = enabled;
        dNN field = field(context, value, min, max, resetTo);
        if (min < 0 && max > 0) {
            field.dvk(true); // its is_bipolar property: drawn from the centre, as Bitwig's Pan and Timbre fields
            if (min != -max) {
                field.dEI((double) -min / (max - min)); // where it is drawn from, when zero isn't the centre
            }
        }
        field.cfM(new Format(suffix, signed));
        enable(context, field, enabled);
        return aLZ.cfM(label, (String) null, field);
    }

    /** Bitwig's Inspector number field, bound to the value: shared by number and steps rows. */
    private static dNN field(BlockContext context, WholeValue value, int min, int max, int resetTo) {
        aLZ panel = (aLZ) context.panel();
        dNN field = dNN.MRY(value); // as Bitwig's Velocity and Pressure fields
        field.cfM(panel.MJs().DxJ().wGc()); // the Inspector's field style from the theme
        field.vpw(true);
        field.Z1C(min); // its range
        field.HL(max);
        field.a_(aLZ.UNj); // its width
        field.MRY(w3.MRY); // its height, as the panel's other fields
        field.z3I((foz) event -> value.setDoubleValue(resetTo)); // what a double-click does
        context.refreshers.add(value::changed);
        return field;
    }

    /**
     * A label and a number field over an ordered list: it shows the label at its place and steps through the list
     * when dragged, upwards towards the end of the list. Typing a label (in any case) chooses it. Double-click for
     * resetTo. get and set take the place in labels, from 0. For values that have an order, as note lengths or
     * phrase lengths do; a list of names with no order is a {@link #choice}.
     */
    public static Object steps(BlockContext context, String label, String[] labels, int resetTo, IntSupplier get,
                               IntConsumer set) {
        return steps(context, label, labels, resetTo, get, set, () -> true);
    }

    /** As the other steps row, greyed out and not changeable while enabled is false: see {@link #number}. */
    public static Object steps(BlockContext context, String label, String[] labels, int resetTo, IntSupplier get,
                               IntConsumer set, BooleanSupplier enabled) {
        if (labels.length == 0) {
            throw new IllegalArgumentException("a steps row needs at least one label");
        }
        String[] shown = labels.clone();
        int last = shown.length - 1;
        WholeValue value = new WholeValue(context, 0, last, get::getAsInt, step -> set.accept((int) step));
        value.enabled = enabled;
        dNN field = field(context, value, 0, last, Math.max(0, Math.min(last, resetTo)));
        field.cfM(new StepFormat(shown));
        enable(context, field, enabled);
        return aLZ.cfM(label, (String) null, field);
    }

    /** The widget's own enabled property, read now and whenever the block's rows are read again. */
    private static void enable(BlockContext context, dve widget, BooleanSupplier enabled) {
        widget.nOs().setBoolValue(enabled.getAsBoolean()); // the widget's enabled property
        context.refreshers.add(() -> widget.nOs().setBoolValue(enabled.getAsBoolean()));
    }

    /** A dropdown, in a row with its label: see {@link #choice}. */
    public interface Choice {
        /** The row, for {@link #column}. */
        Object row();

        /** New options for the dropdown, when they depend on something else the user chose. */
        void options(String... options);
    }

    /** A label and a dropdown of these options. get and set take the option's position, from 0. */
    public static Choice choice(BlockContext context, String label, String[] options, IntSupplier get, IntConsumer set) {
        return choice(context, label, options, get, set, () -> true);
    }

    /**
     * As the other dropdown row, greyed out and not changeable while enabled is false, as Bitwig's own disabled
     * dropdowns: it keeps its value. enabled is asked as for {@link #number}'s.
     */
    public static Choice choice(BlockContext context, String label, String[] options, IntSupplier get, IntConsumer set,
                                BooleanSupplier enabled) {
        OptionValue value = new OptionValue(context, options, get, set);
        value.enabled = enabled;
        dBX list = dBX.cfM(value, (Object[]) options); // Bitwig shows a String option as it is
        list.IwV("(mixed)");
        list.nVd(false);
        list.MRY(w3.MRY); // its style, as Bitwig's Inspector dropdowns
        dve row = aLZ.cfM(label, (String) null, list);
        context.refreshers.add(value::changed);
        enable(context, list, enabled);
        return new Choice() {
            @Override
            public Object row() {
                return row;
            }

            @Override
            public void options(String... next) {
                value.options = next;
                list.ERO(new iW2((Object[]) next)); // the dropdown's list
                value.changed();
            }
        };
    }

    /**
     * Rows shown only while the condition is true. Hidden rows take no space. The condition is asked again whenever
     * a row of this block changes; for a change from anywhere else, call {@link #refresh}. Groups can sit inside
     * each other. A block's minimise arrow works apart from this: a hidden row stays hidden when the block is
     * unfolded, and a folded block stays folded when a condition changes.
     */
    public static Object when(BlockContext context, BooleanSupplier condition, Object... rows) {
        dWb group = dWb.UNj(3); // Bitwig's Inspector spacing, as a column
        for (Object row : rows) {
            group.HL((dve) row); // add
        }
        group.t_(condition.getAsBoolean()); // shown or hidden
        context.refreshers.add(() -> group.t_(condition.getAsBoolean()));
        return group;
    }

    /** Reads every row's value and every condition of this block again, after a change from outside its rows. */
    public static void refresh(BlockContext context) {
        for (Runnable refresher : context.refreshers) {
            refresher.run();
        }
    }

    /**
     * Runs after the user changes any row of this block, once its rows and conditions have been read again. It runs
     * during Bitwig's own handling of that change: post anything that edits the project.
     */
    public static void onUserChange(BlockContext context, Runnable listener) {
        context.userChanges.add(listener);
    }

    /** A button filling the row. */
    public static Object button(BlockContext context, String label, Runnable onClick) {
        dAa button = dAa.hbP(label); // as Bitwig's Inspector buttons
        button.a_(kn.cfM());
        button.MRY(w3.MRY);
        button.Hnt(onClick);
        return button;
    }

    /**
     * A button filling the row, greyed out and not clickable while enabled is false, as Bitwig's own disabled
     * buttons. enabled is asked when the row is made and whenever the block's rows are read again.
     */
    public static Object button(BlockContext context, String label, Runnable onClick, BooleanSupplier enabled) {
        dAa button = dAa.hbP(label); // as Bitwig's Inspector buttons
        button.a_(kn.cfM());
        button.MRY(w3.MRY);
        button.Hnt(() -> {
            if (enabled.getAsBoolean()) {
                onClick.run();
            }
        });
        button.nOs().setBoolValue(enabled.getAsBoolean()); // the widget's enabled property
        context.refreshers.add(() -> button.nOs().setBoolValue(enabled.getAsBoolean()));
        return button;
    }

    /**
     * A label and a line of text the user can't change, as Bitwig's Inspector shows its own read-only text: for
     * showing something about the selection. text is asked when the row is made and whenever the block's rows are
     * read again ({@link #refresh}, or a change in another row of this block); null shows as empty. A null label
     * gives the text the whole row.
     */
    public static Object text(BlockContext context, String label, Supplier<String> text) {
        TextValue value = new TextValue(context, text);
        dve shown = aLZ.cfM((LJt) value); // Bitwig's Inspector text, following the value
        context.refreshers.add(value::changed);
        if (label == null) {
            shown.a_(kn.cfM()); // the whole row
            return shown;
        }
        return aLZ.cfM(label, (String) null, shown);
    }

    /** A value of the mod's as one of Bitwig's value objects, which a widget shows and edits. */
    private abstract static class Value implements rMe {
        private final CopyOnWriteArrayList<foz> listeners = new CopyOnWriteArrayList<>();
        private final BlockContext context;
        private Object shown;
        BooleanSupplier enabled = () -> true; // a greyed-out row takes no change, from the widget or a double-click

        Value(BlockContext context) {
            this.context = context;
        }

        /** The user changed it: the block's rows and conditions may depend on it. */
        void changedByUser() {
            refresh(context);
            for (Runnable listener : context.userChanges) {
                listener.run();
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
            if (listener != null) {
                shown = getValue();
                listeners.addIfAbsent(listener);
            }
        }

        @Override
        public void removeListener(foz listener) {
            listeners.remove(listener);
        }

        /** Tells Bitwig's widgets what the value is now. */
        void changed() {
            Object now = getValue();
            Object before = shown;
            shown = now;
            if (Objects.equals(before, now)) {
                return;
            }
            VGL event = new VGL(this, before, now); // a change event
            for (foz listener : listeners) {
                listener.eventOccurred(event);
            }
        }
    }

    private static final class OptionValue extends Value {
        volatile String[] options;
        final IntSupplier get;
        final IntConsumer set;

        OptionValue(BlockContext context, String[] options, IntSupplier get, IntConsumer set) {
            super(context);
            this.options = options;
            this.get = get;
            this.set = set;
        }

        @Override
        public Object getValue() {
            String[] now = options;
            if (now.length == 0) {
                return ""; // a dropdown given no options shows nothing, rather than failing each time it is drawn
            }
            int i = get.getAsInt();
            return i >= 0 && i < now.length ? now[i] : now[0];
        }

        @Override
        public void setValue(Object next) {
            if (!enabled.getAsBoolean()) {
                changed(); // shows its value again
                return;
            }
            int i = List.of(options).indexOf(next);
            if (i >= 0 && i != get.getAsInt()) {
                set.accept(i);
            }
            changed();
            changedByUser();
        }
    }

    /** Text the mod gives, as one of Bitwig's text values: shown, never set. */
    private static final class TextValue extends Value implements LJt {
        final Supplier<String> text;

        TextValue(BlockContext context, Supplier<String> text) {
            super(context);
            this.text = text;
        }

        @Override
        public Object getValue() {
            String now = text.get();
            return now == null ? "" : now;
        }

        @Override
        public boolean isSettable() {
            return false;
        }

        @Override
        public void setValue(Object next) {
            // read-only
        }
    }

    /** Bitwig's whole-number values implement xDN, which number fields show and step without decimals. */
    private static final class WholeValue extends Value implements ujq, xDN {
        final int min, max;
        final DoubleSupplier get;
        final DoubleConsumer set;

        WholeValue(BlockContext context, int min, int max, DoubleSupplier get, DoubleConsumer set) {
            super(context);
            this.min = min;
            this.max = max;
            this.get = get;
            this.set = set;
        }

        private int read() {
            return fit(get.getAsDouble());
        }

        private int fit(double value) {
            return (int) Math.max(min, Math.min(max, Math.round(value)));
        }

        @Override
        public Object getValue() {
            return read();
        }

        @Override
        public void setValue(Object next) {
            if (next instanceof Number number) {
                setDoubleValue(number.doubleValue());
            }
        }

        @Override
        public Number getMinValue() {
            return min;
        }

        @Override
        public Number getMaxValue() {
            return max;
        }

        @Override
        public int getIntegerValue() {
            return read();
        }

        @Override
        public long getLongValue() {
            return read();
        }

        @Override
        public double getDoubleValue() {
            return read();
        }

        @Override
        public void setIntegerValue(int next) {
            setDoubleValue(next);
        }

        @Override
        public void setLongValue(long next) {
            setDoubleValue(next);
        }

        @Override
        public void setFloatValue(float next) {
            setDoubleValue(next);
        }

        @Override
        public void setDoubleValue(double next) {
            if (!enabled.getAsBoolean()) {
                changed(); // shows its value again
                return;
            }
            int value = fit(next);
            if (value != read()) {
                set.accept(value);
            }
            changed();
            changedByUser();
        }
    }

    /** Shows the number with its suffix, and reads a typed number with or without it. */
    private static final class Format implements dOu {
        final String suffix;
        final boolean signed;

        Format(String suffix, boolean signed) {
            this.suffix = suffix == null ? "" : suffix;
            this.signed = signed;
        }

        @Override
        public String cfM(double value) { // what a value shows as
            long whole = Math.round(value);
            return (signed && whole > 0 ? "+" : "") + whole + suffix;
        }

        @Override
        public String cfM(double value, boolean first, boolean second) { // the same, in the forms Bitwig asks for
            return cfM(value);
        }

        @Override
        public double cfM(String text, double fallback) { // what typed text means
            String number = text.replace(suffix.trim(), "").replace("+", "").trim();
            try {
                return Double.parseDouble(number);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /** Shows a place in the list as its label, and reads a typed label back as its place. */
    private static final class StepFormat implements dOu {
        final String[] labels;

        StepFormat(String[] labels) {
            this.labels = labels;
        }

        @Override
        public String cfM(double value) { // what a value shows as
            int i = (int) Math.max(0, Math.min(labels.length - 1, Math.round(value)));
            return labels[i];
        }

        @Override
        public String cfM(double value, boolean first, boolean second) { // the same, in the forms Bitwig asks for
            return cfM(value);
        }

        @Override
        public double cfM(String text, double fallback) { // what typed text means
            String typed = squeeze(text);
            for (int i = 0; i < labels.length; i++) {
                if (squeeze(labels[i]).equals(typed)) {
                    return i;
                }
            }
            return fallback;
        }

        /** Without spaces or case, so "4 bars" and "4BARS" both read as "4 Bars". */
        private static String squeeze(String text) {
            return text == null ? "" : text.replace(" ", "").toLowerCase(Locale.ROOT);
        }
    }
}
