package bml.pianoroll.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * How a preview hides, in plain values, so it can be checked without Bitwig. A note the plan removes is hidden by a
 * time range over it in its lane, in which Bitwig draws none of the lane's notes. Any other note of that lane the range
 * reaches is one the outcome keeps, so it is hidden too and drawn again as a plain preview note in its own place.
 */
public final class PreviewRule {
    /** Twice Bitwig's rounding of note times: a range reaches a note this close, to be safe either way. */
    public static final double TOLERANCE = 2.0 / 1048576.0;

    private PreviewRule() {
    }

    /** The ranges hiding these removed notes of one lane, {start, end}: sorted, overlapping or touching ones joined. */
    public static List<double[]> ranges(List<double[]> removed) {
        List<double[]> sorted = new ArrayList<>(removed);
        sorted.sort(Comparator.comparingDouble(range -> range[0]));
        List<double[]> joined = new ArrayList<>();
        for (double[] range : sorted) {
            double[] last = joined.isEmpty() ? null : joined.get(joined.size() - 1);
            if (last != null && range[0] <= last[1] + TOLERANCE) {
                last[1] = Math.max(last[1], range[1]);
            } else {
                joined.add(new double[] {range[0], range[1]});
            }
        }
        return joined;
    }

    /** Whether a note of the lane from start to end is reached by one of the ranges, counting touching as reached. */
    public static boolean reached(double start, double end, List<double[]> ranges) {
        for (double[] range : ranges) {
            if (start <= range[1] + TOLERANCE && end >= range[0] - TOLERANCE) {
                return true;
            }
        }
        return false;
    }
}
