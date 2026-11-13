package bml.preferences.internal;

import java.util.ArrayList;
import java.util.List;

import bitwig.obf.dWb; // a row or column of widgets
import bitwig.obf.dnF; // a frame around a widget
import bitwig.obf.dve; // a widget
import bitwig.obf.ruA; // a widget's content sizes
import bitwig.obf.svm; // a widget's size policy
import com.bitwig.base.geom.IBe; // an axis

/**
 * The label column of one section's labelled rows (choices, dropdowns, number fields, sliders), so
 * their value controls start at one x.
 *
 * <p>Bitwig's row helper ({@code cIe.IwV}) puts the label in a frame whose width policy is
 * {@code aBl.dEI()}: 185 px, or the label's own width when that is wider. Bitwig's labels fit in 185
 * px, so its value controls line up; a longer label pushed its control further right than the rows
 * around it. Each frame here keeps Bitwig's policy and widens it to the section's widest label, so
 * with labels that fit, the rows are exactly Bitwig's.
 */
final class LabelColumn {
    private final List<dve> labels = new ArrayList<>();

    /**
     * Joins a row built by Bitwig's row helper: a row whose first widget is the label's frame.
     * Anything else (a checkbox, which carries its own label) is left as it is.
     */
    void join(dve row) {
        if (!(row instanceof dWb box)) return;
        List<?> kids = box.jm_(); // its widgets, in order
        if (kids.size() < 2 || !(kids.get(0) instanceof dnF frame) || !(frame.jE_() instanceof dve label)) return;
        svm bitwig = frame.jH_(); // Bitwig's width policy for the label
        if (bitwig == null) return;
        labels.add(label);
        frame.a_(new Width(bitwig)); // the frame's width policy
    }

    /** The widest label's own width, in Bitwig's layout units. */
    private long widest() {
        long w = 0;
        for (dve label : labels) {
            try {
                w = Math.max(w, label.lYc(IBe.cfM)); // its preferred width
            } catch (Throwable t) {
                // a label that can't be measured doesn't widen the column
            }
        }
        return w;
    }

    /** Bitwig's policy for one label's frame, at least as wide as the section's widest label. */
    private final class Width implements svm {
        private final svm bitwig;

        Width(svm bitwig) {
            this.bitwig = bitwig;
        }

        @Override
        public long MRY(ruA content) { // the minimum
            return Math.max(bitwig.MRY(content), widest());
        }

        @Override
        public long cfM(ruA content) { // the preferred size
            return Math.max(bitwig.cfM(content), widest());
        }

        @Override
        public long Hnt(ruA content) { // the maximum
            return Math.max(bitwig.Hnt(content), widest());
        }
    }
}
