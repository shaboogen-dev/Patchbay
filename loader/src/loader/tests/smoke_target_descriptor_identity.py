from pathlib import Path
import os, subprocess, tempfile, zipfile, sys
ROOT=Path(__file__).resolve().parents[3]
R=Path(__file__).resolve().parents[2]
REMAPPER=Path(sys.argv[1]) if len(sys.argv)>1 else R/'loader/tools/bitwig-helper-remapper.jar'
def call(args, env=None):
 p=subprocess.run([str(x) for x in args],text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env)
 if p.returncode: raise RuntimeError(f'{p.returncode}: {args}\n{p.stdout}\n{p.stderr}')
 return p.stdout+p.stderr
with tempfile.TemporaryDirectory() as tmp:
 d=Path(tmp); src=d/'src';src.mkdir();cl=d/'classes';cl.mkdir()
 for name,body in {'cBi':'public static final cBh cfM = new cBh();','cBh':'','cCV':'public final cCB E3 = new cCB();','cCB':''}.items():
  (src/(name+'.java')).write_text(f'public class {name} {{{body}}}\n')
 (src/'BindingProbe.java').write_text('''
import java.util.*;import java.io.*;import java.nio.file.*;import java.lang.reflect.*;
public class BindingProbe {
 private static String descriptor(Class<?> type) {
   if(type.isPrimitive())return type==int.class?"I":type==boolean.class?"Z":"V";
   if(type.isArray())return type.getName().replace('.','/');
   return "L"+type.getName().replace('.','/')+";";
 }
 public static void main(String[] x) throws Exception {
  Properties p=new Properties();try(Reader r=Files.newBufferedReader(Path.of(System.getenv("BML_MOD_CONFIG")))){p.load(r);}
  for(String id: new String[]{"live_reference","project_hierarchy_model"}){
   String prefix="mod.test.target."+id+".";
   Class<?> o=Class.forName(p.getProperty(prefix+"owner"),false,BindingProbe.class.getClassLoader());
   Field f=o.getDeclaredField(p.getProperty(prefix+"name"));
   Class<?> t=f.getType();
   String got=descriptor(t);
   String expected=p.getProperty(prefix+"descriptor");
   if(!expected.equals(got))throw new AssertionError(id+": resolved target type corrupted "+got+" vs "+expected);
   Object obj=Modifier.isStatic(f.getModifiers())?f.get(null):f.get(o.getConstructor().newInstance());
   if(obj==null)throw new AssertionError(id+": null target value");
   System.out.println(id+": "+got+" OK");
  }
 }
}''')
 call(['javac','--release','21','-d',cl,*src.glob('*.java')])
 inp=d/'orig.jar';out=d/'remap.jar';
 with zipfile.ZipFile(inp,'w') as z: z.write(cl/'BindingProbe.class','BindingProbe.class')
 mp=d/'map.tsv';mp.write_text('C\tcBH\tcBh\nC\tcDa\tcCB\nC\tcBI\tcBi\nC\tcDt\tcCV\n')
 conf=d/'bindings.properties';conf.write_text('''mod.test.target.live_reference.kind=field
mod.test.target.live_reference.owner=cBi
mod.test.target.live_reference.name=cfM
mod.test.target.live_reference.descriptor=LcBh;
mod.test.target.project_hierarchy_model.kind=field
mod.test.target.project_hierarchy_model.owner=cCV
mod.test.target.project_hierarchy_model.name=E3
mod.test.target.project_hierarchy_model.descriptor=LcCB;
''')
 result=call(['java','-jar',REMAPPER,inp,out,mp,d/'source-ancestors.tsv',d/'report.txt','test'])
 env=dict(os.environ,BML_MOD_CONFIG=str(conf))
 result+=call(['java','-Xverify:all','-cp',str(out)+os.pathsep+str(cl),'BindingProbe'],env)
 print(result)
