package bml.pluginpanels.internal;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import bitwig.obf.LJt; // a text value
import bitwig.obf.OpO; // a size fixed at the widget's preferred size
import bitwig.obf.Sxr; // a fixed size (layout units)
import bitwig.obf.Xth; // a device's output level meter
import bitwig.obf.aEJ; // Bitwig's meter on a value
import bitwig.obf.aEM; // a meter's orientation
import bitwig.obf.aoW; // Bitwig's plug-in parameter row: name, slider, value
import bitwig.obf.atr; // Bitwig's plug-in parameter knob
import bitwig.obf.avo; // Bitwig's VU meter
import bitwig.obf.dAd; // a button
import bitwig.obf.dBX; // Bitwig's dropdown
import bitwig.obf.dWb; // a vertical or horizontal group of widgets
import bitwig.obf.dnF; // a padding around a widget (can fill its background)
import bitwig.obf.dns; // a widget, as Bitwig types it (binds properties)
import bitwig.obf.duY; // a text label
import bitwig.obf.dve; // a widget
import bitwig.obf.atu; // Bitwig's bare knob (the knob inside its plug-in knob)
import bitwig.obf.dWh; // Bitwig's line widget (separators)
import bitwig.obf.dtu; // Bitwig's scaled container
import bitwig.obf.dyC; // a widget, as a container's child
import bitwig.obf.eeY; // a theme colour
import bitwig.obf.efU; // a knob style
import bitwig.obf.egC; // a dropdown (popup chooser) style
import bitwig.obf.efu; // a theme font
import bitwig.obf.egY; // a theme button style
import bitwig.obf.ejE; // a font, as a label takes it
import bitwig.obf.ejt; // a colour, as a widget takes it
import bitwig.obf.elA; // a widget style
import bitwig.obf.iok; // a colour
import bitwig.obf.jsd; // a device in a device chain
import bitwig.obf.kn; // a widget's natural size
import bitwig.obf.ljb; // a level meter
import bitwig.obf.svm; // a size policy
import bitwig.obf.tft; // one channel's meter value
import bitwig.obf.uUk; // a plug-in parameter
import bitwig.obf.w3; // Bitwig's standard sizes
import bml.api.Bml;
import bml.pluginpanels.internal.Layouts.Column;
import bml.pluginpanels.internal.Layouts.Item;
import bml.pluginpanels.internal.Layouts.Layout;
import com.bitwig.base.geom.Dgc; // where a widget places its content in its own box (left, centre, ...)
import com.bitwig.flt.document.core.master.device.FDA; // a device, as stored (its meters)
import com.bitwig.flt.widget.core.device.OFG; // Bitwig's body for a plug-in device

/**
 * Builds a layout's page for a loaded plug-in, or returns Bitwig's own page: when no layout
 * matches, when the layout names a parameter the plug-in doesn't have, or when building fails.
 * Every failure is logged in one line (once per message) and never reaches Bitwig.
 */
final class Panels {
    static final Bml.Mod MOD = Bml.mod("plugin-panels-framework");

    // sizes and spacing in layout units (also in the theme sheet, CopyTheme)
    static final long NEEDLE_WIDTH = 64;
    static final long NEEDLE_HEIGHT = 40;
    static final long BAR_WIDTH = 13;
    static final long BAR_HEIGHT = 56;
    static final long LEVEL_WIDTH = 4;     // as Bitwig's own gain-reduction meter
    static final long ENVELOPE_WIDTH = 120;
    static final long ENVELOPE_HEIGHT = 50;
    static final long PAD = 4;             // padding, gaps between columns and between a row's items
    static final long COLUMN_GAP = 2;      // between a column's items
    static final long PAGE_GAP = 3;        // between the title line and the page
    static final long LINE_GAP = 1;        // between a control's label, control and value text
    static final long TAB_GAP = 2;         // between page tabs
    static final long BACKGROUND_PAD = 2;  // around an item with a background
    static final double BACKGROUND_TINT = 0.12; // a mode colour's share in the background tint
    static final long ICON_SIZE = 17; // an icon label's default size: Bitwig's smallest standard control size
    static final long DIVIDER = 1;    // a divider line's width

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private Panels() {
    }

    /** Logs a failure once per key; {@code t} may be null for a one-line message. */
    static void report(String key, String message, Throwable t) {
        if (!REPORTED.add(key + "\n" + message)) return;
        if (t == null) MOD.log(message);
        else MOD.log(message, t);
    }

    /**
     * The page for the plug-in's Parameters tab: the layout's, or {@code bitwigPage}. With the search
     * field kept, Bitwig's own list stays built next to the layout's panel: the panel shows while the
     * search text is empty, the list (filtered by Bitwig) while text is typed (as adoxe (Daniil)'s
     * "Design: Device Panels" plug-in cards do; also his top alignment and width from the columns).
     */
    static dve parametersPage(OFG body, Plugin plugin, dve bitwigPage) {
        Layout layout = Layouts.find(plugin);
        if (layout == null) return bitwigPage;
        try {
            Builder builder = new Builder(body, plugin, layout);
            dve panel = builder.build();
            dWb withList = null;
            if (layout.bar().right() && layout.bar().list()) { // the bar's Layout and List modes: the panel and Bitwig's list, one shown
                withList = dWb.qFI(); // vertical: the panel, Bitwig's list
                withList.TtY(true);   // as wide as the pages' area: the list fills it (the body's width is fixed)
                withList.HL(panel);
                withList.HL(bitwigPage);
                ViewModes.of(body).attach(panel, bitwigPage); // kept per body: Bitwig builds this page again each time it shows
            }
            PanelHooks.panelBuilt(plugin.contents, layout, builder.container, builder.modes); // the toolbar hooks follow the layout only for a built panel
            // the body's width (Bitwig's is 170): the layout's, else the sum of its fixed column widths, else fixed
            // at the content's preferred width, so Bitwig never squeezes the panel (a squeezed row shrinks its
            // labels to "Abc...")
            long width = layout.width() > 0 ? layout.width() : builder.fixedWidth();
            long bar = layout.bar().barWidth();
            ViewModes view = ViewModes.of(body); // the body's kept width, across Bitwig's rebuilds of this page
            if (view.keptWidth() > 0) {
                body.a_(FreeSize.exactly(view.keptWidth())); // kept when the panel first showed: a rebuild (e.g. after the sidechain page) keeps it
            } else {
                // the layout's width (and the bar's), else the content's; kept as measured once the panel has shown
                // (Bitwig lays a fixed width out a little narrower than the sum: VintageVerb 535 units for 566), so
                // Bitwig's other pages and its list take exactly the panel's width
                body.a_(width > 0 ? FreeSize.exactly(width + bar) : OpO.cfM());
                builder.fit.keepWidth(units -> view.width(units, bar));
            }
            body.wGc.E3(0f); // the page switcher's content at the top (Bitwig centres its list vertically)
            builder.bodyBackground(); // the whole body, toolbar included
            MOD.log("custom panel for " + plugin.format() + " " + Plugin.header(plugin) + " from " + layout.source());
            if (withList != null) return withList;
            if (!layout.search() || !layout.toolbar()) return panel; // no search field: the panel alone
            dWb both = dWb.qFI(); // vertical: the panel, Bitwig's list
            both.HL(panel);
            both.HL(bitwigPage);
            synchronized (SHOWN) {
                SHOWN.put(body, new Shown(new WeakReference<>(panel), new WeakReference<>(bitwigPage)));
            }
            searchChanged(body);
            return both;
        } catch (LayoutError e) {
            report("layout:" + layout.source(), layout.source() + ": " + e.getMessage() + "; showing Bitwig's panel for " + plugin.name(), null);
        } catch (Throwable t) {
            report("build:" + layout.source(), layout.source() + ": building the panel failed; showing Bitwig's panel for " + plugin.name(), t);
        }
        return bitwigPage;
    }

