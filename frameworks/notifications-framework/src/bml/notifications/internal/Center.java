package bml.notifications.internal;

import bitwig.obf.dpN; // Bitwig's application: its UI event loop
import bml.api.Bml;
import com.bitwig.flt.app.CHR; // the application window controller (shows the transient notification)

/**
 * The one path every toast and message from a mod takes. Toasts then pass the toast hook, where a mod's
 * toast handler can take them ({@link Toasts}).
 */
public final class Center {
    public static final Bml.Mod MOD = Bml.mod("notifications-framework");

    private Center() { }

    // ---------- toasts ----------

    /** Bitwig's transient notification. Any thread. */
    public static void toast(String text) {
        String t = text == null ? "" : text;
        later(() -> {
            CHR app = CHR.Grk(); // the application window controller
            if (app != null) app.hUR(t); // show the transient notification
        });
    }

    // ---------- messages ----------

    /** Posts a message; the record follows it through replacements. Any thread. */
    public static Record post(Spec spec) {
        Record record = new Record(spec);
        later(() -> show(record));
        return record;
    }

    /** Links the record to the mod's handle, for row layouts. */
    public static void handle(Record record, bml.notifications.Message message) {
        record.handle = message;
    }

    /** Removes the record's message. Any thread. */
    public static void dismiss(Record record) {
        later(() -> {
            record.dismissed = true;
            Entry e = record.entry;
            record.entry = null;
            if (e != null) e.withdraw();
        });
    }

    /** Replaces the record's message with the same options and new words. Any thread. */
    public static void replace(Record record, bml.notifications.Severity severity, String title, String text) {
        later(() -> {
            Entry old = record.entry;
            record.entry = null;
            if (old != null) {
                old.markReplaced(); // watchers see it ended as replaced
                old.withdraw();
            }
            record.spec = record.spec.with(severity, title, text);
            record.dismissed = false;
            show(record);
        });
    }

    private static void show(Record record) {
        if (record.dismissed) return;
        Entry e = new Entry(record.spec, record);
        record.entry = e;
        e.postNow();
    }

    /** Runs a message's action button (Bitwig calls it on the UI thread). */
    static void runAction(String label, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            MOD.log("the action \"" + label + "\" failed", t);
        }
    }

    // ---------- rows outside the centre ----------

    /** Bitwig's message row with these words, never posted; see {@link bml.notifications.Notifications#messageRow}. UI thread. */
    public static Object messageRow(bml.notifications.Severity severity, String title, String text, Runnable onClose) {
        return MessageRows.row(severity, title, text, onClose);
    }

    // ---------- the UI thread ----------

    /** Runs {@code action} on Bitwig's UI thread, after the current event; failures are logged. */
    public static void later(Runnable action) {
        try {
            dpN.TVZ().asyncExec(() -> { // dpN.TVZ(): the application; asyncExec: its event loop
                try {
                    action.run();
                } catch (Throwable t) {
                    MOD.log("notification update failed", t);
                }
            });
        } catch (Throwable t) {
            MOD.log("could not reach Bitwig's UI thread", t);
        }
    }
}
