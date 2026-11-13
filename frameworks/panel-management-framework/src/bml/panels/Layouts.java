package bml.panels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import bitwig.obf.akC; // a display profile: its windows and their layouts
import bitwig.obf.akQ; // a panel
import bitwig.obf.akZ; // a splitter between two parts of a window's layout
import bitwig.obf.akw; // a part of a window's layout
import com.bitwig.flt.document_view_settings.gJi; // a project's view settings for one display profile

/**
 * The framework's record of one display profile: the Bitwig views the policies bound, its view
 * stack if a policy made one, and its side columns. Also runs the profile phases (layout, views,
 * arrange).
 */
final class Layouts implements Layout {
    private static final Map<akC, Layouts> ALL = Collections.synchronizedMap(new IdentityHashMap<>());
    /** Every bound Bitwig panel node and its declared view (for its scale), in every profile. */
    private static final Map<Object, Registry.Declared> BOUND = Collections.synchronizedMap(new WeakHashMap<>());

    private final akC profile;
    private final Map<Registry.Declared, akQ> bound = new IdentityHashMap<>();
    private String caller; // the mod whose policy is running
    Stack stack;                               // the main view stack, or null
    final List<Stack> sides = new ArrayList<>(); // the side columns

    private Layouts(akC profile) {
        this.profile = profile;
    }

    static Layouts of(Object profile) {
        return profile instanceof akC p ? ALL.get(p) : null;
    }

    /** The declared Bitwig view a panel node was bound to, or null. */
    static Registry.Declared boundView(Object node) {
        return node == null ? null : BOUND.get(node);
    }

    /** Whether the node is a view of one of a profile's stacks (the main one or side columns). */
    static boolean inAnyStack(Object node) {
        return stackOf(node) != null;
    }

    /** The stack, in any profile, that holds the node as a view; null if none. */
    static Stack stackOf(Object node) {
        synchronized (ALL) {
            for (Layouts l : ALL.values()) {
                for (Stack s : l.stacks()) if (s.entry(node) != null) return s;
            }
        }
        return null;
    }

    /** Whether the splitter holds a side column (Bitwig's outer one or a ColumnSplit), in any profile. */
    static boolean isColumnSplitter(Object node) {
        synchronized (ALL) {
            for (Layouts l : ALL.values()) {
                for (Stack s : l.sides) if (s.columns.contains(node)) return true;
            }
        }
        return false;
    }

    /** The profile's stacks: the main one first, then the sides. */
    List<Stack> stacks() {
        List<Stack> all = new ArrayList<>();
        if (stack != null) all.add(stack);
        all.addAll(sides);
        return all;
    }

    /** The entry of a declared view in any of the profile's stacks, or null. */
    Stack.Entry entry(String modId, String viewId) {
        for (Stack s : stacks()) {
            Stack.Entry e = s.entry(modId, viewId);
            if (e != null) return e;
        }
        return null;
    }

    /** The stack of the profile that holds the entry. */
    Stack stackOf(Stack.Entry entry) {
        for (Stack s : stacks()) if (s.entries.contains(entry)) return s;
        return null;
    }

    /** The profile phases: every policy's layout, the views (stack or tabs), every policy's arrange. */
    static void build(akC profile) {
        if (profile == null) return;
        Layouts layout = new Layouts(profile);
        ALL.put(profile, layout);
        for (Registry.Policy policy : Registry.policies()) {
            layout.caller = policy.modId();
            try {
                policy.policy().layout(layout);
            } catch (Throwable failure) {
                Registry.LOG.log("mod " + policy.modId() + ": its layout failed in display profile " + profile.MIO(), failure);
            }
        }
        layout.caller = null;
        if (layout.stack == null) Placement.place(profile); // no stack: the mods' views as tabs
        for (Registry.Policy policy : Registry.policies()) {
            layout.caller = policy.modId();
            try {
                policy.policy().arrange(layout);
            } catch (Throwable failure) {
                Registry.LOG.log("mod " + policy.modId() + ": arranging display profile " + profile.MIO() + " failed", failure);
            }
        }
        layout.caller = null;
    }

