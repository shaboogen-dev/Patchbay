package bml.pluginpanels.internal;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import bitwig.obf.dnF; // a padding around a widget (can fill its background)
import bitwig.obf.dpN; // Bitwig's UI event loop
import bitwig.obf.dve; // a widget
import bitwig.obf.efU; // a knob style
import bitwig.obf.eeY; // a theme colour
import bitwig.obf.efd; // a theme colour's kind
import bitwig.obf.foz; // a listener
import bitwig.obf.iok; // a colour
import bitwig.obf.uUk; // a plug-in parameter
import com.bitwig.graphics.SYo; // Bitwig's drawing surface

/**
 * A layout's mode colours ({@code mode_colors}): one colour per mode of a parameter (a reverb's mode, a
 * compressor's character), shown on the parts the layout names, live. The current mode is the name of
 * the parameter's value: the nearest of a dropdown's {@code choices} on the same parameter, else its step
 * name. Each tinted part paints with a {@link Colour}, a theme colour that answers the current mode's
 * colour or, for a mode without one, the part's own colour; a mode change only repaints the parts, it never
 * lays the panel out again. Adapted from adoxe (Daniil)'s plug-in card tints ("Design: Device Panels": his
 * mode head and strip colours, a theme colour answering the current tint, and his tinted knob style).
 */
final class ModeColours {
    private final uUk param;
    private final List<Layouts.Choice> names; // a dropdown's choices; empty: the step names
    private final Map<String, Map<String, iok>> colours; // by mode name (lower case): per target ("" for every target)
    private final Map<String, Double> mix;               // per target: the mode colour's share, when the layout sets it
    private final List<WeakReference<dve>> parts = new ArrayList<>();
    private final foz changed = event -> changedSoon();
    private Map<String, iok> current;
    private boolean queued;
    private boolean listening;

    ModeColours(uUk param, List<Layouts.Choice> names, Map<String, Map<String, iok>> colours, Map<String, Double> mix) {
        this.param = param;
        this.names = names;
        this.colours = colours;
        this.mix = mix;
        current = colours.get(mode());
    }

    /** The name of the parameter's current mode (lower case), or "". */
    String mode() {
        double n = Plugin.normalized(param);
        if (!names.isEmpty()) {
            Layouts.Choice best = names.get(0);
            for (Layouts.Choice c : names) if (Math.abs(c.value() - n) < Math.abs(best.value() - n)) best = c;
            return best.name().trim().toLowerCase(java.util.Locale.ROOT);
        }
        String[] steps = Plugin.stepNames(param);
        if (steps.length == 0) return "";
        int i = (int) Math.round(Math.max(0, Math.min(1, n)) * (steps.length - 1));
        return steps[i].trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** A part to repaint when the mode changes; listening starts with the first part. */
    void repaints(dve part) {
        parts.add(new WeakReference<>(part));
        if (!listening) {
            param.cfM(changed); // value listener
            listening = true;
        }
    }

    private void changedSoon() {
        if (queued) return;
        queued = true;
        dpN.TVZ().asyncExec(() -> {
            queued = false;
            try {
                Map<String, iok> next = colours.get(mode());
                if (next == current) return;
                current = next;
                boolean any = false;
                for (WeakReference<dve> ref : parts) {
                    dve part = ref.get();
                    if (part == null) continue;
                    any = true;
                    part.jbi(); // repaint
                }
                if (!any && listening) { // the panel is gone
                    param.MRY(changed);
                    listening = false;
                }
            } catch (Throwable t) {
                Panels.report("mode-colours", "mode colours stopped following " + Plugin.paramName(param), t);
            }
        });
    }

    /**
     * The current mode's colour for {@code target} (its own, else the mode's colour for every target), mixed
     * into {@code own} by the layout's {@code mix} for the target, else {@code amount} (1: the mode colour
     * itself); {@code own} when the mode has none.
     */
    iok over(iok own, String target, double amount) {
        Map<String, iok> now = current;
        iok mode = now == null ? null : now.getOrDefault(target, now.get(""));
        if (mode == null) return own;
        amount = mix.getOrDefault(target, amount);
        if (amount >= 1 || own == null) return mode;
        return iok.cfM(mix(own.hbP(), mode.hbP(), amount), mix(own.Grk(), mode.Grk(), amount), mix(own.tp2(), mode.tp2(), amount)) // r, g, b
                .cfM(own.Ucl() / 255F); // the own colour's alpha
    }

    private static int mix(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * t);
    }

