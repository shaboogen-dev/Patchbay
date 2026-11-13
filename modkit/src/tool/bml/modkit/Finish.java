package bml.modkit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.FieldModel;
import java.lang.classfile.FieldTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeInvisibleParameterAnnotationsAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The step after javac. Reads every {@code @Hook} and {@code @AddField}, checks it against the Bitwig jar the mod is
 * built against, and writes what the manifest needs to hooks.json. Then rewrites the classes in
 * place: {@code bitwig.obf} names back to Bitwig's own and the mod kit's annotations removed, so
 * the shipped helper.jar refers to Bitwig exactly as Bitwig names its classes.
 */
final class Finish {
    private static final String API = "Lbml/api/";
    private static final String HOOK = "Lbml/api/Hook;";
    private static final String ADD_FIELD = "Lbml/api/AddField;";
    private static final Map<String, String> TRANSFORMS = Map.of(
            "ENTRY", "INSERT_AT_ENTRY",
            "BEFORE_RETURN", "INSERT_BEFORE_FINAL_RETURN",
            "WRAP_RETURN", "WRAP_OBJECT_RETURN",
            "REPLACE_CALL", "REPLACE_INVOKE",
            "OVERRIDE_AFTER_SUPER", "ADD_METHOD",
            "OVERRIDE_BEFORE_SUPER", "ADD_METHOD",
            "OVERRIDE_INSTEAD", "ADD_METHOD");
    private static final Map<String, String> BODIES = Map.of(
            "OVERRIDE_AFTER_SUPER", "CALL_SUPER_THEN_STATIC",
            "OVERRIDE_BEFORE_SUPER", "CALL_STATIC_THEN_SUPER",
            "OVERRIDE_INSTEAD", "CALL_STATIC_ONLY");

    private final Ref ref;
    private final List<String> errors = new ArrayList<>();
    private final List<Map<String, Object>> hooks = new ArrayList<>();
    private final List<Map<String, Object>> fields = new ArrayList<>();
    /** The occurrence each REPLACE_CALL hook names in source, by hook, before the loader's renumbering. */
    private final Map<Map<String, Object>, Integer> sourceOccurrence = new IdentityHashMap<>();
    private final TreeSet<String> hookClasses = new TreeSet<>();
    private final TreeMap<String, Map<String, Object>> access = new TreeMap<>();

    private Finish(Ref ref) {
        this.ref = ref;
    }