    /** A panel and Bitwig's list beside it, for one plug-in body. */
    private record Shown(WeakReference<dve> panel, WeakReference<dve> list) {
    }

    private static final Map<OFG, Shown> SHOWN = new WeakHashMap<>();

    /**
     * Bitwig has applied the body's search text to its list: an empty text shows the panel, any text
     * Bitwig's list (which it has just filtered).
     */
    static void searchChanged(OFG body) {
        Shown shown;
        synchronized (SHOWN) {
            shown = SHOWN.get(body);
        }
        if (shown == null) return;
        String text = body.hUR == null ? null : body.hUR.lYc(); // the body's page state: its search text
        boolean searching = text != null && !text.isBlank();
        dve panel = shown.panel().get(), list = shown.list().get();
        if (panel != null) panel.Dah(!searching); // shown or hidden
        if (list != null) list.Dah(searching);
    }

    /** The layout that matches the plug-in, or null (for the toolbar: no search field, or no toolbar). */
    static Layout layoutFor(Plugin plugin) {
        return plugin == null ? null : Layouts.find(plugin);
    }

    /** A layout that can't be built for this plug-in (an unknown parameter). */
    static final class LayoutError extends RuntimeException {
        LayoutError(String message) {
            super(message);
        }
    }

    /** A theme colour by name (as {@code themed}'s theme has it), or "#rrggbb[aa]"; null (logged once) when unknown. */
    static eeY colourOf(dve themed, String name, String source, String where) {
        eeY c = null;
        if (name.startsWith("#") && (name.length() == 7 || name.length() == 9)) {
            try {
                long v = Long.parseLong(name.substring(1), 16);
                int r, g, b, a = 255;
                if (name.length() == 9) { a = (int) (v & 0xFF); v >>= 8; }
                r = (int) (v >> 16 & 0xFF); g = (int) (v >> 8 & 0xFF); b = (int) (v & 0xFF);
                c = eeY.cfM("plugin-panels " + name, iok.cfM(r, g, b).cfM(a / 255F)); // as a theme-style colour, with its alpha
            } catch (NumberFormatException e) {
                c = null;
            }
        } else {
            c = themed.jl_().BAa().cfM(name); // the theme's colours, by name
        }
        if (c == null) report("colour:" + name, source + ": unknown colour '" + name + "' (" + where + "); left out", null);
        return c;
    }

    // ---------- Resolving parameters ----------

    private static final class Resolver {
        final List<uUk> params;
        final Set<uUk> used = new HashSet<>();

        Resolver(List<uUk> params) {
            this.params = params;
        }

        /**
         * The parameter an explicit reference names; "a|b" tries each alternative in turn (a name that
         * differs between formats or versions). Throws LayoutError when none matches.
         */
        uUk named(String ref) {
            if (ref.indexOf('|') < 0) return one(ref);
            for (String alternative : ref.split("\\|")) {
                if (alternative.isBlank()) continue;
                try {
                    return one(alternative.trim());
                } catch (LayoutError e) {
                    // try the next alternative
                }
            }
            throw new LayoutError("unknown parameter '" + ref + "'");
        }

        private uUk one(String ref) {
            if (ref.startsWith("#")) {
                int n;
                try {
                    n = Integer.parseInt(ref.substring(1).trim());
                } catch (NumberFormatException e) {
                    throw new LayoutError("bad parameter position '" + ref + "'");
                }
                if (n < 1 || n > params.size()) throw new LayoutError("no parameter " + ref + " (the plug-in has " + params.size() + ")");
                return params.get(n - 1);
            }
            String id = ref.regionMatches(true, 0, "id:", 0, 3) ? ref.substring(3).trim() : ref;
            for (uUk p : params) if (Plugin.paramId(p).equalsIgnoreCase(id)) return p;
            for (uUk p : params) if (Plugin.paramName(p).trim().equalsIgnoreCase(ref)) return p;
            String key = loose(ref); // then ignoring case, spaces, underscores and hyphens ("PreDelay" = "Pre Delay")
            if (!key.isEmpty()) for (uUk p : params) if (loose(Plugin.paramName(p)).equals(key)) return p;
            throw new LayoutError("unknown parameter '" + ref + "'");
        }

        private static String loose(String name) {
            StringBuilder b = new StringBuilder(name.length());
            for (char c : name.toCharArray()) if (c != ' ' && c != '_' && c != '-') b.append(Character.toLowerCase(c));
            return b.toString();
        }

        /** The next unused parameter of a kind ("auto:knob", "auto:list", "auto:toggle"), or null. */
        uUk auto(String ref) {
            for (uUk p : params) {
                if (used.contains(p)) continue;
                int steps = Plugin.steps(p);
                boolean fits = switch (ref.toLowerCase()) {
                    case "auto:knob" -> steps == 0;
                    case "auto:list" -> steps >= 3 && Plugin.stepNames(p).length >= 3;
                    case "auto:toggle" -> steps == 2;
                    default -> throw new LayoutError("unknown automatic reference '" + ref + "' (auto:knob, auto:list, auto:toggle)");
                };
                if (fits) return p;
            }
            return null;
        }
    }

    private static boolean isAuto(String ref) {
        return ref.regionMatches(true, 0, "auto:", 0, 5);
    }


    // ---------- Building ----------

