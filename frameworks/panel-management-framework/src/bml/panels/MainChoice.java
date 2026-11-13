package bml.panels;

import bitwig.obf.ajF; // a tab group (several panels, one shown)
import bitwig.obf.akw; // a part of a window's layout

/**
 * A mode's main content as a choice between one of Bitwig's panels and the views that replace it
 * (View.replaces): Bitwig's own tab group, not collapsible (one of them is always shown), the
 * Bitwig panel shown by default. Its buttons go in the bottom bar's row for the mode, as a lower
 * panel's tabs do, and Bitwig's own toggle of the panel (for example Toggle Mixer Panel) chooses
 * it again. Its parts keep the paths they have without it, so the Bitwig panel's saved values stay
 * where they were; the choice itself is saved under the group's own name.
 */
final class MainChoice extends ajF {
    private MainChoice() {
    }

    static MainChoice create(String name) {
        MainChoice group = new MainChoice();
        group.MRY(name); // its name (as ajF.cfM(String) names a tab group)
        group.H_();      // Bitwig's initialisation
        return group;
    }

    /** Bitwig's path rule for its parts: the path each has without this group. */
    @Override
    public String cfM(akw child) {
        akw parent = uva(); // uva(): its parent part (a splitter, whose rule is its path, a dot, the part's name)
        return parent != null ? parent.jG() + "." + child.tSS() : child.tSS();
    }
}
