package bml.preferences.internal;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

import bitwig.obf.AQw; // a boolean value
import bitwig.obf.VGL; // a value's change event
import bitwig.obf.aoT; // Bitwig's number format (scale, unit, unit text)
import bitwig.obf.cIe; // the Settings page base, with the row and section helpers
import bitwig.obf.dBE; // a row of option buttons (segmented choice)
import bitwig.obf.dBX; // a dropdown
import bitwig.obf.dNN; // a number field
import bitwig.obf.dNg; // a slider
import bitwig.obf.dWb; // a row or column of widgets
import bitwig.obf.dnF; // a frame around a widget
import bitwig.obf.dns; // a widget base, with value binding
import bitwig.obf.dve; // a widget
import bitwig.obf.dzv; // a checkbox
import bitwig.obf.fW; // a number format's scale
import bitwig.obf.fX; // a number format's unit
import bitwig.obf.foz; // a value listener
import bitwig.obf.kn; // a frame layout
import bitwig.obf.rMe; // a value
import bitwig.obf.ujq; // a number value
import bitwig.obf.w3; // Bitwig's Settings widget styles
import bitwig.obf.xDN; // an integer value
import bml.preferences.Checkbox;
import bml.preferences.Choice;
import bml.preferences.NumberField;
import bml.preferences.Section;

/** The declared controls, each shown as one of Bitwig's own Settings widgets bound to the mod's value. */
final class Controls {
    private Controls() {
    }

    /** One declared control. */
    abstract static class Control {
        final String modId, id, label;
        final int order; // declaration order across all mods
        Section section;

        Control(String modId, String id, String label, int order) {
            if (id == null || !id.matches(Registry.ID)) throw new IllegalArgumentException("invalid control ID: " + id);
            if (label == null || label.isBlank()) throw new IllegalArgumentException(id + " has no label");
            this.modId = modId;
            this.id = id;
            this.label = label;
            this.order = order;
        }

        String who() {
            return modId + "/" + id;
        }

        /** Why the control can't be shown, or null. */
        String incomplete() {
            return section == null ? "no section (in)" : null;
        }

        /** A new widget for one build of its section; Settings builds its pages again each time it opens. */
        abstract dve widget();

        /** Bitwig's labelled row: the label on the left, the widget on the right. */
        dve row(dve widget) {
            return cIe.IwV(label, widget); // Bitwig's row helper, with a literal label
        }
    }

    static final class CheckboxControl extends Control implements Checkbox {
        BooleanSupplier get;
        Consumer<Boolean> set;

        CheckboxControl(String modId, String id, String label, int order) {
            super(modId, id, label, order);
        }

        @Override
        public Checkbox in(Section section) {
            this.section = section;
            return this;
        }

        @Override
        public Checkbox value(BooleanSupplier get, Consumer<Boolean> set) {
            this.get = get;
            this.set = set;
            return this;
        }

        @Override
        String incomplete() {
            return get == null || set == null ? "no value" : super.incomplete();
        }

        boolean read() {
            try {
                return get.getAsBoolean();
            } catch (Throwable t) {
                Registry.LOG.log("reading " + who() + " failed", t);
                return false;
            }
        }

        @Override
        dve widget() {
            return dzv.cfM(label, new BoolValue(this)); // a checkbox with its label
        }
    }

    static final class ChoiceControl extends Control implements Choice {
        final List<String> options;
        final boolean dropdown;
        IntSupplier get;
        IntConsumer set;

        ChoiceControl(String modId, String id, String label, int order, String[] options, boolean dropdown) {
            super(modId, id, label, order);
            if (options == null || options.length == 0) throw new IllegalArgumentException(id + " has no options");
            for (String o : options) if (o == null || o.isBlank()) throw new IllegalArgumentException(id + " has an empty option");
            if (List.of(options).stream().distinct().count() != options.length) throw new IllegalArgumentException(id + " has an option twice");
            this.options = List.of(options);
            this.dropdown = dropdown;
        }

        @Override
        public Choice in(Section section) {
            this.section = section;
            return this;
        }

