package bml.browser;

/**
 * Places the side browser's results column for a view ({@link View#results}). Called on Bitwig's
 * UI thread while the side browser lays out its rows, once per side browser built with the view
 * active.
 */
@FunctionalInterface
public interface ResultsFactory {
    /**
     * Returns the widget the side browser shows where its results column goes: one of Bitwig's
     * widgets (a {@code dve}, as the mod kit names it), new for this browser, normally one that
     * holds {@link ResultsSlot#results()}. Throwing, or returning anything that isn't a widget, makes
     * the view's claims lapse (logged) and the results show alone.
     */
    Object create(ResultsSlot slot);
}
