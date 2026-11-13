#!/usr/bin/env python3
"""Verify Debug-only extra native trace profiles without any installed Bitwig or mod."""
from pathlib import Path
import subprocess, tempfile, textwrap, shutil
ROOT=Path(__file__).resolve().parents[2]
PROFILE=ROOT/'development/diagnostics/trace-profiles/projects-in-browser-614.tsv'
JAVA=ROOT/'loader/app/bml-control.jar'
SOURCE=textwrap.dedent('''
package dev.bitwigre.bml;
import java.nio.file.*;import java.util.*;import java.lang.reflect.*;
public final class ExternalTracesSmoke {
 public static void main(String[] args) throws Exception {
  String root=args[0]; boolean trace=Boolean.parseBoolean(args[1]);
  Map<String,String> env=trace?Map.of("BML_TRACE_ENABLED","1"):Map.of();
  JavaPreparer prep=new JavaPreparer(Path.of(root).resolve("loader"),Path.of(root),Path.of(root,"mods"),env);
  Field f=JavaPreparer.class.getDeclaredField("traceRows"); f.setAccessible(true);
  @SuppressWarnings("unchecked") List<String> rows=(List<String>)f.get(prep);
  Method m=JavaPreparer.class.getDeclaredMethod("addExternalDebugTraceTargets",Set.class);m.setAccessible(true);
  m.invoke(prep,new HashSet<String>());
  if(trace&&rows.size()!=14)throw new AssertionError("expected 14 trace targets; got "+rows.size());
  if(!trace&&!rows.isEmpty())throw new AssertionError("trace must not run outside Debug Mode");
  for(String row:rows)if(!row.startsWith("diagnostic.external."))throw new AssertionError(row);
  System.out.println("PASS debug="+trace+" rows="+rows.size());
 }
}
''')
assert PROFILE.is_file() and JAVA.is_file()
with tempfile.TemporaryDirectory(prefix='bml-trace-ext-test-') as td:
 t=Path(td);(t/'loader/diagnostics').mkdir(parents=True)
 shutil.copy(PROFILE,t/'loader/diagnostics/trace-targets.tsv')
 src=t/'ExternalTracesSmoke.java';src.write_text(SOURCE)
 subprocess.run(['javac','--release','21','-cp',str(JAVA),'-d',str(t),str(src)],check=True)
 for mode in ['true','false']:
  p=subprocess.run(['java','-cp',str(t)+':'+str(JAVA),'dev.bitwigre.bml.ExternalTracesSmoke',str(t),mode],check=True,capture_output=True,text=True)
  print(p.stdout.strip())
 p=t/'loader/diagnostics/trace-targets.tsv';p.write_text('bad\ttoo\tfew\n')
 x=subprocess.run(['java','-cp',str(t)+':'+str(JAVA),'dev.bitwigre.bml.ExternalTracesSmoke',str(t),'true'],capture_output=True,text=True)
 assert x.returncode != 0 and 'Invalid trace profile TSV' in x.stderr,x.stderr
 print('PASS malformed profile rejected')
print('PASS external debug targets smoke')
