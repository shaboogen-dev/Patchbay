#!/usr/bin/env python3
"""Build a mod release from its mod folder: BML/frameworks/<id> or Mods/<id> (tools/workspace.py).

  python tools/build_mod.py <mod-id> --check    build in a temp folder and compare with the existing
                                                releases/<id>-<version>.zip; writes nothing
  python tools/build_mod.py <mod-id>            -> <mod folder>/releases/<id>-<version>.zip
  python tools/build_mod.py <mod-id> --replace  rebuild an existing version (prints the comparison first)
  python tools/build_mod.py <mod-id> --out DIR  write the ZIP to DIR instead (for testing a rebuild before it
                                                replaces the release)

Mod layout:
  mod.json     shipped manifest, copied verbatim; mod.version names the release
  build.json   {"release": 21, "release_overrides": {"<path under src>": 17}, "classpath_mods": ["<mod-id>"],
                "debug": true}   (debug: javac -g, for mods whose accepted jar carries local-variable tables)
  src/         *.java is compiled into helper.jar; every other file is copied into helper.jar as is
  README.md    required; the mod's one README, shipped in the release folder (and shown by GitHub)
  images/      optional; the README's images (PNG, JPEG or GIF), linked by relative path, e.g.
               ![Alt text](images/window.png). Each image the README references ships at the same path,
               so the README shows the same on GitHub and in Patchbay; a referenced image that is
               missing, outside the mod folder, or not a PNG, JPEG or GIF stops the build. Unreferenced files
               in images/ are listed and not shipped. Web images are left alone (Patchbay shows their alt text).
  package/     optional; copied verbatim into the release folder (descriptors, resources/...); no README.md

Mod kit ("kit": true in build.json): the mod compiles against the kit's compile jar (modkit/build.py) and
bml-api.jar instead of Bitwig's jar, `modkit finish` checks its @Hook methods and renames bitwig.obf back,
and the shipped mod.json is generated: the mod folder's mod.json (no hooks needed) plus a hook per @Hook and
an "opens" list of the non-public Bitwig classes and members the code uses (needs loader 0.34.0-dev.4),
each target reusing the ID of an identical declared target or getting <owner>.<name> (<class> for a class).

Each file is compiled with its own --release (release_overrides, else release), one javac run per
release with -sourcepath src -implicit:none, so a file only ever produces classes at its own target.
The classpath is bitwig/jars/<mod.json built_against> plus the helper.jar of each classpath_mods
entry's accepted release (the acceptance table, Mods/README.md), or of the release an entry names as "<mod-id>@<version>". The release ZIP contains src/ and build.json (standard 9), uses.tsv (the mod's Bitwig dependencies,
see tools/mod_uses.py),
and every entry gets a fixed timestamp so the same source always produces the same ZIP.
"""
from __future__ import annotations
import argparse, hashlib, io, json, re, shutil, subprocess, sys, tempfile, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import mod_uses  # noqa: E402
import workspace as ws  # noqa: E402
import importlib.util  # noqa: E402
_kit_spec = importlib.util.spec_from_file_location("modkit_build", ROOT / "modkit/build.py")
modkit = importlib.util.module_from_spec(_kit_spec)
_kit_spec.loader.exec_module(modkit)
JDK = Path(r"C:\Program Files\Java\jdk-25.0.4.1\bin")
METHOD_DIFF = ROOT / "tools/method_diff.py"
FIXED_DATE = "2026-01-01T00:00:00Z"
FIXED_ZIP_TIME = (2026, 1, 1, 0, 0, 0)
MAJOR = {61: "17", 65: "21"}


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def accepted_version(mod: str) -> str | None:
    for line in ws.ACCEPTED.read_text("utf8").splitlines():
        cells = [c.strip() for c in line.split("|")]
        if len(cells) > 4 and cells[2] == f"`{mod}`":
            return None if cells[3] in ("", "—") else cells[3]
    raise SystemExit(f"{mod} is not listed in {ws.rel(ws.ACCEPTED)}")


