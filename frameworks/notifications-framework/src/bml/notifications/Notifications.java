package bml.notifications;

import bml.notifications.internal.Center;

/**
 * Framework: Notifications. Everything a mod needs to tell the user something, in plain Java:
 *
 * <ul>
 *   <li>{@link #toast}: Bitwig's transient notification, for a reply to something the user just did.</li>
 *   <li>{@link #post} and {@link #message}: a message in Bitwig's notification centre (the dot at the top
 *       right of the window), for an outcome the user should still find later.</li>
 *   <li>{@link #task}: a running task in the centre, with a status line, a progress bar and Cancel.</li>
 * </ul>
 *
 * A mod can also lay out the centre's rows, watch its entries and take its toasts ({@link CentreProvider}),
 * and show a message row of its own outside the centre ({@link #messageRow}).
 *
 * Every call is safe from any thread and returns at once; the framework does the work on Bitwig's
 * UI thread or on its own threads. Nothing here throws for a problem inside Bitwig: it is logged.
 */
public final class Notifications {
    private Notifications() { }

    /** Shows {@code text} in Bitwig's transient notification, as Bitwig's own short messages appear. */
    public static void toast(String text) {
        Center.toast(text);
    }

    /**
     * Posts a message with the default options: a close button, no auto-dismiss, and the panel opened
     * for warnings and errors (information arrives quietly). {@code text} may be empty.
     */
    public static Message post(Severity severity, String title, String text) {
        return message(severity, title, text).post();
    }

    /** Starts a message with options; {@link Message.Builder#post()} posts it. */
    public static Message.Builder message(Severity severity, String title, String text) {
        return new Message.Builder(severity, title, text);
    }

    /**
     * Starts a task entry in the centre, with {@code title} and an empty status line. With
     * {@code onCancel}, the entry has a Cancel button that runs it (on Bitwig's UI thread, once);
     * with null, it has none. Call {@link Task#finish()} when the work ends, however it ends.
     */
    public static Task task(String title, Runnable onCancel) {
        return new Task(title, onCancel);
    }

    /**
     * Bitwig's own message row with these words, as the centre shows a message, laid out by the message-row
     * styler in use, for a mod that builds its own views with Bitwig's classes (a history, a log). The
     * message is never posted: it isn't in the centre and no watcher is told. With {@code onClose} the row
     * has a close button that runs it; with null it has none. No action buttons, no auto-dismiss.
     * Call it on Bitwig's UI thread; the result is Bitwig's widget, never null.
     */
    public static Object messageRow(Severity severity, String title, String text, Runnable onClose) {
        return Center.messageRow(severity, title, text, onClose);
    }
}
