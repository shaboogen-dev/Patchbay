"""Build a scratch Windows 6.1.5 -> 6.1.9 map for the targets in the adjacent fixture."""
import argparse
import gzip
import hashlib
import json
import re
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "jvm"))
import classfile as cf

SOURCE_SHA = "d56248d003101ead1381c8a4fd3e256d933773beccbeb3366c9abe744de41a49"
TARGET_SHA = "3302c49ee0ad44a9690581554880f2f1c50fc7ea3aff9311336bc1e2078d5901"
PAIR = "windows-6.1.5__windows-6.1.9"
PACK_ID = "windows-6.1.9-probe"
FIXTURE = Path(__file__).with_name("fixtures") / (PAIR + ".tsv")


def checked_hash(path, expected):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != expected:
        raise ValueError(f"{path}: expected {expected}, got {digest}")


def checked_rows(source, target):
    rows = [line.split("\t") for line in FIXTURE.read_text("utf8").splitlines()
            if line and not line.startswith("#")]
    classes = {row[1]: row[2] for row in rows if row[0] == "C"}
    left = {name: cf.parse(source.read(name + ".class")) for name in classes}
    right = {name: cf.parse(target.read(name + ".class")) for name in classes.values()}
    source_entries = set(source.namelist())
    seen_source, seen_target = set(), set()
    for row in rows:
        kind = row[0]
        if kind == "C":
            _, a, b = row
            source_id, target_id = (kind, a), (kind, b)
            flags = cf.ACC_INTERFACE | cf.ACC_ENUM | cf.ACC_ANNOTATION
            if (left[a].flags ^ right[b].flags) & flags:
                raise ValueError(f"Class kind changed: {row}")
        elif kind in ("M", "F"):
            _, a, name, desc, b, new_name, new_desc = row
            source_id, target_id = (kind, a, name, desc), (kind, b, new_name, new_desc)
            # The two JARs can contain an unrelated overload with the same short name.
            # Require both declarations and translate every Bitwig type in the descriptor.
            def mapped_type(match):
                cls = match[1]
                if cls + ".class" in source_entries:
                    return "L" + classes[cls] + ";"
                return match[0]

            if classes[a] != b or re.sub(r"L([^;]+);", mapped_type, desc) != new_desc:
                raise ValueError(f"Owner or descriptor mismatch: {row}")
            if kind == "M":
                before = left[a].methods[name, desc].flags
                after = right[b].methods[new_name, new_desc].flags
            else:
                before = left[a].fields[name, desc]
                after = right[b].fields[new_name, new_desc]
            if (before ^ after) & cf.ACC_STATIC:
                raise ValueError(f"Static/instance mismatch: {row}")
        else:
            raise ValueError(f"Unknown fixture row: {row}")
        if source_id in seen_source or target_id in seen_target:
            raise ValueError(f"Duplicate fixture mapping: {row}")
        seen_source.add(source_id)
        seen_target.add(target_id)

    # BML resolves inherited members through the full hierarchy of the source JAR.
    for path in sorted(source.namelist()):
        if path.endswith(".class") and not path.startswith("META-INF/"):
            cls = cf.parse(source.read(path))
            for parent in filter(None, [cls.super, *cls.interfaces]):
                rows.append(["H", cls.name, parent])
    return rows


def json_bytes(value):
    return (json.dumps(value, indent=2) + "\n").encode("utf8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("source_jar", "target_jar", "output_zip"):
        parser.add_argument(arg, type=Path)
    args = parser.parse_args()
    checked_hash(args.source_jar, SOURCE_SHA)
    checked_hash(args.target_jar, TARGET_SHA)
    with ZipFile(args.source_jar) as source, ZipFile(args.target_jar) as target:
        rows = checked_rows(source, target)
    builds = [dict(build_key="windows-" + version, bitwig_version=version, platform="windows",
                   architecture="x64", bitwig_jar_sha256=sha)
              for version, sha in (("6.1.5", SOURCE_SHA), ("6.1.9", TARGET_SHA))]
    raw = "".join("\t".join(row) + "\n" for row in rows).encode("utf8")
    payload = {"builds.json": json_bytes(dict(format_version=2, builds=builds)),
               f"remap/{PAIR}.tsv.gz": gzip.compress(raw, mtime=0)}
    sums = {name: hashlib.sha256(data).hexdigest() for name, data in payload.items()}
    payload["map.json"] = json_bytes(dict(format="bml-map-pack-v2", id=PACK_ID, version="0.1.0",
                                          target_build_key="windows-6.1.9", target_jar_sha256=TARGET_SHA,
                                          file_sha256=sums))
    with ZipFile(args.output_zip, "x") as archive:
        for name, data in sorted(payload.items()):
            archive.writestr(ZipInfo(f"{PACK_ID}/{name}"), data, compress_type=ZIP_DEFLATED)
    print(f"{args.output_zip}: " + ", ".join(f"{sum(r[0] == k for r in rows)} {k}" for k in "CFMH"))


if __name__ == "__main__":
    main()
