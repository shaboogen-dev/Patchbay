package bml.notifications.internal;

import bitwig.obf.dWb; // a vertical or horizontal group of widgets
import bitwig.obf.dWo; // a list of widgets made from a model (the sub-task list)
import bitwig.obf.dnF; // margins (and border, background) around one widget
import bitwig.obf.dns; // a widget's bindings: binds and unbinds its properties
import bitwig.obf.dpt; // a box that stretches one widget
import bitwig.obf.dve; // a widget

import bml.notifications.Part;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A part of a row: one of Bitwig's widgets, and the group it sits in. Parts of one row share a
 * {@link Parts} table, so the same widget is always the same part. UI thread only.
 */
final class WidgetPart implements Part {
    /** The parts of one row, by widget. */
    static final class Parts {
        private final Map<dve, WidgetPart> byWidget = new IdentityHashMap<>();

        /** The part for {@code widget}, sitting in {@code group} (null: not in a group, or not known). */
        WidgetPart of(dve widget, WidgetPart group) {
            if (widget == null) return null;
            WidgetPart p = byWidget.get(widget);
            if (p == null) {
                p = new WidgetPart(this, widget);
                byWidget.put(widget, p);
            }
            if (group != null) p.group = group;
            return p;
        }
    }

    private final Parts parts;
    private final dve widget;
    private final dve content;
    private WidgetPart group;

    private WidgetPart(Parts parts, dve widget) {
        this.parts = parts;
        this.widget = widget;
        this.content = inner(widget);
    }

    /** What a wrapper shows: the widget inside margins or a stretching box; otherwise the widget itself. */
    static dve inner(dve widget) {
        if (widget instanceof dnF margins) return margins.jE_(); // the one widget inside the margins
        if (widget instanceof dpt stretch) return stretch.jE_(); // the one widget it stretches
        return widget;
    }

    /** The group of widgets this part lays out, or null when it isn't a group. */
    private dWb box() {
        return content instanceof dWb b && !(content instanceof dWo) ? b : null; // a sub-task list is filled by Bitwig, never edited
    }

    dve bitwigWidget() {
        return widget;
    }

    @Override
    public Object widget() {
        return widget;
    }

    @Override
    public Object content() {
        return content;
    }

    @Override
    public boolean isGroup() {
        return box() != null;
    }

    @Override
    public List<Part> parts() {
        dWb box = box();
        if (box == null) return List.of();
        List<Part> out = new ArrayList<>();
        for (Object child : box.jm_()) { // its widgets, in order
            if (child instanceof dve w) out.add(parts.of(w, this));
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public void add(Part part) {
        dWb box = box();
        if (box == null) throw new IllegalStateException("not a group");
        add(box.jm_().size(), part);
    }

    @Override
    public void add(int index, Part part) {
        dWb box = box();
        if (box == null) throw new IllegalStateException("not a group");
        WidgetPart p = mine(part);
        p.remove();
        int at = Math.max(0, Math.min(index, box.jm_().size()));
        box.IwV(p.widget, at); // insert the widget at that position
        p.group = this;
    }

    @Override
    public void order(Part... list) {
        dWb box = box();
        if (box == null) throw new IllegalStateException("not a group");
        List<WidgetPart> wanted = new ArrayList<>();
        for (Part part : list) {
            if (part == null) continue;
            WidgetPart p = mine(part);
            if (p != this && !wanted.contains(p)) wanted.add(p);
        }
        for (WidgetPart p : wanted) p.remove();
        for (int i = 0; i < wanted.size(); i++) {
            WidgetPart p = wanted.get(i);
            box.IwV(p.widget, Math.min(i, box.jm_().size())); // insert at its place in the order
            p.group = this;
        }
    }

    @Override
    public void remove() {
        WidgetPart g = group;
        group = null;
        if (g == null) return;
        dWb box = g.box();
        if (box != null && box.jm_().contains(widget)) box.MJ(widget); // take the widget out of the group
    }

    @Override
    public void replaceWith(Part other) {
        WidgetPart o = mine(other);
        if (o == this) return;
        WidgetPart g = group;
        if (g == null) throw new IllegalStateException("not in a group");
        int index = g.box().jm_().indexOf(widget);
        remove();
        g.add(Math.max(0, index), o);
    }

    @Override
    public void shown(boolean shown) {
        dns.cfM(widget.DQO(), false); // drop Bitwig's binding of its visibility, if any
        widget.t_(shown); // shown or hidden
    }

    private WidgetPart mine(Part part) {
        if (part instanceof WidgetPart p && p.parts == parts) return p;
        throw new IllegalArgumentException("a part of another row; use row.part(widget) for a widget of your own");
    }
}
