package dev.bitwigre.bml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/** Install/replace a versioned map ZIP without touching BML's executable code. */
final class MapPackImporter {
    static String importZip(Path archive, Path bmlRoot) throws Exception {
        if (!Files.isRegularFile(archive)) throw new IOException("Not a map ZIP: " + archive);
        Path maps=bmlRoot.resolve("maps");
        Path state=bmlRoot.resolve("state");
        Files.createDirectories(maps);
        Files.createDirectories(state);
        Path staging=Files.createTempDirectory(state,"map-import-");
        try {
            String id=null;
            long total=0;
            Set<String> paths=new HashSet<>();
            try(ZipInputStream zin=new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while((entry=zin.getNextEntry())!=null) {
                    // Directory entries carry no payload; validate only extracted files.
                    if(entry.isDirectory()){zin.closeEntry();continue;}
                    String path=entry.getName();
                    if(path.indexOf('\\')>=0 || path.startsWith("/") || path.contains(":"))
                        throw new IOException("Unsafe map ZIP path: "+path);
                    String[] pieces=path.split("/");
                    if(pieces.length<2 || pieces[0].isBlank() || !pieces[0].matches("[A-Za-z][A-Za-z0-9_.-]*"))
                        throw new IOException("Expected one map-id directory in map ZIP: "+path);
                    if(id==null)id=pieces[0];
                    if(!id.equals(pieces[0]) || Arrays.stream(pieces).anyMatch(p->p.equals("..")||p.equals(".")))
                        throw new IOException("Multiple roots or traversal in map ZIP: "+path);
                    if(!paths.add(path))throw new IOException("Duplicate ZIP entry: "+path);
                    Path output=staging.resolve(path).normalize();
                    if(!output.startsWith(staging))throw new IOException("Unsafe map ZIP entry: "+path);
                    Files.createDirectories(output.getParent());
                    try(OutputStream out=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW)){
                        byte[] buf=new byte[65536];int n;
                        while((n=zin.read(buf))!=-1){
                            total+=n;
                            if(total>100_000_000)throw new IOException("Map ZIP exceeds 100 MB uncompressed");
                            out.write(buf,0,n);
                        }
                    }
                    zin.closeEntry();
                }
            }
            if(id==null)throw new IOException("Empty map archive");
            Path extracted=staging.resolve(id);
            Map<String,Object> meta=SimpleJson.object(SimpleJson.parse(
                Files.readString(extracted.resolve("map.json"),StandardCharsets.UTF_8)));
            String sha=String.valueOf(meta.getOrDefault("target_jar_sha256",""));
            // Reuse the exact parser/validation that prepare will run.
            Path validateRoot=staging.resolve("validate");
            Path validateMaps=validateRoot.resolve("maps");
            Files.createDirectories(validateMaps);
            Files.move(extracted,validateMaps.resolve(id));
            MapPackCatalog.Selected validated=MapPackCatalog.select(validateRoot,sha);
            if(!validated.id().equals(id))throw new IOException("Map package identity changed during import");
            Path current=maps.resolve(id);
            try(Stream<Path> neighbors=Files.list(maps)){
                for(Path other:neighbors.toList()){
                    if(!Files.isDirectory(other,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(other))
                        throw new IOException("Invalid installed map: "+other);
                    if(!other.equals(current)){
                        if(other.getFileName().toString().equalsIgnoreCase(id))
                            throw new IOException("Case-colliding map ID: "+other);
                        Map<String,Object> info=SimpleJson.object(SimpleJson.parse(
                            Files.readString(other.resolve("map.json"),StandardCharsets.UTF_8)));
                        if(sha.equalsIgnoreCase(String.valueOf(info.getOrDefault("target_jar_sha256",""))))
                            throw new IOException("Another map already targets this exact Bitwig build: "+other);
                    }
                }
            }
            Path backup=state.resolve("map-backups").resolve(id+"-previous");
            boolean replacing=Files.exists(current,LinkOption.NOFOLLOW_LINKS);
            Path olderBackup=staging.resolve("older-backup");
            if(replacing){
                Map<String,Object> old=SimpleJson.object(SimpleJson.parse(
                    Files.readString(current.resolve("map.json"),StandardCharsets.UTF_8)));
                if(Objects.equals(old.get("version"),meta.get("version"))
                    && Objects.equals(old.get("file_sha256"),meta.get("file_sha256")))
                    return "Map already installed: "+id+" "+validated.version();
                Files.createDirectories(backup.getParent());
                // Preserve the immediately preceding map for rollback, and bound
                // disk usage to one backup. The older backup is moved aside until
                // the new installation succeeds; then it is discarded.
                if(Files.exists(backup,LinkOption.NOFOLLOW_LINKS))
                    Files.move(backup,olderBackup);
                try{Files.move(current,backup);}
                catch(Exception ex){
                    if(Files.exists(olderBackup))Files.move(olderBackup,backup);
                    throw ex;
                }
            }
            try {
                Files.move(validateMaps.resolve(id),current);
            }catch(Exception ex){
                if(replacing){
                    Files.move(backup,current);
                    if(Files.exists(olderBackup))Files.move(olderBackup,backup);
                }
                throw ex;
            }
            return (replacing ? "Refreshed " : "Installed ")+id+" "+validated.version()+
                (replacing ? " (prior version backed up)" : "");
        }finally{
            deleteTree(staging);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if(!Files.exists(dir))return;
        try(Stream<Path> paths=Files.walk(dir)){
            for(Path file:paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(file);
        }
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: MapPackImporter <map-zip> <bml-root>");
        System.out.println(importZip(Path.of(args[0]),Path.of(args[1])));
    }
}
