import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Prepare-time remapper for helper class bytecode.  A separate Java process is
 * is independent of JDK-internal ASM packages and third-party JARs. It
 * changes constant-pool references without changing bytecode offsets or
 * editing any installed mod. This tool never modifies an installed mod.
 *
 * The mapping data is the current static cross-version understanding.
 */
public final class HelperBytecodeRemapper {
    private record Member(String owner,String name,String descriptor) {}
    private record Key(String owner,String name,String descriptor) {}
    private static final class Mapping {
        final Map<String,String> classes = new HashMap<>();
        final Map<String,List<String>> sourceParents=new HashMap<>();
        int remappedInheritedHelperCalls;
        final Map<Key,Member> methods = new HashMap<>(), fields = new HashMap<>();
        int remappedClasses, remappedMethods, remappedFields, remappedReflectiveNames, remappedClassNameLiterals, remappedOverrides;
        int unresolvedReflectionSites, sharedReflectionLiteralConflicts, bridgeInvocations, bridgeClasses;
        final Set<String> warnings = new TreeSet<>();
        final Set<String> reflectionGaps = new TreeSet<>();
        public String map(String internalName) {
            String result = classes.get(internalName);
            if(result != null && !result.equals(internalName))remappedClasses++;
            return result == null ? internalName : result;
        }
        public String mapMethodName(String owner,String name,String descriptor) {
            Member member=methods.get(new Key(owner,name,descriptor));
            if(member == null)return name;
            String newOwner=classes.getOrDefault(owner,owner);
            if(!newOwner.equals(member.owner)) {
                warnings.add("Method owner changed outside normal class mapping: "+owner+"."+name+descriptor+" -> "+member+"; check invocation compatibility");
                return name; // do not retarget receiver to an unrelated owner
            }
            if(!name.equals(member.name))remappedMethods++;
            return member.name;
        }
        public String mapFieldName(String owner,String name,String descriptor) {
            Member member=fields.get(new Key(owner,name,descriptor));
            if(member == null)return name;
            String newOwner=classes.getOrDefault(owner,owner);
            if(!newOwner.equals(member.owner)) {
                warnings.add("Field owner changed outside normal class mapping: "+owner+"."+name+descriptor+" -> "+member+"; check field access compatibility");
                return name;
            }
            if(!name.equals(member.name))remappedFields++;
            return member.name;
        }
        void load(Path file) throws IOException {
            InputStream input=Files.newInputStream(file);
            if(file.toString().endsWith(".gz"))input=new GZIPInputStream(input);
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
                String line;int n=0;
                while((line=reader.readLine())!=null) {
                    n++;
                    if(line.isBlank()||line.startsWith("#"))continue;
                    String[] parts=line.split("\t",-1);
                    switch(parts[0]) {
                        case "H" -> {
                            if(parts.length!=3)throw new IOException("Invalid source inheritance edge at line "+n);
                            sourceParents.computeIfAbsent(parts[1],unused->new ArrayList<>()).add(parts[2]);
                        }
                        case "C" -> {
                            if(parts.length!=3)throw new IOException("Invalid class map at line "+n);
                            String old=classes.putIfAbsent(parts[1],parts[2]);
                            if(old!=null&&!old.equals(parts[2]))throw new IOException("Conflicting class map at line "+n+": "+parts[1]);
                        }
                        case "M", "F" -> {
                            if(parts.length!=7)throw new IOException("Invalid member map at line "+n);
                            Map<Key,Member> table=parts[0].equals("M")?methods:fields;
                            Key key=new Key(parts[1],parts[2],parts[3]);
                            Member value=new Member(parts[4],parts[5],parts[6]);
                            Member old=table.putIfAbsent(key,value);
                            if(old!=null&&!old.equals(value))throw new IOException("Conflicting member map at line "+n+": "+key);
                        }
                        default -> throw new IOException("Unknown map type "+parts[0]+" at line "+n);
                    }
                }
            }
        }
    }

    /**
     * Dependency-free constant-pool remapper. JVM member declarations, frames, annotations,
     * signatures, method handles, dynamic constants and bytecode refer to constant-pool entries.
     * We preserve code and all attribute bytes, updating those entries and individual
     * name-and-type references without changing instruction offsets or frame positions.
     */
    private static final class ClassFile {
        private record Entry(int tag,int a,int b,long bits,String value) {}
        private final int magic, minor, major;
        private final ArrayList<Entry> pool=new ArrayList<>();
        private final byte[] body;
        private final Map<String,Integer> stringIndices=new HashMap<>();
        ClassFile(byte[] bytes) throws IOException {
            try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))) {
                magic=in.readInt();if(magic!=0xCAFEBABE)throw new IOException("Invalid Java class file");
                minor=in.readUnsignedShort();major=in.readUnsignedShort();
                int count=in.readUnsignedShort();pool.add(null);
                for(int i=1;i<count;i++) {
                    int tag=in.readUnsignedByte();Entry e;
                    switch(tag) {
                        case 1 -> e=new Entry(tag,0,0,0,in.readUTF());
                        case 3,4 -> e=new Entry(tag,0,0,Integer.toUnsignedLong(in.readInt()),null);
                        case 5,6 -> e=new Entry(tag,0,0,in.readLong(),null);
                        case 7,8,16,19,20 -> e=new Entry(tag,in.readUnsignedShort(),0,0,null);
                        case 9,10,11,12,17,18 -> e=new Entry(tag,in.readUnsignedShort(),in.readUnsignedShort(),0,null);
                        case 15 -> e=new Entry(tag,in.readUnsignedByte(),in.readUnsignedShort(),0,null);
                        default -> throw new IOException("Unsupported constant-pool tag "+tag);
                    }
                    pool.add(e);
                    if(tag==5||tag==6){pool.add(null);i++;}
                }
                body=in.readAllBytes();
            }
            for(int i=1;i<pool.size();i++) if(pool.get(i)!=null&&pool.get(i).tag()==1)
                stringIndices.putIfAbsent(pool.get(i).value(),i);
        }
        private Entry at(int i)throws IOException {
            if(i<=0||i>=pool.size()||pool.get(i)==null)throw new IOException("Invalid constant-pool index "+i);
            return pool.get(i);
        }
        private String utf(int i)throws IOException {
            Entry e=at(i);if(e.tag()!=1)throw new IOException("Expected UTF8 at "+i);
            return e.value();
        }
        private int putUtf(String s)throws IOException {
            Integer idx=stringIndices.get(s);if(idx!=null)return idx;
            if(pool.size()>=65535)throw new IOException("Constant pool too large after remapping");
            int index=pool.size();pool.add(new Entry(1,0,0,0,s));stringIndices.put(s,index);
            return index;
        }
        private int putNameType(String name,String desc)throws IOException {
            int n=putUtf(name),d=putUtf(desc);
            for(int i=1;i<pool.size();i++){
                Entry e=pool.get(i);if(e!=null&&e.tag()==12&&e.a()==n&&e.b()==d)return i;
            }
            if(pool.size()>=65535)throw new IOException("Constant pool too large after remapping");
            int index=pool.size();pool.add(new Entry(12,n,d,0,null));return index;
        }
        private String nameOf(int classIndex)throws IOException {
            Entry e=at(classIndex);if(e.tag()!=7)throw new IOException("Expected Class constant at "+classIndex);
            return utf(e.a());
        }
        String className()throws IOException {
            if(body.length<4)throw new IOException("Truncated class definition");
            int thisClass=((body[2]&255)<<8)|(body[3]&255);
            return nameOf(thisClass);
        }
        private record Decl(int flags,String name,String desc,int byteOffset) {}
        private List<Decl> declarations() throws IOException {
            int p=6,interfaces=u2(body,p);p+=2+2*interfaces;
            p=skipMembers(body,p); // fields
            int methodCount=u2(body,p);p+=2;
            List<Decl> result=new ArrayList<>();
            for(int i=0;i<methodCount;i++) {
                int flags=u2(body,p),name=u2(body,p+2),desc=u2(body,p+4);
                result.add(new Decl(flags,utf(name),utf(desc),p));
                int attrs=u2(body,p+6);p+=8;
                for(int j=0;j<attrs;j++) {
                    int len=i4(body,p+2);if(len<0)throw new IOException("Invalid class attribute length");
                    p+=6+len;
                    if(p>body.length)throw new IOException("Truncated class method attributes");
                }
            }
            return result;
        }
        /** Infer mod-owned overrides using already-grounded source superclass/interface member mappings.
         *  Do not rename unrelated helper methods or change a method's signature incompatibly.
         */
        void inferOverrides(Mapping mapping) throws IOException {
            String own=className();
            List<String> bases=new ArrayList<>();
            int sup=u2(body,4);
            if(sup!=0)bases.add(nameOf(sup));
            int interfaces=u2(body,6);
            for(int i=0;i<interfaces;i++)bases.add(nameOf(u2(body,8+i*2)));
            for(Decl d:declarations()) {
                if(d.name().startsWith("<") || (d.flags() & (0x0002|0x0008))!=0)continue;
                // Package-private JVM virtual methods also override when the
                // helper and declaring Bitwig class share a runtime package.
                // The previous public/protected-only filter silently left these
                // declarations on their 6.1.3 obfuscated names. In particular,
                // CurveField.HEY(I) failed to override 6.1.4 dND.Hnt(I),
                // losing the full-widget drag hit rectangle.
                boolean packagePrivate=(d.flags() & (0x0001|0x0002|0x0004))==0;
                String ownPackage=own.contains("/")?own.substring(0,own.lastIndexOf('/')):"";
                // A helper's declared override may be inherited through several
                // *Bitwig* ancestors, not merely from its immediate superclass.
                // Resolve using the ORIGINAL source hierarchy only. Never look up
                // the already translated obfuscated name: names can overlap.
                Set<Member> choices=new HashSet<>();
                ArrayDeque<String> pending=new ArrayDeque<>(bases);
                Set<String> visited=new HashSet<>();
                while(!pending.isEmpty()) {
                    String base=pending.removeFirst();
                    if(!visited.add(base))continue;
                    // Unlike public/protected methods, package-private members
                    // cannot be inherited/overridden across source packages.
                    String basePackage=base.contains("/")?base.substring(0,base.lastIndexOf('/')):"";
                    if(packagePrivate && !ownPackage.equals(basePackage)) {
                        pending.addAll(mapping.sourceParents.getOrDefault(base,List.of()));
                        continue;
                    }
                    Member target=mapping.methods.get(new Key(base,d.name(),d.desc()));
                    if(target!=null && target.owner().equals(mapping.classes.getOrDefault(base,base))
                       && target.descriptor().equals(descriptor(d.desc(),mapping))) {
                        choices.add(new Member(own,target.name(),target.descriptor()));
                    }
                    pending.addAll(mapping.sourceParents.getOrDefault(base,List.of()));
                }
                if(choices.size()>1) {
                    mapping.warnings.add("Conflicting ancestor override targets: "+own+"."+d.name()+d.desc()+" candidates="+choices);
                    continue;
                }
                if(choices.size()==1) {
                    Member target=choices.iterator().next();
                    if(target.name().equals(d.name()))continue;
                    Key k=new Key(own,d.name(),d.desc());
                    Member previous=mapping.methods.putIfAbsent(k,target);
                    if(previous!=null&&!previous.equals(target))throw new IOException("Conflicting helper override mapping: "+k);
                }
            }
        }
        private void rewriteDeclaredOverrides(Mapping mapping) throws IOException {
            String own=className();
            for(Decl d:declarations()) {
                Member target=mapping.methods.get(new Key(own,d.name(),d.desc()));
                if(target==null || !target.owner().equals(own) || target.name().equals(d.name()))continue;
                int index=putUtf(target.name());
                body[d.byteOffset()+2]=(byte)(index>>>8);
                body[d.byteOffset()+3]=(byte)index;
                mapping.remappedOverrides++;
            }
        }
        private static String descriptor(String s,Mapping m) {
            // Rewrite object type tokens in JVM descriptors and generic signatures.
            // An L-object type may immediately follow a primitive argument (e.g. DDLpkg/Type;).
            // Never treat a random string as a descriptor; exact class constants are
            // rewritten separately and ordinary String constant payloads are untouched.
            if(s.isEmpty()|| !(s.charAt(0)=='('||s.charAt(0)=='L'||s.charAt(0)=='['||
                 s.charAt(0)=='<'||s.charAt(0)=='T'||s.startsWith("^L")))return s;
            StringBuilder out=new StringBuilder();boolean changed=false;
            for(int i=0;i<s.length();) {
                if(s.charAt(i)=='L' && (i==0 || "()[;<:+-^BCDFIJSZ".indexOf(s.charAt(i-1))>=0)) {
                    int j=i+1;
                    while(j<s.length()){
                        char ch=s.charAt(j);
                        if(ch==';'||ch=='<'||ch==':'||ch=='.')break;
                        if(!(Character.isLetterOrDigit(ch)||ch=='/'||ch=='$'||ch=='_'||ch=='-'))break;
                        j++;
                    }
                    if(j>s.length()-1 || !(s.charAt(j)==';'||s.charAt(j)=='<'||s.charAt(j)==':'||s.charAt(j)=='.')) {
                        out.append(s.charAt(i++));continue;
                    }
                    String old=s.substring(i+1,j),newName=m.classes.get(old);
                    if(newName!=null&&!newName.equals(old)){
                        out.append('L').append(newName);changed=true;m.remappedClasses++;i=j;continue;
                    }
                }
                out.append(s.charAt(i++));
            }
            return changed?out.toString():s;
        }
        private int u2(byte[] buf,int i) throws IOException {
            if(i<0||i+2>buf.length)throw new IOException("Truncated class section");
            return ((buf[i]&255)<<8)|(buf[i+1]&255);
        }
        private int i4(byte[] buf,int i) throws IOException {
            if(i<0||i+4>buf.length)throw new IOException("Truncated class section");
            return ((buf[i]&255)<<24)|((buf[i+1]&255)<<16)|((buf[i+2]&255)<<8)|(buf[i+3]&255);
        }
        private int skipMembers(byte[] bytes,int start) throws IOException {
            int pos=start;
            int members=u2(bytes,pos);pos+=2;
            for(int m=0;m<members;m++){
                pos+=6;int attrs=u2(bytes,pos);pos+=2;
                for(int a=0;a<attrs;a++){
                    int len=i4(bytes,pos+2);if(len<0)throw new IOException("Invalid attribute length");
                    pos+=6+len;
                    if(pos>bytes.length)throw new IOException("Truncated class attributes");
                }
            }
            return pos;
        }
        private int step(byte[] code,int p)throws IOException {
            int op=code[p]&255;
            int n=switch(op){
                case 0x10,0x12,0x15,0x16,0x17,0x18,0x19,0x36,0x37,0x38,0x39,0x3a,
                     0xa9,0xbc -> 2;
                case 0x11,0x13,0x14,0x84,0x99,0x9a,0x9b,0x9c,0x9d,0x9e,0x9f,0xa0,
                     0xa1,0xa2,0xa3,0xa4,0xa5,0xa6,0xa7,0xa8,
                     0xb2,0xb3,0xb4,0xb5,0xb6,0xb7,0xb8,0xbb,0xbd,0xc0,0xc1,0xc6,0xc7 -> 3;
                case 0xb9,0xba,0xc8,0xc9 -> 5;
                case 0xc5 -> 4;
                case 0xc4 -> p+1<code.length&&(code[p+1]&255)==0x84?6:4;
                case 0xaa -> {
                    int x=(p+4)&~3,lo=i4(code,x+4),hi=i4(code,x+8);
                    long count=(long)hi-lo+1;
                    if(count<0||count>Integer.MAX_VALUE/4)throw new IOException("Invalid tableswitch");
                    yield (int)(x-p+12+4*count);
                }
                case 0xab -> {
                    int x=(p+4)&~3,count=i4(code,x+4);
                    if(count<0||count>Integer.MAX_VALUE/8)throw new IOException("Invalid lookupswitch");
                    yield x-p+8+8*count;
                }
                default -> 1;
            };
            if(p+n>code.length)throw new IOException("Invalid JVM instruction length opcode="+op);
            return n;
        }
        private record Op(int pc,int opcode,int cp) {}
        private List<Op> instructions(byte[] code)throws IOException {
            List<Op> result=new ArrayList<>();
            for(int p=0;p<code.length;){
                int op=code[p]&255,cp=-1;
                if(op==0x12)cp=code[p+1]&255;
                else if(op==0x13||op==0x14||op==0xb6||op==0xb7||op==0xb8||op==0xb9)
                    cp=u2(code,p+1);
                result.add(new Op(p,op,cp));p+=step(code,p);
            }
            return result;
        }
        private boolean isLookup(Entry e)throws IOException {
            if(!(e.tag()==10||e.tag()==11))return false;
            String owner=nameOf(e.a());
            Entry nt=at(e.b());String name=utf(nt.a());
            if(owner.equals("java/lang/invoke/MethodHandles$Lookup"))
                return name.equals("findStatic")||name.equals("findVirtual")||name.equals("findSpecial");
            if(owner.equals("java/lang/Class"))
                return name.equals("getMethod")||name.equals("getDeclaredMethod");
            return false;
        }
        // Resolve reflection members from a known source Class value. Method/field
        // names are selected only when mapped declarations (including inherited
        // lookups for getMethod/getField) agree on ONE exact target name.
        private String reflectedName(Mapping m,String sourceOwner,String originalName,
                                     boolean field,boolean includeInherited) {
            Set<String> names=new TreeSet<>();
            ArrayDeque<String> pending=new ArrayDeque<>();pending.add(sourceOwner);
            Set<String> visited=new HashSet<>();
            while(!pending.isEmpty()) {
                String owner=pending.removeFirst();if(!visited.add(owner))continue;
                String expectedOwner=m.classes.getOrDefault(owner,owner);
                for(var row:(field?m.fields:m.methods).entrySet()) {
                    Key k=row.getKey();Member target=row.getValue();
                    if(k.owner().equals(owner) && k.name().equals(originalName)
                       && target.owner().equals(expectedOwner))names.add(target.name());
                }
                if(includeInherited)pending.addAll(m.sourceParents.getOrDefault(owner,List.of()));
            }
            return names.size()==1?names.iterator().next():null;
        }
        // Do not rewrite arbitrary text that happens to equal a JVM type name.
        // The surrounding instruction sequence must establish reflective use.
        private String mappedBinaryClassName(Mapping mapping,String literal) {
            boolean dotted=literal.indexOf('.')>=0;
            String source=dotted?literal.replace('.','/'):literal;
            String target=mapping.classes.get(source);
            if(target==null && !dotted && source.indexOf('/')<0) {
                // Class.getSimpleName returns only the leaf class name. Use it
                // only when the source leaf uniquely identifies a mapped class.
                // Never infer this from an arbitrary String literal.
                Set<String> matched=new HashSet<>();
                for(var row:mapping.classes.entrySet()) {
                    String owner=row.getKey();int slash=owner.lastIndexOf('/');
                    String leaf=owner.substring(slash+1);
                    int dollar=leaf.lastIndexOf('$');if(dollar>=0)leaf=leaf.substring(dollar+1);
                    if(leaf.equals(source)) {
                        String out=row.getValue();int targetSlash=out.lastIndexOf('/');
                        String targetLeaf=out.substring(targetSlash+1);
                        int targetDollar=targetLeaf.lastIndexOf('$');
                        if(targetDollar>=0)targetLeaf=targetLeaf.substring(targetDollar+1);
                        matched.add(targetLeaf);
                    }
                }
                if(matched.size()==1)target=matched.iterator().next();
            }
            if(target==null||target.equals(source))return null;
            return dotted?target.replace('/','.'):target;
        }
        private boolean memberCall(Op op,String owner,String method,String desc)throws IOException {
            if(op.cp()<=0 || !(op.opcode()==0xb6 || op.opcode()==0xb7 || op.opcode()==0xb8 || op.opcode()==0xb9)) return false;
            Entry ref=at(op.cp());
            if(ref.tag()!=10 && ref.tag()!=11)return false;
            if(!nameOf(ref.a()).equals(owner))return false;
            Entry nt=at(ref.b());
            return utf(nt.a()).equals(method) && utf(nt.b()).equals(desc);
        }
        private boolean callName(Op op,String owner,String... methodNames)throws IOException {
            if(op.cp()<=0 || !(op.opcode()==0xb6||op.opcode()==0xb7||op.opcode()==0xb8||op.opcode()==0xb9))return false;
            Entry ref=at(op.cp());if(ref.tag()!=10&&ref.tag()!=11)return false;
            if(!owner.equals(nameOf(ref.a())))return false;
            Entry nt=at(ref.b());String name=utf(nt.a());
            for(String candidate:methodNames)if(candidate.equals(name))return true;
            return false;
        }
        private int loadLocal(Op op,byte[] code) {
            int opcode=op.opcode();
            if(opcode>=0x2a&&opcode<=0x2d)return opcode-0x2a;
            if(opcode==0x19)return code[op.pc()+1]&255;
            if(opcode==0xc4&&op.pc()+3<code.length&&(code[op.pc()+1]&255)==0x19)
                return ((code[op.pc()+2]&255)<<8)|(code[op.pc()+3]&255);
            return -1;
        }
        private int storeLocal(Op op,byte[] code) {
            int opcode=op.opcode();
            if(opcode>=0x4b&&opcode<=0x4e)return opcode-0x4b;
            if(opcode==0x3a)return code[op.pc()+1]&255;
            if(opcode==0xc4&&op.pc()+3<code.length&&(code[op.pc()+1]&255)==0x3a)
                return ((code[op.pc()+2]&255)<<8)|(code[op.pc()+3]&255);
            return -1;
        }
        // Source reflection operand evaluation is intentionally conservative:
        // only literal Class.forName operands, local Class values created by
        // those calls, and direct class constants are currently understood.
        private String directClassOwner(Op op,Map<Integer,String> localClasses,byte[] code)throws IOException {
            if(op.cp()>0&&(op.opcode()==0x12||op.opcode()==0x13)) {
                Entry e=at(op.cp());if(e.tag()==7)return nameOf(op.cp());
            }
            int index=loadLocal(op,code);
            return index<0?null:localClasses.get(index);
        }
        private boolean reflectiveClassLookup(List<Op> ops,int j)throws IOException {
            for(int k=j+1;k<Math.min(ops.size(),j+6);k++) {
                Op next=ops.get(k);
                if(callName(next,"java/lang/Class","forName"))return true;
                if(next.opcode()==0x12||next.opcode()==0x13) {
                    Entry e=at(next.cp());if(e.tag()==8)return false;
                }
                if(next.opcode()==0xa7||next.opcode()==0xb0||next.opcode()==0xb1)return false;
                if(next.opcode()==0xb6||next.opcode()==0xb8||next.opcode()==0xb9)return false;
            }
            return false;
        }
        private boolean classNameComparison(List<Op> ops,int j)throws IOException {
            // Both `clazz.getSimpleName().equals("old")` and
            // `"old".equals(obj.getClass().getSimpleName())` occur in helpers.
            // Check adjacency/short Class.getName/getSimpleName/getTypeName chains.
            int start=Math.max(0,j-3),end=Math.min(ops.size(),j+6);
            boolean nameCall=false,equalsCall=false;
            for(int k=start;k<end;k++) {
                if(callName(ops.get(k),"java/lang/Class","getName","getSimpleName","getTypeName")) {
                    if(k>=j-2&&k<=j+4)nameCall=true;
                }
                if(k>j&&k<=j+5&&memberCall(ops.get(k),"java/lang/String","equals","(Ljava/lang/Object;)Z"))equalsCall=true;
            }
            return nameCall&&equalsCall;
        }
        private record OperandDecision(int cp,String mapped,boolean className,boolean knownReflectiveUse,
                                       String method,int pc,String kind,String sourceOwner) {
            String location(String owner) {return owner+"."+method+"@"+pc;}
        }
        private void remapReflectiveNames(Mapping mapping)throws IOException {
            byte[] b=body;int p=6;
            int interfaces=u2(b,p);p+=2+2*interfaces;
            p=skipMembers(b,p);
            int count=u2(b,p);p+=2;
            Map<Integer,List<OperandDecision>> decisions=new TreeMap<>();
            String helperOwner=className();
            for(int m=0;m<count;m++) {
                String helperMethod=utf(u2(b,p+2))+utf(u2(b,p+4));
                p+=6;int attrs=u2(b,p);p+=2;
                for(int a=0;a<attrs;a++) {
                    int attrName=u2(b,p),len=i4(b,p+2),start=p+6;
                    if(utf(attrName).equals("Code")) {
                        int codeLen=i4(b,start+4),codeStart=start+8;
                        if(codeLen<0||codeStart+codeLen>p+6+len)throw new IOException("Invalid Code attribute");
                        byte[] code=Arrays.copyOfRange(b,codeStart,codeStart+codeLen);
                        List<Op> ops=instructions(code);
                        Map<Integer,String> localClasses=new HashMap<>();
                        // Invalidate local class provenance after control-flow changes.
                        // A class assignment in one branch is not necessarily valid
                        // in a second branch or exception-handler destination.
                        for(int j=0;j<ops.size();j++) {
                            Op op=ops.get(j);int opcode=op.opcode();
                            if((opcode>=0x99&&opcode<=0xa9)||opcode==0xaa||opcode==0xab||
                               opcode==0xc6||opcode==0xc7||opcode==0xc8||opcode==0xc9)
                                localClasses.clear();
                            int store=storeLocal(op,code);
                            if(store>=0)localClasses.remove(store);
                            if(store>=0 && j>0 && callName(ops.get(j-1),"java/lang/Class","forName")) {
                                // Class.forName pushes a Class, immediately saved
                                // to this local. Assign *at* astore (not before it).
                                for(int z=j-2;z>=Math.max(0,j-6);z--) {
                                    Op preceding=ops.get(z);
                                    if(preceding.cp()>0&&(preceding.opcode()==0x12||preceding.opcode()==0x13)) {
                                        Entry candidate=at(preceding.cp());
                                        if(candidate.tag()==8)
                                            localClasses.put(store,utf(candidate.a()).replace('.','/'));
                                        break;
                                    }
                                }
                            }
                            if((opcode!=0x12&&opcode!=0x13)||op.cp()<0)continue;
                            Entry literal=at(op.cp());if(literal.tag()!=8)continue;
                            String source=utf(literal.a());String mapped=null;
                            boolean binaryClass=false,knownReflection=false;
                            String reflectKind="unclassified",sourceOwner="?";
                            if(reflectiveClassLookup(ops,j)) {
                                reflectKind="Class.forName";
                                knownReflection=true;
                                mapped=mappedBinaryClassName(mapping,source);binaryClass=true;
                            } else if(classNameComparison(ops,j)) {
                                reflectKind="Class.getName/equals";
                                knownReflection=true;
                                mapped=mappedBinaryClassName(mapping,source);binaryClass=true;
                            } else if(j>0) {
                                String owner=directClassOwner(ops.get(j-1),localClasses,code);
                                if(owner==null&&callName(ops.get(j-1),"java/lang/Class","forName")) {
                                    for(int z=j-2;z>=Math.max(0,j-6);z--) {
                                        Op preceding=ops.get(z);
                                        if(preceding.cp()>0&&(preceding.opcode()==0x12||preceding.opcode()==0x13)) {
                                            Entry cp=at(preceding.cp());
                                            if(cp.tag()==8)owner=utf(cp.a()).replace('.','/');
                                            break;
                                        }
                                    }
                                }
                                if(owner!=null) {
                                    sourceOwner=owner;
                                    for(int k=j+1;k<Math.min(ops.size(),j+24);k++) {
                                        Op future=ops.get(k);
                                        if(callName(future,"java/lang/invoke/MethodHandles$Lookup","findStatic","findVirtual","findSpecial")) {
                                            knownReflection=true;
                                            reflectKind="MethodHandles.Lookup";
                                            mapped=reflectedName(mapping,owner,source,false,false);break;
                                        }
                                        if(callName(future,"java/lang/Class","getMethod","getDeclaredMethod")) {
                                            knownReflection=true;
                                            boolean inherit=callName(future,"java/lang/Class","getMethod");
                                            reflectKind=inherit?"Class.getMethod":"Class.getDeclaredMethod";
                                            mapped=reflectedName(mapping,owner,source,false,inherit);break;
                                        }
                                        if(callName(future,"java/lang/Class","getField","getDeclaredField")) {
                                            knownReflection=true;
                                            boolean inherit=callName(future,"java/lang/Class","getField");
                                            reflectKind=inherit?"Class.getField":"Class.getDeclaredField";
                                            mapped=reflectedName(mapping,owner,source,true,inherit);break;
                                        }
                                        if(future.opcode()==0x12||future.opcode()==0x13) {
                                            Entry another=at(future.cp());if(another.tag()==8)break;
                                        }
                                        if(future.opcode()==0xb9||future.opcode()==0xba)break;
                                    }
                                }
                            }
                            decisions.computeIfAbsent(op.cp(),unused->new ArrayList<>())
                                     .add(new OperandDecision(op.cp(),mapped,binaryClass,knownReflection,
                                                             helperMethod,op.pc(),reflectKind,sourceOwner));
                        }
                    }
                    p+=6+len;
                }
            }
            // A string constant can be shared by any number of call sites.
            // Change it only if EVERY use is proven and all uses agree, so a
            // mod-owned label/config string with the same text stays intact.
            for(var row:decisions.entrySet()) {
                List<OperandDecision> votes=row.getValue();
                for(var vote:votes)if(vote.knownReflectiveUse()&&vote.mapped()==null) {
                    mapping.unresolvedReflectionSites++;
                    mapping.reflectionGaps.add("UNRESOLVED class="+helperOwner+" method="+vote.method()+
                        " bci="+vote.pc()+" kind="+vote.kind()+" sourceOwner="+vote.sourceOwner()+
                        " literal="+utf(at(row.getKey()).a()));
                }
                if(votes.stream().anyMatch(v->v.mapped()==null)) {
                    if(votes.stream().anyMatch(v->v.mapped()!=null)) {
                        mapping.sharedReflectionLiteralConflicts++;
                        for(var vote:votes)mapping.reflectionGaps.add("SHARED_UNSAFE class="+helperOwner+
                            " method="+vote.method()+" bci="+vote.pc()+" literal="+utf(at(row.getKey()).a())+
                            " proposed="+(vote.mapped()==null?"unresolved/non-reflective":vote.mapped())+
                            " kind="+vote.kind());
                    }
                    continue;
                }
                Set<String> names=new TreeSet<>();for(var vote:votes)names.add(vote.mapped());
                if(names.size()!=1) {
                    mapping.sharedReflectionLiteralConflicts++;
                    mapping.warnings.add("Conflicting reflective mappings for shared literal: "+utf(at(row.getKey()).a()));
                    for(var vote:votes)mapping.reflectionGaps.add("SHARED_DIFFERENT class="+helperOwner+
                        " method="+vote.method()+" bci="+vote.pc()+" literal="+utf(at(row.getKey()).a())+
                        " proposed="+vote.mapped()+" kind="+vote.kind());
                    continue;
                }
                String target=names.iterator().next(),old=utf(at(row.getKey()).a());
                if(!old.equals(target)) {
                    pool.set(row.getKey(),new Entry(8,putUtf(target),0,0,null));
                    if(votes.stream().allMatch(OperandDecision::className))mapping.remappedClassNameLiterals++;
                    else mapping.remappedReflectiveNames++;
                }
            }
        }
        /** Replace Class reflection API calls with a BML-owned per-helper bridge.
         *  invokevirtual and invokestatic are both 3-byte instructions, so this
         *  changes no bytecode offsets, exception tables or StackMap frames.
         *  The original string constants remain unchanged (including shared literals).
         */
        private int putClass(String internal)throws IOException {
            int u=putUtf(internal);
            for(int i=1;i<pool.size();i++) {Entry e=pool.get(i);if(e!=null&&e.tag()==7&&e.a()==u)return i;}
            if(pool.size()>=65535)throw new IOException("Constant pool exhausted");
            int i=pool.size();pool.add(new Entry(7,u,0,0,null));return i;
        }
        private int putMethodRef(String owner,String name,String desc)throws IOException {
            int c=putClass(owner),nt=putNameType(name,desc);
            for(int i=1;i<pool.size();i++) {Entry e=pool.get(i);if(e!=null&&e.tag()==10&&e.a()==c&&e.b()==nt)return i;}
            if(pool.size()>=65535)throw new IOException("Constant pool exhausted");
            int i=pool.size();pool.add(new Entry(10,c,nt,0,null));return i;
        }
        /** Locate mod-owned helpers which read resolved target names from a
         *  Properties binding and normalize them to binary class names. This
         *  establishes target-version provenance without relying on mod names
         *  or on the helper's chosen method name.
         */
        private Set<String> boundTargetNameResolvers() throws IOException {
            Set<String> names=new HashSet<>();
            byte[] b=body;int p=6;
            int count=u2(b,p);p+=2+2*count;p=skipMembers(b,p);
            int methods=u2(b,p);p+=2;
            String thisClass=nameOf(u2(b,2));
            for(int m=0;m<methods;m++){
                String method=utf(u2(b,p+2)),desc=utf(u2(b,p+4));p+=6;
                int attrs=u2(b,p);p+=2;
                for(int a=0;a<attrs;a++){
                    int len=i4(b,p+2),start=p+6;
                    if(utf(u2(b,p)).equals("Code")&&desc.equals("(Ljava/lang/String;)Ljava/lang/String;")){
                        int size=i4(b,start+4),begin=start+8;
                        List<Op> ops=instructions(Arrays.copyOfRange(b,begin,begin+size));
                        boolean normalizesName=false,readsModTarget=false;
                        for(Op op:ops){
                            if(op.cp()<=0)continue;
                            if(memberCall(op,"java/lang/String","replace","(CC)Ljava/lang/String;"))
                                normalizesName=true;
                            if(op.opcode()==0xb8){
                                Entry e=at(op.cp());
                                if(e.tag()==10&&nameOf(e.a()).equals(thisClass)){
                                    Entry nt=at(e.b());
                                    if(utf(nt.b()).equals("(Ljava/util/Properties;Ljava/lang/String;)Ljava/lang/String;"))
                                        readsModTarget=true;
                                }
                            }
                        }
                        if(normalizesName&&readsModTarget)names.add(method+desc);
                    }
                    p+=6+len;
                }
            }
            return names;
        }
        /** Bounded same-expression matching for
         *     resolved target class name == someObject.getClass().getName().
         *  Legacy comparisons to source-version names remain on getName().
         */
        private boolean comparesBoundTargetName(List<Op> ops,int index,Set<String> resolvers,
                String currentClass)throws IOException {
            if(resolvers.isEmpty()||index+1>=ops.size()||index<3)return false;
            if(!memberCall(ops.get(index+1),"java/lang/String","equals","(Ljava/lang/Object;)Z"))
                return false;
            if(!memberCall(ops.get(index-1),"java/lang/Object","getClass","()Ljava/lang/Class;"))
                return false;
            for(int k=Math.max(0,index-6);k<index-1;k++){
                Op call=ops.get(k);
                if(call.opcode()!=0xb8||call.cp()<=0)continue;
                Entry entry=at(call.cp());
                if(entry.tag()!=10||!nameOf(entry.a()).equals(currentClass))continue;
                Entry nt=at(entry.b());
                if(!resolvers.contains(utf(nt.a())+utf(nt.b())))continue;
                // The manifest target id may be passed through a parameter,
                // not just supplied by an LDC literal at the callsite.
                return true;
            }
            return false;
        }
        /** A helper can cache a BML-resolved physical target owner into a local
         *  variable before comparing it with a superclass' Class.getName().
         *  The older immediate expression matcher cannot see this pattern.
         *  The owner comes from a TargetBinding, not a legacy source literal:
         *  the reflected Class name MUST therefore remain a target JVM name.
         *  Identify the entire resolver method by its owner-field read,
         *  binary-name normalization and actual reflective method lookup.
         *  This is based on provenance/bytecode rather than specific mod IDs.
         */
        private boolean comparesStoredPhysicalOwner(List<Op> ops,byte[] code,String currentClass)throws IOException {
            boolean targetOwner=false,normalizesOwner=false;
            for(Op op:ops){
                if(op.opcode()==0xb4){
                    // The general instruction walker only exposes CP operands
                    // for constants/invocations. Read GETFIELD's pool index
                    // directly here, without broadening other remap passes.
                    Entry member=at(u2(code,op.pc()+1));
                    if(member.tag()!=9)continue;
                    String fieldOwner=nameOf(member.a());
                    Entry nt=at(member.b());
                    if(fieldOwner.endsWith("$TargetBinding")&&utf(nt.a()).equals("owner")&&
                       utf(nt.b()).equals("Ljava/lang/String;"))targetOwner=true;
                }else if(op.opcode()==0xb8 && op.cp()>0){
                    Entry member=at(op.cp());
                    if(member.tag()!=10)continue;
                    if(nameOf(member.a()).equals(currentClass)){
                        Entry nt=at(member.b());
                        if(utf(nt.b()).equals("(Ljava/lang/String;)Ljava/lang/String;")&&
                           utf(nt.a()).equals("binaryName"))normalizesOwner=true;
                    }
                }
            }
            return targetOwner&&normalizesOwner;
        }
        private void patchReflectionCalls(Mapping mapping,String bridge)throws IOException {
            byte[] b=body;int p=6;
            Set<String> boundResolvers=boundTargetNameResolvers();
            String currentClass=nameOf(u2(b,2));
            int ni=u2(b,p);p+=2+2*ni;p=skipMembers(b,p);
            int methods=u2(b,p);p+=2;
            for(int m=0;m<methods;m++) {
                p+=6;int attrs=u2(b,p);p+=2;
                for(int a=0;a<attrs;a++) {
                    int name=u2(b,p),len=i4(b,p+2),start=p+6;
                    if(utf(name).equals("Code")) {
                        int size=i4(b,start+4),begin=start+8;
                        if(size<0||begin+size>p+6+len)throw new IOException("Invalid Code section");
                        byte[] code=Arrays.copyOfRange(b,begin,begin+size);
                        // Descriptor constructors typically branch on primitive/array
                        // Class types and normalize binary dots into JVM slashes.
                        // Those callsites must observe actual TARGET Class.getName(),
                        // whereas ordinary legacy source-name comparisons still use
                        // the source-aware reflection bridge. Match semantics, not
                        // a particular mod or helper class name.
                        List<Op> decoded=instructions(code);
                        boolean buildsJvmDescriptor=
                            decoded.stream().anyMatch(o->{try{return callName(o,"java/lang/Class","isPrimitive");}catch(IOException ex){return false;}}) &&
                            decoded.stream().anyMatch(o->{try{return callName(o,"java/lang/Class","isArray");}catch(IOException ex){return false;}}) &&
                            decoded.stream().anyMatch(o->{try{return memberCall(o,"java/lang/String","replace","(CC)Ljava/lang/String;");}catch(IOException ex){return false;}});
                        boolean usesStoredPhysicalOwner=comparesStoredPhysicalOwner(decoded,code,currentClass);
                        for(int opIndex=0;opIndex<decoded.size();opIndex++) {
                            Op op=decoded.get(opIndex);
                            if(op.cp()<=0||(op.opcode()!=0xb6&&op.opcode()!=0xb8))continue;
                            Entry e=at(op.cp());if(e.tag()!=10)continue;
                            String owner=nameOf(e.a());Entry nt=at(e.b());
                            String method=utf(nt.a()),descriptor=utf(nt.b());
                            boolean supported=false;String targetDesc=descriptor;
                            if(owner.equals("java/lang/Class")) {
                                supported=switch(method) {
                                    case "forName" -> op.opcode()==0xb8 &&
                                        (descriptor.equals("(Ljava/lang/String;)Ljava/lang/Class;")||
                                        descriptor.equals("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"));
                                    case "getMethod","getDeclaredMethod" -> op.opcode()==0xb6 && descriptor.equals("(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
                                    case "getField","getDeclaredField" -> op.opcode()==0xb6 && descriptor.equals("(Ljava/lang/String;)Ljava/lang/reflect/Field;");
                                    case "getName","getSimpleName","getTypeName" -> op.opcode()==0xb6 && descriptor.equals("()Ljava/lang/String;");
                                    default -> false;
                                };
                                if(supported&&op.opcode()==0xb6)targetDesc="(Ljava/lang/Class;"+descriptor.substring(1);
                            } else if(owner.equals("java/lang/ClassLoader") && method.equals("loadClass") &&
                                      op.opcode()==0xb6 && descriptor.equals("(Ljava/lang/String;)Ljava/lang/Class;")) {
                                supported=true;targetDesc="(Ljava/lang/ClassLoader;Ljava/lang/String;)Ljava/lang/Class;";
                            } else if(owner.equals("java/lang/reflect/Method") && method.equals("getName") &&
                                      op.opcode()==0xb6 && descriptor.equals("()Ljava/lang/String;")) {
                                supported=true;targetDesc="(Ljava/lang/reflect/Method;)Ljava/lang/String;";
                            }
                            if(owner.equals("java/lang/invoke/MethodHandles$Lookup")&&op.opcode()==0xb6) {
                                supported=switch(method) {
                                    case "findStatic","findVirtual" -> descriptor.equals("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
                                    case "findSpecial" -> descriptor.equals("(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
                                    default -> false;
                                };
                                if(supported)targetDesc="(Ljava/lang/invoke/MethodHandles$Lookup;"+descriptor.substring(1);
                            }
                            if(!supported)continue;
                            String bridgeMethod=owner.equals("java/lang/reflect/Method")&&method.equals("getName")
                                ?"getMethodName":owner.equals("java/lang/Class")&&method.equals("getName")&&
                                    (buildsJvmDescriptor||usesStoredPhysicalOwner||comparesBoundTargetName(decoded,opIndex,boundResolvers,currentClass))
                                ?"getActualName":method;
                            int ref=putMethodRef(bridge,bridgeMethod,targetDesc);
                            b[begin+op.pc()]=(byte)0xb8;
                            b[begin+op.pc()+1]=(byte)(ref>>>8);b[begin+op.pc()+2]=(byte)ref;
                            mapping.bridgeInvocations++;
                        }
                    }
                    p+=6+len;
                }
            }
        }
        void renameBridgeTemplate(String template,String generated)throws IOException {
            for(int i=1;i<pool.size();i++) {
                Entry e=pool.get(i);
                if(e!=null&&e.tag()==1&&e.value().contains(template))
                    pool.set(i,new Entry(1,0,0,0,e.value().replace(template,generated)));
            }
            stringIndices.clear();
            for(int i=1;i<pool.size();i++)if(pool.get(i)!=null&&pool.get(i).tag()==1)
                stringIndices.putIfAbsent(pool.get(i).value(),i);
        }
        void rewrite(Mapping mapping,String bridge)throws IOException {
            patchReflectionCalls(mapping,bridge);
            // Capture all member reference lookups before any constant-pool mutations.
            record Ref(int index,int tag,int classIdx,int natIdx,String owner,String name,String desc) {}
            List<Ref> refs=new ArrayList<>();
            int initial=pool.size();
            for(int i=1;i<initial;i++) {
                Entry e=pool.get(i);if(e==null||!(e.tag()==9||e.tag()==10||e.tag()==11))continue;
                Entry nt=at(e.b());if(nt.tag()!=12)throw new IOException("Invalid NameAndType constant");
                refs.add(new Ref(i,e.tag(),e.a(),e.b(),nameOf(e.a()),utf(nt.a()),utf(nt.b())));
            }
            // Rename declared mod-owned overrides, in addition to their call sites.
            // Only mappings inferred from the ORIGINAL source superclass/interfaces can
            // affect helper declarations, so overlapping obfuscated names remain one-pass.
            rewriteDeclaredOverrides(mapping);
            // Capture Class identity before changing Utf8 descriptor entries. A Class
            // entry for an array can point to the SAME Utf8 value as a signature.
            // Names may overlap across builds (e.g. dvm -> dve, dve -> duW):
            // consulting the already-translated value would apply the map twice.
            Map<Integer,String> originalClassNames=new HashMap<>();
            for(int i=1;i<initial;i++) {
                Entry e=pool.get(i);
                if(e!=null && e.tag()==7)originalClassNames.put(i,utf(e.a()));
            }
            // Remap descriptor/signature constants. This also updates declared helper
            // method and field signatures, annotations, stack-frame types etc.
            for(int i=1;i<initial;i++){
                Entry e=pool.get(i);if(e==null||e.tag()!=1)continue;
                String rewritten=descriptor(e.value(),mapping);
                if(!e.value().equals(rewritten)){
                    pool.set(i,new Entry(1,0,0,0,rewritten));
                }
            }
            // Add/repair lookup indices after global UTF8 modifications.
            stringIndices.clear();
            for(int i=1;i<pool.size();i++)if(pool.get(i)!=null&&pool.get(i).tag()==1)
                stringIndices.putIfAbsent(pool.get(i).value(),i);
            for(int i=1;i<initial;i++){
                Entry e=pool.get(i);if(e==null||e.tag()!=7)continue;
                // Class entries also represent array descriptors.
                String type=originalClassNames.get(i);
                String mapped=type.startsWith("[")?descriptor(type,mapping):mapping.map(type);
                if(!type.equals(mapped))pool.set(i,new Entry(7,putUtf(mapped),0,0,null));
            }
            // Update member references independently: a shared NameAndType constant
            // can represent different physical owners and must not be edited in place.
            for(Ref r:refs) {
                Member target=r.tag()==9?mapping.fields.get(new Key(r.owner(),r.name(),r.desc())):
                         mapping.methods.get(new Key(r.owner(),r.name(),r.desc()));
                String name=r.name(),desc=descriptor(r.desc(),mapping);
                if(target!=null) {
                    String desiredOwner=mapping.classes.getOrDefault(r.owner(),r.owner());
                    if(!desiredOwner.equals(target.owner())){
                        mapping.warnings.add("Member owner does not match class mapping: "+r.owner()+"."+r.name()+r.desc()+" -> "+target);
                    }else{
                        name=target.name();desc=target.descriptor();
                        if(!name.equals(r.name())) {
                            if(r.tag()==9)mapping.remappedFields++;else mapping.remappedMethods++;
                        }
                    }
                }
                Entry old=pool.get(r.index());
                pool.set(r.index(),new Entry(old.tag(),old.a(),putNameType(name,desc),0,null));
            }
        }
        byte[] save()throws IOException {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(DataOutputStream out=new DataOutputStream(bytes)) {
                out.writeInt(magic);out.writeShort(minor);out.writeShort(major);
                out.writeShort(pool.size());
                for(int i=1;i<pool.size();i++){
                    Entry e=pool.get(i);if(e==null)continue;
                    out.writeByte(e.tag());
                    switch(e.tag()){
                        case 1 -> out.writeUTF(e.value());
                        case 3,4 -> out.writeInt((int)e.bits());
                        case 5,6 -> out.writeLong(e.bits());
                        case 7,8,16,19,20 -> out.writeShort(e.a());
                        case 9,10,11,12,17,18 -> {out.writeShort(e.a());out.writeShort(e.b());}
                        case 15 -> {out.writeByte(e.a());out.writeShort(e.b());}
                        default -> throw new IOException("Unknown tag "+e.tag());
                    }
                }
                out.write(body);
            }
            return bytes.toByteArray();
        }
    }
    /** Resolve calls whose symbolic owner is the mod subclass rather than the Bitwig
     * superclass declaring the method. JVM permits invokevirtual Helper.oldName
     * even when oldName is inherited. Cross-version maps are keyed by the real
     * Bitwig declaring owner, so these need a separate alias pass.
     * Only source ancestry is followed, never target-name ancestry (obfuscated
     * identifiers overlap between versions). Ambiguous paths are not guessed.
     */
    private static void inferHelperOwnedInheritedCalls(Mapping mapping, List<ClassFile> sourceFiles) throws IOException {
        Map<String,ClassFile> helper=new HashMap<>();
        Map<String,Set<Key>> declarations=new HashMap<>();
        for(ClassFile file:sourceFiles) {
            String own=file.className();helper.put(own,file);
            Set<Key> ownDecls=new HashSet<>();
            for(ClassFile.Decl d:file.declarations())ownDecls.add(new Key(own,d.name(),d.desc()));
            declarations.put(own,ownDecls);
        }
        // Collect all uses before appending aliases, so aliases never cause
        // their own recursive reinterpretation.
        Set<Key> candidateMethods=new LinkedHashSet<>();
        Set<Key> candidateFields=new LinkedHashSet<>();
        for(ClassFile f:sourceFiles) for(int i=1;i<f.pool.size();i++) {
            ClassFile.Entry e=f.pool.get(i);
            if(e==null || !(e.tag()==9||e.tag()==10||e.tag()==11))continue;
            String own=f.nameOf(e.a());
            ClassFile.Entry nt=f.at(e.b());
            Key key=new Key(own,f.utf(nt.a()),f.utf(nt.b()));
            if(helper.containsKey(own)) {
                if(e.tag()==9)candidateFields.add(key);
                else if(!declarations.get(own).contains(key))candidateMethods.add(key);
            } else if(mapping.classes.containsKey(own) && mapping.sourceParents.containsKey(own)) {
                // a Bitwig class used as the owner of a member it inherits: map packs carry rows for the
                // declaring class, so resolve through the source hierarchy like the JVM does
                if(e.tag()==9)candidateFields.add(key);else candidateMethods.add(key);
            }
        }
        for(Key key:candidateMethods) {
            if(mapping.methods.containsKey(key))continue;
            Member result=resolveInheritedMember(mapping,helper,key,true);
            if(result!=null) {mapping.methods.put(key,result);mapping.remappedInheritedHelperCalls++;}
        }
        for(Key key:candidateFields) {
            if(mapping.fields.containsKey(key))continue;
            Member result=resolveInheritedMember(mapping,helper,key,false);
            if(result!=null) {mapping.fields.put(key,result);mapping.remappedInheritedHelperCalls++;}
        }
    }
    private static Member resolveInheritedMember(Mapping mapping,Map<String,ClassFile> helper,Key ref,boolean method)throws IOException {
        Set<String> visited=new HashSet<>();
        ArrayDeque<String> pending=new ArrayDeque<>();
        ClassFile start=helper.get(ref.owner());
        String resultOwner=ref.owner();
        if(start!=null) {
            int sup=start.u2(start.body,4);
            if(sup!=0)pending.add(start.nameOf(sup));
            int n=start.u2(start.body,6);
            for(int i=0;i<n;i++)pending.add(start.nameOf(start.u2(start.body,8+i*2)));
        } else {
            // Bitwig-owned reference: start from the owner's source parents; the owner itself is renamed
            pending.addAll(mapping.sourceParents.getOrDefault(ref.owner(),List.of()));
            resultOwner=mapping.classes.get(ref.owner());
        }
        Set<Member> choices=new HashSet<>();
        while(!pending.isEmpty()) {
            String owner=pending.removeFirst();
            if(!visited.add(owner))continue;
            Member mapped=(method?mapping.methods:mapping.fields).get(new Key(owner,ref.name(),ref.descriptor()));
            if(mapped!=null && mapped.owner().equals(mapping.classes.getOrDefault(owner,owner))) {
                choices.add(new Member(resultOwner,mapped.name(),mapped.descriptor()));
                continue;  // the nearest declaration wins; don't look past it on this path
            }
            if(helper.containsKey(owner)) {
                ClassFile h=helper.get(owner);
                int hs=h.u2(h.body,4);if(hs!=0)pending.add(h.nameOf(hs));
                int ni=h.u2(h.body,6);
                for(int i=0;i<ni;i++)pending.add(h.nameOf(h.u2(h.body,8+i*2)));
            }else pending.addAll(mapping.sourceParents.getOrDefault(owner,List.of()));
        }
        if(choices.size()>1) {
            mapping.warnings.add("Ambiguous inherited helper call: "+ref+" -> "+choices);
            return null;
        }
        return choices.isEmpty()?null:choices.iterator().next();
    }

    private static void run(Path original,Path output,Path primary,Path overrides,Path report,String modId) throws Exception {
        Mapping mapping = new Mapping();
        mapping.load(primary);
        if(Files.isRegularFile(overrides))mapping.load(overrides);
        int total=0;int changed=0;
        Files.createDirectories(output.toAbsolutePath().getParent());
        // Work on original source identities only; no remapped data is fed back.
        List<ClassFile> originals=new ArrayList<>();
        try(ZipFile probe=new ZipFile(original.toFile())) {
            Enumeration<? extends ZipEntry> entries=probe.entries();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();
                if(entry.isDirectory()||!entry.getName().endsWith(".class"))continue;
                try(InputStream in=probe.getInputStream(entry)) {
                    originals.add(new ClassFile(in.readAllBytes()));
                }
            }
        }
        // One prepared-only bridge identity per ORIGINAL helper+mapping combination.
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(original));digest.update(Files.readAllBytes(primary));digest.update(modId.getBytes(StandardCharsets.UTF_8));
        StringBuilder nameSuffix=new StringBuilder();
        for(byte octet:Arrays.copyOf(digest.digest(),8))nameSuffix.append(String.format("%02x",octet&255));
        String template="BmlReflectionBridgeTemplate",generated="BmlReflectionBridge"+nameSuffix;
        String packageName="dev/bitwigre/bml/bridge/";
        String bridge=packageName+generated;
        Set<String> literals=new TreeSet<>();
        for(ClassFile f:originals)for(int k=1;k<f.pool.size();k++) {
            ClassFile.Entry e=f.pool.get(k);
            if(e!=null&&e.tag()==8)literals.add(f.utf(e.a()));
        }
        for(ClassFile c:originals)c.inferOverrides(mapping);
        inferHelperOwnedInheritedCalls(mapping,originals);
        try(ZipFile input=new ZipFile(original.toFile());ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(output))) {
            Enumeration<? extends ZipEntry> entries=input.entries();
            Set<String> names=new HashSet<>();
            while(entries.hasMoreElements()) {
                ZipEntry entry=entries.nextElement();
                if(entry.isDirectory())continue;
                String name=entry.getName();
                if(!names.add(name))throw new IOException("Duplicate helper entry: "+name);
                byte[] data;
                try(InputStream in=input.getInputStream(entry)) {data=in.readAllBytes();}
                if(name.endsWith(".class")) {
                    total++;
                    ClassFile remapped=new ClassFile(data);
                    remapped.rewrite(mapping,bridge);
                    byte[] processed=remapped.save();
                    if(!Arrays.equals(data,processed))changed++;
                    data=processed;
                    // Only remap Bitwig references. Mod helper class names remain stable.
                    ClassFile checked=new ClassFile(data);
                    if(!checked.className().equals(name.substring(0,name.length()-6)))
                        throw new IOException("Helper class path changed: "+name+" -> "+checked.className());
                }
                ZipEntry newEntry=new ZipEntry(name);
                out.putNextEntry(newEntry);out.write(data);out.closeEntry();
            }
            if(mapping.bridgeInvocations>0) {
                String resource="dev/bitwigre/bml/bridge/"+generated+".map.gz";
                ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                try(GZIPOutputStream gz=new GZIPOutputStream(buffer);
                    BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(gz,StandardCharsets.UTF_8))) {
                    writer.write("O\t"+modId+"\n");
                    List<String> keys=new ArrayList<>(mapping.classes.keySet());Collections.sort(keys);
                    for(String old:keys)writer.write("C\t"+old+"\t"+mapping.classes.get(old)+"\n");
                    List<Key> ms=new ArrayList<>(mapping.methods.keySet());
                    ms.sort(Comparator.comparing(Key::owner).thenComparing(Key::name).thenComparing(Key::descriptor));
                    for(Key key:ms)if(literals.contains(key.name())) {
                        Member v=mapping.methods.get(key);
                        writer.write("M\t"+key.owner()+"\t"+key.name()+"\t"+key.descriptor()+"\t"+v.owner()+"\t"+v.name()+"\t"+v.descriptor()+"\n");
                    }
                    List<Key> fs=new ArrayList<>(mapping.fields.keySet());
                    fs.sort(Comparator.comparing(Key::owner).thenComparing(Key::name).thenComparing(Key::descriptor));
                    for(Key key:fs)if(literals.contains(key.name())) {
                        Member v=mapping.fields.get(key);
                        writer.write("F\t"+key.owner()+"\t"+key.name()+"\t"+key.descriptor()+"\t"+v.owner()+"\t"+v.name()+"\t"+v.descriptor()+"\n");
                    }
                }
                String templateClass=packageName+template+".class";
                try(InputStream stream=HelperBytecodeRemapper.class.getClassLoader().getResourceAsStream(templateClass)) {
                    if(stream==null)throw new IOException("Missing BML bridge template in remapper tool");
                    ClassFile generatedClass=new ClassFile(stream.readAllBytes());
                    generatedClass.renameBridgeTemplate(template,generated);
                    out.putNextEntry(new ZipEntry(bridge+".class"));
                    out.write(generatedClass.save());out.closeEntry();
                }
                out.putNextEntry(new ZipEntry(resource));out.write(buffer.toByteArray());out.closeEntry();
                mapping.bridgeClasses++;
            }
        }
        List<String> lines = new ArrayList<>(List.of(
            "format=bml-helper-bytecode-remap-1",
            "input="+original.getFileName(),
            "classes_examined="+total,
            "classes_rewritten="+changed,
            "type_references_translated="+mapping.remappedClasses,
            "method_names_translated="+mapping.remappedMethods,
            "helper_override_declarations_translated="+mapping.remappedOverrides,
            "helper_inherited_calls_translated="+mapping.remappedInheritedHelperCalls,
            "field_names_translated="+mapping.remappedFields,
            "reflective_name_constants_translated="+mapping.remappedReflectiveNames,
            "reflected_class_name_constants_translated="+mapping.remappedClassNameLiterals,
            "runtime_reflection_bridge_calls="+mapping.bridgeInvocations,
            "runtime_reflection_bridge_classes="+mapping.bridgeClasses,
            "known_reflection_operands_unresolved="+mapping.unresolvedReflectionSites,
            "shared_reflection_literals_incompatible="+mapping.sharedReflectionLiteralConflicts,
            "reflection_preflight_status="+(mapping.bridgeInvocations>0?"BRIDGE_INSTALLED":"NO_SUPPORTED_REFLECTION_CALLS"),
            "reflection_gap_details="+mapping.reflectionGaps.size(),
            "warnings="+mapping.warnings.size()));
        int printedGaps=0;
        for(String gap:mapping.reflectionGaps) {
            if(printedGaps++>=200)break;
            lines.add("REFLECTION_GAP: "+gap);
        }
        if(mapping.reflectionGaps.size()>200)lines.add("REFLECTION_GAP: truncated="+(mapping.reflectionGaps.size()-200));
        for(String warning:mapping.warnings)lines.add("WARNING: "+warning);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.write(report,lines,StandardCharsets.UTF_8);
        for(String line:lines)System.out.println("AUTO_REMAP "+line);
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=5&&args.length!=6)throw new IllegalArgumentException("Usage: HelperBytecodeRemapper input.jar output.jar map.tsv.gz overrides.tsv report.txt [mod-id]");
        run(Path.of(args[0]),Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),Path.of(args[4]),args.length==6?args[5]:"");
    }
}
