package bml.panels;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import bitwig.obf.YBi; // a file in one of Bitwig's resource folders
import bitwig.obf.dnf; // a window's display: loads and caches its icons
import bitwig.obf.dpN; // Bitwig's UI application
import bml.api.Bml;
import com.bitwig.graphics.BrJ; // a loaded image (an icon)

/**
 * Views' icons from their mods' manifest resources, with the same rules as the Action and Menu
 * framework's icons. A {@code bitwig} resource under Bitwig's {@code icons/} folder is used by its
 * name there, after checking the file exists. A mod's own SVG gets a name of its own,
 * {@code bml-panel-icons/<mod id>/<resource id>.svg}; when Bitwig's window loads that name
 * (dnf.cfM(name, scale), the "panel-icons" hook) it is read from the mod's file and rendered by
 * Bitwig's own SVG renderer at the window's scale, so Bitwig caches it, tints it with the theme
 * colour of the widget's state and derives its highlight and shadow as for its own icons.
 */
final class Icons {
    /** Bitwig's icon names are relative to its {@code resources/icons} folder. */
    private static final String BITWIG_ICONS = "icons/";
    /** The names mods' own SVG files are loaded under (ending in .svg, as Bitwig loads SVG icons by their ending). */
    private static final String MOD_ICONS = "bml-panel-icons/";

    private static final Map<String, Path> FILES = new ConcurrentHashMap<>();

    private Icons() {
    }

    /** The icon name for a resource a mod declares, or null (logged) if it can't be a view's icon. */
    static String name(String modId, String resourceId) {
        if (resourceId == null) {
            Registry.LOG.log("mod " + modId + ": a view has no icon");
            return null;
        }
        try {
            Bml.Mod mod = Bml.mod(modId);
            String bitwig = mod.bitwigResource(resourceId); // throws if not declared
            if (bitwig != null) {
                if (!bitwig.startsWith(BITWIG_ICONS) || !bitwig.endsWith(".svg")) {
                    Registry.LOG.log("mod " + modId + ": resource " + resourceId + " (" + bitwig + ") is not one of Bitwig's SVG icons");
                    return null;
                }
                String name = bitwig.substring(BITWIG_ICONS.length());
                if (!bitwigIconExists(name)) {
                    Registry.LOG.log("mod " + modId + ": resource " + resourceId + ": Bitwig has no icon " + bitwig);
                    return null;
                }
                return name;
            }
            Path file = mod.resource(resourceId);
            if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                Registry.LOG.log("mod " + modId + ": resource " + resourceId + " is not an SVG file");
                return null;
            }
            String name = MOD_ICONS + modId + "/" + resourceId + ".svg";
            FILES.put(name, file);
            return name;
        } catch (Throwable t) {
            Registry.LOG.log("mod " + modId + ": icon resource " + resourceId + " can't be used", t);
            return null;
        }
    }

    /** Whether Bitwig's icons folder has the file (as Bitwig's loader checks it: dnf.FzB → YBi.TtY). */
    private static boolean bitwigIconExists(String name) {
        dpN app = dpN.TVZ();
        if (app == null) return true; // no UI yet: Bitwig's loader reports a missing icon itself
        return new YBi(app.rxS(), name).TtY(); // rxS(): Bitwig's icons folder
    }

    /** A view's own SVG for a name given out here, rendered by Bitwig's SVG renderer; null for any other name (Bitwig's own). */
    static BrJ load(dnf window, String name, double scale) {
        if (name == null || !name.startsWith(MOD_ICONS)) return null;
        Path file = FILES.get(name);
        if (file == null) return null;
        try (InputStream in = Files.newInputStream(file)) {
            return window.hUR().cfM(in, null, scale); // hUR(): the window's image loader; as Bitwig reads its own SVG icons
        } catch (Throwable failure) {
            Registry.LOG.log("could not load the icon " + file, failure);
            return null;
        }
    }
}
