package bml.actions.internal;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import bitwig.obf.dmQ; // an action's binding
import bitwig.obf.dmS; // an action category
import bitwig.obf.dmX; // an action
import bitwig.obf.dpN; // Bitwig's application, which owns the action registry
import bitwig.obf.dve; // a widget; the project window is one
import bml.actions.Action;
import bml.actions.ActionContext;
import bml.actions.ActionProvider;
import bml.actions.BitwigAction;
import bml.actions.Actions;
import bml.actions.Menu;
import bml.actions.Submenu;
import bml.api.Bml;

/** Every mod's declared actions and submenus; the actions are created as Bitwig actions. */
final class Registry {
    static final String FRAMEWORK = "action-menu-composition-framework";
    static final Bml.Mod LOG = Bml.mod(FRAMEWORK);
    private static final String ID = "[A-Za-z][A-Za-z0-9_.-]*";

    /** An item of a menu: an action or a submenu. */
    abstract static class Entry {
        final String modId, id;
        String label; // a placed Bitwig action's is Bitwig's, known once it is found
        final Set<Menu> menus = new LinkedHashSet<>();

        Entry(String modId, String id, String label) {
            if (id == null || !id.matches(ID)) throw new IllegalArgumentException("invalid ID: " + id);
            if (label == null || label.isBlank()) throw new IllegalArgumentException(id + " has no label");
            this.modId = modId;
            this.id = id;
            this.label = label;
        }

        String who() {
            return modId + "/" + id;
        }
    }

    /** One declared action. */
    static final class Declared extends Entry implements Action {
        Consumer<ActionContext> run;
        Predicate<ActionContext> visible = c -> true;
        String category, icon, menuLabel;
        boolean menuOnly, inSubmenu;
        final Set<Class<?>> widgets = new LinkedHashSet<>(); // bindOn; empty: bound on the project window
        ModAction bitwig; // also for a menu-only action, which isn't registered: its menu rows are built from it

        Declared(String modId, String actionId, String label) {
            super(modId, actionId, label);
        }

        @Override
        public Action in(Menu... menus) {
            Collections.addAll(this.menus, menus);
            return this;
        }

        @Override
        public Action onRun(Consumer<ActionContext> run) {
            this.run = run;
            return this;
        }

        @Override
        public Action menuOnly() {
            this.menuOnly = true;
            return this;
        }

        @Override
        public Action menuLabel(String label) {
            if (label == null || label.isBlank()) throw new IllegalArgumentException(id + ": empty menu label");
            this.menuLabel = label;
            return this;
        }

        @Override
        public Action visibleWhen(Predicate<ActionContext> visible) {
            this.visible = visible;
            return this;
        }

        @Override
        public Action category(String categoryId) {
            this.category = categoryId;
            return this;
        }

        @Override
        public Action icon(String resourceId) {
            this.icon = resourceId;
            return this;
        }

        @Override
        public Action bindOn(Class<?>... widgets) {
            if (widgets == null || widgets.length == 0) throw new IllegalArgumentException(id + ": bindOn names no widget");
            for (Class<?> c : widgets) {
                if (c == null || !dve.class.isAssignableFrom(c)) throw new IllegalArgumentException(id + ": bindOn needs a widget class, not " + c);
                this.widgets.add(c);
            }
            return this;
        }

        boolean bindsOn(Class<?> widgetClass) {
            for (Class<?> c : widgets) if (c.isAssignableFrom(widgetClass)) return true;
            return false;
        }

        /** Runs the mod's callback; a failure is logged and contained. */
        void run(ActionContext context) {
            try {
                run.accept(context);
            } catch (Throwable t) {
                LOG.log("action " + who() + " failed", t);
            }
        }

        boolean visibleIn(ActionContext context) {
            if (bitwig == null) return false;
            try {
                return visible.test(context);
            } catch (Throwable t) {
                LOG.log("visibleWhen of " + who() + " failed; hidden", t);
                return false;
            }
        }
    }

    /** One of Bitwig's own actions, placed by a mod. */
    static final class Placed extends Entry implements BitwigAction {
        Predicate<ActionContext> visible = c -> true;
        String icon;
        private boolean resolved;
        private dmX action;

