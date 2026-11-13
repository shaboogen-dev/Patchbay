package bml.pluginpanels.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import bitwig.obf.LJt; // a text value a label can show
import bitwig.obf.VGL; // a value-change event: the value, its old and its new value
import bitwig.obf.dpN; // Bitwig's UI thread
import bitwig.obf.foz; // a listener
import bitwig.obf.uUk; // a plug-in parameter
import com.bitwig.flt.document.core.master.device.dKO; // a plug-in parameter, as stored (asks the plug-in for value texts)

/**
 * The plug-in's text for a parameter's value, kept right after a change. Bitwig's display text can
 * stay at an intermediate value after a drag ("99.3 %" where the plug-in shows "100.0 %"), because
 * it isn't asked for again once the value stops. After each change this asks the plug-in itself for
 * the text of the current value, 150, 500 and 1000 ms later, and shows its answer until the next
 * change. Each ask nudges the value by a few units in the last place, so it isn't answered from a
 * cache of the previous ask. Display only; the value is never touched.
 * Adapted from adoxe (Daniil)'s "Design: Device Panels" plug-in cards (his ParameterSettle: the asks, their timing and the nudge).
 */
final class SettledText implements LJt {
    private static final long[] ASK_MS = { 150, 500, 1000 };

    private final uUk parameter;
    private final dKO stored;
    private final List<foz> listeners = new ArrayList<>();
    private final foz onValue = event -> moved();
    private final foz onText = event -> changed(current());

    private volatile String fresh; // the plug-in's latest answer for the current value, or null
    private int gen;               // bumped by every change; an answer for an older value is dropped
    private int step;              // the next ask (0..2)
    private long movedAt;
    private boolean queued;

    SettledText(uUk parameter) {
        this.parameter = parameter;
        this.stored = parameter instanceof dKO d ? d : null;
    }

    private String current() {
        String f = fresh;
        return f != null ? f : Plugin.display(parameter);
    }

    @Override
    public Object getValue() {
        return current();
    }

    @Override
    public void setValue(Object value) {
        // display only
    }

    @Override
    public boolean isSettable() {
        return false;
    }

    @Override
    public boolean isObservable() {
        return true;
    }

    @Override
    public synchronized void addListener(foz listener) {
        if (listeners.isEmpty()) {
            parameter.cfM(onValue); // value listener
            LJt text = parameter.ODA(); // Bitwig's display text
            if (text != null) text.addListener(onText);
        }
        listeners.add(listener);
    }

    @Override
    public synchronized void removeListener(foz listener) {
        listeners.remove(listener);
        if (!listeners.isEmpty()) return;
        parameter.MRY(onValue);
        LJt text = parameter.ODA();
        if (text != null) text.removeListener(onText);
    }

    /** The value changed: Bitwig's text for now, then the plug-in's own, asked shortly after. */
    private void moved() {
        String before = current();
        synchronized (this) {
            gen++;
            fresh = null;
            step = 0;
            movedAt = System.nanoTime();
        }
        changed(before);
        queue(ASK_MS[0]);
    }

    private synchronized void queue(long ms) {
        if (queued || stored == null) return;
        queued = true;
        try {
            dpN.TVZ().cfM(this::ask, Math.max(0, ms)); // runs on Bitwig's UI thread after ms
        } catch (Throwable t) {
            queued = false;
            Panels.report("settle-timer", "asking the plug-in for " + Plugin.paramName(parameter) + "'s text failed", t);
        }
    }

    private void ask() {
        int k, asked;
        synchronized (this) {
            queued = false;
            if (listeners.isEmpty() || step >= ASK_MS.length) return;
            long since = (System.nanoTime() - movedAt) / 1_000_000L;
            if (since < ASK_MS[step]) {
                long wait = ASK_MS[step] - since;
                queued = false;
                queue(wait);
                return;
            }
            k = step++;
            asked = gen;
        }
        try {
            double at = Plugin.normalized(parameter);
            double probe = at;
            for (int i = 0; i <= k; i++) probe = at >= 0.5 ? Math.nextDown(probe) : Math.nextUp(probe);
            @SuppressWarnings("unchecked")
            CompletionStage<Object> text = (CompletionStage<Object>) stored.nVd(probe); // the plug-in's text for that value
            text.whenComplete((answer, failure) -> dpN.TVZ().asyncExec(() -> answered(asked, answer, failure)));
        } catch (Throwable t) {
            Panels.report("settle-ask", "asking the plug-in for " + Plugin.paramName(parameter) + "'s text failed", t);
        }
        synchronized (this) {
            if (step < ASK_MS.length) queue(ASK_MS[step] - (System.nanoTime() - movedAt) / 1_000_000L);
        }
    }

    private void answered(int asked, Object answer, Throwable failure) {
        if (failure != null || answer == null || answer.toString().isBlank()) return;
        String before;
        synchronized (this) {
            if (asked != gen) return; // the value has changed since
            String text = answer.toString();
            if (text.equals(fresh)) return;
            before = current();
            fresh = text;
        }
        changed(before);
    }

    /** Tells the label (Bitwig's binding expects its value-change event). */
    private void changed(String before) {
        List<foz> now;
        synchronized (this) {
            now = List.copyOf(listeners);
        }
        String after = current();
        for (foz l : now) {
            try {
                l.eventOccurred(new VGL(this, before, after));
            } catch (Throwable t) {
                Panels.report("settle-listener", "updating " + Plugin.paramName(parameter) + "'s text failed", t);
            }
        }
    }
}
