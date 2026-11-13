package dev.bitwigre.bml;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.*;
import java.util.zip.*;
import java.util.regex.*;

/**
 * Mixed Manifest v2/v3 Java preparation pipeline.  Windows/JVMTI launch remains outside this
 * class.  This replaces the PowerShell preparation pipeline while retaining its
 * external runtime artifact contract.  Does not modify Bitwig's stock archives.
 *
 * Experimental implementation: requires Windows 6.1.3 user runtime acceptance.
 */
public final class JavaPreparer {
    static final String LOADER_VERSION = "0.36.0-dev.14";
    private static final String TSV = "\t";
    private final Path loader, bitwigBin, modsRoot, componentsRoot, runtime, stockJar;
    private final BmlPaths paths;
    private final Map<String,String> environment;
    private final boolean verbose;
    private final long start = System.nanoTime();
    private final List<Build> builds = new ArrayList<>();
    private final Map<String,Map<String,Physical>> compatibility = new HashMap<>();
    private boolean selectiveCompatibility = false;
    private final List<Mod> mods = new ArrayList<>();
    /** Selected mods the installed Bitwig's map can't translate (or that need such a mod): mod ID -> why. */
    private final Map<String,String> unavailable = new TreeMap<>();
    private ComponentCatalog catalog=ComponentCatalog.empty();
    private Map<String,List<String>> componentChoices=Map.of();
    private final List<Hook> hooks = new ArrayList<>();
    private final Map<String,String> helperEntries = new TreeMap<>();
    private final Map<String,Map<String,String>> remapTypeCache=new HashMap<>();
    private static final Pattern TYPE_IN_DESCRIPTOR=Pattern.compile("L([^;]+);");
    private final List<String> helperRemapReport=new ArrayList<>();
    private Build runtimeBuild;
    private String stockSha;
    private Path mapRoot;
    private String mapPackId, mapPackVersion;
    private Path replacements, work, resolvedMods;
    private final List<String> properties = new ArrayList<>(), resolutions = new ArrayList<>(), resources = new ArrayList<>();
    private final List<String> patchReport = new ArrayList<>(), composition = new ArrayList<>();
    private final List<String> replacementRows = new ArrayList<>(), traceRows = new ArrayList<>();
    /** Prepared replacement classes: exact jar entry name (dnF.class) -> file under replacements/. */
    private final Map<String,Path> replacementFiles = new TreeMap<>();
    /** Bitwig classes whose access the loader opens for mods: dotted class name -> widener specs. */
    private final Map<String,Set<String>> opens = new TreeMap<>();
    /** Fields the mods add, in the active build's names. */
    private final List<AddedField> addedFields = new ArrayList<>();
    private PrepareProgress progress;

    private record Build(String key,String version,String platform,String arch,String sha) {}
    private record Physical(String kind,String clazz,String owner,String name,String descriptor) {
        String key() { return kind.equals("class") ? "class|"+clazz : kind+"|"+owner+"|"+name+"|"+descriptor; }
        String physical() { return kind.equals("class") ? clazz : owner+"."+name+descriptor; }
    }
    private record Resolved(String status,Physical source,Physical target) {}
    private record AddedField(Mod mod,String id,String target,String owner,String name,String descriptor) {}
    private record Resource(String id,String kind,String declared,Path path,String media,String persistence) {}
    private static final class Mod {
        String id,name,helperClass,sourceKey;
        Set<String> requires=Set.of();
        Path dir,manifestPath,helperJar;
        Map<String,Object> manifest;
        final Map<String,Resolved> targets = new TreeMap<>();
        final Map<String,Resource> resources = new TreeMap<>();
        final List<String> opens = new ArrayList<>();
        List<ProviderRegistrations.Entry> registrations=List.of();
        List<DiagnosticOutputs.Output> diagnosticOutputs=List.of();
        final Map<String,List<ComponentCatalog.Part>> selectedComponents = new TreeMap<>();
        final Map<String,ComponentCatalog.Group> componentGroups = new TreeMap<>();
        int hookCount,traceCount;
    }
    private static final class Hook {
        Mod mod; String id,target,transform,bridgeMethod,bridgeDescriptor,helperClass,argsCsv;
        String ownerJar,ownerDot,method,descriptor,methodFrom="",bodyTemplate="";
        String matchOpcode="",matchOwner="",matchMethod="",matchDescriptor="",matchTarget="";
        int occurrence=1,originalCall,sequence,applySequence,entryOrder;
        List<String> before = new ArrayList<>(), after = new ArrayList<>();
        String identity() {return mod.id+"::"+id;}
        String physicalKey() {return ownerDot+"|"+method+"|"+descriptor;}
        /** The method a REPLACE_INVOKE patches and the calls in it that it can match. */
        String invokeKey() {return physicalKey()+"|"+matchOpcode+"|"+matchOwner+"|"+matchMethod+"|"+matchDescriptor;}
    }

