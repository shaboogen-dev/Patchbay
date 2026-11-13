package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The per-user folder of {@link BmlPaths}: setting it up, once from what this install already holds, and
 * deleting it. A test install, which keeps its settings in its own folders, has nothing to do here.
 */
final class SettingsFolder {
    /** In the install's state folder: this install's own settings have been looked at, and never are again. */
    private static final String IMPORTED="settings-imported";

    private SettingsFolder() {}

    /** Before anything reads or writes the folder: the window and the preparer each call this first. */
    static void ready(BmlPaths paths) throws IOException {
        if(!paths.perUser)return;
        Path root=paths.configDir;
        Path done=paths.stateDir.resolve(IMPORTED);
        boolean absent=!Files.exists(root);
        if(absent)create(root,paths,!Files.exists(done));
        if(!Files.exists(done)){
            Files.createDirectories(paths.stateDir);
            Files.writeString(done,"This install's settings folder is "+root+"\n");
        }
    }

    /** Whole or not at all: the folder appears only once what it starts from is in it. */
    private static void create(Path root,BmlPaths paths,boolean bringOwn) throws IOException {
        createPrivate(root.getParent());
        Path building=root.resolveSibling(root.getFileName()+".new-"+ProcessHandle.current().pid());
        try{
            createPrivate(building);
            if(bringOwn){
                BmlPaths own=new BmlPaths(paths.root,null);
                copyTree(own.configDir.resolve("mods"),building.resolve("mods"));
                copyFile(own.configFile,building.resolve(paths.configFile.getFileName().toString()));
                copyFile(own.enabledModsFile,building.resolve(paths.enabledModsFile.getFileName().toString()));
                copyFile(own.selectedComponentsFile,building.resolve(paths.selectedComponentsFile.getFileName().toString()));
            }
            try{Files.move(building,root,StandardCopyOption.ATOMIC_MOVE);}
            catch(FileAlreadyExistsException another){/* another BML got there first: its folder stands */}
            catch(IOException refused){if(!Files.isDirectory(root))throw refused;}
        }finally{
            deleteTree(building);
        }
    }

    /** On Linux and macOS for this user alone (0700, as the XDG spec asks); elsewhere the folder's inherited access. */
    private static void createPrivate(Path dir) throws IOException {
        if(FileSystems.getDefault().supportedFileAttributeViews().contains("posix")){
            FileAttribute<?> privately=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
            Files.createDirectories(dir,privately);
        }else Files.createDirectories(dir);
    }

    private static void copyFile(Path from,Path to) throws IOException {
        if(Files.isRegularFile(from,LinkOption.NOFOLLOW_LINKS))Files.copy(from,to);
    }

    private static void copyTree(Path from,Path to) throws IOException {
        if(!Files.isDirectory(from,LinkOption.NOFOLLOW_LINKS))return;
        Files.walkFileTree(from,new SimpleFileVisitor<>(){
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes a) throws IOException {
                if(a.isRegularFile())Files.copy(file,to.resolve(from.relativize(file).toString()));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Why the folder can't be deleted now; null if it can. */
    static String deleteRefusal(BmlPaths paths,boolean bitwigRunning) {
        if(!paths.perUser)return "This test install keeps its settings in its own folder: nothing to delete here.";
        if(bitwigRunning)return "Bitwig is running, or BML can't tell whether it is. Close Bitwig, then try again.";
        Path root=paths.configDir;
        if(paths.root.startsWith(root))return "BML itself is inside "+root+": delete what you don't want by hand.";
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return "There are no settings to delete.";
        return null;
    }

    /** Deletes that one folder and nothing else. A link isn't followed: the link itself goes. */
    static void delete(BmlPaths paths,boolean bitwigRunning) throws IOException {
        String refusal=deleteRefusal(paths,bitwigRunning);
        if(refusal!=null)throw new IOException(refusal);
        deleteTree(paths.configDir);
    }

    private static void deleteTree(Path root) throws IOException {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        List<Path> all;
        try(Stream<Path> s=Files.walk(root)){all=s.sorted(Comparator.reverseOrder()).toList();}
        for(Path p:all)Files.deleteIfExists(p);
    }
}
