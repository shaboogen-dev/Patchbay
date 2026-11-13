package bml.browser.internal;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import bitwig.obf.Fq; // Bitwig's UI-thread executor
import bitwig.obf.Jp; // a browser session
import bitwig.obf.cFV; // the side browser
import bitwig.obf.dve; // a widget
import bitwig.obf.dyC; // a splitter's child
import bml.browser.ResultsSlot;

/**
 * A view's results slot ({@code View.results}): where the side browser adds its results column
 * under the filter area, the active view's factory gets the column and returns the widget shown in
 * its place. Mechanism only: the framework draws nothing of its own there.
 *
 * <p>Safety: a launch marker is stored before the factory runs and cleared once its widget is on
 * screen 3 s later. A launch finding the marker (the last one crashed, hung or drew nothing) doesn't
 * call the factory and sets Browser view back to Standard. Any failure gives the column alone.
 */
final class Slots {
    /** The marker's key: the same as the side pane's before 0.3, so a marker left by it still counts. */
    private static final String PENDING = "pane.pending";
    private static final long CONFIRM_MS = 3000;
    private static final Timer TIMER = new Timer("bml-browser-slot", true);
    /** Whether this launch skips the slot, decided on first use. */
    private static Boolean skip;
    /** Every slot built, for its filter listeners. */
    private static final List<SlotImpl> LIVE = new ArrayList<>();

    private Slots() {
    }

    /** One side browser's slot. */
    private static final class SlotImpl implements ResultsSlot {
        final Views.Declared view;
        final WeakReference<cFV> browser;
        final WeakReference<Jp> session;
        final dve results;
        final List<Runnable> filterListeners = new ArrayList<>();

        SlotImpl(Views.Declared view, cFV browser, Jp session, dve results) {
            this.view = view;
            this.browser = new WeakReference<>(browser);
            this.session = new WeakReference<>(session);
            this.results = results;
        }

        @Override
        public String viewId() {
            return view.id;
        }

        @Override
        public Object browser() {
            return browser.get();
        }

        @Override
        public Object session() {
            return session.get();
        }

        @Override
        public Object results() {
            return results;
        }

        @Override
        public void onFiltersChanged(Runnable listener) {
            if (listener == null) return;
            synchronized (filterListeners) {
                filterListeners.add(listener);
            }
        }

        @Override
        public void fail(String reason) {
            if (view.active()) Views.fail(reason == null ? "its results slot failed" : reason);
        }

        void filtersChanged() {
            List<Runnable> listeners;
            synchronized (filterListeners) {
                listeners = List.copyOf(filterListeners);
            }
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (Throwable t) {
                    State.LOG.log("view " + view.key() + ": a filter listener failed", t);
                }
            }
        }
    }

    /** The widget the side browser adds as its results column: the column itself, or the active view's widget. */
    static dyC results(dyC column) {
        Views.Declared view;
        try {
            State.get(); // the providers have declared
            view = Views.activeResults();
        } catch (Throwable t) {
            State.LOG.log("reading the active view failed; the results are shown alone", t);
            return column;
        }
        if (view == null || skipped()) return column;
        cFV browser = SideBrowsers.building();
        Jp session = browser == null ? null : browser.EUG();
        if (!(column instanceof dve results) || session == null) {
            Views.fail("the side browser can't take its results slot");
            return column;
        }
        State.writeStringNow(PENDING, String.valueOf(System.currentTimeMillis()));
        SlotImpl slot = new SlotImpl(view, browser, session, results);
        Object widget;
        try {
            widget = view.results.create(slot);
        } catch (Throwable t) {
            State.writeString(PENDING, "");
            State.LOG.log("view " + view.key() + ": its results slot failed; the results are shown alone", t);
            Views.fail("its results slot could not be built");
            return column;
        }
        if (!(widget instanceof dve placed)) {
            State.writeString(PENDING, "");
            Views.fail("its results factory returned " + (widget == null ? "null" : widget.getClass().getName()) + ", not a widget");
            return column;
        }
        synchronized (LIVE) {
            LIVE.add(slot);
        }
        confirmLater(view, placed);
        State.LOG.log("view " + view.key() + ": results slot built");
        return placed;
    }

    /** A filter of {@code session} got or lost a value, or was offered or not: the slots of that session hear. */
    static void filtersChanged(Jp session) {
        List<SlotImpl> slots = new ArrayList<>();
        synchronized (LIVE) {
            for (Iterator<SlotImpl> it = LIVE.iterator(); it.hasNext(); ) {
                SlotImpl slot = it.next();
                if (slot.browser.get() == null) it.remove();
                else if (slot.session.get() == session) slots.add(slot);
            }
        }
        for (SlotImpl slot : slots) slot.filtersChanged();
    }

    /** A side browser is disposed: its slot is let go. */
    static void disposed(cFV browser) {
        synchronized (LIVE) {
            LIVE.removeIf(slot -> {
                cFV b = slot.browser.get();
                return b == null || b == browser;
            });
        }
    }

    /** True when the last launch's slot never confirmed: Browser view goes back to Standard. */
    private static boolean skipped() {
        if (skip == null) {
            skip = !State.readString(PENDING).isEmpty();
            if (skip) {
                // The last launch built a slot and never confirmed it (it crashed, hung, or nothing drew):
                // back to Standard, so the view can't take Bitwig down again until it is chosen again.
                State.writeString(PENDING, "");
                State.writeString("view", "");
                State.LOG.log("the last launch's results slot never confirmed; Browser view is set back to Standard");
                Views.fail("its results slot didn't confirm in the last launch");
            }
        }
        return skip;
    }

    /** After {@link #CONFIRM_MS}, on the UI thread: the widget on screen clears the marker; otherwise the view fails. */
    private static void confirmLater(Views.Declared view, dve placed) {
        WeakReference<dve> ref = new WeakReference<>(placed);
        TIMER.schedule(new TimerTask() {
            @Override
            public void run() {
                Fq.cfM(() -> {
                    dve widget = ref.get();
                    if (widget == null) return;
                    try {
                        if (widget.uev()) {
                            State.writeString(PENDING, "");
                            State.LOG.log("view " + view.key() + ": results slot drew");
                            return;
                        }
                        // the marker stays: the next launch sets Browser view back to Standard
                        State.LOG.log("view " + view.key() + ": results slot not on screen");
                        if (view.active()) Views.fail("its results slot drew nothing");
                    } catch (Throwable t) {
                        State.LOG.log("checking the results slot failed", t);
                    }
                });
            }
        }, CONFIRM_MS);
    }
}
