#!/usr/bin/env python3
"""Compare two map packs for the same source -> target builds, row by row.

  python BML/tools/map_validator/compare.py <a.zip> <b.zip> [--show N]

For C rows and declared F/M rows (alias rows are derived, so they're left out): how many agree,
disagree (same source, different target), or exist in only one pack. With --show, prints N
examples of each kind of difference.
"""
from __future__ import annotations
import argparse, collections, gzip, zipfile
from pathlib import Path


def load(p: Path):
    with zipfile.ZipFile(p) as z:
        remap = next(n for n in z.namelist() if "/remap/" in n and n.endswith(".tsv.gz"))
        rows = [r.split("\t") for r in gzip.decompress(z.read(remap)).decode("utf8").splitlines() if r]
    out = {}
    for r in rows:
        if r[0] == "C":
            out[("C", r[1])] = r[2]
        elif r[0] in "FM":
            out[(r[0], r[1], r[2], r[3])] = (r[4], r[5], r[6])
    return Path(remap).name, out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("a", type=Path)
    ap.add_argument("b", type=Path)
    ap.add_argument("--show", type=int, default=0)
    x = ap.parse_args()
    na, A = load(x.a)
    nb, B = load(x.b)
    if na != nb:
        raise SystemExit(f"different builds: {na} vs {nb}")
    # alias rows: a member row whose owner differs from the row of the same name elsewhere is hard to tell
    # apart cheaply, so compare everything and report per kind
    stats = collections.defaultdict(collections.Counter)
    ex = collections.defaultdict(list)
    for k in A.keys() | B.keys():
        kind = k[0]
        if k in A and k in B:
            v = "agree" if A[k] == B[k] else "disagree"
        else:
            v = "only_a" if k in A else "only_b"
        stats[kind][v] += 1
        if v != "agree" and len(ex[(kind, v)]) < x.show:
            ex[(kind, v)].append((k, A.get(k), B.get(k)))
    for kind in sorted(stats):
        s = stats[kind]
        both = s["agree"] + s["disagree"]
        print(f"{kind}: agree {s['agree']}  disagree {s['disagree']}  only in a {s['only_a']}  only in b {s['only_b']}"
              f"  (agreement where both map: {s['agree'] / both:.2%})" if both else f"{kind}: {dict(s)}")
    for (kind, v), lst in sorted(ex.items()):
        print(f"-- {kind} {v}")
        for k, va, vb in lst:
            print("  ", "\t".join(k[1:]), "|", va, "|", vb)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
