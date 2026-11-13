package bml.pluginpanels.internal;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import bitwig.obf.AQw; // an on/off value a widget can show and set
import bitwig.obf.VGL; // a value-change event: the value, its old and its new value
import bitwig.obf.YEE; // a plug-in body's pages: Parameters, Programs, sidechain, outputs
import bitwig.obf.dpN; // Bitwig's UI event loop
import bitwig.obf.dve; // a widget
import bitwig.obf.foz; // a listener
import bitwig.obf.rMe; // a value a widget can show and set
import com.bitwig.flt.widget.core.device.OFG; // Bitwig's body for a plug-in device

/**
 * The modes of a right-hand toolbar bar with a list switch: Layout (the panel), List (Bitwig's own
 * parameter list, with its search field above it) and Bitwig's other pages (Programs, sidechain,
 * outputs). Exactly one is lit: Layout and List are our two views of Bitwig's Parameters page, lit only
 * while that page shows; the other pages are Bitwig's own tabs on the same page state.
 *
 * <p>One per plug-in body, kept across Bitwig's rebuilds of the Parameters page (Bitwig builds the page
 * again every time it is shown: the first list switch, tied to the first page, stopped working after
 * a visit to the sidechain page). Each rebuild attaches its panel and list. A new body starts on Layout.
 */
final class ViewModes {
    private static final Map<OFG, ViewModes> BODIES = new WeakHashMap<>();

    private final WeakReference<OFG> body;
    private WeakReference<dve> panel = new WeakReference<>(null), list = new WeakReference<>(null), search = new WeakReference<>(null);
    private boolean listMode;
    private long bodyUnits; // the device body's width, kept on every page and mode (layout units; 0: not known yet)
    private long barUnits;  // the right-hand bar's share of it
    private final List<Watch> watches = new ArrayList<>();
    private boolean listening;

    /** A listener on one of the mode values, and that value's last state. */
    private static final class Watch {
        final Mode value;
        final foz listener;
        boolean last;

        Watch(Mode value, foz listener) {
            this.value = value;
            this.listener = listener;
            this.last = value.getBoolValue();
        }
    }

    private ViewModes(OFG body) {
        this.body = new WeakReference<>(body);
    }

    /** The modes of this body (made with the first ask). */
    static ViewModes of(OFG body) {
        synchronized (BODIES) {
            return BODIES.computeIfAbsent(body, ViewModes::new);
        }
    }

    /** Bitwig's page state of the body, or null. */
    private rMe page() {
        OFG b = body.get();
        return b == null || b.hUR == null ? null : b.hUR.LaE(); // the body's state: its shown page
    }

    private boolean onParameters() {
        rMe page = page();
        return page == null || page.getValue() == YEE.cfM; // YEE.cfM: the Parameters page
    }

    /** The body's kept width (layout units), or 0 while not known. */
    long keptWidth() {
        return bodyUnits;
    }

    /**
     * The body's width, kept on every page and in both views: {@code bodyUnits} in all, {@code barUnits} of it
     * the bar's. Bitwig's list gets the rest: shown, it otherwise brings its own width (the device shrank in List
     * mode).
     */
    void width(long bodyUnits, long barUnits) {
        this.bodyUnits = bodyUnits;
        this.barUnits = barUnits;
        applyWidth();
    }

    private void applyWidth() {
        if (bodyUnits <= 0) return;
        OFG b = body.get();
        if (b != null) b.a_(FreeSize.exactly(bodyUnits)); // again: a rebuilt page doesn't change it
        dve l = list.get();
        // the list fills the pages' area as the panel's does (it has no width of its own): an exact list width
        // (body - bar) rounded its half pixels differently from the area left beside the bar, 1 px apart at 150%
        if (l != null) l.a_(FreeSize.FREE);
        // the pages' area too, beside the bar: the panel and Bitwig's list wanted different widths, and Bitwig gave the
        // half pixels (1.5 px per unit at 150%) to the bar or to the pages depending on them: the bar moved 1 px
        dve pages = pagesPart.get();
        if (pages != null) pages.a_(FreeSize.FREE);
    }

    /** Logs the body's width once per view and width (dev.9: to confirm the width holds in List mode). */
    private WeakReference<dve> pagesPart = new WeakReference<>(null), barPart = new WeakReference<>(null);

    /** The pages' area and the bar beside it (for the width log). */
    void parts(dve pages, dve bar) {
        pagesPart = new WeakReference<>(pages);
        barPart = new WeakReference<>(bar);
        applyWidth();
    }

