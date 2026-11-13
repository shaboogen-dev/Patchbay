import com.sun.source.tree.*;
import com.sun.source.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.*;
import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Ports Java source written against one Bitwig version's obfuscated names to another version.
 *
 * The source is compiled (analysed, not generated) against the FROM classpath with javac, so every
 * identifier is resolved to the exact class, method or field it refers to. Only symbols that the
 * BML remap knows are renamed, at their exact source positions. Nothing is text-matched.
 *
 * Handled: type references (simple, qualified, imports, nested), constructors, method calls and
 * method references, field accesses, and declarations in the ported code that override a mapped
 * Bitwig method. Reflection strings are handled only in the unambiguous form
 *   SomeMappedClass.class.getDeclared{Field,Method}("name", ...)  /  get{Field,Method}
 * and every other string literal that looks like a FROM-version obfuscated member name is reported
 * for manual review.
 *
 * Mod kit source (modkit/README.md) is ported too: classes the compile jar shows as bitwig.obf.X are
 * looked up as X and written back with the prefix, and the Bitwig methods named in @Hook strings
 * (method, call; with or without a descriptor) are renamed like any member.
 *
 * usage: java SourcePorter.java --cp <from-classpath> --map <remap.tsv.gz> [--invert]
 *                               --src <dir> --out <dir>
 *   --map is a BML remap (rows: C old new / F,M owner name desc newOwner newName newDesc).
 *   --invert when the map's direction is TO->FROM (e.g. port 6.1.2 -> 6.1.3 using 6.1.3__6.1.2).
 * Exit code 0 = ported with no unresolved reports; 2 = ported but review items were reported.
 */
public final class SourcePorter {
    record Member(String owner, String name, String desc) {}

