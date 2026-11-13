package bml.panels;

import bitwig.obf.dXs; // the show/hide state of a panel's collapsible part
import bitwig.obf.dXx; // the part of a layout that shows and hides a panel's widget
import bitwig.obf.dve; // a widget
import bitwig.obf.dyC; // a widget, as a container's child
import com.bitwig.base.geom.Jkg; // the side of a splitter a panel is on
import com.bitwig.base.geom.tfK; // a widget size (width, height)

/**
 * A view's collapsible part: Bitwig's own, built as Bitwig builds it, held (no slide animation),
 * and with a fixed minimum height while the view is shown. A side column (beside a left/right
 * split) asks for at least its content's minimum width, as Bitwig's own part does when shown, so a
 * panel is never narrower than it needs (the Project panel's tab row was cut off at a narrower
 * width).
 *
 * Held, Bitwig's part asks for no minimum at all, so a divider could squeeze a shown view to
 * nothing, out of reach between two other dividers. Bitwig's own part asks for its animated
 * height as its minimum while it slides, which overshoots and grew the window; a fixed minimum
 * never changes while views switch, so it can't.
 */
final class ViewPart extends dXx {
    /** The minimum height of a shown view, in Bitwig's layout units (scaled with the UI). */
    static final long MIN_HEIGHT_UNITS = 40;

    private dve content; // its widget

    private ViewPart() {
    }

    static ViewPart create(dve content, Jkg side) {
        ViewPart part = new ViewPart();
        dXs state = dXs.cfM(side, null, false); // Bitwig's show/hide state for that side
        part.cfM(state);                         // as dXx.cfM(dve, Jkg) does
        part.H_();
        part.cfM((dyC) content);                 // its widget
        part.content = content;
        state.cfM(part);
        state.MRY = true;                        // held: shows and hides at once
        return part;
    }

    /**
     * Its minimum size: none while hidden; while shown, a fixed height, or beside a left/right
     * split a width of at least its content's minimum width (tJi(): the content's minimum, as
     * Bitwig's own part reports it while shown).
     */
    @Override
    public tfK mL_() {
        if (!Hnt) return tfK.cfM(); // Hnt: shown
        long min = eLY(MIN_HEIGHT_UNITS); // eLY: layout units to pixels
        Jkg side = gfw();                 // its side of the splitter
        if (side == Jkg.cfM || side == Jkg.MRY) {
            tfK own = content != null ? content.tJi() : null;
            return new tfK(own != null ? Math.max(min, own.FzB()) : min, 0); // FzB(): the width
        }
        return new tfK(0, min);
    }
}
