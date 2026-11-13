package dev.bitwigre.bml;

import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.text.*;

/**
 * A session's bml.log as read so far, in the log face (JetBrains Mono when bundled): lines wrap at word boundaries with continuation lines
 * indented past the time, so nothing scrolls sideways. Each line's UTC timestamp is shown as
 * local HH:mm:ss; the file itself is never changed. Text arrives in chunks that may end
 * mid-line, so the unfinished last line is shown as it is and redrawn when the rest arrives.
 */
final class SessionLogView extends JTextPane {
    private static final Pattern STAMP=Pattern.compile("(?m)^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z(?= |$)");
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HH:mm:ss");
    private final int maxChars;
    private String tail="";
    private int tailShown;

    SessionLogView(int maxChars,Color background,Color text,float size){
        this.maxChars=maxChars;
        setEditorKit(new WrappingKit());
        setEditable(false);setBackground(background);setForeground(text);setCaretColor(text);
        Font font=PatchbayStyle.mono(size);
        setFont(font);setSelectionColor(PatchbayStyle.ACCENT_SEL);setSelectedTextColor(text);
        // Style first, then make the document: a paragraph view reads its indents once, when
        // it is created, so the first paragraph must already see them.
        StyleContext styles=new StyleContext();
        Style base=styles.getStyle(StyleContext.DEFAULT_STYLE);
        StyleConstants.setFontFamily(base,font.getFamily());StyleConstants.setFontSize(base,font.getSize());
        StyleConstants.setForeground(base,text);
        float indent=getFontMetrics(font).stringWidth("00:00:00 ");
        StyleConstants.setLeftIndent(base,indent);StyleConstants.setFirstLineIndent(base,-indent);
        setStyledDocument(new DefaultStyledDocument(styles));
    }
    @Override public boolean getScrollableTracksViewportWidth(){return true;}

    /** Text runs ask for no minimum width, so a word longer than the line (a path, a mod
     *  list) breaks between characters instead of running past the edge. */
    private static final class WrappingKit extends StyledEditorKit {
        private final ViewFactory views=element->{
            String kind=element.getName();
            if(AbstractDocument.ContentElementName.equals(kind))return new LabelView(element){
                @Override public float getMinimumSpan(int axis){return axis==View.X_AXIS?0:super.getMinimumSpan(axis);}
            };
            if(AbstractDocument.ParagraphElementName.equals(kind))return new ParagraphView(element);
            if(AbstractDocument.SectionElementName.equals(kind))return new BoxView(element,View.Y_AXIS);
            if(StyleConstants.ComponentElementName.equals(kind))return new ComponentView(element);
            if(StyleConstants.IconElementName.equals(kind))return new IconView(element);
            return new LabelView(element);
        };
        @Override public ViewFactory getViewFactory(){return views;}
    }

    void clear(){setText("");tail="";tailShown=0;}

    /** Adds text as read from the file. */
    void append(String raw){
        String text=tail+raw;
        int end=text.lastIndexOf('\n')+1;
        String done=localTimes(text.substring(0,end));
        tail=text.substring(end);
        String open=localTimes(tail);
        Document d=getDocument();
        try{
            d.remove(d.getLength()-tailShown,tailShown);
            d.insertString(d.getLength(),done+open,null);
            tailShown=open.length();
            if(d.getLength()>maxChars){
                int cut=d.getLength()-maxChars;
                String rest=d.getText(cut,d.getLength()-cut);
                int line=rest.indexOf('\n');
                d.remove(0,line<0||cut+line+1>d.getLength()-tailShown?cut:cut+line+1);
            }
        }catch(BadLocationException ex){throw new IllegalStateException(ex);}
    }

    private static String localTimes(String text){
        Matcher m=STAMP.matcher(text);
        if(!m.find())return text;
        StringBuilder out=new StringBuilder(text.length());
        do{
            String time;
            try{time=TIME.format(Instant.parse(m.group()).atZone(ZoneId.systemDefault()));}
            catch(DateTimeException ex){time=m.group();}
            m.appendReplacement(out,Matcher.quoteReplacement(time));
        }while(m.find());
        m.appendTail(out);
        return out.toString();
    }
}
