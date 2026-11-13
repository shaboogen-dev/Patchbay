package bml.pluginpanels.internal;

import bitwig.obf.dmO; // a callback before each display frame
import bitwig.obf.dpM; // a display frame
import bitwig.obf.dpN; // Bitwig's UI event loop
import bitwig.obf.drd; // a subscription to the display frames
import bitwig.obf.dtu; // Bitwig's scaled container: its subtree at its own UI scale
import bitwig.obf.dve; // a widget
import com.bitwig.flt.widget.core.device.L1F; // Bitwig's device widget in the chain
import com.bitwig.flt.widget.core.device.OFG; // Bitwig's body for a plug-in device

/**
 * Keeps a layout inside the device chain's height. Every device in the chain gets the same height,
 * and Bitwig clips a body whose content needs more instead of shrinking it. So once the panel is on
 * screen, this compares the body's content height (its minimum height) with the height actually
 * visible (the smallest of the body's and its parents' heights, up to the device widget) and zooms
 * the layout's scaled container down until it fits (and back up, to at most 100%, when it has room
 * again, e.g. after switching to a shorter page). It logs the available and needed heights once,
 * in layout units, and logs when it has to scale or can't fit.
 *
 * <p>With {@link #keepWidth}, once the fit has settled with the panel on screen, the body's width is fixed at
 * the width it has then (measured: for a layout without a fixed width its content's, which would otherwise
 * follow whichever page shows; for a fixed width what Bitwig actually lays out), and every page and view keeps it.
 */
final class HeightFit {
    static final double MIN_ZOOM = 0.6;
    private static final int STABLE_FRAMES = 30;

    private final OFG body;
    private final dtu scaled;
    private final String source;
    private final Spacer probe = Spacer.probe(this::start);
    private drd frames;
    private boolean subscribing;
    private double zoom = 1;
    private int stable;
    private boolean logged;
    private boolean reportedScale;
    private boolean keepWidth;
    private boolean widthKept;

    /** A page's row of columns and the strut that sets its height ({@code fill_height}). */
    record FillRow(dve row, Spacer strut) {
    }

    /** The shown page's row to fill to the available height, or null. */
    private java.util.function.Supplier<FillRow> fill;

    /** Fill the available height with the shown page's row of columns (a layout's {@code fill_height}). */
    void fill(java.util.function.Supplier<FillRow> rows) {
        fill = rows;
    }

    /**
     * Sets the shown page's strut so the layout needs exactly the available height: the free height goes to
     * the row of columns, and a layout too tall gives its strut back before it is zoomed. True when it changed.
     */
    private boolean filled(long available, long needed, double unit) {
        FillRow f = fill == null ? null : fill.get();
        if (f == null) return false;
        long rowPx = f.row().yiX(), now = f.strut().height();
        if (rowPx <= 0) return false;
        long target = now;
        if (needed < available - unit && zoom >= 1) target = (long) Math.floor((rowPx + (available - needed)) / unit / zoom);
        else if (needed > available && now > 0) target = Math.max(0, (long) Math.floor((rowPx - (needed - available)) / unit / zoom));
        if (target == now) return false;
        f.strut().height(target); // lays the row out again
        return true;
    }

    HeightFit(OFG body, dtu scaled, String source) {
        this.body = body;
        this.scaled = scaled;
        this.source = source;
    }

    Spacer probe() {
        return probe;
    }

    /** Fix the body's width once fitted (a layout without a fixed width). */
    void keepWidth(java.util.function.LongConsumer kept) {
        keepWidth = true;
        onKept = kept;
    }

    /** Told the kept width (layout units), or null. */
    private java.util.function.LongConsumer onKept;

    /** The content changed (another page): fit again. */
    void again() {
        stable = 0;
        start();
    }

    private final dmO tick = new dmO() {
        @Override
        public void aboutToPaint(dpM frame) {
            onFrame();
        }
    };

    private void start() {
        if (frames != null || subscribing || stable >= STABLE_FRAMES) return;
        subscribing = true;
        dpN.TVZ().asyncExec(() -> { // after the current paint
            subscribing = false;
            if (frames == null && probe.ZTl()) frames = drd.cfM(tick, probe); // ZTl(): attached to a window
        });
    }

    private void stop() {
        if (frames != null) {
            frames.cfM(); // unsubscribes
            frames = null;
        }
    }

    /** The visible height: the smallest of the body's and its parents' heights, up to the device widget. */
    private long available() {
        long h = body.yiX();
        dve w = body.RbC(); // its parent
        for (int i = 0; w != null && i < 12; i++, w = w.RbC()) {
            if (w.yiX() > 0) h = Math.min(h, w.yiX());
            if (w instanceof L1F) break;
        }
        return h;
    }

    private void onFrame() {
        try {
            if (!probe.ZTl()) {
                stop();
                return;
            }
            long available = available(), needed = body.tJi().IwV(); // tJi(): the body's minimum size
            long page = scaled.tJi().IwV();                            // the layout's own part of it
            if (available <= 0 || needed <= 0 || page <= 0) return;
            double unit = body.eLY(1000) / 1000.0;                     // pixels per layout unit
            if (!logged) {
                logged = true;
                Panels.MOD.log(source + ": height available " + Math.round(available / unit) + " units, needed "
                        + Math.round(needed / unit) + " (Bitwig's toolbar and the layout; layout alone " + Math.round(page / unit) + ")");
            }
            if (filled(available, needed, unit)) { // the columns down to the bottom first; zoom only what still doesn't fit
                stable = 0;
                return;
            }
            double next = zoom;
            if (needed > available) {
                double target = (page - (needed - available)) / (double) page; // the share of the layout that fits
                next = Math.max(MIN_ZOOM, zoom * Math.max(0.1, target));
            } else if (zoom < 1 && needed < available - 2) {
                double target = (page + (available - needed)) / (double) page;
                next = Math.min(1, zoom * target);
            }
            if (Math.abs(next - zoom) > 0.005) {
                zoom = next;
                scaled.cfM(zoom); // the layout's zoom: re-lays out and repaints it
                stable = 0;
                if (zoom < 1 && !reportedScale) {
                    reportedScale = true;
                    Panels.MOD.log(source + ": layout taller than the chain by " + Math.round((needed - available) / unit) + " units; scaled to " + Math.round(zoom * 100) + "%");
                }
                return;
            }
            if (needed > available + 2 && zoom <= MIN_ZOOM + 0.005) {
                Panels.report("too-tall:" + source, source + ": layout too tall by " + Math.round((needed - available) / unit) + " units even at " + Math.round(MIN_ZOOM * 100) + "%; its bottom is cut off", null);
            }
            if (++stable >= STABLE_FRAMES) {
                stop();
                if (keepWidth && !widthKept && body.Sxo() > 0) {
                    widthKept = true;
                    // rounded up: a unit is unit pixels (1.5 at 150%), and Bitwig never lays the panel out narrower than its
                    // content, so a width rounded down showed 1 px narrower in List mode (the list takes exactly the width)
                    long units = (long) Math.ceil(body.Sxo() / unit - 1e-6);
                    body.a_(FreeSize.exactly(units)); // fixed from now on, whatever page shows
                    Panels.MOD.log(source + ": device width kept at " + units + " units on every page (measured " + body.Sxo() + " px at " + unit + " px per unit)");
                    if (onKept != null) onKept.accept(units); // remembered for the body: a rebuilt page keeps it
                }
            }
        } catch (Throwable t) {
            stop();
            Panels.report("fit:" + source, "fitting " + source + " to the chain's height failed", t);
        }
    }
}
