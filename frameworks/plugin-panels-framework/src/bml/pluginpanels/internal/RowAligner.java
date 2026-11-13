package bml.pluginpanels.internal;

import java.util.ArrayList;
import java.util.List;

import bitwig.obf.dmO; // a callback before each display frame
import bitwig.obf.dpM; // a display frame
import bitwig.obf.dpN; // Bitwig's UI event loop
import bitwig.obf.drd; // a subscription to the display frames
import bitwig.obf.dve; // a widget

/**
 * Lines up the knobs of one row: every label on one line (bottom-aligned) and every knob centred on
 * one line, whatever the label fonts and knob sizes. Bitwig's boxes place children only by the
 * box's own alignment and clamp sizes a layout sets to each widget's own limits, so the row is laid
 * out once, then this measures the labels' and knobs' real heights and sets {@link Spacer}s above
 * the label, between label and knob, and under the knob. It runs on display frames from the moment
 * the row is on screen until the heights have been stable for a while, so it also follows a font or
 * scale change that happens while it runs. The first measurement of each row is logged once a
 * session (sizes in Bitwig's layout pixels), to check the layout from Bitwig's log.
 */
final class RowAligner {
    private static final int STABLE_FRAMES = 30;
    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record Knob(String name, dve label, dve knob, dve value, Spacer top, Spacer middle, Spacer bottom) {
    }

    private final String where;
    private final List<Knob> knobs = new ArrayList<>();
    private final Spacer probe = Spacer.probe(this::start);
    private drd frames;
    private boolean subscribing;
    private int stable;
    private boolean logged;

    RowAligner(String where) {
        this.where = where;
    }

    /** The probe goes into the row: it starts the aligner once the row is painted. */
    Spacer probe() {
        return probe;
    }

    /** Registers one knob; returns its column: top spacer, label, middle spacer, knob, bottom spacer. */
    void add(String name, dve label, dve knob, dve value, Spacer top, Spacer middle, Spacer bottom) {
        knobs.add(new Knob(name, label, knob, value, top, middle, bottom));
    }

    boolean isEmpty() {
        return knobs.isEmpty();
    }

    private final dmO tick = new dmO() {
        @Override
        public void aboutToPaint(dpM frame) {
            onFrame();
        }
    };

    private void start() {
        if (frames != null || subscribing || knobs.isEmpty()) return;
        if (stable >= STABLE_FRAMES) return; // done
        subscribing = true;
        dpN.TVZ().asyncExec(() -> { // after the current paint
            subscribing = false;
            if (frames == null && probe.ZTl()) frames = drd.cfM(tick, probe); // ZTl(): attached to a window
        });
    }

    private void stop() {
        if (frames != null) {
            frames.cfM(); // unsubscribes
            frames = null;
        }
    }

    private void onFrame() {
        try {
            if (!probe.ZTl()) {
                stop();
                return;
            }
            long labelLine = 0, knobLine = 0;
            for (Knob k : knobs) {
                long lh = k.label.yiX(), kh = k.knob.yiX(); // laid-out heights
                if (lh <= 0 || kh <= 0) return;            // not laid out yet
                labelLine = Math.max(labelLine, lh);
                knobLine = Math.max(knobLine, kh);
            }
            if (!logged) {
                logged = true;
                if (LOGGED.add(where)) log(labelLine, knobLine); // once per row a session: Bitwig builds a page each time it shows
            }
            boolean changed = false;
            for (Knob k : knobs) {
                long lh = k.label.yiX(), kh = k.knob.yiX();
                long middle = (knobLine - kh) / 2;
                changed |= set(k.top, labelLine - lh);
                changed |= set(k.middle, middle);
                changed |= set(k.bottom, knobLine - kh - middle);
            }
            stable = changed ? 0 : stable + 1;
            if (stable >= STABLE_FRAMES) stop();
        } catch (Throwable t) {
            stop();
            Panels.report("align:" + where, "lining up the knobs of " + where + " failed", t);
        }
    }

    private static boolean set(Spacer s, long h) {
        if (s.height() == Math.max(0, h)) return false;
        s.height(h);
        return true;
    }

    private void log(long labelLine, long knobLine) {
        StringBuilder b = new StringBuilder("sizes in ").append(where).append(" (pixels; label line ").append(labelLine)
                .append(", knob line ").append(knobLine).append("):");
        for (Knob k : knobs) {
            b.append(" [").append(k.name).append(": label ").append(k.label.Sxo()).append('x').append(k.label.yiX())
             .append(" (min ").append(k.label.tJi()).append("), knob ").append(k.knob.Sxo()).append('x').append(k.knob.yiX())
             .append(", value ").append(k.value.Sxo()).append('x').append(k.value.yiX()).append(']');
        }
        Panels.MOD.log(b.toString());
    }
}
