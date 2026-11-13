#!/usr/bin/env python3
"""Build the BML loader core release from loader/src.

  python loader/build.py            -> loader/releases/BML-<version>-core.zip
  python loader/build.py --replace  rebuild an existing version
  python loader/build.py --mac-dylib PATH   take the Mac-built agent from PATH (default: MAC_INPUT)
  python loader/build.py --no-mac   a development build without the macOS agent, not a release:
                                    loader/releases/BML-<version>-core-no-mac.zip
  python loader/build.py --mac-kit DIR      -> DIR/bml-mac-agent-kit/ and DIR/bml-mac-agent-kit.zip,
                                    what a Mac needs to build the agent (nothing else is built)

The version comes from LOADER_VERSION in JavaPreparer.java. The control app (the Patchbay window
and the preparer) is compiled with JDK 25 (--release 21), the same toolchain as every accepted
loader, and jar entries get a fixed timestamp so the same source always produces the same jar. A
file that needs a later Java is listed in APP_RELEASE_OVERRIDES and compiled on its own at that
release (DarkTitleBar.java: the Foreign Function API, final in 22); the rest of the app never names
it, so the jar still runs on Java 21. Files under src/loader/app/resources are copied into the jar
(the bundled fonts, OFL, when present). The jar may use only modules Bitwig's own Java runtime has
(BITWIG_RUNTIME_MODULES): jdeps checks it and the build refuses otherwise, since the window runs
on Bitwig's Java.

The eight classfile tools in src/loader/tools (preparer, patchers, access widener, field adder, remapper, verifier) are compiled
from src/loader/tools/src the same way; no prebuilt jar is taken from src/.

The JVMTI tracer DLL is compiled from src/loader/jvmti/src with MSVC (Visual Studio Build Tools
2026, the toolchain that built every accepted tracer), using the original flags plus /d1trimfile,
which removes the source root from the paths the compiler records (MSVC derives some symbol names
from the source file's full path). The PE timestamps are set to FIXED_EPOCH. So the same source
gives the same DLL, from whichever folder it is built.

The same tracer source is compiled for Linux x86-64 as bitwig-jvmti-tracer.so, beside the DLL in
loader/jvmti/bin, so one release carries both. It is built with g++ inside WSL (distribution
WSL_DISTRO, GCC 13 from Ubuntu 24.04 build-essential, standard 11; the exact version is pinned in
GXX_VERSION and recorded in RELEASE-MANIFEST.json). The JNI headers are the pinned JDK's (the same
on every platform) with the Linux jni_md.h from src/loader/jvmti/include/linux. Fixed flags, no
debug information, symbols stripped, build paths mapped away (-ffile-prefix-map) and a fixed
-frandom-seed, so the same source gives the same .so from whichever folder it is built.
libstdc++ and libgcc are linked in statically and kept private (like the DLL's /MT), so the
agent does not depend on, or interfere with, the C++ runtime of the system or of Bitwig.

The macOS agent, bitwig-jvmti-tracer.dylib (universal arm64 + x86_64), can only be built on a Mac
(Apple's SDK), and build outputs never go into git (standard 12). So it is built on a Mac from
src/loader/jvmti with build-agent-mac.sh (the pinned Xcode command-line tools, standard 11; the
kit from --mac-kit carries the source, the build script and the pinned JDK's headers), and
src/loader/jvmti/bitwig-jvmti-tracer.dylib.sha256 records that build: the toolchain, the
SHA-256 of each input and of the dylib. This build takes the Mac-built file from MAC_INPUT (ignored
by git) or --mac-dylib, and only when the recorded inputs are still the source's and the file's
SHA-256 is the recorded one; it places it beside the DLL. Without it, it refuses to build a
release; --no-mac builds one without macOS support under another name (and without BML.command,
the launcher a Mac user double-clicks in Finder).
"""
from __future__ import annotations
import argparse, hashlib, json, re, shutil, subprocess, sys, tempfile, zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = HERE / "src"
JDK = Path(r"C:\Program Files\Java\jdk-25.0.4.1\bin")
VCVARS = Path(r"C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat")
JDK_HOME = JDK.parent
FIXED_DATE = "2026-01-01T00:00:00Z"
FIXED_EPOCH = 1767225600  # the same instant, for PE headers
WSL_DISTRO = "Ubuntu-24.04"
GXX_VERSION = "g++ (Ubuntu 13.3.0-6ubuntu2~24.04.1) 13.3.0"  # standard 11: pinned toolchain
MAC_DIR = SRC / "loader/jvmti"
MAC_RECORD = MAC_DIR / "bitwig-jvmti-tracer.dylib.sha256"
MAC_INPUT = HERE / "mac-input" / "bitwig-jvmti-tracer.dylib"  # where the Mac-built agent is put (ignored by git)
MAC_COMMAND = "BML.command"  # macOS: double-clicked in Finder, runs bml-ui.sh; in releases with the macOS agent only
EXECUTABLE = (".sh", ".command")  # mode 0755 in the ZIP; LF line endings required
# The Mac build's inputs (record key -> file): the agent source and build script from loader/src,
# the pinned JDK's JNI headers (the same on every platform), as build-agent-mac.sh lays them out.
MAC_INPUTS = {
    "src/bitwig_jvmti_tracer.cpp": MAC_DIR / "src/bitwig_jvmti_tracer.cpp",
    "include/darwin/jni_md.h": MAC_DIR / "include/darwin/jni_md.h",
    "build-agent-mac.sh": MAC_DIR / "build-agent-mac.sh",
    "jdk-include/jni.h": JDK_HOME / "include/jni.h",
    "jdk-include/jvmti.h": JDK_HOME / "include/jvmti.h",
}


