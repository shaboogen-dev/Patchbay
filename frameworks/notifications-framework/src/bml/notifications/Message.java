package bml.notifications;

import bml.notifications.internal.Center;
import bml.notifications.internal.Record;
import bml.notifications.internal.Spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A message posted to Bitwig's notification centre. The handle stays valid after the user closes the
 * message or it dismisses itself; {@link #dismiss()} then does nothing. Safe from any thread.
 */
public final class Message {
    private final Record record; // the framework's record of the posted message

    private Message(Spec spec) {
        this.record = Center.post(spec);
        Center.handle(record, this);
    }

    /** Removes the message from the centre, if it is still there. */
    public void dismiss() {
        Center.dismiss(record);
    }

    /** Replaces the message's title and text, keeping its severity and options. */
    public Message replace(String title, String text) {
        Center.replace(record, null, title, text);
        return this;
    }

    /** Replaces the message's severity, title and text, keeping its options. */
    public Message replace(Severity severity, String title, String text) {
        Center.replace(record, Objects.requireNonNull(severity, "severity"), title, text);
        return this;
    }

    /** The options of a message before it is posted. Not thread-safe: build it on one thread. */
    public static final class Builder {
        private final Severity severity;
        private final String title;
        private final String text;
        private boolean closable = true;
        private int dismissAfterSeconds;
        private Boolean quiet;
        private final List<String> labels = new ArrayList<>();
        private final List<Runnable> actions = new ArrayList<>();

        Builder(Severity severity, String title, String text) {
            this.severity = Objects.requireNonNull(severity, "severity");
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
        }

        /** Whether the message has a close button (default true). */
        public Builder closable(boolean closable) {
            this.closable = closable;
            return this;
        }

        /** Removes the message after {@code seconds}; 0 (the default) keeps it until it is closed. */
        public Builder dismissAfterSeconds(int seconds) {
            this.dismissAfterSeconds = Math.max(0, seconds);
            return this;
        }

        /**
         * Quiet: posting doesn't open the centre's panel. Default: quiet for information, not quiet for
         * warnings and errors.
         */
        public Builder quiet(boolean quiet) {
            this.quiet = quiet;
            return this;
        }

        /** Adds a button with {@code label}; clicking it runs {@code action} on Bitwig's UI thread. */
        public Builder action(String label, Runnable action) {
            labels.add(label == null ? "" : label);
            actions.add(Objects.requireNonNull(action, "action"));
            return this;
        }

        /** Posts the message and returns its handle. */
        public Message post() {
            boolean q = quiet != null ? quiet : severity == Severity.INFORMATION;
            return new Message(new Spec(severity, title, text, closable, dismissAfterSeconds, q,
                List.copyOf(labels), List.copyOf(actions)));
        }
    }
}
