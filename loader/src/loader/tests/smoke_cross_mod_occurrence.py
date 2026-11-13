#!/usr/bin/env python3
"""REPLACE_INVOKE occurrences across mods: each hook must land on the call it means in the original method.

Synthetic: no Bitwig, no real mods. Runs JavaPreparer on a temporary install, then runs the prepared classes.
Run it from an unpacked core ZIP (loader/tests/), or name one: smoke_cross_mod_occurrence.py [<unpacked core>].

Never touches the per-user settings folder: the temporary install has config/portable, which is checked before
anything runs, and the per-user variables point into the temporary folder as well.
"""
import hashlib,json,os,shutil,subprocess,sys,tempfile
from pathlib import Path

STATIC='(Ljava/lang/String;)V'
ACC='(Ljava/lang/String;Ljava/lang/StringBuilder;)Ljava/lang/StringBuilder;'
VIRTUAL='(Lexample/Page;Ljava/lang/String;)V'
CALLS={'x':('INVOKESTATIC','x',STATIC,'x',STATIC),
       'acc':('INVOKESTATIC','acc',ACC,'acc',ACC),
       'y':('INVOKEVIRTUAL','y',STATIC,'y',VIRTUAL)}
PAGE='''package example;
public class Page {
 public static final StringBuilder LOG=new StringBuilder();
 public static void x(String s){LOG.append(s).append(';');}
 public static StringBuilder acc(String s,StringBuilder b){return b.append(s).append(';');}
 public void y(String s){LOG.append(s).append(';');}
 public static void three(){x("one");x("two");x("three");}
 public static void two(){x("one");x("two");}
 public static StringBuilder chain(){StringBuilder b=new StringBuilder();b=acc("one",b);b=acc("two",b);b=acc("three",b);return b;}
 public static void virt(){Page p=new Page();p.y("one");p.y("two");p.y("three");}
}
'''
MAIN='''public class Main {public static void main(String[] a){
 switch(a[0]){
  case "three"->example.Page.three();
  case "two"->example.Page.two();
  case "virt"->example.Page.virt();
  case "chain"->example.Page.LOG.append(example.Page.chain());
 }
 System.out.println(example.Page.LOG);}}
'''
BRIDGE='''package demo.%(m)s;
public final class Bridge {
 public static void x(String s){example.Page.LOG.append("%(m)s(").append(s).append(");");}
 public static StringBuilder acc(String s,StringBuilder b){return b.append("%(m)s(").append(s).append(");");}
 public static void y(example.Page p,String s){example.Page.LOG.append("%(m)s(").append(s).append(");");}
}
'''

def run(args,env=None):
    return subprocess.run([str(a) for a in args],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,env=env)
def must(args,env=None):
    p=run(args,env)
    if p.returncode:raise SystemExit(f'Fixture command failed: {args}\n{p.stdout}')
    return p

