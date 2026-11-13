#!/usr/bin/env python3
"""General bridge regression: inherited reflective methods supplied by field-anchored remaps.

Builds synthetic 6.1.3 and 6.1.4 API stubs, *not* mod patches and *not* Bitwig binaries.
The real BML 6.1.3→6.1.4 remap is used to prepare the original synthetic helper.
"""
from pathlib import Path
import tempfile,subprocess,zipfile
ROOT=Path(__file__).resolve().parents[2]
REMAP=ROOT/'loader/compatibility/remap/windows-6.1.3__windows-6.1.4.tsv.gz'
TOOL=ROOT/'loader/tools/bitwig-helper-remapper.jar'
def call(*cmd):
 p=subprocess.run([str(x) for x in cmd],capture_output=True,text=True)
 if p.returncode:raise AssertionError(f'{cmd}\n{p.stdout}\n{p.stderr}')
 return p.stdout+p.stderr
with tempfile.TemporaryDirectory(prefix='bml-field-anchor-') as x:
 p=Path(x)
 for sub in ('oldsrc','newsrc','probe','oldclasses','newclasses','out'):(p/sub).mkdir()
 (p/'oldsrc/dsD.java').write_text('public class dsD {public java.util.List<String> xwW(){return java.util.List.of("OLD");}}')
 (p/'oldsrc/dsz.java').write_text('public class dsz extends dsD {}')
 (p/'oldsrc/XRh.java').write_text('public class XRh {public double IPy(){return 10.0;}}')
 (p/'oldsrc/LYu.java').write_text('public class LYu extends XRh {}')
 (p/'newsrc/dst.java').write_text('public class dst {public java.util.List<String> E3(){return java.util.List.of("NEW");}}')
 (p/'newsrc/dsp.java').write_text('public class dsp extends dst {}')
 (p/'newsrc/mgr.java').write_text('public class mgr {public double ODA(){return 42.0;}}')
 (p/'newsrc/n1.java').write_text('public class n1 extends mgr {}')
 (p/'probe/Probe.java').write_text('''public class Probe {
  public static String check() throws Exception {
    Class<?> a=Class.forName("dsz"),b=Class.forName("LYu");
    Object x=a.getConstructor().newInstance(),y=b.getConstructor().newInstance();
    String text=(String)((java.util.List<?>)a.getMethod("xwW").invoke(x)).get(0);
    double result=(Double)b.getMethod("IPy").invoke(y);
    return text+":"+result;
  }
}''')
 (p/'Runner.java').write_text('public class Runner {public static void main(String[] args)throws Exception {String s=Probe.check();if(!s.equals("NEW:42.0"))throw new AssertionError(s);System.out.println("FIELD_ANCHOR_BRIDGE_OK");}}')
 call('javac','-d',p/'oldclasses',*sorted((p/'oldsrc').glob('*.java')),(p/'probe/Probe.java'))
 call('javac','-d',p/'newclasses',*sorted((p/'newsrc').glob('*.java')))
 with zipfile.ZipFile(p/'helper.jar','w') as z:z.write(p/'oldclasses/Probe.class','Probe.class')
 (p/'none.tsv').write_text('')
 result=call('java','-jar',TOOL,p/'helper.jar',p/'out/helper.jar',REMAP,p/'none.tsv',p/'out/remap.log')
 assert 'warnings=0' in result,result
 assert 'runtime_reflection_bridge_calls=' in result,result
 call('javac','-cp',str(p/'out/helper.jar')+':'+str(p/'newclasses'),'-d',p/'out',p/'Runner.java')
 r=call('java','-Xverify:all','-cp',str(p/'out')+':'+str(p/'out/helper.jar')+':'+str(p/'newclasses'),'Runner')
 assert 'FIELD_ANCHOR_BRIDGE_OK' in r,r
 print('PASS: untouched original helper + BML remapper + mapped native stubs return NEW:42.0')
