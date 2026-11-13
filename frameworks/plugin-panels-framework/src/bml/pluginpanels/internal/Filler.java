package bml.pluginpanels.internal;

import bitwig.obf.dns; // a widget, as Bitwig types it
import bitwig.obf.dve; // a widget
import com.bitwig.base.geom.tfK; // a widget size (width, height)

/**
 * An empty widget that takes a group's spare space: no minimum or preferred size, no maximum. A group
 * gives spare space only to children that can grow (a size policy alone doesn't make a widget grow: its
 * own maximum still limits it), so a {@link Spacer} with a free size stayed at zero and the container
 * button sat at the left of a toolbar without its search field (vv / dev.9 user run).
 */
final class Filler extends dve {
    private Filler() {
    }

    static Filler create() {
        Filler f = new Filler();
        f.H_(); // Bitwig's initialisation
        return f;
    }

    @Override
    public tfK mL_() {
        return tfK.cfM(); // no minimum
    }

    @Override
    public tfK iH_() {
        return tfK.cfM(); // no preferred size
    }

    @Override
    public tfK nj_() {
        return tfK.MRY(); // unbounded
    }

    @Override
    public dve MJ() {
        return this; // Bitwig's typed self
    }

    @Override
    public dns UZr() {
        return this; // Bitwig's typed self
    }
}
