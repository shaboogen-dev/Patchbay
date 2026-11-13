package bml.browser.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import bitwig.obf.DC; // a browser filter
import bitwig.obf.DD; // a browser filter's state (DC's base)
import bitwig.obf.EE; // the shared library (collections, favourites, ...)
import bitwig.obf.YO; // the Quick Sources list
import bitwig.obf.Jp; // a browser session
import bitwig.obf.cCe; // the search field's suggestion policy
import bitwig.obf.cCk; // a browser (side or pop-up)
import bitwig.obf.cCl; // the browsers' base
import bitwig.obf.cDj; // a browser's result list
import bitwig.obf.cEz; // the pop-up browser
import bitwig.obf.cFV; // the side browser
import bitwig.obf.dVz; // a splitter
import bitwig.obf.dWb; // a row or column of widgets
import bitwig.obf.dmQ; // a registered action
import bitwig.obf.dnF; // a padded widget
import bitwig.obf.dve; // a widget
import bitwig.obf.dyC; // a splitter's child
import bitwig.obf.ecF; // a suggestion
import bitwig.obf.qH; // a browser configuration
import bitwig.obf.sO; // a device-like result
import bitwig.obf.tz; // a list filter
import bitwig.obf.wz; // a browser session, as a result model provider
import com.bitwig.flt.document.core.master.file_preview.hgq; // the cue player

import bml.api.At;
import bml.api.Hook;
import bml.api.Return;
import bml.api.This;
import bml.browser.Feature;
import bml.browser.Scope;

/**
 * Where the framework hooks into Bitwig. Every hook contains its own failures and falls back to
 * Bitwig's behaviour.
 *
 * <p>Suggestions: Bitwig's policy method tries each kind in turn and returns the first match. The
 * hooks gate the calls each kind starts with, so a switched-off kind finds nothing and the next
 * kind is tried. The browser asking (side or pop-up) is noted by the policy's only caller.
 */
public final class BrowserHooks {
    /** The suggestion policy: (session, typed text) → the first suggestion, or null. */
    private static final String POLICY = "cfM(LJp;Ljava/lang/String;)LecF;";
    /** The policy's helper for one filter's values. */
    private static final String FILTER_VALUE = "cfM(Lwz;Ltz;Ljava/lang/String;[Ljava/lang/String;)LecF;";
    /** The side browser lays out its rows. */
    private static final String SIDE_LAYOUT =
            "MRY(Lcom/bitwig/flt/document/core/iface/pHd;LJp;Lcom/bitwig/flt/document_view_settings/hgq;)V";

    /** The browser asking for a suggestion: {@link Scope#SIDE}, {@link Scope#POPUP}, or null for another. */
    private static final ThreadLocal<Scope> ASKING = new ThreadLocal<>();

    private BrowserHooks() {
    }

    // ---------------------------------------------------------------------
    // Suggestions
    // ---------------------------------------------------------------------

    @Hook(target = cCk.class, method = "MRY(Ljava/lang/String;)LecF;", at = At.ENTRY, id = "note-asking-browser")
    public static void suggestionAsked(@This cCk browser) {
        ASKING.set(browser instanceof cFV ? Scope.SIDE : browser instanceof cEz ? Scope.POPUP : null);
    }

    /** Sources: collections whose names match. */
    @Hook(target = cCe.class, method = POLICY, at = At.REPLACE_CALL, callOwner = EE.class, call = "Cuu",
          id = "gate-collections")
    @SuppressWarnings("rawtypes")
    public static List collections(EE library) {
        List all = library.Cuu(); // the collections
        return on(Feature.SUGGEST_SOURCES, false) ? all : List.of();
    }

