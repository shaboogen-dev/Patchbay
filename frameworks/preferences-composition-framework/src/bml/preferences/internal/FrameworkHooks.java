package bml.preferences.internal;

import bitwig.obf.AQw; // a boolean value
import bitwig.obf.LJt; // a text value
import bitwig.obf.aBk; // Bitwig's Settings sidebar row factory
import bitwig.obf.cHA; // the Behaviour page
import bitwig.obf.cHO; // the Locations page
import bitwig.obf.cHS; // the Controllers page
import bitwig.obf.cHq; // the Settings window's sidebar
import bitwig.obf.cHw; // the Audio page
import bitwig.obf.cIe; // the Settings page base
import bitwig.obf.cIh; // the Recording page
import bitwig.obf.cIl; // the Shortcuts page
import bitwig.obf.cIs; // the Synchronization page
import bitwig.obf.cIv; // the User Interface page
import bitwig.obf.cJm; // the Plug-ins page
import bitwig.obf.dWb; // a row or column of widgets
import bitwig.obf.dXA; // a column made ready for display
import bitwig.obf.dve; // a widget
import bitwig.obf.vjp; // a literal text value
import bml.api.At;
import bml.api.Hook;
import bml.api.This;
import bml.preferences.Page;
import com.bitwig.base.geom.IBe; // an alignment

/** Where the framework hooks into Bitwig's Settings. Every hook contains its own failures. */
public final class FrameworkHooks {
    private FrameworkHooks() {
    }

    /*
     * Every section of Bitwig's Settings is wrapped under its heading by one of three helpers of
     * the page base: by heading key, by heading key with another alignment, or by a fixed title.
     * Each helper's inner call is replaced so the section's body gets the mods' controls first.
     */

    /** A section wrapped under its heading key ({@code device}). */
    @Hook(target = cIe.class, method = "cfM(Ljava/lang/String;Ldve;)Ldve;", at = At.REPLACE_CALL,
          callOwner = cIe.class, call = "MRY(Ljava/lang/String;Ldve;Lcom/bitwig/base/geom/IBe;)Ldve;")
    public static dve keyedSection(String key, dve body, IBe alignment) {
        return cIe.MRY(key, Composer.existing(BitwigSections.byKey(key), body), alignment);
    }

    /** A section wrapped under its heading key with another alignment (Synchronization). */
    @Hook(target = cIe.class, method = "Hnt(Ljava/lang/String;Ldve;)Ldve;", at = At.REPLACE_CALL,
          callOwner = cIe.class, call = "MRY(Ljava/lang/String;Ldve;Lcom/bitwig/base/geom/IBe;)Ldve;")
    public static dve alignedKeyedSection(String key, dve body, IBe alignment) {
        return cIe.MRY(key, Composer.existing(BitwigSections.byKey(key), body), alignment);
    }

    /** A section wrapped under a fixed title (Controllers). */
    @Hook(target = cIe.class, method = "MRY(Ljava/lang/String;Ldve;)Ldve;", at = At.REPLACE_CALL,
          callOwner = cIe.class, call = "cfM(Ljava/lang/String;Ldve;Lcom/bitwig/base/geom/IBe;)Ldve;")
    public static dve titledSection(String title, dve body, IBe alignment) {
        return cIe.cfM(title, Composer.existing(BitwigSections.byKey(title), body), alignment);
    }

    /** Plug-in Interface Scaling is wrapped under its translated title, so it is found by its builder. */
    @Hook(target = cJm.class, method = "xcy()Ldve;", at = At.REPLACE_CALL,
          callOwner = cJm.class, call = "MRY(Ljava/lang/String;Ldve;)Ldve;")
    public static dve interfaceScaling(String title, dve body) {
        return cIe.MRY(title, Composer.existing(BitwigSections.INTERFACE_SCALING, body));
    }

    /* A page has been built: the mods' new sections go to the bottom of its column. */

    @Hook(target = cIv.class, method = "LzO()V", at = At.BEFORE_RETURN)
    public static void userInterfacePage(@This cIe page) {
        Composer.appendSections(column(page), Page.USER_INTERFACE);
    }

