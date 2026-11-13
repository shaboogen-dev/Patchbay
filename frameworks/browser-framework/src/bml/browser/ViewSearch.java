package bml.browser;

import bml.browser.internal.State;

/**
 * For mods that focus "the side browser's search field": while a view with its own content shows,
 * Bitwig's field is hidden under it. Ask here first; on false, focus Bitwig's field as before.
 *
 * <p>Call on Bitwig's UI thread. A mod that works with or without this framework can reach this
 * method without a {@code requires} entry: {@code Class.forName("bml.browser.ViewSearch", false,
 * <its own class>.getClassLoader())}, then {@code getMethod("focus", Object.class)}, treating
 * {@code ClassNotFoundException} (the framework not installed or not turned on) as false.
 */
public final class ViewSearch {
    private ViewSearch() {
    }

    /**
     * Focuses the search field that the view showing in {@code sideBrowser} (Bitwig's docked side
     * browser, a {@code cFV} as the mod kit names it) declared with {@link Panel#searchField}.
     *
     * @return true when it did; false when no view shows there or the view declared no field
     */
    public static boolean focus(Object sideBrowser) {
        if (sideBrowser == null) return false;
        try {
            return State.focusViewSearch(sideBrowser);
        } catch (Throwable t) {
            return false;
        }
    }
}