    /** Sources: the configuration's other sources whose names match. */
    @Hook(target = cCe.class, method = POLICY, at = At.REPLACE_CALL, callOwner = qH.class,
          call = "FzB()Ljava/util/stream/Stream;", id = "gate-sources")
    @SuppressWarnings("rawtypes")
    public static Stream sources(qH configuration) {
        Stream all = configuration.FzB(); // the configuration's sources
        if (on(Feature.SUGGEST_SOURCES, false)) return all;
        all.close();
        return Stream.empty();
    }

    /** Instrument kinds: tried only when the configuration offers presets. */
    @Hook(target = cCe.class, method = POLICY, at = At.REPLACE_CALL, callOwner = Set.class, call = "contains",
          occurrence = 1, id = "gate-instrument-kinds")
    @SuppressWarnings("rawtypes")
    public static boolean instrumentKinds(Set kinds, Object presets) {
        return kinds.contains(presets) && on(Feature.SUGGEST_INSTRUMENT_KINDS, true);
    }

    /** Categories in other sources: tried only when the configuration offers presets. */
    @Hook(target = cCe.class, method = POLICY, at = At.REPLACE_CALL, callOwner = Set.class, call = "contains",
          occurrence = 2, id = "gate-other-categories")
    @SuppressWarnings("rawtypes")
    public static boolean otherCategories(Set kinds, Object presets) {
        return kinds.contains(presets) && on(Feature.SUGGEST_OTHER_CATEGORIES, true);
    }

    /** Everything: tried only when the configuration may switch sources. */
    @Hook(target = cCe.class, method = POLICY, at = At.REPLACE_CALL, callOwner = qH.class, call = "IwV()Z",
          occurrence = 2, id = "gate-everything")
    public static boolean everything(qH configuration) {
        return configuration.IwV() && on(Feature.SUGGEST_EVERYTHING, false);
    }

    /** Filter values: the Drum Loop category. */
    @Hook(target = cCe.class, method = POLICY, at = At.REPLACE_CALL, callOwner = cCe.class, call = FILTER_VALUE,
          occurrence = 1, id = "gate-drum-loop")
    public static ecF drumLoop(wz session, tz filter, String text, String[] words) {
        return filterValue(session, filter, text, words);
    }

    /** Filter values: Category. */
    @Hook(target = cCe.class, method = POLICY, at = At.REPLACE_CALL, callOwner = cCe.class, call = FILTER_VALUE,
          occurrence = 2, id = "gate-category")
    public static ecF category(wz session, tz filter, String text, String[] words) {
        return filterValue(session, filter, text, words);
    }

    /** Filter values: Creator. */
    @Hook(target = cCe.class, method = POLICY, at = At.REPLACE_CALL, callOwner = cCe.class, call = FILTER_VALUE,
          occurrence = 3, id = "gate-creator")
    public static ecF creator(wz session, tz filter, String text, String[] words) {
        return filterValue(session, filter, text, words);
    }

    /** Filter values: Vendor. */
    @Hook(target = cCe.class, method = POLICY, at = At.REPLACE_CALL, callOwner = cCe.class, call = FILTER_VALUE,
          occurrence = 4, id = "gate-vendor")
    public static ecF vendor(wz session, tz filter, String text, String[] words) {
        return filterValue(session, filter, text, words);
    }

    /** Filter values: Tags. */
    @Hook(target = cCe.class, method = POLICY, at = At.REPLACE_CALL, callOwner = cCe.class, call = FILTER_VALUE,
          occurrence = 5, id = "gate-tags")
    public static ecF tags(wz session, tz filter, String text, String[] words) {
        return filterValue(session, filter, text, words);
    }

    private static ecF filterValue(wz session, tz filter, String text, String[] words) {
        return on(Feature.SUGGEST_FILTER_VALUES, true) ? cCe.cfM(session, filter, text, words) : null;
    }

