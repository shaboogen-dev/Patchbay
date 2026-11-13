#!/usr/bin/env python3
"""Assemble BML installs from the canonical folders.

  suite <build>     build <installs>/<build> (or --out) from loader + map + accepted mods
  release           build dist/BML-<loader>-<platform>.zip (the tester release for windows, linux, macos)
                    and the workspace-maps ZIP
  clear <name>      after a passed user test: results -> <results>/<name>/, install deleted

A build is named as the mapper names it (workspace.build_name): "6.1.4" is Bitwig 6.1.4 for Windows,
"linux-6.1.4" for Linux, "macos-6.1.4" for macOS. A Linux install is for the Bitwig at /opt/bitwig-studio, run
under Linux (WSL here). A macOS install is for the Bitwig in /Applications/Bitwig Studio.app; it is also packed as
<installs>/<name>.zip, to copy to the Mac (whose launcher clears the quarantine macOS gives the unpacked files).
Only a macOS install carries the core's BML.command (double-clicked in Finder once the quarantine is cleared).

Folders come from the workspace (tools/workspace.py: BML's parent folder, optionally workspace.json).
Inputs (never modified):
  loader/releases/  the release marked **Current.** in loader/README.md
  <maps>/<build>/   exactly one map pack ZIP per build (Mapper/maps/, or maps/ from a downloaded release):
                    maps/6.1.4/ for Windows, maps/linux-6.1.4/ for Linux, maps/macos-6.1.4/ for macOS
  Mods/README.md    the "Accepted" column picks <mod folder>/releases/<id>-<version>.zip, the mod folder being
                    BML/frameworks/<id> or Mods/<id>
  test components   suite-only: <mod folder>/test-components/ and <test_components>/<id>/: each *.zip is unpacked,
                    any other file or folder (a panel's .json, a theme's .bte) is copied as it is, and the mod's
                    test-components/selection.properties is the choice. A mod with a selection.properties but no
                    component in the workspace (e.g. Framework: Themes without Internal's test theme) is left out:
                    it can't prepare.

A mod is installed only when the map pack can translate it: mods are built against Windows builds, so
the pack must hold remap/windows-<built_against>__<target build key>.tsv.gz (the key is windows-<version>
linux-<version> or macos-<version>). Everything else is listed as skipped in BUILD-INFO.json.

Never starts Bitwig. After a Windows suite build, run its guard_selective.py --check / --prepare; after a
Linux one, run loader/prepare-mod.sh in it under Linux (guard_selective.py is Windows-only); a macOS one
prepares on the Mac when the BML window loads.
"""
from __future__ import annotations
import argparse, datetime, hashlib, json, re, shutil, sys, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
sys.path.insert(0, str(TOOLS))
import mod_uses  # noqa: E402
import workspace as ws  # noqa: E402
GUARD_KIND = "BML_SELECTIVE_MULTI_VERSION_V2_TRIAL"
TEST_INSTALL_MARKER = "portable"  # BmlPaths.TEST_INSTALL_MARKER, in config/


