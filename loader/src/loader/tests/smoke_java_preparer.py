#!/usr/bin/env python3
"""Temporary synthetic JavaPreparer tests; never modifies real Bitwig or installed mods.
Requires JDK 21+ and Python 3. No third-party dependencies.
"""
import hashlib,json,os,shutil,subprocess,tempfile,zipfile
from pathlib import Path

SRC=Path(__file__).resolve().parents[2]
def command(args,env=None,ok=True):
    p=subprocess.run([str(a) for a in args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,env={**os.environ,**(env or {})})
    if ok and p.returncode:raise AssertionError(f"Command failed ({p.returncode}): {args}\n{p.stdout}")
    return p

def dump(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,indent=2),encoding='utf-8')

def main():
 with tempfile.TemporaryDirectory(prefix='bml-java-core-test-') as d:
    test=Path(d); root=test/'bml';loader=root/'loader';mods=root/'mods';
    shutil.copytree(SRC/'loader',loader,ignore=shutil.ignore_patterns('mod-runtime','build','tests'))
    mods.mkdir()
    bitwig=test/'bitwig';bin=bitwig/'bin';bin.mkdir(parents=True)
    (bitwig/'jre'/'bin').mkdir(parents=True)
    java=shutil.which('java');assert java
    # A lightweight stand-in for the bundled Bitwig JRE: its existence suffices
    # to test patcher/deep-verifier invocation on the development host.
    javaDest=bitwig/'jre'/'bin'/('java.exe' if os.name=='nt' else 'java')
    if os.name=='nt':shutil.copy2(java,javaDest)
    else:javaDest.symlink_to(java)
    src=test/'src'; classes=test/'vendor-classes';(src/'com'/'example').mkdir(parents=True)
    (src/'com'/'example'/'Foo.java').write_text('package com.example; public class Foo { public void tick() { System.out.println("tick"); } }')
    command(['javac','-d',classes,src/'com'/'example'/'Foo.java'])
    command(['jar','cf',bin/'bitwig.jar','-C',classes,'.'])
    sha=hashlib.sha256((bin/'bitwig.jar').read_bytes()).hexdigest();buildsFile=loader/'compatibility'/'builds.json'
    builds=json.loads(buildsFile.read_text());builds['builds'][next(i for i,b in enumerate(builds['builds']) if b['build_key']=='windows-6.1.3')]['bitwig_jar_sha256']=sha;dump(buildsFile,builds)
    metadata=lambda ident:{'id':ident,'name':ident,'version':'1.0','author':'Test','description':'Synthetic test only'}
    base=lambda ident:{'manifest_version':2,'mod':metadata(ident),'built_against':{'bitwig_version':'6.1.3','platform':'windows','architecture':'x64'},
        'targets':{'tick':{'kind':'method','owner':'com/example/Foo','name':'tick','descriptor':'()V'}},'hooks':[],'trace_targets':[{'id':'tick','target':'tick'}]}
    mod=mods/'first';mod.mkdir();(mod/'config').mkdir();(mod/'config'/'prefs.txt').write_text('hello')
    m=base('first');m['resources']={'prefs':{'kind':'file','path':'config/prefs.txt','persistence':'preserve'}};dump(mod/'mod.json',m)
    cmd=['java','-cp',loader/'app'/'bml-control.jar','dev.bitwigre.bml.JavaPreparer','--loader-root',loader,'--bitwig-bin',bin,'--mods-root',mods]
    p=command(cmd)
    rt=loader/'mod-runtime';assert 'PREPARE_OK' in p.stdout and 'transforms=0' in p.stdout
    assert 'persistence=preserve' in (rt/'mod-bindings.properties').read_text()
    assert 'EXACT' in (rt/'target-resolution.tsv').read_text()
    print('PASS 1: trace-only mod, EXACT resolution, preserved resource and inert class replacement')
    # Selected-none should suppress a manifest that is valid but not requested.
    file=test/'enabled.txt';file.write_text('');p=command(cmd,{'BML_MOD_SELECTION_ACTIVE':'1','BML_ENABLED_MODS_FILE':str(file)})
    assert 'mods=0 transforms=0' in p.stdout
    print('PASS 2: explicit empty selection produces no loaded mods')
    file.write_text('not-installed\n');p=command(cmd,{'BML_MOD_SELECTION_ACTIVE':'1','BML_ENABLED_MODS_FILE':str(file)},False)
    assert p.returncode and 'Selected mod not installed' in p.stdout
    print('PASS 3: missing requested mod fails closed')
    # Broken unselected installations must remain diagnosable but must not
    # prevent requested valid mods from preparing.
    broken=mods/'broken';broken.mkdir();dump(broken/'mod.json',{'manifest_version':2,'mod':{'id':'broken','name':False}})
    file.write_text('first\n')
    p=command(cmd,{'BML_MOD_SELECTION_ACTIVE':'1','BML_ENABLED_MODS_FILE':str(file)})
    assert 'mods=1 transforms=0' in p.stdout,p.stdout
    file.write_text('broken\n')
    p=command(cmd,{'BML_MOD_SELECTION_ACTIVE':'1','BML_ENABLED_MODS_FILE':str(file)},False)
    assert p.returncode and 'Selected mod' in p.stdout and 'broken' in p.stdout,p.stdout
    shutil.rmtree(broken)
    print('PASS 3a: malformed unselected package isolated; selected malformed package blocked')
    m['mod']['documentation']='readme'
    m['resources']['readme']={'kind':'file','path':'missing-readme.md','media_type':'text/markdown'}
    dump(mod/'mod.json',m)
    file.write_text('first\n')
    p=command(cmd,{'BML_MOD_SELECTION_ACTIVE':'1','BML_ENABLED_MODS_FILE':str(file)})
    assert 'PREPARE_OK' in p.stdout and 'Optional Markdown documentation missing' in p.stdout,p.stdout
    m['mod'].pop('documentation');m['resources'].pop('readme');dump(mod/'mod.json',m)
    print('PASS 3b: absent optional Markdown is diagnostic-only; executable resources still validated')
    m['resources']['prefs']['path']='../outside.txt';dump(mod/'mod.json',m)
    p=command(cmd,ok=False);assert p.returncode and 'escapes package root' in p.stdout
    print('PASS 4: resource traversal rejected')
    m['resources']['prefs']['path']='config/prefs.txt';dump(mod/'mod.json',m)
    # Cross-build MAPPED resolution is exercised as metadata only: the test
    # stock archive deliberately contains no real Bitwig implementation classes.
    cross=base('first')
    cross['built_against']['bitwig_version']='6.1.2'
    cross['targets']={'mapped':{'kind':'method','owner':'com/bitwig/flt/document/core/master/device/Uhc','name':'vpt','descriptor':'(Ljava/lang/String;II)V'}}
    cross['trace_targets']=[{'id':'mapped','target':'mapped'}]
    dump(mod/'mod.json',cross)
    command(cmd)
    r=(rt/'target-resolution.tsv').read_text()
    assert 'MAPPED' in r and 'com/bitwig/flt/document/core/master/device/xJu.vhT' in r, r
    print('PASS 5: compatibility export resolves an actual 6.1.2 method to 6.1.3 as MAPPED (metadata only)')
    dump(mod/'mod.json',m)
    # Build two independent helper JARs. Both target the same method.
    helperSrc=test/'helper-sources';helperClasses=test/'helper-classes'
    for modid,word in [('first','first'),('second','second')]:
        folder=mods/modid;folder.mkdir(exist_ok=True)
        (helperSrc/'demo').mkdir(parents=True,exist_ok=True)
        source=helperSrc/'demo'/(modid.title()+'Bridge.java')
        source.write_text('package demo; public class '+modid.title()+'Bridge { public static void entry() { System.out.println("'+word+'"); }}')
        command(['javac','-d',helperClasses,source]);command(['jar','cf',folder/'helper.jar','-C',helperClasses,'demo/'+modid.title()+'Bridge.class'])
        manifest=m if modid=='first' else base(modid)
        manifest['helper']={'jar':'helper.jar','class':'demo.'+modid.title()+'Bridge'}
        manifest['hooks']=[{'id':'entry','transform':'INSERT_AT_ENTRY','target':'tick','bridge':{'method':'entry','descriptor':'()V','args':[]}}]
        dump(folder/'mod.json',manifest)
    p=command(cmd);assert 'mods=2 transforms=2 replacement_classes=1' in p.stdout
    assert 'DEEP_VERIFY_OK' in (rt/'transform-report.txt').read_text()
    assert len((rt/'composition-report.tsv').read_text().splitlines())==3
    print('PASS 6: two-mod shared INSERT_AT_ENTRY hooks compose and deep-verify')
    # Verify actual execution order of injected methods, not just metadata.
    driverSrc=test/'Main.java';driverSrc.write_text('public class Main { public static void main(String[] a) { new com.example.Foo().tick(); }}')
    command(['javac','-cp',bin/'bitwig.jar','-d',test/'driver',driverSrc])
    cp=os.pathsep.join(map(str,[rt/'deep-verify-replacements.jar',rt/'helper.jar',bin/'bitwig.jar',test/'driver']))
    p=command(['java','-cp',cp,'Main'])
    assert p.stdout.splitlines()==['first','second','tick'],p.stdout
    print('PASS 7: actual injected execution order first -> second -> stock method')
    first=json.loads((mods/'first'/'mod.json').read_text());first['hooks'][0]['ordering']={'after':[{'mod':'second','hook':'entry'}]};dump(mods/'first'/'mod.json',first)
    command(cmd)
    cp=os.pathsep.join(map(str,[rt/'deep-verify-replacements.jar',rt/'helper.jar',bin/'bitwig.jar',test/'driver']))
    p=command(['java','-cp',cp,'Main']);assert p.stdout.splitlines()==['second','first','tick'],p.stdout
    print('PASS 8: explicit after ordering reverses execution predictably')
    second=json.loads((mods/'second'/'mod.json').read_text());second['hooks'][0]['ordering']={'after':[{'mod':'first','hook':'entry'}]};dump(mods/'second'/'mod.json',second)
    p=command(cmd,ok=False);assert p.returncode and 'ordering cycle' in p.stdout
    print('PASS 9: active ordering cycle fails closed')
    second['hooks'][0].pop('ordering');dump(mods/'second'/'mod.json',second)
    # Link escape is rejected even when its lexical path is below the mod.
    if os.name!='nt':
        (mod/'config'/'link.txt').symlink_to(test/'outside.txt')
        first['resources']['prefs']['path']='config/link.txt';dump(mod/'mod.json',first)
        p=command(cmd,ok=False);assert p.returncode and 'symlink' in p.stdout,p.stdout
        print('PASS 10: symlink resource rejected')
    print('ALL SYNTHETIC TESTS PASSED. No live Bitwig runtime is available in this test.')

if __name__=='__main__':main()
