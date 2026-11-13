#!/usr/bin/env python3
"""Run a translated helper override on a target-only JVM with no source superclass."""
from pathlib import Path
import tempfile,subprocess,zipfile,gzip
root=Path(__file__).resolve().parents[2]
tool=root/'loader/tools/bitwig-helper-remapper.jar'

def run(*args):
 p=subprocess.run([str(s) for s in args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 assert p.returncode==0,(args,p.stdout)
 return p.stdout
with tempfile.TemporaryDirectory(prefix='bml-override-regression-') as temp:
 t=Path(temp);(t/'old').mkdir();(t/'h').mkdir();(t/'nextapi').mkdir()
 (t/'old/Parent.java').write_text('package old; public class Parent { public int oldMethod() { return -1; } }')
 (t/'h/Child.java').write_text('package h; public class Child extends old.Parent { @Override public int oldMethod() { return 42; } public int unrelated() { return 13; } }')
 (t/'nextapi/Parent.java').write_text('package nextapi; public class Parent { public int newMethod() { return -1; } }')
 (t/'Runner.java').write_text('public class Runner { public static void main(String[] a) { h.Child c=new h.Child(); if(c.newMethod()!=42 || c.unrelated()!=13)throw new AssertionError(); System.out.println("OVERRIDE_OK"); } }')
 run('javac','-d',t/'oldclasses',t/'old/Parent.java',t/'h/Child.java')
 run('javac','-d',t/'newclasses',t/'nextapi/Parent.java')
 with zipfile.ZipFile(t/'helper.jar','w') as z: z.write(t/'oldclasses/h/Child.class','h/Child.class')
 with gzip.open(t/'map.tsv.gz','wt') as z:
  z.write('C\told/Parent\tnextapi/Parent\nM\told/Parent\toldMethod\t()I\tnextapi/Parent\tnewMethod\t()I\n')
 out=run('java','-jar',tool,t/'helper.jar',t/'helper-remapped.jar',t/'map.tsv.gz',t/'none.tsv',t/'report.txt')
 assert 'helper_override_declarations_translated=1' in out,out
 assert 'warnings=0' in out,out
 run('javac','-cp',str(t/'helper-remapped.jar')+':'+str(t/'newclasses'),'-d',t/'runnerclasses',t/'Runner.java')
 result=run('java','-Xverify:all','-cp',str(t/'runnerclasses')+':'+str(t/'helper-remapped.jar')+':'+str(t/'newclasses'),'Runner')
 assert 'OVERRIDE_OK' in result,result
 print('PASS: mapped superclass override renamed in mod-owned class and executed on target-only JVM')
