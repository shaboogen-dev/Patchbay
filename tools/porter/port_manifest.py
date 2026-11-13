#!/usr/bin/env python3
"""Port a hand-written mod.json between Bitwig versions with a BML remap (the manifest half of SourcePorter).

  python tools/porter/port_manifest.py <mod.json> <remap.tsv.gz> <to-version> [--invert] [--out FILE]

Renames every Bitwig class, field and method in targets, and every Bitwig class in descriptors
(targets, hook bridges, raw invoke matches, trace targets), then sets built_against.bitwig_version.
Local IDs, hook IDs and everything else stay as they are. Formatting follows json.dumps(indent=2), the
style of every mod.json in the repository. A target the remap doesn't know stops the port; nothing is
guessed.
"""
from __future__ import annotations
import argparse, gzip, json, re, sys
from pathlib import Path


def load(remap: Path, invert: bool):
    classes, members = {}, {}
    with gzip.open(remap, "rt", encoding="utf8") as f:
        for line in f:
            c = line.rstrip("\n").split("\t")
            if c[0] == "C":
                a, b = (c[2], c[1]) if invert else (c[1], c[2])
                classes[a] = b
            elif c[0] in ("F", "M"):
                x, y = (c[1], c[2], c[3]), (c[4], c[5], c[6])
                a, b = (y, x) if invert else (x, y)
                members[(c[0], *a)] = b
    return classes, members


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("manifest", type=Path)
    ap.add_argument("remap", type=Path)
    ap.add_argument("to_version")
    ap.add_argument("--invert", action="store_true")
    ap.add_argument("--out", type=Path)
    a = ap.parse_args()
    classes, members = load(a.remap, a.invert)
    m = json.loads(a.manifest.read_text("utf-8-sig"))
    problems: list[str] = []

    def desc(d: str) -> str:
        return re.sub(r"L([^;]+);", lambda x: "L" + classes.get(x.group(1), x.group(1)) + ";", d)

    targets = m.get("targets", {})
    for tid, t in targets.items():
        if t["kind"] == "class":
            if t["class"] not in classes:
                problems.append(f"target {tid}: class {t['class']} not in the remap")
            else:
                t["class"] = classes[t["class"]]
            continue
        key = ("F" if t["kind"] == "field" else "M", t["owner"], t["name"], t["descriptor"])
        if key not in members:
            problems.append(f"target {tid}: {t['owner']}.{t['name']}{t['descriptor']} not in the remap")
            continue
        t["owner"], t["name"], t["descriptor"] = members[key]
    for h in m.get("hooks", []):
        b = h.get("bridge", {})
        if "descriptor" in b:
            b["descriptor"] = desc(b["descriptor"])
        mt = h.get("match", {})
        if "owner" in mt:  # raw match: a deliberately fixed non-Bitwig call; only its descriptor can name Bitwig classes
            if mt["owner"] in classes:
                problems.append(f"hook {h['id']}: raw match on Bitwig class {mt['owner']}; declare it as a target")
            mt["descriptor"] = desc(mt["descriptor"])
        meth = h.get("method", {})
        if "descriptor" in meth:
            meth["descriptor"] = desc(meth["descriptor"])
    for t in m.get("trace_targets", []):
        if "descriptor" in t:
            t["descriptor"] = desc(t["descriptor"])
    if problems:
        print("\n".join("PORT_PROBLEM " + p for p in problems), file=sys.stderr)
        return 1
    m["built_against"]["bitwig_version"] = a.to_version
    out = a.out or a.manifest
    out.write_bytes((json.dumps(m, indent=2, ensure_ascii=False) + "\n").encode())
    print(f"ported {a.manifest} -> {out}: {len(targets)} targets, {len(m.get('hooks', []))} hooks, built_against {a.to_version}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