def release_member(zip_path: Path, name: str) -> bytes:
    """Read <top>/<name> from a release ZIP, whatever its top folder is called."""
    with zipfile.ZipFile(zip_path) as z:
        hits = [n for n in z.namelist() if n.replace("\\", "/").split("/", 1)[-1] == name]
        if len(hits) != 1:
            raise SystemExit(f"{zip_path.name}: expected one {name}, found {hits}")
        return z.read(hits[0])


def check_toolchain() -> None:
    javac = subprocess.run([str(JDK / "javac"), "-version"], capture_output=True, text=True).stdout.strip()
    if javac != "javac 25.0.4.1":  # standard 11: pinned toolchain
        raise SystemExit(f"expected javac 25.0.4.1, found {javac!r}")


def load_mod(mod: str) -> tuple[Path, dict, dict]:
    d = ws.mod_dir(mod)
    src = d / "src"
    if not src.is_dir() or not any(src.rglob("*.java")):  # standard 9: no release without source
        raise SystemExit(f"{mod}: no Java source in {ws.rel(src)}; refusing to build")
    for f in ("mod.json", "build.json"):
        if not (d / f).is_file():
            raise SystemExit(f"{mod}: missing {f}")
    manifest = json.loads((d / "mod.json").read_text("utf-8-sig"))
    if manifest["mod"]["id"] != mod:
        raise SystemExit(f"{mod}: mod.json says id {manifest['mod']['id']!r}")
    build = json.loads((d / "build.json").read_text("utf8"))
    for rel in build.get("release_overrides", {}):
        if not (src / rel).is_file():
            raise SystemExit(f"{mod}: release_overrides names {rel}, which is not in src/")
    return d, manifest, build


def classpath(manifest: dict, build: dict, tmp: Path) -> tuple[Path, list[str]]:
    """Bitwig's own jar (for checks) and the javac classpath."""
    built = manifest["built_against"]
    ver = ws.build_name(built.get("platform") or "windows", built["bitwig_version"])
    bitwig, libs = modkit.bitwig_jars(ver)
    if build.get("kit"):
        cp = [modkit.compile_jar(ver), libs, modkit.ensure()["bml-api.jar"]]
    else:
        cp = [bitwig, libs]
    for dep in build.get("classpath_mods", []):
        dep, _, acc = dep.partition("@")  # "<mod-id>" (its accepted version) or "<mod-id>@<version>"
        acc = acc or accepted_version(dep)
        if not acc:
            raise SystemExit(f"classpath mod {dep} has no accepted version; name one as {dep}@<version>")
        jar = tmp / f"cp-{dep}.jar"
        jar.write_bytes(release_member(ws.mod_dir(dep) / "releases" / f"{dep}-{acc}.zip", "helper.jar"))
        cp.append(jar)
    for p in cp:
        if not Path(p).is_file():
            raise SystemExit(f"classpath entry missing: {p}")
    return bitwig, [str(p) for p in cp]


def build_jar(d: Path, build: dict, cp: list[str], tmp: Path, bitwig: Path) -> tuple[bytes, dict | None]:
    """helper.jar, and for kit mods what `modkit finish` found (hooks, non-public members)."""
    src = d / "src"
    classes = tmp / "classes"; classes.mkdir()
    groups: dict[str, list[Path]] = {}
    overrides = build.get("release_overrides", {})
    for f in sorted(src.rglob("*.java")):
        rel = f.relative_to(src).as_posix()
        groups.setdefault(str(overrides.get(rel, build["release"])), []).append(f)
    for release, files in sorted(groups.items()):
        args = tmp / f"sources-{release}.txt"
        args.write_text("\n".join(f'"{f.as_posix()}"' for f in files))
        p = subprocess.run([str(JDK / "javac"), "--release", release, "-nowarn", "-proc:none", "-encoding", "UTF-8"]
                           + (["-g"] if build.get("debug") else []) + [
                            "-implicit:none", "-sourcepath", str(src), "-cp", ";".join(cp), "-d", str(classes), f"@{args}"],
                           capture_output=True, text=True, errors="replace")
        if p.returncode:
            raise SystemExit(f"javac --release {release} failed:\n{p.stdout}{p.stderr}")
    found = None
    if build.get("kit"):
        hooks_json = tmp / "hooks.json"
        others = [c for c in cp[1:] if not c.endswith("bml-api.jar")]
        p = subprocess.run([str(JDK / "java"), "-jar", str(modkit.ensure()["bml-modkit.jar"]), "finish",
                            str(classes), str(hooks_json), str(bitwig)] + others,
                           capture_output=True, text=True, errors="replace")
        print(p.stdout.strip())
        if p.returncode:
            raise SystemExit(f"modkit finish failed:\n{p.stderr}")
        found = json.loads(hooks_json.read_text("utf8"))
    for f in sorted(src.rglob("*")):
        if f.is_file() and f.suffix != ".java":
            out = classes / f.relative_to(src)
            out.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(f, out)
    jar = tmp / "helper.jar"
    subprocess.run([str(JDK / "jar"), "--create", "--file", str(jar), f"--date={FIXED_DATE}", "-C", str(classes), "."], check=True)
    return jar.read_bytes(), found