    @Hook(target = cHA.class, method = "LzO()V", at = At.BEFORE_RETURN)
    public static void behaviourPage(@This cIe page) {
        Composer.appendSections(column(page), Page.BEHAVIOUR);
    }

    @Hook(target = cHw.class, method = "LzO()V", at = At.BEFORE_RETURN)
    public static void audioPage(@This cIe page) {
        Composer.appendSections(column(page), Page.AUDIO);
    }

    @Hook(target = cIh.class, method = "LzO()V", at = At.BEFORE_RETURN)
    public static void recordingPage(@This cIe page) {
        Composer.appendSections(column(page), Page.RECORDING);
    }

    @Hook(target = cHS.class, method = "LzO()V", at = At.BEFORE_RETURN)
    public static void controllersPage(@This cIe page) {
        Composer.appendSections(column(page), Page.CONTROLLERS);
    }

    @Hook(target = cJm.class, method = "LzO()V", at = At.BEFORE_RETURN)
    public static void pluginsPage(@This cIe page) {
        Composer.appendSections(column(page), Page.PLUGINS);
    }

    @Hook(target = cIl.class, method = "LzO()V", at = At.BEFORE_RETURN)
    public static void shortcutsPage(@This cIe page) {
        Composer.appendSections(column(page), Page.SHORTCUTS);
    }

    private static dWb column(cIe page) {
        return page.MRY; // the page's column
    }

    /** Synchronization joins its sections into a column of its own, which it shows instead of the page base's. */
    @Hook(target = cIs.class, method = "LzO()V", at = At.REPLACE_CALL, callOwner = cIs.class, call = "cfM([Ldve;)Ldve;")
    public static dve synchronizationPage(dve[] sections) {
        dve column = cIe.cfM(sections); // Bitwig's join
        if (column instanceof dWb c) Composer.appendSections(c, Page.SYNCHRONIZATION);
        return column;
    }

    /** Locations also builds a column of its own; new sections are added just before Bitwig readies it for display. */
    @Hook(target = cHO.class, method = "LzO()V", at = At.REPLACE_CALL, callOwner = dWb.class, call = "JJY()LdXA;")
    public static dXA locationsPage(dWb column) {
        Composer.appendSections(column, Page.LOCATIONS);
        return column.JJY();
    }

    /**
     * The sidebar adds Bitwig's last page (Advanced): the mods' pages follow it at once. They must be
     * there before the sidebar binds its selected page to the value it keeps across builds
     * ({@code cHq.IwV}), which it does next, later in this method: a build that finds a mod page
     * selected selects that index straight away ({@code dXS.FzB(int)}, unclamped).
     */
    @Hook(target = cHq.class, method = "MRY(Lcom/bitwig/flt/prefs/dWm;)V", at = At.REPLACE_CALL,
          callOwner = cHq.class, call = "cfM(Ljava/lang/String;Ldve;LAQw;)V", id = "sidebar")
    public static void sidebar(cHq sidebar, String key, dve page, AQw visible) {
        sidebar.cfM(key, page, visible);
        Composer.appendPages(sidebar);
    }

    /**
     * A sidebar row is created with the page's translated name, looked up by a key made from its
     * label; a mod page's row gets its literal label instead. Only rows of the pages the framework
     * is adding are changed, so a mod page's label can never rename one of Bitwig's rows.
     */
    @Hook(target = cHq.class, method = "cfM(Ljava/lang/String;Ldve;LAQw;)V", at = At.REPLACE_CALL,
          callOwner = aBk.class, call = "cfM(Ldve;Ljava/lang/String;LLJt;LAQw;Ljava/lang/Runnable;)Ldve;")
    public static dve sidebarRow(dve sidebar, String key, LJt name, AQw selected, Runnable select) {
        LJt shown = name;
        try {
            String label = Composer.addingPage();
            if (label != null) shown = new vjp(label);
        } catch (Throwable t) {
            Registry.LOG.log("sidebar label for " + key + " not set", t);
        }
        return aBk.cfM(sidebar, key, shown, selected, select);
    }
}
