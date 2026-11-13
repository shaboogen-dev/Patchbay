package bml.panels;

import bitwig.obf.aBW; // a window's project view (its bottom bar)
import bitwig.obf.aCU; // a splitter's widget
import bitwig.obf.aYH; // a panel's widget
import bitwig.obf.ajC; // a display profile, as most profile factories return it
import bitwig.obf.akC; // a display profile: its windows and their layouts
import bitwig.obf.akQ; // a panel
import bitwig.obf.akZ; // a splitter between two parts of a window's layout
import bitwig.obf.dAd; // a toggle button widget
import bitwig.obf.aln; // the "Dual Monitor Arranger / Mixer" profile
import bitwig.obf.alo; // the "Dual Monitor Master / Detail" profile
import bitwig.obf.alp; // the "Dual Monitor Studio" profile
import bitwig.obf.alq; // the "Single Large Monitor" profile
import bitwig.obf.als; // the "Single Small Monitor" profile
import bitwig.obf.alt; // the "Dual Display Studio Touch" profile
import bitwig.obf.alu; // the "Tablet" profile
import bitwig.obf.alv; // the "Triple Monitor" profile
import bitwig.obf.dXx; // the part of a layout that shows and hides a panel's widget
import bitwig.obf.dnf; // a window's display: loads and caches its icons
import bitwig.obf.dve; // a widget
import bml.api.At;
import bml.api.Hook;
import bml.api.Return;
import bml.api.This;
import com.bitwig.base.geom.IBe; // the bottom bar's row style
import com.bitwig.base.geom.Jkg; // the side of a splitter a panel is on
import com.bitwig.flt.document_view_settings.gJi; // a project's view settings for one display profile
import com.bitwig.graphics.BrJ; // a loaded image (an icon)

/**
 * The framework's hooks (the helper class), the only patches of these places:
 * - the eight display-profile factories: each profile Bitwig builds runs the profile phases
 *   (layout policies, the views as a stack or as tabs, the policies' arrange; Layouts);
 * - icon loading: a view's own SVG icon (Icons);
 * - for view stacks: the mode chooser's place in the bottom bar (the stack's buttons), the part
 *   that shows and hides a panel (held stack views, Panel Scale for Bitwig views), the splitter
 *   widget (no strip for a hidden view), and the view settings read (load check, dividers);
 * - for side columns: the click of Bitwig's own button of a panel outside the main area.
 * Without registered views or policies nothing changes. Not part of the API.
 */
public final class PanelHooks {
    private PanelHooks() {
    }

    /** The profile phases (Layouts.build). */
    private static <T extends akC> T profile(T profile) {
        try {
            Layouts.build(profile);
        } catch (Throwable failure) {
            Registry.LOG.log("could not lay out a display profile; it stays as Bitwig builds it", failure);
        }
        return profile;
    }

    @Hook(target = alq.class, method = "cfM", at = At.WRAP_RETURN, id = "single-large-monitor")
    public static ajC singleLargeMonitor(@Return ajC profile) {
        return profile(profile);
    }

    @Hook(target = als.class, method = "cfM", at = At.WRAP_RETURN, id = "single-small-monitor")
    public static akC singleSmallMonitor(@Return akC profile) {
        return profile(profile);
    }

    @Hook(target = alu.class, method = "UNj", at = At.WRAP_RETURN, id = "tablet")
    public static ajC tablet(@Return ajC profile) {
        return profile(profile);
    }

    @Hook(target = alp.class, method = "cfM()LajC;", at = At.WRAP_RETURN, id = "dual-monitor-studio")
    public static ajC dualMonitorStudio(@Return ajC profile) {
        return profile(profile);
    }

    @Hook(target = aln.class, method = "cfM", at = At.WRAP_RETURN, id = "dual-monitor-arranger-mixer")
    public static ajC dualMonitorArrangerMixer(@Return ajC profile) {
        return profile(profile);
    }

    @Hook(target = alo.class, method = "cfM()LajC;", at = At.WRAP_RETURN, id = "dual-monitor-master-detail")
    public static ajC dualMonitorMasterDetail(@Return ajC profile) {
        return profile(profile);
    }

