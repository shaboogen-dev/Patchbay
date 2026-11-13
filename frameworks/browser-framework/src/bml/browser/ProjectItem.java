package bml.browser;

/**
 * One item of the open project, from {@link Panel#projectItems}: a track, a device or a clip.
 *
 * <ul>
 * <li>{@code id}: the framework's handle for the item (to hand back to the panel); it stays valid
 *     while Bitwig keeps the item.
 * <li>{@code kind}: {@code "track"}, {@code "device"} or {@code "clip"}.
 * <li>{@code name}: the item's name in Bitwig.
 * <li>{@code track}: the name of the track the item is on (a track's own name for a track), or empty.
 * <li>{@code depth}: how many listed items it is inside: 0 for a top-level track, 1 for its devices
 *     and clips and for the tracks in a group, and so on.
 * </ul>
 */
public record ProjectItem(long id, String kind, String name, String track, int depth) {
}
