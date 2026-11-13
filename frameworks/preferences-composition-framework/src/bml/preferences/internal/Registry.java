package bml.preferences.internal;

import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import bml.api.Bml;
import bml.preferences.Checkbox;
import bml.preferences.Choice;
import bml.preferences.NumberField;
import bml.preferences.Page;
import bml.preferences.PreferenceProvider;
import bml.preferences.Preferences;
import bml.preferences.Section;
import bml.preferences.internal.Controls.CheckboxControl;
import bml.preferences.internal.Controls.ChoiceControl;
import bml.preferences.internal.Controls.Control;
import bml.preferences.internal.Controls.NumberControl;

/** Every mod's declared controls, sections and pages. */
final class Registry {
    static final String FRAMEWORK = "preferences-composition-framework";
    static final Bml.Mod LOG = Bml.mod(FRAMEWORK);
    static final String ID = "[A-Za-z][A-Za-z0-9_.-]*";

    /** A section or page a mod declared, with its heading or sidebar label. */
    record Declared(String id, String label, String modId) {
    }

    /** What one mod declares, collected before any of it is accepted. */
    static final class Declaration implements Preferences {
        final String modId;
        final List<Control> controls = new ArrayList<>();
        final Map<String, String> sections = new LinkedHashMap<>(); // section ID -> title
        final Map<String, String> pages = new LinkedHashMap<>(); // page ID -> label

        Declaration(String modId) {
            this.modId = modId;
        }

        private <T extends Control> T add(T control) {
            controls.add(control);
            return control;
        }

        @Override
        public Checkbox checkbox(String controlId, String label) {
            return add(new CheckboxControl(modId, controlId, label, nextOrder()));
        }

        @Override
        public Choice choice(String controlId, String label, String... options) {
            return add(new ChoiceControl(modId, controlId, label, nextOrder(), options, false));
        }

        @Override
        public Choice dropdown(String controlId, String label, String... options) {
            return add(new ChoiceControl(modId, controlId, label, nextOrder(), options, true));
        }

        @Override
        public NumberField number(String controlId, String label, double min, double max) {
            return add(new NumberControl(modId, controlId, label, nextOrder(), min, max, false));
        }

        @Override
        public NumberField slider(String controlId, String label, double min, double max) {
            return add(new NumberControl(modId, controlId, label, nextOrder(), min, max, true));
        }

        @Override
        public Section section(Page page, String sectionId, String title) {
            if (page == null) throw new IllegalArgumentException("section " + sectionId + " has no page");
            if (sectionId == null || !sectionId.matches(ID)) throw new IllegalArgumentException("invalid section ID: " + sectionId);
            if (title == null || title.isBlank()) throw new IllegalArgumentException("section " + sectionId + " has no title");
            Section s = Section.of(page.id() + "/" + sectionId);
            if (BitwigSections.isBitwigs(s)) throw new IllegalArgumentException(s + " is one of Bitwig's sections; use Section.of(\"" + s + "\")");
            sections.put(s.id(), title);
            return s;
        }

        @Override
        public Page page(String pageId, String label) {
            if (pageId == null || !pageId.matches(ID)) throw new IllegalArgumentException("invalid page ID: " + pageId);
            if (label == null || label.isBlank()) throw new IllegalArgumentException("page " + pageId + " has no label");
            Page p = Page.of(pageId);
            if (BitwigSections.isBitwigs(p)) throw new IllegalArgumentException(pageId + " is one of Bitwig's pages");
            pages.put(pageId, label);
            return p;
        }
    }

    private static final List<Control> CONTROLS = new ArrayList<>();
    private static final Map<String, Declared> SECTIONS = new LinkedHashMap<>(); // new sections by ID
    private static final Map<String, Declared> PAGES = new LinkedHashMap<>(); // mod pages by ID
    private static int order;
    private static boolean loaded;

    private Registry() {
    }

    private static synchronized int nextOrder() {
        return order++;
    }

