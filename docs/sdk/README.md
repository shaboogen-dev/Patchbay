# Writing mods for BML

> **Pre-release document.** This guide has been written by Claude with an extremely light edit pass done by me (shaboogen) and only exists for early testers. Much fuller, human written / edited documents will be written before BML is publicly released. Commands, paths and tool names may change before then.

**Disclaimer: BML is not affiliated with Bitwig Studio or Bitwig GmbH. If anyone from Bitwig has any issues with this software, please contact me.**

BML changes Bitwig Studio's code when Bitwig starts, so mods can add to it or change how it behaves. Bitwig's own jar on disk is never modified. This guide is for anyone writing a BML mod or converting an existing one. It is written to be followed step by step, by a person or by an AI assistant.

## Pages

Read them in this order the first time:

| Page | What it covers |
|---|---|
| [mods.md](mods.md) | What a BML mod is: the folder, `mod.json`, the helper jar, resources and icons, names and IDs, and how one mod runs on several Bitwig versions. |
| [writing.md](writing.md) | Writing a mod with the mod kit: calling Bitwig in plain Java, `@Hook`, `@AddField`, `bml.api.Bml`, comments on Bitwig names. A complete example mod. |
| [frameworks.md](frameworks.md) | BML's frameworks: adding actions and menu items, Settings controls and Inspector blocks, and how themes and native devices are added. Mods must use them where they apply. |
| [building.md](building.md) | Building a mod, checking it offline (`PREPARE_OK`), installing it, and what to report when something fails. |
| [converting.md](converting.md) | Converting an existing mod to this style: reflection strings and `BML_MOD_CONFIG` lookups, a hand-written manifest, a mod written against an older Bitwig version. |
| [jar-mods.md](jar-mods.md) | Porting a mod that ships a modified `bitwig.jar` (or a diff of it): comparing the jars with `bml-modkit jar-diff`, turning each change into a hook, added classes into the helper jar, platforms. |
| [../maps.md](../maps.md) | The map pack format and how BML uses map packs: the files in a pack, their rows, how BML picks a pack for the installed Bitwig, what happens when a name doesn't translate, and what a pack from your own tool must satisfy. |

## Facts to keep in mind

- **Reference version: Bitwig 6.1.4, on your platform.** Write mods against Bitwig 6.1.4's names (`"bitwig_version": "6.1.4"` in `mod.json`), from the Windows, macOS or Linux jar, whichever you have; `built_against` names the platform too. BML's map packs then translate the mod to every supported version (6.0, 6.1, 6.1.1, 6.1.2, 6.1.3 and 6.1.4) on all three platforms. This guide's examples use Windows x64.
- **`mod.json` is the contract.** BML reads only the manifest and the files it names. The mod kit is the recommended way to write the manifest's hooks, not a requirement.
- **Toolchain:** JDK 25.0.4.1 (`javac --release 21`), Windows, Python 3 for BML's tools. The commands in this guide are for PowerShell 7 (`pwsh`); Windows PowerShell 5.1 writes `>` redirections as UTF-16, while BML's Python tools read UTF-8.
- **Bitwig's names are obfuscated.** Bitwig's internal classes and members have short meaningless names (`dWb`, `HL`), and they change with every Bitwig release. A mod names them only in its Java source, in 6.1.4's names, each with a short comment saying what it is.
- **Never ship Bitwig's files.** A mod contains its own code and files only. To use one of Bitwig's icons, a mod references it (`"kind": "bitwig"`); it never copies it.
- **Never edit compiled files.** Every change to a mod is made in its source and rebuilt.
- **Use the frameworks.** Where a framework covers what a mod does, the mod must go through it, never through hooks of its own: actions and menu items, Settings controls, Inspector blocks, themes and native device registration. Hooks of a mod's own on Bitwig's builders for these are the wrong approach in BML: every such mod patches the same builders again and places its entries its own way, while the framework patches them once for all mods and puts every mod's entries in one consistent order. See [frameworks.md](frameworks.md).

## Where the tools are

This guide describes the tools as they are in a BML workspace: one folder holding the `BML` repository and, for BML's own mods, the `Mods` repository side by side (`BML/tools/README.md`). Paths are inside `BML/` unless they start with `Mods/` or name the workspace:

| What | Where |
|---|---|
| Mod kit: `bml-api.jar` (annotations and the runtime class `bml.api.Bml`) and `bml-modkit.jar` (the build tool) | in a BML install from the tester release, `modkit\` (`bml-api.jar`, `bml-modkit.jar`, `README.md`); in a workspace, `modkit/out/`, built by `python modkit/build.py`; reference `modkit/README.md` |
| Bitwig's jars | your Bitwig install's `bin\bitwig.jar` and `bin\libs.jar` (default `C:\Program Files\Bitwig Studio\bin`); in a workspace `bitwig/jars/6.1.4/bitwig614.jar` and `libs614.jar` (next to `BML/`) |
| Framework jars and READMEs | each framework's release ZIP, `frameworks/<framework-id>/releases/<framework-id>-<version>.zip` (its `helper.jar` and `README.md`); in a BML install from the tester release, `mods\<framework-id>\helper.jar` and its `README.md` |
| Map packs | shipped with BML: in a BML install from the tester release, `maps\`; in a workspace, `maps/<version>/windows-<version>-map1-v<pack version>.zip`, and `maps/linux-<version>/` and `maps/macos-<version>/` with `linux-…` and `macos-…` packs |
| Mod build tool for mods kept in a workspace | `tools/build_mod.py` (layout in `Mods/README.md`) |
| Source porter | `tools/porter/SourcePorter.java`, `tools/porter/port_manifest.py` |
| Searching Bitwig's jar for strings, callers and subclasses | `docs/sdk/jarsearch.py` ([writing.md](writing.md)) |
| Offline check | `loader\prepare-mod.cmd` in a BML install; in a workspace `tools/bml_build.py suite` and the install's `guard_selective.py` |