    private static final class Builder {
        final OFG body;          // Bitwig's plug-in body: the theme comes from it
        final Plugin plugin;
        final Layout layout;
        final Map<Item, uUk> resolved = new IdentityHashMap<>();
        /** An envelope's parameters, one per segment; null where the plug-in doesn't have it. */
        final Map<Item, uUk[]> envelopes = new IdentityHashMap<>();
        /** The row being built and its knob aligner (null outside rows, or when the row doesn't align knobs). */
        private Item row;
        private RowAligner aligner;
        /** The width of a knob cell here (layout units): its column's or its share of a row's fixed width; 0: its content's. */
        private long cellWidth;
        /** The layout's mode colours, or null. */
        private ModeColours modes;
        /** The panel's height fit (and width keeper). */
        HeightFit fit;
        /** Per page: its row of columns and the strut that makes it as tall as the body has room for ({@code fill_height}). */
        private final List<HeightFit.FillRow> fillRows = new ArrayList<>();
        /** Where Bitwig's container button goes when the layout has no toolbar row (at the end of the title line), else null. */
        dWb container;

        Builder(OFG body, Plugin plugin, Layout layout) {
            this.body = body;
            this.plugin = plugin;
            this.layout = layout;
        }

        /**
         * The layout's page: the title line (the plug-in's name and, with several pages, the page
         * chooser) over the columns, in Bitwig's scaled container so {@link HeightFit} can zoom it
         * down to the chain's height.
         */
        dve build() {
            resolve();
            dWb page = dWb.qFI(); // vertical
            page.TMF((int) PAGE_GAP); // spacing
            List<Layouts.Page> pages = layout.pages();
            PageValue pageValue = pages.size() > 1 ? new PageValue(pages.stream().map(Layouts.Page::name).toList()) : null;
            dWb top = dWb.ELS(); // horizontal: name, chooser
            top.TMF((int) PAD * 2);
            top.UNj(0.5f); // centred on one line
            if (layout.title()) top.HL(text(plugin.name(), layout.texts().title(), "title")); // the panel's header: the plug-in's name
            if (pageValue != null) top.HL(chooser(pageValue));
            if (!layout.toolbar()) { // no toolbar row: Bitwig's container button moves to the right end of this line (PanelHooks)
                top.cfM(Filler.create(), 1f); // takes the line's spare width, so the button sits at the right
                container = dWb.ELS();
                top.HL(container);
            }
            if (layout.title() || pageValue != null || !layout.toolbar()) page.HL(top);
            dve[] views = new dve[pages.size()];
            // every page built now, in one tree; only the shown one visible (as adoxe (Daniil)'s plug-in cards switch their local pages)
            for (int i = 0; i < views.length; i++) {
                views[i] = safePage(pages, i);
                views[i].Dah(i == 0);
                page.HL(views[i]);
            }
            dtu scaled = LayoutScale.create();            // Bitwig's scaled container (as zoomable device views use it)
            long[] pad = padding();
            dnF padded;
            if (layout.stripe() != null) { // the accent stripe along the top edge, in the top padding
                padded = ModeColours.Striped.around(page, pad, stripeColour(), layout.stripe().height());
                if (applies("stripe")) modes.repaints(padded);
            } else {
                padded = dnF.cfM((int) pad[0], (int) pad[1], (int) pad[2], (int) pad[3]); // left, right, top, bottom
                padded.cfM((dyC) page);
            }
            scaled.cfM((dyC) padded);                     // its one child: the padded page
            fit = new HeightFit(body, scaled, layout.source());
            if (layout.fillHeight() && !fillRows.isEmpty()) // the shown page's row of columns down to the bottom
                fit.fill(() -> fillRows.get(pageValue == null ? 0 : Math.min(pageValue.page(), fillRows.size() - 1)));
            page.HL(fit.probe());                         // starts fitting once the page is painted
            if (pageValue != null) pageValue.addListener(event -> { // another page: show it, and fit again
                for (int i = 0; i < views.length; i++) views[i].Dah(i == pageValue.page());
                fit.again();
            });
            return scaled;
        }

        /** Whether a column has spacers among its items (they then take its spare height, not valign). */
        private static boolean spacers(Column column) {
            return column.items().stream().anyMatch(i -> i.kind().equals("spacer"));
        }

        /** The stripe's colour: the layout's, else the theme's knob value colour; in the mode colour when the layout says so. */
        private eeY stripeColour() {
            String c = layout.stripe().colour();
            eeY own = c.isEmpty() ? themeColour("Knob Value Color") : colour(c, "stripe");
            return tinted("stripe", own, 1);
        }

        /** Whether the layout's mode colours show on {@code target}. */
        private boolean applies(String target) {
            return modes != null && layout.modes().applies(target);
        }

        /** {@code own}, or a colour that follows the mode when the mode colours show on {@code target}. */
        private eeY tinted(String target, eeY own, double amount) {
            return applies(target) ? modes.new Colour(target, own, amount) : own;
        }

