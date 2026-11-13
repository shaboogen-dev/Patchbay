#!/usr/bin/env python3
"""Check a BML map pack against the Bitwig builds it claims to connect.

  python BML/tools/map_validator/validate.py <pack.zip> [--out DIR]

Checks each remap in the pack (one per source build, C/H/F/M rows) on its own: reads the source
and target bitwig.jar and library jars from bitwig/jars/ (builds from the pack's builds.json;
Windows <version>/, macOS <version>-mac/, Linux <version>-linux/)
and checks every row against the bytecode itself. It needs no mapper: only BML's class-file reader
(tools/jvm/) and the workspace's Bitwig jars, both found through tools/workspace.py.

Hard checks (a failure means the row is wrong, or inconsistent with another row):
  C  exists, one-to-one, same kind (interface / enum / annotation), mapped superclass and
     interfaces equal the target's (extra target interfaces with no source counterpart at all are
     reported as real changes, not failures)
  H  equals the source jar's real inheritance edges
  F/M  source and target member exist (declared, or inherited through the owner's hierarchy),
     owner maps to owner, descriptor maps to descriptor, same static-ness, declared targets
     one-to-one, an inherited (alias) row agrees with the row of the member it resolves to,
     members declared outside bitwig.jar (JDK, libs) keep their name, an override maps to the
     same name as the method it overrides
Evidence (how well the bytecode agrees, per row; 0..1, or none when there is nothing to compare):
  body   for a method with code on both sides: overlap of its mapped references, string and
         number constants with the target method's
  usage  for any member: of the mapped methods whose source uses it, the share whose target uses
         the mapped member

Writes DIR/<source>__<target>/report.json (counts) and rows.tsv.gz (every hard failure and every row
with weak evidence) per remap. Prints a summary. Exit code 1 if any hard check fails.
"""
from __future__ import annotations
import argparse, collections, gzip, io, json, re, sys, time, zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import workspace  # noqa: E402
sys.path.insert(0, str(workspace.BML / "tools" / "jvm"))
import classfile as cf

DESC_CLASS = re.compile(r"L([^;]+);")
WEAK = 0.5  # evidence below this is reported as weak


def remaps(path: Path) -> list[str]:
    """The pack's remap files, one per source version, in name order."""
    with zipfile.ZipFile(path) as z:
        return sorted(n for n in z.namelist() if "/remap/" in n and n.endswith(".tsv.gz"))


def load_pack(path: Path, remap: str | None = None):
    """One remap of the pack (default: the first by name) with its source and target versions."""
    remap = remap or remaps(path)[0]
    with zipfile.ZipFile(path) as z:
        builds = json.loads(z.read(next(n for n in z.namelist() if n.endswith("builds.json"))))
        meta = json.loads(z.read(next(n for n in z.namelist() if n.endswith("map.json"))))
        rows = gzip.decompress(z.read(remap)).decode("utf8").splitlines()
    src_key, dst_key = Path(remap).name[:-len(".tsv.gz")].split("__")
    build = {b["build_key"]: workspace.build_name(b["platform"], b["bitwig_version"]) for b in builds["builds"]}
    return meta, build[src_key], build[dst_key], [r.split("\t") for r in rows if r and not r.startswith("#")]


def read_build(build: str):
    """build: "<version>" (Windows) or "<platform>-<version>", as workspace.build_name gives it."""
    bitwig, *libs = workspace.build_jars(build)
    own = cf.read_jar(bitwig)
    ext = {k: v for jar in libs for k, v in cf.read_jar(jar).items()}
    return own, {**ext, **own}


