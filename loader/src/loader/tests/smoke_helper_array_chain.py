#!/usr/bin/env python3
"""One-hop remap for array Class entries when source and target names overlap.
The fixture uses independent stand-in Java classes, not proprietary Bitwig code.
"""
from pathlib import Path
import subprocess, tempfile, zipfile, os, re, shutil
ROOT=Path(__file__).resolve().parents[2]

def run(*cmd):
    p=subprocess.run(list(map(str,cmd)),text=True,capture_output=True)
    if p.returncode: raise AssertionError(f"Command failed: {cmd}\n{p.stdout}\n{p.stderr}")
    return p.stdout

with tempfile.TemporaryDirectory(prefix='bml-array-chain-') as tmp:
    d=Path(tmp)
    old=d/'source'; old.mkdir()
    (old/'dvm.java').write_text('public class dvm {}')
    (old/'dve.java').write_text('public class dve {}')
    (old/'ArrayBridge.java').write_text('''
import java.util.Arrays;
public class ArrayBridge {
  public static dvm[] first(dvm[] a) {return Arrays.copyOf(a,a.length+1);}
  public static dve[] second(dve[] a) {return Arrays.copyOf(a,a.length+1);}
}
''')
    old_classes=d/'oldclasses';run('javac','-d',old_classes,*old.glob('*.java'))
    oldjar=d/'original.jar';run('jar','cf',oldjar,'-C',old_classes,'ArrayBridge.class')
    mapping=d/'map.tsv';mapping.write_text('C\tdvm\tdve\nC\tdve\tduW\n')
    remapped=d/'remapped.jar';report=d/'report.txt'
    remapper=ROOT/'loader/tools/bitwig-helper-remapper.jar'
    run('java','-jar',remapper,oldjar,remapped,mapping,d/'no-overrides.tsv',report)
    javap=run('javap','-c','-p','-s','-classpath',remapped,'ArrayBridge')
    assert 'descriptor: ([Ldve;)[Ldve;' in javap, javap
    assert 'descriptor: ([LduW;)[LduW;' in javap, javap
    assert re.search(r'checkcast\s+#\d+\s+// class "\[Ldve;"',javap),javap
    assert re.search(r'checkcast\s+#\d+\s+// class "\[LduW;"',javap),javap
    target=d/'target';target.mkdir()
    (target/'dve.java').write_text('public class dve {}')
    (target/'duW.java').write_text('public class duW {}')
    (target/'Driver.java').write_text('''
public class Driver {
  public static void main(String[] argv) {
    dve[] a=ArrayBridge.first(new dve[]{new dve()});
    duW[] b=ArrayBridge.second(new duW[]{new duW()});
    if(a.length!=2||b.length!=2)throw new AssertionError("wrong array lengths");
    System.out.println("ARRAY_REMAP_OK");
  }
}
''')
    target_classes=d/'targetclasses'
    run('javac','-cp',remapped,'-d',target_classes,*target.glob('*.java'))
    out=run('java','-Xverify:all','-cp',f'{remapped}{os.pathsep}{target_classes}','Driver')
    assert 'ARRAY_REMAP_OK' in out, out
    # Regression uses exact real Compact helper/mapping in addition to synthetic.
    if (ROOT/'mods/compact-expanded-groups/helper.jar').exists():
        compact=ROOT/'mods/compact-expanded-groups/helper.jar'
        table=ROOT/'loader/compatibility/remap/windows-6.1.3__windows-6.1.4.tsv.gz'
        fixed=d/'compact-remapped.jar'
        run('java','-jar',remapper,compact,fixed,table,d/'no-overrides.tsv',d/'compact-report.txt')
        compact_javap=run('javap','-c','-p','-s','-classpath',fixed,'BmlCompactExpandedGroups')
        section=compact_javap.split('appendGroupTrackSettings(')[1].split('  private static',1)[0]
        assert 'descriptor: ([Ldve;)Ldve;' in section, section
        assert 'class "[Ldve;"' in section, section
        assert 'class "[LduW;"' not in section, section
        assert 'nativePageContent:([Ldve;)Ldve;' in section, section
    else:
        print('NOTE: optional real Compact helper fixture absent from mod-free distribution; synthetic fixture still tested')
print('PASS: overlapping mapping IDs translated once; synthetic helper JVM verified and executed; real Compact fixture is optional')
