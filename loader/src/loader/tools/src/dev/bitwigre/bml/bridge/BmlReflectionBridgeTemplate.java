package dev.bitwigre.bml.bridge;

import java.io.*;
import java.lang.reflect.*;
import java.lang.invoke.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/** BML-generated prepared-helper reflection adapter. Never installed in an original mod. */
public final class BmlReflectionBridgeTemplate {
    private BmlReflectionBridgeTemplate() {}
    // Session-local diagnostic logging is disabled for normal (non-debug) launches.
    // Writes only BML bridge lookup outcomes; never requires mod source edits.
    private static final String TRACE_ROOT="1".equals(System.getenv("BML_TRACE_ENABLED"))
        ? System.getenv("BML_SESSION_ROOT") : null;
    // Limit repetitive successes separately so hot native UI paths cannot obscure
    // a later mapping ambiguity/failure. Log each distinct lookup outcome once.
    private static final AtomicInteger TRACE_SUCCESSES=new AtomicInteger();
    private static final AtomicInteger TRACE_ISSUES=new AtomicInteger();
    private static final Set<String> TRACE_SEEN=ConcurrentHashMap.newKeySet();
    private static final int TRACE_SUCCESS_MAX=160, TRACE_ISSUE_MAX=450;
    private static String safe(String s) {
        if(s==null)return "";
        return s.replace('\t',' ').replace('\r',' ').replace('\n',' ')
                .substring(0,Math.min(180,s.length()));
    }
    private static void trace(String api,String owner,String source,String mapped,String status) {
        if(TRACE_ROOT==null||TRACE_ROOT.isBlank())return;
        boolean issue=!("OK".equals(status)||"MAPPED:OK".equals(status)||"BOUND_TARGET:OK".equals(status));
        String key=api+"\t"+owner+"\t"+source+"\t"+mapped+"\t"+status;
        if(!TRACE_SEEN.add(key))return;
        if(issue) {
            if(TRACE_ISSUES.incrementAndGet()>TRACE_ISSUE_MAX)return;
        } else if(TRACE_SUCCESSES.incrementAndGet()>TRACE_SUCCESS_MAX)return;
        String line=System.currentTimeMillis()+"\t"+safe(api)+"\t"+safe(owner)+"\t"+safe(source)+"\t"+
            safe(mapped)+"\t"+safe(status)+"\n";
        try {
            Path file=Path.of(TRACE_ROOT,"reflection-bridge.tsv");
            synchronized(BmlReflectionBridgeTemplate.class) {
                Files.writeString(file,line,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            }
        } catch(Exception ignored) { /* Diagnostics cannot change mod/Bitwig behavior. */ }
    }
    private static String describe(Exception ex) {
        return ex.getClass().getSimpleName()+":"+safe(ex.getMessage());
    }
    // Bindings are already translated by BML; they must not pass a second
    // source-name translation merely because the obfuscated target name is
    // also the name of a different source-version class.
    private static String BRIDGE_MOD_ID="";
    private static final Set<String> TARGET_CLASSES=new HashSet<>();
    private static final Set<String> TARGET_FIELDS=new HashSet<>();
    private static final Set<String> TARGET_METHODS=new HashSet<>();
    private static final Map<String,String> CLASS_MAP=new HashMap<>();
    private static final Map<String,String> TARGET_TO_SOURCE=new HashMap<>();
    private static final Map<String,List<String[]>> METHODS=new HashMap<>();
    // Reverse reflection names against the method's actual DECLARING class, not
    // the receiver's concrete class (which may inherit the method).
    // Materialize the reverse index only if a helper actually calls Method.getName.
    // A snapshot can hold hundreds of thousands of member mappings, so do not
    // eagerly allocate a second Map-of-Sets for every mod that uses reflection.
    private static volatile Map<String,String> TARGET_METHOD_NAMES;
    private static final Map<String,List<String[]>> FIELDS=new HashMap<>();
    static {
        try(InputStream source=BmlReflectionBridgeTemplate.class.getResourceAsStream("BmlReflectionBridgeTemplate.map.gz")) {
            if(source==null)throw new IllegalStateException("Missing BML reflection mapping snapshot");
            try(BufferedReader in=new BufferedReader(new InputStreamReader(new GZIPInputStream(source),StandardCharsets.UTF_8))) {
                String line;
                while((line=in.readLine())!=null) {
                    if(line.startsWith("#")||line.isEmpty())continue;
                    String[] col=line.split("\t",-1);
                    switch(col[0]) {
                        case "O":
                            if(col.length==2)BRIDGE_MOD_ID=col[1];
                            break;
                        case "C":
                            if(col.length==3) {
                                CLASS_MAP.put(col[1],col[2]);
                                String old=TARGET_TO_SOURCE.putIfAbsent(col[2],col[1]);
                                if(old!=null&&!old.equals(col[1]))TARGET_TO_SOURCE.put(col[2],"");
                            }
                            break;
                        case "M":
                            if(col.length==7) {
                                METHODS.computeIfAbsent(col[2],k->new ArrayList<>()).add(col);
                            }
                            break;
                        case "F":
                            if(col.length==7)FIELDS.computeIfAbsent(col[2],k->new ArrayList<>()).add(col);
                            break;
                    }
                }
            }
        } catch(IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    // The bridge is generated once per mod. Only that mod's BML target
    // bindings are protected; other mods' old obfuscated literals continue
    // to be translated independently.
    static {
        String path=System.getenv("BML_MOD_CONFIG");
        if(path!=null&&!path.isBlank()) {
            try(Reader reader=Files.newBufferedReader(Path.of(path),StandardCharsets.UTF_8)) {
                Properties p=new Properties();p.load(reader);
                for(String key:p.stringPropertyNames()) {
                    if(!key.contains(".target."))continue;
                    if(!BRIDGE_MOD_ID.isBlank()&&!key.startsWith("mod."+BRIDGE_MOD_ID+".target."))continue;
                    if(key.endsWith(".class"))TARGET_CLASSES.add(p.getProperty(key).replace('.','/'));
                    else if(key.endsWith(".owner")) {
                        String prefix=key.substring(0,key.length()-6);
                        String owner=p.getProperty(key).replace('.','/');
                        TARGET_CLASSES.add(owner);
                        String member=p.getProperty(prefix+".name");
                        if(member==null)continue;
                        String kind=p.getProperty(prefix+".kind","");
                        if("field".equals(kind))TARGET_FIELDS.add(owner+"#"+member);
                        if("method".equals(kind)) {
                            String desc=p.getProperty(prefix+".descriptor","");
                            int end=desc.indexOf(')');
                            if(end>=0)TARGET_METHODS.add(owner+"#"+member+"#"+desc.substring(0,end+1));
                        }
                    }
                }
            } catch(Exception ex) {
                trace("boundTargets",BRIDGE_MOD_ID,path,"", "UNAVAILABLE:"+describe(ex));
            }
        }
    }
    private static boolean pinnedClass(String name) {
        return TARGET_CLASSES.contains(name.replace('.','/'));
    }
    private static boolean pinnedField(Class<?> owner,String name) {
        return TARGET_FIELDS.contains(owner.getName().replace('.','/')+"#"+name);
    }
    private static boolean pinnedMethod(Class<?> owner,String name,Class<?>[] args) {
        return TARGET_METHODS.contains(owner.getName().replace('.','/')+"#"+name+"#"+parameters(args));
    }
    private static String remapClassName(String name) {
        if(name==null)return null;
        if(name.startsWith("[")) {
            StringBuilder b=new StringBuilder();int i=0;
            while(i<name.length()&&name.charAt(i)=='[')b.append(name.charAt(i++));
            if(i<name.length()&&name.charAt(i)=='L'&&name.endsWith(";")) {
                String original=name.substring(i+1,name.length()-1).replace('.','/');
                return b+"L"+(pinnedClass(original)?original:CLASS_MAP.getOrDefault(original,original)).replace('/','.')+";";
            }
            return name;
        }
        String key=name.replace('.','/');
        return (pinnedClass(key)?key:CLASS_MAP.getOrDefault(key,key)).replace('/','.');
    }
    public static Class<?> forName(String name) throws ClassNotFoundException {
        String target=remapClassName(name);
        try {Class<?> result=Class.forName(target);trace("forName","",name,target,pinnedClass(name)?"BOUND_TARGET:OK":"OK");return result;}
        catch(ClassNotFoundException ex){trace("forName","",name,target,describe(ex));throw ex;}
    }
    public static Class<?> forName(String name,boolean initialize,ClassLoader loader)throws ClassNotFoundException {
        String target=remapClassName(name);
        try {Class<?> result=Class.forName(target,initialize,loader);trace("forName3","",name,target,pinnedClass(name)?"BOUND_TARGET:OK":"OK");return result;}
        catch(ClassNotFoundException ex){trace("forName3","",name,target,describe(ex));throw ex;}
    }
    public static Class<?> loadClass(ClassLoader loader,String name)throws ClassNotFoundException {
        String target=remapClassName(name);
        try {Class<?> result=loader.loadClass(target);trace("loadClass","",name,target,pinnedClass(name)?"BOUND_TARGET:OK":"OK");return result;}
        catch(ClassNotFoundException ex){trace("loadClass","",name,target,describe(ex));throw ex;}
    }
    private static String oldInternal(Class<?> type) {
        if(type.isArray()) {
            String part=oldInternal(type.getComponentType());
            if(type.getComponentType().isPrimitive())return "["+descriptor(type.getComponentType());
            return "[L"+part+";";
        }
        String target=type.getName().replace('.','/');
        // Preserve the actual target identity whenever it is explicitly
        // present in this mod's BML-resolved binding (owner or descriptor).
        // Returning a source alias here breaks descriptor checks, even though
        // the correctly mapped Class and Field have already been obtained.
        String old=TARGET_TO_SOURCE.get(target);
        return old==null||old.isEmpty()?target:old;
    }
    // Preserve the actual JVM type name when the consumer is constructing a
    // JVM descriptor from a BML-resolved target Field/Method. A reverse-mapped
    // source alias is appropriate for some legacy getName() equality checks,
    // but NOT for verifying a target member's physical descriptor.
    public static String getActualName(Class<?> type) {
        String actual=type.getName();
        trace("getActualName",actual,"",actual,"TARGET_IDENTITY:OK");
        return actual;
    }
    public static String getName(Class<?> type) {
        String old=oldInternal(type).replace('/','.');
        trace("getName",type.getName(),"",old,"OK");
        return old;
    }
    public static String getTypeName(Class<?> type) {
        String result=type.isArray()?getTypeName(type.getComponentType())+"[]":getName(type);
        trace("getTypeName",type.getName(),"",result,"OK");
        return result;
    }
    public static String getSimpleName(Class<?> type) {
        String result;
        if(type.isArray())result=getSimpleName(type.getComponentType())+"[]";
        else {
            String old=oldInternal(type);int slash=old.lastIndexOf('/');old=old.substring(slash+1);
            int dollar=old.lastIndexOf('$');result=old.substring(dollar+1);
        }
        trace("getSimpleName",type.getName(),"",result,"OK");
        return result;
    }
    private static String descriptor(Class<?> type) {
        if(type.isPrimitive()) {
            if(type==void.class)return "V";
            if(type==boolean.class)return "Z";
            if(type==byte.class)return "B";
            if(type==char.class)return "C";
            if(type==short.class)return "S";
            if(type==int.class)return "I";
            if(type==long.class)return "J";
            if(type==float.class)return "F";
            return "D";
        }
        if(type.isArray())return type.getName().replace('.','/');
        return "L"+type.getName().replace('.','/')+";";
    }
    private static String parameters(Class<?>[] p) {
        StringBuilder out=new StringBuilder("(");for(Class<?> c:p)out.append(descriptor(c));return out.append(')').toString();
    }
    private static Map<String,String> reverseMethodNames() {
        Map<String,String> map=TARGET_METHOD_NAMES;
        if(map!=null)return map;
        synchronized(BmlReflectionBridgeTemplate.class) {
            map=TARGET_METHOD_NAMES;
            if(map==null) {
                map=new HashMap<>();
                for(List<String[]> members:METHODS.values())for(String[] col:members) {
                    String key=col[4]+"#"+col[5]+"#"+col[6];
                    String prior=map.putIfAbsent(key,col[2]);
                    if(prior!=null&&!prior.equals(col[2]))map.put(key,"");
                }
                TARGET_METHOD_NAMES=map;
            }
        }
        return map;
    }
    /** Legacy source-name view for methods discovered through reflection.
     *  Multiple source identities with the same target method are ambiguous;
     *  in that case retain the actual method name instead of guessing.
     */
    public static String getMethodName(Method method) {
        String name=method.getName();
        Class<?> declaring=method.getDeclaringClass();
        if(pinnedMethod(declaring,name,method.getParameterTypes()))return name;
        String key=declaring.getName().replace('.','/')+"#"+name+"#"+
                   parameters(method.getParameterTypes())+descriptor(method.getReturnType());
        String candidate=reverseMethodNames().get(key);
        String result=candidate!=null&&!candidate.isEmpty()?candidate:name;
        trace("Method.getName",declaring.getName(),name,result,
              "".equals(candidate)?"AMBIGUOUS":"OK");
        return result;
    }
    private static boolean matchParameters(String methodDescriptor,String params) {
        return methodDescriptor.startsWith(params);
    }
    private static Set<String> methodNames(Class<?> type,String oldName,Class<?>[] args,boolean declared) {
        String expected=parameters(args);
        Set<String> output=new TreeSet<>();
        ArrayDeque<Class<?>> pending=new ArrayDeque<>();pending.add(type);
        Set<Class<?>> visited=new HashSet<>();
        while(!pending.isEmpty()) {
            Class<?> owner=pending.removeFirst();if(!visited.add(owner))continue;
            String old=oldInternal(owner);
            for(String[] col:METHODS.getOrDefault(oldName,List.of())) {
                if(col[1].equals(old)&&matchParameters(col[6],expected)&&
                   col[4].equals(owner.getName().replace('.','/')))output.add(col[5]);
            }
            if(!declared) {
                Class<?> parent=owner.getSuperclass();if(parent!=null)pending.add(parent);
                for(Class<?> face:owner.getInterfaces())pending.add(face);
            }
        }
        return output;
    }
    private static Method findMethod(Class<?> owner,String name,Class<?>[] args,boolean declared)throws NoSuchMethodException {
        boolean pinned=pinnedMethod(owner,name,args);
        Set<String> choices=pinned?Set.of():methodNames(owner,name,args,declared);
        String chosen=pinned?name:choices.size()==1?choices.iterator().next():name;
        String api=declared?"getDeclaredMethod":"getMethod";
        String mapped=chosen+parameters(args);
        String decision=pinned?"BOUND_TARGET":choices.size()>1?"AMBIGUOUS:"+choices:
            choices.isEmpty()?"UNMAPPED":"MAPPED";
        try {
            Method result=declared?owner.getDeclaredMethod(chosen,args):owner.getMethod(chosen,args);
            trace(api,owner.getName(),name+parameters(args),mapped,decision+":OK");
            return result;
        } catch(NoSuchMethodException ex) {
            trace(api,owner.getName(),name+parameters(args),mapped,decision+":"+describe(ex));
            throw ex;
        }
    }
    public static Method getMethod(Class<?> owner,String name,Class<?>[] args)throws NoSuchMethodException {
        return findMethod(owner,name,args,false);
    }
    public static Method getDeclaredMethod(Class<?> owner,String name,Class<?>[] args)throws NoSuchMethodException {
        return findMethod(owner,name,args,true);
    }
    private static String methodHandleName(Class<?> owner,String sourceName,MethodType type) {
        Set<String> names=new TreeSet<>();
        ArrayDeque<Class<?>> pending=new ArrayDeque<>();pending.add(owner);
        Set<Class<?>> visited=new HashSet<>();
        String exact=type.toMethodDescriptorString();
        while(!pending.isEmpty()) {
            Class<?> current=pending.removeFirst();if(!visited.add(current))continue;
            String old=oldInternal(current);
            for(String[] col:METHODS.getOrDefault(sourceName,List.of()))
                if(col[1].equals(old)&&col[6].equals(exact)&&
                   col[4].equals(current.getName().replace('.','/')))names.add(col[5]);
            Class<?> parent=current.getSuperclass();if(parent!=null)pending.add(parent);
            for(Class<?> face:current.getInterfaces())pending.add(face);
        }
        return names.size()==1?names.iterator().next():sourceName;
    }
    public static MethodHandle findStatic(MethodHandles.Lookup lookup,Class<?> owner,String name,MethodType type)
        throws NoSuchMethodException,IllegalAccessException {
        String target=methodHandleName(owner,name,type);
        try {
            MethodHandle found=lookup.findStatic(owner,target,type);
            trace("findStatic",owner.getName(),name+type,target+type,"OK");return found;
        } catch(NoSuchMethodException|IllegalAccessException ex) {
            trace("findStatic",owner.getName(),name+type,target+type,describe(ex));throw ex;
        }
    }
    public static MethodHandle findVirtual(MethodHandles.Lookup lookup,Class<?> owner,String name,MethodType type)
        throws NoSuchMethodException,IllegalAccessException {
        String target=methodHandleName(owner,name,type);
        try {
            MethodHandle found=lookup.findVirtual(owner,target,type);
            trace("findVirtual",owner.getName(),name+type,target+type,"OK");return found;
        } catch(NoSuchMethodException|IllegalAccessException ex) {
            trace("findVirtual",owner.getName(),name+type,target+type,describe(ex));throw ex;
        }
    }
    public static MethodHandle findSpecial(MethodHandles.Lookup lookup,Class<?> owner,String name,MethodType type,Class<?> specialCaller)
        throws NoSuchMethodException,IllegalAccessException {
        String target=methodHandleName(owner,name,type);
        try {
            MethodHandle found=lookup.findSpecial(owner,target,type,specialCaller);
            trace("findSpecial",owner.getName(),name+type,target+type,"OK");return found;
        } catch(NoSuchMethodException|IllegalAccessException ex) {
            trace("findSpecial",owner.getName(),name+type,target+type,describe(ex));throw ex;
        }
    }
    private static Set<String> fieldNames(Class<?> type,String oldName,boolean declared) {
        Set<String> names=new TreeSet<>();ArrayDeque<Class<?>> pending=new ArrayDeque<>();pending.add(type);
        Set<Class<?>> visited=new HashSet<>();
        while(!pending.isEmpty()) {
            Class<?> owner=pending.removeFirst();if(!visited.add(owner))continue;
            String old=oldInternal(owner);
            for(String[] col:FIELDS.getOrDefault(oldName,List.of()))
                if(col[1].equals(old)&&col[4].equals(owner.getName().replace('.','/')))names.add(col[5]);
            if(!declared) {
                Class<?> parent=owner.getSuperclass();if(parent!=null)pending.add(parent);
                for(Class<?> face:owner.getInterfaces())pending.add(face);
            }
        }
        return names;
    }
    private static Field findField(Class<?> owner,String name,boolean declared)throws NoSuchFieldException {
        boolean pinned=pinnedField(owner,name);
        Set<String> choices=pinned?Set.of():fieldNames(owner,name,declared);
        String chosen=pinned?name:choices.size()==1?choices.iterator().next():name;
        String api=declared?"getDeclaredField":"getField";
        String decision=pinned?"BOUND_TARGET":choices.size()>1?"AMBIGUOUS:"+choices:
            choices.isEmpty()?"UNMAPPED":"MAPPED";
        try {
            Field result=declared?owner.getDeclaredField(chosen):owner.getField(chosen);
            trace(api,owner.getName(),name,chosen,decision+":OK");
            return result;
        } catch(NoSuchFieldException ex) {
            trace(api,owner.getName(),name,chosen,decision+":"+describe(ex));
            throw ex;
        }
    }
    public static Field getField(Class<?> owner,String name)throws NoSuchFieldException {
        return findField(owner,name,false);
    }
    public static Field getDeclaredField(Class<?> owner,String name)throws NoSuchFieldException {
        return findField(owner,name,true);
    }
}
