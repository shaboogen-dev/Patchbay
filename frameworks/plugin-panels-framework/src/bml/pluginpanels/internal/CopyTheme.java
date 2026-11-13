package bml.pluginpanels.internal;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import bitwig.obf.Sxr; // a fixed size (layout units)
import bitwig.obf.dve; // a widget
import bitwig.obf.eeY; // a theme colour
import bitwig.obf.efM; // a knob's geometry
import bitwig.obf.efU; // a knob style
import bitwig.obf.efu; // a theme font
import bitwig.obf.egY; // a button style
import bitwig.obf.eiH; // a theme
import bitwig.obf.iok; // a colour
import bitwig.obf.w3; // Bitwig's standard sizes
import bml.actions.ActionContext;
import com.bitwig.flt.app.CHR; // the application window controller (shows notifications)
import com.bitwig.graphics.FontExtents; // a font's ascent, descent and line height

/**
 * "Generate Theme Sheet" in a plug-in device's right-click menu: a JSON theme sheet for a layout builder (its
 * format is in the framework's README): every font, colour, knob size, knob style and button style a
 * layout can name, resolved through the same theme calls the framework makes when it builds a panel,
 * Bitwig's standard sizes and the framework's own spacing. Copied to the clipboard and written to
 * {@code theme.sheet.json} where Generate Parameter Sheet writes its sheets.
 */
final class CopyTheme {
    static final String SHEET = "plugin-panels-theme-sheet";
    static final int SHEET_VERSION = 1;

    private CopyTheme() {
    }

    /**
     * A developer tool, to regenerate the layout builder's default theme after a Bitwig update; offered in
     * Debug Mode only (BML sets the session's mod log then), and to be removed or hidden before release.
     */
    static boolean debugMode() {
        String log = System.getenv("BITWIG_RE_MOD_LOG");
        return log != null && !log.isBlank();
    }

    static void run(ActionContext context) {
        try {
            if (!(context.widget() instanceof dve widget)) return;
            String text = sheet(widget);
            widget.kI_().MIO().cfM(text); // the window's clipboard: set its text
            Path file = CopyParameters.write("theme.sheet.json", text);
            Panels.MOD.log("theme sheet copied" + (file == null ? "" : " and saved to " + file));
            CHR.Grk().hUR("Theme sheet copied"); // notification
        } catch (Throwable t) {
            Panels.report("copy-theme", "Generate Theme Sheet failed", t);
        }
    }