    /** Asks every registered provider for its controls. Once, before Settings is first built. */
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
                ((PreferenceProvider) type.getConstructor().newInstance()).declare(d);
            } catch (Throwable t) {
                LOG.log("mod " + reg.modId() + ": Settings controls skipped", t);
                continue;
            }
            accept(d);
        }
        int shown = 0;
        for (Control c : CONTROLS) {
            String why = unplaced(c.section);
            if (why != null) LOG.log("control " + c.who() + " not shown: " + why);
            else shown++;
        }
        LOG.log("loaded " + shown + " Settings control(s) from " + CONTROLS.stream().map(c -> c.modId).distinct().count() + " mod(s)");
    }

    /** Accepts what a mod declared: its pages and sections, then its complete controls. */
    static synchronized void accept(Declaration d) {
        for (var p : d.pages.entrySet()) {
            Declared earlier = PAGES.putIfAbsent(p.getKey(), new Declared(p.getKey(), p.getValue(), d.modId));
            if (earlier != null && !earlier.label().equals(p.getValue()))
                LOG.log("mod " + d.modId + ": page " + p.getKey() + " is already declared by " + earlier.modId() + " as \"" + earlier.label() + "\"; that one is used");
        }
        for (var s : d.sections.entrySet()) {
            Declared earlier = SECTIONS.putIfAbsent(s.getKey(), new Declared(s.getKey(), s.getValue(), d.modId));
            if (earlier != null && !earlier.label().equals(s.getValue()))
                LOG.log("mod " + d.modId + ": section " + s.getKey() + " is already declared by " + earlier.modId() + " as \"" + earlier.label() + "\"; that one is used");
        }
        Set<String> ids = new HashSet<>();
        for (Control c : CONTROLS) if (c.modId.equals(d.modId)) ids.add(c.id);
        for (Control c : d.controls) {
            String why = c.incomplete();
            if (why != null) LOG.log("control " + c.who() + " skipped: " + why);
            else if (!ids.add(c.id)) LOG.log("control " + c.who() + " skipped: the mod already has a control with that ID");
            else {
                CONTROLS.add(c);
                if (loaded && unplaced(c.section) != null) LOG.log("control " + c.who() + " not shown: " + unplaced(c.section));
            }
        }
    }

    /** Why controls in this section can't be shown, or null. */
    private static String unplaced(Section s) {
        if (BitwigSections.isBitwigs(s)) return null;
        if (!SECTIONS.containsKey(s.id())) return "unknown section " + s;
        Page page = s.page();
        if (!BitwigSections.isBitwigs(page) && !PAGES.containsKey(page.id())) return "unknown page " + page;
        return null;
    }

    /** The order of a section's mod controls: by mod name, then mod ID, then as declared. */
    private static final Comparator<Control> ORDER = Comparator.comparing((Control c) -> modName(c.modId), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(c -> c.modId).thenComparingInt(c -> c.order);

    /** Mod names by mod ID, looked up once: sorting asks for them at every comparison. */
    private static final Map<String, String> NAMES = new HashMap<>();

    private static String modName(String modId) {
        return NAMES.computeIfAbsent(modId, id -> {
            try {
                return Bml.mod(id).name();
            } catch (Throwable notEnabled) {
                return id;
            }
        });
    }

    /** The mods' controls in a section, in display order. */
    static synchronized List<Control> controls(Section section) {
        List<Control> out = new ArrayList<>();
        if (unplaced(section) != null) return out;
        for (Control c : CONTROLS) if (c.section.equals(section)) out.add(c);
        out.sort(ORDER);
        return out;
    }

    private static boolean hasControls(Section section) {
        if (unplaced(section) != null) return false;
        for (Control c : CONTROLS) if (c.section.equals(section)) return true;
        return false;
    }

    /** The new sections on a page that have controls, alphabetical by title. */
    static synchronized List<Declared> sections(Page page) {
        List<Declared> out = new ArrayList<>();
        for (Declared s : SECTIONS.values())
            if (Section.of(s.id()).page().equals(page) && hasControls(Section.of(s.id()))) out.add(s);
        out.sort(Comparator.comparing(Declared::label, String.CASE_INSENSITIVE_ORDER).thenComparing(Declared::id));
        return out;
    }

    /** The mods' pages, alphabetical by label. */
    static synchronized List<Declared> pages() {
        List<Declared> out = new ArrayList<>(PAGES.values());
        out.sort(Comparator.comparing(Declared::label, String.CASE_INSENSITIVE_ORDER).thenComparing(Declared::id));
        return out;
    }
}
