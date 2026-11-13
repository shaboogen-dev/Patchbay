package dev.bitwigre.bml;

import java.util.*;
import java.util.regex.*;

/**
 * What a mod needs to load: every mod its registrations name as their consumer, and every mod in its
 * manifest's {@code requires} list, which may give a minimum version. A turned-on mod whose
 * prerequisite isn't installed, is too old or is turned off is an error, and BML doesn't launch until
 * it is fixed. One whose prerequisite isn't available on this Bitwig version isn't available either.
 */
final class Prerequisites {
    private static final Pattern ID=Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final Pattern SEMVER=Pattern.compile(
        "(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");
    private Prerequisites() {}

    /** Prerequisite mod ID -> minimum version ("" for any), sorted by ID. */
    static Map<String,String> parse(Map<String,Object> manifest,String self) {
        TreeMap<String,String> out=new TreeMap<>();
        if(manifest.get("registrations") instanceof List<?> regs)
            for(Object o:regs)
                if(o instanceof Map<?,?> m&&m.get("consumer") instanceof String c&&!c.equals(self))out.putIfAbsent(c,"");
        Object raw=manifest.get("requires");
        if(raw==null)return out;
        if(!(raw instanceof List<?> list))throw new IllegalArgumentException("requires must be an array");
        HashSet<String> seen=new HashSet<>();
        for(Object item:list){
            Map<String,Object> m;
            try{m=SimpleJson.object(item);}
            catch(RuntimeException ex){throw new IllegalArgumentException("requires entry must be an object");}
            if(!Set.of("id","min_version").containsAll(m.keySet()))
                throw new IllegalArgumentException("requires entry takes only id and min_version");
            if(!(m.get("id") instanceof String id)||!ID.matcher(id).matches())
                throw new IllegalArgumentException("requires entry needs a valid id");
            if(id.equals(self))throw new IllegalArgumentException("A mod can't require itself: "+id);
            if(!seen.add(id))throw new IllegalArgumentException("Duplicate requires entry: "+id);
            String min="";
            if(m.containsKey("min_version")){
                if(!(m.get("min_version") instanceof String v)||!SEMVER.matcher(v).matches())
                    throw new IllegalArgumentException("requires."+id+".min_version must be a semantic version");
                min=v;
            }
            out.put(id,min);
        }
        return out;
    }

    /** True when {@code version} is {@code min} or later (semantic version precedence). */
    static boolean atLeast(String version,String min) {
        if(min.isEmpty())return true;
        Matcher a=SEMVER.matcher(version),b=SEMVER.matcher(min);
        if(!a.matches()||!b.matches())return false;
        for(int i=1;i<=3;i++){
            int c=new java.math.BigInteger(a.group(i)).compareTo(new java.math.BigInteger(b.group(i)));
            if(c!=0)return c>0;
        }
        String pa=a.group(4),pb=b.group(4);
        if(pa==null||pb==null)return pa==null;  // a release is later than its pre-releases
        String[] x=pa.split("\\."),y=pb.split("\\.");
        for(int i=0;i<Math.min(x.length,y.length);i++){
            int c=comparePart(x[i],y[i]);
            if(c!=0)return c>0;
        }
        return x.length>=y.length;
    }
    private static int comparePart(String a,String b) {
        boolean na=a.chars().allMatch(Character::isDigit),nb=b.chars().allMatch(Character::isDigit);
        if(na&&nb)return new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
        if(na!=nb)return na?-1:1;
        return a.compareTo(b);
    }

    /**
     * The chosen mods that need something that isn't chosen: chosen mod ID -> the prerequisite IDs
     * that are turned off. Each is an error until the prerequisite is chosen or the mod isn't.
     * {@code needs}: chosen mod ID -> its prerequisite IDs.
     */
    static Map<String,List<String>> turnedOff(Map<String,? extends Collection<String>> needs) {
        TreeMap<String,List<String>> out=new TreeMap<>();
        for(var e:needs.entrySet()){
            List<String> off=new TreeSet<>(e.getValue()).stream().filter(p->!needs.containsKey(p)).toList();
            if(!off.isEmpty())out.put(e.getKey(),off);
        }
        return out;
    }
}
