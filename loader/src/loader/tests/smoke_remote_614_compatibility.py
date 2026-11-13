#!/usr/bin/env python3
"""Portable regression for the missing inherited help-text mapping and remote-control mods."""
from pathlib import Path
import json,gzip
root=Path(__file__).resolve().parents[2]
comp=json.loads((root/'loader/compatibility/compatibility-v2.json').read_text())
idx={}
for group in comp['groups']:
 src=group['variants'].get('windows-6.1.3');dst=group['variants'].get('windows-6.1.4')
 if src: idx[json.dumps(src,sort_keys=True)]=dst
for mod in ('remote-control-page-buttons-always-visible','visible-remote-controls-randomizer'):
 manifest=json.loads((root/'mods'/mod/'mod.json').read_text());count=0
 for local,t in manifest['targets'].items():
  t=dict(t)
  if 'owner' in t:t['owner']=t['owner'].replace('.','/')
  if 'class' in t:t['class']=t['class'].replace('.','/')
  assert idx.get(json.dumps(t,sort_keys=True)),f'{mod}/{local} missing compatibility'
  count+=1
 print('PASS',mod,count,'compatibility mappings')
required=[
 ('M','dAf','A1','(Ljava/lang/String;)V','dAa','wGc','(Ljava/lang/String;)V'),
 ('M','dAi','THY','(Ljava/lang/String;)V','dAd','TMF','(Ljava/lang/String;)V'),
 ('F','epe','k31','Lepe;','eoV','IwV','LeoV;')]
remap=root/'loader/compatibility/remap/windows-6.1.3__windows-6.1.4.tsv.gz'
with gzip.open(remap,'rt') as f:records={tuple(x.rstrip('\n').split('\t')) for x in f if x and not x.startswith('#')}
for r in required:assert r in records,r
assert (root/'bml.bat').is_file()
assert (root/'mods/shortcut-aliases/resources/config').is_dir()
print('PASS mapping consistency and packaging input requirements')
