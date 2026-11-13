package dev.bitwigre.bml;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;

import static dev.bitwigre.bml.PatchbayStyle.*;

/**
 * Long text over the whole window: a mod's README (Markdown, with its local images) or a session's log. A
 * running session's log is read live and follows its end until scrolled up; "Follow log" turns
 * following back on. Esc or Close returns to the page that opened it.
 */
final class ReaderPanel extends JPanel {
    private static final int LOG_CHARS=512*1024;
    private final JLabel title=label("",caps(12),TEXT),meta=label("",ui(12),MUTED);
    private final Tick follow=new Tick("Follow log");
    private final FlatButton close=new FlatButton("Close");
    private final JPanel body=new JPanel(new BorderLayout());
    private final Runnable onClose;
    private javax.swing.Timer poll;
    private SessionLiveLog source;
    private SessionLogView log;
    private JScrollPane logScroll;
    private boolean reading;

    ReaderPanel(Runnable onClose){
        super(new BorderLayout());
        this.onClose=onClose;
        setBackground(WELL);
        JPanel head=new JPanel(new BorderLayout());head.setBackground(HEAD);
        head.setBorder(new CompoundBorder(new MatteBorder(0,0,1,0,SHADOW),new EmptyBorder(0,14,0,10)));
        head.setPreferredSize(new Dimension(10,40));
        head.add(row(title,meta),BorderLayout.WEST);
        head.add(row(follow,close),BorderLayout.EAST);
        add(head,BorderLayout.NORTH);
        body.setBackground(WELL);add(body,BorderLayout.CENTER);
        close.addActionListener(e->close());
        follow.addActionListener(e->{if(follow.isSelected())toEnd();});
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),"close-reader");
        getActionMap().put("close-reader",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){if(isShowing())close();}});
    }

    /** A README, with the images it may show from {@code root} (the mod's or component's folder). */
    void showMarkdown(String heading,String subtitle,String markdown,java.nio.file.Path readme,java.nio.file.Path root){
        stop();
        title.setText(heading.toUpperCase(java.util.Locale.ROOT));meta.setText(subtitle);
        follow.setVisible(false);
        GraphicsConfiguration gc=getGraphicsConfiguration();
        double scale=gc==null?1.0:gc.getDefaultTransform().getScaleX();
        JEditorPane view=SafeMarkdown.view(markdown,new SafeMarkdown.Images(readme.toAbsolutePath().getParent(),root,scale));
        JPanel column=new JPanel(new GridBagLayout());column.setBackground(WELL);
        // A readable measure: the text keeps to 820 px, centred, however wide the window is.
        JPanel limit=new JPanel(new BorderLayout()){
            @Override public Dimension getPreferredSize(){
                int w=Math.min(820,Math.max(200,getParent()==null?820:getParent().getWidth()));
                // A word or code run that can't wrap widens the text rather than being cut off.
                w=Math.max(w,view.getMinimumSize().width);
                view.setSize(new Dimension(w,Short.MAX_VALUE));
                return new Dimension(w,view.getPreferredSize().height);
            }
        };
        limit.setOpaque(false);limit.add(view);
        GridBagConstraints c=new GridBagConstraints();c.anchor=GridBagConstraints.NORTH;c.weightx=1;c.weighty=1;
        column.add(limit,c);
        JScrollPane s=scroll(column,WELL);
        setBody(s);
        SwingUtilities.invokeLater(()->{s.getVerticalScrollBar().setValue(0);close.requestFocusInWindow();});
    }

    /** A session's log, live (and following) while {@code live}. */
    void showLog(String heading,String subtitle,java.nio.file.Path sessionFolder,boolean live){
        stop();
        title.setText(heading.toUpperCase(java.util.Locale.ROOT));meta.setText(subtitle);
        log=new SessionLogView(LOG_CHARS,BLACK,new Color(0xC9CBCF),12.5f);
        log.setBorder(new EmptyBorder(10,14,10,14));
        logScroll=scroll(log,BLACK);
        logScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        logScroll.addMouseWheelListener(e->{if(e.getWheelRotation()<0)follow.setSelected(false);});
        logScroll.getVerticalScrollBar().addAdjustmentListener(e->{if(e.getValueIsAdjusting()&&!nearEnd())follow.setSelected(false);});
        JPanel frame=new JPanel(new BorderLayout());frame.setBackground(WELL);frame.setBorder(new EmptyBorder(14,16,16,16));
        JPanel well=new JPanel(new BorderLayout());well.setBorder(new LineBorder(LINE));well.add(logScroll);
        frame.add(well);
        setBody(frame);
        follow.setVisible(true);follow.setSelected(live);
        try{source=new SessionLiveLog(sessionFolder);}catch(IOException ex){log.append("[The log can't be read: "+ex.getMessage()+"]\n");return;}
        read();
        if(live){poll=new javax.swing.Timer(500,e->read());poll.start();}
        SwingUtilities.invokeLater(close::requestFocusInWindow);
    }

    private void setBody(JComponent c){body.removeAll();body.add(c);body.revalidate();body.repaint();}

    private void read(){
        if(reading||source==null)return;
        reading=true;
        SessionLiveLog cursor=source;
        new SwingWorker<SessionLiveLog.Delta,Void>(){
            @Override protected SessionLiveLog.Delta doInBackground() throws Exception {return cursor.read();}
            @Override protected void done(){
                reading=false;
                if(cursor!=source)return;
                try{
                    SessionLiveLog.Delta d=get();
                    if(d.reset()){log.clear();log.append("[Earlier lines left out]\n");}
                    if(!d.text().isEmpty()){log.append(d.text());if(follow.isSelected())toEnd();}
                    if(d.more())SwingUtilities.invokeLater(ReaderPanel.this::read);
                }catch(Exception ex){log.append("\n[The log is unavailable for now: "+ex.getMessage()+"]\n");}
            }
        }.execute();
    }
    private boolean nearEnd(){JScrollBar b=logScroll.getVerticalScrollBar();return b.getMaximum()-(b.getValue()+b.getVisibleAmount())<32;}
    private void toEnd(){if(log!=null)log.setCaretPosition(log.getDocument().getLength());}

    private void stop(){
        if(poll!=null){poll.stop();poll=null;}
        source=null;log=null;logScroll=null;
    }
    void close(){stop();onClose.run();}
}