# App sources compiled at another release than 21 (path under src/loader/app/src -> release).
APP_RELEASE_OVERRIDES = {"dev/bitwigre/bml/DarkTitleBar.java": "22"}
# The modules of the Java runtime every Bitwig 6.0-6.1.4 ships (its release file, on all three platforms).
BITWIG_RUNTIME_MODULES = set("""java.base java.compiler java.datatransfer java.xml java.prefs java.desktop java.logging
java.management java.security.sasl java.naming java.scripting java.security.jgss java.transaction.xa java.sql
jdk.charsets jdk.crypto.ec jdk.httpserver jdk.jdwp.agent jdk.localedata jdk.unsupported jdk.xml.dom""".split())
# The bundled fonts (SIL Open Font License); the window falls back to the platform's fonts without them.
FONTS = ["Outfit-Regular.ttf", "Outfit-SemiBold.ttf", "JetBrainsMono-Regular.ttf"]


def build_app(tmp: Path, classes: Path) -> list[str]:
    """Compiles the control app into classes (overrides at their own release, against the rest) and
    copies src/loader/app/resources in. Returns the bundled fonts that are missing."""
    app = SRC / "loader/app/src"
    overrides = {(app / k).resolve(): v for k, v in APP_RELEASE_OVERRIDES.items()}
    sources = sorted(p for p in app.rglob("*.java") if p.resolve() not in overrides)
    (tmp / "sources.txt").write_text("\n".join(f'"{p.as_posix()}"' for p in sources))
    subprocess.run([str(JDK / "javac"), "--release", "21", "-nowarn", "-encoding", "UTF-8", "-d", str(classes), f"@{tmp / 'sources.txt'}"], check=True)
    for path, release in sorted(overrides.items()):
        subprocess.run([str(JDK / "javac"), "--release", release, "-nowarn", "-encoding", "UTF-8", "-cp", str(classes),
                        "-d", str(classes), str(path)], check=True)
    resources = SRC / "loader/app/resources"
    if resources.is_dir():
        for f in sorted(resources.rglob("*")):
            if f.is_file():
                (classes / f.relative_to(resources)).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(f, classes / f.relative_to(resources))
    font_dir = classes / "dev/bitwigre/bml/fonts"
    return [f for f in FONTS if not (font_dir / f).is_file()]


