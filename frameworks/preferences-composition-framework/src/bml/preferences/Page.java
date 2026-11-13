package bml.preferences;

/**
 * A page of Bitwig's Settings: one of Bitwig's own, or one a mod declares with
 * {@link Preferences#page}. Two pages are the same when their IDs are.
 */
public final class Page {
    public static final Page USER_INTERFACE = new Page("user-interface");
    public static final Page BEHAVIOUR = new Page("behaviour");
    public static final Page AUDIO = new Page("audio");
    public static final Page RECORDING = new Page("recording");
    public static final Page CONTROLLERS = new Page("controllers");
    public static final Page SYNCHRONIZATION = new Page("synchronization");
    public static final Page LOCATIONS = new Page("locations");
    public static final Page PLUGINS = new Page("plugins");
    public static final Page SHORTCUTS = new Page("shortcuts");

    private final String id;

    private Page(String id) {
        this.id = id;
    }

    /** The page with this ID: one of Bitwig's (the constants above) or one a mod declares. */
    public static Page of(String pageId) {
        if (pageId == null || pageId.isBlank()) throw new IllegalArgumentException("no page ID");
        return new Page(pageId);
    }

    public String id() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Page p && p.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
