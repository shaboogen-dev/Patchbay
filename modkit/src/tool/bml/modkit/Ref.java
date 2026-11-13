package bml.modkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Class lookup in the Bitwig jar (the first jar), further jars, then the running JDK. */
final class Ref implements AutoCloseable {
    private final List<ZipFile> jars = new ArrayList<>();
    private final Map<String, Optional<ClassModel>> cache = new HashMap<>();

    Ref(Path[] paths) throws IOException {
        for (Path p : paths) jars.add(new ZipFile(p.toFile()));
    }

    /** True when the class is Bitwig's own (in the first jar). */
    boolean isBitwig(String internal) {
        return jars.get(0).getEntry(internal + ".class") != null;
    }

    Optional<ClassModel> find(String internal) {
        return cache.computeIfAbsent(internal, n -> {
            try {
                for (ZipFile z : jars) {
                    ZipEntry e = z.getEntry(n + ".class");
                    if (e != null) {
                        try (InputStream in = z.getInputStream(e)) {
                            return Optional.of(ClassFile.of().parse(in.readAllBytes()));
                        }
                    }
                }
                try (InputStream in = ClassLoader.getSystemResourceAsStream(n + ".class")) {
                    return in == null ? Optional.empty() : Optional.of(ClassFile.of().parse(in.readAllBytes()));
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    /** The class and all its supertypes, nearest first. Unknown supertypes are skipped. */
    List<ClassModel> hierarchy(String internal) {
        List<ClassModel> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(internal));
        while (!todo.isEmpty()) {
            String n = todo.removeFirst();
            if (!seen.add(n)) continue;
            Optional<ClassModel> cm = find(n);
            if (cm.isEmpty()) continue;
            out.add(cm.get());
            cm.get().superclass().ifPresent(s -> todo.addLast(s.asInternalName()));
            cm.get().interfaces().forEach(i -> todo.addLast(i.asInternalName()));
        }
        return out;
    }

    /** Can a value of type {@code actual} be passed where {@code wanted} is declared? */
    boolean assignable(ClassDesc actual, ClassDesc wanted) {
        if (actual.equals(wanted)) return true;
        if (actual.isPrimitive() || wanted.isPrimitive()) return false;
        if (wanted.descriptorString().equals("Ljava/lang/Object;")) return true;
        if (actual.isArray() || wanted.isArray()) return false;
        String w = Names.internal(wanted);
        return hierarchy(Names.internal(actual)).stream().anyMatch(c -> c.thisClass().asInternalName().equals(w));
    }

    record Member(ClassModel owner, int flags) {
    }

    /** JVM field resolution: the class, its interfaces, then superclasses. */
    Optional<Member> field(String owner, String name, String desc) {
        for (ClassModel c : hierarchy(owner))
            for (FieldModel f : c.fields())
                if (f.fieldName().equalsString(name) && f.fieldType().equalsString(desc))
                    return Optional.of(new Member(c, f.flags().flagsMask()));
        return Optional.empty();
    }

    /** JVM method resolution (simplified): the class and its supertypes, nearest first. */
    Optional<Member> method(String owner, String name, String desc) {
        for (ClassModel c : hierarchy(owner))
            for (MethodModel m : c.methods())
                if (m.methodName().equalsString(name) && m.methodType().equalsString(desc))
                    return Optional.of(new Member(c, m.flags().flagsMask()));
        return Optional.empty();
    }

    @Override
    public void close() throws IOException {
        for (ZipFile z : jars) z.close();
    }
}
