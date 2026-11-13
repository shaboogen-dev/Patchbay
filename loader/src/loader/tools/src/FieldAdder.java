package dev.bitwigre.loader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
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
 * Adds declared instance fields to Bitwig classes.
 *
 * <pre>
 * check     &lt;classpath&gt; (&lt;owner&gt; &lt;name&gt; &lt;mod&gt;)...     every refusal, before any class is patched
 * add-jar   &lt;bitwig.jar&gt; &lt;owner&gt; &lt;out.class&gt; &lt;spec&gt;...
 * add-class &lt;in.class&gt; &lt;out.class&gt; &lt;spec&gt;...
 *   spec: name:descriptor
 * </pre>
 *
 * A class changes shape only when it is first loaded, and no subclass of it is loaded earlier, so
 * this is sound at load time and never at retransform. Only constants and one field_info are
 * appended: methods keep their bytes and constant-pool indexes. Fields are public, transient and
 * synthetic, and start at zero or null. A VarHandle's access mode decides the memory semantics of
 * each access, so a field is never declared volatile.
 */
public final class FieldAdder {
    private static final int FLAGS = 0x0001 | 0x0080 | 0x1000;
    private static final String REFUSED = "FIELD_REFUSED ";
    private static final int INTERFACE = 0x0200, ANNOTATION = 0x2000, ENUM = 0x4000, MODULE = 0x8000;

    private FieldAdder() {
    }

    public static void main(String[] args) throws Exception {
        run(args);
    }

    public static void run(String[] a) throws Exception {
        switch (a.length == 0 ? "" : a[0]) {
            case "check" -> check(a);
            case "add-jar" -> {
                try (ZipFile z = new ZipFile(a[1])) {
                    ZipEntry e = z.getEntry(a[2].replace('.', '/') + ".class");
                    if (e == null) throw new IOException("class not in jar: " + a[2]);
                    try (InputStream in = z.getInputStream(e)) {
                        write(in.readAllBytes(), Path.of(a[3]), List.of(a).subList(4, a.length));
                    }
                }
            }
            case "add-class" -> write(Files.readAllBytes(Path.of(a[1])), Path.of(a[2]), List.of(a).subList(3, a.length));
            default -> throw new IllegalArgumentException("usage: check | add-jar | add-class");
        }
    }

    // ---- refusals -----------------------------------------------------------------------------

    private record Spec(String owner, String name, String mod) {
        String at(String cls) {
            return cls.replace('/', '.') + "." + name;
        }
    }

    private static void check(String[] a) throws IOException {
        List<Spec> specs = new ArrayList<>();
        for (int i = 2; i + 2 < a.length; i += 3) specs.add(new Spec(a[i], a[i + 1], a[i + 2]));
        List<String> refused = new ArrayList<>();
        List<ZipFile> jars = new ArrayList<>();
        try {
            for (String p : a[1].split(File.pathSeparator)) if (Files.isRegularFile(Path.of(p))) jars.add(new ZipFile(p));
            Map<Spec, Set<String>> ancestors = new HashMap<>();
            for (Spec s : specs) {
                Set<String> seen = new HashSet<>();
                String problem = walkUp(jars, s, seen);
                if (problem != null) refused.add("Mod " + s.mod() + " can't add field " + s.at(s.owner()) + ": " + problem);
                ancestors.put(s, seen);
            }
            for (int i = 0; i < specs.size(); i++) for (int j = 0; j < specs.size(); j++) {
                Spec x = specs.get(i), y = specs.get(j);
                if (i == j || !x.name().equals(y.name())) continue;
                if (x.owner().equals(y.owner())) {
                    if (i < j) refused.add("Mods " + x.mod() + " and " + y.mod() + " both declare field " + x.at(x.owner()) + ". Untick one of them.");
                } else if (ancestors.get(x).contains(y.owner())) {
                    refused.add("Mods " + x.mod() + " and " + y.mod() + " declare fields named " + x.name() + " on " + x.owner().replace('/', '.')
                            + " and its supertype " + y.owner().replace('/', '.') + ". Untick one of them.");
                }
            }
            refused.addAll(subclassClashes(jars.isEmpty() ? null : jars.get(0), specs));
        } finally {
            for (ZipFile z : jars) z.close();
        }
        for (String r : refused) System.out.println(REFUSED + r);
        if (!refused.isEmpty()) throw new IOException("refused");
        System.out.println("FIELDS_OK " + specs.size());
    }

