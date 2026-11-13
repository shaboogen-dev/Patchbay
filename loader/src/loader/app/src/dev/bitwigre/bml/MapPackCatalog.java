package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/**
 * Independent compatibility map packages installed below BML_ROOT/maps.
 * Map data is discovered on every prepare; rebuilding BML is never required
 * just to add, replace, or correct a Bitwig version map.
 */
final class MapPackCatalog {
    record Selected(Path directory, String id, String version, String targetBuild) {}
    private static final String SCHEMA_V1 = "bml-map-pack-v1";
    private static final String SCHEMA_V2 = "bml-map-pack-v2";

    static Selected select(Path packageRoot, String actualJarSha) throws IOException {
        Path maps = packageRoot.resolve("maps");
        if (!Files.exists(maps, LinkOption.NOFOLLOW_LINKS)) {
            // Transitional fallback for older BML distributions only.
            Path legacy = packageRoot.resolve("loader/compatibility");
            if (!Files.isRegularFile(legacy.resolve("builds.json")))
                throw new IOException("No external maps/ and no legacy compatibility data");
            return new Selected(legacy, "legacy-bundled", "legacy", "");
        }
        if (!Files.isDirectory(maps, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(maps))
            throw new IOException("Map installation root must be a real directory: " + maps);
        ArrayList<Selected> matches = new ArrayList<>();
        int installed = 0;
        try (Stream<Path> dirs = Files.list(maps)) {
            for (Path dir : dirs.sorted().toList()) {
                if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir))
                    throw new IOException("Invalid map package directory: " + dir);
                Path metadata = dir.resolve("map.json");
                if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(metadata))
                    throw new IOException("Map package missing map.json: " + dir);
                if (Files.size(metadata) > 65536)
                    throw new IOException("Map package manifest exceeds 64 KiB: " + metadata);
                Map<String,Object> m;
                try {
                    m = SimpleJson.object(SimpleJson.parse(Files.readString(metadata,StandardCharsets.UTF_8)));
                } catch (RuntimeException ex) {
                    throw new IOException("Invalid map.json: " + metadata + ": " + ex.getMessage(),ex);
                }
                String schema = String.valueOf(m.getOrDefault("format",""));
                String id = String.valueOf(m.getOrDefault("id",""));
                String version = String.valueOf(m.getOrDefault("version",""));
                String build = String.valueOf(m.getOrDefault("target_build_key",""));
                String sha = String.valueOf(m.getOrDefault("target_jar_sha256",""));
                boolean streaming = SCHEMA_V2.equals(schema);
                if (!(SCHEMA_V1.equals(schema) || streaming) || !id.matches("[A-Za-z][A-Za-z0-9_.-]*")
                        || !version.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")
                        || !build.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")
                        || !sha.matches("[a-fA-F0-9]{64}") || !dir.getFileName().toString().equals(id))
                    throw new IOException("Invalid map package schema/identity: " + metadata);
                for (String name : streaming ? List.of("builds.json") : List.of("builds.json","compatibility-v2.json")) {
                    Path file = dir.resolve(name);
                    if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))
                        throw new IOException("Map package lacks regular " + name + ": " + dir);
                }
                Path remap = dir.resolve("remap");
                if (!Files.isDirectory(remap,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(remap))
                    throw new IOException("Map package lacks remap directory: " + dir);
                Object checks = m.get("file_sha256");
                if (!(checks instanceof Map<?,?> hashes) || hashes.size() < (streaming ? 2 : 3))
                    throw new IOException("Map package must provide payload SHA-256 checksums: " + dir);
                for (var entry : hashes.entrySet()) {
                    if (!(entry.getKey() instanceof String rel) || !(entry.getValue() instanceof String expected)
                        || !rel.matches("(?:builds\\.json|compatibility-v2\\.json|remap/[A-Za-z0-9_.-]+__"
                                        +"[A-Za-z0-9_.-]+\\.tsv\\.gz)")
                        || !expected.matches("[a-fA-F0-9]{64}"))
                        throw new IOException("Invalid map payload checksum declaration: " + entry);
                    Path file=dir.resolve(rel).normalize();
                    if (!file.startsWith(dir) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(file))
                        throw new IOException("Missing or unsafe map payload: "+file);
                    try {
                        MessageDigest d=MessageDigest.getInstance("SHA-256");
                        try (var input=Files.newInputStream(file)) {
                            byte[] bytes=new byte[65536]; int n;
                            while((n=input.read(bytes))!=-1) d.update(bytes,0,n);
                        }
                        if(!HexFormat.of().formatHex(d.digest()).equalsIgnoreCase(expected))
                            throw new IOException("Map payload checksum mismatch: "+file);
                    } catch (java.security.NoSuchAlgorithmException ex) {
                        throw new IOException("SHA-256 unavailable",ex);
                    }
                }
                if (!hashes.containsKey("builds.json") || (!streaming && !hashes.containsKey("compatibility-v2.json")) || (streaming && hashes.containsKey("compatibility-v2.json")))
                    throw new IOException("Map package missing essential payload hashes: "+dir);
                try (Stream<Path> remapFiles=Files.list(remap)) {
                    for(Path file:remapFiles.toList()) {
                        if(!hashes.containsKey("remap/"+file.getFileName()))
                            throw new IOException("Map remap file lacks checksum: "+file);
                    }
                }
                if (streaming && hashes.keySet().stream().noneMatch(k -> k.toString().startsWith("remap/")))
                    throw new IOException("Streaming map declares no remap file: "+dir);
                installed++;
                if (sha.equalsIgnoreCase(actualJarSha))
                    matches.add(new Selected(dir,id,version,build));
            }
        }
        if (installed == 0)
            throw new IOException("No map packages installed in " + maps);
        if (matches.size() != 1)
            throw new IOException(matches.isEmpty()
                ? "No installed map supports bitwig.jar SHA-256 " + actualJarSha
                : "Conflicting installed maps for bitwig.jar SHA-256 " + actualJarSha);
        return matches.get(0);
    }

    /** Optional offline diagnostic; normal BML preparation calls select directly. */
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Usage: MapPackCatalog <bml-root> <bitwig-jar-sha256>");
        Selected map=select(Path.of(args[0]),args[1]);
        System.out.println("MAP_SELECTED id="+map.id()+" version="+map.version()+
            " target="+map.targetBuild()+" directory="+map.directory());
    }
}