        Placed(String modId, String bitwigActionId) {
            super(modId, bitwigActionId, bitwigActionId);
        }

        @Override
        public BitwigAction in(Menu... menus) {
            Collections.addAll(this.menus, menus);
            return this;
        }

        @Override
        public BitwigAction visibleWhen(Predicate<ActionContext> visible) {
            this.visible = visible;
            return this;
        }

        @Override
        public BitwigAction icon(String resourceId) {
            this.icon = resourceId;
            return this;
        }

        /**
         * Bitwig's action, found by its ID; null if it doesn't exist. Found (and given the mod's icon)
         * when the actions are loaded, before any window binds its actions: a binding takes the
         * action's icon when it is created.
         */
        synchronized dmX action() {
            if (resolved) return action;
            resolved = true;
            try {
                action = dpN.TVZ().tp2(id); // Bitwig's action by its ID
                if (action == null) {
                    LOG.log("Bitwig action " + id + " (placed by " + modId + ") doesn't exist in this Bitwig; left out");
                    return null;
                }
                String name = action.eLY(); // its label
                if (name != null && !name.isBlank()) label = name;
                if (icon != null) {
                    String own = action.fJR(); // its icon
                    String path = Icons.path(modId, icon);
                    if (own != null && !own.isBlank()) {
                        if (!own.equals(path)) LOG.log("Bitwig action " + id + " has its own icon; " + modId + "'s icon not used");
                    } else if (path != null) action.hUR = path; // the icon field
                }
            } catch (Throwable t) {
                action = null;
                LOG.log("Bitwig action " + id + " (placed by " + modId + ") could not be found", t);
            }
            return action;
        }

        boolean visibleIn(ActionContext context) {
            if (action() == null) return false;
            try {
                return visible.test(context);
            } catch (Throwable t) {
                LOG.log("visibleWhen of " + who() + " failed; hidden", t);
                return false;
            }
        }
    }

    /** One declared submenu and what was declared through it. */
    static final class Folder extends Entry implements Submenu {
        final List<Entry> children = new ArrayList<>();
        private final Declaration in;

        Folder(Declaration in, String submenuId, String label) {
            super(in.modId, submenuId, label);
            this.in = in;
        }

        @Override
        public Submenu in(Menu... menus) {
            Collections.addAll(this.menus, menus);
            return this;
        }

        @Override
        public Action action(String actionId, String label) {
            Declared a = in.action(actionId, label);
            a.inSubmenu = true;
            children.add(a);
            return a;
        }

        @Override
        public Submenu submenu(String submenuId, String label) {
            Folder f = in.folder(submenuId, label);
            children.add(f);
            return f;
        }

        @Override
        public BitwigAction bitwigAction(String bitwigActionId) {
            Placed p = new Placed(in.modId, bitwigActionId);
            children.add(p);
            in.allPlaced.add(p);
            return p;
        }
    }

    /** What one mod declares, collected before any of it is accepted. */
    static final class Declaration implements Actions {
        final String modId;
        final List<Declared> actions = new ArrayList<>();
        final List<Folder> topFolders = new ArrayList<>();
        final List<Placed> topPlaced = new ArrayList<>();
        final List<Placed> allPlaced = new ArrayList<>(); // top-level and in submenus
        final Set<String> folderIds = new HashSet<>();
        final Map<String, String> categories = new LinkedHashMap<>();

        Declaration(String modId) {
            this.modId = modId;
        }

        @Override
        public Declared action(String actionId, String label) {
            Declared a = new Declared(modId, actionId, label);
            actions.add(a);
            return a;
        }

        Folder folder(String submenuId, String label) {
            if (!folderIds.add(submenuId)) throw new IllegalArgumentException("submenu ID " + submenuId + " is declared twice");
            return new Folder(this, submenuId, label);
        }

        @Override
        public Submenu submenu(String submenuId, String label) {
            Folder f = folder(submenuId, label);
            topFolders.add(f);
            return f;
        }

        @Override
        public BitwigAction bitwigAction(String bitwigActionId) {
            Placed p = new Placed(modId, bitwigActionId);
            topPlaced.add(p);
            allPlaced.add(p);
            return p;
        }

