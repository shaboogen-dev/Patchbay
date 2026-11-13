package dev.bitwigre.bml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class EnabledModStore {
    private final BmlPaths paths;
    EnabledModStore(BmlPaths paths) { this.paths = paths; }

    Map<String,Boolean> load(List<ModInfo> mods) throws IOException { return load(mods, Set.of()); }

    /** A mod with no saved tick starts unticked when it is in {@code unavailable} (not available on the
     *  installed Bitwig), so finding one never blocks Load. */
    Map<String,Boolean> load(List<ModInfo> mods, Set<String> unavailable) throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(paths.enabledModsFile)) {
            try (Reader r = Files.newBufferedReader(paths.enabledModsFile, StandardCharsets.UTF_8)) { p.load(r); }
        }
        LinkedHashMap<String,Boolean> out = new LinkedHashMap<>();
        for (ModInfo mod : mods) {
            String raw = p.getProperty("mod." + mod.id());
            out.put(mod.id(), raw == null ? !unavailable.contains(mod.id()) && defaultEnabled(mod) : Boolean.parseBoolean(raw));
        }
        return out;
    }

    private static boolean defaultEnabled(ModInfo mod) {
        // Preserve historical v2 default-on behaviour. Newly installed v3
        // parents need an explicit component choice and start unselected.
        try {
            Map<String,Object> root=SimpleJson.object(SimpleJson.parse(Files.readString(mod.manifest(),StandardCharsets.UTF_8)));
            return !(root.get("manifest_version") instanceof Number n && n.intValue()==3);
        }catch(Exception ex){return false;}
    }

    /** Keeps the ticks of mods not in {@code state}: another copy of BML may have them, and they aren't newly found there. */
    void save(Map<String,Boolean> state) throws IOException {
        Files.createDirectories(paths.enabledModsFile.getParent());
        Properties p = new Properties();
        if (Files.isRegularFile(paths.enabledModsFile)) {
            try (Reader r = Files.newBufferedReader(paths.enabledModsFile, StandardCharsets.UTF_8)) { p.load(r); }
        }
        state.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> p.setProperty("mod." + e.getKey(), Boolean.toString(Boolean.TRUE.equals(e.getValue()))));
        try (Writer w = Files.newBufferedWriter(paths.enabledModsFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            p.store(w, "BML enabled mod state. New discovered mods default to enabled.");
        }
    }
}
