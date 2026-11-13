from pathlib import Path
import subprocess, zipfile, tempfile,os
ROOT=Path(__file__).resolve().parents[2]
def run(cmd,env=None):
 p=subprocess.run(list(map(str,cmd)),capture_output=True,text=True,env=env)
 if p.returncode:raise RuntimeError(' '.join(map(str,cmd))+'\n'+p.stdout+'\n'+p.stderr)
 return p.stdout+p.stderr
with tempfile.TemporaryDirectory() as t:
 d=Path(t);src=d/'src';src.mkdir();cl=d/'classes';cl.mkdir()
 for name,fields in [('eoW','public static final eoW Hnt=new eoW();'),('eoN','public static final eoN MRY=new eoN();')]:
  (src/(name+'.java')).write_text('public class '+name+' {'+fields+'}')
 (src/'Probe.java').write_text('''import java.util.*;import java.nio.file.*;import java.io.*;
public class Probe {
 public static void main(String[] argv)throws Exception{
  Properties p=new Properties();try(Reader r=Files.newBufferedReader(Path.of(System.getenv("BML_MOD_CONFIG")))){p.load(r);}
  String name=p.getProperty("mod.remote.target.menu.owner");
  Class<?> c=Class.forName(name);
  if(!c.getName().equals("epf"))throw new AssertionError("getName mismatch: "+c.getName());
  if(c!=Class.forName("epf"))throw new AssertionError("source literal not translated");
  if(c.getDeclaredField(p.getProperty("mod.remote.target.menu.name")).get(null).getClass()!=c)throw new AssertionError("field failed");
  System.out.println("PASS: original source and BML-resolved target both eoW");
 }
}''')
 (src/'Other.java').write_text('''public class Other {public static void main(String[] args)throws Exception {
 if(!Class.forName("eoW").getName().equals("eoW"))throw new AssertionError("unexpected name");
 if(Class.forName("eoW")!=eoN.class)throw new AssertionError("cross-mod BML binding leaked");
 System.out.println("PASS: other mod maps old eoW to eoN despite remote target being pinned");
}}''')
 run(['javac','--release','21','-d',cl,*src.glob('*.java')]);
 mp=d/'map.tsv';mp.write_text('C\tepf\teoW\nC\teoW\teoN\nF\tepf\tHEY\tLepf;\teoW\tHnt\tLeoW;\n')
 conf=d/'bindings.properties';conf.write_text('''mod.remote.target.menu.kind=field
mod.remote.target.menu.owner=eoW
mod.remote.target.menu.name=Hnt
mod.remote.target.menu.descriptor=LeoW;
mod.other.target.another.kind=field
mod.other.target.another.owner=eoN
mod.other.target.another.name=MRY
mod.other.target.another.descriptor=LeoN;
''')
 env=dict(os.environ,BML_MOD_CONFIG=str(conf))
 remapper=ROOT/'loader/tools/bitwig-helper-remapper.jar'
 for probe,modid in [('Probe','remote'),('Other','other')]:
  orig=d/(modid+'-orig.jar');out=d/(modid+'-remapped.jar')
  with zipfile.ZipFile(orig,'w') as z:z.write(cl/(probe+'.class'),probe+'.class')
  print((run(['java','-jar',remapper,orig,out,mp,d/'missing.tsv',d/'report.txt',modid])).split('runtime_reflection_bridge_calls=')[-1].splitlines()[0])
  res=run(['java','-Xverify:all','-cp',str(out)+os.pathsep+str(cl),probe],env)
  print(res.strip())
print('ALL R7 MOD-SCOPED REGRESSIONS PASSED')
