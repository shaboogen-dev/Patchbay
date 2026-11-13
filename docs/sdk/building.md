# Building and checking a mod

A build turns the source folder ([mods.md](mods.md)) into the installable folder: `helper.jar`, the finished `mod.json`, the mod's `README.md`, the images it references and the files from `package/`. The offline check then runs BML's preparation against a Bitwig install without starting Bitwig. A mod is ready to try in Bitwig when the check prints `PREPARE_OK`.

There are two ways to build:

- **Anywhere** (below): the mod kit's commands, run by hand. This works for a mod folder in any location.
- **Inside a BML workspace**: for a mod kept in the `Mods` repository, `python BML/tools/build_mod.py <mod-id>` runs the same steps and writes `Mods/<mod-id>/releases/<mod-id>-<version>.zip` (see the end of this page).

## What you need

| What | Check |
|---|---|
| JDK 25.0.4.1 | `& 'C:\Program Files\Java\jdk-25.0.4.1\bin\javac.exe' -version` prints `javac 25.0.4.1` |
| Bitwig Studio 6.1.4's `bitwig.jar` and `libs.jar` | `(Get-FileHash <bitwig.jar>).Hash` is `5C38187EEBE14CE7E5FDAA9D8F1EAC0E9B2A34116F57D6A854CEF7A31F2845C0` |
| The mod kit: `bml-api.jar`, `bml-modkit.jar` | in a BML install from the tester release, `<BML>\modkit\`; in a BML workspace, `BML\modkit\out\` (`python BML\modkit\build.py` builds them) |
| Each framework the mod uses: its `helper.jar` | in a BML install from the tester release, `<BML>\mods\<framework-id>\helper.jar`; in a BML workspace, inside its release ZIP, e.g. `BML\frameworks\action-menu-composition-framework\releases\action-menu-composition-framework-0.5.1-dev.1.zip` |
| Python 3 | for the offline check and the version check |

## Build, step by step

Run in PowerShell. Set these once per session; the `C:\work\...` paths are examples, so put your own folders there (`C:\bml` stands for your BML workspace: the folder holding `BML\`) (every later command uses the variables, except the few paths marked as examples).

```powershell
$Jdk       = 'C:\Program Files\Java\jdk-25.0.4.1\bin'
$Kit       = 'C:\bml\BML\modkit\out'                               # bml-api.jar, bml-modkit.jar
$BitwigJar = 'C:\Program Files\Bitwig Studio\bin\bitwig.jar'        # Bitwig 6.1.4
$LibsJar   = 'C:\Program Files\Bitwig Studio\bin\libs.jar'
$Mod       = 'C:\work\example-device-name'                          # the mod's source folder
$Work      = 'C:\work\build\example-device-name'                    # scratch space for this build
$Out       = 'C:\work\out\example-device-name'                      # the installable folder; its name is the mod ID
```

In a BML workspace, Bitwig 6.1.4's jars are also at `C:\bml\bitwig\jars\6.1.4\bitwig614.jar` and `libs614.jar`.

If you put the steps in a `.ps1` script, stop it when a tool fails: after each `javac`, `java` and `jar` command add `if ($LASTEXITCODE) { throw "step failed" }`.

**1. Start clean.**

```powershell
Remove-Item -Recurse -Force $Work, $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$Work\classes", "$Work\deps", $Out | Out-Null
```

**2. Make the compile jar** (once per Bitwig version; reuse it for every mod):

```powershell
$Compile = 'C:\work\build\bitwig614-compile.jar'   # anywhere outside $Work (step 1 deletes $Work)
if (-not (Test-Path $Compile)) { & "$Jdk\java.exe" -jar "$Kit\bml-modkit.jar" compile-jar $BitwigJar $Compile }
```

It prints `COMPILE_JAR_OK classes=31619 out=...` for Bitwig 6.1.4.

**3. Get the frameworks' jars.** For each framework in `build.json`'s `classpath_mods`, unpack its release ZIP; the jar is then at `$Work\deps\<framework-id>\helper.jar`:

```powershell
$FrameworkZips = @(
    'C:\bml\BML\frameworks\action-menu-composition-framework\releases\action-menu-composition-framework-0.5.1-dev.1.zip'
    # one line per framework in classpath_mods
)
foreach ($zip in $FrameworkZips) { Expand-Archive $zip -DestinationPath "$Work\deps" }
$Deps = @(Get-ChildItem "$Work\deps\*\helper.jar" | ForEach-Object FullName)
```

The manual route doesn't read `build.json`: list the same frameworks and versions as its `classpath_mods` (`build.json` is what `tools/build_mod.py` and anyone rebuilding the mod read). A mod that uses no framework skips this step and sets `$Deps = @()`. Only the frameworks the code compiles against are needed here; a framework that only has to be present at runtime (a framework's own prerequisite, such as Framework: Settings next to Framework: Panels) is needed for the offline check, not for the build.

**4. Compile.**

```powershell
$Classpath = (@($Compile, $LibsJar, "$Kit\bml-api.jar") + $Deps) -join ';'
$Sources = @(Get-ChildItem "$Mod\src" -Recurse -Filter *.java | ForEach-Object FullName)
& "$Jdk\javac.exe" --release 21 -proc:none -encoding UTF-8 -implicit:none -sourcepath "$Mod\src" -cp $Classpath -d "$Work\classes" $Sources
```

No output means success.

**5. Finish.** Checks every `@Hook` against Bitwig's jar, writes the hooks and the non-public Bitwig members the code uses to `hooks.json`, and rewrites the classes in place (`bitwig.obf.X` back to `X`, the kit's annotations removed):

```powershell
& "$Jdk\java.exe" -jar "$Kit\bml-modkit.jar" finish "$Work\classes" "$Work\hooks.json" $BitwigJar $LibsJar @Deps
```

It prints `MODKIT_FINISH_OK hooks=<n> non_public=<n> classes_rewritten=<n>`: the number of `@Hook` methods, of non-public Bitwig classes and members the code uses, and of class files it rewrote (the classes it had to change; a mod without hooks usually still has some). On a problem it prints `MODKIT_ERROR ...` lines and exits with 1; fix the source and start again from step 1.

**6. Make `helper.jar`.** Non-Java files in `src/` go into the jar too:

```powershell
Get-ChildItem "$Mod\src" -Recurse -File | Where-Object Extension -ne '.java' | ForEach-Object {
    $dest = Join-Path "$Work\classes" $_.FullName.Substring("$Mod\src\".Length)
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    Copy-Item $_.FullName $dest
}
& "$Jdk\jar.exe" --create --file "$Out\helper.jar" -C "$Work\classes" .
```

**7. Write the finished `mod.json`** into `$Out`, from the source `mod.json` and `$Work\hooks.json` (rules in the next section). Step 1 deletes `$Out`, so keep your finished `mod.json` somewhere else too (next to the source, as `mod.finished.json`, outside `package\`), and copy it back after a rebuild, once you have checked that `hooks.json` hasn't changed.

**8. Copy the README, its images and the package files** next to it (never put a `mod.json` in `package\`: it would replace the finished one):

```powershell
Copy-Item "$Mod\README.md" $Out
if (Test-Path "$Mod\images") { Copy-Item "$Mod\images" $Out -Recurse }
if (Test-Path "$Mod\package") { Copy-Item "$Mod\package\*" $Out -Recurse }
```

`$Out` is now the installable mod folder. For a release, also copy `src\` and `build.json` into it (BML's mods always ship their source, so anyone can rebuild them), then ZIP it with the folder at the top:

```powershell
Copy-Item "$Mod\src" $Out -Recurse; Copy-Item "$Mod\build.json" $Out
Compress-Archive -Path $Out -DestinationPath 'C:\work\out\example-device-name-0.1.0.zip'
```

## The finished mod.json

`hooks.json` looks like this (from "Commander: Enter Selects First Result"):

```json
{
  "modkit": "0.1.0-dev.1",
  "hook_classes": ["com.bitwig.mods.commanderfirstresult.CommanderFirstResult"],
  "hooks": [
    {
      "id": "enter-select-first-result",
      "transform": "INSERT_AT_ENTRY",
      "helper_class": "com.bitwig.mods.commanderfirstresult.CommanderFirstResult",
      "target": { "kind": "method", "owner": "cNC", "name": "eventOccurred", "descriptor": "(Ljava/lang/Object;)V" },
      "bridge": { "method": "ensureFirstSelection", "descriptor": "(LcNC;)V", "args": ["this"] }
    }
  ],
  "access": [
    { "kind": "class", "class": "cNC" },
    { "kind": "field", "owner": "cNC", "name": "cfM", "descriptor": "LcNz;" }
  ]
}
```

Start from a copy of the source `mod.json` and change it like this:

1. Add `"targets": {}` and `"hooks": []` if they are missing.
2. **Target IDs.** Each target object in `hooks.json` (a hook's `target`, `method_from`, `match.target`, and each entry of `access`) becomes an entry of `targets`. If `targets` already holds an identical object, reuse its ID. Otherwise the ID is `<owner>.<name>` for a method or field, `<class>` for a class (a method `vhT` of `com/bitwig/x/Abc` gets `com.bitwig.x.Abc.vhT`), with `/` replaced by `.`, `$` by `_`, and any character other than `A-Z a-z 0-9 _ . -` removed (`<init>` becomes `init`); if that ID is taken, add `-2`, `-3`, ... Copy the object unchanged as the target's value.
3. **Hooks.** For each entry of `hooks.json`'s `hooks`, append to `hooks`, in this key order:
   - `"id"`: as in `hooks.json`;
   - `"target"`: the target ID of its `target`;
   - `"transform"`: as in `hooks.json`;
   - only if it has `method_from`: `"method": {"from_target": "<target ID of method_from>"}` and `"body": {"template": "<its body>"}`;
   - only if it has `match`: `"match"` with `"opcode"`, `"target": "<target ID of match.target>"` and `"occurrence"`, and nothing else, even when `match` also lists `owner`/`name`/`descriptor`. Only when `match` has no `target` (a call to a JDK method), copy `owner`, `name`, `descriptor` as they are instead;
   - `"bridge"`: copied unchanged. (Its `args` name JVM local slots: in an instance method slot 0 is `this`, so `@Arg(0)` appears as `"arg1"`; in a static method `@Arg(0)` is `"arg0"`; a `long` or `double` parameter takes two slots. `WRAP_RETURN` appears as transform `WRAP_OBJECT_RETURN`, and `REPLACE_CALL` as `REPLACE_INVOKE`, both with `args: []`. All of that is correct.)
   Leave out `helper_class`.
4. **Helper.** If `hook_classes` is not empty, add `"helper": {"jar": "helper.jar", "class": "<hook_classes[0]>"}`. `hook_classes` never has more than one entry; if the source `mod.json` already has a `helper`, its `class` must be the same.
5. **Fields.** For each entry of `hooks.json`'s `fields`, add a target for its `owner`, and a class target for each Bitwig class in its `descriptor`, then append to `"fields"`: `"id"`, `"owner"` (the owner's target ID), `"name"`, `"descriptor"`. `uses.tsv` also lists those classes as `C` rows. `name` must start with the mod's ID, every character but letters and digits as `_`, then `$`. Leave `"fields"` out when there are none.
6. **Opens.** If `access` is not empty, add `"opens"`: the target IDs of its entries, sorted alphabetically.
7. Keep everything else from the source `mod.json` as it is. Order doesn't matter to BML, neither of keys nor of `targets` entries nor of `opens`; BML's own mods use `manifest_version`, `mod`, `built_against`, `helper`, `targets`, `hooks`, `fields`, `opens`, then the rest.

For the `hooks.json` above, the result's added parts are:

```json
"helper": { "jar": "helper.jar", "class": "com.bitwig.mods.commanderfirstresult.CommanderFirstResult" },
"targets": {
  "cNC.eventOccurred": { "kind": "method", "owner": "cNC", "name": "eventOccurred", "descriptor": "(Ljava/lang/Object;)V" },
  "cNC": { "kind": "class", "class": "cNC" },
  "cNC.cfM": { "kind": "field", "owner": "cNC", "name": "cfM", "descriptor": "LcNz;" }
},
"hooks": [
  {
    "id": "enter-select-first-result",
    "target": "cNC.eventOccurred",
    "transform": "INSERT_AT_ENTRY",
    "bridge": { "method": "ensureFirstSelection", "descriptor": "(LcNC;)V", "args": ["this"] }
  }
],
"opens": ["cNC", "cNC.cfM"]
```

A mod without hooks or non-public members (`"hooks": []`, `"access": []`) just gets `"targets": {}` and `"hooks": []`.

## Check which Bitwig versions it runs on

In a BML workspace, `BML/tools/mod_uses.py` lists every Bitwig class and member `helper.jar` uses and checks them against a map pack:

```powershell
Set-Location C:\bml   # your BML workspace
python BML\tools\mod_uses.py list "$Out\helper.jar" $BitwigJar > "$Work\uses.tsv"
foreach ($v in '6.0','6.1','6.1.1','6.1.2','6.1.3') { "$v -> " + (python BML\tools\mod_uses.py check "$Work\uses.tsv" (Get-ChildItem "maps\$v\*.zip").FullName 6.1.4 | Out-String).Trim() }
```

The loop leaves out 6.1.4: that is the version the names are written in, so nothing is translated there. (`Set-Location` moves to the workspace, so `BML\tools\...` and `maps\...` resolve from there; the other paths are absolute.) Each row of `uses.tsv` is a kind (`C` class, `F` field, `M` method), the class as the code names it, then for members the name, the descriptor and the class that declares it. `USES_OK` means every name translates to that version. Otherwise it lists the names that don't: BML reads the same `uses.tsv` from the release, and the mod isn't available there until its author handles that version. The packs for macOS and Linux (`maps\macos-<v>\`, `maps\linux-<v>\`, 6.1.4 included) check the same way; the last argument is the build the mod is written against, `6.1.4` for Windows or e.g. `macos-6.1.4`. This checks `helper.jar` only. Hook targets in `mod.json` are checked when BML prepares the mod for a version: the offline check below against a Bitwig of that version (any of 6.0–6.1.3 you have installed), or the first launch there.

## Check offline

The offline check is BML's own preparation of Bitwig, without starting Bitwig. It reads Bitwig's jar, translates and checks every mod, patches the classes, verifies the patched classes with the JVM's verifier, and checks that every Bitwig class and member the helper jars use resolves. Bitwig's files are only read.

Put the mod's folder and the folders of every framework it uses into one folder (each release ZIP unpacks to a folder named by its mod ID: `Expand-Archive <zip> -DestinationPath C:\work\check-mods`; keep the folders as they are: their `src\`, `build.json`, `uses.tsv` and `SHA256SUMS.txt` do no harm), here `C:\work\check-mods`. Include each framework's own prerequisites too (Framework: Panels needs Framework: Settings and Framework: Actions and Menus). A manifest has no field for the frameworks a mod needs: putting them next to the mod is up to you (and, for users, the mod's README).

**With a BML install** (`<BML>` is the folder holding `loader\`):

```powershell
& '<BML>\loader\prepare-mod.cmd' 'C:\Program Files\Bitwig Studio\bin' 'C:\work\check-mods'
```

It prepares every mod folder in `C:\work\check-mods` and writes the result to `<BML>\loader\mod-runtime\` (replacing the install's prepared runtime; BML prepares again at its next launch). The last lines are:

```text
PREPARE_OK loader=0.34.0-dev.15 runtime_build=windows-6.1.4 Bitwig=6.1.4 mods=2 transforms=20 replacement_classes=15
Mods: action-menu-composition-framework, example-device-name
Stock bitwig.jar was read and hashed; it was NOT modified.
```

and the exit code (`$LASTEXITCODE`) is 0.

**In a BML workspace**, build a throwaway install outside `testing\installs\` and run its guard:

```powershell
Set-Location C:\bml   # your BML workspace
python BML\tools\bml_build.py suite 6.1.4 --out C:\work\check-install --mods-from C:\work\check-mods
python C:\work\check-install\guard_selective.py --prepare --bitwig-bin 'C:\Program Files\Bitwig Studio\bin'
```

`--out` must not exist yet. The suite build ends with a `Next:` hint without `--bitwig-bin`; without it the guard uses `C:\Program Files\Bitwig Studio\bin`. The guard prints `STOCK_HASH_OK 6.1.4 <hash> MODS <n>` (the Bitwig jar is 6.1.4's), then `OFFLINE_RESULT {"status": "OFFLINE_PASS", ...}` with `"prepare_ok": true`, and exits with 0. The `PREPARE_OK ...` line itself is inside `OFFLINE_RESULT` (`preparer_signal`) and near the top of the full preparation log, `C:\work\check-install\OFFLINE-SELECTIVE-PREP.log`.

Either way, the Bitwig folder you pass is a Bitwig 6.1.4 install's `bin` folder: next to `bin\` it needs Bitwig's `jre\` and `resources\` (for `kind: bitwig` resources).

### Other Bitwig versions offline (BML workspace)

`mod_uses.py` checks `helper.jar` only. To check hook targets on 6.0–6.1.3, prepare against a Bitwig root of that version made from the workspace's copies (no install needed):

```powershell
Set-Location C:\bml   # your BML workspace
$v = '6.0'; $n = $v.Replace('.', ''); $root = "C:\work\bw\$v"
New-Item -ItemType Directory -Force "$root\bin", "$root\resources" | Out-Null
Copy-Item "bitwig\jars\$v\bitwig$n.jar" "$root\bin\bitwig.jar"
Copy-Item "bitwig\jars\$v\libs$n.jar" "$root\bin\libs.jar"
Copy-Item -Recurse "bitwig\unpacked\$v\PFiles64\Bitwig Studio\resources\icons" "$root\resources\icons"
New-Item -ItemType Junction -Path "$root\jre" -Target 'C:\Program Files\Java\jdk-25.0.4.1' | Out-Null
python BML\tools\bml_build.py suite $v --out "C:\work\check-install-$v" --mods-from C:\work\check-mods
python "C:\work\check-install-$v\guard_selective.py" --prepare --bitwig-bin "$root\bin"
```

Repeat for each version. Expect `OFFLINE_PASS` with `runtime_build=windows-<v>`.

## Changing an existing mod

1. Bump `mod.version` in `mod.json` (`0.1.0-dev.1` → `0.1.0-dev.2`).
2. Make the change in `src\` / `package\`. Keep `mod.id`, action IDs, and any keys the mod stores settings under (moving a control to another section or relabelling it keeps the user's value when the getter and setter read the same stored key).
3. Update `mod.description` and `README.md` to say what the mod does now.
4. Build (steps 1–8, or `build_mod.py <mod-id>` in a workspace). Compare the new `hooks.json` with the previous one: every hook that should be unchanged still has the same `id`, target and transform.
5. Version check (`mod_uses.py`) and offline check on 6.1.4; for new or changed hooks, also the other versions (above).
6. Test the change in Bitwig, and repeat a quick check of what the mod did before.

## Try it in Bitwig

Copy the mod's folder (and its frameworks) into `<BML>\mods\`, tick it in the Patchbay window, and launch Bitwig from there. Lines the mod writes with `MOD.log(...)` appear as `[<mod-id>] ...` in Bitwig's log, `%LOCALAPPDATA%\Bitwig Studio\BitwigStudio.log`; in a Debug launch also in `<BML>\sessions\<session>\traces\mod.log`.

## When something fails

| Where | Message | Usual cause |
|---|---|---|
| javac | `package bitwig.obf does not exist` | The compile jar is not on `-cp`. |
| javac | `cannot access ...: class file for ... not found` | `libs.jar` or a framework's `helper.jar` is not on `-cp`. |
| javac | `package bml.api does not exist` | `bml-api.jar` is not on `-cp`; or the classpath was written with `/c/...` paths from a POSIX shell. Use Windows paths. |
| finish | `MODKIT_ERROR ...` | A hook doesn't match Bitwig's jar: a wrong method name, an ambiguous name (add the descriptor it lists), a parameter type that doesn't accept the value, hooks in two classes. |
| prepare | `PREPARE_FAILED: Selected mod '<id>' cannot prepare: Cannot prepare package: hooks must be an array` | The finished `mod.json` lacks `"hooks": []` (or `targets` lacks `{}`). |
| prepare | `... Missing package resource: <path>` | A resource or descriptor named in `mod.json` isn't in the mod's folder; check step 8. |
| prepare | `DEEP_VERIFY_FAIL <class> java.lang.NoClassDefFoundError: bml/preferences/...` | Framework: Settings is missing from the checked folder (a mod or framework there needs it). |
| prepare | `DEEP_VERIFY_FAIL <class> ... Bad local variable type` | A `WRAP_RETURN` hook takes `@This` or `@Arg` ([writing.md](writing.md)). |
| Bitwig | status bar shows `action.<something>.name`; log has `base-localization error: Localized text for key "action...name" not found` | An action built with a label as its ID ([writing.md](writing.md), "Pitfall: actions you build yourself"). |
| prepare | a target reported `UNMAPPED` | One of the mod's Bitwig names has no counterpart on the Bitwig version being prepared. |

**What to report** when you ask for help or file a problem, so the cause can be found without guessing:

1. The mod's source folder (`mod.json`, `build.json`, `src\`, `package\`), and which BML loader and framework versions you used.
2. The exact command that failed and its **complete** output: javac's errors, every `MODKIT_ERROR` line, or the preparation output from the first `PREPARE_FAILED` or `DEEP_VERIFY_FAIL` line to the end (with a workspace's guard: `OFFLINE-SELECTIVE-PREP.log` and `OFFLINE-SELECTIVE-SUMMARY.json`).
3. For a preparation failure: the finished `mod.json` and `hooks.json`, and from `<BML>\loader\mod-runtime\`: `target-resolution.tsv`, `helper-linkage-preflight.tsv` and `resource-resolution.tsv`.
4. For a problem inside Bitwig: the Bitwig version, what you did and what happened, `[<mod-id>]` and `[<framework-id>]` lines from `BitwigStudio.log`, and from a Debug launch the session folder `<BML>\sessions\<session>\`.

Don't send Bitwig's jars, the compile jar, or anything copied from Bitwig.

## Inside a BML workspace

For a mod kept in `Mods/<mod-id>/` (layout in `Mods/README.md`), with `"kit": true` in `build.json`:

```powershell
Set-Location C:\bml   # your BML workspace: the folder holding BML\ and Mods\
python BML\tools\build_mod.py <mod-id>
```

It runs steps 1–8, takes Bitwig's jars from the workspace's `bitwig/jars/<built_against>/`, takes each `classpath_mods` framework's `helper.jar` from `BML/frameworks/<framework-id>/releases/<framework-id>-<version>.zip` (a bare `<mod-id>` means the version in the Accepted column of `Mods/README.md`), ships each image the README references, usually from `images/` (and stops if one is missing, outside the mod's folder, or not a PNG, JPEG or GIF: [README images](mods.md#readme-images)), adds `src/`, `build.json`, `uses.tsv` and `SHA256SUMS.txt`, and writes `Mods/<mod-id>/releases/<mod-id>-<version>.zip`. It refuses to build a mod without Java source, and to overwrite an existing release (`--replace` rebuilds it, `--out DIR` writes elsewhere). Unpack the ZIP for the offline check.
