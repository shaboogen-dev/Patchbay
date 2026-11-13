package bml.panels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import bitwig.obf.AQw; // a saved on/off value (here: a panel's "is shown")
import bitwig.obf.aBW; // a window's project view
import bitwig.obf.akQ; // a panel
import bitwig.obf.akZ; // a splitter between two parts of a window's layout
import bitwig.obf.akq; // a main area (here only a stand-in)
import bitwig.obf.akw; // a part of a window's layout
import bitwig.obf.dAd; // a toggle button widget
import bitwig.obf.dKW; // a popup menu
import bitwig.obf.dVx; // which side of a splitter keeps its size
import bitwig.obf.dWb; // a row or column of widgets
import bitwig.obf.dmQ; // a clickable action that runs a Runnable
import bitwig.obf.dve; // a widget
import bitwig.obf.dws; // an action, as a button takes it
import bitwig.obf.eoV; // a set of held modifier keys
import bitwig.obf.foz; // a listener
import bitwig.obf.kn; // the bottom bar's button colour
import bitwig.obf.mcu; // a saved number (here: a divider position)
import bitwig.obf.w3; // the bottom bar's button sizes
import com.bitwig.base.geom.IBe; // the bottom bar's row style
import com.bitwig.flt.document_view_settings.gJi; // a project's view settings for one display profile

/**
 * A profile's view stack: views one above the other, each shown and hidden on its own (Bitwig's
 * own "is shown" per view), with their buttons in place of Bitwig's mode chooser. A click shows a
 * view alone, Cmd/Ctrl-click adds it to the split or removes it (the last view shown stays), the
 * right-click menu has its scale and Equalise View Sizes.
 *
 * <pre>
 * stack (StackSplit, the policy's name)
 *   view 1
 *   split_view1 (StackSplit)
 *     view 2
 *     ...
 *       last view
 * </pre>
 *
 * Every view's path is flat under the stack and every split below the first is named by the view
 * above it (StackSplit), so saved values stay with their views whichever views the stack has.
 *
 * Side columns (Layout.sideColumns) hold only the Bitwig panels a policy names, side by side at
 * a side of the window, each a full-height column with its own width:
 *
 * <pre>
 * Bitwig's splitter (its saved width: the first panel's)
 *   column_panel2 (ColumnSplit, its saved width: panel 2's)
 *     column_panel3 ...
 *       what the splitter held before (the main area), with its own path
 *       panel 3
 *     panel 2
 *   panel 1 (at the window's edge)
 * </pre>
 *
 * Each splitter keeps its column's width, so showing a panel takes width from the main area and
 * hiding it gives that back. The panels keep Bitwig's own buttons where Bitwig puts them, given
 * the same clicks (the side-buttons hook), and the columns may all be hidden: a click on the panel
 * shown alone hides it, the last panel can be taken out, and a project may open with none shown.
 */
final class Stack {
    /** A view of the stack: its declaration, its node, the policy mod that made the stack. */
    record Entry(Registry.Declared view, akQ node, String policyMod) {
    }

    /** The modifier for "add to or remove from the split", as the tooltips name it. */
    private static final String SPLIT_KEY = System.getProperty("os.name", "").startsWith("Mac") ? "Cmd" : "Ctrl";
    /** The smallest share of a split either side keeps when both sides show a view. */
    private static final double MIN_SHARE = 0.1;

    final Layouts layout;
    final String name;
    final boolean side; // side columns: Bitwig's buttons, may be empty
    final List<Entry> entries;
    final List<StackSplit> splits = new ArrayList<>(); // the main stack's
    final List<akZ> columns = new ArrayList<>();       // side columns: each panel's splitter, in the panels' order
    akw root;

    /**
     * A column's width (pixels at Bitwig's scale) until it is first opened: Bitwig's default for
     * the Browser column. Opened with it, a column starts at the first column's width (watch).
     */
    private static final double COLUMN_WIDTH = 222;
    /** The view settings whose columns are watched (once each). */
    private final Set<gJi> watched = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private Stack(Layouts layout, String name, boolean side, List<Entry> entries) {
        this.layout = layout;
        this.name = name;
        this.side = side;
        this.entries = List.copyOf(entries);
    }

