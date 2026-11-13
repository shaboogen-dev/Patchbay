package dev.bitwigre.bml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.stream.Stream;

/**
 * The installed map packs and which one is made for the installed Bitwig. {@link #read} hashes the
 * installed bitwig.jar (35 MB, cached while its size and time stay the same), so it runs off the
 * event thread.
 */
final class MapPacks {
    private MapPacks(){}

    /** {@code loads} says which mods load on it, e.g. "6.1.3 and 6.1.4 (Windows, macOS and Linux)";
     *  {@code sources} maps each build it loads mods from, as {@link #buildId}, to its build key (its own
     *  target's included); {@code directory} is the installed pack's folder. */
    record Pack(String id,String version,String targetSha,String bitwig,String platform,String loads,
                Map<String,String> sources,String targetKey,Path directory,String problem) {}
    /** A build as a mod's built_against names it: version, platform and architecture. */
    static String buildId(String version,String platform,String arch){
        return version+"|"+platform.toLowerCase(Locale.ROOT)+"|"+arch.toLowerCase(Locale.ROOT);
    }
    /** Installed packs and the installed bitwig.jar's SHA-256 (null when Bitwig isn't found). */
    record Data(List<Pack> packs,String installedSha) {
        /** The pack made for the installed Bitwig, or null. */
        Pack inUse(){
            Pack found=null;
            for(Pack p:packs)if(p.problem()==null&&p.targetSha().equalsIgnoreCase(String.valueOf(installedSha)))found=p;
            return found;
        }
    }

    static Data read(BmlService service) {
        List<Pack> packs=packs(service.paths().root.resolve("maps"));
        Path exe=service.config().bitwigExecutable();
        String installedSha=null;
        try{
            Path jar=exe==null?null:BmlConfig.jarFolder(exe).resolve("bitwig.jar");
            if(jar!=null&&Files.isRegularFile(jar))installedSha=jarSha(jar);
        }catch(Exception ignored){}
        return new Data(packs,installedSha);
    }

