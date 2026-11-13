package bml.notifications;

import java.util.List;

/**
 * One piece of a notification row: a text, the bar, a button, or a group of other parts. A styler moves,
 * replaces, hides or adds parts; nothing about them needs a Bitwig name. Use them only inside the styler
 * call (on Bitwig's UI thread).
 *
 * <p>A <b>group</b> lays its parts out in a line, one under the other or side by side, in the order of
 * {@link #parts()}. The other parts are single widgets.
 */
public interface Part {
    /** The Bitwig widget as it sits in its group, for a mod that works with Bitwig's own classes. */
    Object widget();

    /**
     * The widget this part shows. Usually {@link #widget()}; for a part that sits in a wrapper (the task's
     * title in its stretching box, a group inside its margins), the widget inside the wrapper.
     */
    Object content();

    /** True for a group of parts. */
    boolean isGroup();

    /** A group's parts, in order. Empty for a part that isn't a group. */
    List<Part> parts();

    /** Adds {@code part} at the end of this group (taking it out of the group it was in). */
    void add(Part part);

    /** Adds {@code part} at {@code index} of this group (taking it out of the group it was in). */
    void add(int index, Part part);

    /**
     * Puts the given parts first in this group, in this order; the group's other parts follow in their
     * order. Nulls are skipped, so absent parts ({@code row.cancel()} without Cancel) can be listed.
     */
    void order(Part... parts);

    /** Takes this part out of its group. */
    void remove();

    /** Puts {@code other} where this part is, and takes this part out. */
    void replaceWith(Part other);

    /** Shows or hides this part, replacing whatever Bitwig bound its visibility to. */
    void shown(boolean shown);
}
