from pathlib import Path
import tempfile,shutil,subprocess,hashlib,json,os
S=Path(__file__).resolve().parents[2];T=Path(tempfile.mkdtemp(prefix='bml-java-transform-'))
B=T/'BML';shutil.copytree(S,B,ignore=shutil.ignore_patterns('build','mod-runtime','tests'))
shutil.rmtree(B/'mods',ignore_errors=True);(B/'mods').mkdir()
bit=T/'Bitwig';bin=bit/'bin';bin.mkdir(parents=True);(bit/'jre/bin').mkdir(parents=True);(bit/'jre/bin/java').symlink_to(shutil.which('java'))
src=T/'src';(src/'com/example').mkdir(parents=True)
(src/'com/example/Foo.java').write_text('''package com.example;
public class Foo {
public void tick(){System.out.println("tick");}
public String call(){return source();}
public static String source(){return "original";}
public Object getObject(){return "original";}
}''')
(src/'com/example/Parent.java').write_text('package com.example; public class Parent {public void inherited(){System.out.println("parent");}}')
(src/'com/example/Child.java').write_text('package com.example; public class Child extends Parent {}')
classes=T/'classes';classes.mkdir();subprocess.run(['javac','-d',classes,*map(str,(src/'com/example').glob('*.java'))],check=True)
subprocess.run(['jar','cf',bin/'bitwig.jar','-C',classes,'.'],check=True)
sha=hashlib.sha256((bin/'bitwig.jar').read_bytes()).hexdigest()
b=Path(B/'loader/compatibility/builds.json');data=json.loads(b.read_text());data['builds'][next(i for i,b in enumerate(data['builds']) if b['build_key']=='windows-6.1.3')]['bitwig_jar_sha256']=sha;b.write_text(json.dumps(data))
mod=B/'mods/other';mod.mkdir(parents=True)
helper=T/'helper/demo';helper.mkdir(parents=True)
(helper/'Bridge.java').write_text('''package demo; public class Bridge {
public static void before(){System.out.println("before");}
public static String replacement(){return "replaced";}
public static Object wrap(Object x){return "wrapped";}
public static void onAdd(){System.out.println("added");}
}''')
subprocess.run(['javac','-d',T/'helperclasses',helper/'Bridge.java'],check=True)
subprocess.run(['jar','cf',mod/'helper.jar','-C',T/'helperclasses','.'],check=True)
mtarget=lambda owner,name,desc:{'kind':'method','owner':owner,'name':name,'descriptor':desc}
manifest={'manifest_version':2,'mod':{'id':'other','name':'Other','version':'1.0','author':'Test','description':'Synthetic'},
'built_against':{'bitwig_version':'6.1.3','platform':'windows','architecture':'x64'},
'helper':{'jar':'helper.jar','class':'demo.Bridge'},
 'targets':{
 'tick':mtarget('com/example/Foo','tick','()V'),
 'call':mtarget('com/example/Foo','call','()Ljava/lang/String;'),
 'source':mtarget('com/example/Foo','source','()Ljava/lang/String;'),
 'object':mtarget('com/example/Foo','getObject','()Ljava/lang/Object;'),
 'child':{'kind':'class','class':'com/example/Child'},
 'inherited':mtarget('com/example/Parent','inherited','()V')},
 'hooks':[
 {'id':'before','target':'tick','transform':'INSERT_BEFORE_FINAL_RETURN','bridge':{'method':'before','descriptor':'()V','args':[]}},
 {'id':'invoke','target':'call','transform':'REPLACE_INVOKE','match':{'opcode':'INVOKESTATIC','target':'source','occurrence':1},'bridge':{'method':'replacement','descriptor':'()Ljava/lang/String;','args':[]}},
 {'id':'wrap','target':'object','transform':'WRAP_OBJECT_RETURN','bridge':{'method':'wrap','descriptor':'(Ljava/lang/Object;)Ljava/lang/Object;','args':[]}},
 {'id':'add','target':'child','transform':'ADD_METHOD','method':{'from_target':'inherited'},'body':{'template':'CALL_SUPER_THEN_STATIC'},'bridge':{'method':'onAdd','descriptor':'()V','args':[]}}
 ],'trace_targets':[]}
(mod/'mod.json').write_text(json.dumps(manifest))
cmd=['java','-cp',B/'loader/app/bml-control.jar','dev.bitwigre.bml.JavaPreparer','--loader-root',B/'loader','--bitwig-bin',bin,'--mods-root',B/'mods']
p=subprocess.run(list(map(str,cmd)),env={**os.environ,'BML_DEBUG_VERBOSE':'1'},text=True,capture_output=True)
if p.returncode: raise AssertionError(f'Java preparation failed: {p.stdout}\n{p.stderr}')
assert 'mods=1 transforms=4 replacement_classes=2' in p.stdout
assert 'DEEP_VERIFY_OK' in (B/'loader/mod-runtime/transform-report.txt').read_text()
print('PASS: ADD_METHOD, INSERT_BEFORE_FINAL_RETURN, REPLACE_INVOKE and WRAP_OBJECT_RETURN patched and deep-verified')
if p.returncode==0:
 driver=T/'Driver.java';driver.write_text('''public class Driver { public static void main(String[] a) {com.example.Foo f = new com.example.Foo();f.tick();System.out.println(f.call());System.out.println(f.getObject()); new com.example.Child().inherited();}}''')
 subprocess.run(['javac','-cp',str(bin/'bitwig.jar'),'-d',str(T/'driver'),str(driver)],check=True)
 cp=os.pathsep.join(map(str,[B/'loader/mod-runtime/deep-verify-replacements.jar',B/'loader/mod-runtime/helper.jar',bin/'bitwig.jar',T/'driver']))
 p=subprocess.run(['java','-cp',cp,'Driver'],capture_output=True,text=True)
 assert p.returncode==0 and p.stdout.splitlines()==['tick','before','replaced','wrapped','parent','added'], (p.stdout,p.stderr)
 print('PASS: four distinct transform families produce correct runtime execution')

shutil.rmtree(T)