        @Override
        public void category(String categoryId, String label) {
            if (categoryId == null || !categoryId.matches(ID)) throw new IllegalArgumentException("invalid category ID: " + categoryId);
            if (label == null || label.isBlank()) throw new IllegalArgumentException("category " + categoryId + " has no label");
            categories.put(categoryId, label);
        }
    }

    private static final Map<String, Declared> ACTIONS = new LinkedHashMap<>();
    private static final List<Folder> TOP_FOLDERS = new ArrayList<>();
    private static final List<Placed> TOP_PLACED = new ArrayList<>();
    private static final List<Placed> ALL_PLACED = new ArrayList<>();
    /** Categories mods declare: ID -> label, and the mod that declared it first. */
    private static final Map<String, String> CATEGORY_LABELS = new LinkedHashMap<>();
    private static final Map<String, String> CATEGORY_OWNERS = new LinkedHashMap<>();
    private static boolean loaded;
    /** The actions bound on widgets rather than the project window. Set once, at the end of {@link #load}. */
    private static volatile List<Declared> widgetBound = List.of();
    /** Those of them for one widget class. Asked only once {@link #widgetBound} is set, so nothing stale is kept. */
    private static final ClassValue<List<Declared>> WIDGET_BOUND = new ClassValue<>() {
        @Override
        protected List<Declared> computeValue(Class<?> widgetClass) {
            List<Declared> out = new ArrayList<>();
            for (Declared a : widgetBound) if (a.bindsOn(widgetClass)) out.add(a);
            return out.isEmpty() ? List.of() : out;
        }
    };

    private Registry() {
    }

