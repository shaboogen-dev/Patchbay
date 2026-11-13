package bml.panels;

import bitwig.obf.AQw; // a saved on/off value (a panel's "is shown")
import bitwig.obf.LJt; // a saved text value (a tab group's choice)
import bitwig.obf.OpO; // the bottom bar's button padding
import bitwig.obf.aBW; // a window's project view
import bitwig.obf.aYH; // a panel's widget
import bitwig.obf.ajF; // a tab group (several panels, one shown)
import bitwig.obf.akQ; // a panel
import bitwig.obf.akS; // Bitwig's generic description of a panel (its meta)
import bitwig.obf.dAd; // a toggle button widget
import bitwig.obf.dUZ; // the saved values a part of a layout declares
import bitwig.obf.dVe; // a saved text value
import bitwig.obf.dmQ; // a clickable action that runs a Runnable
import bitwig.obf.dve; // a widget
import bitwig.obf.dws; // an action, as a button takes it
import bitwig.obf.elA; // a widget style from the theme
import bitwig.obf.kn; // the bottom bar's button colour

/**
 * A view's panel node, the framework's own: one per place the view is in. The framework names it,
 * gives it its icon, title and button, declares the view's saved values and puts it in the layout;
 * the mod supplies the content (View.content). Not part of the API: a mod's code compiles against
 * Bitwig's names through the mod kit, so the API passes Bitwig objects only as Object.
 *
 * Beside Bitwig's own modes (ARRANGE / MIX / EDIT) a view is one more tab of each mode's lower tab
 * group: its button sits in the bottom bar's row for that mode, next to Bitwig's tabs, and a click
 * shows it in the lower panel (or closes the panel if it is already shown), as Bitwig's own tabs
 * do. Each mode has its own node, so each mode remembers its own lower panel.
 */
final class ViewNode extends akQ {
    private Registry.Declared view;

    ViewNode() {
    }

    /** The view's content for one window, from the mod (View.content). */
    private dve content(aBW window) {
        ViewNode node = this;
        return (dve) view.content.apply(new ViewContext() {
            @Override
            public Object window() {
                return window;
            }

            @Override
            public boolean shown() {
                AQw shown = node.cfM(window.RDJ()); // its "is shown" in a stack; none as a tab
                return shown == null || shown.getBoolValue();
            }

            @Override
            public String savedKey(String key) {
                return view.savedKey(key);
            }
        });
    }

    /**
     * The view's saved values, declared as Bitwig's panels declare theirs (the mixer its fader
     * height): one per profile under the view's own key, declared by the first of its nodes
     * (Bitwig's saved values allow each name once).
     */
    @Override
    public void cfM(dUZ values) {
        for (var text : view.savedTexts.entrySet()) {
            String key = view.savedKey(text.getKey());
            if (values.cfM(key) == null) values.cfM(dVe.cfM(key, text.getValue()));
        }
    }

    /**
     * Called by the framework once, before the node is placed: as a tab of a mode's lower panel
     * (shown by its tab group's choice) or as a view of a stack (its own "is shown", hidden by default).
     */
    final void init(Registry.Declared view, boolean inStack) {
        this.view = view;
        IwV(view.nodeName()); // its name: the path segment of its saved values
        ODA = view.icon;      // its icon
        vpw = view.label;     // its button's text
        lYc(inStack);         // in a stack: an "is shown" of its own; a tab: none
        if (inStack) TtY(false); // hidden by default
        H_();                 // Bitwig's initialisation
    }

    Registry.Declared view() {
        return view;
    }

    /** Its title (Bitwig's "Show ... Panel" menu item). */
    @Override
    public String ERO() {
        return view.label;
    }

    /** Its widget, built once per window: Bitwig's panel widget, holding the content at the view's scale. */
    @Override
    public aYH MRY(aBW window) {
        return ViewWidget.create(this, window, content(window));
    }

    /** Bitwig's typed self. */
    @Override
    public akQ iHb() {
        return this;
    }

    /** Its meta: Bitwig's generic panel description (the abstract base throws). */
    @Override
    public akS wGc() {
        return akS.wGc;
    }

    /**
     * Its button, as Bitwig builds a panel's button (akQ.FzB): the same calls in the same order,
     * with the framework's click action in place of Bitwig's, which Bitwig looks up by panel class
     * and has none for a mod's panel. A tab group then binds its lit state to its choice.
     */
    @Override
    public dAd FzB(aBW window) {
        dAd button = dAd.gG();                                   // a toggle button
        button.cfM((dws) dmQ.cfM(new ViewAction(view.key() + "_click", "Show " + view.label),
                (Runnable) () -> toggle(window)));               // the click
        button.TMF(MJs());                                       // the icon
        button.z3I(vpw);                                         // the text field Bitwig sets
        button.MRY(kn.cfM());                                    // the colour
        button.a_(OpO.cfM());                                    // the padding
        button.z3I("Show " + ERO() + " Panel");                  // the tooltip
        button.TtY(true);
        button.cfM((elA) button.jl_().znC().cfM("Borderless")); // Bitwig's borderless style, through the style setter dAE.cfM(elA)
        Scale.addMenu(button, view);                             // right-click: its scale
        return button;
    }

    /**
     * Shows this node's tab in its tab group, or closes the group if it is already shown: what
     * Bitwig's panel toggle (aBW.cfM(Class, boolean)) does for a tab, for this node.
     */
    void toggle(aBW window) {
        try {
            ajF group = (ajF) IwV(ajF.class); // the tab group holding it
            if (group == null) return;
            LJt choice = group.cfM(window.RDJ()); // its saved choice in this window's view settings
            if (choice == null) return;
            String name = tSS();
            if (group.PD3() && name.equals(choice.getValue())) choice.setValue(""); // shown: close the group
            else choice.setValue(name);                                             // show it
        } catch (Throwable failure) {
            Registry.LOG.log("could not show " + view.who(), failure);
        }
    }
}