    public static void main(String[] args) {
        try {
            Map<String,String> opts = new HashMap<>();
            for (int i=0;i<args.length;i++) {
                if (args[i].equals("--help")) {
                    System.out.println("Usage: java -cp loader/app/bml-control.jar dev.bitwigre.bml.JavaPreparer --bitwig-bin <dir> [--mods-root <dir>] [--loader-root <dir>]");
                    return;
                }
                if (!args[i].startsWith("--") || i+1>=args.length) throw new IllegalArgumentException("Expected --option value, got " + args[i]);
                opts.put(args[i++],args[i]);
            }
            Path loader = opts.containsKey("--loader-root") ? Path.of(opts.get("--loader-root")) : BmlPaths.fromRuntime().loader;
            String bin = opts.getOrDefault("--bitwig-bin", defaultBin());
            Path mods = opts.containsKey("--mods-root") ? Path.of(opts.get("--mods-root")) : loader.getParent().resolve("mods");
            new JavaPreparer(loader,Path.of(bin),mods,System.getenv()).run();
        } catch (Exception ex) {
            System.err.println("PREPARE_FAILED: " + ex.getMessage());
            if ("1".equals(System.getenv("BML_DEBUG_VERBOSE"))) ex.printStackTrace(System.err);
            System.exit(1);
        }
    }
    private static String defaultBin() {
        if (mac()) return "/Applications/Bitwig Studio.app/Contents/Java";
        if (!windows()) return "/opt/bitwig-studio/bin";
        String pf = System.getenv("ProgramFiles");
        return (pf==null || pf.isBlank() ? "C:/Program Files" : pf)+"/Bitwig Studio/bin";
    }
    JavaPreparer(Path loader,Path bitwigBin,Path modsRoot,Map<String,String> env) {
        this.loader=loader.toAbsolutePath().normalize();
        this.paths=new BmlPaths(this.loader.getParent());
        this.bitwigBin=bitwigBin.toAbsolutePath().normalize();
        this.modsRoot=modsRoot.toAbsolutePath().normalize();
        this.componentsRoot=this.modsRoot.getParent().resolve("components");
        this.runtime=this.loader.resolve("mod-runtime");
        this.stockJar=this.bitwigBin.resolve("bitwig.jar");
        this.environment=env;
        this.verbose="1".equals(env.get("BML_DEBUG_VERBOSE"));
    }
    private void debug(String s) {if(verbose) System.out.println("[BML debug +"+Duration.ofNanos(System.nanoTime()-start).toMillis()+"ms] "+s);}
    private static String s(Object o) {return o==null?"":String.valueOf(o);}
    private static String str(Map<String,Object> m,String key) {return s(m.get(key));}
    private static String required(Map<String,Object> m,String key,String context) {
        Object v=m.get(key);
        if (!(v instanceof String) || ((String)v).isBlank()) throw new IllegalArgumentException(context+" requires non-empty "+key);
        return (String)v;
    }
    private static Map<String,Object> map(Object o) {return SimpleJson.object(o);}
    private static Map<String,Object> optionalMap(Object o) {return o==null?Map.of():map(o);}
    private static List<Object> list(Object o) {
        if(o==null) return List.of();
        if(o instanceof List<?> l) return new ArrayList<>(l);
        throw new IllegalArgumentException("Expected JSON array, got "+o.getClass().getSimpleName());
    }
    private static Map<String,Object> json(Path p) throws IOException {
        return map(SimpleJson.parse(Files.readString(p,StandardCharsets.UTF_8)));
    }
    private static String id(String value,String name) {
        if(!value.matches("[A-Za-z][A-Za-z0-9_.-]*")) throw new IllegalArgumentException("Invalid "+name+" '"+value+"'");
        return value;
    }
    private static String dot(String s) {return s.replace('/','.');}
    private static String keySegment(String id){return id.replace(".","%2E");}
    private static String internal(String s) {return s.replace('.','/');}
    private static String propertiesEsc(String s) {
        StringBuilder out=new StringBuilder(s.length());
        for(char c:s.toCharArray()){
            switch(c){
                case '\\'->out.append("\\\\");
                case '\t'->out.append("\\t");
                case '\r'->out.append("\\r");
                case '\n'->out.append("\\n");
                case '\f'->out.append("\\f");
                // Properties.load(InputStream) reads ISO-8859-1, so non-ASCII goes as \\uXXXX (readable either way).
                default->{if(c<0x80)out.append(c);else out.append(String.format("\\u%04x",(int)c));}
            }
        }
        return out.toString();
    }
    private static void prop(List<String> props,String key,String value) {
        if(value!=null&&!value.isBlank()) props.add(key+"="+propertiesEsc(value));
    }
    private static String sha256(Path p) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(p)) {byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1) d.update(b,0,n);}
        return HexFormat.of().formatHex(d.digest());
    }
    /** File name stem for a transformed Bitwig class. JVM names are case-sensitive but Windows
     *  file names are not (dnF and dnf are one file there), so the stem carries a short hash of
     *  the exact class name. The real name is kept in replacement-map.tsv and in jar entries. */
    private static String classFileStem(String owner) throws Exception {
        byte[] h=MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
        return owner.replaceAll("[^A-Za-z0-9_.-]","_")+"."+HexFormat.of().formatHex(h).substring(0,10);
    }
    private static void write(Path p,List<String> lines) throws IOException {
        Files.createDirectories(p.getParent());Files.write(p,lines,StandardCharsets.UTF_8);
    }
    private void run() throws Exception {
        debug("Java preparation started; Bitwig bin: "+bitwigBin+"; mods: "+modsRoot);
        Files.createDirectories(modsRoot);
        if(!Files.isRegularFile(stockJar)) throw new IOException("Missing Bitwig JAR: "+stockJar);
        progress=new PrepareProgress(loader.getParent().resolve("state"));
        progress.stage("check");
        stockSha=sha256(stockJar);
        // Whether or not anything is prepared: the mods read these files at runtime.
        SettingsFolder.ready(paths);
        ModData.seedAll(paths,modsRoot);
        // Normal launches reuse the last prepared runtime when nothing that goes into it has
        // changed. Debug launches always prepare: their bindings name the session's log folders.
        boolean cacheable=!isTracing() && !"1".equals(environment.get("BML_PREPARE_FRESH"));
        if(cacheable && reuseCachedRuntime()) return;
        // Reserve an immutable, checksum-validated mapping snapshot before any
        // target is resolved. A GUI map refresh must not change remap bytes
        // halfway through preparation.
        resetRuntime();
        progress.stage("maps");
        loadCompatibility();
        progress.stage("mods");
        discover();
        if (selectiveCompatibility) loadSelectiveCompatibility();
        writeBindings();
        progress.stage("hooks");
        flattenHooks();
        collectOpens();
        collectFields();
        composeHooks();
        // A mod counts as prepared once its code is merged and every one of its hooks is applied.
        Map<String,Integer> hooksPerMod=new TreeMap<>();
        for(Mod m:mods)hooksPerMod.put(m.id,0);
        for(Hook h:hooks)hooksPerMod.merge(h.mod.id,1,Integer::sum);
        progress.mods(hooksPerMod);
        progress.planned("helpers",(int)mods.stream().filter(m->m.helperJar!=null).count());
        progress.planned("transforms",hooks.size()+opens.size()+(int)addedFields.stream().map(AddedField::owner).distinct().count());
        progress.stage("helpers");
        mergeHelpers();
        progress.helpersMerged();
        progress.stage("transforms");
        applyTransforms();
        progress.stage("verify");
        deepVerify();
        progress.stage("linkage");
        verifyPreparedHelperLinkage();
        progress.stage("reports");
        writeTracesAndReports();
        verifyPreparedTraceTargets();
        // Written last, after discover() may have saved default component choices, so the key
        // describes the inputs as the next launch will see them.
        if(cacheable) write(runtime.resolve(CACHE_KEY_FILE),cacheKeyLines());
        progress.finish();
    }

    // ---------------------------------------------------------------- prepared-runtime cache

    private static final String CACHE_KEY_FILE="cache-key.txt";

    private boolean reuseCachedRuntime() throws Exception {
        Path keyFile=runtime.resolve(CACHE_KEY_FILE), info=runtime.resolve("build-info.txt");
        if(!Files.isRegularFile(keyFile) || !Files.isRegularFile(info)) return false;
        Map<String,String> saved=KeyValueFile.read(keyFile),now=keyValues(cacheKeyLines());
        if(!now.get("code").equals(saved.get("code"))) {
            debug("Prepared runtime is out of date; preparing again");
            return false;
        }
        boolean refreshed=!now.get("components").equals(saved.get("components"));
        if(refreshed) {
            // Only components or their selection changed. Component payloads are data that mods
            // read at runtime from mod-bindings.properties; no prepared class depends on them,
            // so only the bindings are written again.
            progress.stage("bindings");
            refreshBindings();
            write(keyFile,cacheKeyLines());
        }
        Map<String,String> b=KeyValueFile.read(info);
        System.out.println("\nPREPARE_OK loader="+LOADER_VERSION+" runtime_build="+b.get("runtime_build_key")+" Bitwig="+b.get("bitwig_version")+
            " mods="+b.get("mod_count")+" transforms="+b.get("transform_count")+" replacement_classes="+b.get("replacement_class_count")+" (cached)");
        String ids=b.getOrDefault("mod_ids","");
        System.out.println("Mods: "+(ids.isEmpty()?"none (inert launch mode)":ids.replace(",",", ")));
        System.out.println((refreshed?"Only components changed since the last preparation; their bindings were written again and the rest is reused."
            :"Nothing changed since the last preparation; reusing it.")+" Runtime: "+runtime);
        progress.cached();
        return true;
    }
    private static Map<String,String> keyValues(List<String> lines) {
        Map<String,String> out=new HashMap<>();
        for(String line:lines)out.put(line.substring(0,line.indexOf('=')),line.substring(line.indexOf('=')+1));
        return out;
    }
    /** Rewrites mod-bindings.properties and resource-resolution.tsv for the current component
     *  selection, exactly as a full preparation would, and leaves every prepared class alone. */
    private void refreshBindings() throws Exception {
        // The maps are part of the code key, so this snapshot is the same pack taken again.
        Path snapshot=runtime.resolve("map-snapshot");
        if(Files.exists(snapshot)) {
            try(Stream<Path> s=Files.walk(snapshot)) {
                for(Path p:s.sorted(Comparator.reverseOrder()).toList())Files.delete(p);
            }
        }
        loadCompatibility();
        discover();
        if (selectiveCompatibility) loadSelectiveCompatibility();
        writeBindings();
    }

    /** The cache key as two hashes. "code" covers everything the prepared classes and helper
     *  jar are made from: Bitwig, the Java running it, the loader, the map packs, the mods and
     *  the user's mod selection. "components" covers the installed components and the chosen
     *  ones, which only reach the bindings. */
    private List<String> cacheKeyLines() throws Exception {
        return List.of("code="+codeKey(),"components="+componentsKey());
    }
    private String componentsKey() throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        Consumer<String> add=s->d.update((s+"\n").getBytes(StandardCharsets.UTF_8));
        hashComponents(add);
        hashFile(add,"selected-components",paths.selectedComponentsFile);
        return HexFormat.of().formatHex(d.digest());
    }
    /** As hashTree, but a file or folder that can't be read goes in the key as unreadable: the catalog
     *  reports it, and it blocks nothing unless it is the selected component. */
    private void hashComponents(Consumer<String> add) throws Exception {
        if(!Files.isDirectory(componentsRoot)) {add.accept("components=-"); return;}
        List<Path> files=new ArrayList<>();
        Set<Path> unreadable=new HashSet<>();
        Files.walkFileTree(componentsRoot,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs) {
                if(Files.isRegularFile(file))files.add(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,IOException failure) {
                files.add(file);unreadable.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(files);
        for(Path p:files) {
            String hash;
            try{hash=unreadable.contains(p)?"unreadable":sha256(p);}catch(IOException ex){hash="unreadable";}
            add.accept("components/"+componentsRoot.relativize(p).toString().replace('\\','/')+"="+hash);
        }
    }
    private String codeKey() throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        Consumer<String> add=s->d.update((s+"\n").getBytes(StandardCharsets.UTF_8));
        add.accept("format=2");
        add.accept("loader_version="+LOADER_VERSION);
        add.accept("java="+System.getProperty("java.home")+"|"+System.getProperty("java.version"));
        add.accept("paths="+loader+"|"+bitwigBin+"|"+modsRoot+"|"+paths.configDir); // the bindings name them
        add.accept("bitwig.jar="+stockSha);
        Path libs=bitwigBin.resolve("libs.jar");
        add.accept("libs.jar="+(Files.isRegularFile(libs)?sha256(libs):"-"));
        Path root=loader.getParent();
        // Files a launch or a running session writes are not inputs: the JVMTI launcher's session
        // files, and mod resources marked "preserve" (a mod's own saved settings, e.g. Shortcut
        // Aliases' alias list), both the copy in the data folder, which is outside everything hashed
        // here, and what the mod's folder holds to start it from. Preparation records only where they are.
        Path jvmti=loader.resolve("jvmti");
        hashTree(add,"loader",loader,p->p.startsWith(runtime) || p.startsWith(jvmti.resolve("reports"))
            || p.equals(jvmti.resolve("active-session.txt")) || p.equals(jvmti.resolve("bin").resolve("session.conf")));
        hashTree(add,"maps",root.resolve("maps"),p->false);
        List<Path> preserved=preservedResources(add);
        hashTree(add,"mods",modsRoot,p->preserved.stream().anyMatch(p::startsWith));
        add.accept("selection_active="+environment.getOrDefault("BML_MOD_SELECTION_ACTIVE",""));
        String enabled=environment.get("BML_ENABLED_MODS_FILE");
        if(enabled!=null && !enabled.isBlank()) hashFile(add,"enabled-mods",Path.of(enabled));
        return HexFormat.of().formatHex(d.digest());
    }
    private static void hashTree(Consumer<String> add,String label,Path dir,java.util.function.Predicate<Path> skip) throws Exception {
        if(!Files.isDirectory(dir)) {add.accept(label+"=-"); return;}
        List<Path> files;
        try(Stream<Path> s=Files.walk(dir)) {
            files=s.filter(Files::isRegularFile).filter(p->!skip.test(p)).sorted().toList();
        }
        for(Path p:files) add.accept(label+"/"+dir.relativize(p).toString().replace('\\','/')+"="+sha256(p));
    }
    /** Each mod's "preserve" resource paths; only whether each one exists goes into the key. */
    private List<Path> preservedResources(Consumer<String> add) throws Exception {
        List<Path> out=new ArrayList<>();
        if(!Files.isDirectory(modsRoot)) return out;
        List<Path> manifests;
        try(Stream<Path> s=Files.list(modsRoot)) {
            manifests=s.map(d->d.resolve("mod.json")).filter(Files::isRegularFile).sorted().toList();
        }
        for(Path manifest:manifests) {
            Map<String,Object> resources;
            try { resources=optionalMap(json(manifest).get("resources")); }
            catch(RuntimeException ex) { continue; }  // an invalid manifest is hashed as a file and fails in preparation
            for(var e:new TreeMap<>(resources).entrySet()) {
                if(!(e.getValue() instanceof Map<?,?>)) continue;
                Map<String,Object> r=map(e.getValue());
                if(!"preserve".equals(str(r,"persistence")) || str(r,"path").isBlank()) continue;
                Path p=manifest.getParent().resolve(str(r,"path")).normalize();
                if(!p.startsWith(manifest.getParent())) continue;
                out.add(p);
                add.accept("preserve/"+modsRoot.relativize(p).toString().replace('\\','/')+"="
                    +(Files.isDirectory(p)?"dir":Files.isRegularFile(p)?"file":"-"));
            }
        }
        return out;
    }
    private static void hashFile(Consumer<String> add,String label,Path p) throws Exception {
        add.accept(label+"="+(Files.isRegularFile(p)?sha256(p):"-"));
    }
    /**
     * Preserve the report-only verification present in the tested 0.32.0
     * compiled BML binary. Its separate Java source snapshot lacked the call.
     */
    private void verifyPreparedHelperLinkage() {
        Path merged=runtime.resolve("helper.jar");
        if (!Files.isRegularFile(merged)) return;
        Path report=runtime.resolve("helper-linkage-preflight.tsv");
        try {
            Path libraries=bitwigBin.resolve("libs.jar");
            if (!Files.isRegularFile(libraries)) {
                System.err.println("WARNING: BML helper linkage preflight skipped; target libs.jar is missing");
                return;
            }
            PreparedHelperLinkageVerifier.Result result=PreparedHelperLinkageVerifier.verify(
                merged,replacementFiles,stockJar,libraries,modsRoot,report);
            debug("BML_LINKAGE_PREFLIGHT helper_classes="+result.helperClasses()+
                  " checked="+result.memberRefs()+" resolved="+result.resolved()+
                  " unresolved_sites="+result.unresolvedSites()+
                  " unresolved_unique="+result.uniqueUnresolved()+
                  " status="+(result.uniqueUnresolved()==0?"STATIC_LINKS_RESOLVED":"PARTIAL_NATIVE_LINKAGE")+
                  " report="+report);
            if (result.uniqueUnresolved()>0) {
                System.err.println("WARNING: "+result.uniqueUnresolved()+
                        " unique prepared helper references unresolved on "+runtimeBuild.key);
                for(String row:Files.readAllLines(report,StandardCharsets.UTF_8).stream().skip(1).limit(8).toList())
                    System.err.println("  "+row);
                System.err.println("Linkage preflight is REPORT ONLY in this experimental BML build.");
            }
        } catch(Exception ex) {
            System.err.println("WARNING: helper linkage preflight failed: "+ex);
            try {
                write(report,List.of("status\treason","PRECHECK_ERROR\t"+ex.toString().replace('\t',' ')));
            } catch(IOException ignored) {}
        }
    }

    private void verifyPreparedTraceTargets() {
        Path target=runtime.resolve("targets.tsv");
        Path helper=runtime.resolve("helper.jar");
        if (!Files.isRegularFile(target) || !Files.isRegularFile(helper)) return;
        try {
            Path libraries=bitwigBin.resolve("libs.jar");
            if (!Files.isRegularFile(libraries)) return;
            PreparedHelperLinkageVerifier.TraceResult result=PreparedHelperLinkageVerifier.verifyTraceTargets(
                target,helper,replacementFiles,stockJar,libraries,
                runtime.resolve("helper-trace-preflight.tsv"));
            debug("BML_TRACE_PREFLIGHT total="+result.total()+
                  " valid="+result.valid()+" invalid="+result.invalid()+
                  " report="+result.report());
            if (result.invalid()>0) {
                System.err.println("WARNING: "+result.invalid()+" invalid prepared trace target descriptors");
                for(String row:Files.readAllLines(result.report(),StandardCharsets.UTF_8).stream()
                        .skip(1).limit(8).toList())System.err.println("  "+row);
                System.err.println("Trace preflight is REPORT ONLY in this experimental BML build.");
            }
        } catch(Exception ex) {
            System.err.println("WARNING: trace preflight failed: "+ex);
        }
    }

    private void loadCompatibility() throws Exception {
        MapPackCatalog.Selected selection=MapPackCatalog.select(loader.getParent(),stockSha);
        mapRoot=selection.directory();
        if(!selection.id().equals("legacy-bundled")){
            Path snapshotRoot=runtime.resolve("map-snapshot");
            Path snapshot=snapshotRoot.resolve("maps").resolve(selection.id());
            try(Stream<Path> entries=Files.walk(mapRoot)){
                for(Path original:entries.toList()){
                    Path target=snapshot.resolve(mapRoot.relativize(original).toString());
                    if(Files.isSymbolicLink(original))throw new IOException("Symlink in selected map: "+original);
                    if(Files.isDirectory(original,LinkOption.NOFOLLOW_LINKS)){
                        Files.createDirectories(target);
                    }else{
                        Files.createDirectories(target.getParent());
                        Files.copy(original,target,StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            MapPackCatalog.Selected fixed=MapPackCatalog.select(snapshotRoot,stockSha);
            if(!fixed.id().equals(selection.id()) || !fixed.version().equals(selection.version()))
                throw new IOException("Installed map was refreshed during preparation; please retry");
            mapRoot=fixed.directory();
        }
        mapPackId=selection.id();
        mapPackVersion=selection.version();
        Map<String,Object> b=json(mapRoot.resolve("builds.json"));
        for(Object raw:list(b.get("builds"))) {
            Map<String,Object> x=map(raw);
            Build build=new Build(str(x,"build_key"),str(x,"bitwig_version"),str(x,"platform"),str(x,"architecture"),str(x,"bitwig_jar_sha256"));
            builds.add(build);
            if(build.sha.equalsIgnoreCase(stockSha)) {
                if(runtimeBuild!=null) throw new IOException("Duplicate Bitwig build SHA in builds.json: "+stockSha);
                runtimeBuild=build;
            }
        }
        if(runtimeBuild==null) throw new IOException("Unsupported bitwig.jar SHA-256: "+stockSha+" (fail-closed)");
        if(!selection.targetBuild().isBlank() && !selection.targetBuild().equals(runtimeBuild.key))
            throw new IOException("Installed map "+mapPackId+" targets "+selection.targetBuild()+
                " but builds.json identifies "+runtimeBuild.key);
        if (!Files.isRegularFile(mapRoot.resolve("compatibility-v2.json"))) {
            selectiveCompatibility=true;
            debug("Recognized Bitwig "+runtimeBuild.version+" ("+runtimeBuild.key+"); map "+
                    mapPackId+" version "+mapPackVersion+"; selective remap v2 enabled");
            return;
        }
        Map<String,Object> compat=json(mapRoot.resolve("compatibility-v2.json"));
        int groups=0;
        for(Object raw:list(compat.get("groups"))) {
            Map<String,Object> variants=map(map(raw).get("variants"));
            Map<String,Physical> group=new TreeMap<>();
            for(var entry:variants.entrySet()) group.put(entry.getKey(),parsePhysical(map(entry.getValue())));
            for(var entry:group.entrySet()) {
                String key=entry.getKey()+"|"+entry.getValue().key();
                if(compatibility.putIfAbsent(key,group)!=null) throw new IOException("Ambiguous physical compatibility identity: "+key);
            }
            groups++;
        }
        debug("Recognized Bitwig "+runtimeBuild.version+" ("+runtimeBuild.key+"); map "+
             mapPackId+" version "+mapPackVersion+"; "+groups+" equivalence groups");
    }
    /**
     * v2 packs carry the complete physical remap, but only identities actually
     * declared by enabled mods are retained. A single gzip scan per source
     * build resolves both declared members and inherited lookup-owner aliases.
     * Existing BML target and helper verification remain unchanged.
     */
    private void loadSelectiveCompatibility() throws IOException {
        Map<String,Set<String>> wanted=new TreeMap<>();
        for (Mod mod:mods) {
            if (mod.sourceKey.equals(runtimeBuild.key)) continue;
            Set<String> keys=wanted.computeIfAbsent(mod.sourceKey,k->new HashSet<>());
            for (Resolved r:mod.targets.values()) keys.add(r.source().key());
        }
        long start=System.nanoTime();
        long rows=0;
        for (var request:wanted.entrySet()) {
            String sourceKey=request.getKey();
            Path file=mapRoot.resolve("remap/"+sourceKey+"__"+runtimeBuild.key+".tsv.gz");
            if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                debug("Selective remap unavailable for "+sourceKey+" -> "+runtimeBuild.key+"; declarations stay UNMAPPED");
                continue;
            }
            Set<String> keys=request.getValue();
            int matched=0;
            try (BufferedReader reader=new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(file)),StandardCharsets.UTF_8))) {
                String line;
                while ((line=reader.readLine())!=null) {
                    rows++;
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] bits=line.split("\t",-1);
                    if (bits[0].equals("H")) {
                        if (bits.length!=3) throw new IOException("Invalid hierarchy remap row "+rows);
                        continue;
                    }
                    String sourceIdentity;
                    Physical destination;
                    if (bits[0].equals("C")) {
                        if (bits.length!=3) throw new IOException("Invalid class remap row "+rows);
                        sourceIdentity="class|"+bits[1];
                        destination=new Physical("class",bits[2],"","","");
                    } else if (bits[0].equals("M") || bits[0].equals("F")) {
                        if (bits.length!=7) throw new IOException("Invalid member remap row "+rows);
                        String kind=bits[0].equals("M")?"method":"field";
                        sourceIdentity=kind+"|"+bits[1]+"|"+bits[2]+"|"+bits[3];
                        destination=new Physical(kind,"",bits[4],bits[5],bits[6]);
                    } else throw new IOException("Unexpected remap row kind "+bits[0]+" at "+rows);
                    if (!keys.contains(sourceIdentity)) continue;
                    String identity=sourceKey+"|"+sourceIdentity;
                    Map<String,Physical> group=Map.of(runtimeBuild.key,destination);
                    Map<String,Physical> existing=compatibility.putIfAbsent(identity,group);
                    if (existing!=null && !existing.get(runtimeBuild.key).equals(destination))
                        throw new IOException("Ambiguous requested physical identity "+identity);
                    if (existing==null) matched++;
                }
            }
            debug("SELECTIVE_REMAP source="+sourceKey+" requested="+keys.size()+
                  " resolved="+matched+" missing="+(keys.size()-matched)+
                  " rows_scanned="+rows);
        }
        for (Mod mod:mods) {
            int unmapped=0;
            for (var entry:mod.targets.entrySet()) {
                Resolved corrected=resolve(entry.getValue().source(),mod.sourceKey);
                entry.setValue(corrected);
                if (corrected.target()==null) unmapped++;
            }
            if (unmapped>0) {
                List<String> reads=missingHelperBindingReads(mod);
                if (!reads.isEmpty())
                    throw new IOException("Unmapped runtime helper target(s) for "+mod.id+
                        " on "+runtimeBuild.key+": "+String.join(", ",reads)+
                        ". These are read through BML_MOD_CONFIG in helper bytecode; map them or revise the helper before launch.");
                System.err.println("WARNING: "+mod.id+" has "+unmapped+
                    " unmapped declared target(s) for "+runtimeBuild.key+"; see target-resolution.tsv. "+
                    "They are not bound; any hook, trace or detected helper binding read using one will fail preparation.");
            }
        }
        debug("SELECTIVE_REMAP_DONE requested_source_builds="+wanted.size()+
              " resolved_identifiers="+compatibility.size()+
              " elapsed_ms="+((System.nanoTime()-start)/1_000_000));
    }
    private static Physical parsePhysical(Map<String,Object> raw) {
        String kind=str(raw,"kind").toLowerCase(Locale.ROOT);
        if(kind.equals("class")) return new Physical(kind,internal(required(raw,"class","class target")),"","","");
        if(kind.equals("field")||kind.equals("method"))
            return new Physical(kind,"",internal(required(raw,"owner",kind+" target")),required(raw,"name",kind+" target"),required(raw,"descriptor",kind+" target"));
        throw new IllegalArgumentException("Unsupported physical target kind: "+kind);
    }
    private Resolved resolve(Physical source,String sourceKey) {
        if(sourceKey.equals(runtimeBuild.key))return new Resolved("EXACT",source,source);
        Map<String,Physical> eq=compatibility.get(sourceKey+"|"+source.key());
        // Not every manifest declaration is used as a transformation or trace target.
        // Preserve missing *declarations* as explicit UNMAPPED diagnostics, but never
        // publish the old JVM identity as a runtime binding. Required consumers
        // (hooks, invoke matches, ADD_METHOD, traces) still fail closed at target().
        if(eq==null || eq.get(runtimeBuild.key)==null) return new Resolved("UNMAPPED",source,null);
        return new Resolved("MAPPED",source,eq.get(runtimeBuild.key));
    }
    private void discover() throws Exception {
        boolean selectionActive="1".equals(environment.get("BML_MOD_SELECTION_ACTIVE"));
        Set<String> selected = new HashSet<>();
        if(selectionActive) {
            String file=environment.get("BML_ENABLED_MODS_FILE");
            if(file==null||file.isBlank()) throw new IOException("BML_MOD_SELECTION_ACTIVE=1 but BML_ENABLED_MODS_FILE is not set");
            for(String line:Files.readAllLines(Path.of(file),StandardCharsets.UTF_8)) {
                String sid=line.trim(); if(sid.isEmpty()||sid.startsWith("#"))continue;
                if(!selected.add(sid))throw new IOException("Duplicate mod ID in enabled selection: "+sid);
            }
        }
        // Component selections belong to user state, never to a particular
        // component folder. Do not silently substitute another installed item.
        ComponentSelectionStore componentStore=new ComponentSelectionStore(paths);
        componentChoices=new LinkedHashMap<>(componentStore.load());
        PackageInventory.Snapshot snapshot=PackageInventory.inspect(modsRoot,componentsRoot,componentChoices);
        // Apply the same first-usable default as the GUI, even for CLI loading.
        // Nonempty saved requests are never silently replaced.
        if(ComponentSelectionStore.seedUnconfiguredSingles(snapshot,componentChoices)){
            componentStore.save(componentChoices);
            snapshot=PackageInventory.inspect(modsRoot,componentsRoot,componentChoices);
        }
        catalog=snapshot.components();
        // An invalid unselected package is reported in the GUI and isolated.
        Map<Path,PackageInventory.Entry> inventoryByPath = new HashMap<>();
        for(PackageInventory.Entry entry:snapshot.entries())inventoryByPath.put(entry.directory,entry);
        Set<String> installed=new HashSet<>();
        List<Path> dirs;
        try(Stream<Path> s=Files.list(modsRoot)) {
            dirs=s.filter(Files::isDirectory).sorted(Comparator.comparing(p->p.getFileName().toString())).toList();
        }
        for(Path dir:dirs) {
            Path manifestPath=dir.resolve("mod.json");if(!Files.isRegularFile(manifestPath))continue;
            PackageInventory.Entry entry=inventoryByPath.get(dir.toAbsolutePath().normalize());
            if(entry==null)continue;
            if(selectionActive&&entry.id!=null)installed.add(entry.id);
            if(selectionActive && (entry.id==null||!selected.contains(entry.id)))continue;
            if(!entry.loadable())throw new IOException("Selected mod '"+entry.key()+"' cannot prepare: "+entry.issueSummary()+" ["+entry.directory+"]");
            Map<String,Object> manifest=json(manifestPath);
            if(!(manifest.get("manifest_version") instanceof Number n)|| (n.doubleValue()!=2.0&&n.doubleValue()!=3.0))
                throw new IllegalArgumentException("Unsupported manifest in "+manifestPath+"; expected version 2 or 3");
            if(n.intValue()==3 && !"mod".equals(manifest.get("package_type")))
                throw new IOException("Only v3 parent mods belong in mods/: "+manifestPath);
            Map<String,Object> metadata=map(manifest.get("mod"));
            Mod mod=new Mod();mod.id=id(required(metadata,"id","mod"),"mod.id");
            for(String f:List.of("name","version","author","description"))required(metadata,f,"mod "+mod.id);
            mod.name=required(metadata,"name","mod "+mod.id);
            mod.requires=Prerequisites.parse(manifest,mod.id).keySet();
            if(!selectionActive && !installed.add(mod.id))throw new IOException("Duplicate mod.id: "+mod.id);
            if(selectionActive) installed.add(mod.id);
            if(selectionActive&&!selected.contains(mod.id))continue;
            Map<String,Object> built=map(manifest.get("built_against"));
            String version=required(built,"bitwig_version",mod.id+" built_against");
            String platform=required(built,"platform",mod.id+" built_against");
            String arch=required(built,"architecture",mod.id+" built_against");
            List<Build> matching=builds.stream().filter(x->x.version.equals(version)&&x.platform.equalsIgnoreCase(platform)&&x.arch.equalsIgnoreCase(arch)).toList();
            if(matching.size()>1)throw new IOException("Ambiguous source build for "+mod.id+": "+version+"/"+platform+"/"+arch);
            // Standard 13: a mod isn't available on a Bitwig version the map can't translate it to.
            // Selecting it is an error, reported with the others after discovery.
            String gap=matching.isEmpty()?"made for Bitwig "+version+" "+platform+" "+arch+", which the map for Bitwig "+runtimeBuild.version+" doesn't list"
                :unavailableReason(matching.get(0));
            if(gap!=null) {
                unavailable.put(mod.id,gap);
                continue;
            }
            mod.sourceKey=matching.get(0).key;
            mod.dir=dir.toAbsolutePath().normalize();mod.manifestPath=manifestPath;mod.manifest=manifest;
            if(n.intValue()==3){
                mod.diagnosticOutputs=DiagnosticOutputs.parse(manifest);
                mod.componentGroups.putAll(ComponentCatalog.parseGroups(manifest));
                for(ComponentCatalog.Group group:mod.componentGroups.values()){
                    List<String> chosen=ComponentSelectionStore.chosen(componentChoices,mod.id,group.id());
                    ComponentCatalog.Evaluation evaluation=catalog.evaluate(mod.id,group,chosen);
                    if(!evaluation.ready())throw new IOException("Cannot prepare v3 mod "+mod.id+" / "+group.id()+": "+String.join("; ",evaluation.problems()));
                    mod.selectedComponents.put(group.id(),evaluation.selected());
                }
            }
            mod.hookCount=list(manifest.get("hooks")).size();
            mod.traceCount=list(manifest.get("trace_targets")).size();
            mod.registrations=ProviderRegistrations.parse(manifest,mod.dir);
            if(mod.hookCount==0&&mod.traceCount==0&&mod.registrations.isEmpty())
                throw new IllegalArgumentException("Mod "+mod.id+" declares neither hooks, trace targets nor provider registrations");
            if(mod.hookCount>0) {
                Map<String,Object> helper=map(manifest.get("helper"));
                mod.helperClass=required(helper,"class",mod.id+" helper");
                String jar=required(helper,"jar",mod.id+" helper");
                mod.helperJar=resolveSafe(mod.dir,jar,"helper",true,false);
            }else if(mod.traceCount>0&&mod.registrations.isEmpty()&&!isTracing()) {
                System.err.println("WARNING: trace-only mod "+mod.id+" is inactive when Debug Mode is off");
            }
            // A framework runs the code a registration names, so that jar is merged and translated
            // like hook code: one code jar per mod, whether it has hooks, registrations or both.
            Set<Path> registrationJars=new TreeSet<>();
            for(ProviderRegistrations.Entry reg:mod.registrations)registrationJars.add(reg.jar().toAbsolutePath().normalize());
            if(!registrationJars.isEmpty()) {
                if(mod.helperJar==null&&registrationJars.size()==1)mod.helperJar=registrationJars.iterator().next();
                else if(registrationJars.size()>1||!registrationJars.contains(mod.helperJar.toAbsolutePath().normalize()))
                    throw new IOException(mod.id+": registrations must name the mod's one code jar"+(mod.helperJar==null?"":" ("+mod.dir.relativize(mod.helperJar)+")")+", not "+registrationJars);
            }
            int unmappedDeclarations=0;
            for(var ent:optionalMap(manifest.get("targets")).entrySet()) {
                String local=id(ent.getKey(),"target ID");
                Physical source=parsePhysical(map(ent.getValue()));
                Resolved candidate=selectiveCompatibility ? new Resolved("PENDING",source,null) : resolve(source,mod.sourceKey);
                mod.targets.put(local,candidate);
                if(candidate.target()==null)unmappedDeclarations++;
            }
            if(!selectiveCompatibility && unmappedDeclarations>0) {
                // A declaration can be unused by hooks yet essential to a helper's
                // *reflective* code. These generated BML_MOD_CONFIG keys are read
                // at runtime rather than appearing as JVM method references.
                // Candidate 16 showed why treating every such declaration as optional
                // can produce a misleading PREPARE_OK and an invisible feature.
                List<String> runtimeKeys=missingHelperBindingReads(mod);
                if(!runtimeKeys.isEmpty())throw new IOException("Unmapped runtime helper target(s) for "+mod.id+
                    " on "+runtimeBuild.key+": "+String.join(", ",runtimeKeys)+
                    ". These are read through BML_MOD_CONFIG in helper bytecode; map them or revise the helper before launch.");
                System.err.println("WARNING: "+mod.id+" has "+unmappedDeclarations+
                    " unmapped declared target(s) for "+runtimeBuild.key+"; see target-resolution.tsv. " +
                    "They are not bound; any hook, trace or detected helper binding read using one will fail preparation.");
            }
            for(Object o:list(manifest.get("opens"))) {
                String local=id(s(o),"opens entry");
                if(!mod.targets.containsKey(local))throw new IOException(mod.id+" opens undeclared target '"+local+"'");
                mod.opens.add(local);
            }
            Set<String> preserveNames=new HashSet<>();
            for(var ent:optionalMap(manifest.get("resources")).entrySet()) {
                String local=id(ent.getKey(),"resource ID");
                Map<String,Object> raw=map(ent.getValue());
                String kind=required(raw,"kind",mod.id+" resource "+local);
                if(!Set.of("file","directory","bitwig").contains(kind))throw new IOException("Unsupported resource kind: "+kind);
                String path=required(raw,"path",mod.id+" resource "+local);
                if(kind.equals("bitwig")) {
                    // One of Bitwig's own files, by its path in the installed Bitwig's resources folder
                    // (e.g. an icon the mod uses): referenced, never copied into the mod.
                    if(!str(raw,"persistence").isEmpty())throw new IOException(mod.id+" resource "+local+": a bitwig resource has no persistence");
                    // (macOS: the app bundle's Contents/Resources, beside Contents/Java)
                    Path bitwigResources=bitwigBin.getParent().resolve(mac()?"Resources":"resources");
                    Path resolved=resolveSafe(bitwigResources,path,"Bitwig resource "+mod.id+"/"+local,true,false);
                    mod.resources.put(local,new Resource(local,kind,path,resolved,str(raw,"media_type"),"replaceable"));
                    continue;
                }
                if("created".equals(str(raw,"persistence"))) {
                    // A file or folder the mod writes itself in its data folder: nothing shipped, and it
                    // may not exist yet. A folder is made empty here, so the mod can write into it.
                    ModData.checkCreatedName(mod.id,local,path);
                    ModData.checkName(mod.id,local,path,preserveNames);
                    Path resolved=ModData.resource(paths,mod.id,path);
                    if(kind.equals("directory"))Files.createDirectories(resolved);
                    if(Files.exists(resolved,LinkOption.NOFOLLOW_LINKS)&&kind.equals("directory")!=Files.isDirectory(resolved,LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("resource "+mod.id+"/"+local+" has wrong file/directory shape: "+resolved);
                    mod.resources.put(local,new Resource(local,kind,path,resolved,str(raw,"media_type"),"created"));
                    continue;
                }
                boolean optionalDocument=local.equals(str(metadata,"documentation"));
                Path resolved=resolveSafe(mod.dir,path,"resource "+mod.id+"/"+local,!optionalDocument,kind.equals("directory"));
                if(optionalDocument && !Files.isRegularFile(resolved,LinkOption.NOFOLLOW_LINKS)) {
                    System.err.println("WARNING: Optional Markdown documentation missing or wrong shape for "+mod.id+": "+path);
                    continue; // No binding for absent optional documentation.
                }
                String persistence=str(raw,"persistence");if(persistence.isEmpty())persistence="replaceable";
                if(!Set.of("replaceable","preserve").contains(persistence))throw new IOException("Unsupported persistence: "+persistence);
                if(persistence.equals("preserve")) {
                    // The mod's copy in the install's data folder (ModData), set up at the start of this run.
                    ModData.checkName(mod.id,local,path,preserveNames);
                    resolved=ModData.resource(paths,mod.id,path);
                    if(!Files.exists(resolved))throw new IOException("resource "+mod.id+"/"+local+" missing: "+resolved);
                    if(kind.equals("directory")!=Files.isDirectory(resolved))throw new IOException("resource "+mod.id+"/"+local+" has wrong file/directory shape: "+resolved);
                }
                mod.resources.put(local,new Resource(local,kind,path,resolved,str(raw,"media_type"),persistence));
            }
            mods.add(mod);
        }
        // Standard 13: nor is a mod that needs something its remap can't translate (such as one platform's
        // own code); the window asks the same RemapCheck.
        if(selectiveCompatibility){
            Map<String,Map<String,Map<String,String>>> bySource=new TreeMap<>();
            for(Mod m:mods)if(!m.sourceKey.equals(runtimeBuild.key))
                bySource.computeIfAbsent(m.sourceKey,k->new TreeMap<>()).put(m.id,RemapCheck.needs(m.dir,m.manifest));
            String runtime="Bitwig "+runtimeBuild.version+" for "+RemapCheck.platformName(runtimeBuild.platform);
            for(var source:bySource.entrySet()){
                Build b=builds.stream().filter(x->x.key.equals(source.getKey())).findFirst().orElseThrow();
                for(var gap:RemapCheck.gaps(mapRoot.resolve("remap/"+b.key+"__"+runtimeBuild.key+".tsv.gz"),source.getValue(),runtime).entrySet())
                    unavailable.put(gap.getKey(),"made for Bitwig "+b.version+" on "+RemapCheck.platformName(b.platform)+", and "+gap.getValue());
            }
        }
        // Every selected mod must load: one that isn't installed, isn't available on this version or
        // needs a mod that is turned off is an error, and preparation refuses until it is fixed (one
        // whose prerequisite isn't installed or is too old was refused above, as a blocking issue).
        Map<String,String> names=new HashMap<>();
        for(PackageInventory.Entry e:snapshot.entries())if(e.id!=null)names.putIfAbsent(e.id,e.name);
        Function<String,String> label=id->names.getOrDefault(id,id)+" ("+id+")";
        List<String> errors=new ArrayList<>();
        if(selectionActive)for(String sid:new TreeSet<>(selected))if(!installed.contains(sid))
            errors.add("Selected mod not installed: "+sid+". Install it, or take it out of the selection.");
        // A mod that needs one not available on this version isn't available either (Standard 13).
        for(boolean changed=true;changed;){
            changed=false;
            for(Mod m:mods)if(!unavailable.containsKey(m.id))
                for(String p:m.requires)if(unavailable.containsKey(p)){
                    unavailable.put(m.id,"needs "+label.apply(p)+", which isn't available on this version");
                    changed=true;break;
                }
        }
        mods.removeIf(m->unavailable.containsKey(m.id));
        for(var ent:unavailable.entrySet())errors.add(label.apply(ent.getKey())+" isn't available on Bitwig "+runtimeBuild.version+
            " ("+ent.getValue()+"). Untick "+names.getOrDefault(ent.getKey(),ent.getKey())+".");
        Map<String,Set<String>> needs=new TreeMap<>();
        for(Mod m:mods)needs.put(m.id,m.requires);
        for(var ent:Prerequisites.turnedOff(needs).entrySet())for(String p:ent.getValue())
            errors.add(label.apply(ent.getKey())+" needs "+label.apply(p)+", which is turned off. Tick "+names.getOrDefault(p,p)+", or untick "+names.getOrDefault(ent.getKey(),ent.getKey())+".");
        if(!errors.isEmpty())throw new IOException("Selected mods can't load:\n  "+String.join("\n  ",errors));
        mods.sort(Comparator.comparing(m->m.id));
        debug("Active mods: "+mods.stream().map(m->m.id).toList());
    }
    /** Why a mod made for {@code source} can't be prepared on the installed Bitwig, or null when it can:
     *  a v2 map translates only from the builds it holds a remap for. */
    private String unavailableReason(Build source) {
        if(source.key.equals(runtimeBuild.key)||!selectiveCompatibility)return null;
        Path remap=mapRoot.resolve("remap/"+source.key+"__"+runtimeBuild.key+".tsv.gz");
        if(Files.isRegularFile(remap,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(remap))return null;
        return "made for Bitwig "+source.version+" on "+RemapCheck.platformName(source.platform)+", and the map for Bitwig "+runtimeBuild.version+" ("+mapPackId+" "+mapPackVersion+") has no remap from it";
    }
    /** Detect required BML_MOD_CONFIG reflection lookups without executing mod code.
     * A helper explicitly referring to `<local-target>.name` (or `.field`,
     * `.class`, `.owner`, `.descriptor`, `.kind`) cannot work if that target was
     * UNMAPPED. This is deliberately conservative: a bytecode reference is a
     * dependency signal, not proof the code path will run in every session.
     * Unrecognized hand-written reflective class/field strings remain outside
     * this scan and require runtime work; we never infer physical identities.
     */
    private static List<String> missingHelperBindingReads(Mod mod) throws IOException {
        if(mod.helperJar==null)return List.of();
        ArrayList<String> missing=new ArrayList<>();
        for(var entry:mod.targets.entrySet())if(entry.getValue().target()==null)missing.add(entry.getKey());
        if(missing.isEmpty())return List.of();
        ArrayList<String> reads=new ArrayList<>();
        // Read Java classfile UTF8 constants directly; no need for a classloader
        // (which could initialize the proprietary application or helper).
        try(ZipFile jar=new ZipFile(mod.helperJar.toFile())) {
            Enumeration<? extends ZipEntry> entries=jar.entries();
            while(entries.hasMoreElements()) {
                ZipEntry item=entries.nextElement();
                if(item.isDirectory()||!item.getName().endsWith(".class"))continue;
                byte[] bytes;
                try(InputStream in=jar.getInputStream(item)){bytes=in.readAllBytes();}
                if(bytes.length>8_000_000)throw new IOException("Oversized helper class: "+item.getName());
                // Latin-1 preserves raw ASCII bytes inside modified UTF8
                // for the property names we are looking for.
                String constants=new String(bytes,StandardCharsets.ISO_8859_1);
                for(String local:missing) {
                    if(reads.contains(local))continue;
                    for(String suffix:List.of(".name",".field",".class",".owner",".descriptor",".kind")) {
                        if(constants.contains(local+suffix)) {reads.add(local);break;}
                    }
                }
            }
        }
        Collections.sort(reads);
        return reads;
    }

    private boolean isTracing() {return Set.of("1","true").contains(environment.getOrDefault("BML_TRACE_ENABLED","").toLowerCase(Locale.ROOT));}
    private static Path resolveSafe(Path root,String declared,String name,boolean mustExist,boolean directory) throws IOException {
        Path relative=Path.of(declared);
        if(relative.isAbsolute())throw new IOException(name+" must be package-relative: "+declared);
        Path base=root.toAbsolutePath().normalize();
        Path candidate=base.resolve(relative).normalize();
        if(!candidate.startsWith(base)||candidate.equals(base))throw new IOException(name+" escapes package root: "+declared);
        for(Path p=candidate;p!=null&&p.startsWith(base);p=p.getParent()) {
            if(Files.isSymbolicLink(p))throw new IOException(name+" uses a symlink: "+declared);
            try {
                // NTFS junctions/reparse points are often exposed as "other" even
                // where they are not reported as ordinary symbolic links.
                BasicFileAttributes attrs=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(attrs.isOther())throw new IOException(name+" traverses a special/reparse path: "+declared);
            }catch(NoSuchFileException ignored) {}
        }
        if(mustExist&&!Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))throw new IOException(name+" missing: "+candidate);
        if(mustExist&&(directory!=Files.isDirectory(candidate,LinkOption.NOFOLLOW_LINKS)))throw new IOException(name+" has wrong file/directory shape: "+declared);
        return candidate;
    }
    private void resetRuntime() throws IOException {
        if(Files.exists(runtime)) {
            try(Stream<Path> s=Files.walk(runtime)) {
                for(Path p:s.sorted(Comparator.reverseOrder()).toList())Files.delete(p);
            }
        }
        replacements=Files.createDirectories(runtime.resolve("replacements"));
        work=Files.createDirectories(runtime.resolve("work"));
        resolvedMods=Files.createDirectories(runtime.resolve("resolved-mods"));
    }
    private void writeBindings() throws IOException {
        prop(properties,"loader.runtime.build_key",runtimeBuild.key);
        prop(properties,"loader.runtime.bitwig_version",runtimeBuild.version);
        prop(properties,"loader.runtime.platform",runtimeBuild.platform);
        prop(properties,"loader.runtime.architecture",runtimeBuild.arch);
        prop(properties,"loader.mod_ids",String.join(",",mods.stream().map(m->m.id).toList()));
        prop(properties,"loader.components.path",componentsRoot.toAbsolutePath().normalize().toString().replace('\\','/'));
        if(isTracing()){
            Set<String> lower=new HashSet<>();
            for(Mod m:mods)if(!lower.add(m.id.toLowerCase(Locale.ROOT)))
                throw new IOException("Case-insensitive Debug mod output folder collision for "+m.id);
        }
        resolutions.add("mod_id\tlocal_target\tbuilt_against\tstatus\tkind\tsource_physical\truntime_build\truntime_physical");
        resources.add("mod_id\tresource_id\tkind\tpersistence\tdeclared_path\tresolved_path\turi\tmedia_type");
        for(Mod m:mods) {
            prop(properties,"mod."+m.id+".name",m.name);
            prop(properties,"mod."+m.id+".data.path",paths.modData(m.id).toString().replace('\\','/'));
            for(ProviderRegistrations.Entry reg:m.registrations){
                String pre="mod."+m.id+".registration."+reg.id();
                prop(properties,pre+".consumer",reg.consumer());
                prop(properties,pre+".descriptor",reg.descriptor().toString().replace('\\','/'));
                prop(properties,pre+".jar",reg.jar().toString().replace('\\','/'));
            }
            for(var ent:m.targets.entrySet()) {
                String id=ent.getKey(); Resolved r=ent.getValue();Physical t=r.target();
                String pre="mod."+m.id+".target."+id;
                if(t==null) {
                    // Never write source-version identities as active-runtime physical bindings.
                    prop(properties,pre+".status","UNMAPPED");
                    resolutions.add(String.join(TSV,m.id,id,m.sourceKey,"UNMAPPED",
                        r.source().kind(),r.source().physical(),runtimeBuild.key,""));
                    continue;
                }
                prop(properties,pre+".kind",t.kind);
                if(t.kind.equals("class"))prop(properties,pre+".class",dot(t.clazz));
                else {
                    prop(properties,pre+".owner",dot(t.owner));
                    prop(properties,pre+".name",t.name);
                    prop(properties,pre+".descriptor",t.descriptor);
                }
                prop(properties,pre+".status",r.status);
                resolutions.add(String.join(TSV,m.id,id,m.sourceKey,r.status,t.kind,r.source.physical(),runtimeBuild.key,t.physical()));
            }
            // Debug output bindings are session-owned and never emitted in Normal mode.
            if(isTracing()){
                String rootEnv=environment.get("BML_SESSION_ROOT");
                String idEnv=environment.get("BML_SESSION_ID");
                if(rootEnv!=null&&!rootEnv.isBlank()&&idEnv!=null&&!idEnv.isBlank()){
                    Path sessionRoot=Path.of(rootEnv).toAbsolutePath().normalize();
                    if(!sessionRoot.getFileName().toString().equals(idEnv)
                            || !sessionRoot.equals(loader.getParent().resolve("sessions").resolve(idEnv).toAbsolutePath().normalize())
                            || !Files.isDirectory(sessionRoot)
                            || Files.isSymbolicLink(sessionRoot))
                        throw new IOException("Invalid BML Debug session root for "+m.id);
                    Path modLogBase=sessionRoot.resolve("mod-logs");
                    Files.createDirectories(modLogBase);
                    // Case-only IDs may coexist on Unix but collide on Windows.
                    Path modRoot=modLogBase.resolve("mod-"+m.id);
                    if(Files.isSymbolicLink(modRoot))throw new IOException("Unsafe mod diagnostic root: "+modRoot);
                    Files.createDirectories(modRoot);
                    String pre="mod."+keySegment(m.id)+".diagnostics";
                    prop(properties,pre+".enabled","true");
                    prop(properties,pre+".root.path",modRoot.toString().replace('\\','/'));
                    prop(properties,pre+".root.uri",modRoot.toUri().toASCIIString());
                    for(DiagnosticOutputs.Output d:m.diagnosticOutputs){
                        Path target=DiagnosticOutputs.resolve(modRoot,d);
                        String outPre=pre+".output."+keySegment(d.id());
                        prop(properties,outPre+".kind",d.kind());
                        prop(properties,outPre+".path",target.toString().replace('\\','/'));
                        prop(properties,outPre+".uri",target.toUri().toASCIIString());
                    }
                }
            }
            for(var ent:m.resources.entrySet()) {
                Resource r=ent.getValue();
                String pre="mod."+m.id+".resource."+r.id;
                String path=r.path.toString().replace('\\','/');
                prop(properties,pre+".kind",r.kind);
                prop(properties,pre+".path",path);
                prop(properties,pre+".uri",r.path.toUri().toASCIIString());
                prop(properties,pre+".persistence",r.persistence);
                prop(properties,pre+".media_type",r.media);
                if(r.kind.equals("bitwig"))prop(properties,pre+".bitwig_path",r.declared.replace('\\','/'));
                resources.add(String.join(TSV,m.id,r.id,r.kind,r.persistence,r.declared,path,r.path.toUri().toASCIIString(),r.media));
            }
            for(var groupEntry:m.componentGroups.entrySet()){
                String groupId=groupEntry.getKey();
                ComponentCatalog.Group group=groupEntry.getValue();
                List<ComponentCatalog.Part> selected=m.selectedComponents.get(groupId);
                // Encode dots in IDs so property segment delimiters are unique.
                String prefix="component."+keySegment(m.id)+".group."+keySegment(groupId);
                prop(properties,prefix+".selection",group.selection());
                prop(properties,prefix+".interface_version",group.interfaceVersion());
                prop(properties,prefix+".selected_count",Integer.toString(selected.size()));
                for(int index=0;index<selected.size();index++){
                    ComponentCatalog.Part part=selected.get(index);
                    String child=prefix+".selected."+index;
                    prop(properties,child+".id",part.id);
                    prop(properties,child+".version",part.version);
                    prop(properties,child+".author",part.author);
                    for(var res:part.resources.entrySet()){
                        ComponentCatalog.Resource r=res.getValue();
                        if(!Files.exists(r.path(),LinkOption.NOFOLLOW_LINKS))continue; // optional missing Markdown is not a binding
                        String rp=child+".resource."+keySegment(r.id());
                        prop(properties,rp+".kind",r.kind());
                        prop(properties,rp+".path",r.path().toString().replace('\\','/'));
                        prop(properties,rp+".uri",r.path().toUri().toASCIIString());
                        prop(properties,rp+".persistence",r.persistence());
                        prop(properties,rp+".media_type",r.mediaType());
                        resources.add(String.join(TSV,m.id,groupId+"/"+part.id+"/"+r.id(),r.kind(),r.persistence(),r.declared(),r.path().toString().replace('\\','/'),r.path().toUri().toASCIIString(),r.mediaType()));
                    }
                }
            }
        }
        write(runtime.resolve("mod-bindings.properties"),properties);
        write(runtime.resolve("target-resolution.tsv"),resolutions);
        write(runtime.resolve("resource-resolution.tsv"),resources);
        debug("Validated "+(resolutions.size()-1)+" targets and "+(resources.size()-1)+" resources");
    }
    private static Resolved target(Mod m,String local,String context) {
        if(local==null||local.isBlank()||!m.targets.containsKey(local))
            throw new IllegalArgumentException(context+" refers to undeclared target '"+local+"' in "+m.id);
        Resolved resolved=m.targets.get(local);
        if(resolved.target()==null)
            throw new IllegalArgumentException("Cannot translate required target '"+local+"' ("+
                resolved.source().physical()+") in "+m.id+" from "+m.sourceKey+
                " to active "+ "runtime build; hook/trace references must be mapped");
        return resolved;
    }
    /** Locate a bounded, explicit physical-equivalence map for this source/runtime pair. */
    private Path helperRemapMap(Mod m) {
        return mapRoot.resolve("remap/"+m.sourceKey+"__"+runtimeBuild.key+".tsv.gz");
    }
    private Map<String,String> helperTypeMap(Mod m) throws IOException {
        if(m.sourceKey.equals(runtimeBuild.key))return Map.of();
        Map<String,String> cached=remapTypeCache.get(m.sourceKey);
        if(cached!=null)return cached;
        Path mapping=helperRemapMap(m);
        if(!Files.isRegularFile(mapping)) {
            debug("No helper remap table for "+m.sourceKey+" -> "+runtimeBuild.key+"; leaving original helper bytecode");
            return Map.of();
        }
        Map<String,String> table=new HashMap<>();
        try(BufferedReader input=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(mapping)),StandardCharsets.UTF_8))) {
            String line;
            while((line=input.readLine())!=null) {
                if(!line.startsWith("C\t"))continue;
                String[] parts=line.split("\t",-1);
                if(parts.length!=3)throw new IOException("Invalid helper class mapping: "+line);
                String original=table.putIfAbsent(parts[1],parts[2]);
                if(original!=null&&!original.equals(parts[2]))throw new IOException("Conflicting class map for "+parts[1]);
            }
        }
        remapTypeCache.put(m.sourceKey,table);
        return table;
    }
    private String remapBridgeDescriptor(Mod m,String descriptor) throws IOException {
        Map<String,String> table=helperTypeMap(m);
        if(table.isEmpty())return descriptor;
        Matcher matcher=TYPE_IN_DESCRIPTOR.matcher(descriptor);
        StringBuffer result=new StringBuffer();
        while(matcher.find()) {
            String mapped=table.getOrDefault(matcher.group(1),matcher.group(1));
            matcher.appendReplacement(result,Matcher.quoteReplacement("L"+mapped+";"));
        }
        matcher.appendTail(result);
        return result.toString();
    }
    /** Per-mod inherited/lookup-owner member translations augment direct class-declaration maps. */
    private Path writeHelperOverrides(Mod m) throws IOException {
        Path output=work.resolve("helper-map-"+m.id+".tsv");
        List<String> lines=new ArrayList<>(List.of("# Per-mod lookup-owner aliases (inherited methods included)"));
        for(Resolved resolved:m.targets.values()) {
            Physical src=resolved.source(),dst=resolved.target();
            if(dst==null)continue;
            if(src.kind().equals("class")||!src.kind().equals(dst.kind()))continue;
            if(!Set.of("EXACT","MAPPED").contains(resolved.status()))continue;
            lines.add(String.join("\t",src.kind().equals("method")?"M":"F",src.owner(),src.name(),src.descriptor(),dst.owner(),dst.name(),dst.descriptor()));
        }
        write(output,lines);
        return output;
    }
    private void flattenHooks() throws IOException {
        int sequence=0;
        for(Mod m:mods) {
            Set<String> hookIds=new HashSet<>();
            for(Object entry:list(m.manifest.get("hooks"))) {
                Map<String,Object> raw=map(entry),bridge=map(raw.get("bridge"));
                Hook h=new Hook();h.mod=m;
                h.id=id(required(raw,"id","hook in "+m.id),"hook ID");
                if(!hookIds.add(h.id))throw new IOException("Duplicate hook "+m.id+"/"+h.id);
                h.transform=required(raw,"transform",h.identity());
                if(!Set.of("INSERT_BEFORE_FINAL_RETURN","INSERT_AT_ENTRY","WRAP_OBJECT_RETURN","REPLACE_INVOKE","ADD_METHOD","REORDER_NATIVE_COMMANDER").contains(h.transform))
                    throw new IOException("Unsupported transform "+h.transform+" for "+h.identity());
                h.target=required(raw,"target",h.identity());
                h.bridgeMethod=required(bridge,"method",h.identity()+" bridge");
                h.bridgeDescriptor=remapBridgeDescriptor(m,required(bridge,"descriptor",h.identity()+" bridge"));
                h.helperClass=m.helperClass;
                h.argsCsv=list(bridge.get("args")).stream().map(JavaPreparer::s).collect(Collectors.joining(","));
                Map<String,Object> ordering=optionalMap(raw.get("ordering"));
                if(!ordering.isEmpty()&&!h.transform.equals("INSERT_AT_ENTRY"))
                    throw new IOException("Only INSERT_AT_ENTRY permits ordering: "+h.identity());
                for(Object o:list(ordering.get("before"))) h.before.add(orderRef(map(o),h.identity()));
                for(Object o:list(ordering.get("after"))) h.after.add(orderRef(map(o),h.identity()));
                Physical t=target(m,h.target,h.identity()).target;
                if(h.transform.equals("ADD_METHOD")) {
                    if(!t.kind.equals("class"))throw new IOException("ADD_METHOD requires class target: "+h.identity());
                    h.ownerJar=t.clazz;
                    Map<String,Object> method=map(raw.get("method"));
                    h.methodFrom=required(method,"from_target",h.identity()+" method");
                    Physical inherited=target(m,h.methodFrom,h.identity()+" method.from_target").target;
                    if(!inherited.kind.equals("method"))throw new IOException("ADD_METHOD method.from_target requires a method: "+h.identity());
                    h.method=inherited.name;h.descriptor=inherited.descriptor;
                    String assertion=str(method,"descriptor");
                    if(!assertion.isEmpty()&&!assertion.equals(h.descriptor))throw new IOException("ADD_METHOD descriptor mismatch: "+h.identity());
                    h.bodyTemplate=required(map(raw.get("body")),"template",h.identity()+" body");
                    if(!Set.of("CALL_SUPER_THEN_STATIC","CALL_STATIC_THEN_SUPER","CALL_STATIC_ONLY").contains(h.bodyTemplate))
                        throw new IOException("Unknown ADD_METHOD body template: "+h.bodyTemplate);
                } else {
                    if(!t.kind.equals("method"))throw new IOException("Hook requires a method target: "+h.identity());
                    h.ownerJar=t.owner;h.method=t.name;h.descriptor=t.descriptor;
                }
                h.ownerDot=dot(h.ownerJar);
                if(h.transform.equals("REPLACE_INVOKE")) {
                    Map<String,Object> match=map(raw.get("match"));
                    h.matchOpcode=required(match,"opcode",h.identity()+" match");
                    h.matchTarget=str(match,"target");
                    if(!h.matchTarget.isBlank()) {
                        Physical mtarget=target(m,h.matchTarget,h.identity()+" match.target").target;
                        if(!mtarget.kind.equals("method"))throw new IOException("Invoke match target must be method: "+h.identity());
                        h.matchOwner=mtarget.owner;h.matchMethod=mtarget.name;h.matchDescriptor=mtarget.descriptor;
                    } else {
                        h.matchOwner=internal(required(match,"owner",h.identity()+" match"));
                        h.matchMethod=required(match,"name",h.identity()+" match");
                        h.matchDescriptor=required(match,"descriptor",h.identity()+" match");
                    }
                    if(!Set.of("INVOKEVIRTUAL","INVOKESPECIAL","INVOKESTATIC","INVOKEINTERFACE","AUTO_INSTANCE").contains(h.matchOpcode))
                        throw new IOException("Unsupported invocation opcode: "+h.matchOpcode);
                    Object occurrence=match.get("occurrence");
                    if(occurrence!=null) {
                        if(!(occurrence instanceof Number))throw new IOException("Invalid occurrence for "+h.identity());
                        h.occurrence=((Number)occurrence).intValue();
                    }
                    if(h.occurrence<1)throw new IOException("occurrence must be >=1: "+h.identity());
                }
                h.sequence=++sequence;h.applySequence=h.sequence;
                hooks.add(h);
            }
            Files.copy(m.manifestPath,resolvedMods.resolve(m.id+".json"),StandardCopyOption.REPLACE_EXISTING);
        }
        debug("Flattened "+hooks.size()+" v2/v3 mod transforms");
    }
    /** The non-public Bitwig classes and members each mod's code uses ("opens"), in the active build's names. */
    private void collectOpens() {
        for(Mod m:mods)for(String local:m.opens) {
            Physical t=target(m,local,"opens").target();
            switch(t.kind()) {
                case "class" -> opens.computeIfAbsent(dot(t.clazz()),k->new TreeSet<>()).add("C");
                case "field" -> opens.computeIfAbsent(dot(t.owner()),k->new TreeSet<>()).add("F:"+t.name()+":"+t.descriptor());
                case "method" -> opens.computeIfAbsent(dot(t.owner()),k->new TreeSet<>()).add("M:"+t.name()+":"+t.descriptor());
                default -> throw new IllegalArgumentException(m.id+" opens "+local+" of unknown kind "+t.kind());
            }
        }
        if(!opens.isEmpty())debug("Opening members of "+opens.size()+" Bitwig classes");
    }
    /** The fields each mod adds, in the active build's names. Everything that can refuse them runs here,
     *  before any class is patched. */
    private void collectFields() throws Exception {
        for(Mod m:mods)for(ModFields.Field f:ModFields.parse(m.manifest,m.id)) {
            String context=m.id+" field "+f.id(),descriptor=f.descriptor();
            // A type goes through its own target only: names overlap across builds, so nothing is looked up twice.
            Matcher type=TYPE_IN_DESCRIPTOR.matcher(descriptor);
            String translated=type.replaceAll(r->{
                String source=r.group(1);
                if(ModFields.jdkType(source))return Matcher.quoteReplacement(r.group());
                String local=m.targets.entrySet().stream().filter(t->source.equals(t.getValue().source().clazz())).map(Map.Entry::getKey).findFirst().orElse(null);
                return Matcher.quoteReplacement("L"+target(m,local,context).target().clazz()+";");
            });
            addedFields.add(new AddedField(m,f.id(),f.owner(),target(m,f.owner(),context).target().clazz(),f.name(),translated));
        }
        if(addedFields.isEmpty())return;
        List<String> cmd=new ArrayList<>();
        add(cmd,chosenJava(),"-jar",loader.resolve("tools/bitwig-field-adder.jar").toString(),"check",
            String.join(File.pathSeparator,stockJar.toString(),bitwigBin.resolve("libs.jar").toString()));
        for(AddedField f:addedFields)add(cmd,f.owner(),f.name(),f.mod().id);
        try {
            patchReport.addAll(exec(cmd,"Fields"));
        } catch(IOException ex) {
            List<String> refused=ex.getMessage().lines().filter(l->l.startsWith("FIELD_REFUSED ")).map(l->l.substring(14)).toList();
            throw refused.isEmpty()?ex:new IOException(String.join("; ",refused));
        }
        debug("Adding "+addedFields.size()+" fields");
    }
    private static String orderRef(Map<String,Object> ref,String owner) {
        return id(required(ref,"mod",owner+" order ref"),"ordering mod")+"::"+
            id(required(ref,"hook",owner+" order ref"),"ordering hook");
    }
    private void composeHooks() throws IOException {
        Map<String,Hook> active=new HashMap<>();
        for(Hook h:hooks)if(active.putIfAbsent(h.identity(),h)!=null)throw new IOException("Duplicate active hook "+h.identity());
        Map<String,List<Hook>> byPhysical=new TreeMap<>();
        for(Hook h:hooks)byPhysical.computeIfAbsent(h.physicalKey(),k->new ArrayList<>()).add(h);
        for(var ent:byPhysical.entrySet()) {
            List<Hook> entryHooks=ent.getValue().stream().filter(h->h.transform.equals("INSERT_AT_ENTRY")).toList();
            if(entryHooks.isEmpty())continue;
            Map<String,Hook> nodes=new HashMap<>();
            Map<String,Set<String>> edges=new HashMap<>();
            Map<String,Integer> degree=new HashMap<>();
            for(Hook h:entryHooks) {nodes.put(h.identity(),h);edges.put(h.identity(),new TreeSet<>());degree.put(h.identity(),0);}
            for(Hook h:entryHooks) {
                for(String ref:h.before) {
                    Hook other=active.get(ref);
                    if(other==null)continue;
                    requireOrder(h,other);
                    if(edges.get(h.identity()).add(ref))degree.merge(ref,1,Integer::sum);
                }
                for(String ref:h.after) {
                    Hook other=active.get(ref);
                    if(other==null)continue;
                    requireOrder(h,other);
                    if(edges.get(ref).add(h.identity()))degree.merge(h.identity(),1,Integer::sum);
                }
            }
            PriorityQueue<String> ready=new PriorityQueue<>();
            for(String k:nodes.keySet())if(degree.get(k)==0)ready.add(k);
            List<String> order=new ArrayList<>();
            while(!ready.isEmpty()) {
                String next=ready.remove();order.add(next);
                for(String downstream:edges.get(next)) if(degree.merge(downstream,-1,Integer::sum)==0)ready.add(downstream);
            }
            if(order.size()!=entryHooks.size())throw new IOException("INSERT_AT_ENTRY ordering cycle in "+ent.getKey());
            List<Integer> slots=entryHooks.stream().map(h->h.sequence).sorted().toList();
            for(int i=0;i<order.size();i++)nodes.get(order.get(i)).entryOrder=i+1;
            for(int i=0;i<order.size();i++)nodes.get(order.get(order.size()-1-i)).applySequence=slots.get(i);
            if(order.size()>1)debug("Composed INSERT_AT_ENTRY on "+ent.getKey()+": "+String.join(" -> ",order));
        }
        for(var ent:byPhysical.entrySet())if(ent.getValue().stream().filter(h->h.transform.equals("ADD_METHOD")).count()>1)
            throw new IOException("Conflicting ADD_METHOD on "+ent.getKey());
    }
    private static void requireOrder(Hook me,Hook other) throws IOException {
        if(!other.transform.equals("INSERT_AT_ENTRY")||!me.physicalKey().equals(other.physicalKey()))
            throw new IOException("Invalid active hook ordering: "+me.identity()+" references "+other.identity()+" on different transform or physical target");
    }
    private void mergeHelpers() throws Exception {
        if(mods.stream().noneMatch(m->m.helperJar!=null))return;
        Set<String> vendorClasses=new HashSet<>();
        try(ZipFile vendor=new ZipFile(stockJar.toFile())) {
            vendor.stream().map(ZipEntry::getName).filter(x->x.endsWith(".class")).forEach(vendorClasses::add);
        }
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(runtime.resolve("helper.jar")))) {
            // BML's own mod API (bml.api.Bml and the mod kit's annotations) comes first, so every mod
            // can call it without shipping it; a mod that ships these classes collides below.
            Path api=loader.resolve("api/bml-api.jar");
            if(!Files.isRegularFile(api))throw new IOException("Missing BML mod API: "+api);
            try(ZipFile in=new ZipFile(api.toFile())) {
                for(ZipEntry entry:Collections.list(in.entries())) {
                    String name=entry.getName();
                    if(entry.isDirectory()||name.regionMatches(true,0,"META-INF/",0,9))continue;
                    helperEntries.put(name,"BML (bml-api.jar)");
                    out.putNextEntry(new ZipEntry(name));
                    try(InputStream stream=in.getInputStream(entry)){stream.transferTo(out);}
                    out.closeEntry();
                }
            }
            for(Mod m:mods) {
                if(m.helperJar==null)continue;
                debug("Merging helper for "+m.id);
                long modStart=System.nanoTime();
                Path preparedHelper=m.helperJar;
                if(!m.sourceKey.equals(runtimeBuild.key)&&Files.isRegularFile(helperRemapMap(m))) {
                    Path tool=loader.resolve("tools/bitwig-helper-remapper.jar");
                    if(!Files.isRegularFile(tool))throw new IOException("Missing helper remapper tool: "+tool);
                    preparedHelper=work.resolve("helper-remapped-"+m.id+".jar");
                    Path report=runtime.resolve("helper-remap-"+m.id+".txt");
                    Path overrides=writeHelperOverrides(m);
                    List<String> cmd=new ArrayList<>();
                    add(cmd,chosenJava(),
                        "-jar",tool.toString(),m.helperJar.toString(),preparedHelper.toString(),
                        helperRemapMap(m).toString(),overrides.toString(),report.toString(),m.id);
                    helperRemapReport.addAll(exec(cmd,"Helper bytecode remap "+m.id));
                }
                try(ZipFile in=new ZipFile(preparedHelper.toFile())) {
                    Enumeration<? extends ZipEntry> entries=in.entries();
                    while(entries.hasMoreElements()) {
                        ZipEntry entry=entries.nextElement();String name=entry.getName();
                        if(entry.isDirectory()||name.regionMatches(true,0,"META-INF/",0,9))continue;
                        if(name.endsWith(".class")&&vendorClasses.contains(name))throw new IOException("Helper "+m.id+" shadows Bitwig class: "+name);
                        if(helperEntries.putIfAbsent(name,m.id)!=null)throw new IOException("Helper JAR collision: "+name+" in "+m.id+" and "+helperEntries.get(name));
                        out.putNextEntry(new ZipEntry(name));
                        try(InputStream stream=in.getInputStream(entry)){stream.transferTo(out);}
                        out.closeEntry();
                    }
                }
                progress.step(m.id,System.nanoTime()-modStart);
            }
        }
        debug("Merged "+helperEntries.size()+" helper entries");
    }
    private static String chosenJava() {
        String home=System.getenv("JAVA_HOME");
        if(home!=null) {
            Path p=Path.of(home,"bin",windows()?"java.exe":"java");
            if(Files.isRegularFile(p))return p.toString();
        }
        return windows()?"java.exe":"java";
    }
    private static boolean windows() {return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");}
    private static boolean mac() {return System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("mac");}
    /** Bitwig's Java in a macOS app bundle (contents = Contents/): the one for the architecture this
     *  JVM runs as, else the other one. */
    private static Path macBundledJava(Path contents) {
        boolean arm=System.getProperty("os.arch","").equals("aarch64");
        Path first=contents.resolve("PlugIns/JavaVM-"+(arm?"arm64":"x64")+".bundle/Contents/Home/bin/java");
        Path other=contents.resolve("PlugIns/JavaVM-"+(arm?"x64":"arm64")+".bundle/Contents/Home/bin/java");
        return Files.isRegularFile(first)||!Files.isRegularFile(other)?first:other;
    }
    private List<String> exec(List<String> argv,String context) throws Exception {
        if(argv.size()>=3 && argv.get(0).equals(chosenJava()) && argv.get(1).equals("-jar"))
            return runTool(Path.of(argv.get(2)),argv.subList(3,argv.size()),context);
        ProcessBuilder pb=new ProcessBuilder(argv).redirectErrorStream(true);
        Process p=pb.start();
        List<String> output=new ArrayList<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))) {
            String line;while((line=r.readLine())!=null) {output.add(line);if(verbose)System.out.println("[BML tool] "+line);}
        }
        int code=p.waitFor();
        if(code!=0)throw new IOException(context+" failed (exit "+code+"):\n"+String.join("\n",output));
        return output;
    }
    /** Runs a classfile tool ("java -jar tool args") inside this JVM instead: the same JDK, the
     *  same arguments and the same output, without starting a process. Starting a JVM costs
     *  about a second on Windows, mostly before Java runs, and preparation runs a tool per hook,
     *  so that was nearly all of its time. Each run gets a fresh class loader (nothing a tool
     *  keeps in static fields reaches the next run), and a tool's own failure is reported like
     *  an exception in its own JVM. A tool with a static run(String[]) is called through it, so
     *  it can refuse by throwing instead of System.exit. */
    private List<String> runTool(Path jar,List<String> args,String context) throws Exception {
        String main;
        try(java.util.jar.JarFile j=new java.util.jar.JarFile(jar.toFile())) {
            main=j.getManifest().getMainAttributes().getValue(java.util.jar.Attributes.Name.MAIN_CLASS);
        }
        if(main==null)throw new IOException("Tool has no Main-Class: "+jar);
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        PrintStream capture=new PrintStream(buffer,true,StandardCharsets.UTF_8);
        PrintStream out=System.out,err=System.err;
        Throwable failure=null;
        try(java.net.URLClassLoader classes=new java.net.URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            System.setOut(capture);System.setErr(capture);
            try {
                Class<?> tool=Class.forName(main,true,classes);
                java.lang.reflect.Method entry;
                try { entry=tool.getMethod("run",String[].class); }
                catch(NoSuchMethodException ex) { entry=tool.getMethod("main",String[].class); }
                entry.invoke(null,(Object)args.toArray(String[]::new));
            } catch(java.lang.reflect.InvocationTargetException ex) {
                failure=ex.getCause();
            } catch(ReflectiveOperationException|LinkageError ex) {
                failure=ex;
            } finally {
                System.setOut(out);System.setErr(err);
            }
        }
        if(failure!=null)failure.printStackTrace(capture);
        capture.flush();
        List<String> output=new BufferedReader(new StringReader(buffer.toString(StandardCharsets.UTF_8))).lines().toList();
        if(verbose)for(String line:output)System.out.println("[BML tool] "+line);
        if(failure!=null)throw new IOException(context+" failed:\n"+String.join("\n",output));
        return output;
    }
    private static void add(List<String> into,String... strings) {Collections.addAll(into,strings);}
    private static List<String> invokeDescriptorParameters(String desc) {
        if(!desc.startsWith("("))throw new IllegalArgumentException("Invalid invocation descriptor: "+desc);
        List<String> parts=new ArrayList<>();int i=1;
        while(i<desc.length()&&desc.charAt(i)!=')') {
            int start=i;while(i<desc.length()&&desc.charAt(i)=='[')i++;
            if(i>=desc.length())throw new IllegalArgumentException("Invalid invocation descriptor: "+desc);
            char kind=desc.charAt(i++);
            if(kind=='L') {int end=desc.indexOf(';',i);if(end<0)throw new IllegalArgumentException("Invalid invocation descriptor: "+desc);i=end+1;}
            else if("BCDFIJSZ".indexOf(kind)<0)throw new IllegalArgumentException("Invalid invocation descriptor: "+desc);
            parts.add(desc.substring(start,i));
        }
        if(i>=desc.length()||desc.charAt(i)!=')')throw new IllegalArgumentException("Invalid invocation descriptor: "+desc);
        return parts;
    }
    private static boolean invokeBridgeCanChain(String original, String replacement) {
        int a=original.indexOf(')'),b=replacement.indexOf(')');
        if(a<0||b<0||!original.substring(a+1).equals(replacement.substring(b+1)))return false;
        List<String> originalArgs=invokeDescriptorParameters(original);
        List<String> replacementArgs=invokeDescriptorParameters(replacement);
        if(originalArgs.isEmpty()||originalArgs.size()!=replacementArgs.size())return false;
        String returned=original.substring(a+1);
        if(!(returned.startsWith("L")||returned.startsWith("["))||!originalArgs.get(originalArgs.size()-1).equals(returned))return false;
        for(int i=0;i<originalArgs.size();i++){
            String old=originalArgs.get(i),now=replacementArgs.get(i);
            if(!old.equals(now)&&!((old.startsWith("L")||old.startsWith("["))&&now.equals("Ljava/lang/Object;")))return false;
        }
        return true;
    }
    private void applyTransforms() throws Exception {
        composition.add("apply_sequence\tdeclared_sequence\tentry_execution_order\tmod_id\thook_id\tlocal_target\tphysical_owner\tphysical_method\tphysical_descriptor\ttransform\tordering_before\tordering_after\tmethod_from_target\tbody_template\tbridge\targs\tmatch_opcode\tmatch_target\tmatch_owner\tmatch_method\tmatch_descriptor\tmatch_occurrence\tmatch_original_call");
        if(hooks.isEmpty()&&opens.isEmpty()&&addedFields.isEmpty())return;
        Path tools=loader.resolve("tools");
        Map<String,List<Hook>> owners=new TreeMap<>();
        for(Hook h:hooks)owners.computeIfAbsent(h.ownerDot,k->new ArrayList<>()).add(h);
        for(String owner:opens.keySet())owners.computeIfAbsent(owner,k->new ArrayList<>());
        for(AddedField f:addedFields)owners.computeIfAbsent(dot(f.owner()),k->new ArrayList<>());
        // Work and replacement files are named by classFileStem, so case-only pairs (dnF / dnf)
        // get different files. Refuse if two stems could still be one file on any filesystem.
        Map<String,String> stems=new HashMap<>();
        for(String owner:owners.keySet()) {
            String stem=classFileStem(owner),prior=stems.putIfAbsent(stem.toLowerCase(Locale.ROOT),owner);
            if(prior!=null)throw new IOException("Transformed classes "+prior+" and "+owner+" would share the file name "+stem+". Refusing unsafe overlay.");
        }
        for(var ent:owners.entrySet()) {
            String owner=ent.getKey(),stem=classFileStem(owner);Path current=null;
            List<Hook> transforms=ent.getValue().stream().sorted(Comparator.comparingInt(h->h.applySequence)).toList();
            // Which call each REPLACE_INVOKE means, numbered in the original method. A mod's occurrence
            // counts the calls left after that mod's own earlier hooks; other mods' hooks don't move it.
            Map<String,TreeSet<Integer>> takenByMod=new HashMap<>();
            for(Hook h:transforms)if(h.transform.equals("REPLACE_INVOKE")) {
                TreeSet<Integer> taken=takenByMod.computeIfAbsent(h.mod.id+"|"+h.invokeKey(),k->new TreeSet<>());
                h.originalCall=h.occurrence;
                for(int call:taken)if(call<=h.originalCall)h.originalCall++;
                taken.add(h.originalCall);
            }
            // Identify original invocation-site collisions before transforming the class.
            // Only the exact static, result-accumulator pattern is currently composable.
            // Hooks must be adjacent in the declared physical-class application order;
            // we must not silently move intervening transforms across a replacement.
            Map<String,List<Hook>> invokeSites=new LinkedHashMap<>();
            for(Hook h:transforms)if(h.transform.equals("REPLACE_INVOKE")) {
                String site=h.invokeKey()+"|"+h.originalCall;
                invokeSites.computeIfAbsent(site,k->new ArrayList<>()).add(h);
            }
            Map<Hook,List<Hook>> chainStarts=new IdentityHashMap<>();
            Set<Hook> chainedFollowers=Collections.newSetFromMap(new IdentityHashMap<>());
            for(var site:invokeSites.entrySet()) {
                List<Hook> same=site.getValue();
                if(same.size()<2)continue;
                // These hooks are all from different mods: one mod's hooks never share a call, because
                // its occurrence=1 used twice means two calls (Previous and Next controls, say).
                Hook first=same.get(0);
                int start=transforms.indexOf(first);
                for(int i=0;i<same.size();i++) {
                    Hook h=same.get(i);
                    if(start+i>=transforms.size()||transforms.get(start+i)!=h)
                        throw new IOException("Conflicting REPLACE_INVOKE at "+site.getKey()+": hooks must be contiguous; "+
                            same.stream().map(Hook::identity).collect(Collectors.joining(", ")));
                    if(!h.matchOpcode.equals("INVOKESTATIC")||!invokeBridgeCanChain(first.matchDescriptor,h.bridgeDescriptor))
                        throw new IOException("Unsafe REPLACE_INVOKE overlap at "+site.getKey()+" between "+
                            same.stream().map(Hook::identity).collect(Collectors.joining(", "))+
                            ": composition requires INVOKESTATIC, exact return and arguments identical or widened only to java.lang.Object. No mod was silently dropped.");
                }
                chainStarts.put(first,same);
                for(int i=1;i<same.size();i++)chainedFollowers.add(same.get(i));
                debug("Composing overlapping REPLACE_INVOKE "+site.getKey()+": "+
                    same.stream().map(Hook::identity).collect(Collectors.joining(" -> ")));
            }
            // The patcher counts the calls still in the class, so each hook is translated from its
            // original call to its place among those: minus the earlier calls already replaced.
            Map<String,TreeSet<Integer>> replaced=new HashMap<>();
            int idx=0;
            for(Hook h:transforms) {
                if(chainedFollowers.contains(h))continue;
                String context="Transform "+h.identity();
                Path tmp=work.resolve(stem+"."+(++idx)+".class");
                List<String> cmd=new ArrayList<>();add(cmd,chosenJava(),"-jar");
                if(h.transform.equals("ADD_METHOD")) {
                    cmd.add(tools.resolve("bitwig-add-method-patcher.jar").toString());
                    if(current==null)add(cmd,"patch-jar",stockJar.toString(),h.ownerJar,tmp.toString());
                    else add(cmd,"patch-class",current.toString(),stockJar.toString(),tmp.toString());
                    add(cmd,h.method,h.descriptor,h.bodyTemplate,h.helperClass,h.bridgeMethod,h.bridgeDescriptor);
                    if(!h.argsCsv.isBlank())cmd.add(h.argsCsv);
                }else if(h.transform.equals("REORDER_NATIVE_COMMANDER")) {
                    cmd.add(tools.resolve("bitwig-invoke-replacer.jar").toString());
                    add(cmd,current==null?"reorder-jar":"reorder-class",
                        current==null?stockJar.toString():current.toString(),current==null?h.ownerJar:"-",tmp.toString(),
                        h.method,h.descriptor);
                }else if(h.transform.equals("REPLACE_INVOKE")) {
                    cmd.add(tools.resolve("bitwig-invoke-replacer.jar").toString());
                    TreeSet<Integer> gone=replaced.computeIfAbsent(h.invokeKey(),k->new TreeSet<>());
                    int remaining=h.originalCall-gone.headSet(h.originalCall).size();
                    context+=" (occurrence "+h.occurrence+" is call "+h.originalCall+" of "+h.matchOwner+"."+h.matchMethod+h.matchDescriptor+
                        " in the original "+h.ownerDot+"."+h.method+h.descriptor+"; hooks have replaced "+gone.size()+" of those calls so far)";
                    gone.add(h.originalCall);
                    List<Hook> chain=chainStarts.get(h);
                    add(cmd,(chain==null?"patch-":"chain-")+(current==null?"jar":"class"),
                        current==null?stockJar.toString():current.toString(),current==null?h.ownerJar:"-",tmp.toString(),
                        h.method,h.descriptor,h.matchOpcode,h.matchOwner,h.matchMethod,h.matchDescriptor,Integer.toString(remaining));
                    if(chain==null)add(cmd,h.helperClass,h.bridgeMethod,h.bridgeDescriptor);
                    else for(Hook bridge:chain)add(cmd,bridge.helperClass,bridge.bridgeMethod,bridge.bridgeDescriptor);
                }else {
                    boolean entry=h.transform.equals("INSERT_AT_ENTRY"),fin=h.transform.equals("INSERT_BEFORE_FINAL_RETURN");
                    cmd.add(tools.resolve(entry?"bitwig-typed-local-patcher.jar":"bitwig-mod-preparer.jar").toString());
                    String suffix=entry?"entry":fin?"final":"wrap-object";
                    add(cmd,current==null?"patch-jar-"+suffix:"patch-class-"+suffix,current==null?stockJar.toString():current.toString());
                    if(current==null)cmd.add(h.ownerJar);
                    add(cmd,tmp.toString(),h.method,h.descriptor,h.helperClass,h.bridgeMethod,h.bridgeDescriptor);
                    if(!h.argsCsv.isBlank())cmd.add(h.argsCsv);
                }
                debug("Patching "+h.identity()+" "+h.transform+" "+h.physicalKey());
                long toolStart=System.nanoTime();
                patchReport.addAll(exec(cmd,context));
                progress.hookDone(h.mod.id,System.nanoTime()-toolStart);
                composition.add(String.join(TSV, Integer.toString(h.applySequence),Integer.toString(h.sequence),h.entryOrder==0?"":Integer.toString(h.entryOrder),
                    h.mod.id,h.id,h.target,h.ownerDot,h.method,h.descriptor,h.transform,String.join(";",h.before),String.join(";",h.after),
                    h.methodFrom,h.bodyTemplate,h.helperClass+"."+h.bridgeMethod+h.bridgeDescriptor,h.argsCsv,h.matchOpcode,h.matchTarget,
                    h.matchOwner,h.matchMethod,h.matchDescriptor,Integer.toString(h.occurrence),h.originalCall==0?"":Integer.toString(h.originalCall)));
                List<Hook> completedChain=chainStarts.get(h);
                if(completedChain!=null)for(int j=1;j<completedChain.size();j++) {
                    Hook follower=completedChain.get(j);
                    progress.hookDone(follower.mod.id,0);
                    patchReport.add("INVOKE_CHAIN"+TSV+follower.identity()+TSV+"composed after "+h.identity());
                    composition.add(String.join(TSV,Integer.toString(follower.applySequence),Integer.toString(follower.sequence),"",
                        follower.mod.id,follower.id,follower.target,follower.ownerDot,follower.method,follower.descriptor,follower.transform,
                        "","",follower.methodFrom,follower.bodyTemplate,
                        follower.helperClass+"."+follower.bridgeMethod+follower.bridgeDescriptor,follower.argsCsv,
                        follower.matchOpcode,follower.matchTarget,follower.matchOwner,follower.matchMethod,
                        follower.matchDescriptor,Integer.toString(follower.occurrence),Integer.toString(follower.originalCall)));
                }
                current=tmp;
            }
            List<AddedField> added=addedFields.stream().filter(f->dot(f.owner()).equals(owner)).toList();
            if(!added.isEmpty()) {
                // After every hook, so the patchers above see Bitwig's own class.
                Path tmp=work.resolve(stem+".fields.class");
                List<String> cmd=new ArrayList<>();
                add(cmd,chosenJava(),"-jar",tools.resolve("bitwig-field-adder.jar").toString());
                if(current==null)add(cmd,"add-jar",stockJar.toString(),owner.replace('.','/'),tmp.toString());
                else add(cmd,"add-class",current.toString(),tmp.toString());
                for(AddedField f:added)cmd.add(f.name()+":"+f.descriptor());
                long toolStart=System.nanoTime();
                patchReport.addAll(exec(cmd,"Add fields to "+owner));
                progress.step(null,System.nanoTime()-toolStart);
                for(AddedField f:added)composition.add(String.join(TSV,"","","",f.mod().id,f.id(),f.target(),owner,f.name(),f.descriptor(),"ADD_FIELD",
                    "","","","","","","","","","","","",""));
                current=tmp;
            }
            Set<String> open=opens.get(owner);
            if(open!=null) {
                // Access flags only, after every hook, so the patchers above see Bitwig's own class.
                Path tmp=work.resolve(stem+".open.class");
                List<String> cmd=new ArrayList<>();
                add(cmd,chosenJava(),"-jar",tools.resolve("bitwig-access-widener.jar").toString());
                if(current==null)add(cmd,"widen-jar",stockJar.toString(),owner.replace('.','/'),tmp.toString());
                else add(cmd,"widen-class",current.toString(),stockJar.toString(),tmp.toString());
                cmd.addAll(open);
                String by=mods.stream().filter(m->m.opens.stream().anyMatch(l->owner.equals(dot(openOwner(m,l))))).map(m->m.id).collect(Collectors.joining(","));
                debug("Opening "+owner+" "+open+" for "+by);
                long toolStart=System.nanoTime();
                patchReport.addAll(exec(cmd,"Open "+owner+" for "+by));
                progress.step(null,System.nanoTime()-toolStart);
                current=tmp;
            }
            Path out=replacements.resolve(stem+".class");
            Files.copy(current,out,StandardCopyOption.REPLACE_EXISTING);
            replacementRows.add(owner+TSV+out.toAbsolutePath().normalize());
            replacementFiles.put(owner.replace('.','/')+".class",out);
        }
        debug("Composed "+hooks.size()+" transforms into "+replacementRows.size()+" replacement classes");
    }
    private static String openOwner(Mod m,String local) {
        Physical t=target(m,local,"opens").target();
        return t.kind().equals("class")?t.clazz():t.owner();
    }
    private void deepVerify() throws Exception {
        if(hooks.isEmpty()&&opens.isEmpty()&&addedFields.isEmpty())return;
        // Replacements are packaged in a ZIP/JAR, entries under their exact class names, for
        // case-sensitive JVM name matching on NTFS.
        Path overlay=runtime.resolve("deep-verify-replacements.jar");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(overlay))) {
            for(var e:replacementFiles.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));Files.copy(e.getValue(),zip);zip.closeEntry();
            }
        }
        // Windows bundles it as <Bitwig>/jre, Linux as <Bitwig>/lib/jre.
        Path bundled=bitwigBin.getParent().resolve("jre/bin").resolve(windows()?"java.exe":"java");
        if(!windows()&&!Files.isRegularFile(bundled))bundled=bitwigBin.getParent().resolve("lib/jre/bin/java");
        // macOS bundles one per architecture: Contents/PlugIns/JavaVM-arm64.bundle and JavaVM-x64.bundle.
        if(mac())bundled=macBundledJava(bitwigBin.getParent());
        if(!Files.isRegularFile(bundled))throw new IOException("Bitwig bundled Java runtime not found: "+bundled);
        Path verifier=loader.resolve("tools/bitwig-deep-verifier.jar");
        if(!Files.isRegularFile(verifier))throw new IOException("Deep verifier missing: "+verifier);
        List<String> cp=new ArrayList<>();cp.add(overlay.toString());
        Path helper=runtime.resolve("helper.jar");if(Files.exists(helper))cp.add(helper.toString());
        cp.add(stockJar.toString());
        Path cpDir=bitwigBin.resolve("cp");if(mac())cpDir=bitwigBin.resolveSibling("Frameworks").resolve("cp");
        if(Files.exists(cpDir))cp.add(cpDir.toString());
        Path libs=bitwigBin.resolve("libs.jar");if(Files.exists(libs))cp.add(libs.toString());
        cp.add(verifier.toString());
        Set<String> verifyTargets=new TreeSet<>();
        for(Hook h:hooks)verifyTargets.add(h.ownerDot);
        verifyTargets.addAll(opens.keySet());
        for(AddedField f:addedFields)verifyTargets.add(dot(f.owner()));
        for(String entry:helperEntries.keySet())if(entry.endsWith(".class")&&!entry.equals("module-info.class"))verifyTargets.add(dot(entry.substring(0,entry.length()-6)));
        List<String> cmd=new ArrayList<>();add(cmd,bundled.toString(),"-Xverify:all","-cp",String.join(File.pathSeparator,cp),"dev.bitwigre.loader.DeepClassVerifier");
        cmd.addAll(verifyTargets);
        for(String line:exec(cmd,"Deep verification"))patchReport.add("DEEP_VERIFY"+TSV+line);
        debug("Deep-verified "+verifyTargets.size()+" classes");
    }
    /** Optional exact-runtime diagnostic targets, independent of any mod manifest.
     * Only read in Debug Mode; never edits or packages the original mod.
     * The operator supplies physical target-version identities, not source names.
     */
    private void addExternalDebugTraceTargets(Set<String> seen) throws IOException {
        if(!isTracing())return;
        Path profile=loader.resolve("diagnostics/trace-targets.tsv");
        if(!Files.exists(profile,LinkOption.NOFOLLOW_LINKS))return;
        if(!Files.isRegularFile(profile,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(profile))
            throw new IOException("BML extra trace profile must be an ordinary file: "+profile);
        int added=0,lineno=0;
        Set<String> ids=new HashSet<>();
        for(String raw:Files.readAllLines(profile,StandardCharsets.UTF_8)) {
            lineno++;
            String line=raw.trim();
            if(line.isEmpty()||line.startsWith("#")||line.equals("id\towner\tmethod\tdescriptor"))continue;
            String[] t=raw.split("\t",-1);
            if(t.length!=4)throw new IOException("Invalid trace profile TSV at line "+lineno+" (expected 4 columns)");
            String tid=t[0].trim(),owner=t[1].trim(),method=t[2].trim(),descriptor=t[3].trim();
            if(!tid.matches("[A-Za-z0-9._-]{1,100}")||!ids.add(tid))
                throw new IOException("Invalid or duplicate extra trace id at line "+lineno);
            if(!owner.matches("[A-Za-z0-9_.$/]+")||!method.matches("[A-Za-z0-9_$<>]+")
                ||!descriptor.startsWith("(")||!descriptor.contains(")")||descriptor.contains("\t"))
                throw new IOException("Invalid exact JVM target at trace profile line "+lineno);
            String dotted=owner.replace('/','.');
            if(seen.add(dotted+"|"+method+"|"+descriptor)) {
                traceRows.add(String.join(TSV,"diagnostic.external."+tid,dotted,method,descriptor,dotted,
                    "Operator Debug-only exact runtime target"));
                added++;
            }
            if(added>128)throw new IOException("Extra trace target limit exceeded (128)");
        }
        if(added>0)debug("Included "+added+" external Debug Mode trace targets from "+profile);
    }

    private void writeTracesAndReports() throws Exception {
        traceRows.add("target_id\towner\tmethod\tdescriptor\treceiver\tnotes");
        Set<String> seen=new HashSet<>();
        for(Hook h:hooks.stream().sorted(Comparator.comparingInt(x->x.sequence)).toList()) {
            String key=h.physicalKey();
            if(seen.add(key))traceRows.add(String.join(TSV,"mod."+h.mod.id+".target."+h.target,h.ownerDot,h.method,h.descriptor,h.ownerDot,"composed Manifest v2 hook"));
        }
        for(Mod m:mods) {
            Set<String> ids=new HashSet<>();
            for(Object o:list(m.manifest.get("trace_targets"))) {
                Map<String,Object> tr=map(o);
                String tid=id(required(tr,"id",m.id+" trace target"),"trace ID");
                if(!ids.add(tid))throw new IOException("Duplicate trace target: "+m.id+"/"+tid);
                String local=str(tr,"target"), owner, method,descriptor,note;
                if(!local.isBlank()) {
                    // A diagnostic-only target may be explicitly optional across
                    // Bitwig versions. Omit an unmapped optional trace; never
                    // relax the requirement for hooks, match sites or required
                    // diagnostics. Do not infer a physical JVM identity.
                    Resolved traceResolution=m.targets.get(local);
                    if(Boolean.TRUE.equals(tr.get("optional")) && traceResolution!=null && traceResolution.target()==null) {
                        System.err.println("OPTIONAL_TRACE_UNMAPPED: "+m.id+"/"+tid+" ("+local+") on "+runtimeBuild.key+"; trace omitted");
                        continue;
                    }
                    Physical t=target(m,local,m.id+" trace target").target;
                    if(!t.kind.equals("method"))throw new IOException("Trace target must be method: "+m.id+"/"+tid);
                    owner=dot(t.owner);method=t.name;descriptor=t.descriptor;
                    note="Resolved local diagnostic target "+local+" for "+m.id;
                }else {
                    String sourceOwner=internal(required(tr,"class",m.id+" trace target"));
                    owner=dot(helperTypeMap(m).getOrDefault(sourceOwner,sourceOwner));
                    method=required(tr,"method",m.id+" trace target");
                    String sourceDescriptor=required(tr,"descriptor",m.id+" trace target");
                    descriptor=remapBridgeDescriptor(m,sourceDescriptor);
                    note="Raw diagnostic trace target for "+m.id+
                        (descriptor.equals(sourceDescriptor)?"":" (source descriptor remapped)");
                }
                if(seen.add(owner+"|"+method+"|"+descriptor))
                    traceRows.add(String.join(TSV,"mod."+m.id+".trace."+tid,owner,method,descriptor,owner,note));
            }
        }
        addExternalDebugTraceTargets(seen);
        write(runtime.resolve("replacement-map.tsv"),replacementRows);
        write(runtime.resolve("targets.tsv"),traceRows);
        write(runtime.resolve("transform-report.txt"),patchReport);
        if(!helperRemapReport.isEmpty())write(runtime.resolve("helper-remap-report.txt"),helperRemapReport);
        write(runtime.resolve("composition-report.tsv"),composition);
        Path helper=runtime.resolve("helper.jar");
        List<String> info=List.of(
            "loader_version="+LOADER_VERSION,
            "runtime_build_key="+runtimeBuild.key,
            "bitwig_version="+runtimeBuild.version,
            "stock_bitwig_sha256="+stockSha,
            "map_package_id="+mapPackId,
            "map_package_version="+mapPackVersion,
            "mods_root="+modsRoot,
            "mod_count="+mods.size(),
            "mod_ids="+mods.stream().map(m->m.id).collect(Collectors.joining(",")),
            "transform_count="+(hooks.size()+addedFields.size()),
            "trace_target_count="+(traceRows.size()-1),
            "replacement_class_count="+replacementRows.size(),
            "combined_helper_entry_count="+helperEntries.size(),
            "helper_jar_sha256="+(Files.isRegularFile(helper)?sha256(helper):"")
        );
        write(runtime.resolve("build-info.txt"),info);
        System.out.println("\nPREPARE_OK loader="+LOADER_VERSION+" runtime_build="+runtimeBuild.key+" Bitwig="+runtimeBuild.version+
            " mods="+mods.size()+" transforms="+(hooks.size()+addedFields.size())+" replacement_classes="+replacementRows.size());
        System.out.println("Mods: "+(mods.isEmpty()?"none (inert launch mode)":mods.stream().map(m->m.id).collect(Collectors.joining(", "))));
        System.out.println("Stock bitwig.jar was read and hashed; it was NOT modified.");
        System.out.println("Runtime: "+runtime);
    }
}