class Side:
    """One build: class lookup and member resolution through the hierarchy."""

    def __init__(self, own, every):
        self.own, self.all = own, every

    def parents(self, name):
        c = self.all.get(name)
        if not c:
            return []
        return ([c.super] if c.super else []) + list(c.interfaces)

    def ancestors(self, name):
        out, todo = set(), self.parents(name)
        while todo:
            p = todo.pop()
            if p not in out:
                out.add(p); todo += self.parents(p)
        return out

    def resolve(self, kind, owner, name, desc):
        """Class that declares the member, in JVM resolution order: the class and its superclasses,
        then all superinterfaces. A JDK ancestor (not in the jars) is assumed to declare it only when
        no class in the jars does."""
        table = "fields" if kind == "F" else "methods"
        chain, o = [], owner
        while o and o not in chain:
            chain.append(o)
            c = self.all.get(o)
            if c is None:
                break
            if (name, desc) in getattr(c, table):
                return o
            o = c.super
        seen, todo, jdk, found = set(), list(chain), None, []
        while todo:
            o = todo.pop(0)
            c = self.all.get(o)
            if c is None:
                if jdk is None and o.startswith(("java/", "javax/", "jdk/", "sun/")):
                    jdk = o
                continue
            for i in c.interfaces:
                if i in seen:
                    continue
                seen.add(i)
                ic = self.all.get(i)
                if ic is not None and (name, desc) in getattr(ic, table):
                    found.append(i)
                todo.append(i)
        if not found:
            return jdk
        # JVM rule: the maximally specific declarations (not a superinterface of another candidate);
        # among several, a default method before an abstract one, then by name for determinism
        specific = [i for i in found if not any(i != j and i in self.ancestors(j) for j in found)]
        return min(specific, key=lambda i: (not getattr(self.all[i], table)[(name, desc)].code_len
                                            if table == "methods" else 0, i))

    def flags(self, kind, owner, name, desc):
        c = self.all.get(owner)
        if c is None:
            return None
        return c.fields.get((name, desc)) if kind == "F" else (c.methods[(name, desc)].flags if (name, desc) in c.methods else None)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pack", type=Path)
    ap.add_argument("--out", type=Path, help="report folder (default: next to nothing, print only)")
    a = ap.parse_args()
    hard = 0
    for remap in remaps(a.pack):
        hard += check(a.pack, remap, a.out / Path(remap).name[:-len(".tsv.gz")] if a.out else None)
    print("MAP_OK" if not hard else f"MAP_FAILURES {hard}")
    return 0 if not hard else 1