    private void logWidth() {
        OFG b = body.get();
        if (b == null || bodyUnits <= 0) return;
        dpN.TVZ().asyncExec(() -> dpN.TVZ().asyncExec(() -> { // after the layout
            double unit = b.eLY(1000) / 1000.0;
            if (unit <= 0 || b.Sxo() <= 0) return;
            long now = Math.round(b.Sxo() / unit);
            Panels.report("view-width:" + (listMode ? "list" : "layout") + ":" + b.Sxo() + ":" + bodyUnits + ":" + (pagesPart.get() == null ? 0 : pagesPart.get().Sxo()),
                    (listMode ? "List" : "Layout") + " mode: device body " + now + " units, " + b.Sxo() + " px (kept " + bodyUnits + " units, " + b.eLY(bodyUnits) + " px); pages "
                            + (pagesPart.get() == null ? "?" : pagesPart.get().Sxo()) + " px, bar " + (barPart.get() == null ? "?" : barPart.get().Sxo()) + " px", null);
        }));
    }

    /** A Parameters page was built: its panel and Bitwig's list (null without a list), shown as the mode says. */
    void attach(dve panel, dve list) {
        this.panel = new WeakReference<>(panel);
        this.list = new WeakReference<>(list);
        apply();
    }

    /** Bitwig's search field, above the pages: shown in List mode only. */
    void search(dve field) {
        this.search = new WeakReference<>(field);
        apply();
    }

    private void apply() {
        dve p = panel.get(), l = list.get(), s = search.get();
        boolean showList = listMode && l != null;
        if (p != null) p.Dah(!showList); // shown or hidden
        if (l != null) l.Dah(showList);
        if (s != null) s.Dah(showList && onParameters());
        applyWidth();
        logWidth();
    }

    /** Layout (false) or List (true): shows the Parameters page in that view. */
    private void select(boolean wantList) {
        listMode = wantList;
        rMe page = page();
        if (page != null && !onParameters()) page.setValue(YEE.cfM); // Bitwig shows (and builds) the Parameters page
        apply();
        changed();
    }

    /** Tells each listener whose value changed, as Bitwig's bindings read a change. */
    private void changed() {
        List<Watch> now;
        synchronized (watches) {
            now = List.copyOf(watches);
        }
        for (Watch w : now) {
            boolean on = w.value.getBoolValue();
            if (on == w.last) continue;
            w.last = on;
            try {
                w.listener.eventOccurred(new VGL(w.value, !on, on));
            } catch (Throwable t) {
                Panels.report("view-modes", "switching between Layout and List failed", t);
            }
        }
    }

    /** Tells {@code mode}'s listeners it is still on (its button turned itself off on a click). */
    private void relight(Mode mode) {
        List<Watch> now;
        synchronized (watches) {
            now = List.copyOf(watches);
        }
        for (Watch w : now) {
            if (w.value != mode || !mode.getBoolValue()) continue;
            w.last = true;
            try {
                w.listener.eventOccurred(new VGL(mode, false, true));
            } catch (Throwable t) {
                Panels.report("view-modes", "switching between Layout and List failed", t);
            }
        }
    }

    /** Follows Bitwig's page state, so Layout and List go dark on another page (and the search field hides). */
    private final foz pageChanged = event -> dpN.TVZ().asyncExec(() -> {
        apply();
        changed();
    });

    private void listen() {
        if (listening) return;
        rMe page = page();
        if (page == null) return;
        page.addListener(pageChanged);
        listening = true;
    }

    /** The Layout mode's value (its button lit while the panel shows). */
    AQw layout() {
        listen();
        return new Mode(false);
    }

    /** The List mode's value (its button lit while Bitwig's list shows). */
    AQw list() {
        listen();
        return new Mode(true);
    }

    /** One mode, for a toggle button: on while the Parameters page shows in this view; turning it on selects it. */
    private final class Mode implements AQw {
        private final boolean wantList;

        Mode(boolean wantList) {
            this.wantList = wantList;
        }

        @Override
        public boolean getBoolValue() {
            return onParameters() && listMode == wantList;
        }

        @Override
        public void setBoolValue(boolean on) {
            if (on) select(wantList);
            else relight(this); // a click on the lit mode keeps it lit (a radio button)
        }

        @Override
        public Object getValue() {
            return getBoolValue();
        }

        @Override
        public void setValue(Object value) {
            if (value instanceof Boolean b) setBoolValue(b);
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
            synchronized (watches) {
                watches.add(new Watch(this, listener));
            }
        }

        @Override
        public void removeListener(foz listener) {
            synchronized (watches) {
                watches.removeIf(w -> w.listener == listener);
            }
        }
    }
}
