package dev.bitwigre.bml;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * The folders the framework declaring the "device-folders" tool (Framework: Custom Nitro
 * Registration) scans for the user's own devices. BML only edits the list, from that framework's
 * selection bar ("Device folders…"): finding the devices and reading them is the framework's, at
 * each start of Bitwig. A listed folder that is gone or can't be read is a problem: while the
 * framework is ticked, Load is blocked until the folder is back or removed from the list.
 */
final class DeviceFoldersTool implements BmlTools.Tool {
    static final String ID="device-folders";

    private static final String REQUIRES="Framework: Custom Nitro Registration";

    @Override public String name(){return "Device Folders";}

    @Override public String unavailable(Path modsRoot){
        try{if(FolderListConfig.find(modsRoot,ID)!=null)return null;}catch(IOException unreadable){}
        return name()+" needs "+REQUIRES+", which isn't installed.";
    }

    /** The list of the installed mod declaring the tool, or null when none does. */
    static FolderListConfig config(Path modsRoot){
        try{return FolderListConfig.find(modsRoot,ID);}catch(IOException unreadable){return null;}
    }

    /** A list that can't be read has no folders to check; the dialog says so. */
    @Override public List<BmlTools.Problem> problems(Path modsRoot){
        try{
            FolderListConfig config=FolderListConfig.find(modsRoot,ID);
            if(config==null)return List.of();
            return problems(config,FolderListConfig.check(new LinkedHashSet<>(config.read())));
        }catch(IOException unreadable){return List.of();}
    }
    List<BmlTools.Problem> problems(FolderListConfig config,Map<String,FolderListConfig.FolderState> states){
        List<BmlTools.Problem> problems=new ArrayList<>();
        for(var state:states.entrySet()){
            String folder=state.getKey();
            if(state.getValue()==FolderListConfig.FolderState.OK)continue;
            boolean missing=state.getValue()==FolderListConfig.FolderState.MISSING;
            problems.add(new BmlTools.Problem(name(),config.modId,new PackageInventory.Issue(
                missing?"DEVICE_FOLDER_MISSING":"DEVICE_FOLDER_UNREADABLE",PackageInventory.Severity.BLOCKING,config.directory,
                missing?"Device folder not found: "+folder:"Device folder can't be read: "+folder,
                missing?"Reconnect or restore the folder, or remove it from the device folders."
                    :"Give your user access to the folder, or remove it from the device folders."),folder));
        }
        return problems;
    }

    @Override public String resolveLabel(){return "Remove Folder";}

    @Override public void resolve(Path modsRoot,BmlTools.Problem problem) throws IOException {
        FolderListConfig config=FolderListConfig.find(modsRoot,ID);
        if(config==null)throw new IOException(REQUIRES+" isn't installed.");
        List<String> next=new ArrayList<>(config.read());next.removeIf(problem.subject()::equals);
        config.write(next);
    }
}
