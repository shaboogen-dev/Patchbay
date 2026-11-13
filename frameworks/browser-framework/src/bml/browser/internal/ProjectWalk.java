package bml.browser.internal;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.bitwig.flt.document.core.iface.RYd; // a track or a device chain (its displayed name)
import com.bitwig.flt.document.core.master.TL; // a track or a device chain (layer, drum pad): has a device chain
import com.bitwig.flt.document.core.master.device.xlG; // a device
import com.bitwig.flt.document.core.master.file_preview.Jkg; // the browser's preview of a file (a document of its own)
import com.bitwig.flt.document.core.master.ruS; // a group track (the project's root group too)
import com.bitwig.flt.document.core.master.zwV; // a track of the project's track list
import com.bitwig.ramona.core.Dgc; // a reference of a project object
import com.bitwig.ramona.core.OLR; // a project object, as a list holds it
import com.bitwig.ramona.core.bnq; // a list of a project object
import com.bitwig.ramona.core.oDc; // a project object
import com.bitwig.ramona.core.uzc; // a property of a project object

import bitwig.obf.Ob; // a clip
import bitwig.obf.SEJ; // the transport (tempo, groove, crossfade automation): not a track of the list
import bitwig.obf.xJo; // a channel: an audio, instrument or hybrid track, a group's own channel, the master
import bitwig.obf.dpN; // Bitwig's UI event loop
import bml.browser.ProjectItem;

/**
 * Lists the open project's tracks, devices and clips from Bitwig's project model: from the project
 * object down through each object's own lists and references (the objects it owns), and every device
 * chain's devices, on the UI thread in slices of a few milliseconds. A clip's contents (notes, audio
 * events) aren't walked. Only what the user made and sees is listed:
 * <ul>
 * <li>a track is one of the track list's ({@code zwV}): audio, instrument, hybrid and effect tracks,
 *     groups, and the master (the root group's own channel). Not the root group, a group's own
 *     channel (the group is listed), the transport ({@code SEJ}), device chains (layers, drum pads:
 *     {@code TL} but not {@code zwV}), nor anything in the browser's file previews ({@code Jkg},
 *     documents of their own that the project holds);</li>
 * <li>a device is one in a device chain's list ({@code TL.KAS().HL()}), of a track or of a chain
 *     inside a device; not the devices Bitwig keeps beside the chain (each track's Track Controls);</li>
 * <li>a clip is any clip object ({@code Ob}: audio, note and automation clips) outside the previews.</li>
 * </ul>
 */
final class ProjectWalk {
    /** At most this many project objects are looked at; the rest is left out (logged). */
    static final int MAX_OBJECTS = 50_000;
    /** How long one slice runs on the UI thread before the walk lets Bitwig run, in nanoseconds. */
    private static final long SLICE_NANOS = 4_000_000L;

    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    /** Project objects by item ID, weakly: an item whose object Bitwig let go is gone. */
    private static final Map<Long, WeakReference<Object>> OBJECTS = new ConcurrentHashMap<>();

    private final oDc root;
    private final BooleanSupplier cancelled;
    private final Consumer<List<ProjectItem>> done;
    private final Consumer<Throwable> failed;
    private final Deque<Node> stack = new ArrayDeque<>();
    private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<ProjectItem> items = new ArrayList<>();
    private int looked;
    private final long started = System.nanoTime();
    /** For the walk's log line: per kind, how many of each class, and how many had no name. */
    private final Map<String, Map<String, Integer>> classes = new TreeMap<>();
    private final Map<String, Integer> unnamed = new TreeMap<>();
    /** For the log line: what was left out, by reason. */
    private final Map<String, Integer> skipped = new TreeMap<>();
    /** The devices in the device chains met so far (identity). */
    private final Set<Object> chainDevices = Collections.newSetFromMap(new IdentityHashMap<>());

    /** An object to look at, with the listed item it is inside. */
    private record Node(oDc object, String track, int depth) {
    }

    private ProjectWalk(oDc root, BooleanSupplier cancelled, Consumer<List<ProjectItem>> done,
                        Consumer<Throwable> failed) {
        this.root = root;
        this.cancelled = cancelled;
        this.done = done;
        this.failed = failed;
    }

    /** Starts listing {@code project} (a project object); the results arrive on the UI thread. */
    static void start(Object project, BooleanSupplier cancelled, Consumer<List<ProjectItem>> done,
                      Consumer<Throwable> failed) {
        if (!(project instanceof oDc root)) throw new IllegalStateException("The project isn't available");
        OBJECTS.values().removeIf(ref -> ref.get() == null);
        ProjectWalk walk = new ProjectWalk(root, cancelled, done, failed);
        walk.stack.push(new Node(root, "", -1));
        dpN.TVZ().asyncExec(walk::slice);
    }

    /** The project object behind an item, or null when Bitwig no longer has it. */
    static Object object(ProjectItem item) {
        WeakReference<Object> ref = item == null ? null : OBJECTS.get(item.id());
        return ref == null ? null : ref.get();
    }

    int looked() {
        return looked;
    }

    private void slice() {
        try {
            if (cancelled != null && cancelled.getAsBoolean()) return;
            long until = System.nanoTime() + SLICE_NANOS;
            while (!stack.isEmpty() && looked < MAX_OBJECTS && System.nanoTime() < until) step(stack.pop());
            if (!stack.isEmpty() && looked < MAX_OBJECTS) {
                dpN.TVZ().asyncExec(this::slice); // let Bitwig run, then go on
                return;
            }
            if (!stack.isEmpty()) State.LOG.log("projectItems: stopped at " + MAX_OBJECTS + " project objects");
            State.LOG.log("projectItems: walked " + looked + " project objects in " + (System.nanoTime() - started) / 1_000_000
                    + " ms; items by kind and class " + classes + "; without a name " + unnamed + "; left out " + skipped);
            done.accept(List.copyOf(items));
        } catch (Throwable t) {
            failed.accept(t);
        }
    }