    @Hook(target = alt.class, method = "MRY", at = At.WRAP_RETURN, id = "dual-display-studio-touch")
    public static akC dualDisplayStudioTouch(@Return akC profile) {
        return profile(profile);
    }

    @Hook(target = alv.class, method = "cfM", at = At.WRAP_RETURN, id = "triple-monitor")
    public static ajC tripleMonitor(@Return ajC profile) {
        return profile(profile);
    }

    /**
     * A window loads an SVG icon by name (dnf.cfM(name, scale), in place of its call to read the
     * file from Bitwig's icons): a name Icons gave out is a view's own SVG, rendered by Bitwig's
     * renderer; every other name goes to Bitwig's own loading.
     */
    @Hook(target = dnf.class, method = "cfM(Ljava/lang/String;D)Lcom/bitwig/graphics/BrJ;", at = At.REPLACE_CALL,
          id = "panel-icons", callOwner = dnf.class, call = "FzB(Ljava/lang/String;D)Lcom/bitwig/graphics/BrJ;",
          occurrence = 1)
    public static BrJ loadSvgIcon(dnf window, String name, double scale) {
        BrJ own = null;
        try {
            own = Icons.load(window, name, scale);
        } catch (Throwable failure) {
            Registry.LOG.log("could not load the icon " + name, failure);
        }
        return own != null ? own : window.FzB(name, scale); // Bitwig's own: from resources/icons
    }

    /** The bottom bar: a profile's view stack's buttons in place of the ARRANGE / MIX / EDIT chooser. */
    @Hook(target = aBW.class, method = "LEd()Ldve;", at = At.REPLACE_CALL, id = "view-buttons",
          callOwner = akC.class, call = "cfM(LaBW;Lcom/bitwig/base/geom/IBe;)Ldve;", occurrence = 1)
    public static dve viewButtons(akC profile, aBW window, IBe style) {
        try {
            Layouts layout = Layouts.of(profile);
            if (layout != null && layout.stack != null) return layout.stack.buttons(window, style);
        } catch (Throwable failure) {
            Registry.LOG.log("could not build the view buttons; the bottom bar keeps Bitwig's mode chooser", failure);
        }
        return profile.cfM(window, style); // Bitwig's mode chooser
    }

    /**
     * A panel's widget is wrapped in the part that shows and hides it: a stack's views in a held
     * part (no animation, a fixed minimum while shown); a Bitwig panel in its scale (a mod's view is
     * scaled inside its own widget).
     */
    @Hook(target = akQ.class, method = "cfM(LaBW;Z)Ldve;", at = At.REPLACE_CALL, id = "view-wrap",
          callOwner = dXx.class, call = "cfM(Ldve;Lcom/bitwig/base/geom/Jkg;)LdXx;", occurrence = 1)
    public static dXx wrapPanel(dve content, Jkg side) {
        try {
            if (content instanceof aYH panel) {
                Object node = panel.MRY; // MRY: the panel's layout node
                Registry.Declared scale = node instanceof ViewNode ? null : Scale.viewFor(node); // a mod's view is scaled inside its own widget
                dve inner = scale != null ? Scale.wrap(content, scale) : content;
                if (Layouts.inAnyStack(node)) return ViewPart.create(inner, side);
                if (inner != content) return dXx.cfM(inner, side);
            }
        } catch (Throwable failure) {
            Registry.LOG.log("could not wrap a panel; it shows and hides as Bitwig does", failure);
        }
        return dXx.cfM(content, side); // Bitwig's own wrapper
    }

    /**
     * A splitter's widget is built: a stack's splitters and every side column's splitter (Bitwig's
     * outer one too) leave no strip for a hidden view, so an open column sits flush against its
     * neighbour and no handle can drag a hidden column open.
     */
    @Hook(target = akZ.class, method = "cfM(LaBW;Z)Ldve;", at = At.REPLACE_CALL, id = "view-splitter",
          callOwner = aCU.class, call = "cfM(LakZ;LaBW;)LaCU;", occurrence = 1)
    public static aCU splitter(akZ node, aBW window) {
        aCU widget = aCU.cfM(node, window); // Bitwig's own splitter widget
        try {
            if (node instanceof StackSplit || Layouts.isColumnSplitter(node)) widget.UNj(0); // the size of a hidden side's strip
        } catch (Throwable failure) {
            Registry.LOG.log("could not remove a view splitter's strip", failure);
        }
        return widget;
    }