MANIFEST_ORDER = ["manifest_version", "package_type", "mod", "built_against", "helper", "targets", "hooks", "fields", "opens"]


def kit_manifest(mod: str, manifest: dict, found: dict) -> bytes:
    """The shipped mod.json of a kit mod: the declared manifest plus one hook per @Hook."""
    m = json.loads(json.dumps(manifest))
    targets = m.setdefault("targets", {})
    by_physical = {json.dumps(t, sort_keys=True): tid for tid, t in targets.items()}

    def target_id(physical: dict) -> str:
        key = json.dumps(physical, sort_keys=True)
        if key not in by_physical:
            base = physical["class"] if physical["kind"] == "class" else f"{physical['owner']}.{physical['name']}"
            base = base.replace("/", ".").replace("$", "_")  # IDs allow [A-Za-z0-9_.-] only
            base = re.sub(r"[^A-Za-z0-9_.-]", "", base)  # constructors: <init> -> init
            tid, n = base, 2
            while tid in targets:
                tid, n = f"{base}-{n}", n + 1
            targets[tid] = physical
            by_physical[key] = tid
        return by_physical[key]

    hooks = m.get("hooks", [])
    for h in found["hooks"]:
        out = {"id": h["id"], "target": target_id(h["target"]), "transform": h["transform"]}
        if "method_from" in h:
            out["method"] = {"from_target": target_id(h["method_from"])}
            out["body"] = {"template": h["body"]}
        if "match" in h:
            mt = h["match"]
            out["match"] = {"opcode": mt["opcode"], "target": target_id(mt["target"]), "occurrence": mt["occurrence"]} if "target" in mt \
                else {"opcode": mt["opcode"], "owner": mt["owner"], "name": mt["name"], "descriptor": mt["descriptor"], "occurrence": mt["occurrence"]}
        out["bridge"] = h["bridge"]
        hooks.append(out)
    ids = [h["id"] for h in hooks]
    if len(ids) != len(set(ids)):
        raise SystemExit(f"{mod}: duplicate hook IDs {sorted({i for i in ids if ids.count(i) > 1})}; set @Hook(id = ...)")
    m["hooks"] = hooks
    if found.get("fields"):
        namespace = re.sub(r"[^A-Za-z0-9]", "_", mod) + "$"  # ModFields.namespace
        for f in found["fields"]:
            if not f["name"].startswith(namespace) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_$]*", f["name"][len(namespace):]):
                raise SystemExit(f"{mod}: @AddField name {f['name']} must be {namespace}<name>")
            for t in mod_uses.DESC_CLASS.findall(f["descriptor"]):
                if not t.startswith("java/"):
                    target_id({"kind": "class", "class": t})
        m["fields"] = m.get("fields", []) + [{"id": f["id"], "owner": target_id(f["owner"]), "name": f["name"], "descriptor": f["descriptor"]}
                                             for f in found["fields"]]
    classes = found["hook_classes"]
    if len(classes) > 1:
        raise SystemExit(f"{mod}: @Hook methods are spread over {classes}; the loader calls one helper class, so keep them in one")
    if classes:
        helper = m.setdefault("helper", {"jar": "helper.jar", "class": classes[0]})
        if helper.get("class") != classes[0]:
            raise SystemExit(f"{mod}: mod.json names helper class {helper.get('class')} but the @Hook methods are in {classes[0]}")
    if found["access"]:  # non-public Bitwig classes and members the code uses; the loader opens them
        m["opens"] = sorted({target_id(a) for a in found["access"]})
    ordered = {k: m[k] for k in MANIFEST_ORDER if k in m}
    ordered.update({k: v for k, v in m.items() if k not in ordered})
    return (json.dumps(ordered, indent=2, ensure_ascii=False) + "\n").encode()


