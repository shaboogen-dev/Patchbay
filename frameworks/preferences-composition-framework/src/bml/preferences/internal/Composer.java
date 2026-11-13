package bml.preferences.internal;

import java.util.List;

import bitwig.obf.aBl; // Bitwig's Settings styles, with the divider between sections
import bitwig.obf.cHq; // the Settings window's sidebar
import bitwig.obf.cIe; // the Settings page base
import bitwig.obf.dWb; // a row or column of widgets
import bitwig.obf.dve; // a widget
import bml.preferences.Page;
import bml.preferences.Section;
import bml.preferences.internal.Controls.Control;

/** Puts the mods' controls into Bitwig's Settings as it builds them. Every step contains its own failures. */
final class Composer {
    private Composer() {
    }

    /**
     * A Bitwig section's body with the mods' controls below Bitwig's own, or the body unchanged
     * when the section has none (or anything fails).
     */
    static dve existing(Section section, dve body) {
        if (section == null || body == null) return body;
        try {
            Registry.load();
            List<Control> controls = Registry.controls(section);
            if (controls.isEmpty()) return body;
            dWb column = dWb.UNj(3); // a new column
            column.HL(body); // Bitwig's own controls, as they are
            if (add(column, controls) == 0) return body;
            return column;
        } catch (Throwable t) {
            Registry.LOG.log("section " + section + " left unchanged", t);
            return body;
        }
    }

    /** Adds the mods' new sections for a page to the bottom of its column, each after a divider. */
    static void appendSections(dWb column, Page page) {
        try {
            Registry.load();
            for (Registry.Declared s : Registry.sections(page)) {
                dve widget = sectionWidget(s);
                if (widget == null) continue;
                column.HL(aBl.Ucl()); // the divider Bitwig puts between its sections
                column.HL(widget);
            }
        } catch (Throwable t) {
            Registry.LOG.log("new sections on page " + page + " skipped", t);
        }
    }

    /** The label of the mod page being added to the sidebar right now (on Bitwig's UI thread), or null. */
    private static String addingPage;

    /** The literal label for the sidebar row Bitwig is creating, when it is a mod page's row; else null. */
    static String addingPage() {
        return addingPage;
    }

    /** Adds the mods' pages to the Settings sidebar, below Bitwig's. */
    static void appendPages(cHq sidebar) {
        try {
            Registry.load();
            for (Registry.Declared p : Registry.pages()) {
                try {
                    dWb column = dWb.UNj(3);
                    int added = 0;
                    for (Registry.Declared s : Registry.sections(Page.of(p.id()))) {
                        dve widget = sectionWidget(s);
                        if (widget == null) continue;
                        if (added++ > 0) column.HL(aBl.Ucl());
                        column.HL(widget);
                    }
                    if (added > 0) {
                        addingPage = p.label(); // its sidebar row gets this label (FrameworkHooks.sidebarRow)
                        try {
                            sidebar.cfM(p.label(), column); // add the page
                        } finally {
                            addingPage = null;
                        }
                    }
                } catch (Throwable t) {
                    Registry.LOG.log("page " + p.id() + " skipped", t);
                }
            }
        } catch (Throwable t) {
            Registry.LOG.log("mod pages skipped", t);
        }
    }

    /** A new section: Bitwig's section heading (with the mod's literal title) over its controls. */
    private static dve sectionWidget(Registry.Declared section) {
        dWb contents = dWb.UNj(3);
        if (add(contents, Registry.controls(Section.of(section.id()))) == 0) return null;
        return cIe.cfM(cIe.MRY(section.label()), contents); // literal heading, then the section body
    }

    /**
     * Adds each control's widget; a control whose widget fails is logged and left out. The labelled
     * rows share one label column, so their value controls start at one x ({@link LabelColumn}).
     */
    private static int add(dWb column, List<Control> controls) {
        int added = 0;
        LabelColumn labels = new LabelColumn();
        for (Control c : controls) {
            try {
                dve widget = c.widget();
                try {
                    labels.join(widget);
                } catch (Throwable t) {
                    Registry.LOG.log("control " + c.who() + " keeps Bitwig's label width", t);
                }
                column.HL(widget);
                added++;
            } catch (Throwable t) {
                Registry.LOG.log("control " + c.who() + " left out", t);
            }
        }
        return added;
    }
}
