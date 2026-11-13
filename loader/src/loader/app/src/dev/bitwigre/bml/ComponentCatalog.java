package dev.bitwigre.bml;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Stream;

/**
 * v3 manifest/component discovery and selection rules. Resource payloads are
 * data-only; this class never loads a component JAR or runs component code.
 * Validation applies to requested selections before JavaPreparer transforms.
 */
final class ComponentCatalog {
    static final String ID_PATTERN="[A-Za-z][A-Za-z0-9_.-]*";
    record Resource(String id,String kind,String declared,Path path,String persistence,String mediaType) {}
    /** A detect value: "name/" for a folder, or a file name pattern with * and ?, holding at least one literal character. */
    private static final String DETECT_PATTERN="[A-Za-z0-9_-][A-Za-z0-9_.-]*/|[A-Za-z0-9_.*?-]*[A-Za-z0-9_.-][A-Za-z0-9_.*?-]*";
    private static final long LARGEST_DETECTED_FILE=64L<<20;
    /** detect: resource slot -> pattern, which lets a package with no mod.json be recognised ({@link #discover}). */
    /** minimum: how many components must be chosen. 1 for "single"; for "multiple" 1 unless the group declares
     *  {@code "minimum"}, where 0 lets the mod prepare and load with nothing chosen. */
    record Group(String id,String name,String selection,String interfaceVersion,Map<String,String> requiredResources,Map<String,String> detect,int minimum) {}
    static final class Part {
        final Path directory,manifest;
        /** Recognised through a group's detect patterns: no mod.json, and for a loose file directory is the file. */
        boolean inferred;
        String id,name="",version="",author="",description="",parent="",group="",interfaceVersion="1";
        List<String> tags=List.of();
        Path documentation;
        Map<String,Resource> resources=Map.of();
        final List<PackageInventory.Issue> issues=new ArrayList<>();
        Part(Path directory) {
            this.directory=directory.toAbsolutePath().normalize();manifest=this.directory.resolve("mod.json");
            name=directory.getFileName().toString();
        }
        String key(){return parent+"/"+group+"/"+id;}
        boolean usable(){return id!=null&&issues.stream().noneMatch(PackageInventory.Issue::blocking);}
        void issue(String code,String message,String remedy){issues.add(new PackageInventory.Issue(code,PackageInventory.Severity.BLOCKING,directory,message,remedy));}
    }
    record Evaluation(boolean ready,boolean configurationRequired,List<String> problems,List<Part> selected) {}
    private final List<Part> parts;
    private final List<PackageInventory.Issue> notes;
    private final Map<String,List<Part>> byGroup;
    private ComponentCatalog(List<Part> parts,List<PackageInventory.Issue> notes){
        this.parts=List.copyOf(parts);this.notes=List.copyOf(notes);
        Map<String,List<Part>> grouped=new LinkedHashMap<>();
        for(Part part:parts)grouped.computeIfAbsent(part.parent+"/"+part.group,k->new ArrayList<>()).add(part);
        this.byGroup=grouped;
    }
    static ComponentCatalog empty(){return new ComponentCatalog(List.of(),List.of());}
    List<Part> parts(){return parts;}
    /** Warnings about what is in the components folder but isn't a part: they stop nothing. */
    List<PackageInventory.Issue> notes(){return notes;}
    List<Part> children(String parent,String group){return byGroup.getOrDefault(parent+"/"+group,List.of());}
    static Map<String,Group> parseGroups(Map<String,Object> manifest) {
        if(!manifest.containsKey("component_groups"))return Map.of();
        Map<String,Object> groupData=SimpleJson.object(manifest.get("component_groups"));
        LinkedHashMap<String,Group> result=new LinkedHashMap<>();
        for(var item:groupData.entrySet()){
            String gid=ident(item.getKey(),"component group ID");
            Map<String,Object> raw=SimpleJson.object(item.getValue());
            if(!Set.of("name","selection","interface_version","required_resources","detect","minimum").containsAll(raw.keySet()))
                throw new IllegalArgumentException("Unknown component group property in "+gid);
            String name=required(raw,"name","component group "+gid);
            String selection=required(raw,"selection","component group "+gid);
            if(!Set.of("single","multiple").contains(selection))throw new IllegalArgumentException("Invalid selection for group "+gid+": "+selection);
            String version=optional(raw,"interface_version","1");
            if(!version.matches("[0-9]+"))throw new IllegalArgumentException("Invalid interface_version in group "+gid);
            Map<String,Object> req=raw.containsKey("required_resources")?SimpleJson.object(raw.get("required_resources")):Map.of();
            LinkedHashMap<String,String> slots=new LinkedHashMap<>();
            for(var slot:req.entrySet()){
                String sid=ident(slot.getKey(),"required resource slot");
                String kind=required(SimpleJson.object(slot.getValue()),"kind","required resource "+sid);
                if(!Set.of("file","directory").contains(kind))throw new IllegalArgumentException("Invalid required resource kind: "+kind);
                slots.put(sid,kind);
            }
            int minimum=1;
            if(raw.containsKey("minimum")){
                if(!(raw.get("minimum") instanceof Number n)||n.doubleValue()!=Math.rint(n.doubleValue())||n.doubleValue()<0||n.doubleValue()>1000)
                    throw new IllegalArgumentException("minimum in group "+gid+" must be a whole number, 0 or more");
                minimum=n.intValue();
                if(selection.equals("single")&&minimum!=1)throw new IllegalArgumentException("Group "+gid+" is a single choice, which always needs one; minimum is for \"multiple\"");
            }
            result.put(gid,new Group(gid,name,selection,version,Map.copyOf(slots),detect(raw,gid,slots),minimum));
        }
        return Collections.unmodifiableMap(result);
    }
    private static Map<String,String> detect(Map<String,Object> raw,String gid,Map<String,String> required){
        if(!raw.containsKey("detect"))return Map.of();
        LinkedHashMap<String,String> slots=new LinkedHashMap<>();
        for(var slot:SimpleJson.object(raw.get("detect")).entrySet()){
            String sid=ident(slot.getKey(),"detect slot");
            if(!(slot.getValue() instanceof String pattern)||!pattern.matches(DETECT_PATTERN)||pattern.contains(".."))
                throw new IllegalArgumentException("Invalid detect pattern for "+sid+" in group "+gid);
            String kind=required.get(sid);
            if(kind!=null&&kind.equals("directory")!=pattern.endsWith("/"))
                throw new IllegalArgumentException("detect pattern for "+sid+" in group "+gid+" doesn't fit its required kind "+kind);
            slots.put(sid,pattern);
        }
        if(slots.values().stream().allMatch(p->p.endsWith("/")))throw new IllegalArgumentException("detect in group "+gid+" needs a file pattern");
        for(String sid:required.keySet())if(!slots.containsKey(sid))throw new IllegalArgumentException("detect in group "+gid+" doesn't cover required resource "+sid);
        return Collections.unmodifiableMap(slots);
    }
    /** One group that recognises packages with no mod.json. */
    private record Detector(PackageInventory.Entry parent,Group group) {
        String label(){return parent.name+" / "+group.name();}
        List<String> filePatterns(){return group.detect().values().stream().filter(p->!p.endsWith("/")).toList();}
        /** A folder is this group's when one of its top-level files matches a file pattern. */
        boolean claims(List<Path> top){
            return filePatterns().stream().anyMatch(p->top.stream().anyMatch(f->isCandidateFile(f)&&matches(p,f)));
        }
        /** The slot a loose file fills: the group has one file pattern, nothing else required, and the file matches it. */
        String looseSlot(Path file){
            if(filePatterns().size()!=1||!matches(filePatterns().get(0),file))return null;
            String slot=group.detect().entrySet().stream().filter(e->!e.getValue().endsWith("/")).findFirst().orElseThrow().getKey();
            return group.requiredResources().keySet().stream().allMatch(slot::equals)?slot:null;
        }
    }
    private static List<Detector> detectors(Map<String,PackageInventory.Entry> parents){
        List<Detector> found=new ArrayList<>();
        for(String id:new TreeSet<>(parents.keySet())){
            PackageInventory.Entry parent=parents.get(id);
            if(parent.manifestVersion==3)for(Group group:parent.groups.values())if(!group.detect().isEmpty())found.add(new Detector(parent,group));
        }
        return found;
    }
    private static boolean matches(String pattern,Path file){
        StringBuilder regex=new StringBuilder();
        for(char c:pattern.toCharArray())regex.append(c=='*'?".*":c=='?'?".":java.util.regex.Pattern.quote(String.valueOf(c)));
        return java.util.regex.Pattern.compile(regex.toString(),java.util.regex.Pattern.CASE_INSENSITIVE).matcher(file.getFileName().toString()).matches();
    }
    /** Dot files, and the folder a Mac's archive tool adds, are never a package or part of one. */
    private static boolean hidden(Path path){
        String name=path.getFileName().toString();
        return name.startsWith(".")||name.equals("__MACOSX");
    }
    /** A symbolic link counts, so that it is refused rather than passed over. */
    private static boolean isCandidateFile(Path file){
        return Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file);
    }
    /** The ID of a package named only by its folder or file: lower case, other characters become "-".
     *  It must start with a letter, so "808 Dark" gets a prefix; a name with no usable character gets a hash of the name. */
    static String derivedId(String name){
        String plain=Normalizer.normalize(name,Normalizer.Form.NFKD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);
        String id=plain.replaceAll("[^a-z0-9_.-]+","-").replaceAll("-{2,}","-").replaceAll("^[-_.]+|[-_.]+$","");
        if(id.isEmpty()){
            try{id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8))).substring(0,8);}
            catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
        }
        return Character.isLetter(id.charAt(0))?id:"c-"+id;
    }
    private static Part inferredPart(Path path,String name,Detector detector){
        Part part=new Part(path);
        part.inferred=true;part.name=name;part.id=derivedId(name);
        part.parent=detector.parent.id;part.group=detector.group.id();part.interfaceVersion=detector.group.interfaceVersion();
        return part;
    }
    /** What stops a file a pattern matched from being used, reported before anything reads it at launch. */
    private static void checkDetectedFile(Part part,Path file){
        String name=file.getFileName().toString();
        try{
            if(Files.size(file)>LARGEST_DETECTED_FILE){part.issue("UNREADABLE_COMPONENT_FILE",name+" is too large to be a text file ("+Files.size(file)+" bytes).","Replace it with the right file.");return;}
            String text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            if(text.indexOf('\0')>=0)throw new java.nio.charset.MalformedInputException(1);
            if(text.isBlank())part.issue("EMPTY_COMPONENT_FILE",name+" is empty.","Put the content in it, or remove it.");
        }catch(CharacterCodingException ex){
            part.issue("UNREADABLE_COMPONENT_FILE",name+" isn't UTF-8 text.","Save it again as UTF-8 plain text.");
        }catch(IOException ex){
            part.issue("UNREADABLE_COMPONENT_FILE",name+" can't be read: "+ex.getMessage(),"Check that nothing has it locked and that you have permission to read it.");
        }
    }
    private static final int LONGEST_NAME=120;
    /** A part found by its files is shown by the top-level "name" of its .json file when there is one: non-blank
     *  text, or a whole number as its digits. The ID stays the one from the file or folder name, so a name changed
     *  inside the file never loses a saved choice. JSON that can't be read, or has no such name, keeps the file name. */
    private static void nameFromJson(Part part){
        if(!part.usable())return;
        for(Resource r:part.resources.values()){
            if(!"file".equals(r.kind())||!r.declared().toLowerCase(Locale.ROOT).endsWith(".json"))continue;
            try{
                if(SimpleJson.parse(Files.readString(r.path(),StandardCharsets.UTF_8)) instanceof Map<?,?> root){
                    Object value=root.get("name");
                    String name=value instanceof String s?s
                        :value instanceof Number n&&n.doubleValue()==Math.rint(n.doubleValue())&&Math.abs(n.doubleValue())<1e15?Long.toString(n.longValue()):null;
                    if(name!=null){
                        name=name.replaceAll("[\\p{Cntrl}\\s]+"," ").strip();
                        if(name.length()>LONGEST_NAME)name=name.substring(0,LONGEST_NAME).strip()+"…";
                        if(!name.isEmpty())part.name=name;
                    }
                }
            }catch(IOException|RuntimeException unreadable){
                // the file name stays
            }
            return;
        }
    }
    private static String looksFor(List<Detector> detectors){
        if(detectors.isEmpty())return "No installed framework recognises files by name, so this folder needs a mod.json. If it is just a folder you keep here, nothing needs doing.";
        List<String> wanted=new ArrayList<>();
        for(Detector d:detectors)wanted.add(d.label()+" looks for "+String.join(" or ",d.filePatterns())+" directly in the folder");
        return String.join("; ",wanted)+". Or add a mod.json. If this is just a folder you keep here, nothing needs doing.";
    }
    /** A folder with no mod.json: one part if exactly one group's patterns claim it, otherwise a warning. */
    private static void inferFolder(Path dir,List<Detector> detectors,List<Part> parts,List<PackageInventory.Issue> notes){
        Path root=dir.toAbsolutePath().normalize();
        String name=dir.getFileName().toString();
        List<Path> top;
        try(Stream<Path> s=Files.list(dir)){top=s.filter(p->!hidden(p)).sorted(Comparator.comparing(p->p.getFileName().toString())).toList();}
        catch(IOException|UncheckedIOException ex){
            notes.add(new PackageInventory.Issue("UNREADABLE_COMPONENT_FOLDER",PackageInventory.Severity.WARNING,root,"Folder '"+name+"' can't be read: "+ex.getMessage(),"Check that you have permission to read it."));
            return;
        }
        List<Detector> claims=detectors.stream().filter(d->d.claims(top)).toList();
        if(claims.isEmpty()){
            notes.add(new PackageInventory.Issue("UNRECOGNISED_COMPONENT_FOLDER",PackageInventory.Severity.WARNING,root,"Folder '"+name+"' has no mod.json and nothing BML recognises.",looksFor(detectors)));
            return;
        }
        if(claims.size()>1){notes.add(ambiguous(root,name,claims));return;}
        Detector detector=claims.get(0);
        Part part=inferredPart(dir,name,detector);parts.add(part);
        LinkedHashMap<String,Resource> resources=new LinkedHashMap<>();
        for(var slot:detector.group.detect().entrySet()){
            String pattern=slot.getValue();
            boolean folder=pattern.endsWith("/");
            String kind=folder?"directory":"file";
            String folderName=folder?pattern.substring(0,pattern.length()-1):null;
            List<Path> found=top.stream().filter(p->folder
                ?p.getFileName().toString().equalsIgnoreCase(folderName)&&(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(p))
                :isCandidateFile(p)&&matches(pattern,p)).toList();
            if(found.size()>1){
                part.issue("TOO_MANY_COMPONENT_FILES","Folder '"+name+"' has "+found.size()+" files matching "+pattern+": "
                    +String.join(", ",found.stream().map(p->p.getFileName().toString()).toList())+". "+detector.label()+" uses one.",
                    "Keep one of them in this folder, or give each its own folder.");
                continue;
            }
            if(found.isEmpty()){
                if(detector.group.requiredResources().containsKey(slot.getKey()))
                    part.issue("MISSING_REQUIRED_RESOURCE","Folder '"+name+"' has nothing matching "+pattern+", which "+detector.label()+" requires.","Add it, or add a mod.json saying where it is.");
                continue;
            }
            String declared=found.get(0).getFileName().toString();
            try{
                Path target=PackageInventory.resolveInside(dir,declared,true,folder);
                if(!folder)checkDetectedFile(part,target);
                resources.put(slot.getKey(),new Resource(slot.getKey(),kind,declared,target,"replaceable",""));
            }catch(IOException ex){
                part.issue("INVALID_COMPONENT","Invalid component package: "+ex.getMessage(),"Remove the link and put the real file or folder here.");
            }
        }
        part.resources=Collections.unmodifiableMap(resources);
        nameFromJson(part);
    }
    /** A file directly in the components folder that a group's pattern matches is a part of its own. Other files are left alone. */
    private static void inferLooseFile(Path file,List<Detector> detectors,List<Part> parts,List<PackageInventory.Issue> notes){
        if(!isCandidateFile(file))return;
        List<Detector> claims=detectors.stream().filter(d->d.looseSlot(file)!=null).toList();
        if(claims.isEmpty())return;
        String fileName=file.getFileName().toString();
        if(claims.size()>1){notes.add(ambiguous(file.toAbsolutePath().normalize(),fileName,claims));return;}
        int dot=fileName.lastIndexOf('.');
        Detector detector=claims.get(0);
        Part part=inferredPart(file,dot>0?fileName.substring(0,dot):fileName,detector);parts.add(part);
        if(Files.isSymbolicLink(file)){
            part.issue("INVALID_COMPONENT","Invalid component package: symbolic links are not valid package roots.","Put the real file here.");
            return;
        }
        checkDetectedFile(part,file);
        String slot=detector.looseSlot(file);
        part.resources=Map.of(slot,new Resource(slot,"file",fileName,part.directory,"replaceable",""));
        nameFromJson(part);
    }
    private static PackageInventory.Issue ambiguous(Path path,String name,List<Detector> claims){
        return new PackageInventory.Issue("AMBIGUOUS_COMPONENT",PackageInventory.Severity.WARNING,path,
            "'"+name+"' could belong to "+String.join(" or ",claims.stream().map(Detector::label).toList())+", so it isn't listed.",
            "Put it in a folder with a mod.json saying which it belongs to.");
    }
    /** Changes when what discovery would find changes: names, sizes and times of what is in the components folder and its folders' top level. */
    static String signature(Path componentsRoot){
        StringBuilder out=new StringBuilder();
        try(Stream<Path> entries=Files.list(componentsRoot)){
            for(Path entry:entries.sorted().toList()){
                signatureLine(out,entry);
                if(Files.isDirectory(entry))try(Stream<Path> inner=Files.list(entry)){
                    for(Path p:inner.sorted().toList())signatureLine(out,p);
                }catch(IOException|UncheckedIOException ex){out.append(entry).append("|unreadable\n");}
            }
        }catch(IOException|UncheckedIOException ex){out.append("unreadable");}
        return out.toString();
    }
    private static void signatureLine(StringBuilder out,Path path){
        out.append(path).append('|');
        try{out.append(Files.size(path)).append('|').append(Files.getLastModifiedTime(path));}
        catch(IOException ex){out.append("unreadable");}
        out.append('\n');
    }
    static ComponentCatalog discover(Path componentsRoot,Map<String,PackageInventory.Entry> parents) throws IOException {
        Files.createDirectories(componentsRoot);
        List<Part> parts=new ArrayList<>();
        List<PackageInventory.Issue> notes=new ArrayList<>();
        List<Detector> detectors=detectors(parents);
        try(Stream<Path> dirStream=Files.list(componentsRoot)){
            for(Path dir:dirStream.sorted(Comparator.comparing(p->p.getFileName().toString())).toList()){
                if(!Files.isDirectory(dir)){
                    if(!hidden(dir))inferLooseFile(dir,detectors,parts,notes);
                    continue;
                }
                // mod.json, when there is one, always decides; a link is refused below as ever.
                if(!Files.isSymbolicLink(dir)&&!Files.exists(dir.resolve("mod.json"),LinkOption.NOFOLLOW_LINKS)){
                    if(!hidden(dir))inferFolder(dir,detectors,parts,notes);
                    continue;
                }
                Part part=new Part(dir);parts.add(part);
                try {
                    if(Files.isSymbolicLink(dir)||Files.isSymbolicLink(part.manifest))throw new IOException("Symbolic links are not valid package roots/manifests");
                    if(!Files.isRegularFile(part.manifest,LinkOption.NOFOLLOW_LINKS))throw new IOException("No mod.json manifest");
                    Map<String,Object> raw=SimpleJson.object(SimpleJson.parse(Files.readString(part.manifest,StandardCharsets.UTF_8)));
                    if(!(raw.get("manifest_version") instanceof Number ver)||ver.doubleValue()!=3.0||!"component".equals(raw.get("package_type")))
                        throw new IllegalArgumentException("components/ requires manifest_version 3 and package_type component");
                    if(raw.containsKey("hooks")||raw.containsKey("targets")||raw.containsKey("helper")||raw.containsKey("trace_targets")||raw.containsKey("built_against")||raw.containsKey("component_groups")||raw.containsKey("mod"))
                        throw new IllegalArgumentException("Component declares executable or parent-only manifest fields");
                    Map<String,Object> meta=SimpleJson.object(raw.get("component"));
                    if(!Set.of("manifest_version","package_type","component","resources").containsAll(raw.keySet()))
                        throw new IllegalArgumentException("Unknown component manifest top-level field");
                    if(!Set.of("id","name","version","author","description","parent","group","interface_version","tags","documentation").containsAll(meta.keySet()))
                        throw new IllegalArgumentException("Unknown component metadata field");
                    part.id=ident(required(meta,"id","component"),"component ID");
                    part.name=required(meta,"name","component");part.version=required(meta,"version","component");
                    part.author=required(meta,"author","component");part.description=required(meta,"description","component");
                    part.parent=ident(required(meta,"parent","component"),"component parent");
                    part.group=ident(required(meta,"group","component"),"component group");
                    part.interfaceVersion=optional(meta,"interface_version","1");
                    if(!part.interfaceVersion.matches("[0-9]+"))throw new IllegalArgumentException("Invalid component interface_version");
                    part.tags=tags(meta);
                    Map<String,Object> src=raw.containsKey("resources")?SimpleJson.object(raw.get("resources")):Map.of();
                    LinkedHashMap<String,Resource> resources=new LinkedHashMap<>();
                    for(var r:src.entrySet()){
                        String rid=ident(r.getKey(),"component resource ID");
                        Map<String,Object> rv=SimpleJson.object(r.getValue());
                        if(!Set.of("kind","path","persistence","media_type").containsAll(rv.keySet()))
                            throw new IllegalArgumentException("Unknown component resource field for "+rid);
                        String kind=required(rv,"kind","component resource "+rid);
                        if(!Set.of("file","directory").contains(kind))throw new IllegalArgumentException("Invalid component resource kind for "+rid);
                        String persistence=optional(rv,"persistence","replaceable");
                        if(!Set.of("preserve","replaceable").contains(persistence))throw new IllegalArgumentException("Invalid component persistence for "+rid);
                        String path=required(rv,"path","component resource "+rid);
                        boolean doc=rid.equals(meta.get("documentation"));
                        Path target=PackageInventory.resolveInside(dir,path,!doc,"directory".equals(kind));
                        if(doc&&!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)){
                            part.issues.add(new PackageInventory.Issue("MISSING_COMPONENT_DOCUMENTATION",PackageInventory.Severity.WARNING,dir,"Optional component Markdown missing: "+path,"Restore README or remove component.documentation."));
                        }
                        resources.put(rid,new Resource(rid,kind,path,target,persistence,optional(rv,"media_type","")));
                    }
                    part.resources=Collections.unmodifiableMap(resources);
                    if(meta.containsKey("documentation")){
                        String doc=required(meta,"documentation","component documentation");
                        Resource resource=resources.get(doc);
                        if(resource==null||!resource.kind.equals("file")||(!resource.declared.toLowerCase(Locale.ROOT).endsWith(".md")&&!resource.mediaType.equalsIgnoreCase("text/markdown"))){
                            part.issues.add(new PackageInventory.Issue("INVALID_COMPONENT_DOCUMENTATION",PackageInventory.Severity.WARNING,dir,"Invalid Markdown reference '"+doc+"'","Declare a file resource with .md or text/markdown."));
                        }else if(Files.isRegularFile(resource.path,LinkOption.NOFOLLOW_LINKS))part.documentation=resource.path;
                    }
                } catch(Exception ex){part.issue("INVALID_COMPONENT","Invalid component package: "+ex.getMessage(),"Fix mod.json, resource paths and install it under components/.");}
            }
        }
        Map<String,List<Part>> duplicates=new LinkedHashMap<>();
        for(Part p:parts)if(p.id!=null)duplicates.computeIfAbsent(p.key(),k->new ArrayList<>()).add(p);
        for(var same:duplicates.entrySet()){
            List<Part> inferred=same.getValue().stream().filter(p->p.inferred).toList();
            if(inferred.size()<same.getValue().size()){
                // A package with a mod.json keeps its identity; what would take it isn't listed.
                for(Part p:inferred){
                    parts.remove(p);same.getValue().remove(p);
                    notes.add(new PackageInventory.Issue("COMPONENT_ID_TAKEN",PackageInventory.Severity.WARNING,p.directory,
                        "'"+p.directory.getFileName()+"' isn't listed: its ID "+p.id+" belongs to a component with a mod.json.","Rename it."));
                }
            }else if(inferred.size()>1){
                String names=String.join(", ",inferred.stream().map(p->"'"+p.directory.getFileName()+"'").toList());
                for(Part p:inferred)p.issue("DUPLICATE_COMPONENT_ID",names+" all make the component ID "+p.id+".","Rename all but one of them.");
                continue;
            }
            if(same.getValue().size()>1)
                for(Part p:same.getValue())p.issue("DUPLICATE_COMPONENT_ID","Duplicate component identity "+p.key(),"Keep one package for this parent/group/component identity.");
        }
        for(Part p:parts)if(p.id!=null&&!p.inferred){
            PackageInventory.Entry parent=parents.get(p.parent);
            if(parent==null)p.issue("ORPHAN_COMPONENT","Parent mod '"+p.parent+"' is not installed.","Install the declaring v3 parent mod in mods/.");
            else if(parent.manifestVersion!=3)p.issue("INCOMPATIBLE_COMPONENT_PARENT","Parent '"+p.parent+"' is not a v3 mod.","Install a v3 parent declaring the group.");
            else {
                Group group=parent.groups.get(p.group);
                if(group==null)p.issue("UNKNOWN_COMPONENT_GROUP","Parent '"+p.parent+"' does not declare group '"+p.group+"'.","Correct component.group or install the matching parent.");
                else {
                    if(!group.interfaceVersion.equals(p.interfaceVersion))p.issue("COMPONENT_INTERFACE_MISMATCH","Group interface version requires "+group.interfaceVersion+", component has "+p.interfaceVersion+".","Install a compatible component/parent pair.");
                    for(var requirement:group.requiredResources.entrySet()){
                        Resource r=p.resources.get(requirement.getKey());
                        if(r==null||!requirement.getValue().equals(r.kind))p.issue("MISSING_REQUIRED_RESOURCE","Group '"+p.group+"' requires resource '"+requirement.getKey()+"' of kind '"+requirement.getValue()+"'.","Add the required resource with the correct filesystem kind.");
                        else if(!Files.exists(r.path,LinkOption.NOFOLLOW_LINKS)||(Files.isDirectory(r.path,LinkOption.NOFOLLOW_LINKS)!="directory".equals(r.kind)))p.issue("INVALID_REQUIRED_RESOURCE","Invalid required resource '"+r.id+"'.","Restore the resource file/directory.");
                    }
                }
            }
        }
        return new ComponentCatalog(parts,notes);
    }
    Evaluation evaluate(String parent,Group group,List<String> ids){
        List<String> problems=new ArrayList<>();List<Part> chosen=new ArrayList<>();
        if(ids.isEmpty()&&group.minimum>0)return new Evaluation(false,true,List.of("Select "+(group.selection.equals("single")?"one":group.minimum==1?"one or more":group.minimum+" or more")+" component(s) for "+group.name+"."),List.of());
        if(group.selection.equals("single")&&ids.size()!=1)problems.add("Group '"+group.name+"' requires exactly one component.");
        else if(ids.size()<group.minimum)problems.add("Group '"+group.name+"' needs at least "+group.minimum+" components chosen.");
        Set<String> dedup=new HashSet<>();
        for(String id:ids){
            if(!dedup.add(id)){problems.add("Duplicate requested component '"+id+"'.");continue;}
            List<Part> matching=children(parent,group.id).stream().filter(p->id.equals(p.id)).toList();
            if(matching.size()!=1){problems.add("Requested component '"+id+"' is missing or duplicated for "+parent+"/"+group.id+".");continue;}
            Part p=matching.get(0);
            if(!p.usable())problems.add("Requested component '"+id+"' is invalid: "+p.issues.stream().filter(PackageInventory.Issue::blocking).map(PackageInventory.Issue::message).findFirst().orElse("unusable"));
            else chosen.add(p);
        }
        return new Evaluation(problems.isEmpty(),false,List.copyOf(problems),List.copyOf(chosen));
    }
    static List<String> tags(Map<String,Object> meta){
        if(!meta.containsKey("tags"))return List.of();
        if(!(meta.get("tags") instanceof List<?> raw))throw new IllegalArgumentException("tags must be an array");
        List<String> result=new ArrayList<>();
        for(Object entry:raw){
            if(!(entry instanceof String tag)||tag.isBlank()||tag.length()>80)throw new IllegalArgumentException("Invalid tag label");
            if(result.stream().noneMatch(t->t.equalsIgnoreCase(tag)))result.add(tag);
        }
        return List.copyOf(result);
    }
    static String ident(String s,String what){if(!s.matches(ID_PATTERN))throw new IllegalArgumentException("Invalid "+what+": "+s);return s;}
    static String required(Map<String,Object> raw,String key,String name){
        Object v=raw.get(key);
        if(!(v instanceof String s)||s.isBlank())throw new IllegalArgumentException(name+" requires "+key);
        return s;
    }
    static String optional(Map<String,Object> raw,String key,String fallback){
        Object v=raw.get(key);
        if(v==null)return fallback;
        if(!(v instanceof String s)||s.isBlank())throw new IllegalArgumentException(key+" must be a nonempty string");
        return s;
    }
}
