"""Where the parts of a BML workspace are.

A workspace is one folder holding the repositories side by side, plus Bitwig's jars and the generated
test installs (none of which are committed):

  <workspace>/
    BML/        loader, mod kit, the frameworks (frameworks/), build tools (this repository)
    Mods/       the mods; Mods/README.md holds the acceptance table
    Mapper/     optional: builds map packs; Mapper/maps/<version>/ holds the current packs
    Internal/   optional: project notes, test results (Internal/testing/results/), test-only inputs
    bitwig/     jars/<version>/bitwig<v>.jar and libs<v>.jar, read-only
    maps/       map packs from a downloaded release, when there is no Mapper
    testing/    installs/ (generated test installs) and, without Internal, results/

The workspace is BML's parent folder (or BML_WORKSPACE). An optional, untracked <workspace>/workspace.json
overrides any of these, as a path relative to the workspace or absolute:

  {"bml": "BML", "mods": "Mods", "mapper": "Mapper", "internal": "Internal", "bitwig": "bitwig",
   "maps": "Mapper/maps", "installs": "testing/installs", "results": "Internal/testing/results",
   "test_components": "Internal/test-components", "accepted": "Mods/README.md",
   "bitwig_jars": {"6.1.4": "C:/Program Files/Bitwig Studio/bin"}}

"bitwig_jars" names a folder holding bitwig.jar and libs.jar for a version (e.g. an installed Bitwig's bin folder),
instead of bitwig/jars/<version>/bitwig<v>.jar and libs<v>.jar.

Mods are found by ID in BML/frameworks/ and Mods/.
"""
from __future__ import annotations
import json, os
from pathlib import Path

BML = Path(__file__).resolve().parent.parent
WORKSPACE = Path(os.environ.get("BML_WORKSPACE") or BML.parent).resolve()
_CONFIG = WORKSPACE / "workspace.json"
_cfg = json.loads(_CONFIG.read_text("utf8")) if _CONFIG.is_file() else {}


def _path(key: str, default: Path) -> Path:
    return (WORKSPACE / _cfg[key]).resolve() if key in _cfg else default


BML = _path("bml", BML)
MODS = _path("mods", WORKSPACE / "Mods")
MAPPER = _path("mapper", WORKSPACE / "Mapper")
INTERNAL = _path("internal", WORKSPACE / "Internal")
BITWIG = _path("bitwig", WORKSPACE / "bitwig")
MAPS = _path("maps", MAPPER / "maps" if MAPPER.is_dir() else WORKSPACE / "maps")
INSTALLS = _path("installs", WORKSPACE / "testing" / "installs")
RESULTS = _path("results", INTERNAL / "testing" / "results" if INTERNAL.is_dir() else WORKSPACE / "testing" / "results")
TEST_COMPONENTS = _path("test_components", INTERNAL / "test-components" if INTERNAL.is_dir() else WORKSPACE / "test-components")
ACCEPTED = _path("accepted", MODS / "README.md")
MOD_FOLDERS = (BML / "frameworks", MODS)


def find_mod_dir(mod_id: str) -> Path | None:
    """The source folder of a mod or framework, by ID, or None."""
    hits = [d / mod_id for d in MOD_FOLDERS if (d / mod_id / "mod.json").is_file()]
    if len(hits) > 1:
        raise SystemExit(f"{mod_id} is in more than one place: {[str(h) for h in hits]}")
    return hits[0] if hits else None


def mod_dir(mod_id: str) -> Path:
    """The source folder of a mod or framework, by ID."""
    found = find_mod_dir(mod_id)
    if not found:
        raise SystemExit(f"{mod_id}: no mod folder in {', '.join(rel(d) for d in MOD_FOLDERS)}")
    return found


def bitwig_jar(version: str, name: str = "bitwig") -> Path:
    """bitwig/jars/<version>/<name><version without dots>.jar, or <name>.jar in the folder workspace.json names"""
    if version in _cfg.get("bitwig_jars", {}):
        return (WORKSPACE / _cfg["bitwig_jars"][version] / f"{name}.jar").resolve()
    return BITWIG / "jars" / version / f"{name}{version.replace('.', '')}.jar"


PLATFORMS = {"windows": "", "macos": "-mac", "linux": "-linux"}  # platform -> suffix of its bitwig/jars/ folder


def build_name(platform: str, version: str) -> str:
    """A Bitwig build as tools name it: "<version>" on Windows, "<platform>-<version>" (macos, linux) otherwise."""
    return version if platform.lower() == "windows" else f"{platform.lower()}-{version}"


def build_jars(build: str) -> list[Path]:
    """bitwig.jar, then the library jars, of a build named by build_name: on Windows bitwig_jar()'s;
    on macOS and Linux bitwig/jars/<version>-mac|-linux/bitwig.jar, libs.jar and, where shipped, lwjgl.jar."""
    platform, _, version = build.rpartition("-")
    platform = platform or "windows"
    if platform not in PLATFORMS:
        raise SystemExit(f"unknown platform in build {build!r} (known: {', '.join(PLATFORMS)})")
    if platform == "windows":
        return [bitwig_jar(version), bitwig_jar(version, "libs")]
    d = BITWIG / "jars" / f"{version}{PLATFORMS[platform]}"
    return [d / "bitwig.jar", d / "libs.jar", *[p for p in (d / "lwjgl.jar",) if p.is_file()]]


def rel(p: Path) -> str:
    """A path as written in build records: relative to the workspace when inside it."""
    try:
        return Path(p).resolve().relative_to(WORKSPACE).as_posix()
    except ValueError:
        return str(p)
