package dev.bitwigre.bml;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.stream.*;

/**
 * Experimental report-only post-remap native linkage preflight.
 * Uses the prepared merged helper.jar and actual runtime target classpath.
 * Java 21, no third-party dependencies. Does not modify any class or mod.
 *
 * Checks actual JVM bytecode references, not merely manifest target declarations.
 * Full runtime compatibility, reflection and lifecycle still require other checks.
 */
public final class PreparedHelperLinkageVerifier {
    private PreparedHelperLinkageVerifier() {}

    private record Member(String kind, String owner, String name, String desc)
        implements Comparable<Member> {
        public int compareTo(Member other) {
            int c=owner.compareTo(other.owner);
            if(c!=0)return c;
            c=kind.compareTo(other.kind);
            if(c!=0)return c;
            c=name.compareTo(other.name);
            return c!=0?c:desc.compareTo(other.desc);
        }
    }
    private record NameDesc(String name,String desc) {}
    private record ClassInfo(String name,String parent,List<String> interfaces,
                             Set<NameDesc> methods,Set<NameDesc> fields,
                             Set<Member> bytecodeRefs) {}
    private static final class Cp {
        int tag,a,b;
        String value;
        Cp(int tag,int a,int b,String value) {
            this.tag=tag;this.a=a;this.b=b;this.value=value;
        }
    }
    private static final class Input {
        final ByteBuffer b;
        Input(byte[] bytes) {b=ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);}
        int u1() {return Byte.toUnsignedInt(b.get());}
        int u2() {return Short.toUnsignedInt(b.getShort());}
        int u4() {return b.getInt();}
        byte[] bytes(int n) {
            if(n<0 || n>b.remaining())throw new IllegalArgumentException("Bad attribute length "+n);
            byte[] d=new byte[n];b.get(d);return d;
        }
    }

    private static int[] scanCode(byte[] code) {
        Set<Integer> cp=new TreeSet<>();
        int p=0;
        while(p<code.length) {
            int op=Byte.toUnsignedInt(code[p]),next;
            switch(op) {
                case 0xaa -> { // tableswitch
                    int pos=(p+4)&~3;
                    if(pos+12>code.length)throw new IllegalArgumentException("Invalid tableswitch");
                    int lo=ByteBuffer.wrap(code,pos+4,4).getInt(),hi=ByteBuffer.wrap(code,pos+8,4).getInt();
                    if(hi<lo || (long)hi-lo>200000)throw new IllegalArgumentException("Invalid tableswitch range");
                    next=pos+12+4*(hi-lo+1);
                }
                case 0xab -> { // lookupswitch
                    int pos=(p+4)&~3;
                    if(pos+8>code.length)throw new IllegalArgumentException("Invalid lookupswitch");
                    int count=ByteBuffer.wrap(code,pos+4,4).getInt();
                    if(count<0 || count>200000)throw new IllegalArgumentException("Invalid lookupswitch pairs");
                    next=pos+8+8*count;
                }
                case 0xc4 -> {
                    if(p+1>=code.length)throw new IllegalArgumentException("Invalid wide");
                    next=p+(Byte.toUnsignedInt(code[p+1])==0x84?6:4);
                }
                default -> {
                    int operand=0;
                    if(op==0x10 || op==0x12 || (op>=0x15 && op<=0x19) ||
                       (op>=0x36 && op<=0x3a) || op==0xa9 || op==0xbc)operand=1;
                    else if(op==0x11 || op==0x13 || op==0x14 || op==0x84 ||
                            (op>=0x99 && op<=0xa8) || (op>=0xb2 && op<=0xb8) ||
                            op==0xbb || op==0xbd || op==0xc0 || op==0xc1 ||
                            op==0xc6 || op==0xc7)operand=2;
                    else if(op==0xb9 || op==0xba || op==0xc8 || op==0xc9)operand=4;
                    else if(op==0xc5)operand=3;
                    next=p+1+operand;
                    if(op==0x12) {
                        if(p+1>=code.length)throw new IllegalArgumentException("Short ldc");
                        cp.add(Byte.toUnsignedInt(code[p+1]));
                    } else if(op==0x13 || op==0x14 ||
                              (op>=0xb2 && op<=0xbb) || op==0xbd ||
                              op==0xc0 || op==0xc1 || op==0xc5) {
                        if(p+2>=code.length)throw new IllegalArgumentException("Short CP index");
                        cp.add((Byte.toUnsignedInt(code[p+1])<<8)|Byte.toUnsignedInt(code[p+2]));
                    }
                }
            }
            if(next>code.length)throw new IllegalArgumentException("Invalid instruction size");
            p=next;
        }
        return cp.stream().mapToInt(Integer::intValue).toArray();
    }

    private static ClassInfo parse(byte[] data) {
        Input r=new Input(data);
        if(r.u4()!=0xcafebabe)throw new IllegalArgumentException("Invalid class magic");
        r.u2();r.u2();
        int count=r.u2();
        Cp[] cp=new Cp[count];
        for(int i=1;i<count;i++) {
            int tag=r.u1();
            switch(tag) {
                case 1 -> {
                    int n=r.u2();
                    cp[i]=new Cp(tag,0,0,new String(r.bytes(n),StandardCharsets.UTF_8));
                }
                case 3,4 -> {r.u4();cp[i]=new Cp(tag,0,0,null);}
                case 5,6 -> {r.bytes(8);cp[i]=new Cp(tag,0,0,null);i++;}
                case 7,8,16,19,20 -> cp[i]=new Cp(tag,r.u2(),0,null);
                case 9,10,11,12,17,18 -> cp[i]=new Cp(tag,r.u2(),r.u2(),null);
                case 15 -> cp[i]=new Cp(tag,r.u1(),r.u2(),null);
                default -> throw new IllegalArgumentException("Unknown JVM CP tag "+tag);
            }
        }
        r.u2(); // access
        String name=className(cp,r.u2());
        String parent=className(cp,r.u2());
        int interfaceCount=r.u2();
        List<String> interfaces=new ArrayList<>();
        for(int j=0;j<interfaceCount;j++)interfaces.add(className(cp,r.u2()));

        Set<NameDesc> fields=new HashSet<>(),methods=new HashSet<>();
        Set<Integer> used=new TreeSet<>();
        for(int kind=0;kind<2;kind++) {
            int n=r.u2();
            for(int j=0;j<n;j++) {
                r.u2();
                String mn=utf(cp,r.u2()),md=utf(cp,r.u2());
                (kind==0?fields:methods).add(new NameDesc(mn,md));
                int attrs=r.u2();
                for(int k=0;k<attrs;k++) {
                    String attribute=utf(cp,r.u2());
                    byte[] a=r.bytes(r.u4());
                    if(kind==1 && attribute.equals("Code")) {
                        Input c=new Input(a);c.u2();c.u2();
                        for(int cpIndex:scanCode(c.bytes(c.u4())))used.add(cpIndex);
                    }
                }
            }
        }

        List<int[]> bootstraps=new ArrayList<>();
        int classAttrs=r.u2();
        for(int k=0;k<classAttrs;k++) {
            String key=utf(cp,r.u2());
            byte[] dataAttr=r.bytes(r.u4());
            if(key.equals("BootstrapMethods")) {
                Input b=new Input(dataAttr);
                int n=b.u2();
                for(int j=0;j<n;j++) {
                    int method=b.u2(),argc=b.u2();
                    int[] group=new int[argc+1];
                    group[0]=method;
                    for(int l=0;l<argc;l++)group[l+1]=b.u2();
                    bootstraps.add(group);
                }
            }
        }
        boolean hasInvokeDynamic=used.stream().anyMatch(x->cp[x]!=null && (cp[x].tag==17 || cp[x].tag==18));
        if(hasInvokeDynamic)
            for(int[] a:bootstraps)for(int k:a)used.add(k);
        Set<Integer> expanded=new TreeSet<>(used);
        for(int k:used)if(cp[k]!=null && cp[k].tag==15)expanded.add(cp[k].b);
        Set<Member> refs=new TreeSet<>();
        for(int k:expanded) {
            Cp c=cp[k];
            if(c==null || (c.tag!=9 && c.tag!=10 && c.tag!=11))continue;
            Cp nt=cp[c.b];
            if(nt==null || nt.tag!=12)throw new IllegalArgumentException("Invalid NameAndType CP");
            refs.add(new Member(c.tag==9?"field":"method",
                    className(cp,c.a),utf(cp,nt.a),utf(cp,nt.b)));
        }
        return new ClassInfo(name,parent,interfaces,methods,fields,refs);
    }
    private static String utf(Cp[] cp,int n) {
        if(n<1 || n>=cp.length || cp[n]==null || cp[n].tag!=1)
            throw new IllegalArgumentException("Expected Utf8 CP "+n);
        return cp[n].value;
    }
    private static String className(Cp[] cp,int n) {
        if(n==0)return null;
        if(n<1 || n>=cp.length || cp[n]==null || cp[n].tag!=7)
            throw new IllegalArgumentException("Expected Class CP "+n);
        return utf(cp,cp[n].a);
    }
    private static boolean jdk(String owner) {
        return owner.startsWith("java/") || owner.startsWith("javax/") ||
                owner.startsWith("jdk/") || owner.startsWith("sun/") ||
                owner.startsWith("com/sun/") || owner.startsWith("org/w3c/") ||
                owner.startsWith("org/xml/") || owner.startsWith("org/ietf/") ||
                owner.startsWith("[");
    }
    private static final Set<NameDesc> OBJECT_METHODS=Set.of(
            new NameDesc("getClass","()Ljava/lang/Class;"),
            new NameDesc("hashCode","()I"),
            new NameDesc("equals","(Ljava/lang/Object;)Z"),
            new NameDesc("toString","()Ljava/lang/String;"),
            new NameDesc("notify","()V"),
            new NameDesc("notifyAll","()V"),
            new NameDesc("wait","()V"),
            new NameDesc("wait","(J)V"),
            new NameDesc("wait","(JI)V"),
            new NameDesc("clone","()Ljava/lang/Object;"),
            new NameDesc("finalize","()V"));

    private static final class Classpath implements AutoCloseable {
        final ZipFile helper,stock,libs;
        final Map<String,Path> replacements;
        final Map<String,ClassInfo> found=new HashMap<>();
        final Set<String> absent=new HashSet<>();
        /** replacements: exact class entry name (dnF.class) -> prepared file. The file names are
         *  case-safe stems, never class names. */
        Classpath(Path helperJar,Map<String,Path> replacements,Path stockJar,Path libsJar) throws IOException {
            helper=new ZipFile(helperJar.toFile());
            stock=new ZipFile(stockJar.toFile());
            libs=new ZipFile(libsJar.toFile());
            this.replacements=Map.copyOf(replacements);
        }
        Set<String> helperClasses() {
            Set<String> names=new TreeSet<>();
            Enumeration<? extends ZipEntry> e=helper.entries();
            while(e.hasMoreElements()){
                String n=e.nextElement().getName();
                if(n.endsWith(".class"))names.add(n.substring(0,n.length()-6));
            }
            return names;
        }
        ClassInfo get(String owner) throws IOException {
            if(owner==null)return null;
            if(found.containsKey(owner))return found.get(owner);
            if(absent.contains(owner))return null;
            String name=owner+".class";
            byte[] d=null;
            // JVM names are case-sensitive even on Windows. Exact index keys
            // prevent a replacement class dSm.class masquerading as dsm.class.
            Path replacement=replacements.get(name);
            if(replacement!=null)d=Files.readAllBytes(replacement);
            if(d==null) {
                for(ZipFile z:List.of(helper,stock,libs)){
                    ZipEntry e=z.getEntry(name);
                    if(e==null)continue;
                    try(InputStream in=z.getInputStream(e)){d=in.readAllBytes();}
                    break;
                }
            }
            if(d==null){absent.add(owner);return null;}
            ClassInfo parsed=parse(d);
            if(!owner.equals(parsed.name()))
                throw new IOException("Wrong class bytecode for owner "+owner+": "+parsed.name());
            found.put(owner,parsed);
            return parsed;
        }
        /*
         * Resolve inherited JDK members *only* after a real helper/Bitwig class
         * traverses into its Java-platform superclass/interface.  The host JVM
         * can inspect JDK class declarations without initializing classes.
         *
         * Do not mark all members of a JDK ancestor valid; verify the exact
         * name and JVM descriptor, retaining strict failure for typos.
         */
        private static boolean resolvesJdkDeclaration(String owner,Member ref) {
            if(!jdk(owner) || owner.startsWith("["))return false;
            try {
                Class<?> cls=Class.forName(owner.replace('/','.'),false,
                                            ClassLoader.getPlatformClassLoader());
                if(ref.kind().equals("method")) {
                    for(java.lang.reflect.Method m:cls.getDeclaredMethods()) {
                        if(m.getName().equals(ref.name()) &&
                           java.lang.invoke.MethodType.methodType(
                               m.getReturnType(),m.getParameterTypes())
                               .toMethodDescriptorString().equals(ref.desc()))
                            return true;
                    }
                } else if(ref.kind().equals("field")) {
                    for(java.lang.reflect.Field f:cls.getDeclaredFields()) {
                        if(f.getName().equals(ref.name()) &&
                           f.getType().descriptorString().equals(ref.desc()))
                            return true;
                    }
                }
            } catch(ClassNotFoundException | LinkageError | SecurityException ignored) {
                // An unavailable platform class is not grounds to claim success.
            }
            return false;
        }
        boolean resolves(Member r) throws IOException {
            if(r.kind().equals("method") && OBJECT_METHODS.contains(new NameDesc(r.name(),r.desc())))
                return true;
            Deque<String> q=new ArrayDeque<>();
            Set<String> seen=new HashSet<>();
            q.add(r.owner());
            while(!q.isEmpty()) {
                String o=q.removeFirst();
                if(!seen.add(o))continue;
                if(jdk(o)) {
                    if(resolvesJdkDeclaration(o,r))return true;
                    // Use the reflected superclass and interface hierarchy as
                    // well, so inherited platform declarations are not missed.
                    if(!o.startsWith("[")) {
                        try {
                            Class<?> type=Class.forName(o.replace('/','.'),false,
                                                       ClassLoader.getPlatformClassLoader());
                            if(type.getSuperclass()!=null)
                                q.add(type.getSuperclass().getName().replace('.','/'));
                            for(Class<?> iface:type.getInterfaces())
                                q.add(iface.getName().replace('.','/'));
                        } catch(ClassNotFoundException | LinkageError | SecurityException ignored){}
                    }
                    continue;
                }
                ClassInfo c=get(o);
                if(c==null)continue;
                NameDesc nd=new NameDesc(r.name(),r.desc());
                if(r.kind().equals("method")?c.methods().contains(nd):c.fields().contains(nd))
                    return true;
                if(c.parent()!=null)q.add(c.parent());
                q.addAll(c.interfaces());
            }
            return false;
        }
        public void close() throws IOException {helper.close();stock.close();libs.close();}
    }

    /**
     * Auto-translate raw mod-owned trace ABIs using original and prepared helper
     * declarations. The type-map-only translation is insufficient when a source
     * helper's native method signature includes classes absent from the physical
     * map; the actual remapper may still have rewritten those type references.
     *
     * Safe conditions:
     *  - Exact requested declaration exists in the ORIGINAL mod helper;
     *  - Same class & method name exist in the prepared merged helper;
     *  - A single prepared candidate has the identical JVM argument/return
     *    primitive-vs-object/array shape. Never choose among overloaded matches.
     *  - Native Bitwig trace targets and undeclared source targets are unchanged.
     */
    public static String inferPreparedHelperTraceDescriptor(
            Path originalHelper, Path preparedHelper,
            String originalOwner, String method, String originalDescriptor,
            String currentlyTranslatedDescriptor) throws IOException {
        if(originalHelper==null || !Files.isRegularFile(originalHelper)
                || !Files.isRegularFile(preparedHelper))return currentlyTranslatedDescriptor;
        String classFile=originalOwner+".class";
        ClassInfo original=null,prepared=null;
        try(ZipFile source=new ZipFile(originalHelper.toFile())) {
            ZipEntry entry=source.getEntry(classFile);
            if(entry==null)return currentlyTranslatedDescriptor;
            try(InputStream in=source.getInputStream(entry)){original=parse(in.readAllBytes());}
        }
        if(!original.methods().contains(new NameDesc(method,originalDescriptor)))
            return currentlyTranslatedDescriptor;
        try(ZipFile target=new ZipFile(preparedHelper.toFile())) {
            ZipEntry entry=target.getEntry(classFile);
            if(entry==null)return currentlyTranslatedDescriptor;
            try(InputStream in=target.getInputStream(entry)){prepared=parse(in.readAllBytes());}
        }
        if(prepared.methods().contains(new NameDesc(method,currentlyTranslatedDescriptor)))
            return currentlyTranslatedDescriptor;
        // Binary class names vary, but parameter shapes must not vary.
        String shape=traceDescriptorShape(originalDescriptor);
        List<String> choices=prepared.methods().stream()
                .filter(m->m.name().equals(method)
                         && traceDescriptorShape(m.desc()).equals(shape))
                .map(NameDesc::desc).distinct().sorted().toList();
        if(choices.size()!=1)return currentlyTranslatedDescriptor;
        return choices.get(0);
    }
    private static String traceDescriptorShape(String descriptor) {
        return descriptor.replaceAll("L[^;]+;","L");
    }

    /** Validate prepared physical trace targets; raw Bitwig 6.1.3 helper signatures
     * cannot be assumed to match remapped Bitwig 6.1.2 method descriptors. */
    public record TraceResult(int total,int valid,int invalid,Path report) {}
    public static TraceResult verifyTraceTargets(Path targets,Path helper,Map<String,Path> replacements,
                                                  Path stock,Path libs,Path out) throws IOException {
        List<String> lines=Files.readAllLines(targets,StandardCharsets.UTF_8);
        List<String> report=new ArrayList<>();
        report.add("status\ttarget_id\towner\tmethod\tdescriptor\treason");
        int total=0,valid=0,invalid=0;
        try(Classpath cp=new Classpath(helper,replacements,stock,libs)) {
            for(String line:lines.stream().skip(1).toList()) {
                if(line.isBlank())continue;
                String[] a=line.split("\t",-1);
                if(a.length<4 || !a[0].contains(".trace."))continue;
                total++;
                String owner=a[1].replace('.','/');
                String reason;
                if(cp.get(owner)==null)reason="MISSING_TRACE_OWNER";
                else if(!cp.resolves(new Member("method",owner,a[2],a[3])))
                    reason="INVALID_TRACE_METHOD_DESCRIPTOR";
                else reason="";
                if(reason.isEmpty())valid++; else {
                    invalid++;
                    report.add("INVALID\t"+a[0]+"\t"+a[1]+"\t"+a[2]+"\t"+a[3]+"\t"+reason);
                }
            }
        }
        Files.createDirectories(out.getParent());
        Files.write(out,report,StandardCharsets.UTF_8);
        return new TraceResult(total,valid,invalid,out);
    }

    public record Issue(Member ref,SortedSet<String> referencingClasses) {}
    public record Result(int helperClasses,int memberRefs,int resolved,int unresolvedSites,int uniqueUnresolved,
                         Path report) {}
    private static Map<String,Set<String>> modOwners(Path modsRoot) throws IOException {
        Map<String,Set<String>> result=new HashMap<>();
        if(!Files.isDirectory(modsRoot))return result;
        try(Stream<Path> dirs=Files.list(modsRoot)) {
            for(Path d:dirs.filter(Files::isDirectory).toList()) {
                Path original=d.resolve("helper.jar");
                if(!Files.isRegularFile(original))continue;
                try(ZipFile jar=new ZipFile(original.toFile())) {
                    Enumeration<? extends ZipEntry> it=jar.entries();
                    while(it.hasMoreElements()) {
                        String n=it.nextElement().getName();
                        if(n.endsWith(".class"))result.computeIfAbsent(n.substring(0,n.length()-6),k->new TreeSet<>())
                                .add(d.getFileName().toString());
                    }
                }
            }
        }
        return result;
    }
    public static Result verify(Path helper,Map<String,Path> replacements,Path stock,Path libs,Path modsRoot,Path out)
            throws IOException {
        TreeMap<Member,SortedSet<String>> unresolved=new TreeMap<>();
        int examined=0,resolved=0,unresolvedSites=0,classCount=0;
        List<String> generatedInventory=new ArrayList<>();
        generatedInventory.add("status\tmod\thelper_class\tkind\towner\tmember\tdescriptor");
        Map<String,Set<String>> mods=modOwners(modsRoot);
        try(Classpath cp=new Classpath(helper,replacements,stock,libs)) {
            for(String name:cp.helperClasses()) {
                ClassInfo cls=cp.get(name);
                classCount++;
                for(Member ref:cls.bytecodeRefs()) {
                    if(jdk(ref.owner()))continue;
                    examined++;
                    boolean ok=cp.resolves(ref);
                    String mod=String.join(",",mods.getOrDefault(name,Set.of("unattributed")));
                    generatedInventory.add((ok?"RESOLVED":"UNRESOLVED")+"\t"+mod+"\t"+name+"\t"+
                        ref.kind()+"\t"+ref.owner()+"\t"+ref.name()+"\t"+ref.desc());
                    if(ok)resolved++;
                    else {
                        unresolvedSites++;
                        unresolved.computeIfAbsent(ref,k->new TreeSet<>()).add(name);
                    }
                }
            }
        }
        List<String> report=new ArrayList<>();
        report.add("status\tmod\thelper_class\tkind\towner\tmember\tdescriptor");
        for(var e:unresolved.entrySet()) {
            Member r=e.getKey();
            for(String owner:e.getValue()) {
                String mod=String.join(",",mods.getOrDefault(owner,Set.of("unattributed")));
                report.add("UNRESOLVED\t"+mod+"\t"+owner+"\t"+r.kind()+
                        "\t"+r.owner()+"\t"+r.name()+"\t"+r.desc());
            }
        }
        Files.createDirectories(out.getParent());
        Files.write(out,report,StandardCharsets.UTF_8);
        Files.write(out.resolveSibling("helper-dependency-inventory.tsv"),generatedInventory,StandardCharsets.UTF_8);
        return new Result(classCount,examined,resolved,unresolvedSites,unresolved.size(),out);
    }
    /** replacement-map.tsv (owner TAB file) -> exact class entry name -> file. */
    static Map<String,Path> readReplacementMap(Path map) throws IOException {
        Map<String,Path> out=new TreeMap<>();
        for(String line:Files.readAllLines(map,StandardCharsets.UTF_8)) {
            String[] cols=line.split("\t",-1);
            if(cols.length<2||cols[0].isBlank())continue;
            out.put(cols[0].trim().replace('.','/')+".class",Path.of(cols[1].trim()));
        }
        return out;
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=6 && args.length!=7)
            throw new IllegalArgumentException(
                "Usage: PreparedHelperLinkageVerifier helper.jar replacement-map.tsv stock.jar libs.jar modsDir report.tsv [--strict]");
        Result r=verify(Path.of(args[0]),readReplacementMap(Path.of(args[1])),Path.of(args[2]),Path.of(args[3]),
                Path.of(args[4]),Path.of(args[5]));
        System.out.println("BML_LINKAGE_PREFLIGHT helper_classes="+r.helperClasses()+
                " checked="+r.memberRefs()+" resolved="+r.resolved()+
                " unresolved_sites="+r.unresolvedSites()+" unresolved_unique="+r.uniqueUnresolved()+
                " report="+r.report());
        if(args.length==7 && args[6].equals("--strict") && r.uniqueUnresolved()>0)System.exit(2);
    }
}
