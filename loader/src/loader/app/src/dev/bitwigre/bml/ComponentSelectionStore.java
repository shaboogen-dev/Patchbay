package dev.bitwigre.bml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Persist requested component identities separately from installed packages. */
final class ComponentSelectionStore {
    final Path file;
    ComponentSelectionStore(BmlPaths paths){file=paths.selectedComponentsFile;}
    ComponentSelectionStore(Path file){this.file=file;}
    private static String enc(String id){return id.replace(".","%2E");}
    static String key(String parent,String group){return "component."+enc(parent)+".group."+enc(group)+".ids";}
    Map<String,List<String>> load() throws IOException {
        Properties p=new Properties();
        if(Files.isRegularFile(file))try(Reader r=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(r);}
        Map<String,List<String>> result=new LinkedHashMap<>();
        for(String key:p.stringPropertyNames()){
            if(!key.startsWith("component.")||!key.endsWith(".ids"))continue;
            String value=p.getProperty(key).trim();
            List<String> ids=value.isEmpty()?List.of():Arrays.asList(value.split(",",-1));
            for(String id:ids)ComponentCatalog.ident(id,"persisted component ID");
            result.put(key,List.copyOf(ids));
        }
        return result;
    }
    void save(Map<String,List<String>> selections) throws IOException {
        Properties props=new Properties();
        for(var selection:selections.entrySet()){
            for(String id:selection.getValue())ComponentCatalog.ident(id,"persisted component ID");
            props.setProperty(selection.getKey(),String.join(",",selection.getValue()));
        }
        Files.createDirectories(file.getParent());
        try(Writer out=Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)){
            props.store(out,"BML v3 requested component selections; never auto-substitute unavailable IDs.");
        }
    }
    /**
     * One-time default for an unconfigured single-choice group.  Choose the
     * first usable item in the catalogue's (visible) order and persist its ID.
     * A nonempty saved request is NEVER replaced, even if its component has
     * since been removed or become invalid. Multiple-choice groups still
     * require an explicit choice.
     *
     * Returns true only if an actual default was assigned; caller persists
     * and re-inspects the inventory before using the selection.
     */
    static boolean seedUnconfiguredSingles(PackageInventory.Snapshot snapshot,Map<String,List<String>> selections){
        boolean changed=false;
        for(PackageInventory.Entry parent:snapshot.entries()){
            if(parent.id==null||parent.manifestVersion!=3||
               parent.issues.stream().anyMatch(i->"DUPLICATE_MOD_ID".equals(i.code())))continue;
            for(ComponentCatalog.Group group:parent.groups.values()){
                if(!"single".equals(group.selection()))continue;
                String selectionKey=key(parent.id,group.id());
                if(!selections.getOrDefault(selectionKey,List.of()).isEmpty())continue;
                var first=snapshot.components().children(parent.id,group.id()).stream()
                    .filter(ComponentCatalog.Part::usable).findFirst();
                if(first.isPresent()){
                    selections.put(selectionKey,List.of(first.get().id));changed=true;
                }
            }
        }
        return changed;
    }
    static List<String> chosen(Map<String,List<String>> selection,String parent,String group){return selection.getOrDefault(key(parent,group),List.of());}
}
