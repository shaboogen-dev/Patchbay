#!/usr/bin/env python3
"""BML independent map packages: validate, refresh, export and inspect.

Only operates on maps/ and the map-backup directory. Never edits BML binaries,
mod archives or Bitwig JARs. Map packages are installed folders:
    maps/<map-id>/map.json
    maps/<map-id>/builds.json
    maps/<map-id>/compatibility-v2.json
    maps/<map-id>/remap/<source>__<target>.tsv.gz
"""

import argparse
from hashlib import sha256
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import tempfile
import zipfile

IDENTITY = re.compile(r"^[A-Za-z][A-Za-z0-9_.-]*$")
VERSION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")
PAYLOAD = re.compile(r"^(?:builds\.json|compatibility-v2\.json|remap/[A-Za-z0-9_.-]+__[A-Za-z0-9_.-]+\.tsv\.gz)$")
HEX = re.compile(r"^[a-fA-F0-9]{64}$")
SCHEMA = "bml-map-pack-v1"


def sha(path: Path) -> str:
    with path.open("rb") as src:
        digest = sha256()
        for data in iter(lambda: src.read(65536), b""):
            digest.update(data)
    return digest.hexdigest()


def load(path: Path) -> dict:
    obj = json.loads(path.read_text("utf-8-sig"))
    if not isinstance(obj, dict):
        raise ValueError(f"Expected JSON object in {path}")
    return obj


def check(directory: Path, *, allow_unsealed=False) -> dict:
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError(f"Invalid map directory: {directory}")
    meta = load(directory / "map.json")
    if meta.get("format") != SCHEMA:
        raise ValueError("Unsupported map package schema")
    for key in ("id", "target_build_key"):
        if not isinstance(meta.get(key), str) or not IDENTITY.fullmatch(meta[key]):
            raise ValueError(f"Invalid map {key}")
    if not isinstance(meta.get("version"), str) or not VERSION.fullmatch(meta["version"]):
        raise ValueError("Invalid map version")
    if meta["id"] != directory.name:
        raise ValueError(f"map.json id must match package directory: {directory}")
    if not HEX.fullmatch(meta.get("target_jar_sha256", "")):
        raise ValueError("Invalid target Bitwig jar SHA-256")
    builds = load(directory / "builds.json")
    selected = [x for x in builds.get("builds", []) if
                x.get("build_key") == meta["target_build_key"] and
                x.get("bitwig_jar_sha256", "").lower() == meta["target_jar_sha256"].lower()]
    if len(selected) != 1:
        raise ValueError("map.json target does not uniquely match builds.json")
    compatibility = load(directory / "compatibility-v2.json")
    if not isinstance(compatibility.get("groups"), list):
        raise ValueError("compatibility-v2.json lacks groups array")
    remap_dir = directory / "remap"
    if not remap_dir.is_dir() or remap_dir.is_symlink():
        raise ValueError("Missing remap directory")
    payload = sorted(p for p in directory.rglob("*") if p.is_file() and p.name != "map.json")
    actual = {}
    for file in payload:
        if file.is_symlink():
            raise ValueError(f"Symlink is not an allowed map payload: {file}")
        rel = file.relative_to(directory).as_posix()
        if not PAYLOAD.fullmatch(rel):
            raise ValueError(f"Unexpected map package file: {rel}")
        actual[rel] = sha(file)
    if not any(x.startswith("remap/") for x in actual):
        raise ValueError("Map pack requires at least one helper remap table")
    expected = meta.get("file_sha256")
    if allow_unsealed:
        return meta | {"file_sha256": actual}
    if expected != actual:
        raise ValueError("Map package content SHA-256 mismatch; reseal after intentional edits")
    return meta


