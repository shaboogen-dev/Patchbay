package dev.bitwigre.bml;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Asks a process's X11 windows to close, the way a window's close button does: a
 * WM_PROTOCOLS / WM_DELETE_WINDOW client message, which the application handles itself (Bitwig
 * asks to save first). Speaks the X11 protocol directly over the display socket, so BML needs
 * no wmctrl or xdotool. A window belongs to the process whose PID is in its _NET_WM_PID
 * property or, failing that, the PID the X server reports for its client (X-Resource 1.2).
 * Works for X11 sessions and for X11 applications under Wayland (Xwayland).
 *
 * <pre>java -cp bml-control.jar dev.bitwigre.bml.X11WindowClose --list
 * java -cp bml-control.jar dev.bitwigre.bml.X11WindowClose --close PID</pre>
 */
final class X11WindowClose {
    record Window(long id, long pid, int mapState, boolean deletable, String wmClass, String name) {}

    private static final int VIEWABLE = 2;
    private static final long TIMEOUT_MS = 10_000;

    private X11WindowClose() {}

    /** Sends WM_DELETE_WINDOW to the process's shown windows (or, if none is shown, all of
     *  its closable windows); returns how many were asked. */
    static int requestClose(long pid) throws IOException {
        try (Connection x = Connection.open()) {
            List<Window> own = x.clientWindows().stream().filter(w -> w.pid == pid && w.deletable).toList();
            List<Window> shown = own.stream().filter(w -> w.mapState == VIEWABLE).toList();
            List<Window> targets = shown.isEmpty() ? own : shown;
            for (Window w : targets) x.sendDelete(w.id);
            x.sync();
            return targets.size();
        }
    }

