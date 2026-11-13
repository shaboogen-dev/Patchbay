#!/usr/bin/env python3
"""End-to-end synthetic 6.1.3 -> 6.1.4 helper bridge/bytecode translation.
This test intentionally uses stand-in classes, NOT proprietary Bitwig classes.
"""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,tempfile,zipfile
S=Path(__file__).resolve().parents[2]
def run(*args,env=None):
    p=subprocess.run([str(a) for a in args],capture_output=True,text=True,env=env)
    if p.returncode:raise AssertionError(f"Command {args} failed {p.returncode}:\n{p.stdout}\n{p.stderr}")
    return p.stdout

def src(root,name,data):
    path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(data);return path
with tempfile.TemporaryDirectory(prefix='bml-autoremap-smoke-') as td:
    tmp=Path(td);project=tmp/'BML';shutil.copytree(S/'loader',project/'loader',ignore=shutil.ignore_patterns('build','mod-runtime','tests'))
    srcold=tmp/'source';oldclasses=tmp/'oldclasses';srcnew=tmp/'target';newclasses=tmp/'newclasses';mods=project/'mods';mods.mkdir()
    path='com/bitwig/flt/document/core/iface/'
    src(srcold,'EIc.java','public class EIc { public String label() {return "OLD";} }')
    src(srcold,path+'SCA.java','package com.bitwig.flt.document.core.iface; public interface SCA { EIc NkU(); }'.replace('EIc NkU()', 'EIc NkU()'))
    # Java packaged code cannot access classes in the default package; use a
    # small packaged return class for the source compilation, then project the
    # same exact physical names through the synthetic compatibility table.
    (srcold/'EIc.java').unlink()
    src(srcold,path+'EIc.java','package com.bitwig.flt.document.core.iface; public class EIc { public String label(){return "OLD";} }')
    (srcold/path/'SCA.java').write_text('package com.bitwig.flt.document.core.iface; public interface SCA { EIc NkU(); }')
    src(srcnew,path+'FVE.java','package com.bitwig.flt.document.core.iface; public class FVE { public String label(){return "UPDATED";} }')
    src(srcnew,path+'kWY.java','package com.bitwig.flt.document.core.iface; public interface kWY { FVE nfq(); }')
    src(srcnew,'btu.java', 'import com.bitwig.flt.document.core.iface.*; public class btu { public static int runs; public void QOl(kWY provider) { FVE v=provider.nfq(); System.out.println(v.label()); runs++; } }')
    src(srcold,'Bridge.java','import com.bitwig.flt.document.core.iface.*; public class Bridge { public static int calls; public static EIc capture(SCA provider){calls++;return provider.NkU();} }')
    files=list(srcold.rglob('*.java'));run('javac','-d',oldclasses,*files)
    run('javac','-d',newclasses,*list(srcnew.rglob('*.java')))
    bin=tmp/'Bitwig/bin';bin.mkdir(parents=True)
    run('jar','cf',bin/'bitwig.jar','-C',newclasses,'.')
    java=shutil.which('java');assert java
    jre=tmp/'Bitwig/jre/bin';jre.mkdir(parents=True)
    link=jre/'java';link.symlink_to(java)
    mod=mods/'synthetic-bridge';mod.mkdir()
    run('jar','cf',mod/'helper.jar','-C',oldclasses,'Bridge.class')
    buildsfile=project/'loader/compatibility/builds.json'
    builds=json.loads(buildsfile.read_text())
    builds['builds'][next(i for i,b in enumerate(builds['builds']) if b['build_key']=='windows-6.1.4')]['bitwig_jar_sha256']=hashlib.sha256((bin/'bitwig.jar').read_bytes()).hexdigest()
    buildsfile.write_text(json.dumps(builds))
    # Overrides intentionally model the *same* names as the real inventory
    # mapping, except our return class is packaged to compile in ordinary Java.
    remapfile=project/'loader/compatibility/remap/windows-6.1.3__windows-6.1.4.tsv.gz'
    import gzip
    with gzip.open(remapfile,'at',encoding='utf-8') as gz:
        gz.write('C\tcom/bitwig/flt/document/core/iface/EIc\tcom/bitwig/flt/document/core/iface/FVE\n')
        gz.write('M\tcom/bitwig/flt/document/core/iface/SCA\tNkU\t()Lcom/bitwig/flt/document/core/iface/EIc;\tcom/bitwig/flt/document/core/iface/kWY\tnfq\t()Lcom/bitwig/flt/document/core/iface/FVE;\n')
    # Append a synthetic one-off equivalence group for the packaged return value.
    compatible=project/'loader/compatibility/compatibility-v2.json'
    groups=json.loads(compatible.read_text())
    for g in groups['groups']:
        a=g['variants'].get('windows-6.1.3')
        if a and a.get('owner')=='com/bitwig/flt/document/core/iface/SCA' and a.get('name')=='NkU':
            g['variants']['windows-6.1.3']['descriptor']='()Lcom/bitwig/flt/document/core/iface/EIc;'
            g['variants']['windows-6.1.4']['descriptor']='()Lcom/bitwig/flt/document/core/iface/FVE;'
    compatible.write_text(json.dumps(groups))
    manifest={'manifest_version':2,'mod':{'id':'synthetic-bridge','name':'Synthetic bridge','version':'1.0','author':'Test','description':'Synthetic bytecode remapping test'},
        'built_against':{'bitwig_version':'6.1.3','platform':'windows','architecture':'x64'},
        'helper':{'jar':'helper.jar','class':'Bridge'},
        'targets':{'method':{'kind':'method','owner':'btO','name':'ioq','descriptor':'()V'},
                   'call':{'kind':'method','owner':'com/bitwig/flt/document/core/iface/SCA','name':'NkU','descriptor':'()Lcom/bitwig/flt/document/core/iface/EIc;'}},
        'hooks':[{'id':'bridge','transform':'REPLACE_INVOKE','target':'method','match':{'opcode':'INVOKEINTERFACE','target':'call','occurrence':1},'bridge':{'method':'capture','descriptor':'(Lcom/bitwig/flt/document/core/iface/SCA;)Lcom/bitwig/flt/document/core/iface/EIc;'}}],
        'trace_targets':[]}
    (mod/'mod.json').write_text(json.dumps(manifest))
    # Mock btu in this test accepts a provider argument instead of the real
    # no-argument signature. Match the synthetic shape on *both* sides.
    for g in groups['groups']:
        a=g['variants'].get('windows-6.1.3')
        if a and a.get('owner')=='btO' and a.get('name')=='ioq':
            g['variants']['windows-6.1.3']['descriptor']='(Lcom/bitwig/flt/document/core/iface/SCA;)V'
            g['variants']['windows-6.1.4']['descriptor']='(Lcom/bitwig/flt/document/core/iface/kWY;)V'
    compatible.write_text(json.dumps(groups))
    manifest['targets']['method']['descriptor']='(Lcom/bitwig/flt/document/core/iface/SCA;)V'
    (mod/'mod.json').write_text(json.dumps(manifest))
    cmd=['java','-cp',project/'loader/app/bml-control.jar','dev.bitwigre.bml.JavaPreparer','--loader-root',project/'loader','--bitwig-bin',bin,'--mods-root',mods]
    result=run(*cmd,env={**os.environ,'BML_DEBUG_VERBOSE':'1'})
    rt=project/'loader/mod-runtime'
    assert 'PREPARE_OK' in result,result
    merged=rt/'helper.jar';assert merged.is_file()
    with zipfile.ZipFile(merged) as z:
        helper=z.read('Bridge.class');assert b'\xca\xfe\xba\xbe' in helper[:4]
    javap=run('javap','-s','-p','-classpath',merged,'Bridge')
    assert '(Lcom/bitwig/flt/document/core/iface/kWY;)Lcom/bitwig/flt/document/core/iface/FVE;' in javap,javap
    composition=(rt/'composition-report.tsv').read_text()
    assert 'Bridge.capture(Lcom/bitwig/flt/document/core/iface/kWY;)Lcom/bitwig/flt/document/core/iface/FVE;' in composition
    assert 'DEEP_VERIFY_OK btu' in (rt/'transform-report.txt').read_text()
    driver=src(tmp,'Driver.java','import com.bitwig.flt.document.core.iface.*; public class Driver { public static void main(String[] a){new btu().QOl(()->new FVE()); System.out.println("calls="+Bridge.calls);}}')
    run('javac','-cp',str(bin/'bitwig.jar')+os.pathsep+str(merged),'-d',tmp/'driver',driver)
    cp=os.pathsep.join(map(str,[rt/'deep-verify-replacements.jar',merged,bin/'bitwig.jar',tmp/'driver']))
    execution=run('java','-Xverify:all','-cp',cp,'Driver')
    assert execution.splitlines()==['UPDATED','calls=1'],execution
    print('PASS: real Java preparation auto-remaps helper class and call/member/type descriptors, patches invocation, deep-verifies, and executes runtime bridge')
