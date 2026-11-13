#!/usr/bin/env python3
"""Original-source helper portability test; builds native API stubs, not mod patches.
Checks reflection Class.forName, Class.getMethod, Class.getField and contextual
Class.getSimpleName equality against target-only runtime classes.
"""
from pathlib import Path
import tempfile, subprocess, gzip, zipfile
ROOT=Path(__file__).resolve().parents[2]
TOOL=ROOT/'loader/tools/bitwig-helper-remapper.jar'
def run(*args):
    p=subprocess.run([str(a) for a in args],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    if p.returncode: raise AssertionError(f'{args}: {p.stdout}')
    return p.stdout
with tempfile.TemporaryDirectory(prefix='bml-reflection-portability-') as tmp:
    t=Path(tmp)
    for d in ['old','future','fixture']:(t/d).mkdir()
    (t/'old/Parent.java').write_text('package old; public class Parent { public int oldMethod(){return 19;} public int oldField=47; }')
    (t/'future/Renamed.java').write_text('package future; public class Renamed { public int newMethod(){return 19;} public int newField=47; }')
    (t/'fixture/Probe.java').write_text('''package fixture;
    public class Probe {
      public static boolean run() throws Exception {
        Class<?> nativeClass=Class.forName("old.Parent");
        Object object=nativeClass.getConstructor().newInstance();
        int a=(Integer)nativeClass.getMethod("oldMethod").invoke(object);
        int b=(Integer)nativeClass.getField("oldField").get(object);
        return a==19&&b==47 && "Parent".equals(object.getClass().getSimpleName());
      }
    }''')
    (t/'Runner.java').write_text('public class Runner { public static void main(String[] a) throws Exception { if(!fixture.Probe.run())throw new AssertionError("reflection mismatch");System.out.println("REFLECTION_PORTABILITY_OK"); }}')
    run('javac','-d',t/'oldclasses',t/'old/Parent.java',t/'fixture/Probe.java')
    run('javac','-d',t/'newclasses',t/'future/Renamed.java')
    with zipfile.ZipFile(t/'helper.jar','w') as z:z.write(t/'oldclasses/fixture/Probe.class','fixture/Probe.class')
    with gzip.open(t/'remap.tsv.gz','wt') as z:z.write('C\told/Parent\tfuture/Renamed\nM\told/Parent\toldMethod\t()I\tfuture/Renamed\tnewMethod\t()I\nF\told/Parent\toldField\tI\tfuture/Renamed\tnewField\tI\n')
    output=run('java','-jar',TOOL,t/'helper.jar',t/'translated.jar',t/'remap.tsv.gz',t/'none.tsv',t/'report.txt')
    assert 'warnings=0' in output,output
    assert 'reflected_class_name_constants_translated=' in output,output
    run('javac','-cp',str(t/'translated.jar')+':'+str(t/'newclasses'),'-d',t/'runnerclasses',t/'Runner.java')
    result=run('java','-Xverify:all','-cp',str(t/'runnerclasses')+':'+str(t/'translated.jar')+':'+str(t/'newclasses'),'Runner')
    assert 'REFLECTION_PORTABILITY_OK' in result,result
    print('PASS: original fixture helper runs unchanged against target-only native API using remapped Class.forName/getMethod/getField/simpleName')