def sha_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def sha_file(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def names(z: zipfile.ZipFile, dirs: bool = False) -> list[tuple[zipfile.ZipInfo, str]]:
    # Some ZIPs were written on Windows with backslash separators.
    out = [(i, i.filename.replace("\\", "/")) for i in z.infolist()]
    return out if dirs else [(i, n) for i, n in out if not n.endswith("/")]


def extract(z: zipfile.ZipFile, dest: Path, strip: str = "") -> None:
    for info, name in names(z, dirs=True):
        if strip:
            if not name.startswith(strip):
                continue
            name = name[len(strip):]
        if not name:
            continue
        out = (dest / name).resolve()
        if not str(out).startswith(str(dest.resolve())):
            raise ValueError(f"Unsafe ZIP entry {info.filename!r}")
        if name.endswith("/"):  # empty folders matter (e.g. resources/config)
            out.mkdir(parents=True, exist_ok=True)
            continue
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(z.read(info))


# ---------------------------------------------------------------- inputs

def current_loader() -> Path:
    text = (ROOT / "loader" / "README.md").read_text("utf8")
    m = re.search(r"`releases/([^`]+\.zip)`\s*\|\s*\*\*Current\.\*\*", text)
    if not m:
        raise SystemExit("loader/README.md has no row marked **Current.**")
    return ROOT / "loader" / "releases" / m.group(1)


LINUX_BITWIG = "/opt/bitwig-studio/bitwig-studio"  # where Bitwig's Linux packages install its launcher
MAC_BITWIG = "/Applications/Bitwig Studio.app/Contents/MacOS/BitwigStudio"  # Bitwig's executable in its app bundle


def build_platform(build: str) -> tuple[str, str]:
    """(platform, version) of a build named by workspace.build_name: "6.1.4" -> ("windows", "6.1.4")."""
    platform, _, version = build.rpartition("-")
    platform = platform or "windows"
    if platform not in ws.PLATFORMS:
        raise SystemExit(f"unknown platform in build {build!r} (known: {', '.join(ws.PLATFORMS)})")
    return platform, version


def build_key(build: str) -> str:
    """The build key map packs use: windows-6.1.4, linux-6.1.4."""
    platform, version = build_platform(build)
    return f"{platform}-{version}"


def map_pack(build: str, override: Path | None = None) -> tuple[Path, dict, set[str]]:
    """The pack for a build, its map.json and the builds it translates from (its remaps
    <source key>__<the build's key>), named as workspace.build_name names them ("6.1.3", "macos-6.1.4")."""
    zips = [override] if override else sorted((ws.MAPS / build).glob("*.zip"))
    if len(zips) != 1:
        raise SystemExit(f"{rel(ws.MAPS / build)}/ must hold exactly one map ZIP, found {len(zips)}")
    with zipfile.ZipFile(zips[0]) as z:
        entries = [n for _, n in names(z)]
        meta_name = next(n for n in entries if n.count("/") == 1 and n.endswith("/map.json"))
        meta = json.loads(z.read(meta_name))
    key = build_key(build)
    if meta.get("target_build_key", key) != key:
        raise SystemExit(f"{zips[0].name} is a map for {meta['target_build_key']}, not {key}")
    pattern = r"/remap/([a-z]+)-([^/]+)__" + re.escape(key) + r"\.tsv\.gz$"
    sources = {ws.build_name(m.group(1), m.group(2)) for n in entries if (m := re.search(pattern, n))}
    return zips[0], meta, sources


def accepted_mods() -> dict[str, str]:
    """mod id -> accepted version, from the acceptance table (Mods/README.md)."""
    out = {}
    for line in ws.ACCEPTED.read_text("utf8").splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 3 and cells[1].startswith("`") and re.match(r"\d", cells[2]):
            out[cells[1].strip("`")] = cells[2]
    return out


def read_mod(mod_id: str, version: str) -> tuple[Path, str, dict]:
    path = ws.mod_dir(mod_id) / "releases" / f"{mod_id}-{version}.zip"
    if not path.is_file():
        raise SystemExit(f"Accepted release missing: {rel(path)}")
    with zipfile.ZipFile(path) as z:
        hits = [n for _, n in names(z) if n.endswith("mod.json") and n.count("/") == 1]
        if len(hits) != 1:
            raise SystemExit(f"{path.name}: expected one <folder>/mod.json, found {hits}")
        manifest = json.loads(z.read(next(i for i, n in names(z) if n == hits[0])))
    return path, hits[0][: -len("mod.json")], manifest


def built_against(manifest: dict) -> str:
    """The build a mod's names come from, named as workspace.build_name names it ("6.1.4", "macos-6.1.3")."""
    b = manifest.get("built_against") or {}
    if not b.get("bitwig_version"):
        raise SystemExit(f"mod.json lacks built_against.bitwig_version: {manifest.get('mod', manifest).get('id')}")
    return ws.build_name(b.get("platform") or "windows", b["bitwig_version"])


def manifest_id(manifest: dict) -> str:
    return (manifest.get("mod") or manifest).get("id")


# ---------------------------------------------------------------- assembly

MAC_COMMAND = "BML.command"  # the core's macOS double-click launcher: macOS installs only
EXECUTABLE = (".sh", ".command")  # mode 0755 in a ZIP for a Mac


def install_core(dest: Path, loader_zip: Path, mac: bool = False) -> None:
    with zipfile.ZipFile(loader_zip) as z:
        top = {n.split("/")[0] for _, n in names(z)}
        if len(top) != 1:
            raise SystemExit(f"{loader_zip.name}: expected one top-level folder")
        extract(z, dest, top.pop() + "/")
    if not mac:
        (dest / MAC_COMMAND).unlink(missing_ok=True)


def install_map(dest: Path, map_zip: Path) -> None:
    with zipfile.ZipFile(map_zip) as z:
        extract(z, dest / "maps")


def install_mods(dest: Path, wanted: dict[str, str], sources: set[str] | None, target: str | None = None,
                 map_zip: Path | None = None) -> tuple[list, list]:
    """`target` is the build installed for (a mod built against it needs no remap). With map_zip, a mod whose
    Bitwig dependencies (uses.tsv) the pack can't all translate is left out."""
    installed, skipped = [], []
    for mod_id, version in sorted(wanted.items()):
        path, prefix, manifest = read_mod(mod_id, version)
        if manifest_id(manifest) != mod_id:
            raise SystemExit(f"{path.name}: mod.json id is {manifest_id(manifest)!r}, expected {mod_id!r}")
        src = built_against(manifest)
        if sources is not None and src not in sources and src != target:
            skipped.append({"id": mod_id, "version": version, "built_against": src,
                            "reason": f"map pack has no {build_key(src)}__{build_key(target)} remap"})
            continue
        with zipfile.ZipFile(path) as z:
            extract(z, dest / "mods" / mod_id, prefix)
        gaps = uses_gaps(dest / "mods" / mod_id, src, map_zip) if map_zip and src != target else []
        if gaps:
            shutil.rmtree(dest / "mods" / mod_id)
            skipped.append({"id": mod_id, "version": version, "built_against": src,
                            "reason": f"map pack can't translate {len(gaps)} of its Bitwig dependencies", "missing": gaps})
            continue
        installed.append({"id": mod_id, "version": version, "built_against": src,
                          "zip": rel(path), "zip_sha256": sha_file(path)})
    return installed, skipped


def uses_gaps(mod_dir: Path, built_against: str, map_zip: Path) -> list[str]:
    """What the pack can't translate of the mod's uses.tsv (generated from helper.jar for releases built
    before build_mod.py wrote one)."""
    uses = mod_dir / "uses.tsv"
    if uses.is_file():
        rows = uses.read_text("utf8").splitlines()
    elif (mod_dir / "helper.jar").is_file():
        rows = mod_uses.uses((mod_dir / "helper.jar").read_bytes(), ws.build_jars(built_against)[0])
    else:
        return []
    return mod_uses.missing(rows, map_zip, built_against)


def loose_test_components(folder: Path) -> list[Path]:
    """What a test-components folder holds as it is, to copy into components/ unchanged: every file and
    folder except ZIPs (unpacked instead), selection.properties, READMEs and hidden ones. So a component
    kept in git as plain files (a panel's .json, a theme's .bte) needs no git-ignored ZIP."""
    if not folder.is_dir():
        return []
    return sorted(p for p in folder.iterdir()
                  if not p.name.startswith(".") and p.suffix.lower() != ".zip" and p.name != "selection.properties"
                  and not p.name.lower().startswith("readme") and not p.is_symlink())


def install_test_components(dest: Path, mod_ids: list[str]) -> tuple[list[str], list[str]]:
    """The components installed (ZIPs, unpacked, and loose files or folders, copied as they are), and the mods
    whose selected test component isn't in this workspace."""
    added, selections, missing = [], [], []
    for mod_id in mod_ids:
        mod_folder = ws.find_mod_dir(mod_id)  # None for --add-mods folders outside the repositories
        folder = mod_folder / "test-components" if mod_folder else ws.TEST_COMPONENTS / mod_id / "none"
        # test inputs that may not be shared (e.g. a theme with an edited Bitwig icon) live outside the repos
        private = ws.TEST_COMPONENTS / mod_id
        zips = sorted(folder.glob("*.zip")) + sorted(private.glob("*.zip"))
        for z_path in zips:
            (dest / "components").mkdir(parents=True, exist_ok=True)
            shutil.copy2(z_path, dest / "components" / z_path.name)
            with zipfile.ZipFile(z_path) as z:
                extract(z, dest / "components")
            added.append(z_path.name)
        loose = loose_test_components(folder) + loose_test_components(private)
        for item in loose:
            target = dest / "components" / item.name
            target.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                raise SystemExit(f"{mod_id}: test component {item.name} is there twice (a ZIP's content, or both "
                                 f"{rel(folder)} and {rel(private)}); keep one")
            if item.is_dir():
                shutil.copytree(item, target)
            else:
                shutil.copy2(item, target)
            added.append(item.name)
        sel = folder / "selection.properties"
        if sel.is_file() and (zips or loose):
            selections.append(sel.read_text("utf8").strip())
        elif sel.is_file():
            missing.append(mod_id)
    if selections:
        (dest / "state").mkdir(exist_ok=True)
        (dest / "state" / "selected-components.properties").write_text(
            "#BML v3 requested component selections (written by tools/bml_build.py)\n"
            + "\n".join(selections) + "\n", "utf8")
    return added, missing


def write_enabled(dest: Path, mod_ids: list[str], unticked: list[str] = ()) -> None:
    (dest / "state").mkdir(exist_ok=True)
    (dest / "state" / "enabled-mods.properties").write_text(
        "#BML enabled mod state (written by tools/bml_build.py)\n"
        + "".join(f"mod.{m}={'false' if m in unticked else 'true'}\n" for m in sorted([*mod_ids, *unticked])), "utf8")


def stamp() -> str:
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")


# ---------------------------------------------------------------- suite

def preserve_results(old: Path) -> Path | None:
    """Before an install is replaced (e.g. after a failed test), keep its sessions and build info
    in <results>/<name>/replaced-<time>/."""
    keep = list(old.glob("sessions/**/*")) + [p for p in [old / "BUILD-INFO.json", old / "OFFLINE-SELECTIVE-SUMMARY.json"] if p.is_file()]
    keep = [p for p in keep if p.is_file()]
    if not any(p.parts[len(old.parts)] == "sessions" for p in keep):
        return None
    out = ws.RESULTS / old.name / ("replaced-" + stamp()[:19].replace(":", "-"))
    for p in keep:
        target = out / p.relative_to(old)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(p, target)
    return out


def install_mod_folders(dest: Path, source: Path, replace: bool = False) -> list[dict]:
    """Copy unpackaged development mods (folders holding mod.json) verbatim."""
    installed = []
    for folder in sorted(p for p in source.iterdir() if (p / "mod.json").is_file()):
        manifest = json.loads((folder / "mod.json").read_text("utf-8-sig"))
        mod_id = manifest_id(manifest)
        if replace and (dest / "mods" / mod_id).exists():
            shutil.rmtree(dest / "mods" / mod_id)
        shutil.copytree(folder, dest / "mods" / mod_id)
        installed.append({"id": mod_id, "version": (manifest.get("mod") or manifest).get("version"),
                          "built_against": built_against(manifest), "folder": str(folder)})
    if not installed:
        raise SystemExit(f"No mod folders (with mod.json) in {source}")
    return installed


def rel(p: Path) -> str:
    return ws.rel(p)


def build_suite(version: str, replace: bool, out: Path | None = None, map_override: Path | None = None,
                loader: Path | None = None, mods_from: Path | None = None, add_mods: list[Path] | None = None,
                skip: dict[str, str] | None = None, only: list[str] | None = None,
                add_unticked: list[Path] | None = None) -> int:
    """`version` is the build (6.1.4, linux-6.1.4, macos-6.1.4). `only`: these accepted mods instead of all of them.
    `add_unticked`: mod folders installed as they are but unticked, and not prepared (e.g. to see in the window
    a mod that isn't available on this build)."""
    platform = build_platform(version)[0]
    linux, mac = platform == "linux", platform == "macos"
    loader_zip = loader or current_loader()
    map_zip, meta, sources = map_pack(version, map_override)
    final = out or ws.INSTALLS / version
    if final.exists() and not replace:
        raise SystemExit(f"{rel(final)} exists; pass --replace (its sessions are kept in {rel(ws.RESULTS)}/ first)")
    if final.exists():
        refuse_if_in_use(final, "suite --replace")
    stage = final.parent / ".building"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)

    install_core(stage, loader_zip, mac)
    if mac and not (stage / "loader" / "jvmti" / "bin" / "bitwig-jvmti-tracer.dylib").is_file():
        shutil.rmtree(stage)
        raise SystemExit(f"{loader_zip.name} has no macOS agent (loader/jvmti/bin/bitwig-jvmti-tracer.dylib): "
                         "build the loader with the Mac-built agent (loader/build.py --mac-dylib)")
    install_map(stage, map_zip)
    if mods_from:
        installed, skipped = install_mod_folders(stage, mods_from), []
        for m in list(installed):  # the same uses.tsv check as for accepted mods
            gaps = uses_gaps(stage / "mods" / m["id"], m["built_against"], map_zip) if m["built_against"] != version else []
            if gaps:
                shutil.rmtree(stage / "mods" / m["id"])
                installed.remove(m)
                skipped.append({"id": m["id"], "version": m["version"], "built_against": m["built_against"],
                                "reason": f"map pack can't translate {len(gaps)} of its Bitwig dependencies", "missing": gaps})
    else:
        wanted = {k: v for k, v in accepted_mods().items() if k not in (skip or {})}
        if only:
            missing = set(only) - set(wanted)
            if missing:
                raise SystemExit(f"Not accepted (or skipped): {sorted(missing)}")
            wanted = {k: v for k, v in wanted.items() if k in only}
        installed, skipped = install_mods(stage, wanted, sources, version, map_zip)
        skipped += [{"id": k, "reason": why} for k, why in sorted((skip or {}).items())]
    for extra_dir in add_mods or []:
        for extra in install_mod_folders(stage, extra_dir, replace=True):
            installed = [m for m in installed if m["id"] != extra["id"]] + [dict(extra, added=True)]
    components, no_component = install_test_components(stage, [m["id"] for m in installed])
    for m in [m for m in installed if m["id"] in no_component]:
        shutil.rmtree(stage / "mods" / m["id"])
        installed.remove(m)
        skipped.append({"id": m["id"], "version": m["version"], "reason": "its test component is not in this workspace "
                        f"({rel(ws.TEST_COMPONENTS / m['id'])}/)"})
    ids = [m["id"] for m in installed]
    unticked = []
    for extra_dir in add_unticked or []:
        for extra in install_mod_folders(stage, extra_dir, replace=True):
            if extra["id"] in ids:
                raise SystemExit(f"--add-unticked {extra['id']}: that mod is already installed ticked")
            unticked.append(extra)
    write_enabled(stage, ids, [m["id"] for m in unticked])
    (stage / "config").mkdir(exist_ok=True)
    (stage / "config" / "bml.properties").write_text(
        "#BML configuration\n"
        + (f"bitwig.executable={LINUX_BITWIG}\n" if linux else f"bitwig.executable={MAC_BITWIG}\n" if mac else
           "bitwig.executable=C\\:\\\\Program Files\\\\Bitwig Studio\\\\Bitwig Studio.exe\n")
        + "ui.hide_debug_mode=false\n", "utf8")
    # The marker keeps this install's settings in its own folders: a test never touches the user's real ones,
    # and `clear` takes them with the install. A release doesn't have it.
    (stage / "config" / TEST_INSTALL_MARKER).write_text(
        "This is a test install: BML keeps its settings in this folder and in state/, not in the per-user folder.\n", "utf8")

    windows = not linux and not mac
    if windows:  # guard_selective.py runs the Windows preparer; the install opens with bml-ui.cmd
        shutil.copy2(TOOLS / "guard_selective.py", stage / "guard_selective.py")
    mod_binaries = {}
    for m in ids + [u["id"] for u in unticked]:  # provider-registration-only mods have no helper.jar
        files = {"mod_json_sha256": sha_file(stage / "mods" / m / "mod.json")}
        if (stage / "mods" / m / "helper.jar").is_file():
            files["helper_jar_sha256"] = sha_file(stage / "mods" / m / "helper.jar")
        mod_binaries[m] = files
    remap = next(k for k in meta["file_sha256"] if k.startswith("remap/"))
    (stage / "TRIAL-SELECTIVE-SETUP.json").write_text(json.dumps({
        "kind": GUARD_KIND, "status": "BUILT_UNTESTED", "created_utc": stamp(),
        "version": version, "target_build_key": meta["target_build_key"],
        "target_sha256": meta["target_jar_sha256"], "map_id": meta["id"],
        "map_version": meta["version"], "map_remap_sha256": meta["file_sha256"][remap],
        "control_sha256": sha_file(stage / "loader" / "app" / "bml-control.jar"),
        "mod_count": len(mod_binaries), "mod_binaries": mod_binaries,
    }, indent=2) + "\n", "utf8")
    (stage / "BUILD-INFO.json").write_text(json.dumps({
        "built_utc": stamp(), "bitwig": version,
        "loader": rel(loader_zip), "mods_from": rel(mods_from) if mods_from else None,
        "map": rel(map_zip), "map_sha256": sha_file(map_zip), "map_version": meta["version"],
        "map_sources": sorted(sources), "mods": installed, "unticked": unticked, "skipped": skipped,
        "test_components": components,
    }, indent=2) + "\n", "utf8")

    if final.exists():
        kept = preserve_results(final)
        if kept:
            print("RESULTS_KEPT", rel(kept))
        shutil.rmtree(final)
    stage.rename(final)
    archive = final.parent / f"{final.name}.zip"
    if mac:  # to copy to the Mac
        write_zip(archive, final, final.name, unix_modes=True)
    print("SUITE_BUILT", final, f"{len(ids)} mods")
    for s in skipped:
        print("  skipped", s["id"], s.get("version", ""), "-", s["reason"])
        for g in s.get("missing", [])[:10]:
            print("      missing", g)
    if linux:
        print("Next, under Linux:", f"sh loader/prepare-mod.sh /opt/bitwig-studio/bin mods  (in {final})")
    elif mac:
        print("MAC_ZIP", archive, "sha256=" + sha_file(archive))
        print("Next, on the Mac: unpack", archive.name, "in Downloads, then in Terminal:",
              f"sh ~/Downloads/{final.name}/bml-ui.sh",
              f"(the first time; after that, double-click {MAC_COMMAND} in that folder)")
    else:
        print("Next:", f"python {final / 'guard_selective.py'} --prepare")
    return 0