    /** The owner and every supertype: what the class is, and whether anything there has the name. */
    private static String walkUp(List<ZipFile> jars, Spec s, Set<String> seen) throws IOException {
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(s.owner()));
        while (!todo.isEmpty()) {
            String c = todo.removeFirst();
            if (!seen.add(c)) continue;
            if (c.equals("java/io/Serializable"))
                return "the class is Serializable, and a new field would change its serialized form";
            if (c.equals("java/lang/Record")) return "the class is a record";
            if (c.equals("java/lang/Enum")) return "the class is an enum";
            byte[] data = read(jars, c);
            if (data == null) return "its supertype " + c.replace('/', '.') + " can't be read, so the field can't be checked";
            Info info = new Info(data);
            if (c.equals(s.owner())) {
                if ((info.access & (INTERFACE | ANNOTATION | MODULE)) != 0) return "the class is an interface";
                if ((info.access & ENUM) != 0) return "the class is an enum";
            }
            if (info.fieldNames.contains(s.name()))
                return c.equals(s.owner()) ? "the class already declares a field of that name"
                        : "its supertype " + c.replace('/', '.') + " already declares a field of that name, which the new field would hide";
            if (info.superName != null) todo.addLast(info.superName);
            todo.addAll(info.interfaces);
        }
        return null;
    }

    /** A subclass of an owner that already has the name: looking the field up from there finds the other one. */
    private static List<String> subclassClashes(ZipFile jar, List<Spec> specs) throws IOException {
        if (jar == null || specs.isEmpty()) return List.of();
        Set<String> names = new HashSet<>();
        for (Spec s : specs) names.add(s.name());
        Map<String, String> superOf = new HashMap<>();
        Map<String, Set<String>> named = new HashMap<>();
        for (Enumeration<? extends ZipEntry> en = jar.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
            Info info;
            try (InputStream in = jar.getInputStream(e)) {
                info = new Info(in.readAllBytes());
            }
            superOf.put(info.name, info.superName);
            for (String n : info.fieldNames) if (names.contains(n)) named.computeIfAbsent(info.name, k -> new HashSet<>()).add(n);
        }
        List<String> out = new ArrayList<>();
        for (Spec s : specs) for (var hit : named.entrySet()) {
            if (!hit.getValue().contains(s.name()) || hit.getKey().equals(s.owner())) continue;
            for (String c = superOf.get(hit.getKey()); c != null; c = superOf.get(c))
                if (c.equals(s.owner())) {
                    out.add("Mod " + s.mod() + " can't add field " + s.at(s.owner()) + ": its subclass " + hit.getKey().replace('/', '.')
                            + " already declares a field of that name");
                    break;
                }
        }
        return out;
    }

    private static byte[] read(List<ZipFile> jars, String cls) throws IOException {
        for (ZipFile z : jars) {
            ZipEntry e = z.getEntry(cls + ".class");
            if (e != null) try (InputStream in = z.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
        try (InputStream in = ClassLoader.getSystemResourceAsStream(cls + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    // ---- the class file -----------------------------------------------------------------------

    private static void write(byte[] data, Path out, List<String> specs) throws IOException {
        Info info = new Info(data);
        if (info.count + 2 * specs.size() > 0xffff || info.fields + specs.size() > 0xffff) throw new IOException("class too large: " + info.name);
        ByteArrayOutputStream pool = new ByteArrayOutputStream(), members = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(pool), m = new DataOutputStream(members);
        int next = info.count;
        for (String spec : specs) {
            String[] f = spec.split(":", -1);
            if (f.length != 2) throw new IOException("bad field spec " + spec);
            if (info.fieldNames.contains(f[0])) throw new IOException(info.name + " already declares a field named " + f[0]);
            p.writeByte(1);
            p.writeUTF(f[0]);
            p.writeByte(1);
            p.writeUTF(f[1]);
            m.writeShort(FLAGS);
            m.writeShort(next++);
            m.writeShort(next++);
            m.writeShort(0);
            System.out.println("ADDED_FIELD " + info.name.replace('/', '.') + "." + f[0] + " " + f[1]);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(data.length + pool.size() + members.size() + 2);
        DataOutputStream o = new DataOutputStream(bytes);
        o.write(data, 0, 8);
        o.writeShort(next);
        o.write(data, 10, info.poolEnd - 10);
        pool.writeTo(o);
        o.write(data, info.poolEnd, info.fieldsAt - info.poolEnd);
        o.writeShort(info.fields + specs.size());
        o.write(data, info.fieldsAt + 2, info.fieldsEnd - info.fieldsAt - 2);
        members.writeTo(o);
        o.write(data, info.fieldsEnd, data.length - info.fieldsEnd);
        Files.write(out, bytes.toByteArray());
    }

    /** What this tool reads of a class file, with the offsets it needs to append to it. */
    private static final class Info {
        final int count, poolEnd, access, fields, fieldsAt, fieldsEnd;
        final String name, superName;
        final List<String> interfaces = new ArrayList<>();
        final Set<String> fieldNames = new HashSet<>();

        Info(byte[] d) throws IOException {
            if (u2(d, 0) != 0xCAFE || u2(d, 2) != 0xBABE) throw new IOException("not a class file");
            count = u2(d, 8);
            int[] at = new int[count];
            int p = 10;
            for (int i = 1; i < count; i++) {
                at[i] = p;
                switch (d[p] & 0xff) {
                    case 1 -> p += 3 + u2(d, p + 1);
                    case 7, 8, 16, 19, 20 -> p += 3;
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> p += 5;
                    case 15 -> p += 4;
                    case 5, 6 -> {
                        p += 9;
                        i++;
                    }
                    default -> throw new IOException("unknown constant pool tag " + (d[p] & 0xff));
                }
            }
            poolEnd = p;
            access = u2(d, p);
            name = cls(d, at, u2(d, p + 2));
            superName = cls(d, at, u2(d, p + 4));
            int ifaces = u2(d, p + 6);
            p += 8;
            for (int i = 0; i < ifaces; i++, p += 2) interfaces.add(cls(d, at, u2(d, p)));
            fieldsAt = p;
            fields = u2(d, p);
            p += 2;
            for (int i = 0; i < fields; i++) {
                fieldNames.add(utf(d, at, u2(d, p + 2)));
                int attrs = u2(d, p + 6);
                p += 8;
                for (int k = 0; k < attrs; k++) p += 6 + ((u2(d, p + 2) << 16) | u2(d, p + 4));
            }
            fieldsEnd = p;
        }

        private static String cls(byte[] d, int[] at, int i) throws IOException {
            return i == 0 ? null : utf(d, at, u2(d, at[i] + 1));
        }

        private static String utf(byte[] d, int[] at, int i) throws IOException {
            return new DataInputStream(new ByteArrayInputStream(d, at[i] + 1, u2(d, at[i] + 1) + 2)).readUTF();
        }
    }

    private static int u2(byte[] d, int at) {
        return ((d[at] & 0xff) << 8) | (d[at + 1] & 0xff);
    }
}
