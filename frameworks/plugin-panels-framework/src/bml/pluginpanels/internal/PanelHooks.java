package bml.pluginpanels.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import bitwig.obf.dAd; // a button
import bitwig.obf.egY; // a button style
import bitwig.obf.dWb; // a vertical or horizontal group of widgets
import bitwig.obf.dWh; // Bitwig's line widget (separators)
import bitwig.obf.dnF; // a padding around a widget (can fill its background)
import bitwig.obf.dyC; // a widget, as a container's child
import bitwig.obf.eeY; // a theme colour
import bitwig.obf.dve; // a widget
import bitwig.obf.ecz; // Bitwig's search field
import bitwig.obf.zcJ; // a plug-in device's contents (VST2, VST3, CLAP)
import bml.api.Arg;
import bml.api.At;
import bml.api.Hook;
import bml.api.This;
import com.bitwig.flt.widget.core.device.OFG; // Bitwig's body for a plug-in device

/**
 * Where the framework hooks into Bitwig. A loaded plug-in's body ({@code OFG.Hnt}) has a toolbar
 * (Parameters, Programs, sidechain, outputs, a parameter search field) over a page switcher; the
 * switcher's factory builds the Parameters page through {@code qoU}.
 * <ul>
 * <li>The Parameters page: Bitwig builds its own page first, then the framework returns a layout's
 * page instead when one matches.</li>
 * <li>The search field: Bitwig's list stays built beside the layout's page; after Bitwig applies the
 * search text to its list ({@code MYI}), the page shows while the text is empty and the list while
 * text is typed. A layout with {@code "search": false} leaves the search field out instead.</li>
 * <li>The toolbar: Bitwig adds its pieces to the row in this order: the Parameters and Programs tabs,
 * the sidechain and outputs buttons, the search field, the container button ("plugin.show_hide_postfx",
 * the plug-in's own FX container), then the row to the body. Each piece but the container button can be
 * left out by the layout; without the search field a filler keeps the container button at the right.
 * With {@code "toolbar": false} the row is left out and the container button moves to the panel's title
 * line. The container button is never removed: it is key to Bitwig.</li>
 * </ul>
 * The toolbar follows the layout only when the layout's panel was built for this body (Bitwig builds the
 * Parameters page before its toolbar); otherwise the toolbar stays Bitwig's, whole.
 * Bitwig's other tabs and its missing, loading and crashed states are untouched.
 */
public final class PanelHooks {
    /** The plug-in whose loaded body is being built on this thread. */
    private static final ThreadLocal<zcJ> BUILDING = new ThreadLocal<>();

    private PanelHooks() {
    }

    /** A layout's panel built for a plug-in, and where its container button goes (null: in the toolbar row). */
    private static final class Built {
        final Layouts.Layout layout;
        final dWb container;
        final ModeColours modes; // the panel's mode colours, or null
        boolean containerMoved;

        Built(Layouts.Layout layout, dWb container, ModeColours modes) {
            this.layout = layout;
            this.container = container;
            this.modes = modes;
        }
    }

    /** A mode colour's share in the bar's background ({@code mode_colors} target {@code toolbar}). */
    static final double BAR_TINT = 0.25;
    /** The List mode's icon: Bitwig's own list icon. */
    static final String LIST_ICON = "track_list.svg";
    /** The Layout mode's icon: Bitwig's own Parameters tab icon. */
    static final String LAYOUT_ICON = "plugin_parameters.svg";

    /** The body being built on this thread. */
    private static final ThreadLocal<OFG> BODY = new ThreadLocal<>();

    private static final Map<zcJ, Built> BUILT = new WeakHashMap<>();

    /**
     * The toolbar buttons kept for the right-hand bar of the body being built on this thread, by part
     * ("parameters" for a Parameters tab kept by the Parameters-tab rule, "pages", "sidechain", "outputs",
     * "container"), in Bitwig's order; assembled in the layout's order when Bitwig adds the pages.
     */
    private static final ThreadLocal<Map<String, List<dve>>> PARTS = new ThreadLocal<>();

