package dev.bitwigre.bml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/**
 * Add Mod: installs a mod ZIP into mods/. A ZIP is checked whole before anything is written: one
 * top folder, a mod.json in it whose mod.id is the folder's name, every entry inside that folder
 * (no absolute paths, drive letters, backslashes or "." / ".." parts), no two entries that differ
 * only in case. It is unpacked into a staging folder in mods/ and moved into place; a mod already
 * installed is moved aside first and put back if the move fails, so nothing is left half-installed.
 * Ticks and settings live outside the mod's folder and stay. src/ isn't required: that rule is for
 * our own releases only.
 */
final class ModInstaller {
    private ModInstaller(){}
    /** Staging folders start with this; one left behind by a crash is removed by the next add. */
    static final String STAGING_PREFIX=".patchbay-add-";
    private static final long MAX_UNPACKED=2_000_000_000L;

    /** A ZIP that passed the checks. {@code installedVersion} is null for a new mod. */
    record Checked(Path zip,String id,String name,String version,String installedVersion){
        boolean replaces(){return installedVersion!=null;}
    }

    /** Checks {@code zip} against what is in {@code mods}; the message of the exception is the
     *  one-line reason it is refused. */
    static Checked check(Path zip,Path mods) throws IOException {
        if(!Files.isRegularFile(zip))throw new IOException("the file can't be found");
        String top=null;
        Set<String> seen=new HashSet<>();
        Map<String,Object> manifest=null;
        try(ZipFile z=new ZipFile(zip.toFile(),StandardCharsets.UTF_8)){
            Enumeration<? extends ZipEntry> all=z.entries();
            while(all.hasMoreElements()){
                ZipEntry entry=all.nextElement();
                String name=entry.getName();
                if(name.isEmpty()||name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf(':')>=0)
                    throw new IOException("it has an unsafe path ("+name+")");
                String[] parts=name.split("/");
                for(String p:parts)if(p.isEmpty()||p.equals(".")||p.equals(".."))
                    throw new IOException("it has an unsafe path ("+name+")");
                if(top==null)top=parts[0];
                else if(!top.equals(parts[0]))throw new IOException("it has more than one top folder ("+top+", "+parts[0]+")");
                if(parts.length==1&&!entry.isDirectory())throw new IOException("it has a file outside the mod's folder ("+name+")");
                if(!seen.add(name.toLowerCase(Locale.ROOT)))throw new IOException("it has two entries named "+name);
                if(name.equals(top+"/mod.json")){
                    try(InputStream in=z.getInputStream(entry)){
                        manifest=SimpleJson.object(SimpleJson.parse(new String(in.readNBytes(4_000_000),StandardCharsets.UTF_8)));
                    }catch(RuntimeException bad){throw new IOException("its mod.json can't be read ("+bad.getMessage()+")");}
                }
            }
        }catch(ZipException notZip){throw new IOException("it isn't a readable ZIP");}
        if(top==null)throw new IOException("it is empty");
        if(top.startsWith("."))throw new IOException("its folder name starts with a dot");
        if(manifest==null)throw new IOException("there's no mod.json in "+top+"/");
        Map<String,Object> mod;
        try{mod=SimpleJson.object(manifest.get("mod"));}catch(RuntimeException bad){throw new IOException("its mod.json has no mod section");}
        String id=text(mod,"id"),name=text(mod,"name"),version=text(mod,"version");
        if(id==null)throw new IOException("its mod.json has no mod.id");
        if(!id.equals(top))throw new IOException("its folder "+top+" doesn't match its mod ID "+id);
        Path target=mods.resolve(id);
        String installed=null;
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(target))
                throw new IOException("mods"+File.separator+id+" is in the way and isn't a mod folder");
            // A folder that differs only in case is another folder on Linux, and the same one on Windows and macOS.
            String actual=target.toRealPath().getFileName().toString();
            if(!actual.equals(id))throw new IOException("mods"+File.separator+actual+" is in the way (its name differs only in case)");
            installed=installedVersion(target);
        }
        try(Stream<Path> others=Files.list(mods)){
            for(Path other:others.filter(Files::isDirectory).toList()){
                String folder=other.getFileName().toString();
                if(folder.equals(id)||folder.startsWith(STAGING_PREFIX))continue;
                if(id.equals(installedId(other)))throw new IOException(name(name,id)+" is already installed in mods"+File.separator+folder+"; remove that folder first");
            }
        }
        return new Checked(zip,id,name(name,id),version==null?"":version,installed);
    }

    /** Unpacks {@code c} and moves it into mods/; an installed copy is replaced. */
    static void install(Checked c,Path mods) throws IOException {
        Path staging=Files.createTempDirectory(mods,STAGING_PREFIX);
        try{
            long total=0;
            try(ZipFile z=new ZipFile(c.zip().toFile(),StandardCharsets.UTF_8)){
                Enumeration<? extends ZipEntry> all=z.entries();
                byte[] buf=new byte[65536];
                while(all.hasMoreElements()){
                    ZipEntry entry=all.nextElement();
                    Path out=staging.resolve(entry.getName()).normalize();
                    if(!out.startsWith(staging.resolve(c.id())))throw new IOException("it has an unsafe path ("+entry.getName()+")");
                    if(entry.isDirectory()){Files.createDirectories(out);continue;}
                    Files.createDirectories(out.getParent());
                    try(InputStream in=z.getInputStream(entry);OutputStream o=Files.newOutputStream(out,StandardOpenOption.CREATE_NEW)){
                        for(int n;(n=in.read(buf))!=-1;){
                            total+=n;
                            if(total>MAX_UNPACKED)throw new IOException("it unpacks to more than 2 GB");
                            o.write(buf,0,n);
                        }
                    }
                }
            }
            Path fresh=staging.resolve(c.id()),target=mods.resolve(c.id()),old=staging.resolve("previous");
            if(!Files.isRegularFile(fresh.resolve("mod.json")))throw new IOException("there's no mod.json in "+c.id()+"/");
            boolean replacing=Files.exists(target,LinkOption.NOFOLLOW_LINKS);
            if(replacing){
                try{Files.move(target,old);}
                catch(IOException locked){throw new IOException("the installed "+c.name()+" can't be moved aside (a file in it is in use?)");}
            }
            try{Files.move(fresh,target);}
            catch(IOException ex){
                if(replacing)Files.move(old,target);
                throw new IOException(c.name()+" can't be moved into mods ("+ex.getMessage()+")");
            }
        }finally{
            deleteTree(staging);
        }
    }

    /** Removes staging folders a crash left in mods/. */
    static void removeLeftovers(Path mods){
        try(Stream<Path> s=Files.list(mods)){
            for(Path p:s.filter(p->p.getFileName().toString().startsWith(STAGING_PREFIX)).toList())deleteTree(p);
        }catch(IOException ignored){}
    }

    private static String installedVersion(Path dir){
        Map<String,Object> mod=installedMod(dir);
        String v=mod==null?null:text(mod,"version");
        return v==null?"unknown version":v;
    }
    private static String installedId(Path dir){
        Map<String,Object> mod=installedMod(dir);
        return mod==null?null:text(mod,"id");
    }
    private static Map<String,Object> installedMod(Path dir){
        try{return SimpleJson.object(SimpleJson.object(SimpleJson.parse(Files.readString(dir.resolve("mod.json"),StandardCharsets.UTF_8))).get("mod"));}
        catch(IOException|RuntimeException unreadable){return null;}
    }
    private static String text(Map<String,Object> m,String key){
        return m.get(key) instanceof String s&&!s.isBlank()?s:null;
    }
    private static String name(String name,String id){return name==null?id:name;}

    private static void deleteTree(Path dir) throws IOException {
        if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return;
        try(Stream<Path> paths=Files.walk(dir)){
            for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);
        }
    }
}
