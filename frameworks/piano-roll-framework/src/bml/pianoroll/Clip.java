package bml.pianoroll;

import bitwig.obf.SEJ; // the project's transport, which knows the global key at a beat
import bitwig.obf.b2O; // a scale
import bitwig.obf.n1; // a note
import bitwig.obf.pBi; // a clip
import com.bitwig.flt.document.core.master.g2L; // a project
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The clip a note belongs to. Two Clips are equal when they are the same Bitwig clip. */
public final class Clip {
    final pBi clip;

    Clip(pBi clip) {
        this.clip = clip;
    }

    /**
     * The clip's notes sounding in part of (start, end), in the clip's beats, whatever their keys, sorted
     * by start. A note that only touches the range isn't one, as in {@link PianoRoll#overlaps}.
     */
    public List<Note> notesBetween(double start, double end) {
        List<Note> notes = new ArrayList<>();
        for (Object item : clip.MLK().TMF()) { // every lane's notes
            if (item != null && item.getClass() == n1.class) {
                Note note = new Note((n1) item, this);
                if (note.start() + PianoRoll.OVERLAP_TOLERANCE < end && start + PianoRoll.OVERLAP_TOLERANCE < note.end()) {
                    notes.add(note);
                }
            }
        }
        notes.sort(Comparator.comparingDouble(Note::start));
        return List.copyOf(notes);
    }

    /** Bitwig's global key at this beat of the clip. Refused when the project has none to read. */
    public KeySignature keySignatureAt(double beat) {
        g2L project = (g2L) clip.UNj(g2L.class); // the project this clip is in
        SEJ transport = project != null ? project.tHK() : null;
        if (transport == null) {
            throw new Refused("the key signature can't be read");
        }
        b2O scale = b2O.cfM(transport.hUR(beat)); // the scale at this beat
        if (scale == null || scale.IwV() == null || scale.IwV().isEmpty()) {
            throw new Refused("the key signature has no scale");
        }
        return new KeySignature(transport.UNj(beat), scale.IwV()); // the root at this beat, the scale's notes
    }

    /** Bitwig's own object, for a mod that goes deeper. */
    public Object bitwigClip() {
        return clip;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Clip that && that.clip == clip;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(clip);
    }
}
