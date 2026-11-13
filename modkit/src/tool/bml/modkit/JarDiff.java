package bml.modkit;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.Label;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compares a modified Bitwig jar with the official jar of the same version and platform, and lists
 * each change sorted into what it becomes in a BML mod: a helper class, a hook of a given kind, an
 * opened member, or something BML has no equivalent for. Reads both jars; writes nothing.
 */
final class JarDiff {
    private static final int VISIBILITY = ClassFile.ACC_PUBLIC | ClassFile.ACC_PRIVATE | ClassFile.ACC_PROTECTED;
    private static final int SHOWN = 12; // instructions shown per inserted or removed region

    private final ClassFile cf = ClassFile.of();
    private final ZipFile official;
    private final Map<String, Optional<ClassModel>> officialClasses = new HashMap<>();
    private final PrintStream out;
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    private JarDiff(ZipFile official, PrintStream out) {
        this.official = official;
        this.out = out;
    }

    static void run(Path officialJar, Path modifiedJar, PrintStream out) throws IOException {
        try (ZipFile off = new ZipFile(officialJar.toFile()); ZipFile mod = new ZipFile(modifiedJar.toFile())) {
            JarDiff d = new JarDiff(off, out);
            out.println("JAR_DIFF official=" + officialJar + " modified=" + modifiedJar);
            d.compare(mod);
            StringBuilder sb = new StringBuilder("JAR_DIFF_DONE");
            d.counts.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
            out.println(sb);
            if (d.counts.getOrDefault("changed_classes", 0) > 1000) {
                out.println("JAR_DIFF_WARNING more than 1000 classes differ: the official jar is probably not the same Bitwig version and platform as the modified one");
            }
        }
    }

    private void count(String kind) {
        counts.merge(kind, 1, Integer::sum);
    }

    private void compare(ZipFile mod) throws IOException {
        TreeSet<String> names = new TreeSet<>();
        official.stream().map(ZipEntry::getName).filter(n -> !n.endsWith("/")).forEach(names::add);
        mod.stream().map(ZipEntry::getName).filter(n -> !n.endsWith("/")).forEach(names::add);
        List<String> changedClasses = new ArrayList<>();
        for (String name : names) {
            if (name.startsWith("META-INF/")) continue; // signatures and the jar manifest
            ZipEntry a = official.getEntry(name), b = mod.getEntry(name);
            boolean isClass = name.endsWith(".class");
            if (a == null) {
                if (isClass) {
                    String cls = name.substring(0, name.length() - 6);
                    out.println("ADDED_CLASS " + cls + "  -> a class of the mod's helper jar" + (packageAdvice(cls)));
                    count("added_classes");
                } else {
                    out.println("ADDED_RESOURCE " + name + "  -> a resource of the mod (kind: file), read by the mod's own code");
                    count("added_resources");
                }
            } else if (b == null) {
                out.println((isClass ? "REMOVED_CLASS " : "REMOVED_RESOURCE ") + name + "  -> no BML equivalent: a mod can't remove Bitwig's files");
                count("removed");
            } else {
                byte[] x = official.getInputStream(a).readAllBytes(), y = mod.getInputStream(b).readAllBytes();
                if (Arrays.equals(x, y)) continue;
                if (isClass) changedClasses.add(name);
                else {
                    out.println("CHANGED_RESOURCE " + name + "  -> no BML equivalent: a mod can't replace Bitwig's files (a mod's own copy can be a resource, used by the mod's code)");
                    count("changed_resources");
                }
            }
        }
        for (String name : changedClasses) {
            ClassModel a = cf.parse(official.getInputStream(official.getEntry(name)).readAllBytes());
            ClassModel b = cf.parse(mod.getInputStream(mod.getEntry(name)).readAllBytes());
            compareClass(a, b);
        }
    }

    private static String packageAdvice(String cls) {
        int slash = cls.lastIndexOf('/');
        if (slash < 0) return "; it is in the unnamed package: move it to a package of your own";
        if (cls.startsWith("com/bitwig/")) return "; it is in a Bitwig package: move it to a package of your own";
        return "";
    }

