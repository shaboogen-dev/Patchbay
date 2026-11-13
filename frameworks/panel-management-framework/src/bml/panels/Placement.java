package bml.panels;

import java.util.ArrayList;
import java.util.List;

import bitwig.obf.aBW; // a window's project view
import bitwig.obf.ajF; // a tab group (several panels, one shown)
import bitwig.obf.akC; // a display profile: its windows and their layouts
import bitwig.obf.akQ; // a panel
import bitwig.obf.akZ; // a splitter between two parts of a window's layout
import bitwig.obf.akj; // the mixer panel
import bitwig.obf.akn; // a mode (ARRANGE, MIX, EDIT)
import bitwig.obf.akq; // a main area: one layout per mode
import bitwig.obf.akw; // a part of a window's layout
import bitwig.obf.akz; // one mode's layout in a main area
import bitwig.obf.ala; // a splitter between two parts of a layout

/**
 * Puts the registered views into a display profile when Bitwig builds it. Beside Bitwig's own
 * modes, each view becomes one more tab of every tab group inside a mode's layout (Bitwig's lower
 * panel of the mode: in Single Large Monitor the "complementary panel" with Detail Editor, Devices and
 * Mixer), after Bitwig's own tabs, in the views' order. A mode without such a tab group doesn't
 * get the views (logged once per profile).
 *
 * A view that replaces the mixer (View.replaces) is, in a mode whose main content is the mixer
 * (MIX), a choice with it instead (MainChoice: the mixer and the view take turns as the main
 * content), and not also a tab of that mode's lower panel.
 */
final class Placement {
    private Placement() {
    }

    /** The profile's views placement; returns how many tabs were added. */
    static int place(akC profile) {
        List<Registry.Declared> views = Registry.views();
        if (profile == null || views.isEmpty()) return 0;
        int added = 0;
        int modesWithout = 0;
        for (int w = 0; w < profile.uWE(); w++) { // its windows
            for (akq main : mainAreas(profile.cfM(w).TMF())) {
                for (int c = 0; c < main.wGc(); c++) { // its modes' layouts
                    akz mode = main.cfM(c);
                    MainChoice choice = mainChoice(mode, views);
                    if (choice != null) added += choice.lYc().size() - 1;
                    List<ajF> groups = new ArrayList<>();
                    if (mode.TMF() != null) tabGroups(mode.TMF(), groups);
                    groups.remove(choice);
                    if (groups.isEmpty() && choice == null) modesWithout++;
                    for (ajF group : groups) {
                        for (Registry.Declared view : views) {
                            if (choice != null && view.replaces != null) continue; // this mode has it as the main content's choice
                            try {
                                ViewNode node = new ViewNode();
                                node.init(view, false);
                                group.cfM(node); // one more tab, after the group's own (ajL.cfM(akQ), as Bitwig adds its tabs)
                                added++;
                            } catch (Throwable failure) {
                                Registry.LOG.log("view " + view.who() + " could not be placed in " + group.tSS(), failure);
                            }
                        }
                    }
                }
            }
        }
        if (added == 0) {
            Registry.LOG.log("display profile " + profile.MIO() + " has no tab group in a mode's layout; the views aren't shown in it");
        } else if (modesWithout > 0) {
            Registry.LOG.log("display profile " + profile.MIO() + ": " + modesWithout
                    + " mode layout(s) have no tab group; the views aren't shown there");
        }
        return added;
    }

    /**
     * In a mode whose main content is the mixer, and with views that replace the mixer: the
     * mixer's place becomes a choice between the mixer and those views (the mixer first and by
     * default). Null when the mode's main content isn't the mixer or no view replaces it.
     */
    private static MainChoice mainChoice(akz mode, List<Registry.Declared> views) {
        List<Registry.Declared> replacing = new ArrayList<>();
        for (Registry.Declared view : views) if ("mixer".equals(view.replaces)) replacing.add(view);
        if (replacing.isEmpty() || mode.TMF() == null) return null;
        akQ mixer = mainPanel(mode.TMF(), akj.class);
        if (mixer == null || !(mixer.uva() instanceof akZ parent)) return null; // uva(): its parent part
        try {
            boolean first = parent.E3() == mixer;
            MainChoice choice = MainChoice.create(mixer.tSS() + "_choice");
            if (first) parent.MRY(akq.cfM("panel-stand-in")); // takes the mixer out (parts are moved through a stand-in)
            else parent.IwV(akq.cfM("panel-stand-in"));
            choice.cfM(mixer); // the mixer: the first choice
            for (Registry.Declared view : replacing) {
                ViewNode node = new ViewNode();
                node.init(view, false);
                choice.cfM(node);
            }
            choice.MRY(0); // the mixer by default
            if (first) parent.MRY(choice);
            else parent.IwV(choice);
            return choice;
        } catch (Throwable failure) {
            Registry.LOG.log("could not make the mixer's place a choice in a mode's layout", failure);
            return null;
        }
    }

    /** A panel of that class in a mode's layout, outside its tab groups (its main content), or null. */
    private static akQ mainPanel(akw node, Class<?> panelClass) {
        if (node instanceof ajF || node instanceof akq) return null; // a tab group's tabs aren't main content
        if (panelClass.isInstance(node)) return (akQ) node;
        if (node instanceof ala split) {
            akQ found = split.E3() != null ? mainPanel(split.E3(), panelClass) : null;
            return found != null ? found : split.wGc() != null ? mainPanel(split.wGc(), panelClass) : null;
        }
        return null;
    }

    /** The main areas (mode switches) of a window's layout. */
    private static List<akq> mainAreas(akw root) {
        List<akq> found = new ArrayList<>();
        collectMain(root, found);
        return found;
    }

    private static void collectMain(akw node, List<akq> found) {
        if (node instanceof akq main) {
            found.add(main);
            return;
        }
        if (node instanceof ala split) {
            if (split.E3() != null) collectMain(split.E3(), found);
            if (split.wGc() != null) collectMain(split.wGc(), found);
        }
    }

    /** The tab groups in a mode's layout (not inside a nested main area). */
    private static void tabGroups(akw node, List<ajF> found) {
        if (node instanceof ajF group) {
            found.add(group);
            return;
        }
        if (node instanceof ala split) {
            if (split.E3() != null) tabGroups(split.E3(), found);
            if (split.wGc() != null) tabGroups(split.wGc(), found);
        }
    }

    /** The node of a view in a window's current mode, or null if the view isn't there. */
    static ViewNode nodeIn(aBW window, Registry.Declared view) {
        akC profile = window.puu();             // the window's display profile
        akn mode = window.RDJ().vpw();          // the current mode (from the window's view settings)
        akw root = profile.cfM(window.sc()).TMF(); // this window's layout
        for (akq main : mainAreas(root)) {
            for (int c = 0; c < main.wGc(); c++) {
                akz layout = main.cfM(c);
                if (!layout.cfM(mode) || layout.TMF() == null) continue;
                ViewNode node = find(layout.TMF(), view);
                if (node != null) return node;
            }
        }
        return null;
    }

    private static ViewNode find(akw node, Registry.Declared view) {
        if (node instanceof ajF group) {
            for (Object tab : group.lYc()) {
                if (tab instanceof ViewNode v && v.view() == view) return v;
            }
            return null;
        }
        if (node instanceof ala split) {
            ViewNode first = split.E3() != null ? find(split.E3(), view) : null;
            return first != null ? first : split.wGc() != null ? find(split.wGc(), view) : null;
        }
        return null;
    }
}
