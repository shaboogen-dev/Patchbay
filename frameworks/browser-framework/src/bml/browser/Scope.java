package bml.browser;

/** Which of Bitwig's browsers something applies to. */
public enum Scope {
    /** The side browser (the Browser panel). */
    SIDE,
    /** The pop-up browser (inserting or replacing a device, clip, sample, ...). */
    POPUP,
    /** Both browsers. */
    BOTH;

    /** True when this scope includes {@code browser} ({@link #SIDE} or {@link #POPUP}). */
    public boolean includes(Scope browser) {
        return this == BOTH || this == browser;
    }
}
