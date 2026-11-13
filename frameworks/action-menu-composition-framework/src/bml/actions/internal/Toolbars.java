package bml.actions.internal;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import bitwig.obf.bmf; // the menu builder of one kind of selection
import bitwig.obf.dKW; // a popup menu
import bitwig.obf.dsp; // a menu model
import bitwig.obf.dve; // a widget
import bml.actions.ActionContext;

/**
 * Selection toolbar dropdowns. Bitwig builds each from a menu model made for a kind of selection by
 * a project panel, which resolves the model's actions; the model is noted with its kind and panel,
 * and each time the dropdown fills its popup from the model, the mod section is appended, with
 * Bitwig's own actions resolved through the same panel.
 */
final class Toolbars {
    /** A model's kind and the panel that built it (weakly: the panel holds the model). */
    private record Model(String kind, WeakReference<dve> panel) {
    }

    private static final Map<dsp, Model> MODELS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<>());
    private static final ThreadLocal<bmf> BUILDING = new ThreadLocal<>();
    private static final ThreadLocal<dve> PANEL = new ThreadLocal<>();
    /**
     * The dropdown button filling its popup on this thread: Bitwig's actions run through it. Kept
     * weakly: a dropdown that is neither a selection toolbar nor the Play menu never clears it.
     */
    private static final ThreadLocal<WeakReference<dve>> BUTTON = new ThreadLocal<>();

    private Toolbars() {
    }

    static void building(bmf builder, dve panel) {
        BUILDING.set(builder);
        PANEL.set(panel);
    }

    static void built(dsp model) {
        bmf builder = BUILDING.get();
        dve panel = PANEL.get();
        BUILDING.remove();
        PANEL.remove();
        if (model != null && builder != null) MODELS.put(model, new Model(builder.lYc(), new WeakReference<>(panel))); // the kind's ID
    }

    static void filling(dve button) {
        BUTTON.set(new WeakReference<>(button));
    }

    /** The dropdown button filling its popup on this thread, or {@code null}. */
    static dve button() {
        WeakReference<dve> button = BUTTON.get();
        return button == null ? null : button.get();
    }

    /** The dropdown has been filled: the button is no longer kept. */
    static void done() {
        BUTTON.remove();
    }

    static void filled(dsp model, dKW popup) {
        dve button = button();
        BUTTON.remove();
        Model m = MODELS.get(model);
        if (m == null || popup == null) return;
        String kind = m.kind();
        dve panel = m.panel().get();
        if (SEEN.add(kind)) Registry.LOG.log("selection toolbar menu opened: " + kind);
        if (panel != null) Menus.append(popup, List.of(ActionContext.selectionToolbar(kind)), panel, true);
        else Menus.append(popup, List.of(ActionContext.selectionToolbar(kind)), button, false);
    }
}
