package bml.pianoroll;

import bitwig.obf.Jsq; // something with a start and a length on a timeline
import bitwig.obf.PSn; // a note lane
import bitwig.obf.Rke; // a note lane of one channel and key
import bitwig.obf.anL; // Bitwig's rounding of times
import bitwig.obf.n1; // a note
import bitwig.obf.pBi; // a clip
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a plan would leave, worked out without changing the project. Each note the plan touches gets a detached
 * copy of the Bitwig note, which the plan's edits change in its order, as they would change the note itself. The
 * copies belong to nothing, so Bitwig records nothing for them.
 */
final class Outcome {
    private static final double SAME_TIME = 2.0 / 1048576.0; // as Edit.copyIfAbsent

    /** A note of the plan as it would end up. */
    static final class Shadow {
        final Note note; // the plan's name for it; null for a copyIfAbsent copy, which has none
        final n1 original; // the Bitwig note it starts as; null for a note the plan adds or copies
        final pBi clip;
        final int channel;
        final int originalKey;
        int key;
        n1 form; // the note as it would end up, made on its first change; belongs to nothing
        boolean removed;
        boolean curveChanged;

        Shadow(Note note, n1 original, pBi clip, int channel, int key) {
            this.note = note;
            this.original = original;
            this.clip = clip;
            this.channel = channel;
            originalKey = key;
            this.key = key;
        }

        n1 current() {
            return form != null ? form : original;
        }
    }

    /** A note to draw: a form, in the lane of this clip, channel and key. original: the note it changes, or null. */
    record Placed(pBi clip, int channel, int key, n1 form, n1 original) {
    }

    private final List<Shadow> shadows = new ArrayList<>();
    private final Map<Note, Shadow> named = new java.util.HashMap<>(); // Note's equality: the same Bitwig note
    private final Map<n1, Shadow> byOriginal = new IdentityHashMap<>();
    private final Set<Note> cut = new LinkedHashSet<>();

    private Shadow of(Note note) {
        Shadow shadow = named.get(note);
        if (shadow == null) {
            pBi clip = note.clip() != null ? note.clip().clip
                    : (pBi) ((com.bitwig.ramona.core.oDc) note.event).UNj(pBi.class); // the clip the note is in
            if (clip == null) {
                throw new Refused("a note's clip can't be read");
            }
            shadow = new Shadow(note, note.event, clip, note.channel(), note.key());
            add(shadow);
        }
        return shadow;
    }

    private void add(Shadow shadow) {
        shadows.add(shadow);
        if (shadow.note != null) {
            named.put(shadow.note, shadow);
        }
        if (shadow.original != null) {
            byOriginal.put(shadow.original, shadow);
        }
    }

    private static n1 form(Shadow shadow) {
        if (shadow.form == null) {
            shadow.form = shadow.original.dvk(); // a copy with all it holds, in no lane
        }
        return shadow.form;
    }

    // The plan's edits, as Edit runs them, on the forms.

    void add(Note note, Clip clip, int channel, int key, double start, double duration, double velocity) {
        Shadow shadow = new Shadow(note, null, clip.clip, channel, key);
        shadow.form = n1.cfM(start, duration, velocity); // a new note, in no lane
        add(shadow);
        Rke lane = clip.clip.MLK().TMF(channel, key, false); // the played lane for this channel and key, if any
        if (lane != null) {
            for (Object other : ((PSn) lane).Hnt(start, start + duration)) { // the lane's notes in this range
                if (other instanceof n1 there && there.x3t() < start + duration - SAME_TIME && there.QWG() > start + SAME_TIME) {
                    cut.add(new Note(there, clip)); // Bitwig's insert cuts it
                }
            }
        }
    }

    void copy(Note note, Note copy, int key, double start, double end) {
        Shadow source = of(note);
        Shadow shadow = new Shadow(copy, null, source.clip, source.channel, key);
        shadow.form = source.current().dvk(); // a copy of the note as it is at this point of the plan
        shadow.form.wGc(start); // start, keeping the length
        shadow.form.eLY(end); // end
        add(shadow);
    }

    void copyIfAbsent(Note note, int key, double start, double end) {
        Shadow source = of(note);
        if (laneHas(source.clip, source.channel, key, start, end)) {
            return;
        }
        copy(note, null, key, start, end);
    }

