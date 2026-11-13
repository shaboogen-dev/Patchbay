#!/usr/bin/env python3
"""Verify a helper reference whose owner is a *Bitwig* class that inherits the member (no alias row in
the map): IntermediateOld.oldName must become IntermediateNew.newName via the H rows."""
from pathlib import Path
import os,subprocess,tempfile,zipfile
# BML_LOADER_ROOT: a built loader's loader/ folder (the tool jars are build outputs, not in src/)
ROOT=Path(os.environ.get('BML_LOADER_ROOT',Path(__file__).resolve().parents[1]))
def run(*args):
 p=subprocess.run([str(a) for a in args],capture_output=True,text=True)
 if p.returncode:raise RuntimeError('FAILED: '+' '.join(map(str,args))+'\n'+p.stdout+'\n'+p.stderr)
 return p.stdout
with tempfile.TemporaryDirectory(prefix='bml-bitwig-owned-') as tmp:
 d=Path(tmp);(d/'src').mkdir();(d/'old').mkdir();(d/'new').mkdir()
 def write(n,code): (d/'src'/n).write_text(code)
 write('GrandOld.java','public class GrandOld { public int oldName(){ return 42; } }')
 write('IntermediateOld.java','public class IntermediateOld extends GrandOld {}')
 write('TestHelper.java','public class TestHelper { public static int test(){ return new IntermediateOld().oldName(); } }')
 run('javac','--release','21','-d',d/'old',*[d/'src'/n for n in ['GrandOld.java','IntermediateOld.java','TestHelper.java']])
 with zipfile.ZipFile(d/'helper.jar','w') as z:z.write(d/'old/TestHelper.class','TestHelper.class')
 # a declared row for GrandOld only; no alias row for IntermediateOld.oldName
 (d/'map.tsv').write_text('C\tGrandOld\tGrandNew\nC\tIntermediateOld\tIntermediateNew\nM\tGrandOld\toldName\t()I\tGrandNew\tnewName\t()I\n'
                          'H\tIntermediateOld\tGrandOld\nH\tGrandOld\tjava/lang/Object\n')
 (d/'none.tsv').write_text('')
 out=run('java','-jar',ROOT/'tools/bitwig-helper-remapper.jar',d/'helper.jar',d/'remapped.jar',d/'map.tsv',d/'none.tsv',d/'report.txt')
 assert 'helper_inherited_calls_translated=1' in out,out
 write('GrandNew.java','public class GrandNew { public int newName(){ return 42; } }')
 write('IntermediateNew.java','public class IntermediateNew extends GrandNew {}')
 write('TestDriver.java','public class TestDriver { public static void main(String[] a){ if(TestHelper.test()!=42)throw new AssertionError();System.out.println("PASS"); } }')
 run('javac','--release','21','-d',d/'new',d/'src/GrandNew.java',d/'src/IntermediateNew.java')
 cp=str(d/'new')+os.pathsep+str(d/'remapped.jar')
 run('javac','--release','21','-cp',cp,'-d',d/'new',d/'src/TestDriver.java')
 assert 'PASS' in run('java','-Xverify:all','-cp',cp,'TestDriver')
print('PASS: Bitwig-owned inherited call IntermediateOld.oldName -> IntermediateNew.newName via H rows, JVM verified')
