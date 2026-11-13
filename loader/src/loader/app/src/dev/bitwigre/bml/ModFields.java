package dev.bitwigre.bml;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The instance fields a mod adds to Bitwig classes: "fields" in its manifest. This reads what the
 * manifest alone can decide, for the window and the preparer alike; what depends on the class is
 * refused by the FieldAdder tool at preparation.
 */
final class ModFields {
    private ModFields(){}
    record Field(String id,String owner,String name,String descriptor) {}
    private static final Pattern DESCRIPTOR=Pattern.compile("\\[*(?:[ZBCSIJFD]|L[A-Za-z0-9_$/]+;)");
    private static final Pattern REFERENCE=Pattern.compile("L([^;]+);");
    private static final Set<String> KEYS=Set.of("id","owner","name","descriptor");

    /** Every field name of a mod starts with this: mod IDs are unique, so two mods can't collide by accident. */
    static String namespace(String modId) {return modId.replaceAll("[^A-Za-z0-9]","_")+"$";}
    /** The classes named in a field descriptor. */
    static List<String> types(String descriptor) {
        List<String> out=new ArrayList<>();
        Matcher m=REFERENCE.matcher(descriptor);
        while(m.find())out.add(m.group(1));
        return out;
    }
    /** A JDK type: the same on every Bitwig build. Anything else in a descriptor is a Bitwig class target. */
    static boolean jdkType(String internal) {return internal.startsWith("java/");}

    static List<Field> parse(Map<String,Object> manifest,String modId) {
        if(manifest.get("fields")==null)return List.of();
        if(!(manifest.get("fields") instanceof List<?> raw))throw new IllegalArgumentException("fields must be an array");
        Map<String,Object> targets=SimpleJson.object(manifest.get("targets"));
        Set<String> classTargets=new HashSet<>(),ids=new HashSet<>();
        for(Object t:targets.values())if(t instanceof Map<?,?> m&&"class".equals(m.get("kind"))&&m.get("class") instanceof String c)classTargets.add(c.replace('.','/'));
        List<Field> out=new ArrayList<>();
        String prefix=namespace(modId);
        for(Object o:raw) {
            if(!(o instanceof Map<?,?> m))throw new IllegalArgumentException("fields entry must be an object");
            Map<String,Object> f=SimpleJson.object(m);
            String id=text(f,"id"),context="field "+id+" of "+modId;
            for(String key:f.keySet())if(!KEYS.contains(key))throw new IllegalArgumentException(context+": unknown key "+key);
            if(!id.matches("[A-Za-z][A-Za-z0-9_.-]*")||!ids.add(id))throw new IllegalArgumentException(context+": invalid or repeated id");
            String owner=text(f,"owner"),name=text(f,"name"),descriptor=text(f,"descriptor");
            if(!(targets.get(owner) instanceof Map<?,?> t)||!"class".equals(t.get("kind")))
                throw new IllegalArgumentException(context+": owner '"+owner+"' must be a class target of the mod");
            if(!name.startsWith(prefix)||!name.substring(prefix.length()).matches("[A-Za-z_][A-Za-z0-9_$]*"))
                throw new IllegalArgumentException(context+": the name must be "+prefix+"<name>, not "+name);
            if(!DESCRIPTOR.matcher(descriptor).matches())throw new IllegalArgumentException(context+": invalid descriptor "+descriptor);
            for(String type:types(descriptor))if(!jdkType(type)&&!classTargets.contains(type))
                throw new IllegalArgumentException(context+": "+type+" in the descriptor must be a class target of the mod; for the mod's own types use Object");
            out.add(new Field(id,owner,name,descriptor));
        }
        return out;
    }
    private static String text(Map<String,Object> f,String key) {
        if(f.get(key) instanceof String s&&!s.isBlank())return s;
        throw new IllegalArgumentException("fields entry needs "+key);
    }
}
