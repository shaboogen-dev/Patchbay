package bml.panels;

/** What a provider declares its views (and, for a layout mod, its layout policy) with. */
public interface Panels {
    /**
     * A view: one of the mod's panels, shown beside Bitwig's own. {@code viewId} is unique within
     * the mod ([A-Za-z][A-Za-z0-9_]*); {@code label} names it on its button, in its actions and in
     * Settings. Builder: {@link View#icon} and {@link View#node} are required.
     */
    View view(String viewId, String label);

    /**
     * One of Bitwig's own panels that the mod's layout policy places itself (for example the
     * arranger in a view stack, or the device panel). The policy binds it to the node it builds
     * ({@link Layout#bind}); the framework then gives it what a view gets in a stack (button, Show and
     * split actions, its icon) and a Panel Scale setting. Its icon is required only if it goes in a
     * stack.
     */
    BitwigView bitwigView(String viewId, String label);

    /**
     * One of Bitwig's panels matched by its class ({@code panelClass}, a subclass of Bitwig's panel
     * {@code akQ}) wherever Bitwig shows it: a mode's main content, a lower-panel tab, a panel beside
     * the main area, a tab of the browser side, a view stack or a side column. It gets a scale:
     * Bitwig's scaled container around the panel, a right-click scale menu on the panel's button and,
     * unless {@link BitwigView#scaleSetting}{@code (false)}, a Panel Scale setting. A panel that
     * matches several declarations follows the first (by mod ID, then declaration order); a Bitwig
     * view a policy binds with a scale of its own comes before any class match.
     */
    BitwigView bitwigPanel(String viewId, String label, Class<?> panelClass);

    /**
     * The mod's layout policy: called for every display profile Bitwig builds, before and after the
     * framework places the views ({@link LayoutPolicy}). Layout mods only (for example Panel:
     * Streamlined Views). {@code viewStack}: whether the policy builds a view stack
     * ({@link Layout#stack}). Bitwig registers actions before it builds any display profile, so the
     * framework adds the stack's actions (Add or Remove ... in the Split for the mods' views, Equalise
     * View Sizes) only when a policy says it builds one. A policy's own Bitwig views (with an icon)
     * get their Show and split actions either way, since they may be side columns.
     */
    void policy(LayoutPolicy policy, boolean viewStack);

    /**
     * The mod's layout policy, without saying whether it builds a view stack: taken as building one
     * when the mod declares a Bitwig view with an icon ({@link #bitwigView}).
     *
     * @deprecated say it with {@link #policy(LayoutPolicy, boolean)}.
     */
    @Deprecated
    void policy(LayoutPolicy policy);
}
