package bml.panels;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import bitwig.obf.IzJ; // a fixed on/off value
import bitwig.obf.dAd; // a toggle button widget
import bitwig.obf.dKW; // a popup menu
import bitwig.obf.dpN; // Bitwig's UI event loop
import bitwig.obf.dtu; // Bitwig's scaled container: its subtree at its own UI scale
import bitwig.obf.dve; // a widget
import bitwig.obf.dyC; // a widget, as a container's child
import bml.api.Settings;

/**
 * Each view's scale (the mods' own views, the Bitwig views layout mods declare and the Bitwig
 * panels mods declare by class), relative to
 * the window's UI scale. The view's content sits in Bitwig's own
 * scaled container (dtu, as Bitwig's zoomable device views use it): everything in it lays out,
 * paints and takes input at the container's scale. The scale is a whole number of quarters of
 * 100%: round(zoom × the window's scale). A change applies at once to every open window.
 * Stored in the framework's settings, key {@code <mod-id>.<view-id>.scale} (the option's position).
 */
final class Scale {
    static final double[] ZOOMS = {0.75, 1.0, 1.25, 1.5, 1.75, 2.0};
    static final String[] LABELS = {"75%", "100%", "125%", "150%", "175%", "200%"};
    static final int DEFAULT = 1; // 100%

    private static final Settings STORE = Registry.LOG.settings();
    private static final Map<String, Integer> CHOSEN = new HashMap<>();
    private static final List<Container> CONTAINERS = new ArrayList<>();

    private record Container(WeakReference<dtu> widget, Registry.Declared view) {
    }

    private Scale() {
    }

    /**
     * The declaration whose scale a panel node follows: a mod's view, a Bitwig view a layout mod
     * bound with a scale of its own, else the first Bitwig panel declared by its class; null if none.
     */
    static Registry.Declared viewFor(Object node) {
        if (node instanceof ViewNode v) return v.view().scalable ? v.view() : null;
        Registry.Declared bound = Layouts.boundView(node);
        if (bound != null && bound.scalable) return bound;
        Registry.Declared matched = Registry.matchedFor(node);
        return matched != null && matched.scalable ? matched : null;
    }

    private static String key(Registry.Declared view) {
        return view.modId + "." + view.id + ".scale";
    }

    /** The view's chosen scale, an index into ZOOMS. */
    static synchronized int scale(Registry.Declared view) {
        if (!view.scalable) return DEFAULT;
        return CHOSEN.computeIfAbsent(key(view), k -> {
            int index = STORE.getInt(k, DEFAULT);
            return index >= 0 && index < ZOOMS.length ? index : DEFAULT;
        });
    }

    /** Chooses the view's scale, stores it and applies it to every open window. */
    static void setScale(Registry.Declared view, int index) {
        if (!view.scalable || index < 0 || index >= ZOOMS.length) return;
        STORE.putInt(key(view), index);
        synchronized (Scale.class) {
            if (Integer.valueOf(index).equals(CHOSEN.put(key(view), index))) return;
        }
        apply(view);
    }

    /** The view's content in a scaled container at its chosen scale. */
    static dve wrap(dve content, Registry.Declared view) {
        dtu scaled = dtu.LzO();               // Bitwig's scaled container
        scaled.cfM(ZOOMS[scale(view)]);       // its zoom relative to the window's scale (taken when it is attached)
        scaled.cfM((dyC) content);            // its one child
        synchronized (CONTAINERS) {
            CONTAINERS.removeIf(c -> c.widget().get() == null);
            CONTAINERS.add(new Container(new WeakReference<>(scaled), view));
        }
        return scaled;
    }

    /** Sets the view's containers in every window to its chosen scale, on the UI thread. */
    private static void apply(Registry.Declared view) {
        dpN.TVZ().asyncExec(() -> {
            List<dtu> widgets = new ArrayList<>();
            synchronized (CONTAINERS) {
                CONTAINERS.removeIf(c -> c.widget().get() == null);
                for (Container c : CONTAINERS) {
                    dtu widget = c.widget().get();
                    if (c.view() == view && widget != null) widgets.add(widget);
                }
            }
            double zoom = ZOOMS[scale(view)];
            for (dtu widget : widgets) {
                try {
                    widget.cfM(zoom); // attached: takes the new scale, re-lays out and repaints its subtree
                } catch (Throwable failure) {
                    Registry.LOG.log("could not scale " + view.who(), failure);
                }
            }
        });
    }

    /** Gives a view's button a right-click menu with the scale choices (Bitwig's own button menu hook). */
    static void addMenu(dAd button, Registry.Declared view) {
        if (!view.scalable) return;
        button.Hnt((Consumer<dKW>) popup -> fillMenu(popup, view)); // filled each time the menu opens
    }

    static void fillMenu(dKW popup, Registry.Declared view) {
        try {
            popup.MRY(view.label + " scale"); // a heading
            int current = scale(view);
            for (int i = 0; i < ZOOMS.length; i++) {
                int index = i;
                popup.cfM(LABELS[i], () -> setScale(view, index), IzJ.cfM(i == current)); // ticked: the current scale
            }
        } catch (Throwable failure) {
            Registry.LOG.log("could not fill the scale menu of " + view.who(), failure);
        }
    }
}
