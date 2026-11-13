package bml.modkit;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;

/**
 * Moving Bitwig's unnamed-package classes into {@value #OBF} and back. A class is in the unnamed
 * package when its internal name has no '/'.
 */
final class Names {
    static final String OBF = "bitwig/obf/";

    private Names() {
    }

    static String toObf(String internal) {
        return internal.indexOf('/') < 0 ? OBF + internal : internal;
    }

    static ClassDesc toObf(ClassDesc d) {
        if (d.isArray()) return toObf(d.componentType()).arrayType();
        if (d.isPrimitive()) return d;
        return ClassDesc.ofInternalName(toObf(internal(d)));
    }

    static MethodTypeDesc toObf(MethodTypeDesc m) {
        ClassDesc[] params = m.parameterList().stream().map(Names::toObf).toArray(ClassDesc[]::new);
        return MethodTypeDesc.of(toObf(m.returnType()), params);
    }

    /** Back to Bitwig's names, in any internal name, descriptor or signature. */
    static String fromObf(String s) {
        return s.replace(OBF, "");
    }

    static String internal(ClassDesc d) {
        String desc = d.descriptorString();
        return desc.substring(1, desc.length() - 1);
    }

    /** Remaps the class names in a generic signature (JVMS 4.7.9.1). */
    static String toObfSignature(String sig) {
        StringBuilder out = new StringBuilder(sig.length() + 32);
        new SigWriter(sig, out).any();
        return out.toString();
    }

    /** A small recursive-descent copy of a signature that rewrites each top-level class name. */
    private static final class SigWriter {
        private final String s;
        private final StringBuilder out;
        private int i;

        SigWriter(String s, StringBuilder out) {
            this.s = s;
            this.out = out;
        }

        void any() {
            if (peek() == '<') typeParams();
            if (peek() == '(') {
                take(); // '('
                while (peek() != ')') type();
                take(); // ')'
                type(); // result, 'V' included
                while (i < s.length() && peek() == '^') {
                    take();
                    type();
                }
            } else {
                while (i < s.length()) type();
            }
        }

        private void typeParams() {
            take(); // '<'
            while (peek() != '>') {
                while (peek() != ':') take(); // identifier
                while (i < s.length() && peek() == ':') {
                    take();
                    char c = peek();
                    if (c == 'L' || c == 'T' || c == '[') type();
                }
            }
            take(); // '>'
        }

        private void type() {
            char c = take();
            switch (c) {
                case 'L' -> classBody();
                case 'T' -> { while (take() != ';') { } }
                case '[' -> type();
                default -> { } // base type or V
            }
        }

        private void classBody() {
            int start = i;
            while (peek() != '<' && peek() != ';' && peek() != '.') i++;
            String name = s.substring(start, i);
            out.append(toObf(name));
            while (true) {
                char c = take();
                if (c == ';') return;
                if (c == '<') {
                    while (peek() != '>') {
                        char a = peek();
                        if (a == '*') take();
                        else {
                            if (a == '+' || a == '-') take();
                            type();
                        }
                    }
                    take(); // '>'
                } else if (c == '.') {
                    while (peek() != '<' && peek() != ';' && peek() != '.') take(); // inner simple name
                }
            }
        }

        private char peek() {
            return s.charAt(i);
        }

        private char take() {
            char c = s.charAt(i++);
            out.append(c);
            return c;
        }
    }
}
