package bml.browser.internal;

import java.util.Set;

import bitwig.obf.cBd; // the browser's action definitions
import bitwig.obf.cCk; // a browser (side or pop-up)
import bitwig.obf.cFV; // the side browser
import bitwig.obf.dmQ; // a registered action
import bml.browser.Feature;
import bml.browser.Scope;

/**
 * Which of the side browser's actions belong to which feature. A row's actions go off with its
 * {@code _ACTIONS} feature; actions that move keyboard focus to a row (the filter columns, the
 * details pane) also need the row on screen. The pop-up browser is unaffected.
 */
final class Actions {
    /** select_prev_search_source, select_next_search_source, toggle_show_favorites. */
    private static final Set<Object> SOURCE_HEADER = Set.of(cBd.wGc, cBd.eLY, cBd.MJ);
    /**
     * focus_filters, select_prev/next_filter_column, focus_location/category/creator/
     * category_or_creator/tags/device/vendor/file_kind_or_type_column, clear_focused_filter.
     */
    private static final Set<Object> FILTERS = Set.of(cBd.E3, cBd.ucQ, cBd.qoU, cBd.vpw, cBd.ERO, cBd.ejd,
            cBd.uWE, cBd.dEI, cBd.Z1C, cBd.dnz, cBd.OzW, cBd.HL);
    /** toggle_favorite, remove_from_collections (toggle_collection is an indexed action, not gated here). */
    private static final Set<Object> FAVOURITES = Set.of(cBd.NC, cBd.E1b);

    private Actions() {
    }

    /** False when the feature an action belongs to is off in this browser. */
    static boolean allowed(cCk browser, dmQ action) {
        if (!(browser instanceof cFV side) || action == null) return true;
        Object definition = action.Ucl();
        State state = State.get();
        if (SOURCE_HEADER.contains(definition)) return on(state, Feature.SOURCE_HEADER_ACTIONS);
        if (definition == cBd.LaE) { // toggle_source_browser: both rows have a button for it
            return on(state, Feature.SOURCE_HEADER_ACTIONS) || on(state, Feature.QUICK_SOURCES_ACTIONS);
        }
        if (FILTERS.contains(definition)) return on(state, Feature.FILTERING) && SideBrowsers.filtersShown(side);
        if (definition == cBd.lYc) { // focus_file_overview
            return on(state, Feature.DETAILS_ACTIONS) && SideBrowsers.detailsShown(side);
        }
        if (FAVOURITES.contains(definition)) return on(state, Feature.FAVOURITE_ACTIONS);
        return true;
    }

    /** False when the Quick Sources shortcuts (F1-F9 and sub-sources) are off for this browser. */
    static boolean quickSourcesAllowed(cCk browser) {
        return !(browser instanceof cFV) || on(State.get(), Feature.QUICK_SOURCES_ACTIONS);
    }

    private static boolean on(State state, Feature feature) {
        return state.get(feature, Scope.SIDE);
    }
}
