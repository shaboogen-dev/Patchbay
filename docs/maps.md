# Map packs

> **Pre-release document.** This guide has been written by Claude with an extremely light edit pass done by me (shaboogen) and only exists for early testers. Much fuller, human written / edited documents will be written before BML is publicly released. Commands, paths and tool names may change before then.

**Disclaimer: BML is not affiliated with Bitwig Studio or Bitwig GmbH. If anyone from Bitwig has any issues with this software, please contact me.**

Bitwig's internal classes and members have obfuscated names that change with every Bitwig release. A mod names them in the names of one Bitwig build, the build in its `mod.json` `built_against`. A **map pack** tells BML what each of those names is called in another Bitwig build. BML reads the pack when it prepares Bitwig and translates every Bitwig name in a mod's `mod.json` and `helper.jar` to the installed build.

This page describes the pack format and what BML does with a pack. Anyone can write a tool that makes packs in this format; BML accepts a pack that satisfies [the contract](#5-the-contract), whatever made it.

## Terms

| Term | Meaning |
|---|---|
| build | One Bitwig release on one platform, identified by the SHA-256 of its `bitwig.jar`. |
| build key | A build's name inside a pack, e.g. `windows-6.1.4`. |
| target build | The build a pack is for: the installed Bitwig. One pack has exactly one target build. |
| source build | A build that mods are written against, on any platform. A pack holds one remap per source build it supports. |
| remap | One file translating every name of one source build to the target build. |

## 1. Pack layout

### The ZIP

A pack is distributed as a ZIP:

```
<platform>-<version>-map1-v<pack version>.zip
```

for example `windows-6.0-map1-v2.0.0-dev.9.zip`. BML does not read the ZIP's file name; it reads the folder inside it. The ZIP holds exactly one top-level folder, named after the pack's `id`:

```
windows-6.0-map1/
  map.json
  builds.json
  remap/
    linux-6.1.3__windows-6.0.tsv.gz
    linux-6.1.4__windows-6.0.tsv.gz
    macos-6.1.3__windows-6.0.tsv.gz
    macos-6.1.4__windows-6.0.tsv.gz
    windows-6.1.3__windows-6.0.tsv.gz
    windows-6.1.4__windows-6.0.tsv.gz
```

| File | What it is for |
|---|---|
| `map.json` | The pack's identity: format, `id`, pack version, target build and its `bitwig.jar` SHA-256, and a SHA-256 for every other file. |
| `builds.json` | Every build the pack refers to (the target build and each source build), with its Bitwig version, platform, architecture and `bitwig.jar` SHA-256. |
| `remap/<source build key>__<target build key>.tsv.gz` | One remap per source build. |

### Installed

In a BML install, packs are unpacked folders: `<BML>\maps\<pack id>\map.json`, and so on. `maps\` holds folders only. Importing a ZIP on the Patchbay window's Maps page (or running `MapPackImporter`, see [Checking a pack](#6-checking-a-pack)) unpacks it there.

## 2. File formats

### map.json

UTF-8 JSON, at most 64 KiB. Example (6.0 pack):

```json
{
  "format": "bml-map-pack-v2",
  "id": "windows-6.0-map1",
  "version": "2.0.0-dev.9",
  "target_build_key": "windows-6.0",
  "target_jar_sha256": "2edfd0b4d80cd8452fccf05afa446584ffca7091bde7d9aff2bc8fec5fa3af05",
  "file_sha256": {
    "builds.json": "aec43febe83bbc1658f897c39f18cc47163f063b94dc1bd7342fe52e9a334c00",
    "remap/linux-6.1.3__windows-6.0.tsv.gz": "718372a888837488b1cbad6b00243741a2ef0bea7b242a798084d076de2f6b44",
    "remap/macos-6.1.3__windows-6.0.tsv.gz": "8677f1549179f8ea18fd2a6fe6445d1430542442605f0112e37762bd72e7945c",
    "remap/windows-6.1.3__windows-6.0.tsv.gz": "e60773985405cf8994d67e47f96dc91707a08a1f804c01e83e5d756d4162a5ee",
    "remap/linux-6.1.4__windows-6.0.tsv.gz": "8375f0961be966d61e94fea24a584b1cc87432feef8c976fd7566b7788d9f3a8",
    "remap/macos-6.1.4__windows-6.0.tsv.gz": "87c0b7dc12f64a37d317081c3647cae9c48544c694f4761013691ce8b256c5d2",
    "remap/windows-6.1.4__windows-6.0.tsv.gz": "54cd294d6653b9984c64ada8b21ab754bc76047dfd1c6cdc333d85c347e0ad58"
  }
}
```

| Key | Rule |
|---|---|
| `format` | `bml-map-pack-v2`. |
| `id` | Matches `[A-Za-z][A-Za-z0-9_.-]*`. Must equal the pack's folder name. |
| `version` | The pack's own version. Matches `[A-Za-z0-9][A-Za-z0-9_.-]*`; BML's packs use semver. |
| `target_build_key` | The target build's key. Matches `[A-Za-z0-9][A-Za-z0-9_.-]*`, and must be the key `builds.json` gives the build with `target_jar_sha256`. |
| `target_jar_sha256` | SHA-256 of the target build's `bitwig.jar`, 64 hex digits. |
| `file_sha256` | Path (relative to the pack folder, `/` separated) → SHA-256 of that file. Must list `builds.json` and every file in `remap/`, and at least one remap. Paths must be `builds.json` or `remap/<name>__<name>.tsv.gz`, with names made of `A-Z a-z 0-9 _ . -`. Every listed file must exist and match its hash. |

### builds.json

UTF-8 JSON. Example (6.1.4 pack):

```json
{
  "format_version": 2,
  "builds": [
    {
      "build_key": "linux-6.1.3",
      "bitwig_version": "6.1.3",
      "platform": "linux",
      "architecture": "x64",
      "bitwig_jar_sha256": "4245a38ccddaa800421935216351e6dda8384b34a128cbc85c21e2e6760cfefc"
    },
    {
      "build_key": "macos-6.1.3",
      "bitwig_version": "6.1.3",
      "platform": "macos",
      "architecture": "universal",
      "bitwig_jar_sha256": "a5c096a8ef95ac322149bb8e2bb2eb2a9806de6cb2af26b7eb3945876c9aad4f"
    },
    {
      "build_key": "windows-6.1.3",
      "bitwig_version": "6.1.3",
      "platform": "windows",
      "architecture": "x64",
      "bitwig_jar_sha256": "37073037335331cc6d14ebd1fb3c8c32f159a346574dceb7a119ea942f938ed6"
    },
    {
      "build_key": "linux-6.1.4",
      "bitwig_version": "6.1.4",
      "platform": "linux",
      "architecture": "x64",
      "bitwig_jar_sha256": "77a50264156a9f79c925fa85e0456720ce157bfa10e2503d6049e2f733af4ba6"
    },
    {
      "build_key": "macos-6.1.4",
      "bitwig_version": "6.1.4",
      "platform": "macos",
      "architecture": "universal",
      "bitwig_jar_sha256": "5c2b1895bbb9e7d08d9216de421985bb1f24b2ee62762c9451b4fe847df2469f"
    },
    {
      "build_key": "windows-6.1.4",
      "bitwig_version": "6.1.4",
      "platform": "windows",
      "architecture": "x64",
      "bitwig_jar_sha256": "5c38187eebe14ce7e5fdaa9d8f1eac0e9b2a34116f57d6a854cef7a31f2845c0"
    }
  ]
}
```

| Key | Use |
|---|---|
| `build_key` | The name the pack's remap file names use for this build. |
| `bitwig_version`, `platform`, `architecture` | Matched against a mod's `built_against` (`bitwig_version` exactly, `platform` and `architecture` ignoring case) to find the mod's source build. |
| `bitwig_jar_sha256` | Matched against the installed `bitwig.jar` to find the target build. No two entries may have the same hash. |

List the target build and every source build that has a remap. BML's packs list them in version order; BML does not depend on the order. `format_version` is `2`.

### Remap files

`remap/<source build key>__<target build key>.tsv.gz`, for example `remap/windows-6.1.4__windows-6.0.tsv.gz`.

**Direction:** source first, target second. The file translates names *from* the build a mod is written against (the left key) *to* the pack's target build (the right key). The right key is always the pack's `target_build_key`. A pack for Windows 6.0 that supports mods written against 6.1.3 and 6.1.4 on every platform holds six remaps, `windows-6.1.3__windows-6.0`, `windows-6.1.4__windows-6.0`, `macos-6.1.3__windows-6.0` and so on. A pack has no remap from its own target build: a mod written against the installed build is used as written.

**Encoding:** gzip-compressed UTF-8 text, one row per line, LF line ends, fields separated by tabs. Empty lines and lines starting with `#` are ignored. There is no header row. Rows may be in any order.

**Names:** every class name is a JVM internal name: `/` between package parts, `$` before a nested class, no `L…;` wrapper (`com/bitwig/base/async/AsyncUtilities$1`, or `A1N` for a class in the default package). Every descriptor is a JVM descriptor (`Z`, `()V`, `(Ljava/lang/String;)V`), written with the class names of that row's side: source names in the source columns, target names in the target columns.

There are four kinds of row. The first field is the kind.

#### C: class

```
C	<source class>	<target class>
```

```
C	A1N	Uye
C	com/bitwig/base/async/CuM	com/bitwig/base/async/yWO
C	com/bitwig/base/async/AsyncUtilities$1	com/bitwig/base/async/AsyncUtilities$1
```

- One row for every class of the source `bitwig.jar` that exists in the target build, **including classes whose name did not change** (the last example). A class with no `C` row counts as not present in the target build.
- Only classes in `bitwig.jar`. Classes of `libs.jar` and the JDK are not obfuscated, have no rows and keep their names.
- One to one: no source class twice, no target class twice.

#### H: hierarchy

```
H	<source class>	<its direct superclass or interface>
```

```
H	A1N	java/lang/Object
H	A1P	Gx2
H	A1P	gG
```

- Both names are **source** names. `H` rows describe the source build; they are not translations.
- One row for each direct superclass and each directly implemented or extended interface of every class in the source `bitwig.jar`, including parents in `libs.jar` and the JDK (`java/lang/Object`). The rows are exactly the source jar's inheritance edges, no more and no fewer.
- BML uses them to translate a member that a mod reaches through a subclass (see "Inherited members" below).

#### F: field, M: method

```
F	<source owner>	<source name>	<source descriptor>	<target owner>	<target name>	<target descriptor>
M	<source owner>	<source name>	<source descriptor>	<target owner>	<target name>	<target descriptor>
```

```
F	A1N	cfM	Z	Uye	r3B	Z
M	A	Iwz	()Lcom/bitwig/flt/document/core/master/zwV;	XQL	PFL	()Lcom/bitwig/flt/document/core/master/wUZ;
M	A	<init>	()V	XQL	<init>	()V
```

- A member is identified by owner, name and descriptor together. Overloads are separate rows.
- The target owner is the owner's `C` translation. The target descriptor is the source descriptor with every class in it translated by its `C` row (classes outside `bitwig.jar` stay as they are).
- One row for every field and method declared by a source class that exists in the target build, including constructors (`<init>`), static initialisers (`<clinit>`) and members whose name did not change. A member with no row counts as not present in the target build.
- A method that overrides or implements a method declared in `libs.jar` or the JDK keeps its name: `M	AUY	run	()V	dff	run	()V`.
- A method that overrides a mapped method of a `bitwig.jar` ancestor gets the same target name as that ancestor's row.
- Static stays static; instance stays instance.

**Inherited members (alias rows).** A row's owner may also be a class that inherits the member instead of declaring it, because a mod's `mod.json` may name a member through the class it is used on:

```
F	AE	MRY	Ljava/lang/String;	tq	Xzy	Ljava/lang/String;
```

Here `AE` does not declare `MRY`; it inherits it. The row gives the target name under `AE`'s translation `tq`, and must agree with the row of the class that declares the field. BML looks up `mod.json` targets by exact owner, name and descriptor, so a target named through a subclass translates only if the pack has that alias row. Inside `helper.jar`, BML also resolves inherited members through the `H` rows when no alias row exists.

**What happens to a name with no row.** It is reported as not translatable (`UNMAPPED`), never guessed and never kept silently as a target binding. See [When a name doesn't translate](#4-when-a-name-doesnt-translate).

## 3. How BML picks and uses a pack

Every time BML prepares Bitwig (at launch, or with `prepare-mod.cmd`), it does this:

1. Computes the SHA-256 of `<Bitwig>\bin\bitwig.jar`.
2. Checks **every** folder in `<BML>\maps\` against [the contract's file rules](#5-the-contract): `map.json`, its keys, every `file_sha256` hash, and that every file in `remap/` has a hash. One invalid pack in `maps\` stops preparation, even if it is for another Bitwig build.
3. Selects the one pack whose `target_jar_sha256` equals the installed `bitwig.jar`'s hash. No pack: preparation stops with `No installed map supports bitwig.jar SHA-256 <hash>`. More than one: it stops with `Conflicting installed maps`.
4. Copies the selected pack into the prepared runtime (`mod-runtime\map-snapshot\`) and checks the copy again, so replacing a pack while BML prepares can't mix two versions.
5. Finds the entry in `builds.json` with the installed jar's hash. None: `Unsupported bitwig.jar SHA-256 … (fail-closed)`. Its `build_key` must equal `target_build_key`.
6. For each enabled mod, finds the `builds.json` entry matching the mod's `built_against` (`bitwig_version`, `platform`, `architecture`). More than one: preparation stops with `Ambiguous source build for <mod>`. The mod is **not available on this version** when there is no matching entry, when the entry isn't the target build and the pack has no `remap/<its build key>__<target build key>.tsv.gz`, or when that remap has no row for something the mod needs (below); preparation then stops (see [A mod not available on a version](#a-mod-not-available-on-a-version)). What a mod needs: every `mod.json` target that a hook uses (the hook's `target`, a `match.target`, an `ADD_METHOD` `method.from_target`) or that `opens` lists, as a `C`, `F` or `M` row with that exact source identity, and, when the mod ships a `uses.tsv` (written by BML's `tools/build_mod.py`; format in `tools/mod_uses.py`), a `C` row for each of its classes and an `F` or `M` row for each member under its declaring class.
7. If the mod's source build is the target build, the mod's names are used as written (status `EXACT`) and no remap is read.
8. Otherwise BML reads `remap/<source build key>__<target build key>.tsv.gz`:
   - **`mod.json` targets:** each target (`kind` `class`, `field` or `method`, with `class` or `owner`, `name`, `descriptor`) is looked up by exact source identity in the `C`, `F` and `M` rows. Found: `MAPPED` to the row's target. Not found: `UNMAPPED`.
   - **`helper.jar`:** the helper's class files are rewritten with the `C`, `F` and `M` rows (constant pool references, descriptors and signatures, and recognised reflection strings), using the `H` rows for inherited members. Names with no row are left as they are.
9. Writes the result per target to `mod-runtime\target-resolution.tsv` (`mod_id`, `local_target`, `built_against`, `status`, `kind`, `source_physical`, `runtime_build`, `runtime_physical`), and the helper translation per mod to `mod-runtime\helper-remap-<mod id>.txt`.

The prepared runtime is reused at the next launch only if nothing that went into it changed; every file under `<BML>\maps\` is part of that check, so importing or replacing a pack makes BML prepare again.

A pack is replaced by importing a newer ZIP with the same `id`. The previous one is kept as `<BML>\state\map-backups\<id>-previous`. Importing a pack with a different `id` that targets a `bitwig.jar` hash another installed pack already targets is refused.

## 4. When a name doesn't translate

| What is missing | What BML does |
|---|---|
| No pack for the installed `bitwig.jar` | Preparation stops. No mod loads. |
| The mod's `built_against` build isn't in the pack's `builds.json` | The mod isn't available on this version. Preparation stops while it is enabled. |
| The build is listed but the pack has no remap from it to the installed build | The mod isn't available on this version. Preparation stops while it is enabled. |
| The remap has no row for something the mod needs (a hook's targets, an `opens` target, a field's owner or a Bitwig class in its type, a `uses.tsv` row) | The mod isn't available on this version. Preparation stops while it is enabled. |
| A `mod.json` target used by a trace is `UNMAPPED` | Preparation stops: `Cannot translate required target '<id>' (<name>) in <mod> from <source> to active runtime build`. |
| A `mod.json` target the helper reads at runtime through `BML_MOD_CONFIG` is `UNMAPPED` | Preparation stops: `Unmapped runtime helper target(s) for <mod> on <build>`. |
| Any other `mod.json` target is `UNMAPPED` | Warning: `<mod> has <n> unmapped declared target(s) …; see target-resolution.tsv`. The target is not bound. Preparation continues. |
| A name used inside `helper.jar` has no row, in a mod without `uses.tsv` | The name stays untranslated. It is listed in `helper-remap-<mod id>.txt` and `mod-runtime\helper-linkage-preflight.tsv`, with a warning `<n> unique prepared helper references unresolved`. Preparation continues; that code fails in Bitwig when it runs. |

### A mod not available on a version

When the installed build's pack can't translate from the build a mod is written against (its `built_against` build isn't in `builds.json`, or there is no remap from it), or its remap has no row for something the mod needs ([step 6](#3-how-bml-picks-and-uses-a-pack)), that mod isn't available on this version:

- The Patchbay window shows the mod greyed out, its description starting with `Not available on <version>`, and it can't be ticked. A mod found for the first time starts unticked.
- A tick saved from before is an error on the Errors page, `<mod name> isn't available on Bitwig <version>.`, with the reason, fixed by unticking the mod. Load refuses until then.
- Preparation with the mod enabled stops: `Selected mods can't load:`, then `<mod name> (<mod id>) isn't available on Bitwig <version> (<why>). Untick <mod name>.` for each. `<why>` is one of:
  - `made for Bitwig <version> <platform> <architecture>, which the map for Bitwig <version> doesn't list`;
  - `made for Bitwig <version> on <platform>, and the map for Bitwig <version> (<pack id> <pack version>) has no remap from it`;
  - `made for Bitwig <version> on <platform>, and it uses <package> (<class> and <n> more), which has no counterpart in Bitwig <version> for <platform>`, when none of that package's classes has a `C` row (typically one platform's own code, such as `com.bitwig.mac_windowing_system` in a remap to Windows);
  - `made for Bitwig <version> on <platform>, and it uses <class or member> and <n> more, which the map can't translate to Bitwig <version> for <platform>`;
  - for a mod that needs one not available ([prerequisites](sdk/mods.md#prerequisites)), `needs <name> (<id>), which isn't available on this version`.

The Patchbay window says the same on the Errors page, which lists each such mod with the reason.

### A mod whose names don't all translate

Translation renames; it does not adapt. When Bitwig removed or changed something a mod uses, that version's pack has no row for it, and the mod can't run there until its author handles that version. BML handles each untranslated name as in the table above: a mod whose hook targets, `opens` or `uses.tsv` don't all translate isn't available on that version, and a name used only inside the `helper.jar` of a mod without `uses.tsv` gives a warning. `mod.json` has no per-version switch. BML's workspace tool `tools/mod_uses.py check <uses.tsv> <pack.zip> <built-against build>` (a build is `6.1.4` for Windows, or `macos-6.1.3`, `linux-6.1.4` and so on) lists, before installing, every Bitwig class and member a mod's `helper.jar` uses that a pack can't translate; BML's test-install builder doesn't install a mod for which it lists anything.

## 5. The contract

A pack, from any tool, must satisfy all of this.

**Checked by BML; a failure stops preparation:**

1. The ZIP has exactly one top-level folder, named after `map.json`'s `id`; no `\` in entry paths, no absolute paths, no `:`, no `.` or `..` parts, no duplicate entries; at most 100 MB uncompressed.
2. `map.json`, `builds.json` and `remap/` exist as regular files and a folder (no symbolic links), and `map.json` follows the rules in [map.json](#mapjson): format `bml-map-pack-v2`, valid `id`, `version`, `target_build_key`, `target_jar_sha256`, and a correct `file_sha256` for `builds.json` and every remap.
3. Exactly one `builds.json` entry has the installed `bitwig.jar`'s hash, and its `build_key` equals `target_build_key`.
4. No two `builds.json` entries have the same `bitwig_version`, `platform` and `architecture`.
5. Each remap is gzip, UTF-8; `C` and `H` rows have 3 fields, `F` and `M` rows 7; no other row kinds.
6. No source class appears in two `C` rows with different targets; no source member appears in two `F` or `M` rows of its kind with different targets.
7. No other installed pack targets the same `bitwig.jar` hash.

**Required for a mod to load** (otherwise the mod isn't available on this version):

- The mod's `built_against` build has a `builds.json` entry with that `bitwig_version`, `platform` and `architecture`, and, unless it is the target build, a remap named `<its build_key>__<target_build_key>.tsv.gz` with a row for everything the mod needs ([step 6](#3-how-bml-picks-and-uses-a-pack)).

**Not checked by BML, but required for correct results** (a pack that breaks these prepares, and then mods misbehave in Bitwig):

8. Every `C`, `F` and `M` row is right: the target really is the same class or member in the target build.
9. Coverage as described under [Remap files](#remap-files): rows for unchanged names too, constructors and static initialisers, alias rows for inherited members, one-to-one classes and declared members, owners and descriptors translated through the `C` rows, names kept for members declared in `libs.jar` or the JDK, overrides named like the method they override.
10. `H` rows are exactly the source `bitwig.jar`'s direct inheritance edges, in source names.
11. A remap holds only rows for things that exist in both builds. A name with no counterpart has no row.

## 6. Checking a pack

These steps need only a BML install (no Bitwig launch). `<BML>` is the BML install folder; `java` is a Java 21 or newer runtime.

**1. Check the files and identity.** Import the pack into an empty scratch folder; the importer runs the same checks preparation runs (contract items 1, 2 and 7):

```powershell
java -cp '<BML>\loader\app\bml-control.jar' dev.bitwigre.bml.MapPackImporter 'C:\work\windows-6.0-map1-v2.0.0-dev.9.zip' 'C:\work\map-check'
```

Expect `Installed windows-6.0-map1 2.0.0-dev.9`. Any problem prints an exception naming it.

**2. Check that the pack is selected for a Bitwig build.** Get the hash of that build's `bitwig.jar`, then ask the catalog which pack it selects:

```powershell
(Get-FileHash 'C:\Program Files\Bitwig Studio\bin\bitwig.jar' -Algorithm SHA256).Hash
```

```powershell
java -cp '<BML>\loader\app\bml-control.jar' dev.bitwigre.bml.MapPackCatalog 'C:\work\map-check' <hash>
```

Expect `MAP_SELECTED id=<id> version=<version> target=<target build key> directory=…`.

**3. Check the rows a mod needs, against a Bitwig of the target version.** Import the pack into the BML install (Maps page, or step 1 with `<BML>` as the second argument), then run the offline preparation for the mods ([building.md](sdk/building.md#check-offline), "Check offline"):

```powershell
& '<BML>\loader\prepare-mod.cmd' 'C:\Program Files\Bitwig Studio\bin' 'C:\work\check-mods'
```

Expect `PREPARE_OK … runtime_build=<target build key> …`, with the mod in its `Mods:` line (preparation stops with `… isn't available on Bitwig …` for a mod the pack can't translate). Then look in `<BML>\loader\mod-runtime\`: `target-resolution.tsv` must show no `UNMAPPED` row the mod needs, and `helper-linkage-preflight.tsv` and `helper-remap-<mod id>.txt` must show no unresolved references. This uses the real Bitwig of that version and reads its jar; it does not start Bitwig.

**4. Check a helper's names before installing** (BML workspace): `python BML/tools/mod_uses.py check <uses.tsv> <pack.zip> <built-against build>` prints `USES_OK`, or the names the pack can't translate.

These steps check the format, the identity and the rows the tested mods use. They don't check the correctness of every row (contract items 8–11). For that, run BML's map validator, which checks every row against both builds' jars (BML workspace, with both Bitwig versions' jars in `bitwig/jars/<version>/`):

```powershell
python BML\tools\map_validator\validate.py <pack.zip> --out C:\work\validation
```

It needs the jars of every build the pack names (`bitwig/jars/<version>/`, `<version>-mac/`, `<version>-linux/`). It exits with 0 when no hard check fails, and writes a report per remap to the `--out` folder. `BML\tools\map_validator\compare.py <a.zip> <b.zip>` compares two packs row by row.

## 7. Platforms

Packs are per build, and a build is per platform: Bitwig's macOS and Linux `bitwig.jar` differ from the Windows one (about 6% of members have different names, and some classes exist on one platform only), so each needs its own pack, with its own `target_jar_sha256`.

- `builds.json` records `platform` and `architecture` per build. BML's Maps page names the platform values `windows`, `macos` and `linux` as Windows, macOS and Linux. BML's packs use `x64` for Windows and Linux and `universal` for macOS, whose one `bitwig.jar` serves arm64 and x86_64: a build has one entry, since no two entries may share a hash. A mod built against a macOS build declares `"architecture": "universal"`.
- BML selects a pack by the `bitwig.jar` hash only, so a pack for another platform is selected the same way.
- A mod's `built_against` names its platform; BML matches it against `builds.json`. Nothing in the format requires a remap's source and target to be on the same platform: a mod written against any platform's build runs on another platform when that platform's pack lists the build in `builds.json` and holds a remap from it.
- Build keys and pack ids are free-form within their character rules. BML's own packs use `<platform>-<version>` build keys (`windows-6.1.4`, `macos-6.1.4`, `linux-6.1.4`), `<platform>-<version>-map1` ids and `<id>-v<pack version>.zip` file names, e.g. `linux-6.1.4-map1-v2.0.0-dev.9.zip`. Every BML pack carries a remap from each of the six builds mods are written against, 6.1.3 and 6.1.4 on Windows, macOS and Linux, except from its own target build: `remap/windows-6.1.3__linux-6.1.4.tsv.gz`, `remap/macos-6.1.4__linux-6.1.4.tsv.gz`, `remap/linux-6.1.3__linux-6.1.4.tsv.gz` and so on.
- A remap between two platforms leaves unmapped the classes of packages that exist in only one of the two `bitwig.jar`s (for example `mac_windowing_system` on macOS, `win32_windowing_system` on Windows, `x11_windowing_system` on Linux): that code is the platform's own, an analogue of the other platform's rather than the same code, so a mod that hooks or uses it isn't available on the other platforms.
