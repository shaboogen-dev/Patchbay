#!/usr/bin/env python3
"""Projects in Browser IO remap regression; requires Java/Javap, no source Bitwig installation."""
from pathlib import Path
import gzip,zipfile,subprocess,json,tempfile,os,sqlite3
root=Path(__file__).resolve().parents[2]
old='com/bitwig/base/io/Lcv'; new='com/bitwig/base/io/z2y'
mapfile=root/'loader/compatibility/remap/windows-6.1.3__windows-6.1.4.tsv.gz'
with gzip.open(mapfile,'rt',encoding='utf-8') as f:lines={s.strip() for s in f}
assert f'C\t{old}\t{new}' in lines
methods=[x.split('\t') for x in lines if x.startswith(f'M\t{old}\t')]
fields=[x.split('\t') for x in lines if x.startswith(f'F\t{old}\t')]
assert len(methods)==26 and len(fields)==3,(len(methods),len(fields))
# Optional exact-inventory validation when supplied for developer use.
a_path=os.environ.get('BML_613_INVENTORY');b_path=os.environ.get('BML_614_INVENTORY')
if a_path and b_path:
 def methods_at(path,name):
  db=sqlite3.connect(path)
  try:return {(n,d):op for n,d,op in db.execute('select m.name,m.descriptor,m.opcode_sha256 from methods m join classes c on c.id=m.class_id where c.name=?',(name,))}
  finally:db.close()
 src=methods_at(a_path,old);dst=methods_at(b_path,new)
 assert len(src)==26 and len(dst)==27
 for _,so,sn,sd,to,tn,td in methods:
  assert so==old and to==new and src[(sn,sd)]==dst[(tn,td)]
# Optionally validate against the externally supplied pristine mod helper.
original=Path(os.environ.get('BML_PROJECTS_ORIGINAL_HELPER',str(root/'mods/projects-in-browser/helper.jar')))
if original.is_file():
 with tempfile.TemporaryDirectory(prefix='bml-project-io-') as td:
  t=Path(td); output=t/'remapped.jar'
  cmd=['java','-jar',str(root/'loader/tools/bitwig-helper-remapper.jar'),str(original),str(output),str(mapfile),str(t/'no-overrides.tsv'),str(t/'report.txt')]
  proc=subprocess.run(cmd,text=True,capture_output=True,check=True)
  assert 'AUTO_REMAP warnings=0' in proc.stdout,proc.stdout
  assert 'helper_override_declarations_translated=' in proc.stdout
  with zipfile.ZipFile(output) as z:
   assert len([n for n in z.namelist() if n.endswith('.class')])==10 # original 9 + BML bridge
  v=subprocess.run(['javap','-c','-p','-classpath',str(output),'BrowserProjectDrillModel','BrowserProjectTrackResult'],text=True,capture_output=True,check=True).stdout
  for token in ('zC.tp2:()Lcom/bitwig/base/io/z2y;','hL.TMF:()Lcom/bitwig/base/io/z2y;','z2y.TMF:()Z','z2y.cfM:()Ljava/io/File;'):
   assert token in v,token
  assert 'com/bitwig/base/io/Lcv' not in v
  verbose=subprocess.run(['javap','-v','-classpath',str(output),'BrowserProjectDrillModel','BrowserProjectTrackResult'],text=True,capture_output=True,check=True).stdout
  assert not any('com/bitwig/base/io/Lcv' in line and '= Utf8' not in line for line in verbose.splitlines())
  assert 'public com.bitwig.base.io.z2y tp2();' in v
j=json.loads((root/'loader/compatibility/compatibility-v2.json').read_text())
g=next(g for g in j['groups'] if g['variants'].get('windows-6.1.3')=={'kind':'class','class':old})
assert g['variants']['windows-6.1.4']=={'kind':'class','class':new}
assert (root/'bml.bat').is_file()
assert not list((root/'mods').glob('*/mod.json'))
print('PASS: IO mapping and mod-free package checks; pristine helper '+('tested' if original.is_file() else 'not supplied'))
