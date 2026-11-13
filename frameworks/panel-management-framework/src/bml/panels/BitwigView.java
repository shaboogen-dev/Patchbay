package bml.panels;

/** A declared Bitwig panel (see {@link Panels#bitwigView}). */
public interface BitwigView {
    /**
     * Its icon, as {@link View#icon}: a resource of the mod's manifest ({@code "kind": "bitwig"} or
     * the mod's own SVG). Required if the panel goes in a view stack.
     */
    BitwigView icon(String resourceId);

    /** Whether it has a scale: Panel Scale setting and right-click scale choices (default true). */
    BitwigView scale(boolean scalable);

    /** As {@link View#scaleSetting}: false leaves the setting to the mod ({@link PanelScale}). */
    BitwigView scaleSetting(boolean listed);
}
