package bml.panels;

import bitwig.obf.akZ; // a splitter between two parts of a window's layout
import bitwig.obf.akq; // a main area (here only a stand-in)
import bitwig.obf.akw; // a part of a window's layout

/**
 * A left/right split of side columns: one column (a panel, keeping its own width) beside the rest
 * of the window towards the main area. The outermost column is Bitwig's own splitter; these are
 * nested inside it, one per further column. A layout part's saved values are keyed by its path of
 * names: every part of a column split is named flat under the outermost splitter, so the main
 * area keeps the path it has without the columns (nothing of it resets), and each column split is
 * named by its column ({@code <outer>.column_<panel>}), so its saved width stays with its panel.
 * A hidden column leaves no strip (view-splitter), in these and in Bitwig's outer splitter.
 */
final class ColumnSplit extends akZ {
    private final akZ outer;  // Bitwig's splitter holding the outermost column
    private final akw column; // its panel

    private ColumnSplit(akZ outer, akw column) {
        this.outer = outer;
        this.column = column;
    }

    /** The split for one column, with stand-ins as its parts until they are set. */
    static ColumnSplit create(akZ outer, akw column) {
        ColumnSplit split = new ColumnSplit(outer, column);
        split.MRY("column_" + column.tSS(), true, akq.cfM("panel-stand-in"), akq.cfM("panel-stand-in")); // name, left/right, two stand-ins
        split.H_(); // Bitwig's initialisation
        return split;
    }

    /** Its path (also the key of its saved divider position: the column's width). */
    @Override
    public String jG() {
        return outer.jG() + ".column_" + column.tSS();
    }

    /** Bitwig's path rule for its two parts: flat under the outermost splitter. */
    @Override
    public String cfM(akw child) {
        return outer.jG() + "." + child.tSS(); // tSS(): the part's name
    }
}
