package bml.panels;

import java.util.List;

/**
 * The scale of a declared view or Bitwig panel, for a mod that places the setting itself
 * ({@link View#scaleSetting}{@code (false)}), for example in its own section of Settings. The
 * framework stores the scale and applies a change at once in every window, as from its own
 * setting or the right-click menu.
 */
public final class PanelScale {
    private PanelScale() {
    }

    /** The choices, in order: "75%", "100%" (the default), "125%", "150%", "175%", "200%". */
    public static List<String> choices() {
        return List.of(Scale.LABELS);
    }

    /** The index of the default choice (100%). */
    public static int defaultChoice() {
        return Scale.DEFAULT;
    }

    /** The chosen scale of one of the mod's declarations, an index into {@link #choices}; the default if it has none. */
    public static int get(String modId, String viewId) {
        Registry.Declared view = Registry.find(modId, viewId);
        return view != null ? Scale.scale(view) : Scale.DEFAULT;
    }

    /** Chooses the scale of one of the mod's declarations and applies it in every window; nothing if it has no scale. */
    public static void set(String modId, String viewId, int choice) {
        Registry.Declared view = Registry.find(modId, viewId);
        if (view != null) Scale.setScale(view, choice);
    }
}
