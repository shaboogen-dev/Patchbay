package bml.pluginpanels.internal;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import bml.api.Bml;

/**
 * Where the framework's files are. Panels are components: one JSON file each, in Patchbay's
 * {@code components/} folder, recognised by the manifest's {@code panels} group ({@code *.json}) and
 * chosen in Patchbay; the framework uses the chosen ones, in the order they were chosen. Sheets
 * (Generate Parameter Sheet, Generate Theme Sheet) go to the framework's folder in Patchbay's
 * per-user settings folder. Both come from the loader's bindings ({@code BML_MOD_CONFIG}), read once:
 * a change of choice takes effect when Bitwig starts again.
 */
final class Components {
    static final String MOD_ID = "plugin-panels-framework";
    private static final String GROUP = "component." + MOD_ID + ".group.panels.";

    private static volatile Properties bindings;

    private Components() {
    }

    private static Properties bindings() {
        Properties p = bindings;
        if (p != null) return p;
        p = new Properties();
        try {
            String env = System.getenv("BML_MOD_CONFIG");
            if (env == null || env.isBlank()) {
                Panels.report("bindings", "BML_MOD_CONFIG is unset: no panels", null);
            } else {
                try (var reader = new InputStreamReader(Files.newInputStream(Path.of(env)), StandardCharsets.UTF_8)) {
                    p.load(reader);
                }
            }
        } catch (Throwable t) {
            Panels.report("bindings", "the loader's bindings can't be read: " + t.getMessage(), null);
        }
        bindings = p;
        return p;
    }

    /** The chosen panel files, in the order they were chosen (the first that matches a plug-in is used). */
    static List<Path> panels() {
        Properties p = bindings();
        List<Path> out = new ArrayList<>();
        int count;
        try {
            count = Integer.parseInt(p.getProperty(GROUP + "selected_count", "0").trim());
        } catch (NumberFormatException e) {
            count = 0;
        }
        for (int i = 0; i < count; i++) {
            String path = p.getProperty(GROUP + "selected." + i + ".resource.panel.path", "");
            if (path.isBlank()) continue;
            Path file = Path.of(path).toAbsolutePath().normalize();
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) out.add(file);
            else Panels.report("panel:" + file, "panel " + file.getFileName() + " is not there any more", null);
        }
        return out;
    }

    /**
     * Patchbay's {@code components/} folder, where Save Panel from Clipboard writes, as the loader
     * says (loader 0.36.0-dev.11 or later; an older loader refuses this framework's manifest anyway,
     * for its group's {@code minimum}). May not exist yet.
     */
    static Path folder() {
        return Bml.componentsFolder();
    }

    /** The framework's folder in Patchbay's per-user settings folder, for the sheets it writes. */
    static Path dataFolder() {
        String path = bindings().getProperty("mod." + MOD_ID + ".data.path", "");
        if (path.isBlank()) throw new IllegalStateException("the framework's settings folder is not known");
        return Path.of(path);
    }
}
