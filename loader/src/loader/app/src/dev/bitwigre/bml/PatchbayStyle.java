package dev.bitwigre.bml;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.*;
import java.util.List;

/**
 * Patchbay's look, in one place: the colours of the brand mock-up, the bundled fonts (Outfit for
 * the interface, JetBrains Mono for logs; the platform's own when the build carries none), the
 * socket-and-cable mark, and the few controls every page shares. Everything is flat: fills and
 * one-pixel lines, no gradients, no shadows, no motion.
 */
final class PatchbayStyle {
    private PatchbayStyle(){}

    static final Color TITLE=new Color(0x17181A),FRAME=new Color(0x26282B),HEAD=new Color(0x313337);
    static final Color WELL=new Color(0x1C1D20),BLACK=new Color(0x121315),LINE=new Color(0x3B3D42),HAIR=new Color(0x25262A);
    static final Color TEXT=new Color(0xE4E5E7),MUTED=new Color(0x9C9FA5),DIM=new Color(0x63666C);
    static final Color FAULT=new Color(0xF0524A),FAULT_BG=new Color(0x2E1A19),FAULT_INK=new Color(0x1F0F0E);
    /** Patch Teal: the accent, its text, and the selected-row tint. */
    static final Color ACCENT=new Color(0x2EC4B6),ACCENT_INK=new Color(0x06201D),ACCENT_SEL=new Color(0x1E3836);
    static final Color CONTROL=new Color(0x2E3034),CONTROL_HOVER=new Color(0x36383D),SHADOW=Color.BLACK;

