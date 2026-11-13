package bml.panels;

import bml.preferences.Page;
import bml.preferences.PreferenceProvider;
import bml.preferences.Preferences;
import bml.preferences.Section;

/**
 * The framework's own registration with the Settings framework: Settings > User Interface >
 * Panel Scale, one dropdown per declaration that has a scale and leaves its setting to the
 * framework (View.scaleSetting): Bitwig panels declared by class, the Bitwig views layout mods
 * declare with a scale of their own, then the mods' views. No section without any. A mod that
 * places its scale setting itself uses PanelScale. Not part of the API.
 */
public final class PanelSettings implements PreferenceProvider {
    @Override
    public void declare(Preferences preferences) {
        Section section = null;
        for (Registry.Declared view : Registry.all()) { // Bitwig panels by class, then layout mods' Bitwig views, then the mods' own
            if (!view.scalable || !view.listed) continue;
            if (section == null) section = preferences.section(Page.USER_INTERFACE, "panel_management_framework_scale", "Panel Scale");
            preferences.dropdown("scale_" + view.key(), view.label, Scale.LABELS)
                       .in(section)
                       .value(() -> Scale.scale(view), index -> Scale.setScale(view, index));
        }
    }
}