        @Override
        public Choice value(IntSupplier get, IntConsumer set) {
            this.get = get;
            this.set = set;
            return this;
        }

        @Override
        String incomplete() {
            return get == null || set == null ? "no value" : super.incomplete();
        }

        /** The selected option; the first when the mod's index is out of range or reading fails. */
        String read() {
            int i;
            try {
                i = get.getAsInt();
            } catch (Throwable t) {
                Registry.LOG.log("reading " + who() + " failed", t);
                return options.get(0);
            }
            return i >= 0 && i < options.size() ? options.get(i) : options.get(0);
        }

        @Override
        dve widget() {
            Object[] shown = options.toArray(); // Bitwig shows a String option as it is
            OptionValue value = new OptionValue(this);
            // As Bitwig's Pre-roll buttons and its bit depth dropdowns, with their style.
            if (dropdown) {
                dBX list = dBX.cfM(value, shown);
                list.MRY(w3.MRY);
                return row(list);
            }
            dBE buttons = dBE.cfM(value, shown);
            buttons.MRY(w3.MRY);
            return row(buttons);
        }
    }

    static final class NumberControl extends Control implements NumberField {
        final double min, max;
        final boolean slider;
        boolean whole;
        boolean decibels;
        DoubleSupplier get;
        DoubleConsumer set;

        NumberControl(String modId, String id, String label, int order, double min, double max, boolean slider) {
            super(modId, id, label, order);
            if (!(min < max) || Double.isInfinite(min) || Double.isInfinite(max))
                throw new IllegalArgumentException(id + ": min must be below max, both finite");
            this.min = min;
            this.max = max;
            this.slider = slider;
        }

        @Override
        public NumberField in(Section section) {
            this.section = section;
            return this;
        }

        @Override
        public NumberField value(DoubleSupplier get, DoubleConsumer set) {
            this.get = get;
            this.set = set;
            return this;
        }

        @Override
        public NumberField wholeNumbers() {
            this.whole = true;
            return this;
        }

        @Override
        public NumberField decibels() {
            this.decibels = true;
            return this;
        }

        @Override
        String incomplete() {
            if (whole && lo() > hi()) return "no whole number between min and max";
            return get == null || set == null ? "no value" : super.incomplete();
        }

        /** The lowest value: min, rounded up for whole numbers. */
        double lo() {
            return whole ? Math.ceil(min) : min;
        }

        /** The highest value: max, rounded down for whole numbers. */
        double hi() {
            return whole ? Math.floor(max) : max;
        }

        /** Bitwig's number format for the field: its decibels, as New track volume, or plain (no unit). A whole number has none. */
        private void format(dNN field) {
            if (decibels) {
                field.cfM(new aoT(fW.MRY, fX.FzB, "")); // decibel scale, decibel unit
            } else if (!whole) {
                field.cfM(new aoT(fW.cfM, fX.cfM, "")); // linear, no unit
            }
        }

        /** The mod's value, kept between min and max (and whole when declared so). */
        double fit(double v) {
            if (Double.isNaN(v)) v = lo();
            v = whole ? Math.round(v) : v;
            return Math.max(lo(), Math.min(hi(), v));
        }

        double read() {
            try {
                return fit(get.getAsDouble());
            } catch (Throwable t) {
                Registry.LOG.log("reading " + who() + " failed", t);
                return lo();
            }
        }

        @Override
        dve widget() {
            // A whole number's value is one of Bitwig's integer values, which number fields show without decimals.
            NumberValue value = whole ? new WholeValue(this) : new NumberValue(this);
            if (!slider) {
                // As Bitwig's New track volume field, with Bitwig's plain number format (no unit).
                dNN field = dNN.cfM(value);
                field.AUp();
                field.vpw(false);
                field.a_(w3.wGc); // its style
                format(field);
                return row(field);
            }
            // As Bitwig's Contrast rows (cIv.cfM(SYo)): a framed slider, its number field beside it.
            dNg bar = dNg.LzO(); // a new slider
            bar.OzW(whole); // whole steps
            bar.Z1C(lo()); // its range
            bar.HL(hi());
            bar.emh(false);
            bar.fJR(false);
            dns.cfM(bar.som(), value); // bind the slider's position and value to the mod's value
            dns.cfM(bar.abo(), value);
            bar.f_(value);
            dnF frame = dnF.Hnt(bar, 2); // the slider's frame
            frame.cfM(bar.q2u().Lcd().IwV()); // the theme's frame colours
            frame.UNj(bar.PEn().Ai());
            frame.a_(kn.cfM());
            frame.MRY(w3.MRY);
            dNN field = dNN.cfM(value);
            field.vpw(false);
            field.f_(value);
            format(field);
            return row(dWb.cfM(3, new dve[] {frame, field})); // side by side
        }
    }

