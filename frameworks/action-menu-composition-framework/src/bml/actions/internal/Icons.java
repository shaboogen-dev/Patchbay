package bml.actions.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import bitwig.obf.YBi; // a file in one of Bitwig's resource folders
import bml.api.Bml;

/**
 * Icons from mods' manifest resources. A {@code bitwig} resource under Bitwig's {@code icons}
 * folder is used as Bitwig's own icon, by its path there. A mod's own SVG file is given a path of
 * its own, which Bitwig's icon loader is answered for from the mod's file.
 */
final class Icons {
    /** Bitwig's icon paths are relative to its {@code resources/icons} folder. */
    private static final String BITWIG_ICONS = "icons/";
    /** The icon paths mods' own SVG files are served under (they must end in .svg to load as SVG). */
    private static final String MOD_ICONS = "bml-mod-icons/";

    private static final Map<String, Path> FILES = new ConcurrentHashMap<>();
    /** The icon path Bitwig's loader is opening on this thread. */
    private static final ThreadLocal<String> LOADING = new ThreadLocal<>();

    private Icons() {
    }

    /** The icon path for a resource a mod declares, or {@code null} (logged) if it can't be an icon. */
    static String path(String modId, String resourceId) {
        if (resourceId == null) return null;
        try {
            Bml.Mod mod = Bml.mod(modId);
            String bitwig = mod.bitwigResource(resourceId); // throws if not declared
            if (bitwig != null) {
                if (bitwig.startsWith(BITWIG_ICONS) && bitwig.endsWith(".svg")) return bitwig.substring(BITWIG_ICONS.length());
                Registry.LOG.log("mod " + modId + ": resource " + resourceId + " (" + bitwig + ") is not one of Bitwig's SVG icons; no icon");
                return null;
            }
            Path file = mod.resource(resourceId);
            if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".svg") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                Registry.LOG.log("mod " + modId + ": resource " + resourceId + " is not an SVG file; no icon");
                return null;
            }
            String path = MOD_ICONS + modId + "/" + resourceId + ".svg";
            FILES.put(path, file);
            return path;
        } catch (Throwable t) {
            Registry.LOG.log("mod " + modId + ": icon resource " + resourceId + " can't be used", t);
            return null;
        }
    }

    static void loading(String path) {
        LOADING.set(path);
    }

    /** Whether the icon being loaded exists: a mod's file, or else Bitwig's own answer. */
    static boolean exists(YBi file) {
        Path own = own();
        return own != null ? Files.isRegularFile(own) : file.TtY(); // Bitwig's: whether the file exists
    }

    /** The icon's contents: a mod's file, or else Bitwig's own file. */
    static InputStream open(YBi file) throws IOException {
        Path own = own();
        return own != null ? Files.newInputStream(own) : file.cfM(); // Bitwig's: open the file
    }

    private static Path own() {
        String path = LOADING.get();
        return path == null || !path.startsWith(MOD_ICONS) ? null : FILES.get(path);
    }
}
