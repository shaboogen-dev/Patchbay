#!/usr/bin/env python3
"""Synthetic 6.1.3-built manifest translated onto synthetic 6.1.4 Java runtime."""
from pathlib import Path
import hashlib,json,tempfile,shutil,subprocess,os
HERE=Path(__file__).resolve().parents[2]
def run(args,check=True):
    result=subprocess.run([str(a) for a in args],capture_output=True,text=True)
    if check and result.returncode:raise AssertionError(result.stdout+'\n'+result.stderr)
    return result
with tempfile.TemporaryDirectory(prefix='bml-614-compat-') as root:
    root=Path(root); bml=root/'bml';loader=bml/'loader';mods=bml/'mods';mods.mkdir(parents=True)
    shutil.copytree(HERE/'loader',loader,ignore=shutil.ignore_patterns('mod-runtime','build','tests'))
    vendor=root/'Bitwig';jarbin=vendor/'bin';jarbin.mkdir(parents=True)
    src=root/'src'/'com'/'example';src.mkdir(parents=True)
    (src/'Foo614.java').write_text('package com.example; public class Foo614 { public void tick614() { System.out.println("OK_614"); } }')
    classes=root/'classes';run(['javac','-d',classes,src/'Foo614.java']);run(['jar','cf',jarbin/'bitwig.jar','-C',classes,'.'])
    builds=loader/'compatibility/builds.json';j=json.loads(builds.read_text());target=next(x for x in j['builds'] if x['build_key']=='windows-6.1.4')
    target['bitwig_jar_sha256']=hashlib.sha256((jarbin/'bitwig.jar').read_bytes()).hexdigest();builds.write_text(json.dumps(j))
    compat=loader/'compatibility/compatibility-v2.json';groups=json.loads(compat.read_text())
    groups['groups'].append({'variants':{'windows-6.1.3':{'kind':'method','owner':'com/example/Foo','name':'tick','descriptor':'()V'},'windows-6.1.4':{'kind':'method','owner':'com/example/Foo614','name':'tick614','descriptor':'()V'}}})
    compat.write_text(json.dumps(groups))
    m=mods/'portable';m.mkdir()
    manifest={'manifest_version':2,'mod':{'id':'portable','name':'Portable test','version':'0.1.0','author':'test','description':'Synthetic fixture'},'built_against':{'bitwig_version':'6.1.3','platform':'windows','architecture':'x64'},'targets':{'core.method':{'kind':'method','owner':'com/example/Foo','name':'tick','descriptor':'()V'}},'hooks':[],'trace_targets':[{'id':'trace','target':'core.method'}]}
    (m/'mod.json').write_text(json.dumps(manifest))
    cmd=['java','-cp',loader/'app'/'bml-control.jar','dev.bitwigre.bml.JavaPreparer','--loader-root',loader,'--bitwig-bin',jarbin,'--mods-root',mods]
    got=run(cmd); assert 'PREPARE_OK' in got.stdout,got.stdout
    rows=(loader/'mod-runtime/target-resolution.tsv').read_text()
    assert 'MAPPED' in rows and 'Foo614.tick614()V' in rows,rows
    print('PASS: synthetic 6.1.3 mod physically translated onto recognized 6.1.4 runtime')
    # Candidate 16: future/ancillary declarations can be absent without causing
    # a mod with fully translated hooks and diagnostic targets to fail preparation.
    manifest['targets']['ancillary.unmapped']={'kind':'method','owner':'com/example/Foo','name':'unmappedOptional','descriptor':'()V'}
    (m/'mod.json').write_text(json.dumps(manifest))
    got=run(cmd)
    rows=(loader/'mod-runtime/target-resolution.tsv').read_text()
    props=(loader/'mod-runtime/mod-bindings.properties').read_text()
    assert 'ancillary.unmapped\twindows-6.1.3\tUNMAPPED\tmethod' in rows,rows
    assert 'mod.portable.target.ancillary.unmapped.status=UNMAPPED' in props,props
    assert 'mod.portable.target.ancillary.unmapped.owner=' not in props,props
    print('PASS: unmapped unused declaration is audited, without leaking stale JVM owner into runtime bindings')
    manifest['trace_targets'].append({'id':'requires-unmapped','target':'ancillary.unmapped'})
    (m/'mod.json').write_text(json.dumps(manifest))
    fail=run(cmd,False)
    assert fail.returncode!=0 and 'Cannot translate required target' in (fail.stdout+fail.stderr),(fail.stdout,fail.stderr)
    print('PASS: referencing an unmapped declaration in a trace fails closed')
    manifest['trace_targets'].pop()
    manifest['targets'].pop('ancillary.unmapped')
    manifest['targets']['core.method']['name']='nonexistent';(m/'mod.json').write_text(json.dumps(manifest))
    fail=run(cmd,False)
    assert fail.returncode!=0 and 'Cannot translate required target' in (fail.stdout+fail.stderr),(fail.stdout,fail.stderr)
    print('PASS: unknown cross-version source target fails instead of silently falling back')
