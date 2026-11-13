#!/usr/bin/env python3
"""Fail-closed guard for isolated multi-version BML selective v2 tests.

--check: verify installed Bitwig and fixed inputs.
--prepare: run offline BML prep only, inspect native-link/trace reports.
--ready: refuse launch until offline receipt passes with exact installed hash.
Never starts Bitwig; real application start is separate user action.
"""
from __future__ import annotations
import argparse,hashlib,json,os,subprocess,sys,time
from pathlib import Path

HERE=Path(__file__).resolve().parent
def sha(p):
 h=hashlib.sha256()
 with Path(p).open("rb") as f:
  for b in iter(lambda:f.read(1<<20),b""):h.update(b)
 return h.hexdigest()
def main():
 ap=argparse.ArgumentParser(description=__doc__)
 g=ap.add_mutually_exclusive_group(required=True)
 for mode in ("check","prepare","ready"):g.add_argument("--"+mode,action="store_true")
 ap.add_argument("--bitwig-bin",type=Path,default=Path(os.environ.get("BITWIG_BIN",str(
    Path(os.environ.get("ProgramFiles",r"C:\Program Files"))/"Bitwig Studio"/"bin"))))
 args=ap.parse_args()
 if args.prepare and not (HERE/"config"/"portable").exists():
  # Without the marker, preparing would write the user's real per-user settings folder.
  print("REFUSED: this install has no config/portable marker; a test install's preparation must not touch the per-user settings",file=sys.stderr);return 3
 try:
  state=json.loads((HERE/"TRIAL-SELECTIVE-SETUP.json").read_text("utf8"))
  if state["kind"]!="BML_SELECTIVE_MULTI_VERSION_V2_TRIAL":raise ValueError("Untrusted trial identity")
  moddirs=sorted(d for d in (HERE/"mods").iterdir() if d.is_dir() and (d/"mod.json").is_file())
  if len(moddirs)!=state["mod_count"] or {d.name for d in moddirs}!=set(state["mod_binaries"]):
   raise ValueError("Enabled mod set differs from frozen suite")
  for d in moddirs:
   files=state["mod_binaries"][d.name]
   for name,key in (("mod.json","mod_json_sha256"),("helper.jar","helper_jar_sha256")):
    if key not in files:
     if (d/name).exists():raise ValueError("Unexpected "+d.name+"/"+name)
     continue
    if sha(d/name)!=files[key]:raise ValueError("Changed mod package "+d.name+"/"+name)
  meta=json.loads((HERE/"maps"/state["map_id"]/"map.json").read_text("utf8"))
  if meta.get("format")!="bml-map-pack-v2" or meta["target_jar_sha256"]!=state["target_sha256"] or meta["version"]!=state["map_version"]:
   raise ValueError("Wrong v2 remap target identity")
  for name,digest in meta["file_sha256"].items():
   if sha(HERE/"maps"/state["map_id"]/name)!=digest:raise ValueError("Map SHA mismatch "+name)
  if sha(HERE/"loader"/"app"/"bml-control.jar")!=state["control_sha256"]:
   raise ValueError("Unapproved BML control binary change")
  bin=args.bitwig_bin.resolve(strict=True)
  jar=bin/"bitwig.jar"
  if not jar.is_file():raise FileNotFoundError(jar)
  actual=sha(jar)
  if actual!=state["target_sha256"]:raise ValueError("Installed Bitwig.jar differs from "+state["version"]+" expected="+state["target_sha256"]+" actual="+actual)
  if not (HERE/"loader"/"prepare-mod.cmd").is_file():raise ValueError("No BML offline preparer")
  print("STOCK_HASH_OK",state["version"],actual,"MODS",len(moddirs),flush=True)
 except Exception as exc:
  print("CHECK_BLOCKED",str(exc),file=sys.stderr,flush=True)
  return 2
 if args.check:
  print("CHECK_PASS - no Bitwig launched",flush=True);return 0
 out=HERE/"OFFLINE-SELECTIVE-SUMMARY.json"
 if args.ready:
  if not out.is_file():print("OFFLINE_PREP_REQUIRED",file=sys.stderr);return 3
  x=json.loads(out.read_text("utf8"))
  if not (x.get("status")=="OFFLINE_PASS" and x.get("map_version")==state["map_version"] and
      x.get("installed_sha256")==actual and x.get("mods")==len(moddirs) and
      x.get("unresolved_helper_links")==0 and x.get("invalid_trace_targets")==0 and
      x.get("unmapped_targets")==0 and x.get("trace_report_present") and x.get("link_report_present") and x.get("target_report_present")):
   print("OFFLINE_READY_BLOCKED: receipt incomplete or failed",file=sys.stderr);return 3
  print("READY_FOR_HUMAN_GUI_TEST",state["version"],len(moddirs),"mods",flush=True);return 0
 env=dict(os.environ);env["BML_TRACE_ENABLED"]="1"
 # Prepare the ticked mods only, as the BML window's Load does (state/enabled-mods.properties).
 ticks=HERE/"state"/"enabled-mods.properties"
 if ticks.is_file():
  chosen=[l.split("=",1)[0][4:] for l in ticks.read_text("utf8").splitlines() if l.startswith("mod.") and l.split("=",1)[-1].strip()=="true"]
  sel=HERE/"state"/"guard-enabled-mods.txt";sel.write_text("".join(c+"\n" for c in chosen),"utf8")
  env["BML_MOD_SELECTION_ACTIVE"]="1";env["BML_ENABLED_MODS_FILE"]=str(sel)
 run=[str(HERE/"loader"/"prepare-mod.cmd"),str(bin),str(HERE/"mods")]
 started=time.monotonic()
 try:
  proc=subprocess.run(run,cwd=HERE,shell=True,env=env,timeout=480,
      capture_output=True,text=True,errors="replace")
  exitcode=proc.returncode;log=proc.stdout+"\n"+proc.stderr
 except subprocess.TimeoutExpired as ex:
  exitcode=-124
  values=[ex.stdout or b"",ex.stderr or b""]
  log="\n".join(v.decode("utf8","replace") if isinstance(v,bytes) else v for v in values)
 (HERE/"OFFLINE-SELECTIVE-PREP.log").write_text(log,"utf8")
 rt=HERE/"loader"/"mod-runtime"
 def rows(name):
  p=rt/name
  return (p.is_file(),p.read_text("utf8",errors="replace").splitlines()[1:] if p.is_file() else [])
 linked,link=rows("helper-linkage-preflight.tsv")
 traced,trace=rows("helper-trace-preflight.tsv")
 targets,target=rows("target-resolution.tsv")
 unresolved=[x for x in link if x.strip()]
 invalid=[x for x in trace if x.strip()]
 missing=[x for x in target if "UNMAPPED" in x.upper()]
 ok=(exitcode==0 and "PREPARE_OK" in log and linked and traced and targets and
    not unresolved and not invalid and not missing)
 receipt={
  "status":"OFFLINE_PASS" if ok else "OFFLINE_FAILED",
  "map_version":state["map_version"],"version":state["version"],
  "installed_sha256":actual,"mods":len(moddirs),
  "exit_code":exitcode,"prepare_ok":"PREPARE_OK" in log,
  "link_report_present":linked,"trace_report_present":traced,
  "target_report_present":targets,
  "unresolved_helper_links":len(unresolved),"invalid_trace_targets":len(invalid),
  "unmapped_targets":len(missing),
  "added_fields":sum("\tADD_FIELD\t" in x for x in rows("composition-report.tsv")[1]),
  "samples":{"links":unresolved[:12],"trace":invalid[:12],"targets":missing[:12]},
  "preparer_signal":[x for x in log.splitlines() if "PREPARE_OK" in x or "PREPARE_FAILED" in x][-5:],
  "seconds":round(time.monotonic()-started,1),
  "bitwig_launched":False
 }
 out.write_text(json.dumps(receipt,indent=2)+"\n","utf8")
 print("OFFLINE_RESULT",json.dumps(receipt),flush=True)
 return 0 if ok else 1
if __name__=="__main__":sys.exit(main())