def check(pack: Path, remap: str, out: Path | None) -> int:
    """Validate one remap; returns its number of hard failures."""
    t0 = time.time()
    meta, sv, tv, rows = load_pack(pack, remap)
    print(f"{pack.name}: {sv} -> {tv}, {len(rows)} rows", flush=True)
    S, T = Side(*read_build(sv)), Side(*read_build(tv))
    print(f"read jars ({time.time() - t0:.0f}s)", flush=True)

    fails = collections.defaultdict(list)   # check -> [row text]
    counts = collections.Counter()

    def fail(check, row, why=""):
        fails[check].append("\t".join(row) + (f"\t# {why}" if why else ""))

    C = {}
    seen_t = {}
    H = set()
    members = {}   # ("M"|"F", owner, name, desc) -> (owner, name, desc)
    for r in rows:
        if r[0] == "C":
            counts["C"] += 1
            if r[1] in C:
                fail("C.duplicate-source", r)
            C[r[1]] = r[2]
            if r[2] in seen_t:
                fail("C.one-to-one", r, f"also target of {seen_t[r[2]]}")
            seen_t[r[2]] = r[1]
        elif r[0] == "H":
            counts["H"] += 1
            H.add((r[1], r[2]))
        else:
            counts[r[0]] += 1
            k = (r[0], r[1], r[2], r[3])
            if k in members and members[k] != (r[4], r[5], r[6]):
                fail("FM.conflict", r)
            members[k] = (r[4], r[5], r[6])

    def cmap(name):
        """Target name of a source class; None for an obfuscated class the pack does not map."""
        if name in C:
            return C[name]
        return None if name in S.own else name

    def dmap(desc):
        out, ok = [], True
        def sub(m):
            nonlocal ok
            t = cmap(m.group(1))
            if t is None:
                ok = False
                return m.group(0)
            return f"L{t};"
        res = DESC_CLASS.sub(sub, desc)
        return res if ok else None

    # ---- classes
    mapped_targets = set(C.values())
    notes = []   # real differences between the builds, reported but not failures
    KIND = cf.ACC_INTERFACE | cf.ACC_ENUM | cf.ACC_ANNOTATION
    for s, t in C.items():
        sc, tc = S.own.get(s), T.own.get(t)
        if sc is None or tc is None:
            fail("C.exists", ("C", s, t), "source missing" if sc is None else "target missing"); continue
        if sc.flags & KIND != tc.flags & KIND:
            fail("C.kind", ("C", s, t), f"{sc.flags & KIND:#x} vs {tc.flags & KIND:#x}")
        if sc.super:
            ms = cmap(sc.super)
            if ms is None:
                counts["C.super-unknown"] += 1
            elif ms != tc.super:
                fail("C.superclass", ("C", s, t), f"{sc.super}->{ms} but target extends {tc.super}")
        mi = [cmap(i) for i in sc.interfaces]
        if None in mi:
            counts["C.interfaces-unknown"] += 1
        elif set(mi) != set(tc.interfaces):
            missing, extra = set(mi) - set(tc.interfaces), set(tc.interfaces) - set(mi)
            s_anc = {cmap(a) for a in S.ancestors(s)}
            # an interface that is only inherited on the other side (Bitwig moved it to a supertype)
            moved = {x for x in missing if x in T.ancestors(t)} | {x for x in extra if x in s_anc}
            new = {x for x in extra - moved if x in T.own and x not in mapped_targets}
            if (missing | extra) - moved - new:
                fail("C.interfaces", ("C", s, t), f"{sorted(mi)} vs {sorted(tc.interfaces)}")
            else:
                # a real change between the builds, not a wrong match: subtyping is preserved, or the
                # target implements interfaces that have no counterpart in the source at all
                counts["C.interfaces-changed"] += 1
                notes.append(f"NOTE C.interfaces-changed\tC\t{s}\t{t}\t# inherited on one side only {sorted(moved)}, no source counterpart {sorted(new)}")
    real_h = {(c.name, p) for c in S.own.values() for p in ([c.super] if c.super else []) + c.interfaces}
    for e in sorted(H - real_h):
        fail("H.not-in-source", ("H",) + e)
    for e in sorted(real_h - H):
        fail("H.missing", ("H",) + e)

    # ---- members
    declared_t = {}
    resolved = {}  # row key -> (src declaring class, tgt declaring class)
    for k, (to, tn, td) in members.items():
        kind, so, sn, sd = k
        row = (kind, so, sn, sd, to, tn, td)
        sdecl = S.resolve(kind, so, sn, sd)
        tdecl = T.resolve(kind, to, tn, td)
        if sdecl is None:
            fail(f"{kind}.source-missing", row); continue
        if tdecl is None:
            fail(f"{kind}.target-missing", row); continue
        resolved[k] = (sdecl, tdecl)
        mo = cmap(so)
        if mo is None:
            counts[f"{kind}.owner-unknown"] += 1
        elif mo != to:
            fail(f"{kind}.owner", row, f"{so} maps to {mo}")
        md = dmap(sd)
        if md is None:
            counts[f"{kind}.descriptor-unknown"] += 1
        elif md != td:
            fail(f"{kind}.descriptor", row, f"maps to {md}")
        if sdecl not in S.own:  # declared in JDK / libs: the name can't change
            if (tn, td) != (sn, md or td):
                fail(f"{kind}.external-renamed", row, f"declared in {sdecl}")
            continue
        sf, tf = S.flags(kind, sdecl, sn, sd), T.flags(kind, tdecl, tn, td)
        if sf is not None and tf is not None and (sf ^ tf) & cf.ACC_STATIC:
            fail(f"{kind}.static", row)
        if sdecl == so:
            if tdecl != to:
                fail(f"{kind}.declared-moved", row, f"target is inherited from {tdecl}")
            prev = declared_t.get((kind, to, tn, td))
            if prev:
                fail(f"{kind}.one-to-one", row, f"also target of {prev}")
            declared_t[(kind, to, tn, td)] = f"{so}.{sn}{sd}"
            counts[f"{kind}.declared"] += 1
        else:
            counts[f"{kind}.alias"] += 1
            base = members.get((kind, sdecl, sn, sd))
            # the target may re-declare the member lower in the hierarchy (an override or an interface
            # re-declaring it); that's the same member as long as it overrides the mapped declaration
            above = T.ancestors(to) | {to}
            if base and base != (tdecl, tn, td) and not (
                    kind == "M" and base[1:] == (tn, td) and base[0] in above and tdecl in above):
                fail(f"{kind}.alias-disagrees", row, f"declaring row maps to {base}")

    # overrides: a method that overrides a mapped ancestor method must get the same target name
    for k, (to, tn, td) in members.items():
        if k[0] != "M" or k not in resolved or resolved[k][0] != k[1] or k[2] in ("<init>", "<clinit>"):
            continue
        _, so, sn, sd = k
        sc = S.own[so]
        if sc.methods[(sn, sd)].flags & (cf.ACC_STATIC | 0x0002):  # static / private don't override
            continue
        for p in S.parents(so):
            anc = S.resolve("M", p, sn, sd)
            if anc is None:
                continue
            if anc not in S.own:
                break  # external ancestor: covered by external-renamed
            af = S.own[anc].methods[(sn, sd)].flags
            if af & (cf.ACC_STATIC | 0x0002):
                break  # a static or private method is not overridden
            if not af & (0x0001 | 0x0004) and anc.rpartition("/")[0] != so.rpartition("/")[0]:
                break  # package-private methods are only overridden inside their package
            base = members.get(("M", anc, sn, sd))
            if base and base[1] != tn:
                fail("M.override-renamed", ("M", so, sn, sd, to, tn, td), f"overrides {anc}.{sn} which maps to {base[1]}")
            break

    # ---- evidence
    def mref(ref):
        """Target identity of a source bytecode reference, or None if the pack can't map it."""
        kind, o, n, d = ref
        if (kind, o, n, d) in members:
            return (kind,) + members[(kind, o, n, d)]
        mo, md = cmap(o), dmap(d)
        if mo is None or md is None:
            return None
        decl = S.resolve(kind, o, n, d)
        if decl is not None and decl in S.own:
            return None  # an obfuscated member the pack doesn't map
        return (kind, mo, n, md)

    body = {}
    use_total, use_hit = collections.Counter(), collections.Counter()
    for k, (to, tn, td) in members.items():
        if k[0] != "M" or k not in resolved or resolved[k][0] != k[1]:
            continue
        sm = S.own[k[1]].methods.get((k[2], k[3]))
        tc = T.own.get(to)
        tm = tc.methods.get((tn, td)) if tc else None
        if sm is None or tm is None or not sm.code_len or not tm.code_len:
            continue
        tgt_refs = collections.Counter(tm.calls)
        mapped = collections.Counter()
        unknown = 0
        for ref in sm.calls:
            m = mref(ref)
            if m is None:
                unknown += 1; continue
            mapped[m] += 1
            if ref in members:
                use_total[ref] += 1
                use_hit[ref] += m in tgt_refs
        sc_ = collections.Counter(sm.strings) + collections.Counter(map(str, sm.numbers))
        tc_ = collections.Counter(tm.strings) + collections.Counter(map(str, tm.numbers))
        inter = sum((mapped & tgt_refs).values()) + sum((sc_ & tc_).values())
        union = sum((mapped | tgt_refs).values()) + sum((sc_ | tc_).values())
        if union:
            body[k] = inter / union

    weak = []
    evid = collections.Counter()
    for k, (to, tn, td) in members.items():
        if k not in resolved or resolved[k][0] != k[1] or resolved[k][0] not in S.own:
            continue
        b = body.get(k)
        u = use_hit[k] / use_total[k] if use_total[k] else None
        scores = [x for x in (b, u) if x is not None]
        if not scores:
            evid[f"{k[0]}.no-evidence"] += 1; continue
        best = max(scores)
        evid[f"{k[0]}.{'strong' if best >= 0.8 else 'ok' if best >= WEAK else 'weak'}"] += 1
        if best < WEAK:
            weak.append("\t".join((*k, to, tn, td)) + f"\t# body={'-' if b is None else f'{b:.2f}'} usage={'-' if u is None else f'{u:.2f}'} ({use_total[k]} uses)")

    # ---- coverage of the whole source jar (not just what any mod uses)
    decl_rows = {(k[0], k[1], k[2], k[3]) for k in resolved if resolved[k][0] == k[1]}
    n_f = sum(len(c.fields) for c in S.own.values())
    n_m = sum(len(c.methods) for c in S.own.values())
    n_m_code = sum(1 for c in S.own.values() for m in c.methods.values() if m.code_len)
    coverage = {
        "classes": f"{len(C)}/{len(S.own)} ({len(C) / len(S.own):.1%})",
        "fields": f"{sum(k[0] == 'F' for k in decl_rows)}/{n_f} ({sum(k[0] == 'F' for k in decl_rows) / n_f:.1%})",
        "methods": f"{sum(k[0] == 'M' for k in decl_rows)}/{n_m} ({sum(k[0] == 'M' for k in decl_rows) / n_m:.1%})",
        "methods_with_body_evidence": f"{len(body)}/{n_m_code}",
        "unmapped_target_classes": len(set(T.own) - set(C.values())),
    }

    hard = sum(len(v) for v in fails.values())
    report = {
        "pack": pack.name, "remap": Path(remap).name, "map_id": meta.get("id"), "map_version": meta.get("version"),
        "source": sv, "target": tv, "rows": dict(counts), "coverage": coverage,
        "hard_failures": {k: len(v) for k, v in sorted(fails.items())},
        "evidence": dict(sorted(evid.items())), "seconds": round(time.time() - t0),
    }
    print(json.dumps(report, indent=2))
    if out:
        out.mkdir(parents=True, exist_ok=True)
        (out / "report.json").write_text(json.dumps(report, indent=2) + "\n", "utf8")
        with gzip.open(out / "rows.tsv.gz", "wt", encoding="utf8") as f:
            for check, lines in sorted(fails.items()):
                for line in lines:
                    f.write(f"FAIL {check}\t{line}\n")
            for line in sorted(weak):
                f.write(f"WEAK\t{line}\n")
            for line in notes:
                f.write(line + "\n")
    print(f"{Path(remap).name}: {'OK' if not hard else f'{hard} hard failures'}", flush=True)
    return hard


if __name__ == "__main__":
    sys.exit(main())
