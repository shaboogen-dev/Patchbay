package bml.pluginpanels.internal;

import java.util.List;

import bitwig.obf.dns; // a widget, as Bitwig types it
import bitwig.obf.dpN; // Bitwig's UI event loop
import bitwig.obf.dve; // a widget
import bitwig.obf.eeY; // a theme colour
import bitwig.obf.foz; // a listener
import bitwig.obf.iok; // a colour
import bitwig.obf.uUk; // a plug-in parameter
import bml.pluginpanels.internal.Layouts.Envelope;
import bml.pluginpanels.internal.Layouts.Segment;
import com.bitwig.base.geom.tfK; // a widget size (width, height)
import com.bitwig.graphics.SYo; // Bitwig's drawing surface

/**
 * A read-only envelope shape (DAHDSR, or any part of it) drawn from plug-in parameters, in Bitwig's
 * display look: the theme's display background, the shape filled and outlined, a dot on each
 * breakpoint. Segment widths are the parameters' normalised values, or their times through the
 * layout's value mappings (linear or log), fitted to the width; the sustain level is its parameter's
 * normalised value, held for a fixed share of the width (all of it when every time is 0). It listens to each parameter's value and
 * repaints on a change (nothing per frame); listening starts when it is first painted and stops when
 * it is taken off screen or disposed. Its size is the layout's, fixed: a change never lays the panel
 * out again. A parameter the plug-in doesn't have (with {@code min_resolved} under 100) leaves the
 * display empty.
 *
 * <p>Dragging the breakpoints (later): a press near a dot starts one gesture ({@link Gesture}) on the
 * parameter(s) it shows (x: the segment's time, y: the sustain level), a drag sets their normalised
 * values (through the inverse of the time mapping, {@link ValueMap#normalised}), the release ends the
 * gesture; a double-click resets, as the knobs do ({@link ResetToDefault}).
 */
final class EnvelopeDisplay extends dve {
    private static final double PLATEAU = 0.2; // the sustain's share of the width
    private static final double MARGIN = 2;    // around the shape, layout units
    private static final double DOT = 2;       // breakpoint radius, layout units

    private final Envelope envelope;
    private final uUk[] params; // per segment; null when the plug-in doesn't have it
    private final eeY line, fill, display; // null: the text colour (line, fill) or no background (display)
    private final boolean empty;
    private final foz changed = event -> repaintSoon();
    private boolean listening;
    private volatile boolean repaintQueued;

    private EnvelopeDisplay(Envelope envelope, uUk[] params, eeY line, eeY fill, eeY display) {
        this.envelope = envelope;
        this.params = params;
        this.line = line;
        this.fill = fill;
        this.display = display;
        boolean missing = false;
        for (uUk p : params) missing |= p == null;
        this.empty = missing;
    }

    static EnvelopeDisplay create(Envelope envelope, uUk[] params, eeY line, eeY fill, eeY display, long width, long height) {
        EnvelopeDisplay d = new EnvelopeDisplay(envelope, params, line, fill, display);
        d.H_(); // Bitwig's initialisation
        d.a_(FreeSize.exactly(width));  // fixed width
        d.MRY(FreeSize.exactly(height)); // fixed height
        return d;
    }

    @Override
    public tfK mL_() {
        return tfK.cfM(); // no minimum
    }

    /** No maximum: a widget's default maximum is zero, and a fixed size is clamped to it (see {@link NeedleMeter#nj_}). */
    @Override
    public tfK nj_() {
        return tfK.MRY(); // unbounded
    }

    @Override
    public dve MJ() {
        return this; // Bitwig's typed self
    }

    @Override
    public dns UZr() {
        return this; // Bitwig's typed self
    }

    // ---------- Listening ----------

    private void start() {
        if (listening || empty) return;
        for (uUk p : params) p.cfM(changed); // value listener
        listening = true;
    }

    private void stop() {
        if (!listening) return;
        for (uUk p : params) p.MRY(changed);
        listening = false;
    }

    /** A value changed: one repaint on Bitwig's UI thread, however many changes arrive before it. */
    private void repaintSoon() {
        if (repaintQueued) return;
        repaintQueued = true;
        dpN.TVZ().asyncExec(() -> {
            repaintQueued = false;
            try {
                if (!ZTl()) stop(); // ZTl(): attached to a window; painting starts listening again
                else jbi(); // repaint
            } catch (Throwable t) {
                Panels.report("envelope-change", "the envelope display stopped following its parameters", t);
            }
        });
    }

    @Override
    public void iy_() {
        stop(); // disposed
        super.iy_();
    }

    // ---------- Painting ----------

