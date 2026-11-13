package dev.bitwigre.bml;

import java.nio.file.*;
import java.util.*;

/** Offline manifest v3 verification: never loads Bitwig. */
public final class ManifestV3CatalogSmoke {
    static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);}
    static String v2(String id){return "{\"manifest_version\":2,\"mod\":{\"id\":\""+id+"\",\"name\":\"V2\",\"version\":\"1\",\"author\":\"A\",\"description\":\"D\"},\"built_against\":{\"bitwig_version\":\"6.1.3\",\"platform\":\"windows\",\"architecture\":\"x64\"},\"targets\":{},\"hooks\":[],\"trace_targets\":[{\"id\":\"x\"}]}";}
    static String parent(){return v2("parent").replace("\"manifest_version\":2","\"manifest_version\":3,\"package_type\":\"mod\",\"component_groups\":{\"themes\":{\"name\":\"Themes\",\"selection\":\"single\",\"required_resources\":{\"theme\":{\"kind\":\"file\"}}},\"extras\":{\"name\":\"Extras\",\"selection\":\"multiple\",\"required_resources\":{\"assets\":{\"kind\":\"directory\"}}}}");}
    static String part(String id,String group,String resources){return "{\"manifest_version\":3,\"package_type\":\"component\",\"component\":{\"id\":\""+id+"\",\"name\":\""+id+"\",\"version\":\"1\",\"author\":\"Tester\",\"description\":\"Test\",\"parent\":\"parent\",\"group\":\""+group+"\"},\"resources\":"+resources+"}";}
    static Path folder(Path root,String name,String manifest) throws Exception {
        Path dir=Files.createDirectories(root.resolve(name));Files.writeString(dir.resolve("mod.json"),manifest);return dir;
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("bml-manifest-v3-test");
        try{
            Path mods=Files.createDirectories(root.resolve("mods")),components=Files.createDirectories(root.resolve("components"));
            folder(mods,"legacy",v2("legacy"));folder(mods,"parent",parent());
            Path dark=folder(components,"dark",part("dark","themes","{\"theme\":{\"kind\":\"file\",\"path\":\"dark.bte\"}}"));Files.writeString(dark.resolve("dark.bte"),"fixture");
            Path light=folder(components,"light",part("light","themes","{\"theme\":{\"kind\":\"file\",\"path\":\"light.bte\"}}"));Files.writeString(light.resolve("light.bte"),"fixture");
            Path extra=folder(components,"extra",part("one","extras","{\"assets\":{\"kind\":\"directory\",\"path\":\"assets\",\"persistence\":\"preserve\"}}"));Files.createDirectory(extra.resolve("assets"));
            Path bad=folder(components,"bad",part("broken","themes","{\"theme\":{\"kind\":\"directory\",\"path\":\"bad-dir\"}}"));Files.createDirectory(bad.resolve("bad-dir"));
            Path orphan=folder(components,"orphan",part("orphan","wrong-group","{}"));
            Map<String,List<String>> choices=new HashMap<>();
            var snapshot=PackageInventory.inspect(mods,components,choices);
            check(snapshot.find("legacy").loadable(),"v2 mod remains valid");
            check(snapshot.find("parent").loadable(),"valid parent selectable without choice");
            check(snapshot.components().parts().size()==5,"all component packages discovered");
            check(snapshot.components().children("parent","themes").stream().filter(ComponentCatalog.Part::usable).count()==2,"independent components attach without parent edit");
            check(snapshot.components().parts().stream().anyMatch(p->p.id.equals("broken")&&!p.usable()),"resource-shape mismatch rejected");
            check(snapshot.components().parts().stream().anyMatch(p->p.id.equals("orphan")&&!p.usable()),"unknown group rejected");
            var themeGroup=snapshot.find("parent").groups.get("themes");
            check(snapshot.components().evaluate("parent",themeGroup,List.of()).configurationRequired(),"no choice requests configuration, not missing installation");
            check(ComponentSelectionStore.seedUnconfiguredSingles(snapshot,choices),"initial single group gets first usable option");
            check(choices.get(ComponentSelectionStore.key("parent","themes")).equals(List.of("dark")),"first usable catalogue option chosen");
            check(!choices.containsKey(ComponentSelectionStore.key("parent","extras")),"multiple group never defaults");
            check(!ComponentSelectionStore.seedUnconfiguredSingles(snapshot,choices),"default assignment idempotent");
            choices.put(ComponentSelectionStore.key("parent","themes"),List.of("missing"));
            check(!ComponentSelectionStore.seedUnconfiguredSingles(snapshot,choices),"missing saved choice never replaced");
            choices.remove(ComponentSelectionStore.key("parent","themes"));
            check(snapshot.components().evaluate("parent",themeGroup,List.of("dark")).ready(),"single selection ready");
            check(!snapshot.components().evaluate("parent",themeGroup,List.of("dark","light")).ready(),"single double-selection rejected");
            check(snapshot.components().evaluate("parent",snapshot.find("parent").groups.get("extras"),List.of("one")).ready(),"multiple group one+ accepted");
            choices.put(ComponentSelectionStore.key("parent","themes"),List.of("dark"));
            choices.put(ComponentSelectionStore.key("parent","extras"),List.of("one"));
            var store=new ComponentSelectionStore(root.resolve("state/selected-components.properties"));store.save(choices);
            check(store.load().equals(choices),"component selection persisted");
            check(PackageInventory.inspect(mods,components,store.load()).find("parent").loadable(),"parent valid with saved selections");
            Files.delete(dark.resolve("dark.bte"));
            var removed=PackageInventory.inspect(mods,components,store.load());
            check(!removed.find("parent").loadable(),"missing requested component blocks parent");
            check(ComponentSelectionStore.chosen(store.load(),"parent","themes").equals(List.of("dark")),"requested identity preserved");
            check(removed.find("legacy").loadable(),"unrelated v2 remains loadable");
            Files.writeString(dark.resolve("dark.bte"),"restored");
            check(PackageInventory.inspect(mods,components,store.load()).find("parent").loadable(),"recovery without reselection");
            folder(components,"dupe",part("dark","themes","{\"theme\":{\"kind\":\"file\",\"path\":\"dupe.bte\"}}"));
            var duplicate=PackageInventory.inspect(mods,components,store.load());
            check(duplicate.components().parts().stream().filter(p->"dark".equals(p.id)).allMatch(p->!p.usable()),"component identity collision blocks both");
            System.out.println("MANIFEST_V3_CATALOG_OK v2_unchanged=true groups=single,multiple persistence=ok recovery=ok duplicate=blocked resources=checked");
        }finally{try(var stream=Files.walk(root)){for(Path path:stream.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