    /**
     * The layout when it puts the toolbar's buttons in a bar on the right. Unlike the top row, the bar
     * follows the layout even when its panel wasn't built (a body opened on another page): every button
     * stays reachable in the bar, beside whatever page shows.
     */
    private static Layouts.Layout barLayout() {
        Built b = built();
        Layouts.Layout layout = b != null ? b.layout : null;
        if (layout == null) {
            zcJ contents = BUILDING.get();
            layout = contents == null ? null : Panels.layoutFor(Plugin.of(contents));
        }
        return layout != null && layout.bar().right() ? layout : null;
    }

    /** Keeps a toolbar button for the right-hand bar, in the layout's style and size. */
    private static void toBar(dve themed, Layouts.Layout layout, dve button, String part) {
        style(themed, layout, button);
        PARTS.get().computeIfAbsent(part, k -> new java.util.ArrayList<>()).add(button);
    }

    /** A bar button in the layout's button style and size. */
    private static void style(dve themed, Layouts.Layout layout, dve button) {
        Layouts.Toolbar bar = layout.bar();
        if (button instanceof dAd b && !bar.style().isEmpty()) {
            egY style = themed.jl_().znC().cfM(bar.style()); // the theme's button styles, by name
            if (style == null) Panels.report("bar-style:" + bar.style(), layout.source() + ": unknown button style '" + bar.style() + "'; Bitwig's used", null);
            else b.cfM((bitwig.obf.elA) style);
        }
        button.a_(FreeSize.exactly(bar.size()));
        button.MRY(FreeSize.exactly(bar.size()));
    }

    /**
     * The right-hand bar: the kept buttons in the layout's {@code order} (a "spacer" takes spare height; without
     * one, {@code align} places the stack), the list switch, the container button always (at the end when the
     * order leaves it out), on the bar's own background, a gap above and under.
     */
    private static dve bar(dve themed, Layouts.Layout layout, Map<String, List<dve>> parts) {
        Layouts.Toolbar bar = layout.bar();
        Built built = built();
        dWb box = dWb.qFI(); // vertical
        box.TMF((int) bar.gap());
        box.UNj(0.5f); // centred across
        box.a_(FreeSize.exactly(bar.size()));
        List<String> order = new java.util.ArrayList<>(bar.order());
        if (!order.contains("container")) {
            order.add("container");
            Panels.report("bar-container:" + layout.source(), layout.source() + ": toolbar.order leaves out the container button; it goes at the end (it is always shown)", null);
        }
        boolean otherPages = bar.list() || (bar.pages() && order.contains("programs")) || (bar.sidechain() && order.contains("sidechain"))
                || (bar.outputs() && order.contains("outputs"));
        if (!order.contains("layout") && otherPages) { // the way back to the layout
            order.add(0, "layout");
            Panels.report("bar-layout:" + layout.source(), layout.source() + ": toolbar.order leaves out the Layout button while other pages can show; it goes first", null);
        }
        ViewModes view = bar.list() ? ViewModes.of(BODY.get()) : null;
        boolean spacers = order.contains("spacer");
        if (!spacers && !bar.align().equals("top")) box.cfM(Filler.create(), 1f); // center, bottom: room above
        for (String entry : order) {
            switch (entry) {
                case "spacer" -> box.cfM(Filler.create(), 1f);
                case "layout" -> {
                    if (view != null) box.HL(mode(themed, layout, view.layout(), LAYOUT_ICON, "Layout")); // Layout mode: the panel
                    else for (dve b : parts.getOrDefault("layout", List.of())) box.HL(b); // Bitwig's Parameters tab
                }
                case "list" -> {
                    if (view != null) box.HL(mode(themed, layout, view.list(), LIST_ICON, "Parameter list")); // List mode: Bitwig's list and search
                }
                default -> {
                    for (dve b : parts.getOrDefault(entry, List.of())) box.HL(b);
                }
            }
        }
        if (!spacers && !bar.align().equals("bottom")) box.cfM(Filler.create(), 1f); // top, center: room below
        dnF padded = dnF.cfM(0, 0, (int) bar.gap(), (int) bar.gap()); // a gap above the first and under the last button
        padded.cfM((dyC) box);
        if (!bar.background().isEmpty()) {
            eeY fill = Panels.colourOf(themed, bar.background(), layout.source(), "toolbar background");
            if (fill != null) {
                boolean tinted = built != null && built.modes != null && layout.modes() != null && layout.modes().applies("toolbar");
                padded.UNj(tinted ? built.modes.new Colour("toolbar", fill, BAR_TINT) : fill); // its own background (following the mode)
                if (tinted) built.modes.repaints(padded);
            }
        }
        return padded;
    }