def seal(directory: Path) -> None:
    meta = check(directory, allow_unsealed=True)
    meta["file_sha256"] = {p.relative_to(directory).as_posix(): sha(p) for p in
                           sorted(directory.rglob("*")) if p.is_file() and p.name != "map.json"}
    (directory / "map.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    check(directory)
    print(f"SEALED map={meta['id']} version={meta['version']} files={len(meta['file_sha256'])}")


def bundle(directory: Path, output: Path) -> None:
    meta = check(directory)
    if output.exists():
        raise FileExistsError(f"Refusing to overwrite map ZIP: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "x", zipfile.ZIP_DEFLATED, compresslevel=7) as dest:
        for file in sorted(directory.rglob("*")):
            if file.is_file():
                dest.write(file, (PurePosixPath(meta["id"]) / file.relative_to(directory).as_posix()).as_posix())
    with zipfile.ZipFile(output) as verify:
        if verify.testzip():
            raise ValueError("Exported map ZIP has CRC error")
    print(f"EXPORTED {output} SHA256={sha(output)}")


def install(archive: Path, bml_root: Path) -> None:
    if not archive.is_file():
        raise FileNotFoundError(archive)
    maps = bml_root / "maps"
    maps.mkdir(parents=True, exist_ok=True)
    backups = bml_root / "state" / "map-backups"
    with zipfile.ZipFile(archive) as src:
        if src.testzip() is not None:
            raise ValueError("Map archive has ZIP CRC error")
        members = [e for e in src.infolist() if not e.is_dir()]
        if not members or sum(e.file_size for e in members) > 200 * 1024 * 1024:
            raise ValueError("Empty or oversized map ZIP")
        names = [PurePosixPath(e.filename) for e in members]
        ids = {n.parts[0] for n in names}
        if len(ids) != 1 or not IDENTITY.fullmatch(next(iter(ids))):
            raise ValueError("Map ZIP must have exactly one map-id root directory")
        mid = next(iter(ids))
        for e, n in zip(members, names):
            if len(n.parts) < 2 or not PAYLOAD.fullmatch("/".join(n.parts[1:])) and n.parts[1:] != ("map.json",):
                raise ValueError(f"Unexpected ZIP path: {e.filename}")
            if ".." in n.parts or "\\" in e.filename or (e.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f"Unsafe ZIP path/link: {e.filename}")
        with tempfile.TemporaryDirectory(prefix="bml-map-import-", dir=maps.parent) as temp:
            staged = Path(temp) / mid
            for e, n in zip(members, names):
                target = staged.joinpath(*n.parts[1:])
                target.parent.mkdir(parents=True, exist_ok=True)
                with src.open(e) as read, target.open("wb") as write:
                    shutil.copyfileobj(read, write)
            meta = check(staged)
            current = maps / mid
            for neighbor in maps.iterdir():
                if neighbor.name.casefold() == mid.casefold() and neighbor.name != mid:
                    raise ValueError(f"Case-colliding map ID already installed: {neighbor.name}")
                if neighbor.is_dir() and neighbor != current:
                    p = neighbor / "map.json"
                    if p.exists() and load(p).get("target_jar_sha256","").lower() == meta["target_jar_sha256"].lower():
                        raise ValueError("A different map package already targets this exact Bitwig build")
            previous = None
            if current.exists():
                prior = check(current)
                if prior["version"] == meta["version"] and prior["file_sha256"] == meta["file_sha256"]:
                    print(f"UNCHANGED {mid} {meta['version']}")
                    return
                backups.mkdir(parents=True, exist_ok=True)
                previous = backups / (mid + "-previous")
                if previous.exists():
                    # Keep backups only when necessary; never silently delete an unreviewed one.
                    raise FileExistsError(f"Prior map backup needs review before another refresh: {previous}")
                os.replace(current, previous)
            try:
                os.replace(staged, current)
            except Exception:
                if previous is not None:
                    os.replace(previous, current)
                raise
    print(f"INSTALLED map={meta['id']} version={meta['version']} at {current}")
    print("The next BML prepare will automatically use this map. BML executable was not changed.")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)
    for cmd in ("check", "seal"):
        sub.add_parser(cmd).add_argument("folder", type=Path)
    x = sub.add_parser("bundle"); x.add_argument("folder", type=Path); x.add_argument("output", type=Path)
    x = sub.add_parser("install"); x.add_argument("archive", type=Path); x.add_argument("--bml-root", type=Path, required=True)
    x = sub.add_parser("list"); x.add_argument("--bml-root", type=Path, required=True)
    args = p.parse_args()
    if args.cmd == "check":
        meta = check(args.folder); print(f"VALID map={meta['id']} version={meta['version']}")
    elif args.cmd == "seal":
        seal(args.folder)
    elif args.cmd == "bundle":
        bundle(args.folder, args.output)
    elif args.cmd == "install":
        install(args.archive, args.bml_root)
    else:
        maps=args.bml_root/"maps"
        for item in sorted(maps.iterdir()) if maps.is_dir() else []:
            meta=check(item)
            print(f"{meta['id']}\t{meta['version']}\t{meta['target_build_key']}\t{meta['target_jar_sha256']}")


if __name__ == "__main__":
    main()
