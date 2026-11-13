package bml.panels;

/** One display profile, as a layout policy sees it. Bitwig objects come as {@code Object}. */
public interface Layout {
    /** The display profile ({@code akC}). */
    Object profile();

    /** Binds one of the mod's declared Bitwig views to the panel node ({@code akQ}) the policy built for this profile. */
    void bind(String bitwigViewId, Object node);

    /**
     * Builds the profile's view stack: the given Bitwig views (bound first, in this order), then
     * every registered view (alphabetical by label), one above the other, each shown and hidden on
     * its own. Returns the stack's layout node ({@code akw}) for the policy to put in place; the
     * framework adds the stack's buttons in place of Bitwig's mode chooser. One such stack per
     * profile. Each view keeps its saved values (visibility, the divider above it) whichever other
     * views the stack has.
     */
    Object stack(String name, String... bitwigViewIds);

    /**
     * Builds side columns in one of Bitwig's left/right splitters ({@code akZ}): the given Bitwig
     * views (bound first; no mods' views) side by side, each a full-height column with its own
     * width, saved with the project. The first view takes the place of {@code replaced} (one of the
     * splitter's parts, for example the browser side's tab group) at the window's edge; the others
     * follow towards the other part (the main area), which keeps its saved values. Showing a
     * column takes width from that part and hiding it gives it back; the other columns keep their
     * widths. Each panel keeps Bitwig's own button, with the view buttons' clicks: click shows it
     * alone (again: hides it), Cmd/Ctrl-click adds or removes it, right-click its scale. Each
     * column keeps its own width (no Equalise). All may be hidden. The splitter's
     * own saved width becomes the first column's. A panel is in one stack at most.
     */
    void sideColumns(Object splitter, Object replaced, String... bitwigViewIds);

    /** Whether the profile has a (main) view stack. */
    boolean hasStack();

    /** Shows only this view of its stack (for example a mode's preset). */
    void showOnly(Object viewSettings, String modId, String viewId);

    /** Whether the panel node is one of the views of the profile's stacks. */
    boolean isStackView(Object node);
}
