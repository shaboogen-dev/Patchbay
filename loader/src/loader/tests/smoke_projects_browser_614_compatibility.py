#!/usr/bin/env python3
"""Candidate 12: project browser JVM target identity and helper remap consistency."""
from pathlib import Path
import json, gzip
root=Path(__file__).resolve().parents[2]
compat=json.loads((root/'loader/compatibility/compatibility-v2.json').read_text())
manifest=json.loads((root/'mods/projects-in-browser/mod.json').read_text())

def key(t):
 if t['kind']=='class':return ('class',t['class'].replace('.','/'))
 return (t['kind'],t['owner'].replace('.','/'),t['name'],t['descriptor'])
refs={}
for g in compat['groups']:
 src=g['variants'].get('windows-6.1.3');dst=g['variants'].get('windows-6.1.4')
 if src:
  k=key(src)
  assert k not in refs,k
  refs[k]=dst
for local,target in manifest['targets'].items():
 assert refs.get(key(target)),local
assert len(manifest['targets'])==17
expected=[
 (('class','cFY'),('class','cFV')),
 (('class','com/bitwig/flt/document/core/iface/KWY'),('class','com/bitwig/flt/document/core/iface/pHd')),
 (('method','cFY','E3I','(Lcom/bitwig/flt/document/core/iface/KWY;LIv;Lcom/bitwig/flt/document_view_settings/AA;)V'),('method','cFV','MRY','(Lcom/bitwig/flt/document/core/iface/pHd;LJp;Lcom/bitwig/flt/document_view_settings/hgq;)V')),
 (('method','cDt','vhT','(Lcom/bitwig/flt/document/core/iface/KWY;Lcom/bitwig/flt/document_view_settings/AA;)LcDt;'),('method','cCV','cfM','(Lcom/bitwig/flt/document/core/iface/pHd;Lcom/bitwig/flt/document_view_settings/hgq;)LcCV;'))]
with gzip.open(root/'loader/compatibility/remap/windows-6.1.3__windows-6.1.4.tsv.gz','rt') as f:lines={line.strip() for line in f}
for a,b in expected:
 assert key(refs[a])==b,(a,refs.get(a))
 marker=('C\t'+'\t'.join(a[1:])+'\t'+'\t'.join(b[1:])) if a[0]=='class' else ('M\t'+'\t'.join(a[1:])+'\t'+'\t'.join(b[1:]))
 assert marker in lines, marker
states={line[4:].split('=',1)[0] for line in (root/'state/enabled-mods.properties').read_text().splitlines() if line.startswith('mod.') and line.endswith('=true')}
assert states=={'projects-in-browser'},states
assert (root/'bml.bat').is_file()
assert (root/'mods/shortcut-aliases/resources/config').is_dir()
print('PASS 17/17 project browser targets and four strict helper mappings; original launcher/config preserved')
