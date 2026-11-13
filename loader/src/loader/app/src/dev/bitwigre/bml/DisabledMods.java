package dev.bitwigre.bml;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/**
 * Mods the user has set aside in Patchbay without uninstalling them. A disabled mod isn't listed
 * (except when the list's Show is "Disabled"), is never prepared or loaded, and none of its issues
 * show or block Load. The IDs are kept in {@code bml.properties} as {@code mods.disabled}; an ID
 * that isn't installed is kept and ignored.
 */
final class DisabledMods {
    private DisabledMods(){}

    /** The IDs in a {@code mods.disabled} value: comma-separated, blanks and repeats dropped. */
    static Set<String> parse(String raw){
        Set<String> out=new TreeSet<>();
        if(raw==null)return out;
        for(String part:raw.split(",")){String id=part.strip();if(!id.isEmpty())out.add(id);}
        return out;
    }

    /** The value to store for {@code ids}: sorted, comma-separated. */
    static String format(Collection<String> ids){
        return String.join(",",parse(String.join(",",ids)));
    }

    /**
     * What keeps a mod from being disabled: prerequisite ID -> the names of the ticked mods that
     * need it, directly or through another prerequisite. {@code ticked} is false for disabled mods.
     */
    static Map<String,List<String>> neededBy(List<PackageInventory.Entry> entries,Predicate<PackageInventory.Entry> ticked){
        Map<String,PackageInventory.Entry> byId=new HashMap<>();
        for(PackageInventory.Entry e:entries)if(e.id!=null)byId.putIfAbsent(e.id,e);
        Map<String,List<String>> out=new TreeMap<>();
        for(PackageInventory.Entry root:entries){
            if(root.id==null||!ticked.test(root))continue;
            Set<String> seen=new HashSet<>();
            ArrayDeque<PackageInventory.Entry> todo=new ArrayDeque<>(List.of(root));
            while(!todo.isEmpty())for(String id:todo.pop().requires.keySet()){
                if(id.equals(root.id)||!seen.add(id))continue;
                List<String> names=out.computeIfAbsent(id,k->new ArrayList<>());
                if(!names.contains(root.name))names.add(root.name);
                PackageInventory.Entry p=byId.get(id);
                if(p!=null)todo.add(p);
            }
        }
        return out;
    }

    /** {@code issues} without those of disabled mods and of components made for a disabled mod. */
    static List<PackageInventory.Issue> visible(List<PackageInventory.Issue> issues,List<PackageInventory.Entry> entries,
            Collection<ComponentCatalog.Part> parts,Set<String> disabled){
        if(disabled.isEmpty())return issues;
        Set<Path> hidden=new HashSet<>();
        for(PackageInventory.Entry e:entries)if(e.id!=null&&disabled.contains(e.id))hidden.add(e.directory);
        for(ComponentCatalog.Part p:parts)if(disabled.contains(p.parent))hidden.add(p.directory);
        if(hidden.isEmpty())return issues;
        List<PackageInventory.Issue> out=new ArrayList<>();
        for(PackageInventory.Issue i:issues)if(!hidden.contains(i.packagePath()))out.add(i);
        return out;
    }
}
