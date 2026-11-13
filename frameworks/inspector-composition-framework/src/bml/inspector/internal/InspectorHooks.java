package bml.inspector.internal;

import java.util.function.Consumer;

import bitwig.obf.LJt; // a text value (a block's title)
import bitwig.obf.aJc; // the Device Inspector
import bitwig.obf.aLZ; // an Inspector panel
import bitwig.obf.dWb; // a vertical or horizontal group of widgets
import bitwig.obf.dve; // a widget
import bml.api.Arg;
import bml.api.At;
import bml.api.Hook;
import bml.api.Return;
import bml.api.This;

/** Where the framework hooks into Bitwig. Every hook contains its own failures. */
public final class InspectorHooks {
    /** An Inspector panel builds itself (every panel type, through this one method). */
    private static final String PANEL = "cfM(LkbG;ZLcom/bitwig/flt/document/core/iface/pHd;)V";
    /** Bitwig's block builder: a header with the title (and an accessory), then the body. */
    private static final String BLOCK = "cfM(LLJt;Ljava/util/function/Consumer;Ldve;)Ldve;";
    /** The Device Inspector builds the rows of its Device block. */
    private static final String DEVICE_ROWS = "IwV(Ljsd;)Ldve;";

    private static final ThreadLocal<aJc> DEVICE_PANEL = new ThreadLocal<>();

    private InspectorHooks() {
    }

    @Hook(target = aLZ.class, method = PANEL, at = At.ENTRY)
    public static void panelStarts(@This aLZ panel) {
        try {
            Panels.panelStarts(panel);
        } catch (Throwable t) {
            Panels.report("panel-start", "Inspector panel start failed", t);
        }
    }

    /**
     * The panel's body builder (overridden by each panel type) is called through this hook, so the
     * framework runs right after Bitwig built the panel's own blocks.
     */
    @Hook(target = aLZ.class, method = PANEL, at = At.REPLACE_CALL, callOwner = aLZ.class, call = "b_(LdWb;)V")
    public static void panelBody(aLZ panel, dWb parent) {
        panel.b_(parent); // Bitwig's own body, through every override
        try {
            Panels.bodyBuilt(panel, parent);
        } catch (Throwable t) {
            Panels.report("panel-body", "adding to the Inspector panel failed", t);
        }
    }

    @Hook(target = aLZ.class, method = PANEL, at = At.BEFORE_RETURN)
    public static void panelEnds() {
        Panels.panelEnds();
    }

    @Hook(target = aLZ.class, method = BLOCK, at = At.ENTRY)
    public static void blockStarts(@This aLZ panel, @Arg(0) LJt title) {
        try {
            Panels.sectionStarts(panel, title);
        } catch (Throwable t) {
            Panels.report("block-start", "Inspector block start failed", t);
        }
    }

    /** The block builder hands the body to the block's content: Bitwig's content first, then the listeners. */
    @Hook(target = aLZ.class, method = BLOCK, at = At.REPLACE_CALL, callOwner = Consumer.class, call = "accept")
    public static void blockFilled(Consumer<Object> content, Object body) {
        content.accept(body);
        try {
            Panels.sectionFilled(body);
        } catch (Throwable t) {
            Panels.report("block-filled", "Inspector block listeners failed", t);
        }
    }

    @Hook(target = aLZ.class, method = BLOCK, at = At.WRAP_RETURN)
    public static dve blockEnds(@Return dve block) {
        try {
            Panels.sectionEnds(block);
        } catch (Throwable t) {
            Panels.report("block-end", "Inspector block end failed", t);
        }
        return block;
    }

    @Hook(target = aJc.class, method = DEVICE_ROWS, at = At.ENTRY)
    public static void deviceRowsStart(@This aJc panel) {
        DEVICE_PANEL.set(panel);
    }

    @Hook(target = aJc.class, method = DEVICE_ROWS, at = At.WRAP_RETURN)
    public static dve deviceRowsBuilt(@Return dve rows) {
        aJc panel = DEVICE_PANEL.get();
        DEVICE_PANEL.remove();
        if (panel == null || rows == null) return rows;
        try {
            Panels.deviceRows(panel, rows);
        } catch (Throwable t) {
            Panels.report("device-rows", "adding device rows failed", t);
        }
        return rows;
    }
}
