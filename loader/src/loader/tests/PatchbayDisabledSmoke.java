package dev.bitwigre.bml;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * Disable Mod in the Patchbay window, without starting Bitwig: builds the window over a test
 * install (one with config/portable, so nothing outside it is touched), reads its mods and drives
 * Disable Mod, Show > Disabled, Enable Mod and the prerequisite rules through the window's own code.
 *   javac -cp bml-control.jar -d tests-classes loader/tests/PatchbayDisabledSmoke.java
 *   java -cp "bml-control.jar;tests-classes" dev.bitwigre.bml.PatchbayDisabledSmoke <test install> [png folder]
 * Adds a mod "zz-disable-smoke" that needs a mod that isn't installed (so it has a blocking error)
 * and removes it again. Prints DISABLE_SMOKE_OK.
 */
public class PatchbayDisabledSmoke {
    static PatchbayWindow w;
    public static void main(String[] a) throws Exception {
        Path root=Path.of(a[0]).toAbsolutePath().normalize();
        Path out=a.length>1?Path.of(a[1]):null;
        require(Files.exists(root.resolve("config").resolve(BmlPaths.TEST_INSTALL_MARKER)),"a test install (config/portable)");
        Path broken=root.resolve("mods").resolve("zz-disable-smoke");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("mod.json"),"{\"manifest_version\":2,\"mod\":{\"id\":\"zz-disable-smoke\",\"version\":\"1.0.0\",\"name\":\"Test: Disable smoke\","
            +"\"author\":\"Smoke\",\"description\":\"Needs a mod that isn't installed.\"},\"requires\":[{\"id\":\"zz-not-installed\"}],"
            +"\"built_against\":{\"bitwig_version\":\"6.1.4\",\"platform\":\"windows\",\"architecture\":\"x64\"},\"targets\":{},\"hooks\":[],"
            +"\"trace_targets\":[{\"id\":\"self\",\"class\":\"example.Helper\",\"method\":\"run\",\"descriptor\":\"()V\"}]}");
        try{run(root,out);}finally{
            try(var s=Files.walk(broken)){for(Path p:s.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
        System.out.println("DISABLE_SMOKE_OK");
        System.exit(0);
    }

    static void run(Path root,Path out) throws Exception {
        BmlPaths paths=new BmlPaths(root);
        // Start from nothing disabled and Show on All.
        on(()->{
            BmlConfig c=new BmlConfig(paths);c.load();c.setDisabledMods(List.of("zz-kept-not-installed"));c.setModsShow("All");c.save();
            PatchbayStyle.install();
            w=new PatchbayWindow(new BmlService(paths));
            call("readEverything",new Class<?>[]{boolean.class},true);
        });
        settle();
        String smoke="zz-disable-smoke";
        on(()->{
            // Ticked, as a newly found mod is (an earlier run may have saved it unticked).
            requested().put(smoke,true);call("saveTicks",new Class<?>[0]);call("recompute",new Class<?>[0]);
            require(rows().contains(smoke),"the broken test mod is listed");
            require(issuesFor(smoke)>0,"its error is on Errors");
            boolean ticked=(boolean)call("ticked",new Class<?>[]{PackageInventory.Entry.class},entry(smoke));
            require(ticked&&blockers()>0,"ticked, it blocks Load");
        });
        int before=rowsOnEdt().size();
        // Disable it: gone from the list, its errors gone, Load no longer blocked by it.
        on(()->call("disableMod",new Class<?>[]{PackageInventory.Entry.class},entry(smoke)));
        on(()->{
            require(!rows().contains(smoke)&&rows().size()==before-1,"disabled, it leaves the list");
            require(issuesFor(smoke)==0,"its errors are hidden");
            require(blockers()==0,"it no longer blocks Load: "+blockerText());
            require(!(boolean)call("ticked",new Class<?>[]{PackageInventory.Entry.class},entry(smoke)),"a disabled mod is never ticked");
            require(text("modsMeta").contains("1 disabled"),"the count says so: "+text("modsMeta"));
        });
        String props=Files.readString(paths.configFile);
        require(props.contains("mods.disabled=zz-disable-smoke,zz-kept-not-installed"),"kept in bml.properties, an absent ID kept");
        require(Files.readString(paths.enabledModsFile).contains("mod.zz-disable-smoke=false"),"its tick is cleared");
        if(out!=null)shot(out.resolve("disabled-hidden.png"));
        // Show > Disabled: only the disabled mods, dimmed, still no errors; no "Disabled" note in the row.
        on(()->showChoice().setSelectedItem("Disabled"));
        on(()->{
            require(rows().equals(List.of(smoke)),"Show > Disabled lists only the disabled mod: "+rows());
            require(issuesFor(smoke)==0&&blockers()==0,"its errors stay hidden");
            require(!descriptionCell(0).contains("Disabled"),"the row has no Disabled note: "+descriptionCell(0));
        });
        require(Files.readString(paths.configFile).contains("ui.mods.show=Disabled"),"the choice is remembered");
        for(String other:List.of("All","Ticked","Unticked")){
            on(()->{showChoice().setSelectedItem(other);require(!rows().contains(smoke),"Show > "+other+" leaves it out");});
        }
        on(()->showChoice().setSelectedItem("Disabled"));
        on(()->select(smoke));
        if(out!=null)shot(out.resolve("disabled-shown-selected.png"));
        // A new window reads the same state.
        on(()->{
            PatchbayWindow first=w;
            w=new PatchbayWindow(new BmlService(paths));
            call("readEverything",new Class<?>[]{boolean.class},true);
            ((Window)fieldOf(first,"frame")).dispose();
        });
        settle();
        on(()->require("Disabled".equals(showChoice().getSelectedItem())&&rows().equals(List.of(smoke))&&issuesFor(smoke)==0,"state survives a restart"));
        // Enable it again: unticked, its error back (as information only while unticked).
        on(()->call("enableMod",new Class<?>[]{PackageInventory.Entry.class},entry(smoke)));
        on(()->{
            require(!(boolean)call("ticked",new Class<?>[]{PackageInventory.Entry.class},entry(smoke)),"enabled again, it is unticked");
            require(issuesFor(smoke)>0,"its error shows again");
            require(!text("modsMeta").contains("disabled"),"no disabled count");
            require(rows().isEmpty(),"enabled, it leaves the Disabled list");
            showChoice().setSelectedItem("All");
            require(rows().contains(smoke),"and is under All again");
        });
        require(Files.readString(paths.configFile).contains("mods.disabled=zz-kept-not-installed"),"only the absent ID stays");

        // Prerequisites: find a mod that needs another installed one.
        PackageInventory.Entry[] pair=new PackageInventory.Entry[2];
        on(()->{
            for(PackageInventory.Entry e:entries())for(String id:e.requires.keySet()){
                PackageInventory.Entry p=entry(id);
                if(p!=null&&p.loadable()&&e.loadable()&&pair[0]==null){pair[0]=e;pair[1]=p;}
            }
        });
        require(pair[0]!=null,"the suite has a mod with a prerequisite");
        PackageInventory.Entry dep=pair[0],pre=pair[1];
        System.out.println("prerequisite pair: "+dep.id+" needs "+pre.id);
        on(()->{
            tick(dep,true);
            String why=(String)call("whyNotDisable",new Class<?>[]{PackageInventory.Entry.class},pre);
            require(why!=null&&why.contains(dep.name),"a ticked mod's prerequisite can't be disabled: "+why);
            call("disableMod",new Class<?>[]{PackageInventory.Entry.class},pre);
            require(!disabled().contains(pre.id),"Disable Mod refuses it");
            require(text("status").contains("can't be disabled"),"the status says why: "+text("status"));
        });
        on(()->{select(pre.id);});
        if(out!=null)shot(out.resolve("prerequisite-greyed.png"));
        on(()->{
            // Untick every mod: now the prerequisite can be disabled.
            for(PackageInventory.Entry e:entries())requested().put(e.id,false);
            call("recompute",new Class<?>[0]);
            require(call("whyNotDisable",new Class<?>[]{PackageInventory.Entry.class},pre)==null,"with nothing ticked it can be disabled");
            call("disableMod",new Class<?>[]{PackageInventory.Entry.class},pre);
            require(disabled().contains(pre.id),"disabled");
            // Ticking the mod that needs it enables it again and ticks it.
            tick(dep,true);
            require(!disabled().contains(pre.id),"ticking a mod enables its disabled prerequisite again");
            require((boolean)call("ticked",new Class<?>[]{PackageInventory.Entry.class},pre),"and ticks it");
            require(text("status").contains(pre.name+" (enabled again)"),"the status says so: "+text("status"));
        });
        require(!Files.readString(paths.configFile).contains(pre.id),"saved");
        on(()->((Window)field("frame")).dispose());
    }

    // ---- helpers --------------------------------------------------------------------------
    interface Body{void run() throws Exception;}
    static void on(Body b) throws Exception {
        Exception[] err=new Exception[1];
        SwingUtilities.invokeAndWait(()->{try{b.run();}catch(Exception e){err[0]=e;}});
        if(err[0]!=null)throw err[0];
    }
    static void settle() throws Exception {
        for(int i=0;i<200;i++){
            boolean[] done=new boolean[1];
            on(()->done[0]=(boolean)field("loaded"));
            if(done[0]){Thread.sleep(300);on(()->{});return;}
            Thread.sleep(50);
        }
        throw new IllegalStateException("the window never read its mods");
    }
    static void require(boolean ok,String what){if(!ok)throw new IllegalStateException("FAILED: "+what);System.out.println("ok  "+what);}
    static Object field(String name) throws Exception {return fieldOf(w,name);}
    static Object fieldOf(Object o,String name) throws Exception {Field f=PatchbayWindow.class.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static Object call(String name,Class<?>[] types,Object... args) throws Exception {
        Method m=PatchbayWindow.class.getDeclaredMethod(name,types);m.setAccessible(true);
        try{return m.invoke(w,args);}catch(InvocationTargetException e){throw e.getCause() instanceof Exception x?x:e;}
    }
    @SuppressWarnings("unchecked") static Set<String> disabled() throws Exception {return (Set<String>)field("disabled");}
    @SuppressWarnings("unchecked") static Map<String,Boolean> requested() throws Exception {return (Map<String,Boolean>)field("requested");}
    static List<PackageInventory.Entry> entries() throws Exception {return ((PackageInventory.Snapshot)field("snapshot")).entries();}
    static PackageInventory.Entry entry(String id) throws Exception {return ((PackageInventory.Snapshot)field("snapshot")).find(id);}
    static List<String> rows() throws Exception {
        JTable t=(JTable)field("modsTable");Object model=t.getModel();
        Field rows=model.getClass().getDeclaredField("rows");rows.setAccessible(true);
        List<String> ids=new ArrayList<>();
        for(Object e:(List<?>)rows.get(model))ids.add(((PackageInventory.Entry)e).id);
        return ids;
    }
    static List<String> rowsOnEdt() throws Exception {List<String> r=new ArrayList<>();on(()->r.addAll(rows()));return r;}
    @SuppressWarnings("unchecked") static long issuesFor(String id) throws Exception {
        Path dir=entry(id).directory;
        return ((List<PackageInventory.Issue>)field("allIssues")).stream().filter(i->i.packagePath().equals(dir)).count();
    }
    @SuppressWarnings("unchecked") static int blockers() throws Exception {return ((List<PackageInventory.Issue>)field("blockers")).size();}
    @SuppressWarnings("unchecked") static String blockerText() throws Exception {return ((List<PackageInventory.Issue>)field("blockers")).toString();}
    @SuppressWarnings("unchecked") static JComboBox<String> showChoice() throws Exception {return (JComboBox<String>)field("show");}
    static String descriptionCell(int row) throws Exception {
        JTable t=(JTable)field("modsTable");
        Component c=t.getCellRenderer(row,2).getTableCellRendererComponent(t,t.getValueAt(row,2),false,false,row,2);
        Field a=c.getClass().getDeclaredField("a"),b=c.getClass().getDeclaredField("b");a.setAccessible(true);b.setAccessible(true);
        return a.get(c)+"|"+b.get(c);
    }
    static String text(String label) throws Exception {return ((JLabel)field(label)).getText();}
    static void tick(PackageInventory.Entry e,boolean on) throws Exception {
        call("changeTick",new Class<?>[]{PackageInventory.Entry.class,boolean.class},e,on);
    }
    static void select(String id) throws Exception {
        JTable t=(JTable)field("modsTable");int i=rows().indexOf(id);
        if(i>=0)t.setRowSelectionInterval(i,i);
    }
    static void shot(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Thread.sleep(300);
        on(()->{
            JFrame f=(JFrame)field("frame");
            f.setSize(1250,835);
            Container c=f.getContentPane();c.setSize(1234,796);
            layout(c);
            BufferedImage img=new BufferedImage(c.getWidth(),c.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=img.createGraphics();c.paint(g);g.dispose();
            ImageIO.write(img,"png",file.toFile());
        });
    }
    static void layout(Component c){c.doLayout();if(c instanceof Container k)for(Component x:k.getComponents())layout(x);}
}
