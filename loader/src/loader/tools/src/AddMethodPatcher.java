package dev.bitwigre.loader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * BML ADD_METHOD patcher.
 *
 * Adds one branch-free method declaration to an existing class. The initial
 * template vocabulary is intentionally small:
 *   CALL_SUPER_THEN_STATIC
 *   CALL_STATIC_THEN_SUPER
 *   CALL_STATIC_ONLY
 *
 * The patcher performs its own classfile editing and therefore preserves the
 * target classfile major version without depending on ASM. It fails closed if
 * the exact method already exists or if the inherited method cannot legally be
 * overridden.
 */
public final class AddMethodPatcher {
    private AddMethodPatcher() {}

    private static final int ACC_PUBLIC       = 0x0001;
    private static final int ACC_PRIVATE      = 0x0002;
    private static final int ACC_PROTECTED    = 0x0004;
    private static final int ACC_STATIC       = 0x0008;
    private static final int ACC_FINAL        = 0x0010;
    private static final int ACC_INTERFACE    = 0x0200;
    private static final int ACC_ABSTRACT     = 0x0400;

    private static int u1(byte[] b, int o) { return b[o] & 0xff; }
    private static int u2(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }
    private static long u4(byte[] b, int o) {
        return ((long)(b[o]&0xff)<<24)|((long)(b[o+1]&0xff)<<16)|((long)(b[o+2]&0xff)<<8)|(b[o+3]&0xffL);
    }
    private static byte[] p2(int v) { return new byte[]{(byte)(v >>> 8), (byte)v}; }
    private static byte[] p4(long v) { return new byte[]{(byte)(v >>> 24), (byte)(v >>> 16), (byte)(v >>> 8), (byte)v}; }
    private static byte[] concat(byte[]... xs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] x : xs) out.write(x);
        return out.toByteArray();
    }

    private static final class CpEntry {
        final int tag;
        String utf8;
        int a, b;
        CpEntry(int tag) { this.tag = tag; }
    }
    private record ParsedCp(CpEntry[] entries, int end, int count) {}

    private static ParsedCp parseCp(byte[] bytes) throws IOException {
        if (bytes.length < 10 || u4(bytes, 0) != 0xcafebabeL) throw new IOException("not a JVM classfile");
        int count = u2(bytes, 8);
        CpEntry[] cp = new CpEntry[count];
        int p = 10;
        for (int i = 1; i < count; i++) {
            if (p >= bytes.length) throw new EOFException("truncated constant pool");
            int tag = u1(bytes, p++);
            CpEntry e = new CpEntry(tag);
            cp[i] = e;
            switch (tag) {
                case 1 -> {
                    int n = u2(bytes, p); p += 2;
                    if (p + n > bytes.length) throw new EOFException("truncated UTF8 constant");
                    e.utf8 = new String(bytes, p, n, StandardCharsets.UTF_8);
                    p += n;
                }
                case 3, 4 -> p += 4;
                case 5, 6 -> { p += 8; i++; }
                case 7, 8, 16, 19, 20 -> { e.a = u2(bytes, p); p += 2; }
                case 9, 10, 11, 12, 17, 18 -> { e.a = u2(bytes, p); e.b = u2(bytes, p + 2); p += 4; }
                case 15 -> { e.a = u1(bytes, p); e.b = u2(bytes, p + 1); p += 3; }
                default -> throw new IOException("unsupported constant-pool tag " + tag + " at index " + i);
            }
            if (p > bytes.length) throw new EOFException("truncated constant pool");
        }
        return new ParsedCp(cp, p, count);
    }

    private static String utf(CpEntry[] cp, int idx) throws IOException {
        if (idx <= 0 || idx >= cp.length || cp[idx] == null || cp[idx].tag != 1)
            throw new IOException("expected CONSTANT_Utf8 at #" + idx);
        return cp[idx].utf8;
    }
    private static String className(CpEntry[] cp, int idx) throws IOException {
        if (idx == 0) return null;
        if (idx < 0 || idx >= cp.length || cp[idx] == null || cp[idx].tag != 7)
            throw new IOException("expected CONSTANT_Class at #" + idx);
        return utf(cp, cp[idx].a);
    }

    private static int skipAttributes(byte[] b, int p, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            if (p + 6 > b.length) throw new EOFException("truncated attribute");
            long len = u4(b, p + 2);
            if (len > Integer.MAX_VALUE || p + 6L + len > b.length) throw new EOFException("truncated attribute body");
            p += 6 + (int)len;
        }
        return p;
    }
    private static int skipMember(byte[] b, int p) throws IOException {
        if (p + 8 > b.length) throw new EOFException("truncated member");
        int attrs = u2(b, p + 6);
        return skipAttributes(b, p + 8, attrs);
    }

    private record MethodDecl(int access, String name, String desc) {}
    private record ClassInfo(byte[] bytes, ParsedCp cp, int access, String name, String superName,
                             List<MethodDecl> methods, int methodsCountOffset, int methodsEnd) {}

    private static ClassInfo parseClass(byte[] bytes) throws IOException {
        ParsedCp parsed = parseCp(bytes);
        CpEntry[] cp = parsed.entries();
        int p = parsed.end();
        if (p + 8 > bytes.length) throw new EOFException("truncated class header");
        int access = u2(bytes, p);
        String name = className(cp, u2(bytes, p + 2));
        String superName = className(cp, u2(bytes, p + 4));
        p += 6;
        int interfaces = u2(bytes, p); p += 2 + 2 * interfaces;
        if (p > bytes.length) throw new EOFException("truncated interfaces");
        int fields = u2(bytes, p); p += 2;
        for (int i = 0; i < fields; i++) p = skipMember(bytes, p);
        int methodsCountOffset = p;
        int methods = u2(bytes, p); p += 2;
        List<MethodDecl> decls = new ArrayList<>();
        for (int i = 0; i < methods; i++) {
            if (p + 8 > bytes.length) throw new EOFException("truncated method");
            int ma = u2(bytes, p);
            String mn = utf(cp, u2(bytes, p + 2));
            String md = utf(cp, u2(bytes, p + 4));
            decls.add(new MethodDecl(ma, mn, md));
            p = skipMember(bytes, p);
        }
        return new ClassInfo(bytes, parsed, access, name, superName, decls, methodsCountOffset, p);
    }

    private static String normalizeOwner(String s) { return s.replace('.', '/').replace('\\', '/'); }
    private static byte[] readClassFromJar(Path jar, String owner) throws IOException {
        String entry = normalizeOwner(owner) + ".class";
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry(entry);
            if (e == null) return null;
            try (InputStream in = z.getInputStream(e)) { return in.readAllBytes(); }
        }
    }
    private static byte[] readHierarchyClass(Path jar, String owner) throws IOException {
        byte[] x = readClassFromJar(jar, owner);
        if (x != null) return x;
        try (InputStream in = ClassLoader.getSystemResourceAsStream(normalizeOwner(owner) + ".class")) {
            if (in != null) return in.readAllBytes();
        }
        throw new FileNotFoundException("superclass not found in Bitwig JAR or runtime: " + owner);
    }

    private static String pkg(String internal) {
        int slash = internal.lastIndexOf('/');
        return slash < 0 ? "" : internal.substring(0, slash);
    }

    private record Inherited(String owner, int access) {}
    private static Inherited resolveInherited(Path hierarchyJar, ClassInfo target, String method, String desc,
                                               boolean requireConcreteSuper) throws IOException {
        String cur = target.superName();
        Set<String> seen = new HashSet<>();
        while (cur != null) {
            if (!seen.add(cur)) throw new IOException("superclass cycle while resolving " + method + desc + ": " + cur);
            ClassInfo ci = parseClass(readHierarchyClass(hierarchyJar, cur));
            for (MethodDecl d : ci.methods()) {
                if (d.name().equals(method) && d.desc().equals(desc)) {
                    int a = d.access();
                    if ((a & ACC_FINAL) != 0) throw new IOException("inherited method is final: " + cur + "." + method + desc);
                    if ((a & ACC_PRIVATE) != 0) throw new IOException("nearest matching superclass method is private and not overrideable: " + cur + "." + method + desc);
                    if ((a & ACC_STATIC) != 0) throw new IOException("nearest matching superclass method is static and not overrideable: " + cur + "." + method + desc);
                    int vis = a & (ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE);
                    if (vis == 0 && !pkg(target.name()).equals(pkg(cur)))
                        throw new IOException("package-private inherited method is not accessible from target package: " + cur + "." + method + desc);
                    if (requireConcreteSuper && (a & ACC_ABSTRACT) != 0)
                        throw new IOException("CALL_SUPER template cannot invoke abstract inherited method: " + cur + "." + method + desc);
                    return new Inherited(cur, a);
                }
            }
            cur = ci.superName();
        }
        throw new IOException("no matching inherited superclass method found for " + target.name() + "." + method + desc + " (interface-only inheritance is not supported by ADD_METHOD v1)");
    }

    private enum Kind { I, F, J, D, A, V }
    private record Type(String desc, Kind kind, int width) {}
    private record MethodType(List<Type> params, Type ret) {}

    private static Type parseType(String d, int[] p, boolean allowVoid) throws IOException {
        if (p[0] >= d.length()) throw new IOException("bad descriptor: " + d);
        int start = p[0];
        char c = d.charAt(p[0]++);
        return switch (c) {
            case 'V' -> {
                if (!allowVoid) throw new IOException("void is not valid here: " + d);
                yield new Type("V", Kind.V, 0);
            }
            case 'Z','B','C','S','I' -> new Type(d.substring(start, p[0]), Kind.I, 1);
            case 'F' -> new Type("F", Kind.F, 1);
            case 'J' -> new Type("J", Kind.J, 2);
            case 'D' -> new Type("D", Kind.D, 2);
            case 'L' -> {
                int semi = d.indexOf(';', p[0]);
                if (semi < 0) throw new IOException("bad object descriptor: " + d);
                p[0] = semi + 1;
                yield new Type(d.substring(start, p[0]), Kind.A, 1);
            }
            case '[' -> {
                while (p[0] < d.length() && d.charAt(p[0]) == '[') p[0]++;
                if (p[0] >= d.length()) throw new IOException("bad array descriptor: " + d);
                if (d.charAt(p[0]) == 'L') {
                    int semi = d.indexOf(';', p[0] + 1);
                    if (semi < 0) throw new IOException("bad array descriptor: " + d);
                    p[0] = semi + 1;
                } else {
                    char e = d.charAt(p[0]++);
                    if ("ZBCSIFJD".indexOf(e) < 0) throw new IOException("bad array element descriptor: " + d);
                }
                yield new Type(d.substring(start, p[0]), Kind.A, 1);
            }
            default -> throw new IOException("bad descriptor type '" + c + "' in " + d);
        };
    }

    private static MethodType parseMethodType(String d) throws IOException {
        if (d == null || d.length() < 3 || d.charAt(0) != '(') throw new IOException("bad method descriptor: " + d);
        int[] p = {1};
        List<Type> params = new ArrayList<>();
        while (p[0] < d.length() && d.charAt(p[0]) != ')') params.add(parseType(d, p, false));
        if (p[0] >= d.length() || d.charAt(p[0]++) != ')') throw new IOException("unterminated method descriptor: " + d);
        Type ret = parseType(d, p, true);
        if (p[0] != d.length()) throw new IOException("trailing descriptor data: " + d);
        return new MethodType(params, ret);
    }

    private record Local(Type type, int slot) {}
    private static Map<Integer, Local> parameterLocals(MethodType mt) {
        Map<Integer, Local> m = new HashMap<>();
        m.put(0, new Local(new Type("L<this>;", Kind.A, 1), 0));
        int slot = 1;
        for (Type t : mt.params()) {
            m.put(slot, new Local(t, slot));
            slot += t.width();
        }
        return m;
    }
    private static int parameterLocalCount(MethodType mt) {
        int n = 1;
        for (Type t : mt.params()) n += t.width();
        return n;
    }

    private static void emitVar(ByteArrayOutputStream o, int shortBase, int generic, int slot) throws IOException {
        if (slot < 0 || slot > 0xffff) throw new IOException("local index out of range: " + slot);
        if (slot <= 3) o.write(shortBase + slot);
        else if (slot <= 0xff) { o.write(generic); o.write(slot); }
        else { o.write(0xc4); o.write(generic); o.write((slot >>> 8) & 0xff); o.write(slot & 0xff); }
    }
    private static void emitLoad(ByteArrayOutputStream o, Type t, int slot) throws IOException {
        switch (t.kind()) {
            case I -> emitVar(o, 0x1a, 0x15, slot);
            case J -> emitVar(o, 0x1e, 0x16, slot);
            case F -> emitVar(o, 0x22, 0x17, slot);
            case D -> emitVar(o, 0x26, 0x18, slot);
            case A -> emitVar(o, 0x2a, 0x19, slot);
            default -> throw new IOException("cannot load type " + t.desc());
        }
    }
    private static void emitStore(ByteArrayOutputStream o, Type t, int slot) throws IOException {
        switch (t.kind()) {
            case I -> emitVar(o, 0x3b, 0x36, slot);
            case J -> emitVar(o, 0x3f, 0x37, slot);
            case F -> emitVar(o, 0x43, 0x38, slot);
            case D -> emitVar(o, 0x47, 0x39, slot);
            case A -> emitVar(o, 0x4b, 0x3a, slot);
            default -> throw new IOException("cannot store type " + t.desc());
        }
    }
    private static void emitReturn(ByteArrayOutputStream o, Type t) throws IOException {
        int op = switch (t.kind()) {
            case V -> 0xb1;
            case I -> 0xac;
            case J -> 0xad;
            case F -> 0xae;
            case D -> 0xaf;
            case A -> 0xb0;
        };
        o.write(op);
    }

    private static void emitU2Insn(ByteArrayOutputStream o, int opcode, int cpIndex) throws IOException {
        o.write(opcode); o.write((cpIndex >>> 8) & 0xff); o.write(cpIndex & 0xff);
    }

    private record LoadPlan(byte[] code, int stackSlots, String summary) {}
    private static LoadPlan helperLoads(String csv, MethodType targetType, String helperDesc) throws IOException {
        String[] toks = (csv == null || csv.isBlank()) ? new String[0]
                : Arrays.stream(csv.split(",")).map(String::trim).toArray(String[]::new);
        MethodType helperType = parseMethodType(helperDesc);
        if (helperType.params().size() != toks.length)
            throw new IOException("bridge descriptor " + helperDesc + " expects " + helperType.params().size() + " args but manifest supplies " + toks.length);
        Map<Integer, Local> locals = parameterLocals(targetType);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int stack = 0;
        List<String> summary = new ArrayList<>();
        for (int i = 0; i < toks.length; i++) {
            String tok = toks[i];
            int slot;
            if (tok.equals("this")) slot = 0;
            else if (tok.startsWith("arg")) {
                try { slot = Integer.parseInt(tok.substring(3)); }
                catch (NumberFormatException e) { throw new IOException("bad bridge arg '" + tok + "'", e); }
            } else throw new IOException("unsupported bridge arg '" + tok + "' (supported: this,arg0,arg1,... raw JVM local slots)");
            Local local = locals.get(slot);
            if (local == null) throw new IOException("bridge arg " + tok + " refers to non-parameter/unmapped raw local slot " + slot + " for target method");
            Type expected = helperType.params().get(i);
            if (local.type().kind() != expected.kind())
                throw new IOException("bridge arg " + tok + " type mismatch: target local is " + local.type().kind() + " but bridge parameter " + (i + 1) + " is " + expected.kind());
            emitLoad(out, local.type(), slot);
            stack += local.type().width();
            summary.add(tok + "=" + local.type().kind() + "@" + slot);
        }
        return new LoadPlan(out.toByteArray(), stack, String.join(";", summary));
    }

    private static int argsStack(MethodType mt) {
        int n = 1; // receiver
        for (Type t : mt.params()) n += t.width();
        return n;
    }

    private static void emitThisAndArgs(ByteArrayOutputStream code, MethodType mt) throws IOException {
        code.write(0x2a); // aload_0
        int slot = 1;
        for (Type t : mt.params()) {
            emitLoad(code, t, slot);
            slot += t.width();
        }
    }

    private static final class CpAppend {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int next;
        CpAppend(int oldCount) { next = oldCount; }
        int utf8(String s) throws IOException {
            byte[] x = s.getBytes(StandardCharsets.UTF_8);
            if (x.length > 0xffff) throw new IOException("UTF8 constant too long");
            int i = next++; bytes.write(1); bytes.write(p2(x.length)); bytes.write(x); return i;
        }
        int clazz(int nameIdx) throws IOException { int i = next++; bytes.write(7); bytes.write(p2(nameIdx)); return i; }
        int nat(int nameIdx, int descIdx) throws IOException { int i = next++; bytes.write(12); bytes.write(p2(nameIdx)); bytes.write(p2(descIdx)); return i; }
        int methodRef(int clsIdx, int natIdx) throws IOException { int i = next++; bytes.write(10); bytes.write(p2(clsIdx)); bytes.write(p2(natIdx)); return i; }
    }

    private record Generated(byte[] code, int maxStack, int maxLocals, String loads) {}
    private static Generated generate(String template, MethodType targetType, String helperDesc, String argsCsv,
                                      int superRef, int helperRef) throws IOException {
        MethodType helperType = parseMethodType(helperDesc);
        LoadPlan helperLoads = helperLoads(argsCsv, targetType, helperDesc);
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        int maxStack = 0;
        int maxLocals = parameterLocalCount(targetType);
        Type ret = targetType.ret();

        switch (template) {
            case "CALL_SUPER_THEN_STATIC" -> {
                if (helperType.ret().kind() != Kind.V)
                    throw new IOException("CALL_SUPER_THEN_STATIC requires a void bridge return: " + helperDesc);
                emitThisAndArgs(code, targetType);
                emitU2Insn(code, 0xb7, superRef); // invokespecial
                maxStack = Math.max(maxStack, argsStack(targetType));
                int tmp = -1;
                if (ret.kind() != Kind.V) {
                    tmp = maxLocals;
                    emitStore(code, ret, tmp);
                    maxLocals += ret.width();
                }
                code.write(helperLoads.code());
                emitU2Insn(code, 0xb8, helperRef); // invokestatic
                maxStack = Math.max(maxStack, helperLoads.stackSlots());
                if (ret.kind() != Kind.V) emitLoad(code, ret, tmp);
                emitReturn(code, ret);
                maxStack = Math.max(maxStack, ret.width());
            }
            case "CALL_STATIC_THEN_SUPER" -> {
                if (helperType.ret().kind() != Kind.V)
                    throw new IOException("CALL_STATIC_THEN_SUPER requires a void bridge return: " + helperDesc);
                code.write(helperLoads.code());
                emitU2Insn(code, 0xb8, helperRef);
                maxStack = Math.max(maxStack, helperLoads.stackSlots());
                emitThisAndArgs(code, targetType);
                emitU2Insn(code, 0xb7, superRef);
                maxStack = Math.max(maxStack, argsStack(targetType));
                maxStack = Math.max(maxStack, ret.width());
                emitReturn(code, ret);
            }
            case "CALL_STATIC_ONLY" -> {
                if (!helperType.ret().desc().equals(ret.desc()))
                    throw new IOException("CALL_STATIC_ONLY bridge return must exactly match target return: target=" + ret.desc() + " bridge=" + helperType.ret().desc());
                code.write(helperLoads.code());
                emitU2Insn(code, 0xb8, helperRef);
                maxStack = Math.max(maxStack, helperLoads.stackSlots());
                maxStack = Math.max(maxStack, ret.width());
                emitReturn(code, ret);
            }
            default -> throw new IOException("unsupported ADD_METHOD template: " + template);
        }
        if (code.size() > 65535) throw new IOException("generated method code exceeds JVM 65535-byte limit");
        return new Generated(code.toByteArray(), maxStack, maxLocals, helperLoads.summary());
    }

    private static byte[] buildMethodInfo(int access, int nameIdx, int descIdx, int codeIdx, Generated g) throws IOException {
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        info.write(p2(g.maxStack()));
        info.write(p2(g.maxLocals()));
        info.write(p4(g.code().length));
        info.write(g.code());
        info.write(p2(0)); // exception_table_length
        info.write(p2(0)); // Code attributes_count; straight-line method needs no StackMapTable

        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.write(p2(access));
        m.write(p2(nameIdx));
        m.write(p2(descIdx));
        m.write(p2(1));
        m.write(p2(codeIdx));
        m.write(p4(info.size()));
        m.write(info.toByteArray());
        return m.toByteArray();
    }

    private record PatchResult(byte[] bytes, String targetOwner, String inheritedOwner, int generatedAccess,
                               int oldMethodCount, int newMethodCount, Generated generated) {}

    private static PatchResult patch(byte[] original, Path hierarchyJar, String method, String desc, String template,
                                     String helperOwner, String helperMethod, String helperDesc, String argsCsv) throws IOException {
        ClassInfo target = parseClass(original);
        if ((target.access() & ACC_INTERFACE) != 0) throw new IOException("ADD_METHOD v1 does not add methods to interfaces: " + target.name());
        if (method.equals("<init>") || method.equals("<clinit>")) throw new IOException("ADD_METHOD cannot generate constructors or class initializers");
        for (MethodDecl d : target.methods()) {
            if (d.name().equals(method) && d.desc().equals(desc))
                throw new IOException("target already declares exact method " + target.name() + "." + method + desc);
        }
        boolean superTemplate = template.equals("CALL_SUPER_THEN_STATIC") || template.equals("CALL_STATIC_THEN_SUPER");
        if (!superTemplate && !template.equals("CALL_STATIC_ONLY")) throw new IOException("unsupported ADD_METHOD template: " + template);
        Inherited inherited = resolveInherited(hierarchyJar, target, method, desc, superTemplate);
        int vis = inherited.access() & (ACC_PUBLIC | ACC_PROTECTED); // package-private remains zero
        int generatedAccess = vis;

        MethodType targetType = parseMethodType(desc);
        // Validate helper and construct CP entries. Duplicates are intentional: this keeps the patcher simple and version-agnostic.
        parseMethodType(helperDesc);
        CpAppend add = new CpAppend(target.cp().count());
        int methodNameUtf = add.utf8(method);
        int methodDescUtf = add.utf8(desc);
        int codeUtf = add.utf8("Code");

        int superOwnerUtf = add.utf8(inherited.owner());
        int superClass = add.clazz(superOwnerUtf);
        int superNat = add.nat(methodNameUtf, methodDescUtf);
        int superRef = add.methodRef(superClass, superNat);

        int helperOwnerUtf = add.utf8(normalizeOwner(helperOwner));
        int helperClass = add.clazz(helperOwnerUtf);
        int helperNameUtf = add.utf8(helperMethod);
        int helperDescUtf = add.utf8(helperDesc);
        int helperNat = add.nat(helperNameUtf, helperDescUtf);
        int helperRef = add.methodRef(helperClass, helperNat);

        if (add.next > 0xffff) throw new IOException("constant pool overflow");
        Generated g = generate(template, targetType, helperDesc, argsCsv, superRef, helperRef);
        byte[] newMethod = buildMethodInfo(generatedAccess, methodNameUtf, methodDescUtf, codeUtf, g);

        int oldCount = target.methods().size();
        if (oldCount >= 0xffff) throw new IOException("method count overflow");
        byte[] out = concat(
                Arrays.copyOfRange(original, 0, 8),
                p2(add.next),
                Arrays.copyOfRange(original, 10, target.cp().end()),
                add.bytes.toByteArray(),
                Arrays.copyOfRange(original, target.cp().end(), target.methodsCountOffset()),
                p2(oldCount + 1),
                Arrays.copyOfRange(original, target.methodsCountOffset() + 2, target.methodsEnd()),
                newMethod,
                Arrays.copyOfRange(original, target.methodsEnd(), original.length)
        );
        // Structural self-check catches accidental layout mistakes before the external deep verifier.
        ClassInfo check = parseClass(out);
        long exact = check.methods().stream().filter(d -> d.name().equals(method) && d.desc().equals(desc)).count();
        if (exact != 1) throw new IOException("internal ADD_METHOD structural check failed; generated exact method count=" + exact);
        return new PatchResult(out, target.name(), inherited.owner(), generatedAccess, oldCount, oldCount + 1, g);
    }

    private static void run(boolean jarMode, String[] a) throws Exception {
        final byte[] original;
        final Path hierarchyJar, out;
        final String ownerLabel, method, desc, template, helperOwner, helperMethod, helperDesc, argsCsv;
        if (jarMode) {
            if (a.length != 10 && a.length != 11)
                throw new IllegalArgumentException("patch-jar <jar> <owner> <out.class> <method> <desc> <template> <helperOwner> <helperMethod> <helperDesc> [argsCsv]");
            hierarchyJar = Path.of(a[1]);
            String owner = a[2];
            out = Path.of(a[3]); method = a[4]; desc = a[5]; template = a[6]; helperOwner = a[7]; helperMethod = a[8]; helperDesc = a[9]; argsCsv = a.length == 11 ? a[10] : "";
            original = readClassFromJar(hierarchyJar, owner);
            if (original == null) throw new FileNotFoundException("class not found in jar: " + normalizeOwner(owner) + ".class");
            ownerLabel = owner;
        } else {
            if (a.length != 10 && a.length != 11)
                throw new IllegalArgumentException("patch-class <in.class> <hierarchy.jar> <out.class> <method> <desc> <template> <helperOwner> <helperMethod> <helperDesc> [argsCsv]");
            Path in = Path.of(a[1]); hierarchyJar = Path.of(a[2]); out = Path.of(a[3]); method = a[4]; desc = a[5]; template = a[6]; helperOwner = a[7]; helperMethod = a[8]; helperDesc = a[9]; argsCsv = a.length == 11 ? a[10] : "";
            original = Files.readAllBytes(in); ownerLabel = in.toString();
        }
        PatchResult r = patch(original, hierarchyJar, method, desc, template, helperOwner, helperMethod, helperDesc, argsCsv);
        Path parent = out.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
        Files.write(out, r.bytes());
        System.out.printf(Locale.ROOT,
                "PATCH_OK_ADD_METHOD owner=%s target=%s method=%s%s template=%s inherited_owner=%s access=0x%04x major=%d class_bytes=%d->%d methods=%d->%d code=%d max_stack=%d max_locals=%d bridge=%s.%s%s args=%s loads=%s%n",
                ownerLabel, r.targetOwner(), method, desc, template, r.inheritedOwner(), r.generatedAccess(), u2(original, 6), original.length, r.bytes().length,
                r.oldMethodCount(), r.newMethodCount(), r.generated().code().length, r.generated().maxStack(), r.generated().maxLocals(), helperOwner, helperMethod, helperDesc, argsCsv, r.generated().loads());
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Commands: patch-jar | patch-class");
        switch (args[0]) {
            case "patch-jar" -> run(true, args);
            case "patch-class" -> run(false, args);
            default -> throw new IllegalArgumentException("unknown command: " + args[0]);
        }
    }
}
