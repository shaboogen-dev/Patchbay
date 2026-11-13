package bml.pluginpanels.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A layout's value mapping for a control: how the plug-in's displayed value relates to the
 * parameter's normalised value (0..1), so a typed value in display units ("50 %", "500 ms", "8k",
 * "1.5 s") can be set without asking the plug-in. From a knob's {@code "values"} object:
 * <ul>
 * <li>{@code min}, {@code max}: the displayed value at 0 and at 1;</li>
 * <li>{@code unit}: the display unit ("%", "ms", "s", "Hz", "dB", "X", ...); typed with or without it,
 * and ms / s and Hz / kHz convert into each other, "k" multiplies by 1000;</li>
 * <li>{@code curve}: {@code "linear"} (default); {@code "log"} (or {@code "exp"}): geometric, value =
 * min x (max / min)^n, for frequencies and times (min and max above 0); {@code {"pow": k}}: value =
 * min + (max - min) x n^k;</li>
 * <li>{@code points}: [[display, normalised], ...] for any other taper, interpolated piecewise
 * (replaces min, max and curve);</li>
 * <li>{@code steps}: the number of steps (the value snaps to the nearest);</li>
 * <li>{@code normalised}: true to also take a bare number from 0 to 1 as a normalised value.</li>
 * </ul>
 * A typed value outside the range is clamped to it.
 */
final class ValueMap {
    enum Curve { LINEAR, LOG, POW, POINTS }

    private static final Pattern NUMBER = Pattern.compile("^\\s*([-+]?\\d+(?:[.,]\\d+)?|[-+]?[.,]\\d+)\\s*(.*?)\\s*$");

    final double min, max, exponent;
    final String unit;
    final Curve curve;
    final double[][] points; // [display, normalised], by normalised
    final int steps;
    final boolean normalised;

    private ValueMap(double min, double max, String unit, Curve curve, double exponent, double[][] points, int steps, boolean normalised) {
        this.min = min;
        this.max = max;
        this.unit = unit;
        this.curve = curve;
        this.exponent = exponent;
        this.points = points;
        this.steps = steps;
        this.normalised = normalised;
    }

