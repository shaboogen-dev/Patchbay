package bml.panels;

import bitwig.obf.aBW; // a window's project view
import bitwig.obf.aCp; // the project window
import bml.actions.ActionProvider;
import bml.actions.Actions;

/**
 * The framework's own registration with the Actions and Menus framework (Bitwig's Panel Management
 * category). For every view (and every Bitwig view with an icon, which can be in a stack):
 * "Show <label>" ({@code <mod_id>_show_<view_id>}): in a stack (main or side) shows it alone, else toggles its
 * tab in the window's current mode; "Add or Remove <label> in the Split"
 * ({@code <mod_id>_split_<view_id>}) and "Equalise View Sizes"
 * ({@code panel_management_framework_equalise}): only where they can act. Bitwig registers
 * actions before it builds any display profile, so this follows the declarations: a mod's own
 * view gets its split action, and Equalise exists, only when a layout policy builds a view stack
 * (Panels.policy(policy, true)); a Bitwig view gets Show and split only when its mod has a policy
 * (it is in that mod's stack or side columns). Not part of the API.
 */
public final class PanelActions implements ActionProvider {
    /** Bitwig's own "Panel Management" category in the shortcut settings and the Commander. */
    private static final String PANELS = "panel_management";

    @Override
    public void declare(Actions actions) {
        boolean stacks = Registry.viewStacks(); // a policy builds a view stack: the mods' views can be in it
        for (Registry.Declared view : Registry.all()) {
            if (view.icon == null) continue; // Bitwig panels outside stacks (panels declared by class for their scale, the device panel)
            boolean stackable = view.bitwig ? Registry.hasPolicy(view.modId) : stacks; // a Bitwig view: in its mod's stack or side columns
            if (view.bitwig && !stackable) continue; // a Bitwig view acts only in a stack or side columns
            actions.action(view.modKey() + "_show_" + view.id, "Show " + view.label)
                   .category(PANELS)
                   .onRun(context -> show(context.widget(), view));
            if (!stackable) continue;
            actions.action(view.modKey() + "_split_" + view.id, "Add or Remove " + view.label + " in the Split")
                   .category(PANELS)
                   .onRun(context -> split(context.widget(), view));
        }
        if (stacks) {
            actions.action("panel_management_framework_equalise", "Equalise View Sizes")
                   .category(PANELS)
                   .onRun(context -> equalise(context.widget()));
        }
    }

    private static aBW projectView(Object window) {
        return window instanceof aCp projectWindow ? projectWindow.Dah() : null; // Dah(): its current project view
    }

    private static void show(Object window, Registry.Declared view) {
        try {
            aBW projectView = projectView(window);
            if (projectView == null) return;
            Layouts layout = Layouts.of(projectView.puu());
            Stack.Entry entry = layout != null ? layout.entry(view.modId, view.id) : null; // in the main stack or a side
            if (entry != null) {
                layout.stackOf(entry).showAlone(projectView.RDJ(), entry); // shows it (a side panel too; never hides)
            } else if (!view.bitwig) {
                ViewNode node = Placement.nodeIn(projectView, view);
                if (node != null) node.toggle(projectView); // nothing in a mode without the view
            }
        } catch (Throwable failure) {
            Registry.LOG.log("could not show " + view.who(), failure);
        }
    }

    private static void split(Object window, Registry.Declared view) {
        try {
            aBW projectView = projectView(window);
            if (projectView == null) return;
            Layouts layout = Layouts.of(projectView.puu());
            Stack.Entry entry = layout != null ? layout.entry(view.modId, view.id) : null;
            if (entry != null) layout.stackOf(entry).toggleInSplit(projectView.RDJ(), entry); // without a stack: nothing
        } catch (Throwable failure) {
            Registry.LOG.log("could not add or remove " + view.who() + " in the split", failure);
        }
    }

    private static void equalise(Object window) {
        try {
            aBW projectView = projectView(window);
            if (projectView == null) return;
            Layouts layout = Layouts.of(projectView.puu());
            if (layout != null && layout.stack != null) layout.stack.equalise(projectView.RDJ());
        } catch (Throwable failure) {
            Registry.LOG.log("could not equalise the view sizes", failure);
        }
    }
}
