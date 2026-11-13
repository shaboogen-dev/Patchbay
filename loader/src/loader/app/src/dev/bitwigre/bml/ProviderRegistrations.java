package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Declarative package registrations, not bytecode hooks or traces. */
final class ProviderRegistrations {
    record Entry(String id,String consumer,Path descriptor,Path jar) {}
    static List<Entry> parse(Map<String,Object> manifest,Path modFolder) throws IOException {
        Object raw=manifest.get("registrations");
        if(raw==null)return List.of();
        if(!(raw instanceof List<?> list))throw new IOException("registrations must be an array");
        HashSet<String> ids=new HashSet<>();
        ArrayList<Entry> result=new ArrayList<>();
        for(Object item:list){
            Map<String,Object> m;
            try{m=SimpleJson.object(item);}
            catch(RuntimeException ex){throw new IOException("registration must be an object",ex);}
            if(!m.keySet().equals(Set.of("id","consumer","descriptor","jar")))
                throw new IOException("registration requires exactly id, consumer, descriptor, jar");
            String id=value(m,"id"), consumer=value(m,"consumer");
            if(!id.matches("[A-Za-z][A-Za-z0-9_.-]*")||
               !consumer.matches("[A-Za-z][A-Za-z0-9_.-]*"))
                throw new IOException("Invalid registration ID or consumer: "+id);
            if(!ids.add(id))throw new IOException("Duplicate registration id: "+id);
            String jarValue=value(m,"jar");
            if(!jarValue.toLowerCase(Locale.ROOT).endsWith(".jar"))
                throw new IOException("Registration jar must have .jar extension: "+id);
            Path descriptor=PackageInventory.resolveInside(modFolder,value(m,"descriptor"),true,false);
            Path jar=PackageInventory.resolveInside(modFolder,jarValue,true,false);
            if(!Files.isRegularFile(descriptor,LinkOption.NOFOLLOW_LINKS)||
               !Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Missing registration file or jar: "+id);
            result.add(new Entry(id,consumer,descriptor,jar));
        }
        return List.copyOf(result);
    }
    private static String value(Map<String,Object> m,String key)throws IOException{
        if(!(m.get(key) instanceof String s)||s.isBlank())
            throw new IOException("registration."+key+" must be a nonblank string");
        return s;
    }
}
