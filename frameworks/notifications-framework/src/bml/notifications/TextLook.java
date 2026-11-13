package bml.notifications;

/** How a text in a notification row looks, in the colours and fonts of Bitwig's current theme. */
public enum TextLook {
    /** As a row's title: Bitwig's bold font, the notification text colour. */
    TITLE,
    /** As an error message's title: Bitwig's bold font, the notification error colour. */
    ERROR_TITLE,
    /** As a message's description: the default font, the secondary text colour. */
    SECONDARY
}
