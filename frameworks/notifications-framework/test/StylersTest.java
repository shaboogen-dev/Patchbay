import bml.notifications.internal.Stylers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Offline checks of how the framework picks and runs row stylers (no Bitwig needed): the last declared
 * wins and the others are named as passed over; a mod declaring twice keeps one place; a styler that
 * throws is reported, turned off, and the caller falls back to Bitwig's own row.
 * Run: java -cp <helper.jar>;<this folder's classes> StylersTest
 */
public final class StylersTest {
    static int checks;

    public static void main(String[] args) {
        // none declared
        Stylers<Consumer<StringBuilder>> none = new Stylers<>("task rows");
        check(none.choose().isEmpty(), "no stylers: nothing logged");
        check(none.current() == null, "no stylers: none in use");
        check(none.run(new StringBuilder(), Consumer::accept, (m, t) -> { throw new AssertionError(m); }), "no stylers: run is a no-op that succeeds");

        // last wins, the others passed over
        Stylers<Consumer<StringBuilder>> s = new Stylers<>("task rows");
        s.declare("a-mod", b -> b.append("a"));
        s.declare("look-and-feel", b -> b.append("laf"));
        s.declare("z-mod", b -> b.append("z"));
        List<String> lines = s.choose();
        eq(lines.size(), 3, "one line used, two passed over");
        check(lines.get(0).contains("using the layout of z-mod"), "the last declared is used: " + lines.get(0));
        check(lines.get(1).contains("a-mod") && lines.get(1).contains("passed over"), "a-mod passed over: " + lines.get(1));
        check(lines.get(2).contains("look-and-feel") && lines.get(2).contains("passed over"), "look-and-feel passed over: " + lines.get(2));
        eq(s.currentMod(), "z-mod", "current mod");
        StringBuilder row = new StringBuilder();
        check(s.run(row, Consumer::accept, (m, t) -> { throw new AssertionError(m); }), "run succeeds");
        eq(row.toString(), "z", "only the chosen styler ran");

        // a second declaration from the same mod replaces the first, in its first place
        Stylers<Consumer<StringBuilder>> again = new Stylers<>("task rows");
        again.declare("b-mod", b -> b.append("b1"));
        again.declare("c-mod", b -> b.append("c"));
        again.declare("b-mod", b -> b.append("b2"));
        again.choose();
        eq(again.currentMod(), "c-mod", "replacing keeps the first place, so c-mod stays last");
        StringBuilder r2 = new StringBuilder();
        again.run(r2, Consumer::accept, (m, t) -> { });
        eq(r2.toString(), "c", "c-mod's styler ran");

        // a styler that throws: reported, turned off, caller falls back
        Stylers<Consumer<StringBuilder>> bad = new Stylers<>("task rows");
        bad.declare("good-mod", b -> b.append("good"));
        bad.declare("bad-mod", b -> { b.append("half"); throw new IllegalStateException("boom"); });
        bad.choose();
        List<String> logged = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        StringBuilder r3 = new StringBuilder();
        boolean ok = bad.run(r3, Consumer::accept, (m, t) -> { logged.add(m); errors.add(t); });
        check(!ok, "a throwing styler reports failure, so the caller uses Bitwig's row");
        eq(logged.size(), 1, "the failure is logged once");
        check(logged.get(0).contains("bad-mod") && logged.get(0).contains("failed"), "the log names the mod: " + logged.get(0));
        check(errors.get(0) instanceof IllegalStateException, "the exception is passed to the log");
        check(bad.current() == null, "the failed styler is turned off");
        check(bad.currentMod() == null, "no mod in use after the failure");
        StringBuilder r4 = new StringBuilder();
        check(bad.run(r4, Consumer::accept, (m, t) -> logged.add(m)), "later runs do nothing and succeed");
        eq(r4.toString(), "", "the failed styler isn't called again, and the passed-over one isn't either");
        eq(logged.size(), 1, "nothing more logged");

        // a log sink that throws never escapes
        Stylers<Consumer<StringBuilder>> worse = new Stylers<>("message rows");
        worse.declare("bad-mod", b -> { throw new RuntimeException("styler"); });
        worse.choose();
        boolean ok2 = worse.run(new StringBuilder(), Consumer::accept, (m, t) -> { throw new RuntimeException("log"); });
        check(!ok2, "a failing log doesn't escape either");

        // an Error from a styler is contained too
        Stylers<Consumer<StringBuilder>> error = new Stylers<>("task rows");
        error.declare("err-mod", b -> { throw new StackOverflowError(); });
        error.choose();
        check(!error.run(new StringBuilder(), Consumer::accept, (m, t) -> { }), "an Error is contained");

        System.out.println("StylersTest OK (" + checks + " checks)");
    }

    static void eq(Object a, Object b, String what) {
        check(a == null ? b == null : a.equals(b), what + ": expected [" + b + "], got [" + a + "]");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            System.out.println("FAIL: " + what);
            System.exit(1);
        }
    }
}