    static String sheet(dve w) {
        eiH theme = w.jl_(); // the theme, as the framework reads it from the plug-in body
        double scale = w.eLY(1000) / 1000.0; // pixels per layout unit
        String nl = System.lineSeparator();
        StringBuilder out = new StringBuilder("{").append(nl);
        out.append("  \"sheet\": ").append(str(SHEET)).append(", \"version\": ").append(SHEET_VERSION).append(',').append(nl);
        out.append("  \"bitwig\": ").append(str(bitwigVersion())).append(',').append(nl);
        out.append("  \"ui_scale\": ").append(num(scale)).append(',').append(nl);

        List<String> rows = new ArrayList<>();
        for (Object o : theme.Ai().nr_()) { // the theme's font table
            if (!(o instanceof efu f)) continue;
            rows.add(str(f.uva()) + ": " + font(w, f, scale));
        }
        section(out, "fonts", rows, nl);

        rows = new ArrayList<>();
        for (Object o : theme.BAa().nr_()) { // the theme's colour table
            if (o instanceof eeY c) rows.add(str(c.uva()) + ": " + colour(c, w));
        }
        section(out, "colors", rows, nl);

        rows = new ArrayList<>();
        for (String size : Layouts.KNOB_SIZES) rows.add(str(size) + ": " + geometry(theme.FCz().cfM(size))); // as a knob's "size" sets it
        section(out, "knob_sizes", rows, nl);

        rows = new ArrayList<>();
        for (Object o : theme.Mph().nr_()) { // the theme's knob styles
            if (o instanceof efU s) rows.add(str(s.uva()) + ": " + knobStyle(s, w));
        }
        section(out, "knob_styles", rows, nl);

        List<String> buttons = new ArrayList<>();
        for (Object o : theme.znC().nr_()) if (o instanceof egY s) buttons.add(str(s.uva())); // the theme's button styles
        out.append("  \"button_styles\": [").append(String.join(", ", buttons)).append("],").append(nl);

        List<String> dropdowns = new ArrayList<>();
        for (Object o : theme.kTh().nr_()) if (o instanceof bitwig.obf.egC s) dropdowns.add(str(s.uva())); // the theme's dropdown styles
        out.append("  \"dropdown_styles\": [").append(String.join(", ", dropdowns)).append("],").append(nl);

        out.append("  \"text_colors\": { \"text\": ").append(colour(w.e3y(), w)) // a label's default text colour
           .append(", \"separator\": ").append(colour(w.PEn().QOl(), w)).append(" },").append(nl); // a divider's colour

        out.append("  \"bitwig_sizes\": { \"standard\": [")
           .append(units(w3.cfM)).append(", ").append(units(w3.MRY)).append(", ").append(units(w3.Hnt)).append(", ")
           .append(units(w3.IwV)).append(", ").append(units(w3.UNj)).append(", ").append(units(w3.hUR))
           .append("], \"row_height\": ").append(units(w3.MRY)) // toggles, page tabs, Bitwig's toolbar row
           .append(", \"body_width\": 170 },").append(nl); // Bitwig's own plug-in body width

        out.append("  \"framework\": { ")
           .append("\"padding\": ").append(Panels.PAD)
           .append(", \"layout_gap\": ").append(Panels.PAD)
           .append(", \"row_gap\": ").append(Panels.PAD)
           .append(", \"column_gap\": ").append(Panels.COLUMN_GAP)
           .append(", \"page_gap\": ").append(Panels.PAGE_GAP)
           .append(", \"title_gap\": ").append(Panels.PAD * 2)
           .append(", \"line_gap\": ").append(Panels.LINE_GAP)
           .append(", \"tab_gap\": ").append(Panels.TAB_GAP)
           .append(", \"background_padding\": ").append(Panels.BACKGROUND_PAD)
           .append(", \"icon_size\": ").append(Panels.ICON_SIZE)
           .append(", \"divider_width\": ").append(Panels.DIVIDER)
           .append(", \"needle\": [").append(Panels.NEEDLE_WIDTH).append(", ").append(Panels.NEEDLE_HEIGHT).append(']')
           .append(", \"bar\": [").append(Panels.BAR_WIDTH).append(", ").append(Panels.BAR_HEIGHT).append(']')
           .append(", \"level\": [").append(Panels.LEVEL_WIDTH).append(", ").append(Panels.BAR_HEIGHT).append(']')
           .append(", \"envelope\": [").append(Panels.ENVELOPE_WIDTH).append(", ").append(Panels.ENVELOPE_HEIGHT).append(']')
           .append(", \"min_zoom\": ").append(num(HeightFit.MIN_ZOOM))
           .append(" }").append(nl);
        out.append('}').append(nl);
        return out.toString();
    }

    private static void section(StringBuilder out, String name, List<String> rows, String nl) {
        out.append("  ").append(str(name)).append(": {").append(nl);
        for (int i = 0; i < rows.size(); i++) out.append("    ").append(rows.get(i)).append(i < rows.size() - 1 ? "," : "").append(nl);
        out.append("  },").append(nl);
    }

