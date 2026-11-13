#!/usr/bin/env python3
"""Synthetic Manifest v3.1 binding and isolation smoke; never starts Bitwig."""
import hashlib,json,os,shutil,subprocess,tempfile
from pathlib import Path
from smoke_java_preparer import SRC,command,dump

def run():
 with tempfile.TemporaryDirectory(prefix='bml-debug-outputs-') as t:
  base=Path(t);root=base/'bml';loader=root/'loader';mods=root/'mods';state=root/'state';
  shutil.copytree(SRC/'loader',loader,ignore=shutil.ignore_patterns('mod-runtime','build','tests'))
  mods.mkdir();state.mkdir(); (root/'components').mkdir()
  bbin=base/'bitwig/bin';bbin.mkdir(parents=True)
  jr=base/'bitwig/jre/bin';jr.mkdir(parents=True)
  java=shutil.which('java')
  if os.name=='nt':shutil.copy2(java,jr/'java.exe')
  else:(jr/'java').symlink_to(java)
  src=base/'src/com/example';src.mkdir(parents=True);classes=base/'classes'
  (src/'Foo.java').write_text('package com.example; public class Foo {public void tick(){}}')
  command(['javac','-d',classes,src/'Foo.java']);command(['jar','cf',bbin/'bitwig.jar','-C',classes,'.'])
  builds=loader/'compatibility/builds.json';data=json.loads(builds.read_text());data['builds'][-1]['bitwig_jar_sha256']=hashlib.sha256((bbin/'bitwig.jar').read_bytes()).hexdigest();dump(builds,data)
  for name,version in [('alpha',3),('legacy',2)]:
   folder=mods/name;folder.mkdir()
   manifest={'manifest_version':version,'mod':{'id':name,'name':name,'version':'1.0','author':'Test','description':'Synthetic'},'built_against':{'bitwig_version':'6.1.3','platform':'windows','architecture':'x64'},'targets':{},'hooks':[],'trace_targets':[{'id':'trace','class':'com.example.Foo','method':'tick','descriptor':'()V'}]}
   if version==3:
    manifest['package_type']='mod';manifest['diagnostics']={'outputs':{'runtime':{'kind':'file','path':'runtime.log'},'events':{'kind':'directory','path':'events'}}}
   dump(folder/'mod.json',manifest)
  cmd=['java','-cp',loader/'app/bml-control.jar','dev.bitwigre.bml.JavaPreparer','--loader-root',loader,'--bitwig-bin',bbin,'--mods-root',mods]
  session=(root/'sessions'/'2026-10-11_12-30-00_abcd1234abcd');session.mkdir(parents=True)
  debug={'BML_TRACE_ENABLED':'1','BML_SESSION_ID':session.name,'BML_SESSION_ROOT':str(session)}
  run=command(cmd,debug);assert 'PREPARE_OK' in run.stdout,run.stdout
  props=(loader/'mod-runtime/mod-bindings.properties').read_text()
  assert 'mod.alpha.diagnostics.enabled=true' in props
  assert 'mod.alpha.diagnostics.output.runtime.path=' in props
  assert 'mod.alpha.diagnostics.output.events.kind=directory' in props
  assert 'mod.legacy.diagnostics.root.path=' in props
  assert 'mod-logs/mod-alpha' in props.replace('\\','/') and 'mod-logs/mod-legacy' in props.replace('\\','/')
  assert (session/'mod-logs/mod-alpha').is_dir() and (session/'mod-logs/mod-legacy').is_dir()
  assert not (session/'mod-logs/mod-alpha/runtime.log').exists(), 'optional file should not be created by BML'
  print('PASS: debug bindings are early and isolated per mod, optional outputs are not created')
  run=command(cmd,{'BML_TRACE_ENABLED':'0','BML_SESSION_ID':session.name,'BML_SESSION_ROOT':str(session)})
  props=(loader/'mod-runtime/mod-bindings.properties').read_text()
  assert 'diagnostics.' not in props,props
  print('PASS: normal mode retains v2/v3 preparation without optional debug bindings')
  bad=json.loads((mods/'alpha/mod.json').read_text());bad['diagnostics']['outputs']['runtime']['path']='../unsafe.log';dump(mods/'alpha/mod.json',bad)
  badRun=command(cmd,debug,False)
  assert badRun.returncode!=0 and 'unsafe' in badRun.stdout.lower(),badRun.stdout
  print('PASS: selected unsafe v3 diagnostics block preparation')
  selection=state/'enable.txt';selection.write_text('legacy\n')
  run=command(cmd,{**debug,'BML_MOD_SELECTION_ACTIVE':'1','BML_ENABLED_MODS_FILE':str(selection)})
  assert 'PREPARE_OK' in run.stdout,run.stdout
  print('PASS: invalid unselected v3 diagnostics do not contaminate v2 prepare')
  # Check component schema did not gain the field; v2 schema unchanged by source diff checks.
  v3=json.loads((loader/'mod-v3.schema.json').read_text())
  assert 'diagnostics' in v3['oneOf'][0]['properties'] and 'diagnostics' not in v3['oneOf'][1]['properties']
  print('DIAGNOSTICS_V31_SYNTHETIC_OK')
if __name__=='__main__':run()