    @Override
    public void cfM(SYo g) {
        start(); // painting means it is on screen
        try {
            paint(g);
        } catch (Throwable t) {
            Panels.report("envelope-paint", "the envelope display could not draw", t);
        }
    }

    private void paint(SYo g) {
        double w = Sxo(), h = yiX(); // width, height in pixels
        if (w <= 2 || h <= 2) return;
        double unit = eLY(1000) / 1000.0; // pixels per layout unit
        g.qoU(); // save
        if (display != null) {
            g.nVd(); // new path
            g.MRY(0, 0, w, h); // rectangle
            g.Hnt(display.cfM(this));
            g.wGc(); // fill
        }
        if (!empty) shape(g, w, h, unit);
        g.ucQ(); // restore
    }

    private void shape(SYo g, double w, double h, double unit) {
        double m = MARGIN * unit + DOT * unit;
        double left = m, right = w - m, top = m, bottom = h - m;
        if (right - left < 2 || bottom - top < 2) return;
        double[] values = new double[params.length];
        for (int i = 0; i < values.length; i++) values[i] = Plugin.normalized(params[i]);
        double[][] pts = points(envelope, values, right - left);
        iok ink = line != null ? line.cfM(this) : e3y().cfM(this); // the theme's colour, else its text colour
        iok area = fill != null ? fill.cfM(this) : ink.cfM(0.2F);
        // the area under the shape
        g.nVd();
        g.MRY(left + pts[0][0], bottom); // move to
        for (double[] p : pts) g.cfM(left + p[0], bottom - p[1] * (bottom - top)); // line to
        g.cfM(left + pts[pts.length - 1][0], bottom);
        g.cfM(left + pts[0][0], bottom);
        g.Hnt(area);
        g.wGc(); // fill
        // the outline
        g.nVd();
        g.MRY(left + pts[0][0], bottom - pts[0][1] * (bottom - top));
        for (int i = 1; i < pts.length; i++) g.cfM(left + pts[i][0], bottom - pts[i][1] * (bottom - top));
        g.Hnt(ink);
        g.IwV(Math.max(1, 1.5 * unit)); // line width
        g.hbP(); // stroke
        if (!envelope.points()) return;
        for (int i = 1; i < pts.length; i++) {
            g.nVd();
            g.MRY(left + pts[i][0], bottom - pts[i][1] * (bottom - top), DOT * unit); // circle
            g.Hnt(ink);
            g.wGc();
        }
    }

    /**
     * The breakpoints, from (0, 0): x in pixels across {@code width}, y as level 0..1. Delay (flat at 0),
     * attack (to 1), hold (flat at 1), decay (to the sustain level, or to 0 without sustain), the sustain
     * plateau, release (to 0). {@code values}: each segment's normalised value.
     */
    static double[][] points(Envelope envelope, double[] values, double width) {
        List<Segment> segments = envelope.segments();
        double[] size = new double[segments.size()];
        double total = 0;
        for (int i = 0; i < size.length; i++) {
            if (segments.get(i).key().equals("sustain")) continue;
            size[i] = width(envelope, segments.get(i), values[i]);
            total += size[i];
        }
        double plateau = !envelope.plateau() ? 0 : total > 0 ? width * PLATEAU : width; // every time at 0: all sustain
        double scale = total > 0 ? (width - plateau) / total : 0;
        double sustain = envelope.has("sustain") ? level(values[envelope.index("sustain")]) : envelope.has("decay") ? 0 : 1;
        double[][] pts = new double[segments.size() + 2][];
        int n = 0;
        double x = 0, y = 0;
        pts[n++] = new double[] { 0, 0 };
        for (int i = 0; i < size.length; i++) {
            String key = segments.get(i).key();
            if (key.equals("sustain")) continue;
            if (key.equals("release") && plateau > 0) pts[n++] = new double[] { x += plateau, y };
            x += size[i] * scale;
            y = switch (key) {
                case "attack", "hold" -> 1;
                case "decay" -> sustain;
                default -> 0; // delay, release
            };
            pts[n++] = new double[] { x, y };
        }
        if (plateau > 0 && !envelope.has("release")) pts[n++] = new double[] { x + plateau, y };
        return java.util.Arrays.copyOf(pts, n);
    }

    /** A segment's share before fitting: its normalised value, or its time (seconds) on the layout's scale. */
    private static double width(Envelope envelope, Segment segment, double normalised) {
        if (segment.values() == null) return Math.max(0, normalised);
        double seconds = Math.max(0, segment.seconds(normalised));
        return envelope.log() ? Math.log10(1 + seconds * 1000) : seconds; // log: by decades of milliseconds
    }

    private static double level(double n) {
        return Math.max(0, Math.min(1, n));
    }
}