        /**
         * The layout's mode colours: the parameter, its modes' names (the choices of a dropdown on it, else its
         * step names) and each mode's colour. A parameter the plug-in lacks fails the layout, unless
         * {@code min_resolved} is under 100 (then no mode colours, logged).
         */
        private ModeColours modeColours(Resolver resolver, List<Item> all) {
            Layouts.Modes spec = layout.modes();
            if (spec == null) return null;
            uUk p;
            try {
                p = resolver.named(spec.param());
            } catch (LayoutError e) {
                if (layout.minResolved() >= 100) throw new LayoutError("mode_colors: " + e.getMessage());
                report("modes:" + layout.source() + ":" + plugin.name(), layout.source() + ": " + plugin.name() + " has no '" + spec.param() + "'; no mode colours", null);
                return null;
            }
            List<Layouts.Choice> names = List.of();
            for (Item item : all) {
                if (item.kind().equals("dropdown") && resolved.get(item) == p && !item.choices().isEmpty()) {
                    names = item.choices(); // the first dropdown with choices on the parameter
                    break;
                }
            }
            List<String> known = new ArrayList<>();
            if (!names.isEmpty()) for (Layouts.Choice c : names) known.add(c.name().trim().toLowerCase(java.util.Locale.ROOT));
            else for (String s : Plugin.stepNames(p)) known.add(s.trim().toLowerCase(java.util.Locale.ROOT));
            if (known.isEmpty()) throw new LayoutError("mode_colors: '" + spec.param() + "' has no dropdown with choices and no step names to name its modes");
            Map<String, Map<String, iok>> colours = new HashMap<>();
            List<String> unknown = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> e : spec.colours().entrySet()) {
                String key = e.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!known.contains(key)) unknown.add(e.getKey());
                Map<String, iok> byTarget = new HashMap<>();
                for (Map.Entry<String, String> t : e.getValue().entrySet()) {
                    eeY c = colour(t.getValue(), "mode_colors " + e.getKey() + (t.getKey().isEmpty() ? "" : " " + t.getKey())); // logged when unknown
                    if (c != null) byTarget.put(t.getKey(), c.cfM(body)); // as the theme shows it now
                }
                colours.put(key, byTarget);
            }
            if (!unknown.isEmpty())
                report("modes-unknown:" + layout.source() + ":" + plugin.name(), layout.source() + ": mode_colors " + unknown + " are not modes of '" + spec.param() + "'; never shown", null);
            return new ModeColours(p, names, colours, spec.mix());
        }

        /** The knob's value arc in the mode colour (its style otherwise the knob's own). */
        private void tintArc(atu knob, Item item) {
            if (!applies("knob_arc")) return;
            efU base = item.knobStyle().isEmpty() ? body.jl_().Mph().IwV() : body.jl_().Mph().cfM(item.knobStyle()); // the theme's default style, or the item's
            if (base == null) return;
            eeY own = knob.FCz() != null ? knob.FCz() : base.ODA(); // the arc's own colour: the knob's, else the style's value_color
            eeY arc = modes.new Colour("knob_arc", own, 1);
            knob.MRY((efU) new ModeColours.KnobStyle(base, arc)); // the arc when the style uses its value_color
            knob.IwV(arc); // the arc otherwise: the knob's own colour (dNv.JqA)
            modes.repaints(knob);
        }

        private long[] padding() {
            long[] p = layout.sizes().padding();
            return p != null ? p : new long[] { PAD, PAD, PAD, PAD };
        }

        /**
         * The panel's width when every column of every page has a fixed width: padding, columns and the
         * gaps between them (a divider is drawn inside its gap; the widest page). 0 when a column takes
         * its content's width.
         */
        long fixedWidth() {
            long widest = 0;
            long gap = layout.gap() >= 0 ? layout.gap() : PAD;
            for (Layouts.Page page : layout.pages()) {
                long w = 0;
                for (Column column : page.columns()) {
                    if (column.width() <= 0) return 0;
                    w += column.width();
                }
                w += gap * Math.max(0, page.columns().size() - 1);
                widest = Math.max(widest, w);
            }
            long[] pad = padding();
            return widest + pad[0] + pad[1];
        }

        /** Bitwig's own widgets to choose the page: a row of toggle buttons (tabs), or a dropdown. */
        private dve chooser(PageValue pages) {
            if (layout.chooser().equals("dropdown")) {
                PageValue.Name[] names = pages.namesForDropdown();
                dBX menu = dBX.cfM(pages.dropdown(names), names); // Bitwig's dropdown
                menu.MRY(kn.cfM());
                return menu;
            }
            dWb tabs = dWb.ELS();
            tabs.TMF((int) TAB_GAP);
            List<Layouts.Page> all = layout.pages();
            for (int i = 0; i < all.size(); i++) {
                dAd tab = dAd.MRY(pages.tab(i)); // Bitwig's toggle button, lit for the shown page
                tab.IwV(all.get(i).name());
                tab.MRY(w3.MRY);
                tabs.HL(tab);
            }
            return tabs;
        }

        /** A page's widget; never throws into Bitwig (an empty group and a log line instead). */
        private dve safePage(List<Layouts.Page> pages, int i) {
            try {
                return pageView(pages.get(i));
            } catch (LayoutError e) {
                throw e;
            } catch (Throwable t) {
                report("page:" + layout.source(), layout.source() + ": building a page failed", t);
                return dWb.qFI();
            }
        }

        /** A page: its columns, and under them its footer row. */
        private dve pageView(Layouts.Page page) {
            dve columns = columns(page);
            if (page.footer().isEmpty()) return columns;
            dWb view = dWb.qFI(); // vertical
            view.HL(columns);
            if (layout.sizes().footerGap() > 0) view.HL(Spacer.create(1, layout.sizes().footerGap()));
            dWb footer = dWb.ELS(); // horizontal
            footer.TMF((int) (layout.gap() >= 0 ? layout.gap() : PAD));
            footer.UNj(0.5f); // its items centred on one line
            long outer = cellWidth;
            cellWidth = 0;
            try {
                for (Item item : page.footer()) add(footer, item, true, "footer");
            } finally {
                cellWidth = outer;
            }
            view.HL(footer);
            return view;
        }

        private dve columns(Layouts.Page shown) {
            dWb columns = dWb.ELS(); // horizontal; the gaps are spacers, a divider is drawn in the middle of its gap
            columns.TMF(0);
            columns.TtY(true); // a divider runs the full height
            long gap = layout.gap() >= 0 ? layout.gap() : PAD;
            boolean first = true;
            boolean headers = shown.columns().stream().anyMatch(c -> !c.header().isEmpty()); // then every column has a header line
            int index = 0;
            for (Column column : shown.columns()) {
                index++;
                if (!first) {
                    if (column.divider() || layout.dividers()) {
                        long before = Math.max(0, (gap - DIVIDER) / 2);
                        columns.HL(Spacer.create(before, 1));
                        columns.HL(divider());
                        columns.HL(Spacer.create(Math.max(0, gap - DIVIDER - before), 1));
                    } else {
                        columns.HL(Spacer.create(gap, 1));
                    }
                }
                first = false;
                dWb items = dWb.qFI(); // the column's items; its gap is between items, not under the header
                items.TMF((int) (column.place().gap() >= 0 ? column.place().gap() : COLUMN_GAP));
                if (column.place().align() >= 0) items.UNj(column.place().align()); // the column's items, left / centre / right
                else if (column.width() > 0) items.UNj(0.5f); // a fixed column centres its items
                long inner = column.width() > 0 ? Math.max(1, column.width() - column.place().across()) : 0; // its padding is inside its width
                if (inner > 0) items.a_(FreeSize.exactly(inner)); // exactly its width, whatever it shows
                dWb col = items;
                if (headers) {
                    col = dWb.qFI();
                    col.TMF(0);
                    // an empty header still takes its line, so the labels under it line up across columns
                    duY header = text(column.header().isEmpty() ? " " : column.header(), column.headerText().or(layout.texts().header()), "header");
                    if (applies("header")) {
                        String own = column.headerText().or(layout.texts().header()).colour();
                        header.Hnt((ejt) tinted("header", own.isEmpty() ? null : colour(own, "header"), 1)); // the header's text colour, following the mode
                        modes.repaints(header);
                    }
                    col.HL(fixLine(header, inner, layout.sizes().headerHeight()));
                    if (spacers(column)) col.cfM(items, 1f); // the items take the column's spare height: their spacers share it
                    else col.HL(items);
                }
                long outer = cellWidth;
                cellWidth = inner;
                try {
                    for (Item item : column.items()) add(items, item, false, "column " + index);
                } finally {
                    cellWidth = outer;
                }
                dve full = col;
                if (layout.fillHeight() && !spacers(column)) { // the column as tall as its row: the spare height above, below or around its content (valign)
                    dWb tall = dWb.qFI(); // vertical
                    tall.TMF(0);
                    float v = column.place().valign();
                    if (v >= 0.25f) tall.cfM(Filler.create(), 1f);      // center, bottom: room above
                    tall.HL(col);
                    if (v < 0.75f) tall.cfM(Filler.create(), 1f);       // top (default), center: room below
                    full = tall;
                }
                columns.HL(background(padded(full, column.place().padding()), column.background(), "column " + column.header()));
            }
            if (layout.fillHeight()) { // a strut no wider than nothing: the row as tall as HeightFit sets it, the columns stretched to it
                Spacer strut = Spacer.create(0, 0);
                columns.HL(strut);
                fillRows.add(new HeightFit.FillRow(columns, strut));
            }
            return columns;
        }

        /**
         * A text line of a fixed size: {@code width} and {@code height} in layout units (0: the text's own),
         * with the text centred in it. The line is a padding widget of that size around the label: a padding
         * places its child by the child's own alignment (Bitwig's row and column groups don't), and the label
         * keeps its text's width. A fixed line never changes size with its text, so a new value never lays
         * the panel out again.
         */
        private static dve fixLine(dve label, long width, long height) {
            if (width <= 0 && height <= 0) return label;
            label.cfM(Dgc.lYc); // centred in its padding, both ways
            dnF line = dnF.cfM(0, 0, 0, 0); // a padding of nothing around the label
            line.cfM((dyC) label);
            if (width > 0) line.a_(FreeSize.exactly(width));
            if (height > 0) line.MRY(FreeSize.exactly(height));
            return line;
        }

        /** A column's or row's padding around it: [left, right, top, bottom] in layout units; none when null. */
        private static dve padded(dve widget, long[] padding) {
            if (padding == null) return widget;
            dnF box = dnF.cfM((int) padding[0], (int) padding[1], (int) padding[2], (int) padding[3]); // left, right, top, bottom
            box.cfM((dyC) widget);
            return box;
        }

        /** The layout's background on Bitwig's whole plug-in body, so it covers the toolbar too. */
        void bodyBackground() {
            if (layout.background().isEmpty()) return;
            eeY fill = colour(layout.background(), "layout background");
            if (fill == null) return;
            body.UNj(tinted("background_tint", fill, BACKGROUND_TINT)); // the body is a padding widget: filled with the colour (tinted by the mode)
            if (applies("background_tint")) modes.repaints(body);
        }

        /** Explicit references first, so automatic ones skip what the layout already shows. */
        private void resolve() {
            Resolver resolver = new Resolver(plugin.parameters());
            List<Item> all = new ArrayList<>();
            for (Layouts.Page page : layout.pages()) {
                for (Column column : page.columns()) flatten(column.items(), all);
                flatten(page.footer(), all);
            }
            int named = 0;
            List<String> missing = new ArrayList<>();
            List<String> drawnEmpty = new ArrayList<>(); // an envelope's missing references: it keeps its size, drawn empty
            for (Item item : all) {
                if (item.kind().equals("envelope")) {
                    List<Layouts.Segment> segments = item.envelope().segments();
                    uUk[] params = new uUk[segments.size()];
                    for (int i = 0; i < params.length; i++) {
                        named++;
                        try {
                            params[i] = resolver.named(segments.get(i).ref());
                        } catch (LayoutError e) {
                            if (layout.minResolved() >= 100) throw e; // every reference must resolve
                            drawnEmpty.add(segments.get(i).ref());
                            continue;
                        }
                        resolver.used.add(params[i]);
                    }
                    envelopes.put(item, params);
                    continue;
                }
                if (!controls(item) || isAuto(item.ref())) continue;
                named++;
                uUk p;
                try {
                    p = resolver.named(item.ref());
                } catch (LayoutError e) {
                    if (layout.minResolved() >= 100) throw e; // every reference must resolve
                    missing.add(item.ref());
                    continue;
                }
                resolver.used.add(p);
                resolved.put(item, p);
            }
            if (!missing.isEmpty() || !drawnEmpty.isEmpty()) {
                int found = named - missing.size() - drawnEmpty.size();
                if (found * 100 < layout.minResolved() * named) {
                    List<String> every = new ArrayList<>(missing);
                    every.addAll(drawnEmpty);
                    throw new LayoutError("only " + found + " of " + named + " parameters found (min_resolved " + layout.minResolved() + "%); missing " + every);
                }
                if (!missing.isEmpty())
                    report("missing:" + layout.source() + ":" + plugin.name(), layout.source() + ": " + plugin.name() + " has no " + missing + "; those items are left out", null);
                if (!drawnEmpty.isEmpty())
                    report("envelope-missing:" + layout.source() + ":" + plugin.name(), layout.source() + ": " + plugin.name() + " has no " + drawnEmpty + "; those envelopes are drawn empty", null);
            }
            for (Item item : all) {
                if (!controls(item) || !isAuto(item.ref())) continue;
                uUk p = resolver.auto(item.ref());
                if (p == null) {
                    report("auto:" + layout.source() + ":" + item.ref(), layout.source() + ": no parameter left for " + item.ref() + " in " + plugin.name() + "; item left out", null);
                    continue;
                }
                resolver.used.add(p);
                resolved.put(item, p);
            }
            modes = modeColours(resolver, all);
        }

        private void flatten(List<Item> items, List<Item> out) {
            for (Item item : items) {
                out.add(item);
                flatten(item.children(), out);
            }
        }

        private static boolean controls(Item item) {
            return !item.kind().equals("meter") && !item.kind().equals("row") && !item.kind().equals("text") && !item.kind().equals("envelope")
                    && !item.kind().equals("spacer");
        }

        /**
         * Adds an item to a column ({@code inRow} false) or a row. Bitwig's boxes place every child by
         * the box's own alignment, so an item with its own alignment across the box gets a box of its
         * own: one that fills the parent's width (or height) and places its single child there.
         */
        private void add(dWb parent, Item item, boolean inRow, String where) {
            if (item.kind().equals("spacer")) { // the parent's spare length (several share it; none: 0)
                parent.cfM(Filler.create(), 1f);
                return;
            }
            dve widget = widget(item, where);
            if (widget == null) return;
            dve placed = background(widget, item.background(), item.kind() + " " + item.ref());
            float across = inRow ? item.place().valign() : item.place().align();
            if (across >= 0 && !item.kind().equals("row")) {
                dWb own = inRow ? dWb.ELS() : dWb.qFI(); // across the parent's cross axis: vertical in a row, horizontal in a column
                own.UNj(across);  // its child: start / centre / end
                own.TtY(true);    // it takes the parent's full cross size
                own.HL(placed);
                parent.TtY(true); // the parent stretches it (other children only up to their own maximum)
                placed = own;
            }
            parent.HL(placed);
        }

        private dve widget(Item item, String where) {
            if (item.kind().equals("row")) {
                dWb line = dWb.ELS(); // horizontal
                long gap = item.place().gap() >= 0 ? item.place().gap() : PAD;
                line.TMF((int) gap);
                if (item.place().valign() >= 0) line.UNj(item.place().valign()); // its items: top / centre / bottom
                Item outerRow = row;
                RowAligner outerAligner = aligner;
                long outerCell = cellWidth;
                row = item;
                long outerWidth = item.width() > 0 ? item.width() : cellWidth;
                long rowWidth = outerWidth > 0 ? Math.max(1, outerWidth - item.place().across()) : 0; // its padding is inside its width
                int n = (int) item.children().stream().filter(c -> !c.kind().equals("spacer")).count(); // the cells (spacers take only what is left)
                if (rowWidth > 0) line.a_(FreeSize.exactly(rowWidth));
                cellWidth = rowWidth > 0 && n > 0 ? Math.max(1, (rowWidth - gap * (n - 1)) / n) : 0; // equal cells
                boolean knobs = item.children().stream().anyMatch(c -> c.kind().equals("knob"));
                // fixed-size knob cells line up by construction; the aligner is for knobs of natural size
                boolean fixedKnobs = item.children().stream().allMatch(c -> !c.kind().equals("knob") || c.knobPx() > 0);
                aligner = knobs && !fixedKnobs && item.place().alignKnobs() ? new RowAligner(where + ", row " + n) : null;
                try {
                    for (Item child : item.children()) add(line, child, true, where);
                    if (aligner != null && !aligner.isEmpty()) line.HL(aligner.probe()); // starts the aligner once painted
                } finally {
                    row = outerRow;
                    aligner = outerAligner;
                    cellWidth = outerCell;
                }
                return padded(line, item.place().padding());
            }
            if (item.kind().equals("meter")) return meter(item);
            if (item.kind().equals("envelope")) return envelope(item);
            if (item.kind().equals("text")) return text(item.ref(), item.labelText().or(layout.texts().label()), "text");
            uUk p = resolved.get(item);
            if (p == null) return null; // an automatic item with nothing left
            return switch (item.kind()) {
                case "knob" -> knob(p, item);
                case "dropdown" -> dropdown(p, item);
                case "toggle" -> toggle(p, item);
                case "value" -> value(p, item);
                case "slider" -> slider(p, item);
                default -> throw new LayoutError("unknown item kind " + item.kind());
            };
        }

        /** A background behind a widget: a theme colour by name, or "#rrggbb". Cosmetic: a bad colour is logged and left out. */
        private dve background(dve widget, String colour, String where) {
            if (colour.isEmpty()) return widget;
            eeY fill = colour(colour, where);
            if (fill == null) return widget;
            dnF box = dnF.Hnt(widget, (int) BACKGROUND_PAD); // a padding around it
            box.UNj(fill);                 // filled with the colour
            return box;
        }

        /** A theme colour by name, or "#rrggbb[aa]"; null (logged) when unknown. */
        private eeY colour(String name, String where) {
            return colourOf(body, name, layout.source(), where);
        }

        /** A text label in a text style. */
        private duY text(String text, Layouts.Text style, String where) {
            return style(duY.MIO(text), style, where);
        }

        /**
         * Applies a text style to a label: a theme font by name ("Bold"), optionally at another size
         * ("Bold 16": the theme font's typeface at size 16), and a text colour. Unknown names are
         * logged and Bitwig's defaults kept. The label's width is fixed at its preferred width (the
         * whole text): Bitwig's labels otherwise shrink to "Abc..." when their row is short of space.
         */
        private duY style(duY label, Layouts.Text style, String where) {
            label.a_(OpO.cfM()); // width: always the whole text
            if (!style.font().isEmpty()) {
                efu font = font(style.font(), where);
                if (font != null) label.cfM((ejE) font); // the label's font, as Bitwig's own styled labels set it
            }
            if (!style.colour().isEmpty()) {
                eeY colour = colour(style.colour(), where + " colour");
                if (colour != null) label.Hnt((ejt) colour); // the text colour, as Bitwig's own styled labels set it
            }
            return label;
        }

        /** A theme font by name, or "<name> <size>" for its typeface at that size; null (logged) when unknown. */
        private efu font(String spec, String where) {
            String name = spec;
            float size = 0;
            int space = spec.lastIndexOf(' ');
            if (space > 0) {
                try {
                    size = Float.parseFloat(spec.substring(space + 1));
                    name = spec.substring(0, space).trim();
                } catch (NumberFormatException e) {
                    size = 0; // the whole spec is a name ("Title large")
                }
            }
            efu base = body.jl_().Ai().cfM(name); // the theme's font table, by name
            if (base == null) {
                report("font:" + spec, layout.source() + ": unknown font '" + name + "' (" + where + "); Bitwig's default used", null);
                return null;
            }
            if (size <= 0) return base;
            if (size > 200) {
                report("font-size:" + spec, layout.source() + ": font size " + size + " too large (" + where + "); " + name + " used", null);
                return base;
            }
            return efu.cfM("plugin-panels " + name + " " + size, base.lYc(), size); // the theme font's typeface at that size
        }

        /**
         * The value text, kept up to date, in the item's or the layout's value style: the plug-in's
         * own text, or the layout's format of the value (display only).
         */
        private duY valueText(uUk p, Item item) {
            LJt text = !item.format().kind().isEmpty() ? new FormattedValue(p, item.format()) // the layout's format
                    : refresh(item).equals("settle") ? new SettledText(p) // the plug-in's text, asked again after a change
                    : (LJt) p.ODA();                                  // the plug-in's display text
            return style(duY.FzB(text), item.valueText().or(layout.texts().value()), "value");
        }

        /** The item's label: an icon of Bitwig's (by reference), else its text or the parameter's name. */
        private dve label(Item item, uUk p) {
            String text = item.label().isEmpty() ? Plugin.paramName(p) : item.label();
            if (item.icon().isEmpty()) return text(text, item.labelText().or(layout.texts().label()), "label");
            dAd icon = dAd.gG(); // a button used as an icon: no action
            icon.cfM((elA) body.jl_().znC().cfM("Borderless")); // Bitwig's borderless style, as its device toolbar
            icon.TMF(iconPath(item.icon())); // Bitwig's icon file, under its icons folder
            icon.z3I(text);                  // the tooltip: the label's text
            long size = item.iconSize() > 0 ? item.iconSize() : ICON_SIZE;
            icon.a_(new Sxr(size));
            icon.MRY(new Sxr(size));
            return icon;
        }

        /** Bitwig's separator line between columns, in the theme's separator colour (as its tab bar draws one). */
        private dve divider() {
            dWh line = dWh.FzB(1); // Bitwig's line widget, 1 unit thick
            if (applies("divider")) {
                line.cfM(tinted("divider", body.PEn().QOl(), 1)); // the separator colour, following the mode
                modes.repaints(line);
            } else {
                line.MRY(body.PEn().QOl().cfM(body)); // the theme's separator colour
            }
            return line;
        }

        /** An item's own width and height (layout units, exactly), when the layout gives them. */
        private static void size(dve widget, Item item) {
            if (item.width() > 0) widget.a_(FreeSize.exactly(item.width()));
            if (item.height() > 0) widget.MRY(FreeSize.exactly(item.height()));
        }

        /** Bitwig's icon name: the path under its icons folder ("icons/device/stereo.svg" -> "device/stereo.svg"). */
        private static String iconPath(String path) {
            String p = path.replace('\\', '/');
            return p.startsWith("icons/") ? p.substring("icons/".length()) : p;
        }

        /**
         * Bitwig's plug-in knob, with its label above and the plug-in's value text below. In a row that
         * aligns its knobs, spacers around label and knob let the {@link RowAligner} line them up.
         */
        private dve knob(uUk p, Item item) {
            if (item.knobPx() > 0) return cell(p, item);
            atr knob = atr.Vr(); // Bitwig's plug-in parameter knob
            knob.Hnt(p);          // bound to the parameter (drag, automation, modulation, menu)
            knob.SQN().z3I(new ResetToDefault(p)); // double-click: the plug-in's default
            typedValues(knob.SQN(), p, item);
            if (!item.size().isEmpty()) knob.SQN().MRY(item.size()); // the knob's theme size
            if (!item.knobStyle().isEmpty()) {
                if (body.jl_().Mph().cfM(item.knobStyle()) == null) // the theme's knob styles, by name
                    report("knob-style:" + item.knobStyle(), layout.source() + ": unknown knob style '" + item.knobStyle() + "'; Bitwig's default used", null);
                else knob.SQN().cfM(item.knobStyle()); // the knob's theme style
            }
            tintArc(knob.SQN(), item);
            knob.a_(kn.cfM());   // its natural size
            knob.MRY(kn.cfM());
            size(knob, item);
            dve label = label(item, p);
            duY value = valueText(p, item);
            dWb box = dWb.qFI();
            box.UNj(0.5f);        // centred
            if (aligner == null) {
                box.TMF((int) LINE_GAP);
                box.HL(label);
                box.HL(knob);
                box.HL(value);
                return box;
            }
            Spacer top = Spacer.create(0, 0), middle = Spacer.create(0, 1), bottom = Spacer.create(0, 0);
            box.HL(top);
            box.HL(label);
            box.HL(middle);
            box.HL(knob);
            box.HL(bottom);
            box.HL(value);
            aligner.add(item.label().isEmpty() ? Plugin.paramName(p) : item.label(), label, knob, value, top, middle, bottom);
            return box;
        }

        /**
         * A knob cell of fixed size (the cell design of adoxe (Daniil)'s "Design: Device Panels" plug-in cards,
         * adapted): its label, Bitwig's bare knob at exactly {@code size} layout units,
         * and the value text, each line the cell's width ({@link #cellWidth}, or the item's {@code width})
         * with its text centred. Nothing in it takes its size from its text, so a changing value never
         * lays the panel out again. The knob is Bitwig's plug-in knob, as in its own parameter list, so
         * typed values (Ctrl-click) take Bitwig's own path; a double-click resets the parameter to the
         * plug-in's default.
         */
        private dve cell(uUk p, Item item) {
            long px = item.knobPx();
            long width = item.width() > 0 ? item.width() : cellWidth;
            // Bitwig's plug-in knob, as its own parameter list builds every row (the rows are this knob): its bare knob
            // inside, set up and bound by Bitwig, with Bitwig's own typed-value path (Ctrl-click). Only the size changes.
            atr holder = atr.Vr();
            atu knob = holder.SQN(); // the bare knob inside
            knob.MRY(KnobGeometry.of(body.jl_(), px, knob.ELS())); // its geometry at exactly that size
            knob.a_(FreeSize.exactly(px));
            knob.MRY(FreeSize.exactly(px));
            holder.a_(FreeSize.exactly(px));
            holder.MRY(FreeSize.exactly(px));
            knob.z3I(new ResetToDefault(p)); // double-click: the plug-in's default
            holder.Hnt(p);             // bound to the parameter, as Bitwig binds its list's rows
            typedValues(knob, p, item);
            if (!item.knobStyle().isEmpty()) {
                if (body.jl_().Mph().cfM(item.knobStyle()) == null) // the theme's knob styles, by name
                    report("knob-style:" + item.knobStyle(), layout.source() + ": unknown knob style '" + item.knobStyle() + "'; Bitwig's default used", null);
                else knob.cfM(item.knobStyle()); // the knob's theme style
            }
            tintArc(knob, item);
            dWb box = dWb.qFI();
            box.UNj(0.5f); // centred
            box.TMF((int) LINE_GAP);
            if (width > 0) box.a_(FreeSize.exactly(width));
            if (!item.hideLabel()) {
                dve label = label(item, p);
                box.HL(label instanceof duY ? fixLine(label, width, layout.sizes().labelHeight()) : label);
            }
            box.HL(holder);
            box.HL(fixLine(valueText(p, item), width, layout.sizes().valueHeight()));
            return box;
        }

        /**
         * Typed values (Ctrl-click) through {@link TypedEntry}: binding gave the knob Bitwig's own converter,
         * which this replaces (the knob keeps it while it shows the same parameter).
         */
        private void typedValues(atu knob, uUk p, Item item) {
            String typing = typing(item);
            if (typing.equals("plugin")) return; // Bitwig's own converter: the plug-in's text-to-value
            if (typing.equals("mapping") && item.values() == null)
                throw new LayoutError("'" + item.ref() + "': \"typing\": \"mapping\" needs \"values\"");
            try {
                if (p instanceof com.bitwig.flt.document.core.master.device.dKO stored)
                    knob.FzB(new TypedEntry(p, stored, typing.equals("mapping") ? item.values() : null)); // the knob's converter
            } catch (Throwable t) {
                report("typed-entry", "typed values for " + Plugin.paramName(p) + " left to Bitwig", t);
            }
        }

        /** The control's value-text refresh: its own, else the layout's, else none. */
        private String refresh(Item item) {
            return !item.refresh().isEmpty() ? item.refresh() : !layout.refresh().isEmpty() ? layout.refresh() : "none";
        }

        /** The control's typing: its own, else the layout's, else the mapping when it has one, else the plug-in's. */
        private String typing(Item item) {
            return !item.typing().isEmpty() ? item.typing() : !layout.typing().isEmpty() ? layout.typing()
                    : item.values() != null ? "mapping" : "plugin";
        }

        /**
         * Bitwig's dropdown on a parameter: the layout's presets ({@code choices}), else the
         * parameter's step names, else (a stepped parameter without names) the plug-in's own text for
         * each step.
         */
        private dve dropdown(uUk p, Item item) {
            String[] names = Plugin.stepNames(p);
            dBX[] menu = new dBX[1];
            StepChoice choice;
            if (!item.choices().isEmpty()) choice = StepChoice.presets(p, item.choices());
            else if (names.length >= 2) choice = StepChoice.steps(p, names);
            else if (Plugin.steps(p) >= 2) choice = StepChoice.pluginTexts(p, Plugin.steps(p), () -> {
                if (menu[0] != null) menu[0].jbi(); // a name arrived: redraw
            });
            else throw new LayoutError("'" + Plugin.paramName(p) + "' is continuous: a dropdown on it needs \"choices\"");
            menu[0] = dBX.cfM(choice, choice.items()); // Bitwig's dropdown bound to the choice
            menu[0].MRY(kn.cfM()); // its natural height, as Bitwig's own dropdown widget sets it
            if (!item.style().isEmpty()) {
                egC style = body.jl_().kTh().cfM(item.style()); // the theme's dropdown (popup chooser) styles, by name
                if (style == null) report("dropdown-style:" + item.style(), layout.source() + ": unknown dropdown style '" + item.style() + "'; Bitwig's default used", null);
                else menu[0].Hnt(style);
            }
            return dropdownBox(menu[0], item, p);
        }

        private dve dropdownBox(dBX menu, Item item, uUk p) {
            size(menu, item); // a fixed width and height: a longer name never resizes it
            if (item.hideLabel()) return menu;
            dWb box = dWb.qFI();
            box.TMF((int) LINE_GAP);
            box.HL(label(item, p));
            box.HL(menu);
            return box;
        }

        /** Bitwig's text toggle button, lit while the parameter is on (any parameter: on at 0.5 and above). */
        private dve toggle(uUk p, Item item) {
            dAd button = dAd.MRY(new ToggleValue(p)); // Bitwig's toggle button on an on/off value (lit = on), as the Track Inspector's
            button.IwV(item.label().isEmpty() ? Plugin.paramName(p) : item.label()); // its text
            if (!item.icon().isEmpty()) button.TMF(iconPath(item.icon()));            // or an icon of Bitwig's
            button.MRY(w3.MRY); // Bitwig's row height
            if (!item.style().isEmpty()) {
                egY style = body.jl_().znC().cfM(item.style()); // the theme's button styles, by name
                if (style == null) report("button-style:" + item.style(), layout.source() + ": unknown button style '" + item.style() + "'; Bitwig's default used", null);
                else button.cfM((elA) style);
            }
            size(button, item);
            return button;
        }

        /** The label and the plug-in's value text. */
        private dve value(uUk p, Item item) {
            dWb box = dWb.qFI();
            box.TMF((int) LINE_GAP);
            if (!item.hideLabel()) box.HL(label(item, p));
            duY value = valueText(p, item);
            size(value, item);
            box.HL(value);
            return box;
        }

        /** Bitwig's own parameter row (name, slider, value), as in its generic list. */
        private dve slider(uUk p, Item item) {
            aoW row = aoW.wGc();
            row.Hnt(p);
            size(row, item);
            return row;
        }

        private dve meter(Item item) {
            if (!item.ref().equalsIgnoreCase("output")) throw new LayoutError("unknown meter source '" + item.ref() + "' (output)");
            Xth level = outputMeter();
            if (level == null) throw new LayoutError("the device has no output meter");
            String style = item.style().isEmpty() ? "needle" : item.style().toLowerCase();
            switch (style) {
                case "bar": {
                    avo bar = avo.cfM(false, true); // Bitwig's VU meter
                    bar.Hnt((ljb) level); // bound to the meter
                    bar.FzB((int) BAR_WIDTH);
                    bar.Hnt(0);
                    bar.a_(new Sxr(BAR_WIDTH));
                    bar.MRY(new Sxr(BAR_HEIGHT));
                    size(bar, item);
                    return bar;
                }
                case "level": { // Bitwig's meter widget (as the mini plug-in device's gain reduction), one per channel
                    dWb pair = dWb.ELS();
                    pair.TMF((int) LINE_GAP);
                    int channels = 0;
                    for (Object o : level.FzB()) { // the meter's values: left, right, ...
                        if (!(o instanceof tft channel) || channels++ >= 2) continue;
                        aEJ m = aEJ.Ucl();
                        m.cfM(aEM.cfM); // as Bitwig sets it
                        m.a_(new Sxr(LEVEL_WIDTH));
                        m.MRY(new Sxr(BAR_HEIGHT));
                        dns.cfM(m.emh(), channel.FzB()); // its value: the channel's RMS
                        pair.HL(m);
                    }
                    return pair;
                }
                case "needle":
                    return NeedleMeter.create(level, item.width() > 0 ? item.width() : NEEDLE_WIDTH,
                            item.height() > 0 ? item.height() : NEEDLE_HEIGHT);
                default:
                    throw new LayoutError("unknown meter style '" + item.style() + "' (needle, bar, level)");
            }
        }

        /**
         * An envelope drawn from its parameters ({@link EnvelopeDisplay}), at the item's size (default 120 x 50),
         * in the theme's display colours unless the layout names others.
         */
        private dve envelope(Item item) {
            Layouts.Envelope shape = item.envelope();
            String line = item.labelText().colour(), fill = shape.fill(), display = shape.display();
            eeY lineColour = line.isEmpty() ? themeColour("Display Waveform") : colour(line, "envelope color");
            eeY fillColour = fill.isEmpty() ? null : colour(fill, "envelope fill_color"); // null: the line colour, faint
            eeY displayColour = display.equalsIgnoreCase("none") ? null
                    : display.isEmpty() ? themeColour("Display Background") : colour(display, "envelope display_color");
            EnvelopeDisplay drawn = EnvelopeDisplay.create(shape, envelopes.get(item), tinted("envelope", lineColour, 1), fillColour, displayColour,
                    item.width() > 0 ? item.width() : ENVELOPE_WIDTH, item.height() > 0 ? item.height() : ENVELOPE_HEIGHT);
            if (applies("envelope")) modes.repaints(drawn);
            return drawn;
        }

        /** A theme colour by name, or null (not logged: the caller has a fallback). */
        private eeY themeColour(String name) {
            return body.jl_().BAa().cfM(name);
        }

        /** The output level meter of the plug-in's device. */
        private Xth outputMeter() {
            Object device = plugin.contents.IwV(jsd.class); // the device holding the contents
            if (!(device instanceof FDA stored)) return null;
            var meter = stored.rLP(); // the device's output meter
            return meter == null ? null : meter.ODA(); // its level
        }
    }
}