# ---------------------------------------------------------------- release

RELEASE_PLATFORMS = ("windows", "linux", "macos")
RELEASE_DATE = (2026, 1, 1, 0, 0, 0)  # fixed entry times: the same inputs give the same ZIP
# The tester's install steps, shipped as INSTALL.md: @FOLDER@ becomes the folder the ZIP unpacks to,
# @VERSION@ the loader version, @BITWIG_VERSIONS@ the Bitwig versions the release's maps support.
INSTALL_NOTES = {p: TOOLS / "release" / f"INSTALL-{p}.md" for p in RELEASE_PLATFORMS}
LEGAL_FILES = (ws.BML / "LICENSE", ws.BML / "THIRD_PARTY_NOTICES.md")  # shipped at the release root
AGENTS = {"windows": "bitwig-jvmti-tracer.dll", "linux": "bitwig-jvmti-tracer.so", "macos": "bitwig-jvmti-tracer.dylib"}


def core_platforms(path: str) -> set[str]:
    """The platforms a core file (its path in the core, e.g. loader/prepare-mod.cmd) ships on. Windows runs
    the .cmd, .bat and .ps1 scripts and the DLL; Linux and macOS run the .sh scripts (one set: loader/platform.sh
    tells the two apart), Linux loads the .so, macOS the .dylib, and only macOS has BML.command. The agent's
    platform build inputs go with their platform: include/linux/ and include/darwin/ (jni_md.h), and on macOS
    build-agent-mac.sh and the dylib's record. Every other file (the jars, the agent's source, the docs,
    schemas and tests) ships on every platform."""
    name, suffix = Path(path).name, Path(path).suffix
    if (suffix in (".dylib", ".command") or path.startswith("loader/jvmti/include/darwin/")
            or name in ("build-agent-mac.sh", "bitwig-jvmti-tracer.dylib.sha256")):
        return {"macos"}
    if suffix == ".so" or path.startswith("loader/jvmti/include/linux/"):
        return {"linux"}
    if suffix in (".cmd", ".bat", ".ps1", ".dll"):
        return {"windows"}
    if suffix == ".sh":
        return {"linux", "macos"}
    return set(RELEASE_PLATFORMS)


