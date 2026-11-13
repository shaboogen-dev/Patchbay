package bml.notifications.internal;

import bitwig.obf.FXB; // a notification's severity: cfM warning, MRY error, Hnt information
import bitwig.obf.YFZ; // a background task
import bitwig.obf.cpI; // a notification-centre entry
import bitwig.obf.dMD; // a task's entry in the notification centre
import bitwig.obf.rMe; // an observable value

import bml.notifications.CentreEntry;
import bml.notifications.Message;
import bml.notifications.Severity;
import bml.notifications.Task;

/**
 * One entry of the centre as watchers see it: what it said when it was shown, brought up to date when
 * it ends. UI thread.
 */
final class Live implements CentreEntry {
    private final cpI entry;
    private final YFZ task; // null for a message
    private final long shownAt;
    private String title;
    private String text;
    private Severity severity;
    private boolean cancelled;
    private long endedAt;
    private boolean replaced;

    Live(cpI entry, long shownAt) {
        this.entry = entry;
        this.task = entry instanceof dMD d ? d.nVd() : null; // the task a task entry shows
        this.shownAt = shownAt;
        read();
    }

    /** It left the centre (UI thread). */
    void ended(long at, boolean replacedByMod) {
        read();
        endedAt = at;
        replaced = replacedByMod;
    }

    private void read() {
        if (task != null) {
            title = orEmpty(task.hUR());                       // its title
            Object s = ((rMe) task.TtY()).getValue();         // its status line
            text = s == null ? "" : s.toString();
            severity = Severity.INFORMATION;
            cancelled = task.fJR();                            // cancelled
        } else {
            title = orEmpty(entry.ac_());                      // its title
            text = orEmpty(entry.FzB());                       // its description
            severity = severity(entry.MRY());                  // its severity
            cancelled = false;
        }
    }

    private static Severity severity(FXB s) {
        if (s == FXB.MRY) return Severity.ERROR;
        if (s == FXB.cfM) return Severity.WARNING;
        return Severity.INFORMATION;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override public boolean isTask() { return task != null; }
    @Override public String title() { return title; }
    @Override public String text() { return text; }
    @Override public Severity severity() { return severity; }
    @Override public boolean cancelled() { return cancelled; }
    @Override public long shownAt() { return shownAt; }
    @Override public long endedAt() { return endedAt; }
    @Override public boolean replaced() { return replaced; }
    @Override public Message message() { return entry instanceof Entry e ? e.handle() : null; }
    @Override public Task task() { return task instanceof BarTask b ? b.handle() : null; }
    @Override public Object entry() { return entry; }

    @Override
    public String toString() {
        return (isTask() ? "task " : "message ") + title;
    }
}
