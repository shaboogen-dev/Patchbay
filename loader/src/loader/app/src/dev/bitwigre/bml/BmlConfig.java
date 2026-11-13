package dev.bitwigre.bml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

final class BmlConfig {
    static final String BITWIG_EXE = "bitwig.executable";
    static final String HIDE_DEBUG_MODE = "ui.hide_debug_mode";
    static final String DISABLED_MODS = "mods.disabled";
    static final String MODS_SHOW = "ui.mods.show";
    private final BmlPaths paths;
    private final Properties props = new Properties();

    BmlConfig(BmlPaths paths) { this.paths = paths; }

    void load() throws IOException {
        props.clear();
        if (Files.isRegularFile(paths.configFile)) {
            try (Reader r = Files.newBufferedReader(paths.configFile, StandardCharsets.UTF_8)) { props.load(r); }
        }
        if (props.getProperty(BITWIG_EXE, "").isBlank()) {
            String pf = System.getenv("ProgramFiles");
            if (pf != null && !pf.isBlank()) {
                props.setProperty(BITWIG_EXE, Path.of(pf, "Bitwig Studio", "Bitwig Studio.exe").toString());
            } else if (BmlPaths.MAC && Files.isRegularFile(MAC_DEFAULT)) {
                props.setProperty(BITWIG_EXE, MAC_DEFAULT.toString());
            } else if (!BmlPaths.WINDOWS && Files.isRegularFile(LINUX_DEFAULT)) {
                props.setProperty(BITWIG_EXE, LINUX_DEFAULT.toString());
            }
        }
    }

    /** Where Bitwig's Linux packages install its launcher (/usr/bin/bitwig-studio links to it). */
    static final Path LINUX_DEFAULT = Path.of("/opt/bitwig-studio/bitwig-studio");
    /** Bitwig's executable inside its app bundle, installed in /Applications. */
    static final Path MAC_DEFAULT = Path.of("/Applications/Bitwig Studio.app/Contents/MacOS/BitwigStudio");

    /** On macOS the app bundle (Bitwig Studio.app) may be chosen: its executable is inside it. */
    static Path executableOf(Path chosen) {
        if (BmlPaths.MAC && chosen.getFileName() != null && chosen.getFileName().toString().endsWith(".app")
                && Files.isDirectory(chosen)) return chosen.resolve("Contents/MacOS/BitwigStudio");
        return chosen;
    }

    /** The folder holding Bitwig's bitwig.jar and libs.jar: <Bitwig>/bin on Windows and Linux,
     *  Contents/Java beside Contents/MacOS (the executable's folder) in the macOS app bundle. */
    static Path jarFolder(Path executable) {
        Path dir = executable.getParent();
        if (BmlPaths.MAC && dir.getFileName() != null && dir.getFileName().toString().equals("MacOS")) return dir.resolveSibling("Java");
        return dir.resolve("bin");
    }

    Path bitwigExecutable() {
        String s = props.getProperty(BITWIG_EXE, "").trim();
        if (s.isBlank()) return null;
        Path p = executableOf(Path.of(s).toAbsolutePath().normalize());
        // Outside Windows the launcher is often chosen through a link (/usr/bin/bitwig-studio);
        // Bitwig's folders (bin/, lib/jre) are found next to the file the link points to.
        if (!BmlPaths.WINDOWS && Files.isSymbolicLink(p)) {
            try { p = p.toRealPath(); } catch (IOException ignored) {}
        }
        return p;
    }

    void setBitwigExecutable(Path path) {
        if (path == null) props.remove(BITWIG_EXE);
        else props.setProperty(BITWIG_EXE, path.toAbsolutePath().normalize().toString());
    }

    // An absent preference means the standard, non-debug-facing UI. Explicit
    // "false" is the only value that reveals the advanced footer control.
    boolean hideDebugMode() {
        return !"false".equalsIgnoreCase(props.getProperty(HIDE_DEBUG_MODE, "true").trim());
    }

    void setHideDebugMode(boolean hide) {
        props.setProperty(HIDE_DEBUG_MODE, Boolean.toString(hide));
    }

    /** Column widths the user dragged in one of the window's lists ("1:320,3:110"); empty for none. */
    String columnWidths(String list) {
        return props.getProperty("ui.columns." + list, "").trim();
    }

    void setColumnWidths(String list, String value) {
        if (value == null || value.isBlank()) props.remove("ui.columns." + list);
        else props.setProperty("ui.columns." + list, value);
    }

    /** The IDs of the mods disabled in Patchbay ({@link DisabledMods}). */
    java.util.Set<String> disabledMods() {
        return DisabledMods.parse(props.getProperty(DISABLED_MODS));
    }

    void setDisabledMods(java.util.Collection<String> ids) {
        String value = DisabledMods.format(ids);
        if (value.isEmpty()) props.remove(DISABLED_MODS);
        else props.setProperty(DISABLED_MODS, value);
    }

    /** The Mods list's Show choice (All, Ticked, Unticked or Disabled); All unless chosen. */
    String modsShow() {
        return props.getProperty(MODS_SHOW, "All").trim();
    }

    void setModsShow(String show) {
        if (show == null || show.isBlank() || "All".equals(show)) props.remove(MODS_SHOW);
        else props.setProperty(MODS_SHOW, show);
    }

    void save() throws IOException {
        Files.createDirectories(paths.configDir);
        try (Writer w = Files.newBufferedWriter(paths.configFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            props.store(w, "BML configuration");
        }
    }
}
