package bml.browser;

/** What a view is given when it becomes active or stops. */
public interface ViewContext {
    /** The view's ID, as declared. */
    String viewId();

    /** The browsers' features (the view's claims included). */
    Features features();

    /** The live panels showing the view's content ({@link View#content}), the most recently built last. */
    java.util.List<Panel> panels();
}
