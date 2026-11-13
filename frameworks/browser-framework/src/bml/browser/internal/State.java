package bml.browser.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import bml.api.Bml;
import bml.api.Settings;
import bml.browser.Feature;
import bml.browser.Features;
import bml.browser.Scope;

/**
 * The features: the stored values (kept in the framework's settings, set through the deprecated
 * {@code Features.set}), the mods' holds over them ({@code Browser.hide}: in memory only, a feature
 * is off while any loaded mod holds it), and the active view's claims on top ({@link Views}).
 */
public final class State implements Features {
    static final String FRAMEWORK = "browser-framework";
    static final Bml.Mod LOG = Bml.mod(FRAMEWORK);
    private static final Settings STORE = LOG.settings();

    static final State INSTANCE = new State();

    private final List<Runnable> listeners = new ArrayList<>();
    /** For each feature key ({@link #key}), the mods holding it off. Never stored. */
    private final Map<String, Set<String>> holds = new HashMap<>();
    /** Filtering in effect at the last change, to clear the filters when it turns off. */
    private Boolean filtering;

    private State() {
    }

    /** The features, after every provider has declared (so views and a mod's changes are in place). */
    public static State get() {
        Registry.load();
        if (INSTANCE.filtering == null) INSTANCE.filtering = INSTANCE.get(Feature.FILTERING, Scope.SIDE);
        return INSTANCE;
    }

    /** {@link bml.browser.ViewSearch#focus}: the declared search field of the view shown in a side browser. */
    public static boolean focusViewSearch(Object sideBrowser) {
        return Contents.focusSearch(sideBrowser);
    }

    @Override
    public boolean get(Feature feature, Scope scope) {
        if (feature.perBrowser() && scope == Scope.BOTH) {
            return effective(feature, Scope.SIDE) && effective(feature, Scope.POPUP);
        }
        return effective(feature, feature.perBrowser() ? scope : Scope.SIDE);
    }

    @Override
    public boolean userValue(Feature feature, Scope scope) {
        if (feature.perBrowser() && scope == Scope.BOTH) {
            return read(feature, Scope.SIDE) && read(feature, Scope.POPUP);
        }
        return read(feature, feature.perBrowser() ? scope : Scope.SIDE);
    }

    @Override
    public boolean set(Feature feature, Scope scope, boolean on) {
        String claimant = controlledBy(feature, scope);
        if (claimant != null) {
            LOG.log(feature.id() + " is controlled by the view " + claimant + "; the change was refused");
            return false;
        }
        for (Scope browser : browsers(feature, scope)) write(key(feature, browser), on);
        changed();
        return true;
    }

    @Override
    public String controlledBy(Feature feature, Scope scope) {
        for (Scope browser : browsers(feature, scope)) {
            String claimant = Views.claimant(feature, browser);
            if (claimant != null) return claimant;
        }
        return null;
    }

    @Override
    public void onChange(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    /** The value in one browser: the active view's claim, else off while a mod holds it, else the stored value. */
    private boolean effective(Feature feature, Scope browser) {
        Boolean claim = Views.claim(feature, browser);
        if (claim != null) return claim;
        if (held(key(feature, browser))) return false;
        return read(feature, browser);
    }

    private boolean held(String key) {
        synchronized (holds) {
            Set<String> mods = holds.get(key);
            return mods != null && !mods.isEmpty();
        }
    }

    /** {@code Browser.hide}: a mod holds a feature off, or lets it go. Applies at once when anything changed. */
    void hide(String modId, Feature feature, Scope scope, boolean hidden) {
        if (feature == null || scope == null) return;
        boolean changedAny = false;
        synchronized (holds) {
            for (Scope browser : browsers(feature, scope)) {
                String key = key(feature, browser);
                Set<String> mods = holds.computeIfAbsent(key, k -> new LinkedHashSet<>());
                changedAny |= hidden ? mods.add(modId) : mods.remove(modId);
            }
        }
        if (!changedAny) return;
        LOG.log("mod " + modId + (hidden ? " hides " : " no longer hides ") + feature.id() + " (" + scope + ")");
        changed();
    }

    /** {@code Browser.hides}: true while the mod holds the feature off in every browser the scope names. */
    boolean hides(String modId, Feature feature, Scope scope) {
        if (feature == null || scope == null) return false;
        synchronized (holds) {
            for (Scope browser : browsers(feature, scope)) {
                Set<String> mods = holds.get(key(feature, browser));
                if (mods == null || !mods.contains(modId)) return false;
            }
        }
        return true;
    }

    /** The single browsers a scope names for this feature. */
    private static List<Scope> browsers(Feature feature, Scope scope) {
        if (!feature.perBrowser()) return List.of(Scope.SIDE);
        return scope == Scope.BOTH ? List.of(Scope.SIDE, Scope.POPUP) : List.of(scope);
    }

    private static String key(Feature feature, Scope browser) {
        if (!feature.perBrowser()) return feature.id();
        return feature.id() + "." + (browser == Scope.POPUP ? "popup" : "side");
    }

    private static boolean read(Feature feature, Scope browser) {
        return STORE.getBoolean(key(feature, browser), feature.defaultOn());
    }

    private static void write(String key, boolean on) {
        STORE.putBoolean(key, on);
    }

    static String readString(String key) {
        return STORE.get(key, "");
    }

    static void writeString(String key, String value) {
        STORE.put(key, value);
    }

    /** As {@link #writeString}, on disk before this returns: for a marker that must outlive a crash in the next step. */
    static void writeStringNow(String key, String value) {
        STORE.put(key, value);
        STORE.flush();
    }

    /** A value in effect may have changed (the user's, or the active view's): apply it everywhere. */
    void changed() {
        boolean now = get(Feature.FILTERING, Scope.SIDE);
        if (Boolean.TRUE.equals(filtering) && !now) {
            try {
                SideBrowsers.clearFilters();
            } catch (Throwable t) {
                LOG.log("clearing the filters failed", t);
            }
        }
        filtering = now;
        SideBrowsers.applyAll();
        for (Runnable listener : List.copyOf(listeners)) {
            try {
                listener.run();
            } catch (Throwable t) {
                LOG.log("a feature listener failed", t);
            }
        }
    }
}