README_IMAGE = re.compile(r"!\[([^\]\n]{0,400})\]\(([^)\n]{1,2000})\)")  # SafeMarkdown.TOKEN, images only
IMAGE_TYPES = {".png": (b"\x89PNG\r\n\x1a\n",), ".jpg": (b"\xff\xd8\xff",), ".jpeg": (b"\xff\xd8\xff",),
               ".gif": (b"GIF87a", b"GIF89a")}


def readme_images(d: Path, readme: bytes) -> dict[str, bytes]:
    """The local images README.md references, by the relative path it uses (anywhere in the mod folder
    except src/, releases/, test/ and dev/; images/ is the convention). A web image (a URL) is left alone;
    a local one that is missing or isn't a PNG, JPEG or GIF stops the build."""
    from urllib.parse import unquote
    text, fence = [], False
    for line in readme.decode("utf-8-sig").replace("\r\n", "\n").split("\n"):
        if line.strip().startswith(("```", "~~~")):  # fenced code shows as code, as in Patchbay and on GitHub
            fence = not fence
        elif not fence:
            text.append(line)
    found: dict[str, bytes] = {}
    for m in README_IMAGE.finditer("\n".join(text)):
        target = m.group(2).strip()
        target = target[1:target.index(">")] if target.startswith("<") and ">" in target else target.split(" ", 1)[0]
        if re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", target) and not re.match(r"^[A-Za-z]:[\\/]", target):
            continue  # a web image (https:, data:, ...): Patchbay shows its alt text and fetches nothing
        path = unquote(target)
        why = None
        if not path or "\\" in path or path.startswith("/") or ":" in path or "?" in path or "#" in path:
            why = "use a relative path with forward slashes, like images/window.png"
        elif ".." in path.split("/"):
            why = "\"..\" isn't allowed; keep the image inside the mod's folder"
        elif path.split("/", 1)[0] in ("src", "releases", "test", "dev"):
            why = "an image can't come from src/, releases/, test/ or dev/; images/ beside the README is the usual place"
        elif Path(path).suffix.lower() not in IMAGE_TYPES:
            why = "only PNG, JPEG or GIF can be shown (Patchbay can't render SVG or other formats)"
        elif not (d / path).is_file():
            why = "the file is missing"
        if why:
            raise SystemExit(f"{d.name}: README.md image {target!r}: {why}")
        data = (d / path).read_bytes()
        if not data.startswith(IMAGE_TYPES[Path(path).suffix.lower()]):
            raise SystemExit(f"{d.name}: README.md image {target!r}: the file's content isn't a {Path(path).suffix[1:].upper()}")
        found[path] = data
    images = d / "images"
    if images.is_dir():
        unused = sorted(f.relative_to(d).as_posix() for f in images.rglob("*") if f.is_file()
                        and f.relative_to(d).as_posix() not in found)
        if unused:
            print(f"  not shipped (README.md doesn't reference them): {unused}")
    return found


