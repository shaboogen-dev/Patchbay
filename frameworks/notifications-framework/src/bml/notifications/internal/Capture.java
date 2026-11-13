package bml.notifications.internal;

import bitwig.obf.TEG; // Bitwig's per-item listener on an observable list
import bitwig.obf.cpI; // a notification-centre entry
import bitwig.obf.dpN; // Bitwig's application
import bitwig.obf.iu1; // the notification store
import bitwig.obf.pPb; // an observable list
import bitwig.obf.yqe; // the notification centre's view: what it shows

import bml.notifications.CentreWatcher;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Tells the watchers mods declared about the centre's entries, with no hook: one listener on the
 * centre's view (an entry was shown when it entered the view) and one on Bitwig's notification store
 * (an entry ends when it leaves the store, however it ends). Attached only when a mod declared a
 * watcher. Everything runs on Bitwig's UI thread, and nothing a listener meets reaches Bitwig.
 */
final class Capture {
    private static final Map<cpI, Live> LIVE = new IdentityHashMap<>(); // shown, not ended yet
    private static boolean attached;

    private Capture() { }

    /**
     * Attaches the two listeners, once, when a mod declared a watcher. Called from the centre's list as it
     * is made, so it is in place before the first entry is shown. Bitwig's per-item listener reports what
     * is already there as inserted.
     */
    static void attach() {
        if (attached) return;
        if (RowRegistry.watchers().isEmpty()) {
            attached = true; // nobody watches: no listeners at all
            return;
        }
        dpN app = dpN.TVZ(); // the application
        if (app == null) return;
        iu1 store = app.v_(); // the notification store
        yqe view = app.YNG();  // the centre's view
        if (store == null || view == null) return; // not ready: the next call tries again
        attached = true;
        view.MRY().cfM(new Shown());   // the view's list: entries as they are shown
        store.cfM().cfM(new Ended());  // the store's list: entries as they end
        Center.MOD.log("telling " + RowRegistry.watchers().size() + " watcher(s) about the notification centre");
    }

    /** The view's list: an entry entering it has been shown (the first time counts). */
    private static final class Shown extends TEG {
        @Override
        public void insertedItem(pPb list, Object item, int index) {
            try {
                if (!(item instanceof cpI entry) || LIVE.containsKey(entry)) return;
                Live live = new Live(entry, System.currentTimeMillis());
                LIVE.put(entry, live);
                RowRegistry.watchers().tell(live, CentreWatcher::shown, Center.MOD::log);
            } catch (Throwable t) {
                Center.MOD.log("the watchers missed an entry", t);
            }
        }

        @Override
        public void aboutToRemoveItem(pPb list, Object item, int index) {
            // the view also empties and refills when the project filter changes: ending is the store's
        }
    }

    /** The store's list: an entry leaving it has ended. */
    private static final class Ended extends TEG {
        @Override
        public void insertedItem(pPb list, Object item, int index) {
            // posted; it counts once it is shown
        }

        @Override
        public void aboutToRemoveItem(pPb list, Object item, int index) {
            try {
                if (!(item instanceof cpI entry)) return;
                Live live = LIVE.remove(entry);
                if (live == null) return; // never shown
                live.ended(System.currentTimeMillis(), entry instanceof Entry e && e.replaced());
                RowRegistry.watchers().tell(live, CentreWatcher::ended, Center.MOD::log);
            } catch (Throwable t) {
                Center.MOD.log("the watchers missed an entry", t);
            }
        }
    }
}
