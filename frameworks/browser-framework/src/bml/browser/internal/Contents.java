package bml.browser.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.bitwig.flt.document.core.iface.pHd; // a project
import com.bitwig.flt.document.core.master.file_preview.hgq; // the cue player

import bitwig.obf.cFV; // the side browser
import bitwig.obf.dWe; // a stack of widgets, one shown
import bitwig.obf.dve; // a widget
import bitwig.obf.dyC; // a container's child
import bml.browser.Panel;

/**
 * The views' own content in the side browser ({@code View.content}). The side browser sets its
 * content once, at the end of its build ({@code cFV.cfM(dyC)}); with a content view active, the
 * framework sets a stack instead: Bitwig's content first, then the view's widget, the view's shown.
 * Bitwig's content stays built underneath (its session, filters and cue player live on), hidden.
 * A panel is disposed with its side browser ({@code cFV.iy_()}).
 */
final class Contents {
    /** The live panels, in the order they were built. */
    private static final List<PanelImpl> LIVE = new ArrayList<>();
    /** The views' state per project (weakly: it goes with the project). */
    private static final Map<pHd, Map<String, Map<String, Object>>> STATE = Collections.synchronizedMap(new WeakHashMap<>());

    private Contents() {
    }

    /**
     * What the side browser being built sets as its content: Bitwig's, or with a content view active, a
     * stack of Bitwig's and the view's widget. Any failure gives Bitwig's alone.
     */
    static dyC content(cFV browser, dyC stock) {
        Views.Declared view;
        try {
            State.get(); // the providers have declared
            view = Views.activeContent();
        } catch (Throwable t) {
            State.LOG.log("reading the active view failed; the side browser shows Bitwig's browser", t);
            return stock;
        }
        if (view == null || !(stock instanceof dve bitwigContent)) return stock;
        PanelImpl panel = new PanelImpl(view, browser);
        Object widget;
        try {
            widget = view.content.create(panel);
        } catch (Throwable t) {
            State.LOG.log("view " + view.key() + ": building its content failed", t);
            Views.fail("its content could not be built");
            return stock;
        }
        if (!(widget instanceof dve viewContent)) {
            Views.fail("its content factory returned " + (widget == null ? "null" : widget.getClass().getName())
                    + ", not a widget");
            return stock;
        }
        try {
            dWe stack = dWe.LzO();
            stack.HL(bitwigContent); // index 0: Bitwig's browser, kept built
            stack.HL(viewContent);   // index 1: the view's
            stack.Hnt(1);
            panel.built(viewContent);
            synchronized (LIVE) {
                LIVE.add(panel);
            }
            State.LOG.log("view " + view.key() + ": content shown in a side browser");
            return stack;
        } catch (Throwable t) {
            State.LOG.log("view " + view.key() + ": placing its content failed; the side browser shows Bitwig's browser", t);
            panel.dispose();
            return stock;
        }
    }

    /** A side browser is disposed: its panel too. */
    static void disposed(cFV browser) {
        List<PanelImpl> gone = new ArrayList<>();
        synchronized (LIVE) {
            for (Iterator<PanelImpl> it = LIVE.iterator(); it.hasNext(); ) {
                PanelImpl panel = it.next();
                if (panel.browserObject() == browser) {
                    it.remove();
                    gone.add(panel);
                }
            }
        }
        for (PanelImpl panel : gone) panel.dispose();
    }

    /** Focuses the search field the view showing in {@code browser} (a side browser) declared; false without one. */
    static boolean focusSearch(Object browser) {
        PanelImpl found = null;
        synchronized (LIVE) {
            for (PanelImpl panel : LIVE) {
                if (panel.browserObject() == browser && panel.live()) found = panel;
            }
        }
        return found != null && found.focusSearch();
    }

    /** The live panels of one view, the most recently built last. */
    static List<Panel> panels(Views.Declared view) {
        List<Panel> panels = new ArrayList<>();
        synchronized (LIVE) {
            for (PanelImpl panel : LIVE) {
                if (panel.view == view) panels.add(panel);
            }
        }
        return panels;
    }

    /** A cue player finished loading a file: tell the panel that asked for it. */
    static void previewLoaded(hgq player) {
        PanelImpl owner = null;
        synchronized (LIVE) {
            if (LIVE.isEmpty()) return;
            for (PanelImpl panel : LIVE) {
                if (panel.owns(player)) owner = panel;
            }
        }
        if (owner != null) owner.loaded(player);
    }

    /** One view's state map for a project, the same map while the project is open. */
    static Map<String, Object> state(pHd project, String viewKey) {
        if (project == null) return Collections.synchronizedMap(new HashMap<>());
        Map<String, Map<String, Object>> views = STATE.computeIfAbsent(project, p -> Collections.synchronizedMap(new HashMap<>()));
        return views.computeIfAbsent(viewKey, k -> Collections.synchronizedMap(new HashMap<>()));
    }
}
