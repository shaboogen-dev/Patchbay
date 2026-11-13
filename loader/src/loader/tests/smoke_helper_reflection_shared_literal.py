#!/usr/bin/env python3
"""Ambiguous shared reflection names must never be guessed or globally replaced."""
from pathlib import Path
import tempfile,subprocess,zipfile
ROOT=Path(__file__).resolve().parents[2]
def run(*cmd):
 p=subprocess.run([str(i) for i in cmd],capture_output=True,text=True)
 assert p.returncode==0,(cmd,p.stdout,p.stderr)
 return p.stdout
with tempfile.TemporaryDirectory() as d:
 t=Path(d)
 (t/'old').mkdir()
 (t/'old/One.java').write_text('package old; public class One { public void oldName(){} }')
 (t/'old/Two.java').write_text('package old; public class Two { public void oldName(){} }')
 (t/'Probe.java').write_text('public class Probe { public static void check() throws Exception { Class.forName("old.One").getMethod("oldName"); Class.forName("old.Two").getMethod("oldName"); } }')
 run('javac','-d',t/'classes',t/'old/One.java',t/'old/Two.java',t/'Probe.java')
 with zipfile.ZipFile(t/'helper.jar','w') as z:z.write(t/'classes/Probe.class','Probe.class')
 (t/'map.tsv').write_text('C\told/One\tnew/One\nC\told/Two\tnew/Two\nM\told/One\toldName\t()V\tnew/One\tmethodA\t()V\nM\told/Two\toldName\t()V\tnew/Two\tmethodB\t()V\n')
 text=run('java','-jar',ROOT/'loader/tools/bitwig-helper-remapper.jar',t/'helper.jar',t/'translated.jar',t/'map.tsv',t/'overrides.tsv',t/'report.txt')
 assert 'runtime_reflection_bridge_calls=4' in text,text
 assert 'reflection_preflight_status=BRIDGE_INSTALLED' in text,text
 assert 'warnings=0' in text,text
 asm=run('javap','-c','-p','-classpath',t/'translated.jar','Probe')
 assert 'String oldName' in asm and 'String methodA' not in asm and 'String methodB' not in asm,asm
 assert 'String old.One' in asm and 'String old.Two' in asm and '.getMethod:' in asm,asm
 print('PASS: shared oldName unchanged; BML runtime bridge owns owner-aware resolution')
