# Converting an existing mod

A converted mod is written in the style of [writing.md](writing.md): built against Bitwig 6.1.4, every Bitwig name in its Java source (none in strings), hooks as `@Hook` annotations, `bml.api.Bml` for its name, resources and logging, and a framework wherever one covers what it does. Its behaviour stays the same.

## 1. Look at what the mod has

Open the mod's `mod.json` and source and note which of these apply. Most old mods have several.

| You find | Case |
|---|---|
| `built_against.bitwig_version` is not `6.1.4` | **A: older Bitwig version.** Port it first (step 2). |
| `System.getenv("BML_MOD_CONFIG")`, a `Properties` file loaded from it, keys like `mod.<id>.target.<name>.owner`; or `Class.forName("...")`, `getDeclaredMethod("...")`, `getDeclaredField("...")`, `setAccessible(true)` on Bitwig classes | **B: reflection strings and binding lookups.** Replace them with Java references (step 3). |
| `mod.json` has `targets`, `hooks` and `helper` written by hand, and hook methods taking `Object` | **C: hand-written manifest.** Turn each hook into an `@Hook` method (step 4). |
| Its own logging code (writing to `BITWIG_RE_MOD_LOG`), or resource paths read from the bindings | Replace with `bml.api.Bml` (step 5). |
| A registration with a framework whose descriptor or provider doesn't match the framework's current API | Rewrite the provider against [frameworks.md](frameworks.md) (step 6). |
| Code that patches menus, Settings or the Inspector itself | Consider moving it onto a framework (step 6). |

Work in a copy of the mod. Keep the original next to it: you need its manifest to look names up.

## 2. Port to Bitwig 6.1.4 names (case A)

BML's source porter renames Bitwig names in Java source from one version to another, using a map pack's remap. It compiles the source against the old Bitwig jar, so every name is resolved to exactly what it refers to; only Bitwig names the remap knows are renamed. `port_manifest.py` does the same for a hand-written `mod.json`.