    private void step(Node node) {
        oDc object = node.object();
        if (!seen.add(object)) return;
        looked++;
        if (object instanceof Jkg) { // a file preview: its tracks, devices and clips aren't the project's
            skip("file preview");
            return;
        }
        List<oDc> chain = chain(object);
        chainDevices.addAll(chain);
        String kind = kind(object);
        String track = node.track();
        int depth = node.depth();
        if (kind != null) {
            String name = name(object);
            classes.computeIfAbsent(kind, k -> new TreeMap<>()).merge(object.getClass().getSimpleName(), 1, Integer::sum);
            if (name.isEmpty()) unnamed.merge(kind, 1, Integer::sum);
            if (kind.equals("track")) track = name;
            depth++;
            long id = NEXT_ID.getAndIncrement();
            OBJECTS.put(id, new WeakReference<>(object));
            items.add(new ProjectItem(id, kind, name, kind.equals("track") ? name : track, depth));
            if (kind.equals("clip")) return; // a clip's notes and events aren't items
        }
        List<oDc> children = new ArrayList<>(chain); // the chain's devices first, in their order
        children.addAll(children(object));
        for (int i = children.size() - 1; i >= 0; i--) stack.push(new Node(children.get(i), track, depth)); // in order
    }

    private void skip(String reason) {
        skipped.merge(reason, 1, Integer::sum);
    }

    /** What a project object is as an item, or null when it isn't one (the reason counted). */
    private String kind(oDc object) {
        if (object instanceof zwV) {
            if (object instanceof SEJ) {
                skip("transport");
                return null;
            }
            ruS group = ((zwV) object).zjb(); // the group it is in; null for the root group (and the transport)
            if (group == null) {
                skip("root group");
                return null;
            }
            if (object instanceof xJo channel && channel.CDi()) { // a cue channel (Bitwig names it "Cue", not "FX"): not one of the user's
                skip("cue channel");
                return null;
            }
            if (object instanceof xJo && group.Lqt() == object) { // a group's own channel
                if (group.zjb() == null) return "track"; // the root group's: the master
                skip("group channel");
                return null;
            }
            return "track";
        }
        if (object instanceof TL) {
            skip("device chain");
            return null;
        }
        if (object instanceof xlG) {
            if (chainDevices.contains(object)) return "device";
            skip("device outside a chain");
            return null;
        }
        if (object instanceof Ob) return "clip";
        return null;
    }

    /** A track's or chain's devices, as its device chain lists them; else none. */
    private static List<oDc> chain(oDc object) {
        if (!(object instanceof TL owner)) return List.of();
        List<oDc> devices = new ArrayList<>();
        try {
            for (Object device : owner.KAS().HL()) { // its device chain's devices
                if (device instanceof xlG && device instanceof oDc d) devices.add(d);
            }
        } catch (Throwable t) {
            State.LOG.log("projectItems: reading a device chain of " + object.getClass().getName() + " failed", t);
        }
        return devices;
    }

    /** The objects a project object owns: in its lists and its references, in Bitwig's order. */
    private static List<oDc> children(oDc object) {
        List<oDc> children = new ArrayList<>();
        try {
            for (Object field : object.upC()) { // its lists
                if (!(field instanceof bnq list)) continue;
                int size = list.MRY();
                for (int i = 0; i < size; i++) {
                    OLR element = list.Hnt(i);
                    if (element instanceof oDc child && child.K_() == object) children.add(child);
                }
            }
            for (Object field : object.CXm()) { // its references: the ones it owns
                if (!(field instanceof Dgc reference)) continue;
                Object value = reference.getValue();
                if (value instanceof oDc child && child.K_() == object) children.add(child);
            }
        } catch (Throwable t) {
            State.LOG.log("projectItems: reading the contents of " + object.getClass().getName() + " failed", t);
        }
        return children;
    }

    /**
     * The name Bitwig shows for the object: a track's displayed name (the user's name, else Bitwig's
     * own, "Audio 1"; the "name" property is only the user's and is empty until renamed), a clip's
     * name (held by its content, not the clip object); else the "name" property; for a device without
     * one, Bitwig's device name; else empty.
     */
    static String name(oDc object) {
        try {
            String shown = shownName(object);
            if (shown != null && !shown.isBlank()) return shown;
            for (Object field : object.iM()) { // its properties
                if (field instanceof uzc property && "name".equals(property.E3())
                        && property.getValue() instanceof String name && !name.isBlank()) return name;
            }
            if (object instanceof xlG device) {
                String name = device.aG_();
                if (name != null && !name.isBlank()) return name;
            }
        } catch (Throwable t) {
            State.LOG.log("projectItems: reading a name failed", t);
        }
        return "";
    }

    /** A track's displayed name ({@code RYd.cb_()}, as its header shows it) or a clip's ({@code Ob.dH_()}); else null. */
    private static String shownName(oDc object) {
        try {
            if (object instanceof RYd track) return track.cb_();
            if (object instanceof Ob clip) return clip.dH_(); // the name its content holds
        } catch (Throwable t) {
            // no content or not attached: the properties below
        }
        return null;
    }
}