    /**
     * A theme colour that answers the current mode's colour (mixed into its own by {@code amount}), else
     * its own: {@code own}, or with no own colour the widget's text colour. Read on every paint; nothing
     * is looked up there.
     */
    final class Colour extends eeY {
        private final eeY own;
        private final String target;
        private final double amount;

        /** For {@code target} (a {@code mode_colors} apply target), with its default share {@code amount}. */
        Colour(String target, eeY own, double amount) {
            this.own = own;
            this.target = target;
            this.amount = amount;
            cfM("plugin-panels mode " + target, efd.cfM, iok.cfM(0x808080), 0f, 0f, 0f); // as a theme colour (Daniil's mode head is built the same way)
            H_(); // Bitwig's initialisation
        }

        @Override
        public iok cfM(dve w) {
            iok base = own != null ? own.cfM(w) : w.e3y().cfM(w); // its own colour, else the text colour
            return over(base, target, amount);
        }
    }

    /**
     * A knob style with its value arc in a mode colour; every other colour and flag is the base style's (Daniil's
     * tinted knob style). Bitwig's knob (dNv.JqA) paints the arc in the knob's own colour (dNS.FCz) unless the
     * style's use_color_for_line is set, else in the style's value_color: this style answers value_color, and
     * the knob's own colour is set too ({@code Panels}' tintArc).
     */
    static final class KnobStyle extends efU {
        private final efU base;
        private final eeY arc;

        KnobStyle(efU base, eeY arc) {
            this.base = base;
            this.arc = arc;
        }

        @Override public eeY lYc() { return base.lYc(); }    // stroke (the thin ring inside the body)
        @Override public eeY E3() { return base.E3(); }      // fill
        @Override public eeY wGc() { return base.wGc(); }    // dot
        @Override public eeY iHb() { return base.iHb(); }    // emboss highlight
        @Override public eeY Ucl() { return base.Ucl(); }    // emboss shadow
        @Override public eeY MIO() { return base.MIO(); }    // value background
        @Override public eeY ODA() { return arc; }           // value_color: the value arc
        @Override public float ERO() { return base.ERO(); }  // gradient amount
        @Override public boolean uWE() { return base.uWE(); } // use colour for line
    }

    /**
     * Bitwig's padding widget with a stripe along its top edge, {@code height} layout units thick, in a
     * colour (the layout's {@code stripe}; as adoxe (Daniil)'s card panel draws his stripe under the toolbar).
     * The stripe sits in the top padding, so it counts in the panel's height.
     */
    static final class Striped extends dnF {
        private final eeY colour;
        private final long height;

        private Striped(eeY colour, long height) {
            this.colour = colour;
            this.height = height;
        }

        /** Padding (left, right, top, bottom, layout units) around {@code child}, the stripe added to the top. */
        static Striped around(dve child, long[] pad, eeY colour, long height) {
            Striped s = new Striped(colour, height);
            s.PlX(); // as dnF's own factory builds one
            s.H_();
            s.iCS((int) pad[0]); // left
            s.ERO((int) pad[1]); // right
            s.dEI((int) (pad[2] + height)); // top, with the stripe
            s.OzW((int) pad[3]); // bottom
            s.cfM((bitwig.obf.dyC) child);
            return s;
        }

        @Override
        public void a_(SYo g) { // protected in Bitwig; public in the mod kit's view
            super.a_(g); // the background
            try {
                long w = Sxo(), h = eLY(height); // width; the stripe's height in pixels
                if (w <= 0 || h <= 0 || colour == null) return;
                g.nVd(); // new path
                g.MRY(0, 0, w, h); // rectangle
                g.Hnt(colour.cfM(this));
                g.wGc(); // fill
            } catch (Throwable t) {
                Panels.report("stripe-paint", "the stripe could not draw", t);
            }
        }
    }
}
