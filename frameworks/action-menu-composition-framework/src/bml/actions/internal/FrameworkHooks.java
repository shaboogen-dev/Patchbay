package bml.actions.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import bitwig.obf.Us; // a clip launcher slot
import bitwig.obf.YBi; // a file in one of Bitwig's resource folders
import bitwig.obf.aBW; // the project window's panel, which builds selection menu models
import bitwig.obf.aCf; // the transport bar, which builds the Play menu
import bitwig.obf.aCp; // the project window
import bitwig.obf.aoq; // the right-click menu builder of a model object
import bitwig.obf.big; // a view's selection
import bitwig.obf.bks; // a selection menu builder that builds its menu itself
import bitwig.obf.blP; // the zone selection menu builder, which builds its menu itself
import bitwig.obf.bmf; // the right-click menu builder of one kind of selection
import bitwig.obf.bvs; // the clip launcher grid
import bitwig.obf.dCS; // a list widget
import bitwig.obf.dDW; // the right-click menu provider of a list
import bitwig.obf.dEe; // the browser's result list
import bitwig.obf.dKW; // a popup menu
import bitwig.obf.dnf; // Bitwig's icon loader
import bitwig.obf.dLh; // a dropdown button
import bitwig.obf.dpN; // Bitwig's application
import bitwig.obf.dsp; // a menu model
import bitwig.obf.dve; // a widget
import bitwig.obf.eoV; // an input event
import bitwig.obf.jsd; // a device
import bitwig.obf.uN3; // a model object
import bitwig.obf.v3U; // a parameter
import bml.actions.ActionContext;
import bml.api.At;
import bml.api.Arg;
import bml.api.Hook;
import bml.api.Return;
import bml.api.This;
import com.bitwig.flt.widget.core.device.THR; // the device panel widget

/** Where the framework hooks into Bitwig. Every hook contains its own failures. */
public final class FrameworkHooks {
    private FrameworkHooks() {
    }

    /**
     * Bitwig's application registers its actions through one overridable method, which each
     * application class extends; the call to it is replaced so the mods' actions are added once all
     * of Bitwig's are in (and with them all of Bitwig's categories), before registration closes.
     */
    @Hook(target = dpN.class, method = "dnz()V", at = At.REPLACE_CALL, callOwner = dpN.class, call = "vpw()V")
    public static void registerActions(dpN app) {
        app.vpw(); // Bitwig's own registrations, through every override
        try {
            Registry.load();
        } catch (Throwable t) {
            Registry.LOG.log("loading actions failed", t);
        }
    }

    /** A project window is binding its actions: bind the mods' actions too. */
    @Hook(target = aCp.class, method = "Glc", at = At.ENTRY)
    public static void projectWindowBinding(@This dve window) {
        try {
            Registry.bind(window);
        } catch (Throwable t) {
            Registry.LOG.log("binding actions failed", t);
        }
    }

    /**
     * A widget is being attached to its display. Every attach of every widget comes through here,
     * overriding classes included, and it is the last point at which a widget may take bindings.
     */
    @Hook(target = dve.class, method = "cfM(Ldnf;)V", at = At.ENTRY)
    public static void widgetAttaching(@This dve widget) {
        try {
            Registry.bindOnWidget(widget);
        } catch (Throwable t) {
            Registry.LOG.log("binding actions on a widget failed", t);
        }
    }

    /** The device panel adds its right-click entries for a clicked device. */
    @Hook(target = THR.class, method = "cfM(LdKW;DDLeoV;Ljsd;)V", at = At.ENTRY)
    public static void deviceMenu(@Arg(0) dKW popup, @Arg(4) jsd device) {
        try {
            Menus.opened(popup, ActionContext.device(device));
        } catch (Throwable t) {
            Registry.LOG.log("device menu failed", t);
        }
    }

    /**
     * A widget showing a model object fills its right-click menu; for a parameter (any knob, slider
     * or value field) that is the parameter menu.
     */
    @Hook(target = aoq.class, method = "cfM(LuN3;Ldve;LdKW;)V", at = At.ENTRY)
    public static void modelMenu(@Arg(0) uN3 model, @Arg(2) dKW popup) {
        try {
            if (model instanceof v3U) Menus.opened(popup, ActionContext.parameter(model));
        } catch (Throwable t) {
            Registry.LOG.log("parameter menu failed", t);
        }
    }

