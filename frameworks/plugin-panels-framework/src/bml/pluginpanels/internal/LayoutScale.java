package bml.pluginpanels.internal;

import bitwig.obf.dtu; // Bitwig's scaled container: its subtree at its own UI scale

/**
 * Bitwig's scaled container for a layout's page, inside a chain that may itself be scaled (the device
 * panel at another scale than the window).
 *
 * <p>Bitwig's container takes its stored zoom ({@code ELS()}) again each time it is attached or told of a
 * scale change, and multiplies it by its parent's scale ({@code bbz()}). But when its scale changes it stores
 * that scale over the window's ({@code dtu.Hnt(int)}), the same thing only when its parent is at the window's
 * scale. Under a scaled parent every re-attach (selecting the track again) would multiply the scale by
 * parent / window. Here the stored zoom is relative to the parent, which is what it is multiplied by.
 */
final class LayoutScale extends dtu {
    private LayoutScale() {
    }

    /** Built as {@code dtu.LzO()} builds Bitwig's. */
    static LayoutScale create() {
        LayoutScale s = new LayoutScale();
        s.gfw(); // Bitwig's initialisation of the container
        s.H_();  // and of a widget
        return s;
    }

    @Override
    public void Hnt(int previous) {
        super.Hnt(previous);
        if (ZTl()) MRY((double) kC_() / bbz()); // ZTl(): attached to a window; this scale over the parent's
    }
}
