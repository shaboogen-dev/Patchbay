package dev.bitwigre.mods.btethemeloader;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import bitwig.obf.YBi;
import bitwig.obf.iok;
import bml.api.Bml;

/**
 * Applies the selected .bte theme: a named colour is registered with the theme's value instead of
 * Bitwig's, "Gradient: false" bypasses Bitwig's colour derivation, and files under the overlay
 * folder's icons/ and images/ replace the matching stock resource streams. Bitwig's own colour
 * values are never written to: they are immutable and shared (Bitwig registers "Black" with its
 * one black constant), so each themed colour is a value of the theme's own, made once. Theme and
 * overlays are read once, when the first hook runs; restart Bitwig after editing them.
 */
final class ThemeRuntime {
    static final Bml.Mod MOD = Bml.mod("bte-theme-loader");

    /** "name: #rgb[a] | #rrggbb[aa]  // optional comment" */
    private static final Pattern BTE_COLOR = Pattern.compile("^\\s*([^/:][^:]*)\\s*:\\s*(#[0-9A-Fa-f]{3,8})(?:\\s*//.*)?$");
    private static final Pattern BTE_GRADIENT = Pattern.compile("^\\s*Gradient\\s*:\\s*(true|false)\\s*(?://.*)?$", Pattern.CASE_INSENSITIVE);

    private static final Theme THEME = loadTheme();
    /** Overlays by their path relative to icons/ or images/. */
    private static final Map<String, Overlay> OVERLAYS = loadOverlays();

    private static boolean loggedColor, loggedGradient, loggedOverlay;

    private ThemeRuntime() {
    }

    /** Whether the theme names any colour. */
    static boolean hasColors() {
        return !THEME.colors.isEmpty();
    }

    /** Whether the theme names any of the keys. */
    static boolean namesAny(String... keys) {
        for (String key : keys) if (THEME.colors.containsKey(key)) return true;
        return false;
    }

    /** The theme's colour for a name, or null when the theme doesn't name it. */
    static iok themedColor(String name) {
        return name == null ? null : THEME.colors.get(name);
    }

    static boolean gradientDisabled() {
        return THEME.gradientDisabled;
    }

    /** The value a named colour is registered with: the theme's for its name, else Bitwig's. */
    static iok registeredColor(String name, iok stock) {
        iok themed = themedColor(name);
        if (themed == null) return stock;
        if (!loggedColor) {
            loggedColor = true;
            MOD.log("Applied first theme colour: " + name);
        }
        return themed;
    }

    /** With "Gradient: false", the colour a derivation started from instead of the derived one. */
    static iok onGradientResult(iok derived, iok original) {
        if (!THEME.gradientDisabled || original == null) return derived;
        if (!loggedGradient) {
            loggedGradient = true;
            MOD.log("Gradient: false active; bypassing Bitwig's colour derivation.");
        }
        return original;
    }

    /** The overlay's bytes when the file's path ends with an overlay's path. */
    static InputStream onResourceStream(InputStream stream, YBi file) {
        if (stream == null || file == null || OVERLAYS.isEmpty()) return stream;
        String path = String.valueOf(file).replace('\\', '/');
        Overlay overlay = findOverlay(path);
        if (overlay == null) return stream;
        try {
            stream.close();
        } catch (Throwable ignored) {
            // the stock stream is discarded either way
        }
        if (!loggedOverlay) {
            loggedOverlay = true;
            MOD.log("Resource overlay active: " + path + " -> " + overlay.source);
        }
        return new ByteArrayInputStream(overlay.bytes);
    }

    /**
     * The overlay for the longest end of the path that one names: the whole path, then the path
     * after each "/" in turn. The most specific overlay wins; one lookup per path segment.
     */
    private static Overlay findOverlay(String path) {
        Overlay overlay = OVERLAYS.get(path);
        for (int slash = path.indexOf('/'); overlay == null && slash >= 0; slash = path.indexOf('/', slash + 1))
            overlay = OVERLAYS.get(path.substring(slash + 1));
        return overlay;
    }

