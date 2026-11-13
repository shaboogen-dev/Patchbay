#!/usr/bin/env python3
"""Regression for two identical invoke matches in one mod targeting successive stock sites.

Do not group same-mod REPLACE_INVOKE declarations by match signature: the original
patcher intentionally matches occurrence 1 of the *remaining* instructions.
No Bitwig files or private mod files are needed.
"""
import hashlib,json,os,shutil,tempfile
from pathlib import Path
from smoke_java_preparer import SRC,command,dump

def main():
 with tempfile.TemporaryDirectory(prefix='bml-same-mod-sites-') as d:
  t=Path(d);root=t/'bml';ldr=root/'loader';mods=root/'mods';mods.mkdir(parents=True)
  shutil.copytree(SRC/'loader',ldr,ignore=shutil.ignore_patterns('build','mod-runtime','tests','build-invoke'))
  bin=t/'bitwig/bin';bin.mkdir(parents=True)
  jre=t/'bitwig/jre/bin';jre.mkdir(parents=True)
  java=shutil.which('java');assert java
  if os.name=='nt':shutil.copy2(java,jre/'java.exe')
  else:(jre/'java').symlink_to(java)
  source=t/'source/example';source.mkdir(parents=True)
  (source/'Button.java').write_text('''package example;
public class Button {
 public static final StringBuilder LOG = new StringBuilder();
 public final String name;
 public Button(String name) { this.name=name; }
 public void setHelp(String text) { LOG.append(name).append(":help:").append(text).append(";"); }
 public void setVisible(boolean v) { LOG.append(name).append(":visible:").append(v).append(";"); }
}''')
  (source/'Page.java').write_text('''package example;
public class Page {
 public static void render() {
   Button previous=new Button("previous"), next=new Button("next");
   previous.setHelp("stock"); next.setHelp("stock");
   previous.setVisible(false);next.setVisible(false);
 }
}''')
  command(['javac','--release','21','-d',t/'classes',source/'Button.java',source/'Page.java'])
  command(['jar','cf',bin/'bitwig.jar','-C',t/'classes','.'])
  builds=ldr/'compatibility/builds.json';data=json.loads(builds.read_text());next(b for b in data['builds'] if b['build_key']=='windows-6.1.3')['bitwig_jar_sha256']=hashlib.sha256((bin/'bitwig.jar').read_bytes()).hexdigest();dump(builds,data)
  helper=t/'helper/demo';helper.mkdir(parents=True)
  (helper/'Bridge.java').write_text('''package demo;import example.Button;
public final class Bridge {
  public static void previousHelp(Button button,String text) { button.setHelp("PREV"); }
  public static void nextHelp(Button button,String text) { button.setHelp("NEXT"); }
  public static void previousVisible(Button button,boolean v) { button.setVisible(true); }
  public static void nextVisible(Button button,boolean v) { button.setVisible(true); }
}''')
  command(['javac','--release','21','-cp',t/'classes','-d',t/'bridges',helper/'Bridge.java'])
  mod=mods/'page-buttons';mod.mkdir();command(['jar','cf',mod/'helper.jar','-C',t/'bridges','.'])
  hooks=[]
  for name,method,target,desc in [
   ('previous-page-button-editor-right-click','previousHelp','help','(Lexample/Button;Ljava/lang/String;)V'),
   ('next-page-button-editor-right-click','nextHelp','help','(Lexample/Button;Ljava/lang/String;)V'),
   ('previous-page-button-visible','previousVisible','visible','(Lexample/Button;Z)V'),
   ('next-page-button-visible','nextVisible','visible','(Lexample/Button;Z)V')]:
    hooks.append({'id':name,'target':'render','transform':'REPLACE_INVOKE',
      'match':{'opcode':'INVOKEVIRTUAL','target':target,'occurrence':1},
      'bridge':{'method':method,'descriptor':desc,'args':[]}})
  manifest={'manifest_version':2,'mod':{'id':'page-buttons','name':'Page Buttons','version':'1.0.0','author':'BML','description':'Sequential invocation site fixture'},
   'built_against':{'bitwig_version':'6.1.3','platform':'windows','architecture':'x64'},
   'helper':{'jar':'helper.jar','class':'demo.Bridge'},
   'targets':{'render':{'kind':'method','owner':'example/Page','name':'render','descriptor':'()V'},
              'help':{'kind':'method','owner':'example/Button','name':'setHelp','descriptor':'(Ljava/lang/String;)V'},
              'visible':{'kind':'method','owner':'example/Button','name':'setVisible','descriptor':'(Z)V'}},
   'hooks':hooks,'trace_targets':[]}
  dump(mod/'mod.json',manifest)
  cmd=['java','-cp',ldr/'app/bml-control.jar','dev.bitwigre.bml.JavaPreparer','--loader-root',ldr,'--bitwig-bin',bin,'--mods-root',mods]
  p=command(cmd);assert 'PREPARE_OK' in p.stdout and 'transforms=4' in p.stdout,p.stdout
  rt=ldr/'mod-runtime'
  report=(rt/'transform-report.txt').read_text()
  assert report.count('PATCH_OK mode=')==4 and 'PATCH_OK_CHAIN' not in report and 'DEEP_VERIFY_OK' in report,report
  driver=t/'Main.java';driver.write_text('''public class Main {public static void main(String[] a){
    example.Page.render();System.out.println(example.Button.LOG.toString());}}''')
  command(['javac','-cp',bin/'bitwig.jar','-d',t/'driver',driver])
  cp=os.pathsep.join(map(str,[rt/'deep-verify-replacements.jar',rt/'helper.jar',bin/'bitwig.jar',t/'driver']))
  result=command(['java','-Xverify:all','-cp',cp,'Main']).stdout.strip()
  expect='previous:help:PREV;next:help:NEXT;previous:visible:true;next:visible:true;'
  assert result==expect,(result,expect)
  print('PASS: same-mod sequential REPLACE_INVOKE matches patch Previous and Next independently')
  print('PASS: four hooks deep-verify; both INVOKEVIRTUAL match types execute correctly')
  print('PASS: cross-mod chain is not incorrectly invoked for one mod with two remaining sites')
  # Insufficient remaining matches must fail, rather than silently alias a
  # second hook to the first physical site.
  (source/'Page.java').write_text('''package example;public class Page {
 public static void render() {new Button("previous").setHelp("stock");
 new Button("previous").setVisible(false);new Button("next").setVisible(false);}
}''')
  command(['javac','--release','21','-d',t/'classes',source/'Button.java',source/'Page.java'])
  command(['jar','cf',bin/'bitwig.jar','-C',t/'classes','.'])
  next(b for b in data['builds'] if b['build_key']=='windows-6.1.3')['bitwig_jar_sha256']=hashlib.sha256((bin/'bitwig.jar').read_bytes()).hexdigest();dump(builds,data)
  failed=command(cmd,ok=False)
  assert failed.returncode and 'Invocation match not found' in failed.stdout,failed.stdout
  print('PASS: missing second physical site rejects rather than miscomposing hooks')
  print('SAME_MOD_INVOKE_SITES_SMOKE_OK')
if __name__=='__main__':main()
