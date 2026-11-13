package bml.notifications;

/**
 * Lays out message rows. Called on Bitwig's UI thread each time Bitwig builds a message's row, before it
 * is shown; change the row's parts in place. Keep it short: it runs once per row, never per paint.
 * Whatever it throws is caught: the row is then Bitwig's own, and the styler isn't called again this
 * session.
 */
@FunctionalInterface
public interface MessageRowStyler {
    void style(MessageRow row);
}
