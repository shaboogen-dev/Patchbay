package bml.browser.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import bml.browser.Feature;
import bml.browser.ContentFactory;
import bml.browser.Features;
import bml.browser.Panel;
import bml.browser.ResultsFactory;
import bml.browser.Scope;
import bml.browser.View;
import bml.browser.ViewContext;

/**
 * The views mods declare and the one the user has chosen. Only the chosen view's claims apply,
 * and only while it is working: a view whose {@code onActive} throws loses its claims (logged).
 * A chosen view whose mod isn't enabled isn't declared, so standard behaviour applies; the choice
 * is kept for when the mod returns.
 */
final class Views {
    private static final String CHOICE = "view";
    private static final String ID = "[A-Za-z][A-Za-z0-9_.-]*";

    /** A declared view. */
    static final class Declared implements View, ViewContext {
        final String modId, id, name;
        final Map<String, Boolean> claims = new HashMap<>();
        Consumer<ViewContext> onActive, onInactive;
        /** The view's own content for the side browser panel, or null. */
        ContentFactory content;
        /** Where the view puts the side browser's results column, or null. */
        ResultsFactory results;
        boolean failed;

        Declared(String modId, String id, String name) {
            this.modId = modId;
            this.id = id;
            this.name = name;
        }

        String key() {
            return modId + "/" + id;
        }

        @Override
        public View claims(Feature feature, boolean value) {
            return claims(feature, Scope.BOTH, value);
        }

        @Override
        public View claims(Feature feature, Scope scope, boolean value) {
            if (!feature.perBrowser()) {
                claims.put(claimKey(feature, Scope.SIDE), value);
            } else {
                if (scope.includes(Scope.SIDE)) claims.put(claimKey(feature, Scope.SIDE), value);
                if (scope.includes(Scope.POPUP)) claims.put(claimKey(feature, Scope.POPUP), value);
            }
            return this;
        }

        @Override
        public View onActive(Consumer<ViewContext> action) {
            onActive = action;
            return this;
        }

        @Override
        public View onInactive(Consumer<ViewContext> action) {
            onInactive = action;
            return this;
        }

        @Override
        public View content(ContentFactory factory) {
            content = factory;
            return this;
        }

        @Override
        public View results(ResultsFactory factory) {
            results = factory;
            return this;
        }

        @Override
        public boolean active() {
            return Views.active == this && !failed;
        }

        @Override
        public List<Panel> panels() {
            return Contents.panels(this);
        }

        @Override
        public String viewId() {
            return id;
        }

        @Override
        public Features features() {
            return State.INSTANCE;
        }
    }

    private static final List<Declared> ALL = new ArrayList<>();
    private static Declared active;

    private Views() {
    }

    /** A provider declares a view. */
    static Declared declare(String modId, String viewId, String name) {
        Declared d = new Declared(modId, viewId, name);
        if (viewId == null || !viewId.matches(ID)) {
            State.LOG.log("view " + modId + "/" + viewId + ": invalid ID; skipped");
        } else if (name == null || name.isBlank()) {
            State.LOG.log("view " + d.key() + " has no name; skipped");
        } else if (ALL.stream().anyMatch(v -> v.key().equals(d.key()))) {
            State.LOG.log("view " + d.key() + ": the mod already declared that ID; skipped");
        } else {
            ALL.add(d);
        }
        return d;
    }