    /**
     * Side columns in Bitwig's left/right splitter: the side holding {@code replaced} becomes the
     * first panel's column (the window's edge), and the other side the other panels' columns
     * around what it held (the main area). Every splitter keeps its column's width.
     */
    static Stack buildColumns(Layouts layout, akZ outer, akw replaced, List<Entry> entries) {
        Stack stack = new Stack(layout, outer.tSS(), true, entries);
        boolean edgeSecond = outer.wGc() == replaced; // wGc(): the second (right) part
        akw inner = edgeSecond ? outer.E3() : outer.wGc();
        outer.MRY((akw) akq.cfM("panel-stand-in")); // takes both parts out (a part has one parent)
        outer.IwV((akw) akq.cfM("panel-stand-in"));
        akw core = inner;
        List<akZ> nested = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 1; i--) { // from the main area outwards
            akw panel = entries.get(i).node();
            ColumnSplit split = ColumnSplit.create(outer, panel);
            split.MRY(edgeSecond ? core : panel); // the first (left) part
            split.IwV(edgeSecond ? panel : core); // the second (right) part
            split.cfM(edgeSecond ? dVx.MRY : dVx.cfM); // the column's side keeps its width
            split.cfM(COLUMN_WIDTH);
            nested.add(0, split);
            core = split;
        }
        akw edge = entries.get(0).node();
        outer.MRY(edgeSecond ? core : edge);
        outer.IwV(edgeSecond ? edge : core);
        stack.columns.add(outer);
        stack.columns.addAll(nested);
        stack.root = outer;
        return stack;
    }

    static Stack build(Layouts layout, String name, boolean side, List<Entry> entries) {
        Stack stack = new Stack(layout, name, side, entries);
        if (entries.size() == 1) {
            stack.root = entries.get(0).node();
            return stack;
        }
        StackSplit first = StackSplit.create(name, null);
        StackSplit split = first;
        for (int i = 0; i < entries.size() - 1; i++) {
            stack.splits.add(split);
            split.MRY((akw) entries.get(i).node()); // the upper part: this view (akw: the part setter)
            akw lower;
            if (i == entries.size() - 2) {
                lower = entries.get(i + 1).node();   // the last view
            } else {
                lower = StackSplit.create("split", first);
            }
            split.IwV(lower);                          // the lower part
            split.cfM(dVx.FzB);                        // the lower side keeps its share of the height
            split.cfM(0.5);
            if (lower instanceof StackSplit next) split = next;
        }
        stack.root = first;
        return stack;
    }

    Entry entry(Object node) {
        for (Entry e : entries) if (e.node() == node) return e;
        return null;
    }

    Entry entry(String modId, String viewId) {
        for (Entry e : entries) if (e.view().modId.equals(modId) && e.view().id.equals(viewId)) return e;
        return null;
    }

    boolean isSplit(Object node) {
        return splits.contains(node);
    }

    // ---------- Showing ----------

    /**
     * A click on the view's button: the view alone. In side columns, a click on the panel shown
     * alone hides it (the side closes), as Bitwig's own toggle does.
     */
    void clicked(gJi settings, Entry entry) {
        watch(settings);
        if (side && shownAlone(settings, entry)) {
            AQw shown = entry.node().cfM(settings);
            if (shown != null) shown.setBoolValue(false);
            return;
        }
        showAlone(settings, entry);
    }

    private boolean shownAlone(gJi settings, Entry entry) {
        for (Entry each : entries) {
            AQw shown = each.node().cfM(settings);
            if ((shown != null && shown.getBoolValue()) != (each == entry)) return false;
        }
        return true;
    }

    /** Shows the view alone: through its policy if it does it (a mode's preset), else directly. */
    void showAlone(gJi settings, Entry entry) {
        if (side) { // no presets for a side
            showOnly(settings, entry);
            return;
        }
        LayoutPolicy policy = Layouts.policyOf(entry.policyMod());
        try {
            if (policy != null && policy.showAlone(layout, settings, entry.view().modId, entry.view().id)) return;
        } catch (Throwable failure) {
            Registry.LOG.log("mod " + entry.policyMod() + ": showing " + entry.view().who() + " alone failed", failure);
        }
        showOnly(settings, entry);
    }

    void showOnly(gJi settings, Entry entry) {
        for (Entry each : entries) {
            AQw shown = each.node().cfM(settings); // its "is shown" in these settings
            if (shown != null && shown.getBoolValue() != (each == entry)) shown.setBoolValue(each == entry);
        }
    }

    /** Shows the view beside the others, or hides it unless it is the only one shown (in side columns: also then). */
    void toggleInSplit(gJi settings, Entry entry) {
        watch(settings);
        AQw shown = entry.node().cfM(settings);
        if (shown == null) return;
        if (!shown.getBoolValue()) {
            shown.setBoolValue(true);
            repairSplits(settings);
            return;
        }
        if (side) {
            shown.setBoolValue(false);
            return;
        }
        for (Entry other : entries) {
            AQw otherShown = other.node().cfM(settings);
            if (other != entry && otherShown != null && otherShown.getBoolValue()) {
                shown.setBoolValue(false);
                return;
            }
        }
    }

    /**
     * A project's view settings were read. When no view is shown, the policy may show one (a mode's
     * preset); otherwise the first view. Then the dividers get usable positions.
     */
    void settingsLoaded(gJi settings) {
        watch(settings);
        if (!side && !anyShown(root, settings)) { // side columns may open with none shown
            for (Registry.Policy p : Registry.policies()) {
                try {
                    p.policy().nothingShown(layout, settings);
                } catch (Throwable failure) {
                    Registry.LOG.log("mod " + p.modId() + ": choosing the view to show failed", failure);
                }
            }
            if (!anyShown(root, settings)) showOnly(settings, entries.get(0));
        }
        repairSplits(settings);
    }

    /**
     * Every split with a shown view on both sides gets back to halves when its saved position (the
     * lower side's share) leaves either side less than MIN_SHARE, so a view shown again beside
     * others always gets a usable share.
     */
    void repairSplits(gJi settings) {
        for (StackSplit split : splits) {
            if (!anyShown(split.E3(), settings) || !anyShown(split.wGc(), settings)) continue;
            mcu position = split.cfM(settings); // its saved divider position: the lower side's share
            if (position == null) continue;
            double share = position.getDoubleValue();
            if (share < MIN_SHARE || share > 1 - MIN_SHARE) position.setDoubleValue(0.5);
        }
    }

    /**
     * Side columns: whenever a column other than the first is shown (its button, an action,
     * Bitwig's toggle, a controller), one still at the default width starts at the first column's
     * width; one with a width of its own keeps it. Once per view settings.
     */
    void watch(gJi settings) {
        if (!side || settings == null || columns.size() < 2 || !watched.add(settings)) return;
        for (int i = 1; i < entries.size() && i < columns.size(); i++) {
            AQw shown = entries.get(i).node().cfM(settings);
            mcu width = columns.get(i).cfM(settings); // its saved divider position: the column's width
            if (shown == null || width == null) continue;
            shown.addListener((foz) event -> {
                try {
                    if (shown.getBoolValue()) startWidth(settings, width);
                } catch (Throwable failure) {
                    Registry.LOG.log("could not give an opened column its starting width", failure);
                }
            });
        }
    }

    private void startWidth(gJi settings, mcu width) {
        if (Math.abs(width.getDoubleValue() - COLUMN_WIDTH) > 0.5) return; // a width of its own
        mcu first = columns.get(0).cfM(settings);
        if (first != null) width.setDoubleValue(first.getDoubleValue());
    }

    /**
     * Equal heights for the main stack's shown views: each split's lower share is its shown views
     * below over all shown views from it down. Side columns keep their own widths (nothing to do).
     */
    void equalise(gJi settings) {
        for (StackSplit split : splits) {
            int above = countShown(split.E3(), settings), below = countShown(split.wGc(), settings);
            if (above == 0 || below == 0) continue; // one side empty: its share doesn't matter
            mcu position = split.cfM(settings);
            if (position != null) position.setDoubleValue((double) below / (above + below));
        }
    }

    private static boolean anyShown(akw node, gJi settings) {
        return countShown(node, settings) > 0;
    }

    private static int countShown(akw node, gJi settings) {
        if (node instanceof StackSplit split) return countShown(split.E3(), settings) + countShown(split.wGc(), settings);
        if (!(node instanceof akQ view)) return 0;
        AQw shown = view.cfM(settings);
        return shown != null && shown.getBoolValue() ? 1 : 0;
    }

    // ---------- The bottom bar ----------

    /** The row in place of the mode chooser: one button per view, in the stack's order. */
    dve buttons(aBW window, IBe style) {
        dWb row = dWb.Hnt(style); // a row in the bottom bar's style
        row.UNj(0.5F);            // centred vertically
        for (Entry entry : entries) {
            try {
                dAd button = button(window, entry);
                if (button != null) row.HL(button);
            } catch (Throwable failure) {
                Registry.LOG.log("could not build the button of " + entry.view().who(), failure);
            }
        }
        return row;
    }

    /** A view's button: click shows it alone, Cmd/Ctrl-click adds it to the split or removes it. */
    private dAd button(aBW window, Entry entry) {
        AQw shown = entry.node().cfM(window.RDJ());
        if (shown == null) return null;
        Registry.Declared view = entry.view();
        dAd button = entry.node().FzB(window); // the panel's button (Bitwig's for its panels, ViewNode's for a mod's)
        if (view.icon != null) button.TMF(view.icon); // the declared icon
        // window.RDJ(): the window's view settings, read at click time.
        button.cfM((dws) dmQ.cfM(new ViewAction(view.key() + "_alone", "Show " + view.label + " only"),
                (Runnable) () -> clicked(window.RDJ(), entry)));                         // click: the view alone
        button.cfM(eoV.lYc, dmQ.cfM(new ViewAction(view.key() + "_split", "Add " + view.label + " to the split, or remove it"),
                (Runnable) () -> toggleInSplit(window.RDJ(), entry)), false);           // Cmd on macOS, Ctrl elsewhere; on release, like a click
        // Setting the click action unbinds the button's lit state, so lit() binds it again afterwards.
        Registry.Declared scale = Scale.viewFor(entry.node()); // whose scale it follows
        button.z3I(view.label + ": click to show it alone, " + SPLIT_KEY + "+click to add it to the split or remove it"
                + ", right-click for " + (scale != null ? "its scale and " : "") + "equal sizes"); // the tooltip
        button.Hnt((Consumer<dKW>) popup -> menu(popup, window, view, scale)); // right-click
        return lit(button, entry.node(), shown);
    }

    /**
     * A side column panel's button, as Bitwig builds it for a panel outside the main area (its icon,
     * its place in the left or right group, lit while shown, bound by Bitwig after this): the same
     * clicks as a view button, with a click on the panel shown alone hiding it. Its right-click
     * menu is its scale's alone (button-menu): columns keep their own widths, no Equalise.
     */
    void sideButton(aBW window, Entry entry, dAd button) {
        watch(window.RDJ());
        Registry.Declared view = entry.view();
        button.cfM((dws) dmQ.cfM(new ViewAction(view.key() + "_alone", "Show " + view.label + " only, or hide it"),
                (Runnable) () -> clicked(window.RDJ(), entry)));                         // click: the panel alone, or hidden
        button.cfM(eoV.lYc, dmQ.cfM(new ViewAction(view.key() + "_split", "Add " + view.label + " to the split, or remove it"),
                (Runnable) () -> toggleInSplit(window.RDJ(), entry)), false);           // Cmd on macOS, Ctrl elsewhere
        Registry.Declared scale = Scale.viewFor(entry.node());
        button.z3I(view.label + ": click to show it alone (again to hide it), " + SPLIT_KEY + "+click to add it to the split or remove it"
                + (scale != null ? ", right-click for its scale" : ""));
    }

    private void menu(dKW popup, aBW window, Registry.Declared view, Registry.Declared scale) {
        try {
            if (scale != null) {
                Scale.fillMenu(popup, scale);
                popup.ELS(); // a separator
            }
            popup.cfM("Equalise View Sizes", (Runnable) () -> equalise(window.RDJ()));
        } catch (Throwable failure) {
            Registry.LOG.log("could not fill the menu of " + view.who(), failure);
        }
    }

    /** The bottom bar's styling, and the button lit while the panel is shown (as Bitwig's bottom bar does). */
    static dAd lit(dAd button, akQ panel, AQw shown) {
        button.MRY(kn.cfM());
        button.MRY(button.LIK(), shown); // selected while shown
        if (panel.sut().E3()) { // a touch window
            button.a_(w3.UNj);
            button.MRY(kn.cfM());
        } else {
            button.a_(w3.Hnt);
        }
        return button;
    }
}