    private void compareClass(ClassModel a, ClassModel b) {
        String cls = a.thisClass().asInternalName();
        List<String> lines = new ArrayList<>();
        if (a.flags().flagsMask() != b.flags().flagsMask()) {
            lines.add(access("class " + cls, a.flags().flagsMask(), b.flags().flagsMask()));
        }
        String supA = a.superclass().map(ClassEntry::asInternalName).orElse("-"), supB = b.superclass().map(ClassEntry::asInternalName).orElse("-");
        if (!supA.equals(supB)) {
            lines.add("CHANGED_SUPERCLASS " + supA + " -> " + supB + "  -> no BML equivalent: candidate for a new hook type");
            count("unsupported");
        }
        List<String> ifA = a.interfaces().stream().map(ClassEntry::asInternalName).toList(), ifB = b.interfaces().stream().map(ClassEntry::asInternalName).toList();
        if (!new TreeSet<>(ifA).equals(new TreeSet<>(ifB))) {
            lines.add("CHANGED_INTERFACES " + ifA + " -> " + ifB + "  -> no BML equivalent: candidate for a new hook type");
            count("unsupported");
        }
        Map<String, FieldModel> fa = fields(a), fb = fields(b);
        for (var e : fb.entrySet()) {
            FieldModel f = fa.get(e.getKey());
            if (f == null) {
                lines.add("ADDED_FIELD " + e.getKey() + "  -> @AddField; or a weak identity map in the helper, if the value can't reach the " + cls + " (not a plain WeakHashMap: it compares keys with equals, and a value that reaches its key is never collected)");
                count("added_fields");
            } else if (f.flags().flagsMask() != e.getValue().flags().flagsMask()) {
                lines.add(access("field " + cls + "." + e.getKey(), f.flags().flagsMask(), e.getValue().flags().flagsMask()));
            }
        }
        for (String k : fa.keySet()) {
            if (!fb.containsKey(k)) {
                lines.add("REMOVED_FIELD " + k + "  -> no BML equivalent");
                count("removed");
            }
        }
        Map<String, MethodModel> ma = methods(a), mb = methods(b);
        for (var e : mb.entrySet()) {
            MethodModel m = ma.get(e.getKey());
            if (m == null) {
                lines.add(addedMethod(a, cls, e.getKey(), e.getValue()));
                continue;
            }
            if (m.flags().flagsMask() != e.getValue().flags().flagsMask()) {
                lines.add(access("method " + cls + "." + e.getKey(), m.flags().flagsMask(), e.getValue().flags().flagsMask()));
            }
            List<String> ca = code(m), cb = code(e.getValue());
            if (!ca.equals(cb)) lines.add(changedMethod(cls, e.getKey(), ca, cb, (m.flags().flagsMask() & ClassFile.ACC_STATIC) != 0));
        }
        for (String k : ma.keySet()) {
            if (!mb.containsKey(k)) {
                lines.add("REMOVED_METHOD " + k + "  -> no BML equivalent (an OVERRIDE_INSTEAD or REPLACE_CALL at its callers may stand in)");
                count("removed");
            }
        }
        out.println("CLASS " + cls + (lines.isEmpty() ? "  (bytes differ, no difference in members or code: debug info, constant order or attributes only; nothing to port)" : ""));
        for (String l : lines) out.println("  " + l.replace("\n", "\n  "));
        count("changed_classes");
    }

    private String access(String what, int before, int after) {
        String kind = describeAccess(before) + " -> " + describeAccess(after);
        if ((before & VISIBILITY) != (after & VISIBILITY) || (before & ClassFile.ACC_FINAL) != (after & ClassFile.ACC_FINAL)) {
            count("opened");
            return "OPEN " + what + "  " + kind + "  -> nothing to do: use it from the helper; the mod kit lists it under opens";
        }
        count("other_flags");
        return "FLAGS " + what + "  " + kind + "  -> other modifier changed: no BML equivalent";
    }

    private static String describeAccess(int f) {
        String v = (f & ClassFile.ACC_PUBLIC) != 0 ? "public" : (f & ClassFile.ACC_PROTECTED) != 0 ? "protected" : (f & ClassFile.ACC_PRIVATE) != 0 ? "private" : "package";
        if ((f & ClassFile.ACC_FINAL) != 0) v += " final";
        if ((f & ClassFile.ACC_STATIC) != 0) v += " static";
        return v;
    }

    private static Map<String, FieldModel> fields(ClassModel c) {
        Map<String, FieldModel> m = new LinkedHashMap<>();
        for (FieldModel f : c.fields()) m.put(f.fieldName().stringValue() + " " + f.fieldType().stringValue(), f);
        return m;
    }

