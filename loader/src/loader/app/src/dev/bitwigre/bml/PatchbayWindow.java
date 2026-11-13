package dev.bitwigre.bml;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static dev.bitwigre.bml.PatchbayStyle.*;

/**
 * Patchbay, the control window: Mods, Maps, Errors, Sessions and Settings in the sidebar, each with
 * a count; the footer with Load, Stop / Quit and the status line. Selecting a row (click or Enter)
 * shows a selection bar with that item's options above the error strip; Esc clears it. A mod's
 * right-click menu has its own actions, Disable Mod among them: a disabled mod is set aside (not
 * listed unless Show is Disabled, never loaded, its errors hidden) until Enable Mod. Only tick
 * boxes tick. Long text (a README, a session's log) opens in the full-window reader. While an error
 * blocks Load, Load is greyed and a red strip says why. No motion, no tooltips.
 */
final class PatchbayWindow {
    enum Page {
        MODS("Mods"),MAPS("Maps"),ERRORS("Errors"),SESSIONS("Sessions"),SETTINGS("Settings");
        final String title;
        Page(String title){this.title=title;}
    }
    private static final DateTimeFormatter SESSION_DATE=DateTimeFormatter.ofPattern("d MMM uuuu, h:mm a").withZone(ZoneId.systemDefault());
    private static final String NO_SESSION_WINDOW="patchbay-window-";

    private final BmlService service;
    private final JFrame frame=new JFrame(AppText.NAME);
    private final CardLayout rootCards=new CardLayout();
    private final JPanel root=new JPanel(rootCards);
    private final CardLayout pageCards=new CardLayout();
    private final JPanel pages=new JPanel(pageCards);
    private final ReaderPanel reader=new ReaderPanel(this::closeReader);
    private Page page=Page.MODS;
    private final Map<Page,NavItem> nav=new EnumMap<>(Page.class);

    // ---- what was read ----
    private PackageInventory.Snapshot snapshot=new PackageInventory.Snapshot(List.of(),List.of(),ComponentCatalog.empty());
    private MapPacks.Data maps=new MapPacks.Data(List.of(),null);
    private boolean loaded;
    private long readGeneration;
    private final LinkedHashMap<String,Boolean> requested=new LinkedHashMap<>();
    private final Map<String,List<String>> componentChoices=new LinkedHashMap<>();
    private Map<String,List<String>> prerequisiteGaps=Map.of();
    private List<BmlTools.Problem> toolProblems=List.of();
    private Set<String> modsWithToolErrors=Set.of();
    private final List<PackageInventory.Issue> startupIssues=new ArrayList<>();
    /** Every issue, blocking first; and the ones that block Load. */
    private List<PackageInventory.Issue> allIssues=List.of(),blockers=List.of();
    /** Ticked parents with a group still to choose: mod ID -> its issue. */
    private Map<String,PackageInventory.Issue> choiceNeeded=Map.of();
    private volatile boolean busy;
    private boolean sessionRunning,launchCheckRunning,toolCheckRunning;
    private SessionInfo runningSession;

    // ---- Mods ----
    private final JTextField search=field("Search mods…");
    private final JComboBox<String> show=combo(new String[]{"All","Ticked","Unticked","Disabled"});
    private final FlatButton selectAll=new FlatButton("Select All"),refresh=new FlatButton("Refresh"),addMod=new FlatButton("Add Mod…");
    private final JLabel modsMeta=new JLabel();
    private final ModsModel modsModel=new ModsModel();
    private final ListTable modsTable=new ListTable(modsModel);
    private String selectedMod;
    /** Mods set aside with Disable Mod ({@link DisabledMods}); kept in bml.properties. */
    private final Set<String> disabled=new TreeSet<>();
    /** Mods that can't be disabled now: ID -> the ticked mods that need it. */
    private Map<String,List<String>> neededBy=Map.of();

    // ---- Maps ----
    private final JLabel mapsMeta=new JLabel();
    private final JPanel mapsStatus=new JPanel(new BorderLayout());
    private final SimpleModel<MapPacks.Pack> mapsModel=new SimpleModel<>("Map","Loads mods made for","","Version");
    private final ListTable mapsTable=new ListTable(mapsModel);
    private final FlatButton importMap=new FlatButton("Import Map…"),openMaps=new FlatButton("Open Folder");

    // ---- Errors ----
    private final JLabel errorsMeta=new JLabel();
    private final SimpleModel<PackageInventory.Issue> errorsModel=new SimpleModel<>("Severity","Package","Problem","Fix");
    private final ListTable errorsTable=new ListTable(errorsModel);
    private final CardLayout errorsCards=new CardLayout();
    private final JPanel errorsContent=new JPanel(errorsCards);

    // ---- Sessions ----
    private final JLabel sessionsMeta=new JLabel();
    private final SimpleModel<SessionArchive> sessionsModel=new SimpleModel<>("","Started","Status","Mode","Mods");
    private final ListTable sessionsTable=new ListTable(sessionsModel);
    private final CardLayout sessionsCards=new CardLayout();
    private final JPanel sessionsContent=new JPanel(sessionsCards);
    private final JPanel cleanupNotice=new JPanel(new BorderLayout());
    private final FlatButton clearAll=new FlatButton("Clear All");
    private int exportCount;
    private boolean sessionsReading;
    private String selectedSession;
    private javax.swing.Timer sessionsPoll;

    // ---- Settings ----
    private final JPanel settingsContent=new JPanel();
    private final Tick showDebug=new Tick("Show Debug Mode");

    // ---- below the pages ----
    private final JPanel selectionBar=new JPanel(new BorderLayout());
    private final JPanel errorStrip=new JPanel(new BorderLayout());
    private final JLabel stripText=shrinkingLabel("",ui(13),TEXT),stripFix=shrinkingLabel("",ui(13),MUTED);
    private final FlatButton showInErrors=new FlatButton("Show in Errors");
    private final JLabel status=shrinkingLabel("Reading mods…",ui(13),MUTED);
    private final StatusDot statusDot=new StatusDot();
    private final PrepareBar prepareBar=new PrepareBar();
    private final Tick debugMode=new Tick("Debug Mode");
    private final FlatButton stop=new FlatButton("Stop / Quit");
    private final LoadButton load=new LoadButton();
    private javax.swing.Timer prepareProgressPoll,sessionIdentityPoll;
    private String watchedIdentity="";
    private long watchGeneration;
    private boolean sessionRefreshPending;

    PatchbayWindow(BmlService service){
        this.service=service;
        for(String broken:service.brokenSessionFolders()){
            int colon=broken.indexOf(':');
            String id=colon<0?broken:broken.substring(0,colon),why=colon<0?"":broken.substring(colon+1).strip();
            startupIssues.add(new PackageInventory.Issue("SESSION_FOLDER_UNREADABLE",PackageInventory.Severity.WARNING,
                service.paths().sessionsDir.resolve(id),"A session folder can't be read: "+id+(why.isEmpty()?"":" ("+why+")")+".",
                "Open its location and delete the folder if you don't need it; nothing else is affected."));
        }
        disabled.addAll(service.config().disabledMods());
        build();
    }

    void show(){
        // The dark title bar is asked for before the window is first shown, by a title only it has.
        String unique=NO_SESSION_WINDOW+UUID.randomUUID();
        frame.setTitle(unique);
        frame.addNotify();
        darkTitleBar(frame,unique);
        frame.setTitle(AppText.NAME);
        frame.setVisible(true);
        modsTable.requestFocusInWindow();
        readEverything(true);
        refreshSessionState();
        readSessions();
    }

