package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Optional Manifest v3.1 destinations (never package resources or input files). */
final class DiagnosticOutputs {
    record Output(String id,String kind,String relativePath) {}
    static List<Output> parse(Map<String,Object> manifest) throws IOException {
        if(!manifest.containsKey("diagnostics"))return List.of();
        if(!(manifest.get("manifest_version") instanceof Number version) || version.doubleValue()!=3.0 || !"mod".equals(manifest.get("package_type")))
            throw new IOException("diagnostics is supported only by executable Manifest v3 mod packages");
        Map<String,Object> raw=object(manifest.get("diagnostics"),"diagnostics");
        if(!raw.keySet().equals(Set.of("outputs")))throw new IOException("diagnostics requires exactly outputs");
        Map<String,Object> outputs=object(raw.get("outputs"),"diagnostics.outputs");
        if(outputs.isEmpty())throw new IOException("diagnostics.outputs must not be empty");
        ArrayList<Output> result=new ArrayList<>();
        Set<String> normalizedNames=new HashSet<>();
        Set<String> normalizedPaths=new HashSet<>();
        for(var e:outputs.entrySet()){
            String id=e.getKey();
            if(!id.matches("[A-Za-z][A-Za-z0-9_.-]*"))throw new IOException("Invalid diagnostic output ID: "+id);
            if(!normalizedNames.add(id.toLowerCase(Locale.ROOT)))throw new IOException("Case-insensitive diagnostic output ID collision: "+id);
            Map<String,Object> value=object(e.getValue(),"diagnostic output "+id);
            if(!value.keySet().equals(Set.of("kind","path")))throw new IOException("Diagnostic output "+id+" requires exactly kind and path");
            String kind=string(value.get("kind"),"kind");
            if(!Set.of("file","directory").contains(kind))throw new IOException("Unsupported diagnostic output kind: "+kind);
            String declared=string(value.get("path"),"path");
            String clean=safeRelative(declared);
            String caseFold=clean.toLowerCase(Locale.ROOT);
            if(!normalizedPaths.add(caseFold))throw new IOException("Diagnostic output path collision: "+declared);
            result.add(new Output(id,kind,clean));
        }
        for(Output a:result)for(Output b:result)if(a!=b){
            String ap=a.relativePath().toLowerCase(Locale.ROOT), bp=b.relativePath().toLowerCase(Locale.ROOT);
            if(bp.startsWith(ap+"/")&&a.kind().equals("file"))throw new IOException("Diagnostic output nested under file: "+a.id());
        }
        return List.copyOf(result);
    }
    private static Map<String,Object> object(Object o,String context) throws IOException {
        if(!(o instanceof Map<?,?> m))throw new IOException(context+" must be an object");
        Map<String,Object> result=new LinkedHashMap<>();
        for(var e:m.entrySet()){
            if(!(e.getKey() instanceof String s))throw new IOException(context+" contains non-string key");
            result.put(s,e.getValue());
        }
        return result;
    }
    private static String string(Object o,String name) throws IOException {
        if(!(o instanceof String s)||s.isBlank())throw new IOException("Diagnostic "+name+" must be a nonempty string");
        return s;
    }
    static String safeRelative(String declared) throws IOException {
        if(declared.indexOf('\\')>=0||declared.startsWith("/")||declared.contains(":")||declared.indexOf('\0')>=0)
            throw new IOException("Unsafe diagnostic output path: "+declared);
        String[] segments=declared.split("/",-1);
        for(String segment:segments){
            if(segment.isEmpty()||segment.equals(".")||segment.equals("..")||segment.endsWith(".")||segment.endsWith(" ")
                ||segment.chars().anyMatch(c->c<32||"<>\"|?*".indexOf(c)>=0))
                throw new IOException("Unsafe diagnostic output path segment: "+declared);
            String stem=segment.split("\\.",2)[0].toUpperCase(Locale.ROOT);
            if(stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))throw new IOException("Windows-reserved diagnostic output path: "+declared);
        }
        String clean=String.join("/",segments);
        if(clean.length()>220)throw new IOException("Diagnostic output path is too long");
        return clean;
    }
    static Path resolve(Path root,Output output) throws IOException {
        Path base=root.toAbsolutePath().normalize(), file=base.resolve(output.relativePath()).normalize();
        if(!file.startsWith(base)||file.equals(base))throw new IOException("Diagnostic output escaped session root");
        for(Path p=file;p!=null&&p.startsWith(base);p=p.getParent())
            if(Files.isSymbolicLink(p))throw new IOException("Symbolic link in diagnostic output path: "+p);
        return file;
    }
}
