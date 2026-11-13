package bml.browser.internal;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import bitwig.obf.AQw; // a boolean value
import bitwig.obf.DC; // a browser filter
import bitwig.obf.Jp; // a browser session
import bitwig.obf.cFV; // the side browser
import bitwig.obf.dAd; // a toggle button
import bitwig.obf.dWb; // a row or column of widgets
import bitwig.obf.dnF; // a padded widget
import bitwig.obf.w3; // Bitwig's standard sizes
import bitwig.obf.dve; // a widget
import bml.browser.Feature;
import bml.browser.Scope;

/**
 * Every side browser built so far (one per window's Browser panel, rebuilt with the window), held
 * weakly, with the rows the features show or hide. Hiding sets Bitwig's own visibility on the row,
 * so the row stays in the tree and Bitwig's layout closes the gap.
 */
final class SideBrowsers {
    /** One side browser's rows. */
    private static final class Rows {
        final WeakReference<cFV> browser;
        final WeakReference<dve> toolbar, separator, header, filters, details;

        Rows(cFV browser, dve toolbar, dve separator, dve header, dve filters, dve details) {
            this.browser = new WeakReference<>(browser);
            this.toolbar = new WeakReference<>(toolbar);
            this.separator = new WeakReference<>(separator);
            this.header = new WeakReference<>(header);
            this.filters = new WeakReference<>(filters);
            this.details = new WeakReference<>(details);
        }
    }

    /**
     * The All Sources button's place, in Bitwig's layout units: the row's left inset (in place of the
     * search row's own 7), the margins above and right of the button, and the gap to the search field.
     * The space after the button is two single units, not one of 2: units round one by one
     * ({@code MVg.cfM(long, int)}), so at 1.5x two single units are 2 px where 2 units are 3, and the
     * field stays where it was.
     */
    static final int ROW_INSET = 2;
    private static final int BUTTON_MARGIN = 1, BUTTON_GAP = 1;
    /** What the icon's scale is relative to: the toolbar's own button uses its size over this ({@code cBH.cfM(wz)}). */
    private static final double ICON_UNITS = 34;

    /** The side browser being built and the rows it has added so far (it builds on the UI thread). */
    private static final ThreadLocal<Object[]> BUILDING = ThreadLocal.withInitial(() -> new Object[6]);
    private static final List<Rows> ALL = new ArrayList<>();

    private SideBrowsers() {
    }

    /** A side browser starts laying out its rows. */
    static void starts(cFV browser) {
        Object[] building = BUILDING.get();
        building[0] = browser;
        building[1] = null;
        building[2] = null;
        building[3] = null;
        building[4] = null;
        building[5] = null;
    }

    /** The side browser being built on this thread, or null. */
    static cFV building() {
        return BUILDING.get()[0] instanceof cFV browser ? browser : null;
    }

    /**
     * What the side browser pads as its search row: the search field, or, with
     * {@link Feature#ALL_SOURCES_BUTTON}, the All Sources button and the field in a row. The field
     * takes the rest of the width, as in the pop-up browser's search row ({@code cEz}). Any failure
     * leaves the field alone in its row.
     */
    static dve searchRow(dve search) {
        try {
            cFV browser = building();
            if (browser == null || !State.get().get(Feature.ALL_SOURCES_BUTTON, Scope.SIDE)) return search;
            dWb row = dWb.cfM(BUTTON_GAP, new dve[] {placed(allSourcesButton(browser))});
            row.UNj(0.5f); // centred across the row, as in the toolbar
            row.cfM(search, 1f);
            return row;
        } catch (Throwable t) {
            State.LOG.log("building the All Sources button failed; the search field is shown alone", t);
            return search;
        }
    }

    /**
     * The button in a holder of the search field's height, so the row keeps that height: the margin
     * above moves the button down within it, and the button keeps its size (the holder's layout starts
     * it at the margin when it doesn't fit, {@code dJY.cfM(dve, long, long, IBe, float)}). Its last
     * pixels below the holder are the icon's empty border.
     */
    private static dve placed(dve button) {
        dnF holder = dnF.cfM(button, 0, BUTTON_MARGIN, BUTTON_MARGIN, 0);
        holder.MRY(w3.MRY);
        return holder;
    }

    /**
     * The All Sources button, built as the Quick Sources toolbar builds its own ({@code cBH.cfM(wz)}):
     * a toggle on the toolbar's own "showing sources" value, Bitwig's icon and style. Its size is the
     * search field's ({@code w3.MRY}, as {@code ecz} sets its height), so the row stays the field's
     * height, and the icon is scaled to it as the toolbar scales its own to 24. The value opens and
     * closes the All Sources view whether or not the toolbar is on screen; the view is placed by the
     * browser, not by the button.
     */
    private static dve allSourcesButton(cFV browser) {
        dAd button = dAd.cfM((AQw) browser.TMF.x3t());
        button.eLY(browser.TMF.x3t());
        button.TMF("show_browser_sources.svg");
        button.iHb("Borderless");
        button.a_(w3.MRY);
        button.MRY(w3.MRY);
        button.cfM(w3.MRY.cfM() / ICON_UNITS); // a double: the icon's scale (the float overload is something else)
        return button;
    }