    /** Asks every registered provider for its actions and registers them with Bitwig. Once. */
    static synchronized void load() {
        if (loaded) return;
        loaded = true;
        for (Bml.Registration reg : Bml.registrations(FRAMEWORK)) {
            Declaration d = new Declaration(reg.modId());
            try {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(reg.descriptor())) {
                    p.load(in);
                }
                if (!"1".equals(p.getProperty("api.version", "").trim()))
                    throw new IllegalArgumentException("api.version must be 1 in " + reg.descriptor());
                String className = p.getProperty("provider.class", "").trim();
                Class<?> type = Class.forName(className, true, Registry.class.getClassLoader());
                ((ActionProvider) type.getConstructor().newInstance()).declare(d);
            } catch (Throwable t) {
                LOG.log("mod " + reg.modId() + ": actions skipped", t);
                continue;
            }
            for (var c : d.categories.entrySet()) {
                String owner = CATEGORY_OWNERS.putIfAbsent(c.getKey(), reg.modId());
                if (owner == null) CATEGORY_LABELS.put(c.getKey(), c.getValue());
                else if (!CATEGORY_LABELS.get(c.getKey()).equals(c.getValue()))
                    LOG.log("mod " + reg.modId() + ": category " + c.getKey() + " is already declared by " + owner + " as \"" + CATEGORY_LABELS.get(c.getKey()) + "\"; that one is used");
            }
            for (Declared a : d.actions) {
                if (a.run == null) LOG.log("action " + a.who() + " has no onRun; skipped");
                else if (ACTIONS.containsKey(a.id)) LOG.log("action " + a.who() + " skipped: " + ACTIONS.get(a.id).who() + " already uses that ID");
                else ACTIONS.put(a.id, a);
            }
            TOP_FOLDERS.addAll(d.topFolders);
            TOP_PLACED.addAll(d.topPlaced);
            ALL_PLACED.addAll(d.allPlaced);
        }
        dpN app = dpN.TVZ(); // the application singleton
        Map<String, dmS> categories = new LinkedHashMap<>();
        StringBuilder known = new StringBuilder();
        for (Object c : app.jKQ()) { // Bitwig's action categories, in its order
            if (!(c instanceof dmS cat)) continue;
            categories.put(cat.cfM(), cat); // by ID
            known.append(known.isEmpty() ? "" : ", ").append(cat.cfM()).append('=').append(cat.Hnt()); // ID = label
        }
        LOG.log("Bitwig action categories: " + known);
        for (var c : CATEGORY_LABELS.entrySet()) {
            if (categories.containsKey(c.getKey())) LOG.log("category " + c.getKey() + " is one of Bitwig's; Bitwig's is used");
            else categories.put(c.getKey(), new ModCategory(c.getKey(), c.getValue()));
        }
        for (Declared a : ACTIONS.values()) {
            if (a.menuOnly && a.menus.isEmpty() && !a.inSubmenu) {
                LOG.log("action " + a.who() + " is menu-only but in no menu; skipped");
                continue;
            }
            try {
                dmS category = null;
                if (a.category != null) {
                    category = categories.get(a.category);
                    if (category == null) LOG.log("action " + a.who() + ": unknown category " + a.category + "; listed under General");
                }
                String icon = Icons.path(a.modId, a.icon);
                a.bitwig = category == null ? new ModAction(a.id, a.label, a.menuLabel, icon)
                        : new ModAction(a.id, a.label, a.menuLabel, icon, category);
                if (!a.menuOnly) app.MRY(a.bitwig); // register the action
            } catch (Throwable t) {
                a.bitwig = null;
                LOG.log("action " + a.who() + " could not be registered with Bitwig", t);
            }
        }
        for (Placed p : ALL_PLACED) p.action(); // find Bitwig's actions and set icons before windows bind them
        List<Declared> onWidgets = new ArrayList<>();
        for (Declared a : ACTIONS.values()) {
            if (a.widgets.isEmpty() || a.bitwig == null) continue;
            if (a.menuOnly) LOG.log("action " + a.who() + " is menu-only; bindOn has no effect");
            else onWidgets.add(a);
        }
        widgetBound = List.copyOf(onWidgets);
        LOG.log("registered " + ACTIONS.values().stream().filter(a -> a.bitwig != null && !a.menuOnly).count() + " action(s) from "
                + ACTIONS.values().stream().map(a -> a.modId).distinct().count() + " mod(s)");
        long menuOnly = ACTIONS.values().stream().filter(a -> a.bitwig != null && a.menuOnly).count();
        if (menuOnly > 0) LOG.log(menuOnly + " menu-only action(s), not registered");
    }

    /** Binds every action that names no widget in a project window, so its shortcut and the Commander run it there. */
    static synchronized void bind(dve window) {
        for (Declared a : ACTIONS.values()) {
            if (a.bitwig == null || a.menuOnly || !a.widgets.isEmpty()) continue;
            try {
                dmQ existing = window.cfM(a.bitwig); // the action's binding in this window, if any
                if (existing == null) window.cfM(a.bitwig, () -> a.run(ActionContext.shortcut(window))); // bind
            } catch (Throwable t) {
                LOG.log("action " + a.who() + " could not be bound in a project window", t);
            }
        }
    }

    /** Binds the actions that name this widget's class on it. Runs for every widget, so it holds no lock and looks up per class. */
    static void bindOnWidget(dve widget) {
        if (widgetBound.isEmpty()) return;
        for (Declared a : WIDGET_BOUND.get(widget.getClass())) {
            try {
                if (widget.cfM(a.bitwig) == null) widget.cfM(a.bitwig, () -> a.run(ActionContext.shortcut(widget))); // bind, once: a widget can be attached again
            } catch (Throwable t) {
                LOG.log("action " + a.who() + " could not be bound on " + widget.getClass().getName(), t);
            }
        }
    }

    /** The order of a menu's mod entries: submenus first, then actions; each alphabetical by label. */
    static final Comparator<Entry> ORDER = Comparator.comparing((Entry e) -> !(e instanceof Folder))
            .thenComparing(e -> e.label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(e -> e.modId).thenComparing(e -> e.id);

    /** The entries a mod placed directly in the menu a context was opened in, unfiltered. */
    static synchronized List<Entry> top(ActionContext context) {
        List<Entry> out = new ArrayList<>();
        for (Folder f : TOP_FOLDERS) if (placed(f, context)) out.add(f);
        for (Declared a : ACTIONS.values()) if (placed(a, context)) out.add(a);
        for (Placed p : TOP_PLACED) if (placed(p, context)) out.add(p);
        return out;
    }

    private static boolean placed(Entry e, ActionContext context) {
        String id = context.menu() == null ? "" : context.menu().id();
        return e.menus.contains(context.menu())
                || id.startsWith("selection:") && e.menus.contains(Menu.SELECTION)
                || id.startsWith("selection-toolbar:") && e.menus.contains(Menu.SELECTION_TOOLBAR);
    }
}
