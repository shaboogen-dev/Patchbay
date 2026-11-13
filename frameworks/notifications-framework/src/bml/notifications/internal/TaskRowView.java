package bml.notifications.internal;

import bitwig.obf.AQw; // an observable true/false value
import bitwig.obf.YFZ; // a background task
import bitwig.obf.Zb1; // a background task with a progress value and bar
import bitwig.obf.dAa; // a push button
import bitwig.obf.dHK; // the busy spinner
import bitwig.obf.dIc; // a progress bar
import bitwig.obf.dWb; // a vertical or horizontal group of widgets
import bitwig.obf.dWo; // a list of widgets made from a model (the sub-task list)
import bitwig.obf.dnF; // margins (and border, background) around one widget
import bitwig.obf.dns; // a widget's bindings: binds and unbinds its properties
import bitwig.obf.dpt; // a box that stretches one widget
import bitwig.obf.duY; // a one-line text label
import bitwig.obf.dve; // a widget
import bitwig.obf.eaA; // a task's row in the notification centre
import bitwig.obf.ecp; // a wrapping text
import bitwig.obf.rMe; // an observable value
import bitwig.obf.ujq; // an observable number

import bml.notifications.Part;
import bml.notifications.Task;
import bml.notifications.TaskRow;
import bml.notifications.TextLook;

import java.util.List;
import java.util.function.Function;

/**
 * A task row as Bitwig built it ({@code eaA.MRY(YFZ)}), read into parts. {@link #read} returns null when
 * the row doesn't have the shape Bitwig 6.0 to 6.1.4 build: the row then stays Bitwig's own. UI thread.
 */
final class TaskRowView implements TaskRow {
    private final eaA row;
    private final YFZ task;
    private final WidgetPart.Parts parts = new WidgetPart.Parts();
    private WidgetPart frame, column, titleLine, title, spinner, barBox, cancel, status, subtasks, images, bar;
    private dIc barWidget;

    private TaskRowView(eaA row, YFZ task) {
        this.row = row;
        this.task = task;
    }

    /**
     * Bitwig's tree: the row holds margins with a border and background, holding a horizontal group (the
     * images, when there are any, then the column in margins); the column holds the title line (spinner,
     * title in a stretching box), the bar group, Cancel, the status line and the sub-task list.
     */
    static TaskRowView read(eaA row, YFZ task) {
        if (!(row.jE_() instanceof dnF frameWidget)) return null; // the bordered background
        if (!(frameWidget.jE_() instanceof dWb bodyBox) || bodyBox instanceof dWo) return null; // images and column
        List<?> bodyKids = bodyBox.jm_();
        if (bodyKids.isEmpty() || !(bodyKids.get(bodyKids.size() - 1) instanceof dnF columnMargins)) return null;
        if (!(columnMargins.jE_() instanceof dWb columnBox) || columnBox instanceof dWo) return null;

        TaskRowView v = new TaskRowView(row, task);
        v.frame = v.parts.of(frameWidget, null); // its group: the images and the column
        v.column = v.parts.of(columnMargins, v.frame);
        if (bodyKids.size() > 1 && bodyKids.get(0) instanceof dpt imageBox) v.images = v.parts.of(imageBox, v.frame);

        for (Object o : columnBox.jm_()) {
            if (!(o instanceof dve w)) continue;
            if (w instanceof dWo) {
                v.subtasks = v.parts.of(w, v.column);
            } else if (w instanceof dWb box) {
                dHK spin = first(box, dHK.class);
                dIc progress = first(box, dIc.class);
                if (spin != null && v.titleLine == null) {
                    v.titleLine = v.parts.of(box, v.column);
                    v.spinner = v.parts.of(spin, v.titleLine);
                    for (Object t : box.jm_()) {
                        if (t instanceof dpt stretch && stretch.jE_() instanceof ecp) v.title = v.parts.of(stretch, v.titleLine);
                    }
                } else if (progress != null && v.barBox == null) {
                    v.barBox = v.parts.of(box, v.column);
                    v.bar = v.parts.of(progress, v.barBox);
                    v.barWidget = progress;
                }
            } else if (w instanceof dAa) {
                v.cancel = v.parts.of(w, v.column);
            } else if (w instanceof duY) {
                v.status = v.parts.of(w, v.column);
            }
        }
        if (v.titleLine == null || v.title == null || v.barBox == null) return null;
        return v;
    }

