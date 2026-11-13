#!/usr/bin/env python3
"""Plugin Undo physical mappings, inherited chain and packaging smoke."""
import pathlib,json,gzip
R=pathlib.Path(__file__).resolve().parents[2]
DB=json.loads((R/'loader/compatibility/compatibility-v2.json').read_text())
K=lambda x:(x.get('kind'),x.get('owner'),x.get('name'),x.get('descriptor'),x.get('class'))
ix={}
for g in DB['groups']:
 s=g['variants'].get('windows-6.1.3');t=g['variants'].get('windows-6.1.4')
 if s:
  k=K(s); assert k not in ix,(k,'duplicate');ix[k]=t
for mid,total in [('plugin-undo',32),('preferences-composition-framework',17)]:
 d=json.loads((R/'mods'/mid/'mod.json').read_text()); assert len(d['targets'])==total
 for id,target in d['targets'].items():
  item=dict(target)
  for field in ('owner','class'):
   if field in item:item[field]=item[field].replace('.','/')
  assert ix.get(K(item)),(mid,id,item)
 print('PASS full manifest',mid,total)
rows=[
('com/bitwig/flt/document/core/master/device/xJu','twb','(Z)V','com/bitwig/flt/document/core/master/device/HSu','xL','(Z)V'),
('com/bitwig/flt/document/core/master/device/xHN','twb','(Z)V','com/bitwig/flt/document/core/master/device/rUE','xL','(Z)V'),
('com/bitwig/flt/document/core/master/device/xJu','zW1','(Z)V','com/bitwig/flt/document/core/master/device/HSu','qm','(Z)V'),
('com/bitwig/flt/document/core/master/device/xHN','zW1','(Z)V','com/bitwig/flt/document/core/master/device/rUE','qm','(Z)V')]
with gzip.open(R/'loader/compatibility/remap/windows-6.1.3__windows-6.1.4.tsv.gz','rt') as stream:
 tab={line.strip() for line in stream}
for so,sn,sd,to,tn,td in rows:
 assert ix[('method',so,sn,sd,None)]==dict(kind='method',owner=to,name=tn,descriptor=td)
 assert '\t'.join(('M',so,sn,sd,to,tn,td)) in tab
assert (R/'mods/shortcut-aliases/resources/config').is_dir()
assert (R/'bml.bat').read_bytes()==b'@echo off\r\ncall "bml-ui.cmd"\r\nexit' or (R/'bml.bat').read_bytes().replace(b'\r\n',b'\n').strip()==b'@echo off\ncall "bml-ui.cmd"\nexit'
state=(R/'state/enabled-mods.properties').read_text()
enabled={line[4:].split('=',1)[0] for line in state.splitlines() if line.startswith('mod.') and line.endswith('=true')}
assert enabled <= {d.name for d in (R/'mods').iterdir() if d.is_dir()},enabled
print('PASS aliases + remap parity + packaging (independent of active mod selection)')