    /** Installed packs, newest Bitwig first; a pack that can't be read is listed with why. */
    static List<Pack> packs(Path maps) {
        List<Pack> out=new ArrayList<>();
        if(!Files.isDirectory(maps))return out;
        try(Stream<Path> dirs=Files.list(maps)){
            for(Path dir:dirs.filter(Files::isDirectory).sorted().toList()){
                try{
                    Map<String,Object> m=SimpleJson.object(SimpleJson.parse(Files.readString(dir.resolve("map.json"),StandardCharsets.UTF_8)));
                    Map<String,String[]> builds=new HashMap<>();  // build key -> version, platform, architecture
                    for(Object o:(List<?>)SimpleJson.object(SimpleJson.parse(Files.readString(dir.resolve("builds.json"),StandardCharsets.UTF_8))).get("builds")){
                        Map<String,Object> b=SimpleJson.object(o);
                        builds.put(String.valueOf(b.get("build_key")),new String[]{String.valueOf(b.get("bitwig_version")),
                            String.valueOf(b.get("platform")),String.valueOf(b.get("architecture"))});
                    }
                    String target=String.valueOf(m.get("target_build_key"));
                    String[] t=builds.get(target);
                    if(t==null)throw new IllegalStateException("its builds.json doesn't list "+target);
                    Map<String,String> sources=new HashMap<>();  // buildId -> build key, the target's own included
                    List<String[]> loads=new ArrayList<>();loads.add(t);sources.put(buildId(t[0],t[1],t[2]),target);
                    try(Stream<Path> remaps=Files.list(dir.resolve("remap"))){
                        for(Path r:remaps.toList()){
                            String name=r.getFileName().toString();
                            if(!name.endsWith("__"+target+".tsv.gz"))continue;
                            String key=name.substring(0,name.indexOf("__"));
                            String[] s=builds.get(key);
                            if(s!=null){loads.add(s);sources.put(buildId(s[0],s[1],s[2]),key);}
                        }
                    }catch(NoSuchFileException ignored){}
                    out.add(new Pack(String.valueOf(m.get("id")),String.valueOf(m.get("version")),String.valueOf(m.get("target_jar_sha256")),
                        t[0],platformName(t[1],t[2]),loadsText(loads),Map.copyOf(sources),target,dir,null));
                }catch(Exception ex){
                    out.add(new Pack(dir.getFileName().toString(),"","","","","",Map.of(),"",dir,String.valueOf(ex.getMessage())));
                }
            }
        }catch(Exception ignored){}
        out.sort((a,b)->a.problem()!=null||b.problem()!=null?Boolean.compare(a.problem()!=null,b.problem()!=null):compareVersions(b.bitwig(),a.bitwig()));
        return out;
    }
    private static String platformName(String platform,String arch){
        return RemapCheck.platformName(platform)+" "+arch;
    }
    /** The builds a pack loads mods from, by version, each with its platforms; consecutive versions with
     *  the same platforms share them: "6.0 (macOS), 6.1.3 and 6.1.4 (Windows, macOS and Linux)". */
    private static String loadsText(List<String[]> builds){
        List<String> order=List.of("windows","macos","linux");
        TreeMap<String,TreeSet<String>> byVersion=new TreeMap<>(MapPacks::compareVersions);
        for(String[] b:builds)byVersion.computeIfAbsent(b[0],v->new TreeSet<>(Comparator.comparing((String p)->order.indexOf(p)).thenComparing(p->p)))
            .add(b[1].toLowerCase(Locale.ROOT));
        List<String> groups=new ArrayList<>(),versions=new ArrayList<>();
        Set<String> last=null;
        for(var e:byVersion.entrySet()){
            if(last!=null&&!last.equals(e.getValue())){groups.add(group(versions,last));versions=new ArrayList<>();}
            versions.add(e.getKey());last=e.getValue();
        }
        if(last!=null)groups.add(group(versions,last));
        return String.join(", ",groups);
    }
    private static String group(List<String> versions,Set<String> platforms){
        return joinAnd(versions)+" ("+joinAnd(platforms.stream().map(RemapCheck::platformName).toList())+")";
    }
    /** The Bitwig versions the installed maps run on, each with its platforms, oldest first:
     *  "6.1.3 (Windows) and 6.1.4 (Windows, macOS and Linux)". Empty when no map is installed. */
    static String supported(List<Pack> packs){
        List<String> order=List.of("windows","macos","linux");
        TreeMap<String,TreeSet<String>> byVersion=new TreeMap<>(MapPacks::compareVersions);
        for(Pack p:packs)if(p.problem()==null){
            String platform=p.targetKey().contains("-")?p.targetKey().substring(0,p.targetKey().indexOf('-')):"";
            byVersion.computeIfAbsent(p.bitwig(),v->new TreeSet<>(Comparator.comparing((String x)->order.indexOf(x)).thenComparing(x->x))).add(platform);
        }
        List<String> parts=new ArrayList<>();
        for(var e:byVersion.entrySet())parts.add(e.getKey()+" ("+joinAnd(e.getValue().stream().map(RemapCheck::platformName).toList())+")");
        return joinAnd(parts);
    }
    static int compareVersions(String a,String b){
        String[] x=a.split("\\."),y=b.split("\\.");
        for(int i=0;i<Math.max(x.length,y.length);i++){
            int p=i<x.length?Integer.parseInt(x[i]):0,q=i<y.length?Integer.parseInt(y[i]):0;
            if(p!=q)return Integer.compare(p,q);
        }
        return 0;
    }
    private static String joinAnd(List<String> v){return v.size()<2?String.join("",v):String.join(", ",v.subList(0,v.size()-1))+" and "+v.get(v.size()-1);}
    /** The jar's hash, kept while its size and modification time stay the same: the page opens
     *  often and bitwig.jar is 35 MB. */
    private static final Map<String,String> SHA_CACHE=new java.util.concurrent.ConcurrentHashMap<>();
    private static String jarSha(Path jar) throws Exception {
        String key=jar.toAbsolutePath()+"|"+Files.size(jar)+"|"+Files.getLastModifiedTime(jar).toMillis();
        String known=SHA_CACHE.get(key);
        if(known!=null)return known;
        String sha=sha256(jar);SHA_CACHE.put(key,sha);return sha;
    }
    private static String sha256(Path p) throws Exception {
        MessageDigest d=MessageDigest.getInstance("SHA-256");
        try(InputStream in=Files.newInputStream(p)){byte[] b=new byte[1<<16];int n;while((n=in.read(b))>0)d.update(b,0,n);}
        return HexFormat.of().formatHex(d.digest());
    }
}
