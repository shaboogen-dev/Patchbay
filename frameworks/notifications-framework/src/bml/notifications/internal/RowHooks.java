package bml.notifications.internal;

import bitwig.obf.aCp; // a window: shows the transient notification (toast)
import bitwig.obf.sdu; // a controller script's surface: its popup notification
import com.bitwig.flt.app.TL; // the application: every toast passes through it
import bitwig.obf.dMP; // the notification centre's list
import bitwig.obf.eaA; // a task's row in the notification centre
import bml.api.Arg;
import bml.api.At;
import bml.api.Hook;
import bml.api.Return;

/**
 * The framework's hooks, all in one class because the loader calls one helper class. The row hooks
 * change a row only when a mod declared a layout (or the task is the framework's own); the toast hook
 * skips Bitwig's toast only when a mod's toast handler took it. Nothing they meet reaches Bitwig.
 */
public final class RowHooks {
    private RowHooks() { }

    /**
     * Every task row Bitwig builds, before it is shown: in the centre, inside another task's row and in
     * the list of hidden tasks. Returns the row to show.
     */
    @Hook(target = eaA.class, method = "cfM(LYFZ;)LeaA;", at = At.WRAP_RETURN, id = "task-row")
    public static eaA taskRow(@Return eaA row) {
        try {
            return TaskRows.finish(row);
        } catch (Throwable t) {
            Center.MOD.log("a task row failed; Bitwig's own row instead", t);
            return row;
        }
    }

    /**
     * The centre's list, as it is made: registers the message-row maker before its first row, and
     * attaches the watchers' listeners before the first entry is shown.
     */
    @Hook(target = dMP.class, method = "MRY(Lyqe;)V", at = At.ENTRY, id = "message-rows")
    public static void messageRows() {
        try {
            MessageRows.register();
        } catch (Throwable t) {
            Center.MOD.log("could not register the message-row maker; Bitwig's own message rows", t);
        }
        try {
            Capture.attach();
        } catch (Throwable t) {
            Center.MOD.log("could not attach the notification-centre watchers", t);
        }
    }

    /**
     * Every toast, on the UI thread, just before the active window shows it: the call to the window's
     * toast in the application's private toast method ({@code TL.uWE}: if a window is active, it shows
     * the text). When a mod's toast handler takes it, the window's toast is skipped; otherwise (no
     * handler, the handler declined, any failure) Bitwig's toast shows as before.
     */
    @Hook(target = TL.class, method = "uWE(Ljava/lang/String;)V", at = At.REPLACE_CALL,
          callOwner = aCp.class, call = "Hnt(Ljava/lang/String;)V", occurrence = 1, id = "toast")
    public static void toast(aCp window, String text) {
        boolean taken = false;
        try {
            taken = Toasts.taken(text);
        } catch (Throwable t) {
            Center.MOD.log("a toast couldn't reach the toast handler; Bitwig's toast instead", t);
        }
        if (!taken) window.Hnt(text); // Bitwig's toast
    }

    /**
     * A controller script's popup notification ({@code showPopupNotification}, e.g. a knob's value on
     * every turn), on the script's thread: marks its text so its toast reaches the toast handler as a
     * controller popup. Three instructions, no allocation or branch, so an entry hook is safe there.
     */
    @Hook(target = sdu.class, method = "IwV(Ljava/lang/String;)V", at = At.ENTRY, id = "controller-popup")
    public static void controllerPopup(@Arg(0) String text) {
        try {
            Toasts.controller(text);
        } catch (Throwable t) {
            Center.MOD.log("a controller popup couldn't be marked", t);
        }
    }
}
