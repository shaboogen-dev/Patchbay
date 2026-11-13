import tempfile,subprocess,shutil,pathlib,hashlib,json,os
S=pathlib.Path(__file__).resolve().parents[2]
T=pathlib.Path(tempfile.mkdtemp(prefix='bml-auto-preparer-'))
try:
 B=T/'BML';shutil.copytree(S/'loader',B/'loader',ignore=shutil.ignore_patterns('build','mod-runtime','tests','build-invoke'))
 mods=B/'mods';mods.mkdir()
 bit=T/'Bitwig';bn=bit/'bin';bn.mkdir(parents=True);j=bit/'jre/bin';j.mkdir(parents=True)
 java=shutil.which('java');(j/'java').symlink_to(java)
 src=T/'src/example';src.mkdir(parents=True)
 (src/'CutOps.java').write_text('package example;import java.util.List;public interface CutOps{void indirect(List<?> l,double a,double b,Gg3 g);}')
 (src/'Unc.java').write_text('package example;import java.util.List;public class Unc implements CutOps{public static int nativeRuns=0; public void indirect(List<?> l,double a,double b,Gg3 g){nativeRuns++;}public void JQk(List<?> l,double a,double b,Gg3 g){nativeRuns++;}}')
 (src/'Gg3.java').write_text('package example;import java.util.List;public class Gg3{public void XYc(){CutOps x=new Unc();x.indirect(List.of("a"),16,24,this);}}')
 classes=T/'classes';classes.mkdir();subprocess.run(['javac','--release','21','-d',str(classes),*map(str,src.glob('*.java'))],check=True)
 subprocess.run(['jar','cf',str(bn/'bitwig.jar'),'-C',str(classes),'.'],check=True)
 builds=B/'loader/compatibility/builds.json';builds_data=json.loads(builds.read_text());next(b for b in builds_data['builds'] if b['build_key']=='windows-6.1.3')['bitwig_jar_sha256']=hashlib.sha256((bn/'bitwig.jar').read_bytes()).hexdigest();builds.write_text(json.dumps(builds_data))
 mod=mods/'auto-test';mod.mkdir()
 bridge=T/'bridge/demo';bridge.mkdir(parents=True)
 (bridge/'Bridge.java').write_text('package demo;import example.Gg3;import java.util.List;public class Bridge{public static int hits=0;public static void compositeCutTime(Object e,List<?> l,double a,double b,Gg3 g){hits++;}}')
 subprocess.run(['javac','--release','21','-cp',str(classes),'-d',str(T/'bridgeclasses'),str(bridge/'Bridge.java')],check=True)
 subprocess.run(['jar','cf',str(mod/'helper.jar'),'-C',str(T/'bridgeclasses'),'.'],check=True)
 method=lambda o,n,d: {'kind':'method','owner':o,'name':n,'descriptor':d}
 doc={'manifest_version':2,'mod':{'id':'auto-test','name':'Auto test','version':'1','author':'BML','description':'Auto match fixture'},'built_against':{'bitwig_version':'6.1.3','platform':'windows','architecture':'x64'},'helper':{'jar':'helper.jar','class':'demo.Bridge'},'targets':{'cut':method('example/Gg3','XYc','()V'),'original':method('example/Unc','JQk','(Ljava/util/List;DDLexample/Gg3;)V')},'hooks':[{'id':'cut','target':'cut','transform':'REPLACE_INVOKE','match':{'opcode':'AUTO_INSTANCE','target':'original','occurrence':1},'bridge':{'method':'compositeCutTime','descriptor':'(Ljava/lang/Object;Ljava/util/List;DDLexample/Gg3;)V','args':[]}}],'trace_targets':[]}
 (mod/'mod.json').write_text(json.dumps(doc))
 cmd=['java','-cp',str(B/'loader/app/bml-control.jar'),'dev.bitwigre.bml.JavaPreparer','--loader-root',str(B/'loader'),'--bitwig-bin',str(bn),'--mods-root',str(mods)]
 p=subprocess.run(cmd,capture_output=True,text=True,env={**os.environ,'BML_DEBUG_VERBOSE':'1'})
 if p.returncode: print(p.stdout,p.stderr);raise RuntimeError('prepare failed')
 assert 'PREPARE_OK' in p.stdout,p.stdout
 rt=B/'loader/mod-runtime';report=(rt/'transform-report.txt').read_text();assert 'AUTO_INSTANCE_RESOLVED' in report and 'DEEP_VERIFY_OK' in report,report
 print('PASS full preparer and deep verify, alternate symbolic name/owner/opcode')
 driver=T/'Main.java';driver.write_text('public class Main{public static void main(String[] x){new example.Gg3().XYc();if(demo.Bridge.hits!=1||example.Unc.nativeRuns!=0)throw new RuntimeException("wrong dispatch");System.out.println("PASS runtime auto call");}}')
 subprocess.run(['javac','-cp',str(bn/'bitwig.jar')+os.pathsep+str(mod/'helper.jar'),'-d',str(T/'driver'),str(driver)],check=True)
 cp=os.pathsep.join(map(str,[rt/'deep-verify-replacements.jar',rt/'helper.jar',bn/'bitwig.jar',T/'driver']))
 p=subprocess.run(['java','-Xverify:all','-cp',cp,'Main'],capture_output=True,text=True)
 print(p.stdout,p.stderr);assert p.returncode==0
finally:
 shutil.rmtree(T,ignore_errors=True)
