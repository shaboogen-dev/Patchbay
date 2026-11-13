package dev.bitwigre.bml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Standard 13: what a v2 map's remap from a mod's build can't translate of what the mod needs, which
 * makes the mod not available on the installed Bitwig. A mod needs the targets its hooks use (the hook
 * target, an invoke match, an ADD_METHOD source), the targets it opens, the classes of the fields it adds and, when its release ships one,
 * every Bitwig class and member in its uses.tsv. Typically this is a mod built against one platform's
 * own code (macOS's Cocoa windowing, say), which has no counterpart on another platform. The window and
 * the preparer both ask here, so they agree. One scan of a remap answers every mod built against its source.
 */
final class RemapCheck {
    private RemapCheck(){}
    private static final Map<String,Map<String,String>> CACHE=new ConcurrentHashMap<>();

    /** "Windows", "macOS" or "Linux" for a builds.json / built_against platform. */
    static String platformName(String platform) {
        return switch(platform.toLowerCase(Locale.ROOT)){case "windows"->"Windows";case "macos"->"macOS";case "linux"->"Linux";default->platform;};
    }

    /** What the mod needs translated, as remap rows name it ("C|class", "M|owner|name|desc", "F|...")
     *  -> how a reader names it. Malformed declarations are left to the preparer's own checks. */
    static Map<String,String> needs(Path modDir,Map<String,Object> manifest) throws IOException {
        Map<String,String> out=new TreeMap<>();
        Map<String,Object> targets=manifest.get("targets") instanceof Map<?,?> t?SimpleJson.object(t):Map.of();
        List<String> locals=new ArrayList<>();
        if(manifest.get("hooks") instanceof List<?> hooks)for(Object o:hooks){
            if(!(o instanceof Map<?,?> raw))continue;
            Map<String,Object> hook=SimpleJson.object(raw);
            locals.add(String.valueOf(hook.get("target")));
            if(hook.get("match") instanceof Map<?,?> m)locals.add(String.valueOf(m.get("target")));
            if(hook.get("method") instanceof Map<?,?> m)locals.add(String.valueOf(m.get("from_target")));
        }
        if(manifest.get("opens") instanceof List<?> opens)for(Object o:opens)locals.add(String.valueOf(o));
        // A field's owner and any Bitwig class in its descriptor; its name is the mod's own and has no row.
        if(manifest.get("fields") instanceof List<?> fields)for(Object o:fields){
            if(!(o instanceof Map<?,?> f))continue;
            locals.add(String.valueOf(f.get("owner")));
            if(f.get("descriptor") instanceof String d)for(String type:ModFields.types(d))
                if(!ModFields.jdkType(type))out.put("C|"+type,dot(type));
        }
        for(String local:locals){
            if(!(targets.get(local) instanceof Map<?,?> raw))continue;
            Map<String,Object> t=SimpleJson.object(raw);
            String kind=String.valueOf(t.get("kind"));
            if(kind.equals("class")&&t.get("class") instanceof String c)out.put("C|"+c.replace('.','/'),dot(c));
            else if((kind.equals("method")||kind.equals("field"))&&t.get("owner") instanceof String owner
                    &&t.get("name") instanceof String name&&t.get("descriptor") instanceof String desc){
                String o=owner.replace('.','/');
                out.put((kind.equals("method")?"M|":"F|")+o+"|"+name+"|"+desc,dot(o)+"."+name+(kind.equals("method")?desc:""));
            }
        }
        // uses.tsv (tools/mod_uses.py): C <class>; F|M <owner> <name> <desc> <declaring class, "-" for the JDK>
        Path uses=modDir.resolve("uses.tsv");
        if(Files.isRegularFile(uses,LinkOption.NOFOLLOW_LINKS))for(String line:Files.readAllLines(uses,StandardCharsets.UTF_8)){
            String[] f=line.split("\t",-1);
            if(f.length==2&&f[0].equals("C"))out.put("C|"+f[1],dot(f[1]));
            else if(f.length==5&&(f[0].equals("M")||f[0].equals("F"))&&!f[4].equals("-")&&!f[4].equals("?"))
                out.put(f[0]+"|"+f[4]+"|"+f[2]+"|"+f[3],dot(f[4])+"."+f[2]+(f[0].equals("M")?f[3]:""));
        }
        return out;
    }

    /** Mod ID -> why it isn't available, for the mods (ID -> needs) whose needs {@code remap} lacks.
     *  {@code runtime} names the installed Bitwig for the reason, e.g. "Bitwig 6.1.4 for Windows". */
    static Map<String,String> gaps(Path remap,Map<String,Map<String,String>> needsByMod,String runtime) throws IOException {
        Set<String> wanted=new HashSet<>();Set<String> packages=new HashSet<>();
        for(Map<String,String> needs:needsByMod.values())for(String k:needs.keySet()){
            wanted.add(k);
            if(k.startsWith("C|"))packages.add(pkg(k.substring(2)));
        }
        if(wanted.isEmpty())return Map.of();
        String cacheKey=remap.toAbsolutePath()+"|"+Files.size(remap)+"|"+Files.getLastModifiedTime(remap).toMillis()+"|"+runtime+"|"+needsByMod.hashCode();
        Map<String,String> cached=CACHE.get(cacheKey);
        if(cached!=null)return cached;
        Set<String> found=new HashSet<>();Set<String> mappedPackages=new HashSet<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(remap)),StandardCharsets.UTF_8))){
            for(String line;(line=r.readLine())!=null;){
                String[] f=line.split("\t",5);
                if(f.length<3)continue;
                String id=switch(f[0]){case "C"->"C|"+f[1];case "M","F"->f.length<4?null:f[0]+"|"+f[1]+"|"+f[2]+"|"+f[3];default->null;};
                if(id==null)continue;
                if(wanted.contains(id))found.add(id);
                if(f[0].equals("C")){String p=pkg(f[1]);if(packages.contains(p))mappedPackages.add(p);}
            }
        }
        Map<String,String> out=new TreeMap<>();
        for(var mod:needsByMod.entrySet()){
            List<String> missing=new ArrayList<>();
            for(String k:mod.getValue().keySet())if(!found.contains(k))missing.add(k);
            if(missing.isEmpty())continue;
            // A class from a package with no counterpart at all (one platform's own code) says the most;
            // otherwise the first class, then the first member.
            java.util.function.Predicate<String> noCounterpart=k->k.startsWith("C|")&&!pkg(k.substring(2)).isEmpty()
                &&!mappedPackages.contains(pkg(k.substring(2)));
            missing.sort(Comparator.comparing((String k)->!noCounterpart.test(k)).thenComparing(k->!k.startsWith("C|")).thenComparing(k->k));
            String first=missing.get(0), more=missing.size()>1?" and "+(missing.size()-1)+" more":"";
            String p=pkg(first.substring(2));
            out.put(mod.getKey(),noCounterpart.test(first)
                ?"it uses "+dot(p)+" ("+simple(first.substring(2))+more+"), which has no counterpart in "+runtime
                :"it uses "+mod.getValue().get(first)+more+", which the map can't translate to "+runtime);
        }
        out=Collections.unmodifiableMap(out);
        if(CACHE.size()>16)CACHE.clear();
        CACHE.put(cacheKey,out);
        return out;
    }
    private static String pkg(String internal){int i=internal.lastIndexOf('/');return i<0?"":internal.substring(0,i);}
    private static String simple(String internal){return internal.substring(internal.lastIndexOf('/')+1);}
    private static String dot(String s){return s.replace('/','.');}
}
