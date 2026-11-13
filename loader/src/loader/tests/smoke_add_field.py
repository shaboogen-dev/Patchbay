#!/usr/bin/env python3
"""Declared instance fields ("fields" in a manifest, FieldAdder): what they add, and what they refuse.

Synthetic: no Bitwig, no real mods. Runs JavaPreparer on a temporary install, then runs the prepared classes.
Run it from an unpacked core ZIP (loader/tests/), or name one: smoke_add_field.py [<unpacked core>].

Never touches the per-user settings folder: the temporary install has config/portable, which is checked before
anything runs, and the per-user variables point into the temporary folder as well.
"""
import hashlib,json,os,shutil,subprocess,sys,tempfile,zipfile
from pathlib import Path

CLASSES={
 'Base':'public class Base { public int inheritedBits; }',
 'Page':'public class Page extends Base { public static int ticks; public void tick(){ticks++;} public int stock(){return 41;} }',
 'PageSub':'public class PageSub extends Page { public int own=7; public long wide=8; }',
 'Thing':'public class Thing { public String toString(){return "thing";} }',
 'Hidden':'class Hidden { }',
 'Clash':'public class Clash { public int mod$x; }',
 'ParentClash':'public class ParentClash { public String mod$x; }',
 'Inherits':'public class Inherits extends ParentClash { }',
 'Parent':'public class Parent { }',
 'ChildClash':'public class ChildClash extends Parent { public int mod$x; }',
 'Iface':'public interface Iface { }',
 'Rec':'public record Rec(int a) { }',
 'En':'public enum En { ONE }',
 'Ser':'public class Ser implements java.io.Serializable { }',
 'SerSub':'public class SerSub extends Ser { }',
 'SerFace':'public interface SerFace extends java.io.Serializable { }',
 'UsesSerFace':'public class UsesSerFace implements SerFace { }',
}
FIELDS=[('z','Z','boolean'),('b','B','byte'),('c','C','char'),('s','S','short'),('i','I','int'),('j','J','long'),('f','F','float'),('d','D','double'),
        ('o','Ljava/lang/Object;','Object'),('t','Ljava/lang/String;','String'),('w','Lexample/Thing;','example.Thing'),('a','[I','int[]'),('r','[Lexample/Thing;','example.Thing[]')]
HELPER='''package demo.mod;
import java.lang.invoke.*;
public final class H {
 static VarHandle vh(Class<?> owner,String name,Class<?> type){
  try{return MethodHandles.privateLookupIn(owner,MethodHandles.lookup()).findVarHandle(owner,name,type);}
  catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
 }
 static final class F {
%(handles)s
 }
 public static void tick(example.Page p){
  F.z.setVolatile(p,true);F.b.setVolatile(p,(byte)1);F.c.setVolatile(p,'x');F.s.setVolatile(p,(short)2);F.i.setVolatile(p,3);
  F.j.setVolatile(p,4L);F.f.setVolatile(p,5f);F.d.setVolatile(p,6d);F.o.setVolatile(p,(Object)"object");F.t.setVolatile(p,"string");
  F.w.setVolatile(p,new example.Thing());F.a.setVolatile(p,new int[]{9});F.r.setVolatile(p,new example.Thing[]{new example.Thing()});
 }
 public static String read(example.Page p){
  return ""+(boolean)F.z.getVolatile(p)+" "+(byte)F.b.getVolatile(p)+" "+(char)F.c.getVolatile(p)+" "+(short)F.s.getVolatile(p)+" "+(int)F.i.getVolatile(p)
   +" "+(long)F.j.getVolatile(p)+" "+(float)F.f.getVolatile(p)+" "+(double)F.d.getVolatile(p)+" "+(Object)F.o.getVolatile(p)+" "+(String)F.t.getVolatile(p)
   +" "+(example.Thing)F.w.getVolatile(p)+" "+((int[])F.a.getVolatile(p))[0]+" "+((example.Thing[])F.r.getVolatile(p))[0];
 }
 public static String plain(example.Page p){F.i.set(p,20);return ""+(int)F.i.get(p);}
 public static boolean hidden() throws Exception{
  Class<?> c=Class.forName("example.Hidden");
  java.lang.reflect.Constructor<?> k=c.getDeclaredConstructor();k.setAccessible(true);Object o=k.newInstance();
  VarHandle h=vh(c,"mod$hidden",long.class);h.setVolatile(o,5L);return (long)h.getVolatile(o)==5L;
 }
}
'''
MAIN='''import java.lang.reflect.*;
public class Main {public static void main(String[] a) throws Exception{
 example.PageSub p=new example.PageSub();
 System.out.println("start "+demo.mod.H.class.getSimpleName());
 p.tick();
 System.out.println(demo.mod.H.read(p));
 System.out.println("plain "+demo.mod.H.plain(p));
 System.out.println("sub "+p.own+" "+p.wide+" "+p.stock()+" "+example.Page.ticks);
 example.PageSub q=new example.PageSub();
 System.out.println("separate "+q.getClass().getSuperclass().getField("mod$i").get(q));
 int m=example.Page.class.getField("mod$i").getModifiers();
 System.out.println("flags "+Modifier.isPublic(m)+" "+Modifier.isTransient(m)+" "+((m&0x1000)!=0)+" "+Modifier.isVolatile(m)+" "+Modifier.isStatic(m));
 System.out.println("hidden "+demo.mod.H.hidden()+" public "+Modifier.isPublic(Class.forName("example.Hidden").getModifiers()));
}}
'''
PLAIN_HELPER='''package demo.%(pkg)s;
public final class H { public static void tick(example.Page p){} }
'''

