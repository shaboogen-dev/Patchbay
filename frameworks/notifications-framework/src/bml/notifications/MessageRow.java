package bml.notifications;

/**
 * A message's row in Bitwig's notification centre, as Bitwig built it, handed to a
 * {@link MessageRowStyler} before Bitwig shows it. Every message's row, Bitwig's own and every mod's.
 *
 * <p>Bitwig's layout, top to bottom, in {@link #column()}: the title line (title, then the close button
 * at the right), the body text, the buttons. A part the message doesn't have is null.
 */
public interface MessageRow extends Row {
    // ---------- the message ----------

    /** The framework's handle when a mod posted the message through this framework; otherwise null. */
    Message message();

    /** How serious the message is. */
    Severity severity();

    /** The message's title. */
    String titleText();

    /** The message's text; empty when there is none. */
    String bodyText();

    // ---------- the parts ----------

    /** The column, a group: title line, body, buttons, in Bitwig's order. */
    Part column();

    /** The title line, a group: title, a stretch, close. */
    Part titleLine();

    /** The title (one line, cut when too long). */
    Part title();

    /** The close button, or null for a message without one. */
    Part close();

    /** The body text (wrapping), or null for a message without text. */
    Part body();

    /** The group of buttons (hidden while there are none), inside its margins. */
    Part buttons();
}
