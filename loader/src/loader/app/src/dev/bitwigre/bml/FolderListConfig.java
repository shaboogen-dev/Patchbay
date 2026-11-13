package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A list of folders kept in a mod's own JSON resource, for a tool to edit. The mod says where in its
 * manifest: {@code "bml_tools": {"<tool id>": {"resource": "<resource ID>", "key": "<top-level key>"}}}.
 * Everything else in the file is left as it is. The resource should be marked "preserve", so the
 * list is in the mod's data folder ({@link ModData}), survives updates of the mod and a
 * change to it needs no new preparation.
 */
final class FolderListConfig {
    enum FolderState {OK,MISSING,UNREADABLE}
    /** How long a check waits for a disk. A sleeping or dead drive counts as missing until it answers. */
    static final long CHECK_TIMEOUT_MILLIS=2000;
    /** Checks still waiting for a disk: a later check of that folder waits on the same one. */
    private static final Map<String,CompletableFuture<FolderState>> RUNNING=new ConcurrentHashMap<>();

    final String modId,modName;
    final Path directory;
    private final Path file;
    /** What the mod's folder holds to start a preserved file from; null for a file that is in the mod's folder. */
    private final Path shipped;
    private final String key;

    private FolderListConfig(String modId,String modName,Path directory,Path file,Path shipped,String key){
        this.modId=modId;this.modName=modName;this.directory=directory;this.file=file;this.shipped=shipped;this.key=key;
    }

    /** The installed mod declaring this tool, or null if none does. A manifest that can't be read doesn't declare it. */
    static FolderListConfig find(Path modsRoot,String toolId) throws IOException {
        if(!Files.isDirectory(modsRoot))return null;
        List<Path> dirs;
        try(Stream<Path> s=Files.list(modsRoot)){dirs=s.filter(Files::isDirectory).sorted().toList();}
        for(Path dir:dirs){
            Path manifest=dir.resolve("mod.json");
            if(!Files.isRegularFile(manifest))continue;
            try{
                Map<String,Object> root=SimpleJson.object(SimpleJson.parse(Files.readString(manifest,StandardCharsets.UTF_8)));
                if(!(root.get("bml_tools") instanceof Map<?,?> tools)||!(tools.get(toolId) instanceof Map<?,?> tool))continue;
                Map<String,Object> resource=SimpleJson.object(SimpleJson.object(root.get("resources")).get(String.valueOf(tool.get("resource"))));
                Path file=PackageInventory.resolveInside(dir,String.valueOf(resource.get("path")),false,false);
                Map<String,Object> mod=SimpleJson.object(root.get("mod"));
                String id=String.valueOf(mod.get("id"));
                Path shipped=null;
                if("preserve".equals(resource.get("persistence"))){
                    shipped=file;
                    file=ModData.resource(new BmlPaths(modsRoot.toAbsolutePath().normalize().getParent()),id,String.valueOf(resource.get("path")));
                }else if("created".equals(resource.get("persistence"))){
                    // the mod's own file in its data folder, which this tool writes first; nothing shipped
                    ModData.checkCreatedName(id,String.valueOf(tool.get("resource")),String.valueOf(resource.get("path")));
                    file=ModData.resource(new BmlPaths(modsRoot.toAbsolutePath().normalize().getParent()),id,String.valueOf(resource.get("path")));
                }
                return new FolderListConfig(id,String.valueOf(mod.get("name")),dir.toAbsolutePath().normalize(),file,shipped,String.valueOf(tool.get("key")));
            }catch(RuntimeException|IOException unreadable){}
        }
        return null;
    }

    /** The folders listed. A file that isn't there yet lists none; one that can't be understood throws, and is left alone. */
    List<String> read() throws IOException {
        Object list=readRoot().get(key);
        if(list==null)return List.of();
        if(!(list instanceof List<?> items))throw new IOException(key+" in "+file.getFileName()+" isn't a list");
        List<String> folders=new ArrayList<>();
        for(Object item:items){
            if(!(item instanceof String folder))throw new IOException(key+" in "+file.getFileName()+" lists something that isn't a folder");
            folders.add(folder);
        }
        return folders;
    }