**Pick the remap** (map packs ship with BML: in a BML workspace, `maps\<version>\` for Windows, `maps\macos-<version>\` and `maps\linux-<version>\`; a pack is a ZIP; unpack it with `Expand-Archive`). Port on the mod's own platform: `<p>` is its `built_against.platform` (`windows`, `macos` or `linux`), and a pack's folder is `maps\<v>\` on Windows and `maps\<p>-<v>\` on macOS and Linux.

| Mod built against | Map pack to unpack | Remap file inside it | Flag |
|---|---|---|---|
| 6.1.3 | the 6.1.4 pack of its platform | `remap\<p>-6.1.3__<p>-6.1.4.tsv.gz` | none |
| 6.0, 6.1, 6.1.1 or 6.1.2 (`<v>`) | the `<v>` pack of its platform | `remap\<p>-6.1.4__<p>-<v>.tsv.gz` | `--invert` |

`port_manifest.py` changes `built_against.bitwig_version` only; the platform and architecture stay the mod's.

You also need the old version's `bitwig.jar` and `libs.jar` (in a workspace, `bitwig\jars\<v>\bitwig<v without dots>.jar` and `libs<...>.jar` for Windows, `bitwig.jar` and `libs.jar` in `bitwig\jars\<v>-mac\` or `bitwig\jars\<v>-linux\` for macOS and Linux). For mod kit source, use the old version's compile jar: `python BML\modkit\build.py compile-jar <v>` for Windows, `compile-jar <p>-<v>` for macOS and Linux.

```powershell
Set-Location C:\bml   # your BML workspace
$Jdk   = 'C:\Program Files\Java\jdk-25.0.4.1\bin'
$Remap = 'C:\work\maps\windows-6.1.4-map1\remap\windows-6.1.3__windows-6.1.4.tsv.gz'
Expand-Archive maps\6.1.4\windows-6.1.4-map1-v2.0.0-dev.9.zip -DestinationPath C:\work\maps

# Java source
& "$Jdk\java.exe" BML\tools\porter\SourcePorter.java --cp 'bitwig\jars\6.1.3\bitwig613.jar;bitwig\jars\6.1.3\libs613.jar' --map $Remap --src C:\work\old-mod\src --out C:\work\ported-mod\src

# a hand-written mod.json (targets, hook descriptors, built_against)
python BML\tools\porter\port_manifest.py C:\work\old-mod\mod.json $Remap 6.1.4 --out C:\work\ported-mod\mod.json
```

Add `--invert` to both commands when the table says so. The porter prints `ported <n> files: <n> renames, <n> items for review` and exits with 0, or with 2 when it reports review items: string literals that look like Bitwig names (`REVIEW <file>:<line> string literal "..." looks like an obfuscated name; port by hand`). Look at each one: a Bitwig name in a string must be ported by hand (or, better, removed in step 3); anything else (a property key such as `"name"`) is left alone. `port_manifest.py` stops at any target the remap doesn't know: that class or member has no counterpart in 6.1.4, and the mod's author has to decide what to do instead.

If the old source already uses the mod kit (`import bitwig.obf...`), give the porter the old version's compile jar instead: `--cp '<old compile jar>;<old libs.jar>;<bml-api.jar>;<framework jars>'`. It renames `bitwig.obf` imports and the method names in `@Hook` strings too.

Comments are not ported. Make sure no comment repeats an old name.

From here on, work in the ported copy, against 6.1.4.

## 3. Replace reflection strings and binding lookups (case B)

Each Bitwig name a mod looks up at runtime comes from somewhere you can read:

- A **binding key** `mod.<mod-id>.target.<target-id>.<field>` refers to the target `<target-id>` in the `targets` of the mod `<mod-id>`'s `mod.json`: usually the mod's own, sometimes a framework's. Its `kind`, `owner`, `name` and `descriptor` are the Bitwig names, in the version of that manifest's `built_against`. If that isn't 6.1.4 (for a framework's manifest, step 2 didn't port it), port a copy of it with `port_manifest.py` as in step 2 and read the names from the copy. A target's ID describes what the target was used for when the manifest was written; trust its `owner`, `name` and `descriptor`, not its ID. `owner` may use `/` (`com/bitwig/x/Y`); in Java that's `com.bitwig.x.Y`.
- A **literal** in `Class.forName("...")`, `getDeclaredMethod("...")` and so on is the name itself.

For each lookup, write the plain Java it stands for:

| Old code | New code |
|---|---|
| `Class.forName(owner)` / a target with `"kind": "class"`, `"class": "dWb"` | `import bitwig.obf.dWb; // what it is` (a class in a package, e.g. `com/bitwig/flt/app/CHR`: `import com.bitwig.flt.app.CHR;`) |
| a static method target `owner dWb`, `name UNj`, `descriptor (I)LdWb;`, invoked with `(3)` | `dWb.UNj(3)` |
| an instance method target `owner dWb`, `name HL`, `descriptor (Ldve;)V`, invoked on `parent` with `child` | `((dWb) parent).HL((dve) child)`, or declare the variables with Bitwig's types and drop the casts |
| a field target `owner cNC`, `name cfM`, `descriptor LcNz;`, read with `field.get(listener)` | `listener.cfM` (with `listener` typed `cNC`) |
| a constructor (`name <init>`) | `new X(...)` |
| `isInstance` on a looked-up class | `instanceof X` |
| `setAccessible(true)` | nothing: the build lists non-public members under `opens`, and BML makes them public |
| a method found by name and argument count in a loop over `getMethods()` | the one method the target names: its descriptor gives the parameter types |

Then delete what is left: the bindings loader, the lookup helpers, the target IDs as string constants, and the targets in `mod.json` that only these lookups used (the build writes the targets it needs). Give every Bitwig name you wrote a comment ([writing.md](writing.md)).

When a descriptor has a `long` or `double` parameter, an `int` or another primitive, write the Java type it stands for (`J` long, `D` double, `I` int, `Z` boolean, `F` float).

## 4. Turn hand-written hooks into @Hook methods (case C)

For each entry of `hooks` in the (ported) `mod.json`, find its `target` in `targets` (`owner`, `name`, `descriptor`) and its `bridge.method` in the helper class, and annotate that method:

| `transform` | `@Hook(at = ...)` |
|---|---|
| `INSERT_AT_ENTRY` | `At.ENTRY` |
| `INSERT_BEFORE_FINAL_RETURN` | `At.BEFORE_RETURN` |
| `WRAP_OBJECT_RETURN` | `At.WRAP_RETURN` |
| `REPLACE_INVOKE` | `At.REPLACE_CALL`, with `callOwner`, `call` and `occurrence` from `match` |
| `ADD_METHOD` with body template `CALL_SUPER_THEN_STATIC` | `At.OVERRIDE_AFTER_SUPER` |
| `ADD_METHOD` with `CALL_STATIC_THEN_SUPER` | `At.OVERRIDE_BEFORE_SUPER` |
| `ADD_METHOD` with `CALL_STATIC_ONLY` | `At.OVERRIDE_INSTEAD` |

```java
@Hook(target = cNC.class, method = "eventOccurred", at = At.ENTRY, id = "enter-select-first-result")
public static void ensureFirstSelection(@This cNC listener) { ... }
```

- `target` = the target's `owner`, `method` = its `name`; add the descriptor (`"eventOccurred(Ljava/lang/Object;)V"`) only when the build says the name is ambiguous. For `ADD_METHOD`, `target` is the hook's class target and `method` the name of `method.from_target`.
- `id`: keep the old hook ID.
- **Parameters.** `bridge.args` lists what the old hook received, in order. `"this"` becomes a parameter marked `@This`. `"argN"` is a JVM local slot, not a parameter index: in an instance method slot 0 is `this`, and a `long` or `double` parameter takes two slots. Count the target descriptor's parameters to turn the slot into the parameter index for `@Arg(n)` (for `(Ljava/lang/String;)...` on an instance method, `arg1` is `@Arg(0)`). For `WRAP_OBJECT_RETURN`, the first bridge parameter is the returned value: mark it `@Return`. For `REPLACE_INVOKE`, the parameters are the call's receiver and arguments, unannotated.
- **Types.** Replace `Object` parameters with the Bitwig types they really are, so the body needs no casts.
- **`REPLACE_INVOKE` occurrences.** `match.target` names the call: `callOwner` is its `owner`, `call` its `name` (with the descriptor if needed). `@Hook`'s `occurrence` counts matching calls in Bitwig's original method. A manifest's `occurrence` counts the calls left after that mod's earlier hooks on the same call: where a hand-written manifest has several `REPLACE_INVOKE` hooks on one method, recount in the original method.
- Move every hook method into one class: the helper class.
- `trace_targets` are a debugging aid; drop them, or keep them in `mod.json` as they are.

Then remove `helper`, `targets` and `hooks` from the source `mod.json` (the build writes them), set `"kit": true` in `build.json`, and build ([building.md](building.md)). The build's `hooks.json` should list the same hooks with the same IDs, targets and transforms as the old manifest; compare them.

## 5. Use bml.api.Bml (all cases)

| Old code | New code |
|---|---|
| copied logging code writing `[mod-id] ...` to `System.err` and `BITWIG_RE_MOD_LOG` | `private static final Bml.Mod MOD = Bml.mod("<mod-id>");` and `MOD.log(...)` |
| `mod.<id>.resource.<rid>.path` from the bindings | `MOD.resource("<rid>")` |
| `mod.<id>.name` from the bindings | `MOD.name()` |
| `mod.<id>.registration...` keys | not needed in a mod: frameworks find registrations themselves |

Declare every file the mod reads at runtime as a resource. Icons are resources too: one of Bitwig's by reference (`"kind": "bitwig"`), or the mod's own SVG; if the old mod shipped a copy of one of Bitwig's files, delete the copy and reference it instead ([mods.md](mods.md)).

## 6. Frameworks

If the mod adds actions, menu items, Settings controls or Inspector content, it must use the framework for it ([frameworks.md](frameworks.md)), even when its old hooks still work: register in `mod.json`, add the descriptor to `package/`, write the provider, add the framework to `build.json`'s `classpath_mods`, and delete the hooks and code that did it before.

If the mod already registers with a framework but in a form the framework no longer reads (other descriptor keys, a provider that isn't the framework's provider interface, Bitwig names looked up through the framework's targets), rewrite the registration against the current API:

- **Descriptor:** replace the old file with exactly the two lines [frameworks.md](frameworks.md) shows, `api.version=1` (the current API's version, whatever number the old file had) and `provider.class=...`. Name it as frameworks.md does (`actions.properties`, `preferences.properties`, `inspector-blocks.properties`) and update the registration's `descriptor`; BML reads whatever file the registration names, so the name is a convention. Keep the registration's `id`.
- **What to show:** read what the old descriptor and provider said (which context or menu, the title or label, top or bottom, what each entry contains) and say the same with the builder calls. Old keys with no counterpart in the current API (an owner ID, a contribution kind, the name of a factory method, layout modes) are dropped: the framework now takes the mod ID from the registration, and the builder call is the factory. An old entry ID becomes the new block, control or action ID.
- **Provider:** the provider becomes a public class with a public no-argument constructor implementing the framework's provider interface. Whatever the old factory method received (the panel being built, a parent widget, a bindings `Properties`) comes from the framework's context object now (for the Inspector, `BlockContext.panel()`), or isn't needed. Bitwig names the old provider looked up through the framework's targets (`mod.<framework-id>.target.<id>...`) are in that framework's old `mod.json`: look them up there as in step 3.

## 7. Finish

1. Whatever the case: the source `mod.json` has no `helper`, `targets`, `hooks` or `opens` (the build writes them), and `build.json` has `"kit": true` and the frameworks in `classpath_mods` ([mods.md](mods.md)).
2. `mod.json`: `built_against` `6.1.4`; `mod.name` in the "Area: What it does" format (a test-only mod nobody installs may keep any name); a `mod.description` that says what the mod does for a user, in plain words; a new `mod.version`. Keep `mod.id` and every action ID: BML stores enabled mods by mod ID, and Bitwig stores shortcuts by action ID. Keeping hook, resource, registration, block and control IDs as they were is good practice; nothing stored depends on them.
3. Build it ([building.md](building.md)); `MODKIT_FINISH_OK`.
4. Check which versions it translates to (`BML\tools\mod_uses.py`), and check it offline: `PREPARE_OK`.
5. Search the source for leftovers: no `BML_MOD_CONFIG`, no `Class.forName`, no `getDeclared` or `getMethods` on Bitwig classes, no Bitwig name in a string, a comment on every Bitwig name. `Get-ChildItem <mod>\src -Recurse -Filter *.java | Select-String -Pattern 'BML_MOD_CONFIG|forName|getDeclared|getMethods|setAccessible'` finds the first four; read the remaining string literals yourself.
6. Try it in Bitwig: every feature the old mod had still works.

## Worked examples

In a clone of the Mods repository, its git history holds each converted mod before and after (`git log -- <mod-id>` lists the commits; `git show <commit>:<path>` shows a file as it was). They are optional reading; the steps above are complete without them.

- **Commander: Enter Selects First Result** (`commander-first-result`), cases B, C and A, restyled first and then ported: `f2a8e72` is the old style (6.1.3, a hand-written manifest, every Bitwig name read from `BML_MOD_CONFIG`); `af42b1b` the same mod in plain Java with `@Hook`, still on 6.1.3; `1decc71` ported to 6.1.4 by the porter; `73fe1f7` with `bml.api.Bml` for logging.
- **Arranger: Paste Time and Cut Time Fixes** (`paste-time`), case A first and then B and C, onto the Action and Menu framework: `dc4e48e` is the port from 6.1.3 (`SourcePorter`: 36 renames and 3 review items, all the property key `"name"`; `port_manifest.py`: 32 targets, 2 hooks); `52a86c0` the restyle with the mod kit and Action and Menu.
