package bml.pluginpanels.internal;

import bitwig.obf.Xth; // a device's output level meter
import bitwig.obf.dmO; // a callback before each display frame
import bitwig.obf.dns; // a widget, as Bitwig types it
import bitwig.obf.dpM; // a display frame
import bitwig.obf.dpN; // Bitwig's UI event loop
import bitwig.obf.drd; // a subscription to the display frames
import bitwig.obf.dve; // a widget
import bitwig.obf.foz; // a listener
import bitwig.obf.iok; // a colour
import bitwig.obf.tft; // one channel's meter value (RMS and peak, linear)
import com.bitwig.base.geom.tfK; // a widget size (width, height)
import com.bitwig.graphics.SYo; // Bitwig's drawing surface

/**
 * A VU-style needle on the device's output level: the louder channel's RMS, with VU ballistics
 * (about 300 ms), on a scale from -20 to +3 VU (0 VU = -18 dBFS). The only drawn widget of the
 * framework. It reads the meter once per display frame while it is on screen and repaints only when
 * the needle moved; it stops when it is taken off screen or disposed.
 */
final class NeedleMeter extends dve {
    private static final double REFERENCE_DB = -18; // 0 VU
    private static final double MIN_VU = -20, MAX_VU = 3;
    private static final double SWEEP = Math.toRadians(100); // the scale's angle
    private static final double TIME_CONSTANT = 0.3 / 2.2;  // VU rise time 300 ms (10-90 %)
    private static final double[] MARKS = { -20, -10, -7, -5, -3, -2, -1, 0, 1, 2, 3 };

    private final Xth meter;
    private final foz wake = event -> { }; // listening keeps the engine sending levels
    private drd frames;
    private boolean subscribing;
    private boolean listening;
    private double needle = MIN_VU; // in VU
    private tft[] channels = new tft[0]; // the channels listened to, read once per start: nothing allocated per frame
    private long lastFrame;

    private NeedleMeter(Xth meter) {
        this.meter = meter;
    }

    static NeedleMeter create(Xth meter, long width, long height) {
        NeedleMeter m = new NeedleMeter(meter);
        m.H_(); // Bitwig's initialisation
        m.a_(new bitwig.obf.Sxr(width));  // fixed width
        m.MRY(new bitwig.obf.Sxr(height)); // fixed height
        return m;
    }

    @Override
    public tfK mL_() {
        return tfK.cfM(); // no minimum
    }

    /**
     * No maximum. Bitwig clamps a fixed size between a widget's preferred and maximum size, and a
     * widget's default maximum is zero, so without this the needle is laid out at zero size and
     * never painted (dev.1 and dev.2).
     */
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

    // ---------- Display frames ----------

    private final dmO tick = new dmO() {
        @Override
        public void aboutToPaint(dpM frame) {
            onFrame();
        }
    };

    private void onFrame() {
        try {
            if (!ZTl()) { // ZTl(): attached to a window
                stop();
                return;
            }
            long now = System.nanoTime();
            double dt = lastFrame == 0 ? 0.016 : Math.min(0.1, (now - lastFrame) / 1e9);
            lastFrame = now;
            double target = Math.max(MIN_VU - 2, Math.min(MAX_VU + 1, vu(level())));
            double next = needle + (target - needle) * (1 - Math.exp(-dt / TIME_CONSTANT));
            if (Math.abs(next - needle) > 0.01) {
                needle = next;
                jbi(); // repaint
            }
        } catch (Throwable t) {
            stop();
            Panels.report("needle-frame", "the needle meter stopped", t);
        }
    }

    /** The louder channel's RMS, linear 0..1. */
    private double level() {
        double rms = 0;
        for (tft channel : channels) rms = Math.max(rms, channel.cfM()); // cfM(): RMS, linear
        return rms;
    }

    /** Left and right (the meter's list: left, right, then a combined value). */
    private tft[] channels() {
        java.util.List<?> values = meter.FzB(); // the meter's channel values
        java.util.List<tft> out = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(2, values.size()); i++) if (values.get(i) instanceof tft channel) out.add(channel);
        return out.toArray(new tft[0]);
    }

    private static double vu(double linear) {
        if (linear <= 1e-6) return MIN_VU - 2;
        return 20 * Math.log10(linear) - REFERENCE_DB;
    }

    private void start() {
        if (frames != null || subscribing) return;
        if (!listening) {
            channels = channels();
            for (tft channel : channels) channel.cfM(wake); // RMS listener
            listening = true;
        }
        subscribing = true;
        dpN.TVZ().asyncExec(() -> { // after the current paint
            subscribing = false;
            if (frames == null && ZTl()) frames = drd.cfM(tick, this); // this window's display frames
        });
    }

    private void stop() {
        if (frames != null) {
            frames.cfM(); // unsubscribes
            frames = null;
        }
        if (listening) {
            for (tft channel : channels) channel.MRY(wake); // the same channels it listened to
            channels = new tft[0];
            listening = false;
        }
        lastFrame = 0;
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
            Panels.report("needle-paint", "the needle meter could not draw", t);
        }
    }

    private static volatile boolean sizeLogged;

    private void paint(SYo g) {
        double w = Sxo(), h = yiX(); // width, height
        if (!sizeLogged) { // dev: the first paint's size, to check the layout gives the needle room
            sizeLogged = true;
            Panels.MOD.log("needle meter painted at " + w + " x " + h);
        }
        if (w <= 4 || h <= 4) return;
        iok text = e3y().cfM(this); // the theme's text colour
        double cx = w / 2, cy = h * 0.92;
        double radius = Math.min(w / 2 / Math.sin(SWEEP / 2), cy) * 0.92;
        g.qoU(); // save
        g.MRY(0.5, 0.5, w - 1, h - 1); // the frame
        g.Hnt(text.cfM(0.15F));
        g.IwV(1);
        g.hbP(); // stroke
        for (double mark : MARKS) {
            double a = angle(mark);
            double inner = radius * (mark == 0 ? 0.80 : 0.86);
            line(g, cx + Math.sin(a) * inner, cy - Math.cos(a) * inner,
                    cx + Math.sin(a) * radius, cy - Math.cos(a) * radius,
                    text.cfM(mark > 0 ? 0.95F : 0.55F), mark >= 0 ? 2 : 1); // the red zone (above 0 VU) bolder
        }
        double a = angle(Math.max(MIN_VU - 1.5, Math.min(MAX_VU + 0.8, needle)));
        line(g, cx, cy, cx + Math.sin(a) * radius * 0.97, cy - Math.cos(a) * radius * 0.97, text, 1.5);
        g.MRY(cx, cy, 2.5); // the pivot
        g.Hnt(text);
        g.wGc(); // fill
        g.ucQ(); // restore
    }

    /** The needle's angle for a VU value: 0 straight up, negative to the left. */
    private static double angle(double vu) {
        double t = (vu - MIN_VU) / (MAX_VU - MIN_VU);
        return (t - 0.5) * SWEEP;
    }

    private static void line(SYo g, double x0, double y0, double x1, double y1, iok colour, double width) {
        g.MRY(x0, y0); // move to
        g.cfM(x1, y1); // line to
        g.Hnt(colour);
        g.IwV(width);
        g.hbP(); // stroke
    }
}
