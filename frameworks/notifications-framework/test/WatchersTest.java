import bml.notifications.internal.Watchers;

import java.util.ArrayList;
import java.util.List;

/**
 * Offline checks of the watcher list (no Bitwig needed): every watcher is told, in declaration order;
 * one that throws is logged once and not told again, and the others go on.
 * Run: java -cp <helper.jar>;<this folder's classes> WatchersTest
 */
public final class WatchersTest {
    static int checks;

    public static void main(String[] args) {
        Watchers<List<String>> w = new Watchers<>();
        check(w.isEmpty(), "empty at first");
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        List<String> order = new ArrayList<>();
        w.declare("a-mod", a);
        w.declare("b-mod", b);
        w.declare("c-mod", null);
        eq(w.size(), 2, "a null watcher isn't kept");
        List<String> logged = new ArrayList<>();
        w.tell("x", (list, v) -> { list.add(v); order.add(list == a ? "a" : "b"); }, (m, t) -> logged.add(m));
        eq(a, List.of("x"), "the first is told");
        eq(b, List.of("x"), "the second is told");
        eq(order, List.of("a", "b"), "in declaration order");

        // a throwing watcher
        w.tell("boom", (list, v) -> { if (list == a) throw new IllegalStateException("bad"); list.add(v); }, (m, t) -> logged.add(m));
        eq(b, List.of("x", "boom"), "the others go on after one throws");
        eq(logged.size(), 1, "the failure is logged once");
        check(logged.get(0).contains("a-mod"), "the log names the mod: " + logged.get(0));
        w.tell("y", (list, v) -> list.add(v), (m, t) -> logged.add(m));
        eq(a, List.of("x"), "the failed watcher isn't told again");
        eq(b, List.of("x", "boom", "y"), "the other still is");
        w.tell("z", (list, v) -> { throw new Error("worse"); }, (m, t) -> { throw new RuntimeException("log fails"); });
        check(true, "an Error and a failing log are contained");

        System.out.println("WatchersTest OK (" + checks + " checks)");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    static void eq(Object a, Object b, String what) {
        check(a == null ? b == null : a.equals(b), what + ": expected [" + b + "], got [" + a + "]");
    }
}
