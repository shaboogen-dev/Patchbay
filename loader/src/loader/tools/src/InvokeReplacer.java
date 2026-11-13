import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Generic classfile transform: replace one matched invocation with a static
 * bridge call. This implementation intentionally uses no ASM or JDK-internal
 * APIs, so it is independent of the JDK's bundled bytecode libraries and can
 * preserve newer classfile versions verbatim.
 */
public final class InvokeReplacer {
    private static final int INVOKEVIRTUAL   = 0xb6;
    private static final int INVOKESPECIAL   = 0xb7;
    private static final int INVOKESTATIC    = 0xb8;
    private static final int INVOKEINTERFACE = 0xb9;
    // Opt-in, fail-closed resolution for a unique instance callsite by method descriptor.
    private static final int AUTO_INSTANCE = -1;

    private static final class Cp {
        final int tag;
        String utf8;
        int a, b;
        Cp(int tag) { this.tag = tag; }
    }

    private static int u1(byte[] b, int p) { return b[p] & 0xff; }
    private static int u2(byte[] b, int p) { return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff); }
    private static long u4(byte[] b, int p) {
        return ((long)(b[p] & 0xff) << 24) | ((long)(b[p+1] & 0xff) << 16) |
               ((long)(b[p+2] & 0xff) << 8) | (long)(b[p+3] & 0xff);
    }
    private static void putU2(byte[] b, int p, int v) { b[p] = (byte)(v >>> 8); b[p+1] = (byte)v; }
    private static void writeU2(DataOutputStream out, int v) throws IOException { out.writeByte(v >>> 8); out.writeByte(v); }

    private static final class ParsedCp {
        final Cp[] cp;
        final int end;
        ParsedCp(Cp[] cp, int end) { this.cp = cp; this.end = end; }
    }

    private static ParsedCp parseCp(byte[] bytes) throws IOException {
        if (bytes.length < 10 || u4(bytes, 0) != 0xCAFEBABEL) throw new IOException("Invalid classfile");
        int count = u2(bytes, 8);
        Cp[] cp = new Cp[count];
        int p = 10;
        for (int i = 1; i < count; i++) {
            int tag = u1(bytes, p++);
            Cp e = new Cp(tag);
            cp[i] = e;
            switch (tag) {
                case 1 -> {
                    int len = u2(bytes, p); p += 2;
                    if (p + len > bytes.length) throw new EOFException("Truncated UTF8 constant");
                    e.utf8 = new String(bytes, p, len, StandardCharsets.UTF_8);
                    p += len;
                }
                case 3, 4 -> p += 4;
                case 5, 6 -> { p += 8; i++; }
                case 7, 8, 16, 19, 20 -> { e.a = u2(bytes, p); p += 2; }
                case 9, 10, 11, 12, 17, 18 -> { e.a = u2(bytes, p); e.b = u2(bytes, p+2); p += 4; }
                case 15 -> { e.a = u1(bytes, p); e.b = u2(bytes, p+1); p += 3; }
                default -> throw new IOException("Unsupported constant-pool tag " + tag + " at index " + i);
            }
            if (p > bytes.length) throw new EOFException("Truncated constant pool");
        }
        return new ParsedCp(cp, p);
    }

    private static String utf(Cp[] cp, int idx) {
        if (idx <= 0 || idx >= cp.length || cp[idx] == null || cp[idx].tag != 1)
            throw new IllegalArgumentException("Expected CONSTANT_Utf8 at #" + idx);
        return cp[idx].utf8;
    }
    private static String className(Cp[] cp, int idx) {
        if (idx <= 0 || idx >= cp.length || cp[idx] == null || cp[idx].tag != 7)
            throw new IllegalArgumentException("Expected CONSTANT_Class at #" + idx);
        return utf(cp, cp[idx].a);
    }

    private static final class MemberRef {
        final String owner, name, desc;
        MemberRef(String owner, String name, String desc) { this.owner = owner; this.name = name; this.desc = desc; }
    }
    private static MemberRef memberRef(Cp[] cp, int idx) {
        if (idx <= 0 || idx >= cp.length || cp[idx] == null || (cp[idx].tag != 10 && cp[idx].tag != 11))
            throw new IllegalArgumentException("Expected method reference at #" + idx);
        Cp r = cp[idx];
        String owner = className(cp, r.a);
        Cp nt = cp[r.b];
        if (nt == null || nt.tag != 12) throw new IllegalArgumentException("Expected NameAndType at #" + r.b);
        return new MemberRef(owner, utf(cp, nt.a), utf(cp, nt.b));
    }

    private static int opcode(String s) {
        return switch (s) {
            case "INVOKEVIRTUAL" -> INVOKEVIRTUAL;
            case "INVOKESPECIAL" -> INVOKESPECIAL;
            case "INVOKESTATIC" -> INVOKESTATIC;
            case "INVOKEINTERFACE" -> INVOKEINTERFACE;
            case "AUTO_INSTANCE" -> AUTO_INSTANCE;
            default -> throw new IllegalArgumentException("Unsupported invoke opcode: " + s);
        };
    }

    private static int typeSlots(String d, int[] pos) {
        int p = pos[0];
        while (d.charAt(p) == '[') p++;
        char c = d.charAt(p++);
        if (c == 'L') {
            int semi = d.indexOf(';', p);
            if (semi < 0) throw new IllegalArgumentException("Bad descriptor: " + d);
            p = semi + 1;
            pos[0] = p;
            return 1;
        }
        pos[0] = p;
        return (c == 'J' || c == 'D') ? 2 : 1;
    }
    private static int argSlots(String desc) {
        if (desc.length() < 3 || desc.charAt(0) != '(') throw new IllegalArgumentException("Bad method descriptor: " + desc);
        int[] p = {1};
        int slots = 0;
        while (desc.charAt(p[0]) != ')') slots += typeSlots(desc, p);
        return slots;
    }
    private static String returnDesc(String desc) {
        int p = desc.indexOf(')');
        if (p < 0 || p + 1 >= desc.length()) throw new IllegalArgumentException("Bad method descriptor: " + desc);
        return desc.substring(p + 1);
    }
    private static void validateStackShape(int originalOpcode, String originalDesc, String bridgeDesc) {
        if (!returnDesc(originalDesc).equals(returnDesc(bridgeDesc)))
            throw new IllegalArgumentException("Bridge return type must exactly match invocation return type: " + returnDesc(originalDesc) + " vs " + returnDesc(bridgeDesc));
        int oldSlots = argSlots(originalDesc) + (originalOpcode == INVOKESTATIC ? 0 : 1);
        int newSlots = argSlots(bridgeDesc);
        if (oldSlots != newSlots)
            throw new IllegalArgumentException("Bridge stack-slot count mismatch: original consumes " + oldSlots + ", bridge consumes " + newSlots);
    }

    private static int skipAttributes(byte[] b, int p, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            if (p + 6 > b.length) throw new EOFException("Truncated attribute");
            long len = u4(b, p + 2);
            if (len > Integer.MAX_VALUE) throw new IOException("Attribute too large");
            p += 6 + (int)len;
            if (p > b.length) throw new EOFException("Truncated attribute body");
        }
        return p;
    }
    private static int skipMember(byte[] b, int p) throws IOException {
        if (p + 8 > b.length) throw new EOFException("Truncated member");
        int ac = u2(b, p + 6);
        return skipAttributes(b, p + 8, ac);
    }

    private static int fixedLen(int op) {
        return switch (op) {
            case 0x10,0x12,0x15,0x16,0x17,0x18,0x19,0x36,0x37,0x38,0x39,0x3a,0xa9,0xbc -> 2;
            case 0x11,0x13,0x14,0x84,
                 0x99,0x9a,0x9b,0x9c,0x9d,0x9e,0x9f,0xa0,0xa1,0xa2,0xa3,0xa4,0xa5,0xa6,0xa7,0xa8,
                 0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xbb,0xbd,0xc0,0xc1,0xc6,0xc7 -> 3;
            case 0xc5 -> 4;
            case 0xb9,0xba,0xc8,0xc9 -> 5;
            default -> 1;
        };
    }
    private static int s4(byte[] b, int p) {
        return (b[p] << 24) | ((b[p+1] & 0xff) << 16) | ((b[p+2] & 0xff) << 8) | (b[p+3] & 0xff);
    }
    private static int insnLen(byte[] code, int pc) throws IOException {
        int op = u1(code, pc);
        if (op == 0xaa) { // tableswitch
            int q = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
            if (q + 12 > code.length) throw new EOFException("Truncated tableswitch");
            int low = s4(code, q + 4), high = s4(code, q + 8);
            long n = (long)high - low + 1;
            long len = (long)(q - pc) + 12 + 4L * n;
            if (n < 0 || len > Integer.MAX_VALUE || pc + len > code.length) throw new IOException("Invalid tableswitch");
            return (int)len;
        }
        if (op == 0xab) { // lookupswitch
            int q = pc + 1 + ((4 - ((pc + 1) & 3)) & 3);
            if (q + 8 > code.length) throw new EOFException("Truncated lookupswitch");
            int n = s4(code, q + 4);
            long len = (long)(q - pc) + 8 + 8L * n;
            if (n < 0 || len > Integer.MAX_VALUE || pc + len > code.length) throw new IOException("Invalid lookupswitch");
            return (int)len;
        }
        if (op == 0xc4) { // wide
            if (pc + 2 > code.length) throw new EOFException("Truncated wide");
            int sub = u1(code, pc + 1);
            return sub == 0x84 ? 6 : 4;
        }
        return fixedLen(op);
    }

    private static final class MatchLocation {
        final int codeOffset; // absolute offset in original class bytes
        final int pc;
        final int opcode;
        final String identity;
        MatchLocation(int codeOffset, int pc, int opcode, String identity) { this.codeOffset = codeOffset; this.pc = pc; this.opcode = opcode; this.identity = identity; }
    }

    private static MatchLocation findMatch(byte[] bytes, ParsedCp parsed,
                                           String targetMethod, String targetDesc,
                                           int matchOpcode, String matchOwner, String matchName, String matchDesc,
                                           int occurrence) throws IOException {
        Cp[] cp = parsed.cp;
        int p = parsed.end;
        if (p + 8 > bytes.length) throw new EOFException("Truncated class header");
        p += 6; // access, this, super
        int ic = u2(bytes, p); p += 2 + 2 * ic;
        int fc = u2(bytes, p); p += 2;
        for (int i = 0; i < fc; i++) p = skipMember(bytes, p);
        int mc = u2(bytes, p); p += 2;
        boolean targetSeen = false;
        int seen = 0;
        List<MatchLocation> sameName = new ArrayList<>();
        List<MatchLocation> sameDescriptor = new ArrayList<>();
        List<String> inspected = new ArrayList<>();
        for (int i = 0; i < mc; i++) {
            if (p + 8 > bytes.length) throw new EOFException("Truncated method");
            String mName = utf(cp, u2(bytes, p + 2));
            String mDesc = utf(cp, u2(bytes, p + 4));
            int ac = u2(bytes, p + 6);
            p += 8;
            boolean isTarget = mName.equals(targetMethod) && mDesc.equals(targetDesc);
            if (isTarget) {
                if (targetSeen) throw new IOException("Duplicate target method unexpectedly found: " + targetMethod + targetDesc);
                targetSeen = true;
            }
            for (int ai = 0; ai < ac; ai++) {
                if (p + 6 > bytes.length) throw new EOFException("Truncated method attribute");
                String aName = utf(cp, u2(bytes, p));
                long aLenL = u4(bytes, p + 2);
                if (aLenL > Integer.MAX_VALUE) throw new IOException("Attribute too large");
                int aLen = (int)aLenL;
                int body = p + 6;
                if (body + aLen > bytes.length) throw new EOFException("Truncated method attribute body");
                if (isTarget && aName.equals("Code")) {
                    if (aLen < 8) throw new IOException("Invalid Code attribute");
                    int codeLen = (int)u4(bytes, body + 4);
                    int codeOff = body + 8;
                    if (codeOff + codeLen > body + aLen) throw new IOException("Invalid Code length");
                    byte[] code = Arrays.copyOfRange(bytes, codeOff, codeOff + codeLen);
                    for (int pc = 0; pc < code.length; ) {
                        int op = u1(code, pc);
                        if (op == INVOKEVIRTUAL || op == INVOKESPECIAL || op == INVOKESTATIC || op == INVOKEINTERFACE) {
                            if (pc + 3 > code.length) throw new EOFException("Truncated invoke instruction");
                            int cpIndex = u2(code, pc + 1);
                            MemberRef r = memberRef(cp, cpIndex);
                            String identity = "opcode=" + op + " " + r.owner + "." + r.name + r.desc + " pc=" + pc;
                            if (inspected.size() < 50) inspected.add(identity);
                            if (matchOpcode == AUTO_INSTANCE) {
                                if (op != INVOKESTATIC && r.desc.equals(matchDesc)) {
                                    MatchLocation loc = new MatchLocation(codeOff, pc, op, identity);
                                    sameDescriptor.add(loc);
                                    if (r.name.equals(matchName)) sameName.add(loc);
                                }
                            } else if (op == matchOpcode && r.owner.equals(matchOwner) && r.name.equals(matchName) && r.desc.equals(matchDesc)) {
                                seen++;
                                if (seen == occurrence) return new MatchLocation(codeOff, pc, op, identity);
                            }
                        }
                        int len = insnLen(code, pc);
                        if (len <= 0 || pc + len > code.length) throw new IOException("Invalid bytecode instruction length at pc=" + pc + " opcode=" + op);
                        pc += len;
                    }
                }
                p = body + aLen;
            }
        }
        if (!targetSeen) throw new IOException("Target method not found: " + targetMethod + targetDesc);
        if (matchOpcode == AUTO_INSTANCE) {
            // The physical JVM stack traces identify a *resolved* method, not
            // necessarily the symbolic owner/opcode used by the callsite.
            // Choose by name + descriptor if unique, otherwise descriptor alone
            // only if unique. Never guess between multiple compatible sites.
            if (occurrence != 1) throw new IOException("AUTO_INSTANCE requires occurrence=1");
            if (sameName.size() == 1) return sameName.get(0);
            if (sameName.size() > 1) throw new IOException("Ambiguous AUTO_INSTANCE name+descriptor matches: " +
                    sameName.stream().map(x -> x.identity).toList());
            if (sameDescriptor.size() == 1) return sameDescriptor.get(0);
            throw new IOException("AUTO_INSTANCE expected exactly one compatible call: " + matchOwner + "." + matchName +
                matchDesc + "; found " + sameDescriptor.size() + " candidate(s): " +
                sameDescriptor.stream().map(x -> x.identity).toList() + "; inspected=" + inspected);
        }
        throw new IOException("Invocation match not found: opcode=" + matchOpcode + " " + matchOwner + "." + matchName + matchDesc + " occurrence=" + occurrence + " (matches=" + seen + "); inspected=" + inspected);
    }

    private static byte[] cpUtf8(String s) throws IOException {
        byte[] u = s.getBytes(StandardCharsets.UTF_8);
        if (u.length > 65535) throw new IOException("UTF8 constant too long");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.writeByte(1); writeU2(out, u.length); out.write(u); out.flush();
        return bo.toByteArray();
    }
    private static byte[] cpClass(int nameIndex) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bo);
        out.writeByte(7); writeU2(out, nameIndex); out.flush(); return bo.toByteArray();
    }
    private static byte[] cpNameType(int name, int desc) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bo);
        out.writeByte(12); writeU2(out, name); writeU2(out, desc); out.flush(); return bo.toByteArray();
    }
    private static byte[] cpMethodRef(int ownerClass, int nameType) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bo);
        out.writeByte(10); writeU2(out, ownerClass); writeU2(out, nameType); out.flush(); return bo.toByteArray();
    }

    private static byte[] patch(byte[] original,
                                String targetMethod, String targetDesc,
                                int matchOpcode, String matchOwner, String matchName, String matchDesc,
                                int occurrence,
                                String bridgeOwner, String bridgeName, String bridgeDesc) throws Exception {
        if (occurrence < 1) throw new IllegalArgumentException("occurrence must be >= 1");
        if (matchOpcode != AUTO_INSTANCE) validateStackShape(matchOpcode, matchDesc, bridgeDesc);
        ParsedCp parsed = parseCp(original);
        MatchLocation loc = findMatch(original, parsed, targetMethod, targetDesc, matchOpcode, matchOwner, matchName, matchDesc, occurrence);
        if (matchOpcode == AUTO_INSTANCE) System.out.println("AUTO_INSTANCE_RESOLVED " + loc.identity);
        validateStackShape(loc.opcode, matchDesc, bridgeDesc);

        int oldCount = parsed.cp.length;
        if (oldCount + 6 > 65535) throw new IOException("Constant pool full");
        int ownerUtf = oldCount;
        int ownerCls = oldCount + 1;
        int nameUtf  = oldCount + 2;
        int descUtf  = oldCount + 3;
        int nt       = oldCount + 4;
        int methodRef= oldCount + 5;

        ByteArrayOutputStream add = new ByteArrayOutputStream();
        add.write(cpUtf8(bridgeOwner));
        add.write(cpClass(ownerUtf));
        add.write(cpUtf8(bridgeName));
        add.write(cpUtf8(bridgeDesc));
        add.write(cpNameType(nameUtf, descUtf));
        add.write(cpMethodRef(ownerCls, nt));
        byte[] added = add.toByteArray();

        byte[] mutated = original.clone();
        int invoke = loc.codeOffset + loc.pc;
        mutated[invoke] = (byte)INVOKESTATIC;
        putU2(mutated, invoke + 1, methodRef);
        if (loc.opcode == INVOKEINTERFACE) {
            // invokeinterface is 5 bytes; invokestatic is 3. Preserve all following
            // bytecode offsets by turning the two trailing operand bytes into NOPs.
            mutated[invoke + 3] = 0;
            mutated[invoke + 4] = 0;
        }

        ByteArrayOutputStream bo = new ByteArrayOutputStream(mutated.length + added.length);
        bo.write(mutated, 0, 8);
        DataOutputStream out = new DataOutputStream(bo);
        writeU2(out, oldCount + 6);
        bo.write(mutated, 10, parsed.end - 10);
        bo.write(added);
        bo.write(mutated, parsed.end, mutated.length - parsed.end);
        out.flush();
        return bo.toByteArray();
    }

    /*
     * Constrained REPLACE_INVOKE composition.  Two or more static bridges with
     * exactly the invocation descriptor may be composed when the final
     * reference argument and reference return descriptor are identical.
     * Each bridge receives the original immutable arguments and the previous
     * bridge's result as the final argument.  No original site is executed
     * twice, and the original invocation stack shape is preserved.
     *
     * The adapter is inserted as a private synthetic static method in the
     * transformed owner, avoiding class loader policy changes or helper ABI
     * dependencies. All branches and bytecode length changes are inside the
     * newly added branch-free method; existing method offsets are untouched.
     */
    private static List<String> parameters(String descriptor) throws IOException {
        if (!descriptor.startsWith("(")) throw new IOException("Invalid method descriptor: " + descriptor);
        List<String> parts = new ArrayList<>();
        int p = 1;
        while (p < descriptor.length() && descriptor.charAt(p) != ')') {
            int begin = p;
            while (p < descriptor.length() && descriptor.charAt(p) == '[') p++;
            if (p >= descriptor.length()) throw new IOException("Invalid descriptor: " + descriptor);
            char type = descriptor.charAt(p++);
            if (type == 'L') {
                int end = descriptor.indexOf(';', p);
                if (end < 0) throw new IOException("Invalid object descriptor: " + descriptor);
                p = end + 1;
            } else if ("BCDFIJSZ".indexOf(type) < 0) throw new IOException("Unsupported parameter type: " + type);
            parts.add(descriptor.substring(begin, p));
        }
        if (p >= descriptor.length() || descriptor.charAt(p) != ')') throw new IOException("Invalid descriptor: " + descriptor);
        return parts;
    }
    private static boolean chainBridgeCompatible(String invocationDesc, String bridgeDesc) throws IOException {
        if (!returnDesc(invocationDesc).equals(returnDesc(bridgeDesc))) return false;
        List<String> original = parameters(invocationDesc);
        List<String> bridge = parameters(bridgeDesc);
        if (original.size() != bridge.size()) return false;
        for (int i = 0; i < original.size(); i++) {
            String supplied = original.get(i), accepted = bridge.get(i);
            if (supplied.equals(accepted)) continue;
            // Java reference types (including arrays) always widen to Object.
            if (!(supplied.startsWith("L") || supplied.startsWith("[")) ||
                !accepted.equals("Ljava/lang/Object;")) return false;
        }
        return true;
    }
    private static int width(String type) { return type.equals("J") || type.equals("D") ? 2 : 1; }
    private static void localLoad(ByteArrayOutputStream code, String type, int slot) {
        int op = switch (type.charAt(0)) {
            case 'J' -> 0x16;
            case 'D' -> 0x18;
            case 'F' -> 0x17;
            case 'L', '[' -> 0x19;
            default -> 0x15;
        };
        if (slot <= 255) { code.write(op); code.write(slot); }
        else { code.write(0xc4); code.write(op); code.write(slot >>> 8); code.write(slot); }
    }
    private static byte[] chainPatch(byte[] original, String targetMethod, String targetDesc,
                                     int matchOpcode, String matchOwner, String matchName, String matchDesc,
                                     int occurrence, List<String[]> bridges) throws Exception {
        if (matchOpcode != INVOKESTATIC || bridges.size() < 2)
            throw new IOException("REPLACE_INVOKE composition currently requires 2+ bridges for an INVOKESTATIC site");
        List<String> parts = parameters(matchDesc);
        String ret = returnDesc(matchDesc);
        if (parts.isEmpty() || !ret.equals(parts.get(parts.size()-1)) ||
            !(ret.startsWith("L") || ret.startsWith("[")))
            throw new IOException("Unsafe REPLACE_INVOKE composition: return must be identical to the last reference parameter: " + matchDesc);
        // A helper may deliberately accept an Object instead of a narrower
        // reference type. The original caller still supplies its verified JVM
        // types, so widening to java.lang.Object is verifier-safe. Reject any
        // other descriptor substitution (including return-type changes).
        for (String[] bridge : bridges)
            if (!chainBridgeCompatible(matchDesc, bridge[2]))
                throw new IOException("Unsafe REPLACE_INVOKE composition: bridge " + bridge[0] + "." + bridge[1] + bridge[2] +
                    " is not reference-widening compatible with " + matchDesc +
                    " (only an identical argument or reference-to-Object argument is supported; return must match exactly)");
        ParsedCp parsed = parseCp(original);
        MatchLocation loc = findMatch(original,parsed,targetMethod,targetDesc,matchOpcode,matchOwner,matchName,matchDesc,occurrence);
        Cp[] cp = parsed.cp;
        int p = parsed.end;
        int flags = u2(original,p);
        if ((flags & 0x0200) != 0) throw new IOException("Cannot add a synthetic composition method to interface");
        int thisClass = u2(original,p+2);
        p += 6;
        int interfaces=u2(original,p);p+=2+2*interfaces;
        int fields=u2(original,p);p+=2;
        for(int i=0;i<fields;i++)p=skipMember(original,p);
        int methodsCountAt=p;
        int existing=u2(original,p);p+=2;
        for(int i=0;i<existing;i++)p=skipMember(original,p);
        int methodsEnd=p;
        if (existing == 65535) throw new IOException("Method table full");
        int codeName = -1;
        for(int i=1;i<cp.length;i++)if(cp[i]!=null && cp[i].tag==1 && "Code".equals(cp[i].utf8)) {codeName=i;break;}
        String generated = "bml$compose$" + Integer.toUnsignedString(
            Objects.hash(targetMethod,targetDesc,matchOpcode,matchOwner,matchName,matchDesc,occurrence,Arrays.deepToString(bridges.toArray())),36);
        // A class can be patched incrementally. Refuse an unexpected duplicate.
        for(int i=1;i<cp.length;i++)if(cp[i]!=null && generated.equals(cp[i].utf8))
            throw new IOException("Synthesized composition method already exists: " + generated);
        int next=cp.length;
        ByteArrayOutputStream constants=new ByteArrayOutputStream();
        int newName=next++;constants.write(cpUtf8(generated));
        int newDesc=next++;constants.write(cpUtf8(matchDesc));
        if(codeName<0){codeName=next++;constants.write(cpUtf8("Code"));}
        int adapterNT=next++;constants.write(cpNameType(newName,newDesc));
        int adapterRef=next++;constants.write(cpMethodRef(thisClass,adapterNT));
        int[] bridgeRefs=new int[bridges.size()];
        for(int i=0;i<bridges.size();i++) {
            String[] b=bridges.get(i);
            int ownerUtf=next++;constants.write(cpUtf8(b[0].replace('.','/')));
            int ownerCls=next++;constants.write(cpClass(ownerUtf));
            int methodUtf=next++;constants.write(cpUtf8(b[1]));
            int descUtf=next++;constants.write(cpUtf8(b[2]));
            int nt=next++;constants.write(cpNameType(methodUtf,descUtf));
            bridgeRefs[i]=next++;constants.write(cpMethodRef(ownerCls,nt));
        }
        if(next>65535)throw new IOException("Constant pool full");
        int[] slots=new int[parts.size()];
        int locals=0;
        for(int i=0;i<parts.size();i++){slots[i]=locals;locals+=width(parts.get(i));}
        if(locals>65535)throw new IOException("Too many local slots for composition");
        ByteArrayOutputStream code=new ByteArrayOutputStream();
        for(int i=0;i<bridges.size();i++) {
            for(int j=0;j<parts.size();j++)localLoad(code,parts.get(j),slots[j]);
            code.write(INVOKESTATIC); code.write(bridgeRefs[i] >>> 8);code.write(bridgeRefs[i]);
            if(i+1<bridges.size()){
                int slot=slots[slots.length-1];
                if(slot<=255){code.write(0x3a);code.write(slot);}
                else {code.write(0xc4);code.write(0x3a);code.write(slot>>>8);code.write(slot);}
                // Store the previous bridge result as the final accumulator.
            }
        }
        code.write(0xb0); // areturn
        byte[] body=code.toByteArray();
        if(body.length>65535)throw new IOException("Composition adapter too large");
        ByteArrayOutputStream extra=new ByteArrayOutputStream();
        DataOutputStream out=new DataOutputStream(extra);
        out.writeShort(0x100a); // private static synthetic
        out.writeShort(newName);out.writeShort(newDesc);out.writeShort(1);
        out.writeShort(codeName);out.writeInt(12+body.length);
        out.writeShort(locals);out.writeShort(locals);
        out.writeInt(body.length);out.write(body);
        out.writeShort(0);out.writeShort(0);
        out.flush();
        byte[] patched=original.clone();
        int call=loc.codeOffset+loc.pc;
        putU2(patched,call+1,adapterRef);
        ByteArrayOutputStream result=new ByteArrayOutputStream(patched.length+constants.size()+extra.size()+2);
        DataOutputStream data=new DataOutputStream(result);
        result.write(patched,0,8);data.writeShort(next);
        result.write(patched,10,parsed.end-10);
        constants.writeTo(result);
        result.write(patched,parsed.end,methodsCountAt-parsed.end);
        data.writeShort(existing+1);
        result.write(patched,methodsCountAt+2,methodsEnd-methodsCountAt-2);
        extra.writeTo(result);
        result.write(patched,methodsEnd,patched.length-methodsEnd);
        data.flush();
        return result.toByteArray();
    }

    /** Reorder a native Commander dispatch without needing an obfuscated dismiss mapping.
     *  Hard constraint: exactly the observed 11-byte, branch-free implementation:
     *    aload_0; invokevirtual dismiss()V; aload_0; aload_1;
     *    invokevirtual dispatch(action)Object; pop; return
     *  The original constant-pool references and invocation opcodes are retained.
     *  If a Bitwig revision changes this shape, fail closed instead of modifying it.
     */
    private static byte[] reorderCommander(byte[] original, String targetMethod, String targetDesc) throws Exception {
        ParsedCp parsed=parseCp(original);
        Cp[] cp=parsed.cp;
        int p=parsed.end;
        p+=6;
        int interfaces=u2(original,p);p+=2+2*interfaces;
        int fields=u2(original,p);p+=2;
        for(int i=0;i<fields;i++)p=skipMember(original,p);
        int methods=u2(original,p);p+=2;
        int found=0;
        byte[] patched=original.clone();
        for(int i=0;i<methods;i++){
            String name=utf(cp,u2(original,p+2));
            String desc=utf(cp,u2(original,p+4));
            int ac=u2(original,p+6);p+=8;
            boolean target=name.equals(targetMethod)&&desc.equals(targetDesc);
            if(target)found++;
            for(int a=0;a<ac;a++){
                String attribute=utf(cp,u2(original,p));
                int length=(int)u4(original,p+2),body=p+6;
                if(target&&attribute.equals("Code")){
                    int stack=u2(original,body),locals=u2(original,body+2);
                    int codeLen=(int)u4(original,body+4),off=body+8;
                    if(stack<2||locals<2||codeLen!=11)
                        throw new IOException("Commander order requires max_stack>=2 max_locals>=2 and 11-byte body; got " + stack+"/"+locals+"/"+codeLen);
                    byte[] code=Arrays.copyOfRange(original,off,off+codeLen);
                    if(u1(code,0)!=0x2a||u1(code,1)!=INVOKEVIRTUAL||
                       u1(code,4)!=0x2a||u1(code,5)!=0x2b||
                       u1(code,6)!=INVOKEVIRTUAL||u1(code,9)!=0x57||u1(code,10)!=0xb1)
                        throw new IOException("Unexpected Commander dispatch bytecode; refusing reorder: "+HexFormat.of().formatHex(code));
                    MemberRef cleanup=memberRef(cp,u2(code,2));
                    MemberRef action=memberRef(cp,u2(code,7));
                    if(!cleanup.desc.equals("()V")||
                       !action.desc.startsWith(targetDesc.substring(0,targetDesc.indexOf(')')+1))||
                       !(returnDesc(action.desc).startsWith("L")||returnDesc(action.desc).startsWith("[")))
                        throw new IOException("Unexpected Commander dispatch signatures: cleanup="+cleanup.owner+"."+cleanup.name+cleanup.desc+
                            " action="+action.owner+"."+action.name+action.desc);
                    byte[] swapped={(byte)0x2a,(byte)0x2b,(byte)INVOKEVIRTUAL,code[7],code[8],
                                    (byte)0x57,(byte)0x2a,(byte)INVOKEVIRTUAL,code[2],code[3],(byte)0xb1};
                    System.arraycopy(swapped,0,patched,off,swapped.length);
                    System.out.println("COMMANDER_ORDER_OK target="+targetMethod+targetDesc+
                        " native_action="+action.owner+"."+action.name+action.desc+
                        " native_cleanup="+cleanup.owner+"."+cleanup.name+cleanup.desc+
                        " bytecode="+HexFormat.of().formatHex(code)+" -> "+HexFormat.of().formatHex(swapped));
                }
                p=body+length;
            }
        }
        if(found!=1)throw new IOException("Commander dispatch target count="+found+" for "+targetMethod+targetDesc);
        return patched;
    }

    private static byte[] readJarClass(Path jar, String owner) throws Exception {
        String normalized = owner.replace('.', '/');
        String entryName = normalized.endsWith(".class") ? normalized : normalized + ".class";
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry e = jf.getJarEntry(entryName);
            if (e == null) throw new FileNotFoundException("Class not found in jar: " + entryName);
            try (InputStream in = jf.getInputStream(e)) { return in.readAllBytes(); }
        }
    }

    public static void main(String[] args) throws Exception {
        if(args.length==6&&(args[0].equals("reorder-jar")||args[0].equals("reorder-class"))){
            byte[] original=args[0].equals("reorder-jar")?readJarClass(Path.of(args[1]),args[2]):Files.readAllBytes(Path.of(args[1]));
            byte[] patched=reorderCommander(original,args[4],args[5]);
            Path output=Path.of(args[3]);Files.createDirectories(output.toAbsolutePath().getParent());Files.write(output,patched);
            System.out.println("PATCH_OK_NATIVE_COMMANDER_ORDER mode="+args[0]+" target="+args[4]+args[5]+" class_major="+u2(patched,6));
            return;
        }
        boolean chain = args.length >= 17 && (args[0].equals("chain-jar") || args[0].equals("chain-class")) && (args.length - 11) % 3 == 0;
        if (!chain && (args.length != 14 || !(args[0].equals("patch-jar") || args[0].equals("patch-class")))) {
            throw new IllegalArgumentException("usage: <patch-jar|patch-class|chain-jar|chain-class> <input> <owner-or-dash> <output> <targetMethod> <targetDesc> <matchOpcode> <matchOwner> <matchName> <matchDesc> <occurrence> <bridgeOwner> <bridgeName> <bridgeDesc> [more bridge triples in chain mode]");
        }
        String mode = args[0];
        Path input = Path.of(args[1]);
        String owner = args[2];
        Path output = Path.of(args[3]);
        String targetMethod = args[4];
        String targetDesc = args[5];
        int matchOpcode = opcode(args[6]);
        String matchOwner = args[7].replace('.', '/');
        String matchName = args[8];
        String matchDesc = args[9];
        int occurrence = Integer.parseInt(args[10]);
        String bridgeOwner = args[11].replace('.', '/');
        String bridgeName = args[12];
        String bridgeDesc = args[13];

        byte[] original = switch (mode) {
            case "patch-jar", "chain-jar" -> readJarClass(input, owner);
            case "patch-class", "chain-class" -> Files.readAllBytes(input);
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };
        byte[] patched;
        if (chain) {
            List<String[]> bridges = new ArrayList<>();
            for(int i=11;i<args.length;i+=3)bridges.add(new String[]{args[i],args[i+1],args[i+2]});
            patched=chainPatch(original,targetMethod,targetDesc,matchOpcode,matchOwner,matchName,matchDesc,occurrence,bridges);
        } else patched=patch(original,targetMethod,targetDesc,matchOpcode,matchOwner,matchName,matchDesc,occurrence,
                               bridgeOwner,bridgeName,bridgeDesc);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.write(output, patched);
        int major = u2(patched, 6);
        System.out.println((chain ? "PATCH_OK_CHAIN bridges="+((args.length-11)/3)+" " : "PATCH_OK ") + "mode=" + mode + " method=" + targetMethod + targetDesc + " match=" + args[6] + " " + matchOwner + "." + matchName + matchDesc + " occurrence=" + occurrence + " bridge=" + bridgeOwner + "." + bridgeName + bridgeDesc + " class_major=" + major);
    }
}
