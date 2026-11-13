"""Regression: helper Method.getName() is a source-version name on mapped native methods."""
from pathlib import Path
import subprocess,tempfile,zipfile
_file=Path(__file__).resolve()
ROOT=(_file.parents[1] if _file.parent.name=='tests' else
      _file.parent/'BML-Preview29-Foundation-R9.7-Mod-Free-View-Projection-Diagnostics/loader')

def run(*cmd):
    p=subprocess.run([str(x) for x in cmd],capture_output=True,text=True,timeout=60)
    if p.returncode:raise AssertionError(f'{cmd}\n{p.stdout}\n{p.stderr}')
    return p.stdout+p.stderr

with tempfile.TemporaryDirectory(prefix='bml-method-name-alias-') as temp:
    r=Path(temp)
    src=r/'src';old=r/'old';new=r/'new';helpers=r/'helpers'
    for d in (src,old,new,helpers):d.mkdir()
    examples={
        src/'OldBase.java':'package bw; public class OldBase { public String vhT(String s) {return "OLD";} }',
        src/'OldView.java':'package bw; public class OldView extends OldBase {}',
        src/'NewBase.java':'package bw; public class NewBase { public String cfM(String s) {return "NEW:"+s;} }',
        src/'NewView.java':'package bw; public class NewView extends NewBase {}',
        src/'NativeReflectionHelper.java':'''import bw.*; import java.lang.reflect.*;
          public class NativeReflectionHelper {
            public static String nameTest() throws Exception {
              Class<?> view=OldView.class;
              for(Class<?> type=view;type!=null;type=type.getSuperclass()) {
                for(Method method:type.getDeclaredMethods()) {
                  if(method.getName().equals("vhT") && method.getParameterCount()==1) {
                    Object value=view.getConstructor().newInstance();
                    if(!"NEW:yes".equals(method.invoke(value,"yes")))throw new AssertionError("wrong impl");
                    Method declared=type.getDeclaredMethod("vhT",String.class);
                    if(!declared.equals(method))throw new AssertionError("declared mismatch");
                    return method.getName();
                  }
                }
              }
              throw new AssertionError("inherited source name unavailable");
            }
          }''',
        src/'Runner.java':'public class Runner { public static void main(String[] args) throws Exception { if(!NativeReflectionHelper.nameTest().equals("vhT"))throw new AssertionError(); System.out.println("METHOD_NAME_ALIAS_SMOKE_PASS"); }}'
    }
    for p,s in examples.items():p.write_text(s,encoding='utf8')
    run('javac','-d',old,src/'OldBase.java',src/'OldView.java')
    run('javac','-d',new,src/'NewBase.java',src/'NewView.java')
    run('javac','-cp',old,'-d',helpers,src/'NativeReflectionHelper.java',src/'Runner.java')
    helper=r/'helper.jar';run('jar','cf',helper,'-C',helpers,'.')
    mapping=r/'mapping.tsv'
    mapping.write_text('C\tbw/OldBase\tbw/NewBase\nC\tbw/OldView\tbw/NewView\n'
                       'M\tbw/OldBase\tvhT\t(Ljava/lang/String;)Ljava/lang/String;\tbw/NewBase\tcfM\t(Ljava/lang/String;)Ljava/lang/String;\n')
    prepared=r/'prepared.jar';report=r/'report.txt'
    line=run('java','-jar',ROOT/'tools/bitwig-helper-remapper.jar',helper,prepared,mapping,r/'none.tsv',report)
    assert 'runtime_reflection_bridge_calls=' in line,line
    text=run('java','-Xverify:all','-cp',str(prepared)+';'+str(new),'Runner')
    assert 'METHOD_NAME_ALIAS_SMOKE_PASS' in text,text
    inspect=run('javap','-c','-p','-classpath',prepared,'NativeReflectionHelper')
    assert 'getMethodName:' in inspect,inspect
    print('PASS: inherited reflective method enumeration, old-name Method.getName, Class.getDeclaredMethod, physical invocation')
