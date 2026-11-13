package bml.pianoroll;

import bitwig.obf.IQg; // a note's expression lane with its points
import bitwig.obf.TrQ; // a selected note in the note editor
import bitwig.obf.VKK; // a note's expressions
import bitwig.obf.jRs; // how an expression point is joined to the next
import bitwig.obf.n1; // a note
import bitwig.obf.oWe; // a note's expression lane
import bitwig.obf.pBi; // a clip
import bitwig.obf.ucd; // a kind of note expression
import bitwig.obf.vOB; // an expression point
import java.util.ArrayList;
import java.util.List;

/**
 * A note as it was when read. Editing doesn't change this object: read the selection again after
 * {@link Edit#apply()}. Two Notes are equal when they are the same Bitwig note.
 */
public final class Note {
    private static final double STRAIGHT_TOLERANCE = 1.0E-9;

    n1 event; // for a planned copy, set when the copy is made
    final TrQ selected; // the selection item it was read from; null when it wasn't
    private final Note copyOf; // for a planned copy, the note copied
    private final Clip clip;
    private final int key;
    private final int channel;
    private final double start;
    private final double end;
    private final double offset; // where the note's content starts
    private final double velocity;
    private final double releaseVelocity;
    private List<PitchPoint> pitchCurve;

    Note(TrQ selected) {
        this((n1) selected.cfM(), clipOf(selected), selected);
    }

    private static Clip clipOf(TrQ selected) {
        pBi owner = selected.Vr();
        return owner == null ? null : new Clip(owner);
    }

    Note(n1 event, Clip clip) {
        this(event, clip, (TrQ) null);
    }

    private Note(n1 event, Clip clip, TrQ selected) {
        this.event = event;
        this.selected = selected;
        copyOf = null;
        this.clip = clip;
        key = event.cfM();
        channel = event.UZr();
        start = event.x3t();
        end = event.QWG();
        offset = event.ODA();
        velocity = event.UNj();
        releaseVelocity = event.hUR();
    }

    /** A note a plan will add: it has no lane to read its key and channel from yet, and no pitch curve. */
    Note(n1 event, Clip clip, int channel, int key, double start, double end) {
        this.event = event;
        selected = null;
        copyOf = null;
        this.clip = clip;
        this.channel = channel;
        this.key = key;
        this.start = start;
        this.end = end;
        offset = event.ODA();
        velocity = event.UNj();
        releaseVelocity = event.hUR();
        pitchCurve = List.of();
    }

    /** A copy a plan will make: the copy doesn't exist until its edit runs, so this reads from the note copied. */
    Note(Note copyOf, int key, double start, double end) {
        this.copyOf = copyOf;
        selected = null;
        clip = copyOf.clip;
        channel = copyOf.channel;
        this.key = key;
        this.start = start;
        this.end = end;
        offset = copyOf.offset;
        velocity = copyOf.velocity;
        releaseVelocity = copyOf.releaseVelocity;
    }

    /** 0-127. */
    public int key() {
        return key;
    }

    public int channel() {
        return channel;
    }

    /** In the clip's beats. */
    public double start() {
        return start;
    }

    /** In the clip's beats. */
    public double end() {
        return end;
    }

    /** How hard the note is played: 0 to 1, as Bitwig holds it (Bitwig's 127 is 1). */
    public double velocity() {
        return velocity;
    }

    /** How fast the note is released: 0 to 1. */
    public double releaseVelocity() {
        return releaseVelocity;
    }

    /** The clip the note belongs to; null when Bitwig reports none. */
    public Clip clip() {
        return clip;
    }

    /** A clip time as a time on this note's pitch curve. */
    public double curveTime(double clipTime) {
        return clipTime - start + offset;
    }

    /**
     * The note's pitch curve, in Bitwig's order; empty when the note has none. Read the first time it's
     * asked for. Refused when the note holds something the framework can't read as points.
     */
    public List<PitchPoint> pitchCurve() {
        if (pitchCurve == null) {
            pitchCurve = copyOf != null && event == null ? copyOf.pitchCurve() : readPitchCurve();
        }
        return pitchCurve;
    }

    private List<PitchPoint> readPitchCurve() {
        VKK expressions = event.VpW(); // the note's expressions
        if (expressions == null) {
            throw new Refused("a note has no expressions");
        }
        oWe lane = expressions.MRY(ucd.MRY, false); // the pitch lane, if it exists
        if (lane == null) {
            return List.of();
        }
        if (!(lane instanceof IQg pointLane)) {
            throw new Refused("a note's pitch expression is of an unknown kind");
        }
        List<PitchPoint> points = new ArrayList<>();
        for (Object item : pointLane.cfM()) { // its points
            if (item == null || item.getClass() != vOB.class) {
                throw new Refused("a pitch expression point is of an unknown kind");
            }
            vOB point = (vOB) item;
            boolean straight = point.FzB() == jRs.MRY // joined to the next by a straight line
                    && Math.abs(point.dS_()) <= STRAIGHT_TOLERANCE && Math.abs(point.dQ_()) <= STRAIGHT_TOLERANCE; // no curve
            points.add(new PitchPoint(point.x3t(), point.cfM(), straight)); // x3t: time, cfM: value
        }
        return List.copyOf(points);
    }

    /** Bitwig's own object, for a mod that goes deeper. For a planned copy, null until its edit has run. */
    public Object bitwigNote() {
        return event;
    }

    @Override
    public boolean equals(Object other) {
        if (copyOf != null) {
            return other == this; // its Bitwig note doesn't exist when it is made
        }
        return other instanceof Note that && that.copyOf == null && that.event == event;
    }

    @Override
    public int hashCode() {
        return copyOf != null ? System.identityHashCode(this) : System.identityHashCode(event);
    }
}
