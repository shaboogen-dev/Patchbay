package bml.modkit;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.attribute.EnclosingMethodAttribute;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.PermittedSubclassesAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The jar mods compile against. It is generated from the Bitwig jar on the modder's own machine
 * and is never shipped: it holds signatures only (no method bodies), every class and member is
 * public, and classes from the unnamed package live in {@code bitwig.obf}.
 */
final class CompileJar {
    private static final int VISIBILITY = ClassFile.ACC_PUBLIC | ClassFile.ACC_PRIVATE | ClassFile.ACC_PROTECTED;
    private static final FileTime FIXED_TIME = FileTime.fromMillis(1767225600000L); // 2026-01-01T00:00:00Z

    private CompileJar() {
    }

    static void write(Path bitwigJar, Path out) throws IOException {
        ClassFile cf = ClassFile.of();
        List<String> names = new ArrayList<>();
        try (ZipFile in = new ZipFile(bitwigJar.toFile())) {
            in.stream().map(ZipEntry::getName)
                    .filter(n -> n.endsWith(".class") && !n.endsWith("module-info.class") && !n.startsWith("META-INF/"))
                    .sorted().forEach(names::add);
            Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
            Files.createDirectories(out.toAbsolutePath().getParent());
            int count = 0;
            try (OutputStream os = Files.newOutputStream(tmp); ZipOutputStream zip = new ZipOutputStream(os)) {
                for (String name : names) {
                    ClassModel cm = cf.parse(in.getInputStream(in.getEntry(name)).readAllBytes());
                    byte[] bytes = strip(cf, cm);
                    ZipEntry e = new ZipEntry(Names.toObf(cm.thisClass().asInternalName()) + ".class");
                    e.setLastModifiedTime(FIXED_TIME);
                    zip.putNextEntry(e);
                    zip.write(bytes);
                    zip.closeEntry();
                    count++;
                }
            }
            Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("COMPILE_JAR_OK classes=" + count + " out=" + out);
        }
    }

    private static int open(int flags) {
        return (flags & ~VISIBILITY) | ClassFile.ACC_PUBLIC;
    }

    /**
     * Members are also un-synthesized so javac shows them: obfuscation leaves real state in
     * synthetic fields (e.g. an inner class's outer instance). Bridge methods stay synthetic, or
     * javac would see two methods with the same erasure.
     */
    private static int openMember(int flags) {
        int f = open(flags);
        return (f & ClassFile.ACC_BRIDGE) != 0 ? f : f & ~ClassFile.ACC_SYNTHETIC;
    }

    private static byte[] strip(ClassFile cf, ClassModel cm) {
        ClassDesc self = Names.toObf(cm.thisClass().asSymbol());
        return cf.build(self, cb -> {
            cb.withVersion(cm.majorVersion(), cm.minorVersion());
            cb.withFlags(open(cm.flags().flagsMask()));
            cm.superclass().ifPresent(s -> cb.withSuperclass(Names.toObf(s.asSymbol())));
            cb.withInterfaceSymbols(cm.interfaces().stream().map(i -> Names.toObf(i.asSymbol())).toList());
            cm.findAttribute(Attributes.signature()).ifPresent(a ->
                    cb.with(SignatureAttribute.of(cb.constantPool().utf8Entry(Names.toObfSignature(a.signature().stringValue())))));
            cm.findAttribute(Attributes.innerClasses()).ifPresent(a -> cb.with(InnerClassesAttribute.of(
                    a.classes().stream().map(CompileJar::openInner).toList())));
            cm.findAttribute(Attributes.enclosingMethod()).ifPresent(a -> cb.with(EnclosingMethodAttribute.of(
                    Names.toObf(a.enclosingClass().asSymbol()),
                    a.enclosingMethodName().map(n -> n.stringValue()),
                    a.enclosingMethodType().map(t -> Names.toObf(MethodTypeDesc.ofDescriptor(t.stringValue()))))));
            cm.findAttribute(Attributes.permittedSubclasses()).ifPresent(a -> cb.with(PermittedSubclassesAttribute.ofSymbols(
                    a.permittedSubclasses().stream().map(c -> Names.toObf(c.asSymbol())).toList())));
            cm.findAttribute(Attributes.record()).ifPresent(a -> cb.with(RecordAttribute.of(
                    a.components().stream().map(c -> RecordComponentInfo.of(c.name().stringValue(),
                            Names.toObf(ClassDesc.ofDescriptor(c.descriptor().stringValue())),
                            c.findAttribute(Attributes.signature()).map(s -> List.<java.lang.classfile.Attribute<?>>of(
                                    SignatureAttribute.of(cb.constantPool().utf8Entry(Names.toObfSignature(s.signature().stringValue())))))
                                    .orElse(List.of()))).toList())));
            for (FieldModel f : cm.fields()) {
                cb.withField(f.fieldName().stringValue(), Names.toObf(f.fieldTypeSymbol()), fb -> {
                    fb.withFlags(openMember(f.flags().flagsMask()));
                    f.findAttribute(Attributes.constantValue()).ifPresent(c -> fb.with(ConstantValueAttribute.of(c.constant().constantValue())));
                    f.findAttribute(Attributes.signature()).ifPresent(s ->
                            fb.with(SignatureAttribute.of(fb.constantPool().utf8Entry(Names.toObfSignature(s.signature().stringValue())))));
                });
            }
            for (MethodModel m : cm.methods()) {
                cb.withMethod(m.methodName().stringValue(), Names.toObf(m.methodTypeSymbol()), openMember(m.flags().flagsMask()), mb -> {
                    m.findAttribute(Attributes.signature()).ifPresent(s ->
                            mb.with(SignatureAttribute.of(mb.constantPool().utf8Entry(Names.toObfSignature(s.signature().stringValue())))));
                    m.findAttribute(Attributes.exceptions()).ifPresent(x -> mb.with(ExceptionsAttribute.ofSymbols(
                            x.exceptions().stream().map(e -> Names.toObf(e.asSymbol())).toList())));
                });
            }
        });
    }

    private static InnerClassInfo openInner(InnerClassInfo i) {
        return InnerClassInfo.of(Names.toObf(i.innerClass().asSymbol()),
                i.outerClass().map(ClassEntry::asSymbol).map(Names::toObf),
                i.innerName().map(n -> n.stringValue()),
                open(i.flagsMask()));
    }
}
