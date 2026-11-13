package dev.bitwigre.mods.btethemeloader;

import bitwig.obf.eeY;
import bitwig.obf.iok;

/**
 * Colours Bitwig computes instead of registering by name, under the keys of Berikai's Bitwig Theme
 * Editor 2.4.0-dev1: the EQ's eight bands (and its curve line without gradients), the Note FX
 * chain's positions, and named colours resolved for a relative style. A key the theme doesn't
 * name keeps Bitwig's colour. A route the theme names no key for costs one field read per call.
 */
final class HardcodedColors {
    private static final String[] EQ_BANDS = {
        "EQ Band 1", "EQ Band 2", "EQ Band 3", "EQ Band 4", "EQ Band 5", "EQ Band 6", "EQ Band 7", "EQ Band 8"};
    private static final int NOTE_FX_CHAINS = 32;
    private static final String[] NOTE_FX_KEYS = new String[NOTE_FX_CHAINS];
    static {
        for (int i = 0; i < NOTE_FX_CHAINS; i++) NOTE_FX_KEYS[i] = "Note FX Chain " + (i + 1);
    }

    /** Whether the theme names an EQ colour. */
    static final boolean EQ = ThemeRuntime.namesAny(EQ_BANDS) || ThemeRuntime.namesAny("EQ Curve Line");
    /** Whether the theme names a Note FX chain colour. */
    static final boolean NOTE_FX = ThemeRuntime.namesAny(NOTE_FX_KEYS);

    private static boolean loggedEq, loggedNoteFx, loggedRelative;

    private HardcodedColors() {
    }

    /** The EQ's colour for a pitch, by BTE 2.4.0-dev1's band thresholds in Bitwig's pitch units. */
    static iok eqColor(iok color, double pitch) {
        if (color == null) return null;
        final String key;
        if (ThemeRuntime.gradientDisabled()) key = "EQ Curve Line";
        else if (pitch <= 24d) key = EQ_BANDS[0];
        else if (pitch <= 39d) key = EQ_BANDS[1];
        else if (pitch <= 57d) key = EQ_BANDS[2];
        else if (pitch <= 78d) key = EQ_BANDS[3];
        else if (pitch <= 101d) key = EQ_BANDS[4];
        else if (pitch <= 123d) key = EQ_BANDS[5];
        else if (pitch <= 140d) key = EQ_BANDS[6];
        else key = EQ_BANDS[7];
        iok themed = ThemeRuntime.themedColor(key);
        if (themed == null) return color;
        if (!loggedEq) {
            loggedEq = true;
            ThemeRuntime.MOD.log("Applied first EQ colour: " + key);
        }
        return themed;
    }

    /** The Note FX chain's colour for a position (from 0). */
    static iok noteFxColor(iok color, int index) {
        if (color == null || index >= NOTE_FX_CHAINS) return color;
        iok themed = ThemeRuntime.themedColor(NOTE_FX_KEYS[index]);
        if (themed == null) return color;
        if (!loggedNoteFx) {
            loggedNoteFx = true;
            ThemeRuntime.MOD.log("Applied first Note FX chain colour: " + NOTE_FX_KEYS[index]);
        }
        return themed;
    }

    /** A named colour resolved for a relative style: the theme's value for the colour's name. */
    static iok relativeStyle(iok color, eeY named) {
        if (named == null || color == null || !ThemeRuntime.hasColors()) return color;
        String name;
        try {
            name = named.toString(); // the colour's name
        } catch (Throwable t) {
            return color;
        }
        if (name == null || name.isBlank()) return color;
        if (name.equals("Same as background") || name.equals("Transparent")
                || name.equals("Image Source") || name.equals("Inherited")) return color;
        iok themed = ThemeRuntime.themedColor(name);
        if (themed == null) return color;
        if (!loggedRelative) {
            loggedRelative = true;
            ThemeRuntime.MOD.log("Applied first relative style colour: " + name);
        }
        return themed;
    }
}