class Fixture:
    def __init__(self,tmp,core):
        self.t=tmp;self.root=tmp/'bml';self.loader=self.root/'loader';self.mods=self.root/'mods'
        shutil.copytree(core/'loader',self.loader,ignore=shutil.ignore_patterns('mod-runtime','tests','jvmti'))
        (self.root/'config').mkdir(parents=True);(self.root/'config/portable').write_text('')
        if not (self.root/'config/portable').exists():raise SystemExit('No config/portable: refusing to run')
        self.env={k:v for k,v in os.environ.items() if not k.startswith('BML_')}
        self.env.update(LOCALAPPDATA=str(tmp/'peruser'),XDG_CONFIG_HOME=str(tmp/'peruser'),BML_PREPARE_FRESH='1')
        self.bin=tmp/'bitwig/bin';self.bin.mkdir(parents=True)
        jre=tmp/'bitwig/jre/bin';jre.mkdir(parents=True)
        java=Path(os.environ['JAVA_HOME'])/'bin'/('java.exe' if os.name=='nt' else 'java') if 'JAVA_HOME' in os.environ else Path(shutil.which('java'))
        shutil.copytree(java.parent.parent,tmp/'bitwig/jre',dirs_exist_ok=True,ignore=shutil.ignore_patterns('jmods','include','legal','demo','man','lib/src.zip','src.zip'))
        src=tmp/'src/example';src.mkdir(parents=True);(src/'Page.java').write_text(PAGE)
        must(['javac','--release','21','-d',tmp/'classes',src/'Page.java'])
        must(['jar','cf',self.bin/'bitwig.jar','-C',tmp/'classes','.'])
        (self.loader/'compatibility').mkdir(exist_ok=True)
        (self.loader/'compatibility/builds.json').write_text(json.dumps({'format_version':2,'builds':[{
            'build_key':'windows-6.1.3','bitwig_version':'6.1.3','platform':'windows','architecture':'x64',
            'bitwig_jar_sha256':hashlib.sha256((self.bin/'bitwig.jar').read_bytes()).hexdigest()}]}))
        (tmp/'main').mkdir();(tmp/'main/Main.java').write_text(MAIN)
        must(['javac','--release','21','-cp',tmp/'classes','-d',tmp/'driver',tmp/'main/Main.java'])
        self.jars={}
    def helper(self,mod):
        if mod not in self.jars:
            src=self.t/'bridge-src'/mod;src.mkdir(parents=True);(src/'Bridge.java').write_text(BRIDGE%{'m':mod})
            out=self.t/'bridge-classes'/mod
            must(['javac','--release','21','-cp',self.t/'classes','-d',out,src/'Bridge.java'])
            self.jars[mod]=self.t/'bridge-jars'/(mod+'.jar');self.jars[mod].parent.mkdir(exist_ok=True)
            must(['jar','cf',self.jars[mod],'-C',out,'.'])
        return self.jars[mod]
    def prepare(self,method,call,mods):
        """mods: {mod id: [occurrence, ...]} replacing `call` in Page.<method>; returns the process."""
        if self.mods.exists():shutil.rmtree(self.mods)
        opcode,name,desc,bridge,bridge_desc=CALLS[call]
        ret='()Ljava/lang/StringBuilder;' if method=='chain' else '()V'
        for mod,occurrences in mods.items():
            folder=self.mods/mod;folder.mkdir(parents=True)
            shutil.copy2(self.helper(mod),folder/'helper.jar')
            (folder/'mod.json').write_text(json.dumps({'manifest_version':2,
                'mod':{'id':mod,'name':mod,'version':'1.0.0','author':'BML','description':'Occurrence fixture'},
                'built_against':{'bitwig_version':'6.1.3','platform':'windows','architecture':'x64'},
                'helper':{'jar':'helper.jar','class':f'demo.{mod}.Bridge'},
                'targets':{'method':{'kind':'method','owner':'example/Page','name':method,'descriptor':ret},
                           'call':{'kind':'method','owner':'example/Page','name':name,'descriptor':desc}},
                'hooks':[{'id':f'hook{i}','target':'method','transform':'REPLACE_INVOKE',
                          'match':{'opcode':opcode,'target':'call','occurrence':o},
                          'bridge':{'method':bridge,'descriptor':bridge_desc,'args':[]}} for i,o in enumerate(occurrences,1)],
                'trace_targets':[]}))
        return run(['java','-cp',self.loader/'app/bml-control.jar','dev.bitwigre.bml.JavaPreparer','--loader-root',self.loader,
                    '--bitwig-bin',self.bin,'--mods-root',self.mods],self.env)
    def execute(self,method):
        rt=self.loader/'mod-runtime'
        cp=os.pathsep.join(str(p) for p in (rt/'deep-verify-replacements.jar',rt/'helper.jar',self.bin/'bitwig.jar',self.t/'driver'))
        return must(['java','-Xverify:all','-cp',cp,'Main',method]).stdout.strip()
    def applied(self):
        return (self.loader/'mod-runtime/replacement-map.tsv').exists()

failures=[]
def report(name,ok,detail):
    print(('PASS' if ok else 'FAIL')+': '+name+'\n      '+detail)
    if not ok:failures.append(name)
def works(f,name,method,call,mods,expect):
    p=f.prepare(method,call,mods)
    if p.returncode or 'PREPARE_OK' not in p.stdout:
        report(name,False,f'expected {expect}; preparation refused: {last_lines(p.stdout)}');return
    got=f.execute(method)
    report(name,got==expect,f'expected {expect}  got {got}')
def refused(f,name,method,call,mods,*needles):
    p=f.prepare(method,call,mods)
    if not p.returncode:
        report(name,False,f'expected a refusal; prepared and ran: {f.execute(method)}');return
    missing=[n for n in needles if n not in p.stdout]
    if f.applied():missing.append('(replacement-map.tsv was written)')
    report(name,not missing,'refused: '+last_lines(p.stdout)+(f'  MISSING {missing}' if missing else ''))