    /** The clip launcher grid fills a right-click menu; for an empty slot that is the clip slot menu. */
    @Hook(target = bvs.class, method = "cfM(LdKW;DDLeoV;)V", at = At.ENTRY)
    public static void launcherGridMenu(@This bvs grid, @Arg(0) dKW popup) {
        try {
            Object clicked = grid.ifN().QNk().getValue(); // the slot or scene under the grid's cursor
            if (clicked instanceof Us slot && slot.UNj() == null) Menus.opened(popup, ActionContext.clipSlot(slot)); // no clip in it
        } catch (Throwable t) {
            Registry.LOG.log("clip slot menu failed", t);
        }
    }

    /**
     * A list hands its right-click menu to its provider with the clicked item and the selected
     * ones; in the browser's result list that is the browser item menu.
     */
    @Hook(target = dCS.class, method = "cfM(LdKW;DDLeoV;)V", at = At.REPLACE_CALL, callOwner = dDW.class, call = "addContextMenuItems")
    public static void listMenu(dDW provider, dKW popup, Object item, List<?> selected, eoV event) {
        try {
            if (Menus.building() instanceof dEe) Menus.opened(popup, ActionContext.browserItem(item, selected));
        } catch (Throwable t) {
            Registry.LOG.log("browser item menu failed", t);
        }
        provider.addContextMenuItems(popup, item, selected, event); // the list's own entries
    }

    /** A selection right-click menu (track, clip, note, time, event, ...) is being built. */
    @Hook(target = bmf.class, method = "cfM(LdKW;Ldve;Lbig;LkbG;)V", at = At.ENTRY)
    public static void selectionMenu(@This bmf builder, @Arg(0) dKW popup, @Arg(2) big selection) {
        noteSelectionMenu(builder, popup, selection);
    }

    /** As {@link #selectionMenu}, for a builder whose override doesn't call the shared one. */
    @Hook(target = bks.class, method = "cfM(LdKW;Ldve;Lbig;LkbG;)V", at = At.ENTRY)
    public static void ownSelectionMenu(@This bmf builder, @Arg(0) dKW popup, @Arg(2) big selection) {
        noteSelectionMenu(builder, popup, selection);
    }

    /** As {@link #selectionMenu}, for the zone builder, whose override doesn't call the shared one. */
    @Hook(target = blP.class, method = "cfM(LdKW;Ldve;Lbig;LkbG;)V", at = At.ENTRY)
    public static void zoneSelectionMenu(@This bmf builder, @Arg(0) dKW popup, @Arg(2) big selection) {
        noteSelectionMenu(builder, popup, selection);
    }

    private static void noteSelectionMenu(bmf builder, dKW popup, big selection) {
        try {
            Menus.opened(popup, ActionContext.selection(Selections.kind(builder), selection));
        } catch (Throwable t) {
            Registry.LOG.log("selection menu failed", t);
        }
    }

    /** A kind of selection's toolbar dropdown model is about to be built (with the kind's extra actions). */
    @Hook(target = aBW.class, method = "cfM(Lbmf;[LdmX;)Ldsp;", at = At.ENTRY)
    public static void toolbarModelStarts(@This aBW panel, @Arg(0) bmf builder) {
        Toolbars.building(builder, panel);
    }

    /** A kind of selection's toolbar dropdown model is about to be built. */
    @Hook(target = aBW.class, method = "cfM(Lbmf;)Ldsp;", at = At.ENTRY)
    public static void plainToolbarModelStarts(@This aBW panel, @Arg(0) bmf builder) {
        Toolbars.building(builder, panel);
    }

    /*
     * The model, once built. Only the return value is taken: Bitwig's stack map frames at these
     * returns no longer hold the arguments, so the builder is noted at entry instead.
     */
    @Hook(target = aBW.class, method = "cfM(Lbmf;[LdmX;)Ldsp;", at = At.WRAP_RETURN)
    public static dsp toolbarModel(@Return dsp model) {
        noteToolbarModel(model);
        return model;
    }

