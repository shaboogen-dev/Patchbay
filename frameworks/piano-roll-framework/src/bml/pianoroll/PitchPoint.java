package bml.pianoroll;

/** A point of a note's pitch curve. */
public final class PitchPoint {
    private final double time;
    private final double semitones;
    private final boolean straight;

    PitchPoint(double time, double semitones, boolean straight) {
        this.time = time;
        this.semitones = semitones;
        this.straight = straight;
    }

    /** A point with a straight line to the next one. */
    public static PitchPoint of(double time, double semitones) {
        return new PitchPoint(time, semitones, true);
    }

    /** In curve time: see {@link Note#curveTime}. */
    public double time() {
        return time;
    }

    public double semitones() {
        return semitones;
    }

    /** A straight line to the next point, with no curve shape. */
    public boolean straight() {
        return straight;
    }
}
