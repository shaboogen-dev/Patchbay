package bml.pianoroll.internal;

import bitwig.obf.Rke; // a note lane of one channel and key
import bitwig.obf.YeP; // a clip's note lanes
import bitwig.obf.nPO; // an event a lane plays, as the engine's encoder reads it
import bitwig.obf.pBi; // a clip
import bml.api.Bml;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Heard previews: while one is shown, the clip plays as Apply would leave it.
 *
 * Bitwig turns a clip's notes into bytes for the audio engine in one method, which walks the clip's played lanes and
 * each lane's notes. Two hooks there hand it the outcome instead: a lane the preview changes gives its notes minus
 * the ones the plan removes or changes, plus the preview notes for that lane; and the preview's own lanes (keys with
 * no played lane) are walked too. The framework then asks Bitwig to re-encode the clip with its own public call, when
 * a heard preview starts and when it ends. The engine's copy of the notes isn't saved, undone or counted as a change.
 *
 * With no heard preview, each hook makes Bitwig's call and returns after one volatile read.
 */
public final class Heard {
    private static final Bml.Mod MOD = Bml.mod("piano-roll-framework");
    private static final Comparator<Object> BY_START = Comparator.comparingDouble(event -> ((nPO) event).x3t());

    private static volatile Feed[] feeds = new Feed[0]; // the heard previews shown, in any project
    private static volatile boolean unavailable; // Bitwig's re-encode can't be reached: previews stay silent

    private Heard() {
    }

    /** What one heard preview plays. Built on Bitwig's UI thread; read by the hooks, never changed once started. */
    public static final class Feed {
        private final Set<Object> gone = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<Rke, List<Object>> added = new IdentityHashMap<>();
        private final Map<YeP, List<Rke>> lanes = new IdentityHashMap<>();
        private final Set<Rke> touched = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<pBi> clips = Collections.newSetFromMap(new IdentityHashMap<>());
        private final BooleanSupplier shown;

        /** shown: whether the preview is still on screen, so a feed whose preview went without ending can be let go. */
        public Feed(BooleanSupplier shown) {
            this.shown = shown;
        }

        /** A note the plan removes or changes: it doesn't play. */
        public void gone(Object note, Object lane) {
            gone.add(note);
            if (lane instanceof Rke played) {
                touched.add(played);
            }
        }

        /** A preview note put in a played lane's preview notes: it plays in that lane. */
        public void added(Rke lane, Object note) {
            added.computeIfAbsent(lane, l -> new ArrayList<>()).add(note);
            touched.add(lane);
        }

        /** A preview lane made for a key with no played lane, holding preview notes as its own: it plays too. */
        public void lane(YeP clipLanes, Rke lane) {
            List<Rke> list = lanes.computeIfAbsent(clipLanes, l -> new ArrayList<>());
            if (!list.contains(lane)) {
                list.add(lane);
            }
        }

        /** A clip to re-encode when the preview starts and ends. */
        public void clip(pBi clip) {
            clips.add(clip);
        }
    }

    /** Whether heard previews work on this Bitwig: false once Bitwig's re-encode couldn't be reached. */
    public static boolean available() {
        return !unavailable;
    }

    /** The preview plays from now: its clips are re-encoded. False when it can't be heard on this Bitwig. */
    public static boolean start(Feed feed) {
        if (unavailable) {
            return false;
        }
        List<Feed> now = new ArrayList<>();
        for (Feed other : feeds) {
            if (other.shown.getAsBoolean()) { // one whose preview went without ending is let go
                now.add(other);
            }
        }
        now.add(feed);
        feeds = now.toArray(new Feed[0]);
        if (!reencode(feed.clips)) {
            stop(feed);
            return false;
        }
        return true;
    }

    /** The preview has ended: its clips are re-encoded from the real notes. Stopping it again does nothing. */
    public static void stop(Feed feed) {
        Feed[] before = feeds;
        List<Feed> now = new ArrayList<>(before.length);
        boolean found = false;
        for (Feed other : before) {
            if (other == feed) {
                found = true;
            } else {
                now.add(other);
            }
        }
        if (!found) {
            return;
        }
        feeds = now.toArray(new Feed[0]);
        reencode(feed.clips);
    }

    /** Bitwig's own call: marks each clip's engine copy for a re-encode at the end of the current event. */
    private static boolean reencode(Collection<pBi> clips) {
        for (pBi clip : clips) {
            try {
                clip.mtS().qm(); // the clip's note content: re-encode it for the engine
            } catch (LinkageError e) {
                unavailable = true;
                MOD.log("previews can't be heard on this Bitwig; they stay silent", e);
                return false;
            } catch (RuntimeException e) {
                MOD.log("a clip could not be re-encoded for a heard preview", e);
            }
        }
        return true;
    }

    // The hooks' work. Each makes Bitwig's own call first, so Bitwig always gets its result when anything fails.

    /** The clip's played lanes, plus the preview lanes of a heard preview. */
    public static List<?> playedLanes(YeP clipLanes) {
        List<?> played = clipLanes.wGc();
        Feed[] now = feeds;
        if (now.length == 0) {
            return played;
        }
        try {
            List<Object> all = null;
            for (Feed feed : now) {
                List<Rke> extra = feed.lanes.get(clipLanes);
                if (extra != null) {
                    if (all == null) {
                        all = new ArrayList<>(played);
                    }
                    all.addAll(extra);
                }
            }
            return all != null ? all : played;
        } catch (RuntimeException e) {
            MOD.log("a heard preview's lanes could not be added", e);
            return played;
        }
    }

    /** A lane's notes: as Apply would leave them when a heard preview changes the lane. */
    public static List<?> laneNotes(Rke lane) {
        List<?> real = lane.cfM();
        Feed[] now = feeds;
        if (now.length == 0) {
            return real;
        }
        try {
            List<Object> outcome = null;
            for (Feed feed : now) {
                if (!feed.touched.contains(lane)) {
                    continue;
                }
                if (outcome == null) {
                    outcome = new ArrayList<>(real);
                }
                outcome.removeIf(feed.gone::contains);
                List<Object> added = feed.added.get(lane);
                if (added != null) {
                    outcome.addAll(added);
                }
            }
            if (outcome == null) {
                return real;
            }
            outcome.sort(BY_START); // stable: a real note stays before a preview note starting with it
            return outcome;
        } catch (RuntimeException e) {
            MOD.log("a heard preview's notes could not be given to the engine", e);
            return real;
        }
    }
}
