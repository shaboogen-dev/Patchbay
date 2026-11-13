package bml.notifications.internal;

import bitwig.obf.FXB; // a notification's severity: cfM warning, MRY error, Hnt information
import bitwig.obf.cpI; // a notification-centre entry
import bitwig.obf.dAa; // a push button
import bitwig.obf.dMF; // a message's row in the notification centre
import bitwig.obf.dWb; // a vertical or horizontal group of widgets
import bitwig.obf.dWo; // a list of widgets made from a model
import bitwig.obf.dnF; // margins (and border, background) around one widget
import bitwig.obf.duY; // a one-line text label
import bitwig.obf.dve; // a widget
import bitwig.obf.ecp; // a wrapping text

import bml.notifications.Message;
import bml.notifications.MessageRow;
import bml.notifications.Part;
import bml.notifications.Severity;
import bml.notifications.TextLook;

import java.util.List;

/**
 * A message row as Bitwig built it ({@code dMF.MRY(cpI)}), read into parts. {@link #read} returns null
 * when the row doesn't have the shape Bitwig 6.0 to 6.1.4 build: the row then stays Bitwig's own.
 * UI thread.
 */
final class MessageRowView implements MessageRow {
    private final dMF row;
    private final cpI entry;
    private final WidgetPart.Parts parts = new WidgetPart.Parts();
    private WidgetPart frame, column, titleLine, title, close, body, buttons;

    private MessageRowView(dMF row, cpI entry) {
        this.row = row;
        this.entry = entry;
    }

    /**
     * Bitwig's tree: the row holds margins with a border and background, holding a horizontal group with
     * the column; the column holds the title line (title, stretch, close), the description when there is
     * one, and the buttons group in margins.
     */
    static MessageRowView read(dMF row, cpI entry) {
        if (!(row.jE_() instanceof dnF frameWidget)) return null; // the bordered background
        if (!(frameWidget.jE_() instanceof dWb outer) || outer instanceof dWo) return null;
        List<?> outerKids = outer.jm_();
        if (outerKids.isEmpty() || !(outerKids.get(0) instanceof dWb columnBox) || columnBox instanceof dWo) return null;
        List<?> kids = columnBox.jm_();
        if (kids.isEmpty() || !(kids.get(0) instanceof dWb line)) return null;

        MessageRowView v = new MessageRowView(row, entry);
        v.frame = v.parts.of(frameWidget, null); // its group: the column
        v.column = v.parts.of(columnBox, v.frame);
        v.titleLine = v.parts.of(line, v.column);
        for (Object o : line.jm_()) {
            if (o instanceof duY t && v.title == null) v.title = v.parts.of(t, v.titleLine);
            else if (o instanceof dAa c) v.close = v.parts.of(c, v.titleLine);
        }
        for (int i = 1; i < kids.size(); i++) {
            Object o = kids.get(i);
            if (o instanceof ecp text && v.body == null) v.body = v.parts.of(text, v.column);
            else if (o instanceof dnF margins && margins.jE_() instanceof dWb) v.buttons = v.parts.of(margins, v.column);
        }
        if (v.title == null || v.buttons == null) return null;
        return v;
    }

    // ---------- the message ----------

    @Override
    public Message message() {
        return entry instanceof Entry e ? e.handle() : null;
    }

    @Override
    public Severity severity() {
        FXB s = entry.MRY(); // its severity
        if (s == FXB.MRY) return Severity.ERROR;   // error
        if (s == FXB.cfM) return Severity.WARNING; // warning
        return Severity.INFORMATION;
    }

    @Override
    public String titleText() {
        String t = entry.ac_(); // its title
        return t == null ? "" : t;
    }

    @Override
    public String bodyText() {
        String t = entry.FzB(); // its description
        return t == null ? "" : t;
    }

    // ---------- the parts ----------

    @Override public Object widget() { return row; }
    @Override public Part frame() { return frame; }
    @Override public Part column() { return column; }
    @Override public Part titleLine() { return titleLine; }
    @Override public Part title() { return title; }
    @Override public Part close() { return close; }
    @Override public Part body() { return body; }
    @Override public Part buttons() { return buttons; }

    @Override
    public Part part(Object bitwigWidget) {
        if (!(bitwigWidget instanceof dve w)) throw new IllegalArgumentException("not one of Bitwig's widgets: " + bitwigWidget);
        return parts.of(w, null);
    }

    @Override
    public Part newText(String text, TextLook look) {
        return parts.of(Looks.text(row, text, look), null);
    }
}
