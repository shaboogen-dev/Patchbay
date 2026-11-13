package bml.pianoroll;

import bitwig.obf.PSn; // a note lane, with its played notes
import bitwig.obf.Rke; // a note lane of one channel and key
import bitwig.obf.YvI; // an event a lane plays
import bitwig.obf.n1; // a note
import bitwig.obf.YeP; // a clip's note lanes
import bitwig.obf.eJy; // an event a lane can hold
import bitwig.obf.lh; // something an event can stand in for
import bitwig.obf.wMV; // an event that can stand in for another
import bml.pianoroll.internal.Heard;
import com.bitwig.flt.document.core.master.WWV; // what a project shows as its one preview
import com.bitwig.flt.document.core.master.g2L; // a project
import com.bitwig.flt.document.core.master.sZK; // a time range in which a lane's notes aren't drawn
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A plan's outcome as Bitwig's own preview notes, in the project's one preview slot, as Bitwig's pencil puts the
 * note it is drawing there. Preview notes live in the lanes' preview lists, which Bitwig doesn't save, doesn't
 * record for Undo, doesn't count as a change to the project and doesn't send to the audio engine. A heard preview is
 * given to the engine by {@link Heard}, from what is recorded here as the notes go in.
 *
 * Hiding is Bitwig's too. A preview note that stands in for a note (Bitwig's link, as its Note Inspector previews
 * set it) hides that note wherever it is in the clip. A note with no stand-in is hidden with a time range in its
 * lane's preview list, in which the lane's notes aren't drawn.
 */
final class PreviewNotes extends WWV {
    private final Preview preview;
    private final List<Outcome.Placed> placed;
    private final boolean hideChanged;
    private final Map<Rke, List<double[]>> hiddenRanges; // per lane, sorted, not overlapping: {start, end}
    private final List<Object[]> standIns; // {lane, note}: kept notes a range hides, drawn again plain
    private final List<Rke> told; // lanes of the striped or hidden notes: told their preview changed, so editors repaint
    private final Set<Rke> holding = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<YeP> timelines = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Heard.Feed heard; // what the engine plays while it is shown; null for a silent preview

    PreviewNotes(g2L project, Preview preview, List<Outcome.Placed> placed, boolean hideChanged,
                 Map<Rke, List<double[]>> hiddenRanges, List<Object[]> standIns, List<Rke> told, Heard.Feed heard) {
        super(project);
        this.preview = preview;
        this.placed = placed;
        this.hideChanged = hideChanged;
        this.hiddenRanges = hiddenRanges;
        this.standIns = standIns;
        this.told = told;
        this.heard = heard;
    }

    /** Bitwig puts the preview in, after taking out the one before. */
    @Override
    public void MRY() {
        for (Rke lane : told) {
            lane.hk_(); // it holds no preview notes now: this only tells its editors
        }
        for (Outcome.Placed note : placed) {
            if (hideChanged && note.original() != null) {
                ((wMV) note.form()).cfM((lh) note.original()); // stands in for the note: Bitwig doesn't draw that one
            }
            YeP lanes = note.clip().MLK(); // the clip's note lanes
            Rke played = lanes.TMF(note.channel(), note.key(), false); // the played lane for this key, if any
            if (played != null) {
                played.cfM((eJy) note.form()); // into its preview notes
                holding.add(played);
                if (heard != null && playable(note.form())) {
                    heard.added(played, note.form()); // plays in that lane
                }
            } else {
                // A preview lane, made for this key, holding the note as its own: Bitwig looks there, and in played
                // lanes' preview notes, for a stand-in. Preview lanes and all they hold aren't saved, undone or played.
                PSn lane = lanes.UNj(note.channel(), note.key(), true); // a new preview lane
                n1 form = note.form();
                lane.Hnt((YvI) form, lane.Hnt(form.x3t(), false)); // insert, at the place for its start
                if (heard != null && playable(form)) {
                    heard.lane(lanes, (Rke) lane); // played as its own lane
                }
            }
            timelines.add(lanes);
        }
        for (Object[] standIn : standIns) {
            Rke lane = (Rke) standIn[0];
            lane.cfM((eJy) standIn[1]); // into its preview notes: a hiding range doesn't reach those
            holding.add(lane);
        }
        hiddenRanges.forEach((lane, ranges) -> {
            for (double[] range : ranges) {
                lane.a_(sZK.cfM(range[0], range[1] - range[0])); // the lane's notes in this range aren't drawn
            }
        });
    }

    /**
     * The engine's encoder reads a note's channel through the lane holding it: a preview note that can't answer stays
     * silent rather than stop Bitwig's encode.
     */
    private static boolean playable(n1 note) {
        try {
            note.UZr(); // its channel, from its lane
            return true;
        } catch (RuntimeException e) {
            bml.api.Bml.mod("piano-roll-framework").log("a preview note can't be heard: it has no lane", e);
            return false;
        }
    }

    /** Bitwig takes the preview out: for another preview, before a change, or when the mod ends it. */
    @Override
    public void Hnt() {
        List<Runnable> out = new ArrayList<>();
        for (Rke lane : holding) {
            out.add(lane::hk_); // its preview notes
        }
        for (Rke lane : hiddenRanges.keySet()) {
            out.add(lane::bE_); // its hiding ranges
        }
        for (Rke lane : told) {
            if (!holding.contains(lane)) {
                out.add(lane::hk_); // tells its editors the stripes or the hiding may have gone
            }
        }
        for (YeP lanes : timelines) {
            out.add(lanes::LEd); // preview lanes, now empty
        }
        for (Runnable step : out) {
            try {
                step.run();
            } catch (RuntimeException e) {
                bml.api.Bml.mod("piano-roll-framework").log("a preview note could not be taken out", e);
            }
        }
        holding.clear();
        timelines.clear();
        preview.removed(this);
    }
}