    /** A project's view settings have been read: the stack shows a view, and its dividers get usable positions. */
    @Hook(target = gJi.class, method = "cfM(Lcom/bitwig/flt/document_view_settings/gJi;)V", at = At.BEFORE_RETURN,
          id = "settings-loaded")
    public static void settingsLoaded(@This gJi settings) {
        try {
            Layouts layout = Layouts.of(settings.IwV()); // the settings' profile
            if (layout != null) for (Stack stack : layout.stacks()) stack.settingsLoaded(settings);
        } catch (Throwable failure) {
            Registry.LOG.log("could not check the views of an opened project", failure);
        }
    }

    /**
     * A panel's widget, as Bitwig puts it in the layout (akQ.cfM(aBW, boolean): a mode's main
     * content, a tab's card, or a collapsible part): a Bitwig panel with a declared scale, not
     * in a collapsible part (scaled in view-wrap), in its scaled container. The only place every
     * panel passes, so its scale is the same wherever it is.
     */
    @Hook(target = akQ.class, method = "cfM(LaBW;Z)Ldve;", at = At.WRAP_RETURN, id = "panel-scale")
    public static dve scalePanel(@Return dve built) {
        try {
            if (built instanceof aYH panel && !(panel.MRY instanceof ViewNode)) { // not a collapsible part, not a mod's view
                Registry.Declared scale = Scale.viewFor(panel.MRY);
                if (scale != null) return Scale.wrap(built, scale);
            }
        } catch (Throwable failure) {
            Registry.LOG.log("could not scale a panel; it keeps Bitwig's scale", failure);
        }
        return built;
    }

    /**
     * Bitwig's button of a panel outside the main area (akQ.Hnt(row, window), for a collapsible
     * panel with its own "is shown": the left or right group of the bottom bar) is made by
     * akQ.FzB(window); Bitwig binds its lit state afterwards. A side column's panel gets the view
     * buttons' clicks on it (Stack.sideButton); every other panel keeps Bitwig's toggle.
     */
    @Hook(target = akQ.class, method = "Hnt(LdWb;LaBW;)V", at = At.REPLACE_CALL, id = "side-buttons",
          callOwner = akQ.class, call = "FzB(LaBW;)LdAd;", occurrence = 1)
    public static dAd sideButton(akQ panel, aBW window) {
        dAd button = panel.FzB(window); // Bitwig's button (its scale menu through button-of / button-menu)
        try {
            Stack stack = Layouts.stackOf(panel);
            if (stack != null && stack.side && button != null) stack.sideButton(window, stack.entry(panel), button);
        } catch (Throwable failure) {
            Registry.LOG.log("could not give a side column's button its clicks; it keeps Bitwig's toggle", failure);
        }
        return button;
    }

    /** The panel whose button akQ.FzB is building, on this thread (for button-menu). */
    private static final ThreadLocal<akQ> BUTTON_OF = new ThreadLocal<>();

    /** A Bitwig panel's button is being built: note the panel. */
    @Hook(target = akQ.class, method = "FzB(LaBW;)LdAd;", at = At.ENTRY, id = "button-of")
    public static void buttonOf(@This akQ panel) {
        BUTTON_OF.set(panel);
    }

    /** A Bitwig panel's button (its tab or group button): right-click for the panel's scale. */
    @Hook(target = akQ.class, method = "FzB(LaBW;)LdAd;", at = At.WRAP_RETURN, id = "button-menu")
    public static dAd buttonMenu(@Return dAd button) {
        akQ panel = BUTTON_OF.get();
        BUTTON_OF.remove();
        try {
            Registry.Declared scale = panel != null ? Scale.viewFor(panel) : null;
            if (scale != null && button != null) Scale.addMenu(button, scale);
        } catch (Throwable failure) {
            Registry.LOG.log("could not give a panel's button its scale menu", failure);
        }
        return button;
    }
}
