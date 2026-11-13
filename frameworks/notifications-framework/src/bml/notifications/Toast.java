package bml.notifications;

/**
 * A toast on its way to the screen.
 *
 * @param text the text, as Bitwig would show it
 * @param fromController true for a controller script's popup notification (its
 *     {@code showPopupNotification}, e.g. a knob's value on every turn)
 */
public record Toast(String text, boolean fromController) {
}