    /** A mod's value as one of Bitwig's value objects, which a Settings widget shows and edits. */
    abstract static class ModValue implements rMe {
        final Control control;
        private final CopyOnWriteArrayList<foz> listeners = new CopyOnWriteArrayList<>();

        ModValue(Control control) {
            this.control = control;
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
            if (listener != null) listeners.addIfAbsent(listener);
        }

        @Override
        public void removeListener(foz listener) {
            listeners.remove(listener);
        }

        /** Writes through the mod's setter, then tells Bitwig's widgets what the value is now. */
        void write(Runnable setter) {
            Object before = getValue();
            try {
                setter.run();
            } catch (Throwable t) {
                Registry.LOG.log("writing " + control.who() + " failed", t);
            }
            Object after = getValue();
            if (Objects.equals(before, after) || listeners.isEmpty()) return;
            VGL event = new VGL(this, before, after); // a change event
            for (foz l : listeners) {
                try {
                    l.eventOccurred(event);
                } catch (Throwable t) {
                    Registry.LOG.log("a Settings widget failed to update for " + control.who(), t);
                }
            }
        }

        @Override
        public String toString() {
            return "Preference[" + control.who() + "]";
        }
    }

    static final class BoolValue extends ModValue implements AQw {
        private final CheckboxControl c;

        BoolValue(CheckboxControl c) {
            super(c);
            this.c = c;
        }

        @Override
        public boolean getBoolValue() {
            return c.read();
        }

        @Override
        public void setBoolValue(boolean next) {
            if (next != c.read()) write(() -> c.set.accept(next));
        }

        @Override
        public Object getValue() {
            return c.read();
        }

        @Override
        public void setValue(Object next) {
            if (next instanceof Boolean b) setBoolValue(b);
        }
    }

    static final class OptionValue extends ModValue {
        private final ChoiceControl c;

        OptionValue(ChoiceControl c) {
            super(c);
            this.c = c;
        }

        @Override
        public Object getValue() {
            return c.read();
        }

        @Override
        public void setValue(Object next) {
            int i = c.options.indexOf(next);
            if (i >= 0 && !next.equals(c.read())) write(() -> c.set.accept(i));
        }
    }

    static class NumberValue extends ModValue implements ujq {
        private final NumberControl c;

        NumberValue(NumberControl c) {
            super(c);
            this.c = c;
        }

        @Override
        public Object getValue() {
            double v = c.read();
            return c.whole ? (Object) (int) v : (Object) v;
        }

        @Override
        public void setValue(Object next) {
            if (next instanceof Number n) setDoubleValue(n.doubleValue());
        }

        @Override
        public Number getMinValue() {
            return c.whole ? (Number) (int) c.lo() : (Number) c.min;
        }

        @Override
        public Number getMaxValue() {
            return c.whole ? (Number) (int) c.hi() : (Number) c.max;
        }

        @Override
        public int getIntegerValue() {
            return (int) Math.round(c.read());
        }

        @Override
        public long getLongValue() {
            return Math.round(c.read());
        }

        @Override
        public double getDoubleValue() {
            return c.read();
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
            double v = c.fit(next);
            if (v != c.read()) write(() -> c.set.accept(v));
        }
    }

    /** A whole number: Bitwig's integer values implement xDN, which number fields and sliders show without decimals. */
    static final class WholeValue extends NumberValue implements xDN {
        WholeValue(NumberControl c) {
            super(c);
        }
    }
}
