#!/usr/bin/env python3
"""Build the BML mod kit.

  python modkit/build.py                   -> modkit/out/bml-api.jar, modkit/out/bml-modkit.jar
  python modkit/build.py compile-jar 6.1.3 -> modkit/cache/6.1.3/bitwig613-compile.jar
  python modkit/build.py compile-jar macos-6.1.3 -> modkit/cache/macos-6.1.3/bitwig613-compile.jar

bml-api.jar holds the annotations and the Bml runtime class mods compile with (javac --release 17, so
mods targeting 17 can use it too). Its source is the loader's (loader/src/loader/api/src), because the
loader provides the same classes to mods at runtime; loader/build.py builds the identical jar.
bml-modkit.jar is the build tool, from modkit/src/tool (javac --release 25: it uses the
java.lang.classfile API). Both are rebuilt only when their source changes, with fixed jar timestamps,
so the same source gives the same jars.

A build is "<version>" for Windows or "<platform>-<version>" (macos, linux). The compile jar is derived from
the workspace's bitwig/jars/<version>/, <version>-mac/ or <version>-linux/ (tools/workspace.py; read-only use,
standard 8) and cached under modkit/cache/, which is never committed (standard 12).
"""
from __future__ import annotations
import hashlib, subprocess, sys, tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
OUT = HERE / "out"
CACHE = HERE / "cache"
JDK = Path(r"C:\Program Files\Java\jdk-25.0.4.1\bin")
FIXED_DATE = "2026-01-01T00:00:00Z"
API_SRC = ROOT / "loader/src/loader/api/src"
sys.path.insert(0, str(ROOT / "tools"))
import workspace  # noqa: E402
PARTS = {"bml-api.jar": (API_SRC, "17", None), "bml-modkit.jar": (HERE / "src/tool", "25", "bml.modkit.ModKit")}


def check_toolchain() -> None:
    javac = subprocess.run([str(JDK / "javac"), "-version"], capture_output=True, text=True).stdout.strip()
    if javac != "javac 25.0.4.1":  # standard 11: pinned toolchain
        raise SystemExit(f"expected javac 25.0.4.1, found {javac!r}")


def source_hash(src: Path) -> str:
    h = hashlib.sha256()
    for f in sorted(src.rglob("*.java")):
        h.update(f.relative_to(src).as_posix().encode() + b"\0" + f.read_bytes() + b"\0")
    return h.hexdigest()


def build_jar(src: Path, release: str, main: str | None, target: Path) -> None:
    """javac --release <release> over src/**/*.java, then a jar with fixed timestamps."""
    with tempfile.TemporaryDirectory() as tmp:
        classes = Path(tmp)
        files = [str(f) for f in sorted(src.rglob("*.java"))]
        subprocess.run([str(JDK / "javac"), "--release", release, "-nowarn", "-encoding", "UTF-8",
                        "-d", str(classes)] + files, check=True)
        target.unlink(missing_ok=True)
        subprocess.run([str(JDK / "jar"), "--create", "--file", str(target), f"--date={FIXED_DATE}"]
                       + (["--main-class", main] if main else []) + ["-C", str(classes), "."], check=True)


def ensure() -> dict[str, Path]:
    """Build whichever kit jar is missing or older than its source; return their paths."""
    out = {}
    for jar, (src, release, main) in PARTS.items():
        target, stamp = OUT / jar, OUT / (jar + ".src-sha256")
        want = source_hash(src)
        if not (target.is_file() and stamp.is_file() and stamp.read_text().strip() == want):
            check_toolchain()
            OUT.mkdir(exist_ok=True)
            build_jar(src, release, main, target)
            stamp.write_text(want + "\n")
            print(f"BUILT {target.relative_to(ROOT)}")
        out[jar] = target
    return out


def bitwig_jars(build: str) -> tuple[Path, Path]:
    jars = tuple(workspace.build_jars(build)[:2])
    for j in jars:
        if not j.is_file():
            raise SystemExit(f"missing {j}")
    return jars


def compile_jar(build: str) -> Path:
    """The signatures-only jar mods compile against, regenerated when Bitwig's jar or the kit changes."""
    kit = ensure()["bml-modkit.jar"]
    bitwig, _ = bitwig_jars(build)
    target = CACHE / build / f"bitwig{build.rpartition('-')[2].replace('.', '')}-compile.jar"
    stamp = target.with_suffix(".jar.inputs-sha256")
    want = hashlib.sha256(hashlib.sha256(bitwig.read_bytes()).digest() + hashlib.sha256(kit.read_bytes()).digest()).hexdigest()
    if not (target.is_file() and stamp.is_file() and stamp.read_text().strip() == want):
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([str(JDK / "java"), "-jar", str(kit), "compile-jar", str(bitwig), str(target)], check=True)
        stamp.write_text(want + "\n")
    return target


def main() -> int:
    if len(sys.argv) == 3 and sys.argv[1] == "compile-jar":
        print(compile_jar(sys.argv[2]))
        return 0
    if len(sys.argv) != 1:
        raise SystemExit(__doc__)
    for p in ensure().values():
        print(p)
    return 0


if __name__ == "__main__":
    sys.exit(main())
