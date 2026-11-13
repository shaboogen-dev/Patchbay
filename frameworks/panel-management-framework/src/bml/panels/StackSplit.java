package bml.panels;

import bitwig.obf.akZ; // a splitter between two parts of a window's layout
import bitwig.obf.akq; // a main area (here only a stand-in)
import bitwig.obf.akw; // a part of a window's layout

/**
 * A top/bottom split of a view stack. A layout part's saved values (visibility, divider position)
 * are keyed by its path of names; a stack's splits name their parts flat under the stack
 * ({@code <stack>.<view>}), and every split below the first is named by the view above it
 * ({@code <stack>.split_<view>}), so each view keeps its saved values whichever other views the
 * stack has (a mod switched off or on moves nothing).
 */
final class StackSplit extends akZ {
    private StackSplit root; // the stack's first split; null: this is it

    private StackSplit() {
    }

    /** A split named {@code name}, with stand-ins as its parts until they are set. */
    static StackSplit create(String name, StackSplit root) {
        StackSplit split = new StackSplit();
        split.root = root;
        split.MRY(name, false, akq.cfM("panel-stand-in"), akq.cfM("panel-stand-in")); // name, top/bottom, two stand-ins
        split.H_(); // Bitwig's initialisation
        return split;
    }

    /** The stack's own path: its first split's (Bitwig's path rule). */
    private String stackPath() {
        return root == null ? super.jG() : root.jG();
    }

    /** Its path (also the key of its saved divider position): the first split's is Bitwig's; the others are named by the view above them. */
    @Override
    public String jG() {
        if (root == null) return super.jG();
        akw upper = E3(); // E3(): the upper part
        return root.jG() + ".split_" + (upper != null ? upper.tSS() : tSS());
    }

    /** Bitwig's path rule for its two parts: flat under the stack. */
    @Override
    public String cfM(akw child) {
        return stackPath() + "." + child.tSS(); // tSS(): the part's name
    }
}