    /** Replaces the list. Written to a new file first, so a failure leaves the old one whole. */
    void write(List<String> folders) throws IOException {
        Map<String,Object> root=readRoot();
        root.put(key,new ArrayList<Object>(folders));
        StringBuilder json=new StringBuilder();
        appendJson(json,root,"");
        json.append('\n');
        Files.createDirectories(file.getParent());
        Path temp=file.resolveSibling(file.getFileName()+".tmp");
        try{
            Files.writeString(temp,json,StandardCharsets.UTF_8);
            Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{
            Files.deleteIfExists(temp);
        }
    }

    /** The state of each folder, within the time limit overall whatever the disks do. Call off the event thread. */
    static Map<String,FolderState> check(Collection<String> folders){return check(folders,CHECK_TIMEOUT_MILLIS,FolderListConfig::probe);}
    static Map<String,FolderState> check(Collection<String> folders,long timeoutMillis,Function<String,FolderState> probe){
        Map<String,CompletableFuture<FolderState>> checks=new LinkedHashMap<>();
        for(String folder:folders)checks.put(folder,RUNNING.computeIfAbsent(folder,f->start(f,probe)));
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Map<String,FolderState> states=new LinkedHashMap<>();
        for(var check:checks.entrySet()){
            FolderState state;
            try{state=check.getValue().get(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
            catch(InterruptedException ex){Thread.currentThread().interrupt();state=FolderState.MISSING;}
            catch(TimeoutException|ExecutionException ex){state=FolderState.MISSING;}
            states.put(check.getKey(),state);
        }
        return states;
    }
    /** A thread of its own, as a call to a dead network path can block for a long time. */
    private static CompletableFuture<FolderState> start(String folder,Function<String,FolderState> probe){
        CompletableFuture<FolderState> result=new CompletableFuture<>();
        Thread worker=new Thread(()->{
            try{result.complete(probe.apply(folder));}
            catch(Throwable t){result.complete(FolderState.MISSING);}
            finally{RUNNING.remove(folder,result);}
        },"BML folder check");
        worker.setDaemon(true);
        worker.start();
        return result;
    }
    private static FolderState probe(String folder){
        Path path;
        try{path=Path.of(folder);}catch(InvalidPathException ex){return FolderState.MISSING;}
        if(!Files.isDirectory(path))return FolderState.MISSING;
        try(DirectoryStream<Path> listing=Files.newDirectoryStream(path)){return FolderState.OK;}
        catch(AccessDeniedException ex){return FolderState.UNREADABLE;}
        catch(IOException|RuntimeException ex){return Files.isDirectory(path)?FolderState.UNREADABLE:FolderState.MISSING;}
    }

    private Map<String,Object> readRoot() throws IOException {
        if(shipped!=null)ModData.seedFile(shipped,file);  // the tool may run before the mod was ever prepared
        if(!Files.exists(file))return new LinkedHashMap<>();
        try{return SimpleJson.object(SimpleJson.parse(Files.readString(file,StandardCharsets.UTF_8)));}
        catch(RuntimeException ex){throw new IOException(file.getFileName()+" can't be read: "+ex.getMessage(),ex);}
    }

    private static void appendJson(StringBuilder out,Object value,String indent){
        String inner=indent+"  ";
        if(value instanceof Map<?,?> map){
            if(map.isEmpty()){out.append("{}");return;}
            out.append("{\n");
            int left=map.size();
            for(var e:map.entrySet()){
                out.append(inner);appendString(out,String.valueOf(e.getKey()));out.append(": ");
                appendJson(out,e.getValue(),inner);
                out.append(--left>0?",\n":"\n");
            }
            out.append(indent).append('}');
        }else if(value instanceof List<?> list){
            if(list.isEmpty()){out.append("[]");return;}
            out.append("[\n");
            int left=list.size();
            for(Object item:list){
                out.append(inner);appendJson(out,item,inner);
                out.append(--left>0?",\n":"\n");
            }
            out.append(indent).append(']');
        }else if(value instanceof String s)appendString(out,s);
        // SimpleJson reads every number as a double: a whole one goes back without its ".0".
        else if(value instanceof Double d&&d==Math.rint(d)&&Math.abs(d)<1e15)out.append(d.longValue());
        else out.append(value);  // other numbers, true, false, null
    }
    private static void appendString(StringBuilder out,String s){
        out.append('"');
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            switch(c){
                case '"'->out.append("\\\"");
                case '\\'->out.append("\\\\");
                case '\n'->out.append("\\n");
                case '\r'->out.append("\\r");
                case '\t'->out.append("\\t");
                default->{if(c<' ')out.append(String.format("\\u%04x",(int)c));else out.append(c);}
            }
        }
        out.append('"');
    }
}