    /** Every provider has declared: sort the views and activate the user's choice. */
    static void loaded() {
        ALL.sort(Comparator.comparing((Declared v) -> v.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(v -> v.modId).thenComparing(v -> v.id));
        String choice = State.readString(CHOICE);
        for (Declared v : ALL) {
            if (v.key().equals(choice)) activate(v);
        }
        State.LOG.log("views: " + ALL.size() + ", active: " + (active == null ? "Standard" : active.key())
                + (choice.isEmpty() || active != null ? "" : " (chosen " + choice + " is not available)"));
    }

    /** The options of the "Browser view" control: Standard, then every view by name. */
    static String[] options() {
        String[] options = new String[ALL.size() + 1];
        options[0] = "Standard";
        for (int i = 0; i < ALL.size(); i++) options[i + 1] = ALL.get(i).name;
        return options;
    }

    /** The chosen option: 0 for Standard. */
    static int chosen() {
        return active == null ? 0 : ALL.indexOf(active) + 1;
    }

    /** The user chooses an option. */
    static void choose(int option) {
        Declared next = option <= 0 || option > ALL.size() ? null : ALL.get(option - 1);
        if (next == active) return;
        boolean slotBefore = activeResults() != null;
        boolean buttonBefore = State.INSTANCE.get(Feature.ALL_SOURCES_BUTTON, Scope.SIDE);
        boolean contentBefore = activeContent() != null;
        State.writeString(CHOICE, next == null ? "" : next.key());
        if (active != null) {
            Declared old = active;
            active = null;
            if (old.onInactive != null) {
                try {
                    old.onInactive.accept(old);
                } catch (Throwable t) {
                    State.LOG.log("view " + old.key() + ": onInactive failed", t);
                }
            }
        }
        if (next != null) activate(next);
        State.LOG.log("view chosen: " + (active == null ? "Standard" : active.key()));
        State.INSTANCE.changed();
        // a view's results slot and content, and the All Sources button, are built with the browser: a change of them rebuilds it
        if (slotBefore || activeResults() != null
                || State.INSTANCE.get(Feature.ALL_SOURCES_BUTTON, Scope.SIDE) != buttonBefore
                || contentBefore || activeContent() != null) Rebuild.soon("Browser view");
    }

    @SuppressWarnings("deprecation")
    private static void activate(Declared view) {
        active = view;
        view.failed = false;
        if (view.results == null && Boolean.TRUE.equals(view.claims.get(claimKey(Feature.SIDE_PANE, Scope.SIDE)))) {
            // A view built for 0.2 and earlier, when the framework built the side pane: without it the view
            // would hide the source rows and leave nothing in their place.
            view.failed = true;
            State.LOG.log("view " + view.key() + ": claims the side pane, which the framework no longer builds (update the mod);"
                    + " its claims lapse and standard behaviour applies");
            return;
        }
        if (view.onActive == null) return;
        try {
            view.onActive.accept(view);
        } catch (Throwable t) {
            view.failed = true;
            State.LOG.log("view " + view.key() + ": onActive failed; its claims lapse and standard behaviour applies", t);
        }
    }

    /** The active view's claim on a feature in one browser, or null. */
    static Boolean claim(Feature feature, Scope browser) {
        Declared view = active;
        if (view == null || view.failed) return null;
        return view.claims.get(claimKey(feature, browser));
    }

    /** The active view's name, or null (Standard, or the view's activation failed). */
    static String activeName() {
        Declared view = active;
        return view == null || view.failed ? null : view.name;
    }

    /** The active view can't run (its tree, say): its claims lapse until it is chosen again. */
    static void fail(String reason) {
        Declared view = active;
        if (view == null || view.failed) return;
        view.failed = true;
        State.LOG.log("view " + view.key() + ": " + reason + "; its claims lapse and standard behaviour applies");
        State.INSTANCE.changed();
        if (view.content != null || view.results != null) Rebuild.soon("view " + view.key() + " failed"); // its content or slot is in the built browser
    }

    /** The active, working view with a results slot, or null. */
    static Declared activeResults() {
        Declared view = active;
        return view == null || view.failed || view.results == null ? null : view;
    }

    /** The active, working view with content, or null. */
    static Declared activeContent() {
        Declared view = active;
        return view == null || view.failed || view.content == null ? null : view;
    }

    /** The name of the view claiming a feature in one browser, or null. */
    static String claimant(Feature feature, Scope browser) {
        return claim(feature, browser) == null ? null : active.name;
    }

    private static String claimKey(Feature feature, Scope browser) {
        return feature.id() + "/" + browser;
    }
}