def release_files(d: Path, jar: bytes, uses: list[str], manifest: bytes) -> dict[str, bytes]:
    """Every file in the release folder, by path relative to it."""
    files = {"helper.jar": jar, "mod.json": manifest,
             "uses.tsv": "".join(r + "\n" for r in uses).encode()}
    readme = d / "README.md"
    if not readme.is_file():
        raise SystemExit(f"{d.name}: no README.md in the mod folder (it ships with the release)")
    files["README.md"] = readme.read_bytes()
    pkg = d / "package"
    if (pkg / "README.md").exists():
        raise SystemExit(f"{d.name}: package/README.md: the README lives at the mod folder's root")
    if pkg.is_dir():
        for f in sorted(pkg.rglob("*")):
            if f.is_file():
                files[f.relative_to(pkg).as_posix()] = f.read_bytes()
            elif not any(f.iterdir()):  # empty folders ship as folder entries
                files[f.relative_to(pkg).as_posix() + "/"] = b""
    for n, b in readme_images(d, files["README.md"]).items():
        if n in files or any(k.startswith(n + "/") for k in files):
            raise SystemExit(f"{d.name}: README.md image {n} clashes with package/{n}")
        files[n] = b
    # git keeps no empty folders, so a directory resource the manifest declares is shipped even when a fresh
    # checkout has no package/<path>/ (e.g. Shortcut Aliases' resources/config, which it fills at runtime)
    for r in json.loads(manifest).get("resources", {}).values():
        path = r.get("path", "").strip("/")
        if r.get("kind") == "directory" and path and not any(n == path + "/" or n.startswith(path + "/") for n in files):
            files[path + "/"] = b""
    files["SHA256SUMS.txt"] = "".join(f"{sha(b)}  {n}\n" for n, b in sorted(files.items()) if not n.endswith("/")).encode()
    files["build.json"] = (d / "build.json").read_bytes()
    for f in sorted((d / "src").rglob("*")):
        if f.is_file():
            files["src/" + f.relative_to(d / "src").as_posix()] = f.read_bytes()
    return files


