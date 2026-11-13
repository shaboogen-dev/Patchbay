"""Regression: target-name comparisons use true JVM names while old literals retain compatibility aliases."""
from pathlib import Path
import os, subprocess, tempfile, zipfile

ROOT=Path(__file__).resolve().parents[2]
REMAPPER=ROOT/'loader/tools/bitwig-helper-remapper.jar'
def run(cmd,env=None):
    r=subprocess.run(list(map(str,cmd)),capture_output=True,text=True,env=env)
    if r.returncode:
        raise RuntimeError(str(cmd)+'\n'+r.stdout+'\n'+r.stderr)
    return r.stdout+r.stderr

with tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp);src=root/'src';src.mkdir();classes=root/'classes';classes.mkdir()
    (src/'cKU.java').write_text('public class cKU {}\n')
    (src/'zLm.java').write_text('public class zLm {}\n')
    (src/'TargetProbe.java').write_text('''
import java.util.*;import java.io.*;import java.nio.file.*;
public class TargetProbe {
 private static String req(Properties p,String k) {return p.getProperty("mod.test.target."+k);}
 private static Properties cfg()throws Exception {
  Properties p=new Properties();
  try(Reader r=Files.newBufferedReader(Path.of(System.getenv("BML_MOD_CONFIG")))){p.load(r);}
  return p;
 }
 private static String boundClass(String id)throws Exception {
  return req(cfg(),id+".class").replace('/','.');
 }
 private static boolean dynamicCompare(Object obj,String id)throws Exception {
  return boundClass(id).equals(obj.getClass().getName());
 }
 private static boolean directCompare(Object obj)throws Exception {
  return boundClass("cue").equals(obj.getClass().getName());
 }
 public static void main(String[] args)throws Exception {
  if(!dynamicCompare(new cKU(),"cue"))throw new AssertionError("cue target comparison failed");
  if(!dynamicCompare(new zLm(),"gesture"))throw new AssertionError("gesture target comparison failed");
  if(!directCompare(new cKU()))throw new AssertionError("literal-key target comparison failed");
  if(!cKU.class.getName().equals("cKX"))throw new AssertionError("source-name legacy compatibility regressed");
  System.out.println("PASS: both dynamic and literal target comparisons; legacy class alias preserved");
 }
}''')
    run(['javac','--release','21','-d',classes,*src.glob('*.java')])
    source=root/'original.jar';out=root/'remapped.jar'
    with zipfile.ZipFile(source,'w') as z:z.write(classes/'TargetProbe.class','TargetProbe.class')
    mapping=root/'map.tsv';mapping.write_text('C\tcKX\tcKU\nC\tnQp\tzLm\n')
    conf=root/'config.properties';conf.write_text('mod.test.target.cue.class=cKU\nmod.test.target.gesture.class=zLm\n')
    print(run(['java','-jar',REMAPPER,source,out,mapping,root/'source-ancestors.tsv',root/'report.txt','test']).split('runtime_reflection_bridge_calls=')[-1].splitlines()[0])
    env=dict(os.environ,BML_MOD_CONFIG=str(conf))
    print(run(['java','-Xverify:all','-cp',str(out)+os.pathsep+str(classes),'TargetProbe'],env))
