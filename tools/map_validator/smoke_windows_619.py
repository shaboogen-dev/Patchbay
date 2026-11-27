"""Check the Windows 6.1.9 probe with a built BML loader. Requires java, javac and jar on PATH."""
import argparse
import csv
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from windows_619_probe import FIXTURE, TARGET_SHA, checked_hash


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path)
    parser.add_argument("loader", type=Path, help="loader/ directory of a built Patchbay install")
    parser.add_argument("bitwig_bin", type=Path, help="Windows 6.1.9 bin/ directory")
    args = parser.parse_args()
    checked_hash(args.bitwig_bin / "bitwig.jar", TARGET_SHA)

    with tempfile.TemporaryDirectory(prefix="bml-619-probe-") as tmp:
        root = Path(tmp)
        loader = root / "loader"
        (root / "config").mkdir()
        (root / "config/portable").touch()
        (loader / "app").mkdir(parents=True)
        control = loader / "app/bml-control.jar"
        shutil.copy2(args.loader / "app/bml-control.jar", control)
        for directory in ("api", "tools"):
            shutil.copytree(args.loader / directory, loader / directory)
        mod = root / "mods/probe-615"
        mod.mkdir(parents=True)
        helper = root / "Probe.java"
        helper.write_text("package probe; public class Probe { public static void afterPaint() {} }\n", "utf8")
        subprocess.run(["javac", "--release", "21", "-d", root, helper], check=True)
        subprocess.run(["jar", "cf", mod / "helper.jar", "-C", root, "probe/Probe.class"], check=True)

        targets, expected = {}, {}
        paint = None
        for line in FIXTURE.read_text("utf8").splitlines():
            if not line or line.startswith("#"):
                continue
            kind, owner, *rest = line.split("\t")
            key = f"anchor.{len(targets)}"
            if kind == "C":
                targets[key] = {"kind": "class", "class": owner}
                expected[key], = rest
            else:
                name, desc, to, new_name, new_desc = rest
                targets[key] = dict(kind={"M": "method", "F": "field"}[kind], owner=owner, name=name, descriptor=desc)
                expected[key] = to + "." + new_name + new_desc
                if (owner, name, desc) == ("bpT", "IYM", "(Lcom/bitwig/graphics/CPO;LXEQ;DDDZZDDDDDD)V"):
                    paint = key
        if paint is None:
            raise ValueError("Missing clip painter overload in fixture")
        manifest = dict(manifest_version=2, mod=dict(id="probe-615", name="Clip paint probe", version="0.1.0",
                        author="BML", description="Offline compatibility test"),
                        built_against=dict(bitwig_version="6.1.5", platform="windows", architecture="x64"),
                        targets=targets, helper={"jar": "helper.jar", "class": "probe.Probe"},
                        hooks=[dict(id="after-paint", transform="INSERT_BEFORE_FINAL_RETURN", target=paint,
                                    bridge=dict(method="afterPaint", descriptor="()V", args=[]))])
        (mod / "mod.json").write_text(json.dumps(manifest), "utf8")
        command = ["java", "-cp", control]
        subprocess.run([*command, "dev.bitwigre.bml.MapPackImporter", args.pack, root], check=True)
        # Use only this test's mod, even when the shell was opened from a configured BML session.
        env = {k: v for k, v in os.environ.items() if not k.upper().startswith("BML_")}
        subprocess.run([*command, "dev.bitwigre.bml.JavaPreparer", "--loader-root", loader,
                        "--bitwig-bin", args.bitwig_bin, "--mods-root", mod.parent], env=env, check=True)
        runtime = loader / "mod-runtime"
        with (runtime / "target-resolution.tsv").open(encoding="utf8", newline="") as stream:
            actual = {r["local_target"]: r["runtime_physical"] for r in csv.DictReader(stream, delimiter="\t")
                      if r["mod_id"] == "probe-615" and r["status"] == "MAPPED"}
        if actual != expected:
            raise ValueError(f"Unresolved or incorrect targets: {sorted(expected.items() - actual.items())}")
        if "DEEP_VERIFY\tDEEP_VERIFY_OK bqG" not in (runtime / "transform-report.txt").read_text("utf8").splitlines():
            raise ValueError("Patched 6.1.9 clip painter did not pass deep verification")
        print(f"PASS: {len(expected)} mapped targets and verified clip painter")


if __name__ == "__main__":
    main()
