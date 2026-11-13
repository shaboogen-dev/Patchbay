package bml.pluginpanels.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Panel files: the panels chosen in Patchbay ({@link Components#panels()}), read again when a file's
 * size or time changes, so an edited file applies the next time Bitwig builds the plug-in's page
 * (reload the plug-in). A file holds one layout object, or {@code {"layouts": [...]}}, and may name
 * the panel ({@code "name"}, for Patchbay's list). A file that can't be read is skipped and logged;
 * the others still work. The format is described in the framework's README.
 */
final class Layouts {
    static final String[] KINDS = { "knob", "dropdown", "toggle", "value", "slider", "meter", "text", "row", "envelope", "spacer" };
    static final List<String> KNOB_SIZES = List.of("tiny", "small", "normal", "large", "huge");

    /**
     * Placement: {@code align} / {@code valign} place an item in the space its column or row gives it,
     * or on a column / row all its items (-1: Bitwig's default). On a row, {@code gap} is the space
     * between its items and {@code alignKnobs} lines its knobs up (labels on one line, knob centres
     * on one line). {@code padding}: on a column or row, the space inside its edges [left, right, top,
     * bottom], inside its {@code width}; null when absent (none).
     */
    record Place(float align, float valign, long gap, boolean alignKnobs, long[] padding) {
        /** Left plus right padding. */
        long across() {
            return padding == null ? 0 : padding[0] + padding[1];
        }
    }

    /** Text style: a theme font ("Bold", or "Bold 16" for that font at size 16) and a colour (a theme colour name or #rrggbb). */
    record Text(String font, String colour) {
        static final Text NONE = new Text("", "");

        Text or(Text fallback) {
            return new Text(font.isEmpty() ? fallback.font : font, colour.isEmpty() ? fallback.colour : colour);
        }
    }

    /**
     * One element: a control ({@code ref} names its parameter, or the meter's source), a text
     * ({@code ref} is the text) or a row ({@code children} side by side).
     */
    /**
     * How a value is shown (display only: the plug-in keeps its own values). Kind "" is the plug-in's
     * own text; "percent"; "range" (the normalised value mapped to lo..hi, with decimals and a unit);
     * "map" (the text of the nearest listed value).
     */
    record Format(String kind, double lo, double hi, int decimals, String unit, List<Choice> map) {
        static final Format PLUGIN = new Format("", 0, 1, 0, "", List.of());
    }

    /** A named value (normalised 0..1): a preset in a dropdown, or an entry of a value map. */
    record Choice(String name, double value) {
    }

    /**
     * {@code size} is a theme knob size ("small", ...) or, as {@code knobPx}, an exact knob size in layout
     * units (0: not given). {@code hideLabel}: {@code "label": false}, no label at all.
     */
    /**
     * {@code values}: the control's value mapping, or null ({@link ValueMap}). {@code refresh} ("settle",
     * "none") and {@code typing} ("mapping", "plugin", "normalised"): the control's own, or "" for the
     * layout's.
     */
    record Item(String kind, String ref, String label, boolean hideLabel, String icon, long iconSize, String style,
                String size, long knobPx, String knobStyle, Text labelText, Text valueText, Format format,
                List<Choice> choices, ValueMap values, String refresh, String typing, long width, long height,
                String background, Place place, List<Item> children, Envelope envelope) {
    }

    /** An envelope's keys, in the order its segments are drawn. */
    static final List<String> ENVELOPE_KEYS = List.of("delay", "attack", "hold", "decay", "sustain", "release");
    static final List<String> ENVELOPE_SCALES = List.of("linear", "log");

    /**
     * One part of an envelope: its key ("attack", ...), its parameter reference, and for a time its value
     * mapping (null: drawn by the normalised value). The mapping's unit is ms or s.
     */
    record Segment(String key, String ref, ValueMap values) {
        /** The time in seconds at normalised {@code n}, through the mapping. */
        double seconds(double n) {
            Double s = ValueMap.inUnit(values.display(n), values.unit.replace(" ", "").toLowerCase(Locale.ROOT), "s");
            return s == null ? 0 : s;
        }
    }

    /**
     * An envelope item: its segments in drawing order, whether times are on a log scale, whether the
     * breakpoints get dots, and its fill and display colours ("" for the defaults; display "none" for no
     * background). The line colour is the item's {@code color}.
     */
    record Envelope(List<Segment> segments, boolean log, boolean points, String fill, String display) {
        boolean has(String key) {
            return index(key) >= 0;
        }

        int index(String key) {
            for (int i = 0; i < segments.size(); i++) if (segments.get(i).key().equals(key)) return i;
            return -1;
        }

        /** A held level between decay and release: with sustain, or with release and no decay (an AR shape at full level). */
        boolean plateau() {
            return has("sustain") || (has("release") && !has("decay"));
        }
    }

    static final List<String> REFRESH = List.of("none", "settle");
    static final List<String> TYPING = List.of("plugin", "mapping", "normalised");

    /** A page of columns, and the items of its footer row under them; a layout without pages has one unnamed page. */
    record Page(String name, List<Column> columns, List<Item> footer) {
    }

    /** {@code width}: the column's fixed width in layout units (0: its content's). */
    record Column(String header, Text headerText, String background, boolean divider, long width, Place place, List<Item> items) {
    }

    /** Layout-wide text defaults. */
    record Texts(Text title, Text header, Text label, Text value) {
    }

    /**
     * Fixed heights of a knob cell's lines and of a column's header slot (0: their text's own), the
     * padding around the page (left, right, top, bottom), the gap above the footer row, whether the
     * plug-in's search field stays (it shows Bitwig's list while text is typed), and the share of
     * parameter references that must resolve (percent; under 100 a reference the plug-in lacks is left
     * out instead of failing the layout).
     */
    record Sizes(long headerHeight, long labelHeight, long valueHeight, long[] padding, long footerGap) {
    }

    record Layout(String source, String vendor, String name, String format, long width, String background,
                  boolean dividers, long gap, Texts texts, boolean title, boolean toolbar, String chooser,
                  List<Page> pages, Sizes sizes, boolean search, int minResolved, String refresh, String typing,
                  Stripe stripe, Modes modes, Toolbar bar, boolean fillHeight) {
        boolean matches(Plugin plugin) {
            if (!name.equals("*") && !name.equalsIgnoreCase(plugin.name().trim())) return false;
            if (!vendor.isEmpty() && !vendor.equalsIgnoreCase(plugin.vendor().trim())) return false;
            return format.isEmpty() || format.equalsIgnoreCase(plugin.format());
        }
    }

    /** The accent stripe along the panel's top edge: a colour ("": the theme's "Knob Value Color") and a height in layout units. */
    record Stripe(String colour, long height) {
        static final long DEFAULT_HEIGHT = 2;
    }

    /**
     * Which of Bitwig's toolbar pieces stay: the page tabs (Parameters, Programs), the sidechain and
     * outputs buttons. The search field is the layout's {@code search}; the toolbar row itself is its
     * {@code toolbar}. The container button is never left out.
     */
    record Toolbar(boolean pages, boolean sidechain, boolean outputs, boolean right, String style, long size, long gap,
                   boolean list, List<String> order, String align, String background, boolean divider) {
        /**
         * The Parameters tab: with the page tabs, and also whenever the sidechain or outputs button stays.
         * Those buttons open pages of the same switcher, so without the Parameters tab there would be no
         * way back to the panel.
         */
        boolean parametersTab() {
            return pages || sidechain || outputs;
        }

        static final long SIZE = 21; // Bitwig's row height (w3.MRY), its toolbar buttons' size
        static final long GAP = 2;
        /** The bar's buttons from the top: the default order (the container button at the bottom). */
        static final List<String> ORDER = List.of("layout", "list", "programs", "sidechain", "outputs", "spacer", "container");
        /** The entries an order can name; "pages" is short for "layout", "programs". */
        static final List<String> ORDER_ENTRIES = List.of("layout", "list", "programs", "sidechain", "outputs", "container", "spacer", "pages");
        static final Toolbar ALL = new Toolbar(true, true, true, false, "", SIZE, GAP, false, ORDER, "top", "", false);
        static final Toolbar NONE = new Toolbar(false, false, false, false, "", SIZE, GAP, false, ORDER, "top", "", false);

        /** The bar's width added to the body: the buttons' size, the gap before the bar and, with a divider, the line and its gap. */
        long barWidth() {
            return right ? size + gap + (divider ? 1 + gap : 0) : 0;
        }
    }

    static final List<String> TOOLBAR_PARTS = List.of("pages", "sidechain", "outputs", "search", "position", "style", "size", "gap",
            "list", "order", "align", "background", "divider");
    static final List<String> RIGHT_ONLY = List.of("style", "size", "gap", "list", "order", "align", "background", "divider");

    /** Where mode colours can show. */
    static final List<String> MODE_TARGETS = List.of("stripe", "knob_arc", "header", "divider", "background_tint", "envelope", "toolbar");

    /**
     * Mode colours: the parameter whose mode picks the colour, the colour of each mode by name (as written;
     * matched without regard to case), and the parts it shows on ({@link #MODE_TARGETS}).
     */
    /**
     * {@code colours}: per mode name, its colour per target ("" for every target in {@code apply});
     * {@code mix}: per target, the mode colour's share (1: exactly the mode colour), when the layout sets it.
     */
    record Modes(String param, Map<String, Map<String, String>> colours, List<String> apply, Map<String, Double> mix) {
        boolean applies(String target) {
            return apply.contains(target);
        }
    }

    private Layouts() {
    }

    /** A panel file as last read: its layouts (or the reason it can't be used), kept while its size and time stay. */
    private record Cached(long size, long modified, List<Layout> layouts, String error) {
    }

    private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();

    /**
     * The first layout (in the order the panels were chosen in Patchbay, then the order in the file)
     * that matches the plug-in, or null. A later panel that also matches is logged once. Bitwig builds
     * a plug-in's body often, so each file is read again only when its size or time changed.
     */
    static Layout find(Plugin plugin) {
        Layout found = null;
        String foundIn = null;
        for (Path file : Components.panels()) {
            Cached c = cached(file);
            if (c.error != null) {
                Panels.report("file:" + file + ":" + c.error, "panel " + file.getFileName() + " skipped: " + c.error, null);
                continue;
            }
            for (Layout layout : c.layouts) {
                if (!layout.matches(plugin)) continue;
                if (found == null) {
                    found = layout;
                    foundIn = file.getFileName().toString();
                } else {
                    Panels.report("also:" + file + ":" + plugin.name(), "panel " + file.getFileName() + " also matches " + plugin.name()
                            + "; " + foundIn + " is used (it was chosen first in Patchbay)", null);
                }
            }
        }
        return found;
    }

    private static Cached cached(Path file) {
        long size, modified;
        try {
            size = Files.size(file);
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return new Cached(-1, -1, List.of(), "it can't be read: " + e.getMessage());
        }
        Cached c = CACHE.get(file);
        if (c != null && c.size == size && c.modified == modified) return c;
        try {
            c = new Cached(size, modified, read(file), null);
        } catch (Throwable t) {
            c = new Cached(size, modified, List.of(), String.valueOf(t.getMessage()));
        }
        CACHE.put(file, c);
        return c;
    }

    static List<Layout> read(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString());
    }

    /** A panel file's layouts; throws with the reason when it isn't a valid panel. */
    static List<Layout> parse(String text, String source) {
        Object root = Json.parse(text);
        List<Layout> out = new ArrayList<>();
        Map<String, Object> top = map(root, "the file");
        if (top.containsKey("sheet")) throw new IllegalArgumentException("this is a sheet, not a panel");
        optString(top.get("name"), "name"); // the panel's name, as Patchbay lists it
        if (top.containsKey("layouts")) {
            int i = 0;
            for (Object o : list(top.get("layouts"), "layouts")) out.add(layout(map(o, "layouts[" + i + "]"), source + " #" + (++i)));
        } else {
            out.add(layout(top, source));
        }
        return out;
    }

    private static Layout layout(Map<String, Object> o, String source) {
        Map<String, Object> match = map(o.get("match"), "match");
        String name = string(match.get("name"), "match.name");
        if (name.isBlank()) throw new IllegalArgumentException("match.name is empty");
        String vendor = optString(match.get("vendor"), "match.vendor");
        String format = optString(match.get("format"), "match.format");
        long width = o.get("width") == null ? 0 : Math.round(number(o.get("width"), "width"));
        List<Page> pages = new ArrayList<>();
        if (o.containsKey("pages")) {
            if (o.containsKey("columns")) throw new IllegalArgumentException("a layout has either columns or pages, not both");
            int i = 0;
            for (Object po : list(o.get("pages"), "pages")) {
                String where = "pages[" + i++ + "]";
                Map<String, Object> page = map(po, where);
                pages.add(new Page(string(page.get("name"), where + ".name"), columns(page.get("columns"), where + ".columns"),
                        page.get("footer") == null ? List.of() : items(page.get("footer"), where + ".footer")));
            }
            if (pages.isEmpty()) throw new IllegalArgumentException("no pages");
            if (o.containsKey("footer")) throw new IllegalArgumentException("with pages, each page has its own footer");
        } else {
            pages.add(new Page("", columns(o.get("columns"), "columns"),
                    o.get("footer") == null ? List.of() : items(o.get("footer"), "footer")));
        }
        String chooser = optString(o.get("page_chooser"), "page_chooser").trim().toLowerCase(Locale.ROOT);
        if (chooser.isEmpty()) chooser = "tabs";
        if (!chooser.equals("tabs") && !chooser.equals("dropdown")) throw new IllegalArgumentException("page_chooser must be tabs or dropdown");
        Texts texts = new Texts(text(o, "title_font", "title_color", "layout"), text(o, "header_font", "header_color", "layout"),
                text(o, "label_font", "label_color", "layout"), text(o, "value_font", "value_color", "layout"));
        int minResolved = o.get("min_resolved") == null ? 100 : (int) Math.round(number(o.get("min_resolved"), "min_resolved"));
        if (minResolved < 1 || minResolved > 100) throw new IllegalArgumentException("min_resolved must be 1 to 100 (percent)");
        Sizes sizes = new Sizes(size(o.get("header_height"), "header_height"), size(o.get("label_height"), "label_height"),
                size(o.get("value_height"), "value_height"), padding(o.get("padding")), size(o.get("footer_gap"), "footer_gap"));
        // "toolbar": true (Bitwig's row), false (no row: every piece out, the container button moves to the title line),
        // or {"pages", "sidechain", "outputs", "search"} (each true by default; the row stays)
        Object tb = o.get("toolbar");
        boolean row = true;
        boolean search = o.get("search") == null || bool(o.get("search"), "search");
        Toolbar bar = Toolbar.ALL;
        if (tb instanceof Boolean b) {
            if (!b) {
                row = false;
                search = false;
                bar = Toolbar.NONE;
            }
        } else if (tb != null) {
            Map<String, Object> t = map(tb, "toolbar");
            for (String k : t.keySet()) if (!TOOLBAR_PARTS.contains(k)) throw new IllegalArgumentException("toolbar: unknown key '" + k + "' (" + String.join(", ", TOOLBAR_PARTS) + ")");
            String position = choice(t.get("position"), "toolbar.position", List.of("top", "right"));
            boolean right = position.equals("right");
            if (!right) for (String k : RIGHT_ONLY) if (t.containsKey(k))
                throw new IllegalArgumentException("toolbar: " + k + " is for \"position\": \"right\"");
            if (right && (Boolean.TRUE.equals(t.get("search")) || Boolean.TRUE.equals(o.get("search"))))
                throw new IllegalArgumentException("toolbar: no search field with \"position\": \"right\" (leave search out)");
            long size = t.get("size") == null ? Toolbar.SIZE : Math.round(number(t.get("size"), "toolbar.size"));
            if (size < 12 || size > 60) throw new IllegalArgumentException("toolbar.size must be 12 to 60");
            long gap = t.get("gap") == null ? Toolbar.GAP : Math.round(number(t.get("gap"), "toolbar.gap"));
            if (gap < 0 || gap > 20) throw new IllegalArgumentException("toolbar.gap must be 0 to 20");
            bar = new Toolbar(t.get("pages") == null || bool(t.get("pages"), "toolbar.pages"),
                    t.get("sidechain") == null || bool(t.get("sidechain"), "toolbar.sidechain"),
                    t.get("outputs") == null || bool(t.get("outputs"), "toolbar.outputs"),
                    right, right ? (t.get("style") == null ? "Borderless" : string(t.get("style"), "toolbar.style").trim()) : "", size, gap,
                    t.get("list") != null && bool(t.get("list"), "toolbar.list"), order(t), align(t),
                    optString(t.get("background"), "toolbar.background").trim(), bool(t.get("divider"), "toolbar.divider"));
            if (right || (t.get("search") != null && !bool(t.get("search"), "toolbar.search"))) search = false;
        }
        return new Layout(source, vendor.trim(), name.trim(), format.trim(), width,
                optString(o.get("background"), "background"), bool(o.get("dividers"), "dividers"),
                gap(o.get("gap"), "gap"), texts, o.get("title") == null || bool(o.get("title"), "title"),
                row, chooser, pages, sizes, search, minResolved,
                choice(o.get("refresh"), "refresh", REFRESH), choice(o.get("typing"), "typing", TYPING),
                stripe(o.get("stripe")), modes(o, o.get("stripe") != null && !Boolean.FALSE.equals(o.get("stripe"))), bar,
                bool(o.get("fill_height"), "fill_height"));
    }

    /** The bar's "order": its entries from the top; at most one of each but "spacer". Default {@link Toolbar#ORDER}. */
    private static List<String> order(Map<String, Object> t) {
        if (t.get("order") == null) return Toolbar.ORDER;
        List<String> out = new ArrayList<>();
        for (Object e : list(t.get("order"), "toolbar.order")) {
            String v = string(e, "toolbar.order").trim().toLowerCase(Locale.ROOT);
            if (!Toolbar.ORDER_ENTRIES.contains(v)) throw new IllegalArgumentException("toolbar.order: '" + v + "' is not one of " + Toolbar.ORDER_ENTRIES);
            for (String one : v.equals("pages") ? List.of("layout", "programs") : List.of(v)) {
                if (!one.equals("spacer") && out.contains(one)) throw new IllegalArgumentException("toolbar.order: '" + one + "' twice");
                out.add(one);
            }
        }
        return List.copyOf(out);
    }

    /** The bar's "align" (top, center, bottom; default top); only for an order without a spacer. */
    private static String align(Map<String, Object> t) {
        if (t.get("align") == null) return "top";
        String a = string(t.get("align"), "toolbar.align").trim().toLowerCase(Locale.ROOT);
        if (a.equals("centre")) a = "center";
        if (!List.of("top", "center", "bottom").contains(a)) throw new IllegalArgumentException("toolbar.align must be top, center or bottom");
        if (order(t).contains("spacer")) throw new IllegalArgumentException("toolbar.align: the order has a spacer (the default order has one); give an order without spacers to align the buttons");
        return a;
    }

    /** "stripe": true (the defaults), false or absent (none), or {"color": ..., "height": n}. */
    private static Stripe stripe(Object o) {
        if (o == null || Boolean.FALSE.equals(o)) return null;
        if (Boolean.TRUE.equals(o)) return new Stripe("", Stripe.DEFAULT_HEIGHT);
        Map<String, Object> m = map(o, "stripe");
        for (String k : m.keySet()) if (!k.equals("color") && !k.equals("height")) throw new IllegalArgumentException("stripe: unknown key '" + k + "' (color, height)");
        long height = m.get("height") == null ? Stripe.DEFAULT_HEIGHT : Math.round(number(m.get("height"), "stripe.height"));
        if (height < 1 || height > 20) throw new IllegalArgumentException("stripe.height must be 1 to 20");
        return new Stripe(optString(m.get("color"), "stripe.color").trim(), height);
    }

    /** "mode_colors": {"param": ref, "map": {mode: colour, ...}, "apply": [...]}; null when absent. */
    private static Modes modes(Map<String, Object> layout, boolean stripe) {
        Object o = layout.get("mode_colors");
        if (o == null) return null;
        Map<String, Object> m = map(o, "mode_colors");
        for (String k : m.keySet()) if (!List.of("param", "map", "apply", "mix").contains(k)) throw new IllegalArgumentException("mode_colors: unknown key '" + k + "' (param, map, apply, mix)");
        String param = string(m.get("param"), "mode_colors.param").trim();
        if (param.isEmpty() || param.regionMatches(true, 0, "auto:", 0, 5)) throw new IllegalArgumentException("mode_colors.param must name a parameter");
        // a mode's colour: one for every target in apply, or {target: colour, ...}
        Map<String, Map<String, String>> colours = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(m.get("map"), "mode_colors.map").entrySet()) {
            String w = "mode_colors.map." + e.getKey();
            Map<String, String> byTarget = new java.util.LinkedHashMap<>();
            if (e.getValue() instanceof Map<?, ?>) {
                for (Map.Entry<String, Object> t : map(e.getValue(), w).entrySet()) {
                    if (!MODE_TARGETS.contains(t.getKey())) throw new IllegalArgumentException(w + ": '" + t.getKey() + "' is not one of " + MODE_TARGETS);
                    String colour = string(t.getValue(), w + "." + t.getKey()).trim();
                    if (colour.isEmpty()) throw new IllegalArgumentException(w + "." + t.getKey() + " is empty");
                    byTarget.put(t.getKey(), colour);
                }
                if (byTarget.isEmpty()) throw new IllegalArgumentException(w + " is empty");
            } else {
                String colour = string(e.getValue(), w).trim();
                if (colour.isEmpty()) throw new IllegalArgumentException(w + " is empty");
                byTarget.put("", colour);
            }
            colours.put(e.getKey().trim(), byTarget);
        }
        if (colours.isEmpty()) throw new IllegalArgumentException("mode_colors.map is empty");
        List<String> apply = new ArrayList<>();
        if (m.get("apply") == null) {
            if (stripe) apply.add("stripe");
            apply.add("knob_arc");
        } else {
            for (Object a : list(m.get("apply"), "mode_colors.apply")) {
                String t = string(a, "mode_colors.apply").trim().toLowerCase(Locale.ROOT);
                if (!MODE_TARGETS.contains(t)) throw new IllegalArgumentException("mode_colors.apply: '" + t + "' is not one of " + MODE_TARGETS);
                if (!apply.contains(t)) apply.add(t);
            }
            if (apply.isEmpty()) throw new IllegalArgumentException("mode_colors.apply is empty");
        }
        if (apply.contains("stripe") && !stripe) throw new IllegalArgumentException("mode_colors.apply: stripe needs the layout's stripe");
        if (apply.contains("background_tint") && optString(layout.get("background"), "background").isBlank())
            throw new IllegalArgumentException("mode_colors.apply: background_tint needs the layout's background");
        for (Map.Entry<String, Map<String, String>> e : colours.entrySet())
            for (String t : e.getValue().keySet())
                if (!t.isEmpty() && !apply.contains(t)) throw new IllegalArgumentException("mode_colors.map." + e.getKey() + "." + t + ": " + t + " is not in apply");
        Map<String, Double> mix = new java.util.LinkedHashMap<>();
        if (m.get("mix") != null) {
            for (Map.Entry<String, Object> e : map(m.get("mix"), "mode_colors.mix").entrySet()) {
                if (!apply.contains(e.getKey())) throw new IllegalArgumentException("mode_colors.mix." + e.getKey() + ": not a target in apply " + apply);
                double v = number(e.getValue(), "mode_colors.mix." + e.getKey());
                if (v < 0 || v > 1) throw new IllegalArgumentException("mode_colors.mix." + e.getKey() + " must be 0 to 1");
                mix.put(e.getKey(), v);
            }
        }
        if (apply.contains("toolbar")) {
            Object tb = layout.get("toolbar");
            if (!(tb instanceof Map<?, ?> t) || !"right".equals(t.get("position")) || !(t.get("background") instanceof String b) || b.isBlank())
                throw new IllegalArgumentException("mode_colors.apply: toolbar needs a right-hand toolbar with a background");
        }
        return new Modes(param, colours, List.copyOf(apply), mix);
    }

    /** One of {@code allowed}, lower case; "" when absent. */
    private static String choice(Object o, String what, List<String> allowed) {
        if (o == null) return "";
        String v = string(o, what).trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(v)) throw new IllegalArgumentException(what + " must be one of " + allowed);
        return v;
    }

    /** "padding": one number for every side, or [left, right, top, bottom]; null when absent. */
    private static long[] padding(Object o) {
        return padding(o, "padding");
    }

    private static long[] padding(Object o, String what) {
        if (o == null) return null;
        if (o instanceof Double) {
            long n = Math.round(number(o, what));
            if (n < 0 || n > 200) throw new IllegalArgumentException(what + " must be 0 to 200");
            return new long[] { n, n, n, n };
        }
        List<Object> sides = list(o, what);
        if (sides.size() != 4) throw new IllegalArgumentException(what + " must be a number or [left, right, top, bottom]");
        long[] out = new long[4];
        for (int i = 0; i < 4; i++) {
            out[i] = Math.round(number(sides.get(i), what));
            if (out[i] < 0 || out[i] > 200) throw new IllegalArgumentException(what + " must be 0 to 200");
        }
        return out;
    }

    private static List<Column> columns(Object o, String what) {
        List<Column> columns = new ArrayList<>();
        int c = 0;
        for (Object co : list(o, what)) {
            String where = what + "[" + c++ + "]";
            Map<String, Object> col = map(co, where);
            columns.add(new Column(optString(col.get("header"), where + ".header"),
                    text(col, "header_font", "header_color", where),
                    optString(col.get("background"), where + ".background"),
                    bool(col.get("divider"), where + ".divider"), size(col.get("width"), where + ".width"), place(col, where),
                    items(col.get("items"), where + ".items")));
        }
        if (columns.isEmpty()) throw new IllegalArgumentException(what + ": no columns");
        return columns;
    }

    private static List<Item> items(Object o, String where) {
        List<Item> items = new ArrayList<>();
        int i = 0;
        for (Object io : list(o, where)) items.add(item(map(io, where + "[" + i + "]"), where + "[" + i++ + "]"));
        return items;
    }

    private static Item item(Map<String, Object> o, String where) {
        String kind = null;
        for (String k : KINDS) {
            if (!o.containsKey(k)) continue;
            if (kind != null) throw new IllegalArgumentException(where + ": an item has both " + kind + " and " + k);
            kind = k;
        }
        if (kind == null) throw new IllegalArgumentException(where + ": an item needs one of " + String.join(", ", KINDS) + "; found " + o.keySet());
        String background = optString(o.get("background"), where + ".background");
        if (!kind.equals("row") && o.containsKey("padding")) throw new IllegalArgumentException(where + ": padding is for rows and columns (put the item in a row)");
        if (kind.equals("row")) {
            return new Item(kind, "", "", false, "", 0, "", "", 0, "", Text.NONE, Text.NONE, Format.PLUGIN, List.of(), null, "", "",
                    size(o.get("width"), where + ".width"), 0, background, place(o, where), items(o.get("row"), where + ".row"), null);
        }
        if (kind.equals("spacer")) { // {"spacer": true}: takes the spare height of its column (or width of its row)
            if (!Boolean.TRUE.equals(o.get("spacer"))) throw new IllegalArgumentException(where + ".spacer must be true");
            for (String k : o.keySet()) if (!k.equals("spacer")) throw new IllegalArgumentException(where + ": a spacer takes no other keys (found " + k + ")");
            return new Item(kind, "", "", true, "", 0, "", "", 0, "", Text.NONE, Text.NONE, Format.PLUGIN, List.of(), null, "", "",
                    0, 0, "", new Place(-1, -1, -1, true, null), List.of(), null);
        }
        if (kind.equals("envelope")) {
            return new Item(kind, "", "", true, "", 0, "", "", 0, "", text(o, "font", "color", where), Text.NONE, Format.PLUGIN,
                    List.of(), null, "", "", size(o.get("width"), where + ".width"), size(o.get("height"), where + ".height"),
                    background, place(o, where), List.of(), envelope(o, where));
        }
        String label = "", icon = "";
        long iconSize = 0;
        boolean hideLabel = false;
        Object l = o.get("label");
        if (l instanceof Boolean b) {
            if (b) throw new IllegalArgumentException(where + ".label: text, an object, or false (no label)");
            hideLabel = true;
        } else if (l instanceof Map<?, ?>) {
            Map<String, Object> m = map(l, where + ".label");
            label = optString(m.get("text"), where + ".label.text");
            icon = optString(m.get("icon"), where + ".label.icon").trim();
            iconSize = size(m.get("size"), where + ".label.size");
        } else {
            label = optString(l, where + ".label");
        }
        String size = "";
        long knobPx = 0;
        if (o.get("size") instanceof Double) knobPx = size(o.get("size"), where + ".size"); // an exact knob size
        else size = optString(o.get("size"), where + ".size").trim().toLowerCase(Locale.ROOT);
        if (!size.isEmpty() && !KNOB_SIZES.contains(size)) throw new IllegalArgumentException(where + ": size must be a number or one of " + KNOB_SIZES);
        String ref = string(o.get(kind), where + "." + kind);
        return new Item(kind, kind.equals("text") ? ref : ref.trim(), label, hideLabel, icon, iconSize,
                optString(o.get("style"), where + ".style"), size, knobPx, optString(o.get("knob_style"), where + ".knob_style"),
                text(o, "font", "color", where), text(o, "value_font", "value_color", where),
                format(o.get("value_format"), where + ".value_format"), choices(o.get("choices"), where + ".choices"),
                o.get("values") == null ? null : ValueMap.parse(map(o.get("values"), where + ".values"), where + ".values"),
                choice(o.get("refresh"), where + ".refresh", REFRESH), choice(o.get("typing"), where + ".typing", TYPING),
                size(o.get("width"), where + ".width"), size(o.get("height"), where + ".height"), background,
                place(o, where), List.of(), null);
    }

    /**
     * {"envelope": {"attack": ref, ...}}: each key a parameter reference, or {"param": ref, "values": {...}}
     * for a time drawn in seconds; on the item {@code scale}, {@code points}, {@code fill_color} and
     * {@code display_color}.
     */
    private static Envelope envelope(Map<String, Object> o, String where) {
        String w = where + ".envelope";
        Map<String, Object> e = map(o.get("envelope"), w);
        for (String k : e.keySet()) if (!ENVELOPE_KEYS.contains(k)) throw new IllegalArgumentException(w + ": unknown key '" + k + "' (" + String.join(", ", ENVELOPE_KEYS) + ")");
        List<Segment> segments = new ArrayList<>();
        int timed = 0, mapped = 0;
        for (String k : ENVELOPE_KEYS) {
            Object v = e.get(k);
            if (v == null) continue;
            String kw = w + "." + k;
            String ref;
            ValueMap values = null;
            if (v instanceof Map<?, ?>) {
                Map<String, Object> m = map(v, kw);
                for (String mk : m.keySet()) if (!mk.equals("param") && !mk.equals("values")) throw new IllegalArgumentException(kw + ": unknown key '" + mk + "' (param, values)");
                ref = string(m.get("param"), kw + ".param");
                if (m.get("values") != null) {
                    if (k.equals("sustain")) throw new IllegalArgumentException(kw + ": a level takes no values (it is drawn at its normalised value)");
                    values = ValueMap.parse(map(m.get("values"), kw + ".values"), kw + ".values");
                    String unit = values.unit.replace(" ", "").toLowerCase(Locale.ROOT);
                    if (unit.isEmpty() || ValueMap.inUnit(1, unit, "s") == null) throw new IllegalArgumentException(kw + ".values.unit must be ms or s");
                }
            } else {
                ref = string(v, kw);
            }
            ref = ref.trim();
            if (ref.isEmpty()) throw new IllegalArgumentException(kw + " is empty");
            if (ref.regionMatches(true, 0, "auto:", 0, 5)) throw new IllegalArgumentException(kw + ": an envelope takes named references, not auto:");
            if (!k.equals("sustain")) {
                timed++;
                if (values != null) mapped++;
            }
            segments.add(new Segment(k, ref, values));
        }
        Envelope shape = new Envelope(segments, false, true, "", "");
        if (!shape.has("attack")) throw new IllegalArgumentException(w + " needs attack");
        if (shape.has("sustain") && !shape.has("decay")) throw new IllegalArgumentException(w + ": sustain needs decay");
        if (shape.has("release") && shape.has("decay") && !shape.has("sustain")) throw new IllegalArgumentException(w + ": release after decay needs sustain");
        if (mapped != 0 && mapped != timed) throw new IllegalArgumentException(w + ": values on every time key or on none");
        String scale = choice(o.get("scale"), where + ".scale", ENVELOPE_SCALES);
        if (scale.equals("log") && mapped == 0) throw new IllegalArgumentException(where + ".scale: log needs values on every time key");
        return new Envelope(segments, scale.equals("log"), o.get("points") == null || bool(o.get("points"), where + ".points"),
                optString(o.get("fill_color"), where + ".fill_color").trim(), optString(o.get("display_color"), where + ".display_color").trim());
    }

    /** "percent", or {"range": [lo, hi], "decimals": n, "unit": "..."}, or {"map": [[value, "text"], ...]}. */
    private static Format format(Object o, String where) {
        if (o == null) return Format.PLUGIN;
        if (o instanceof String s) {
            if (s.trim().equalsIgnoreCase("percent")) return new Format("percent", 0, 100, 0, "%", List.of());
            if (s.trim().equalsIgnoreCase("plugin")) return Format.PLUGIN;
            throw new IllegalArgumentException(where + " must be \"percent\", \"plugin\" or an object");
        }
        Map<String, Object> m = map(o, where);
        if (m.containsKey("map")) return new Format("map", 0, 1, 0, "", choices(m.get("map"), where + ".map"));
        double lo = 0, hi = 1;
        if (m.containsKey("range")) {
            List<Object> r = list(m.get("range"), where + ".range");
            if (r.size() != 2) throw new IllegalArgumentException(where + ".range must be [low, high]");
            lo = number(r.get(0), where + ".range");
            hi = number(r.get(1), where + ".range");
        }
        int decimals = m.get("decimals") == null ? 0 : (int) Math.round(number(m.get("decimals"), where + ".decimals"));
        if (decimals < 0 || decimals > 6) throw new IllegalArgumentException(where + ".decimals must be 0 to 6");
        return new Format("range", lo, hi, decimals, optString(m.get("unit"), where + ".unit"), List.of());
    }

    /** [["name", value], ...] or [[value, "name"], ...]: values normalised 0..1. */
    private static List<Choice> choices(Object o, String where) {
        if (o == null) return List.of();
        List<Choice> out = new ArrayList<>();
        int i = 0;
        for (Object e : list(o, where)) {
            String w = where + "[" + i++ + "]";
            List<Object> pair = list(e, w);
            if (pair.size() != 2) throw new IllegalArgumentException(w + " must be [name, value]");
            Object a = pair.get(0), b = pair.get(1);
            String name = a instanceof String s ? s : string(b, w);
            double value = a instanceof String ? number(b, w) : number(a, w);
            if (value < 0 || value > 1) throw new IllegalArgumentException(w + ": values are normalised, 0 to 1");
            out.add(new Choice(name, value));
        }
        if (out.isEmpty()) throw new IllegalArgumentException(where + " is empty");
        return out;
    }

    private static Text text(Map<String, Object> o, String fontKey, String colourKey, String where) {
        return new Text(optString(o.get(fontKey), where + "." + fontKey).trim(), optString(o.get(colourKey), where + "." + colourKey).trim());
    }

    private static Place place(Map<String, Object> o, String where) {
        float align = position(o.get("align"), where + ".align", "left", "center", "right");
        float valign = position(o.get("valign"), where + ".valign", "top", "center", "bottom");
        Object knobs = o.get("align_knobs");
        return new Place(align, valign, gap(o.get("gap"), where + ".gap"), knobs == null || bool(knobs, where + ".align_knobs"),
                padding(o.get("padding"), where + ".padding"));
    }

    /** "left" / "center" / "right" (or top / center / bottom) as 0, 0.5, 1; -1 when absent. */
    private static float position(Object o, String what, String start, String middle, String end) {
        if (o == null) return -1;
        String v = string(o, what).trim().toLowerCase(Locale.ROOT);
        if (v.equals(start)) return 0;
        if (v.equals(middle) || v.equals("centre")) return 0.5f;
        if (v.equals(end)) return 1;
        throw new IllegalArgumentException(what + " must be " + start + ", " + middle + " or " + end);
    }

    private static boolean bool(Object o, String what) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        throw new IllegalArgumentException(what + " must be true or false");
    }

    /** A gap in layout units (0 allowed); -1 when absent (the default gap). */
    private static long gap(Object o, String what) {
        if (o == null) return -1;
        long n = Math.round(number(o, what));
        if (n < 0 || n > 2000) throw new IllegalArgumentException(what + " must be between 0 and 2000");
        return n;
    }

    /** A size in layout units; 0 when absent. */
    private static long size(Object o, String what) {
        if (o == null) return 0;
        long n = Math.round(number(o, what));
        if (n <= 0 || n > 2000) throw new IllegalArgumentException(what + " must be between 1 and 2000");
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o, String what) {
        if (o instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException(what + " must be an object");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o, String what) {
        if (o instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException(what + " must be a list");
    }

    private static String string(Object o, String what) {
        if (o instanceof String s) return s;
        if (o instanceof Double d && d == Math.rint(d)) return Long.toString(d.longValue()); // a whole number is read as its digits
        throw new IllegalArgumentException(what + " must be text");
    }

    private static String optString(Object o, String what) {
        return o == null ? "" : string(o, what);
    }

    private static double number(Object o, String what) {
        if (o instanceof Double d) return d;
        throw new IllegalArgumentException(what + " must be a number");
    }
}
