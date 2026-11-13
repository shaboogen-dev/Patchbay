package bml.browser;

import java.util.function.Consumer;

/**
 * A view a mod offers: a way of running the browser that takes over some features. The user picks
 * the active view in Settings ("Browser view"); only the active view's claims apply. If the view's
 * {@code onActive} fails, or its mod is turned off, its claims lapse and the user's values apply.
 */
public interface View {
    /** While active, {@code feature} has {@code value} in every browser (for per-browser features). */
    View claims(Feature feature, boolean value);

    /** While active, {@code feature} has {@code value} in the browsers {@code scope} names. */
    View claims(Feature feature, Scope scope, boolean value);

    /** Called when the view becomes active (the user chose it, or Bitwig started with it chosen). */
    View onActive(Consumer<ViewContext> action);

    /** Called when the view stops being active. */
    View onInactive(Consumer<ViewContext> action);

    /**
     * While active, the side browser panel shows the view's own widget, built by {@code factory} for
     * each panel, in place of Bitwig's browser content (which stays built, hidden). Choosing the view,
     * or another view, rebuilds the browser.
     */
    View content(ContentFactory factory);

    /**
     * While active, the side browser's results column goes where the view puts it: {@code factory}
     * gets the column for each side browser built and returns the widget shown in its place,
     * normally one that holds the column beside or under the view's own widgets. Choosing the view,
     * or another view, rebuilds the browser. A factory that throws or returns anything that isn't
     * a widget makes the view's claims lapse (logged) and the results show alone; so does a launch
     * after one whose slot never came on screen (Browser view goes back to Standard).
     */
    View results(ResultsFactory factory);

    /** True while this view is the chosen one and working (its claims apply). */
    boolean active();
}
