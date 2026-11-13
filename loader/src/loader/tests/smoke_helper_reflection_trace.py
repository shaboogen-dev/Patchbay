from pathlib import Path
import os
import tempfile, subprocess, zipfile
ROOT=Path(__file__).resolve().parents[2]
def run(*cmd,expect=0):
 r=subprocess.run(list(map(str,cmd)),capture_output=True,text=True,env=dict(os.environ,BML_TRACE_ENABLED="1",BML_SESSION_ROOT=os.environ.get("BML_REFLECTION_TEST_ROOT",os.getcwd())))
 if r.returncode!=expect:raise RuntimeError(str(cmd)+'\n'+r.stdout+'\n'+r.stderr)
 return r.stdout+r.stderr
with tempfile.TemporaryDirectory() as td:
 p=Path(td);(p/'src/one').mkdir(parents=True);(p/'src/two').mkdir(parents=True)
 (p/'src/one/NewOne.java').write_text('package one; public class NewOne { public String newName(int x){return "A";} public int newField=7; }')
 (p/'src/two/NewTwo.java').write_text('package two; public class NewTwo { public String secondName(int x){return "B";} public int secondField=8; }')
 (p/'src/Probe.java').write_text('''public class Probe {
 public static String check() throws Exception {
  Class<?> a=Class.forName("old.One"), b=Class.forName("old.Two");
  Object obj=a.getConstructor().newInstance();
  Object obj2=b.getConstructor().newInstance();
  String x=(String)a.getMethod("method",int.class).invoke(obj,1);
  String y=(String)b.getMethod("method",int.class).invoke(obj2,2);
  int f=a.getField("field").getInt(obj),g=b.getField("field").getInt(obj2);
  if(!a.getName().equals("old.One") || !b.getSimpleName().equals("Two"))throw new AssertionError(a.getName()+b.getSimpleName());
  if(!"method".equals("method"))throw new AssertionError("nonreflective data changed");
  try {a.getMethod("intentionalMissingMethod",int.class);throw new AssertionError("missing lookup unexpectedly succeeded");}
  catch(NoSuchMethodException expected) { /* runtime diagnostic should capture this */ }
  return x+y+f+g;
 }
}
''')
 (p/'src/Runner.java').write_text('public class Runner {public static void main(String[]args)throws Exception{System.out.println(Probe.check());}}')
 (p/'classes').mkdir();run('javac','-d',p/'classes',*list((p/'src').rglob('*.java')))
 with zipfile.ZipFile(p/'original.jar','w') as z:z.write(p/'classes/Probe.class','Probe.class')
 (p/'map.tsv').write_text('''C\told/One\tone/NewOne
C\told/Two\ttwo/NewTwo
M\told/One\tmethod\t(I)Ljava/lang/String;\tone/NewOne\tnewName\t(I)Ljava/lang/String;
M\told/Two\tmethod\t(I)Ljava/lang/String;\ttwo/NewTwo\tsecondName\t(I)Ljava/lang/String;
F\told/One\tfield\tI\tone/NewOne\tnewField\tI
F\told/Two\tfield\tI\ttwo/NewTwo\tsecondField\tI
''')
 output=run('java','-jar',ROOT/'loader/tools/bitwig-helper-remapper.jar',p/'original.jar',p/'prepared.jar',p/'map.tsv',p/'missing.tsv',p/'report.txt')
 assert 'runtime_reflection_bridge_calls=' in output
 result=run('java','-Xverify:all','-cp',str(p/'prepared.jar')+':'+str(p/'classes'),'Runner')
 assert 'AB78' in result,result
 log=Path(os.environ.get('BML_REFLECTION_TEST_ROOT',os.getcwd()))/'reflection-bridge.tsv'
 assert log.exists(), 'Debug trace did not reach session folder'
 lines=log.read_text().splitlines()
 assert any('getMethod' in x and 'method' in x and 'newName' in x for x in lines), lines
 assert any('getField' in x and 'field' in x and 'newField' in x for x in lines), lines
 assert any('intentionalMissingMethod' in x and 'NoSuchMethodException' in x for x in lines), lines
 before=log.read_bytes()
 normal=subprocess.run(['java','-Xverify:all','-cp',str(p/'prepared.jar')+':'+str(p/'classes'),'Runner'],
     capture_output=True,text=True,env=dict(os.environ,BML_TRACE_ENABLED='0',BML_SESSION_ROOT=str(log.parent)))
 assert normal.returncode==0 and 'AB78' in normal.stdout,normal.stdout+normal.stderr
 assert before==log.read_bytes(), 'Debug-disabled run wrote reflection trace'
 print('PASS: owner-aware bridge success/failure diagnostics, runtime verification and debug-off suppression ('+str(len(lines))+' distinct events)')