    /**
     * True when {@code kind} is on for the browser asking; a kind that sets a filter also needs
     * filtering in the side browser. Unknown browsers and failures keep Bitwig's behaviour.
     */
    private static boolean on(Feature kind, boolean setsFilter) {
        try {
            Scope browser = ASKING.get();
            if (browser == null) return true;
            State state = State.get();
            if (!state.get(kind, browser)) return false;
            return !setsFilter || browser != Scope.SIDE || state.get(Feature.FILTERING, Scope.SIDE);
        } catch (Throwable t) {
            State.LOG.log("reading " + kind.id() + " failed", t);
            return true;
        }
    }

    // ---------------------------------------------------------------------
    // Drill-down arrows
    // ---------------------------------------------------------------------

    /** The result list building a row. */
    private static final ThreadLocal<cDj> ROW_LIST = new ThreadLocal<>();

    @Hook(target = cDj.class, method = "cfM(Lwt;LdWb;)V", at = At.ENTRY, id = "note-result-row")
    public static void resultRowStarts(@This cDj list) {
        ROW_LIST.set(list);
    }

    /**
     * A device or plug-in row adds its drill-down arrow ("show presets for", which sets the Device
     * filter): left out of the side browser's rows while filtering is off.
     */
    @Hook(target = cDj.class, method = "cfM(Lwt;LdWb;)V", at = At.REPLACE_CALL, callOwner = dWb.class,
          call = "HL(Ldve;)V", occurrence = 2, id = "gate-drill-down-arrow")
    public static void addDrillDownArrow(dWb row, dve arrow) {
        cDj list = ROW_LIST.get();
        ROW_LIST.remove();
        if (list != null && !drillDownAllowed(list.bB())) return;
        row.HL(arrow);
    }

    /** The drill-down arrow is clicked (a row built before filtering was turned off). */
    @Hook(target = cDj.class, method = "cfM(LsO;)V", at = At.REPLACE_CALL, callOwner = Jp.class,
          call = "cfM(LsO;)V", id = "gate-drill-down")
    public static void drillDown(Jp session, sO device) {
        if (drillDownAllowed(session)) session.cfM(device);
    }

