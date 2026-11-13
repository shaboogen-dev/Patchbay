package bml.notifications;

/**
 * Decides where a toast goes. Declared through {@link Centre#toasts}; one is used (the last mod in order
 * of mod ID). Called on Bitwig's UI thread, just before the active window would show the toast. Whatever
 * it throws is caught and logged, the toast is shown as Bitwig shows it, and the handler isn't called
 * again in this session.
 */
public interface ToastHandler {
    /**
     * True when the handler took the toast (Bitwig then doesn't show it); false to leave it to Bitwig.
     */
    boolean toast(Toast toast);
}
