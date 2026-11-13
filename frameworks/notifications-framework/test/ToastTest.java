import bml.notifications.internal.ToastRouter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Offline checks of how a controller script's popup is told from other toasts (no Bitwig needed): each
 * mark once, marks expire and are capped.
 * Run: java -cp <helper.jar>;<this folder's classes> ToastTest
 */
public final class ToastTest {
    static int checks;

    public static void main(String[] args) {
        AtomicLong clock = new AtomicLong(1_000_000);
        ToastRouter r = new ToastRouter(clock::get);

        check(!r.fromController("Voice Lead Chords: select the chords"), "a mod's toast isn't a controller popup");
        check(!r.fromController(""), "an empty toast isn't");
        check(!r.fromController(null), "a null toast isn't");

        // controller popups
        r.controller("Volume: -3.2 dB");
        eq(r.marks(), 1, "a controller popup is marked");
        check(r.fromController("Volume: -3.2 dB"), "its toast is a controller popup");
        eq(r.marks(), 0, "its mark is used up");
        check(!r.fromController("Volume: -3.2 dB"), "the same text later from a mod isn't");
        r.controller("Pan: C");
        r.controller("Pan: C");
        check(r.fromController("Pan: C"), "two marks for two popups: the first");
        check(r.fromController("Pan: C"), "two marks for two popups: the second");
        check(!r.fromController("Pan: C"), "no third mark");
        r.controller(null);
        eq(r.marks(), 0, "a null popup isn't marked");

        // marks expire
        r.controller("Old");
        clock.addAndGet(ToastRouter.MARK_MS + 1);
        check(!r.fromController("Old"), "a mark older than 10 s is gone (its toast never came)");
        eq(r.marks(), 0, "expired marks are dropped");

        // marks are capped
        for (int i = 0; i < ToastRouter.MAX_MARKS + 50; i++) r.controller("Knob " + i);
        eq(r.marks(), ToastRouter.MAX_MARKS, "at most 256 marks");
        check(!r.fromController("Knob 0"), "the oldest marks go first");
        check(r.fromController("Knob " + (ToastRouter.MAX_MARKS + 49)), "the newest are kept");

        System.out.println("ToastTest OK (" + checks + " checks)");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    static void eq(Object a, Object b, String what) {
        check(a == null ? b == null : a.equals(b), what + ": expected [" + b + "], got [" + a + "]");
    }
}