def check_modules(jar: Path) -> str:
    """The modules the jar needs (jdeps); refuses any Bitwig's runtime doesn't have."""
    r = subprocess.run([str(JDK / "jdeps"), "--print-module-deps", "--ignore-missing-deps", "--multi-release", "25", str(jar)],
                       capture_output=True, text=True)
    if r.returncode:
        raise SystemExit("jdeps failed:\n" + r.stdout + r.stderr)
    needed = set(r.stdout.strip().split(","))
    outside = sorted(needed - BITWIG_RUNTIME_MODULES)
    if outside:
        raise SystemExit(f"{jar.name} needs {', '.join(outside)}, which Bitwig's Java runtime doesn't have")
    return ",".join(sorted(needed))


def sha(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def version() -> str:
    text = (SRC / "loader/app/src/dev/bitwigre/bml/JavaPreparer.java").read_text("utf8")
    return re.search(r'LOADER_VERSION\s*=\s*"([^"]+)"', text).group(1)


# jar name -> (main class, sources under src/loader/tools/src)
TOOLS = {
    "bitwig-access-widener.jar": ("dev.bitwigre.loader.AccessWidener", ["AccessWidener.java"]),
    "bitwig-add-method-patcher.jar": ("dev.bitwigre.loader.AddMethodPatcher", ["AddMethodPatcher.java"]),
    "bitwig-deep-verifier.jar": ("dev.bitwigre.loader.DeepClassVerifier", ["DeepClassVerifier.java"]),
    "bitwig-field-adder.jar": ("dev.bitwigre.loader.FieldAdder", ["FieldAdder.java"]),
    "bitwig-helper-remapper.jar": ("HelperBytecodeRemapper", ["HelperBytecodeRemapper.java", "dev/bitwigre/bml/bridge/BmlReflectionBridgeTemplate.java"]),
    "bitwig-invoke-replacer.jar": ("InvokeReplacer", ["InvokeReplacer.java"]),
    "bitwig-mod-preparer.jar": ("dev.bitwigre.loader.SemanticModPreparer", ["SemanticModPreparer.java"]),
    "bitwig-typed-local-patcher.jar": ("dev.bitwigre.loader.TypedLocalPatcher", ["TypedLocalPatcher.java"]),
}


def build_tools(tmp: Path, tools_dir: Path) -> dict[str, str]:
    src = SRC / "loader/tools/src"
    hashes = {}
    for jar, (main, sources) in TOOLS.items():
        classes = tmp / ("tool-" + jar); classes.mkdir()
        subprocess.run([str(JDK / "javac"), "--release", "21", "-nowarn", "-encoding", "UTF-8", "-d", str(classes)]
                       + [str(src / s) for s in sources], check=True)
        out = tools_dir / jar
        subprocess.run([str(JDK / "jar"), "--create", "--file", str(out), f"--date={FIXED_DATE}", "--main-class", main,
                        "-C", str(classes), "."], check=True)
        hashes[jar] = sha(out)
    return hashes


def build_api(tmp: Path, out: Path) -> str:
    """bml-api.jar (annotations + the Bml runtime class), the jar modkit/build.py builds too: same
    flags (--release 17), same fixed date. The loader copies its classes into the prepared helper.jar."""
    classes = tmp / "api"; classes.mkdir()
    sources = sorted(str(p) for p in (SRC / "loader/api/src").rglob("*.java"))
    subprocess.run([str(JDK / "javac"), "--release", "17", "-nowarn", "-encoding", "UTF-8", "-d", str(classes)] + sources, check=True)
    out.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(JDK / "jar"), "--create", "--file", str(out), f"--date={FIXED_DATE}", "-C", str(classes), "."], check=True)
    return sha(out)


