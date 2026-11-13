package dev.bitwigre.mods.btethemeloader;

import java.io.InputStream;

import bitwig.obf.YBi; // a virtual file (Bitwig's resources and the file system)
import bitwig.obf.aOV; // the EQ device's colours
import bitwig.obf.eeY; // a named colour
import bitwig.obf.efd; // how a named colour relates to its base
import bitwig.obf.iok; // a colour value (packed ARGB, immutable, often shared)
import bml.api.Arg;
import bml.api.At;
import bml.api.Hook;
import bml.api.Return;
import bml.api.This;
import com.bitwig.flt.widget.core.device.L1F; // the Note FX chain's colours

/**
 * The mod's hooks. Each forwards to {@link ThemeRuntime} (named colours, gradients, overlays) or
 * {@link HardcodedColors} (colours Bitwig computes instead of naming). Bitwig's colour values are
 * never changed: a themed colour is the theme's own value, handed to Bitwig in their place.
 */
public final class ThemeBridge {
    /** The name of the colour being registered on this thread, between the two registration hooks. */
    private static final ThreadLocal<String> REGISTERING = new ThreadLocal<>();
    /** The EQ pitch noted at entry; NaN when none. One holder per thread, so nothing is allocated per call. */
    private static final ThreadLocal<double[]> EQ_PITCH = ThreadLocal.withInitial(() -> new double[] {Double.NaN});
    /** The Note FX chain position noted at entry; -1 when none. */
    private static final ThreadLocal<int[]> NOTE_FX_INDEX = ThreadLocal.withInitial(() -> new int[] {-1});

    private ThemeBridge() {
    }

    /** A named colour is being registered: note its name for the store below. */
    @Hook(target = eeY.class, method = "cfM(Ljava/lang/String;Lefd;Liok;FFF)V", at = At.ENTRY,
          id = "note-theme-color-name-at-registration")
    public static void onColorRegistering(@Arg(0) String name) {
        if (ThemeRuntime.hasColors()) REGISTERING.set(name);
    }

    /** The named colour stores its value: the theme's value for the name is stored instead of Bitwig's. */
    @Hook(target = eeY.class, method = "cfM(Ljava/lang/String;Lefd;Liok;FFF)V", at = At.REPLACE_CALL,
          id = "apply-theme-colors-at-registration",
          callOwner = eeY.class, call = "cfM(Lefd;Liok;FFF)V", occurrence = 1)
    public static void storeColor(eeY named, efd style, iok color, float a, float b, float c) {
        iok stored = color;
        if (ThemeRuntime.hasColors()) {
            String name = REGISTERING.get();
            REGISTERING.remove();
            stored = ThemeRuntime.registeredColor(name, color);
        }
        named.cfM(style, stored, a, b, c);
    }

    /** A colour derived by adding to its hue, saturation and brightness. */
    @Hook(target = iok.class, method = "hUR(FFF)Liok;", at = At.WRAP_RETURN,
          id = "bypass-additive-color-derivation-when-gradients-disabled")
    public static iok onAdditiveDerivation(@Return iok derived, @This iok original) {
        return ThemeRuntime.onGradientResult(derived, original);
    }

    /** A colour derived by scaling its saturation. */
    @Hook(target = iok.class, method = "TMF(FFF)Liok;", at = At.WRAP_RETURN,
          id = "bypass-scaled-saturation-color-derivation-when-gradients-disabled")
    public static iok onScaledDerivation(@Return iok derived, @This iok original) {
        return ThemeRuntime.onGradientResult(derived, original);
    }

    /** A virtual file is opened for reading: an overlay with the same path replaces it. */
    @Hook(target = YBi.class, method = "cfM()Ljava/io/InputStream;", at = At.WRAP_RETURN,
          id = "overlay-theme-resource-streams")
    public static InputStream onResourceStream(@Return InputStream stream, @This YBi file) {
        return ThemeRuntime.onResourceStream(stream, file);
    }

    /** The EQ's colour for a pitch: noted here, used when the colour is returned. */
    @Hook(target = aOV.class, method = "cfM(DZ)Liok;", at = At.ENTRY, id = "theme-eq-procedural-pitch-entry")
    public static void captureEqPitch(@Arg(0) double pitch) {
        if (HardcodedColors.EQ) EQ_PITCH.get()[0] = pitch;
    }

    @Hook(target = aOV.class, method = "cfM(DZ)Liok;", at = At.WRAP_RETURN, id = "theme-eq-procedural-color-return")
    public static iok onEqColor(@Return iok color) {
        if (!HardcodedColors.EQ) return color;
        double[] noted = EQ_PITCH.get();
        double pitch = noted[0];
        noted[0] = Double.NaN;
        return Double.isNaN(pitch) ? color : HardcodedColors.eqColor(color, pitch);
    }

    /** The Note FX chain's colour for a position: noted here, used when the colour is returned. */
    @Hook(target = L1F.class, method = "cfM(I)Liok;", at = At.ENTRY, id = "theme-note-fx-index-entry")
    public static void captureNoteFxIndex(@Arg(0) int index) {
        if (HardcodedColors.NOTE_FX) NOTE_FX_INDEX.get()[0] = index;
    }

    @Hook(target = L1F.class, method = "cfM(I)Liok;", at = At.WRAP_RETURN, id = "theme-note-fx-procedural-color-return")
    public static iok onNoteFxColor(@Return iok color) {
        if (!HardcodedColors.NOTE_FX) return color;
        int[] noted = NOTE_FX_INDEX.get();
        int index = noted[0];
        noted[0] = -1;
        return index < 0 ? color : HardcodedColors.noteFxColor(color, index);
    }

    /** A named colour resolved for a relative style ({@code dve}): the theme's value for the colour's name. */
    @Hook(target = eeY.class, method = "cfM(Ldve;)Liok;", at = At.WRAP_RETURN, id = "theme-relative-style-color-return")
    public static iok onRelativeStyle(@Return iok color, @This eeY named) {
        return HardcodedColors.relativeStyle(color, named);
    }
}
