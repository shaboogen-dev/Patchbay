package bml.notifications.internal;

import bitwig.obf.cpI; // a notification-centre entry
import bitwig.obf.dJV; // makes a widget for a model object
import bitwig.obf.dMD; // a task's entry in the notification centre
import bitwig.obf.dMF; // a message's row in the notification centre
import bitwig.obf.dMP; // the notification centre's list
import bitwig.obf.dve; // a widget

import bml.notifications.MessageRowStyler;

/**
 * Message rows: Bitwig's centre asks its own registry of row makers ({@code dMP.MRY}, empty in Bitwig)
 * before it builds a row. When a mod declared a message layout, the framework registers this maker for
 * every entry: for a message it builds Bitwig's row and hands it to the layout; for a task, or when
 * anything fails, it answers null and Bitwig builds its own row. UI thread.
 */
final class MessageRows implements dJV {
    private static final MessageRows INSTANCE = new MessageRows();
    private static boolean registered;

    private MessageRows() { }

    /** Registers the maker once, when a mod declared a message layout. From the centre's list as it is made. */
    static synchronized void register() {
        if (registered) return;
        registered = true;
        if (RowRegistry.messages().current() == null) return; // no layout: Bitwig's registry stays untouched
        dMP.MRY.cfM(cpI.class, INSTANCE); // every entry (messages and tasks) asks us first
    }

    /**
     * Bitwig's message row for {@code entry} (an entry the mod made, never posted), laid out by the message
     * styler when a mod declared one, so it looks like the centre's rows. Never null. UI thread.
     */
    static dve row(cpI entry) {
        try {
            Stylers<MessageRowStyler> stylers = RowRegistry.messages();
            if (stylers.current() == null) return dMF.cfM(entry); // Bitwig's own message row
            dMF row = dMF.cfM(entry);
            MessageRowView view = MessageRowView.read(row, entry);
            if (view == null) return row;
            return stylers.run(view, MessageRowStyler::style, Center.MOD::log) ? row : dMF.cfM(entry); // a fresh row after a failed layout
        } catch (Throwable t) {
            Center.MOD.log("a message row failed; Bitwig's own row instead", t);
            return dMF.cfM(entry);
        }
    }

    /** {@link #row} for a stand-in message with these words. UI thread. */
    static dve row(bml.notifications.Severity severity, String title, String text, Runnable onClose) {
        return row(new Standin(severity, title, text, onClose));
    }

    @Override
    public dve createWidget(Object entry) {
        try {
            if (!(entry instanceof cpI message) || entry instanceof dMD) return null; // a task: Bitwig's row (and the task-row hook)
            Stylers<MessageRowStyler> stylers = RowRegistry.messages();
            if (stylers.current() == null) return null;
            dMF row = dMF.cfM(message); // Bitwig's own message row
            MessageRowView view = MessageRowView.read(row, message);
            if (view == null) return row; // as Bitwig built it
            return stylers.run(view, MessageRowStyler::style, Center.MOD::log) ? row : null; // null: Bitwig builds a fresh row
        } catch (Throwable t) {
            Center.MOD.log("a message row failed; Bitwig's own row instead", t);
            return null;
        }
    }
}