def run(args,env=None):
    return subprocess.run([str(a) for a in args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,env=env)
def must(args,env=None):
    p=run(args,env)
    if p.returncode:raise SystemExit(f'Fixture command failed: {args}\n{p.stdout}')
    return p

class Fixture:
    def __init__(self,tmp,core):
        self.t=tmp;self.root=tmp/'bml';self.loader=self.root/'loader';self.mods=self.root/'mods'
        shutil.copytree(core/'loader',self.loader,ignore=shutil.ignore_patterns('mod-runtime','tests','jvmti'))
        (self.root/'config').mkdir(parents=True);(self.root/'config/portable').write_text('')
        if not (self.root/'config/portable').exists():raise SystemExit('No config/portable: refusing to run')
        self.env={k:v for k,v in os.environ.items() if not k.startswith('BML_')}
        self.env.update(LOCALAPPDATA=str(tmp/'peruser'),XDG_CONFIG_HOME=str(tmp/'peruser'),BML_PREPARE_FRESH='1')
        self.bin=tmp/'bitwig/bin';self.bin.mkdir(parents=True)
        java=Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java') if 'JAVA_HOME' in os.environ else Path(shutil.which('java'))
        shutil.copytree(java.parent.parent,tmp/'bitwig/jre',dirs_exist_ok=True,ignore=shutil.ignore_patterns('jmods','include','legal','demo','man','src.zip'))
        src=tmp/'src/example';src.mkdir(parents=True)
        for name,body in CLASSES.items():(src/f'{name}.java').write_text('package example;\n'+body+'\n')
        must(['javac','--release','21','-d',tmp/'classes',*src.glob('*.java')])
        must(['jar','cf',self.bin/'bitwig.jar','-C',tmp/'classes','.'])
        (self.loader/'compatibility').mkdir(exist_ok=True)
        (self.loader/'compatibility/builds.json').write_text(json.dumps({'format_version':2,'builds':[{
            'build_key':'windows-6.1.3','bitwig_version':'6.1.3','platform':'windows','architecture':'x64',
            'bitwig_jar_sha256':hashlib.sha256((self.bin/'bitwig.jar').read_bytes()).hexdigest()}]}))
    def jar(self,name,source,package):
        src=self.t/'h-src'/name;src.mkdir(parents=True,exist_ok=True);(src/'H.java').write_text(source)
        out=self.t/'h-classes'/name
        must(['javac','--release','21','-cp',self.t/'classes','-d',out,src/'H.java'])
        jar=self.t/'h-jars'/f'{name}.jar';jar.parent.mkdir(exist_ok=True)
        must(['jar','cf',jar,'-C',out,'.'])
        return jar
    def mod(self,mod,fields,targets=None,helper=None,cls=None,opens=()):
        """fields: [{id, owner (a local target), name, descriptor}, ...]; targets: local target -> physical."""
        folder=self.mods/mod;folder.mkdir(parents=True)
        pkg=''.join(c if c.isalnum() else '_' for c in mod)
        shutil.copy2(helper or self.jar(pkg,PLAIN_HELPER%{'pkg':pkg},pkg),folder/'helper.jar')
        all_targets={'tick':{'kind':'method','owner':'example/Page','name':'tick','descriptor':'()V'}}
        all_targets.update(targets or {})
        (folder/'mod.json').write_text(json.dumps({'manifest_version':2,
            'mod':{'id':mod,'name':mod,'version':'1.0.0','author':'BML','description':'Field fixture'},
            'built_against':{'bitwig_version':'6.1.3','platform':'windows','architecture':'x64'},
            'helper':{'jar':'helper.jar','class':cls or f'demo.{pkg}.H'},
            'targets':all_targets,
            'hooks':[{'id':'tick','target':'tick','transform':'INSERT_AT_ENTRY','bridge':{'method':'tick','descriptor':'(Lexample/Page;)V','args':['this']}}],
            'fields':fields,'opens':list(opens),'trace_targets':[]}))
    def prepare(self):
        return run(['java','-cp',self.loader/'app/bml-control.jar','dev.bitwigre.bml.JavaPreparer','--loader-root',self.loader,
                    '--bitwig-bin',self.bin,'--mods-root',self.mods],self.env)
    def clear(self):
        if self.mods.exists():shutil.rmtree(self.mods)
    def execute(self):
        rt=self.loader/'mod-runtime'
        cp=os.pathsep.join(str(p) for p in (rt/'deep-verify-replacements.jar',rt/'helper.jar',self.bin/'bitwig.jar',self.t/'driver'))
        return must(['java','-Xverify:all','-cp',cp,'Main']).stdout.strip().splitlines()
    def patched(self):
        """Whatever a refusal must not leave: prepared classes or a replacement map."""
        rt=self.loader/'mod-runtime'
        return [p.name for p in [*(rt/'replacements').glob('*.class'),rt/'replacement-map.tsv'] if p.exists()]

failures=[]
def report(name,ok,detail):
    print(('PASS' if ok else 'FAIL')+': '+name+'\n      '+detail)
    if not ok:failures.append(name)
def field(owner,name='mod$x',descriptor='I',id='x'):
    return {'id':id,'owner':owner,'name':name,'descriptor':descriptor}
def cls(name):
    return {name:{'kind':'class','class':'example/'+name}}
def refused(f,title,mods,*needles):
    f.clear()
    for mod,fields,targets in mods:f.mod(mod,fields,targets)
    p=f.prepare()
    if not p.returncode:
        report(title,False,'expected a refusal; prepared');return
    missing=[n for n in needles if n not in p.stdout]
    left=f.patched()
    report(title,not missing and not left,' | '.join(l for l in p.stdout.splitlines() if l.strip())[:360]
           +(f'  MISSING {missing}' if missing else '')+(f'  LEFT {left}' if left else ''))
def one(owner):
    return [('mod',[field(owner)],cls(owner))]

def main():
    core=Path(sys.argv[1]) if len(sys.argv)>1 else Path(__file__).resolve().parents[2]
    if not (core/'loader/app/bml-control.jar').is_file():
        raise SystemExit(f'No built loader in {core}: run from an unpacked core ZIP or name one')
    print('Loader under test:',core)
    with tempfile.TemporaryDirectory(prefix='bml-add-field-') as d:
        f=Fixture(Path(d),core)
        # Works: every kind of type, beside a hook on the same class, a subclass with fields of its own, a non-public owner.
        handles='\n'.join(f'  static final VarHandle {n}=vh(example.Page.class,"mod${n}",{t}.class);' for n,_,t in FIELDS)
        helper=f.jar('mod',HELPER%{'handles':handles},'mod')
        targets={'tick':{'kind':'method','owner':'example/Page','name':'tick','descriptor':'()V'},**cls('Page'),**cls('Thing'),**cls('Hidden')}
        fields=[field('Page','mod$'+n,desc,'f-'+n) for n,desc,_ in FIELDS]+[field('Hidden','mod$hidden','J','hidden')]
        f.mod('mod',fields,targets,helper,'demo.mod.H',['Hidden'])
        p=f.prepare()
        if p.returncode or 'PREPARE_OK' not in p.stdout:
            report('fields prepare',False,p.stdout[-600:])
        else:
            report('fields prepare',f'transforms={1+len(fields)}' in p.stdout,[l for l in p.stdout.splitlines() if 'PREPARE_OK' in l][0])
            src=Path(d)/'main';src.mkdir();(src/'Main.java').write_text(MAIN)
            rt=f.loader/'mod-runtime'
            must(['javac','--release','21','-cp',os.pathsep.join(map(str,[rt/'deep-verify-replacements.jar',rt/'helper.jar',f.bin/'bitwig.jar'])),'-d',Path(d)/'driver',src/'Main.java'])
            got=f.execute()
            want=['start H','true 1 x 2 3 4 5.0 6.0 object string thing 9 thing',
                  'plain 20','sub 7 8 41 1','separate 0','flags true true true false false','hidden true public true']
            ok=got==want
            report('every field type works through a VarHandle, per instance, under -Xverify:all',ok,' / '.join(got))
            comp=(rt/'composition-report.tsv').read_text()
            report('the composition report lists each field',comp.count('\tADD_FIELD\t')==len(fields),f"{comp.count(chr(9)+'ADD_FIELD'+chr(9))} rows")
            report('the fields are declared in the prepared class',all(('mod$'+n) in must(['javap','-p','-cp',str(rt/'deep-verify-replacements.jar'),'example.Page']).stdout for n,_,_ in FIELDS),'javap')
            with zipfile.ZipFile(f.bin/'bitwig.jar') as z:report('bitwig.jar is untouched',b'mod$i' not in z.read('example/Page.class'),'stock Page has no field')
        # Refused, by the class.
        refused(f,'a name the class declares',one('Clash'),'Mod mod can\'t add field example.Clash.mod$x','already declares')
        refused(f,'a name a superclass declares',one('Inherits'),'its supertype example.ParentClash','hide')
        refused(f,'a name a subclass declares',one('Parent'),'its subclass example.ChildClash')
        refused(f,'an interface',one('Iface'),'is an interface')
        refused(f,'a record',one('Rec'),'is a record')
        refused(f,'an enum',one('En'),'is an enum')
        refused(f,'a Serializable class',one('Ser'),'Serializable')
        refused(f,'a Serializable superclass',one('SerSub'),'Serializable')
        refused(f,'a Serializable superinterface',one('UsesSerFace'),'Serializable')
        # Refused, by another mod. Mod IDs that share a namespace are the one way two mods can name one field.
        refused(f,'two mods, one field',[('dup-a',[field('Page','dup_a$x')],cls('Page')),('dup_a',[field('Page','dup_a$x')],cls('Page'))],
                'Mods dup-a and dup_a both declare','example.Page.dup_a$x','Untick')
        refused(f,'two mods, one name on a class and its supertype',[('dup-a',[field('Page','dup_a$x')],cls('Page')),('dup_a',[field('Base','dup_a$x')],cls('Base'))],
                'dup-a','dup_a','its supertype example.Base')
        refused(f,'one mod, the same field twice',[('mod',[field('Page'),field('Page',id='y')],cls('Page'))],'both declare')
        # Refused, by the manifest.
        refused(f,'a name outside the mod\'s namespace',[('mod',[field('Page','other$x')],cls('Page'))],'the name must be mod$<name>')
        refused(f,'a void descriptor',[('mod',[field('Page',descriptor='V')],cls('Page'))],'invalid descriptor V')
        refused(f,'a method descriptor',[('mod',[field('Page',descriptor='()V')],cls('Page'))],'invalid descriptor')
        refused(f,'a type that is not a target',[('mod',[field('Page',descriptor='Lexample/Thing;')],cls('Page'))],'must be a class target')
        refused(f,'an owner that is not a class target',[('mod',[field('tick')],None)],'must be a class target')
        refused(f,'an unknown key',[('mod',[{**field('Page'),'static':True}],cls('Page'))],'unknown key static')
        # Everything is reported at once, and a refusal in one class leaves nothing patched in another.
        refused(f,'several refusals together',[('mod',[field('Clash'),field('Iface',id='y'),field('Page',id='z')],{**cls('Clash'),**cls('Iface'),**cls('Page')})],
                'example.Clash.mod$x','example.Iface.mod$x')
        # No field declared: no tool runs and the class is what a hook alone makes it.
        f.clear();f.mod('mod',[],None)
        p=f.prepare()
        report('no fields declared',p.returncode==0 and 'transforms=1' in p.stdout and 'ADD_FIELD' not in (f.loader/'mod-runtime/composition-report.tsv').read_text()
               and 'FIELDS_OK' not in (f.loader/'mod-runtime/transform-report.txt').read_text(),'')
    print()
    if failures:
        print(f'ADD_FIELD_SMOKE_FAILED {len(failures)} of the cases above')
        sys.exit(1)
    print('ADD_FIELD_SMOKE_OK')

if __name__=='__main__':main()