def prune_core(stage: Path, platform: str) -> list[str]:
    """Deletes the unpacked core's files for other platforms (core_platforms); returns their paths."""
    removed = []
    for p in sorted(p for p in stage.rglob("*") if p.is_file()):
        r = p.relative_to(stage).as_posix()
        if platform not in core_platforms(r):
            p.unlink()
            removed.append(r)
            d = p.parent
            while d != stage and not any(d.iterdir()):  # e.g. include/darwin/, emptied
                d.rmdir()
                d = d.parent
    return removed


def release_builds(platform: str) -> list[str]:
    """The platform's builds in <maps>/, oldest first: 6.0 ... 6.1.4 for Windows, linux-6.1.4 for Linux."""
    pattern = (r"" if platform == "windows" else re.escape(platform) + "-") + r"\d+(\.\d+)*"
    builds = [p.name for p in ws.MAPS.iterdir() if p.is_dir() and re.fullmatch(pattern, p.name)]
    if not builds:
        raise SystemExit(f"no {platform} map packs in {rel(ws.MAPS)}/")
    return sorted(builds, key=lambda b: [int(x) for x in build_platform(b)[1].split(".")])


def write_zip(archive: Path, folder: Path, top: str, unix_modes: bool = False) -> None:
    """`folder` as `top/...`, entries sorted with fixed times, so a rebuild is byte-identical. Empty folders
    get their own entries (some mods need one, e.g. resources/config). unix_modes: marked as made on Unix,
    .sh and .command files executable (for a Mac)."""
    paths = sorted(p for p in folder.rglob("*") if p.is_file() or not any(p.iterdir()))
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p in paths:
            entry = f"{top}/{p.relative_to(folder).as_posix()}"
            if p.is_dir():
                info = zipfile.ZipInfo(entry + "/", RELEASE_DATE)
                info.external_attr = (0o40755 << 16) | 0x10
                if unix_modes:
                    info.create_system = 3
                z.writestr(info, b"")
                continue
            info = zipfile.ZipInfo(entry, RELEASE_DATE)
            info.compress_type, info.external_attr = zipfile.ZIP_DEFLATED, 0o644 << 16
            if unix_modes:
                info.create_system = 3
                info.external_attr = (0o100755 if p.suffix in EXECUTABLE else 0o100644) << 16
            z.writestr(info, p.read_bytes())