    private static boolean drillDownAllowed(Jp session) {
        try {
            return !SideBrowsers.isSideSession(session) || State.get().get(Feature.FILTERING, Scope.SIDE);
        } catch (Throwable t) {
            State.LOG.log("checking the drill-down failed", t);
            return true;
        }
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    /** The browser running a Quick Sources shortcut. */
    private static final ThreadLocal<cCk> QUICK = new ThreadLocal<>();

    /**
     * Bitwig asks a browser whether one of its actions is available. After its own checks (each a
     * "no"), the browser asks its widget base, which always answers yes ({@code dve.cfM(dmQ)});
     * the framework answers in its place, so an action whose feature is off is unavailable in the
     * side browser.
     */
    @Hook(target = cCk.class, method = "cfM(LdmQ;)Z", at = At.REPLACE_CALL, callOwner = cCl.class,
          call = "cfM(LdmQ;)Z", id = "gate-browser-actions")
    public static boolean actionAvailable(cCl browser, dmQ action) {
        try {
            return !(browser instanceof cCk b) || Actions.allowed(b, action);
        } catch (Throwable t) {
            State.LOG.log("checking a browser action failed", t);
            return true;
        }
    }

    /** A Quick Sources shortcut (F1-F9) runs. */
    @Hook(target = cCk.class, method = "cfM(I)V", at = At.ENTRY, id = "note-quick-source")
    public static void quickSource(@This cCk browser) {
        QUICK.set(browser);
    }

    /** The Quick Sources list's size: none while the shortcuts are off, so the shortcut does nothing. */
    @Hook(target = cCk.class, method = "cfM(I)V", at = At.REPLACE_CALL, callOwner = YO.class, call = "tp2()I",
          id = "gate-quick-source")
    public static int quickSources(YO sources) {
        int count = sources.tp2(); // the number of Quick Sources
        return quickAllowed() ? count : 0;
    }

    /** A Quick Sources sub-source shortcut runs. */
    @Hook(target = cCk.class, method = "Hnt(I)V", at = At.ENTRY, id = "note-quick-sub-source")
    public static void quickSubSource(@This cCk browser) {
        QUICK.set(browser);
    }

    /** The sub-sources' count: none while the shortcuts are off. */
    @Hook(target = cCk.class, method = "Hnt(I)V", at = At.REPLACE_CALL, callOwner = ArrayList.class, call = "size()I",
          id = "gate-quick-sub-source")
    @SuppressWarnings("rawtypes")
    public static int quickSubSources(ArrayList subSources) {
        int count = subSources.size();
        return quickAllowed() ? count : 0;
    }

    private static boolean quickAllowed() {
        cCk browser = QUICK.get();
        QUICK.remove();
        try {
            return browser == null || Actions.quickSourcesAllowed(browser);
        } catch (Throwable t) {
            State.LOG.log("checking a Quick Sources shortcut failed", t);
            return true;
        }
    }

    // ---------------------------------------------------------------------
    // Side browser rows
    // ---------------------------------------------------------------------

    /** The first row: the Quick Sources toolbar. */
    @Hook(target = cFV.class, method = SIDE_LAYOUT, at = At.REPLACE_CALL, callOwner = dWb.class, call = "HL(Ldve;)V",
          occurrence = 1, id = "note-quick-sources")
    public static void addToolbar(dWb column, dve toolbar) {
        column.HL(toolbar); // add the row
        try {
            SideBrowsers.toolbarAdded(toolbar);
        } catch (Throwable t) {
            State.LOG.log("noting the Quick Sources toolbar failed", t);
        }
    }

    /** The second row: the separator under the toolbar. */
    @Hook(target = cFV.class, method = SIDE_LAYOUT, at = At.REPLACE_CALL, callOwner = dWb.class, call = "HL(Ldve;)V",
          occurrence = 2, id = "note-toolbar-separator")
    public static void addSeparator(dWb column, dve separator) {
        column.HL(separator); // add the row
        try {
            SideBrowsers.separatorAdded(separator);
        } catch (Throwable t) {
            State.LOG.log("noting the toolbar separator failed", t);
        }
    }

    /** The side browser adds its results column under the filter area: with a view's results slot, the view's widget. */
    @Hook(target = cFV.class, method = SIDE_LAYOUT, at = At.REPLACE_CALL, callOwner = dVz.class, call = "MRY(LdyC;)V",
          id = "results-slot")
    public static void addResultsColumn(dVz splitter, dyC column) {
        splitter.MRY(Slots.results(column));
    }

    /** The search field is padded for its row: with the All Sources button, the button and the field side by side are. */
    @Hook(target = cFV.class, method = SIDE_LAYOUT, at = At.REPLACE_CALL, callOwner = dnF.class, call = "cfM(Ldve;IIII)LdnF;",
          id = "search-row")
    public static dnF padSearchRow(dve search, int left, int right, int top, int bottom) {
        dve row = SideBrowsers.searchRow(search);
        return dnF.cfM(row, row == search ? left : SideBrowsers.ROW_INSET, right, top, bottom);
    }

    /** The source header, built by the side browser's own method. */
    @Hook(target = cFV.class, method = SIDE_LAYOUT, at = At.REPLACE_CALL, callOwner = cFV.class, call = "UNj(LJp;)Ldve;",
          id = "note-source-header")
    public static dve buildHeader(cFV browser, Jp session) {
        dve header = browser.UNj(session); // the source header
        try {
            SideBrowsers.headerBuilt(header);
        } catch (Throwable t) {
            State.LOG.log("noting the source header failed", t);
        }
        return header;
    }

    /** The filter area, built by the browsers' shared method. */
    @Hook(target = cFV.class, method = SIDE_LAYOUT, at = At.REPLACE_CALL, callOwner = cFV.class, call = "IwV(LJp;)Ldve;",
          id = "note-filter-area")
    public static dve buildFilters(cFV browser, Jp session) {
        dve filters = browser.IwV(session); // the filter area
        try {
            SideBrowsers.filtersBuilt(filters);
        } catch (Throwable t) {
            State.LOG.log("noting the filter area failed", t);
        }
        return filters;
    }

    /** The details pane under the results, built by the side browser's own method. */
    @Hook(target = cFV.class, method = SIDE_LAYOUT, at = At.REPLACE_CALL, callOwner = cFV.class, call = "DNo()Ldve;",
          id = "note-details-pane")
    public static dve addDetails(cFV browser) {
        dve details = browser.DNo(); // the details pane
        try {
            SideBrowsers.detailsAdded(details);
        } catch (Throwable t) {
            State.LOG.log("noting the details pane failed", t);
        }
        return details;
    }

    /** The side browser starts laying out its rows. */
    @Hook(target = cFV.class, method = SIDE_LAYOUT, at = At.ENTRY, id = "side-browser-starts")
    public static void sideBrowserStarts(@This cFV browser) {
        try {
            SideBrowsers.starts(browser);
        } catch (Throwable t) {
            State.LOG.log("noting a side browser failed", t);
        }
    }

    /** The side browser is built. Bitwig's frame keeps no locals at this return, so the browser comes from the entry. */
    @Hook(target = cFV.class, method = SIDE_LAYOUT, at = At.BEFORE_RETURN, id = "side-browser-built")
    public static void sideBrowserBuilt() {
        try {
            SideBrowsers.built();
        } catch (Throwable t) {
            State.LOG.log("applying the switches to a side browser failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // A view's own content (Contents)
    // ---------------------------------------------------------------------

    /** The side browser sets its content, last in its build: with a content view, a stack with the view's widget. */
    @Hook(target = cFV.class, method = SIDE_LAYOUT, at = At.REPLACE_CALL, callOwner = cFV.class, call = "cfM(LdyC;)V",
          occurrence = 1, id = "view-content")
    public static void setContent(cFV browser, dyC content) {
        browser.cfM(Contents.content(browser, content));
    }

    /** A side browser is disposed (its window is rebuilt or closed): a view's panel in it too. */
    @Hook(target = cFV.class, method = "iy_()V", at = At.ENTRY, id = "side-browser-disposed")
    public static void sideBrowserDisposed(@This cFV browser) {
        try {
            Contents.disposed(browser);
            Slots.disposed(browser);
        } catch (Throwable t) {
            State.LOG.log("disposing a view's content failed", t);
        }
    }

    /** A cue player finished loading a file (its only return): a view that asked for it hears. */
    @Hook(target = hgq.class, method = "cfM(Ljava/lang/String;ZLSbG;Z)V", at = At.BEFORE_RETURN, id = "preview-loaded")
    public static void previewLoaded(@This hgq player) {
        try {
            Contents.previewLoaded(player);
        } catch (Throwable t) {
            State.LOG.log("telling a view about a loaded preview failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // Filter changes
    // ---------------------------------------------------------------------

    /** A filter gets or loses a value: a hidden filter area shows while any filter applies. */
    @Hook(target = DD.class, method = "FzB(Z)V", at = At.BEFORE_RETURN, id = "filter-value-changed")
    public static void filterValueChanged(@This DD filter) {
        filterChanged(filter);
    }

    /** A filter is offered or no longer offered for the current source (switching sources). */
    @Hook(target = DD.class, method = "hUR(Z)V", at = At.BEFORE_RETURN, id = "filter-offered-changed")
    public static void filterOfferedChanged(@This DD filter) {
        filterChanged(filter);
    }

    /**
     * A session builds its filter list: from then on the declared file kinds are allowed
     * ({@link Kinds#allowed}). The providers have declared (and the chosen view is active) before
     * any filter list is built, so a view's own hooks on the list, which a session builds while it
     * is restored, before any side browser, see the view as it is.
     */
    @Hook(target = Jp.class, method = "zpW", at = At.ENTRY, id = "note-filter-list-session")
    public static void filterListStarts() {
        try {
            State.get(); // the providers have declared
        } catch (Throwable t) {
            State.LOG.log("loading the browser providers failed", t);
        }
        Kinds.filtersListed();
    }

    /** The file kinds a configuration allows: Everything's with the file kinds mods declare ({@link Kinds}). */
    @Hook(target = qH.class, method = "MRY()Ljava/util/Set;", at = At.WRAP_RETURN, id = "kinds-allowed")
    @SuppressWarnings("rawtypes")
    public static Set kindsAllowed(@Return Set stock, @This qH configuration) {
        try {
            return Kinds.allowed(stock, configuration);
        } catch (Throwable t) {
            State.LOG.log("adding the declared file kinds failed", t);
            return stock;
        }
    }

    /** The file kind value whose icon is asked for ({@code CB.Ucl()}). */
    private static final ThreadLocal<bitwig.obf.CB> ICON_OF = new ThreadLocal<>();

    @Hook(target = bitwig.obf.CB.class, method = "Ucl", at = At.ENTRY, id = "note-kind-icon")
    public static void kindIconAsked(@This bitwig.obf.CB value) {
        ICON_OF.set(value);
    }

    /**
     * A file kind's small icon, by name (the file kind filter's values): a declared kind's own. The
     * value comes from the entry above: {@code CB.Ucl()}'s stack map drops {@code this} before its
     * final return, so a return hook can't take it.
     */
    @Hook(target = bitwig.obf.CB.class, method = "Ucl", at = At.WRAP_RETURN, id = "kind-icon-name")
    public static String kindIconName(@Return String icon) {
        bitwig.obf.CB value = ICON_OF.get();
        ICON_OF.remove();
        try {
            return value == null ? icon : Kinds.iconName(icon, value.E3());
        } catch (Throwable t) {
            return icon;
        }
    }

    /** Bitwig's source registry, as a stream: then the kinds of the file kinds mods add ({@link Kinds}). */
    @Hook(target = bitwig.obf.Vv.class, method = "MRY()Ljava/util/stream/Stream;", at = At.WRAP_RETURN, id = "registry-kinds")
    @SuppressWarnings("rawtypes")
    public static Stream registryWithKinds(@Return Stream registry) {
        try {
            return Kinds.withKinds(registry);
        } catch (Throwable t) {
            State.LOG.log("adding the kinds to the sources failed", t);
            return registry;
        }
    }

    /** The source name the registry is asked for (a saved session's source being restored). */
    private static final ThreadLocal<String> SOURCE_NAME = new ThreadLocal<>();

    @Hook(target = bitwig.obf.Vv.class, method = "cfM(Ljava/lang/String;)LVu;", at = At.ENTRY, id = "note-source-name")
    public static void sourceNameAsked(@bml.api.Arg(0) String name) {
        SOURCE_NAME.set(name);
    }

    /** Bitwig's lookup by name found nothing: one of the added kinds, if it has that name. */
    @Hook(target = bitwig.obf.Vv.class, method = "cfM(Ljava/lang/String;)LVu;", at = At.WRAP_RETURN, id = "registry-kind-by-name")
    public static bitwig.obf.Vu sourceNamed(@Return bitwig.obf.Vu found) {
        String name = SOURCE_NAME.get();
        SOURCE_NAME.remove();
        if (found != null) return found;
        try {
            return Kinds.named(name);
        } catch (Throwable t) {
            State.LOG.log("finding the kind " + name + " failed", t);
            return null;
        }
    }

    private static void filterChanged(DD filter) {
        if (!(filter instanceof DC dc)) return;
        try {
            SideBrowsers.filterChanged(dc);
        } catch (Throwable t) {
            State.LOG.log("updating the filter area failed", t);
        }
    }
}
