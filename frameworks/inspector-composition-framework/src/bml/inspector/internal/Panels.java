package bml.inspector.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import bitwig.obf.LJt; // a text value (a block's title)
import bitwig.obf.aGh; // the Arranger Clip Inspector
import bitwig.obf.aGk; // the Cue Marker Inspector
import bitwig.obf.aJL; // the Launcher Clip Inspector
import bitwig.obf.aJc; // the Device Inspector
import bitwig.obf.aKc; // the Modulator Inspector
import bitwig.obf.aKh; // the Module Inspector
import bitwig.obf.aLW; // the Scene Inspector
import bitwig.obf.aLZ; // an Inspector panel
import bitwig.obf.aLn; // the Note Inspector
import bitwig.obf.aMx; // the Slot Inspector
import bitwig.obf.aNV; // the Track Inspector
import bitwig.obf.dWb; // a vertical or horizontal group of widgets
import bitwig.obf.dnF; // a framed widget
import bitwig.obf.dvW; // a widget's base: children and visibility
import bitwig.obf.dve; // a widget
import bitwig.obf.kbG; // the selection an Inspector panel shows
import bitwig.obf.svm; // a size policy
import bitwig.obf.vjp; // a fixed text value
import bml.inspector.BlockContext;
import bml.inspector.BuiltBlock;
import bml.inspector.Context;

/** What the framework does while Bitwig builds an Inspector panel. */
final class Panels {
    /**
     * Minimum height of Bitwig's track block (all track types) while one track is selected and a mod adds a block to
     * the Track Inspector, so Bitwig's block doesn't shrink until it looks wrong when it shares the Inspector: at 1256
     * the block and its fader keep the same size whichever track type is selected. With several tracks selected the
     * block shows Bitwig's shared rows (no fader) and keeps its own height; with its body hidden it keeps its own
     * height too.
     */
    static final long TRACK_MINIMUM_HEIGHT = 1256;

    /** One panel being built. */
    private static final class Build {
        final aLZ panel;
        final Context context;
        final List<NativeBlock> nativeBlocks = new ArrayList<>();
        /** Bitwig's track block as its builder filled it: header first, then the body. */
        dvW trackGroup;

        Build(aLZ panel) {
            this.panel = panel;
            this.context = contextOf(panel);
        }
    }

    private record NativeBlock(String title, dve widget) {
    }

    private record Section(aLZ panel, String title) {
    }

    private record Anchor(dvW container, int index) {
    }

    /** A mod's block while the framework builds it through Bitwig's block builder. */
    private static final class OwnBlock {
        final Registry.Declared declared;
        final Context context;
        boolean built;

        OwnBlock(Registry.Declared declared, Context context) {
            this.declared = declared;
            this.context = context;
        }
    }

    private static final ThreadLocal<Build> BUILD = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<Section>> SECTIONS = ThreadLocal.withInitial(ArrayDeque::new);
    /** Set while the framework itself builds a block through Bitwig's block builder. */
    private static final ThreadLocal<OwnBlock> OWN_BLOCK = new ThreadLocal<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private Panels() {
    }

    /** The context an Inspector panel shows, or null for panels the framework doesn't cover. */
    static Context contextOf(Object panel) {
        if (panel instanceof aNV) return Context.TRACK;
        if (panel instanceof aJc) return Context.DEVICE;
        if (panel instanceof aGh) return Context.ARRANGER_CLIP;
        if (panel instanceof aJL) return Context.LAUNCHER_CLIP;
        if (panel instanceof aMx) return Context.SLOT;
        if (panel instanceof aLW) return Context.SCENE;
        if (panel instanceof aLn) return Context.NOTE;
        if (panel instanceof aKh) return Context.MODULE;
        if (panel instanceof aKc) return Context.MODULATOR;
        if (panel instanceof aGk) return Context.CUE_MARKER;
        return null;
    }

    /** Logs a failure once per key, so a failure in a panel rebuilt many times doesn't flood the log. */
    static void report(String key, String message, Throwable t) {
        if (REPORTED.add(key)) Registry.LOG.log(message, t);
    }

    // ---- the panel -------------------------------------------------------------------------

    static void panelStarts(aLZ panel) {
        BUILD.set(new Build(panel));
    }

    static void panelEnds() {
        BUILD.remove();
    }

    /** After Bitwig built the panel's body: the track minimum, then the mods' blocks. */
    static void bodyBuilt(aLZ panel, dWb parent) {
        Build build = BUILD.get();
        if (build == null || build.panel != panel || build.context == null) return;
        if (build.context == Context.TRACK && Registry.trackBlocks()) {
            try {
                applyTrackMinimum(build, parent);
            } catch (Throwable t) {
                report("track-minimum", "track block minimum height failed", t);
            }
        }
        addBlocks(build, parent);
    }

    // ---- every block, Bitwig's and the mods' ----------------------------------------------------

    static void sectionStarts(aLZ panel, LJt title) {
        SECTIONS.get().push(new Section(panel, title == null ? "" : String.valueOf(title.getValue())));
    }

