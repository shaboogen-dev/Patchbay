package dev.bitwigre.loader;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * BML typed-local patcher.
 *
 * Generic classfile-major-version-agnostic patcher for INSERT_AT_ENTRY and
 * INSERT_BEFORE_FINAL_RETURN. Unlike the historical preparer, argN is treated
 * as a raw JVM local slot whose opcode is derived from the target method
 * descriptor/access flags. Primitive slots therefore use ILOAD/FLOAD/LLOAD/
 * DLOAD instead of being forced through ALOAD.
 */
public final class TypedLocalPatcher {
    private TypedLocalPatcher() {}

    private static int u1(byte[] b,int o){return b[o]&0xff;}
    private static int u2(byte[] b,int o){return ((b[o]&0xff)<<8)|(b[o+1]&0xff);}
    private static long u4(byte[] b,int o){return ((long)(b[o]&0xff)<<24)|((long)(b[o+1]&0xff)<<16)|((long)(b[o+2]&0xff)<<8)|(b[o+3]&0xffL);}
    private static byte[] p2(int v){return new byte[]{(byte)(v>>>8),(byte)v};}
    private static byte[] p4(long v){return new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};}
    private static byte[] concat(byte[]...xs)throws IOException{ByteArrayOutputStream o=new ByteArrayOutputStream();for(byte[]x:xs)o.write(x);return o.toByteArray();}

    private record Cp(String[] utf8,int end,int count){}
    private static Cp parseCp(byte[] data)throws IOException{
        if(data.length<10||u4(data,0)!=0xcafebabeL)throw new IOException("not a JVM classfile");
        int count=u2(data,8),o=10;String[] utf8=new String[count];
        for(int i=1;i<count;i++){
            int tag=u1(data,o++);
            switch(tag){
                case 1->{int n=u2(data,o);o+=2;utf8[i]=new String(data,o,n,StandardCharsets.UTF_8);o+=n;}
                case 3,4->o+=4;
                case 5,6->{o+=8;i++;}
                case 7,8,16,19,20->o+=2;
                case 9,10,11,12,17,18->o+=4;
                case 15->o+=3;
                default->throw new IOException("unsupported constant-pool tag "+tag+" at index "+i);
            }
            if(o>data.length)throw new EOFException("truncated constant pool");
        }
        return new Cp(utf8,o,count);
    }

    private static final class CpAppend{
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();int next;
        CpAppend(int oldCount){next=oldCount;}
        int utf8(String s)throws IOException{byte[]x=s.getBytes(StandardCharsets.UTF_8);int i=next++;bytes.write(1);bytes.write(p2(x.length));bytes.write(x);return i;}
        int clazz(int name)throws IOException{int i=next++;bytes.write(7);bytes.write(p2(name));return i;}
        int nat(int name,int desc)throws IOException{int i=next++;bytes.write(12);bytes.write(p2(name));bytes.write(p2(desc));return i;}
        int methodRef(int cls,int nat)throws IOException{int i=next++;bytes.write(10);bytes.write(p2(cls));bytes.write(p2(nat));return i;}
    }
    private record Appended(byte[] bytes,int methodRef){}
    private static Appended appendBridgeRef(byte[] data,String helperOwner,String helperMethod,String helperDesc)throws IOException{
        Cp cp=parseCp(data);CpAppend a=new CpAppend(cp.count());
        int ucls=a.utf8(helperOwner.replace('.','/'));int cls=a.clazz(ucls);int uname=a.utf8(helperMethod);int udesc=a.utf8(helperDesc);int nat=a.nat(uname,udesc);int ref=a.methodRef(cls,nat);
        byte[] out=concat(Arrays.copyOfRange(data,0,8),p2(a.next),Arrays.copyOfRange(data,10,cp.end()),a.bytes.toByteArray(),Arrays.copyOfRange(data,cp.end(),data.length));
        return new Appended(out,ref);
    }

    private static int skipMembers(byte[] d,int o,int count)throws IOException{
        for(int i=0;i<count;i++){
            if(o+8>d.length)throw new EOFException();o+=6;int ac=u2(d,o);o+=2;
            for(int j=0;j<ac;j++){long n=u4(d,o+2);o+=6;if(n>Integer.MAX_VALUE||o+n>d.length)throw new EOFException();o+=(int)n;}
        }
        return o;
    }

    private enum Kind{I,F,J,D,A,TOP}
    private record Param(Kind kind,int width){}

    private static List<Param> parseParams(String desc)throws IOException{
        if(desc==null||desc.isEmpty()||desc.charAt(0)!='(')throw new IOException("bad method descriptor: "+desc);
        List<Param> out=new ArrayList<>();int i=1;
        while(i<desc.length()&&desc.charAt(i)!=')'){
            char c=desc.charAt(i++);
            switch(c){
                case 'Z','B','C','S','I'->out.add(new Param(Kind.I,1));
                case 'F'->out.add(new Param(Kind.F,1));
                case 'J'->out.add(new Param(Kind.J,2));
                case 'D'->out.add(new Param(Kind.D,2));
                case 'L'->{int semi=desc.indexOf(';',i);if(semi<0)throw new IOException("bad object descriptor: "+desc);i=semi+1;out.add(new Param(Kind.A,1));}
                case '['->{while(i<desc.length()&&desc.charAt(i)=='[')i++;if(i>=desc.length())throw new IOException("bad array descriptor: "+desc);if(desc.charAt(i)=='L'){int semi=desc.indexOf(';',i+1);if(semi<0)throw new IOException("bad array descriptor: "+desc);i=semi+1;}else i++;out.add(new Param(Kind.A,1));}
                default->throw new IOException("bad parameter type '"+c+"' in "+desc);
            }
        }
        if(i>=desc.length()||desc.charAt(i)!=')')throw new IOException("unterminated descriptor: "+desc);
        return out;
    }

    private static Map<Integer,Kind> localKinds(String targetDesc,boolean isStatic)throws IOException{
        Map<Integer,Kind> m=new HashMap<>();int slot=0;
        if(!isStatic)m.put(slot++,Kind.A);
        for(Param p:parseParams(targetDesc)){
            m.put(slot,p.kind());
            if(p.width()==2)m.put(slot+1,Kind.TOP);
            slot+=p.width();
        }
        return m;
    }

    private static int localIndex(String token)throws IOException{
        if("this".equals(token))return 0;
        if(token.startsWith("arg")){
            try{return Integer.parseInt(token.substring(3));}catch(NumberFormatException e){throw new IOException("bad bridge arg '"+token+"'",e);}
        }
        throw new IOException("unsupported bridge arg '"+token+"' (supported: this,arg0,arg1,... raw JVM local slots)");
    }

    private static void emitLoad(ByteArrayOutputStream o,Kind k,int local)throws IOException{
        if(local<0||local>255)throw new IOException("local index out of range: "+local);
        int baseShort,baseGeneric;
        switch(k){
            case I->{baseShort=0x1a;baseGeneric=0x15;}
            case J->{baseShort=0x1e;baseGeneric=0x16;}
            case F->{baseShort=0x22;baseGeneric=0x17;}
            case D->{baseShort=0x26;baseGeneric=0x18;}
            case A->{baseShort=0x2a;baseGeneric=0x19;}
            default->throw new IOException("cannot load second word/TOP local slot "+local);
        }
        if(local<=3)o.write(baseShort+local);else{o.write(baseGeneric);o.write(local);}
    }

    private record LoadPlan(byte[] code,int stackSlots,String summary){}
    private static LoadPlan loadOps(String csv,String targetDesc,int accessFlags,String bridgeDesc)throws IOException{
        String[] toks=(csv==null||csv.isBlank())?new String[0]:Arrays.stream(csv.split(",")).map(String::trim).toArray(String[]::new);
        List<Param> bridgeParams=parseParams(bridgeDesc);
        if(bridgeParams.size()!=toks.length)throw new IOException("bridge descriptor "+bridgeDesc+" expects "+bridgeParams.size()+" args but manifest supplies "+toks.length);
        boolean isStatic=(accessFlags&0x0008)!=0;Map<Integer,Kind> kinds=localKinds(targetDesc,isStatic);
        ByteArrayOutputStream out=new ByteArrayOutputStream();int stack=0;List<String> summary=new ArrayList<>();
        for(int i=0;i<toks.length;i++){
            String t=toks[i];int local=localIndex(t);
            if("this".equals(t)&&isStatic)throw new IOException("bridge arg 'this' used on static target "+targetDesc);
            Kind actual=kinds.get(local);
            if(actual==null)throw new IOException("bridge arg "+t+" refers to non-parameter/unmapped raw local slot "+local+" for target "+targetDesc);
            if(actual==Kind.TOP)throw new IOException("bridge arg "+t+" refers to second word of long/double local slot "+local+" for target "+targetDesc);
            Kind expected=bridgeParams.get(i).kind();
            if(actual!=expected)throw new IOException("bridge arg "+t+" type mismatch: target local slot "+local+" is "+actual+" but bridge parameter "+(i+1)+" in "+bridgeDesc+" is "+expected);
            emitLoad(out,actual,local);int w=(actual==Kind.J||actual==Kind.D)?2:1;stack+=w;summary.add(t+"="+actual+"@"+local);
        }
        return new LoadPlan(out.toByteArray(),stack,String.join(";",summary));
    }

    private static int addU2(int v,int delta,String what)throws IOException{int n=v+delta;if(n<0||n>0xffff)throw new IOException(what+" offset overflow: "+v+" + "+delta);return n;}
    private static byte[] shiftFirstStackMapFrame(byte[] info,int delta)throws IOException{
        if(info.length<2)throw new EOFException("truncated StackMapTable");int count=u2(info,0);if(count==0)return info;if(info.length<3)throw new EOFException("truncated first StackMapTable frame");int ft=u1(info,2);
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(info,0,2);
        if(ft<=63){int n=ft+delta;if(n<=63){out.write(n);out.write(info,3,info.length-3);}else{out.write(251);out.write(p2(n));out.write(info,3,info.length-3);}}
        else if(ft<=127){int n=(ft-64)+delta;if(n<=63){out.write(64+n);out.write(info,3,info.length-3);}else{out.write(247);out.write(p2(n));out.write(info,3,info.length-3);}}
        else if(ft==247||(ft>=248&&ft<=255)){if(info.length<5)throw new EOFException("truncated extended StackMapTable frame");out.write(ft);out.write(p2(addU2(u2(info,3),delta,"StackMapTable")));out.write(info,5,info.length-5);}
        else throw new IOException("reserved StackMapTable frame type "+ft);
        return out.toByteArray();
    }
    private static byte[] shiftCodeAttributePayload(String name,byte[] info,int delta)throws IOException{
        byte[] x=Arrays.copyOf(info,info.length);
        if("LineNumberTable".equals(name)){if(x.length<2)throw new EOFException();int n=u2(x,0),o=2;for(int i=0;i<n;i++,o+=4){if(o+4>x.length)throw new EOFException();byte[]v=p2(addU2(u2(x,o),delta,name));x[o]=v[0];x[o+1]=v[1];}return x;}
        if("LocalVariableTable".equals(name)||"LocalVariableTypeTable".equals(name)){if(x.length<2)throw new EOFException();int n=u2(x,0),o=2;for(int i=0;i<n;i++,o+=10){if(o+10>x.length)throw new EOFException();byte[]v=p2(addU2(u2(x,o),delta,name));x[o]=v[0];x[o+1]=v[1];}return x;}
        if("StackMapTable".equals(name))return shiftFirstStackMapFrame(x,delta);
        if("RuntimeVisibleTypeAnnotations".equals(name)||"RuntimeInvisibleTypeAnnotations".equals(name))throw new IOException("INSERT_AT_ENTRY does not support offset-bearing Code attribute "+name);
        return x;
    }

    private record Patched(byte[] bytes,int oldCodeLen,int newCodeLen,int oldMaxStack,int newMaxStack,String loads){}
    private static Patched patchFinalReturn(byte[] data,String method,String desc,int mref,String argsCsv,String bridgeDesc)throws IOException{
        Cp cp=parseCp(data);int o=cp.end();o+=6;int ic=u2(data,o);o+=2+2*ic;int fc=u2(data,o);o+=2;o=skipMembers(data,o,fc);int mc=u2(data,o);o+=2;int matches=0;byte[]result=null;int oldLen=0,newLen=0,oldMs=0,newMs=0;String loadSummary="";
        for(int mi=0;mi<mc;mi++){
            int methodStart=o;int access=u2(data,methodStart),nameIdx=u2(data,methodStart+2),descIdx=u2(data,methodStart+4);o+=6;int ac=u2(data,o);o+=2;String mn=cp.utf8()[nameIdx],md=cp.utf8()[descIdx];
            for(int ai=0;ai<ac;ai++){
                int attrStart=o,anIdx=u2(data,o);long al=u4(data,o+2);int info=o+6,end=info+(int)al;String an=cp.utf8()[anIdx];
                if(Objects.equals(mn,method)&&Objects.equals(md,desc)&&"Code".equals(an)){
                    matches++;LoadPlan lp=loadOps(argsCsv,desc,access,bridgeDesc);byte[]call=concat(lp.code(),new byte[]{(byte)0xb8},p2(mref));
                    int maxStack=u2(data,info),maxLocals=u2(data,info+2),codeLen=(int)u4(data,info+4),cs=info+8,ce=cs+codeLen;
                    if(codeLen<=0||u1(data,ce-1)!=0xb1)throw new IOException("target "+method+desc+" does not end in RETURN (0xb1)");
                    byte[]newCode=concat(Arrays.copyOfRange(data,cs,ce-1),call,new byte[]{(byte)0xb1});int ms=Math.max(maxStack,lp.stackSlots());
                    byte[]newInfo=concat(p2(ms),p2(maxLocals),p4(newCode.length),newCode,Arrays.copyOfRange(data,ce,end));byte[]newAttr=concat(p2(anIdx),p4(al+call.length),newInfo);result=concat(Arrays.copyOfRange(data,0,attrStart),newAttr,Arrays.copyOfRange(data,end,data.length));oldLen=codeLen;newLen=newCode.length;oldMs=maxStack;newMs=ms;loadSummary=lp.summary();
                }
                o=end;
            }
        }
        if(matches!=1)throw new IOException("expected exactly one target method "+method+desc+", found "+matches);return new Patched(result,oldLen,newLen,oldMs,newMs,loadSummary);
    }

    private static Patched patchEntry(byte[] data,String method,String desc,int mref,String argsCsv,String bridgeDesc)throws IOException{
        Cp cp=parseCp(data);int o=cp.end();o+=6;int ic=u2(data,o);o+=2+2*ic;int fc=u2(data,o);o+=2;o=skipMembers(data,o,fc);int mc=u2(data,o);o+=2;int matches=0;byte[]result=null;int oldLen=0,newLen=0,oldMs=0,newMs=0;String loadSummary="";
        for(int mi=0;mi<mc;mi++){
            int methodStart=o;int access=u2(data,methodStart),nameIdx=u2(data,methodStart+2),descIdx=u2(data,methodStart+4);o+=6;int ac=u2(data,o);o+=2;String mn=cp.utf8()[nameIdx],md=cp.utf8()[descIdx];
            for(int ai=0;ai<ac;ai++){
                int attrStart=o,anIdx=u2(data,o);long al=u4(data,o+2);int info=o+6,end=info+(int)al;String an=cp.utf8()[anIdx];
                if(Objects.equals(mn,method)&&Objects.equals(md,desc)&&"Code".equals(an)){
                    matches++;LoadPlan lp=loadOps(argsCsv,desc,access,bridgeDesc);byte[]rawCall=concat(lp.code(),new byte[]{(byte)0xb8},p2(mref));int pad=(4-(rawCall.length&3))&3;byte[]call=concat(rawCall,new byte[pad]);int delta=call.length;
                    int maxStack=u2(data,info),maxLocals=u2(data,info+2),codeLen=(int)u4(data,info+4),cs=info+8,ce=cs+codeLen;byte[]newCode=concat(call,Arrays.copyOfRange(data,cs,ce));
                    int q=ce,exCount=u2(data,q);q+=2;ByteArrayOutputStream ex=new ByteArrayOutputStream();ex.write(p2(exCount));
                    for(int i=0;i<exCount;i++){if(q+8>end)throw new EOFException();ex.write(p2(addU2(u2(data,q),delta,"exception.start_pc")));ex.write(p2(addU2(u2(data,q+2),delta,"exception.end_pc")));ex.write(p2(addU2(u2(data,q+4),delta,"exception.handler_pc")));ex.write(Arrays.copyOfRange(data,q+6,q+8));q+=8;}
                    if(q+2>end)throw new EOFException();int nac=u2(data,q);q+=2;ByteArrayOutputStream nested=new ByteArrayOutputStream();nested.write(p2(nac));
                    for(int ni=0;ni<nac;ni++){if(q+6>end)throw new EOFException();int nidx=u2(data,q),len=(int)u4(data,q+2),is=q+6,ie=is+len;if(ie>end)throw new EOFException();String nn=cp.utf8()[nidx];byte[]payload=shiftCodeAttributePayload(nn,Arrays.copyOfRange(data,is,ie),delta);nested.write(p2(nidx));nested.write(p4(payload.length));nested.write(payload);q=ie;}
                    if(q!=end)throw new IOException("Code attribute parse did not end cleanly for "+method+desc);int ms=Math.max(maxStack,lp.stackSlots());byte[]newInfo=concat(p2(ms),p2(maxLocals),p4(newCode.length),newCode,ex.toByteArray(),nested.toByteArray());byte[]newAttr=concat(p2(anIdx),p4(newInfo.length),newInfo);result=concat(Arrays.copyOfRange(data,0,attrStart),newAttr,Arrays.copyOfRange(data,end,data.length));oldLen=codeLen;newLen=newCode.length;oldMs=maxStack;newMs=ms;loadSummary=lp.summary();
                }
                o=end;
            }
        }
        if(matches!=1)throw new IOException("expected exactly one target method "+method+desc+", found "+matches);return new Patched(result,oldLen,newLen,oldMs,newMs,loadSummary);
    }

    private static byte[] readClassFromJar(Path jar,String owner)throws IOException{String entry=owner.replace('.','/').replace('\\','/')+".class";try(ZipFile z=new ZipFile(jar.toFile())){ZipEntry e=z.getEntry(entry);if(e==null)throw new FileNotFoundException("class not found in jar: "+entry);try(InputStream in=z.getInputStream(e)){return in.readAllBytes();}}}

    private static void runPatch(boolean fromJar,boolean entry,String[] a)throws Exception{
        final byte[] original;final Path out;final String method,desc,helperOwner,helperMethod,helperDesc,argsCsv;final String ownerLabel;
        if(fromJar){
            if(a.length!=9&&a.length!=10)throw new IllegalArgumentException("patch-jar-entry|patch-jar-final <jar> <owner> <out.class> <method> <desc> <helperOwner> <helperMethod> <helperDesc> [argsCsv]");
            Path jar=Path.of(a[1]);String owner=a[2];out=Path.of(a[3]);method=a[4];desc=a[5];helperOwner=a[6];helperMethod=a[7];helperDesc=a[8];argsCsv=a.length==10?a[9]:"";original=readClassFromJar(jar,owner);ownerLabel=owner;
        }else{
            if(a.length!=8&&a.length!=9)throw new IllegalArgumentException("patch-class-entry|patch-class-final <in.class> <out.class> <method> <desc> <helperOwner> <helperMethod> <helperDesc> [argsCsv]");
            Path in=Path.of(a[1]);out=Path.of(a[2]);method=a[3];desc=a[4];helperOwner=a[5];helperMethod=a[6];helperDesc=a[7];argsCsv=a.length==9?a[8]:"";original=Files.readAllBytes(in);ownerLabel=in.toString();
        }
        Appended ap=appendBridgeRef(original,helperOwner,helperMethod,helperDesc);Patched p=entry?patchEntry(ap.bytes(),method,desc,ap.methodRef(),argsCsv,helperDesc):patchFinalReturn(ap.bytes(),method,desc,ap.methodRef(),argsCsv,helperDesc);Files.createDirectories(out.toAbsolutePath().getParent());Files.write(out,p.bytes());
        System.out.printf(Locale.ROOT,"PATCH_OK_TYPED transform=%s owner=%s method=%s%s major=%d class_bytes=%d->%d code=%d->%d max_stack=%d->%d bridge=%s.%s%s args=%s loads=%s%n",entry?"INSERT_AT_ENTRY":"INSERT_BEFORE_FINAL_RETURN",ownerLabel,method,desc,u2(original,6),original.length,p.bytes().length,p.oldCodeLen(),p.newCodeLen(),p.oldMaxStack(),p.newMaxStack(),helperOwner,helperMethod,helperDesc,argsCsv,p.loads());
    }

    public static void main(String[] args)throws Exception{
        if(args.length==0)throw new IllegalArgumentException("Commands: patch-jar-entry | patch-class-entry | patch-jar-final | patch-class-final");
        switch(args[0]){
            case "patch-jar-entry"->runPatch(true,true,args);
            case "patch-class-entry"->runPatch(false,true,args);
            case "patch-jar-final"->runPatch(true,false,args);
            case "patch-class-final"->runPatch(false,false,args);
            default->throw new IllegalArgumentException("unknown command: "+args[0]);
        }
    }
}