    final Map<String, String> classes = new HashMap<>();       // internal name -> internal name
    final Map<Member, Member> members = new HashMap<>();       // F and M rows
    final Map<String, List<Member>> membersByOwnerName = new HashMap<>(); // for reflection strings
    final Set<String> fromMemberNames = new HashSet<>();
    static final String OBF = "bitwig/obf/";  // where the mod kit's compile jar puts unnamed-package classes
    int renamed, reviewed;

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--invert")) a.put("invert", "1");
            else a.put(args[i].substring(2), args[++i]);
        }
        for (String k : List.of("cp", "map", "src", "out"))
            if (!a.containsKey(k)) throw new IllegalArgumentException("missing --" + k);
        SourcePorter p = new SourcePorter();
        p.loadMap(Path.of(a.get("map")), a.containsKey("invert"));
        int status = p.port(a.get("cp"), Path.of(a.get("src")), Path.of(a.get("out")));
        System.exit(status);
    }

    void loadMap(Path map, boolean invert) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(map)), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("#")) continue;
                String[] c = line.split("\t");
                switch (c[0]) {
                    case "C" -> { if (invert) classes.put(c[2], c[1]); else classes.put(c[1], c[2]); }
                    case "F", "M" -> {
                        Member x = new Member(c[1], c[2], c[3]), y = new Member(c[4], c[5], c[6]);
                        Member from = invert ? y : x, to = invert ? x : y;
                        members.put(from, to);
                        membersByOwnerName.computeIfAbsent(from.owner + "." + from.name, k -> new ArrayList<>()).add(from);
                        fromMemberNames.add(from.name);
                    }
                    default -> { }
                }
            }
        }
        System.err.printf("map: %,d classes, %,d members%s%n", classes.size(), members.size(), invert ? " (inverted)" : "");
    }

    int port(String cp, Path src, Path out) throws IOException {
        src = src.toAbsolutePath().normalize();  // javac reports absolute file URIs
        List<Path> files;
        try (var s = Files.walk(src)) { files = s.filter(f -> f.toString().endsWith(".java")).sorted().toList(); }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StandardJavaFileManager fm = javac.getStandardFileManager(diags, null, StandardCharsets.UTF_8);
        JavacTask task = (JavacTask) javac.getTask(null, fm, diags,
                List.of("-proc:none", "--release", "21", "-cp", cp, "-encoding", "UTF-8"), null,
                fm.getJavaFileObjectsFromPaths(files));
        Iterable<? extends CompilationUnitTree> units = task.parse();
        task.analyze();
        boolean errors = false;
        for (var d : diags.getDiagnostics())
            if (d.getKind() == Diagnostic.Kind.ERROR) { errors = true; System.err.println("FROM-COMPILE ERROR " + d); }
        if (errors) { System.err.println("Source does not compile against the FROM classpath; nothing written."); return 1; }

        Trees trees = Trees.instance(task);
        Elements elements = task.getElements();
        Types types = task.getTypes();
        for (CompilationUnitTree unit : units) {
            String text = unit.getSourceFile().getCharContent(true).toString();
            List<int[]> spans = new ArrayList<>();
            List<String> repl = new ArrayList<>();
            new Visitor(trees, elements, types, unit, text, spans, repl).scan(unit, null);
            // apply right-to-left; drop exact duplicate spans
            Integer[] order = new Integer[spans.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (x, y) -> Integer.compare(spans.get(y)[0], spans.get(x)[0]));
            StringBuilder sb = new StringBuilder(text);
            int lastStart = Integer.MAX_VALUE;
            for (int i : order) {
                int[] s = spans.get(i);
                if (s[1] > lastStart) continue; // overlapping (already replaced by an enclosing edit)
                sb.replace(s[0], s[1], repl.get(i));
                lastStart = s[0];
            }
            Path rel = src.relativize(Path.of(unit.getSourceFile().toUri()));
            Path dest = out.resolve(rel);
            Files.createDirectories(dest.getParent() == null ? out : dest.getParent());
            Files.writeString(dest, sb.toString(), StandardCharsets.UTF_8);
        }
        System.err.printf("ported %d files: %d renames, %d items for review%n", files.size(), renamed, reviewed);
        return reviewed == 0 ? 0 : 2;
    }

    final class Visitor extends TreePathScanner<Void, Void> {
        final Trees trees; final Elements el; final Types types; final CompilationUnitTree unit; final String text;
        final List<int[]> spans; final List<String> repl; final SourcePositions pos;

        Visitor(Trees trees, Elements el, Types types, CompilationUnitTree unit, String text, List<int[]> spans, List<String> repl) {
            this.trees = trees; this.el = el; this.types = types; this.unit = unit; this.text = text;
            this.spans = spans; this.repl = repl; this.pos = trees.getSourcePositions();
        }

        String internal(TypeElement t) {
            String n = el.getBinaryName(t).toString().replace('.', '/');
            return n.startsWith(OBF) ? n.substring(OBF.length()) : n;
        }

        /** A mapped class as a qualified source name; unnamed-package classes keep the kit's prefix. */
        String qualified(String newInternal, boolean kit) {
            String n = (kit && newInternal.indexOf('/') < 0 ? OBF : "") + newInternal;
            return n.replace('/', '.').replace('$', '.');
        }

        String desc(TypeMirror t) {
            t = types.erasure(t);
            return switch (t.getKind()) {
                case BOOLEAN -> "Z"; case BYTE -> "B"; case CHAR -> "C"; case SHORT -> "S"; case INT -> "I";
                case LONG -> "J"; case FLOAT -> "F"; case DOUBLE -> "D"; case VOID -> "V";
                case ARRAY -> "[" + desc(((ArrayType) t).getComponentType());
                case DECLARED -> "L" + internal((TypeElement) ((DeclaredType) t).asElement()) + ";";
                case TYPEVAR -> desc(((TypeVariable) t).getUpperBound());
                default -> throw new IllegalStateException("type " + t);
            };
        }

        String methodDesc(ExecutableElement m) {
            StringBuilder b = new StringBuilder("(");
            for (VariableElement p : m.getParameters()) b.append(desc(p.asType()));
            return b.append(")").append(m.getKind() == ElementKind.CONSTRUCTOR ? "V" : desc(m.getReturnType())).toString();
        }

        void edit(long start, long end, String replacement) {
            if (start < 0 || end <= start) return;
            if (text.substring((int) start, (int) end).equals(replacement)) return;
            spans.add(new int[]{(int) start, (int) end}); repl.add(replacement); renamed++;
        }

        void review(Tree t, String why) {
            long line = unit.getLineMap().getLineNumber(pos.getStartPosition(unit, t));
            System.err.printf("REVIEW %s:%d %s%n", Path.of(unit.getSourceFile().toUri()).getFileName(), line, why);
            reviewed++;
        }

        /** New simple (source) name for a mapped class, e.g. a/b/Outer$Inner -> Inner'. */
        String newSimple(String newInternal) {
            String s = newInternal.substring(newInternal.lastIndexOf('/') + 1);
            return s.substring(s.lastIndexOf('$') + 1);
        }

        String mappedClass(Element e) {
            if (e instanceof TypeElement t) return classes.get(internal(t));
            return null;
        }

        Member mappedMember(Element e) {
            if (!(e.getEnclosingElement() instanceof TypeElement owner)) return null;
            String o = internal(owner);
            if (e instanceof ExecutableElement m) {
                if (m.getKind() == ElementKind.CONSTRUCTOR) return null; // follows the class rename
                Member hit = members.get(new Member(o, m.getSimpleName().toString(), methodDesc(m)));
                if (hit != null) return hit;
                // inherited through an unmapped override chain: look for a mapped overridden method
                for (TypeMirror sup : types.directSupertypes(owner.asType())) {
                    Member h = overriddenMapped(m, (TypeElement) types.asElement(sup));
                    if (h != null) return h;
                }
                return null;
            }
            if (e instanceof VariableElement v && (v.getKind() == ElementKind.FIELD || v.getKind() == ElementKind.ENUM_CONSTANT))
                return members.get(new Member(o, v.getSimpleName().toString(), desc(v.asType())));
            return null;
        }

        Member overriddenMapped(ExecutableElement m, TypeElement type) {
            if (type == null) return null;
            for (Element x : type.getEnclosedElements())
                if (x instanceof ExecutableElement cand && cand.getSimpleName().equals(m.getSimpleName())
                        && el.overrides(m, cand, (TypeElement) m.getEnclosingElement())) {
                    Member hit = members.get(new Member(internal(type), cand.getSimpleName().toString(), methodDesc(cand)));
                    if (hit != null) return hit;
                }
            for (TypeMirror sup : types.directSupertypes(type.asType())) {
                Member h = overriddenMapped(m, (TypeElement) types.asElement(sup));
                if (h != null) return h;
            }
            return null;
        }

        void renameName(Tree t, Name name, String newName) {
            long end = pos.getEndPosition(unit, t);
            edit(end - name.length(), end, newName);
        }

        @Override public Void visitIdentifier(IdentifierTree t, Void v) {
            Element e = trees.getElement(getCurrentPath());
            if (e != null) {
                String c = mappedClass(e);
                if (c != null) renameName(t, t.getName(), newSimple(c));
                else { Member m = mappedMember(e); if (m != null) renameName(t, t.getName(), m.name()); }
            }
            return super.visitIdentifier(t, v);
        }

        @Override public Void visitMemberSelect(MemberSelectTree t, Void v) {
            Element e = trees.getElement(getCurrentPath());
            if (e != null) {
                String c = mappedClass(e);
                if (c != null) {
                    // qualified type name (e.g. an import): the package may change too
                    Element q = trees.getElement(new TreePath(getCurrentPath(), t.getExpression()));
                    if (q instanceof PackageElement pk) {
                        boolean kit = pk.getQualifiedName().toString().equals("bitwig.obf");
                        edit(pos.getStartPosition(unit, t), pos.getEndPosition(unit, t), qualified(c, kit));
                        return null;
                    }
                    renameName(t, t.getIdentifier(), newSimple(c));
                } else {
                    Member m = mappedMember(e);
                    if (m != null) renameName(t, t.getIdentifier(), m.name());
                }
            }
            return super.visitMemberSelect(t, v);
        }

        @Override public Void visitMemberReference(MemberReferenceTree t, Void v) {
            Element e = trees.getElement(getCurrentPath());
            Member m = e == null ? null : mappedMember(e);
            if (m != null) renameName(t, t.getName(), m.name());
            return super.visitMemberReference(t, v);
        }

        @Override public Void visitMethod(MethodTree t, Void v) {
            Element e = trees.getElement(getCurrentPath());
            if (e instanceof ExecutableElement m && m.getKind() == ElementKind.METHOD) {
                TypeElement owner = (TypeElement) m.getEnclosingElement();
                if (classes.get(internal(owner)) == null) { // our own class overriding a Bitwig method
                    Member hit = null;
                    for (TypeMirror sup : types.directSupertypes(owner.asType())) {
                        hit = overriddenMapped(m, (TypeElement) types.asElement(sup));
                        if (hit != null) break;
                    }
                    if (hit != null && !hit.name().equals(t.getName().toString())) {
                        // the name token follows the return type; find it after the return type's end
                        long from = t.getReturnType() == null ? pos.getStartPosition(unit, t) : pos.getEndPosition(unit, t.getReturnType());
                        int at = text.indexOf(t.getName().toString(), (int) from);
                        if (at >= 0) edit(at, at + t.getName().length(), hit.name());
                    }
                }
            }
            return super.visitMethod(t, v);
        }

        /** @Hook(target = X.class, method = "name[desc]", callOwner = Y.class, call = "name[desc]"). */
        @Override public Void visitAnnotation(AnnotationTree t, Void v) {
            Element type = trees.getElement(new TreePath(getCurrentPath(), t.getAnnotationType()));
            if (type instanceof TypeElement te && te.getQualifiedName().contentEquals("bml.api.Hook")) {
                Map<String, ExpressionTree> args = new HashMap<>();
                for (ExpressionTree a : t.getArguments())
                    if (a instanceof AssignmentTree as && as.getVariable() instanceof IdentifierTree id) args.put(id.getName().toString(), as.getExpression());
                portHookMethod(args.get("target"), args.get("method"));
                portHookMethod(args.get("callOwner"), args.get("call"));
            }
            return super.visitAnnotation(t, v);
        }

        void portHookMethod(ExpressionTree owner, ExpressionTree spec) {
            if (owner == null || !(spec instanceof LiteralTree lit) || !(lit.getValue() instanceof String s)) return;
            if (!(owner instanceof MemberSelectTree cls) || !cls.getIdentifier().contentEquals("class")) return;
            Element o = trees.getElement(new TreePath(getCurrentPath(), cls.getExpression()));
            if (!(o instanceof TypeElement te)) return;
            String name = s.contains("(") ? s.substring(0, s.indexOf('(')) : s;
            String desc = s.contains("(") ? s.substring(name.length()) : null;
            // Declared or inherited (OVERRIDE_ hooks name an inherited method), as the kit resolves it.
            Set<String> ported = new TreeSet<>();
            for (Element e : el.getAllMembers(te)) {
                if (!(e instanceof ExecutableElement m) || !m.getSimpleName().contentEquals(name)) continue;
                String md = methodDesc(m);
                if (desc != null && !md.equals(desc)) continue;
                Member hit = mappedMember(m);
                if (hit == null) continue;
                ported.add(hit.name() + (desc == null ? "" : hit.desc()));
            }
            if (ported.size() == 1) {
                edit(pos.getStartPosition(unit, lit), pos.getEndPosition(unit, lit), "\"" + ported.iterator().next() + "\"");
            } else if (ported.size() > 1) {
                review(lit, "@Hook method \"" + s + "\" on " + internal(te) + " maps to several methods " + ported + "; add the descriptor");
            } else if (fromMemberNames.contains(name)) {
                review(lit, "@Hook method \"" + s + "\" on " + internal(te) + " is not in the map; port by hand");
            }
        }

        @Override public Void visitMethodInvocation(MethodInvocationTree t, Void v) {
            // SomeMappedClass.class.getDeclaredField("x") and friends
            if (t.getMethodSelect() instanceof MemberSelectTree sel && !t.getArguments().isEmpty()
                    && t.getArguments().get(0) instanceof LiteralTree lit && lit.getValue() instanceof String s) {
                String call = sel.getIdentifier().toString();
                boolean field = call.equals("getDeclaredField") || call.equals("getField");
                boolean method = call.equals("getDeclaredMethod") || call.equals("getMethod");
                if ((field || method) && sel.getExpression() instanceof MemberSelectTree cls && cls.getIdentifier().contentEquals("class")) {
                    Element owner = trees.getElement(new TreePath(new TreePath(getCurrentPath(), sel), cls.getExpression()));
                    if (owner instanceof TypeElement te) {
                        List<Member> cands = membersByOwnerName.getOrDefault(internal(te) + "." + s, List.of()).stream()
                                .filter(x -> field ? !x.desc().startsWith("(") : x.desc().startsWith("(")).toList();
                        Set<String> newNames = new HashSet<>();
                        for (Member c : cands) newNames.add(members.get(c).name());
                        if (newNames.size() == 1) {
                            edit(pos.getStartPosition(unit, lit), pos.getEndPosition(unit, lit), "\"" + newNames.iterator().next() + "\"");
                            return super.visitMethodInvocation(t, v);
                        } else if (newNames.size() > 1) review(lit, "ambiguous reflective name \"" + s + "\" on " + internal(te) + " -> " + newNames);
                    }
                }
            }
            return super.visitMethodInvocation(t, v);
        }

        @Override public Void visitLiteral(LiteralTree t, Void v) {
            // A string that is exactly a descriptor of a mapped class ("LAbc;", "[LAbc;") is
            // unambiguous: port it. One that mentions such a descriptor inside other text is reported.
            if (t.getValue() instanceof String s) {
                java.util.regex.Matcher d = java.util.regex.Pattern.compile("L([A-Za-z0-9_$/]+);").matcher(s);
                if (s.matches("\\[*L[A-Za-z0-9_$/]+;") && d.find() && classes.containsKey(d.group(1))) {
                    edit(pos.getStartPosition(unit, t), pos.getEndPosition(unit, t),
                            "\"" + s.substring(0, d.start(1)) + classes.get(d.group(1)) + ";\"");
                    return super.visitLiteral(t, v);
                }
                d.reset();
                while (d.find())
                    if (classes.containsKey(d.group(1)) && !classes.get(d.group(1)).equals(d.group(1)))
                        review(t, "string literal mentions descriptor L" + d.group(1) + "; port by hand if it is more than text");
            }
            // A string holding a fully-qualified mapped class name ("com.bitwig.x.Abc", for
            // Class.forName and similar) is unambiguous: port it.
            if (t.getValue() instanceof String s && s.indexOf('.') > 0 && !s.contains(" ")) {
                String mapped = classes.get(s.replace('.', '/'));
                if (mapped != null) {
                    edit(pos.getStartPosition(unit, t), pos.getEndPosition(unit, t), "\"" + mapped.replace('/', '.') + "\"");
                    return super.visitLiteral(t, v);
                }
            }
            if (t.getValue() instanceof String s && s.length() <= 4 && fromMemberNames.contains(s) && !s.isBlank()) {
                // ignore literals already handled by visitMethodInvocation (they were edited)
                long start = pos.getStartPosition(unit, t);
                boolean handled = false;
                for (int[] sp : spans) if (sp[0] == start) { handled = true; break; }
                if (!handled) review(t, "string literal \"" + s + "\" looks like an obfuscated name; port by hand");
            }
            return super.visitLiteral(t, v);
        }
    }
}
