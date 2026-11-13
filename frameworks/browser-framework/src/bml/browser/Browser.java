package bml.browser;

/** What a provider declares its browser entries with. */
public interface Browser {
    /** The browsers' features. The object may be kept and used later. */
    Features features();

    /**
     * Declares a view, offered to the user in Settings ("Browser view"). {@code viewId} only needs
     * to be unique within the mod; {@code name} is shown to the user.
     */
    View view(String viewId, String name);

    /**
     * Adds one of Bitwig's file kinds (its id, such as {@code "projects"}) to the side browser:
     * Everything shows files of that kind, the file kind filter offers it, and it is listed as a kind
     * under BY KIND in the side pane and in Bitwig's source picker, with this name.
     * <ul>
     *   <li>{@code tileResource}: the kind's tile, a resource in the declaring mod's manifest. A
     *       raster tile, as Bitwig draws its own kinds' ({@code images/library/<id>.webp}): one of
     *       Bitwig's tiles ({@code "kind": "bitwig"}, such as {@code images/library/templates.webp})
     *       or the mod's own WebP file, never a copy of Bitwig's files. An SVG isn't drawn.</li>
     *   <li>{@code iconName}: the small icon where Bitwig draws the file kind by name (the file
     *       kind filter's values), one of Bitwig's icon names such as {@code "browser/document.svg"};
     *       null keeps Bitwig's.</li>
     * </ul>
     * The first mod to declare a file kind wins.
     */
    void kind(String fileKind, String name, String tileResource, String iconName);

    /**
     * Turns a feature off while {@code hidden} is true and this mod is loaded; false gives it back.
     * Nothing is stored: when the mod isn't loaded, its parts are back. A feature is off when any
     * loaded mod hides it (whichever mods they are); the active view's claim still wins
     * ({@link Features#get}). Call during {@code declare} and later, on Bitwig's UI thread; it
     * applies at once.
     */
    void hide(Feature feature, Scope scope, boolean hidden);

    /** True while this mod hides {@code feature} in that scope ({@link #hide}). */
    boolean hides(Feature feature, Scope scope);

    /**
     * Rebuilds the side browser shortly, for a change that only applies when it is built (a view's
     * own options for what its {@link View#results} slot builds, say). Its source, search and
     * filters stay. Several requests close together make one rebuild.
     */
    void rebuild(String why);
}
