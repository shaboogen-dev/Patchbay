package bml.actions;

/**
 * A submenu of a mod's own. It is shown in the menus named with {@link #in}, and holds the actions
 * and submenus declared through it. It is left out of a menu when none of its items are visible.
 */
public interface Submenu {
    /** The menus the submenu appears in (for a nested submenu: none needed, it appears in its parent). */
    Submenu in(Menu... menus);

    /** Declares an action shown inside this submenu (see {@link Actions#action}). */
    Action action(String actionId, String label);

    /** Declares a submenu nested inside this one. */
    Submenu submenu(String submenuId, String label);

    /** Places one of Bitwig's own actions inside this submenu (see {@link Actions#bitwigAction}). */
    BitwigAction bitwigAction(String bitwigActionId);
}
