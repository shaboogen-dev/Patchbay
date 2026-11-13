package bml.inspector;

/** The Inspector contexts a block can appear in: what the Inspector is showing. */
public enum Context {
    /** A track (every track type). */
    TRACK("track"),
    /** A device. */
    DEVICE("device"),
    /** A clip in the arranger. */
    ARRANGER_CLIP("arranger-clip"),
    /** A clip in the clip launcher. */
    LAUNCHER_CLIP("launcher-clip"),
    /** A clip launcher slot. */
    SLOT("slot"),
    /** A scene. */
    SCENE("scene"),
    /** Notes: the whole Note Inspector. */
    NOTE("note"),
    /** Notes: next to Bitwig's NOTE block (top: before it, bottom: after it). */
    NOTE_SECTION("note.section"),
    /** Notes: next to Bitwig's OPERATORS block. */
    NOTE_OPERATORS("note.operators"),
    /** Notes: next to Bitwig's EXPRESSIONS block. */
    NOTE_EXPRESSIONS("note.expressions"),
    /** A module of a Grid device. */
    MODULE("module"),
    /** A modulator. */
    MODULATOR("modulator"),
    /** A cue marker. */
    CUE_MARKER("cue-marker");

    private final String id;

    Context(String id) {
        this.id = id;
    }

    /** The context's ID, as the framework logs it. */
    public String id() {
        return id;
    }

    /** True for the contexts placed next to one of Bitwig's blocks inside the Note Inspector. */
    public boolean noteBlock() {
        return this == NOTE_SECTION || this == NOTE_OPERATORS || this == NOTE_EXPRESSIONS;
    }
}