    static List<Window> listWindows() throws IOException {
        try (Connection x = Connection.open()) { return x.clientWindows(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--list")) {
            for (Window w : listWindows())
                System.out.printf("window=0x%x pid=%d map_state=%d closable=%s class=%s name=%s%n",
                        w.id, w.pid, w.mapState, w.deletable, w.wmClass, w.name);
        } else if (args.length == 2 && args[0].equals("--close")) {
            System.out.println("asked " + requestClose(Long.parseLong(args[1])) + " window(s) to close");
        } else {
            System.err.println("Usage: X11WindowClose --list | --close PID");
            System.exit(2);
        }
    }

    private static final class XError extends IOException {
        final int code;
        XError(int code, int request) { super("X11 error " + code + " for request " + request); this.code = code; }
    }

    private static final class Connection implements Closeable {
        private static final int ATOM_ATOM = 4, ATOM_CARDINAL = 6, ATOM_STRING = 31, ATOM_WM_NAME = 39, ATOM_WM_CLASS = 67;
        private static final int BAD_WINDOW = 3;
        private final SocketChannel channel;
        private final Timer watchdog = new Timer("bml-x11-timeout", true);
        private long root;
        private int sequence;
        private int wmProtocols, wmDeleteWindow, netWmPid, netWmName, utf8String, xresOpcode = -1;

        private Connection(SocketChannel channel) {
            this.channel = channel;
            // A server that stops answering must not hang BML: closing the channel ends any read.
            watchdog.schedule(new TimerTask() { public void run() { try { channel.close(); } catch (IOException ignored) {} } }, TIMEOUT_MS);
        }

        static Connection open() throws IOException {
            String display = System.getenv("DISPLAY");
            if (display == null || display.isBlank()) throw new IOException("No X11 display (DISPLAY is not set).");
            int colon = display.lastIndexOf(':');
            if (colon < 0) throw new IOException("Unsupported DISPLAY: " + display);
            String host = display.substring(0, colon), rest = display.substring(colon + 1);
            int dot = rest.indexOf('.');
            int number, screen;
            try {
                number = Integer.parseInt(dot < 0 ? rest : rest.substring(0, dot));
                screen = dot < 0 ? 0 : Integer.parseInt(rest.substring(dot + 1));
            } catch (NumberFormatException ex) { throw new IOException("Unsupported DISPLAY: " + display); }
            boolean local = host.isEmpty() || host.equals("unix");
            SocketChannel ch;
            if (local) {
                ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                ch.connect(UnixDomainSocketAddress.of("/tmp/.X11-unix/X" + number));
            } else {
                ch = SocketChannel.open(new InetSocketAddress(host, 6000 + number));
            }
            Connection x = new Connection(ch);
            try {
                x.setup(Integer.toString(number), screen, local);
                x.internAtoms();
                return x;
            } catch (IOException | RuntimeException ex) {
                x.close();
                throw ex;
            }
        }

        @Override public void close() throws IOException {
            watchdog.cancel();
            channel.close();
        }

        // ------------------------------------------------------------------ windows

        /** Windows that carry WM_PROTOCOLS: application windows. Frames a window manager adds
         *  around them have none, so the search goes through those to the windows inside. */
        List<Window> clientWindows() throws IOException {
            List<Window> out = new ArrayList<>();
            collect(root, 0, out);
            return out;
        }

        private void collect(long parent, int depth, List<Window> out) throws IOException {
            for (long w : children(parent)) {
                int[] protocols = wmProtocols == 0 ? null : property32(w, wmProtocols, ATOM_ATOM);
                if (protocols != null) {
                    out.add(describe(w, protocols));
                } else if (depth < 2) {
                    collect(w, depth + 1, out);
                }
            }
        }

        private Window describe(long w, int[] protocols) throws IOException {
            boolean deletable = wmDeleteWindow != 0 && Arrays.stream(protocols).anyMatch(a -> a == wmDeleteWindow);
            long pid = -1;
            int[] p = netWmPid == 0 ? null : property32(w, netWmPid, ATOM_CARDINAL);
            if (p != null && p.length > 0) pid = Integer.toUnsignedLong(p[0]);
            if (pid <= 0) pid = clientPid(w);
            String name = netWmName != 0 ? propertyString(w, netWmName, utf8String) : null;
            if (name == null) name = propertyString(w, ATOM_WM_NAME, ATOM_STRING);
            String wmClass = propertyString(w, ATOM_WM_CLASS, ATOM_STRING);
            return new Window(w, pid, mapState(w), deletable,
                    wmClass == null ? "" : wmClass.replace('\0', ' ').trim(), name == null ? "" : name);
        }

        void sendDelete(long window) throws IOException {
            ByteBuffer b = request(25, 0, 11);      // SendEvent, propagate=false
            b.putInt((int) window);
            b.putInt(0);                            // empty event mask: delivered to the window's owner
            b.put((byte) 33).put((byte) 32).putShort((short) 0);   // ClientMessage, format 32
            b.putInt((int) window).putInt(wmProtocols);
            b.putInt(wmDeleteWindow).putInt(0);     // data: WM_DELETE_WINDOW, CurrentTime
            b.putInt(0).putInt(0).putInt(0);
            send(b);
        }

        /** Round trip, so every request before it has been handled; reports their errors. */
        void sync() throws IOException {
            send(request(43, 0, 1));                // GetInputFocus
            reply(sequence, true);
        }

        // ------------------------------------------------------------------ requests

        private List<Long> children(long window) throws IOException {
            ByteBuffer b = request(15, 0, 2);       // QueryTree
            b.putInt((int) window);
            ByteBuffer r = call(b);
            if (r == null) return List.of();
            int n = Short.toUnsignedInt(r.getShort(16));
            List<Long> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(Integer.toUnsignedLong(r.getInt(32 + 4 * i)));
            return out;
        }

        private int mapState(long window) throws IOException {
            ByteBuffer b = request(3, 0, 2);        // GetWindowAttributes
            b.putInt((int) window);
            ByteBuffer r = call(b);
            return r == null ? -1 : Byte.toUnsignedInt(r.get(26));
        }

        /** The value of a format-32 property, or null if the window has none of that type. */
        private int[] property32(long window, int property, int type) throws IOException {
            ByteBuffer r = getProperty(window, property, type, 64);
            if (r == null || r.get(1) != 32 || r.getInt(8) != type) return null;
            int n = r.getInt(16);
            int[] out = new int[n];
            for (int i = 0; i < n; i++) out[i] = r.getInt(32 + 4 * i);
            return out;
        }

        private String propertyString(long window, int property, int type) throws IOException {
            ByteBuffer r = getProperty(window, property, type, 256);
            if (r == null || r.get(1) != 8 || r.getInt(8) != type) return null;
            byte[] bytes = new byte[r.getInt(16)];
            r.get(32, bytes);
            return new String(bytes, type == ATOM_STRING ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8);
        }

        private ByteBuffer getProperty(long window, int property, int type, int maxWords) throws IOException {
            ByteBuffer b = request(20, 0, 6);       // GetProperty, delete=false
            b.putInt((int) window).putInt(property).putInt(type).putInt(0).putInt(maxWords);
            return call(b);
        }

        /** The PID the X server knows for the client that created the window (X-Resource). */
        private long clientPid(long window) throws IOException {
            if (xresOpcode < 0) return -1;
            ByteBuffer b = request(xresOpcode, 4, 4);   // XResQueryClientIds, one spec
            b.putInt(1).putInt((int) window).putInt(2); // mask: LocalClientPID
            ByteBuffer r = call(b);
            if (r == null || r.getInt(8) < 1 || r.limit() < 48 || r.getInt(40) < 4) return -1;
            return Integer.toUnsignedLong(r.getInt(44));
        }

        private void internAtoms() throws IOException {
            wmProtocols = atom("WM_PROTOCOLS");
            wmDeleteWindow = atom("WM_DELETE_WINDOW");
            netWmPid = atom("_NET_WM_PID");
            netWmName = atom("_NET_WM_NAME");
            utf8String = atom("UTF8_STRING");
            if (netWmName != 0 && utf8String == 0) netWmName = 0;
            int xres = extensionOpcode("X-Resource");
            if (xres >= 0) {
                ByteBuffer b = request(xres, 0, 2);     // XResQueryVersion 1.2
                b.put((byte) 1).put((byte) 2).putShort((short) 0);
                ByteBuffer r = call(b);
                if (r != null) {
                    int major = Short.toUnsignedInt(r.getShort(8)), minor = Short.toUnsignedInt(r.getShort(10));
                    if (major > 1 || (major == 1 && minor >= 2)) xresOpcode = xres;
                }
            }
        }

        /** An existing atom, or 0: nothing is created on the server. */
        private int atom(String name) throws IOException {
            byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
            ByteBuffer b = request(16, 1, 2 + (n.length + 3) / 4);  // InternAtom, only-if-exists
            b.putShort((short) n.length).putShort((short) 0).put(n);
            ByteBuffer r = call(b);
            return r == null ? 0 : r.getInt(8);
        }

        private int extensionOpcode(String name) throws IOException {
            byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
            ByteBuffer b = request(98, 0, 2 + (n.length + 3) / 4);  // QueryExtension
            b.putShort((short) n.length).putShort((short) 0).put(n);
            ByteBuffer r = call(b);
            return r == null || r.get(8) == 0 ? -1 : Byte.toUnsignedInt(r.get(9));
        }

        // ------------------------------------------------------------------ wire

        private void setup(String displayNumber, int screen, boolean local) throws IOException {
            byte[][] auth = XAuthority.cookie(displayNumber, local);
            byte[] name = auth[0], data = auth[1];
            ByteBuffer b = ByteBuffer.allocate(12 + pad(name.length) + pad(data.length)).order(ByteOrder.LITTLE_ENDIAN);
            b.put((byte) 'l').put((byte) 0).putShort((short) 11).putShort((short) 0);
            b.putShort((short) name.length).putShort((short) data.length).putShort((short) 0);
            b.put(name).position(12 + pad(name.length)).put(data).position(b.capacity());
            write(b);
            ByteBuffer head = read(8);
            int status = head.get(0);
            ByteBuffer body = read(Short.toUnsignedInt(head.getShort(6)) * 4);
            if (status != 1) {
                int len = status == 0 ? Byte.toUnsignedInt(head.get(1)) : body.limit();
                byte[] reason = new byte[Math.min(len, body.limit())];
                body.get(0, reason);
                throw new IOException("The X server refused BML's connection: " + new String(reason, StandardCharsets.ISO_8859_1).trim());
            }
            int vendor = Short.toUnsignedInt(body.getShort(16));
            int screens = Byte.toUnsignedInt(body.get(20)), formats = Byte.toUnsignedInt(body.get(21));
            int at = 32 + pad(vendor) + 8 * formats;
            if (screen >= screens) screen = 0;
            for (int s = 0; s < screen; s++) {             // skip SCREENs before the one in DISPLAY
                int depths = Byte.toUnsignedInt(body.get(at + 39));
                at += 40;
                for (int d = 0; d < depths; d++) at += 8 + 24 * Short.toUnsignedInt(body.getShort(at + 2));
            }
            root = Integer.toUnsignedLong(body.getInt(at));
        }

        private ByteBuffer request(int opcode, int data, int words) {
            ByteBuffer b = ByteBuffer.allocate(words * 4).order(ByteOrder.LITTLE_ENDIAN);
            b.put((byte) opcode).put((byte) data).putShort((short) words);
            return b;
        }

        private void send(ByteBuffer b) throws IOException {
            b.position(b.capacity());
            write(b);
            sequence = (sequence + 1) & 0xffff;
        }

        /** Sends a request with a reply; null if the window it names is gone. */
        private ByteBuffer call(ByteBuffer b) throws IOException {
            send(b);
            try { return reply(sequence, false); }
            catch (XError e) { if (e.code == BAD_WINDOW) return null; throw e; }
        }

        private ByteBuffer reply(int expected, boolean reportEarlierErrors) throws IOException {
            for (;;) {
                ByteBuffer head = read(32);
                int type = Byte.toUnsignedInt(head.get(0)), seq = Short.toUnsignedInt(head.getShort(2));
                if (type == 0) {                            // error
                    int code = Byte.toUnsignedInt(head.get(1));
                    // A window that closed after it was listed is not a failure.
                    if (seq == expected || (reportEarlierErrors && code != BAD_WINDOW)) throw new XError(code, seq);
                    continue;
                }
                long extra = (type == 1 || (type & 0x7f) == 35) ? Integer.toUnsignedLong(head.getInt(4)) * 4 : 0;
                if (extra > 64L * 1024 * 1024) throw new IOException("X11 reply too large");
                ByteBuffer full = ByteBuffer.allocate(32 + (int) extra).order(ByteOrder.LITTLE_ENDIAN);
                full.put(head);
                if (extra > 0) full.put(read((int) extra));
                full.flip();
                if (type == 1 && seq == expected) return full;
                // an event or an earlier reply: not ours
            }
        }

        private void write(ByteBuffer b) throws IOException {
            b.flip();
            while (b.hasRemaining()) channel.write(b);
        }

        private ByteBuffer read(int n) throws IOException {
            ByteBuffer b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
            while (b.hasRemaining()) if (channel.read(b) < 0) throw new EOFException("The X server closed the connection.");
            return b.flip();
        }

        private static int pad(int n) { return (n + 3) & ~3; }
    }

    /** The MIT-MAGIC-COOKIE-1 for a display from $XAUTHORITY (or ~/.Xauthority); none if absent. */
    private static final class XAuthority {
        private static final int FAMILY_LOCAL = 256, FAMILY_WILD = 65535;

        static byte[][] cookie(String displayNumber, boolean local) {
            byte[][] none = {new byte[0], new byte[0]};
            String file = System.getenv("XAUTHORITY");
            Path path = file != null && !file.isBlank() ? Path.of(file) : Path.of(System.getProperty("user.home", ""), ".Xauthority");
            if (!Files.isRegularFile(path)) return none;
            String host = hostname();
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                for (;;) {
                    int family;
                    try { family = in.readUnsignedShort(); } catch (EOFException end) { return none; }
                    String address = new String(field(in), StandardCharsets.ISO_8859_1);
                    String number = new String(field(in), StandardCharsets.ISO_8859_1);
                    byte[] name = field(in), data = field(in);
                    boolean hostMatches = family == FAMILY_WILD || (local ? family == FAMILY_LOCAL && address.equals(host) : family != FAMILY_LOCAL);
                    if (hostMatches && (number.isEmpty() || number.equals(displayNumber))
                            && new String(name, StandardCharsets.ISO_8859_1).equals("MIT-MAGIC-COOKIE-1"))
                        return new byte[][]{name, data};
                }
            } catch (IOException unreadable) {
                return none;
            }
        }

        private static byte[] field(DataInputStream in) throws IOException {
            byte[] b = new byte[in.readUnsignedShort()];
            in.readFully(b);
            return b;
        }

        private static String hostname() {
            try { return Files.readString(Path.of("/proc/sys/kernel/hostname")).trim(); }
            catch (IOException ex) {
                String h = System.getenv("HOSTNAME");
                return h == null ? "" : h;
            }
        }
    }
}
