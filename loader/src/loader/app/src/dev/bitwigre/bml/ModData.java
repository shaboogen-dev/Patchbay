package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * Where a mod's "preserve" resources live: {@code <mod's data folder>/<last part of the declared path>},
 * apart from the mod's folder, which a rebuild or an update replaces. What is in the mod's folder under the
 * declared path is what that folder starts from: anything not in the data folder yet is copied across, and
 * nothing already there is overwritten. So a shipped default arrives on first use, a file a user kept in the
 * mod's folder comes along, and a file the user has beats a newer shipped one. Nothing in the mod's folder is
 * ever changed or deleted.
 *
 * <p>A file of a "preserve" folder is copied once: {@value #SEEDED_FILE} in the data folder lists each one
 * seen, so one the user deletes stays deleted, and a new version's new files still arrive. A "preserve" file
 * resource is required by its mod, so it comes back when it is deleted, which starts it from its default.
 */
final class ModData {
    /** The store of {@code bml.api.Settings}, beside the resources. */
    private static final String SETTINGS_FILE="settings.properties";
    private static final String SEEDED_FILE="seeded-files.txt";

    private ModData() {}

    static Path resource(BmlPaths paths,String modId,String declared) {
        return paths.modData(modId).resolve(name(declared));
    }
    private static String name(String declared) {
        return Path.of(declared).normalize().getFileName().toString();
    }

    /** Refuses a resource that would share its place in the data folder with another, or with the settings. */
    static void checkName(String modId,String resourceId,String declared,Set<String> taken) throws IOException {
        String name=name(declared).toLowerCase(Locale.ROOT);
        if(name.equals(SETTINGS_FILE)||name.equals(SEEDED_FILE))
            throw new IOException(modId+" resource "+resourceId+": a resource in the data folder can't be called "+name+", which BML keeps there");
        if(!taken.add(name))
            throw new IOException(modId+" resource "+resourceId+": another resource of the mod in its data folder ends in the same name, "+name(declared));
    }

    /** A "created" resource names its file or folder in the data folder: a plain name, with nothing shipped to start it from. */
    static void checkCreatedName(String modId,String resourceId,String declared) throws IOException {
        if(declared.isBlank()||declared.contains("/")||declared.contains("\\")||declared.equals(".")||declared.equals("..")
                ||!declared.equals(declared.strip())||!declared.matches("[^:*?\"<>|\\p{Cntrl}]+"))
            throw new IOException(modId+" resource "+resourceId+": a created resource's path is the plain name of its file or folder in the mod's data folder, not "+declared);
    }

    /** Before anything reads a data folder: every installed mod's, ticked or not, as tools edit an unticked mod's too. */
    static void seedAll(BmlPaths paths,Path modsRoot) throws IOException {
        if(!Files.isDirectory(modsRoot))return;
        List<Path> manifests;
        try(Stream<Path> s=Files.list(modsRoot)){
            manifests=s.map(d->d.resolve("mod.json")).filter(Files::isRegularFile).sorted().toList();
        }
        for(Path manifest:manifests){
            try{
                Map<String,Object> root=SimpleJson.object(SimpleJson.parse(Files.readString(manifest,StandardCharsets.UTF_8)));
                Map<String,Object> mod=SimpleJson.object(root.get("mod"));
                String modId=String.valueOf(mod.get("id"));
                if(!modId.matches("[A-Za-z][A-Za-z0-9_.-]*")||!(root.get("resources") instanceof Map<?,?> resources))continue;
                Set<String> taken=new HashSet<>();
                Path record=paths.modData(modId).resolve(SEEDED_FILE);
                Set<String> seen=Files.isRegularFile(record)?new TreeSet<>(Files.readAllLines(record,StandardCharsets.UTF_8)):new TreeSet<>();
                int before=seen.size();
                for(var e:new TreeMap<>(SimpleJson.object(resources)).entrySet()){
                    if(!(e.getValue() instanceof Map<?,?> r)||!"preserve".equals(r.get("persistence"))||!(r.get("path") instanceof String declared))continue;
                    checkName(modId,e.getKey(),declared,taken);
                    Path shipped=PackageInventory.resolveInside(manifest.getParent(),declared,false,false);
                    seed(shipped,resource(paths,modId,declared),seen);
                }
                if(seen.size()!=before)writeRecord(record,seen);
            }catch(IOException|RuntimeException problem){
                // A ticked mod is refused when it is prepared; an unticked one must not stop the others.
                System.err.println("WARNING: "+manifest.getParent().getFileName()+": its preserved files couldn't be set up: "+problem.getMessage());
            }
        }
    }

    /** A "preserve" file resource, if it isn't in the data folder. A missing {@code shipped} copies nothing. */
    static void seedFile(Path shipped,Path data) throws IOException {
        if(Files.isRegularFile(shipped,LinkOption.NOFOLLOW_LINKS))copyIfAbsent(shipped,data);
    }

    /** Copies what isn't in {@code data} yet; of a folder, only files {@code seen} doesn't list, adding each one. */
    private static void seed(Path shipped,Path data,Set<String> seen) throws IOException {
        if(Files.isRegularFile(shipped,LinkOption.NOFOLLOW_LINKS)){
            copyIfAbsent(shipped,data);
            return;
        }
        if(!Files.isDirectory(shipped,LinkOption.NOFOLLOW_LINKS))return;
        String folder=data.getFileName().toString();
        Files.createDirectories(data);
        Files.walkFileTree(shipped,new SimpleFileVisitor<>(){
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a) throws IOException {
                Files.createDirectories(data.resolve(shipped.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes a) throws IOException {
                if(!a.isRegularFile())return FileVisitResult.CONTINUE;
                String relative=shipped.relativize(file).toString();
                if(seen.add(folder+"/"+relative.replace('\\','/')))copyIfAbsent(file,data.resolve(relative));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void writeRecord(Path record,Set<String> seen) throws IOException {
        Path temp=record.resolveSibling(SEEDED_FILE+".tmp");
        try{
            Files.write(temp,seen,StandardCharsets.UTF_8);
            try{Files.move(temp,record,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,record,StandardCopyOption.REPLACE_EXISTING);}
        }finally{
            Files.deleteIfExists(temp);
        }
    }

    private static void copyIfAbsent(Path from,Path to) throws IOException {
        if(Files.exists(to,LinkOption.NOFOLLOW_LINKS))return;
        Files.createDirectories(to.getParent());
        // Whole or not at all: a file that is there is never copied again.
        Path temp=to.resolveSibling(to.getFileName()+".seeding");
        try{
            Files.copy(from,temp,StandardCopyOption.REPLACE_EXISTING);
            try{Files.move(temp,to,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,to);}
        }finally{
            Files.deleteIfExists(temp);
        }
    }
}