def build_modkit() -> Path:
    """The kit's jars from source (modkit/build.py rebuilds only what changed); the kit's bml-api.jar is the
    loader's, so a mismatch means one of them is stale."""
    import subprocess
    subprocess.run([sys.executable, str(ROOT / "modkit" / "build.py")], check=True)
    return ROOT / "modkit" / "out"


def release_mods(values: list[str] | None, platforms: list[str]) -> dict[str, list[str] | None]:
    """--mods values -> the mod ids per platform built (None: every accepted mod). "a,b" narrows every
    platform built; "linux=a,b" only that platform."""
    out: dict[str, list[str] | None] = {p: None for p in platforms}
    for value in values or []:
        platform, sep, ids = value.partition("=")
        if not sep:
            platform, ids = None, value
        elif platform not in out:
            raise SystemExit(f"--mods {value}: {platform!r} is not a platform being built ({', '.join(platforms)})")
        for p in [platform] if platform else platforms:
            out[p] = [i for i in ids.split(",") if i]
    return out


def release_unavailable(values: list[str] | None) -> dict[str, set[str]]:
    """--unavailable-on values (ID=VERSION) -> mod id -> the Bitwig versions it is known not to translate to."""
    out: dict[str, set[str]] = {}
    for value in values or []:
        mod_id, sep, bitwig = value.partition("=")
        if not sep or not mod_id or not bitwig:
            raise SystemExit(f"--unavailable-on {value}: expected ID=VERSION, e.g. add-to-drum-machine=6.0")
        out.setdefault(mod_id, set()).add(bitwig)
    return out


