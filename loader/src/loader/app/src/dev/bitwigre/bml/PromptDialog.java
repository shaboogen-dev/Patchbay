package dev.bitwigre.bml;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;

import static dev.bitwigre.bml.PatchbayStyle.*;

/**
 * A question or a message that must be read, over the window in its own style: a header with the
 * title and ×, the text, an optional muted note, and the buttons at the right. With two buttons the
 * first is the action and the second the safe answer: Enter, Esc, × and closing the dialog all give
 * the safe answer, so they never delete anything. With one button (a message) it is the only answer.
 */
final class PromptDialog extends JDialog {
    private static final int WIDTH=480;
    private boolean accepted;

    PromptDialog(Window owner,String title,String detail,String note,String yes,String no,boolean dangerous){
        super(owner,title,Dialog.ModalityType.APPLICATION_MODAL);
        setUndecorated(true);setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        FlatButton accept=new FlatButton(yes,dangerous?Kind.DANGER:Kind.NORMAL);
        FlatButton decline=no==null?accept:new FlatButton(no);
        accept.addActionListener(e->{accepted=true;dispose();});
        if(decline!=accept)decline.addActionListener(e->dispose());

        JPanel head=new JPanel(new BorderLayout());head.setBackground(FRAME);
        head.setBorder(new CompoundBorder(new MatteBorder(0,0,1,0,LINE),new EmptyBorder(0,18,0,6)));
        head.setPreferredSize(new Dimension(WIDTH,44));
        head.add(label(title,semibold(17),TEXT),BorderLayout.CENTER);
        JButton close=new JButton("×"){
            @Override protected void paintComponent(Graphics gr){
                Graphics2D g=(Graphics2D)gr.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getModel().isRollover()?TEXT:MUTED);g.setStroke(new BasicStroke(1.4f));
                int cx=getWidth()/2,cy=getHeight()/2;g.drawLine(cx-5,cy-5,cx+5,cy+5);g.drawLine(cx-5,cy+5,cx+5,cy-5);g.dispose();
            }
        };
        close.setContentAreaFilled(false);close.setBorderPainted(false);close.setFocusable(false);close.setRolloverEnabled(true);
        close.setPreferredSize(new Dimension(36,36));close.addActionListener(e->dispose());
        close.getAccessibleContext().setAccessibleName("Close");
        head.add(close,BorderLayout.EAST);

        JPanel body=new JPanel();body.setBackground(FRAME);body.setLayout(new BoxLayout(body,BoxLayout.Y_AXIS));
        body.setBorder(new EmptyBorder(16,18,16,18));
        JTextArea text=wrapping(detail,ui(14),TEXT);text.setSize(new Dimension(WIDTH-36,Short.MAX_VALUE));
        text.setAlignmentX(0);body.add(text);
        if(note!=null){
            body.add(Box.createVerticalStrut(6));
            JTextArea n=wrapping(note,ui(13),MUTED);n.setSize(new Dimension(WIDTH-36,Short.MAX_VALUE));n.setAlignmentX(0);body.add(n);
        }
        JPanel buttons=decline==accept?row(null,accept):row(null,decline,accept);
        buttons.setBorder(new EmptyBorder(0,18,16,18));

        JPanel root=new JPanel(new BorderLayout());root.setBackground(FRAME);root.setBorder(new LineBorder(SHADOW));
        root.add(head,BorderLayout.NORTH);root.add(body,BorderLayout.CENTER);root.add(buttons,BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().setDefaultButton(decline);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),"decline");
        getRootPane().getActionMap().put("decline",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){dispose();}});
        pack();
        setSize(WIDTH,getPreferredSize().height);
        addWindowListener(new WindowAdapter(){@Override public void windowOpened(WindowEvent e){decline.requestFocusInWindow();}});
    }
    /** Shows it centred over its window; true if the first button was chosen. */
    boolean ask(){setLocationRelativeTo(getOwner());setVisible(true);return accepted;}
    /** Asks before an action; true only if the action's button was pressed. */
    static boolean confirm(Window owner,String title,String detail,String note,String action){
        return new PromptDialog(owner,title,detail,note,action,"Cancel",action.startsWith("Delete")||action.startsWith("Clear")).ask();
    }
    /** A message with a single OK. */
    static void inform(Window owner,String title,String detail){
        new PromptDialog(owner,title,detail,null,"OK",null,false).ask();
    }
}
