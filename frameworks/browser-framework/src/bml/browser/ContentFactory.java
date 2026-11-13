package bml.browser;

/**
 * Builds a view's own content for one side browser panel ({@link View#content}). Called on Bitwig's
 * UI thread while the side browser is built, once per panel: when a window's Browser panel is
 * built with the view active (Bitwig starting, a project opening, the windows rebuilt after the user
 * chooses the view).
 */
@FunctionalInterface
public interface ContentFactory {
    /**
     * Returns the widget shown in the panel in place of Bitwig's browser content: one of Bitwig's
     * widgets (a {@code dve}, as the mod kit names it), new for this panel. {@code panel} is the
     * panel's helpers, for the widget to keep. Throwing, or returning anything that isn't a widget,
     * makes the view's claims and content lapse (logged) and the panel shows Bitwig's browser.
     */
    Object create(Panel panel);
}