    /** Bitwig's block builder filled a block's body: tell the listeners, and note Bitwig's track block. */
    static void sectionFilled(Object body) {
        ArrayDeque<Section> stack = SECTIONS.get();
        if (stack.isEmpty() || !(body instanceof dvW group)) return;
        Section section = stack.peek();
        OwnBlock own = OWN_BLOCK.get();
        if (own == null) {
            Build build = BUILD.get();
            if (build != null && build.panel == section.panel && build.context == Context.TRACK && stack.size() == 1)
                build.trackGroup = group;
        } else if (!own.built) {
            return; // its content failed: the block isn't shown
        }
        List<Registry.Listener> listeners = Registry.listeners();
        if (listeners.isEmpty()) return;
        BuiltBlock built = own != null
                ? new BuiltBlock(own.context, own.declared.title, own.declared.modId, own.declared.id, section.panel, group)
                : new BuiltBlock(contextOf(section.panel), section.title, null, null, section.panel, group);
        for (Registry.Listener l : listeners) {
            try {
                l.listener().accept(built);
            } catch (Throwable t) {
                report("listener-" + l.modId(), "mod " + l.modId() + ": block listener failed", t);
            }
        }
    }

    static void sectionEnds(dve block) {
        ArrayDeque<Section> stack = SECTIONS.get();
        if (stack.isEmpty()) return;
        Section section = stack.pop();
        Build build = BUILD.get();
        if (build != null && build.panel == section.panel && block != null && OWN_BLOCK.get() == null)
            build.nativeBlocks.add(new NativeBlock(section.title, block));
        if (stack.isEmpty()) SECTIONS.remove();
    }

    /**
     * Raises the three lengths of the track block's own height policy (minimum, preferred, maximum) to at least the
     * minimum while one track is selected and the block's body is shown. Its widgets stay as Bitwig built them.
     *
     * <p>The Track Inspector is one panel for one track and for several: the block's title follows the selection
     * ("TRACKS (2)") and Bitwig swaps the rows below it when the selection changes (one track's rows, or the shared
     * rows when the selection holds several, {@code kbG.zpW()}), without building the panel again. So the selection,
     * and whether the body is hidden, are asked at every layout, not once.
     */
    private static void applyTrackMinimum(Build build, dWb parent) {
        List<?> contents = parent.jm_();
        if (contents.size() != 1 || !(contents.get(0) instanceof dve block)) return;
        svm original = block.jI_(); // its height policy
        if (original == null) return;
        dvW group = build.trackGroup;
        kbG selection = build.panel.vpw();
        block.MRY(new MinimumHeight(original, TRACK_MINIMUM_HEIGHT,
                () -> bodyHidden(group) || severalSelected(selection)));
    }

    /** Whether every widget below the block's header is hidden (by any mod); false if the block isn't known. */
    private static boolean bodyHidden(dvW group) {
        if (group == null) return false;
        try {
            List<?> children = group.jm_();
            if (children.size() < 2) return false;
            for (Object child : children.subList(1, children.size())) {
                if (!(child instanceof dvW widget) || widget.uev()) return false; // shown
            }
            return true;
        } catch (Throwable t) {
            report("track-body", "reading the track block's body failed; the track minimum stays", t);
            return false;
        }
    }

    /** Whether the selection holds several tracks; on a failure, false (the minimum stays, as before). */
    private static boolean severalSelected(kbG selection) {
        if (selection == null) return false;
        try {
            return selection.zpW();
        } catch (Throwable t) {
            report("track-selection", "reading the track selection failed; the track minimum stays", t);
            return false;
        }
    }

    // ---- the mods' blocks ----------------------------------------------------------------------

    private static void addBlocks(Build build, dWb parent) {
        int topAdded = 0;
        Map<dve, dve> lastAfter = new IdentityHashMap<>();
        for (Registry.Declared b : Registry.blocks()) {
            Context context = null;
            for (Context c : b.contexts) {
                if (c == build.context || (c.noteBlock() && build.context == Context.NOTE)) {
                    context = c;
                    break;
                }
            }
            if (context == null) continue;
            try {
                NativeBlock target = context.noteBlock() ? nativeBlock(build, context) : null;
                if (context.noteBlock() && target == null) {
                    report("anchor-" + b.who(), "block " + b.who() + ": Bitwig's " + context.id() + " block not found; not shown", null);
                    continue;
                }
                dve block = createBlock(build.panel, b, context);
                if (block == null) continue;
                if (target != null) {
                    dve reference = b.top ? target.widget : lastAfter.getOrDefault(target.widget, target.widget);
                    Anchor at = anchor(parent, reference);
                    if (at == null) {
                        report("anchor-" + b.who(), "block " + b.who() + ": Bitwig's " + context.id() + " block not found in the panel; not shown", null);
                        continue;
                    }
                    at.container.IwV(block, at.index + (b.top ? 0 : 1)); // insert at
                    if (!b.top) lastAfter.put(target.widget, block);
                } else if (b.top) {
                    parent.IwV(block, topAdded++); // insert at
                } else {
                    parent.HL(block); // add
                }
            } catch (Throwable t) {
                report("block-" + b.who(), "block " + b.who() + " failed", t);
            }
        }
    }

