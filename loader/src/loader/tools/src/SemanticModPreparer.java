package dev.bitwigre.loader;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/**
 * BML semantic mod preparer: the original classfile patcher, now used for INSERT_BEFORE_FINAL_RETURN
 * and WRAP_OBJECT_RETURN (INSERT_AT_ENTRY moved to TypedLocalPatcher, which also loads primitive
 * bridge args with their own opcodes; this one loads every arg with ALOAD).
 *
 * Source recovered on 27 Oct 2026 from the accepted bitwig-mod-preparer.jar, of which no source
 * survived: decompiled with Vineflower, then rewritten in TypedLocalPatcher's style (its successor,
 * which shares much of this code) until it compiled to the accepted instructions.
 */
public final class SemanticModPreparer {
    private SemanticModPreparer() {}

    private static int u1(byte[] b,int o){return b[o]&0xff;}
    private static int u2(byte[] b,int o){return ((b[o]&0xff)<<8)|(b[o+1]&0xff);}
    private static long u4(byte[] b,int o){return ((long)(b[o]&0xff)<<24)|((long)(b[o+1]&0xff)<<16)|((long)(b[o+2]&0xff)<<8)|(b[o+3]&0xffL);}
    private static byte[] p2(int v){return new byte[]{(byte)(v>>>8),(byte)v};}
    private static byte[] p4(long v){return new byte[]{(byte)(v>>>24),(byte)(v>>>16),(byte)(v>>>8),(byte)v};}

