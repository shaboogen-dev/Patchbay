package bml.panels;

import bitwig.obf.aBW; // a window's project view
import bitwig.obf.aYH; // a panel's widget
import bitwig.obf.aYK; // Bitwig's generic description of a main panel widget (its meta)
import bitwig.obf.dve; // a widget
import com.bitwig.base.geom.tfK; // a widget size (width, height)

/**
 * A view's panel widget: Bitwig's panel widget (its layout node and panel style) with the view's
 * content, in Bitwig's scaled container, as its content widget (aYH.Hnt, "setContentWidget").
 */
final class ViewWidget extends aYH {
    private ViewWidget() {
    }

    static ViewWidget create(ViewNode node, aBW window, dve content) {
        ViewWidget widget = new ViewWidget();
        widget.cfM(node, true); // its layout node and Bitwig's panel style
        widget.H_();            // Bitwig's initialisation
        widget.Hnt(Scale.wrap(content, node.view())); // the content, at the view's scale
        return widget;
    }

    // ---------- Sizes: no minimum, no maximum (fills its place like a stock panel) ----------

    @Override
    public tfK mL_() {
        return tfK.cfM(); // zero
    }

    @Override
    public tfK nj_() {
        return tfK.MRY(); // unbounded
    }

    @Override
    public aYH Bpn() {
        return this; // Bitwig's typed self
    }

    @Override
    public aYK BBT() {
        return aYK.IwV; // Bitwig's generic main panel widget description
    }
}
