package bml.api;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.prefs.Preferences;

/**
 * The values a mod kept in Java Preferences before BML kept settings per install, read once, when
 * the mod's settings file doesn't exist yet. Only reads: other installs may still use these nodes,
 * and a node that isn't there isn't created. This is the only place in BML that touches Java
 * Preferences; a mod not listed here never does.
 */
final class LegacyValues {
    /** A node, and the only keys of it that are the mod's (empty: all of them). */
    private record Source(String node, List<String> keys) {
    }

    private LegacyValues() {
    }

    private static Source source(String modId) {
        switch (modId) {
            case "browser-framework":
            case "inspector-composition-framework":
            case "panel-management-framework":
            case "panel-sides":
            case "exclusive-arm":
            case "shared-tool-mode":
                return new Source("/bml/" + modId, List.of());
            // This node isn't the mod's own: only its one key is taken from it.
            case "plugin-undo":
                return new Source("/bitwig-superpowers", List.of("vst_clap_plugin_undo"));
            default:
                return null;
        }
    }

    /** Never throws: without the values, the mod starts from its defaults. */
    static Map<String, String> read(Bml.Mod mod) {
        Map<String, String> found = new TreeMap<>();
        Source source = source(mod.id());
        if (source == null) return found;
        try {
            Preferences root = Preferences.userRoot();
            if (!root.nodeExists(source.node())) return found; // node() would create it
            Preferences node = root.node(source.node());
            for (String key : source.keys().isEmpty() ? List.of(node.keys()) : source.keys()) {
                String value = node.get(key, null);
                if (value != null) found.put(key, value);
            }
            if (!found.isEmpty()) mod.log("took " + found.size() + " existing setting(s) from Java Preferences " + source.node());
        } catch (Throwable t) {
            mod.log("could not read the existing settings in Java Preferences " + source.node() + ": " + t);
        }
        return found;
    }
}
