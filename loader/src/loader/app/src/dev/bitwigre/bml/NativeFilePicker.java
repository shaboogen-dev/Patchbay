package dev.bitwigre.bml;

import java.awt.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Opens a file with the system's own file dialog (java.awt.FileDialog, which is the Windows
 * common Open dialog on Windows) instead of Swing's JFileChooser. Each picker remembers the
 * folder it last opened from while the window runs; the first time it starts in the given
 * folder, else the user's Downloads, else their home folder. Folders are chosen with the same
 * dialog: it can't select a folder on Windows and Linux, so the user opens the folder and accepts
 * the name it offers.
 */
final class NativeFilePicker {
    private NativeFilePicker(){}
    private static final Map<String,Path> lastFolders=new HashMap<>();

    /** The chosen file, or null if the dialog was cancelled. {@code extension} filters the
     *  list (e.g. "zip"), or is null for any file. */
    static Path open(Frame owner,String key,String title,String extension,Path preferredFolder){
        FileDialog dialog=new FileDialog(owner,title,FileDialog.LOAD);
        Path folder=startFolder(key,preferredFolder);
        if(folder!=null)dialog.setDirectory(folder.toString());
        if(extension!=null){
            String suffix="."+extension.toLowerCase(Locale.ROOT);
            // Windows ignores FilenameFilter and filters by the pattern in the name box;
            // other platforms do the reverse. Set both.
            dialog.setFilenameFilter((dir,name)->name.toLowerCase(Locale.ROOT).endsWith(suffix));
            if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))dialog.setFile("*"+suffix);
        }
        dialog.setVisible(true);
        String name=dialog.getFile(),dir=dialog.getDirectory();
        dialog.dispose();
        if(name==null||dir==null)return null;
        Path chosen=Path.of(dir,name);
        if(chosen.getParent()!=null)lastFolders.put(key,chosen.getParent());
        return chosen;
    }

    /** The chosen files, several at once; empty if the dialog was cancelled. */
    static List<Path> openMany(Frame owner,String key,String title,String extension){
        FileDialog dialog=new FileDialog(owner,title,FileDialog.LOAD);
        dialog.setMultipleMode(true);
        Path folder=startFolder(key,null);
        if(folder!=null)dialog.setDirectory(folder.toString());
        if(extension!=null){
            String suffix="."+extension.toLowerCase(Locale.ROOT);
            dialog.setFilenameFilter((dir,name)->name.toLowerCase(Locale.ROOT).endsWith(suffix));
            if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))dialog.setFile("*"+suffix);
        }
        dialog.setVisible(true);
        File[] files=dialog.getFiles();
        dialog.dispose();
        List<Path> chosen=new ArrayList<>();
        for(File f:files)chosen.add(f.toPath());
        if(!chosen.isEmpty()&&chosen.get(0).getParent()!=null)lastFolders.put(key,chosen.get(0).getParent());
        return chosen;
    }

    /** The chosen folder, or null if the dialog was cancelled. */
    static Path openFolder(Frame owner,String key,String title){
        FileDialog dialog=new FileDialog(owner,title,FileDialog.LOAD);
        Path folder=startFolder(key,null);
        if(folder!=null)dialog.setDirectory(folder.toString());
        boolean mac=BmlPaths.MAC;
        if(mac)System.setProperty("apple.awt.fileDialogForDirectories","true");
        else dialog.setFile("Use this folder");
        try{
            dialog.setVisible(true);
        }finally{
            if(mac)System.clearProperty("apple.awt.fileDialogForDirectories");
        }
        String name=dialog.getFile(),dir=dialog.getDirectory();
        dialog.dispose();
        if(name==null||dir==null)return null;
        // macOS returns the folder itself. Elsewhere: the offered name, or a file picked by accident,
        // stands for the folder it was accepted in; a folder's name typed in stands for that folder.
        Path named=Path.of(dir,name);
        Path chosen=Files.isDirectory(named)?named:Path.of(dir);
        lastFolders.put(key,chosen);
        return chosen;
    }

    private static Path startFolder(String key,Path preferred){
        Path home=Path.of(System.getProperty("user.home",""));
        for(Path p:new Path[]{lastFolders.get(key),preferred,home.resolve("Downloads"),home})
            if(p!=null&&Files.isDirectory(p))return p;
        return null;
    }
}