    /** Bitwig's block a note context is placed next to; null if not exactly one. */
    private static NativeBlock nativeBlock(Build build, Context context) {
        String title = switch (context) {
            case NOTE_SECTION -> "note";
            case NOTE_OPERATORS -> "operators";
            case NOTE_EXPRESSIONS -> "expressions";
            default -> null;
        };
        NativeBlock match = null;
        for (NativeBlock n : build.nativeBlocks) {
            if (!names(n.title, title)) continue;
            if (match != null) return null;
            match = n;
        }
        return match;
    }

    /**
     * Whether a title of Bitwig's names this block. Bitwig titles the Note Inspector's main block with the selection's
     * live text, "NOTE" for one note and "NOTES (8)" for eight, so a count in brackets and a plural are allowed.
     */
    private static boolean names(String shown, String block) {
        String t = shown.toLowerCase(java.util.Locale.ROOT).trim().replaceFirst("\\s*\\(\\d+\\)$", "");
        return t.equals(block) || t.equals(block + "s");
    }

    /** Where {@code target} sits in the panel's widget tree (five levels deep at most); null if not exactly once. */
    private static Anchor anchor(dvW root, dve target) {
        List<Anchor> found = new ArrayList<>();
        scan(root, target, 0, new IdentityHashMap<>(), found);
        return found.size() == 1 ? found.get(0) : null;
    }

    private static void scan(dvW current, dve target, int depth, Map<Object, Boolean> seen, List<Anchor> found) {
        if (depth > 5 || found.size() > 1 || seen.put(current, true) != null) return;
        List<?> children = current.jm_();
        for (int i = 0; i < Math.min(children.size(), 40); i++) {
            Object child = children.get(i);
            if (child == target) found.add(new Anchor(current, i));
            if (child instanceof dvW group && depth < 5) scan(group, target, depth + 1, seen, found);
        }
    }

    /**
     * A block of a mod's own, through Bitwig's block builder: its header with the title, and a body group holding the
     * mod's content. Null if the content failed, so no header is left without a body.
     */
    private static dve createBlock(aLZ panel, Registry.Declared b, Context context) {
        String key = b.who() + "/" + context.id();
        OwnBlock own = new OwnBlock(b, context);
        Consumer<Object> fill = group -> {
            try {
                dWb body = dWb.UNj(2); // a vertical group
                panel.cfM((dWb) group, new dve[]{body}); // add to the block's body, as Bitwig adds its rows
                Object content = b.content.apply(new BlockContext(context, panel));
                if (!(content instanceof dve widget))
                    throw new IllegalStateException("content returned " + (content == null ? "null" : content.getClass().getName()) + ", not a Bitwig widget");
                panel.cfM(body, new dve[]{widget});
                own.built = true;
            } catch (Throwable t) {
                report("content-" + key, "block " + b.who() + ": content failed; block not shown", t);
            }
        };
        dve block;
        OwnBlock outer = OWN_BLOCK.get();
        OWN_BLOCK.set(own);
        try {
            block = panel.cfM(new vjp(b.title), fill, (dve) null); // Bitwig's block builder, no accessory
        } finally {
            if (outer == null) OWN_BLOCK.remove();
            else OWN_BLOCK.set(outer);
        }
        return own.built ? block : null;
    }

    // ---- device rows ---------------------------------------------------------------------------

    /**
     * Bitwig built its Device block's rows: add the mods' rows at the end, with Bitwig's divider
     * between rows of different mods.
     */
    static void deviceRows(aJc panel, dve rowsGroup) {
        if (!(rowsGroup instanceof dWb group)) return;
        String lastMod = null;
        for (Registry.Rows r : Registry.rows()) {
            try {
                Object content = r.content.apply(new BlockContext(Context.DEVICE, panel));
                if (!(content instanceof dve widget))
                    throw new IllegalStateException("content returned " + (content == null ? "null" : content.getClass().getName()) + ", not a Bitwig widget");
                int before = group.jm_().size();
                panel.cfM(group, new dve[]{widget});
                if (lastMod != null && !lastMod.equals(r.modId) && group.jm_().size() == before + 1) {
                    try {
                        group.IwV(divider(panel), before);
                    } catch (Throwable t) {
                        report("divider-" + r.who(), "divider before " + r.who() + " failed", t);
                    }
                }
                lastMod = r.modId;
            } catch (Throwable t) {
                report("rows-" + r.who(), "device rows " + r.who() + " failed; not shown", t);
            }
        }
    }

    /** A one-unit divider in Bitwig's block background, as between Bitwig's own block bodies. */
    private static dve divider(aJc panel) {
        dWb line = dWb.UNj(0); // a vertical group
        line.Hnt(1L); // a gap of one unit
        dnF shell = dnF.Hnt(line, 0); // framed
        shell.cfM(panel.q2u().lYc().IwV()); // in the theme's block background: palette, its background group, its drawable
        return shell;
    }
}
