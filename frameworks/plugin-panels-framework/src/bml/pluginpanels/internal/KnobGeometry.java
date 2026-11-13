package bml.pluginpanels.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import bitwig.obf.efM; // a knob's geometry: radii, track and pointer widths, box size
import bitwig.obf.eiH; // a theme

/**
 * A knob geometry at an exact size. Bitwig's theme knob sizes (small, large, ...) come in a few fixed
 * steps; this scales the knob's own geometry (radii, track, pointer, box) to any box size in layout
 * units, the way the theme's own geometries are built. One per theme and size.
 * Adapted from adoxe (Daniil)'s "Design: Device Panels" plug-in cards (his Geometry: Bitwig's default proportions scaled to the box).
 */
final class KnobGeometry extends efM {
    /** Bitwig's default knob proportions, used when the knob has no geometry yet: box 37, radii 12 and 17, track 2, pointer 3. */
    private static final double[] DEFAULT = { 12, 17, 2, 3, 37, 37, 8, 14, 14, 5 };

    private static final Map<eiH, Map<Long, KnobGeometry>> CACHE = new WeakHashMap<>();

    private KnobGeometry(eiH theme, long size, efM base) {
        MRY("plugin-panels knob " + size, theme); // named, in the theme
        double[] b = base == null ? DEFAULT : new double[] { base.E3(), base.iHb(), base.MIO(), base.vpw(),
                base.ejd(), base.dEI(), base.dnz(), base.MJ(), base.UZr(), base.BNq() };
        double box = b[4] > 0 ? b[4] : DEFAULT[4];
        double k = size / box;
        cfM(b[0] * k, b[1] * k, Math.max(b[2], Math.round(b[2] * k)), b[3] * k, // inner radius, outer radius, track, pointer
                (int) size, (int) size, (int) b[6], (int) b[7], (int) b[8], (int) b[9]); // box; the theme's text metrics kept
        H_(); // Bitwig's initialisation
    }

    /** The geometry of {@code size} for this theme, scaled from {@code base} (the knob's own; may be null). */
    static synchronized efM of(eiH theme, long size, efM base) {
        Map<Long, KnobGeometry> sizes = CACHE.computeIfAbsent(theme, t -> new HashMap<>());
        return sizes.computeIfAbsent(size, s -> new KnobGeometry(theme, s, base));
    }
}