    /** A mode button of the bar: Bitwig's toggle button on a mode, with one of Bitwig's icons, in the bar's style. */
    private static dve mode(dve themed, Layouts.Layout layout, bitwig.obf.AQw value, String icon, String tooltip) {
        dAd button = dAd.MRY(value); // Bitwig's toggle button, lit while the mode shows
        button.TMF(icon);            // Bitwig's own icon, under its icons folder
        button.z3I(tooltip);
        style(themed, layout, button);
        return button;
    }

    /** A layout's panel was built for {@code contents} (from the Parameters page, before the toolbar). */
    static void panelBuilt(zcJ contents, Layouts.Layout layout, dWb container, ModeColours modes) {
        synchronized (BUILT) {
            BUILT.put(contents, new Built(layout, container, modes));
        }
    }

    /** The panel built for the body being built on this thread, or null. */
    private static Built built() {
        zcJ contents = BUILDING.get();
        if (contents == null) return null;
        synchronized (BUILT) {
            return BUILT.get(contents);
        }
    }

    /** Adds a toolbar piece unless the built panel's layout leaves it out. */
    private static void piece(dWb toolbar, dve child, String part) {
        try {
            Layouts.Layout right = child instanceof dAd ? barLayout() : null;
            if (right != null) {
                Layouts.Toolbar bar = right.bar();
                if (PARTS.get() == null) PARTS.set(new LinkedHashMap<>());
                // kept: on (the bar's booleans); the order places them (bar())
                switch (part) {
                    case "parameters" -> toBar(toolbar, right, child, "layout"); // the Layout mode (Bitwig's tab, or ours with a list)
                    case "pages" -> { if (bar.pages()) toBar(toolbar, right, child, "programs"); }
                    case "sidechain" -> { if (bar.sidechain()) toBar(toolbar, right, child, "sidechain"); }
                    default -> { if (bar.outputs()) toBar(toolbar, right, child, "outputs"); }
                }
                return;
            }
            Built b = built();
            if (b != null && child instanceof dAd) {
                Layouts.Toolbar bar = b.layout.bar();
                boolean keep = switch (part) {
                    case "parameters" -> {
                        if (!bar.pages() && bar.parametersTab())
                            Panels.report("parameters-tab:" + b.layout.source(), b.layout.source() + ": the Parameters tab stays: the sidechain or outputs button opens a page with no other way back", null);
                        yield bar.parametersTab();
                    }
                    case "pages" -> bar.pages();
                    case "sidechain" -> bar.sidechain();
                    default -> bar.outputs();
                };
                if (!keep) return;
            }
        } catch (Throwable t) {
            Panels.report("toolbar-" + part, "checking the plug-in's toolbar failed; keeping Bitwig's", t);
        }
        toolbar.HL(child); // Bitwig's own addition
    }

    @Hook(id = "toolbar-parameters-tab", target = OFG.class, method = "Hnt(LzcJ;)Ldve;", at = At.REPLACE_CALL,
          callOwner = dWb.class, call = "HL(Ldve;)V", occurrence = 1)
    public static void toolbarParametersTab(dWb toolbar, dve child) {
        piece(toolbar, child, "parameters");
    }

    @Hook(id = "toolbar-programs-tab", target = OFG.class, method = "Hnt(LzcJ;)Ldve;", at = At.REPLACE_CALL,
          callOwner = dWb.class, call = "HL(Ldve;)V", occurrence = 2)
    public static void toolbarProgramsTab(dWb toolbar, dve child) {
        piece(toolbar, child, "pages");
    }

