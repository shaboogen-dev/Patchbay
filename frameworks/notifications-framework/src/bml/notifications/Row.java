package bml.notifications;

/** What task rows and message rows have in common. */
public interface Row {
    /** The row itself, Bitwig's widget, for a mod that works with Bitwig's own classes. */
    Object widget();

    /** The row's frame: the group inside its border and background, holding everything else. */
    Part frame();

    /** One of Bitwig's widgets the mod made itself, as a part it can add to a group. */
    Part part(Object bitwigWidget);

    /** A new wrapping text with {@code text}, in {@code look}, as a part to add to a group. */
    Part newText(String text, TextLook look);
}
