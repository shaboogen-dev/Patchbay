package bml.inspector.internal;

import java.util.function.BooleanSupplier;

import bitwig.obf.ruA; // the layout's size query
import bitwig.obf.svm; // a size policy: minimum, preferred and maximum length

/**
 * A widget's own size policy, with each length raised to at least a minimum while {@code off} is
 * false. {@code off} is asked at every layout, so the minimum follows the block's state live.
 */
final class MinimumHeight implements svm {
    private final svm original;
    private final long minimum;
    private final BooleanSupplier off;

    MinimumHeight(svm original, long minimum, BooleanSupplier off) {
        this.original = original;
        this.minimum = minimum;
        this.off = off;
    }

    private long raise(long length) {
        return off.getAsBoolean() ? length : Math.max(minimum, length);
    }

    @Override
    public long Hnt(ruA query) {
        return raise(original.Hnt(query));
    }

    @Override
    public long MRY(ruA query) {
        return raise(original.MRY(query));
    }

    @Override
    public long cfM(ruA query) {
        return raise(original.cfM(query));
    }

    @Override
    public String MRY() {
        return original.MRY();
    }

    @Override
    public String toString() {
        return "TrackMinimum(" + original + ")";
    }
}
