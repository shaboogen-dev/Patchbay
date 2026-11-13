package bml.browser;

/**
 * The browsers' features. The value in effect is, first that wins: the claim of the view the user
 * has chosen, while it is active; off while any loaded mod hides the feature
 * ({@link Browser#hide}); the stored value ({@link #set}, on by default for most features).
 * {@code scope} matters only for {@link Feature#perBrowser()} features ({@link Scope#BOTH}: both
 * browsers); the others apply to the side browser. Call on Bitwig's UI thread.
 */
public interface Features {
    /** The value in effect: the active view's claim, else off while a loaded mod hides it, else the stored value. */
    boolean get(Feature feature, Scope scope);

    /** The stored value, whatever a view claims or a mod hides. */
    boolean userValue(Feature feature, Scope scope);

    /**
     * Sets the stored value and applies it at once. Refused (returns false, nothing changes) while
     * the active view claims the feature in that scope.
     *
     * @deprecated the framework keeps the value for the mod, so it stays applied after the mod is
     *     turned off. Use {@link Browser#hide}, which holds a feature off only while the mod is
     *     loaded, and keep the mod's own options in its own settings. Still works; a stored value
     *     that isn't the default is applied by the framework until it is set back.
     */
    @Deprecated
    boolean set(Feature feature, Scope scope, boolean on);

    /** The name of the view that claims the feature in that scope, or null. */
    String controlledBy(Feature feature, Scope scope);

    /** Calls {@code listener} after any value in effect may have changed. */
    void onChange(Runnable listener);
}
