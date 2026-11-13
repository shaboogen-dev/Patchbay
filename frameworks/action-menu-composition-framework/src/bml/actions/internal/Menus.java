package bml.actions.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import bitwig.obf.dKW; // a popup menu
import bitwig.obf.dmQ; // an action's binding
import bitwig.obf.dmX; // an action
import bitwig.obf.dsg; // a panel that resolves the actions of menus it builds
import bitwig.obf.dve; // a widget
import bml.actions.ActionContext;

/**
 * Mod items in Bitwig's popups. A hook on the code that builds a popup notes which menu it is and
 * what was clicked; when Bitwig has finished the popup, the mod section is appended. One popup can
 * be noted more than once (a device's menu is also a selection menu); each item then runs with the
 * first noted context it was placed in.
 */
final class Menus {
    private static final Map<dKW, List<ActionContext>> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    /** The widget whose popup is being built on this thread. */
    private static final ThreadLocal<dve> BUILDING = new ThreadLocal<>();
    /** Bitwig actions already logged as having no binding in a menu. */
    private static final Set<String> UNBOUND = Collections.synchronizedSet(new HashSet<>());

    private Menus() {
    }

    static void started(dve widget) {
        BUILDING.set(widget);
    }

    /** The widget whose popup is being built on this thread, or {@code null}. */
    static dve building() {
        return BUILDING.get();
    }

    static void opened(dKW popup, ActionContext context) {
        if (popup != null) PENDING.computeIfAbsent(popup, p -> new ArrayList<>()).add(context);
    }

    /** Called once Bitwig has added all its own entries, before the popup is shown. */
    static void finished(dKW popup) {
        List<ActionContext> contexts = PENDING.remove(popup);
        dve widget = BUILDING.get();
        BUILDING.remove();
        if (contexts != null) append(popup, contexts, widget, false);
    }

    /**
     * Appends the mod section for the menus a popup was opened as. Bitwig's own actions are run
     * through {@code widget}: the clicked widget, whose binding (or a parent's) is used, as in
     * Bitwig's own right-click rows; or, with {@code panel} set, the panel that built a toolbar
     * dropdown, which forwards to the focused editor, as in Bitwig's own toolbar rows. Without a
     * widget they are left out.
     */
    static void append(dKW popup, List<ActionContext> contexts, dve widget, boolean panel) {
        Target target = new Target(widget, panel);
        Map<Registry.Entry, ActionContext> placed = new IdentityHashMap<>();
        for (ActionContext opened : contexts) {
            ActionContext context = opened.withWidget(widget); // what the menu was opened from
            for (Registry.Entry e : Registry.top(context)) placed.putIfAbsent(e, context);
        }
        List<Registry.Entry> items = new ArrayList<>();
        for (var p : placed.entrySet()) if (shown(p.getKey(), p.getValue(), target)) items.add(p.getKey());
        if (items.isEmpty()) return;
        items.sort(Registry.ORDER);
        popup.ELS(); // a separator before the next row, as Bitwig's own sections use
        Set<dmX> rendered = new HashSet<>(); // a Bitwig action placed by two mods appears once
        for (Registry.Entry e : items) render(popup, e, placed.get(e), target, rendered);
    }

    /** What Bitwig's own actions in a popup run through. */
    private record Target(dve widget, boolean panel) {
    }

    /** Whether an entry is shown; a submenu is shown only if something in it is. */
    private static boolean shown(Registry.Entry e, ActionContext context, Target target) {
        if (e instanceof Registry.Declared a) return a.visibleIn(context);
        if (e instanceof Registry.Placed p) {
            if (!p.visibleIn(context)) return false;
            if (binding(target, p) != null) return true;
            if (UNBOUND.add(p.id + " in " + context.menu()))
                Registry.LOG.log("Bitwig action " + p.id + " has no binding in " + context.menu() + "; left out there");
            return false;
        }
        for (Registry.Entry c : ((Registry.Folder) e).children) if (shown(c, context, target)) return true;
        return false;
    }

    /** A Bitwig action's binding where the menu was opened, as Bitwig's own rows there get it. */
    private static dmQ binding(Target target, Registry.Placed p) {
        if (target.widget() == null) return null;
        try {
            dmX action = p.action();
            if (!target.panel()) return target.widget().FzB(action); // the clicked widget's binding, or a parent's
            dmQ own = target.widget().cfM(action); // the panel's binding, once it has one
            if (own != null || !(target.widget() instanceof dsg panel)) return own;
            return panel.MRY(action); // Bitwig's forwarding binding, as its toolbar rows get (created once)
        } catch (Throwable t) {
            Registry.LOG.log("Bitwig action " + p.id + " has no binding here", t);
            return null;
        }
    }

    private static void render(dKW popup, Registry.Entry e, ActionContext context, Target target, Set<dmX> rendered) {
        if (e instanceof Registry.Placed p) {
            if (rendered.add(p.action())) popup.MRY(binding(target, p)); // Bitwig's own row for it
            return;
        }
        if (e instanceof Registry.Declared a) {
            // Bitwig's own action row: its label, its shortcut, and this menu's context when run.
            dmQ binding = dmQ.cfM(a.bitwig, () -> a.run(context)); // a binding of the action to this callback
            popup.MRY(binding); // an action row
            return;
        }
        Registry.Folder f = (Registry.Folder) e;
        List<Registry.Entry> children = new ArrayList<>();
        for (Registry.Entry c : f.children) if (shown(c, context, target)) children.add(c);
        children.sort(Registry.ORDER);
        dKW child = popup.Hnt(f.label); // a submenu row and the popup it opens
        for (Registry.Entry c : children) render(child, c, context, target, rendered);
    }
}