def build_dll(tmp: Path, out: Path) -> None:
    """Original build-native.cmd MSVC flags plus /d1trimfile (source root); timestamps fixed afterwards."""
    work = tmp / "native"; work.mkdir()
    out.parent.mkdir(parents=True, exist_ok=True)
    src = SRC / "loader/jvmti/src/bitwig_jvmti_tracer.cpp"
    script = work / "build.cmd"
    script.write_text("@echo off\r\n"
        f'call "{VCVARS}" >nul\r\n'
        f'cd /d "{work}"\r\n'
        f'cl /nologo /EHsc /std:c++17 /O2 /MT /LD /d1trimfile:"{SRC}\\\\" '
        f'/I"{JDK_HOME / "include"}" /I"{JDK_HOME / "include" / "win32"}" '
        f'"{src}" /link /INCREMENTAL:NO /OUT:"{out}"\r\n', "ascii")
    p = subprocess.run(["cmd", "/c", str(script)], capture_output=True, text=True, errors="replace")
    if p.returncode or not out.is_file():
        raise SystemExit("JVMTI tracer build failed:\n" + p.stdout + p.stderr)
    for extra in out.parent.glob("*"):
        if extra != out:
            extra.unlink()
    fix_pe_timestamps(out)


def fix_pe_timestamps(dll: Path) -> None:
    """Set the COFF header and debug-directory TimeDateStamp fields to FIXED_EPOCH."""
    import struct
    b = bytearray(dll.read_bytes())
    pe = struct.unpack_from("<I", b, 0x3C)[0]
    if b[pe:pe + 4] != b"PE\0\0":
        raise SystemExit("not a PE file: " + str(dll))
    struct.pack_into("<I", b, pe + 8, FIXED_EPOCH)
    nsec = struct.unpack_from("<H", b, pe + 6)[0]
    optsize = struct.unpack_from("<H", b, pe + 20)[0]
    opt = pe + 24
    if struct.unpack_from("<H", b, opt)[0] != 0x20B:
        raise SystemExit("expected a 64-bit (PE32+) DLL")
    dbg_rva, dbg_size = struct.unpack_from("<II", b, opt + 112 + 6 * 8)  # data directory 6 = debug
    if dbg_rva:
        for i in range(nsec):
            vsize, va, rsize, raw = struct.unpack_from("<IIII", b, opt + optsize + i * 40 + 8)
            if va <= dbg_rva < va + max(vsize, rsize):
                off = raw + dbg_rva - va
                for k in range(dbg_size // 28):
                    struct.pack_into("<I", b, off + k * 28 + 4, FIXED_EPOCH)
                break
    dll.write_bytes(bytes(b))


def wsl(*argv: str) -> subprocess.CompletedProcess:
    return subprocess.run(["wsl", "-d", WSL_DISTRO, "-e", *argv], capture_output=True, text=True, errors="replace")


def wsl_path(p: Path) -> str:
    r = wsl("wslpath", "-a", "-u", str(p))
    if r.returncode or not r.stdout.strip():
        raise SystemExit(f"wslpath failed for {p}:\n{r.stdout}{r.stderr}")
    return r.stdout.strip()


def check_gxx() -> str:
    r = wsl("g++", "--version")
    found = r.stdout.splitlines()[0].strip() if r.returncode == 0 and r.stdout else ""
    if found != GXX_VERSION:
        raise SystemExit(f"expected {GXX_VERSION!r} in WSL {WSL_DISTRO}, found {found or (r.stdout + r.stderr).strip()!r}"
                         f" (install with: sudo apt install build-essential)")
    return found


def build_so(tmp: Path, out: Path) -> None:
    """The tracer for Linux x86-64, compiled with g++ in WSL; see the module docstring."""
    work = tmp / "native-linux"; work.mkdir()
    out.parent.mkdir(parents=True, exist_ok=True)
    # Export only the agent entry points, as the DLL does: C++ template code from the standard
    # library headers would otherwise be exported too.
    exports = work / "exports.map"
    exports.write_bytes(b"{\n  global: Agent_OnLoad; Agent_OnAttach; Agent_OnUnload;\n  local: *;\n};\n")
    src_root, jdk_include, w_out = wsl_path(SRC), wsl_path(JDK_HOME / "include"), wsl_path(out)
    q = lambda s: "'" + s.replace("'", "'\\''") + "'"
    flags = ["-std=c++17", "-O2", "-fPIC", "-shared", "-fvisibility=hidden",
             "-static-libstdc++", "-static-libgcc", "-Wl,--exclude-libs,ALL", f"-Wl,--version-script={wsl_path(exports)}",
             "-Wl,--build-id=sha1", "-s",
             "-frandom-seed=bitwig_jvmti_tracer",
             f"-ffile-prefix-map={src_root}/=", f"-ffile-prefix-map={jdk_include}/=jdk/include/"]
    cmd = (["g++"] + flags + [f"-I{jdk_include}", f"-I{src_root}/loader/jvmti/include/linux",
            "-o", w_out, f"{src_root}/loader/jvmti/src/bitwig_jvmti_tracer.cpp"])
    script = work / "build.sh"
    script.write_bytes(("set -eu\ncd /\n" + " ".join(q(c) for c in cmd) + "\n").encode("ascii"))
    r = wsl("sh", wsl_path(script))
    if r.returncode or not out.is_file():
        raise SystemExit("JVMTI tracer (Linux) build failed:\n" + r.stdout + r.stderr)


def mac_record() -> dict[str, str]:
    """bitwig-jvmti-tracer.dylib.sha256 as key -> value (lines key=value; # comments)."""
    out = {}
    for line in MAC_RECORD.read_text("utf8").splitlines():
        if line.strip() and not line.startswith("#"):
            k, _, v = line.partition("=")
            out[k.strip()] = v.strip()
    return out


def mac_record_stale() -> list[str]:
    """The Mac build inputs whose SHA-256 is no longer the recorded one (the dylib must be rebuilt)."""
    rec = mac_record()
    return [k for k, p in MAC_INPUTS.items() if not p.is_file() or rec.get(k) != sha(p)]


def check_mac_dylib(path: Path) -> str:
    """Refuses unless `path` is the Mac-built agent the record describes, built from the current
    source; returns the toolchain it was built with."""
    rec = mac_record()
    stale = mac_record_stale()
    if stale:
        raise SystemExit(f"{MAC_RECORD.relative_to(HERE)} describes other inputs than the source ({', '.join(stale)} changed): "
                         "rebuild the agent on a Mac (python loader/build.py --mac-kit DIR), update the record "
                         "from its BML_MAC_AGENT line, or build with --no-mac")
    if not path.is_file():
        raise SystemExit(f"The macOS agent is missing: {path}\nBuild it on a Mac (python loader/build.py --mac-kit DIR) and put "
                         f"bitwig-jvmti-tracer.dylib there, pass --mac-dylib PATH, or build with --no-mac (not a release).")
    got = sha(path)
    if got != rec.get("dylib"):
        raise SystemExit(f"{path} has SHA-256 {got}, not the recorded {rec.get('dylib')}: not the agent built from this source")
    return (f"Xcode Command Line Tools {rec['clt']}, {rec['clang']}, macOS SDK {rec['sdk']}, "
            f"target macOS {rec['target']}, on a Mac (build-agent-mac.sh)")


MAC_KIT_README = """BML agent for macOS: how to build it on the Mac
===============================================

1. Put bml-mac-agent-kit.zip in your Downloads folder and double-click it there.
   A folder named bml-mac-agent-kit appears next to it.
2. Open Terminal (Cmd+Space, type Terminal, press Return).
3. Copy this line, paste it into Terminal and press Return:

   sh ~/Downloads/bml-mac-agent-kit/build-agent-mac.sh

   If it says the Xcode command-line tools are missing, run  xcode-select --install ,
   click Install, wait until it has finished, then paste the line again.
4. The last line starts with BML_MAC_AGENT. The agent is
   ~/Downloads/bml-mac-agent-kit/out/bitwig-jvmti-tracer.dylib

The kit holds BML's agent source (src/), the macOS JNI header (include/darwin/), the pinned
JDK's jni.h and jvmti.h (jdk-include/), the build script and the record of the expected result
(bitwig-jvmti-tracer.dylib.sha256). SHA256SUMS lists every file. Nothing outside this folder is
changed.
"""


def make_mac_kit(out_dir: Path) -> int:
    """What a Mac needs to build the agent: build-agent-mac.sh, the record, the agent source and
    headers, laid out as build-agent-mac.sh expects; and a ZIP of it (sorted entries, fixed times,
    .sh executable, so the same inputs give the same ZIP)."""
    root = out_dir.resolve() / "bml-mac-agent-kit"
    if root.exists():
        shutil.rmtree(root)
    files = {"build-agent-mac.sh": MAC_DIR / "build-agent-mac.sh", "bitwig-jvmti-tracer.dylib.sha256": MAC_RECORD}
    files.update(MAC_INPUTS)
    for rel, src in files.items():
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, root / rel)
    (root / "HOW-TO-BUILD.txt").write_bytes(MAC_KIT_README.encode("ascii"))
    listed = sorted(p for p in root.rglob("*") if p.is_file())
    (root / "SHA256SUMS").write_bytes("".join(f"{sha(p)}  {p.relative_to(root).as_posix()}\n" for p in listed).encode("ascii"))
    zpath = out_dir.resolve() / "bml-mac-agent-kit.zip"
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(p for p in root.rglob("*") if p.is_file()):
            info = zipfile.ZipInfo(f"bml-mac-agent-kit/{f.relative_to(root).as_posix()}", (2026, 1, 1, 0, 0, 0))
            info.compress_type, info.create_system = zipfile.ZIP_DEFLATED, 3
            info.external_attr = (0o100755 if f.suffix == ".sh" else 0o100644) << 16
            z.writestr(info, f.read_bytes())
    stale = mac_record_stale()
    print(f"MAC_KIT {zpath}  sha256={sha(zpath)}  expected dylib={mac_record().get('dylib')}"
          + (f"  (record is stale for: {', '.join(stale)}; the Mac build will say so)" if stale else ""))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--replace", action="store_true")
    ap.add_argument("--mac-dylib", type=Path, default=MAC_INPUT, help="the Mac-built agent (default: %(default)s)")
    ap.add_argument("--no-mac", action="store_true", help="development build without the macOS agent (not a release)")
    ap.add_argument("--mac-kit", type=Path, metavar="DIR", help="only pack the Mac build kit into DIR")
    a = ap.parse_args()
    if a.mac_kit:
        return make_mac_kit(a.mac_kit)
    ver = version()
    name = f"BML-{ver}-core"
    out = HERE / "releases" / (f"{name}-no-mac.zip" if a.no_mac else f"{name}.zip")
    if out.exists() and not a.replace:
        raise SystemExit(f"{out.name} exists; pass --replace to rebuild it")
    mac_tools = None if a.no_mac else check_mac_dylib(a.mac_dylib)  # refuse before building anything
    if not VCVARS.is_file():
        raise SystemExit(f"MSVC not found: {VCVARS}")
    javac = subprocess.run([str(JDK / "javac"), "-version"], capture_output=True, text=True).stdout.strip()
    if javac != "javac 25.0.4.1":  # standard 11: pinned toolchain
        raise SystemExit(f"expected javac 25.0.4.1, found {javac!r}")
    gxx = check_gxx()

    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        classes = tmp / "classes"; classes.mkdir()
        missing_fonts = build_app(tmp, classes)
        if missing_fonts:
            print("WARNING: bundled fonts not in src/loader/app/resources/dev/bitwigre/bml/fonts: " + ", ".join(missing_fonts)
                  + "; the window uses the platform's fonts")
        (tmp / "manifest.mf").write_text("Main-Class: dev.bitwigre.bml.BmlControlApp\n")
        stage = tmp / name
        # generated files that may exist in a working tree are never copied: they are built below
        # (and BML.command, the macOS double-click launcher, only ships with the macOS agent)
        shutil.copytree(SRC, stage, ignore=shutil.ignore_patterns("*.jar", "*.class", "*.so", "*.dylib", "jdk-include",
                                                                  "build-invoke", "build-helper-remap", "__pycache__",
                                                                  *([MAC_COMMAND] if a.no_mac else [])))
        tool_hashes = build_tools(tmp, stage / "loader/tools")
        jar = stage / "loader/app/bml-control.jar"
        subprocess.run([str(JDK / "jar"), "--create", "--file", str(jar), f"--date={FIXED_DATE}",
                        "--manifest", str(tmp / "manifest.mf"), "-C", str(classes), "."], check=True)
        modules = check_modules(jar)
        dll = stage / "loader/jvmti/bin/bitwig-jvmti-tracer.dll"
        build_dll(tmp, dll)
        so = dll.with_suffix(".so")
        build_so(tmp, so)  # after build_dll, which clears everything else from bin/
        dylib = dll.with_suffix(".dylib")
        if mac_tools:
            shutil.copyfile(a.mac_dylib, dylib)
        api_sha = build_api(tmp, stage / "loader/api/bml-api.jar")
        (stage / "RELEASE-MANIFEST.json").write_text(json.dumps({
            "name": name, "loader_version": ver,
            "control_jar_sha256": sha(jar), "jvmti_dll_sha256": sha(dll), "jvmti_so_sha256": sha(so),
            "jvmti_dylib_sha256": sha(dylib) if mac_tools else None,
            "tool_jar_sha256": tool_hashes, "api_jar_sha256": api_sha,
            "control_jar_modules": modules,
            "control_jar_fonts": "bundled" if not missing_fonts else "platform (bundled fonts missing)",
            "built_with": "JDK 25 javac --release 21 (DarkTitleBar.java --release 22); MSVC (VS Build Tools 2026) /O2 /MT /Brepro; "
                          f"{gxx} (WSL {WSL_DISTRO}) -O2 -static-libstdc++"
                          + (f"; {mac_tools}" if mac_tools else "; no macOS agent (--no-mac: not a release)"),
            "source": "loader/src",
        }, indent=2) + "\n", "utf8")
        out.parent.mkdir(exist_ok=True)
        tmpzip = tmp / "out.zip"
        with zipfile.ZipFile(tmpzip, "w", zipfile.ZIP_DEFLATED) as z:
            for f in sorted(stage.rglob("*")):
                if f.is_file():
                    info = zipfile.ZipInfo(f"{name}/{f.relative_to(stage).as_posix()}", (2026, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    data = f.read_bytes()
                    if f.suffix in EXECUTABLE:  # executable when unzipped on Linux/macOS
                        if b"\r" in data:
                            raise SystemExit(f"{f.relative_to(stage).as_posix()} has CRLF line endings, which break sh and bash")
                        info.create_system, info.external_attr = 3, 0o100755 << 16
                    z.writestr(info, data)
        shutil.copy2(tmpzip, out)
        print(f"BUILT {out.relative_to(HERE.parent)}  control_jar_sha256={sha(jar)}  sha256={sha(out)}"
              + ("  (no macOS agent: a development build, not a release)" if a.no_mac else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
