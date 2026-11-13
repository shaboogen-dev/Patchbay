package bml.actions;

/** What a provider declares its actions with. */
public interface Actions {
    /**
     * Declares a Bitwig action. Bitwig stores {@code actionId} in the user's shortcut assignments,
     * so it must never change after release; prefix it with the mod ID so IDs can't collide.
     * {@code label} is what menus, the Commander and the shortcut settings show.
     */
    Action action(String actionId, String label);

    /**
     * Declares a submenu. {@code submenuId} only needs to be unique within the mod. Actions and
     * submenus declared through it are shown inside it.
     */
    Submenu submenu(String submenuId, String label);

    /**
     * Declares an action category of the mod's own: a group of its own in the Commander and the
     * shortcut settings. Actions join it with {@link Action#category(String)}. Prefix the ID with
     * the mod ID; an ID that is one of Bitwig's categories means Bitwig's.
     */
    void category(String categoryId, String label);

    /**
     * Places one of Bitwig's own actions, by Bitwig's ID for it ({@code "paste_and_push"}), in
     * menus. An ID the installed Bitwig doesn't have is logged and the entry left out.
     */
    BitwigAction bitwigAction(String bitwigActionId);
}
