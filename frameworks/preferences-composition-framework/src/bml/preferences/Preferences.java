package bml.preferences;

/**
 * What a provider declares its controls, sections and pages with. Every control is one of
 * Bitwig's own Settings widgets; the mod keeps the value (its getter and setter are called).
 */
public interface Preferences {
    /** A checkbox with its label, as in Bitwig's own sections. */
    Checkbox checkbox(String controlId, String label);

    /** A row of buttons, one per option, of which one is selected (as Bitwig's Pre-roll). */
    Choice choice(String controlId, String label, String... options);

    /** A dropdown list of options, of which one is selected. */
    Choice dropdown(String controlId, String label, String... options);

    /** A number field, dragged or typed, between {@code min} and {@code max}. */
    NumberField number(String controlId, String label, double min, double max);

    /** A slider with a number field beside it (as Bitwig's Contrast rows). */
    NumberField slider(String controlId, String label, double min, double max);

    /**
     * Declares a new section on a page, below Bitwig's sections, with {@code title} as its heading.
     * Prefix {@code sectionId} with the mod ID. Shown only when a control is in it.
     */
    Section section(Page page, String sectionId, String title);

    /**
     * Declares a page of its own in the Settings sidebar, below Bitwig's pages. Prefix
     * {@code pageId} with the mod ID. Shown only when a control is in one of its sections.
     */
    Page page(String pageId, String label);
}
