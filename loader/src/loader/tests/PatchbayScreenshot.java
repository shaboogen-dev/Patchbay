package dev.bitwigre.bml;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;

/**
 * Renders each page of the Patchbay window to PNG (never starts Bitwig):
 *   java -cp bml-control.jar;tests dev.bitwigre.bml.PatchbayScreenshot <bml root> <out folder> [width height]
 * Writes mods.png, mods-selected.png, maps.png, errors.png, errors-selected.png, sessions.png,
 * sessions-selected.png, settings.png and reader.png (the first mod with a README). Paints at the screen's own scale.
 */
public class PatchbayScreenshot {
    public static void main(String[] a) throws Exception {
        Path root=Path.of(a[0]),out=Path.of(a[1]);Files.createDirectories(out);
        int w=a.length>2?Integer.parseInt(a[2]):1250,h=a.length>3?Integer.parseInt(a[3]):835;
        PatchbayWindow[] win=new PatchbayWindow[1];
        SwingUtilities.invokeAndWait(()->{try{
            PatchbayStyle.install();
            win[0]=new PatchbayWindow(new BmlService(new BmlPaths(root)));
            win[0].show();
            frame(win[0]).setSize(w,h);
        }catch(Exception e){throw new RuntimeException(e);}});
        Thread.sleep(2500);
        shot(win[0],"MODS",null,out.resolve("mods.png"));
        shot(win[0],"MODS",t->select(win[0],"modsTable",pickWithReadme(win[0])),out.resolve("mods-selected.png"));
        shot(win[0],"MAPS",t->select(win[0],"mapsTable",0),out.resolve("maps.png"));
        shot(win[0],"ERRORS",null,out.resolve("errors.png"));
        shot(win[0],"ERRORS",t->select(win[0],"errorsTable",0),out.resolve("errors-selected.png"));
        shot(win[0],"SESSIONS",null,out.resolve("sessions.png"));
        shot(win[0],"SESSIONS",t->select(win[0],"sessionsTable",0),out.resolve("sessions-selected.png"));
        shot(win[0],"SETTINGS",null,out.resolve("settings.png"));
        SwingUtilities.invokeAndWait(()->{try{
            activate(win[0],"MODS");select(win[0],"modsTable",pickWithReadme(win[0]));
            Method m=PatchbayWindow.class.getDeclaredMethod("openReadme",PackageInventory.Entry.class);m.setAccessible(true);
            JTable t=(JTable)field(win[0],"modsTable");
            Object model=t.getModel();Method get=model.getClass().getDeclaredMethod("get",int.class);get.setAccessible(true);
            m.invoke(win[0],get.invoke(model,t.getSelectedRow()));
        }catch(Exception e){throw new RuntimeException(e);}});
        Thread.sleep(800);
        save(frame(win[0]),out.resolve("reader.png"));
        System.exit(0);
    }
    interface Step{void run(Object t) throws Exception;}
    static void shot(PatchbayWindow w,String page,Step step,Path file) throws Exception {
        SwingUtilities.invokeAndWait(()->{try{activate(w,page);if(step!=null)step.run(null);}catch(Exception e){throw new RuntimeException(e);}});
        Thread.sleep(1500);
        save(frame(w),file);
    }
    static void save(JFrame f,Path file) throws Exception {
        SwingUtilities.invokeAndWait(()->{try{
            f.validate();
            double s=f.getGraphicsConfiguration().getDefaultTransform().getScaleX();
            Container c=f.getContentPane();
            BufferedImage img=new BufferedImage((int)Math.round(c.getWidth()*s),(int)Math.round(c.getHeight()*s),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=img.createGraphics();g.scale(s,s);c.paint(g);g.dispose();
            ImageIO.write(img,"png",file.toFile());
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    static int pickWithReadme(PatchbayWindow w) throws Exception {
        JTable t=(JTable)field(w,"modsTable");
        Object model=t.getModel();Method get=model.getClass().getDeclaredMethod("get",int.class);get.setAccessible(true);
        for(int i=0;i<t.getRowCount();i++){
            PackageInventory.Entry e=(PackageInventory.Entry)get.invoke(model,i);
            if(e.documentation!=null&&!e.groups.isEmpty())return i;
        }
        for(int i=0;i<t.getRowCount();i++)if(((PackageInventory.Entry)get.invoke(model,i)).documentation!=null)return i;
        return 0;
    }
    static void select(PatchbayWindow w,String table,int row) throws Exception {
        JTable t=(JTable)field(w,table);if(t.getRowCount()>row)t.setRowSelectionInterval(row,row);
    }
    static void activate(PatchbayWindow w,String page) throws Exception {
        Class<?> pages=Class.forName("dev.bitwigre.bml.PatchbayWindow$Page");
        Method m=PatchbayWindow.class.getDeclaredMethod("activate",pages);m.setAccessible(true);
        m.invoke(w,Enum.valueOf(pages.asSubclass(Enum.class),page));
    }
    static JFrame frame(PatchbayWindow w) throws Exception {return (JFrame)field(w,"frame");}
    static Object field(Object o,String name) throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
}