    /** The mapping of a layout's {@code "values"} object; throws IllegalArgumentException with {@code where}. */
    static ValueMap parse(Map<String, Object> o, String where) {
        String unit = o.get("unit") instanceof String s ? s.trim() : "";
        int steps = o.get("steps") instanceof Double d ? (int) Math.round(d) : 0;
        if (steps == 1 || steps < 0) throw new IllegalArgumentException(where + ".steps must be 2 or more");
        boolean normalised = Boolean.TRUE.equals(o.get("normalised"));
        if (o.containsKey("points")) {
            if (!(o.get("points") instanceof List<?> list) || list.size() < 2)
                throw new IllegalArgumentException(where + ".points must be a list of at least two [display, normalised] pairs");
            List<double[]> pts = new ArrayList<>();
            for (Object e : list) {
                if (!(e instanceof List<?> pair) || pair.size() != 2 || !(pair.get(0) instanceof Double v) || !(pair.get(1) instanceof Double n) || n < 0 || n > 1)
                    throw new IllegalArgumentException(where + ".points: each is [display, normalised 0..1]");
                pts.add(new double[] { v, n });
            }
            pts.sort((a, b) -> Double.compare(a[1], b[1]));
            double[][] p = pts.toArray(new double[0][]);
            for (int i = 1; i < p.length; i++) {
                if (p[i][1] == p[i - 1][1]) throw new IllegalArgumentException(where + ".points: two points at normalised " + p[i][1]);
                if (Math.signum(p[i][0] - p[i - 1][0]) != Math.signum(p[p.length - 1][0] - p[0][0]))
                    throw new IllegalArgumentException(where + ".points: the display values must rise (or fall) all the way");
            }
            return new ValueMap(p[0][0], p[p.length - 1][0], unit, Curve.POINTS, 1, p, steps, normalised);
        }
        if (!(o.get("min") instanceof Double min) || !(o.get("max") instanceof Double max))
            throw new IllegalArgumentException(where + " needs min and max (display values at 0 and 1), or points");
        if (min.equals(max)) throw new IllegalArgumentException(where + ": min and max are the same");
        Object c = o.get("curve");
        Curve curve = Curve.LINEAR;
        double exponent = 1;
        if (c instanceof String s) {
            switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "linear" -> curve = Curve.LINEAR;
                case "log", "exp" -> curve = Curve.LOG;
                default -> throw new IllegalArgumentException(where + ".curve must be linear, log, exp or {\"pow\": k}");
            }
        } else if (c instanceof Map<?, ?> m && m.get("pow") instanceof Double k && k > 0) {
            curve = Curve.POW;
            exponent = k;
        } else if (c != null) {
            throw new IllegalArgumentException(where + ".curve must be linear, log, exp or {\"pow\": k}");
        }
        if (curve == Curve.LOG && (min <= 0 || max <= 0)) throw new IllegalArgumentException(where + ": a log curve needs min and max above 0");
        return new ValueMap(min, max, unit, curve, exponent, null, steps, normalised);
    }

    /** The displayed value at normalised n. */
    double display(double n) {
        n = Math.max(0, Math.min(1, n));
        return switch (curve) {
            case LINEAR -> min + (max - min) * n;
            case LOG -> min * Math.pow(max / min, n);
            case POW -> min + (max - min) * Math.pow(n, exponent);
            case POINTS -> {
                for (int i = 1; i < points.length; i++) {
                    double[] a = points[i - 1], b = points[i];
                    if (n <= b[1]) yield n <= a[1] ? a[0] : a[0] + (b[0] - a[0]) * (n - a[1]) / (b[1] - a[1]);
                }
                yield points[points.length - 1][0];
            }
        };
    }

    /** The normalised value showing {@code v} (clamped to the range, snapped to steps). */
    double normalised(double v) {
        double lo = Math.min(min, max), hi = Math.max(min, max);
        v = Math.max(lo, Math.min(hi, v));
        double n = switch (curve) {
            case LINEAR -> (v - min) / (max - min);
            case LOG -> Math.log(v / min) / Math.log(max / min);
            case POW -> Math.pow((v - min) / (max - min), 1 / exponent);
            case POINTS -> {
                double r = points[points.length - 1][1];
                for (int i = 1; i < points.length; i++) {
                    double[] a = points[i - 1], b = points[i];
                    if ((v - a[0]) * (v - b[0]) <= 0) { r = a[0] == b[0] ? a[1] : a[1] + (b[1] - a[1]) * (v - a[0]) / (b[0] - a[0]); break; }
                }
                yield r;
            }
        };
        n = Math.max(0, Math.min(1, n));
        if (steps >= 2) n = Math.round(n * (steps - 1)) / (double) (steps - 1);
        return n;
    }

    /**
     * The normalised value for typed text, or null when it isn't understood: a number with an optional
     * unit ("50", "50 %", "500ms", "1.5 s", "8k", "8 kHz"), in this mapping's unit after conversion; with
     * {@code normalised}, a bare number from 0 to 1 is taken as normalised.
     */
    Double typed(String text) {
        Matcher m = NUMBER.matcher(text);
        if (!m.matches()) return null;
        double v;
        try {
            v = Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
        String rest = m.group(2).replace(" ", "").toLowerCase(Locale.ROOT);
        if (rest.isEmpty() && normalised && v >= 0 && v <= 1) return Math.max(0, Math.min(1, v));
        Double inUnit = inUnit(v, rest, unit.replace(" ", "").toLowerCase(Locale.ROOT));
        return inUnit == null ? null : normalised(inUnit);
    }

    /** {@code v} typed with {@code typedUnit}, in {@code unit}; null when the units don't convert. */
    static Double inUnit(double v, String typedUnit, String unit) {
        if (typedUnit.isEmpty() || typedUnit.equals(unit)) return v;
        if (typedUnit.equals("k")) return v * 1000;
        double[] from = scale(typedUnit), to = scale(unit);
        if (from != null && to != null && from[0] == to[0]) return v * from[1] / to[1];
        return null;
    }

    /** {kind, factor to the kind's base unit} for a known unit, or null. */
    private static double[] scale(String u) {
        return switch (u) {
            case "ms" -> new double[] { 1, 0.001 };
            case "s", "sec" -> new double[] { 1, 1 };
            case "hz" -> new double[] { 2, 1 };
            case "khz", "k" -> new double[] { 2, 1000 };
            default -> null;
        };
    }

    String describe() {
        return switch (curve) {
            case LINEAR -> "linear " + min + ".." + max + " " + unit;
            case LOG -> "log " + min + ".." + max + " " + unit;
            case POW -> "pow " + exponent + " " + min + ".." + max + " " + unit;
            case POINTS -> points.length + " points " + min + ".." + max + " " + unit;
        };
    }
}