    void move(Note note, double start) {
        n1 form = form(of(note));
        double length = form.QWG() - form.x3t(); // its end and start
        form.wGc(start); // start, keeping the length
        form.eLY(start + length); // end
    }

    void setKey(Note note, int key) {
        Shadow shadow = of(note);
        form(shadow);
        shadow.key = key;
    }

    void setVelocity(Note note, double velocity) {
        form(of(note)).cfM(velocity); // its velocity
    }

    void setReleaseVelocity(Note note, double velocity) {
        form(of(note)).MRY(velocity); // its release velocity
    }

    void trimStart(Note note, double start) {
        form(of(note)).cfM(start, true, null); // Bitwig's trim of a note's start
    }

    void setEnd(Note note, double end) {
        n1 form = form(of(note));
        ((Jsq) form).lYc(anL.UNj(end - form.x3t())); // its length, rounded as Bitwig's API rounds it
    }

    void setPitchCurve(Note note) {
        Shadow shadow = of(note);
        form(shadow);
        shadow.curveChanged = true;
    }

    void remove(Note note) {
        of(note).removed = true;
    }

    /** Whether the lane of this clip, channel and key would have a note from start to end at this point of the plan. */
    private boolean laneHas(pBi clip, int channel, int key, double start, double end) {
        Set<Shadow> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Rke lane = clip.MLK().TMF(channel, key, false); // the played lane, if any; none is made
        if (lane != null) {
            for (Object other : ((PSn) lane).Hnt(start, end)) { // the lane's notes in this range
                if (!(other instanceof n1 there)) {
                    continue;
                }
                Shadow shadow = byOriginal.get(there);
                if (shadow == null) {
                    if (sameTimes(there, start, end)) {
                        return true;
                    }
                } else {
                    seen.add(shadow);
                    if (!shadow.removed && shadow.key == key && sameTimes(shadow.current(), start, end)) {
                        return true;
                    }
                }
            }
        }
        for (Shadow shadow : shadows) {
            if (!seen.contains(shadow) && !shadow.removed && shadow.clip == clip && shadow.channel == channel
                    && shadow.key == key && sameTimes(shadow.current(), start, end)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameTimes(n1 note, double start, double end) {
        return Math.abs(note.x3t() - start) <= SAME_TIME && Math.abs(note.QWG() - end) <= SAME_TIME;
    }

    // What it comes to.

    /** The notes the plan would remove or change, and those a note it adds would cut, in the plan's order. */
    List<Note> changed() {
        Set<Note> changed = new LinkedHashSet<>();
        for (Shadow shadow : shadows) {
            if (shadow.original != null && (shadow.removed || !unchanged(shadow))) {
                changed.add(shadow.note);
            }
        }
        changed.addAll(cut);
        return List.copyOf(changed);
    }

    /** The notes the plan removes, in the plan's order. */
    List<Note> removed() {
        List<Note> removed = new ArrayList<>();
        for (Shadow shadow : shadows) {
            if (shadow.original != null && shadow.removed) {
                removed.add(shadow.note);
            }
        }
        return List.copyOf(removed);
    }

    /** The notes to draw: everything the plan adds or copies, and each changed note as it would end up. */
    List<Placed> placed() {
        List<Placed> placed = new ArrayList<>();
        for (Shadow shadow : shadows) {
            if (!shadow.removed && shadow.form != null && (shadow.original == null || !unchanged(shadow))) {
                placed.add(new Placed(shadow.clip, shadow.channel, shadow.key, shadow.form, shadow.original));
            }
        }
        return placed;
    }

    /** The clips the plan touches. */
    Set<pBi> clips() {
        Set<pBi> clips = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        shadows.forEach(shadow -> clips.add(shadow.clip));
        cut.forEach(note -> clips.add(note.clip().clip));
        return clips;
    }

    private static boolean unchanged(Shadow shadow) {
        n1 was = shadow.original;
        n1 now = shadow.form;
        return now == null || shadow.key == shadow.originalKey && !shadow.curveChanged
                && now.x3t() == was.x3t() && now.QWG() == was.QWG() // start, end
                && now.ODA() == was.ODA() // where its content starts
                && now.UNj() == was.UNj() && now.hUR() == was.hUR(); // velocity, release velocity
    }
}
