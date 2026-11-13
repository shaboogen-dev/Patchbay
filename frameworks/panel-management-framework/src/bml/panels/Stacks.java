package bml.panels;

import bitwig.obf.dAd; // a toggle button widget

/** Lookups for layout mods, outside the profile callbacks (for example in their own hooks). */
public final class Stacks {
    private Stacks() {
    }

    /** The layout of a display profile ({@code akC}) the framework has seen, or null. */
    public static Layout layout(Object profile) {
        return Layouts.of(profile);
    }

    /**
     * Gives a button ({@code dAd}) the right-click scale menu of one of the mod's declared views or
     * Bitwig views (for example the device panel's button). Nothing if it has no scale.
     */
    public static void scaleMenu(Object button, String modId, String viewId) {
        Registry.Declared view = Registry.find(modId, viewId);
        if (view != null && button instanceof dAd b) Scale.addMenu(b, view);
    }
}
