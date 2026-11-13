#!/usr/bin/env python3
"""Synthetic overlapping REPLACE_INVOKE regression; no vendor jars or private mods."""
import hashlib,json,os,shutil,tempfile
from pathlib import Path
from smoke_java_preparer import SRC,command,dump

def main():
 with tempfile.TemporaryDirectory(prefix='bml-invoke-compose-') as d:
  t=Path(d);root=t/'bml';ldr=root/'loader';mods=root/'mods';mods.mkdir(parents=True)
  shutil.copytree(SRC/'loader',ldr,ignore=shutil.ignore_patterns('build','mod-runtime','tests','build-invoke'))
  bin=t/'bitwig/bin';bin.mkdir(parents=True)
  jre=t/'bitwig/jre/bin';jre.mkdir(parents=True)
  java=shutil.which('java');assert java
  if os.name=='nt':shutil.copy2(java,jre/'java.exe')
  else:(jre/'java').symlink_to(java)
  source=t/'source/example';source.mkdir(parents=True)
  (source/'Page.java').write_text('''package example;
public final class Page {
  public static StringBuilder original(String label, StringBuilder b) {return b.append("original");}
  public static StringBuilder create() {return original("X",new StringBuilder());}
}''')
  command(['javac','-d',t/'classes',source/'Page.java'])
  command(['jar','cf',bin/'bitwig.jar','-C',t/'classes','.'])
  builds=ldr/'compatibility/builds.json';data=json.loads(builds.read_text());data['builds'][next(i for i,b in enumerate(data['builds']) if b['build_key']=='windows-6.1.3')]['bitwig_jar_sha256']=hashlib.sha256((bin/'bitwig.jar').read_bytes()).hexdigest();dump(builds,data)
  # Real source/signatures of Device Window Helper and Plugin Undo are not provided.
  # This fixture reproduces their collision *shape*, not those actual mod behaviours.
  helper=t/'helper';(helper/'demo').mkdir(parents=True)
  (helper/'demo/FirstBridge.java').write_text('''package demo;public class FirstBridge {
 public static StringBuilder replace(String name,StringBuilder b) {return b.append("A");}
}''')
  (helper/'demo/SecondBridge.java').write_text('''package demo;public class SecondBridge {
 public static StringBuilder replace(String name,StringBuilder b) {return b.append("B");}
 public static StringBuilder alternate(Object name,Object b) {return ((StringBuilder)b).append("B");}
 public static StringBuilder incompatible(StringBuilder name,StringBuilder b) {return b.append("unwanted");}
}''')
  command(['javac','-d',t/'bridges',*(helper/'demo').glob('*.java')])
  def manifest(name):
   className={'first':'FirstBridge','second':'SecondBridge'}[name]
   return {'manifest_version':2,'mod':{'id':name,'name':name,'version':'1.0','author':'BML','description':'Synthetic collision fixture'},
    'built_against':{'bitwig_version':'6.1.3','platform':'windows','architecture':'x64'},
    'helper':{'jar':'helper.jar','class':'demo.'+className},
    'targets':{
      'create':{'kind':'method','owner':'example/Page','name':'create','descriptor':'()Ljava/lang/StringBuilder;'},
      'original':{'kind':'method','owner':'example/Page','name':'original','descriptor':'(Ljava/lang/String;Ljava/lang/StringBuilder;)Ljava/lang/StringBuilder;'}},
    'hooks':[{'id':'replace','target':'create','transform':'REPLACE_INVOKE',
      'match':{'opcode':'INVOKESTATIC','target':'original','occurrence':1},
      'bridge':{'method':'replace','descriptor':'(Ljava/lang/String;Ljava/lang/StringBuilder;)Ljava/lang/StringBuilder;','args':[]}}],
    'trace_targets':[]}
  for name in ('first','second'):
   folder=mods/name;folder.mkdir()
   command(['jar','cf',folder/'helper.jar','-C',t/'bridges','demo/'+{'first':'FirstBridge','second':'SecondBridge'}[name]+'.class'])
   dump(folder/'mod.json',manifest(name))
  cmd=['java','-cp',ldr/'app/bml-control.jar','dev.bitwigre.bml.JavaPreparer',
       '--loader-root',ldr,'--bitwig-bin',bin,'--mods-root',mods]
  r=command(cmd);assert 'PREPARE_OK' in r.stdout and 'transforms=2' in r.stdout,r.stdout
  rt=ldr/'mod-runtime'
  report=(rt/'transform-report.txt').read_text()
  assert 'PATCH_OK_CHAIN bridges=2' in report and 'DEEP_VERIFY_OK' in report,report
  comp=(rt/'composition-report.tsv').read_text()
  assert 'first\treplace' in comp and 'second\treplace' in comp,comp
  main=t/'Main.java';main.write_text('public class Main {public static void main(String[]args){System.out.println(example.Page.create().toString());}}')
  command(['javac','-cp',bin/'bitwig.jar','-d',t/'driver',main])
  cp=os.pathsep.join(map(str,[rt/'deep-verify-replacements.jar',rt/'helper.jar',bin/'bitwig.jar',t/'driver']))
  p=command(['java','-Xverify:all','-cp',cp,'Main'])
  assert p.stdout.strip()=='AB',p.stdout
  print('PASS: two mods replace one static invocation; chained A then B and full verification succeeded')
  # A real v2 mod uses a wider Object reference for its final widget parameter.
  # This is safe: the incoming reference is still the exact original JVM type.
  wider=manifest('second')
  wider['hooks'][0]['bridge']['descriptor']='(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/StringBuilder;'
  wider['hooks'][0]['bridge']['method']='alternate'
  dump(mods/'second/mod.json',wider)
  p=command(cmd)
  assert 'PREPARE_OK' in p.stdout,p.stdout
  p=command(['java','-Xverify:all','-cp',cp,'Main'])
  assert p.stdout.strip()=='AB',p.stdout
  print('PASS: reference-to-Object widening of both bridge parameters composes and executes')
  # An incompatible non-Object parameter must still fail with both identities.
  wrong=manifest('second')
  wrong['hooks'][0]['bridge']['descriptor']='(Ljava/lang/StringBuilder;Ljava/lang/StringBuilder;)Ljava/lang/StringBuilder;'
  wrong['hooks'][0]['bridge']['method']='incompatible'
  dump(mods/'second/mod.json',wrong)
  failed=command(cmd,ok=False)
  assert failed.returncode and 'Unsafe REPLACE_INVOKE overlap' in failed.stdout and 'first::replace' in failed.stdout and 'second::replace' in failed.stdout,failed.stdout
  print('PASS: incompatible parameter narrowing rejects with both mod/hook identities')
  # Equal slot counts do not make return-type changes composable.
  wrong=manifest('second')
  wrong['hooks'][0]['bridge']['descriptor']='(Ljava/lang/String;Ljava/lang/StringBuilder;)Ljava/lang/Object;'
  dump(mods/'second/mod.json',wrong)
  failed=command(cmd,ok=False)
  assert failed.returncode and 'Unsafe REPLACE_INVOKE overlap' in failed.stdout,failed.stdout
  print('PASS: different bridge return descriptor remains rejected')
  # Old single replacement remains unchanged.
  shutil.rmtree(mods/'second')
  p=command(cmd)
  assert 'PREPARE_OK' in p.stdout and 'PATCH_OK mode=' in (rt/'transform-report.txt').read_text()
  print('PASS: one-mod replacement remains on existing patch path')
  # Byte-for-byte descriptor shapes taken from the separately verified real
  # Device Window Helper / Plugin Undo declarations; bodies remain synthetic.
  # The actual mod packages and Bitwig vendor classes are never redistributed.
  real=t/'actual-signature-shape';real.mkdir()
  (real/'dvm.java').write_text('public class dvm {public final StringBuilder log = new StringBuilder();}')
  (real/'cHD.java').write_text('''public class cHD {
    public static dvm vhT(String key, dvm widget) {throw new AssertionError("stock method should be replaced");}
    public static dvm qHi() {return vhT("device", new dvm());}
  }''')
  (real/'DeviceWindowHelper.java').write_text('''public class DeviceWindowHelper {
    public static dvm extendDeviceSection(String key, dvm widget) {widget.log.append("D");return widget;}
  }''')
  (real/'PluginUndoMod613.java').write_text('''public class PluginUndoMod613 {
    public static dvm wrapDeviceSection(String key, Object widget) {((dvm)widget).log.append("P");return (dvm)widget;}
  }''')
  (real/'RealDriver.java').write_text('''public class RealDriver {
    public static void main(String[] args) {System.out.println(cHD.qHi().log.toString());}
  }''')
  command(['javac','--release','21','-d',real/'classes',*(real.glob('*.java'))])
  command(['jar','cf',real/'stock.jar','-C',real/'classes','.'])
  replacer=ldr/'tools/bitwig-invoke-replacer.jar'
  output=real/'overlay/cHD.class'
  q=command(['java','-jar',replacer,'chain-jar',real/'stock.jar','cHD',output,'qHi','()Ldvm;',
     'INVOKESTATIC','cHD','vhT','(Ljava/lang/String;Ldvm;)Ldvm;','1',
     'DeviceWindowHelper','extendDeviceSection','(Ljava/lang/String;Ldvm;)Ldvm;',
     'PluginUndoMod613','wrapDeviceSection','(Ljava/lang/String;Ljava/lang/Object;)Ldvm;'])
  assert 'PATCH_OK_CHAIN bridges=2' in q.stdout,q.stdout
  live=command(['java','-Xverify:all','-cp',os.pathsep.join([str(real/'overlay'),str(real/'classes')]),'RealDriver'])
  assert live.stdout.strip()=='DP',live.stdout
  print('PASS: exact real mod bridge descriptor shapes compose, JVM-verify and execute D then P')
  print('INVOKE_COMPOSITION_SMOKE_OK')

if __name__=='__main__':main()