    @Hook(id = "toolbar-sidechain", target = OFG.class, method = "Hnt(LzcJ;)Ldve;", at = At.REPLACE_CALL,
          callOwner = dWb.class, call = "HL(Ldve;)V", occurrence = 3)
    public static void toolbarSidechain(dWb toolbar, dve child) {
        piece(toolbar, child, "sidechain");
    }

    @Hook(id = "toolbar-outputs", target = OFG.class, method = "Hnt(LzcJ;)Ldve;", at = At.REPLACE_CALL,
          callOwner = dWb.class, call = "HL(Ldve;)V", occurrence = 4)
    public static void toolbarOutputs(dWb toolbar, dve child) {
        piece(toolbar, child, "outputs");
    }

    @Hook(id = "parameters-page", target = OFG.class, method = "cfM(LzcJ;LYEE;)Ldve;", at = At.REPLACE_CALL,
          callOwner = OFG.class, call = "qoU(LzcJ;)Ldve;")
    public static dve parametersPage(OFG body, zcJ contents) {
        dve page = body.qoU(contents); // Bitwig's generic parameter list
        try {
            Plugin plugin = Plugin.of(contents);
            return plugin == null ? page : Panels.parametersPage(body, plugin, page);
        } catch (Throwable t) {
            Panels.report("hook", "plug-in panel failed; showing Bitwig's panel", t);
            return page;
        }
    }

    @Hook(id = "loaded-body-start", target = OFG.class, method = "Hnt(LzcJ;)Ldve;", at = At.ENTRY)
    public static void loadedBodyStarts(@This OFG body, @Arg(0) zcJ contents) {
        BUILDING.set(contents);
        BODY.set(body);
        PARTS.remove();
    }

    /** Bitwig has applied the search text to its list: the layout's page or Bitwig's list shows. */
    @Hook(id = "search-changed", target = OFG.class, method = "MYI()V", at = At.BEFORE_RETURN)
    public static void searchChanged(@This OFG body) {
        try {
            Panels.searchChanged(body);
        } catch (Throwable t) {
            Panels.report("search", "showing the search result failed", t);
        }
    }

    /**
     * The toolbar's fifth addition is the search field: left out when the layout says so ({@code "search": false},
     * {@code "toolbar": {"search": false}} or {@code "toolbar": false}); a filler then takes its place, so the
     * container button stays at the right.
     */
    @Hook(id = "toolbar-search-field", target = OFG.class, method = "Hnt(LzcJ;)Ldve;", at = At.REPLACE_CALL,
          callOwner = dWb.class, call = "HL(Ldve;)V", occurrence = 5)
    public static void toolbarAdds(dWb toolbar, dve child) {
        try {
            Layouts.Layout right = child instanceof ecz ? barLayout() : null;
            if (right != null) { // a right-hand bar: the search field only for its List mode, above the pages
                if (right.bar().list()) {
                    if (PARTS.get() == null) PARTS.set(new LinkedHashMap<>());
                    PARTS.get().computeIfAbsent("search", k -> new java.util.ArrayList<>()).add(child);
                }
                return;
            }
            Built b = built();
            if (child instanceof ecz && b != null && !b.layout.search()) {
                toolbar.cfM(Filler.create(), 1f); // takes the row's spare width, as the search field did
                return;
            }
        } catch (Throwable t) {
            Panels.report("toolbar", "checking the plug-in's toolbar failed; keeping Bitwig's", t);
        }
        toolbar.HL(child); // Bitwig's own addition
    }

