package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/**
 * Read-only package inventory for the window. Package issues are isolated by
 * folder so broken installations remain inspectable. The runtime preparer remains
 * the final authority for physical EXACT/MAPPED resolution and bytecode safety.
 */
final class PackageInventory {
    /** INFO: listed for completeness only; nothing to fix (e.g. a mod not available on this version that isn't ticked). */
    enum Severity { BLOCKING, WARNING, INFO }
    record Issue(String code, Severity severity, Path packagePath, String message, String remedy) {
        boolean blocking() { return severity == Severity.BLOCKING; }
    }
    static final class Entry {
        final Path directory, manifest;
        int manifestVersion=2;
        Map<String,ComponentCatalog.Group> groups=Map.of();
        String id, name, version="", author="", description="", bitwigVersion="", platform="", architecture="";
        /** What the mod needs a map to translate when it runs on another build ({@link RemapCheck#needs}). */
        Map<String,String> needs=Map.of();
        /** Set when the installed Bitwig's map can't translate this mod: why it isn't available on this version. */
        String unavailable;
        /** The installed Bitwig version, when {@link #unavailable} is set. */
        String unavailableOn;
        /** What this mod needs to load: prerequisite mod ID -> minimum version ("" for any). */
        Map<String,String> requires=Map.of();
        Path documentation;
        /** The manifest's documentation web page (mod.homepage, http or https), or null. */
        java.net.URI homepage;
        /** The manifest's mod.area as written; empty when not given ({@link #area()} falls back). */
        String declaredArea = "";
        List<String> tags = List.of();
        final List<Issue> issues = new ArrayList<>();
        Entry(Path dir) { directory=dir.toAbsolutePath().normalize(); manifest=directory.resolve("mod.json"); name=dir.getFileName().toString(); }
        String key() { return id == null || id.isBlank() ? "invalid:"+directory : id; }
        /** The area the mod belongs to: mod.area, else the "Area: " prefix of its name, else "Other". */
        String area() {
            if(!declaredArea.isBlank()) return declaredArea.strip();
            int colon=name.indexOf(": ");
            return colon>0 ? name.substring(0,colon).strip() : "Other";
        }
        /** The name without its area prefix, when it starts with "<area>: " (any case). */
        String shortName() {
            String prefix=area()+": ";
            return name.regionMatches(true,0,prefix,0,prefix.length()) && name.length()>prefix.length() ? name.substring(prefix.length()) : name;
        }
        boolean loadable() { return id!=null && unavailable==null && issues.stream().noneMatch(Issue::blocking); }
        String issueSummary() { return issues.stream().filter(Issue::blocking).map(Issue::message).findFirst().orElse(unavailable!=null?unavailable:issues.isEmpty()?"Ready":"Warning"); }
        ModInfo modInfo() { return new ModInfo(id,name,version,author,description,directory,manifest); }
        void issue(String code, Severity severity, String message, String remedy) { issues.add(new Issue(code,severity,directory,message,remedy)); }
    }
    record Snapshot(List<Entry> entries, List<Issue> issues, ComponentCatalog components) {
        Entry find(String id) { return entries.stream().filter(e->Objects.equals(e.id,id)).findFirst().orElse(null); }
        long errors() { return issues.stream().filter(Issue::blocking).count(); }
    }

