#!/usr/bin/env python3
"""BML Candidate 10: full manifest coverage and remap consistency for settings pair."""
from pathlib import Path
import json,gzip,zipfile,hashlib
ROOT=Path(__file__).resolve().parents[2]
comp=json.loads((ROOT/'loader/compatibility/compatibility-v2.json').read_text())['groups']
index={}
for group in comp:
 src=group['variants'].get('windows-6.1.3');dst=group['variants'].get('windows-6.1.4')
 if src:
  k=tuple(src.get(x) for x in ('kind','owner','name','descriptor','class'))
  assert k not in index,'duplicate source key'
  index[k]=dst
mods=['preferences-composition-framework','device-window-helper']
for mod in mods:
 manifest=json.loads((ROOT/'mods'/mod/'mod.json').read_text())
 unresolved=[]
 for id,s in manifest['targets'].items():
  t={k:v.replace('.','/') if k in ('owner','class') else v for k,v in s.items()};k=tuple(t.get(x) for x in ('kind','owner','name','descriptor','class'))
  if index.get(k) is None:unresolved.append((id,t))
 assert not unresolved,(mod,unresolved)
 print('PASS',mod,'targets',len(manifest['targets']))
required=[
 ('cHD','qHi','()Ldvm;','cHA','ztx','()Ldve;'),
 ('cHD','vhT','(Ljava/lang/String;Ldvm;)Ldvm;','cHA','cfM','(Ljava/lang/String;Ldve;)Ldve;'),
 ('cIf','vhT','(Ljava/lang/String;Ldvm;)Ldvm;','cIe','cfM','(Ljava/lang/String;Ldve;)Ldve;'),
]
with gzip.open(ROOT/'loader/compatibility/remap/windows-6.1.3__windows-6.1.4.tsv.gz','rt') as f:lines={x.rstrip('\n') for x in f}
for so,sn,sd,to,tn,td in required:
 assert '\t'.join(['M',so,sn,sd,to,tn,td]) in lines
 assert index[('method',so,sn,sd,None)]==dict(kind='method',owner=to,name=tn,descriptor=td)
assert (ROOT/'mods/shortcut-aliases/resources/config').is_dir()
assert (ROOT/'bml.bat').is_file()
# Selection is independently tested by the current candidate's own smoke test.
print('PASS compatibility + remap parity + preserved packaging requirements')
