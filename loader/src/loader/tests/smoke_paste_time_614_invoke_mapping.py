#!/usr/bin/env python3
"""Synthetic 613->614 Cut Time guard callsite translation; no proprietary Bitwig binaries."""
from pathlib import Path
import subprocess, tempfile, shutil, hashlib, json, os, zipfile
ROOT=Path(__file__).resolve().parents[2]
MOD=json.loads((ROOT/'mods/paste-time/mod.json').read_text())
COMP=json.loads((ROOT/'loader/compatibility/compatibility-v2.json').read_text())
CALL='timeline.view.binding.inherited_set_component_callsite'

def run(*args,good=True,env=None):
    p=subprocess.run(list(map(str,args)),text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env=env)
    if good and p.returncode:raise AssertionError(f'FAIL cmd={args}:\n{p.stdout}')
    return p

def put(root,name,data):
    path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(data)
    return path

def main():
    g=next(g for g in COMP['groups'] if g['variants'].get('windows-6.1.3')==MOD['targets'][CALL])
    assert g['variants']['windows-6.1.4']=={'kind':'method','owner':'bos','name':'Hnt','descriptor':'(LjFk;)V'}
    h=next(h for h in MOD['hooks'] if h['id']=='guard-null-timeline-model-after-cut-time')
    assert h['match']=={'opcode':'INVOKEVIRTUAL','target':CALL,'occurrence':1}
    with tempfile.TemporaryDirectory(prefix='bml-c18-cuttime-callsite-') as work:
        t=Path(work);loader=t/'bml/loader';mods=t/'bml/mods';mods.mkdir(parents=True)
        shutil.copytree(ROOT/'loader',loader,ignore=shutil.ignore_patterns('mod-runtime','build','tests','build-*'))
        bin=t/'Vendor/bin';bin.mkdir(parents=True)
        javaDest=t/'Vendor/jre/bin/java';javaDest.parent.mkdir(parents=True);javaDest.symlink_to(shutil.which('java'))
        put(t,'oldsrc/boJ.java','public class boJ { public void HEY(QLv x) {} }')
        put(t,'oldsrc/QLv.java','public class QLv {}')
        put(t,'oldsrc/PasteTimeFeature613.java','public class PasteTimeFeature613 { public static void guardTimelineModelBinding(boJ x, QLv y) { if(y!=null)x.HEY(y); } }')
        run('javac','-d',t/'old-classes',*(t/'oldsrc').glob('*.java'))
        m=mods/'paste-time';m.mkdir();run('jar','cf',m/'helper.jar','-C',t/'old-classes','PasteTimeFeature613.class')
        put(t,'newsrc/jFk.java','public class jFk {}')
        put(t,'newsrc/kPd.java','public class kPd { public final jFk value = new jFk(); }')
        put(t,'newsrc/bos.java','public class bos { public static int calls=0; public void Hnt(jFk x) { calls++; } public void MRY(kPd item) { this.Hnt(item.value); } }')
        run('javac','-d',t/'new-classes',*(t/'newsrc').glob('*.java'))
        run('jar','cf',bin/'bitwig.jar','-C',t/'new-classes','.')
        builds=loader/'compatibility/builds.json';b=json.loads(builds.read_text())
        next(x for x in b['builds'] if x['build_key']=='windows-6.1.4')['bitwig_jar_sha256']=hashlib.sha256((bin/'bitwig.jar').read_bytes()).hexdigest()
        builds.write_text(json.dumps(b))
        # Isolate the actual Cut-Time guard. Other, unrelated Cut/Paste hooks use
        # proprietary classes outside this intentionally limited synthetic fixture.
        manifest={k:MOD[k] for k in ('manifest_version','mod','built_against','helper')}
        manifest['targets']={k:MOD['targets'][k] for k in ('timeline.view.binding.apply_item_model',CALL,'arranger.cut_time.callback')}
        manifest['hooks']=[h];manifest['trace_targets']=[{'id':'native-cut-time-callback','target':'arranger.cut_time.callback','optional':True}]
        (m/'mod.json').write_text(json.dumps(manifest))
        command=('java','-cp',loader/'app/bml-control.jar','dev.bitwigre.bml.JavaPreparer','--loader-root',loader,'--bitwig-bin',bin,'--mods-root',mods)
        prepared=run(*command)
        assert 'PREPARE_OK' in prepared.stdout,prepared.stdout
        assert 'OPTIONAL_TRACE_UNMAPPED: paste-time/native-cut-time-callback' in prepared.stdout,prepared.stdout
        runtime=loader/'mod-runtime';targets=(runtime/'target-resolution.tsv').read_text()
        assert 'arranger.cut_time.callback\twindows-6.1.3\tUNMAPPED' in targets,targets
        assert 'native-cut-time-callback' not in (runtime/'targets.tsv').read_text()
        assert '\tbos.Hnt(LjFk;)V' in targets,targets
        comp=(runtime/'composition-report.tsv').read_text()
        assert '\tbos\tMRY\t(LkPd;)V\tREPLACE_INVOKE' in comp,comp
        assert '\tbos\tHnt\t(LjFk;)V\t1' in comp,comp
        patch=(runtime/'transform-report.txt').read_text()
        assert 'PATCH_OK' in patch and 'DEEP_VERIFY_OK' in patch,patch
        remapped=runtime/'helper.jar';overlay=runtime/'deep-verify-replacements.jar';assert remapped.exists(),list(runtime.iterdir())
        put(t,'Runner.java','public class Runner { public static void main(String[] args) { new bos().MRY(new kPd()); if(bos.calls!=1) throw new AssertionError("calls "+bos.calls); System.out.println("CUT_TIME_INVOKE_REMAP_OK"); } }')
        run('javac','-cp',str(bin/'bitwig.jar'),'-d',t/'driver',t/'Runner.java')
        cp=os.pathsep.join(map(str,[overlay,remapped,bin/'bitwig.jar',t/'driver']))
        execresult=run('java','-Xverify:all','-cp',cp,'Runner')
        assert 'CUT_TIME_INVOKE_REMAP_OK' in execresult.stdout,execresult.stdout
        print('PASS: source 6.1.3 inherited invocation bound as bos.Hnt(LjFk;)V')
        print('PASS: target bos.MRY transformed with exact invocation, helper remapped, deep verified')
        print('PASS: -Xverify:all runtime executes converted synthetic Cut-Time guard')
        # Fail closed when the resolved physical callsite is absent.
        put(t,'newsrc/bos.java','public class bos {public void Hnt(jFk x){} public void MRY(kPd x){} }')
        run('javac','-d',t/'new-classes',*(t/'newsrc').glob('*.java'))
        run('jar','cf',bin/'bitwig.jar','-C',t/'new-classes','.')
        b=json.loads(builds.read_text());next(x for x in b['builds'] if x['build_key']=='windows-6.1.4')['bitwig_jar_sha256']=hashlib.sha256((bin/'bitwig.jar').read_bytes()).hexdigest();builds.write_text(json.dumps(b))
        failed=run(*command,good=False)
        assert failed.returncode and 'Invocation match not found: opcode=182 bos.Hnt(LjFk;)V' in failed.stdout,failed.stdout
        print('PASS: physically missing invocation rejects preparation with translated target in error')
        # Restore a valid invocation before checking required-trace failure.
        put(t,'newsrc/bos.java','public class bos { public static int calls=0; public void Hnt(jFk x) { calls++; } public void MRY(kPd item) { this.Hnt(item.value); } }')
        run('javac','-d',t/'new-classes',*(t/'newsrc').glob('*.java'))
        run('jar','cf',bin/'bitwig.jar','-C',t/'new-classes','.')
        b=json.loads(builds.read_text());next(x for x in b['builds'] if x['build_key']=='windows-6.1.4')['bitwig_jar_sha256']=hashlib.sha256((bin/'bitwig.jar').read_bytes()).hexdigest();builds.write_text(json.dumps(b))
        # A required diagnostic target must still reject an unmapped identity.
        manifest['trace_targets'][0].pop('optional');(m/'mod.json').write_text(json.dumps(manifest))
        required=run(*command,good=False)
        assert required.returncode and 'Cannot translate required target' in required.stdout,required.stdout
        print('PASS: optional diagnostic omitted, same unmapped target fails if required')
if __name__=='__main__':main()
