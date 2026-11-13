package bml.panels;

import bitwig.obf.dmX; // Bitwig's action class

/**
 * A click action on a view's button. Bitwig looks an action's name up in its translation tables by
 * action ID; this subclass answers with the view's own label instead, so the status bar hint reads
 * e.g. "CLICK Show Mix Map" and nothing is looked up.
 */
final class ViewAction extends dmX {
    private final String label;

    ViewAction(String id, String label) {
        super(id); // the action ID: never shown, never looked up
        this.label = label;
    }

    /** Bitwig's name lookup (protected in Bitwig; answered with the label). */
    @Override
    public String TtY() {
        return label;
    }
}
