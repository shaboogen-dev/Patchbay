package bml.actions.internal;

import bitwig.obf.dmS; // Bitwig's action category
import bitwig.obf.dmX; // Bitwig's action class

/**
 * A mod's action as a real Bitwig action. Bitwig looks action labels up in its translation tables;
 * this subclass answers with the mod's labels instead, so menus, the Commander and the shortcut
 * settings show them.
 */
final class ModAction extends dmX {
    private final String label;
    private final String menuLabel;
    private final String icon;

    ModAction(String actionId, String label, String menuLabel, String icon) {
        super(actionId);
        this.label = label;
        this.menuLabel = menuLabel;
        this.icon = icon;
    }

    ModAction(String actionId, String label, String menuLabel, String icon, dmS category) {
        super(actionId, category);
        this.label = label;
        this.menuLabel = menuLabel;
        this.icon = icon;
    }

    /** Bitwig's label lookup (protected in Bitwig; answered with the mod's label). */
    @Override
    public String TtY() {
        return label;
    }

    /** Bitwig's menu label lookup: the mod's menu label, else Bitwig's (which falls back to the label). */
    @Override
    public String Hnt() {
        return menuLabel != null ? menuLabel : super.Hnt();
    }

    /** Bitwig's icon lookup: the resource path of one of Bitwig's icons, or none. */
    @Override
    public String fJR() {
        return icon != null ? icon : super.fJR();
    }
}