def write_zip(mod: str, files: dict[str, bytes]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for n, b in sorted(files.items()):
            info = zipfile.ZipInfo(f"{mod}/{n}", FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_STORED if n.endswith("/") else zipfile.ZIP_DEFLATED
            z.writestr(info, b)
    return buf.getvalue()


FRAME_LINE = re.compile(r"^\s*(frame_type|locals =|stack =|StackMapTable:)")
LOOSE = {"frames": "stack-map frames differ (verifier type hints; the old javac inferred different types)",
         "attr-order": "class attributes in a different order (old bytecode patcher)"}


def classify(want: Path, got: Path) -> set[str] | str:
    """Same instructions, member by member? Returns the set of tolerated differences, or what differs."""
    sys.path.insert(0, str(METHOD_DIFF.parent))
    import method_diff

    def split(p: Path):
        """method_diff keys every 2-space-indented javap line as a member; everything after the closing
        "}" (InnerClasses / BootstrapMethods entries included) is class attributes, grouped per attribute."""
        members, tail, attrs = {}, None, []
        for k, lines in method_diff.methods(str(p)).items():
            if tail is None:
                if "}" not in lines:
                    members[k] = lines; continue
                i = lines.index("}")
                members[k], tail = lines[:i], lines[i + 1:]
            else:
                tail += [" " + k] + lines
        for ln in tail or []:
            if ln and not ln.startswith(" "):
                attrs.append([])
            if attrs:
                attrs[-1].append(ln)
        return members, attrs

    (wm, wa), (gm, ga) = split(want), split(got)
    if set(wm) != set(gm):
        return f"members: only in accepted {sorted(set(wm) - set(gm))[:3]}, only in built {sorted(set(gm) - set(wm))[:3]}"
    kinds = set()
    if wa != ga:
        if sorted(wa) != sorted(ga):
            return "class attributes differ"
        kinds.add("attr-order")
    for k in wm:
        if wm[k] != gm[k]:
            if [l for l in wm[k] if not FRAME_LINE.match(l)] != [l for l in gm[k] if not FRAME_LINE.match(l)]:
                return f"instructions differ in {k[:100]}"
            kinds.add("frames")
    return kinds


def compare(old_zip: Path, files: dict[str, bytes], tmp: Path) -> bool:
    """Compare a fresh build with an existing release: classes, jar resources and shipped files."""
    want = zipfile.ZipFile(io.BytesIO(release_member(old_zip, "helper.jar")))
    got = zipfile.ZipFile(io.BytesIO(files["helper.jar"]))
    wn = {n for n in want.namelist() if not n.endswith("/") and not n.startswith("META-INF/")}
    gn = {n for n in got.namelist() if not n.endswith("/") and not n.startswith("META-INF/")}
    exact, strict, bad, loose = 0, 0, [], {}
    for n in sorted(wn & gn):
        a, b = want.read(n), got.read(n)
        if a == b:
            exact += 1; continue
        if not n.endswith(".class"):
            bad.append(f"resource differs: {n}"); continue
        wa, gb = tmp / "cmp/want" / n, tmp / "cmp/got" / n
        for p, data in ((wa, a), (gb, b)):
            p.parent.mkdir(parents=True, exist_ok=True); p.write_bytes(data)
        wmaj, gmaj = int.from_bytes(a[6:8], "big"), int.from_bytes(b[6:8], "big")
        if wmaj != gmaj:
            bad.append(f"class version differs: {n} (accepted Java {MAJOR.get(wmaj, wmaj)}, built Java {MAJOR.get(gmaj, gmaj)})")
            continue
        verdict = classify(wa, gb)
        if isinstance(verdict, str):
            bad.append(f"code differs: {n} ({verdict})")
        else:
            strict += 1
            for kind in verdict:
                loose.setdefault(kind, []).append(n)
    bad += [f"only in accepted jar: {n}" for n in sorted(wn - gn)]
    bad += [f"only in built jar: {n}" for n in sorted(gn - wn)]
    with zipfile.ZipFile(old_zip) as z:
        shipped = {n.replace("\\", "/").split("/", 1)[-1]: n for n in z.namelist() if not n.endswith("/")}
        notes = []
        for n, b in sorted(files.items()):
            if n in ("helper.jar", "build.json", "SHA256SUMS.txt") or n.startswith("src/") or n.endswith("/"):
                continue
            if n not in shipped:
                notes.append(f"new file: {n}")
            elif z.read(shipped[n]) != b:
                bad.append(f"shipped file differs: {n}")
        notes += [f"not in new build: {n}" for n in sorted(set(shipped) - set(files) - {"SHA256SUMS.txt"})
                  if not n.startswith("src/")]
        folders = {n.replace("\\", "/").split("/", 1)[-1] for n in z.namelist() if n.endswith("/")} - {""}
        bad += [f"folder only in accepted release: {n}" for n in sorted(folders - set(files)) if not n.startswith("src/")]
    print(f"compared with {ws.rel(old_zip)}: {len(wn)} jar entries; "
          f"exact={exact} equivalent={strict} (same instructions; line numbers / constant-pool / lambda numbering may differ) "
          f"not matching={len(bad)}")
    for kind, names in sorted(loose.items()):
        print(f"  equivalent, but {LOOSE[kind]}: {len(names)} {[Path(x).stem for x in names][:8]}")
    for line in bad + notes:
        print("  " + line)
    return not bad


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mod")
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--check", action="store_true", help="compare with the existing release, write nothing")
    g.add_argument("--replace", action="store_true", help="overwrite an existing release of this version")
    g.add_argument("--out", type=Path, help="write the ZIP to this folder; releases/ is not touched")
    a = ap.parse_args()
    check_toolchain()
    d, manifest, build = load_mod(a.mod)
    ver = manifest["mod"]["version"]
    out = d / "releases" / f"{a.mod}-{ver}.zip"
    if a.check and not out.is_file():
        raise SystemExit(f"--check: {ws.rel(out)} does not exist")
    target = a.out / out.name if a.out else out
    if target.exists() and not (a.check or a.replace):
        raise SystemExit(f"{target} exists; pass --check to compare or --replace to rebuild it")
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        bitwig, cp = classpath(manifest, build, tmp)
        jar, found = build_jar(d, build, cp, tmp, bitwig)
        shipped = kit_manifest(a.mod, manifest, found) if found is not None else (d / "mod.json").read_bytes()
        files = release_files(d, jar, mod_uses.with_fields(mod_uses.uses(jar, bitwig), json.loads(shipped)), shipped)
        ok = compare(out, files, tmp) if out.is_file() else True
        if a.check:
            print("CHECK_OK" if ok else "CHECK_MISMATCH")
            return 0 if ok else 1
        data = write_zip(a.mod, files)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        print(f"BUILT {target}  helper_jar_sha256={sha(jar)}  sha256={sha(data)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
