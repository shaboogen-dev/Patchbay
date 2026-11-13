#!/usr/bin/env python3
"""Regression: a mod can override methods declared multiple Bitwig ancestors above it."""
from pathlib import Path
import subprocess,tempfile,gzip,zipfile,os
root=Path(__file__).resolve().parents[2]
remapper=root/'loader/tools/bitwig-helper-remapper.jar'

def run(*args,**kw):
    p=subprocess.run([str(a) for a in args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=50,**kw)
    assert p.returncode==0, p.stdout
    return p.stdout
with tempfile.TemporaryDirectory(prefix='bml-deep-override-') as tmp:
    t=Path(tmp)
    def write(path,text):
        p=t/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);return p
    old=[write('old/A.java','package old; public class A { public boolean oldActive(){return false;} public String oldLabel(){return "old";} }'),
         write('old/B.java','package old; public class B extends A {}'),
         write('old/C.java','package old; public class C extends B {}'),
         write('helper/Filter.java','package helper; public class Filter extends old.C { @Override public boolean oldActive(){return true;} @Override public String oldLabel(){return "Project";} }')]
    new=[write('nextapi/A.java','package nextapi; public class A { public boolean newActive(){return false;} public String newLabel(){return "native";} }'),
         write('nextapi/B.java','package nextapi; public class B extends A {}'),
         write('nextapi/C.java','package nextapi; public class C extends B {}')]
    run('javac','-d',t/'oldclasses',*old)
    run('javac','-d',t/'newclasses',*new)
    with zipfile.ZipFile(t/'mod.jar','w') as z:z.write(t/'oldclasses/helper/Filter.class','helper/Filter.class')
    with gzip.open(t/'map.tsv.gz','wt') as f:
        for row in ['C\told/A\tnextapi/A','C\told/B\tnextapi/B','C\told/C\tnextapi/C','H\told/C\told/B','H\told/B\told/A',
                    'M\told/A\toldActive\t()Z\tnextapi/A\tnewActive\t()Z',
                    'M\told/A\toldLabel\t()Ljava/lang/String;\tnextapi/A\tnewLabel\t()Ljava/lang/String;']:
            f.write(row+'\n')
    result=run('java','-jar',remapper,t/'mod.jar',t/'translated.jar',t/'map.tsv.gz',t/'absent.tsv',t/'report.txt')
    assert 'helper_override_declarations_translated=2' in result,result
    assert 'warnings=0' in result,result
    runner=write('Runner.java','public class Runner {public static void main(String[] args){helper.Filter filter = new helper.Filter(); if(!filter.newActive() || !filter.newLabel().equals("Project"))throw new AssertionError(); System.out.println("DEEP_OVERRIDE_OK");}}')
    cp=os.pathsep.join([str(t/'translated.jar'),str(t/'newclasses')])
    run('javac','-cp',cp,'-d',t/'runnerclasses',runner)
    assert 'DEEP_OVERRIDE_OK' in run('java','-Xverify:all','-cp',os.pathsep.join([str(t/'runnerclasses'),cp]),'Runner')
    print('PASS: deeply inherited activity/label overrides translated and dispatched on target-only JVM')
