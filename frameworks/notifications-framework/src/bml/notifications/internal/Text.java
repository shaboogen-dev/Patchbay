package bml.notifications.internal;

/**
 * The one guard for every title and text handed to Bitwig. No Bitwig names, so it can be tested
 * without Bitwig.
 *
 * <p>Why: Bitwig resolves an entry's title through its localiser, which answers an empty name with
 * null, and Bitwig's task runner names its thread after the title ({@code Thread.setName(null)}
 * throws, on a thread whose exception takes Bitwig down). So a title is never empty or blank. Line
 * breaks and other control characters become spaces (Bitwig's rows are single labels), and very long
 * strings are cut, so one mod's mistake can't make a row unreadable.
 */
public final class Text {
    /** What an empty title becomes. */
    public static final String UNTITLED = "Notification";
    public static final int MAX_TITLE = 200;
    public static final int MAX_TEXT = 1000;

    private Text() { }

    /** A title Bitwig can show: never null, empty or blank; {@code fallback} (or "Notification") instead. */
    public static String title(String title, String fallback) {
        String t = clean(title, MAX_TITLE);
        if (!t.isEmpty()) return t;
        String f = clean(fallback, MAX_TITLE);
        return f.isEmpty() ? UNTITLED : f;
    }

    /** A message's text or a task's status: never null; may be empty (Bitwig then shows none). */
    public static String text(String text) {
        return clean(text, MAX_TEXT);
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(Math.min(s.length(), max + 1));
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ws = Character.isWhitespace(c) || Character.isISOControl(c) || Character.isSpaceChar(c);
            if (ws) {
                space = b.length() > 0; // collapse runs; drop leading ones
                continue;
            }
            if (space) {
                b.append(' ');
                space = false;
            }
            b.append(c);
        }
        String r = b.toString();
        if (r.length() > max) {
            int cut = max - 1;
            if (Character.isHighSurrogate(r.charAt(cut - 1))) cut--;
            r = r.substring(0, cut) + "…";
        }
        return r;
    }
}
