#!/usr/bin/env python3
"""Verify code-level inherited method alias and reflection string name translations."""
import subprocess,tempfile,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def run(*args,check=True):
 p=subprocess.run([str(a) for a in args],text=True,capture_output=True)
 if check and p.returncode:raise AssertionError(f'{args}:\n{p.stdout}\n{p.stderr}')
 return p

with tempfile.TemporaryDirectory(prefix='bml-c7-reflect-') as temp:
 root=Path(temp)
 old=root/'old';new=root/'new';helpers=root/'helpers'
 for folder in (old,new,helpers):folder.mkdir()
 files={
  old/'OldBase.java':'package bw; public class OldBase { public void g23(boolean b){ if(!b)throw new AssertionError(); } }',
  old/'OldChild.java':'package bw; public class OldChild extends OldBase {}',
  old/'OldUtil.java':'package bw; public class OldUtil { public static String vhT(OldChild[] v){ return "static-ok"; } }',
  old/'OldPresenter.java':'package bw; public class OldPresenter { public String vhT(String v){ return "virtual-"+v; } }',
  new/'NewBase.java':'package bw; public class NewBase { public void OzW(boolean b){ if(!b)throw new AssertionError(); } }',
  new/'NewChild.java':'package bw; public class NewChild extends NewBase {}',
  new/'NewUtil.java':'package bw; public class NewUtil { public static String cfM(NewChild[] v){ return "static-ok"; } }',
  new/'NewPresenter.java':'package bw; public class NewPresenter { public String cfM(String v){ return "virtual-"+v; } }',
  helpers/'PortabilityHelper.java':'''import bw.*; import java.lang.invoke.*;
public class PortabilityHelper {
 public static void main(String[] args)throws Throwable {
  OldChild child=new OldChild(); child.g23(true);
  java.lang.reflect.Method method=OldChild.class.getMethod("g23", boolean.class);
  method.invoke(child,true);
  MethodHandles.Lookup lookup=MethodHandles.lookup();
  MethodHandle staticCall=lookup.findStatic(OldUtil.class,"vhT",MethodType.methodType(String.class,OldChild[].class));
  MethodHandle virtualCall=lookup.findVirtual(OldPresenter.class,"vhT",MethodType.methodType(String.class,String.class));
  if(!((String)staticCall.invokeExact(new OldChild[]{child})).equals("static-ok"))throw new AssertionError();
  if(!((String)virtualCall.invokeExact(new OldPresenter(),"ok")).equals("virtual-ok"))throw new AssertionError();
  if(!"unrelated".equals("unrelated"))throw new AssertionError("Unrelated literal unexpectedly changed");
  System.out.println("PASS reflection+inherited runtime");
 }
}'''
 }
 for p,s in files.items():
  p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s)
 oldClasses=root/'old-classes';newClasses=root/'new-classes';helperClasses=root/'helper-classes'
 run('javac','-d',oldClasses,*old.glob('*.java'))
 run('javac','-d',newClasses,*new.glob('*.java'))
 run('javac','-cp',oldClasses,'-d',helperClasses,helpers/'PortabilityHelper.java')
 jar=root/'helper.jar';run('jar','cf',jar,'-C',helperClasses,'.')
 tsv=root/'map.tsv';tsv.write_text('\n'.join([
  'C\tbw/OldBase\tbw/NewBase','C\tbw/OldChild\tbw/NewChild',
  'C\tbw/OldUtil\tbw/NewUtil','C\tbw/OldPresenter\tbw/NewPresenter',
  'M\tbw/OldChild\tg23\t(Z)V\tbw/NewChild\tOzW\t(Z)V',
  'M\tbw/OldUtil\tvhT\t([Lbw/OldChild;)Ljava/lang/String;\tbw/NewUtil\tcfM\t([Lbw/NewChild;)Ljava/lang/String;',
  'M\tbw/OldPresenter\tvhT\t(Ljava/lang/String;)Ljava/lang/String;\tbw/NewPresenter\tcfM\t(Ljava/lang/String;)Ljava/lang/String;',
 ])+'\n')
 translated=root/'translated.jar';report=root/'report.txt'
 r=run('java','-jar',ROOT/'tools/bitwig-helper-remapper.jar',jar,translated,tsv,root/'missing-overrides.tsv',report)
 assert 'runtime_reflection_bridge_calls=3' in r.stdout,r.stdout
 r=run('java','-Xverify:all','-cp',f'{translated}:{newClasses}','PortabilityHelper')
 assert 'PASS reflection+inherited runtime' in r.stdout,r.stdout
 print('PASS: changed inherited invocation + MethodHandles.findStatic/findVirtual + Class.getMethod execution')
