package bml.panels;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import bitwig.obf.akQ; // a panel
import bml.api.Bml;

/** Every mod's declared views, Bitwig views, Bitwig panels matched by class and layout policies, checked, in their order. */
final class Registry {
    static final String FRAMEWORK = "panel-management-framework";
    static final Bml.Mod LOG = Bml.mod(FRAMEWORK);
    private static final String ID = "[A-Za-z][A-Za-z0-9_]*";

    /** A declared view: a mod's own (with its content) or one of Bitwig's panels a policy places. */
    static final class Declared {
        final String modId, id, label;
        final boolean bitwig;
        String iconResource;
        String icon; // the icon name Bitwig loads (resolved by Icons); null for a Bitwig view without one
        Function<ViewContext, Object> content;
        String replaces;           // a Bitwig panel it can take the place of ("mixer"), or null
        Class<?> panelClass;       // a Bitwig panel matched by class (Panels.bitwigPanel): its class
        final Map<String, String> savedTexts = new LinkedHashMap<>(); // key → default
        boolean scalable = true;
        boolean listed = true;     // its scale listed in the framework's Panel Scale section

        Declared(String modId, String id, String label, boolean bitwig) {
            this.modId = modId;
            this.id = id;
            this.label = label;
            this.bitwig = bitwig;
        }

        String who() {
            return modId + "/" + id;
        }

        /** Its path segment in Bitwig's layout (and so in the saved view settings): unique per mod and view. */
        String nodeName() {
            return key();
        }

        /** The prefix of its generated IDs and stored keys: {@code <mod_id>_<view_id>}. */
        String key() {
            return modId.replace('-', '_').replace('.', '_') + "_" + id;
        }

        /** The full key of one of its saved values: {@code <mod_id>_<view_id>.<key>}. */
        String savedKey(String key) {
            return key() + "." + key;
        }

        /** The mod ID as action IDs start with it. */
        String modKey() {
            return modId.replace('-', '_').replace('.', '_');
        }
    }

    private static final class ViewBuilder implements View {
        final Declared d;

        ViewBuilder(Declared d) {
            this.d = d;
        }

        @Override
        public View icon(String resourceId) {
            d.iconResource = resourceId;
            return this;
        }

        @Override
        public View content(Function<ViewContext, Object> content) {
            d.content = content;
            return this;
        }

        @Override
        public View savedText(String key, String defaultValue) {
            if (key != null && key.matches("[A-Za-z][A-Za-z0-9_]*")) d.savedTexts.put(key, defaultValue != null ? defaultValue : "");
            else LOG.log("view " + d.who() + ": saved value key " + key + " is invalid; skipped");
            return this;
        }

        @Override
        public View replaces(String bitwigPanel) {
            if ("mixer".equals(bitwigPanel)) d.replaces = bitwigPanel;
            else LOG.log("view " + d.who() + ": it can't replace " + bitwigPanel + " (only \"mixer\"); ignored");
            return this;
        }

        @Override
        public View scale(boolean scalable) {
            d.scalable = scalable;
            return this;
        }

        @Override
        public View scaleSetting(boolean listed) {
            d.listed = listed;
            return this;
        }
    }

    private static final class BitwigBuilder implements BitwigView {
        final Declared d;

        BitwigBuilder(Declared d) {
            this.d = d;
        }

        @Override
        public BitwigView icon(String resourceId) {
            d.iconResource = resourceId;
            return this;
        }

        @Override
        public BitwigView scale(boolean scalable) {
            d.scalable = scalable;
            return this;
        }

        @Override
        public BitwigView scaleSetting(boolean listed) {
            d.listed = listed;
            return this;
        }
    }

    /** A declared layout policy. */
    record Policy(String modId, LayoutPolicy policy, boolean viewStack) {
    }

    /** One provider's declarations, before they are checked. */
    private static final class Declaration implements Panels {
        final String modId;
        final List<Declared> views = new ArrayList<>();
        final List<LayoutPolicy> policies = new ArrayList<>();
        final List<Boolean> viewStacks = new ArrayList<>(); // per policy; null: not said (deprecated policy(p))

        Declaration(String modId) {
            this.modId = modId;
        }

        @Override
        public View view(String viewId, String label) {
            Declared d = new Declared(modId, viewId, label, false);
            views.add(d);
            return new ViewBuilder(d);
        }

        @Override
        public BitwigView bitwigView(String viewId, String label) {
            Declared d = new Declared(modId, viewId, label, true);
            views.add(d);
            return new BitwigBuilder(d);
        }

        @Override
        public BitwigView bitwigPanel(String viewId, String label, Class<?> panelClass) {
            Declared d = new Declared(modId, viewId, label, true);
            d.panelClass = panelClass;
            if (panelClass != null) views.add(d);
            else LOG.log("view " + d.who() + " names no panel class; skipped");
            return new BitwigBuilder(d);
        }

        @Override
        @Deprecated
        public void policy(LayoutPolicy policy) {
            add(policy, null);
        }

        @Override
        public void policy(LayoutPolicy policy, boolean viewStack) {
            add(policy, viewStack);
        }

