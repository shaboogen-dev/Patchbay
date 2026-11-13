package bml.notifications.internal;

import bitwig.obf.FXB; // a notification's severity: cfM warning, MRY error, Hnt information
import bitwig.obf.cpI; // a notification-centre entry
import bitwig.obf.iFt; // the visitor every entry accepts (messages ignore it)
import bitwig.obf.npm; // a notification-centre message, posted through Bitwig's event route
import bitwig.obf.sO3; // literal (untranslated) text: a name and a description

import bml.notifications.Severity;

import java.util.List;

/**
 * A message in Bitwig's notification centre, as Bitwig's own literal messages are made: a subclass of
 * Bitwig's posted-message class with literal title and text. Built and posted on the UI thread.
 */
final class Entry extends npm {
    private final FXB severity;
    private final boolean closable;
    private final int seconds;
    private final boolean opensPanel;
    private final Record record;
    private int tries; // UI thread only
    private boolean replaced; // UI thread only: withdrawn by replace(), reported to watchers as replaced

    Entry(Spec spec, Record record) {
        super(new sO3(Text.title(spec.title(), spec.text()), Text.text(spec.text()))); // literal title and description, never an empty title
        this.severity = switch (spec.severity()) {
            case INFORMATION -> FXB.Hnt; // information
            case WARNING -> FXB.cfM;     // warning
            case ERROR -> FXB.MRY;       // error
        };
        this.closable = spec.closable();
        this.seconds = spec.dismissAfterSeconds();
        this.opensPanel = !spec.quiet();
        this.record = record;
        List<String> labels = spec.labels();
        List<Runnable> actions = spec.actions();
        for (int i = 0; i < labels.size(); i++) {
            Runnable action = actions.get(i);
            String label = labels.get(i);
            cfM(new sO3(Text.title(label, "OK")), () -> Center.runAction(label, action)); // adds a button to the message's row
        }
    }

    /** The mod's handle for this message. */
    bml.notifications.Message handle() {
        return record.handle;
    }

    /** Marks it as withdrawn for a replacement (CentreEntry.replaced). UI thread. */
    void markReplaced() {
        replaced = true;
    }

    /** Whether replace() withdrew it. UI thread. */
    boolean replaced() {
        return replaced;
    }

    /** Posts it (from the UI thread; Bitwig adds it at the end of the current event). */
    void postNow() {
        ((npm) this).ucQ(); // post through Bitwig's notification event
    }

    /**
     * Removes it. Bitwig adds a posted entry at the end of the event that posted it, so one withdrawn
     * straight after posting may not be in the store yet: then it is tried again on the next events.
     * UI thread.
     */
    void withdraw() {
        cpI self = this;
        if (self.lYc()) { // in the store
            self.hUR(); // remove it
        } else if (++tries <= 5) {
            Center.later(this::withdraw);
        }
    }

    @Override
    public FXB MRY() { // its severity
        return severity;
    }

    @Override
    public boolean IwV() { // has a close button
        return closable;
    }

    @Override
    public int wGc() { // seconds until it removes itself; 0 = stays
        return seconds;
    }

    @Override
    public boolean ab_() { // opens the centre's panel when added
        return opensPanel;
    }

    @Override
    public void cfM(iFt visitor) { // messages ignore the visitor, as Bitwig's own do
    }
}