    /** A font: its typeface file, family, weight and style read from that name, its size, and its measured extents (layout units). */
    private static String font(dve w, efu f, double scale) {
        String typeface = f.lYc() == null ? "" : f.lYc().cfM(); // the typeface's file ("Lato-Semibold.ttf")
        String base = typeface.replaceFirst("\\.[A-Za-z0-9]+$", "");
        int cut = base.indexOf('-') >= 0 ? base.indexOf('-') : base.indexOf('_');
        String family = cut > 0 ? base.substring(0, cut) : base;
        String face = cut > 0 ? base.substring(cut + 1).toLowerCase(Locale.ROOT) : "";
        String weight = face.contains("semibold") ? "semibold" : face.contains("bold") ? "bold" : face.contains("medium") ? "medium"
                : face.contains("light") ? "light" : "regular";
        String style = face.contains("italic") ? "italic" : "normal";
        StringBuilder b = new StringBuilder("{ \"typeface\": ").append(str(typeface)).append(", \"family\": ").append(str(family))
                .append(", \"weight\": ").append(str(weight)).append(", \"style\": ").append(str(style))
                .append(", \"size\": ").append(num(f.ODA())); // the theme's size, as "<name> <size>" takes it
        try {
            FontExtents e = w.kI_().cfM(f, w).cfM(); // the font as a label draws it: ascent, descent, height, ...
            b.append(", \"ascent\": ").append(num(e.cfM() / scale)).append(", \"descent\": ").append(num(e.MRY() / scale))
             .append(", \"height\": ").append(num(e.Hnt() / scale));
        } catch (Throwable t) {
            b.append(", \"ascent\": null, \"descent\": null, \"height\": null");
        }
        return b.append(" }").toString();
    }

    /** "#rrggbbaa" as the colour shows on this widget, or null. */
    private static String colour(eeY c, dve w) {
        if (c == null) return "null";
        try {
            iok k = c.cfM(w); // resolved for the widget, as a widget paints it
            return k == null ? "null" : str(String.format(Locale.ROOT, "#%02x%02x%02x%02x", k.hbP(), k.Grk(), k.tp2(), k.Ucl())); // r, g, b, a
        } catch (Throwable t) {
            return "null";
        }
    }

    /** A knob geometry in layout units, by Bitwig's own field names; null when the theme has none. */
    private static String geometry(efM g) {
        if (g == null) return "null";
        return "{ \"inner_radius\": " + num(g.E3()) + ", \"arc_radius\": " + num(g.iHb()) + ", \"arc_width\": " + num(g.MIO())
                + ", \"center_area_radius\": " + num(g.vpw()) + ", \"knob_width\": " + g.ejd() + ", \"knob_height\": " + g.dEI()
                + ", \"text_distance\": " + g.dnz() + " }";
    }

    /** A knob style's colours, by Bitwig's own field names. */
    private static String knobStyle(efU s, dve w) {
        return "{ \"stroke_color\": " + colour(s.lYc(), w) + ", \"fill_color\": " + colour(s.E3(), w)
                + ", \"dot_color\": " + colour(s.wGc(), w) + ", \"emboss_highlight_color\": " + colour(s.iHb(), w)
                + ", \"emboss_shadow_color\": " + colour(s.Ucl(), w) + ", \"value_background_color\": " + colour(s.MIO(), w)
                + ", \"value_color\": " + colour(s.ODA(), w) + ", \"gradient_amount\": " + num(s.ERO())
                + ", \"use_color_for_line\": " + s.uWE() + " }";
    }

    private static String units(Sxr size) {
        return num(size.cfM()); // its length in layout units
    }

    /** The running Bitwig's version, as BML prepared it ({@code loader.runtime.bitwig_version}); "" when unknown. */
    private static String bitwigVersion() {
        try {
            String config = System.getenv("BML_MOD_CONFIG");
            if (config == null || config.isBlank()) return "";
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(Path.of(config), StandardCharsets.UTF_8)) {
                p.load(r);
            }
            return p.getProperty("loader.runtime.bitwig_version", "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String num(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e9) return Long.toString((long) d);
        return String.format(Locale.ROOT, "%.4f", d).replaceFirst("0+$", "").replaceFirst("\\.$", ""); // "18.0000" -> "18", never "18."
    }

    private static String str(String s) {
        return CopyParameters.str(s);
    }
}
