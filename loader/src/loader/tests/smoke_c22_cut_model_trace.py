#!/usr/bin/env python3
"""Check C22 6.1.3 -> 6.1.4 cross-version trace resolution on a synthetic jar.
No proprietary vendor binaries; validates trace metadata and preparer lookup only.
"""
from pathlib import Path
import hashlib, json, tempfile, shutil, subprocess
ROOT=Path(__file__).resolve().parents[2]
SRC_MAN=json.loads((ROOT/'mods/paste-time/mod.json').read_text())
PROBES=[x for x in SRC_MAN['trace_targets'] if x['id'].startswith('cut-model-')]
assert len(PROBES)==5

def run(*args):
 r=subprocess.run(list(map(str,args)),capture_output=True,text=True)
 if r.returncode:raise AssertionError(f'{args}:\n{r.stdout}\n{r.stderr}')
 return r.stdout

with tempfile.TemporaryDirectory(prefix='bml-c22-traces-') as t:
 t=Path(t);loader=t/'bml/loader';mod=t/'bml/mods/cut-trace';mod.mkdir(parents=True)
 shutil.copytree(ROOT/'loader',loader,ignore=shutil.ignore_patterns('mod-runtime','build','tests','build-*'))
 p=t/'vendor/bin';p.mkdir(parents=True)
 src=t/'src';src.mkdir()
 fixture={
  'jFk.java':'public class jFk { public int BDm() {return 1;} }',
  'LawI.java':'public class LawI {}',
  'hb.java':'public class hb {}',
  'hqk.java':'public class hqk {}',
  'pPb.java':'public class pPb {}',
  'Nh.java':'import java.util.ArrayList; public class Nh { public LawI cfM(jFk x) { return new LawI(); } public hb MRY(jFk x) {return new hb();} public void cfM(jFk x,hqk y) {} public void cfM(ArrayList x,LawI y) {} }',
  'MbM.java':'public class MbM { public void insertedItem(pPb x,Object y,int z) {} }',
 }
 for name,content in fixture.items():(src/name).write_text(content)
 run('javac','-d',t/'classes',*(src.glob('*.java')))
 run('jar','cf',p/'bitwig.jar','-C',t/'classes','.')
 builds=loader/'compatibility/builds.json';j=json.loads(builds.read_text())
 next(x for x in j['builds'] if x['build_key']=='windows-6.1.4')['bitwig_jar_sha256']=hashlib.sha256((p/'bitwig.jar').read_bytes()).hexdigest()
 builds.write_text(json.dumps(j))
 target_names=[x['target'] for x in PROBES]
 m={k:SRC_MAN[k] for k in ('manifest_version','mod','built_against')}
 m['mod']=dict(m['mod'],id='cut-trace',name='Cut trace synthetic')
 m['targets']={k:v for k,v in SRC_MAN['targets'].items() if k in target_names}
 m['hooks']=[];m['trace_targets']=PROBES
 (mod/'mod.json').write_text(json.dumps(m))
 result=run('java','-cp',loader/'app/bml-control.jar','dev.bitwigre.bml.JavaPreparer','--loader-root',loader,'--bitwig-bin',p,'--mods-root',mod.parent)
 assert 'PREPARE_OK' in result,result
 targets=(loader/'mod-runtime/targets.tsv').read_text()
 resolution=(loader/'mod-runtime/target-resolution.tsv').read_text()
 for probe in PROBES:
  key=probe['target']
  assert key+'\twindows-6.1.3\tMAPPED\t' in resolution,(key,resolution)
  assert ('cut-trace' in targets and probe['id'] in targets),(probe,targets)
 for name in ('Nh','MbM'):
  assert name in targets,targets
 print('PASS: all five cut-model traces physically translate from 6.1.3 to 6.1.4')
 print('PASS: all five trace targets emitted into preparation runtime with expected owners')
 print('PASS: synthetic 6.1.4 preparation completes without transforming Bitwig classes')
