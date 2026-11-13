package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class ModRegistry {
    private final BmlPaths paths;
    ModRegistry(BmlPaths paths) { this.paths = paths; }

    List<ModInfo> discover() throws IOException {
        Files.createDirectories(paths.mods);
        ArrayList<ModInfo> mods = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(paths.mods)) {
            for (Path dir : ds) {
                if (!Files.isDirectory(dir)) continue;
                Path manifest = dir.resolve("mod.json");
                if (!Files.isRegularFile(manifest)) continue;
                try {
                    Map<String,Object> root = SimpleJson.object(SimpleJson.parse(Files.readString(manifest, StandardCharsets.UTF_8)));
                    Object mv = root.get("manifest_version");
                    if (!(mv instanceof Number n) || (n.intValue() != 2 && n.intValue()!=3)) {
                        throw new IllegalArgumentException("manifest_version must be 2 or 3");
                    }
                    Map<String,Object> mod = SimpleJson.object(root.get("mod"));
                    String id = string(mod, "id", true);
                    String name = string(mod, "name", true);
                    String version = string(mod, "version", true);
                    String author = string(mod, "author", true);
                    String description = string(mod, "description", true);
                    if (!ids.add(id)) throw new IOException("Duplicate mod.id '" + id + "' in " + manifest);
                    mods.add(new ModInfo(id, name, version, author, description, dir.toAbsolutePath().normalize(), manifest.toAbsolutePath().normalize()));
                } catch (RuntimeException ex) {
                    throw new IOException("Malformed mod manifest: " + manifest + " — " + ex.getMessage(), ex);
                }
            }
        }
        mods.sort(Comparator.comparing(ModInfo::id));
        return List.copyOf(mods);
    }

    private static String string(Map<String,Object> m, String key, boolean required) {
        Object v = m.get(key);
        if (v == null) {
            if (required) throw new IllegalArgumentException("Missing mod." + key);
            return "";
        }
        if (!(v instanceof String s)) throw new IllegalArgumentException("mod." + key + " must be a string");
        if (required && s.isBlank()) throw new IllegalArgumentException("mod." + key + " is empty");
        return s;
    }
}