def build_release(platforms: list[str], only: dict[str, list[str] | None], out_dir: Path,
                  loader: Path | None = None, unavailable: dict[str, set[str]] | None = None) -> int:
    """A tester release per platform: BML-<loader>-<platform>.zip, an install folder that runs as it is
    unpacked (loader, the platform's map packs, the frameworks and the accepted mods, no theme or other test
    content), plus the mod kit and the guide. A further ZIP holds every platform's map pack ZIPs in a
    workspace's maps/<build>/ layout, for testers who build mods with a clone of BML. No mod is chosen per
    version here: the loader lists a mod the installed Bitwig's map can't translate as unavailable, unticked.
    `unavailable` names the gaps that are known and accepted; any other gap refuses the release."""
    loader_zip = loader or current_loader()
    version = re.sub(r"^BML-|-core$", "", loader_zip.stem)
    out_dir.mkdir(parents=True, exist_ok=True)
    kit = build_modkit()
    for platform in platforms:
        platform_release(platform, only.get(platform), out_dir, loader_zip, version, kit, unavailable or {})

    maps_stage = out_dir / f"BML-{version}-workspace-maps"
    if maps_stage.exists():
        shutil.rmtree(maps_stage)
    for build in (b for p in RELEASE_PLATFORMS for b in release_builds(p)):
        map_zip = map_pack(build)[0]
        (maps_stage / "maps" / build).mkdir(parents=True)
        shutil.copy2(map_zip, maps_stage / "maps" / build / map_zip.name)
    maps_archive = out_dir / f"{maps_stage.name}.zip"
    with zipfile.ZipFile(maps_archive, "w", zipfile.ZIP_STORED) as z:  # the packs are ZIPs already
        for p in sorted(maps_stage.rglob("*.zip")):
            info = zipfile.ZipInfo(p.relative_to(maps_stage).as_posix(), RELEASE_DATE)
            info.external_attr = 0o644 << 16
            z.writestr(info, p.read_bytes())
    shutil.rmtree(maps_stage)
    print("WORKSPACE_MAPS", maps_archive, "sha256=" + sha_file(maps_archive))
    return 0


