import bml.notifications.internal.Text;

/**
 * Offline checks of the title and text guard (no Bitwig needed): a title is never null, empty or
 * blank, control characters become single spaces, long strings are cut without splitting a
 * surrogate pair. Run: java -cp <helper.jar>;<this folder's classes> TextTest
 */
public final class TextTest {
    static int checks;

    public static void main(String[] args) {
        eq(Text.title(null, null), Text.UNTITLED, "null title");
        eq(Text.title("", null), Text.UNTITLED, "empty title");
        eq(Text.title("   \t\n ", ""), Text.UNTITLED, "blank title");
        eq(Text.title("\u00a0\u2003", null), Text.UNTITLED, "non-breaking and em spaces only");
        eq(Text.title("", "Set Up Audio Analysis"), "Set Up Audio Analysis", "fallback");
        eq(Text.title(" ", "  \n"), Text.UNTITLED, "blank fallback");
        eq(Text.title("  Set Up\r\nAudio\tAnalysis  ", null), "Set Up Audio Analysis", "collapsed");
        eq(Text.title("a\u0000b", null), "a b", "control character");
        eq(Text.text(null), "", "null text");
        eq(Text.text("  "), "", "blank text is empty");
        eq(Text.text("failed (exit 1)\n; details in C:\\logs"), "failed (exit 1) ; details in C:\\logs", "text keeps backslashes");
        String longTitle = "x".repeat(500);
        String t = Text.title(longTitle, null);
        eq(t.length(), Text.MAX_TITLE, "long title length");
        check(t.endsWith("…"), "long title ends with an ellipsis");
        eq(Text.text("y".repeat(5000)).length(), Text.MAX_TEXT, "long text length");
        String emoji = "a".repeat(Text.MAX_TITLE - 2) + "\uD83D\uDE00" + "bbbb";
        String cut = Text.title(emoji, null);
        check(!Character.isHighSurrogate(cut.charAt(cut.length() - 2)), "no split surrogate pair");
        eq(Text.title("x".repeat(Text.MAX_TITLE), null).length(), Text.MAX_TITLE, "exact length kept");
        check(!Text.title("x".repeat(Text.MAX_TITLE), null).endsWith("…"), "exact length not cut");
        for (String s : new String[] {null, "", " ", "\n", "\t\t", "\u200b"}) {
            String r = Text.title(s, null);
            check(r != null && !r.isBlank(), "never blank: [" + s + "] -> [" + r + "]");
        }
        System.out.println("TextTest OK (" + checks + " checks)");
    }

    static void eq(Object a, Object b, String what) {
        checks++;
        if (!a.equals(b)) throw new AssertionError(what + ": got [" + a + "], expected [" + b + "]");
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
