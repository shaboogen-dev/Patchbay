package dev.bitwigre.loader;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Makes Bitwig classes, fields and methods public so mod code can use them directly.
 *
 * <pre>
 * widen-jar   &lt;bitwig.jar&gt; &lt;owner&gt; &lt;out.class&gt; &lt;spec&gt;...
 * widen-class &lt;in.class&gt; &lt;bitwig.jar&gt; &lt;out.class&gt; &lt;spec&gt;...
 *   spec: C (the class itself) | F:name:descriptor | M:name:descriptor
 * </pre>
 *
 * Only access flags change; the class keeps its length and layout. A non-public instance
 * method that became public would join virtual dispatch: it could start overriding a method of
 * the same name and descriptor in a superclass, or be overridden by one in a subclass. The tool
 * refuses that case (exit 3) instead of changing Bitwig's behaviour. {@link #run} does the same
 * work for a caller in the same JVM (the loader's preparer) and throws the refusal instead.
 */
public final class AccessWidener {
    private static final int PUBLIC = 0x0001, PRIVATE = 0x0002, PROTECTED = 0x0004, STATIC = 0x0008;

    private AccessWidener() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5 || !(args[0].equals("widen-jar") || args[0].equals("widen-class"))) {
            System.err.println("usage: widen-jar <bitwig.jar> <owner> <out.class> <spec>... | widen-class <in.class> <bitwig.jar> <out.class> <spec>...");
            System.exit(2);
        }
        try {
            run(args);
        } catch (DispatchConflict c) {
            System.err.println("WIDEN_REFUSED " + c.getMessage());
            System.exit(3);
        }
    }

    public static void run(String[] args) throws Exception {
        if (args.length < 5 || !(args[0].equals("widen-jar") || args[0].equals("widen-class")))
            throw new IllegalArgumentException("usage: widen-jar <bitwig.jar> <owner> <out.class> <spec>... | widen-class <in.class> <bitwig.jar> <out.class> <spec>...");
        boolean fromJar = args[0].equals("widen-jar");
        Path jar = Path.of(fromJar ? args[1] : args[2]);
        byte[] data;
        if (fromJar) {
            try (ZipFile z = new ZipFile(jar.toFile())) {
                ZipEntry e = z.getEntry(args[2].replace('.', '/') + ".class");
                if (e == null) throw new IOException("class not in jar: " + args[2]);
                try (InputStream in = z.getInputStream(e)) {
                    data = in.readAllBytes();
                }
            }
        } else {
            data = Files.readAllBytes(Path.of(args[1]));
        }
        List<String> specs = List.of(args).subList(4, args.length);
        byte[] out = widen(data, specs, jar);
        Files.write(Path.of(args[3]), out);
    }

    static final class DispatchConflict extends Exception {
        private static final long serialVersionUID = 1L;

        DispatchConflict(String m) {
            super(m);
        }
    }

    /** A parsed class: constant-pool strings and the offsets of every access_flags field. */
    private static final class Parsed {
        final Map<Integer, String> utf8 = new HashMap<>();
        final Map<Integer, Integer> classNameIndex = new HashMap<>();
        int classFlags;
        String name, superName;
        final List<String> interfaces = new ArrayList<>();
        final List<int[]> fields = new ArrayList<>();  // {flagsOffset, nameIndex, descIndex}
        final List<int[]> methods = new ArrayList<>();

        String str(int i) {
            return utf8.get(i);
        }

        String cls(int i) {
            return i == 0 ? null : utf8.get(classNameIndex.get(i));
        }
    }

    private static Parsed parse(byte[] d) throws IOException {
        Parsed p = new Parsed();
        Reader r = new Reader(d);
        if (r.u4() != 0xCAFEBABE) throw new IOException("not a class file");
        r.skip(4);
        int count = r.u2();
        for (int i = 1; i < count; i++) {
            int tag = r.u1();
            switch (tag) {
                case 1 -> {
                    int len = r.u2();
                    p.utf8.put(i, new DataInputStream(new ByteArrayInputStream(d, r.pos - 2, len + 2)).readUTF());
                    r.skip(len);
                }
                case 7 -> p.classNameIndex.put(i, r.u2());
                case 3, 4, 9, 10, 11, 12, 17, 18 -> r.skip(4);
                case 5, 6 -> {
                    r.skip(8);
                    i++;
                }
                case 8, 16, 19, 20 -> r.skip(2);
                case 15 -> r.skip(3);
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        p.classFlags = r.pos;
        r.skip(2);
        p.name = p.cls(r.u2());
        p.superName = p.cls(r.u2());
        int interfaces = r.u2();
        for (int i = 0; i < interfaces; i++) p.interfaces.add(p.cls(r.u2()));
        for (List<int[]> members : List.of(p.fields, p.methods)) {
            int n = r.u2();
            for (int i = 0; i < n; i++) {
                int flags = r.pos;
                r.skip(2);
                members.add(new int[]{flags, r.u2(), r.u2()});
                int attrs = r.u2();
                for (int a = 0; a < attrs; a++) {
                    r.skip(2);
                    r.skip(r.u4());
                }
            }
        }
        return p;
    }

    static byte[] widen(byte[] data, List<String> specs, Path jar) throws IOException, DispatchConflict {
        byte[] out = data.clone();
        Parsed p = parse(data);
        Hierarchy hierarchy = null;
        for (String spec : specs) {
            if (spec.equals("C")) {
                open(out, p.classFlags);
                System.out.println("WIDENED class " + p.name);
                continue;
            }
            String[] parts = spec.split(":", 3);
            if (parts.length != 3 || !(parts[0].equals("F") || parts[0].equals("M"))) throw new IOException("bad spec " + spec);
            boolean field = parts[0].equals("F");
            int[] member = null;
            for (int[] m : field ? p.fields : p.methods)
                if (p.str(m[1]).equals(parts[1]) && p.str(m[2]).equals(parts[2])) member = m;
            if (member == null) throw new IOException((field ? "field " : "method ") + p.name + "." + parts[1] + parts[2] + " not declared in this class");
            int flags = u2(out, member[0]);
            boolean virtual = !field && (flags & (STATIC | PUBLIC | PROTECTED)) == 0 && !parts[1].equals("<init>");
            if (virtual) {
                if (hierarchy == null) hierarchy = new Hierarchy(jar);
                hierarchy.check(p.name, parts[1], parts[2], (flags & PRIVATE) != 0);
            }
            open(out, member[0]);
            System.out.println("WIDENED " + (field ? "field " : "method ") + p.name + "." + parts[1] + parts[2]);
        }
        return out;
    }

    private static void open(byte[] d, int at) {
        int f = u2(d, at);
        f = (f & ~(PRIVATE | PROTECTED)) | PUBLIC;
        d[at] = (byte) (f >>> 8);
        d[at + 1] = (byte) f;
    }

    private static int u2(byte[] d, int at) {
        return ((d[at] & 0xff) << 8) | (d[at + 1] & 0xff);
    }

    /** Superclasses and declared instance methods of every class in the jar, read on demand. */
    private static final class Hierarchy {
        private final Map<String, String> superOf = new HashMap<>();
        private final Map<String, List<String>> interfacesOf = new HashMap<>();
        private final Map<String, Set<String>> methods = new HashMap<>(); // "name+desc" of non-static methods

        Hierarchy(Path jar) throws IOException {
            try (ZipFile z = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                    byte[] b;
                    try (InputStream in = z.getInputStream(e)) {
                        b = in.readAllBytes();
                    }
                    Parsed p = parse(b);
                    superOf.put(p.name, p.superName);
                    interfacesOf.put(p.name, p.interfaces);
                    Set<String> own = new HashSet<>();
                    for (int[] m : p.methods)
                        if ((u2(b, m[0]) & STATIC) == 0) own.add(p.str(m[1]) + p.str(m[2]));
                    methods.put(p.name, own);
                }
            }
        }

        private static String pkg(String n) {
            int i = n.lastIndexOf('/');
            return i < 0 ? "" : n.substring(0, i);
        }

        /**
         * A private method is invisible to dispatch, and a package-private one only takes part
         * within its own package; once public, both take part everywhere. Refuse if any class
         * above or below would now be linked to it by dispatch.
         */
        void check(String owner, String name, String desc, boolean wasPrivate) throws DispatchConflict {
            String sig = name + desc;
            List<String> clashes = new ArrayList<>();
            // Every supertype, interfaces included: an interface method of the same name would
            // also start being implemented by the newly public method.
            List<String> todo = new ArrayList<>(List.of(owner));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String c = todo.remove(todo.size() - 1);
                if (!seen.add(c)) continue;
                if (!c.equals(owner) && methods.getOrDefault(c, Set.of()).contains(sig) && (wasPrivate || !pkg(c).equals(pkg(owner))))
                    clashes.add(c);
                if (superOf.get(c) != null) todo.add(superOf.get(c));
                todo.addAll(interfacesOf.getOrDefault(c, List.of()));
            }
            for (var e : superOf.entrySet()) {
                String c = e.getKey();
                if (c.equals(owner) || !methods.getOrDefault(c, Set.of()).contains(sig)) continue;
                for (String s = e.getValue(); s != null; s = superOf.get(s))
                    if (s.equals(owner)) {
                        if (wasPrivate || !pkg(c).equals(pkg(owner))) clashes.add(c);
                        break;
                    }
            }
            if (!clashes.isEmpty())
                throw new DispatchConflict(owner + "." + sig + " can't be made public: " + clashes.stream().sorted().toList()
                        + " declare the same method, so making it public would change which method Bitwig calls."
                        + " Reach it by reflection instead.");
        }
    }

    private static final class Reader {
        final byte[] d;
        int pos;

        Reader(byte[] d) {
            this.d = d;
        }

        int u1() {
            return d[pos++] & 0xff;
        }

        int u2() {
            int v = AccessWidener.u2(d, pos);
            pos += 2;
            return v;
        }

        int u4() {
            int v = (u2() << 16) | u2();
            return v;
        }

        void skip(int n) {
            pos += n;
        }
    }
}