    // =====================================================================================
    // Building
    // =====================================================================================
    private void build(){
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(980,650));
        frame.setSize(1250,835);frame.setLocationByPlatform(true);
        frame.setIconImages(appIcons());
        frame.setBackground(FRAME);
        frame.getRootPane().putClientProperty("apple.awt.windowAppearance","NSAppearanceNameDarkAqua");
        if(BmlPaths.MAC)try{
            if(Taskbar.isTaskbarSupported()&&Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE))
                Taskbar.getTaskbar().setIconImage(appIcons().get(4));
        }catch(RuntimeException unsupported){}

        JPanel main=new JPanel(new BorderLayout());main.setBackground(FRAME);
        main.add(buildSidebar(),BorderLayout.WEST);
        JPanel right=new JPanel(new BorderLayout());right.setBackground(WELL);
        pages.setBackground(WELL);
        pages.add(buildModsPage(),Page.MODS.name());
        pages.add(buildMapsPage(),Page.MAPS.name());
        pages.add(buildErrorsPage(),Page.ERRORS.name());
        pages.add(buildSessionsPage(),Page.SESSIONS.name());
        pages.add(buildSettingsPage(),Page.SETTINGS.name());
        right.add(pages,BorderLayout.CENTER);
        JPanel bottom=new JPanel();bottom.setLayout(new BoxLayout(bottom,BoxLayout.Y_AXIS));
        buildSelectionBar();buildErrorStrip();
        bottom.add(selectionBar);bottom.add(errorStrip);bottom.add(buildFooter());
        right.add(bottom,BorderLayout.SOUTH);
        main.add(right,BorderLayout.CENTER);
        root.add(main,"main");root.add(reader,"reader");
        // Escape anywhere in the window (not the reader, which closes on it, nor a dialog) clears the page's selection.
        main.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),"unpick-page");
        main.getActionMap().put("unpick-page",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){
            JTable t=switch(page){case MODS->modsTable;case MAPS->mapsTable;case ERRORS->errorsTable;case SESSIONS->sessionsTable;default->null;};
            if(t!=null)t.clearSelection();
        }});
        frame.setContentPane(root);

        int menu=Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F,menu),"find");
        frame.getRootPane().getActionMap().put("find",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){
            closeReader();activate(Page.MODS);search.requestFocusInWindow();search.selectAll();
        }});
        frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_O,menu),"add-mod");
        frame.getRootPane().getActionMap().put("add-mod",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){chooseMods();}});
        installModDrop();
        frame.addWindowFocusListener(new WindowAdapter(){
            @Override public void windowGainedFocus(WindowEvent e){if(loaded&&!busy)readEverything(false);}
        });
        activate(Page.MODS);
        updateControls();
    }

    private JComponent buildSidebar(){
        JPanel side=new JPanel(){
            @Override public Dimension getPreferredSize(){return new Dimension(208,super.getPreferredSize().height);}
        };
        side.setLayout(new BoxLayout(side,BoxLayout.Y_AXIS));
        side.setBackground(FRAME);side.setBorder(new MatteBorder(0,0,0,1,SHADOW));
        JLabel brand=label(AppText.NAME,semibold(24),TEXT);brand.setIcon(markIcon(24));brand.setIconTextGap(9);
        brand.setBorder(new EmptyBorder(18,18,10,18));
        side.add(left(brand));
        side.add(left(group("Library")));
        for(Page p:List.of(Page.MODS,Page.MAPS))side.add(left(navItem(p)));
        JPanel rule=new JPanel(){{setOpaque(false);setBorder(new CompoundBorder(new EmptyBorder(10,18,0,18),new MatteBorder(1,0,0,0,LINE)));}
            @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,11);}};
        side.add(left(rule));
        side.add(left(group("System")));
        for(Page p:List.of(Page.ERRORS,Page.SESSIONS,Page.SETTINGS))side.add(left(navItem(p)));
        side.add(Box.createVerticalGlue());
        JTextArea foot=wrapping(AppText.NOT_AFFILIATED,ui(11),DIM);
        foot.setSize(new Dimension(172,Short.MAX_VALUE));
        JPanel footBox=new JPanel(new BorderLayout());footBox.setOpaque(false);footBox.setBorder(new EmptyBorder(12,18,12,18));
        footBox.add(foot);footBox.setMaximumSize(new Dimension(208,80));
        side.add(left(footBox));
        return side;
    }
    private static JComponent left(JComponent c){c.setAlignmentX(Component.LEFT_ALIGNMENT);return c;}
    private static JComponent group(String text){
        JLabel l=label(text.toUpperCase(Locale.ROOT),caps(11),DIM);l.setBorder(new EmptyBorder(16,18,5,18));return l;
    }
    private NavItem navItem(Page p){
        NavItem item=new NavItem(p.title);
        item.addActionListener(e->{closeReader();activate(p);focusPage();});
        nav.put(p,item);return item;
    }

    /** A sidebar entry: a socket ring (an accent ring on the open page), the name and its count. */
    private static final class NavItem extends JButton {
        private boolean active,fault;private String count="";
        NavItem(String text){
            super(text);setContentAreaFilled(false);setBorderPainted(false);setFocusPainted(false);setOpaque(false);setRolloverEnabled(true);
            setFont(ui(15));setHorizontalAlignment(LEFT);
        }
        void set(boolean active,String count,boolean fault){this.active=active;this.count=count;this.fault=fault;repaint();}
        @Override public Dimension getPreferredSize(){return new Dimension(208,34);}
        @Override public Dimension getMaximumSize(){return getPreferredSize();}
        @Override protected void paintComponent(Graphics gr){
            Graphics2D g=(Graphics2D)gr.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int x=8,w=getWidth()-16;
            if(active){g.setColor(HEAD);g.fillRect(x,0,w,getHeight());}
            if(isFocusOwner()){g.setColor(ACCENT);g.drawRect(x,0,w-1,getHeight()-1);}
            int cy=getHeight()/2;
            if(active){g.setColor(ACCENT);g.setStroke(new BasicStroke(1.5f));g.drawOval(x+10,cy-4,9,9);}
            g.setFont(getFont());FontMetrics fm=g.getFontMetrics();
            int base=(getHeight()-fm.getHeight())/2+fm.getAscent();
            g.setColor(active||getModel().isRollover()?TEXT:MUTED);
            g.drawString(getText(),x+30,base);
            if(!count.isEmpty()){
                g.setFont(ui(13));FontMetrics cm=g.getFontMetrics();
                g.setColor(fault?FAULT:DIM);
                g.drawString(count,x+w-10-cm.stringWidth(count),(getHeight()-cm.getHeight())/2+cm.getAscent());
            }
            g.dispose();
        }
    }

    private void activate(Page p){
        page=p;
        pageCards.show(pages,p.name());
        if(p==Page.SESSIONS)readSessions();
        if(p==Page.SETTINGS)fillSettings();
        updateNav();updateSelectionBar();
        if(p!=Page.SETTINGS&&loaded)runToolChecks(null);
    }
    /** After a sidebar click, the page's list has the keyboard, as it would after Tab. */
    private void focusPage(){
        JComponent target=switch(page){case MODS->modsTable;case MAPS->mapsTable;case ERRORS->errorsTable;case SESSIONS->sessionsTable;default->null;};
        if(target!=null)target.requestFocusInWindow();
    }
    private void updateNav(){
        long blocking=allIssues.stream().filter(PackageInventory.Issue::blocking).count();
        long info=allIssues.stream().filter(i->i.severity()==PackageInventory.Severity.INFO).count();
        nav.get(Page.MODS).set(page==Page.MODS,loaded?String.valueOf(modEntries().size()):"",false);
        nav.get(Page.MAPS).set(page==Page.MAPS,loaded?String.valueOf(maps.packs().size()):"",false);
        nav.get(Page.ERRORS).set(page==Page.ERRORS,blocking+info>0?String.valueOf(blocking+info):"",blocking>0);
        nav.get(Page.SESSIONS).set(page==Page.SESSIONS,sessionsModel.items.isEmpty()?"":String.valueOf(sessionsModel.items.size()),false);
        nav.get(Page.SETTINGS).set(page==Page.SETTINGS,"",false);
    }

    /** A page: its header strip, its control strip (if any), and its content. */
    private static JPanel page(String title,JLabel meta,JComponent controls,JComponent content){
        JPanel p=new JPanel(new BorderLayout());p.setBackground(WELL);
        JPanel top=new JPanel(new BorderLayout());top.setBackground(WELL);
        top.add(pageHeader(title,meta),BorderLayout.NORTH);
        if(controls!=null)top.add(controlStrip(controls),BorderLayout.CENTER);
        p.add(top,BorderLayout.NORTH);p.add(content,BorderLayout.CENTER);
        return p;
    }
    private static JComponent empty(String title,String detail){
        JPanel p=new JPanel(new GridBagLayout());p.setBackground(WELL);
        JPanel text=new JPanel();text.setOpaque(false);text.setLayout(new BoxLayout(text,BoxLayout.Y_AXIS));
        JLabel t=label(title,semibold(17),TEXT);t.setAlignmentX(0.5f);
        JLabel d=label(detail,ui(13),MUTED);d.setAlignmentX(0.5f);
        text.add(t);text.add(Box.createVerticalStrut(6));text.add(d);p.add(text);
        return p;
    }

    // =====================================================================================
    // Lists
    // =====================================================================================
    /** A list: 30 px rows on the well, hairlines between them, the selected row tinted. Enter
     *  selects the highlighted row, Esc clears the selection; the selection is what the bar shows. */
    private final class ListTable extends JTable {
        /** Columns whose cell isn't a click on the row: the tick column ticks. */
        int tickColumn=-1;
        Consumer<Integer> onTick;
        ListTable(TableModel model){
            super(model);
            setRowHeight(30);setShowGrid(false);setIntercellSpacing(new Dimension(0,0));
            setFillsViewportHeight(true);setBackground(WELL);setForeground(TEXT);
            setSelectionBackground(ACCENT_SEL);setSelectionForeground(TEXT);
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
            JTableHeader h=getTableHeader();
            h.setReorderingAllowed(false);h.setResizingAllowed(true);
            h.setPreferredSize(new Dimension(10,26));
            h.setDefaultRenderer((t,v,s,f,r,c)->{
                JLabel l=label(String.valueOf(v).toUpperCase(Locale.ROOT),caps(11),MUTED);
                l.setOpaque(true);l.setBackground(FRAME);
                Border pad=new EmptyBorder(0,c==0?14:0,0,12);
                if(draggable(c))pad=new CompoundBorder(DIVIDER,pad);
                l.setBorder(new CompoundBorder(new MatteBorder(0,0,1,0,SHADOW),pad));
                return l;
            });
            h.setBackground(FRAME);
            h.addMouseListener(new MouseAdapter(){
                @Override public void mouseReleased(MouseEvent e){if(dragged){dragged=false;saveWidths();}}
                @Override public void mouseClicked(MouseEvent e){
                    if(e.getClickCount()!=2||!SwingUtilities.isLeftMouseButton(e)||spec==null)return;
                    int edge=0;
                    for(int i=0;i<getColumnCount();i++){
                        edge+=getColumnModel().getColumn(i).getWidth();
                        if(Math.abs(e.getX()-edge)<=4&&draggable(i)){resetColumn(i);return;}
                    }
                }
            });
            model.addTableModelListener(e->{if(spec!=null){fitsStale=true;revalidate();repaint();}});
            InputMap in=getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
            in.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"pick");
            in.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),"unpick");
            in.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,0),"tick");
            in.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB,0),"none");
            in.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB,InputEvent.SHIFT_DOWN_MASK),"none");
            setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,null);
            setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,null);
            getActionMap().put("pick",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){
                if(getRowCount()==0)return;
                int row=getSelectedRow();
                if(row<0){row=Math.max(0,Math.min(getRowCount()-1,getSelectionModel().getLeadSelectionIndex()));setRowSelectionInterval(row,row);}
            }});
            getActionMap().put("unpick",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){clearSelection();}});
            getActionMap().put("tick",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){
                int row=getSelectedRow();if(row<0)row=getSelectionModel().getLeadSelectionIndex();
                if(onTick!=null&&row>=0&&row<getRowCount())onTick.accept(row);
            }});
        }
        /** A press on the tick box only ticks; a plain press on the selected row, or below the last
         *  row, clears the selection (Ctrl/Cmd+click does too, as Swing does). */
        @Override protected void processMouseEvent(MouseEvent e){
            if(e.getID()==MouseEvent.MOUSE_PRESSED)swallowRelease=false;
            if(e.getID()==MouseEvent.MOUSE_PRESSED&&SwingUtilities.isLeftMouseButton(e)){
                int row=rowAtPoint(e.getPoint()),col=columnAtPoint(e.getPoint());
                boolean plain=(e.getModifiersEx()&(InputEvent.SHIFT_DOWN_MASK|InputEvent.CTRL_DOWN_MASK|InputEvent.META_DOWN_MASK|InputEvent.ALT_DOWN_MASK))==0;
                if(tickColumn>=0&&row>=0&&col==tickColumn){if(onTick!=null)onTick.accept(row);requestFocusInWindow();e.consume();return;}
                if(row<0||(plain&&isRowSelected(row))){
                    clearSelection();requestFocusInWindow();e.consume();swallowRelease=true;return;
                }
            }
            // The release and click that end a press which deselected stay ours, so Swing doesn't select again.
            if(swallowRelease&&(e.getID()==MouseEvent.MOUSE_RELEASED||e.getID()==MouseEvent.MOUSE_CLICKED)){e.consume();return;}
            super.processMouseEvent(e);
        }
        private boolean swallowRelease;
        @Override public boolean isCellEditable(int r,int c){return false;}

        // ---- Columns: fixed, fitted to their content, or filling what is left -------------------
        // A FIT column is as wide as its widest entry (or header) plus padding, worked out again
        // whenever the list changes; FILL columns share the remaining width. Dividers can be
        // dragged in the header: the width is remembered per user (ui.columns.<list>), and a
        // double-click on the divider fits the column again. Between two FILL columns the
        // divider moves their split instead.
        static final int FIT=0,FILL=-1;
        private static final int MIN=48,FILL_MIN=160;
        private String listKey;
        /** Per column: a width (fixed), FIT, or a negative fill weight. */
        private int[] spec,fit,laid;
        private final Map<Integer,Integer> user=new HashMap<>();
        private int split=-1;              // percent of the fill space for the first of two FILL columns
        private boolean fitsStale=true,dragged;

        /** Sets how the columns size, and reads the widths the user dragged before. */
        void columns(String key,int... spec){
            this.listKey=key;this.spec=spec.clone();fit=new int[spec.length];
            for(int i=0;i<spec.length;i++)getColumnModel().getColumn(i).setResizable(draggable(i));
            for(String part:service.config().columnWidths(key).split(",")){
                String[] kv=part.split(":");
                if(kv.length!=2)continue;
                try{
                    int v=Integer.parseInt(kv[1].strip());
                    if("split".equals(kv[0].strip()))split=Math.max(10,Math.min(90,v));
                    else{int c=Integer.parseInt(kv[0].strip());if(c>=0&&c<spec.length&&spec[c]==FIT)user.put(c,Math.max(MIN,v));}
                }catch(NumberFormatException ignored){}
            }
            fitsStale=true;revalidate();
        }
        private boolean draggable(int col){
            if(spec==null||col<0||col>=spec.length-1||spec[col]>0)return false;
            return spec[col]==FIT||spec[col+1]<=0;
        }
        private List<Integer> fills(){List<Integer> f=new ArrayList<>();for(int i=0;i<spec.length;i++)if(spec[i]<0)f.add(i);return f;}

        private void refit(){
            fitsStale=false;
            for(int c=0;c<spec.length;c++){
                if(spec[c]!=FIT)continue;
                TableCellRenderer hr=getTableHeader().getDefaultRenderer();
                int w=hr.getTableCellRendererComponent(this,getColumnName(c),false,false,-1,c).getPreferredSize().width+8;
                for(int r=0;r<getRowCount();r++){
                    Component cell=getCellRenderer(r,c).getTableCellRendererComponent(this,getValueAt(r,c),false,false,r,c);
                    if(cell instanceof Cell x)w=Math.max(w,x.naturalWidth());
                }
                fit[c]=Math.max(MIN,w);
            }
        }

        @Override public void doLayout(){
            if(spec==null||spec.length!=getColumnCount()){super.doLayout();return;}
            if(fitsStale)refit();
            int n=spec.length,total=getWidth();
            List<Integer> fills=fills();
            TableColumn resizing=getTableHeader()==null?null:getTableHeader().getResizingColumn();
            if(resizing!=null&&laid!=null)followDrag(resizing.getModelIndex(),resizing.getWidth(),fills,total);
            int[] w=new int[n];int others=0;
            for(int i=0;i<n;i++){
                if(spec[i]>0)w[i]=spec[i];
                else if(spec[i]==FIT)w[i]=user.getOrDefault(i,fit[i]);
                if(spec[i]>=0)others+=w[i];
            }
            int need=FILL_MIN*fills.size(),space=total-others;
            if(space<need){
                // Too narrow: the fitted columns give up width (down to MIN) before the text column does.
                int give=0;for(int i=0;i<n;i++)if(spec[i]==FIT)give+=w[i]-MIN;
                int cut=Math.min(give,need-space);
                for(int i=0;i<n&&give>0;i++)if(spec[i]==FIT){int c=(int)((long)cut*(w[i]-MIN)/give);w[i]-=c;others-=c;}
                space=total-others;
            }
            space=Math.max(0,space);
            if(fills.size()==2&&split>0){
                w[fills.get(0)]=space*split/100;w[fills.get(1)]=space-w[fills.get(0)];
            }else if(!fills.isEmpty()){
                int weights=0;for(int f:fills)weights-=spec[f];
                int left=space;
                for(int k=0;k<fills.size();k++){int f=fills.get(k);w[f]=k==fills.size()-1?left:space*(-spec[f])/weights;left-=w[f];}
            }
            for(int i=0;i<n;i++){
                TableColumn c=getColumnModel().getColumn(i);
                c.setMinWidth(0);c.setMaxWidth(Integer.MAX_VALUE);
                if(c.getPreferredWidth()!=w[i])c.setPreferredWidth(w[i]);
                if(c.getWidth()!=w[i])c.setWidth(w[i]);
            }
            laid=w;
        }
        /** The header is dragging column {@code i}'s right edge to {@code width}. */
        private void followDrag(int i,int width,List<Integer> fills,int total){
            int delta=width-laid[i];
            if(delta==0||i+1>=spec.length)return;
            int fillSpace=0;for(int f:fills)fillSpace+=laid[f];
            if(spec[i]==FIT){
                int grow=Math.min(delta,fillSpace-FILL_MIN*fills.size());
                user.put(i,Math.max(MIN,laid[i]+grow));dragged=true;
            }else if(spec[i]<0&&spec[i+1]==FIT){
                // A text column's right edge: the fitted column after it gives or takes the width.
                user.put(i+1,Math.max(MIN,Math.min(laid[i+1]-delta,laid[i+1]+laid[i]-FILL_MIN)));dragged=true;
            }else if(spec[i]<0&&spec[i+1]<0&&fills.size()==2){
                int pair=laid[i]+laid[i+1];
                if(pair>0){split=Math.max(10,Math.min(90,(laid[i]+delta)*100/pair));dragged=true;}
            }
        }
        private void resetColumn(int i){
            if(spec[i]==FIT)user.remove(i);
            else if(spec[i]<0&&spec[i+1]==FIT)user.remove(i+1);
            else split=-1;
            saveWidths();revalidate();repaint();getTableHeader().repaint();
        }
        private void saveWidths(){
            List<String> parts=new ArrayList<>();
            new TreeMap<>(user).forEach((c,v)->parts.add(c+":"+v));
            if(split>0)parts.add("split:"+split);
            service.config().setColumnWidths(listKey,String.join(",",parts));
            try{service.config().save();}catch(IOException ex){setStatus("The column widths couldn't be saved: "+ex.getMessage());}
        }
    }
    /** A short hairline on a header cell's right edge: the divider that can be dragged. */
    private static final Border DIVIDER=new Border(){
        @Override public void paintBorder(Component c,Graphics g,int x,int y,int w,int h){g.setColor(LINE);g.fillRect(x+w-1,y+(h-12)/2,1,12);}
        @Override public Insets getBorderInsets(Component c){return new Insets(0,0,0,0);}
        @Override public boolean isBorderOpaque(){return false;}
    };

    /** Rows of items with as many columns as names; the text comes from the page's renderer. */
    private static final class SimpleModel<T> extends AbstractTableModel {
        final String[] names;List<T> items=List.of();
        SimpleModel(String... names){this.names=names;}
        void set(List<T> list){items=List.copyOf(list);fireTableDataChanged();}
        T get(int row){return items.get(row);}
        @Override public int getRowCount(){return items.size();}
        @Override public int getColumnCount(){return names.length;}
        @Override public String getColumnName(int c){return names[c];}
        @Override public Object getValueAt(int r,int c){return items.get(r);}
    }

    /** One cell: one or two runs of text (each in its own colour and font), cut with "…" to fit. */
    private static final class Cell extends JComponent implements TableCellRenderer {
        String a="",b="";Color ca=TEXT,cb=MUTED,bg=WELL;Font fa=ui(14),fb=ui(14);int pad=0;
        boolean tick,ticked,fault,tickEnabled=true;Color dot;
        interface Styler{void style(Cell c,int row,int col);}
        private final Styler styler;
        Cell(Styler styler){this.styler=styler;}
        @Override public Component getTableCellRendererComponent(JTable t,Object v,boolean sel,boolean focus,int row,int col){
            a="";b="";ca=TEXT;cb=MUTED;fa=ui(14);fb=ui(13);pad=col==0?14:0;tick=false;fault=false;dot=null;tickEnabled=true;
            bg=sel?ACCENT_SEL:WELL;
            styler.style(this,row,col);
            if(sel)bg=ACCENT_SEL;
            return this;
        }
        /** The width that shows this cell's text uncut. */
        int naturalWidth(){
            if(tick||dot!=null)return 0;
            int w=pad+12+4;
            if(!a.isEmpty())w+=getFontMetrics(fa).stringWidth(a);
            if(!b.isEmpty())w+=getFontMetrics(fb).stringWidth(b);
            return w;
        }
        @Override protected void paintComponent(Graphics gr){
            Graphics2D g=(Graphics2D)gr.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(bg);g.fillRect(0,0,getWidth(),getHeight());
            g.setColor(HAIR);g.fillRect(0,getHeight()-1,getWidth(),1);
            if(tick){paintTick(g,16,(getHeight()-15)/2,ticked,fault,tickEnabled,false);g.dispose();return;}
            if(dot!=null){
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(dot);g.fillOval(14,getHeight()/2-3,7,7);g.dispose();return;
            }
            int x=pad,limit=getWidth()-12;
            g.setFont(fa);FontMetrics fm=g.getFontMetrics();
            int base=(getHeight()-1-fm.getHeight())/2+fm.getAscent();
            x=draw(g,a,ca,fa,x,base,limit);
            if(!b.isEmpty())draw(g,b,cb,fb,x,base,limit);
            g.dispose();
        }
        private static int draw(Graphics2D g,String s,Color c,Font f,int x,int base,int limit){
            if(s.isEmpty()||x>=limit)return x;
            g.setFont(f);g.setColor(c);FontMetrics fm=g.getFontMetrics();
            if(x+fm.stringWidth(s)>limit){
                String dots="…";int n=s.length();
                while(n>0&&x+fm.stringWidth(s.substring(0,n))+fm.stringWidth(dots)>limit)n--;
                s=s.substring(0,n).stripTrailing()+dots;
            }
            g.drawString(s,x,base);
            return x+fm.stringWidth(s);
        }
    }

    // =====================================================================================
    // Mods
    // =====================================================================================
    private final class ModsModel extends AbstractTableModel {
        List<PackageInventory.Entry> rows=List.of();
        void set(List<PackageInventory.Entry> list){rows=List.copyOf(list);fireTableDataChanged();}
        PackageInventory.Entry get(int r){return rows.get(r);}
        @Override public int getRowCount(){return rows.size();}
        @Override public int getColumnCount(){return 5;}
        @Override public String getColumnName(int c){return new String[]{"","Name","Description","Version","Author"}[c];}
        @Override public Object getValueAt(int r,int c){return rows.get(r);}
    }

    private JComponent buildModsPage(){
        modsTable.tickColumn=0;
        modsTable.onTick=row->{PackageInventory.Entry e=modsModel.get(row);changeTick(e,!ticked(e));};
        modsTable.setDefaultRenderer(Object.class,new Cell((c,row,col)->styleMod(c,modsModel.get(row),col)));
        modsTable.columns("mods",42,ListTable.FIT,ListTable.FILL,ListTable.FIT,ListTable.FIT);
        modsTable.getSelectionModel().addListSelectionListener(e->{
            if(e.getValueIsAdjusting())return;
            int row=modsTable.getSelectedRow();
            selectedMod=row<0?null:modsModel.get(row).key();
            updateSelectionBar();
        });
        modsTable.getAccessibleContext().setAccessibleDescription("Installed mods. Space ticks the highlighted mod; Enter shows its options; Escape hides them.");
        search.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){fillMods();}public void removeUpdate(DocumentEvent e){fillMods();}public void changedUpdate(DocumentEvent e){fillMods();}
        });
        search.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN,0),"to-list");
        search.getActionMap().put("to-list",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){modsTable.requestFocusInWindow();}});
        search.setMaximumSize(new Dimension(250,CONTROL_HEIGHT));
        // Wide enough for its longest choice with the arrow ("Disabled" was cut short).
        Dimension showSize=new Dimension(show.getPreferredSize().width+18,CONTROL_HEIGHT);
        show.setPreferredSize(showSize);show.setMaximumSize(showSize);
        show.setSelectedItem(service.config().modsShow());
        if(show.getSelectedIndex()<0)show.setSelectedIndex(0);
        show.addActionListener(e->{
            service.config().setModsShow(String.valueOf(show.getSelectedItem()));
            try{service.config().save();}catch(IOException ex){setStatus("The view couldn't be saved: "+ex.getMessage());}
            fillMods();
        });
        selectAll.addActionListener(e->toggleAll());
        refresh.addActionListener(e->{readEverything(false);setStatus("Mods, maps and components read again.");});
        addMod.addActionListener(e->chooseMods());
        installModMenu();
        JComponent controls=row(search,label("Show",ui(13),MUTED),show,null,selectAll,refresh,addMod);
        return page("Mods",modsMeta,controls,scroll(modsTable,WELL));
    }
    /** A disabled mod is never ticked, whatever its saved tick says. */
    private boolean ticked(PackageInventory.Entry e){return !isDisabled(e)&&Boolean.TRUE.equals(requested.get(e.id));}
    private boolean isDisabled(PackageInventory.Entry e){return e.id!=null&&disabled.contains(e.id);}
    /** Mods and broken packages in mods/, disabled ones left out: what Load, the counts and the errors see. */
    private List<PackageInventory.Entry> modEntries(){
        if(disabled.isEmpty())return snapshot.entries();
        return snapshot.entries().stream().filter(e->!isDisabled(e)).toList();
    }
    private static String areaKey(PackageInventory.Entry e){return e.area().toLowerCase(Locale.ROOT);}

    private void styleMod(Cell c,PackageInventory.Entry e,int col){
        if(isDisabled(e)){styleDisabledMod(c,e,col);return;}
        boolean on=ticked(e),na=e.unavailable!=null;
        boolean fault=flagged(e);
        if(fault)c.bg=FAULT_BG;
        switch(col){
            case 0->{c.tick=true;c.ticked=on;c.fault=fault;c.tickEnabled=on||e.loadable();}
            case 1->{
                String area=e.area();
                boolean shown=!"Other".equals(area)||!e.declaredArea.isBlank();
                c.a=shown?area+": ":"";c.ca=na?DIM:fault?FAULT:MUTED;c.fa=ui(14);
                c.b=shown?e.shortName():e.name;c.cb=na?DIM:on?TEXT:MUTED;c.fb=ui(14);
            }
            case 2->{
                String note=note(e);
                c.a=note==null?"":note+(e.description.isBlank()?"":"  ·  ");c.ca=fault?FAULT:DIM;c.fa=ui(13);
                c.b=e.description;c.cb=na?DIM:MUTED;c.fb=ui(13);
            }
            case 3->{c.a=e.version;c.ca=na?DIM:MUTED;c.fa=ui(13);}
            case 4->{c.a=e.author;c.ca=na?DIM:MUTED;c.fa=ui(13);}
            default->{}
        }
    }
    /** A disabled mod, listed when Show is Disabled: dimmed, its tick box greyed, never red. */
    private void styleDisabledMod(Cell c,PackageInventory.Entry e,int col){
        switch(col){
            case 0->{c.tick=true;c.ticked=false;c.tickEnabled=false;}
            case 1->{
                String area=e.area();
                boolean shown=!"Other".equals(area)||!e.declaredArea.isBlank();
                c.a=shown?area+": ":"";c.ca=DIM;c.b=shown?e.shortName():e.name;c.cb=DIM;
            }
            case 2->{c.a=e.description;c.ca=DIM;c.fa=ui(13);}
            case 3->{c.a=e.version;c.ca=DIM;c.fa=ui(13);}
            case 4->{c.a=e.author;c.ca=DIM;c.fa=ui(13);}
            default->{}
        }
    }
    /** A mod's row is red with "!" when it can't load (other than on this version), needs a mod
     *  that is unticked, has a tool problem, or still needs a component chosen. */
    private boolean flagged(PackageInventory.Entry e){
        if(isDisabled(e))return false;
        boolean on=ticked(e);
        return (!e.loadable()&&e.unavailable==null)||prerequisiteGaps.containsKey(e.id)
            ||(on&&modsWithToolErrors.contains(e.id))||(on&&choiceNeeded.containsKey(e.id));
    }
    /** The status note, only when it adds something to the row. */
    private String note(PackageInventory.Entry e){
        if(e.unavailable!=null)return "Not available on "+(e.unavailableOn==null?"this version":e.unavailableOn);
        if(prerequisiteGaps.containsKey(e.id))return "Needs "+names(prerequisiteGaps.get(e.id));
        if(!e.loadable())return "Can't load";
        if(ticked(e)&&modsWithToolErrors.contains(e.id))return "A device folder is missing";
        if(ticked(e)&&choiceNeeded.containsKey(e.id))return "Choose a component";
        return null;
    }
    private String names(Collection<String> ids){
        List<String> out=new ArrayList<>();
        for(String id:ids){PackageInventory.Entry p=snapshot.find(id);out.add(p==null?id:p.name);}
        return String.join(", ",out);
    }

    private void fillMods(){
        String q=search.getText().strip().toLowerCase(Locale.ROOT),which=String.valueOf(show.getSelectedItem());
        List<PackageInventory.Entry> rows=new ArrayList<>();
        // Disabled lists only the disabled mods; the other choices leave them out.
        boolean disabledView="Disabled".equals(which);
        for(PackageInventory.Entry e:snapshot.entries()){
            if(isDisabled(e)!=disabledView)continue;
            if("Ticked".equals(which)&&!ticked(e))continue;
            if("Unticked".equals(which)&&ticked(e))continue;
            String hay=(e.name+" "+e.area()+" "+e.id+" "+e.version+" "+e.author+" "+e.description+" "+String.join(" ",e.tags)).toLowerCase(Locale.ROOT);
            if(!q.isEmpty()&&!hay.contains(q))continue;
            rows.add(e);
        }
        rows.sort(Comparator.comparing(PatchbayWindow::areaKey).thenComparing(e->e.shortName(),TextOrder::compareHumanText).thenComparing(e->e.directory.toString()));
        String keep=selectedMod;
        Point at=modsTable.getParent() instanceof JViewport v?v.getViewPosition():null;
        modsModel.set(rows);
        reselect(modsTable,keep==null?-1:indexOf(rows,e->e.key().equals(keep)));
        if(at!=null)((JViewport)modsTable.getParent()).setViewPosition(at);
        long tickedCount=modEntries().stream().filter(this::ticked).count();
        long off=snapshot.entries().size()-modEntries().size();
        modsMeta.setText(snapshot.entries().size()+" installed · "+tickedCount+" ticked"+(off>0?" · "+off+" disabled":""));
    }
    private static <T> int indexOf(List<T> rows,java.util.function.Predicate<T> p){for(int i=0;i<rows.size();i++)if(p.test(rows.get(i)))return i;return -1;}
    private static void reselect(JTable t,int row){
        if(row>=0){t.setRowSelectionInterval(row,row);t.scrollRectToVisible(t.getCellRect(row,0,true));}
        else t.clearSelection();
    }

    private void changeTick(PackageInventory.Entry e,boolean on){
        if(busy)return;
        if(isDisabled(e)){setStatus(e.name+" is disabled: choose Enable Mod first.");return;}
        if(on&&!e.loadable()){
            setStatus(e.unavailable!=null?e.name+" isn't available on this Bitwig version.":e.name+" can't load; see Errors.");return;
        }
        requested.put(e.id,on);
        List<String> also=new ArrayList<>();
        if(on)tickPrerequisites(e,also);
        saveTicks();recompute();
        if(!also.isEmpty())setStatus("Also ticked "+String.join(", ",also)+": "+e.name+" needs "+(also.size()==1?"it":"them")+".");
        else if(!on){
            List<String> needing=modEntries().stream().filter(x->prerequisiteGaps.getOrDefault(x.id,List.of()).contains(e.id)).map(x->x.name).toList();
            if(!needing.isEmpty())setStatus(String.join(", ",needing)+(needing.size()==1?" needs ":" need ")+e.name+": Load is blocked until you tick it again or untick "+(needing.size()==1?"that mod.":"them."));
        }
    }
    /** Ticks what {@code root} needs, and what that needs. A disabled prerequisite is enabled again
     *  first: the auto-tick wins. The names ticked go in {@code also}, marked when enabled again;
     *  the disabled list is saved when it changed. */
    private void tickPrerequisites(PackageInventory.Entry root,List<String> also){
        boolean enabledAgain=false;
        ArrayDeque<PackageInventory.Entry> todo=new ArrayDeque<>(List.of(root));
        while(!todo.isEmpty())for(String id:todo.pop().requires.keySet()){
            PackageInventory.Entry p=snapshot.find(id);
            if(p==null)continue;
            boolean wasDisabled=isDisabled(p);
            if(wasDisabled){disabled.remove(id);enabledAgain=true;}
            if(!p.loadable()||ticked(p)){if(wasDisabled)also.add(p.name+" (enabled again)");continue;}
            requested.put(id,true);also.add(p.name+(wasDisabled?" (enabled again)":""));todo.add(p);
        }
        if(enabledAgain)saveDisabled();
    }
    private void toggleAll(){
        long loadable=modEntries().stream().filter(PackageInventory.Entry::loadable).count();
        long on=modEntries().stream().filter(PackageInventory.Entry::loadable).filter(this::ticked).count();
        boolean next=on!=loadable;
        List<PackageInventory.Entry> active=modEntries();
        for(PackageInventory.Entry e:active)if(e.loadable()||!next&&ticked(e))requested.put(e.id,next);
        List<String> also=new ArrayList<>();
        if(next)for(PackageInventory.Entry e:active)if(ticked(e))tickPrerequisites(e,also);
        saveTicks();recompute();
        setStatus(next?"Every mod that can load is ticked."+(also.isEmpty()?"":" Also ticked "+String.join(", ",also)+": ticked mods need "+(also.size()==1?"it.":"them.")):"Every mod is unticked.");
    }

    // ---- Disable Mod / Enable Mod -----------------------------------------------------------
    /** Why {@code e} can't be disabled now, or null when it can. */
    private String whyNotDisable(PackageInventory.Entry e){
        if(e.id==null)return "it has no mod ID";
        List<String> by=neededBy.get(e.id);
        if(by!=null&&!by.isEmpty())return "needed by "+(by.size()<=2?String.join(" and ",by):by.get(0)+" and "+(by.size()-1)+" more ticked mods");
        return null;
    }
    private void disableMod(PackageInventory.Entry e){
        if(busy||isDisabled(e))return;
        String why=whyNotDisable(e);
        if(why!=null){setStatus(e.name+" can't be disabled: "+why+".");return;}
        disabled.add(e.id);requested.put(e.id,false);
        saveTicks();saveDisabled();recompute();
        setStatus("Disabled "+e.name+": it isn't loaded and its errors are hidden. Choose Disabled in Show to find it again.");
    }
    private void enableMod(PackageInventory.Entry e){
        if(busy||!isDisabled(e))return;
        disabled.remove(e.id);requested.put(e.id,false);
        saveTicks();saveDisabled();recompute();
        setStatus("Enabled "+e.name+"; tick it to load it.");
    }
    private void saveDisabled(){
        service.config().setDisabledMods(disabled);
        try{service.config().save();}catch(IOException ex){setStatus("The disabled mods couldn't be saved: "+ex.getMessage());}
    }

    /** The Mods list's right-click menu (also Shift+F10 or the menu key): the selected mod's own actions. */
    private void installModMenu(){
        modsTable.addMouseListener(new MouseAdapter(){
            @Override public void mousePressed(MouseEvent e){maybeMenu(e);}
            @Override public void mouseReleased(MouseEvent e){maybeMenu(e);}
            private void maybeMenu(MouseEvent e){
                if(!e.isPopupTrigger())return;
                int row=modsTable.rowAtPoint(e.getPoint());
                if(row<0)return;
                modsTable.setRowSelectionInterval(row,row);modsTable.requestFocusInWindow();
                showModMenu(modsModel.get(row),e.getX(),e.getY());
            }
        });
        InputMap in=modsTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_F10,InputEvent.SHIFT_DOWN_MASK),"mod-menu");
        in.put(KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU,0),"mod-menu");
        modsTable.getActionMap().put("mod-menu",new AbstractAction(){@Override public void actionPerformed(ActionEvent x){
            int row=modsTable.getSelectedRow();
            if(row<0||row>=modsModel.rows.size())return;
            Rectangle r=modsTable.getCellRect(row,1,true);
            showModMenu(modsModel.get(row),r.x+8,r.y+r.height);
        }});
    }
    private void showModMenu(PackageInventory.Entry e,int x,int y){
        JPopupMenu menu=popupMenu();
        if(e.documentation!=null)menu.add(menuItem("README",null,true,()->openReadme(e)));
        if(e.homepage!=null)menu.add(menuItem("Open on the Web",null,true,()->openLink(e.homepage.toString())));
        menu.add(menuItem("Open Folder",null,true,()->openFolder(e.directory)));
        menu.add(menuRule());
        if(isDisabled(e))menu.add(menuItem("Enable Mod",null,!busy,()->enableMod(e)));
        else{String why=whyNotDisable(e);menu.add(menuItem("Disable Mod",why==null?null:"Can't: "+why,why==null&&!busy,()->disableMod(e)));}
        menu.show(modsTable,x,y);
    }
    private void saveTicks(){
        try{service.saveEnabled(requested);}catch(IOException ex){setStatus("Ticks couldn't be saved: "+ex.getMessage());}
    }

    // ---- Add Mod ------------------------------------------------------------------------------
    /** Add Mod… (the button, Ctrl/Cmd+O): mod ZIPs chosen in the system's file dialog, several at once. */
    private void chooseMods(){
        if(busy||!loaded)return;
        List<Path> zips=NativeFilePicker.openMany(frame,"add-mod","Add mods (ZIP files)","zip");
        if(!zips.isEmpty())addMods(zips);
    }
    /** ZIPs dropped anywhere on the window are added as with Add Mod…; the Mods list keeps Copy. */
    private void installModDrop(){
        TransferHandler copy=modsTable.getTransferHandler();
        final class Drop extends TransferHandler {
            @Override public boolean canImport(TransferSupport s){
                return !busy&&loaded&&s.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }
            @Override public boolean importData(TransferSupport s){
                if(!canImport(s))return false;
                try{
                    @SuppressWarnings("unchecked")
                    List<java.io.File> files=(List<java.io.File>)s.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                    List<Path> paths=files.stream().map(java.io.File::toPath).toList();
                    if(paths.isEmpty())return false;
                    SwingUtilities.invokeLater(()->addMods(paths));
                    return true;
                }catch(Exception ex){setStatus("The dropped files couldn't be read: "+ex.getMessage());return false;}
            }
            @Override public int getSourceActions(JComponent c){return copy==null?NONE:copy.getSourceActions(c);}
            @Override public void exportToClipboard(JComponent c,java.awt.datatransfer.Clipboard clip,int action){if(copy!=null)copy.exportToClipboard(c,clip,action);}
        }
        Drop drop=new Drop();
        frame.setTransferHandler(drop);
        modsTable.setTransferHandler(drop);
        if(modsTable.getParent() instanceof JViewport v)v.setTransferHandler(drop);
    }
    private record AddChecks(List<ModInstaller.Checked> ok,List<String> refused){}
    private record AddResult(List<String> added,List<String> replaced,List<String> failed){}
    /** Checks every ZIP before anything is written, asks once before replacing installed mods,
     *  installs, then reads everything again as on startup. Nothing is ticked. */
    private void addMods(List<Path> zips){
        if(busy||!loaded)return;
        closeReader();activate(Page.MODS);
        Path mods=service.paths().mods;
        runAsync("Checking "+(zips.size()==1?zips.get(0).getFileName().toString():zips.size()+" ZIP files"),false,()->{
            Files.createDirectories(mods);
            ModInstaller.removeLeftovers(mods);
            List<ModInstaller.Checked> ok=new ArrayList<>();List<String> refused=new ArrayList<>();
            Set<String> ids=new HashSet<>();
            for(Path zip:zips){
                String file=zip.getFileName().toString();
                if(!file.toLowerCase(Locale.ROOT).endsWith(".zip")){refused.add(file+" isn't a ZIP");continue;}
                try{
                    ModInstaller.Checked c=ModInstaller.check(zip,mods);
                    if(ids.add(c.id()))ok.add(c);else refused.add(file+" has the same mod as another ZIP");
                }catch(IOException ex){refused.add(file+" can't be added: "+ex.getMessage());}
            }
            return new AddChecks(ok,refused);
        },checks->installChecked(checks.ok(),checks.refused()));
    }
    private void installChecked(List<ModInstaller.Checked> ok,List<String> refused){
        List<ModInstaller.Checked> replacing=ok.stream().filter(ModInstaller.Checked::replaces).toList();
        List<ModInstaller.Checked> go=new ArrayList<>(ok);
        List<String> kept=new ArrayList<>();
        if(!replacing.isEmpty()){
            List<String> lines=new ArrayList<>();
            for(ModInstaller.Checked c:replacing)lines.add(c.name()+" "+c.installedVersion()+" → "+c.version());
            boolean one=replacing.size()==1;
            if(!PromptDialog.confirm(frame,one?"Replace "+replacing.get(0).name()+"?":"Replace "+replacing.size()+" installed mods?",
                String.join("\n",lines),(one?"Its":"Their")+" ticks and settings stay.","Replace")){
                go.removeAll(replacing);
                for(ModInstaller.Checked c:replacing)kept.add(c.name());
            }
        }
        if(go.isEmpty()){
            List<String> parts=new ArrayList<>(refused);
            if(!kept.isEmpty())parts.add("Kept "+String.join(", ",kept)+" as installed");
            setStatus(parts.isEmpty()?"Nothing added.":String.join(". ",parts)+".");
            return;
        }
        Path mods=service.paths().mods;
        runAsync("Adding "+(go.size()==1?go.get(0).name():go.size()+" mods"),false,()->{
            List<String> added=new ArrayList<>(),replaced=new ArrayList<>(),failed=new ArrayList<>();
            for(ModInstaller.Checked c:go){
                try{
                    ModInstaller.install(c,mods);
                    if(c.replaces())replaced.add(c.name()+" ("+c.installedVersion()+" → "+c.version()+")");else added.add(c.name());
                }catch(IOException ex){failed.add(c.zip().getFileName()+" can't be added: "+ex.getMessage());}
            }
            return new AddResult(added,replaced,failed);
        },r->{
            List<String> parts=new ArrayList<>();
            if(!r.added().isEmpty())parts.add("Added "+String.join(", ",r.added())+"; tick "+(r.added().size()==1?"it":"them")+" to load "+(r.added().size()==1?"it":"them"));
            if(!r.replaced().isEmpty())parts.add("Replaced "+String.join(", ",r.replaced()));
            if(!kept.isEmpty())parts.add("Kept "+String.join(", ",kept)+" as installed");
            parts.addAll(refused);parts.addAll(r.failed());
            String summary=String.join(". ",parts)+".";
            setStatus(summary);
            readEverything(false,()->setStatus(summary));
        });
    }

    // =====================================================================================
    // Reading, and working out what blocks Load
    // =====================================================================================
    private record Read(PackageInventory.Snapshot snapshot,MapPacks.Data maps,Map<String,List<String>> choices,Map<String,Boolean> ticks){}

    /** Reads mods, components and maps off the event thread (it hashes bitwig.jar), then shows them. */
    private void readEverything(boolean first){readEverything(first,null);}
    /** As above, then does {@code then}, if any, once the new read is shown. */
    private void readEverything(boolean first,Runnable then){
        long generation=++readGeneration;
        new SwingWorker<Read,Void>(){
            @Override protected Read doInBackground() throws Exception {
                ComponentSelectionStore store=new ComponentSelectionStore(service.paths());
                Map<String,List<String>> choices=new LinkedHashMap<>(store.load());
                Path components=service.paths().root.resolve("components");
                PackageInventory.Snapshot s=PackageInventory.inspect(service.paths().mods,components,choices);
                if(ComponentSelectionStore.seedUnconfiguredSingles(s,choices)){store.save(choices);s=PackageInventory.inspect(service.paths().mods,components,choices);}
                MapPacks.Data m=MapPacks.read(service);
                MapPacks.Pack inUse=m.inUse();
                if(inUse!=null)PackageInventory.markUnavailable(s,inUse);
                List<ModInfo> infos=new ArrayList<>();Set<String> unavailable=new HashSet<>();
                for(PackageInventory.Entry e:s.entries())if(e.id!=null){infos.add(e.modInfo());if(e.unavailable!=null)unavailable.add(e.id);}
                return new Read(s,m,choices,service.loadEnabled(infos,unavailable));
            }
            @Override protected void done(){
                if(generation!=readGeneration)return;
                try{
                    Read r=get();
                    snapshot=r.snapshot();maps=r.maps();
                    componentChoices.clear();componentChoices.putAll(r.choices());
                    requested.clear();requested.putAll(r.ticks());
                    // A disabled mod is never loaded: its saved tick is cleared, so enabling it again finds it unticked.
                    for(String id:disabled)if(requested.containsKey(id))requested.put(id,false);
                    // A ticked mod's prerequisite can't stay disabled (the list was edited by hand): it is enabled again.
                    List<String> back=new ArrayList<>();
                    for(String id:DisabledMods.neededBy(snapshot.entries(),PatchbayWindow.this::ticked).keySet())
                        if(disabled.remove(id)){PackageInventory.Entry p=snapshot.find(id);back.add(p==null?id:p.name);}
                    if(!back.isEmpty())saveDisabled();
                    saveTicks();
                    boolean wasLoaded=loaded;loaded=true;
                    recompute();
                    fillMaps();
                    if(page==Page.SETTINGS)fillSettings();
                    if(!wasLoaded&&!sessionRunning)setStatus(readyText());
                    if(!back.isEmpty())setStatus("Enabled "+String.join(", ",back)+" again: a ticked mod needs "+(back.size()==1?"it.":"them."));
                    // A tool problem found after an add would reset the status line: say the add's result after it.
                    runToolChecks(then);
                }catch(Exception ex){
                    Throwable t=ex.getCause()==null?ex:ex.getCause();
                    loaded=true;setStatus("Mods couldn't be read: "+t.getMessage());updateControls();
                }
            }
        }.execute();
    }

    /** Asks the tools, off the event thread, what is wrong; then does {@code then}, if any. */
    private void runToolChecks(Runnable then){
        Path mods=service.paths().mods;
        if(toolCheckRunning&&then==null)return;
        toolCheckRunning=true;
        new SwingWorker<List<BmlTools.Problem>,Void>(){
            @Override protected List<BmlTools.Problem> doInBackground(){
                List<BmlTools.Problem> problems=new ArrayList<>();
                for(BmlTools.Tool tool:BmlTools.ALL){try{problems.addAll(tool.problems(mods));}catch(RuntimeException ignored){}}
                return problems;
            }
            @Override protected void done(){
                toolCheckRunning=false;
                try{List<BmlTools.Problem> found=get();if(!found.equals(toolProblems)){toolProblems=List.copyOf(found);recompute();}}
                catch(Exception ignored){}
                if(then!=null)then.run();
            }
        }.execute();
    }

    /** Works out every issue and what blocks Load from what was read and what is ticked, then
     *  updates every view of it. */
    private void recompute(){
        Map<String,Set<String>> needs=new TreeMap<>();
        for(PackageInventory.Entry e:modEntries())if(e.loadable()&&ticked(e))needs.put(e.id,e.requires.keySet());
        prerequisiteGaps=Prerequisites.turnedOff(needs);
        neededBy=DisabledMods.neededBy(snapshot.entries(),this::ticked);
        List<PackageInventory.Issue> selection=new ArrayList<>(),notes=new ArrayList<>();
        Map<String,PackageInventory.Issue> choices=new HashMap<>();
        for(PackageInventory.Entry e:modEntries()){
            if(e.id==null)continue;
            if(e.unavailable!=null){
                if(ticked(e))selection.add(new PackageInventory.Issue("NOT_AVAILABLE_ON_VERSION",PackageInventory.Severity.BLOCKING,e.directory,
                    e.name+" isn't available on Bitwig "+e.unavailableOn+".","Untick "+e.name+". ("+e.unavailable+".)"));
                else notes.add(new PackageInventory.Issue("NOT_AVAILABLE_ON_VERSION",PackageInventory.Severity.INFO,e.directory,
                    e.name+" isn't available on Bitwig "+e.unavailableOn+". Nothing to fix: it stays unticked and Load works without it.",
                    "Nothing to do. ("+e.unavailable+".)"));
            }
            if(ticked(e)&&e.loadable())for(ComponentCatalog.Group g:e.groups.values()){
                var ev=snapshot.components().evaluate(e.id,g,ComponentSelectionStore.chosen(componentChoices,e.id,g.id()));
                if(ev.configurationRequired()&&!choices.containsKey(e.id)){
                    PackageInventory.Issue issue=new PackageInventory.Issue("COMPONENT_CHOICE_NEEDED",PackageInventory.Severity.BLOCKING,e.directory,
                        e.name+" needs a choice of "+g.name()+".","Select "+e.name+" on Mods and choose in its bar, or untick it.");
                    choices.put(e.id,issue);selection.add(issue);
                }
            }
        }
        for(var gap:prerequisiteGaps.entrySet()){
            PackageInventory.Entry e=snapshot.find(gap.getKey());
            for(String id:gap.getValue()){
                PackageInventory.Entry p=snapshot.find(id);String name=p==null?id:p.name;
                selection.add(new PackageInventory.Issue("PREREQUISITE_OFF",PackageInventory.Severity.BLOCKING,e.directory,
                    e.name+" needs "+name+", which is turned off.","Tick "+name+", or untick "+e.name+"."));
            }
        }
        Set<String> withTools=new HashSet<>();
        for(BmlTools.Problem p:toolProblems)if(!disabled.contains(p.modId())&&Boolean.TRUE.equals(requested.get(p.modId()))){selection.add(p.issue());withTools.add(p.modId());}
        modsWithToolErrors=withTools;choiceNeeded=choices;

        Path exe=service.config().bitwigExecutable();
        List<PackageInventory.Issue> bitwig=new ArrayList<>();
        if(exe==null||!Files.isRegularFile(exe))bitwig.add(new PackageInventory.Issue("BITWIG_NOT_FOUND",PackageInventory.Severity.BLOCKING,
            exe==null?service.paths().root:exe,exe==null?"Bitwig Studio hasn't been chosen.":"Bitwig Studio isn't at "+exe+".",
            "Choose Bitwig Studio in Settings."));

        List<PackageInventory.Issue> block=new ArrayList<>(bitwig);block.addAll(selection);
        for(PackageInventory.Entry e:modEntries())if(ticked(e)&&e.unavailable==null)
            for(PackageInventory.Issue i:e.issues)if(i.blocking()&&!block.contains(i))block.add(i);
        blockers=List.copyOf(block);

        List<PackageInventory.Issue> all=new ArrayList<>(bitwig);all.addAll(selection);
        // A disabled mod's issues, and those of components made for it, aren't shown.
        for(PackageInventory.Issue i:DisabledMods.visible(snapshot.issues(),snapshot.entries(),snapshot.components().parts(),disabled))
            if(!all.contains(i))all.add(i);
        all.addAll(startupIssues);all.addAll(notes);
        all.sort(Comparator.comparingInt(i->i.severity().ordinal()));
        allIssues=List.copyOf(all);

        fillMods();fillErrors();updateNav();updateErrorStrip();updateControls();updateSelectionBar();
        if(loaded&&!busy&&!sessionRunning&&!launchCheckRunning)setStatus(readyText());
    }
    private String readyText(){
        int n=modEntries().size();
        String mods=n+(n==1?" mod":" mods");
        if(!blockers.isEmpty())return blockers.size()+(blockers.size()==1?" error blocks":" errors block")+" Load · "+mods;
        return "Ready · "+mods;
    }

    // =====================================================================================
    // Maps
    // =====================================================================================
    private JComponent buildMapsPage(){
        importMap.addActionListener(e->importMap());
        openMaps.addActionListener(e->openFolder(service.paths().root.resolve("maps")));
        mapsTable.setDefaultRenderer(Object.class,new Cell((c,row,col)->styleMap(c,mapsModel.get(row),col)));
        mapsTable.columns("maps",ListTable.FIT,ListTable.FILL,ListTable.FIT,ListTable.FIT);
        mapsTable.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())updateSelectionBar();});
        mapsStatus.setBackground(WELL);
        JPanel content=new JPanel(new BorderLayout());content.setBackground(WELL);
        content.add(mapsStatus,BorderLayout.NORTH);
        content.add(scroll(mapsTable,WELL),BorderLayout.CENTER);
        JTextArea note=wrapping(AppText.MAPS_NOTE,ui(13),MUTED);
        JPanel noteBox=new JPanel(new BorderLayout());noteBox.setBackground(WELL);noteBox.setBorder(new EmptyBorder(10,14,12,14));noteBox.add(note);
        content.add(noteBox,BorderLayout.SOUTH);
        return page("Maps",mapsMeta,null,content);
    }
    private void styleMap(Cell c,MapPacks.Pack p,int col){
        MapPacks.Pack inUse=maps.inUse();
        if(p.problem()!=null){
            if(col==0){c.a=p.id();c.ca=TEXT;}
            if(col==1){c.a="Can't be used: "+p.problem();c.ca=FAULT;c.fa=ui(13);}
            return;
        }
        switch(col){
            case 0->{c.a="Bitwig "+p.bitwig();c.b="  ·  "+p.platform();c.cb=MUTED;}
            case 1->{c.a=p.loads();c.ca=MUTED;c.fa=ui(13);}
            case 2->{if(p==inUse){c.a="In use";c.ca=ACCENT;c.fa=semibold(13);}}
            case 3->{c.a=p.version();c.ca=MUTED;c.fa=ui(13);}
            default->{}
        }
    }
    private void fillMaps(){
        mapsModel.set(maps.packs());
        mapsMeta.setText(maps.packs().size()+" installed");
        MapPacks.Pack inUse=maps.inUse();
        Color dot;String head,detail;
        if(inUse!=null){dot=ACCENT;head="Bitwig Studio "+inUse.bitwig()+" is supported";
            detail="Using the "+inUse.bitwig()+" map, version "+inUse.version()+". Mods made for Bitwig "+inUse.loads()+" load on it.";}
        else if(maps.installedSha()!=null){dot=FAULT;head="No map for the installed Bitwig Studio";
            detail="Mods can't load until you import the map made for your Bitwig version.";}
        else{dot=DIM;head="Bitwig Studio not found";detail="Choose Bitwig Studio in Settings.";}
        mapsStatus.removeAll();
        JPanel card=new JPanel(new BorderLayout(14,0));card.setBackground(FRAME);
        card.setBorder(new CompoundBorder(new MatteBorder(0,0,1,0,SHADOW),new EmptyBorder(12,14,12,14)));
        JLabel h=label(head,semibold(15),TEXT);h.setIcon(new DotIcon(dot,9));h.setIconTextGap(10);
        JPanel text=new JPanel(new BorderLayout(0,3));text.setOpaque(false);
        text.add(h,BorderLayout.NORTH);
        JLabel d=label(detail,ui(13),MUTED);d.setBorder(new EmptyBorder(0,19,0,0));text.add(d,BorderLayout.CENTER);
        card.add(text,BorderLayout.CENTER);card.add(row(importMap,openMaps),BorderLayout.EAST);
        mapsStatus.add(card);mapsStatus.revalidate();mapsStatus.repaint();
        updateNav();
    }
    private void importMap(){
        Path file=NativeFilePicker.open(frame,"map","Import a "+AppText.NAME+" map ZIP","zip",null);
        if(file==null)return;
        importMap.setEnabled(false);
        new SwingWorker<String,Void>(){
            @Override protected String doInBackground() throws Exception {return MapPackImporter.importZip(file,service.paths().root);}
            @Override protected void done(){
                importMap.setEnabled(true);
                try{setStatus(get()+"; used from the next Load.");readEverything(false);}
                catch(Exception ex){setStatus("The map couldn't be imported: "+cause(ex).getMessage());}
            }
        }.execute();
    }

    // =====================================================================================
    // Errors
    // =====================================================================================
    private JComponent buildErrorsPage(){
        errorsTable.setDefaultRenderer(Object.class,new Cell((c,row,col)->styleIssue(c,errorsModel.get(row),col)));
        errorsTable.columns("errors",ListTable.FIT,ListTable.FIT,-55,-45);
        errorsTable.getSelectionModel().addListSelectionListener(e->{if(!e.getValueIsAdjusting())updateSelectionBar();});
        errorsContent.setBackground(WELL);
        errorsContent.add(scroll(errorsTable,WELL),"list");
        errorsContent.add(empty("No installation issues","Installed mods, components and settings have nothing to fix."),"empty");
        return page("Errors",errorsMeta,null,errorsContent);
    }
    private static String severity(PackageInventory.Issue i){
        return switch(i.severity()){case BLOCKING->"Blocking";case WARNING->"Warning";case INFO->"Info";};
    }
    private void styleIssue(Cell c,PackageInventory.Issue i,int col){
        switch(col){
            case 0->{c.a=severity(i);c.ca=i.blocking()?FAULT:i.severity()==PackageInventory.Severity.WARNING?TEXT:DIM;c.fa=semibold(13);}
            case 1->{c.a=packageName(i);c.ca=TEXT;c.fa=ui(13);}
            case 2->{c.a=i.message();c.ca=i.severity()==PackageInventory.Severity.INFO?MUTED:TEXT;c.fa=ui(13);}
            case 3->{c.a=i.remedy();c.ca=MUTED;c.fa=ui(13);}
            default->{}
        }
    }
    private String packageName(PackageInventory.Issue i){
        if("BITWIG_NOT_FOUND".equals(i.code()))return "Bitwig Studio";
        for(PackageInventory.Entry e:modEntries())if(e.directory.equals(i.packagePath())&&e.id!=null)return e.name;
        Path f=i.packagePath().getFileName();return f==null?i.packagePath().toString():f.toString();
    }
    private void fillErrors(){
        PackageInventory.Issue keep=selectedIssue();
        errorsModel.set(allIssues);
        long blocking=allIssues.stream().filter(PackageInventory.Issue::blocking).count();
        long info=allIssues.stream().filter(i->i.severity()==PackageInventory.Severity.INFO).count();
        long warnings=allIssues.size()-blocking-info;
        errorsMeta.setText(allIssues.isEmpty()?"":blocking+" blocking · "+warnings+(warnings==1?" warning":" warnings")+" · "+info+" info");
        errorsCards.show(errorsContent,allIssues.isEmpty()?"empty":"list");
        reselect(errorsTable,keep==null?-1:allIssues.indexOf(keep));
    }
    private PackageInventory.Issue selectedIssue(){int r=errorsTable.getSelectedRow();return r<0||r>=errorsModel.items.size()?null:errorsModel.get(r);}
    private void showIssue(PackageInventory.Issue issue){
        activate(Page.ERRORS);
        int row=allIssues.indexOf(issue);
        reselect(errorsTable,row);
        errorsTable.requestFocusInWindow();
    }
    /** Where an issue's Open Location goes: the package's folder (a loose file's folder), the
     *  components folder for a choice of a parent, the session folder; only inside Patchbay's own
     *  mods, components and sessions folders, never through a link. */
    private Path issueLocation(PackageInventory.Issue issue){
        String code=issue.code();
        if("COMPONENT_SELECTION_BLOCKED".equals(code)||"NO_USABLE_COMPONENTS".equals(code))return service.paths().root.resolve("components");
        if("BITWIG_NOT_FOUND".equals(code))return null;
        Path p=issue.packagePath();
        if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))p=p.getParent();
        return safeLocation(p)?p:null;
    }
    private boolean safeLocation(Path path){
        if(path==null)return false;
        Path n=path.toAbsolutePath().normalize();
        Path rootPath=service.paths().root.toAbsolutePath().normalize();
        List<Path> roots=List.of(service.paths().mods.toAbsolutePath().normalize(),rootPath.resolve("components"),service.paths().sessionsDir.toAbsolutePath().normalize());
        Path base=roots.stream().filter(n::startsWith).findFirst().orElse(null);
        if(base==null||!Files.isDirectory(n,LinkOption.NOFOLLOW_LINKS))return false;
        for(Path p=n;p!=null&&p.startsWith(rootPath);p=p.getParent()){
            if(Files.isSymbolicLink(p))return false;
            try{if(Files.readAttributes(p,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isOther())return false;}
            catch(IOException ex){return false;}
        }
        try{return n.toRealPath().startsWith(base.toRealPath());}catch(IOException ex){return false;}
    }

    // =====================================================================================
    // Sessions
    // =====================================================================================
    private JComponent buildSessionsPage(){
        sessionsTable.setDefaultRenderer(Object.class,new Cell((c,row,col)->styleSession(c,sessionsModel.get(row),col)));
        sessionsTable.columns("sessions",36,ListTable.FILL,ListTable.FIT,ListTable.FIT,ListTable.FIT);
        sessionsTable.getSelectionModel().addListSelectionListener(e->{
            if(e.getValueIsAdjusting())return;
            int r=sessionsTable.getSelectedRow();
            selectedSession=r<0?null:sessionsModel.get(r).id;
            updateSelectionBar();
        });
        clearAll.addActionListener(e->clearAllSessions());
        cleanupNotice.setBackground(FRAME);cleanupNotice.setVisible(false);
        JPanel list=new JPanel(new BorderLayout());list.setBackground(WELL);
        list.add(cleanupNotice,BorderLayout.NORTH);list.add(scroll(sessionsTable,WELL),BorderLayout.CENTER);
        sessionsContent.setBackground(WELL);
        sessionsContent.add(list,"list");
        sessionsContent.add(empty("No sessions yet","A session is kept each time "+AppText.NAME+" starts Bitwig."),"empty");
        sessionsPoll=new javax.swing.Timer(1200,e->{if(page==Page.SESSIONS&&frame.isShowing())readSessions();});
        sessionsPoll.start();
        return page("Sessions",sessionsMeta,row(null,clearAll),sessionsContent);
    }
    private static boolean active(SessionArchive a){return !a.terminal();}
    private static String sessionStatus(SessionArchive a){
        if(active(a))return "Active";
        return switch(a.state()){case "Complete"->"Complete";case "Failed"->"Failed";default->"Incomplete";};
    }
    private void styleSession(Cell c,SessionArchive a,int col){
        String st=sessionStatus(a);
        switch(col){
            case 0->c.dot=active(a)?ACCENT:"Complete".equals(st)?DIM:FAULT;
            case 1->{c.a=SESSION_DATE.format(a.created);c.ca=TEXT;}
            case 2->{c.a=st;c.ca="Complete".equals(st)||active(a)?MUTED:FAULT;c.fa=ui(13);}
            case 3->{c.a=a.debug?"Debug":"Normal";c.ca=MUTED;c.fa=ui(13);}
            case 4->{c.a=a.modIds.isBlank()?"0":String.valueOf(a.modIds.split(",").length);c.ca=MUTED;c.fa=ui(13);}
            default->{}
        }
    }
    private record SessionsRead(List<SessionArchive> sessions,int exports,long[] old){}
    private void readSessions(){
        if(sessionsReading)return;
        sessionsReading=true;
        Path reminder=service.paths().stateDir.resolve("sessions-cleanup-reminder.properties");
        new SwingWorker<SessionsRead,Void>(){
            @Override protected SessionsRead doInBackground() throws Exception {
                List<SessionArchive> all=service.archivedSessions();
                long count=0,bytes=0;
                if(!remindedRecently(reminder))for(SessionArchive a:all)if(eligibleOld(a)){count++;bytes+=folderSize(a.folder());}
                return new SessionsRead(all,service.archivedExportCount(),new long[]{count,bytes});
            }
            @Override protected void done(){
                sessionsReading=false;
                try{
                    SessionsRead r=get();
                    String keep=selectedSession;
                    sessionsModel.set(r.sessions());exportCount=r.exports();
                    reselect(sessionsTable,keep==null?-1:indexOf(r.sessions(),a->a.id.equals(keep)));
                    if(keep!=null&&sessionsTable.getSelectedRow()<0)selectedSession=null;
                    sessionsMeta.setText(r.sessions().size()+(r.sessions().size()==1?" session":" sessions"));
                    sessionsCards.show(sessionsContent,r.sessions().isEmpty()?"empty":"list");
                    clearAll.setEnabled(exportCount>0||r.sessions().stream().anyMatch(a->a.terminal()&&!a.processConfirmedAlive()));
                    fillCleanupNotice(r.old()[0],r.old()[1],reminder);
                    updateNav();updateSelectionBar();
                }catch(Exception ex){setStatus("Sessions couldn't be read: "+cause(ex).getMessage());}
            }
        }.execute();
    }
    /** The clean-up offer at the top of Sessions: completed sessions older than 7 days, unless
     *  "Keep for Now" was chosen in the last 7 days. */
    private void fillCleanupNotice(long count,long bytes,Path reminder){
        cleanupNotice.removeAll();
        if(count==0){cleanupNotice.setVisible(false);return;}
        cleanupNotice.setBorder(new CompoundBorder(new MatteBorder(0,0,1,0,SHADOW),new EmptyBorder(7,14,7,14)));
        FlatButton clear=new FlatButton("Clear Old Sessions"),keep=new FlatButton("Keep for Now");
        clear.addActionListener(e->clearOldSessions());
        keep.addActionListener(e->{markReminded(reminder);cleanupNotice.setVisible(false);});
        String text=count+(count==1?" session":" sessions")+" finished more than 7 days ago ("+sizeText(bytes)+").";
        cleanupNotice.add(row(label(text,ui(13),MUTED),null,clear,keep));
        cleanupNotice.setVisible(true);cleanupNotice.revalidate();cleanupNotice.repaint();
    }
    private SessionArchive selectedSessionArchive(){
        int r=sessionsTable.getSelectedRow();return r<0||r>=sessionsModel.items.size()?null:sessionsModel.get(r);
    }
    private static String ran(SessionArchive a){
        Duration d=a.ran();
        if(d==null)return active(a)?"running":"run time not recorded";
        long ms=d.toMillis();
        if(ms<60_000)return String.format(Locale.ROOT,"ran %.1f s",ms/1000.0);
        long min=ms/60_000;
        if(min<60)return "ran "+min+" min";
        return "ran "+min/60+" h "+min%60+" min";
    }
    private void viewLog(SessionArchive a){
        boolean live=active(a)&&a.processConfirmedAlive();
        reader.showLog(SESSION_DATE.format(a.created),"Session log"+(live?" · live":"")+" · "+a.id,a.folder(),live);
        rootCards.show(root,"reader");
    }
    private void zipSession(SessionArchive a){
        if(!a.terminal()||a.processConfirmedAlive())return;
        runAsync("Making the session's ZIP",false,()->{
            Path zip=service.zipArchivedSession(a.id);
            try{service.platform().openFolder(zip.getParent());}catch(Exception ignored){}
            return zip;
        },zip->setStatus("Session ZIP made: "+zip.getFileName()));
    }
    private void deleteSession(SessionArchive a){
        if(!a.terminal()||a.processConfirmedAlive())return;
        if(!PromptDialog.confirm(frame,"Delete this session?",
            "The session from "+SESSION_DATE.format(a.created)+" will be permanently deleted, with its log and diagnostics.",
            "Session ZIPs you made are kept.","Delete Session"))return;
        runAsync("Deleting the session",true,()->{service.deleteArchivedSession(a.id);return null;},x->{
            selectedSession=null;setStatus("Session deleted.");readSessions();
        });
    }
    private void clearAllSessions(){
        long closed=sessionsModel.items.stream().filter(a->a.terminal()&&!a.processConfirmedAlive()).count();
        if(closed==0&&exportCount==0){setStatus("No closed sessions or session ZIPs to clear.");return;}
        if(!PromptDialog.confirm(frame,"Clear all sessions?",
            counted(closed,"closed session","closed sessions",exportCount,"session ZIP","session ZIPs")+" will be permanently deleted.",
            "Active sessions and unrelated files are kept."+(exportCount>0?" Deleted ZIPs can't be recovered.":""),"Clear All"))return;
        runAsync("Clearing sessions",true,service::clearAllArchivedSessionsAndExports,r->{
            readSessions();
            String deleted=counted(r.sessionsDeleted(),"session","sessions",r.exportsDeleted(),"ZIP","ZIPs");
            setStatus((deleted.isEmpty()?"Nothing deleted":"Deleted "+deleted)+(r.failures().isEmpty()?".":"; "+r.failures().size()+" couldn't be deleted."));
            if(!r.failures().isEmpty())PromptDialog.inform(frame,"Some items couldn't be deleted",String.join("\n",r.failures()));
        });
    }
    private void clearOldSessions(){
        runAsync("Clearing old sessions",true,()->{
            int deleted=0;List<String> failures=new ArrayList<>();
            for(SessionArchive a:service.archivedSessions())if(eligibleOld(a)){
                try{service.deleteArchivedSession(a.id);deleted++;}catch(IOException ex){failures.add(a.id+": "+ex.getMessage());}
            }
            return new AbstractMap.SimpleEntry<>(deleted,failures);
        },r->{
            readSessions();
            setStatus("Deleted "+r.getKey()+(r.getKey()==1?" old session":" old sessions")+(r.getValue().isEmpty()?".":"; "+r.getValue().size()+" couldn't be deleted."));
            if(!r.getValue().isEmpty())PromptDialog.inform(frame,"Some sessions couldn't be deleted",String.join("\n",r.getValue()));
        });
    }
    private static boolean eligibleOld(SessionArchive a){
        return "Complete".equals(a.state())&&a.ended()!=null&&a.ended().isBefore(Instant.now().minus(Duration.ofDays(7)))&&!a.processConfirmedAlive();
    }
    private static boolean remindedRecently(Path state) throws IOException {
        if(!Files.exists(state,LinkOption.NOFOLLOW_LINKS))return false;
        if(Files.isSymbolicLink(state))return true;
        Properties saved=new Properties();try(var in=Files.newInputStream(state)){saved.load(in);}
        try{return !Instant.parse(saved.getProperty("last_shown_utc","")).isBefore(Instant.now().minus(Duration.ofDays(7)));}
        catch(Exception unreadable){return false;}
    }
    private static void markReminded(Path state){
        try{
            Properties p=new Properties();p.setProperty("last_shown_utc",Instant.now().toString());
            Files.createDirectories(state.getParent());
            Path temp=Files.createTempFile(state.getParent(),".cleanup-",".tmp");
            try(var out=Files.newOutputStream(temp)){p.store(out,AppText.NAME+" session clean-up: Keep for Now");}
            try{Files.move(temp,state,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException ex){Files.move(temp,state,StandardCopyOption.REPLACE_EXISTING);}
        }catch(IOException ignored){}
    }
    private static long folderSize(Path folder){
        try(var files=Files.walk(folder)){
            return files.filter(f->Files.isRegularFile(f,LinkOption.NOFOLLOW_LINKS)).mapToLong(f->{try{return Files.size(f);}catch(IOException gone){return 0;}}).sum();
        }catch(IOException|java.io.UncheckedIOException unreadable){return 0;}
    }
    private static String sizeText(long bytes){
        if(bytes<1024*1024)return Math.max(1,Math.round(bytes/1024.0))+" KB";
        double mb=bytes/(1024.0*1024.0);
        if(mb<1024)return (mb<10?String.format(Locale.ROOT,"%.1f",mb):String.valueOf(Math.round(mb)))+" MB";
        return String.format(Locale.ROOT,"%.1f GB",mb/1024);
    }
    private static String counted(long first,String one,String many,long second,String secondOne,String secondMany){
        List<String> parts=new ArrayList<>();
        if(first>0)parts.add(first+" "+(first==1?one:many));
        if(second>0)parts.add(second+" "+(second==1?secondOne:secondMany));
        return String.join(" and ",parts);
    }

    // =====================================================================================
    // Settings
    // =====================================================================================
    private JComponent buildSettingsPage(){
        settingsContent.setLayout(new BoxLayout(settingsContent,BoxLayout.Y_AXIS));
        settingsContent.setBackground(WELL);settingsContent.setBorder(new EmptyBorder(16,16,24,16));
        showDebug.setSelected(!service.config().hideDebugMode());
        showDebug.addActionListener(e->changeShowDebug());
        JPanel holder=new JPanel(new BorderLayout()){
            @Override public Dimension getPreferredSize(){
                Dimension d=super.getPreferredSize();
                return new Dimension(getParent()==null?d.width:getParent().getWidth(),d.height);
            }
        };
        holder.setBackground(WELL);holder.add(settingsContent,BorderLayout.NORTH);
        JScrollPane s=scroll(holder,WELL);
        s.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return page("Settings",null,null,s);
    }
    /** A card of the Settings page: a flat header, then its rows. */
    private static JPanel card(String title,JComponent... rows){
        JPanel c=new JPanel(new BorderLayout());c.setBackground(FRAME);c.setBorder(new LineBorder(SHADOW));
        c.add(pageHeader(title,null),BorderLayout.NORTH);
        JPanel body=new JPanel();body.setOpaque(false);body.setLayout(new BoxLayout(body,BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(14,16,16,16));
        for(int i=0;i<rows.length;i++){rows[i].setAlignmentX(0);body.add(rows[i]);if(i<rows.length-1)body.add(Box.createVerticalStrut(8));}
        c.add(body,BorderLayout.CENTER);
        c.setAlignmentX(0);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE,Integer.MAX_VALUE));
        return c;
    }
    private static JComponent text(String s,Font f,Color c){
        JTextArea a=wrapping(s,f,c);
        JPanel p=new JPanel(new BorderLayout()){@Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}};
        p.setOpaque(false);p.add(a);return p;
    }
    private void fillSettings(){
        settingsContent.removeAll();
        Path exe=service.config().bitwigExecutable();
        boolean found=exe!=null&&Files.isRegularFile(exe);
        JLabel state=label(found?"Bitwig Studio found":exe==null?"Choose Bitwig Studio":"Bitwig Studio isn't at this path",semibold(15),TEXT);
        state.setIcon(new DotIcon(found?ACCENT:exe==null?DIM:FAULT,9));state.setIconTextGap(10);
        JTextField path=field(null);path.setEditable(false);path.setText(exe==null?"":exe.toString());
        path.setMaximumSize(new Dimension(Integer.MAX_VALUE,CONTROL_HEIGHT));
        FlatButton browse=new FlatButton("Browse…");browse.setEnabled(!busy&&!sessionRunning);browse.addActionListener(e->chooseBitwig());
        settingsContent.add(card("Bitwig Studio",state,text(AppText.NAME+" starts this program when you press Load. Choosing it saves it.",ui(13),MUTED),row(path,browse)));
        settingsContent.add(Box.createVerticalStrut(14));

        showDebug.setEnabled(!sessionRunning);
        settingsContent.add(card("Debug Mode",showDebug,text("Shows the Debug Mode box beside Load. Debug Mode records traces for finding problems in mods; it is off each time "
            +AppText.NAME+" opens. Hidden, launches are always normal; a session already running in Debug Mode stays in it.",ui(13),MUTED)));
        settingsContent.add(Box.createVerticalStrut(14));

        BmlPaths paths=service.paths();
        FlatButton open=new FlatButton("Open Folder"),delete=new FlatButton("Delete "+AppText.NAME+"'s Settings…",Kind.DANGER);
        open.setEnabled(Files.isDirectory(paths.configDir));delete.setEnabled(paths.perUser);
        open.addActionListener(e->openFolder(paths.configDir));delete.addActionListener(e->deleteSettingsFolder());
        settingsContent.add(card("Settings Folder",
            label(paths.perUser?"Your settings are kept here":"This test install keeps its settings here",semibold(15),TEXT),
            text(paths.configDir.toString(),ui(14),TEXT),
            text(paths.perUser?"Each mod's settings and files, which mods are ticked, and "+AppText.NAME+"'s own choices. Every copy of "+AppText.NAME
                +" on this computer uses them. Deleting removes this one folder for every copy; "+AppText.NAME+" then closes. Bitwig has to be closed first."
                :"Nothing here is shared with other copies, and it goes when the install does.",ui(13),MUTED),
            row(open,delete)));
        settingsContent.add(Box.createVerticalStrut(14));

        JLabel name=label(AppText.NAME+" "+BmlService.VERSION,semibold(20),TEXT);name.setIcon(markIcon(48));name.setIconTextGap(14);
        String versions=MapPacks.supported(maps.packs());
        FlatButton github=new FlatButton("Open on GitHub");github.addActionListener(e->openLink(AppText.GITHUB));
        settingsContent.add(card("About",name,
            text(versions.isEmpty()?"No map is installed, so no Bitwig Studio version is supported yet.":"Supports Bitwig Studio "+versions+".",ui(13),MUTED),
            text(AppText.ABOUT,ui(14),TEXT),
            text(AppText.DISCLAIMER,ui(13),MUTED),
            row(github)));
        settingsContent.add(Box.createVerticalStrut(14));
        settingsContent.add(card("Acknowledgements",text(AppText.ACKNOWLEDGEMENTS,ui(14),TEXT)));
        settingsContent.revalidate();settingsContent.repaint();
        SwingUtilities.invokeLater(()->settingsContent.scrollRectToVisible(new Rectangle(0,0,1,1)));
    }
    private void chooseBitwig(){
        Path p=service.config().bitwigExecutable();
        String programs=System.getenv("ProgramFiles");
        Path start=p!=null?p.getParent():BmlPaths.WINDOWS&&programs!=null?Path.of(programs,"Bitwig Studio"):BmlPaths.MAC?Path.of("/Applications"):BmlConfig.LINUX_DEFAULT.getParent();
        String packages=BmlPaths.MAC?System.setProperty("apple.awt.use-file-dialog-packages","true"):null;
        Path chosen;
        try{chosen=NativeFilePicker.open(frame,"bitwig","Choose Bitwig Studio",BmlPaths.WINDOWS?"exe":null,start);}
        finally{if(BmlPaths.MAC){if(packages==null)System.clearProperty("apple.awt.use-file-dialog-packages");else System.setProperty("apple.awt.use-file-dialog-packages",packages);}}
        if(chosen==null)return;
        Path exe=BmlConfig.executableOf(chosen.toAbsolutePath().normalize());
        if(!Files.isRegularFile(exe)){setStatus("That isn't a program: "+exe);return;}
        Path previous=service.config().bitwigExecutable();
        service.config().setBitwigExecutable(exe);
        try{service.config().save();setStatus("Bitwig Studio saved: "+exe);}
        catch(IOException ex){service.config().setBitwigExecutable(previous);setStatus("Bitwig Studio couldn't be saved: "+ex.getMessage());}
        fillSettings();readEverything(false);
    }
    private void changeShowDebug(){
        boolean shown=showDebug.isSelected();
        boolean before=!service.config().hideDebugMode();
        service.config().setHideDebugMode(!shown);
        try{service.config().save();}
        catch(IOException ex){service.config().setHideDebugMode(!before);showDebug.setSelected(before);setStatus("The setting couldn't be saved: "+ex.getMessage());return;}
        if(!shown)debugMode.setSelected(false);
        updateControls();
        setStatus(shown?"Debug Mode is shown beside Load.":"Debug Mode is hidden; launches are normal.");
    }
    private void deleteSettingsFolder(){
        BmlPaths paths=service.paths();
        String refusal=SettingsFolder.deleteRefusal(paths,service.bitwigRunning());
        if(refusal!=null){PromptDialog.inform(frame,"Can't delete the settings",refusal);return;}
        if(!PromptDialog.confirm(frame,"Delete "+AppText.NAME+"'s settings?","This deletes the folder "+paths.configDir+" and everything in it: every mod's settings and files, and which mods are ticked.",
            "Your projects, your devices and "+AppText.NAME+"'s own folder aren't touched. "+AppText.NAME+" closes afterwards.","Delete and Close"))return;
        try{SettingsFolder.delete(paths,service.bitwigRunning());frame.dispose();System.exit(0);}
        catch(Exception ex){PromptDialog.inform(frame,"The settings couldn't be deleted",String.valueOf(ex.getMessage()));}
    }

    // =====================================================================================
    // The selection bar and the error strip
    // =====================================================================================
    private void buildSelectionBar(){
        selectionBar.setBackground(ACCENT_SEL);
        selectionBar.setBorder(new CompoundBorder(new MatteBorder(1,0,0,0,ACCENT),new EmptyBorder(8,14,8,14)));
        selectionBar.setVisible(false);
    }
    private String barShown;
    /** What the bar shows now, as text: the bar is made again only when this changes, so a refresh
     *  (Sessions reads every 1.2 s) never replaces a button while it is being pressed. */
    private String barSignature(){
        StringBuilder b=new StringBuilder(page.name()).append('|').append(busy).append('|').append(sessionRunning);
        switch(page){
            case MODS->{
                int r=modsTable.getSelectedRow();
                if(r<0||r>=modsModel.rows.size())return b.append("none").toString();
                PackageInventory.Entry e=modsModel.get(r);
                b.append(System.identityHashCode(e)).append(ticked(e)).append(isDisabled(e)).append(e.id==null?null:neededBy.get(e.id))
                    .append(componentChoices).append(toolProblems.size())
                    .append(allIssues.stream().filter(i->i.packagePath().equals(e.directory)).count());
            }
            case MAPS->b.append(mapsTable.getSelectedRow()).append(System.identityHashCode(maps));
            case ERRORS->{PackageInventory.Issue i=selectedIssue();b.append(i).append(toolProblems);}
            case SESSIONS->{
                SessionArchive a=selectedSessionArchive();
                if(a!=null)b.append(a.id).append(a.state()).append(a.ran()).append(a.pid()).append(a.processConfirmedAlive());
            }
            default->{}
        }
        return b.toString();
    }
    private void updateSelectionBar(){
        String signature=barSignature();
        if(signature.equals(barShown))return;
        barShown=signature;
        selectionBar.removeAll();
        JComponent[] content=switch(page){
            case MODS->modBar();
            case MAPS->mapBar();
            case ERRORS->issueBar();
            case SESSIONS->sessionBar();
            default->null;
        };
        if(content==null){selectionBar.setVisible(false);selectionBar.revalidate();return;}
        selectionBar.add(content[0],BorderLayout.CENTER);
        if(content[1]!=null)selectionBar.add(content[1],BorderLayout.EAST);
        selectionBar.setVisible(true);selectionBar.revalidate();selectionBar.repaint();
    }
    /** The bar's left side: the item's name, then a short muted summary. */
    private static JComponent barText(String title,String detail){return barText(title,null,detail);}
    /** The bar's left side: the item's name, its description (wrapped, at most three lines), then
     *  a small muted line of facts. */
    private static JComponent barText(String title,String description,String detail){
        JPanel p=new JPanel();p.setOpaque(false);p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));
        JLabel t=label(title,semibold(15),TEXT),d=label(detail,ui(12),MUTED);
        t.setAlignmentX(0);d.setAlignmentX(0);
        p.add(t);
        if(description!=null&&!description.isBlank()){
            ClampedText text=new ClampedText(description.strip(),ui(13),TEXT,3);text.setAlignmentX(0);
            p.add(Box.createVerticalStrut(3));p.add(text);
        }
        p.add(Box.createVerticalStrut(3));p.add(d);
        p.setMinimumSize(new Dimension(80,0));
        return p;
    }

    /** Text wrapped to the width it is given, at most {@code maxLines} lines; the last line of a
     *  longer text ends in "…". */
    private static final class ClampedText extends JComponent {
        private final String text;private final int maxLines;
        ClampedText(String text,Font font,Color color,int maxLines){
            this.text=text.replaceAll("\\s+"," ");this.maxLines=maxLines;setFont(font);setForeground(color);
        }
        @Override public javax.accessibility.AccessibleContext getAccessibleContext(){
            if(accessibleContext==null){
                accessibleContext=new AccessibleJComponent(){
                    @Override public javax.accessibility.AccessibleRole getAccessibleRole(){return javax.accessibility.AccessibleRole.LABEL;}
                };
                accessibleContext.setAccessibleName(text);
            }
            return accessibleContext;
        }
        private int lineHeight(){return getFontMetrics(getFont()).getHeight();}
        @Override public Dimension getPreferredSize(){
            int w=getWidth()>0?getWidth():560;
            return new Dimension(80,lineHeight()*Math.max(1,lines(w).size()));
        }
        @Override public Dimension getMinimumSize(){return new Dimension(0,getPreferredSize().height);}
        @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
        @Override public void setBounds(int x,int y,int w,int h){
            int before=getWidth()>0?lines(getWidth()).size():-1;
            super.setBounds(x,y,w,h);
            // The height follows the width: a new width that changes the line count lays the bar out again.
            if(w>0&&lines(w).size()!=before&&lines(w).size()*lineHeight()!=h)SwingUtilities.invokeLater(this::revalidate);
        }
        private List<String> lines(int width){
            FontMetrics fm=getFontMetrics(getFont());
            List<String> out=new ArrayList<>();
            String rest=text;
            while(!rest.isEmpty()&&out.size()<maxLines){
                int n=fit(fm,rest,width);
                if(n>=rest.length()){out.add(rest);rest="";break;}
                int cut=rest.lastIndexOf(' ',n);
                if(cut<=0)cut=Math.max(1,n);
                out.add(rest.substring(0,cut).stripTrailing());
                rest=rest.substring(cut).stripLeading();
            }
            if(!rest.isEmpty()&&!out.isEmpty()){
                String last=out.get(out.size()-1)+" "+rest,dots="…";
                int n=fit(fm,last,width-fm.stringWidth(dots));
                int cut=last.lastIndexOf(' ',n);
                out.set(out.size()-1,(cut>n/2?last.substring(0,cut):last.substring(0,Math.max(0,n))).stripTrailing()+dots);
            }
            return out;
        }
        /** How many characters of {@code s} fit in {@code width}. */
        private static int fit(FontMetrics fm,String s,int width){
            if(fm.stringWidth(s)<=width)return s.length();
            int lo=0,hi=s.length();
            while(lo<hi){int mid=(lo+hi+1)/2;if(fm.stringWidth(s.substring(0,mid))<=width)lo=mid;else hi=mid-1;}
            return lo;
        }
        @Override protected void paintComponent(Graphics gr){
            Graphics2D g=(Graphics2D)gr.create();
            Object hints=Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if(hints instanceof Map<?,?> m)g.addRenderingHints(m);
            else g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont());g.setColor(getForeground());
            FontMetrics fm=g.getFontMetrics();
            int y=fm.getAscent();
            for(String line:lines(getWidth())){g.drawString(line,0,y);y+=fm.getHeight();}
            g.dispose();
        }
    }

    private JComponent[] modBar(){
        int row=modsTable.getSelectedRow();
        if(row<0||row>=modsModel.rows.size())return null;
        PackageInventory.Entry e=modsModel.get(row);
        boolean off=isDisabled(e);
        String why=off?null:whyNotDisable(e);
        List<String> summary=new ArrayList<>();
        summary.add(e.id==null?"no ID":"ID "+e.id);
        if(off)summary.add("disabled: never loaded, its errors hidden");
        if(!e.requires.isEmpty())summary.add("needs "+names(e.requires.keySet()));
        long issues=allIssues.stream().filter(i->i.packagePath().equals(e.directory)).count();
        if(issues>0)summary.add(issues+(issues==1?" issue":" issues"));
        if(why!=null&&e.id!=null)summary.add("can't be disabled: "+why);
        String title=e.name+(e.version.isBlank()?"":"  "+e.version);
        List<Component> controls=new ArrayList<>();
        if(!off)for(ComponentCatalog.Group g:e.groups.values())controls.addAll(componentControls(e,g));
        if(!off&&!e.groups.isEmpty()){
            FlatButton folder=new FlatButton("Components Folder");
            folder.addActionListener(x->openFolder(service.paths().root.resolve("components")));controls.add(folder);
        }
        FolderListConfig devices=DeviceFoldersTool.config(service.paths().mods);
        if(devices!=null&&e.id!=null&&e.id.equals(devices.modId)){
            FlatButton df=new FlatButton("Device folders…");df.addActionListener(x->deviceFolders());controls.add(df);
        }
        if(e.documentation!=null){
            FlatButton readme=new FlatButton("README");readme.addActionListener(x->openReadme(e));controls.add(readme);
        }
        if(e.homepage!=null){
            FlatButton web=new FlatButton("Open on the Web");web.addActionListener(x->openLink(e.homepage.toString()));controls.add(web);
        }
        FlatButton open=new FlatButton("Open Folder");open.addActionListener(x->openFolder(e.directory));controls.add(open);
        if(e.id!=null){
            FlatButton toggle=new FlatButton(off?"Enable Mod":"Disable Mod");
            toggle.addActionListener(x->{if(off)enableMod(e);else disableMod(e);});
            toggle.setEnabled(!busy&&why==null);controls.add(toggle);
        }
        return new JComponent[]{barText(title,e.description,String.join("  ·  ",summary)),row(controls.toArray(new Component[0]))};
    }
    /** A component group in a mod's bar: a dropdown for a single choice, tick boxes for several. */
    private List<Component> componentControls(PackageInventory.Entry parent,ComponentCatalog.Group group){
        List<Component> out=new ArrayList<>();
        List<ComponentCatalog.Part> parts=snapshot.components().children(parent.id,group.id());
        List<String> chosen=ComponentSelectionStore.chosen(componentChoices,parent.id,group.id());
        out.add(label(group.name(),ui(13),MUTED));
        if(parts.isEmpty()){out.add(label("none installed",ui(13),group.minimum()>0?FAULT:MUTED));return out;}
        boolean editable=!busy&&parent.id!=null;
        if("single".equals(group.selection())){
            List<String> labels=new ArrayList<>();List<ComponentCatalog.Part> usable=new ArrayList<>();
            for(ComponentCatalog.Part p:parts)if(p.usable()){usable.add(p);labels.add(p.name);}
            String missing=chosen.isEmpty()?null:chosen.get(0);
            boolean present=missing!=null&&usable.stream().anyMatch(p->p.id.equals(missing));
            if(missing!=null&&!present)labels.add(0,missing+" (missing)");
            JComboBox<String> box=combo(labels.toArray(new String[0]));
            int at=present?indexOf(usable,p->p.id.equals(missing)):missing!=null?0:-1;
            box.setSelectedIndex(at);
            box.setEnabled(editable);
            box.addActionListener(x->{
                int i=box.getSelectedIndex()-(missing!=null&&!present?1:0);
                if(i>=0&&i<usable.size())changeComponent(parent,group,usable.get(i));
            });
            out.add(box);
        }else for(ComponentCatalog.Part p:parts){
            Tick t=new Tick(p.name);t.setSelected(chosen.contains(p.id));t.setEnabled(editable&&p.usable());
            t.addActionListener(x->changeComponent(parent,group,p));out.add(t);
        }
        return out;
    }
    private void changeComponent(PackageInventory.Entry parent,ComponentCatalog.Group group,ComponentCatalog.Part part){
        if(busy||!part.usable())return;
        String key=ComponentSelectionStore.key(parent.id,group.id());
        List<String> current=new ArrayList<>(ComponentSelectionStore.chosen(componentChoices,parent.id,group.id()));
        if("single".equals(group.selection())){if(current.equals(List.of(part.id)))return;current=List.of(part.id);}
        else if(current.contains(part.id)){
            if(current.size()<=group.minimum()&&ticked(parent)){
                setStatus((group.minimum()==1?"At least one stays":"At least "+group.minimum()+" stay")+" chosen while "+parent.name+" is ticked.");updateSelectionBar();return;
            }
            current.remove(part.id);
        }else current.add(part.id);
        componentChoices.put(key,List.copyOf(current));
        try{new ComponentSelectionStore(service.paths()).save(componentChoices);}
        catch(IOException ex){setStatus("The choice couldn't be saved: "+ex.getMessage());return;}
        setStatus(group.name()+": "+part.name+(current.contains(part.id)?" chosen":" no longer chosen")+"; used from the next Load.");
        readEverything(false);
    }
    private void openReadme(PackageInventory.Entry e){
        try{
            reader.showMarkdown(e.name,"README · "+e.version,SafeMarkdown.load(e.documentation),e.documentation,e.directory);
            rootCards.show(root,"reader");
        }catch(IOException ex){setStatus("The README couldn't be read: "+ex.getMessage());}
    }
    private void closeReader(){rootCards.show(root,"main");}

    private JComponent[] mapBar(){
        int row=mapsTable.getSelectedRow();
        if(row<0||row>=mapsModel.items.size())return null;
        MapPacks.Pack p=mapsModel.get(row);
        String title=p.problem()!=null?p.id():"Bitwig "+p.bitwig()+" · "+p.platform();
        String detail=p.problem()!=null?"Can't be used: "+p.problem():"Map "+p.id()+" · version "+p.version()+(p==maps.inUse()?" · in use":"");
        FlatButton open=new FlatButton("Open Folder");open.addActionListener(x->openFolder(p.directory()));
        return new JComponent[]{barText(title,detail),row(open)};
    }
    private JComponent[] issueBar(){
        PackageInventory.Issue i=selectedIssue();
        if(i==null)return null;
        List<Component> controls=new ArrayList<>();
        BmlTools.Problem problem=toolProblems.stream().filter(p->p.issue().equals(i)).findFirst().orElse(null);
        if(problem!=null){
            FlatButton df=new FlatButton("Device folders…");df.addActionListener(x->deviceFolders());controls.add(df);
            BmlTools.Tool tool=BmlTools.ALL.stream().filter(t->t.name().equals(problem.toolName())).findFirst().orElse(null);
            if(tool!=null&&tool.resolveLabel()!=null){
                FlatButton fix=new FlatButton(tool.resolveLabel());
                fix.addActionListener(x->{
                    try{tool.resolve(service.paths().mods,problem);setStatus("Removed "+problem.subject()+"; used from the next Load.");}
                    catch(IOException ex){setStatus("The folder list couldn't be saved: "+ex.getMessage());}
                    runToolChecks(null);
                });
                controls.add(fix);
            }
        }
        if("BITWIG_NOT_FOUND".equals(i.code())){FlatButton s=new FlatButton("Open Settings");s.addActionListener(x->activate(Page.SETTINGS));controls.add(s);}
        Path where=issueLocation(i);
        boolean openable=where!=null&&problem==null;
        if(openable){FlatButton open=new FlatButton("Open Location");open.addActionListener(x->openFolder(where));controls.add(open);}
        // The path is left out when Open Location goes there.
        String detail=severity(i)+"  ·  "+packageName(i)+"  ·  "+i.remedy()+"  ·  "+i.code()+(openable?"":"  ·  "+i.packagePath());
        return new JComponent[]{barText(i.message(),detail),controls.isEmpty()?null:row(controls.toArray(new Component[0]))};
    }
    private JComponent[] sessionBar(){
        SessionArchive a=selectedSessionArchive();
        if(a==null)return null;
        boolean closed=a.terminal()&&!a.processConfirmedAlive();
        int mods=a.modIds.isBlank()?0:a.modIds.split(",").length;
        String detail=sessionStatus(a)+"  ·  "+ran(a)+"  ·  "+(a.debug?"Debug":"Normal")+"  ·  "+mods+(mods==1?" mod":" mods")
            +(a.pid()>0?"  ·  PID "+a.pid():"")+"  ·  "+a.id;
        FlatButton log=new FlatButton("View Log"),open=new FlatButton("Open Folder"),zip=new FlatButton("ZIP Session"),del=new FlatButton("Delete Session",Kind.DANGER);
        log.addActionListener(x->viewLog(a));
        open.addActionListener(x->{try{openFolder(SessionArchive.locate(service.paths(),a.id));}catch(IOException ex){setStatus(ex.getMessage());}});
        zip.addActionListener(x->zipSession(a));del.addActionListener(x->deleteSession(a));
        zip.setEnabled(closed&&!busy);del.setEnabled(closed&&!busy);
        return new JComponent[]{barText(SESSION_DATE.format(a.created),detail),row(log,open,zip,del)};
    }

    private void buildErrorStrip(){
        errorStrip.setBackground(FAULT_BG);
        errorStrip.setBorder(new CompoundBorder(new MatteBorder(1,0,0,0,FAULT),new EmptyBorder(0,14,0,14)));
        JLabel badge=new JLabel("ERROR"){
            @Override protected void paintComponent(Graphics gr){
                Graphics2D g=(Graphics2D)gr.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(FAULT);g.fillRoundRect(0,(getHeight()-18)/2,getWidth(),18,4,4);g.dispose();super.paintComponent(gr);
            }
        };
        badge.setFont(caps(11));badge.setForeground(FAULT_INK);badge.setBorder(new EmptyBorder(0,7,0,7));
        badge.setHorizontalAlignment(SwingConstants.CENTER);
        showInErrors.addActionListener(e->{if(!blockers.isEmpty()){closeReader();showIssue(blockers.get(0));}});
        JPanel text=new JPanel();text.setOpaque(false);text.setLayout(new BoxLayout(text,BoxLayout.X_AXIS));
        text.add(badge);text.add(Box.createHorizontalStrut(12));text.add(stripText);text.add(Box.createHorizontalStrut(12));text.add(stripFix);
        errorStrip.add(text,BorderLayout.CENTER);
        JPanel right=row(showInErrors);right.setBorder(new EmptyBorder(0,12,0,0));
        errorStrip.add(right,BorderLayout.EAST);
        errorStrip.setPreferredSize(new Dimension(10,42));
        errorStrip.setMaximumSize(new Dimension(Integer.MAX_VALUE,42));
        errorStrip.setVisible(false);
    }
    private void updateErrorStrip(){
        if(blockers.isEmpty()){errorStrip.setVisible(false);return;}
        PackageInventory.Issue first=blockers.get(0);
        stripText.setText(first.message()+(blockers.size()>1?"  (+"+(blockers.size()-1)+" more)":""));
        stripFix.setText(first.remedy());
        errorStrip.setVisible(true);errorStrip.revalidate();errorStrip.repaint();
    }

    // =====================================================================================
    // Device folders
    // =====================================================================================
    /** The folders Custom Nitro Registration scans for the user's own devices: add and remove. */
    private void deviceFolders(){
        FolderListConfig config=DeviceFoldersTool.config(service.paths().mods);
        if(config==null){setStatus("Framework: Custom Nitro Registration isn't installed.");return;}
        JDialog d=new JDialog(frame,"Device folders",Dialog.ModalityType.APPLICATION_MODAL);
        d.setUndecorated(true);
        JPanel rootPanel=new JPanel(new BorderLayout());rootPanel.setBackground(FRAME);rootPanel.setBorder(new LineBorder(SHADOW));
        JPanel head=new JPanel(new BorderLayout());head.setBackground(FRAME);
        head.setBorder(new CompoundBorder(new MatteBorder(0,0,1,0,LINE),new EmptyBorder(10,18,10,18)));
        head.add(label("Device folders",semibold(17),TEXT));
        rootPanel.add(head,BorderLayout.NORTH);
        JPanel list=new JPanel();list.setOpaque(false);list.setLayout(new BoxLayout(list,BoxLayout.Y_AXIS));
        list.setBorder(new EmptyBorder(14,18,8,18));
        rootPanel.add(list,BorderLayout.CENTER);
        FlatButton add=new FlatButton("Add Folder…"),done=new FlatButton("Close");
        JPanel buttons=row(add,null,done);buttons.setBorder(new EmptyBorder(8,18,16,18));
        rootPanel.add(buttons,BorderLayout.SOUTH);
        Runnable[] fill=new Runnable[1];
        fill[0]=()->{
            list.removeAll();
            JTextArea intro=wrapping(config.modName+" looks through these folders and their subfolders each time Bitwig starts, and registers every "
                +".bwdevice, .bwmodulator and .bwmodule file it finds while it is ticked. A change here is used from the next Load.",ui(13),MUTED);
            intro.setSize(new Dimension(560,Short.MAX_VALUE));intro.setAlignmentX(0);list.add(intro);list.add(Box.createVerticalStrut(12));
            List<String> folders;
            try{folders=config.read();}
            catch(IOException ex){JLabel l=label("The folder list can't be read: "+ex.getMessage(),ui(13),FAULT);l.setAlignmentX(0);list.add(l);add.setEnabled(false);d.pack();return;}
            Map<String,FolderListConfig.FolderState> states=FolderListConfig.check(new LinkedHashSet<>(folders));
            if(folders.isEmpty()){JLabel l=label("No folders yet.",ui(13),MUTED);l.setAlignmentX(0);list.add(l);}
            for(String folder:folders){
                FolderListConfig.FolderState st=states.getOrDefault(folder,FolderListConfig.FolderState.OK);
                FlatButton remove=new FlatButton("Remove");
                remove.addActionListener(x->{
                    List<String> next=new ArrayList<>(folders);next.remove(folder);
                    try{config.write(next);setStatus("Removed "+folder+"; used from the next Load.");}
                    catch(IOException ex){PromptDialog.inform(d,"The folder list couldn't be saved",String.valueOf(ex.getMessage()));}
                    fill[0].run();
                });
                JLabel name=label(folder,ui(14),st==FolderListConfig.FolderState.OK?TEXT:FAULT);
                JLabel note=label(st==FolderListConfig.FolderState.MISSING?"Not found":st==FolderListConfig.FolderState.UNREADABLE?"Can't be read":"",ui(12),FAULT);
                JPanel r=row(name,note,null,remove);r.setAlignmentX(0);r.setMaximumSize(new Dimension(Integer.MAX_VALUE,32));
                list.add(r);list.add(Box.createVerticalStrut(6));
            }
            list.revalidate();d.pack();d.setSize(Math.max(620,d.getWidth()),d.getHeight());
        };
        add.addActionListener(x->{
            Path chosen=NativeFilePicker.openFolder(frame,DeviceFoldersTool.ID,"Choose a folder with your devices");
            if(chosen==null)return;
            String folder=chosen.toAbsolutePath().normalize().toString();
            try{
                List<String> current=config.read();
                if(current.contains(folder)){setStatus(folder+" is already listed.");return;}
                List<String> next=new ArrayList<>(current);next.add(folder);config.write(next);
                setStatus("Added "+folder+"; used from the next Load.");
            }catch(IOException ex){PromptDialog.inform(d,"The folder list couldn't be saved",String.valueOf(ex.getMessage()));}
            fill[0].run();
        });
        done.addActionListener(x->d.dispose());
        d.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),"close");
        d.getRootPane().getActionMap().put("close",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){d.dispose();}});
        d.setContentPane(rootPanel);
        fill[0].run();
        d.setLocationRelativeTo(frame);d.setVisible(true);
        runToolChecks(null);
    }

    // =====================================================================================
    // Footer, Load, Stop / Quit and the running session
    // =====================================================================================
    private JComponent buildFooter(){
        JPanel f=new JPanel(new BorderLayout());f.setBackground(TITLE);
        f.setBorder(new CompoundBorder(new MatteBorder(1,0,0,0,SHADOW),new EmptyBorder(0,14,0,10)));
        f.setPreferredSize(new Dimension(10,46));f.setMaximumSize(new Dimension(Integer.MAX_VALUE,46));
        prepareBar.setVisible(false);
        f.add(row(statusDot,status,prepareBar),BorderLayout.CENTER);
        f.add(row(debugMode,stop,load),BorderLayout.EAST);
        load.addActionListener(e->startLaunch());
        stop.addActionListener(e->stopQuit());
        debugMode.setSelected(false);
        return f;
    }
    private void updateControls(){
        boolean launchable=service.platform().launchSupported();
        load.setCount((int)modEntries().stream().filter(e->e.loadable()&&ticked(e)).count());
        load.setEnabled(loaded&&!busy&&!sessionRunning&&!launchCheckRunning&&blockers.isEmpty()&&launchable);
        stop.setEnabled(!busy&&sessionRunning);
        // The filled button is the next thing to do: Stop / Quit while Bitwig runs, Load otherwise.
        // The keyboard follows it when the other one just went disabled under it.
        boolean wasStop=stop.isPrimary();
        stop.setPrimary(sessionRunning);load.setPrimary(!sessionRunning);
        if(wasStop!=sessionRunning){
            if(sessionRunning&&load.isFocusOwner()&&stop.isEnabled())stop.requestFocusInWindow();
            else if(!sessionRunning&&stop.isFocusOwner()&&load.isEnabled())load.requestFocusInWindow();
        }
        boolean showDebugBox=!service.config().hideDebugMode();
        debugMode.setVisible(showDebugBox);debugMode.setEnabled(!busy&&!sessionRunning);
        if(!showDebugBox)debugMode.setSelected(false);
        refresh.setEnabled(!busy);selectAll.setEnabled(!busy&&loaded);addMod.setEnabled(!busy&&loaded);
        long loadable=modEntries().stream().filter(PackageInventory.Entry::loadable).count();
        long on=modEntries().stream().filter(PackageInventory.Entry::loadable).filter(this::ticked).count();
        selectAll.setText(loadable>0&&on==loadable?"Deselect All":"Select All");
        statusDot.color=!blockers.isEmpty()?FAULT:sessionRunning?ACCENT:DIM;statusDot.repaint();
    }
    private void setStatus(String s){status.setText(s);}

    private void startLaunch(){
        if(launchCheckRunning||busy)return;
        launchCheckRunning=true;updateControls();setStatus("Checking…");
        // A folder may have gone since the last check: check again first, without holding up the window.
        runToolChecks(()->{launchCheckRunning=false;updateControls();launchChecked();});
    }
    private void launchChecked(){
        if(!blockers.isEmpty()){
            PackageInventory.Issue first=blockers.get(0);
            setStatus(readyText());
            PromptDialog.inform(frame,"Load refused",first.message()+" "+first.remedy()+(blockers.size()>1?" ("+blockers.size()+" errors; see Errors.)":""));
            return;
        }
        LinkedHashMap<String,Boolean> selection=new LinkedHashMap<>();
        // Disabled mods go in unticked: they are never prepared or loaded.
        for(PackageInventory.Entry e:snapshot.entries())if(e.loadable()&&Files.isRegularFile(e.manifest))selection.put(e.id,ticked(e));
        boolean debug=!service.config().hideDebugMode()&&debugMode.isSelected();
        beginPrepareProgress();
        runAsync("Preparing mods",true,()->service.launch(selection,debug,line->{}),s->{
            endPrepareProgress();
            refreshSessionState();
        });
    }
    private void stopQuit(){
        setStatus("Asking Bitwig to close. If it asks to save, answer it in Bitwig.");
        runAsync("Closing Bitwig",false,()->{service.stopAndQuit(line->{});return null;},x->{refreshSessionState();setStatus("Bitwig closed.");});
    }
    private void refreshSessionState(){
        try{applySessionState(service.reconcileCurrentSession());}
        catch(IOException ex){setStatus("The session couldn't be read: "+ex.getMessage());}
    }
    private void applySessionState(Optional<SessionInfo> opt){
        boolean was=sessionRunning;
        runningSession=opt.filter(SessionInfo::processAlive).orElse(null);
        sessionRunning=runningSession!=null;
        if(sessionRunning){
            boolean details=!service.config().hideDebugMode();
            setStatus("Bitwig is running"+(runningSession.traceEnabled?" in Debug Mode":"")
                +(details?" · session "+runningSession.id+" · PID "+runningSession.pid:""));
            watch(runningSession);
        }else{
            clearWatcher();
            if(was)setStatus(opt.isPresent()&&"exited-unverified".equals(opt.get().status)?"Bitwig closed; how it closed couldn't be confirmed.":"Bitwig closed.");
            else if(loaded&&!busy)setStatus(readyText());
        }
        readSessions();
        updateControls();
        if(page==Page.SETTINGS)fillSettings();
    }
    private void watch(SessionInfo s){
        String key=s.identityKey();if(key.equals(watchedIdentity))return;
        clearWatcher();watchedIdentity=key;long gen=++watchGeneration;
        ProcessHandle.of(s.pid).ifPresent(ph->ph.onExit().thenRun(()->SwingUtilities.invokeLater(()->{if(gen==watchGeneration)exitSeen(key);})));
        // onExit may be late or missed on some Windows launches: check the identity every 1.5 s too.
        sessionIdentityPoll=new javax.swing.Timer(1500,e->{if(gen==watchGeneration)exitSeen(key);});
        sessionIdentityPoll.start();
    }
    private void clearWatcher(){
        if(sessionIdentityPoll!=null){sessionIdentityPoll.stop();sessionIdentityPoll=null;}
        watchedIdentity="";watchGeneration++;
    }
    private void exitSeen(String key){
        if(busy){sessionRefreshPending=true;return;}
        try{
            Optional<SessionInfo> now=service.reconcileCurrentSession();
            if(now.isEmpty()||!key.equals(now.get().identityKey())||now.get().processAlive())return;
            applySessionState(now);
        }catch(Exception ex){setStatus("Bitwig's exit couldn't be recorded: "+ex.getMessage());}
    }

    /** Runs work off the event thread with the window busy. A failure is said in the status line,
     *  or in a dialog when it must be read ({@code mustRead}: Bitwig failing to start, a delete). */
    private <T> void runAsync(String what,boolean mustRead,Callable<T> work,Consumer<T> after){
        if(busy)return;
        busy=true;updateControls();setStatus(what+"…");
        new SwingWorker<T,Void>(){
            @Override protected T doInBackground() throws Exception {return work.call();}
            @Override protected void done(){
                busy=false;updateControls();
                try{after.accept(get());}
                catch(Exception ex){
                    endPrepareProgress();
                    String message=String.valueOf(cause(ex).getMessage());
                    setStatus(what+" failed: "+message);
                    if(mustRead)PromptDialog.inform(frame,what.startsWith("Preparing")?"Bitwig couldn't be started":what+" failed",message);
                }
                if(sessionRefreshPending){sessionRefreshPending=false;String k=watchedIdentity;SwingUtilities.invokeLater(()->exitSeen(k));}
                updateSelectionBar();
            }
        }.execute();
    }
    private static Throwable cause(Throwable t){return t.getCause()==null?t:t.getCause();}

    private void beginPrepareProgress(){
        try{Files.deleteIfExists(service.paths().stateDir.resolve(PrepareProgress.PROGRESS_FILE));}catch(IOException ignored){}
        prepareBar.setFraction(0);prepareBar.setVisible(true);
        if(prepareProgressPoll!=null)prepareProgressPoll.stop();
        prepareProgressPoll=new javax.swing.Timer(200,e->{
            if(!busy){endPrepareProgress();return;}
            PrepareProgress.Snapshot p=PrepareProgress.read(service.paths().stateDir);
            if(p==null)return;
            prepareBar.setFraction(p.fraction());
            if(!p.state().equals("running")){setStatus("Mods ready · starting Bitwig…");return;}
            StringBuilder t=new StringBuilder(p.label());
            if(p.modsTotal()>0)t.append(" · ").append(p.modsDone()).append(" of ").append(p.modsTotal()).append(" mods prepared");
            if(p.remainingMs()>=0)t.append(" · ").append(remainingText(p.remainingMs()));
            setStatus(t.toString());
        });
        prepareProgressPoll.start();
    }
    private void endPrepareProgress(){
        if(prepareProgressPoll!=null){prepareProgressPoll.stop();prepareProgressPoll=null;}
        prepareBar.setVisible(false);
    }
    static String remainingText(long ms){
        long s=(ms+999)/1000;
        if(s<60)return "about "+Math.max(5,(s+4)/5*5)+" s left";
        return "about "+(s+30)/60+" min left";
    }

    // =====================================================================================
    // Small pieces
    // =====================================================================================
    private void openFolder(Path folder){
        new SwingWorker<Void,Void>(){
            @Override protected Void doInBackground() throws Exception {service.platform().openFolder(folder);return null;}
            @Override protected void done(){try{get();}catch(Exception ex){setStatus("The folder couldn't be opened: "+cause(ex).getMessage());}}
        }.execute();
    }
    private void openLink(String url){
        try{
            URI u=URI.create(url);
            if(!PromptDialog.confirm(frame,"Open this link?",u.toString(),"It opens in your web browser.","Open Link"))return;
            if(!Desktop.isDesktopSupported()||!Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))throw new IOException("This system can't open web pages from Java.");
            Desktop.getDesktop().browse(u);
        }catch(Exception ex){setStatus("The link couldn't be opened: "+ex.getMessage());}
    }

    /** The footer's status dot. */
    private static final class StatusDot extends JComponent {
        Color color=DIM;
        StatusDot(){setPreferredSize(new Dimension(7,7));setMaximumSize(new Dimension(7,7));setMinimumSize(new Dimension(7,7));}
        @Override protected void paintComponent(Graphics gr){
            Graphics2D g=(Graphics2D)gr.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);g.fillOval(0,0,7,7);g.dispose();
        }
    }
    private static final class DotIcon implements Icon {
        private final Color color;private final int size;
        DotIcon(Color color,int size){this.color=color;this.size=size;}
        public int getIconWidth(){return size;}public int getIconHeight(){return size;}
        public void paintIcon(Component c,Graphics gr,int x,int y){
            Graphics2D g=(Graphics2D)gr.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);g.fillOval(x,y,size,size);g.dispose();
        }
    }
    /** Preparation progress in the footer: the control grey, filled in the accent. */
    private static final class PrepareBar extends JComponent {
        private double fraction;
        PrepareBar(){setPreferredSize(new Dimension(160,4));setMaximumSize(new Dimension(160,4));}
        void setFraction(double f){double n=Math.max(0,Math.min(1,f));if(n!=fraction){fraction=n;repaint();}}
        @Override protected void paintComponent(Graphics g){
            g.setColor(CONTROL);g.fillRect(0,0,getWidth(),getHeight());
            g.setColor(ACCENT);g.fillRect(0,0,(int)Math.round(getWidth()*fraction),getHeight());
        }
    }

    /** Windows: the dark title bar, through {@link DarkTitleBar} (Java 22+) by reflection; nothing
     *  happens on another platform, an older Java or any failure. macOS gets its dark appearance from
     *  the root pane's property; Linux follows the desktop. */
    private static void darkTitleBar(JFrame frame,String uniqueTitle){
        if(!BmlPaths.WINDOWS)return;
        try{
            Class.forName("dev.bitwigre.bml.DarkTitleBar").getDeclaredMethod("apply",String.class).invoke(null,uniqueTitle);
        }catch(Throwable unavailable){
            // The normal title bar stays.
        }
    }
}
