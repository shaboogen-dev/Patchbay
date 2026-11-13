package bml.panels;

import java.util.function.Function;

/** A declared view. */
public interface View {
    /**
     * Its icon (required): a resource ID from the mod's manifest, either one of Bitwig's icons
     * ({@code "kind": "bitwig"}, a path under {@code icons/}, checked to exist) or the mod's own SVG
     * ({@code "kind": "file"}). Bitwig tints it with the theme: only its shape counts. A view whose
     * icon can't be used is logged and left out.
     */
    View icon(String resourceId);

    /**
     * Its content (required): called each time Bitwig builds a window's panel for the view, once
     * for every place the view is in, returning one of Bitwig's widgets ({@code dve}). The framework
     * puts it in Bitwig's scaled container (Panel Scale) inside Bitwig's panel widget.
     */
    View content(Function<ViewContext, Object> content);

    /**
     * A saved text value of the view's own, declared as Bitwig's panels declare theirs: kept with
     * the project's view settings (per display profile), never in the project. One value per
     * profile, shared by every place the view is in; its full key is
     * {@link ViewContext#savedKey}{@code (key)}.
     */
    View savedText(String key, String defaultValue);

    /**
     * One of Bitwig's panels the view can take the place of: {@code "mixer"}. In a mode whose main
     * content is that panel (MIX's mixer), the main content becomes a choice between the panel and
     * the view (both buttons in the bottom bar's row for that mode, one shown at a time, the panel
     * by default); there the view is not also a tab of the lower panel. Other modes keep it as a
     * lower-panel tab. Without a view stack only (in a stack every view has its own place).
     */
    View replaces(String bitwigPanel);

    /** Whether it has a scale: Panel Scale setting and right-click scale choices (default true). */
    View scale(boolean scalable);

    /**
     * Whether the framework lists its scale in Settings > User Interface > Panel Scale (default
     * true). Pass false to place the setting where the mod wants it, with {@link PanelScale}.
     */
    View scaleSetting(boolean listed);
}