    static Snapshot inspect(BmlPaths paths) throws IOException {
        return inspect(paths.mods,paths.root.resolve("components"),new ComponentSelectionStore(paths).load());
    }
    static Snapshot inspect(Path modsRoot) throws IOException {return inspect(modsRoot,null,Map.of());}
    static Snapshot inspect(Path modsRoot,Path componentsRoot,Map<String,List<String>> selections) throws IOException {
        Files.createDirectories(modsRoot);
        ArrayList<Entry> entries=new ArrayList<>();
        try(Stream<Path> folders=Files.list(modsRoot)) {
            for(Path dir:folders.filter(Files::isDirectory).sorted(Comparator.comparing(p->p.getFileName().toString())).toList()) {
                Entry e = new Entry(dir); entries.add(e);
                if(!Files.exists(e.manifest, LinkOption.NOFOLLOW_LINKS)) {
                    e.issue("MISSING_MANIFEST",Severity.BLOCKING,
                            "Package directory has no mod.json manifest.",
                            "Install a complete v2 mod package in this folder or remove the incomplete directory.");
                } else read(e);
            }
        }
        Map<String,List<Entry>> byId=new HashMap<>();
        for(Entry e:entries) if(e.id!=null) byId.computeIfAbsent(e.id,k->new ArrayList<>()).add(e);
        for(var same:byId.entrySet()) if(same.getValue().size()>1)
            for(Entry e:same.getValue()) e.issue("DUPLICATE_MOD_ID",Severity.BLOCKING,
                    "Duplicate mod ID '"+same.getKey()+"' is installed in multiple folders.",
                    "Keep exactly one mod package with this ID under mods/.");
        ComponentCatalog catalog=ComponentCatalog.empty();
        if(componentsRoot!=null){
            Map<String,Entry> parents=new HashMap<>();
            for(Entry e:entries)if(e.id!=null)parents.putIfAbsent(e.id,e);
            catalog=ComponentCatalog.discover(componentsRoot,parents);
            for(Entry parent:entries)if(parent.manifestVersion==3&&parent.id!=null){
                for(ComponentCatalog.Group group:parent.groups.values()){
                    List<ComponentCatalog.Part> usable=catalog.children(parent.id,group.id()).stream()
                        .filter(ComponentCatalog.Part::usable).toList();
                    // A group that may be empty works with none installed.
                    if(usable.isEmpty()&&group.minimum()>0)parent.issue("NO_USABLE_COMPONENTS",Severity.BLOCKING,
                        "No usable components are installed for group '"+group.name()+"'.",
                        group.detect().isEmpty()?"Install or repair a compatible component in components/, then Refresh."
                            :"Put a file matching "+String.join(" or ",group.detect().values().stream().filter(d->!d.endsWith("/")).toList())+", or a folder holding one, in components/.");
                    List<String> requested=ComponentSelectionStore.chosen(selections,parent.id,group.id());
                    if(!requested.isEmpty()) {
                        ComponentCatalog.Evaluation evaluation=catalog.evaluate(parent.id,group,requested);
                        for(String problem:evaluation.problems())parent.issue("COMPONENT_SELECTION_BLOCKED",Severity.BLOCKING,
                            problem,"Restore the requested component or change your selection in Components; no automatic replacement is made.");
                    }
                }
            }
        }
        checkPrerequisites(entries);
        ArrayList<Issue> issues=new ArrayList<>();
        for(Entry e:entries) issues.addAll(e.issues);
        for(ComponentCatalog.Part c:catalog.parts())issues.addAll(c.issues);
        issues.addAll(catalog.notes());
        return new Snapshot(List.copyOf(entries),List.copyOf(issues),catalog);
    }
    /** Standard 13: a mod made for a Bitwig build (version, platform, architecture) the installed Bitwig's
     *  map has no remap from, or that needs something the remap can't translate (such as one platform's
     *  own code, {@link RemapCheck}), isn't available on this version. Installed, it isn't an error
     *  (nothing to fix in the mod or the install), so it gets no issue and can't be selected; selected,
     *  it is an error the window lists and the preparer refuses. {@code map} is the map in use. */
    static void markUnavailable(Snapshot snapshot,MapPacks.Pack map) {
        String bitwig=map.bitwig();
        for(Entry e:snapshot.entries())e.unavailableOn=bitwig;
        Map<String,Map<String,Map<String,String>>> bySource=new TreeMap<>();  // source key -> mod ID -> needs
        for(Entry e:snapshot.entries()){
            if(e.id==null||e.bitwigVersion.isEmpty())continue;
            String source=map.sources().get(MapPacks.buildId(e.bitwigVersion,e.platform,e.architecture));
            if(source==null)
                e.unavailable="Not available on Bitwig "+bitwig+": made for Bitwig "+e.bitwigVersion+" on "+
                    RemapCheck.platformName(e.platform)+" ("+e.architecture+"), which its map can't translate";
            else if(!source.equals(map.targetKey()))bySource.computeIfAbsent(source,k->new TreeMap<>()).put(e.id,e.needs);
        }
        Map<String,Entry> ids=installed(snapshot.entries());
        String runtime="Bitwig "+bitwig+" for "+RemapCheck.platformName(map.targetKey().substring(0,map.targetKey().indexOf('-')));
        for(var source:bySource.entrySet()){
            try{
                var gaps=RemapCheck.gaps(map.directory().resolve("remap/"+source.getKey()+"__"+map.targetKey()+".tsv.gz"),
                    source.getValue(),runtime);
                for(var gap:gaps.entrySet()){
                    Entry e=ids.get(gap.getKey());
                    e.unavailable="Not available on Bitwig "+bitwig+": made for Bitwig "+e.bitwigVersion+" on "+
                        RemapCheck.platformName(e.platform)+", and "+gap.getValue();
                }
            }catch(IOException ex){
                // An unreadable remap: preparation reads it too and reports the problem.
            }
        }
        // A mod that needs one of them isn't available either.
        Map<String,Entry> byId=installed(snapshot.entries());
        for(boolean changed=true;changed;){
            changed=false;
            for(Entry e:snapshot.entries())if(e.id!=null&&e.unavailable==null)
                for(String id:e.requires.keySet()){
                    Entry p=byId.get(id);
                    if(p!=null&&p.unavailable!=null){
                        e.unavailable="Not available on Bitwig "+bitwig+": needs "+p.name+", which isn't available on this version";
                        changed=true;break;
                    }
                }
        }
    }
    private static Map<String,Entry> installed(List<Entry> entries) {
        Map<String,Entry> byId=new HashMap<>();
        for(Entry e:entries)if(e.id!=null)byId.putIfAbsent(e.id,e);
        return byId;
    }
    /** A prerequisite that isn't installed, is older than the version asked for, or has errors itself
     *  blocks the mod that needs it (and, in turn, the mods that need that one). */
    private static void checkPrerequisites(List<Entry> entries) {
        Map<String,Entry> byId=installed(entries);
        for(Entry e:entries)if(e.id!=null)
            for(var req:e.requires.entrySet()){
                Entry p=byId.get(req.getKey());
                String min=req.getValue().isEmpty()?"":" "+req.getValue()+" or later";
                if(p==null)e.issue("MISSING_PREREQUISITE",Severity.BLOCKING,
                    "Needs "+req.getKey()+min+", which isn't installed.",
                    "Install "+req.getKey()+min+" in mods/, or remove "+e.name+".");
                else if(!Prerequisites.atLeast(p.version,req.getValue()))e.issue("PREREQUISITE_TOO_OLD",Severity.BLOCKING,
                    "Needs "+p.name+min+"; "+p.version+" is installed.",
                    "Install "+p.name+min+" in mods/.");
            }
        for(boolean changed=true;changed;){
            changed=false;
            for(Entry e:entries)if(e.id!=null&&e.issues.stream().noneMatch(Issue::blocking))
                for(String id:e.requires.keySet()){
                    Entry p=byId.get(id);
                    if(p!=null&&!p.loadable()){
                        e.issue("PREREQUISITE_BLOCKED",Severity.BLOCKING,"Needs "+p.name+", which can't load: "+p.issueSummary(),
                            "Fix "+p.name+" (see its entry on this page).");
                        changed=true;break;
                    }
                }
        }
    }
    private static void read(Entry e) {
        try {
            if(Files.isSymbolicLink(e.directory)||Files.isSymbolicLink(e.manifest)) throw new IOException("Package directory/manifest uses a symbolic link");
            Map<String,Object> root=SimpleJson.object(SimpleJson.parse(Files.readString(e.manifest,StandardCharsets.UTF_8)));
            if(!(root.get("manifest_version") instanceof Number v) || (v.doubleValue()!=2.0&&v.doubleValue()!=3.0))
                throw new IllegalArgumentException("Unsupported manifest_version (expected v2 or v3)");
            e.manifestVersion=v.intValue();
            if(e.manifestVersion==3){
                if(!"mod".equals(root.get("package_type")))throw new IllegalArgumentException("mods/ requires v3 package_type mod (components belong in components/)");
                e.groups=ComponentCatalog.parseGroups(root);
                DiagnosticOutputs.parse(root);
            }
            Map<String,Object> mod=SimpleJson.object(root.get("mod"));
            e.id=required(mod,"id","mod.id");
            if(!e.id.matches("[A-Za-z][A-Za-z0-9_.-]*")) throw new IllegalArgumentException("Invalid mod.id: "+e.id);
            e.requires=Prerequisites.parse(root,e.id);
            e.name=required(mod,"name","mod.name"); e.version=required(mod,"version","mod.version");
            e.author=required(mod,"author","mod.author");e.description=required(mod,"description","mod.description");
            Map<String,Object> built=SimpleJson.object(root.get("built_against"));
            e.bitwigVersion=required(built,"bitwig_version","built_against.bitwig_version");
            e.platform=required(built,"platform","built_against.platform");
            e.architecture=required(built,"architecture","built_against.architecture");
            e.needs=RemapCheck.needs(e.directory,root);
            if(!(root.get("targets") instanceof Map<?,?>))throw new IllegalArgumentException("targets must be an object");
            if(!(root.get("hooks") instanceof List<?> hooks))throw new IllegalArgumentException("hooks must be an array");
            ModFields.parse(root,e.id);
            Object traces=root.get("trace_targets");
            if(traces!=null && !(traces instanceof List<?>))throw new IllegalArgumentException("trace_targets must be an array");
            List<ProviderRegistrations.Entry> registrations=ProviderRegistrations.parse(root,e.directory);
            if(hooks.isEmpty() && (!(traces instanceof List<?> t) || t.isEmpty()) && registrations.isEmpty())
                throw new IllegalArgumentException("Mod declares neither hooks, trace targets nor provider registrations");
            if(!hooks.isEmpty()) {
                Map<String,Object> helper=SimpleJson.object(root.get("helper"));
                Path jar=resolveInside(e.directory,required(helper,"jar","helper.jar"),true,false);
                required(helper,"class","helper.class");
                if(!Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS))throw new IOException("Helper JAR missing or not a file: "+jar);
            }
            if(mod.containsKey("area")) {
                if(mod.get("area") instanceof String a && !a.isBlank() && a.length()<=60) e.declaredArea=a;
                else e.issue("INVALID_AREA",Severity.WARNING,"mod.area must be text of 1 to 60 characters; it is ignored.",
                        "Correct mod.area in mod.json, or remove it.");
            }
            if(mod.containsKey("homepage")) {
                java.net.URI page=null;
                try {
                    if(mod.get("homepage") instanceof String h) {
                        java.net.URI u=new java.net.URI(h.strip());
                        String scheme=u.getScheme()==null?"":u.getScheme().toLowerCase(Locale.ROOT);
                        if((scheme.equals("http")||scheme.equals("https")) && u.getHost()!=null) page=u;
                    }
                } catch(java.net.URISyntaxException ignored) { }
                if(page!=null) e.homepage=page;
                else e.issue("INVALID_HOMEPAGE",Severity.WARNING,"mod.homepage must be an http or https address; it is ignored.",
                        "Correct mod.homepage in mod.json, or remove it.");
            }
            if(mod.containsKey("tags")) {
                Object t=mod.get("tags");
                if(!(t instanceof List<?> l))throw new IllegalArgumentException("mod.tags must be a string array");
                ArrayList<String> tags=new ArrayList<>();
                for(Object obj:l) {
                    if(!(obj instanceof String s)||s.isBlank()||s.length()>80)throw new IllegalArgumentException("mod.tags contains an invalid label");
                    if(tags.stream().noneMatch(x->x.equalsIgnoreCase(s)))tags.add(s);
                }
                e.tags=List.copyOf(tags);
            }
            Map<String,Object> resources=root.get("resources")==null?Map.of():SimpleJson.object(root.get("resources"));
            for(var item:resources.entrySet()) {
                if(!item.getKey().matches("[A-Za-z][A-Za-z0-9_.-]*"))throw new IllegalArgumentException("Invalid resource ID: "+item.getKey());
                Map<String,Object> resource=SimpleJson.object(item.getValue());
                String kind=required(resource,"kind","resource kind");
                if(!Set.of("file","directory","bitwig").contains(kind))throw new IllegalArgumentException("Invalid resource kind: "+kind);
                if(kind.equals("bitwig")) {
                    // One of Bitwig's own files, outside the package: checked against the installed
                    // Bitwig when the mod is prepared; here only its form.
                    String bitwigPath=required(resource,"path","resource path");
                    if(resource.containsKey("persistence")||Path.of(bitwigPath).isAbsolute()||Path.of(bitwigPath).normalize().startsWith(".."))
                        throw new IllegalArgumentException("Invalid bitwig resource: "+item.getKey());
                    continue;
                }
                String persistence=(String)resource.getOrDefault("persistence","replaceable");
                if(!Set.of("replaceable","preserve","created").contains(persistence))throw new IllegalArgumentException("Invalid persistence: "+persistence);
                String resourcePath=required(resource,"path","resource path");
                if(persistence.equals("created")){
                    // Written by the mod in its data folder: nothing in the package to check.
                    ModData.checkCreatedName(e.id==null?"mod":e.id,item.getKey(),resourcePath);
                    continue;
                }
                boolean isDocumentation=item.getKey().equals(mod.get("documentation"));
                // An optional documentation asset may be absent without making an
                // otherwise valid v2 mod unloadable. Path traversal/reparse is
                // still a blocking resource-security fault.
                Path candidate=resolveInside(e.directory,resourcePath,!isDocumentation,kind.equals("directory"));
                if(isDocumentation && (!Files.exists(candidate,LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS))) {
                    e.issue("MISSING_DOCUMENTATION",Severity.WARNING,
                            "Optional documentation file is missing: "+resourcePath,
                            "Restore the README or remove mod.documentation.");
                }
            }
            if(mod.containsKey("documentation")) {
                Object doc=mod.get("documentation");
                if(!(doc instanceof String docId)||docId.isBlank()){
                    e.issue("INVALID_DOCUMENTATION",Severity.WARNING,"Invalid documentation resource reference.","Set mod.documentation to a declared Markdown file resource ID.");
                } else if(!resources.containsKey(docId)){
                    e.issue("MISSING_DOCUMENTATION",Severity.WARNING,"Documentation resource '"+docId+"' is not declared.","Declare it in resources or remove mod.documentation.");
                } else {
                    try {
                        Map<String,Object> resource=SimpleJson.object(resources.get(docId));
                        if(!"file".equals(resource.get("kind")))throw new IOException("Documentation resource must be a file");
                        String path=required(resource,"path","documentation path");
                        if(!path.toLowerCase(Locale.ROOT).endsWith(".md") && !"text/markdown".equalsIgnoreCase(String.valueOf(resource.get("media_type"))))
                            throw new IOException("Documentation requires .md extension or text/markdown media type");
                        Path found=resolveInside(e.directory,path,false,false);
                        if(Files.isRegularFile(found,LinkOption.NOFOLLOW_LINKS))e.documentation=found;
                    } catch(Exception ex) {
                        e.issue("INVALID_DOCUMENTATION",Severity.WARNING,"Documentation unavailable: "+ex.getMessage(),"Correct the optional documentation resource declaration.");
                    }
                }
            }
        } catch(Exception ex) {
            e.issue("INVALID_MANIFEST",Severity.BLOCKING,"Cannot prepare package: "+ex.getMessage(),
                    "Repair the package's mod.json or missing resources, then Refresh Mods.");
        }
    }
    static Path resolveInside(Path root,String declared,boolean mustExist,boolean directory) throws IOException {
        Path rel=Path.of(declared);
        if(rel.isAbsolute())throw new IOException("Absolute package resource path is not allowed: "+declared);
        Path base=root.toAbsolutePath().normalize(), candidate=base.resolve(rel).normalize();
        if(candidate.equals(base)||!candidate.startsWith(base))throw new IOException("Resource path escapes package root: "+declared);
        for(Path p=candidate;p!=null && p.startsWith(base);p=p.getParent()) {
            if(Files.isSymbolicLink(p))throw new IOException("Package resource uses a symlink: "+declared);
            try {
                BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(a.isOther())throw new IOException("Package resource uses a reparse/special path: "+declared);
            } catch(NoSuchFileException ignored) { }
        }
        if(mustExist && !Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing package resource: "+declared);
        if(mustExist && (directory!=Files.isDirectory(candidate,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Wrong resource file/directory shape: "+declared);
        return candidate;
    }
    private static String required(Map<String,Object> m,String key,String where) {
        Object x=m.get(key);
        if(!(x instanceof String s)||s.isBlank())throw new IllegalArgumentException(where+" is required and must be a non-empty string");
        return s;
    }
}