    private static Map<String, MethodModel> methods(ClassModel c) {
        Map<String, MethodModel> m = new LinkedHashMap<>();
        for (MethodModel x : c.methods()) m.put(x.methodName().stringValue() + x.methodType().stringValue(), x);
        return m;
    }

    // ------------------------------------------------------------------ added methods

    private String addedMethod(ClassModel a, String cls, String sig, MethodModel m) {
        int paren = sig.indexOf('(');
        String name = sig.substring(0, paren), desc = sig.substring(paren);
        String declarer = (m.flags().flagsMask() & ClassFile.ACC_STATIC) != 0 || name.startsWith("<") ? null : inheritedDeclarer(a, name, desc);
        if (declarer == null) {
            count("added_methods");
            return "ADDED_METHOD " + sig + "  -> a static method of the helper (with the object as a parameter if it used this); called from a hook";
        }
        List<String> body = code(m);
        int superCall = -1;
        for (int i = 0; i < body.size(); i++) {
            if (body.get(i).equals("INVOKESPECIAL " + declarer + "." + name + desc) || body.get(i).startsWith("INVOKESPECIAL ") && body.get(i).endsWith("." + name + desc)) {
                superCall = i;
                break;
            }
        }
        String at = superCall < 0 ? "OVERRIDE_INSTEAD" : superCall < body.size() / 2 ? "OVERRIDE_AFTER_SUPER" : "OVERRIDE_BEFORE_SUPER";
        count("hooks");
        TreeSet<String> imports = new TreeSet<>(List.of("import bml.api.At;", "import bml.api.Hook;"));
        String ret = at.equals("OVERRIDE_INSTEAD") ? javaType(java.lang.constant.MethodTypeDesc.ofDescriptor(desc).returnType().descriptorString(), imports) : "void";
        return "ADDED_OVERRIDE " + sig + "  -> " + at + " (overrides " + declarer + "." + name + desc
                + (superCall < 0 ? "; no super call" : "; super call at instruction " + superCall + " of " + body.size()) + ")"
                + "\n  " + hook(cls, name, desc, at, "", ret, params(cls, desc, false, null, true, imports), imports);
    }

    /** The superclass or interface (in the official jar) that declares an instance method with this name and descriptor. */
    private String inheritedDeclarer(ClassModel c, String name, String desc) {
        List<String> todo = new ArrayList<>();
        c.superclass().ifPresent(s -> todo.add(s.asInternalName()));
        c.interfaces().forEach(i -> todo.add(i.asInternalName()));
        for (int i = 0; i < todo.size() && i < 200; i++) {
            String n = todo.get(i);
            Optional<ClassModel> m = officialClass(n);
            if (m.isEmpty()) {
                if (n.startsWith("java/") && jdkDeclares(n, name, desc)) return n;
                continue;
            }
            for (MethodModel x : m.get().methods()) {
                if (x.methodName().stringValue().equals(name) && x.methodType().stringValue().equals(desc)
                        && (x.flags().flagsMask() & (ClassFile.ACC_STATIC | ClassFile.ACC_PRIVATE)) == 0) return n;
            }
            m.get().superclass().ifPresent(s -> todo.add(s.asInternalName()));
            m.get().interfaces().forEach(x -> todo.add(x.asInternalName()));
        }
        return null;
    }