    private static Theme loadTheme() {
        Path file = ConfigPaths.selectedTheme();
        if (file == null) {
            MOD.log("No theme selected; using stock Bitwig colours and gradients.");
            return Theme.STOCK;
        }
        HashMap<String, iok> found = new HashMap<>();
        int invalid = 0;
        boolean gradientSet = false;
        boolean noGradient = false;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher gradient = BTE_GRADIENT.matcher(line);
                if (gradient.matches()) {
                    gradientSet = true;
                    noGradient = "false".equalsIgnoreCase(gradient.group(1));
                    continue;
                }
                Matcher color = BTE_COLOR.matcher(line);
                if (!color.matches()) continue;
                String name = color.group(1).trim();
                try {
                    found.put(name, new iok(parseArgb(color.group(2).trim())));
                } catch (RuntimeException e) {
                    invalid++;
                    MOD.log("Skipping invalid colour: " + name + " = " + color.group(2).trim());
                }
            }
        } catch (Throwable t) {
            MOD.log("Could not load " + file + "; using stock Bitwig colours and gradients.", t);
            return Theme.STOCK;
        }
        MOD.log("Loaded " + found.size() + " colours from " + file
                + (invalid == 0 ? "" : " (" + invalid + " invalid entries skipped)")
                + "; Gradient=" + !noGradient + (gradientSet ? "" : " (default)")
                + ". Restart Bitwig after editing the file.");
        return new Theme(Collections.unmodifiableMap(found), noGradient);
    }

    private static Map<String, Overlay> loadOverlays() {
        Path root = ConfigPaths.overlayRoot();
        if (root == null) return Map.of();
        HashMap<String, Overlay> found = new HashMap<>();
        loadOverlayFolder(root.resolve("icons"), found);
        loadOverlayFolder(root.resolve("images"), found);
        if (!found.isEmpty()) MOD.log("Loaded " + found.size() + " resource overlays from " + root + ".");
        return Collections.unmodifiableMap(found);
    }

    /** Every file under dir becomes an overlay for the path relative to dir. */
    private static void loadOverlayFolder(Path dir, Map<String, Overlay> out) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                try {
                    String relative = dir.relativize(file).toString().replace('\\', '/');
                    if (relative.isEmpty()) return;
                    Overlay overlay = new Overlay(Files.readAllBytes(file), file.toAbsolutePath().normalize());
                    Overlay earlier = out.putIfAbsent(relative, overlay);
                    if (earlier != null) MOD.log("Skipping resource overlay " + file + ": " + earlier.source + " has the same path");
                } catch (Throwable t) {
                    MOD.log("Skipping unreadable resource overlay " + file + ": " + t);
                }
            });
        } catch (Throwable t) {
            MOD.log("Could not scan resource overlay folder " + dir + ": " + t);
        }
    }

    /** #rgb, #rgba, #rrggbb or #rrggbbaa to packed ARGB, the layout of Bitwig's colour value. */
    private static int parseArgb(String value) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        int r, g, b, a;
        if (hex.length() == 3 || hex.length() == 4) {
            r = nibble(hex.charAt(0)) * 17;
            g = nibble(hex.charAt(1)) * 17;
            b = nibble(hex.charAt(2)) * 17;
            a = hex.length() == 4 ? nibble(hex.charAt(3)) * 17 : 255;
        } else if (hex.length() == 6 || hex.length() == 8) {
            long bits = Long.parseLong(hex, 16);
            if (hex.length() == 6) {
                r = (int) (bits >>> 16 & 255L);
                g = (int) (bits >>> 8 & 255L);
                b = (int) (bits & 255L);
                a = 255;
            } else {
                r = (int) (bits >>> 24 & 255L);
                g = (int) (bits >>> 16 & 255L);
                b = (int) (bits >>> 8 & 255L);
                a = (int) (bits & 255L);
            }
        } else {
            throw new IllegalArgumentException("Unsupported colour length: " + value);
        }
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int nibble(char c) {
        int value = Character.digit(c, 16);
        if (value < 0) throw new IllegalArgumentException("Invalid hex digit: " + c);
        return value;
    }

    private record Theme(Map<String, iok> colors, boolean gradientDisabled) {
        static final Theme STOCK = new Theme(Map.of(), false);
    }

    private record Overlay(byte[] bytes, Path source) {
    }
}
