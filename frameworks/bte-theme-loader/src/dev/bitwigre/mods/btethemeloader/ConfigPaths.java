package dev.bitwigre.mods.btethemeloader;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The selected theme component's files, from the loader's component bindings (BML_MOD_CONFIG;
 * the Bml API has no call for component groups). This mod ships no theme or artwork: the
 * .bte file and the optional overlay folder both come from the one selected component.
 */
final class ConfigPaths {
    private static final String PREFIX="component.bte-theme-loader.group.themes.";
    private ConfigPaths(){}

    private static void warn(String msg) {
        ThemeRuntime.MOD.log(msg);
    }
    private static Properties bindings() {
        try{
            String env=System.getenv("BML_MOD_CONFIG");
            if(env==null || env.isBlank()){warn("BML_MOD_CONFIG is unset");return null;}
            Path path=Path.of(env);
            if(!path.isAbsolute() || !Files.isRegularFile(path)){warn("Invalid BML_MOD_CONFIG file");return null;}
            Properties p=new Properties();
            try(var reader=new InputStreamReader(Files.newInputStream(path),StandardCharsets.UTF_8)){
                p.load(reader);
            }
            return p;
        }catch(Exception ex){warn("Cannot read prepared component bindings: "+ex.getClass().getSimpleName());return null;}
    }
    private static String selectedPrefix(Properties p){
        if(p==null)return null;
        if(!"single".equals(p.getProperty(PREFIX+"selection")) ||
           !"1".equals(p.getProperty(PREFIX+"interface_version")) ||
           !"1".equals(p.getProperty(PREFIX+"selected_count"))){
            warn("The Themes group needs one selected component (interface v1)");
            return null;
        }
        String id=p.getProperty(PREFIX+"selected.0.id","");
        if(id.isBlank()){warn("No selected theme component");return null;}
        return PREFIX+"selected.0.resource.";
    }
    private static Path location(Properties p,String base,String name,String kind,boolean required){
        String prefix=base+name+".";
        String gotKind=p.getProperty(prefix+"kind","");
        String string=p.getProperty(prefix+"path","");
        if(gotKind.isBlank() && string.isBlank() && !required)return null;
        if(!kind.equals(gotKind) || string.isBlank()){
            warn("Invalid selected component "+name+" binding");return null;
        }
        try{
            Path path=Path.of(string).toAbsolutePath().normalize();
            if(!Path.of(string).isAbsolute()){warn("Relative selected component "+name+" path rejected");return null;}
            boolean ok="file".equals(kind)?
                Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS):
                Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS);
            if(!ok){warn("Selected component "+name+" resource not present");return null;}
            return path;
        }catch(RuntimeException e){warn("Invalid component "+name+" path: "+e.getClass().getSimpleName());return null;}
    }
    /** The bindings, read once: both the theme and the overlays come from them. */
    private static Properties cached;
    private static boolean read;
    private static synchronized Properties bindingsOnce(){
        if(!read){cached=bindings();read=true;}
        return cached;
    }
    static Path selectedTheme(){
        Properties p=bindingsOnce();
        String base=selectedPrefix(p);
        return base==null?null:location(p,base,"theme","file",true);
    }
    static Path overlayRoot(){
        Properties p=bindingsOnce();
        String base=selectedPrefix(p);
        return base==null?null:location(p,base,"overlays","directory",false);
    }
}