    private static boolean jdkDeclares(String cls, String name, String desc) {
        try {
            for (var m : Class.forName(cls.replace('/', '.'), false, ClassLoader.getPlatformClassLoader()).getMethods()) {
                if (m.getName().equals(name) && java.lang.invoke.MethodType.methodType(m.getReturnType(), m.getParameterTypes()).toMethodDescriptorString().equals(desc)) return true;
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
        }
        return false;
    }

    private Optional<ClassModel> officialClass(String name) {
        return officialClasses.computeIfAbsent(name, n -> {
            ZipEntry e = official.getEntry(n + ".class");
            if (e == null) return Optional.empty();
            try {
                return Optional.of(cf.parse(official.getInputStream(e).readAllBytes()));
            } catch (IOException ex) {
                return Optional.empty();
            }
        });
    }

    // ------------------------------------------------------------------ changed methods

    /** How Java source imports a class: Bitwig's unnamed-package classes from bitwig.obf (the compile jar's package). */
    private static String importOf(String internal) {
        return "import " + (internal.indexOf('/') < 0 ? "bitwig.obf." + internal : internal.replace('/', '.')) + ";";
    }

    /**
     * For an inserted run of loads ending in one call: which of the method's values they pass, as -1 for
     * this and n for parameter n. Null when the inserted code is anything else.
     */
    private static List<Integer> loaded(List<String> inserted, String desc, boolean isStatic) {
        List<Integer> out = new ArrayList<>();
        Map<Integer, Integer> slotToArg = new HashMap<>();
        int slot = isStatic ? 0 : 1, arg = 0;
        for (var t : java.lang.constant.MethodTypeDesc.ofDescriptor(desc).parameterList()) {
            slotToArg.put(slot, arg++);
            slot += t.descriptorString().equals("J") || t.descriptorString().equals("D") ? 2 : 1;
        }
        if (inserted.isEmpty() || !isCall(inserted.get(inserted.size() - 1))) return null;
        for (int i = 0; i < inserted.size() - 1; i++) {
            String[] w = inserted.get(i).split(" ");
            if (!w[0].equals("LOAD")) return null;
            int s = Integer.parseInt(w[2]);
            if (!isStatic && s == 0) out.add(-1);
            else if (slotToArg.containsKey(s)) out.add(slotToArg.get(s));
            else return null;
        }
        return out;
    }

    /** Hook parameters: the values the inserted code passed, or else this and every parameter (objects only unless primitives are allowed). */
    private static List<String> params(String cls, String desc, boolean isStatic, List<Integer> which, boolean primitives, TreeSet<String> imports) {
        var ps = java.lang.constant.MethodTypeDesc.ofDescriptor(desc).parameterList();
        List<Integer> use = which;
        if (use == null) {
            use = new ArrayList<>();
            if (!isStatic) use.add(-1);
            for (int i = 0; i < ps.size(); i++) use.add(i);
        }
        List<String> out = new ArrayList<>();
        for (int i : use) {
            if (i < 0) {
                imports.add("import bml.api.This;");
                out.add("@This " + javaType("L" + cls + ";", imports) + " self");
            } else {
                String d = ps.get(i).descriptorString();
                if (!primitives && !(d.startsWith("L") || d.startsWith("["))) continue;
                imports.add("import bml.api.Arg;");
                out.add("@Arg(" + i + ") " + javaType(d, imports) + " arg" + i);
            }
        }
        return out;
    }

    /** The Java type a descriptor stands for, adding the import it needs. */
    private static String javaType(String d, TreeSet<String> imports) {
        int dims = 0;
        while (d.charAt(dims) == '[') dims++;
        String base = d.substring(dims), name = switch (base.charAt(0)) {
            case 'Z' -> "boolean"; case 'B' -> "byte"; case 'C' -> "char"; case 'S' -> "short"; case 'I' -> "int";
            case 'J' -> "long"; case 'F' -> "float"; case 'D' -> "double"; case 'V' -> "void";
            default -> {
                String internal = base.substring(1, base.length() - 1);
                if (!internal.startsWith("java/lang/") || internal.indexOf('/', 10) >= 0) imports.add(importOf(internal));
                yield internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.');
            }
        };
        return name + "[]".repeat(dims);
    }

    /** A ready-to-use hook: annotation, method signature and imports, one per line. */
    private static String hook(String cls, String name, String desc, String at, String extra, String ret, List<String> ps, TreeSet<String> imports) {
        String simple = cls.substring(cls.lastIndexOf('/') + 1).replace('$', '_');
        String base = simple + "-" + name.replace("<", "").replace(">", "");
        String id = base.toLowerCase().replaceAll("[^a-z0-9.-]", "-") + "-" + at.toLowerCase().replace('_', '-');
        String method = (simple.substring(0, 1).toLowerCase() + simple.substring(1) + "_" + name.replace("<", "").replace(">", "")).replaceAll("[^A-Za-z0-9_]", "_");
        String target = javaType("L" + cls + ";", imports);
        return "@Hook(id = \"" + id + "\", target = " + target + ".class, method = \"" + name + desc + "\", at = At." + at + extra + ")"
                + "\n  public static " + ret + " " + method + "(" + String.join(", ", ps) + ")"
                + "\n  " + String.join("\n  ", imports);
    }

    private String changedMethod(String cls, String sig, List<String> a, List<String> b, boolean isStatic) {
        int p = 0;
        while (p < a.size() && p < b.size() && a.get(p).equals(b.get(p))) p++;
        int s = 0;
        while (s < a.size() - p && s < b.size() - p && a.get(a.size() - 1 - s).equals(b.get(b.size() - 1 - s))) s++;
        List<String> removed = a.subList(p, a.size() - s), inserted = b.subList(p, b.size() - s);
        String name = sig.substring(0, sig.indexOf('(')), desc = sig.substring(sig.indexOf('('));
        TreeSet<String> imports = new TreeSet<>(List.of("import bml.api.At;", "import bml.api.Hook;"));
        String kind, advice;
        if (removed.isEmpty() && p == 0) {
            kind = "ENTRY";
            advice = hook(cls, name, desc, "ENTRY", "", "void", params(cls, desc, isStatic, loaded(inserted, desc, isStatic), true, imports), imports);
        } else if (removed.isEmpty() && s == 1 && a.get(a.size() - 1).endsWith("RETURN")) {
            boolean value = !a.get(a.size() - 1).equals("RETURN");
            boolean wraps = value && !inserted.isEmpty() && inserted.get(inserted.size() - 1).startsWith("INVOKE") && !inserted.get(inserted.size() - 1).endsWith(")V");
            kind = wraps ? "WRAP_RETURN" : "BEFORE_RETURN";
            if (wraps) {
                String ret = javaType(java.lang.constant.MethodTypeDesc.ofDescriptor(desc).returnType().descriptorString(), imports);
                imports.add("import bml.api.Return;");
                advice = hook(cls, name, desc, kind, "", ret, List.of("@Return " + ret + " value"), imports)
                        + "\n  (the inserted call takes the return value and returns a new one; the hook returns the value to use)";
            } else {
                advice = hook(cls, name, desc, kind, "", "void", params(cls, desc, isStatic, loaded(inserted, desc, isStatic), false, imports), imports);
            }
        } else if (removed.size() == 1 && isCall(removed.get(0)) && inserted.stream().anyMatch(JarDiff::isCall)) {
            String call = removed.get(0);
            int occurrence = 1;
            for (int i = 0; i < p; i++) if (a.get(i).equals(call)) occurrence++;
            String member = call.substring(call.indexOf(' ') + 1);
            String owner = member.substring(0, member.lastIndexOf('.', member.indexOf('(')));
            String callName = member.substring(owner.length() + 1);
            var callType = java.lang.constant.MethodTypeDesc.ofDescriptor(callName.substring(callName.indexOf('(')));
            List<String> ps = new ArrayList<>();
            if (!call.startsWith("INVOKESTATIC")) ps.add(javaType("L" + owner + ";", imports) + " receiver");
            int n = 0;
            for (var t : callType.parameterList()) ps.add(javaType(t.descriptorString(), imports) + " arg" + n++);
            kind = "REPLACE_CALL";
            advice = hook(cls, name, desc, kind, ", callOwner = " + javaType("L" + owner + ";", imports) + ".class, call = \"" + callName + "\", occurrence = " + occurrence,
                    javaType(callType.returnType().descriptorString(), imports), ps, imports)
                    + "\n  (the hook runs instead of the call: make the original call yourself, receiver." + callName.substring(0, callName.indexOf('(')) + "(...), unless your edit dropped it)";
        } else if (removed.isEmpty()) {
            kind = "INSERT_MID";
            advice = "no hook inserts mid-method: use REPLACE_CALL on a call next to the insertion point and do the inserted work in the hook, before or after calling the original. "
                    + "Calls before it: " + nearbyCalls(a, p, -1) + "; calls after it: " + nearbyCalls(a, a.size() - s, 1);
        } else {
            kind = "REWRITE";
            advice = "the method's code was changed in place (or edited in several places): compare both versions with javap -c and express each edit as a hook "
                    + "(REPLACE_CALL on the calls involved, an ENTRY that skips nothing, or OVERRIDE_INSTEAD in a subclass); if nothing fits, report it as a candidate hook type";
        }
        count("INSERT_MID".equals(kind) || "REWRITE".equals(kind) ? "needs_review" : "hooks");
        StringBuilder sb = new StringBuilder("CHANGED_METHOD " + sig + "  -> " + kind + "\n  " + advice);
        sb.append("\n  official: ").append(a.size()).append(" instructions; modified: ").append(b.size())
                .append("; same first ").append(p).append(", same last ").append(s);
        region(sb, "removed", removed);
        region(sb, "inserted", inserted);
        return sb.toString();
    }

    /** A call a REPLACE_CALL can name: invokevirtual, invokestatic, invokespecial or invokeinterface (not invokedynamic). */
    private static boolean isCall(String ins) {
        return ins.startsWith("INVOKE") && !ins.startsWith("INVOKEDYNAMIC");
    }

    private static String nearbyCalls(List<String> code, int from, int step) {
        List<String> found = new ArrayList<>();
        for (int i = step < 0 ? from - 1 : from; i >= 0 && i < code.size() && found.size() < 2; i += step) {
            if (isCall(code.get(i))) found.add(code.get(i));
        }
        return found.isEmpty() ? "none" : String.join(", ", found);
    }

    private static void region(StringBuilder sb, String what, List<String> r) {
        if (r.isEmpty()) return;
        sb.append("\n  ").append(what).append(" (").append(r.size()).append("):");
        for (int i = 0; i < r.size() && i < SHOWN; i++) sb.append("\n    ").append(r.get(i));
        if (r.size() > SHOWN) sb.append("\n    ... ").append(r.size() - SHOWN).append(" more");
    }

    /** A method's instructions as comparable strings: branch targets relative, no constant-pool indexes or line numbers. */
    private static List<String> code(MethodModel m) {
        Optional<CodeModel> cm = m.code();
        if (cm.isEmpty()) return List.of();
        List<CodeElement> elements = cm.get().elementList();
        Map<Label, Integer> at = new HashMap<>();
        int index = 0;
        for (CodeElement e : elements) {
            if (e instanceof LabelTarget t) at.put(t.label(), index);
            else if (e instanceof Instruction) index++;
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        for (CodeElement e : elements) {
            if (e instanceof Instruction ins) {
                out.add(describe(ins, i, at));
                i++;
            }
        }
        return out;
    }

    private static String describe(Instruction ins, int self, Map<Label, Integer> at) {
        Opcode op = ins.opcode();
        return switch (ins) {
            case InvokeInstruction x -> op.name() + " " + x.owner().asInternalName() + "." + x.name().stringValue() + x.type().stringValue();
            case FieldInstruction x -> op.name() + " " + x.owner().asInternalName() + "." + x.name().stringValue() + " " + x.type().stringValue();
            case InvokeDynamicInstruction x -> op.name() + " " + x.name().stringValue() + x.type().stringValue() + " " + x.bootstrapArgs();
            case LoadInstruction x -> "LOAD " + x.typeKind() + " " + x.slot();
            case StoreInstruction x -> "STORE " + x.typeKind() + " " + x.slot();
            case IncrementInstruction x -> "IINC " + x.slot() + " " + x.constant();
            case BranchInstruction x -> op.name() + " " + rel(x.target(), self, at);
            case ConstantInstruction x -> "CONST " + x.constantValue();
            case NewObjectInstruction x -> "NEW " + x.className().asInternalName();
            case NewReferenceArrayInstruction x -> "ANEWARRAY " + x.componentType().asInternalName();
            case NewPrimitiveArrayInstruction x -> "NEWARRAY " + x.typeKind();
            case NewMultiArrayInstruction x -> "MULTIANEWARRAY " + x.arrayType().asInternalName() + " " + x.dimensions();
            case TypeCheckInstruction x -> op.name() + " " + x.type().asInternalName();
            case ReturnInstruction x -> op == Opcode.RETURN ? "RETURN" : op.name();
            case TableSwitchInstruction x -> "TABLESWITCH " + x.lowValue() + ".." + x.highValue() + " default " + rel(x.defaultTarget(), self, at) + " " + cases(x.cases(), self, at);
            case LookupSwitchInstruction x -> "LOOKUPSWITCH default " + rel(x.defaultTarget(), self, at) + " " + cases(x.cases(), self, at);
            default -> op.name();
        };
    }

    private static String rel(Label l, int self, Map<Label, Integer> at) {
        Integer t = at.get(l);
        return t == null ? "?" : (t - self >= 0 ? "+" : "") + (t - self);
    }

    private static String cases(List<SwitchCase> cs, int self, Map<Label, Integer> at) {
        StringBuilder sb = new StringBuilder("[");
        for (SwitchCase c : cs) sb.append(c.caseValue()).append(':').append(rel(c.target(), self, at)).append(' ');
        return sb.append(']').toString();
    }
}