def platform_release(platform: str, only: list[str] | None, out_dir: Path, loader_zip: Path, version: str,
                     kit: Path, unavailable: dict[str, set[str]]) -> Path:
    name = f"BML-{version}-{platform}"
    stage = out_dir / name
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)

    install_core(stage, loader_zip, mac=True)  # prune_core decides about BML.command
    left_out = prune_core(stage, platform)
    agent = stage / "loader" / "jvmti" / "bin" / AGENTS[platform]
    if not agent.is_file():
        raise SystemExit(f"{loader_zip.name} has no {platform} agent (loader/jvmti/bin/{AGENTS[platform]})")
    # The core's README.md describes an old loader; INSTALL.md is the release's own note.
    (stage / "README.md").unlink(missing_ok=True)
    for legal in LEGAL_FILES:
        shutil.copy2(legal, stage / legal.name)

    packs, map_zips = [], []
    builds = release_builds(platform)
    for build in builds:
        map_zip, meta, sources = map_pack(build)
        install_map(stage, map_zip)
        map_zips.append((build, map_zip, sources))
        packs.append({"bitwig": build, "map": map_zip.name, "map_sha256": sha_file(map_zip),
                      "sources": sorted(sources)})
    versions = [build_platform(b)[1] for b in builds]
    note = INSTALL_NOTES[platform].read_text("utf8").replace("@FOLDER@", name).replace("@VERSION@", version).replace(
        "@BITWIG_VERSIONS@", versions[0] if len(versions) == 1 else ", ".join(versions[:-1]) + " or " + versions[-1])
    if re.search(r"@[A-Z_]+@", note):
        raise SystemExit(f"{INSTALL_NOTES[platform].name}: unknown placeholder {re.search(r'@[A-Z_]+@', note).group()}")
    (stage / "INSTALL.md").write_bytes(note.encode("utf8"))

    wanted = accepted_mods()
    if only:
        missing = set(only) - set(wanted)
        if missing:
            raise SystemExit(f"Not accepted: {sorted(missing)}")
        wanted = {k: v for k, v in wanted.items() if k in only}
    installed, _ = install_mods(stage, wanted, None)
    known_gaps = []
    for m in installed:
        mod_dir = stage / "mods" / m["id"]
        if not (mod_dir / "src").is_dir():  # standard 9
            raise SystemExit(f"{m['zip']} has no src/: a release ships only mods with their source")
        # The loader lists a mod the installed version can't translate as unavailable, and it stays unticked.
        # A gap ships only when it is named, so an unexpected one still stops the release.
        for build, map_zip, sources in map_zips:
            if m["built_against"] == build or m["built_against"] not in sources:
                continue
            gaps = uses_gaps(mod_dir, m["built_against"], map_zip)
            named = build_platform(build)[1] in unavailable.get(m["id"], ())
            if gaps and not named:
                raise SystemExit(f"{m['id']} {m['version']}: the {build} map can't translate {len(gaps)} of its Bitwig "
                                 f"dependencies (e.g. {gaps[0]}); fix the mod, leave it out with --mods, or name the "
                                 f"gap with --unavailable-on {m['id']}={build_platform(build)[1]}")
            if named and not gaps:
                raise SystemExit(f"--unavailable-on {m['id']}={build_platform(build)[1]}: the {build} map translates "
                                 "this mod; remove the option")
            if gaps:
                known_gaps.append({"id": m["id"], "version": m["version"], "bitwig": build, "missing": gaps})
    unused = sorted(set(unavailable) - {m["id"] for m in installed})
    if unused:
        raise SystemExit(f"--unavailable-on names mods not in this release: {unused}")
    if (stage / "components").exists() or (stage / "state").exists() or (stage / "config").exists():
        raise SystemExit("A release ships no components, no saved selections and no config (nor the test-install marker)")

    (stage / "modkit").mkdir()
    for jar in ("bml-api.jar", "bml-modkit.jar"):
        shutil.copy2(kit / jar, stage / "modkit" / jar)
    shutil.copy2(ROOT / "modkit" / "README.md", stage / "modkit" / "README.md")
    if sha_file(stage / "modkit" / "bml-api.jar") != sha_file(stage / "loader" / "api" / "bml-api.jar"):
        raise SystemExit("modkit/out/bml-api.jar differs from the loader's: rebuild both from source")
    shutil.copytree(ROOT / "docs", stage / "docs", ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))

    unix = platform != "windows"
    if unix:  # sh refuses a script with CR line ends
        crlf = [p.relative_to(stage).as_posix() for p in stage.rglob("*")
                if p.suffix in EXECUTABLE and p.is_file() and b"\r" in p.read_bytes()]
        if crlf:
            raise SystemExit(f"{name}: shell scripts with CR line ends: {crlf}")

    (stage / "BUILD-INFO.json").write_text(json.dumps({
        "platform": platform, "loader": loader_zip.name, "loader_sha256": sha_file(loader_zip),
        "core_left_out": left_out, "maps": packs,
        "mods": [{k: m[k] for k in ("id", "version", "built_against", "zip_sha256")} for m in installed],
        "unavailable": known_gaps,
        "modkit": {jar: sha_file(stage / "modkit" / jar) for jar in ("bml-api.jar", "bml-modkit.jar")},
    }, indent=2) + "\n", "utf8", newline="\n")

    archive = out_dir / f"{name}.zip"
    write_zip(archive, stage, name, unix_modes=unix)
    print("RELEASE_BUILT", archive, f"{len(packs)} maps, {len(installed)} mods", "sha256=" + sha_file(archive))
    for gap in known_gaps:
        print("  unavailable", gap["id"], gap["version"], "on", gap["bitwig"], f"({len(gap['missing'])} names)")
    return archive


