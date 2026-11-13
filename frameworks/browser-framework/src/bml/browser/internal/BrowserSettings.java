package bml.browser.internal;

import bml.preferences.Page;
import bml.preferences.PreferenceProvider;
import bml.preferences.Preferences;
import bml.preferences.Section;

/**
 * The framework's one setting, on Settings → Mods: which view runs the side browser (Standard, or a
 * view a mod declares). The choice of view lives here because only one view can run the browser at
 * a time: it is the user's choice among every declared view, and the framework that runs the views
 * keeps it. Nothing else: parts are hidden by mods ({@code Browser.hide}) and a view's own options
 * are its mod's. Without any view declared there is nothing to choose, and the section isn't shown.
 */
public final class BrowserSettings implements PreferenceProvider {
    @Override
    public void declare(Preferences preferences) {
        State.get(); // providers declared, views known
        String[] options = Views.options();
        if (options.length < 2) return; // Standard only: no view to choose
        Page mods = preferences.page("mods", "Mods"); // the page every mod shares: this exact ID
        Section browser = preferences.section(mods, "browser-framework", "Browser");
        preferences.dropdown("view", "Browser view", options)
                   .in(browser)
                   .value(Views::chosen, Views::choose);
    }
}