    private static <T> T first(dWb box, Class<T> type) {
        for (Object o : box.jm_()) {
            if (type.isInstance(o)) return type.cast(o);
        }
        return null;
    }

    // ---------- the task ----------

    @Override
    public Task task() {
        return task instanceof BarTask b ? b.handle() : null;
    }

    @Override
    public String titleText() {
        String t = task.hUR(); // its title
        return t == null ? "" : t;
    }

    @Override
    public String statusText() {
        Object s = ((rMe) task.TtY()).getValue(); // its status line
        return s == null ? "" : s.toString();
    }

    @Override
    public boolean hasProgress() {
        return task instanceof Zb1;
    }

    @Override
    public double progress() {
        return task instanceof Zb1 z ? ((ujq) z.MIO()).getDoubleValue() : Double.NaN; // its progress value
    }

    @Override
    public boolean cancellable() {
        return task.Hnt(); // has a Cancel button
    }

    @Override
    public boolean cancelled() {
        return task.nVd().getBoolValue(); // was cancelled
    }

    // ---------- the parts ----------

    @Override public Object widget() { return row; }
    @Override public Part frame() { return frame; }
    @Override public Part column() { return column; }
    @Override public Part titleLine() { return titleLine; }
    @Override public Part title() { return title; }
    @Override public Part spinner() { return spinner; }
    @Override public Part barBox() { return barBox; }
    @Override public Part bar() { return bar; }
    @Override public Part cancel() { return cancel; }
    @Override public Part status() { return status; }
    @Override public Part subtasks() { return subtasks; }
    @Override public Part images() { return images; }

    @Override
    public Part part(Object bitwigWidget) {
        if (!(bitwigWidget instanceof dve w)) throw new IllegalArgumentException("not one of Bitwig's widgets: " + bitwigWidget);
        return parts.of(w, null);
    }

    @Override
    public Part newText(String text, TextLook look) {
        return parts.of(Looks.text(row, text, look), null);
    }

    // ---------- helpers ----------

    @Override
    public Part wrappingStatus(TextLook look) {
        ecp text = ecp.MRY(task.TtY()); // a wrapping text bound to the task's status
        Looks.apply(row, text, look);
        dns.cfM(text.DQO(), task.TtY().Hnt()); // shown while the status isn't empty, as Bitwig binds its status line
        return parts.of(text, null);
    }

    @Override
    public void barShownWhileRunning() {
        dns.cfM(barWidget.DQO(), false); // drop Bitwig's binding of the bar's visibility (a progress task's "bar shown")
        barWidget.t_(true); // shown
    }

    @Override
    public void barWaiting(boolean waiting) {
        dns.cfM(barWidget.EUG(), false); // drop any binding of the busy mode
        barWidget.MRY(waiting); // busy mode on or off
    }

    @Override
    public void barWaitingWhileUnknown() {
        if (task instanceof Zb1 z) {
            AQw busy = unknown(z).cfM(z.tp2().j_()); // unknown, or the task hides its bar
            dns.cfM(barWidget.EUG(), busy, true); // bind the busy mode, replacing any binding
        } else {
            barWaiting(true); // no progress value at all: always busy
        }
    }

    /** Binds a progress task's bar to busy while its value is 0 (the framework's own tasks: unknown). */
    static void ownBar(TaskRowView v) {
        if (v.task instanceof Zb1 z) dns.cfM(v.barWidget.EUG(), unknown(z), true); // busy while unknown
    }

    /** True while the task's progress value is 0 or less: nothing known yet. */
    private static AQw unknown(Zb1 task) {
        Function<Object, Object> zero = value -> !(value instanceof Number n) || n.doubleValue() <= 0;
        return ((rMe) task.MIO()).Hnt(zero); // an observable that follows the progress value through the function
    }
}
