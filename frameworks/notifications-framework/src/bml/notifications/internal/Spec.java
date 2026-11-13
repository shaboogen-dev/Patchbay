package bml.notifications.internal;

import bml.notifications.Severity;

import java.util.List;

/** What a message is: plain values, no Bitwig names. {@code labels} and {@code actions} pair up. */
public record Spec(Severity severity, String title, String text, boolean closable, int dismissAfterSeconds,
                   boolean quiet, List<String> labels, List<Runnable> actions) {

    /** The same options with another severity (null keeps this one), title and text. */
    public Spec with(Severity newSeverity, String newTitle, String newText) {
        return new Spec(newSeverity == null ? severity : newSeverity, newTitle == null ? "" : newTitle,
            newText == null ? "" : newText, closable, dismissAfterSeconds, quiet, labels, actions);
    }
}
