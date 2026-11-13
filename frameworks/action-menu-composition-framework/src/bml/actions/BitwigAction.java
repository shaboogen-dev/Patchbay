package bml.actions;

import java.util.function.Predicate;

/**
 * One of Bitwig's own actions, placed in menus by a mod. It appears as Bitwig shows it: its label,
 * its icon and its shortcut, and runs as it would from Bitwig's own menus.
 */
public interface BitwigAction {
    /** The menus it appears in. */
    BitwigAction in(Menu... menus);

    /** Optional: asked each time a menu opens; {@code false} leaves it out of that menu. */
    BitwigAction visibleWhen(Predicate<ActionContext> visible);

    /**
     * Optional: the action's icon, a resource the mod declares in its manifest's {@code resources}:
     * one of Bitwig's icons ({@code "kind": "bitwig"}, a path under {@code icons/}) or an SVG of the
     * mod's own ({@code "kind": "file"}). See the README. Used only if the action has no icon of its
     * own; it is then the action's icon everywhere Bitwig shows it.
     */
    BitwigAction icon(String resourceId);
}
