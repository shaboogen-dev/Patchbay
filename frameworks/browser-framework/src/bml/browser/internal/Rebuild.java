package bml.browser.internal;

import java.util.Timer;
import java.util.TimerTask;

import com.bitwig.flt.app.CHR; // the application

import bitwig.obf.doH; // the dialog windows (plug-in windows, undocked views)
import bitwig.obf.dpN; // Bitwig's UI event loop

/**
 * Rebuilds the side browser without restarting Bitwig: the windows are rebuilt from their display
 * profile, as Bitwig does when a display profile is chosen in Settings (the sequence Panel Sides
 * uses to apply its settings live): after the current event, with the documents' updates held
 * ({@code CHR.puu()} / {@code sc()}) and the open dialog windows hidden and shown again around
 * {@code CHR.kTh()}. Each window's side browser is built again; its session (source, search,
 * filters) is restored from the window's view settings, as when a project opens.
 *
 * <p>Requested when a change only takes effect when the browser is built (the side pane on or off,
 * its tabs). Requests within {@link #DEBOUNCE_MS} make one rebuild, so ticking several boxes
 * rebuilds once.
 */
final class Rebuild {
    private static final long DEBOUNCE_MS = 400;
    private static final Timer TIMER = new Timer("bml-browser-rebuild", true);
    private static TimerTask pending;

    private Rebuild() {
    }

    /** Rebuilds the side browser shortly (once for several requests). */
    static synchronized void soon(String why) {
        if (pending != null) pending.cancel();
        pending = new TimerTask() {
            @Override
            public void run() {
                dpN.TVZ().asyncExec(() -> now(why));
            }
        };
        TIMER.schedule(pending, DEBOUNCE_MS);
    }

    /** On the UI thread: rebuilds the windows, and with them the side browser. */
    private static void now(String why) {
        State.LOG.log("rebuilding the browser (" + why + ")");
        CHR app = CHR.Grk();
        app.puu(); // holds the documents' updates
        try {
            doH.JhA(); // hides the open dialog windows
            try {
                app.kTh(); // rebuilds the windows from the display profile
            } finally {
                doH.VpW(); // shows them again
            }
        } catch (Throwable t) {
            State.LOG.log("rebuilding the browser failed; restart Bitwig to apply the change", t);
        } finally {
            app.sc(); // releases the documents' updates
        }
    }
}
