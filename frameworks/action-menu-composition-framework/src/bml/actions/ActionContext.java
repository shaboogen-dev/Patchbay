package bml.actions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What an action was invoked on, captured when the menu opened. Every getter except
 * {@link #widget()} returns {@code null} when it doesn't apply, including for shortcuts and the
 * Commander.
 */
public final class ActionContext {
    private final Menu menu;
    private Object widget;
    private Object device;
    private String selectionKind;
    private Object selection;
    private Object parameter;
    private Object slot;
    private Object item;
    private List<Object> items;

    private ActionContext(Menu menu) {
        this.menu = menu;
    }

    /** For the framework: the context of a shortcut or the Commander, run from the widget holding the binding. */
    public static ActionContext shortcut(Object widget) {
        ActionContext c = new ActionContext(null);
        c.widget = widget;
        return c;
    }

    /** For the framework: this context, run from {@code widget}. */
    public ActionContext withWidget(Object widget) {
        ActionContext c = new ActionContext(menu);
        c.widget = widget;
        c.device = device;
        c.selectionKind = selectionKind;
        c.selection = selection;
        c.parameter = parameter;
        c.slot = slot;
        c.item = item;
        c.items = items;
        return c;
    }

    /** For the framework: the context of a device right-click. */
    public static ActionContext device(Object device) {
        ActionContext c = new ActionContext(Menu.DEVICE);
        c.device = device;
        return c;
    }

    /** For the framework: the context of a selection right-click. */
    public static ActionContext selection(String kindId, Object selection) {
        ActionContext c = new ActionContext(Menu.selection(kindId));
        c.selectionKind = kindId;
        c.selection = selection;
        return c;
    }

    /** For the framework: the context of a selection toolbar dropdown. */
    public static ActionContext selectionToolbar(String kindId) {
        ActionContext c = new ActionContext(Menu.selectionToolbar(kindId));
        c.selectionKind = kindId;
        return c;
    }

    /** For the framework: the context of the transport's Play menu. */
    public static ActionContext play() {
        return new ActionContext(Menu.PLAY);
    }

    /** For the framework: the context of a parameter right-click. */
    public static ActionContext parameter(Object parameter) {
        ActionContext c = new ActionContext(Menu.PARAMETER);
        c.parameter = parameter;
        return c;
    }

    /** For the framework: the context of an empty clip slot's right-click. */
    public static ActionContext clipSlot(Object slot) {
        ActionContext c = new ActionContext(Menu.CLIP_SLOT);
        c.slot = slot;
        return c;
    }

    /** For the framework: the context of a browser item's right-click. */
    public static ActionContext browserItem(Object item, List<?> items) {
        ActionContext c = new ActionContext(Menu.BROWSER_ITEM);
        c.item = item;
        c.items = items == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(items));
        return c;
    }

    /**
     * The menu the item was chosen from, or {@code null}. For a selection menu this is
     * {@code Menu.selection(kind)}, also when the item was placed with {@link Menu#SELECTION}.
     */
    public Menu menu() {
        return menu;
    }

    /**
     * The widget the action was run from (Bitwig's widget object): the clicked widget for a
     * right-click menu, the panel that built a toolbar dropdown; for a shortcut or the Commander, the
     * project window, or the widget it is bound on with {@link Action#bindOn}. Use it as the parent of
     * a dialog the action opens. {@code null} only if Bitwig gave none.
     */
    public Object widget() {
        return widget;
    }

    /** The clicked device: Bitwig's device object. */
    public Object device() {
        return device;
    }

    /** The kind of selection the menu was opened on: Bitwig's ID for it (see the README). */
    public String selectionKind() {
        return selectionKind;
    }

    /**
     * The selection the menu was opened on: Bitwig's selection object for that view. Not provided
     * for toolbar dropdowns, which act on the current selection.
     */
    public Object selection() {
        return selection;
    }

    /** The clicked parameter: Bitwig's parameter object. */
    public Object parameter() {
        return parameter;
    }

    /** The clicked empty clip slot: Bitwig's slot object. */
    public Object slot() {
        return slot;
    }

    /** The clicked browser item: Bitwig's object for it. */
    public Object item() {
        return item;
    }

    /** The browser items selected when the menu opened (usually including the clicked one). */
    public List<Object> items() {
        return items;
    }
}