    // ---- fonts ----
    private static final Font UI_REGULAR,UI_SEMIBOLD,MONO;
    /** True when the bundled fonts were found in the jar. */
    static final boolean BUNDLED_FONTS;
    static {
        Font regular=load("Outfit-Regular.ttf"),semibold=load("Outfit-SemiBold.ttf"),mono=load("JetBrainsMono-Regular.ttf");
        BUNDLED_FONTS=regular!=null&&semibold!=null&&mono!=null;
        String family=family("Segoe UI","Helvetica Neue","Noto Sans","DejaVu Sans","Dialog");
        UI_REGULAR=regular!=null?regular:new Font(family,Font.PLAIN,14);
        String semiFamily=family("Segoe UI Semibold",family);
        UI_SEMIBOLD=semibold!=null?semibold:new Font(semiFamily,semiFamily.equals(family)?Font.BOLD:Font.PLAIN,14);
        MONO=mono!=null?mono:new Font(family("Consolas","Menlo","DejaVu Sans Mono","Monospaced"),Font.PLAIN,13);
    }
    private static Font load(String name){
        try(InputStream in=PatchbayStyle.class.getResourceAsStream("fonts/"+name)){
            if(in==null)return null;
            Font f=Font.createFont(Font.TRUETYPE_FONT,in);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(f);
            return f;
        }catch(Exception unreadable){return null;}
    }
    private static String family(String... candidates){
        Set<String> installed=new HashSet<>();
        for(String n:GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT))installed.add(n.toLowerCase(Locale.ROOT));
        for(String c:candidates)if(installed.contains(c.toLowerCase(Locale.ROOT)))return c;
        return Font.DIALOG;
    }
    static Font ui(float size){return UI_REGULAR.deriveFont(Font.PLAIN,size);}
    static Font semibold(float size){return UI_SEMIBOLD.deriveFont(size);}
    static Font mono(float size){return MONO.deriveFont(Font.PLAIN,size);}
    /** Small capitals for panel headers, column heads and sidebar groups: semibold, tracked +9%. */
    static Font caps(float size){
        Map<TextAttribute,Object> a=new HashMap<>();a.put(TextAttribute.TRACKING,0.09f);
        return semibold(size).deriveFont(a);
    }

    /** The look and feel under the painted controls: Metal, so every platform starts the same,
     *  with the defaults it would otherwise draw in light colours set dark. */
    static void install() throws Exception {
        UIManager.put("swing.boldMetal",Boolean.FALSE);
        UIManager.setLookAndFeel(new MetalLookAndFeel());
        Font base=ui(14);
        for(String key:List.of("Label.font","Button.font","CheckBox.font","TextField.font","ComboBox.font","Table.font",
                "TableHeader.font","TextArea.font","TextPane.font","EditorPane.font","List.font","PopupMenu.font","MenuItem.font","OptionPane.font"))
            UIManager.put(key,new javax.swing.plaf.FontUIResource(base));
        for(String key:List.of("Panel.background","OptionPane.background","Viewport.background","ScrollPane.background",
                "SplitPane.background","PopupMenu.background","List.background","Table.background","TextArea.background"))
            UIManager.put(key,new javax.swing.plaf.ColorUIResource(WELL));
        for(String key:List.of("Label.foreground","List.foreground","Table.foreground","TextField.foreground","TextArea.foreground",
                "CheckBox.foreground","Button.foreground","ComboBox.foreground","OptionPane.messageForeground"))
            UIManager.put(key,new javax.swing.plaf.ColorUIResource(TEXT));
        UIManager.put("ToolTip.enableToolTipMode","none");
        UIManager.put("List.selectionBackground",new javax.swing.plaf.ColorUIResource(ACCENT_SEL));
        UIManager.put("List.selectionForeground",new javax.swing.plaf.ColorUIResource(TEXT));
        UIManager.put("ComboBox.selectionBackground",new javax.swing.plaf.ColorUIResource(ACCENT_SEL));
        UIManager.put("ComboBox.selectionForeground",new javax.swing.plaf.ColorUIResource(TEXT));
        UIManager.put("ComboBox.background",new javax.swing.plaf.ColorUIResource(CONTROL));
        UIManager.put("TextField.selectionBackground",new javax.swing.plaf.ColorUIResource(ACCENT_SEL));
        UIManager.put("TextField.selectionForeground",new javax.swing.plaf.ColorUIResource(TEXT));
        UIManager.put("TextPane.selectionBackground",new javax.swing.plaf.ColorUIResource(ACCENT_SEL));
        UIManager.put("EditorPane.selectionBackground",new javax.swing.plaf.ColorUIResource(ACCENT_SEL));
        UIManager.put("TextArea.selectionBackground",new javax.swing.plaf.ColorUIResource(ACCENT_SEL));
        UIManager.put("ScrollPane.border",BorderFactory.createEmptyBorder());
        UIManager.put("PopupMenu.border",BorderFactory.createLineBorder(LINE));
        ToolTipManager.sharedInstance().setEnabled(false);
    }

    // ---- the mark ----
    /** The mark on a 16-unit grid: two sockets of a patchbay column joined by one looped cable. */
    static void paintMark(Graphics2D g,double x,double y,double size,Color rings,Color cable){
        Graphics2D m=(Graphics2D)g.create();
        m.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        m.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,RenderingHints.VALUE_STROKE_PURE);
        m.translate(x,y);m.scale(size/16.0,size/16.0);
        m.setColor(cable);m.setStroke(new BasicStroke(2f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_ROUND));
        m.draw(new CubicCurve2D.Double(5,4,15.5,4,15.5,12,5,12));
        m.setColor(rings);m.setStroke(new BasicStroke(1.5f));
        m.draw(new Ellipse2D.Double(5-2.75,4-2.75,5.5,5.5));
        m.draw(new Ellipse2D.Double(5-2.75,12-2.75,5.5,5.5));
        m.dispose();
    }
    static Icon markIcon(int size){
        return new Icon(){
            public int getIconWidth(){return size;}
            public int getIconHeight(){return size;}
            public void paintIcon(Component c,Graphics g,int x,int y){paintMark((Graphics2D)g,x,y,size,TEXT,ACCENT);}
        };
    }
    /** The window and taskbar icons: the mark on the title-bar grey, at 16, 20, 32, 48 and 256 px. */
    static List<Image> appIcons(){
        List<Image> out=new ArrayList<>();
        for(int size:new int[]{16,20,32,48,256}){
            BufferedImage img=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(TITLE);
            double r=Math.max(2,size*0.18);
            g.fill(new RoundRectangle2D.Double(0,0,size,size,r,r));
            paintMark(g,0,0,size,TEXT,ACCENT);
            g.dispose();
            out.add(img);
        }
        return out;
    }

    // ---- shared controls ----
    /** The one height of every button, dropdown and text field, so controls in a row line up. */
    static final int CONTROL_HEIGHT=28;
    /** A flat button: {@link #CONTROL_HEIGHT} px, a one-pixel rule, square-ish corners.
     *  {@link Kind#PRIMARY} is the one filled control, the next thing to do (Load, or Stop / Quit
     *  while Bitwig runs); {@link Kind#DANGER} has red text (deleting). */
    enum Kind {NORMAL,PRIMARY,DANGER}
    static class FlatButton extends JButton {
        private Kind kind;
        private boolean swaps;
        FlatButton(String text){this(text,Kind.NORMAL);}
        FlatButton(String text,Kind kind){
            super(text);this.kind=kind;
            setContentAreaFilled(false);setBorderPainted(false);setFocusPainted(false);setOpaque(false);setRolloverEnabled(true);
            setFont(kind==Kind.PRIMARY?semibold(14):ui(13));
            setBorder(new EmptyBorder(0,12,0,12));
            setForeground(TEXT);
        }
        /** Makes this the filled primary button, or a plain one. A button that swaps keeps the width
         *  of its primary caption either way, so the row beside it never moves. */
        void setPrimary(boolean primary){
            swaps=true;
            Kind next=primary?Kind.PRIMARY:Kind.NORMAL;
            if(next==kind)return;
            kind=next;setFont(primary?semibold(14):ui(13));revalidate();repaint();
        }
        boolean isPrimary(){return kind==Kind.PRIMARY;}
        @Override public Dimension getPreferredSize(){
            Dimension d=super.getPreferredSize();
            int w=d.width;
            if(swaps){Insets in=getInsets();w=Math.max(w,getFontMetrics(semibold(14)).stringWidth(getText())+in.left+in.right);}
            return new Dimension(Math.max(w,40),CONTROL_HEIGHT);
        }
        @Override public Dimension getMaximumSize(){return getPreferredSize();}
        @Override protected void paintComponent(Graphics gr){
            Graphics2D g=(Graphics2D)gr.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            boolean on=isEnabled(),hover=on&&getModel().isRollover();
            Color fill,rule,ink;
            if(kind==Kind.PRIMARY&&on){fill=hover?ACCENT.brighter():ACCENT;rule=fill;ink=ACCENT_INK;}
            else{fill=hover?CONTROL_HOVER:CONTROL;rule=LINE;ink=!on?DIM:kind==Kind.DANGER?FAULT:TEXT;}
            g.setColor(fill);g.fill(new RoundRectangle2D.Float(0,0,getWidth(),getHeight(),4,4));
            g.setColor(rule);g.draw(new RoundRectangle2D.Float(0.5f,0.5f,getWidth()-1,getHeight()-1,4,4));
            if(isFocusOwner()){g.setColor(ACCENT);g.draw(new RoundRectangle2D.Float(1.5f,1.5f,getWidth()-3,getHeight()-3,3,3));}
            paintLabel(g,ink);
            g.dispose();
        }
        /** The caption; Load overrides it to add its count. */
        void paintLabel(Graphics2D g,Color ink){
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont());g.setColor(ink);
            FontMetrics fm=g.getFontMetrics();
            g.drawString(getText(),(getWidth()-fm.stringWidth(getText()))/2,(getHeight()-fm.getHeight())/2+fm.getAscent());
        }
    }

    /** Load: the label at the left, the count of mods that will load at the right. */
    static final class LoadButton extends FlatButton {
        private int count;
        LoadButton(){super("Load",Kind.PRIMARY);}
        void setCount(int n){if(n!=count){count=n;repaint();}}
        @Override public Dimension getPreferredSize(){return new Dimension(Math.max(112,super.getPreferredSize().width+44),CONTROL_HEIGHT);}
        @Override void paintLabel(Graphics2D g,Color ink){
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(ink);
            g.setFont(getFont());FontMetrics fm=g.getFontMetrics();
            int base=(getHeight()-fm.getHeight())/2+fm.getAscent();
            g.drawString(getText(),14,base);
            g.setFont(ui(13));String n=String.valueOf(count);
            g.drawString(n,getWidth()-14-g.getFontMetrics().stringWidth(n),base);
        }
    }

    /** A tick box: a 15 px square, accent-filled with a check when ticked, red with "!" when what
     *  it ticks blocks Load. The only control that ticks. */
    static final class Tick extends JCheckBox {
        private boolean fault;
        Tick(String text){
            super(text);setOpaque(false);setFocusPainted(false);setFont(ui(13));setForeground(TEXT);setIconTextGap(8);
            setBorder(new EmptyBorder(2,1,2,1));
            Icon icon=new Icon(){
                public int getIconWidth(){return 15;}
                public int getIconHeight(){return 15;}
                public void paintIcon(Component c,Graphics g,int x,int y){paintTick((Graphics2D)g,x,y,isSelected(),fault,isEnabled(),isFocusOwner());}
            };
            setIcon(icon);setSelectedIcon(icon);setDisabledIcon(icon);setDisabledSelectedIcon(icon);setRolloverIcon(icon);setPressedIcon(icon);
        }
        void setFault(boolean f){if(f!=fault){fault=f;repaint();}}
        @Override public void setEnabled(boolean b){super.setEnabled(b);setForeground(b?TEXT:DIM);}
    }
    static void paintTick(Graphics2D gr,int x,int y,boolean ticked,boolean fault,boolean enabled,boolean focused){
        Graphics2D g=(Graphics2D)gr.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        if(fault){
            g.setColor(FAULT);g.fill(new RoundRectangle2D.Float(x,y,15,15,3,3));
            g.setColor(FAULT_BG);g.setFont(semibold(11).deriveFont(Font.BOLD));
            FontMetrics fm=g.getFontMetrics();
            g.drawString("!",x+(15-fm.stringWidth("!"))/2f,y+(15-fm.getHeight())/2f+fm.getAscent());
        }else if(ticked){
            g.setColor(enabled?ACCENT:DIM);g.fill(new RoundRectangle2D.Float(x,y,15,15,3,3));
            g.setColor(enabled?ACCENT_INK:WELL);g.setStroke(new BasicStroke(2f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER));
            Path2D.Float check=new Path2D.Float();check.moveTo(x+3.5f,y+7.5f);check.lineTo(x+6.3f,y+10.3f);check.lineTo(x+11.6f,y+4.6f);
            g.draw(check);
        }else{
            g.setColor(enabled?DIM:HAIR.brighter());g.setStroke(new BasicStroke(1.5f));
            g.draw(new RoundRectangle2D.Float(x+0.75f,y+0.75f,13.5f,13.5f,3,3));
        }
        if(focused){g.setColor(ACCENT);g.setStroke(new BasicStroke(1f));g.draw(new RoundRectangle2D.Float(x-2.5f,y-2.5f,20,20,4,4));}
        g.dispose();
    }

    /** A dropdown: the control grey with a small arrow; its list on the frame grey. */
    static <T> JComboBox<T> combo(T[] items){
        JComboBox<T> box=new JComboBox<>(items);
        box.setUI(new BasicComboBoxUI(){
            @Override protected JButton createArrowButton(){
                JButton b=new JButton(){
                    @Override protected void paintComponent(Graphics gr){
                        Graphics2D g=(Graphics2D)gr.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(MUTED);int cx=getWidth()/2,cy=getHeight()/2;
                        g.fillPolygon(new int[]{cx-4,cx+4,cx},new int[]{cy-2,cy-2,cy+3},3);g.dispose();
                    }
                };
                b.setOpaque(false);b.setContentAreaFilled(false);b.setBorderPainted(false);b.setFocusable(false);
                b.setPreferredSize(new Dimension(22,22));
                return b;
            }
            @Override public void paintCurrentValueBackground(Graphics g,Rectangle bounds,boolean focus){}
            @Override public void paint(Graphics g,JComponent c){
                g.setColor(CONTROL);g.fillRect(0,0,c.getWidth(),c.getHeight());
                super.paint(g,c);
            }
            @Override protected javax.swing.plaf.basic.ComboPopup createPopup(){
                javax.swing.plaf.basic.BasicComboPopup p=new javax.swing.plaf.basic.BasicComboPopup(comboBox);
                p.setBorder(BorderFactory.createLineBorder(LINE));
                p.getList().setBackground(FRAME);p.getList().setForeground(TEXT);
                p.getList().setSelectionBackground(ACCENT_SEL);p.getList().setSelectionForeground(TEXT);
                return p;
            }
        });
        box.setFont(ui(13));box.setForeground(TEXT);box.setBackground(CONTROL);
        box.setBorder(new CompoundBorder(new LineBorder(LINE),new EmptyBorder(0,4,0,0)));
        box.setRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> l,Object v,int i,boolean sel,boolean focus){
                super.getListCellRendererComponent(l,v,i,sel,false);
                setBorder(new EmptyBorder(4,6,4,8));setFont(ui(13));
                setForeground(TEXT);setBackground(i<0?CONTROL:sel?ACCENT_SEL:FRAME);setOpaque(i>=0);
                return this;
            }
        });
        box.setPreferredSize(new Dimension(box.getPreferredSize().width,CONTROL_HEIGHT));
        box.setMaximumSize(box.getPreferredSize());
        return box;
    }

    /** A text field on the log black, with a muted hint while empty. */
    static JTextField field(String hint){
        JTextField f=new JTextField(){
            @Override protected void paintComponent(Graphics g){
                super.paintComponent(g);
                if(getText().isEmpty()&&hint!=null){
                    Graphics2D h=(Graphics2D)g.create();
                    h.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    h.setColor(DIM);h.setFont(getFont());FontMetrics fm=h.getFontMetrics();
                    h.drawString(hint,getInsets().left,(getHeight()-fm.getHeight())/2+fm.getAscent());h.dispose();
                }
            }
        };
        f.setFont(ui(13));f.setBackground(BLACK);f.setForeground(TEXT);f.setCaretColor(TEXT);
        f.setSelectionColor(ACCENT_SEL);f.setSelectedTextColor(TEXT);
        f.setBorder(new CompoundBorder(new LineBorder(LINE),new EmptyBorder(0,8,0,8)));
        f.setPreferredSize(new Dimension(250,CONTROL_HEIGHT));
        return f;
    }

    /** A thin dark scrollbar, drawn only when there is something to scroll. */
    static JScrollPane scroll(Component view,Color background){
        JScrollPane s=new JScrollPane(view);
        s.setBorder(BorderFactory.createEmptyBorder());s.setViewportBorder(null);
        s.getViewport().setBackground(background);s.setBackground(background);
        for(JScrollBar bar:new JScrollBar[]{s.getVerticalScrollBar(),s.getHorizontalScrollBar()}){
            bar.setUI(new BasicScrollBarUI(){
                @Override protected JButton createDecreaseButton(int o){return zero();}
                @Override protected JButton createIncreaseButton(int o){return zero();}
                private JButton zero(){JButton b=new JButton();b.setPreferredSize(new Dimension(0,0));b.setMinimumSize(new Dimension(0,0));b.setMaximumSize(new Dimension(0,0));return b;}
                @Override protected void paintTrack(Graphics g,JComponent c,Rectangle r){g.setColor(background);g.fillRect(r.x,r.y,r.width,r.height);}
                @Override protected void paintThumb(Graphics gr,JComponent c,Rectangle r){
                    if(r.isEmpty()||scrollbar.getVisibleAmount()>=scrollbar.getMaximum()-scrollbar.getMinimum())return;
                    Graphics2D g=(Graphics2D)gr.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(LINE);g.fillRoundRect(r.x+3,r.y+2,r.width-6,r.height-4,6,6);g.dispose();
                }
            });
            bar.setBackground(background);bar.setOpaque(true);
            if(bar.getOrientation()==Adjustable.VERTICAL)bar.setPreferredSize(new Dimension(11,0));else bar.setPreferredSize(new Dimension(0,11));
        }
        s.getVerticalScrollBar().setUnitIncrement(24);
        SmoothScroll.install(s);
        return s;
    }

    static JLabel label(String text,Font font,Color color){JLabel l=new JLabel(text);l.setFont(font);l.setForeground(color);return l;}

    /** A label that can shrink to no width (long text is cut with "…") but keeps its own height, so a
     *  BoxLayout row still centres it: a zero minimum height skews the row's vertical alignment. */
    static JLabel shrinkingLabel(String text,Font font,Color color){
        JLabel l=new JLabel(text){@Override public Dimension getMinimumSize(){return new Dimension(0,super.getPreferredSize().height);}};
        l.setFont(font);l.setForeground(color);return l;
    }

    /** Wrapping text that can't be edited, with no background of its own. */
    static JTextArea wrapping(String text,Font font,Color color){
        JTextArea a=new JTextArea(text){
            @Override public Dimension getPreferredSize(){
                // Wrap to the width it is given; with none yet, to a sensible line.
                if(getWidth()==0)setSize(new Dimension(560,Short.MAX_VALUE));
                return super.getPreferredSize();
            }
        };
        a.setEditable(false);a.setFocusable(false);a.setLineWrap(true);a.setWrapStyleWord(true);a.setOpaque(false);
        a.setFont(font);a.setForeground(color);a.setBorder(null);a.setHighlighter(null);
        return a;
    }

    /** A row of controls, left to right, 8 px apart; a null pushes what follows to the right end. */
    static JPanel row(Component... parts){
        JPanel p=new JPanel();p.setOpaque(false);p.setLayout(new BoxLayout(p,BoxLayout.X_AXIS));
        boolean first=true;
        for(Component c:parts){
            if(c==null){p.add(Box.createHorizontalGlue());first=true;continue;}
            if(!first)p.add(Box.createHorizontalStrut(8));
            if(c instanceof JComponent j){
                j.setAlignmentY(Component.CENTER_ALIGNMENT);
                // A control never shrinks below the shared height, so a row's controls stay one height.
                if(j.getPreferredSize().height==CONTROL_HEIGHT&&!j.isMinimumSizeSet())j.setMinimumSize(new Dimension(j.getMinimumSize().width,CONTROL_HEIGHT));
            }
            p.add(c);first=false;
        }
        return p;
    }

    /** A page's flat header strip: the title in small capitals, then a muted line. */
    static JPanel pageHeader(String title,JLabel meta){
        JPanel p=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0)){
            @Override public Dimension getPreferredSize(){return new Dimension(super.getPreferredSize().width,30);}
        };
        p.setLayout(new BoxLayout(p,BoxLayout.X_AXIS));
        p.setBackground(HEAD);p.setBorder(new CompoundBorder(new MatteBorder(0,0,1,0,SHADOW),new EmptyBorder(0,14,0,14)));
        p.add(label(title.toUpperCase(Locale.ROOT),caps(12),TEXT));
        if(meta!=null){p.add(Box.createHorizontalStrut(14));meta.setFont(ui(12));meta.setForeground(MUTED);p.add(meta);}
        p.add(Box.createHorizontalGlue());
        return p;
    }

    /** A right-click menu, drawn as a dropdown's list is: the frame grey in a hairline. */
    static JPopupMenu popupMenu(){
        JPopupMenu m=new JPopupMenu();
        m.setBackground(FRAME);m.setOpaque(true);
        m.setBorder(new CompoundBorder(new LineBorder(LINE),new EmptyBorder(3,0,3,0)));
        return m;
    }
    /** A menu entry: its text, tinted while highlighted. A greyed entry is dim and may say why
     *  after it (Patchbay has no tooltips). */
    static JMenuItem menuItem(String text,String reason,boolean enabled,Runnable action){
        JMenuItem item=new JMenuItem(text){
            @Override public Dimension getPreferredSize(){
                int w=getFontMetrics(ui(13)).stringWidth(text)+32;
                if(reason!=null)w+=getFontMetrics(ui(12)).stringWidth(reason)+14;
                return new Dimension(Math.max(170,w),28);
            }
            @Override protected void paintComponent(Graphics gr){
                Graphics2D g=(Graphics2D)gr.create();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(isEnabled()&&getModel().isArmed()?ACCENT_SEL:FRAME);g.fillRect(0,0,getWidth(),getHeight());
                g.setFont(ui(13));FontMetrics fm=g.getFontMetrics();
                int base=(getHeight()-fm.getHeight())/2+fm.getAscent();
                g.setColor(isEnabled()?TEXT:DIM);g.drawString(text,16,base);
                if(reason!=null){g.setFont(ui(12));g.setColor(DIM);g.drawString(reason,16+fm.stringWidth(text)+14,base);}
                g.dispose();
            }
        };
        item.setOpaque(true);item.setBorder(new EmptyBorder(0,0,0,0));item.setEnabled(enabled);
        item.getAccessibleContext().setAccessibleDescription(reason);
        item.addActionListener(e->action.run());
        return item;
    }
    /** A hairline between groups of menu entries. */
    static JComponent menuRule(){
        JComponent rule=new JComponent(){
            @Override protected void paintComponent(Graphics g){g.setColor(LINE);g.fillRect(0,getHeight()/2,getWidth(),1);}
        };
        rule.setPreferredSize(new Dimension(10,9));
        return rule;
    }

    /** The strip of controls under a page header. */
    static JPanel controlStrip(JComponent content){
        JPanel p=new JPanel(new BorderLayout());p.setBackground(FRAME);
        p.setBorder(new CompoundBorder(new MatteBorder(0,0,1,0,SHADOW),new EmptyBorder(8,14,8,14)));
        p.add(content);return p;
    }
}
