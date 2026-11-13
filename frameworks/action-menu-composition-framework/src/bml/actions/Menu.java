package bml.actions;

/**
 * A Bitwig menu an action can appear in. Two menus are the same when their IDs are.
 */
public final class Menu {
    /** Device right-click. The context provides {@link ActionContext#device()}. */
    public static final Menu DEVICE = new Menu("device");

    /**
     * Parameter right-click: knobs, sliders and value fields of device, mixer and modulator
     * parameters. The context provides {@link ActionContext#parameter()}.
     */
    public static final Menu PARAMETER = new Menu("parameter");

    /**
     * Right-click on an empty clip launcher slot (a slot with a clip opens the clip's selection
     * menu). The context provides {@link ActionContext#slot()}.
     */
    public static final Menu CLIP_SLOT = new Menu("clip-slot");

    /**
     * Right-click on an item in the browser's result list. The context provides
     * {@link ActionContext#item()} and {@link ActionContext#items()}.
     */
    public static final Menu BROWSER_ITEM = new Menu("browser-item");

    /**
     * Every selection right-click menu: tracks, clips, notes, time selections, events and the other
     * kinds of selection Bitwig builds a menu for. The context provides
     * {@link ActionContext#selectionKind()} and {@link ActionContext#selection()}.
     */
    public static final Menu SELECTION = new Menu("selection");

    /**
     * Every selection toolbar dropdown: the menu button of a kind of selection's toolbar, such as
     * the device header menu or the time-selection toolbar's menu. They act on the current
     * selection; the context provides {@link ActionContext#selectionKind()} only.
     */
    public static final Menu SELECTION_TOOLBAR = new Menu("selection-toolbar");

    /**
     * The Play menu: the dropdown beside the transport's Play button. Items come after Bitwig's own
     * Play options. The context provides nothing beyond {@link ActionContext#widget()}; an action
     * reads the project and transport itself.
     */
    public static final Menu PLAY = new Menu("play");

    private final String id;

    private Menu(String id) {
        this.id = id;
    }

    /**
     * The right-click menu of one kind of selection, by Bitwig's ID for that kind (listed in the
     * README). The context is as for {@link #SELECTION}.
     */
    public static Menu selection(String kindId) {
        if (kindId == null || kindId.isBlank()) throw new IllegalArgumentException("no selection kind");
        return new Menu("selection:" + kindId);
    }

    /**
     * The toolbar dropdown of one kind of selection, by Bitwig's ID for that kind. The context is as
     * for {@link #SELECTION_TOOLBAR}.
     */
    public static Menu selectionToolbar(String kindId) {
        if (kindId == null || kindId.isBlank()) throw new IllegalArgumentException("no selection kind");
        return new Menu("selection-toolbar:" + kindId);
    }

    /** The menu's ID as the documentation and logs name it. */
    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Menu m && m.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