        private void add(LayoutPolicy policy, Boolean viewStack) {
            if (policy == null) return;
            policies.add(policy);
            viewStacks.add(viewStack);
        }
    }

    private static List<Declared> views = List.of();     // the mods' own views, in their order
    private static List<Declared> bitwigViews = List.of(); // Bitwig views declared by layout mods
    private static List<Declared> matched = List.of();     // Bitwig panels matched by class, by mod ID
    private static List<Declared> all = List.of();         // all three, in that order
    private static List<Policy> policies = List.of();
    private static boolean loaded;

    private Registry() {
    }

    /** The mods' own views in their order: by label (case-insensitive), then mod ID and view ID. */
    static synchronized List<Declared> views() {
        load();
        return views;
    }

    /** The Bitwig views layout mods declared, in declaration order. */
    static synchronized List<Declared> bitwigViews() {
        load();
        return bitwigViews;
    }

    /** Every declaration: Bitwig panels matched by class, the Bitwig views layout mods declare, the mods' own views. */
    static synchronized List<Declared> all() {
        load();
        return all;
    }

    /** The first Bitwig panel declaration matching a panel node's class, or null. */
    static Declared matchedFor(Object node) {
        List<Declared> list;
        synchronized (Registry.class) {
            load();
            list = matched;
        }
        for (Declared d : list) if (d.panelClass.isInstance(node)) return d;
        return null;
    }

    /** Whether any layout policy builds a view stack (so the stack's actions can act). */
    static synchronized boolean viewStacks() {
        load();
        for (Policy p : policies) if (p.viewStack()) return true;
        return false;
    }

    /** Whether the mod has a layout policy (so its Bitwig views can be in a stack or side columns). */
    static synchronized boolean hasPolicy(String modId) {
        load();
        for (Policy p : policies) if (p.modId().equals(modId)) return true;
        return false;
    }

    /** The layout policies, by mod ID. */
    static synchronized List<Policy> policies() {
        load();
        return policies;
    }

    static synchronized Declared find(String modId, String viewId) {
        for (Declared d : all()) if (d.modId.equals(modId) && d.id.equals(viewId)) return d;
        return null;
    }

    /** Asks every registered provider for its declarations. Once. */
    private static void load() {
        if (loaded) return;
        loaded = true;
        List<Declared> own = new ArrayList<>(), bitwig = new ArrayList<>(), byClass = new ArrayList<>();
        List<Policy> allPolicies = new ArrayList<>();
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
                ((PanelProvider) type.getConstructor().newInstance()).declare(d);
            } catch (Throwable t) {
                LOG.log("mod " + reg.modId() + ": views skipped", t);
                continue;
            }
            Set<String> ids = new HashSet<>();
            for (Declared v : d.views) {
                if (v.id == null || !v.id.matches(ID)) LOG.log("view " + v.who() + ": invalid ID; skipped");
                else if (!ids.add(v.id)) LOG.log("view " + v.who() + ": the mod already declared that ID; skipped");
                else if (v.label == null || v.label.isBlank()) LOG.log("view " + v.who() + " has no label; skipped");
                else if (!v.bitwig && v.content == null) LOG.log("view " + v.who() + " has no content; skipped");
                else if (v.panelClass != null && !akQ.class.isAssignableFrom(v.panelClass)) LOG.log("view " + v.who() + ": " + v.panelClass.getName() + " is not one of Bitwig's panels; skipped");
                else if (v.iconResource != null && (v.icon = Icons.name(v.modId, v.iconResource)) == null) LOG.log("view " + v.who() + " has no usable icon; skipped");
                else if (v.iconResource == null && !v.bitwig) LOG.log("view " + v.who() + " has no icon; skipped");
                else (v.panelClass != null ? byClass : v.bitwig ? bitwig : own).add(v); // a Bitwig view outside stacks needs no icon
            }
            boolean stackViews = d.views.stream().anyMatch(v -> v.bitwig && v.panelClass == null && v.icon != null); // for policy(p): views for a stack
            for (int i = 0; i < d.policies.size(); i++) {
                Boolean said = d.viewStacks.get(i);
                allPolicies.add(new Policy(reg.modId(), d.policies.get(i), said != null ? said : stackViews));
            }
        }
        own.sort(Comparator.comparing((Declared v) -> v.label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(v -> v.modId)
                .thenComparing(v -> v.id));
        allPolicies.sort(Comparator.comparing(Policy::modId));
        byClass.sort(Comparator.comparing((Declared v) -> v.modId)); // stable: declaration order within a mod
        views = List.copyOf(own);
        bitwigViews = List.copyOf(bitwig);
        matched = List.copyOf(byClass);
        List<Declared> every = new ArrayList<>(matched);
        every.addAll(bitwigViews);
        every.addAll(views);
        all = List.copyOf(every);
        policies = List.copyOf(allPolicies);
        LOG.log("views: " + views.size() + ", Bitwig views: " + bitwigViews.size() + ", Bitwig panels by class: " + matched.size()
                + ", layout policies: " + policies.size());
    }
}
