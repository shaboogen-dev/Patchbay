package bml.pianoroll;

/** Two notes that sound together, and where: see {@link PianoRoll#overlaps}. */
public final class Overlap {
    private final Note earlier;
    private final Note later;

    Overlap(Note earlier, Note later) {
        this.earlier = earlier;
        this.later = later;
    }

    /** The note that starts first. */
    public Note earlier() {
        return earlier;
    }

    public Note later() {
        return later;
    }

    /** Where the overlap starts, in the clip's beats: the later note's start. */
    public double start() {
        return later.start();
    }

    /** Where the overlap ends, in the clip's beats: whichever note ends first. */
    public double end() {
        return Math.min(earlier.end(), later.end());
    }
}
