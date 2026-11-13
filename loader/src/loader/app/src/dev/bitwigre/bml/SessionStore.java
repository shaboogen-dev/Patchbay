package dev.bitwigre.bml;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/**
 * Durable, intentionally UI-independent session inventory and manual operations.
 * Historical state/session-logs and loader/jvmti/reports are not modified.
 * Bitwig-owned logs are intentionally out of scope.
 */
final class SessionStore {
    private static final long MAX_EXPORT_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int MAX_EXPORT_FILES = 100000;
    private static final Object LOCAL_OPERATIONS_LOCK = new Object();
    private final BmlPaths paths;
    private final Path root;
    record CleanupResult(int sessionsDeleted,int exportsDeleted,List<String> failures) {}
    private static final java.util.regex.Pattern MANAGED_EXPORT = java.util.regex.Pattern.compile("(?:session|trace)-[A-Za-z0-9][A-Za-z0-9_.-]{0,128}\\.zip");
    SessionStore(BmlPaths paths) throws IOException {
        this.paths=paths;
        this.root=paths.sessionsDir.toAbsolutePath().normalize();
        if(Files.exists(root,LinkOption.NOFOLLOW_LINKS)&&unsafe(root))throw new IOException("Unsafe BML sessions root");
        Files.createDirectories(root);
    }
    private static boolean unsafe(Path p) throws IOException {
        if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS))return false;
        BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        return a.isSymbolicLink()||a.isOther();
    }
    private void ensureRoot() throws IOException {
        if(unsafe(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe or missing Sessions root");
    }
    List<SessionArchive> list() throws IOException {
        ensureRoot();
        List<SessionArchive> result=new ArrayList<>();
        try(DirectoryStream<Path> folders=Files.newDirectoryStream(root)){
            for(Path p:folders){
                if(unsafe(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))continue;
                try{
                    SessionArchive a=SessionArchive.restore(paths,p.getFileName().toString());
                    if(a!=null)result.add(a);
                }catch(Exception bad){ /* Non-session/legacy/corrupt metadata left alone. */ }
            }
        }
        result.sort(Comparator.comparing((SessionArchive a)->a.created).reversed().thenComparing(a->a.id));
        return result;
    }
    /**
     * Best-effort durable startup reconciliation. A corrupt folder stays intact and
     * is reported; nothing is erased, zipped or imported automatically.
     */
    List<String> reconcileAll() throws IOException {
        ensureRoot();
        List<String> warnings=new ArrayList<>();
        try(DirectoryStream<Path> ds=Files.newDirectoryStream(root)){
            for(Path p:ds){
                String id=p.getFileName().toString();
                if(!id.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}-[0-9]{2}-[0-9]{2}_[a-f0-9]{12}"))continue;
                try{
                    if(unsafe(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("unsafe session directory");
                    SessionArchive a=SessionArchive.restore(paths,id);
                    if(a==null)throw new IOException("metadata missing");
                    a.reconcile();
                }catch(Exception ex){warnings.add(id+": "+ex.getMessage());}
            }
        }
        return warnings;
    }
    private SessionArchive requireClosed(String id) throws IOException {
        ensureRoot();
        Path folder=SessionArchive.locate(paths,id);
        if(unsafe(folder)||!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw new IOException("Session directory unavailable or unsafe");
        SessionArchive a=SessionArchive.restore(paths,id);
        if(a==null)throw new IOException("Session metadata unavailable");
        a.reconcile();
        if(!a.terminal()||a.processConfirmedAlive())throw new IOException("Session is active or finalizing and cannot be exported/deleted");
        return a;
    }
    private void inspectTree(Path dir) throws IOException {
        if(unsafe(dir))throw new IOException("Unsafe session entry: "+dir);
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path d,BasicFileAttributes a) throws IOException {
                if(unsafe(d)||!a.isDirectory())throw new IOException("Unsafe session directory entry: "+d);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f,BasicFileAttributes a) throws IOException {
                if(unsafe(f)||!a.isRegularFile())throw new IOException("Unsafe session file entry: "+f);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    private static boolean managedExport(Path entry) {
        return MANAGED_EXPORT.matcher(entry.getFileName().toString()).matches();
    }
    /** The only managed destination for session ZIP exports. */
    Path exportDirectory() throws IOException {
        ensureRoot();
        Path dir=paths.sessionExportsDir.toAbsolutePath().normalize();
        if(!dir.getParent().equals(root)||unsafe(dir))throw new IOException("Unsafe Sessions exports directory");
        Files.createDirectories(dir);
        if(unsafe(dir)||!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe Sessions exports directory");
        return dir;
    }
    int countExports() throws IOException {
        ensureRoot();
        return countExportsUnder(paths.sessionExportsDir);
    }
    private int countExportsUnder(Path dir) throws IOException {
        dir=dir.toAbsolutePath().normalize();
        if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return 0;
        if(unsafe(dir)||!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe exports directory: "+dir);
        int count=0;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(dir)) {
            for(Path entry:entries)if(managedExport(entry))count++;
        }
        return count;
    }
    /**
     * Explicit Clear All backend. Serialize against manual ZIP and individual
     * deletion. Preserve active sessions and any unknown/legacy files.
     */
    CleanupResult clearAllClosedSessionsAndExports() throws IOException {
        synchronized(LOCAL_OPERATIONS_LOCK){
            try(FileChannel channel=operationChannel();FileLock ignored=channel.lock()){
                int sessions=0,exports=0;
                List<String> failures=new ArrayList<>();
                for(SessionArchive item:list()){
                    if(!item.terminal()||item.processConfirmedAlive())continue;
                    try{deleteSessionLocked(item.id);sessions++;}
                    catch(IOException error){failures.add("Session "+item.id+": "+error.getMessage());}
                }
                exports+=deleteManagedExportsUnder(paths.sessionExportsDir,failures);
                return new CleanupResult(sessions,exports,List.copyOf(failures));
            }
        }
    }
    private int deleteManagedExportsUnder(Path dir,List<String> failures) throws IOException {
        dir=dir.toAbsolutePath().normalize();
        if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return 0;
        if(unsafe(dir)||!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)){
            failures.add("Exports: unsafe directory; no ZIPs removed under "+dir);return 0;
        }
        int deleted=0;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(dir)){
            for(Path file:entries){
                if(!managedExport(file))continue;
                try{
                    if(unsafe(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("unsafe entry");
                    Files.delete(file);deleted++;
                }catch(IOException error){failures.add("Export "+file.getFileName()+": "+error.getMessage());}
            }
        }
        try{Files.delete(dir);}catch(DirectoryNotEmptyException notEmpty){}
        catch(IOException issue){failures.add("Exports: could not remove directory "+dir+": "+issue.getMessage());}
        return deleted;
    }
    /** Explicit manual export; not called by close or recovery. */
    Path zipSession(String id) throws IOException {
        synchronized(LOCAL_OPERATIONS_LOCK){
            try(FileChannel channel=operationChannel(); FileLock ignored=channel.lock()){
                return zipSessionLocked(id);
            }
        }
    }
    private FileChannel operationChannel() throws IOException {
        ensureRoot();
        Path lock=root.resolve(".operations.lock");
        if(unsafe(lock))throw new IOException("Unsafe Sessions operation lock");
        return FileChannel.open(lock,StandardOpenOption.WRITE,StandardOpenOption.CREATE);
    }
    private Path zipSessionLocked(String id) throws IOException {
        SessionArchive session=requireClosed(id);
        Path dir=session.folder();
        inspectTree(dir);
        Path exports=exportDirectory();
        Path destination=exports.resolve("session-"+id+".zip");
        if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("Export already exists; original ZIP was preserved: "+destination);
        Path temp=Files.createTempFile(exports,".session-"+id+"-",".tmp");
        try{
            List<Path> files;
            try(var walker=Files.walk(dir)){
                files=walker.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted().toList();
            }
            if(files.size()>MAX_EXPORT_FILES)throw new IOException("Too many files for session export");
            long total=0;
            try(ZipOutputStream z=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
                for(Path file:files){
                    if(unsafe(file))throw new IOException("Unsafe session entry before export: "+file);
                    long size=Files.size(file);
                    if(size<0||size>MAX_EXPORT_BYTES-total)throw new IOException("Session export exceeds safety size limit");
                    total+=size;
                    String entry=id+"/"+dir.relativize(file).toString().replace('\\','/');
                    ZipEntry zipEntry=new ZipEntry(entry);
                    zipEntry.setTime(Files.getLastModifiedTime(file,LinkOption.NOFOLLOW_LINKS).toMillis());
                    z.putNextEntry(zipEntry);
                    try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){input.transferTo(z);}
                    z.closeEntry();
                }
            }
            Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE);
            return destination;
        }catch(IOException|RuntimeException failure){Files.deleteIfExists(temp);throw failure;}
    }
    /** Backend primitive. UI must obtain explicit confirmation before calling. */
    void deleteSession(String id) throws IOException {
        synchronized(LOCAL_OPERATIONS_LOCK){
            try(FileChannel channel=operationChannel();FileLock ignored=channel.lock()){
                deleteSessionLocked(id);
            }
        }
    }
    private void deleteSessionLocked(String id) throws IOException {
        SessionArchive archive=requireClosed(id);
        Path dir=archive.folder();inspectTree(dir);
        Files.walkFileTree(dir,new SimpleFileVisitor<>(){
            @Override public FileVisitResult visitFile(Path p,BasicFileAttributes a)throws IOException {
                if(unsafe(p))throw new IOException("Unsafe session entry during delete");
                Files.delete(p);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path p,IOException ex)throws IOException{
                if(ex!=null)throw ex;
                if(unsafe(p))throw new IOException("Unsafe session directory during delete");
                Files.delete(p);return FileVisitResult.CONTINUE;
            }
        });
    }
}