    @Hook(target = aBW.class, method = "cfM(Lbmf;)Ldsp;", at = At.WRAP_RETURN)
    public static dsp plainToolbarModel(@Return dsp model) {
        noteToolbarModel(model);
        return model;
    }

    private static void noteToolbarModel(dsp model) {
        try {
            Toolbars.built(model);
        } catch (Throwable t) {
            Registry.LOG.log("toolbar menu failed", t);
        }
    }

    /** Bitwig's icon loader starts loading an SVG icon by its path (a mod's own icon is served below). */
    @Hook(target = dnf.class, method = "FzB(Ljava/lang/String;D)Lcom/bitwig/graphics/BrJ;", at = At.ENTRY)
    public static void iconLoading(@Arg(0) String path) {
        Icons.loading(path);
    }

    /** Whether the icon file exists: for a mod's own icon, its SVG file. */
    @Hook(target = dnf.class, method = "FzB(Ljava/lang/String;D)Lcom/bitwig/graphics/BrJ;", at = At.REPLACE_CALL, callOwner = YBi.class, call = "TtY")
    public static boolean iconExists(YBi file) {
        try {
            return Icons.exists(file);
        } catch (Throwable t) {
            Registry.LOG.log("icon lookup failed", t);
            return false;
        }
    }

    /** The icon file's contents: for a mod's own icon, its SVG file. */
    @Hook(target = dnf.class, method = "FzB(Ljava/lang/String;D)Lcom/bitwig/graphics/BrJ;", at = At.REPLACE_CALL, callOwner = YBi.class, call = "cfM()Ljava/io/InputStream;")
    public static InputStream iconContents(YBi file) throws IOException {
        return Icons.open(file);
    }

    /** A dropdown button starts filling its popup (Bitwig's actions in it run through the button). */
    @Hook(target = dLh.class, method = "MRY(LdKW;)V", at = At.ENTRY)
    public static void dropdownFilling(@This dLh button) {
        try {
            Toolbars.filling(button);
        } catch (Throwable t) {
            Registry.LOG.log("dropdown failed", t);
        }
    }

    /** A dropdown fills its popup from a menu model; a selection toolbar's gets the mod section. */
    @Hook(target = dsp.class, method = "addMenuItems(LdKW;)V", at = At.BEFORE_RETURN)
    public static void menuModelFilled(@This dsp model, @Arg(0) dKW popup) {
        try {
            Toolbars.filled(model, popup);
        } catch (Throwable t) {
            Registry.LOG.log("adding toolbar menu items failed", t);
        }
    }

    /**
     * The transport's Play dropdown fills its popup with Bitwig's Play options (one widget); the mod
     * section follows them. Bitwig's own actions there run through the dropdown button.
     */
    @Hook(target = aCf.class, method = "cfM(LaBF;LdKW;)V", at = At.BEFORE_RETURN, id = "play-menu")
    public static void playMenu(@Arg(1) dKW popup) {
        try {
            if (popup != null) Menus.append(popup, List.of(ActionContext.play()), Toolbars.button(), false);
        } catch (Throwable t) {
            Registry.LOG.log("Play menu failed", t);
        } finally {
            Toolbars.done();
        }
    }

    /** Bitwig's context popup builder starts on a widget: the clicked widget, noted for the popup. */
    @Hook(target = dve.class, method = "ucQ(DDLeoV;)LdKW;", at = At.ENTRY)
    public static void popupStarted(@This dve widget) {
        try {
            Menus.started(widget);
        } catch (Throwable t) {
            Registry.LOG.log("popup start failed", t);
        }
    }

    /**
     * Bitwig's context popup builder counts the finished popup's rows to decide whether to show it:
     * the point where all of Bitwig's entries are in.
     */
    @Hook(target = dve.class, method = "ucQ(DDLeoV;)LdKW;", at = At.REPLACE_CALL, callOwner = dKW.class, call = "FFu")
    public static int popupFinished(dKW popup) {
        try {
            Menus.finished(popup);
        } catch (Throwable t) {
            Registry.LOG.log("adding menu items failed", t);
        }
        return popup.FFu(); // the row count Bitwig asked for
    }
}
