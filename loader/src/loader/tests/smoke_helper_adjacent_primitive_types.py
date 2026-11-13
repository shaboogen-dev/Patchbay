#!/usr/bin/env python3
"""Regression: preserve remapped object types following primitive descriptor tokens.
Independent synthetic Java classes, plus original Compact helper bytecode.
"""
from pathlib import Path
import os,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
REMAP=ROOT/'loader/tools/bitwig-helper-remapper.jar'

def run(*cmd):
    p=subprocess.run(list(map(str,cmd)),text=True,capture_output=True)
    if p.returncode:raise AssertionError(f'{cmd} returned {p.returncode}:\n{p.stdout}\n{p.stderr}')
    return p.stdout
with tempfile.TemporaryDirectory(prefix='bml-adjacent-primitive-') as tmp:
    d=Path(tmp)
    old=d/'old';old.mkdir();oldcls=d/'oldclasses'
    (old/'mTn.java').write_text('public class mTn { public final int x=7; }')
    (old/'Bridge.java').write_text('''
import java.util.*;
public class Bridge {
  public static mTn render(double a, double b, mTn value, boolean tail) { return value; }
  public static mTn many(int a, long b, float c, mTn value) { return value; }
  public static mTn[] arrays(double a, mTn[] values) {return values;}
  public static List<mTn> generic(List<mTn> values) {return values;}
}
''')
    run('javac','-d',oldcls,*old.glob('*.java'))
    original=d/'old.jar';run('jar','cf',original,'-C',oldcls,'Bridge.class')
    remap=d/'map.tsv';remap.write_text('C\tmTn\tDlD\n')
    output=d/'fixed.jar';run('java','-jar',REMAP,original,output,remap,d/'none.tsv',d/'report.txt')
    javap=run('javap','-s','-p','-classpath',output,'Bridge')
    for signature in ['(DDLDlD;Z)LDlD;', '(IJFLDlD;)LDlD;', '(D[LDlD;)[LDlD;']:
        assert signature in javap, ('Missing remapped '+signature+'\n'+javap)
    assert 'LmTn;' not in javap, javap
    target=d/'target';target.mkdir()
    (target/'DlD.java').write_text('public class DlD { public final int x=7; }')
    (target/'Driver.java').write_text('''
import java.util.*;
public class Driver {
 public static void main(String[] args) {
  DlD v=new DlD();
  if(Bridge.render(1,2,v,true)!=v || Bridge.many(1,2L,3f,v)!=v)throw new AssertionError();
  if(Bridge.arrays(2,new DlD[]{v})[0]!=v)throw new AssertionError();
  if(Bridge.generic(List.of(v)).get(0)!=v)throw new AssertionError();
  System.out.println("PRIMITIVE_DESCRIPTOR_OK");
 }
}
''')
    tgt=d/'targetclasses'
    run('javac','-cp',output,'-d',tgt,*target.glob('*.java'))
    execution=run('java','-Xverify:all','-cp',str(output)+os.pathsep+str(tgt),'Driver')
    assert 'PRIMITIVE_DESCRIPTOR_OK' in execution, execution
    if (ROOT/'mods/compact-expanded-groups/helper.jar').exists():
        compact=ROOT/'mods/compact-expanded-groups/helper.jar'
        physical=ROOT/'loader/compatibility/remap/windows-6.1.3__windows-6.1.4.tsv.gz'
        real=d/'compact-remapped.jar';run('java','-jar',REMAP,compact,real,physical,d/'none.tsv',d/'real-report.txt')
        j=run('javap','-c','-p','-s','-classpath',real,'BmlCompactExpandedGroups')
        assert 'DDLcom/bitwig/base/geom/DlD;Z)V' in j
        assert 'DDLcom/bitwig/base/geom/mTn;Z)V' not in j
        assert 'descriptor: ([Ldve;)Ldve;' in j
        report=(d/'real-report.txt').read_text();assert 'warnings=0' in report
    else:
        print('NOTE: optional real Compact helper fixture absent from mod-free distribution; synthetic fixture still tested')
print('PASS: all adjacent-primitive object types remapped, synthetic target-only JVM verifies and executes, Compact helper fixture optional')
