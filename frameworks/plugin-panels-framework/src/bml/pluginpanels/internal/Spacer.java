package bml.pluginpanels.internal;

import bitwig.obf.dns; // a widget, as Bitwig types it
import bitwig.obf.dve; // a widget
import com.bitwig.base.geom.tfK; // a widget size (width, height)
import com.bitwig.graphics.SYo; // Bitwig's drawing surface

/**
 * Empty space of an exact size, in the units Bitwig lays widgets out in (the same as a widget's
 * {@code Sxo()} / {@code yiX()}). Its minimum, preferred and maximum size are all that size, so
 * Bitwig's boxes give it exactly that. {@link RowAligner} sets it after measuring the row.
 * Draws nothing; a spacer with a {@code probe} runs it once it is on screen.
 */
final class Spacer extends dve {
    private long width;
    private long height;
    private Runnable onPaint;

    private Spacer(long width, long height) {
        this.width = width;
        this.height = height;
    }

    static Spacer create(long width, long height) {
        Spacer s = new Spacer(width, height);
        s.H_(); // Bitwig's initialisation
        return s;
    }

    /** A 1 x 1 spacer that calls {@code onPaint} each time it is painted (so: once it is on screen). */
    static Spacer probe(Runnable onPaint) {
        Spacer s = create(1, 1);
        s.onPaint = onPaint;
        return s;
    }

    long height() {
        return height;
    }

    /** Changes the height; Bitwig lays the spacer's window out again. */
    void height(long h) {
        long v = Math.max(0, h);
        if (v == height) return;
        height = v;
        oQi(); // lay out again
    }

    @Override
    public tfK mL_() {
        return new tfK(width, height); // minimum
    }

    @Override
    public tfK iH_() {
        return new tfK(width, height); // preferred
    }

    @Override
    public tfK nj_() {
        return new tfK(width, height); // maximum
    }

    @Override
    public void cfM(SYo g) {
        Runnable r = onPaint;
        if (r != null) r.run();
    }

    @Override
    public dve MJ() {
        return this; // Bitwig's typed self
    }

    @Override
    public dns UZr() {
        return this; // Bitwig's typed self
    }
}
