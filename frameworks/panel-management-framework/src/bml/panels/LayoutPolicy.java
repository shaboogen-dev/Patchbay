package bml.panels;

/**
 * A layout mod's policy. For every display profile Bitwig builds, the framework runs, in this
 * order: every policy's {@link #layout} (by mod ID), then places the registered views (into the
 * profile's view stack if a policy made one, else as tabs of the modes' lower panels), then every
 * policy's {@link #arrange} (by mod ID). Only the framework patches Bitwig's profile builders.
 */
public interface LayoutPolicy {
    /** Builds the policy's layout (for example a view stack, {@link Layout#stack}). */
    void layout(Layout layout);

    /** Arranges the profile after the views are placed (for example which side panels go on). */
    default void arrange(Layout layout) {
    }

    /**
     * A project's view settings were read and the profile's stack shows no view: the policy may
     * show one ({@link Layout#showOnly}); if it doesn't, the stack's first view is shown.
     */
    default void nothingShown(Layout layout, Object viewSettings) {
    }

    /**
     * A view of the stack is to be shown alone (its button, its Show action). Return true if the
     * policy did it itself (for example by choosing a mode whose preset shows it); false lets the
     * framework show it alone.
     */
    default boolean showAlone(Layout layout, Object viewSettings, String modId, String viewId) {
        return false;
    }
}