    /** The policy of a mod, for the stack's callbacks. */
    static LayoutPolicy policyOf(String modId) {
        for (Registry.Policy p : Registry.policies()) if (p.modId().equals(modId)) return p.policy();
        return null;
    }

    // ---------- Layout ----------

    @Override
    public Object profile() {
        return profile;
    }

    @Override
    public void bind(String bitwigViewId, Object node) {
        Registry.Declared view = caller != null ? Registry.find(caller, bitwigViewId) : null;
        if (view == null || !view.bitwig) {
            Registry.LOG.log("bind: mod " + caller + " declared no Bitwig view " + bitwigViewId);
            return;
        }
        if (!(node instanceof akQ panel)) {
            Registry.LOG.log("bind: " + view.who() + " needs a panel node");
            return;
        }
        bound.put(view, panel);
        BOUND.put(panel, view);
    }

    @Override
    public Object stack(String name, String... bitwigViewIds) {
        if (stack != null) throw new IllegalStateException("display profile " + profile.MIO() + " already has a view stack");
        checkName(name);
        for (Registry.Policy p : Registry.policies()) {
            if (p.modId().equals(caller) && !p.viewStack()) {
                Registry.LOG.log("mod " + caller + " builds a view stack but declared its policy without one; "
                        + "the stack's Split and Equalise actions may be missing (Panels.policy(policy, true))");
            }
        }
        List<Stack.Entry> entries = boundEntries("stack", true, bitwigViewIds);
        for (Registry.Declared view : Registry.views()) {
            try {
                ViewNode node = new ViewNode();
                node.init(view, true);
                entries.add(new Stack.Entry(view, node, caller));
            } catch (Throwable failure) {
                Registry.LOG.log("view " + view.who() + " could not be placed in the stack", failure);
            }
        }
        if (entries.isEmpty()) throw new IllegalStateException("a view stack needs at least one view");
        stack = Stack.build(this, name, false, entries);
        return stack.root;
    }

    @Override
    public void sideColumns(Object splitter, Object replaced, String... bitwigViewIds) {
        if (!(splitter instanceof akZ outer) || !outer.lYc()) throw new IllegalArgumentException("side columns need a left/right splitter");
        if (replaced == null || (outer.E3() != replaced && outer.wGc() != replaced)) {
            throw new IllegalArgumentException("side columns replace one of the splitter's two parts");
        }
        checkName(outer.tSS());
        List<Stack.Entry> entries = boundEntries("sideColumns", false, bitwigViewIds);
        if (entries.isEmpty()) throw new IllegalStateException("side columns need at least one bound Bitwig view");
        sides.add(Stack.buildColumns(this, outer, (akw) replaced, entries));
    }

    private void checkName(String name) {
        for (Stack s : stacks()) {
            if (s.name.equals(name)) throw new IllegalStateException("display profile " + profile.MIO() + " already has a stack " + name);
        }
    }

    /** The bound Bitwig views of the calling mod, in this order (main stack views need an icon: their buttons are the framework's). */
    private List<Stack.Entry> boundEntries(String what, boolean needIcon, String... bitwigViewIds) {
        List<Stack.Entry> entries = new ArrayList<>();
        for (String id : bitwigViewIds) {
            Registry.Declared view = caller != null ? Registry.find(caller, id) : null;
            akQ node = view != null ? bound.get(view) : null;
            if (node == null) Registry.LOG.log(what + ": " + caller + "/" + id + " isn't a bound Bitwig view; left out");
            else if (needIcon && view.icon == null) Registry.LOG.log(what + ": " + view.who() + " has no icon; left out");
            else if (stackOf(node) != null) Registry.LOG.log(what + ": " + view.who() + " is in a stack already; left out");
            else entries.add(new Stack.Entry(view, node, caller));
        }
        return entries;
    }

    @Override
    public boolean hasStack() {
        return stack != null;
    }

    @Override
    public void showOnly(Object viewSettings, String modId, String viewId) {
        Stack.Entry entry = entry(modId, viewId);
        if (entry != null && viewSettings instanceof gJi settings) stackOf(entry).showOnly(settings, entry);
    }

    @Override
    public boolean isStackView(Object node) {
        for (Stack s : stacks()) if (s.entry(node) != null) return true;
        return false;
    }
}