    static boolean run(Path classes, Path hooksJson, Path[] jars) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.walk(classes)) {
            files = s.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        }
        try (Ref ref = new Ref(jars)) {
            Finish f = new Finish(ref);
            Map<Path, byte[]> rewritten = new LinkedHashMap<>();
            for (Path p : files) {
                byte[] in = Files.readAllBytes(p);
                byte[] out = f.process(in);
                if (out != in) rewritten.put(p, out);
            }
            if (!f.errors.isEmpty()) {
                f.errors.forEach(e -> System.err.println("MODKIT_ERROR " + e));
                return false;
            }
            for (var e : rewritten.entrySet()) Files.write(e.getKey(), e.getValue());
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("modkit", ModKit.VERSION);
            doc.put("hook_classes", new ArrayList<>(f.hookClasses));
            doc.put("hooks", f.hooks);
            doc.put("fields", f.fields);
            doc.put("access", new ArrayList<>(f.access.values()));
            Files.writeString(hooksJson, Json.write(doc) + "\n", StandardCharsets.UTF_8);
            System.out.println("MODKIT_FINISH_OK hooks=" + f.hooks.size() + (f.fields.isEmpty() ? "" : " fields=" + f.fields.size()) + " non_public=" + f.access.size()
                    + " classes_rewritten=" + rewritten.size());
            return true;
        }
    }

    private byte[] process(byte[] bytes) throws IOException {
        ClassModel cm = ClassFile.of().parse(bytes);
        String cls = cm.thisClass().asInternalName();
        boolean hasApi = false;
        boolean hasObf = false;
        for (PoolEntry e : cm.constantPool()) {
            if (e instanceof java.lang.classfile.constantpool.Utf8Entry u) {
                String s = u.stringValue();
                if (s.contains(API)) hasApi = true;
                if (s.contains(Names.OBF)) hasObf = true;
            }
            if (e instanceof StringEntry s && (s.stringValue().contains(Names.OBF) || s.stringValue().contains("bitwig.obf."))) {
                errors.add(dot(cls) + ": the string \"" + s.stringValue() + "\" names bitwig.obf, which exists only at "
                        + "compile time; use Bitwig's own name in strings");
            }
            noteAccess(cls, e);
        }
        for (MethodModel m : cm.methods()) {
            Optional<Annotation> hook = kitAnnotations(m).stream().filter(a -> a.className().equalsString(HOOK)).findFirst();
            if (hook.isPresent()) hook(cm, m, hook.get());
        }
        for (FieldModel f : cm.fields())
            kitAnnotations(f).stream().filter(a -> a.className().equalsString(ADD_FIELD)).findFirst().ifPresent(a -> addField(cm, f, a));
        byte[] out = bytes;
        if (hasApi) out = stripAnnotations(bytes, cm);
        if (hasObf) out = renameUtf8(out);
        return out;
    }

    // ---- hooks -------------------------------------------------------------------------------

    private void hook(ClassModel helper, MethodModel m, Annotation a) {
        String where = dot(helper.thisClass().asInternalName()) + "." + m.methodName().stringValue();
        Map<String, AnnotationValue> v = new LinkedHashMap<>();
        a.elements().forEach(e -> v.put(e.name().stringValue(), e.value()));
        String at = ((AnnotationValue.OfEnum) v.get("at")).constantName().stringValue();
        String target = Names.fromObf(Names.internal(((AnnotationValue.OfClass) v.get("target")).classSymbol()));
        String spec = ((AnnotationValue.OfString) v.get("method")).stringValue();
        String id = v.containsKey("id") ? ((AnnotationValue.OfString) v.get("id")).stringValue() : "";
        if (id.isEmpty()) id = kebab(m.methodName().stringValue());
        where += " (@Hook " + at + " " + target + "." + spec + ")";
        int flags = m.flags().flagsMask();
        if ((flags & ClassFile.ACC_STATIC) == 0 || (flags & ClassFile.ACC_PUBLIC) == 0) {
            errors.add(where + ": a hook method must be public static");
            return;
        }
        if ((helper.flags().flagsMask() & ClassFile.ACC_PUBLIC) == 0) {
            errors.add(where + ": the class holding hooks must be public");
            return;
        }
        Optional<ClassModel> tcls = ref.isBitwig(target) ? ref.find(target) : Optional.empty();
        if (tcls.isEmpty()) {
            errors.add(where + ": " + dot(target) + " is not a class in the Bitwig jar this mod is built against");
            return;
        }
        MethodTypeDesc hookType = MethodTypeDesc.ofDescriptor(Names.fromObf(m.methodTypeSymbol().descriptorString()));
        List<List<Annotation>> params = parameterAnnotations(m, hookType.parameterCount());

        Map<String, Object> h = new LinkedHashMap<>();
        h.put("id", id);
        h.put("transform", TRANSFORMS.get(at));
        h.put("helper_class", dot(helper.thisClass().asInternalName()));
        List<String> args = new ArrayList<>();
        int before = errors.size();
        if (at.startsWith("OVERRIDE_")) {
            Optional<MethodModel> own = find(tcls.get(), spec);
            if (own.isPresent()) {
                errors.add(where + ": " + dot(target) + " already declares " + spec + "; hook it with ENTRY, BEFORE_RETURN or WRAP_RETURN instead");
                return;
            }
            Optional<Map.Entry<ClassModel, MethodModel>> inherited = inherited(tcls.get(), spec, where);
            if (inherited.isEmpty()) return;
            MethodModel tm = inherited.get().getValue();
            MethodTypeDesc tt = tm.methodTypeSymbol();
            h.put("target", ordered("kind", "class", "class", target));
            h.put("method_from", physical(inherited.get().getKey().thisClass().asInternalName(), tm));
            h.put("body", BODIES.get(at));
            bindParams(where, at, target, false, tt, hookType, params, args);
            ClassDesc want = at.equals("OVERRIDE_INSTEAD") ? tt.returnType() : ClassDesc.ofDescriptor("V");
            if (!hookType.returnType().equals(want))
                errors.add(where + ": must return " + pretty(want) + " (returns " + pretty(hookType.returnType()) + ")");
        } else {
            Optional<MethodModel> found = resolve(tcls.get(), spec, where);
            if (found.isEmpty()) return;
            MethodModel tm = found.get();
            MethodTypeDesc tt = tm.methodTypeSymbol();
            boolean isStatic = (tm.flags().flagsMask() & ClassFile.ACC_STATIC) != 0;
            h.put("target", physical(target, tm));
            switch (at) {
                case "ENTRY", "BEFORE_RETURN" -> {
                    bindParams(where, at, target, isStatic, tt, hookType, params, args);
                    if (!hookType.returnType().descriptorString().equals("V"))
                        errors.add(where + ": must return void");
                }
                case "WRAP_RETURN" -> {
                    if (tt.returnType().isPrimitive() || tt.returnType().isArray())
                        errors.add(where + ": WRAP_RETURN needs a method that returns an object; " + spec + " returns " + pretty(tt.returnType()));
                    else if (hookType.parameterCount() == 0 || !has(params.get(0), "Return"))
                        errors.add(where + ": the first parameter must be marked @Return");
                    else {
                        if (!ref.assignable(tt.returnType(), hookType.parameterType(0)))
                            errors.add(where + ": @Return parameter is " + pretty(hookType.parameterType(0)) + " but the method returns " + pretty(tt.returnType()));
                        if (!ref.assignable(hookType.returnType(), tt.returnType()) && !hookType.returnType().descriptorString().equals("Ljava/lang/Object;"))
                            errors.add(where + ": must return " + pretty(tt.returnType()) + " or Object");
                        bindParams(where, at, target, isStatic, tt, hookType.dropParameterTypes(0, 1), params.subList(1, params.size()), args);
                    }
                }
                case "REPLACE_CALL" -> replaceCall(where, v, tm, hookType, params, h);
                default -> errors.add(where + ": unknown At." + at);
            }
        }
        if (errors.size() > before) return;
        h.put("bridge", ordered("method", m.methodName().stringValue(), "descriptor", hookType.descriptorString(), "args", args));
        hooks.add(h);
        hookClasses.add(dot(helper.thisClass().asInternalName()));
    }

    /** One @AddField. The loader decides at Load on the running build; this reports the same early, on the build's own jar. */
    private void addField(ClassModel helper, FieldModel f, Annotation a) {
        Map<String, AnnotationValue> v = new LinkedHashMap<>();
        a.elements().forEach(e -> v.put(e.name().stringValue(), e.value()));
        String owner = Names.fromObf(Names.internal(((AnnotationValue.OfClass) v.get("target")).classSymbol()));
        String name = ((AnnotationValue.OfString) v.get("name")).stringValue();
        String id = v.containsKey("id") ? ((AnnotationValue.OfString) v.get("id")).stringValue() : kebab(f.fieldName().stringValue());
        String where = dot(helper.thisClass().asInternalName()) + "." + f.fieldName().stringValue() + " (@AddField " + dot(owner) + "." + name + ")";
        int flags = f.flags().flagsMask();
        if ((flags & ClassFile.ACC_STATIC) == 0 || (flags & ClassFile.ACC_FINAL) == 0 || !f.fieldType().equalsString("Ljava/lang/invoke/VarHandle;")) {
            errors.add(where + ": @AddField goes on the static final VarHandle that reaches the field");
            return;
        }
        Optional<ClassModel> c = ref.isBitwig(owner) ? ref.find(owner) : Optional.empty();
        if (c.isEmpty()) {
            errors.add(where + ": " + dot(owner) + " is not a class in the Bitwig jar this mod is built against");
            return;
        }
        if ((c.get().flags().flagsMask() & (ClassFile.ACC_INTERFACE | ClassFile.ACC_ENUM)) != 0) errors.add(where + ": " + dot(owner) + " is an interface or an enum");
        for (ClassModel s : ref.hierarchy(owner)) {
            String n = s.thisClass().asInternalName();
            if (n.equals("java/io/Serializable") || n.equals("java/lang/Record") || n.equals("java/lang/Enum"))
                errors.add(where + ": " + dot(owner) + " is a " + n.substring(n.lastIndexOf('/') + 1) + "; BML can't add a field to it");
            if (s.fields().stream().anyMatch(x -> x.fieldName().equalsString(name))) errors.add(where + ": " + dot(n) + " already declares a field of that name");
        }
        String desc = Names.fromObf(((AnnotationValue.OfClass) v.get("type")).classSymbol().descriptorString());
        if (desc.equals("V")) errors.add(where + ": a field can't be void");
        String element = desc.substring(desc.lastIndexOf('[') + 1);
        if (element.startsWith("L") && !element.startsWith("Ljava/") && !ref.isBitwig(element.substring(1, element.length() - 1)))
            errors.add(where + ": the type must be a primitive, a JDK class or a Bitwig class; for the mod's own types use Object and cast");
        fields.add(ordered("id", id, "owner", ordered("kind", "class", "class", owner), "name", name, "descriptor", desc));
    }

    /** ENTRY / BEFORE_RETURN / WRAP_RETURN / OVERRIDE_*: each parameter is @This or @Arg(n). */
    private void bindParams(String where, String at, String target, boolean isStatic, MethodTypeDesc tt,
                            MethodTypeDesc hookType, List<List<Annotation>> params, List<String> args) {
        boolean refsOnly = at.equals("BEFORE_RETURN") || at.equals("WRAP_RETURN");
        for (int i = 0; i < hookType.parameterCount(); i++) {
            ClassDesc p = hookType.parameterType(i);
            List<Annotation> anns = params.get(i);
            if (has(anns, "This")) {
                if (isStatic) {
                    errors.add(where + ": @This on a static method");
                    continue;
                }
                if (!ref.assignable(ClassDesc.ofInternalName(target), p))
                    errors.add(where + ": @This parameter is " + pretty(p) + " but the object is " + dot(target));
                args.add("this");
            } else if (has(anns, "Arg")) {
                int n = ((AnnotationValue.OfInt) anns.stream().filter(x -> x.className().equalsString(API + "Arg;"))
                        .findFirst().orElseThrow().elements().get(0).value()).intValue();
                if (n < 0 || n >= tt.parameterCount()) {
                    errors.add(where + ": @Arg(" + n + ") but the method has " + tt.parameterCount() + " parameter(s)");
                    continue;
                }
                ClassDesc actual = tt.parameterType(n);
                if (refsOnly && actual.isPrimitive())
                    errors.add(where + ": " + at + " can pass object parameters only; @Arg(" + n + ") is " + pretty(actual));
                else if (!ref.assignable(actual, p))
                    errors.add(where + ": @Arg(" + n + ") is " + pretty(actual) + " in Bitwig but the parameter is " + pretty(p));
                int slot = isStatic ? 0 : 1;
                for (int k = 0; k < n; k++) slot += width(tt.parameterType(k));
                args.add("arg" + slot);
            } else {
                errors.add(where + ": parameter " + (i + 1) + " needs @This or @Arg(n)");
            }
        }
    }

    private void replaceCall(String where, Map<String, AnnotationValue> v, MethodModel tm, MethodTypeDesc hookType,
                             List<List<Annotation>> params, Map<String, Object> h) {
        if (!v.containsKey("callOwner") || !v.containsKey("call")) {
            errors.add(where + ": REPLACE_CALL needs callOwner and call");
            return;
        }
        String owner = Names.fromObf(Names.internal(((AnnotationValue.OfClass) v.get("callOwner")).classSymbol()));
        String call = ((AnnotationValue.OfString) v.get("call")).stringValue();
        int occurrence = v.containsKey("occurrence") ? ((AnnotationValue.OfInt) v.get("occurrence")).intValue() : 1;
        String name = call.contains("(") ? call.substring(0, call.indexOf('(')) : call;
        String desc = call.contains("(") ? call.substring(call.indexOf('(')) : null;
        List<InvokeInstruction> calls = new ArrayList<>();
        tm.code().ifPresent(code -> {
            for (CodeElement e : code)
                if (e instanceof InvokeInstruction ii && ii.owner().asInternalName().equals(owner)
                        && ii.name().equalsString(name) && (desc == null || ii.type().equalsString(desc)))
                    calls.add(ii);
        });
        if (calls.isEmpty()) {
            errors.add(where + ": no call to " + dot(owner) + "." + call + " in that method");
            return;
        }
        List<String> descs = calls.stream().map(c -> c.type().stringValue()).distinct().toList();
        if (descs.size() > 1) {
            errors.add(where + ": " + call + " is ambiguous; add the descriptor, e.g. call = \"" + name + descs.get(0) + "\". Candidates: " + descs);
            return;
        }
        List<String> ops = calls.stream().map(c -> c.opcode().name()).distinct().toList();
        if (ops.size() > 1) {
            errors.add(where + ": the calls use different instructions " + ops + "; not supported");
            return;
        }
        if (occurrence < 1 || occurrence > calls.size()) {
            errors.add(where + ": occurrence " + occurrence + " but the method has " + calls.size() + " matching call(s)");
            return;
        }
        String callDesc = calls.get(0).typeSymbol().descriptorString();
        // The loader applies a mod's hooks in manifest order and counts a call's occurrences after that
        // mod's earlier hooks, which took the calls they replaced. So the manifest names the call by its
        // position among the calls still there: the source occurrence minus the earlier hooks on the same
        // called method (owner, name and descriptor) whose source occurrence is lower.
        int emitted = occurrence;
        for (Map<String, Object> other : hooks) {
            Map<?, ?> m = other.get("transform").equals("REPLACE_INVOKE") ? (Map<?, ?>) other.get("match") : null;
            if (m == null || !other.get("target").equals(h.get("target")) || !m.get("owner").equals(owner)
                    || !m.get("name").equals(name) || !m.get("descriptor").equals(callDesc)) continue;
            int earlier = sourceOccurrence.get(other);
            if (earlier == occurrence) {
                errors.add(where + ": occurrence " + occurrence + " of " + name + " is already replaced by another hook");
                return;
            }
            if (earlier < occurrence) emitted--;
        }
        sourceOccurrence.put(h, occurrence);
        InvokeInstruction ii = calls.get(0);
        String op = ii.opcode().name();
        MethodTypeDesc ct = ii.typeSymbol();
        List<ClassDesc> stack = new ArrayList<>();
        if (!op.equals("INVOKESTATIC")) stack.add(ClassDesc.ofInternalName(owner));
        stack.addAll(ct.parameterList());
        for (List<Annotation> anns : params)
            if (!anns.isEmpty()) errors.add(where + ": REPLACE_CALL parameters take no @This/@Arg/@Return");
        if (hookType.parameterCount() != stack.size()) {
            errors.add(where + ": the call passes " + stack.stream().map(Finish::pretty).toList() + " (receiver first); the hook takes "
                    + hookType.parameterList().stream().map(Finish::pretty).toList());
            return;
        }
        for (int i = 0; i < stack.size(); i++)
            if (!ref.assignable(stack.get(i), hookType.parameterType(i)))
                errors.add(where + ": parameter " + (i + 1) + " is " + pretty(hookType.parameterType(i)) + " but the call passes " + pretty(stack.get(i)));
        ClassDesc ret = ct.returnType();
        if (!(hookType.returnType().equals(ret) || (!ret.isPrimitive() && ref.assignable(hookType.returnType(), ret))))
            errors.add(where + ": must return " + pretty(ret) + " like the call it replaces");
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("opcode", op);
        if (ref.isBitwig(owner)) match.put("target", ordered("kind", "method", "owner", owner, "name", name, "descriptor", ct.descriptorString()));
        match.put("owner", owner);
        match.put("name", name);
        match.put("descriptor", ct.descriptorString());
        match.put("occurrence", emitted);
        h.put("match", match);
    }

    private Optional<MethodModel> resolve(ClassModel c, String spec, String where) {
        String name = spec.contains("(") ? spec.substring(0, spec.indexOf('(')) : spec;
        List<MethodModel> named = c.methods().stream().filter(m -> m.methodName().equalsString(name)).toList();
        List<MethodModel> hits = spec.contains("(") ? named.stream().filter(m -> m.methodType().equalsString(spec.substring(name.length()))).toList() : named;
        if (hits.size() == 1) return Optional.of(hits.get(0));
        String cls = dot(c.thisClass().asInternalName());
        if (hits.isEmpty() && named.isEmpty()) {
            List<String> up = ref.hierarchy(c.thisClass().asInternalName()).stream().skip(1)
                    .filter(s -> s.methods().stream().anyMatch(m -> m.methodName().equalsString(name)))
                    .map(s -> dot(s.thisClass().asInternalName())).toList();
            errors.add(where + ": " + cls + " declares no method " + name + (up.isEmpty() ? "" : "; it is declared in " + up
                    + " (hook that class, or use an OVERRIDE_ hook to add it here)"));
        } else if (hits.isEmpty()) {
            errors.add(where + ": " + cls + " has no " + spec + "; candidates: " + candidates(named));
        } else {
            errors.add(where + ": " + cls + "." + name + " is overloaded; add the descriptor, e.g. method = \"" + name + hits.get(0).methodType().stringValue() + "\". Candidates: " + candidates(hits));
        }
        return Optional.empty();
    }

    private Optional<MethodModel> find(ClassModel c, String spec) {
        String name = spec.contains("(") ? spec.substring(0, spec.indexOf('(')) : spec;
        return c.methods().stream().filter(m -> m.methodName().equalsString(name)
                && (!spec.contains("(") || m.methodType().equalsString(spec.substring(name.length())))).findFirst();
    }

    private Optional<Map.Entry<ClassModel, MethodModel>> inherited(ClassModel c, String spec, String where) {
        String name = spec.contains("(") ? spec.substring(0, spec.indexOf('(')) : spec;
        List<Map.Entry<ClassModel, MethodModel>> hits = new ArrayList<>();
        for (ClassModel s : ref.hierarchy(c.thisClass().asInternalName()).stream().skip(1).toList())
            for (MethodModel m : s.methods())
                if (m.methodName().equalsString(name) && (!spec.contains("(") || m.methodType().equalsString(spec.substring(name.length())))
                        && (m.flags().flagsMask() & (ClassFile.ACC_STATIC | ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL)) == 0
                        && hits.stream().noneMatch(x -> x.getValue().methodType().equalsString(m.methodType().stringValue())))
                    hits.add(Map.entry(s, m));
        if (hits.size() == 1) return Optional.of(hits.get(0));
        if (hits.isEmpty()) errors.add(where + ": no overridable " + spec + " is inherited by " + dot(c.thisClass().asInternalName()));
        else errors.add(where + ": " + name + " is overloaded; add the descriptor, e.g. method = \"" + name
                + hits.get(0).getValue().methodType().stringValue() + "\". Candidates: " + candidates(hits.stream().map(Map.Entry::getValue).toList()));
        return Optional.empty();
    }

    private static String candidates(List<MethodModel> ms) {
        return ms.stream().map(m -> m.methodName().stringValue() + m.methodType().stringValue()).collect(Collectors.joining(", "));
    }

    private static Map<String, Object> physical(String owner, MethodModel m) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("kind", "method");
        t.put("owner", owner);
        t.put("name", m.methodName().stringValue());
        t.put("descriptor", m.methodType().stringValue());
        return t;
    }

    // ---- non-public Bitwig members the code uses ----------------------------------------------

    private void noteAccess(String cls, PoolEntry e) {
        if (e instanceof ClassEntry c) {
            String n = Names.fromObf(c.asInternalName());
            while (n.startsWith("[")) n = n.substring(1);
            if (n.startsWith("L")) n = n.substring(1, n.length() - 1);
            if (n.length() > 1 && ref.isBitwig(n)) {
                ClassModel cm = ref.find(n).orElseThrow();
                if ((cm.flags().flagsMask() & ClassFile.ACC_PUBLIC) == 0)
                    access.putIfAbsent("C " + n, ordered("kind", "class", "class", n));
            }
        } else if (e instanceof MemberRefEntry m) {
            String owner = Names.fromObf(m.owner().asInternalName());
            if (owner.startsWith("[") || !ref.isBitwig(owner)) return;
            String name = m.name().stringValue();
            String desc = Names.fromObf(m.type().stringValue());
            boolean field = m instanceof FieldRefEntry;
            Optional<Ref.Member> r = field ? ref.field(owner, name, desc) : ref.method(owner, name, desc);
            if (r.isEmpty()) {
                errors.add(dot(cls) + ": " + dot(owner) + "." + name + " " + desc + " is not in the Bitwig jar");
                return;
            }
            if ((r.get().flags() & ClassFile.ACC_PUBLIC) == 0) {
                String decl = r.get().owner().thisClass().asInternalName();
                access.putIfAbsent((field ? "F " : "M ") + decl + " " + name + " " + desc,
                        ordered("kind", field ? "field" : "method", "owner", decl, "name", name, "descriptor", desc));
            }
        }
    }

    // ---- class rewriting ---------------------------------------------------------------------

    private static List<Annotation> kitAnnotations(MethodModel m) {
        return m.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(RuntimeInvisibleAnnotationsAttribute::annotations)
                .orElse(List.of()).stream().filter(a -> a.className().stringValue().startsWith(API)).toList();
    }

    private static List<Annotation> kitAnnotations(FieldModel f) {
        return f.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(RuntimeInvisibleAnnotationsAttribute::annotations)
                .orElse(List.of()).stream().filter(a -> a.className().stringValue().startsWith(API)).toList();
    }

    private static List<List<Annotation>> parameterAnnotations(MethodModel m, int count) {
        List<List<Annotation>> out = new ArrayList<>();
        List<List<Annotation>> raw = m.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                .map(RuntimeInvisibleParameterAnnotationsAttribute::parameterAnnotations).orElse(List.of());
        for (int i = 0; i < count; i++)
            out.add(i < raw.size() ? raw.get(i).stream().filter(a -> a.className().stringValue().startsWith(API)).toList() : List.of());
        return out;
    }

    private static boolean has(List<Annotation> anns, String simple) {
        return anns.stream().anyMatch(a -> a.className().equalsString(API + simple + ";"));
    }

    /**
     * Removes the kit's annotations by cutting their attributes out of the class bytes, so
     * everything javac wrote (code, stack maps, attribute order) stays as it was. Their names stay
     * behind as unused constant-pool strings, which nothing reads. A method that mixes the kit's
     * annotations with other invisible annotations is rewritten with the ClassFile API instead.
     */
    private static byte[] stripAnnotations(byte[] data, ClassModel cm) throws IOException {
        List<Boolean> kitOnly = new ArrayList<>();
        boolean mixed = false;
        for (MethodModel m : cm.methods()) {
            List<Annotation> all = new ArrayList<>(m.findAttribute(Attributes.runtimeInvisibleAnnotations())
                    .map(RuntimeInvisibleAnnotationsAttribute::annotations).orElse(List.of()));
            m.findAttribute(Attributes.runtimeInvisibleParameterAnnotations())
                    .ifPresent(a -> a.parameterAnnotations().forEach(all::addAll));
            long kit = all.stream().filter(x -> x.className().stringValue().startsWith(API)).count();
            kitOnly.add(kit > 0 && kit == all.size());
            mixed |= kit > 0 && kit < all.size();
        }
        List<Boolean> fieldKitOnly = new ArrayList<>();
        boolean fieldAnnotations = false;
        for (FieldModel f : cm.fields()) {
            int all = f.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(a -> a.annotations().size()).orElse(0);
            int kit = kitAnnotations(f).size();
            fieldKitOnly.add(kit > 0 && kit == all);
            fieldAnnotations |= kit > 0;
            mixed |= kit > 0 && kit < all;
        }
        if (mixed) return stripMixed(cm, fieldAnnotations);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        ByteArrayOutputStream bout = new ByteArrayOutputStream(data.length);
        DataOutputStream out = new DataOutputStream(bout);
        out.write(in.readNBytes(8));
        int count = in.readUnsignedShort();
        out.writeShort(count);
        Map<Integer, String> utf8 = new java.util.HashMap<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            out.writeByte(tag);
            switch (tag) {
                case 1 -> {
                    String v = in.readUTF();
                    utf8.put(i, v);
                    out.writeUTF(v);
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> out.write(in.readNBytes(4));
                case 5, 6 -> {
                    out.write(in.readNBytes(8));
                    i++;
                }
                case 7, 8, 16, 19, 20 -> out.write(in.readNBytes(2));
                case 15 -> out.write(in.readNBytes(3));
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        out.write(in.readNBytes(6)); // access, this, super
        int interfaces = in.readUnsignedShort();
        out.writeShort(interfaces);
        out.write(in.readNBytes(2 * interfaces));
        for (int kind = 0; kind < 2; kind++) { // fields, then methods
            int members = in.readUnsignedShort();
            out.writeShort(members);
            for (int k = 0; k < members; k++) {
                out.write(in.readNBytes(6));
                int attrs = in.readUnsignedShort();
                List<byte[]> keep = new ArrayList<>();
                for (int a = 0; a < attrs; a++) {
                    int name = in.readUnsignedShort();
                    int len = in.readInt();
                    byte[] body = in.readNBytes(len);
                    String n = utf8.get(name);
                    if (kind == 1 && kitOnly.get(k) && ("RuntimeInvisibleAnnotations".equals(n) || "RuntimeInvisibleParameterAnnotations".equals(n)))
                        continue;
                    if (kind == 0 && fieldKitOnly.get(k) && "RuntimeInvisibleAnnotations".equals(n)) continue;
                    ByteArrayOutputStream one = new ByteArrayOutputStream();
                    DataOutputStream o = new DataOutputStream(one);
                    o.writeShort(name);
                    o.writeInt(len);
                    o.write(body);
                    keep.add(one.toByteArray());
                }
                out.writeShort(keep.size());
                for (byte[] b : keep) out.write(b);
            }
        }
        out.write(in.readAllBytes()); // class attributes
        return bout.toByteArray();
    }

    private static byte[] stripMixed(ClassModel cm, boolean fieldAnnotations) {
        MethodTransform mt = (mb, e) -> {
            if (e instanceof RuntimeInvisibleAnnotationsAttribute a) {
                List<Annotation> keep = a.annotations().stream().filter(x -> !x.className().stringValue().startsWith(API)).toList();
                if (!keep.isEmpty()) mb.with(RuntimeInvisibleAnnotationsAttribute.of(keep));
            } else if (e instanceof RuntimeInvisibleParameterAnnotationsAttribute a) {
                List<List<Annotation>> keep = a.parameterAnnotations().stream()
                        .map(l -> l.stream().filter(x -> !x.className().stringValue().startsWith(API)).toList()).toList();
                if (keep.stream().anyMatch(l -> !l.isEmpty())) mb.with(RuntimeInvisibleParameterAnnotationsAttribute.of(keep));
            } else {
                mb.with(e);
            }
        };
        FieldTransform ft = (fb, e) -> {
            if (e instanceof RuntimeInvisibleAnnotationsAttribute a) {
                List<Annotation> keep = a.annotations().stream().filter(x -> !x.className().stringValue().startsWith(API)).toList();
                if (!keep.isEmpty()) fb.with(RuntimeInvisibleAnnotationsAttribute.of(keep));
            } else {
                fb.with(e);
            }
        };
        ClassTransform t = ClassTransform.transformingMethods(mt);
        return ClassFile.of().transformClass(cm, fieldAnnotations ? t.andThen(ClassTransform.transformingFields(ft)) : t);
    }

    /**
     * Every class name, descriptor and signature lives in a CONSTANT_Utf8 entry, so removing the
     * bitwig/obf/ prefix there renames every reference. String literals naming bitwig.obf were
     * rejected earlier, so no string constant is changed.
     */
    static byte[] renameUtf8(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        ByteArrayOutputStream bout = new ByteArrayOutputStream(data.length);
        DataOutputStream out = new DataOutputStream(bout);
        out.write(in.readNBytes(8));
        int count = in.readUnsignedShort();
        out.writeShort(count);
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            out.writeByte(tag);
            switch (tag) {
                case 1 -> out.writeUTF(Names.fromObf(in.readUTF()));
                case 3, 4, 9, 10, 11, 12, 17, 18 -> out.write(in.readNBytes(4));
                case 5, 6 -> {
                    out.write(in.readNBytes(8));
                    i++;
                }
                case 7, 8, 16, 19, 20 -> out.write(in.readNBytes(2));
                case 15 -> out.write(in.readNBytes(3));
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        out.write(in.readAllBytes());
        return bout.toByteArray();
    }

    // ---- small helpers -----------------------------------------------------------------------

    /** A map that keeps its keys in the order given, so hooks.json is the same on every run. */
    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static int width(ClassDesc d) {
        String s = d.descriptorString();
        return s.equals("J") || s.equals("D") ? 2 : 1;
    }

    private static String dot(String internal) {
        return internal.replace('/', '.');
    }

    private static String pretty(ClassDesc d) {
        return Names.fromObf(d.isPrimitive() || d.isArray() ? d.displayName() : Names.internal(d)).replace('/', '.');
    }

    static String kebab(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }
}