    /**
     * The sixth addition is the container button (the plug-in's FX container): never left out. Without a
     * toolbar row it goes to the panel's title line instead.
     */
    @Hook(id = "toolbar-container", target = OFG.class, method = "Hnt(LzcJ;)Ldve;", at = At.REPLACE_CALL,
          callOwner = dWb.class, call = "HL(Ldve;)V", occurrence = 6)
    public static void toolbarContainer(dWb toolbar, dve child) {
        try {
            Layouts.Layout right = child instanceof dAd ? barLayout() : null;
            if (right != null) {
                if (PARTS.get() == null) PARTS.set(new LinkedHashMap<>());
                toBar(toolbar, right, child, "container"); // always shown
                return;
            }
            Built b = built();
            if (b != null && b.container != null && child instanceof dAd) {
                b.container.HL(child); // at the right end of the panel's title line
                b.containerMoved = true;
                return;
            }
        } catch (Throwable t) {
            Panels.report("toolbar-container", "moving the container button failed; keeping Bitwig's toolbar", t);
        }
        toolbar.HL(child); // Bitwig's own addition
    }

    /**
     * The seventh addition puts the toolbar row into the body (the eighth adds the page switcher under it):
     * left out when the layout says {@code "toolbar": false} and the container button has moved to the panel.
     */
    @Hook(id = "toolbar-row", target = OFG.class, method = "Hnt(LzcJ;)Ldve;", at = At.REPLACE_CALL,
          callOwner = dWb.class, call = "HL(Ldve;)V", occurrence = 7)
    public static void bodyAdds(dWb column, dve child) {
        try {
            if (child instanceof dWb && PARTS.get() != null) return; // the buttons are in the right-hand bar: the row is empty
            Built b = built();
            if (child instanceof dWb && b != null && !b.layout.toolbar() && b.containerMoved) return;
        } catch (Throwable t) {
            Panels.report("toolbar-row", "checking the plug-in's toolbar row failed; keeping Bitwig's", t);
        }
        column.HL(child); // Bitwig's own addition
    }

    /**
     * The eighth addition puts the page switcher (the panel, or Bitwig's other pages) into the body: with a
     * right-hand bar, the switcher and the bar side by side, the bar as tall as the body.
     */
    @Hook(id = "body-pages", target = OFG.class, method = "Hnt(LzcJ;)Ldve;", at = At.REPLACE_CALL,
          callOwner = dWb.class, call = "HL(Ldve;)V", occurrence = 8)
    public static void bodyPages(dWb column, dve child) {
        Map<String, List<dve>> parts = PARTS.get();
        PARTS.remove();
        try {
            Layouts.Layout layout = parts == null ? null : barLayout();
            if (layout != null) {
                Layouts.Toolbar bar = layout.bar();
                dWb side = dWb.ELS(); // horizontal: the pages, (a divider,) the bar
                side.TMF((int) bar.gap());
                side.TtY(true); // the bar as tall as the body
                dve pages = child;
                List<dve> search = parts.remove("search");
                if (search != null && !search.isEmpty()) { // Bitwig's search field over the pages, shown in List mode only
                    dWb withSearch = dWb.qFI(); // vertical
                    withSearch.TMF(2);
                    withSearch.TtY(true); // the field as wide as the pages
                    withSearch.HL(search.get(0));
                    withSearch.cfM(child, 1f);
                    ViewModes.of(BODY.get()).search(search.get(0));
                    pages = withSearch;
                }
                side.cfM(pages, 1f); // the pages take the rest of the width (once the width is kept, with none of their own: ViewModes)
                if (bar.divider()) {
                    dWh line = dWh.FzB(1); // Bitwig's line widget, 1 unit thick
                    line.cfM(column.PEn().QOl()); // the theme's separator colour
                    side.HL(line);
                }
                dve barWidget = bar(column, layout, parts);
                side.HL(barWidget);
                ViewModes.of(BODY.get()).parts(pages, barWidget); // for the width log
                column.HL(side);
                return;
            }
        } catch (Throwable t) {
            Panels.report("toolbar-bar", "placing the toolbar's bar failed", t);
        }
        column.HL(child); // Bitwig's own addition
        if (parts != null) { // never lose the buttons: in a row under the pages
            dWb row = dWb.ELS();
            for (List<dve> group : parts.values()) for (dve b : group) row.HL(b);
            column.HL(row);
        }
    }
}
