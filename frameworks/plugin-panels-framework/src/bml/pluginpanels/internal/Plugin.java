package bml.pluginpanels.internal;

import java.util.ArrayList;
import java.util.List;

import bitwig.obf.LJt; // a text value
import bitwig.obf.jsd; // a device in a device chain
import bitwig.obf.uUk; // a plug-in parameter
import bitwig.obf.zcJ; // a plug-in device's contents (VST2, VST3, CLAP)
import com.bitwig.flt.document.core.master.device.DCf; // VST2 contents
import com.bitwig.flt.document.core.master.device.Wlm; // VST3 contents
import com.bitwig.flt.document.core.master.device.dKO; // a plug-in parameter, as stored
import com.bitwig.flt.document.core.master.device.qc1; // CLAP contents
import com.bitwig.flt.document.core.master.device.rUE; // plug-in contents, as stored (name, vendor)
import com.bitwig.ramona.core.brd; // a list of child objects

/** What the framework reads from a loaded plug-in: its identity and its parameters. */
final class Plugin {
    final zcJ contents;

    private Plugin(zcJ contents) {
        this.contents = contents;
    }

    static Plugin of(zcJ contents) {
        return contents == null ? null : new Plugin(contents);
    }

    /** The plug-in of a device, or null when the device isn't a plug-in. */
    static Plugin ofDevice(Object device) {
        if (!(device instanceof jsd d)) return null;
        Object contents = d.rGR().getValue(); // the device's current contents
        return contents instanceof zcJ plugin ? new Plugin(plugin) : null;
    }

    String name() {
        return text(contents.tp2()); // the plug-in's name
    }

    String vendor() {
        return contents instanceof rUE stored ? text(stored.Yt()) : ""; // the plug-in's vendor
    }

    String category() {
        return text(contents.Ucl()); // the plug-in's category
    }

    String format() {
        if (contents instanceof Wlm) return "VST3";
        if (contents instanceof qc1) return "CLAP";
        if (contents instanceof DCf) return "VST2";
        return contents.getClass().getSimpleName();
    }

    /** The format's own plug-in ID: the VST3 class ID, the CLAP ID or the VST2 unique ID. */
    String pluginId() {
        if (contents instanceof Wlm vst3) return text(vst3.Coi()); // the VST3 class ID
        if (contents instanceof qc1 clap) return text(clap.Bpn()); // the CLAP plug-in ID
        if (contents instanceof DCf vst2) return Integer.toString(vst2.Coi()); // the VST2 unique ID
        return "";
    }

    /** Every parameter the plug-in defines, in Bitwig's order. */
    List<uUk> parameters() {
        List<uUk> out = new ArrayList<>();
        brd list = contents.Z1C(); // all parameters
        for (Object o : list.TtY()) if (o instanceof uUk p) out.add(p);
        return out;
    }

    static String paramName(uUk p) {
        return text(p.fFs()); // the parameter's name
    }

    /** Bitwig's ID for the parameter ("PID..."), the key its controller API uses; "" when unknown. */
    static String paramId(uUk p) {
        return p instanceof dKO stored ? text(stored.ay_()) : "";
    }

    /** The plug-in's display text for the current value ("300.0 ms"). */
    static String display(uUk p) {
        LJt text = p.ODA(); // the display text, observable
        Object v = text == null ? null : text.getValue();
        return v == null ? "" : v.toString();
    }

    /** Number of steps (0 for a continuous parameter). */
    static int steps(uUk p) {
        return Math.max(0, p.Z1C()); // quantization step count
    }

    /** The plug-in's step names, or an empty array. */
    static String[] stepNames(uUk p) {
        String[] names = p.OzW(); // step names
        return names == null ? new String[0] : names;
    }

    /** The current value, normalised to 0..1. */
    static double normalized(uUk p) {
        return p.vpw().cfM(p.hUR()); // value to normalised
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    static String header(Plugin plugin) {
        return plugin.vendor().isEmpty() ? plugin.name() : plugin.name() + " (" + plugin.vendor() + ")";
    }
}
