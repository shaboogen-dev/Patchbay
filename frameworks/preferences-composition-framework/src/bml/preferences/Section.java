package bml.preferences;

/**
 * A section of a Settings page, by its ID {@code <page ID>/<section ID>}: one of Bitwig's own
 * sections (listed in the README, e.g. {@code behaviour/device}) or one a mod declares with
 * {@link Preferences#section}. Two sections are the same when their IDs are.
 */
public final class Section {
    private final String id;

    private Section(String id) {
        this.id = id;
    }

    /** The section with this ID, e.g. {@code Section.of("recording/pre-roll")}. */
    public static Section of(String sectionId) {
        int slash = sectionId == null ? -1 : sectionId.indexOf('/');
        if (slash < 1 || slash == sectionId.length() - 1 || sectionId.indexOf('/', slash + 1) >= 0)
            throw new IllegalArgumentException("a section ID is <page>/<section>: " + sectionId);
        return new Section(sectionId);
    }

    public String id() {
        return id;
    }

    /** The page it is on. */
    public Page page() {
        return Page.of(id.substring(0, id.indexOf('/')));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Section s && s.id.equals(id);
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