    private record Cp(String[] utf8,int end,int count,int[] classNames){}
    private static Cp parseCp(byte[] data)throws IOException{
        if(data.length<10||u4(data,0)!=0xcafebabeL)throw new IOException("not a JVM classfile");
        int count=u2(data,8),o=10;String[] utf8=new String[count];int[] classNames=new int[count];
        for(int i=1;i<count;i++){
            int tag=u1(data,o++);
            switch(tag){
                case 1->{int n=u2(data,o);o+=2;utf8[i]=new String(data,o,n,StandardCharsets.UTF_8);o+=n;}
                case 3,4->o+=4;
                case 5,6->{o+=8;i++;}
                case 7->{classNames[i]=u2(data,o);o+=2;}
                case 8,16,19,20->o+=2;
                case 9,10,11,12,17,18->o+=4;
                case 15->o+=3;
                default->throw new IOException("unsupported constant-pool tag "+tag+" at index "+i);
            }
            if(o>data.length)throw new EOFException("truncated constant pool");
        }
        return new Cp(utf8,o,count,classNames);
    }
    private static byte[] concat(byte[]...xs)throws IOException{ByteArrayOutputStream o=new ByteArrayOutputStream();for(byte[]x:xs)o.write(x);return o.toByteArray();}

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
        byte[] added=a.bytes.toByteArray();
        byte[] out=concat(Arrays.copyOfRange(data,0,8),p2(a.next),Arrays.copyOfRange(data,10,cp.end()),added,Arrays.copyOfRange(data,cp.end(),data.length));
        return new Appended(out,ref);
    }
    private record AppendedWrap(byte[] bytes,int methodRef,int castClassRef){}
    private static AppendedWrap appendBridgeRefAndClass(byte[] data,String helperOwner,String helperMethod,String helperDesc,String castInternal)throws IOException{
        Cp cp=parseCp(data);CpAppend a=new CpAppend(cp.count());
        int ucls=a.utf8(helperOwner.replace('.','/'));int cls=a.clazz(ucls);int uname=a.utf8(helperMethod);int udesc=a.utf8(helperDesc);int nat=a.nat(uname,udesc);int ref=a.methodRef(cls,nat);
        int ucast=a.utf8(castInternal);int cast=a.clazz(ucast);
        byte[] added=a.bytes.toByteArray();
        byte[] out=concat(Arrays.copyOfRange(data,0,8),p2(a.next),Arrays.copyOfRange(data,10,cp.end()),added,Arrays.copyOfRange(data,cp.end(),data.length));
        return new AppendedWrap(out,ref,cast);
    }
    private static String objectReturnInternal(String desc)throws IOException{
        int close=desc.lastIndexOf(')');if(close<0||close+1>=desc.length())throw new IOException("invalid method descriptor: "+desc);
        String ret=desc.substring(close+1);
        if(ret.length()<3||ret.charAt(0)!='L'||ret.charAt(ret.length()-1)!=';')throw new IOException("WRAP_OBJECT_RETURN requires an object return descriptor, got: "+desc);
        return ret.substring(1,ret.length()-1);
    }

    private static int skipMembers(byte[] d,int o,int count)throws IOException{
        for(int i=0;i<count;i++){
            if(o+8>d.length)throw new EOFException();o+=6;int ac=u2(d,o);o+=2;
            for(int j=0;j<ac;j++){long n=u4(d,o+2);o+=6;if(n>Integer.MAX_VALUE||o+n>d.length)throw new EOFException();o+=(int)n;}
        }
        return o;
    }

    /** ALOAD for each of "this" / "argN" (N is a raw local slot, always loaded as a reference). */
    private static byte[] loadOps(String csv)throws IOException{
        ByteArrayOutputStream o=new ByteArrayOutputStream();if(csv==null||csv.isBlank())return o.toByteArray();
        for(String raw:csv.split(",")){
            String t=raw.trim();int local;
            if(t.equals("this"))local=0;
            else if(t.startsWith("arg"))local=Integer.parseInt(t.substring(3));
            else throw new IOException("unsupported bridge arg '"+t+"' (supported: this,arg1,arg2,...)");
            if(local>=0&&local<=3)o.write(0x2a+local);else if(local<=255){o.write(0x19);o.write(local);}else throw new IOException("local index too large: "+local);
        }
        return o.toByteArray();
    }

    private static int skipVerificationType(byte[] d,int o)throws IOException{
        if(o>=d.length)throw new EOFException("truncated verification_type_info");int tag=u1(d,o++);
        if(tag==7||tag==8){if(o+2>d.length)throw new EOFException("truncated verification_type_info payload");o+=2;}
        else if(tag<0||tag>8)throw new IOException("invalid verification_type_info tag "+tag);
        return o;
    }
    /** A verification type: its raw verification_type_info bytes, or an Object type named by cls (from the method
     * descriptor) whose class constant is looked up, or appended, only when a frame is written with it. */
    private record VType(byte[] raw,String cls){boolean isTop(){return raw!=null&&raw[0]==0;}}
    private static VType vtype(byte[] d,int o)throws IOException{return new VType(Arrays.copyOfRange(d,o,skipVerificationType(d,o)),null);}
    /** Class constants for rewritten frames: an existing one is reused, a missing one is appended to the constant pool once the method is patched. */
    private static final class ClassRefs{
        final Cp cp;final ByteArrayOutputStream bytes=new ByteArrayOutputStream();final Map<String,Integer> added=new HashMap<>();int next;
        ClassRefs(Cp cp){this.cp=cp;next=cp.count();}
        int ref(String name)throws IOException{
            for(int i=1;i<cp.count();i++)if(cp.classNames()[i]!=0&&name.equals(cp.utf8()[cp.classNames()[i]]))return i;
            Integer a=added.get(name);if(a!=null)return a;
            byte[]x=name.getBytes(StandardCharsets.UTF_8);int u=next++;bytes.write(1);bytes.write(p2(x.length));bytes.write(x);int c=next++;bytes.write(7);bytes.write(p2(u));added.put(name,c);return c;
        }
        byte[] apply(byte[] data)throws IOException{
            if(added.isEmpty())return data;if(next>0xffff)throw new IOException("constant pool overflow while rewriting StackMapTable");
            return concat(Arrays.copyOfRange(data,0,8),p2(next),Arrays.copyOfRange(data,10,cp.end()),bytes.toByteArray(),Arrays.copyOfRange(data,cp.end(),data.length));
        }
    }
    /** The locals of the method's implicit initial frame (JVMS 4.10.1.6), which the first delta frame is relative to. */
    private static List<VType> initialLocals(String method,String desc,boolean isStatic,int thisClass)throws IOException{
        List<VType> l=new ArrayList<>();
        if(!isStatic)l.add(new VType("<init>".equals(method)?new byte[]{6}:new byte[]{7,(byte)(thisClass>>>8),(byte)thisClass},null));
        if(desc.isEmpty()||desc.charAt(0)!='(')throw new IOException("invalid method descriptor: "+desc);
        int i=1;
        while(i<desc.length()&&desc.charAt(i)!=')'){
            int s=i;while(i<desc.length()&&desc.charAt(i)=='[')i++;if(i>=desc.length())throw new IOException("invalid method descriptor: "+desc);
            char c=desc.charAt(i);if(c=='L'){i=desc.indexOf(';',i);if(i<0)throw new IOException("invalid method descriptor: "+desc);}i++;
            if(i-s>1&&desc.charAt(s)=='[')l.add(new VType(null,desc.substring(s,i)));
            else switch(c){
                case 'Z','B','C','S','I'->l.add(new VType(new byte[]{1},null));
                case 'F'->l.add(new VType(new byte[]{2},null));
                case 'J'->l.add(new VType(new byte[]{4},null));
                case 'D'->l.add(new VType(new byte[]{3},null));
                case 'L'->l.add(new VType(null,desc.substring(s+1,i-1)));
                default->throw new IOException("invalid method descriptor: "+desc);
            }
        }
        if(i>=desc.length())throw new IOException("invalid method descriptor: "+desc);
        return l;
    }
    /** One StackMapTable frame with its implicit locals and stack resolved; start/end are its bytes in the table. */
    private record Frame(int start,int end,int ft,int delta,int pc,List<VType> locals,List<VType> stack){}
    private static List<Frame> decodeFrames(byte[] smt,List<VType> initial)throws IOException{
        if(smt.length<2)throw new EOFException("truncated StackMapTable");int count=u2(smt,0),o=2,offset=-1;List<VType> locals=initial;List<Frame> frames=new ArrayList<>();
        for(int i=0;i<count;i++){
            int start=o;if(o>=smt.length)throw new EOFException("truncated StackMapTable frame");int ft=u1(smt,o++),delta;List<VType> stack=List.of();
            if(ft<=63)delta=ft;
            else if(ft<=127){delta=ft-64;stack=List.of(vtype(smt,o));o=skipVerificationType(smt,o);}
            else if(ft==247){if(o+2>smt.length)throw new EOFException();delta=u2(smt,o);o+=2;stack=List.of(vtype(smt,o));o=skipVerificationType(smt,o);}
            else if(ft>=248&&ft<=251){
                if(o+2>smt.length)throw new EOFException();delta=u2(smt,o);o+=2;int k=251-ft;
                if(k>locals.size())throw new IOException("StackMapTable chop frame removes "+k+" locals but only "+locals.size()+" are present");locals=locals.subList(0,locals.size()-k);
            }
            else if(ft>=252&&ft<=254){if(o+2>smt.length)throw new EOFException();delta=u2(smt,o);o+=2;List<VType> n=new ArrayList<>(locals);for(int k=0;k<ft-251;k++){n.add(vtype(smt,o));o=skipVerificationType(smt,o);}locals=n;}
            else if(ft==255){
                if(o+4>smt.length)throw new EOFException();delta=u2(smt,o);o+=2;int nLocals=u2(smt,o);o+=2;List<VType> n=new ArrayList<>();
                for(int k=0;k<nLocals;k++){n.add(vtype(smt,o));o=skipVerificationType(smt,o);}locals=n;
                if(o+2>smt.length)throw new EOFException();int nStack=u2(smt,o);o+=2;List<VType> s=new ArrayList<>();for(int k=0;k<nStack;k++){s.add(vtype(smt,o));o=skipVerificationType(smt,o);}stack=s;
            }else throw new IOException("reserved StackMapTable frame type "+ft);
            offset+=delta+1;locals=List.copyOf(locals);frames.add(new Frame(start,o,ft,delta,offset,locals,List.copyOf(stack)));
        }
        return frames;
    }
    private static void writeVType(ByteArrayOutputStream out,VType t,ClassRefs refs)throws IOException{if(t.raw()!=null)out.write(t.raw());else{out.write(7);out.write(p2(refs.ref(t.cls())));}}
    private static void writeFullFrame(ByteArrayOutputStream out,int delta,List<VType> locals,List<VType> stack,ClassRefs refs)throws IOException{
        out.write(255);out.write(p2(delta));out.write(p2(locals.size()));for(VType t:locals)writeVType(out,t,refs);out.write(p2(stack.size()));for(VType t:stack)writeVType(out,t,refs);
    }
    private static boolean lacksThis(Frame f){return f.locals().isEmpty()||f.locals().get(0).isTop();}
    /** If the frame at bytecode offset pc has local 0 TOP or absent (an obfuscator may drop the dead "this" from frames),
     * make it the class itself again, so a bridge call that loads "this" before the final RETURN verifies. Any frame type
     * works: frames are decoded with their implicit locals (tracked from the method's initial frame) and a revived frame
     * is written as a full_frame. Since the paths into the RETURN must carry "this" too, every frame of the method that
     * dropped it is revived as well; that is sound because local 0 is never reassigned, which is checked first (a frame
     * before a constructor's super() call would not verify, so deep verification catches that case). A delta frame after
     * a rewritten one is written in full, since it would otherwise be relative to the revived locals. */
    private static byte[] reviveThisAtFrame(byte[] smt,int pc,int thisClass,List<VType> initial,boolean isStatic,boolean slot0Stored,ClassRefs refs)throws IOException{
        List<Frame> frames=decodeFrames(smt,initial);int target=-1;
        for(int i=0;i<frames.size();i++)if(frames.get(i).pc()==pc)target=i;
        if(target<0||!lacksThis(frames.get(target)))return smt;
        if(isStatic)throw new IOException("INSERT_BEFORE_FINAL_RETURN loads this, but the target method is static");
        if(slot0Stored)throw new IOException("INSERT_BEFORE_FINAL_RETURN loads this, but the target method reassigns local 0, so this is not available at its final RETURN");
        VType thisType=new VType(new byte[]{7,(byte)(thisClass>>>8),(byte)thisClass},null);
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(smt,0,2);boolean prevChanged=false;
        for(int i=0;i<frames.size();i++){
            Frame f=frames.get(i);boolean changed=lacksThis(f);
            if(changed){List<VType> locals=new ArrayList<>(f.locals());if(locals.isEmpty())locals.add(thisType);else locals.set(0,thisType);writeFullFrame(out,f.delta(),locals,f.stack(),refs);}
            else if(prevChanged&&f.ft()!=255)writeFullFrame(out,f.delta(),f.locals(),f.stack(),refs);
            else out.write(smt,f.start(),f.end()-f.start());
            prevChanged=changed;
        }
        return out.toByteArray();
    }
    /** Whether the bytecode stores to local 0 (xSTORE, xSTORE_0, IINC or their WIDE forms). */
    private static boolean storesLocal0(byte[] c,int s,int e)throws IOException{
        int pc=s;
        while(pc<e){
            int op=u1(c,pc),len;
            if(op==0x3b||op==0x3f||op==0x43||op==0x47||op==0x4b)return true;
            if((op>=0x36&&op<=0x3a)||op==0x84){if(pc+1>=e)throw new EOFException("truncated bytecode");if(u1(c,pc+1)==0)return true;}
            if(op==0xc4){if(pc+3>=e)throw new EOFException("truncated bytecode");int w=u1(c,pc+1);if(((w>=0x36&&w<=0x3a)||w==0x84)&&u2(c,pc+2)==0)return true;len=w==0x84?6:4;}
            else if(op==0xaa){int p=pc+1+((4-((pc-s+1)&3))&3);if(p+12>e)throw new EOFException("truncated tableswitch");long lo=(int)u4(c,p+4),hi=(int)u4(c,p+8);len=(int)(p-pc+12+4*(hi-lo+1));}
            else if(op==0xab){int p=pc+1+((4-((pc-s+1)&3))&3);if(p+8>e)throw new EOFException("truncated lookupswitch");len=p-pc+8+8*(int)u4(c,p+4);}
            else if(op==0x10||op==0x12||(op>=0x15&&op<=0x19)||(op>=0x36&&op<=0x3a)||op==0xa9||op==0xbc)len=2;
            else if(op==0x11||op==0x13||op==0x14||op==0x84||(op>=0x99&&op<=0xa8)||(op>=0xb2&&op<=0xb8)||op==0xbb||op==0xbd||op==0xc0||op==0xc1||op==0xc6||op==0xc7)len=3;
            else if(op==0xc5)len=4;
            else if(op==0xb9||op==0xba||op==0xc8||op==0xc9)len=5;
            else len=1;
            if(len<=0)throw new IOException("invalid instruction length at "+(pc-s));pc+=len;
        }
        return false;
    }
    /** The Code attribute after the bytecode: exception table copied, nested attributes copied, "this" revived in the frame at the old RETURN if the bridge loads it. */
    private static byte[] rewriteFinalReturnCodeTail(byte[] data,int start,int end,Cp cp,int returnPc,int thisClass,boolean loadsThis,List<VType> initial,boolean isStatic,boolean slot0Stored,ClassRefs refs)throws IOException{
        int o=start;if(o+2>end)throw new EOFException();int exCount=u2(data,o),exLen=2+8*exCount;if(o+exLen>end)throw new EOFException();
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(data,o,exLen);o+=exLen;
        if(o+2>end)throw new EOFException();int nac=u2(data,o);out.write(p2(nac));o+=2;
        for(int i=0;i<nac;i++){
            if(o+6>end)throw new EOFException();int nidx=u2(data,o),len=(int)u4(data,o+2),is=o+6,ie=is+len;if(ie>end)throw new EOFException();
            byte[] payload=Arrays.copyOfRange(data,is,ie);if(loadsThis&&"StackMapTable".equals(cp.utf8()[nidx]))payload=reviveThisAtFrame(payload,returnPc,thisClass,initial,isStatic,slot0Stored,refs);
            out.write(p2(nidx));out.write(p4(payload.length));out.write(payload);o=ie;
        }
        if(o!=end)throw new IOException("Code attribute parse did not end cleanly while rewriting final return");
        return out.toByteArray();
    }

    private record Patched(byte[] bytes,int oldCodeLen,int newCodeLen,int oldMaxStack,int newMaxStack){}
    private static Patched patchFinalReturn(byte[] data,String method,String desc,int mref,String argsCsv)throws IOException{
        Cp cp=parseCp(data);int o=cp.end();o+=6;int ic=u2(data,o);o+=2+2*ic;int fc=u2(data,o);o+=2;o=skipMembers(data,o,fc);int mc=u2(data,o);o+=2;int matches=0;byte[]result=null;int oldLen=0,newLen=0,oldMs=0,newMs=0;
        byte[]loads=loadOps(argsCsv);byte[]call=concat(loads,new byte[]{(byte)0xb8},p2(mref));int argc=(argsCsv==null||argsCsv.isBlank())?0:argsCsv.split(",").length;ClassRefs refs=new ClassRefs(cp);
        for(int mi=0;mi<mc;mi++){
            int methodStart=o;int nameIdx=u2(data,methodStart+2),descIdx=u2(data,methodStart+4);o+=6;int ac=u2(data,o);o+=2;String mn=cp.utf8()[nameIdx],md=cp.utf8()[descIdx];
            for(int ai=0;ai<ac;ai++){
                int attrStart=o,anIdx=u2(data,o);long al=u4(data,o+2);int info=o+6,end=info+(int)al;String an=cp.utf8()[anIdx];
                if(Objects.equals(mn,method)&&Objects.equals(md,desc)&&"Code".equals(an)){
                    matches++;int maxStack=u2(data,info),maxLocals=u2(data,info+2),codeLen=(int)u4(data,info+4),cs=info+8,ce=cs+codeLen;
                    if(codeLen<=0||u1(data,ce-1)!=0xb1)throw new IOException("target "+method+desc+" does not end in RETURN (0xb1)");
                    byte[]newCode=concat(Arrays.copyOfRange(data,cs,ce-1),call,new byte[]{(byte)0xb1});int ms=Math.max(maxStack,argc);
                    boolean loadsThis=argsCsv!=null&&Arrays.stream(argsCsv.split(",")).map(String::trim).anyMatch("this"::equals);int thisClass=u2(data,cp.end()+2);
                    boolean isStatic=(u2(data,methodStart)&0x0008)!=0;
                    byte[]tail=rewriteFinalReturnCodeTail(data,ce,end,cp,codeLen-1,thisClass,loadsThis,initialLocals(mn,md,isStatic,thisClass),isStatic,loadsThis&&storesLocal0(data,cs,ce),refs);
                    byte[]newInfo=concat(p2(ms),p2(maxLocals),p4(newCode.length),newCode,tail);byte[]newAttr=concat(p2(anIdx),p4(newInfo.length),newInfo);result=concat(Arrays.copyOfRange(data,0,attrStart),newAttr,Arrays.copyOfRange(data,end,data.length));oldLen=codeLen;newLen=newCode.length;oldMs=maxStack;newMs=ms;
                }
                o=end;
            }
        }
        if(matches!=1)throw new IOException("expected exactly one target method "+method+desc+", found "+matches);return new Patched(refs.apply(result),oldLen,newLen,oldMs,newMs);
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
        if("RuntimeVisibleTypeAnnotations".equals(name)||"RuntimeInvisibleTypeAnnotations".equals(name))throw new IOException("INSERT_AT_ENTRY does not yet support offset-bearing Code attribute "+name);
        return x;
    }

    private static Patched patchEntry(byte[] data,String method,String desc,int mref,String argsCsv)throws IOException{
        Cp cp=parseCp(data);int o=cp.end();o+=6;int ic=u2(data,o);o+=2+2*ic;int fc=u2(data,o);o+=2;o=skipMembers(data,o,fc);int mc=u2(data,o);o+=2;int matches=0;byte[]result=null;int oldLen=0,newLen=0,oldMs=0,newMs=0;
        byte[]loads=loadOps(argsCsv);byte[]rawCall=concat(loads,new byte[]{(byte)0xb8},p2(mref));int pad=(4-(rawCall.length&3))&3;byte[]call=concat(rawCall,new byte[pad]);int delta=call.length;int argc=(argsCsv==null||argsCsv.isBlank())?0:argsCsv.split(",").length;
        for(int mi=0;mi<mc;mi++){
            int methodStart=o;int nameIdx=u2(data,methodStart+2),descIdx=u2(data,methodStart+4);o+=6;int ac=u2(data,o);o+=2;String mn=cp.utf8()[nameIdx],md=cp.utf8()[descIdx];
            for(int ai=0;ai<ac;ai++){
                int attrStart=o,anIdx=u2(data,o);long al=u4(data,o+2);int info=o+6,end=info+(int)al;String an=cp.utf8()[anIdx];
                if(Objects.equals(mn,method)&&Objects.equals(md,desc)&&"Code".equals(an)){
                    matches++;int maxStack=u2(data,info),maxLocals=u2(data,info+2),codeLen=(int)u4(data,info+4),cs=info+8,ce=cs+codeLen;byte[]newCode=concat(call,Arrays.copyOfRange(data,cs,ce));
                    int q=ce,exCount=u2(data,q);q+=2;ByteArrayOutputStream ex=new ByteArrayOutputStream();ex.write(p2(exCount));
                    for(int i=0;i<exCount;i++){if(q+8>end)throw new EOFException();ex.write(p2(addU2(u2(data,q),delta,"exception.start_pc")));ex.write(p2(addU2(u2(data,q+2),delta,"exception.end_pc")));ex.write(p2(addU2(u2(data,q+4),delta,"exception.handler_pc")));ex.write(Arrays.copyOfRange(data,q+6,q+8));q+=8;}
                    if(q+2>end)throw new EOFException();int nac=u2(data,q);q+=2;ByteArrayOutputStream nested=new ByteArrayOutputStream();nested.write(p2(nac));
                    for(int ni=0;ni<nac;ni++){if(q+6>end)throw new EOFException();int nidx=u2(data,q),len=(int)u4(data,q+2),is=q+6,ie=is+len;if(ie>end)throw new EOFException();String nn=cp.utf8()[nidx];byte[]payload=shiftCodeAttributePayload(nn,Arrays.copyOfRange(data,is,ie),delta);nested.write(p2(nidx));nested.write(p4(payload.length));nested.write(payload);q=ie;}
                    if(q!=end)throw new IOException("Code attribute parse did not end cleanly for "+method+desc);int ms=Math.max(maxStack,argc);byte[]newInfo=concat(p2(ms),p2(maxLocals),p4(newCode.length),newCode,ex.toByteArray(),nested.toByteArray());byte[]newAttr=concat(p2(anIdx),p4(newInfo.length),newInfo);result=concat(Arrays.copyOfRange(data,0,attrStart),newAttr,Arrays.copyOfRange(data,end,data.length));oldLen=codeLen;newLen=newCode.length;oldMs=maxStack;newMs=ms;
                }
                o=end;
            }
        }
        if(matches!=1)throw new IOException("expected exactly one target method "+method+desc+", found "+matches);return new Patched(result,oldLen,newLen,oldMs,newMs);
    }

    private static Patched patchObjectReturn(byte[] data,String method,String desc,int mref,int castRef,String argsCsv)throws IOException{
        Cp cp=parseCp(data);int o=cp.end();o+=6;int ic=u2(data,o);o+=2+2*ic;int fc=u2(data,o);o+=2;o=skipMembers(data,o,fc);int mc=u2(data,o);o+=2;int matches=0;byte[]result=null;int oldLen=0,newLen=0,oldMs=0,newMs=0;
        byte[]loads=loadOps(argsCsv);byte[]call=concat(loads,new byte[]{(byte)0xb8},p2(mref),new byte[]{(byte)0xc0},p2(castRef));int argc=(argsCsv==null||argsCsv.isBlank())?0:argsCsv.split(",").length;
        for(int mi=0;mi<mc;mi++){
            int methodStart=o;int nameIdx=u2(data,methodStart+2),descIdx=u2(data,methodStart+4);o+=6;int ac=u2(data,o);o+=2;String mn=cp.utf8()[nameIdx],md=cp.utf8()[descIdx];
            for(int ai=0;ai<ac;ai++){
                int attrStart=o,anIdx=u2(data,o);long al=u4(data,o+2);int info=o+6,end=info+(int)al;String an=cp.utf8()[anIdx];
                if(Objects.equals(mn,method)&&Objects.equals(md,desc)&&"Code".equals(an)){
                    matches++;int maxStack=u2(data,info),maxLocals=u2(data,info+2),codeLen=(int)u4(data,info+4),cs=info+8,ce=cs+codeLen;
                    if(codeLen<=0||u1(data,ce-1)!=0xb0)throw new IOException("target "+method+desc+" does not end in ARETURN (0xb0)");
                    byte[]newCode=concat(Arrays.copyOfRange(data,cs,ce-1),call,new byte[]{(byte)0xb0});int ms=Math.max(maxStack,1+argc);
                    byte[]newInfo=concat(p2(ms),p2(maxLocals),p4(newCode.length),newCode,Arrays.copyOfRange(data,ce,end));byte[]newAttr=concat(p2(anIdx),p4(al+call.length),newInfo);result=concat(Arrays.copyOfRange(data,0,attrStart),newAttr,Arrays.copyOfRange(data,end,data.length));oldLen=codeLen;newLen=newCode.length;oldMs=maxStack;newMs=ms;
                }
                o=end;
            }
        }
        if(matches!=1)throw new IOException("expected exactly one target method "+method+desc+", found "+matches);return new Patched(result,oldLen,newLen,oldMs,newMs);
    }

    private static byte[] readClassFromJar(Path jar,String owner)throws IOException{String entry=owner.replace('.','/').replace('\\','/')+".class";try(ZipFile z=new ZipFile(jar.toFile())){ZipEntry e=z.getEntry(entry);if(e==null)throw new FileNotFoundException("class not found in jar: "+entry);try(InputStream in=z.getInputStream(e)){return in.readAllBytes();}}}

    private static void writePatched(Path out,byte[] original,String ownerLabel,String method,String desc,String helperOwner,String helperMethod,String helperDesc,String argsCsv,boolean entry)throws Exception{
        Appended ap=appendBridgeRef(original,helperOwner,helperMethod,helperDesc);Patched p=entry?patchEntry(ap.bytes(),method,desc,ap.methodRef(),argsCsv):patchFinalReturn(ap.bytes(),method,desc,ap.methodRef(),argsCsv);
        Files.createDirectories(out.toAbsolutePath().getParent());Files.write(out,p.bytes());int major=u2(original,6);
        System.out.printf(Locale.ROOT,"PATCH_OK transform=%s owner=%s method=%s%s major=%d class_bytes=%d->%d code=%d->%d max_stack=%d->%d bridge=%s.%s%s args=%s%n",entry?"INSERT_AT_ENTRY":"INSERT_BEFORE_FINAL_RETURN",ownerLabel,method,desc,major,original.length,p.bytes().length,p.oldCodeLen(),p.newCodeLen(),p.oldMaxStack(),p.newMaxStack(),helperOwner,helperMethod,helperDesc,argsCsv);
    }
    private static void writeWrapped(Path out,byte[] original,String ownerLabel,String method,String desc,String helperOwner,String helperMethod,String helperDesc,String argsCsv)throws Exception{
        String cast=objectReturnInternal(desc);AppendedWrap ap=appendBridgeRefAndClass(original,helperOwner,helperMethod,helperDesc,cast);Patched p=patchObjectReturn(ap.bytes(),method,desc,ap.methodRef(),ap.castClassRef(),argsCsv);
        Files.createDirectories(out.toAbsolutePath().getParent());Files.write(out,p.bytes());int major=u2(original,6);
        System.out.printf(Locale.ROOT,"PATCH_OK transform=WRAP_OBJECT_RETURN owner=%s method=%s%s major=%d class_bytes=%d->%d code=%d->%d max_stack=%d->%d bridge=%s.%s%s args=%s cast=%s%n",ownerLabel,method,desc,major,original.length,p.bytes().length,p.oldCodeLen(),p.newCodeLen(),p.oldMaxStack(),p.newMaxStack(),helperOwner,helperMethod,helperDesc,argsCsv,cast);
    }

    private static void patchJar(String[] a)throws Exception{
        if(a.length!=9&&a.length!=10)throw new IllegalArgumentException("patch-jar-final|patch-jar-entry <jar> <owner> <out.class> <method> <desc> <helperOwner> <helperMethod> <helperDesc> [argsCsv]");
        String argsCsv=a.length==10?a[9]:"";Path jar=Path.of(a[1]),out=Path.of(a[3]);String owner=a[2];byte[] original=readClassFromJar(jar,owner);boolean entry="patch-jar-entry".equals(a[0]);
        writePatched(out,original,owner,a[4],a[5],a[6],a[7],a[8],argsCsv,entry);
    }
    private static void patchClass(String[] a)throws Exception{
        if(a.length!=8&&a.length!=9)throw new IllegalArgumentException("patch-class-final|patch-class-entry <in.class> <out.class> <method> <desc> <helperOwner> <helperMethod> <helperDesc> [argsCsv]");
        String argsCsv=a.length==9?a[8]:"";Path in=Path.of(a[1]),out=Path.of(a[2]);byte[] original=Files.readAllBytes(in);boolean entry="patch-class-entry".equals(a[0]);
        writePatched(out,original,in.getFileName().toString(),a[3],a[4],a[5],a[6],a[7],argsCsv,entry);
    }
    private static void patchJarWrap(String[] a)throws Exception{
        if(a.length!=9&&a.length!=10)throw new IllegalArgumentException("patch-jar-wrap-object <jar> <owner> <out.class> <method> <desc> <helperOwner> <helperMethod> <helperDesc> [argsCsv]");
        String argsCsv=a.length==10?a[9]:"";Path jar=Path.of(a[1]),out=Path.of(a[3]);String owner=a[2];byte[] original=readClassFromJar(jar,owner);
        writeWrapped(out,original,owner,a[4],a[5],a[6],a[7],a[8],argsCsv);
    }
    private static void patchClassWrap(String[] a)throws Exception{
        if(a.length!=8&&a.length!=9)throw new IllegalArgumentException("patch-class-wrap-object <in.class> <out.class> <method> <desc> <helperOwner> <helperMethod> <helperDesc> [argsCsv]");
        String argsCsv=a.length==9?a[8]:"";Path in=Path.of(a[1]),out=Path.of(a[2]);byte[] original=Files.readAllBytes(in);
        writeWrapped(out,original,in.getFileName().toString(),a[3],a[4],a[5],a[6],a[7],argsCsv);
    }

    public static void main(String[] args)throws Exception{
        if(args.length==0){System.err.println("Commands: patch-jar-final|entry|wrap-object ... | patch-class-final|entry|wrap-object ...");System.exit(2);}
        switch(args[0]){
            case "patch-jar","patch-jar-final","patch-jar-entry"->patchJar(args);
            case "patch-class-final","patch-class-entry"->patchClass(args);
            case "patch-jar-wrap-object"->patchJarWrap(args);
            case "patch-class-wrap-object"->patchClassWrap(args);
            default->throw new IllegalArgumentException("unknown command: "+args[0]);
        }
    }
}
