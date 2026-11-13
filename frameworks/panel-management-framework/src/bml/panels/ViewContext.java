package bml.panels;

/** What a view's content is built for. Bitwig objects come as {@code Object}. */
public interface ViewContext {
    /** The window's project view ({@code aBW}): its view settings ({@code RDJ()}), its project. */
    Object window();

    /**
     * Whether the view is shown in that window: in a view stack, its own "is shown"; as a tab of
     * a mode's lower panel (which shows one tab at a time), true while it is attached.
     */
    boolean shown();

    /** The full key of one of the view's saved text values ({@link View#savedText}), to read it from the view settings. */
    String savedKey(String key);
}
