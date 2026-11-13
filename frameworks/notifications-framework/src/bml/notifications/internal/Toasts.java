package bml.notifications.internal;

import bml.notifications.Toast;
import bml.notifications.ToastHandler;

/**
 * Every toast on its way to the screen: handed to the toast handler a mod declared, if there is one,
 * marked as a controller popup when it is one ({@link ToastRouter}). Without a handler every toast is
 * Bitwig's. UI thread, except {@link #controller}.
 */
final class Toasts {
    private static final ToastRouter ROUTER = new ToastRouter(System::currentTimeMillis);

    private Toasts() { }

    /** A controller script's popup notification, on the script's thread. */
    static void controller(String text) {
        if (RowRegistry.loadedWithoutToastHandler()) return; // nobody asks: no mark needed
        ROUTER.controller(text);
    }

    /**
     * The toast {@code text}, about to be shown (UI thread). True when the mod's handler took it; false
     * when Bitwig should show it (no handler, the handler declined, or it failed).
     */
    static boolean taken(String text) {
        boolean controller = ROUTER.fromController(text); // takes the mark either way
        Stylers<ToastHandler> handlers = RowRegistry.toasts();
        if (handlers.current() == null) return false;
        Toast toast = new Toast(text == null ? "" : text, controller);
        boolean[] took = {false};
        if (!handlers.run(toast, (h, t) -> took[0] = h.toast(t), Center.MOD::log)) return false;
        return took[0];
    }
}