def last_lines(text):
    lines=[l for l in text.splitlines() if l.strip()]
    keep=[l for l in lines if 'PREPARE_FAILED' in l or 'match not found' in l]
    return ' | '.join(keep or lines[-1:])[:420]

def main():
    core=Path(sys.argv[1]) if len(sys.argv)>1 else Path(__file__).resolve().parents[2]
    if not (core/'loader/app/bml-control.jar').is_file():
        raise SystemExit(f'No built loader in {core}: run from an unpacked core ZIP or name one')
    print('Loader under test:',core)
    with tempfile.TemporaryDirectory(prefix='bml-occurrence-') as d:
        f=Fixture(Path(d),core)
        # Two mods, different calls.
        works(f,'three calls, first mod occurrence 1, second mod occurrence 2','three','x',{'first':[1],'second':[2]},'first(one);second(two);three;')
        works(f,'three calls, first mod occurrence 2, second mod occurrence 1','three','x',{'first':[2],'second':[1]},'second(one);first(two);three;')
        works(f,'three calls, first mod occurrence 1, second mod occurrence 3','three','x',{'first':[1],'second':[3]},'first(one);two;second(three);')
        works(f,'two calls, first mod occurrence 1, second mod occurrence 2','two','x',{'first':[1],'second':[2]},'first(one);second(two);')
        works(f,'two calls, first mod occurrence 2, second mod occurrence 1','two','x',{'first':[2],'second':[1]},'second(one);first(two);')
        works(f,'instance calls, first mod occurrence 1, second mod occurrence 2','virt','y',{'first':[1],'second':[2]},'first(one);second(two);three;')
        # Two mods, one physical call.
        works(f,'one static accumulator call from two mods chains','chain','acc',{'first':[2],'second':[2]},'one;first(two);second(two);three;')
        works(f,'one call reached by different numbers (occurrences 1,1 and 2) chains','chain','acc',{'first':[1,1],'second':[2]},'first(one);first(two);second(two);three;')
        works(f,'a chain after another mod\'s earlier call (1 / 2 / 2)','chain','acc',{'first':[1],'second':[2],'third':[2]},'first(one);second(two);third(two);three;')
        refused(f,'one void static call from two mods is refused','three','x',{'first':[2],'second':[2]},'Unsafe REPLACE_INVOKE overlap','first::hook1','second::hook1','three')
        refused(f,'one instance call from two mods is refused','virt','y',{'first':[2],'second':[2]},'Unsafe REPLACE_INVOKE overlap','first::hook1','second::hook1','virt')
        refused(f,'one instance call reached by different numbers (1,1 and 2) is refused','virt','y',{'first':[1,1],'second':[2]},'Unsafe REPLACE_INVOKE overlap','first::hook2','second::hook1','virt')
        refused(f,'a chain split by another hook is refused (2 and 1,1)','chain','acc',{'first':[2],'second':[1,1]},'hooks must be contiguous','first::hook1','second::hook2','chain')
        # One mod: occurrence counts the calls left after the mod's own earlier hooks.
        works(f,'one mod, occurrence 1 twice, takes two separate calls','three','x',{'first':[1,1]},'first(one);first(two);three;')
        works(f,'one mod, occurrences 2,1,1 take all three calls','three','x',{'first':[2,1,1]},'first(one);first(two);first(three);')
        works(f,'one mod, occurrences 1,1 beside another mod\'s third call','three','x',{'first':[1,1],'second':[3]},'first(one);first(two);second(three);')
        # Numbers that don't exist.
        refused(f,'occurrence 4 of three calls is refused','three','x',{'first':[4]},'first::hook1','match not found')
        refused(f,'second mod occurrence 3 of two calls is refused','two','x',{'first':[1],'second':[3]},'second::hook1','match not found')
        refused(f,'one mod, occurrence 1 three times on two calls is refused','two','x',{'first':[1,1,1]},'first::hook3','match not found')
    print()
    if failures:
        print(f'CROSS_MOD_OCCURRENCE_SMOKE_FAILED {len(failures)} of the cases above')
        sys.exit(1)
    print('CROSS_MOD_OCCURRENCE_SMOKE_OK')

if __name__=='__main__':main()
