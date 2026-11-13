#!/usr/bin/env python3
"""Synthetic mixed v2/v3 Java preparation. Never modifies an installed Bitwig."""
import hashlib,json,os,shutil,subprocess,tempfile
from pathlib import Path
from smoke_java_preparer import SRC,command,dump

def fixture(name):
 return {'id':name,'name':name,'version':'1.0','author':'Test','description':'Fixture'}

def run():
 with tempfile.TemporaryDirectory(prefix='bml-v3-prep-') as d:
  root=Path(d)/'bml'; loader=root/'loader';mods=root/'mods';children=root/'components';state=root/'state'
  shutil.copytree(SRC/'loader',loader,ignore=shutil.ignore_patterns('mod-runtime','build','tests'))
  mods.mkdir();children.mkdir();state.mkdir()
  bbin=Path(d)/'bitwig/bin';bbin.mkdir(parents=True)
  bjava=Path(d)/'bitwig/jre/bin';bjava.mkdir(parents=True)
  java=shutil.which('java');
  if os.name=='nt':shutil.copy2(java,bjava/'java.exe')
  else:(bjava/'java').symlink_to(java)
  src=Path(d)/'src/com/example';src.mkdir(parents=True);classes=Path(d)/'classes'
  (src/'Foo.java').write_text('package com.example; public class Foo { public void tick() {} }')
  command(['javac','-d',classes,src/'Foo.java']);command(['jar','cf',bbin/'bitwig.jar','-C',classes,'.'])
  sha=hashlib.sha256((bbin/'bitwig.jar').read_bytes()).hexdigest()
  bf=loader/'compatibility/builds.json';builds=json.loads(bf.read_text());builds['builds'][-1]['bitwig_jar_sha256']=sha;dump(bf,builds)
  base={'built_against':{'bitwig_version':'6.1.3','platform':'windows','architecture':'x64'},'targets':{},'hooks':[],'trace_targets':[{'id':'trace','class':'com.example.Foo','method':'tick','descriptor':'()V'}]}
  legacy=mods/'legacy';legacy.mkdir();dump(legacy/'mod.json',{'manifest_version':2,'mod':fixture('legacy'),**base})
  parent=mods/'parent';parent.mkdir()
  manifest={'manifest_version':3,'package_type':'mod','mod':fixture('parent'),**base,
  'component_groups':{'themes':{'name':'Themes','selection':'single','required_resources':{'theme':{'kind':'file'}}},'extras':{'name':'Extras','selection':'multiple','required_resources':{'assets':{'kind':'directory'}}}}}
  dump(parent/'mod.json',manifest)
  for id,group,key,kind,path in [('dark','themes','theme','file','theme.bte'),('light','themes','theme','file','theme.bte'),('extra','extras','assets','directory','assets')]:
   c=children/id;c.mkdir()
   meta={**fixture(id),'parent':'parent','group':group};
   dump(c/'mod.json',{'manifest_version':3,'package_type':'component','component':meta,'resources':{key:{'kind':kind,'path':path,'persistence':'preserve'}}})
   if kind=='file':(c/path).write_text(id)
   else:(c/path).mkdir()
  choices=state/'selected-components.properties';choices.write_text('component.parent.group.themes.ids=dark\ncomponent.parent.group.extras.ids=extra\n')
  selected=state/'mods.txt';selected.write_text('parent\nlegacy\n')
  cmd=['java','-cp',loader/'app/bml-control.jar','dev.bitwigre.bml.JavaPreparer','--loader-root',loader,'--bitwig-bin',bbin,'--mods-root',mods]
  env={'BML_MOD_SELECTION_ACTIVE':'1','BML_ENABLED_MODS_FILE':str(selected)}
  run=command(cmd,env)
  binding=(loader/'mod-runtime/mod-bindings.properties').read_text()
  assert 'PREPARE_OK' in run.stdout and 'mods=2' in run.stdout,run.stdout
  assert 'component.parent.group.themes.selected.0.id=dark' in binding
  assert 'component.parent.group.extras.selected.0.resource.assets.persistence=preserve' in binding
  assert 'mod.legacy.' not in binding or 'loader.mod_ids=legacy,parent' in binding
  assert sum('theme.bte' in row for row in (loader/'mod-runtime/resource-resolution.tsv').read_text().splitlines())==1
  print('PASS: mixed v2/v3 prepare; component bindings, resource persistence, no component execution')
  choices.write_text('component.parent.group.themes.ids=missing\ncomponent.parent.group.extras.ids=extra\n')
  fail=command(cmd,env,False);assert fail.returncode and 'Selected mod' in fail.stdout and 'missing' in fail.stdout,fail.stdout
  assert 'missing' in choices.read_text()
  print('PASS: missing selected child blocks parent, persists requested identity')
  choices.write_text('component.parent.group.extras.ids=extra\n')
  auto=command(cmd,env)
  assert 'PREPARE_OK' in auto.stdout,auto.stdout
  assert 'component.parent.group.themes.selected.0.id=dark' in (loader/'mod-runtime/mod-bindings.properties').read_text()
  assert 'component.parent.group.themes.ids=dark' in choices.read_text()
  print('PASS: unconfigured single-choice defaults to first valid component and persists during CLI preparation')
  selected.write_text('legacy\n')
  run=command(cmd,env);assert 'mods=1' in run.stdout,run.stdout
  print('PASS: existing v2 mod loads even when unselected v3 parent has incomplete groups')
  selected.write_text('parent\nlegacy\n')
  choices.write_text('component.parent.group.themes.ids=light\ncomponent.parent.group.extras.ids=extra\n')
  run=command(cmd,env);assert 'component.parent.group.themes.selected.0.id=light' in (loader/'mod-runtime/mod-bindings.properties').read_text()
  print('PASS: component switch without parent manifest edits')
  (children/'light/theme.bte').unlink()
  fail=command(cmd,env,False);assert fail.returncode and 'Selected mod' in fail.stdout,fail.stdout
  print('PASS: removed selected resource fails closed without substitution')
  print('MANIFEST_V3_PREPARER_OK')

if __name__=='__main__':run()
