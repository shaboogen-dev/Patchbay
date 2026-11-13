package bml.pluginpanels.internal;

import bitwig.obf.Sxr; // a fixed size (layout units), clamped by a base policy
import bitwig.obf.ruA; // a widget's own sizes, as a size policy reads them
import bitwig.obf.svm; // a size policy

/**
 * A size policy with no limits of its own (preferred and minimum 0, no maximum). Bitwig's fixed size
 * {@code Sxr(n, base)} clamps n between the base's minimum and maximum, and its plain {@code Sxr(n)}
 * clamps it to the widget's own preferred and maximum size, so a label always got at least the width
 * of its current text. On this base, {@link #exactly(long)} is exactly n whatever the widget shows:
 * a value text that changes never changes its cell, so the panel is never laid out again.
 * Fixed cells as in adoxe (Daniil)'s "Design: Device Panels" plug-in cards (his card's FLEX size spec).
 */
final class FreeSize implements svm {
    static final FreeSize FREE = new FreeSize();

    private FreeSize() {
    }

    /** Exactly {@code n} layout units. */
    static svm exactly(long n) {
        return new Sxr(n, FREE);
    }

    @Override
    public long cfM(ruA widget) {
        return 0; // preferred
    }

    @Override
    public long MRY(ruA widget) {
        return 0; // minimum
    }

    @Override
    public long Hnt(ruA widget) {
        return Long.MAX_VALUE; // maximum
    }
}