# ---------------------------------------------------------------- clear

def refuse_if_in_use(install: Path, action: str) -> None:
    """Windows refuses to rename a folder while any file in it is open, so a rename tells us whether Bitwig or
    the BML window still runs from this install, before anything is copied or deleted."""
    probe = install.with_name(install.name + ".in-use-check")
    try:
        install.rename(probe)
    except OSError:
        raise SystemExit(f"{rel(install)} is in use: close Bitwig and the BML window started from it, then run {action} again")
    probe.rename(install)


def clear_install(name: str, note: str) -> int:
    """After a passed user test: keep the results in <results>/<name>/, delete the install (standard 2)."""
    install = ws.INSTALLS / name
    if not (install / "BUILD-INFO.json").is_file():
        raise SystemExit(f"not a generated test install: {rel(install)}")
    refuse_if_in_use(install, "clear")
    out = ws.RESULTS / name
    # an earlier --replace may already have kept sessions here (replaced-*/); anything else is a clash
    if out.exists() and any(not p.name.startswith("replaced-") for p in out.iterdir()):
        raise SystemExit(f"{rel(out)} already holds results")
    out.mkdir(parents=True, exist_ok=True)
    for f in ("BUILD-INFO.json", "OFFLINE-SELECTIVE-SUMMARY.json"):
        if (install / f).is_file():
            shutil.copy2(install / f, out / f)
    sessions = sorted(p for p in (install / "sessions").glob("*") if p.is_dir())
    for s in sessions:
        shutil.copytree(s, out / "sessions" / s.name)
    (out / "RESULT.json").write_text(json.dumps({
        "result": "PASS", "recorded_utc": stamp(), "note": note, "sessions": [s.name for s in sessions],
    }, indent=2) + "\n", "utf8")
    shutil.rmtree(install)
    (install.parent / f"{install.name}.zip").unlink(missing_ok=True)  # a macOS install's copy for the Mac
    print(f"CLEARED {rel(install)}  results -> {rel(out)} ({len(sessions)} sessions)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("suite", help="build <installs>/<build>")
    s.add_argument("version", metavar="build", help="6.1.4 (Windows), linux-6.1.4 or macos-6.1.4")
    s.add_argument("--mods", help="comma-separated accepted mod ids to install (default: every accepted mod)")
    s.add_argument("--replace", action="store_true", help="replace an existing install (its sessions are kept in <results>/)")
    s.add_argument("--out", type=Path, help="build somewhere else, e.g. <installs>/<test-name>")
    s.add_argument("--loader", type=Path, help="loader core ZIP to use instead of the current release")
    s.add_argument("--map", type=Path, help="map pack ZIP to use instead of <maps>/<version>/ (to test a new pack)")
    s.add_argument("--mods-from", type=Path, help="install these development mod folders instead of the accepted mods")
    s.add_argument("--skip", action="append", metavar="ID=REASON", help="leave out an accepted mod, with the reason recorded in BUILD-INFO.json (repeatable)")
    s.add_argument("--add-mods", type=Path, action="append", help="also install these development mod folders, replacing accepted mods with the same id (repeatable)")
    s.add_argument("--add-unticked", type=Path, action="append", help="also install these mod folders unticked, as they are; they aren't prepared (repeatable)")
    r = sub.add_parser("release", help="build the tester release per platform in dist/")
    r.add_argument("--platform", choices=(*RELEASE_PLATFORMS, "all"), default="all",
                   help="the platform to build the release for (default: all)")
    r.add_argument("--mods", action="append", metavar="[PLATFORM=]IDS",
                   help="comma-separated mod ids (default: every accepted mod); with linux= etc. only for that "
                        "platform (repeatable)")
    r.add_argument("--unavailable-on", action="append", metavar="ID=VERSION",
                   help="ship this mod although that Bitwig version's maps can't translate it: BML lists it as "
                        "unavailable there; any gap not named refuses the release (repeatable)")
    r.add_argument("--out", type=Path, default=ROOT / "dist")
    r.add_argument("--loader", type=Path, help="loader core ZIP to use instead of the current release")
    c = sub.add_parser("clear", help="after a passed test: keep results in <results>/<name>/ and delete the install")
    c.add_argument("name", help="folder name under <installs>/")
    c.add_argument("--note", required=True, help="what was tested and passed")
    a = ap.parse_args()
    if a.cmd == "clear":
        return clear_install(a.name, a.note)
    if a.cmd == "suite":
        skip = dict(s.split("=", 1) for s in a.skip or [])
        return build_suite(a.version, a.replace, a.out, a.map, a.loader, a.mods_from, a.add_mods, skip,
                           a.mods.split(",") if a.mods else None, a.add_unticked)
    platforms = list(RELEASE_PLATFORMS) if a.platform == "all" else [a.platform]
    return build_release(platforms, release_mods(a.mods, platforms), a.out, a.loader, release_unavailable(a.unavailable_on))


if __name__ == "__main__":
    sys.exit(main())
