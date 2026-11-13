#!/usr/bin/env python3
"""C8: class-name strings in Class.getName() comparisons follow class renames."""
import subprocess, tempfile, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def run(*cmd):
 p=subprocess.run([str(v) for v in cmd],text=True,capture_output=True)
 if p.returncode: raise AssertionError(f'{cmd}: exit={p.returncode}\n{p.stdout}\n{p.stderr}')
 return p.stdout
with tempfile.TemporaryDirectory(prefix='bml-c8-reflect-') as tmp:
 p=Path(tmp);old=p/'old';new=p/'new';helper=p/'helper'
 for d in (old,new,helper):d.mkdir()
 (old/'rgb.java').write_text('public interface rgb {}')
 (new/'foz.java').write_text('public interface foz {}')
 (new/'ListenerTarget.java').write_text('public class ListenerTarget {public void addListener(foz l) {} }')
 (helper/'CompatProbe.java').write_text('''import java.lang.reflect.*;
public class CompatProbe {
 public static String descriptor(rgb ignored){return "unused";}
 public static Method findListener(Object obj)throws Exception {
   for(Method m:obj.getClass().getMethods()){
     if(m.getName().equals("addListener") && m.getParameterCount()==1 &&
        m.getParameterTypes()[0].getName().equals("rgb")) return m;
   }
   throw new NoSuchMethodException("listener not found");
 }
 public static String ordinaryText(){return "unchanged string";}
}''')
 (new/'Runner.java').write_text('''public class Runner {
 public static void main(String[] args)throws Exception {
  java.lang.reflect.Method m=CompatProbe.findListener(new ListenerTarget());
  if(!m.getParameterTypes()[0].getName().equals("foz"))throw new AssertionError("wrong listener");
  if(!CompatProbe.ordinaryText().equals("unchanged string"))throw new AssertionError();
  System.out.println("PASS: reflected class name and unrelated string");
 }
}''')
 run('javac','-d',old,old/'rgb.java')
 helperClasses=p/'helper-classes'
 run('javac','-cp',old,'-d',helperClasses,helper/'CompatProbe.java')
 run('javac','-d',new,new/'foz.java',new/'ListenerTarget.java')
 orig=p/'helper.jar';run('jar','cf',orig,'-C',helperClasses,'.')
 mp=p/'map.tsv';mp.write_text('C\trgb\tfoz\n')
 remapped=p/'translated.jar';report=p/'report.txt'
 output=run('java','-jar',ROOT/'tools/bitwig-helper-remapper.jar',orig,remapped,mp,p/'missing.tsv',report)
 assert 'runtime_reflection_bridge_calls=1' in output,output
 # Compare the unmodified, source-era binary in a fully absent-original runtime.
 run('javac','-cp',f'{remapped}:{new}','-d',new,new/'Runner.java')
 fail=subprocess.run(['java','-cp',f'{orig}:{new}','Runner'],text=True,capture_output=True)
 assert fail.returncode!=0 and 'listener not found' in fail.stderr,fail.stderr
 out=run('java','-Xverify:all','-cp',f'{remapped}:{new}','Runner')
 assert 'PASS: reflected class name' in out,out
 with zipfile.ZipFile(remapped) as z:
  assert b'foz' in z.read('CompatProbe.class')
 print('PASS: source-era comparison fails, translated comparison links and executes against target type')