    /** The side browser added its Quick Sources toolbar (padded). */
    static void toolbarAdded(dve toolbar) {
        BUILDING.get()[1] = toolbar;
    }

    /** The side browser added the separator under the toolbar. */
    static void separatorAdded(dve separator) {
        BUILDING.get()[2] = separator;
    }

    /** The side browser built its source header. */
    static void headerBuilt(dve header) {
        BUILDING.get()[4] = header;
    }

    /** The side browser built its filter area. */
    static void filtersBuilt(dve filters) {
        BUILDING.get()[5] = filters;
    }

    /** The side browser added the details pane under the results (the selected item's type, category, collections). */
    static void detailsAdded(dve details) {
        BUILDING.get()[3] = details;
    }

    /** The side browser is built: remember its rows and apply the features. */
    static void built() {
        Object[] building = BUILDING.get();
        BUILDING.remove();
        if (!(building[0] instanceof cFV browser)) return;
        Rows rows = new Rows(browser, (dve) building[1], (dve) building[2], (dve) building[4], (dve) building[5],
                (dve) building[3]);
        synchronized (ALL) {
            ALL.add(rows);
        }
        apply(rows, State.get());
    }

    /** True when {@code session} is a live side browser's session. */
    static boolean isSideSession(Jp session) {
        for (Rows rows : live()) {
            cFV browser = rows.browser.get();
            if (browser != null && browser.EUG() == session) return true;
        }
        return false;
    }

    /** The session of the most recently built side browser still alive, or null. */
    static Jp anySideSession() {
        List<Rows> all = live();
        for (int i = all.size() - 1; i >= 0; i--) {
            cFV browser = all.get(i).browser.get();
            if (browser != null) return browser.EUG();
        }
        return null;
    }

    /** True when {@code browser}'s filter area is on screen. */
    static boolean filtersShown(cFV browser) {
        Rows rows = rowsOf(browser);
        dve filters = rows == null ? null : rows.filters.get();
        return filters == null || filters.uev();
    }

    /** True when {@code browser}'s details pane is on screen. */
    static boolean detailsShown(cFV browser) {
        Rows rows = rowsOf(browser);
        dve details = rows == null ? null : rows.details.get();
        return details == null || details.uev();
    }

    /** A filter got or lost a value, or was offered or not: show or hide its side browser's filter area. */
    static void filterChanged(DC filter) {
        Jp session = filter.zpW();
        if (session == null) return;
        for (Rows rows : live()) {
            cFV browser = rows.browser.get();
            if (browser != null && browser.EUG() == session) {
                setVisible(rows.filters.get(), filtersVisible(State.INSTANCE, session));
            }
        }
        Slots.filtersChanged(session);
    }

    /** Clears every side browser's filters (filtering was turned off). */
    static void clearFilters() {
        for (Rows rows : live()) {
            cFV browser = rows.browser.get();
            if (browser != null && browser.EUG() != null) browser.EUG().wZs(); // clear all filters
        }
    }

    /** Applies the features to every side browser. */
    static void applyAll() {
        State state = State.INSTANCE;
        for (Rows rows : live()) apply(rows, state);
    }

    private static void apply(Rows rows, State state) {
        boolean toolbar = state.get(Feature.QUICK_SOURCES_VISIBLE, Scope.SIDE);
        setVisible(rows.toolbar.get(), toolbar);
        setVisible(rows.separator.get(), toolbar);
        setVisible(rows.header.get(), state.get(Feature.SOURCE_HEADER_VISIBLE, Scope.SIDE));
        setVisible(rows.details.get(), state.get(Feature.DETAILS_VISIBLE, Scope.SIDE));
        cFV browser = rows.browser.get();
        setVisible(rows.filters.get(), filtersVisible(state, browser == null ? null : browser.EUG()));
    }

    /**
     * The filter area is shown when it is switched on and filtering works, or, if auto-show is on,
     * while any filter applies (so a filter never narrows the results out of sight).
     */
    private static boolean filtersVisible(State state, Jp session) {
        if (state.get(Feature.FILTERS_VISIBLE, Scope.SIDE) && state.get(Feature.FILTERING, Scope.SIDE)) return true;
        return state.get(Feature.FILTER_AUTO_SHOW, Scope.SIDE) && anyFilterApplies(session);
    }

    /**
     * True when any of the session's filters narrows the results: it is offered for the current
     * source ({@code EUG}, which every chip on show has) and has a value ({@code ODA}; a chip
     * showing "*" has none).
     */
    private static boolean anyFilterApplies(Jp session) {
        if (session == null) return false;
        for (Object filter : session.jHi()) {
            if (filter instanceof DC dc && dc.EUG() && dc.ODA()) return true;
        }
        return false;
    }

    private static void setVisible(dve widget, boolean visible) {
        if (widget != null && widget.uev() != visible) widget.t_(visible);
    }

    private static Rows rowsOf(cFV browser) {
        for (Rows rows : live()) {
            if (rows.browser.get() == browser) return rows;
        }
        return null;
    }

    /** The side browsers still alive; drops the rest. */
    private static List<Rows> live() {
        synchronized (ALL) {
            for (Iterator<Rows> it = ALL.iterator(); it.hasNext(); ) {
                if (it.next().browser.get() == null) it.remove();
            }
            return List.copyOf(ALL);
        }
    }
}
