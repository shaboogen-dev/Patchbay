package bml.actions;

import java.util.function.Consumer;
import java.util.function.Predicate;

/** One declared action; each call returns the same action, so calls can be chained. */
public interface Action {
    /** The menus the action appears in. Without any, it exists only as a Bitwig action (shortcut, Commander). */
    Action in(Menu... menus);

    /** Required: runs when the action is chosen from a menu, a shortcut or the Commander. */
    Action onRun(Consumer<ActionContext> run);

    /**
     * Optional: the action exists only as rows in its menus. It can't be assigned a shortcut and isn't
     * listed in the Commander or the shortcut settings. Its label, menu label, icon and {@code visibleWhen}
     * work as usual; {@link #category} has no effect. Needs a menu, through {@link #in} or a submenu; without
     * one it is logged and skipped.
     */
    Action menuOnly();

    /**
     * Optional: the label in menus, when it differs from the action's label (which the Commander
     * and the shortcut settings show). Bitwig's own actions use this, for example to end the menu
     * label of an action that opens a dialog with "…". Default: the action's label.
     */
    Action menuLabel(String label);

    /** Optional: asked each time a menu opens; {@code false} leaves the item out of that menu. */
    Action visibleWhen(Predicate<ActionContext> visible);

    /**
     * Optional: the group the action is listed under in the Commander and the shortcut settings.
     * One of Bitwig's categories by its ID ({@code "arranger"}, {@code "edit"}, ...; see the README)
     * or one the mod declares with {@link Actions#category}. Default: Bitwig's "General".
     */
    Action category(String categoryId);

    /**
     * Optional: the action's icon, a resource the mod declares in its manifest's {@code resources}:
     * one of Bitwig's icons ({@code "kind": "bitwig"}, a path under {@code icons/}) or an SVG of the
     * mod's own ({@code "kind": "file"}). See the README. Shown with the action in menus, the Commander
     * and the shortcut settings.
     */
    Action icon(String resourceId);

    /**
     * Optional: bind the action on widgets of these classes (and their subclasses) instead of on the
     * project window. Its shortcut and the Commander then reach it only while focus is inside such a
     * widget, and {@link ActionContext#widget()} is that widget. Elsewhere the key goes to whatever else
     * Bitwig has on it. An action that shares a key with another action should use this; see the README.
     * Each class must be one of Bitwig's widget classes. No effect on a {@link #menuOnly} action.
     */
    Action bindOn(Class<?>... widgets);
}
