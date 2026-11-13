package bml.browser;

/**
 * A part of Bitwig's browsers the framework can switch on or off. Every row of the side browser
 * has two features: whether it is shown ({@code _VISIBLE}) and whether its functions work
 * ({@code _ACTIONS}, {@link #FILTERING}). Suggestion kinds are set per browser; the other features
 * apply to the side browser. Everything except {@link #SIDE_PANE} and {@link #ALL_SOURCES_BUTTON}
 * is on by default (Bitwig as it ships).
 */
public enum Feature {
    /** Search suggestion: "Go to" a collection or another source whose name matches. */
    SUGGEST_SOURCES("suggest.sources", "Suggest sources and collections", true),
    /** Search suggestion: "Go to" Drums, Bass, Synths, Keys or Other Instruments, with the category set. */
    SUGGEST_INSTRUMENT_KINDS("suggest.instrument-kinds", "Suggest instrument kinds", true),
    /** Search suggestion: "Set Category / Creator / Vendor / Tags to" a value, and the Drum Loop category. */
    SUGGEST_FILTER_VALUES("suggest.filter-values", "Suggest filter values", true),
    /** Search suggestion: "Go to" a category the current results don't offer, in Everything or Audio FX. */
    SUGGEST_OTHER_CATEGORIES("suggest.other-categories", "Suggest categories in other sources", true),
    /** Search suggestion: "Go to Everything" when there are fewer than 10 results. */
    SUGGEST_EVERYTHING("suggest.everything", "Suggest Everything", true),

    /** The Quick Sources toolbar and the line under it are shown. */
    QUICK_SOURCES_VISIBLE("show.quick-sources", "Show Quick Sources toolbar", false),
    /** The Quick Sources shortcuts (F1-F9 and their sub-sources) work. */
    QUICK_SOURCES_ACTIONS("actions.quick-sources", "Quick Sources shortcuts", false),
    /** The source header (source icon, arrows, Favourites star, sub-sources) is shown. */
    SOURCE_HEADER_VISIBLE("show.source-header", "Show source header", false),
    /** The source header's shortcuts work: previous/next source and show favourites only. */
    SOURCE_HEADER_ACTIONS("actions.source-header", "Source shortcuts (previous/next, favourites only)", false),
    /** The filter area above the results is shown (while {@link #FILTERING} is on). */
    FILTERS_VISIBLE("show.filters", "Show filters", false),
    /**
     * Filtering works: the filter shortcuts, the suggestions that set a filter, and the results'
     * drill-down arrows (they set the Device filter). Turning it off
     * clears the side browser's filters once and hides the filter area, except while a filter
     * that Bitwig sets (a restored project, a snapshot, a preset drill-down) applies and
     * {@link #FILTER_AUTO_SHOW} is on.
     */
    FILTERING("actions.filters", "Filtering", false),
    /** The filter area is shown while any filter applies, even when it is otherwise hidden. */
    FILTER_AUTO_SHOW("auto-show.filters", "Show filters while a filter applies", false),
    /** The details pane under the results (type, category, vendor, collections) is shown. */
    DETAILS_VISIBLE("show.details", "Show details pane", false),
    /** The details pane's shortcut (focus the pane) works. */
    DETAILS_ACTIONS("actions.details", "Details pane shortcut", false),
    /** The favourite and collection shortcuts work (the result's context menu is unaffected). */
    FAVOURITE_ACTIONS("actions.favourites", "Favourite and collection shortcuts", false),

    /**
     * Was: a pane beside the side browser's results, which the framework built. The framework no
     * longer builds one: a view places the results itself with {@link View#results} (the pane is
     * Browser: Pre-5.0 Style Side Browser's now). A view that claims this on and has no results slot
     * fails when activated (its claims lapse, logged), so the browser isn't left without its
     * source rows. Its value can still be read.
     *
     * @deprecated use {@link View#results}
     */
    @Deprecated
    SIDE_PANE("pane.side", "Pane beside the results", false, false),

    /**
     * Bitwig's All Sources button at the left of the side browser's search field, the same button
     * the Quick Sources toolbar has. For a view that hides the toolbar. Off by default; it applies
     * when the side browser is built (a view that changes it rebuilds the browser).
     */
    ALL_SOURCES_BUTTON("show.all-sources-button", "All Sources button beside the search field", false, false);

    private final String id;
    private final String label;
    private final boolean perBrowser;
    private final boolean defaultOn;

    Feature(String id, String label, boolean perBrowser) {
        this(id, label, perBrowser, true);
    }

    Feature(String id, String label, boolean perBrowser, boolean defaultOn) {
        this.id = id;
        this.label = label;
        this.perBrowser = perBrowser;
        this.defaultOn = defaultOn;
    }

    /** The feature's ID, as the framework stores and logs it. */
    public String id() {
        return id;
    }

    /** A short human-readable name. */
    public String label() {
        return label;
    }

    /** True for features set per browser (side and pop-up); the others apply to the side browser. */
    public boolean perBrowser() {
        return perBrowser;
    }

    /** The value until the user or a mod sets it. */
    public boolean defaultOn() {
        return defaultOn;
    }
}
