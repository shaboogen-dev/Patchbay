# Porting a whole-jar mod to BML

For mods made by changing Bitwig's `bitwig.jar` itself: classes edited, classes added, and the modified jar (or a binary diff of it) shipped with an installer that swaps the jar. This page turns such a mod into BML mods. Read [mods.md](mods.md), [writing.md](writing.md) and [building.md](building.md) first.

## How BML differs

| Whole-jar mod | BML mod |
|---|---|
| Ships a modified `bitwig.jar` or a diff of it; an installer replaces Bitwig's jar and keeps a backup. | Ships `mod.json`, a small `helper.jar` with only the mod's own classes, and its files. Bitwig's installed files are never changed: BML patches the classes in its own folder when it prepares Bitwig. No installer, no backup, no restore. |
| One build per Bitwig version (and per platform); the diff applies only to the exact official jar. | One source, written against Bitwig 6.1.4's names on any one platform; BML's map packs translate it to 6.0–6.1.4 on Windows, macOS and Linux. |
| Two jar mods can't be combined: whichever jar is installed wins. | Mods compose: several mods may hook the same Bitwig method; they're applied together. |
| All features in one patch. | Each feature can be its own mod; users enable each one in the Patchbay window. |
| Edits anywhere in a method. | Edits are hooks at defined points: method start, before the final return, on the return value, instead of one call, or a new override. Mid-method edits become a hook on a nearby call. |
| Widened access by editing flags. | The mod kit lists the non-public members the helper uses; BML opens exactly those. |
| Menus, settings, inspector panels built by patching Bitwig's builders. | Use the frameworks ([frameworks.md](frameworks.md)): actions and menu items, Settings controls, Inspector blocks, themes and native devices, without hooks of your own. Don't port these patches as hooks. |

## What you need

1. Your modified jar. If you ship a binary diff, apply it to the official jar first.
2. The **official** `bitwig.jar` of exactly the same Bitwig version **and platform** as your modified jar. Check its SHA-256 against the one your installer pins: `(Get-FileHash <jar>).Hash`.
3. The mod kit (`bml-modkit.jar`, `bml-api.jar`) and, if your jar is not Bitwig 6.1.4 or 6.1.3, the official Bitwig 6.1.4 `bitwig.jar` and `libs.jar` of your platform too.
4. The source of the classes you added, if you have it. If not, recover it from your jar (your own classes only: never ship decompiled Bitwig code). For small classes, `javap -c -p -cp <modified jar> <your.Class>` is enough to rewrite them by hand; for large ones use a Java decompiler of your choice.

## Platforms

Bitwig obfuscates each platform's jar separately. For 6.1.4, measured against the Windows jar: about 31,600 classes on each platform; a few dozen are platform-only (each platform's windowing system and file watching). Of the Windows jar's members, the Mac jar has 92.4% with the same name and descriptor and 6.2% renamed in place; Linux has 89.3% and 6.2%.

- Compare your modified jar only with the official jar **of its own platform**. Against another platform's jar, thousands of classes differ (`jar-diff` then prints `JAR_DIFF_WARNING`).
- BML runs on Windows, Linux and macOS, and a mod may be written against the **6.1.4** (or 6.1.3) names of any of the three: set `built_against.platform` to your jar's platform (`windows`, `macos` or `linux`; `architecture` `x64`, or `universal` on macOS). BML's map packs translate it to the other platforms.
- What the packs can't translate is each platform's own code, the packages only its jar has (the windowing system and file watching, e.g. `com.bitwig.mac_windowing_system`). A mod that hooks or uses one isn't available on the other platforms ([mods.md](mods.md), "Running on several Bitwig versions"). Where you can, hook the shared code the platform's classes call into instead ([writing.md](writing.md), "Calling Bitwig").

## Steps

### 1. Compare the jars

```powershell
java -jar <kit>\bml-modkit.jar jar-diff <official bitwig.jar> <modified bitwig.jar> > C:\work\jar-diff.txt
```

The last line counts the changes, listing only kinds that occur, e.g. `JAR_DIFF_DONE added_classes=1 opened=2 changed_classes=4 hooks=2` (`changed_classes` counts every `CLASS` block, including those with only `OPEN` lines). `hooks` counts method changes that map onto one hook; `needs_review` (`INSERT_MID`, `REWRITE`) and `unsupported` count the rest. If a `JAR_DIFF_WARNING` line follows, the official jar is the wrong version or platform: get the right one and repeat.

Each line says what the change becomes:

| `jar-diff` line | Becomes |
|---|---|
| `ADDED_CLASS <class>` | A class in the mod's `src\`, in a package of your own ([writing.md](writing.md), "Java packages"). |
| `CHANGED_METHOD ... -> ENTRY` (code added at the start) | `@Hook(..., at = At.ENTRY)` |
| `CHANGED_METHOD ... -> BEFORE_RETURN` (code added before the final return) | `@Hook(..., at = At.BEFORE_RETURN)` |
| `CHANGED_METHOD ... -> WRAP_RETURN` (the returned value changed) | `@Hook(..., at = At.WRAP_RETURN)`, first parameter `@Return` |
| `CHANGED_METHOD ... -> REPLACE_CALL` (one call swapped for another) | `@Hook(..., at = At.REPLACE_CALL, callOwner = ..., call = ..., occurrence = ...)` |
| `ADDED_OVERRIDE ...` (a new override of an inherited method) | `@Hook(..., at = At.OVERRIDE_AFTER_SUPER / OVERRIDE_BEFORE_SUPER / OVERRIDE_INSTEAD)` |
| `CHANGED_METHOD ... -> INSERT_MID` (code added in the middle) | `REPLACE_CALL` on a call next to the insertion point (the line lists the calls before and after it): the hook does the added work, then makes the original call (or the other way round). |
| `CHANGED_METHOD ... -> REWRITE` (changed in place, or in several places) | Read both versions (`javap -c -p` on each jar) and express each edit as one of the hooks above. If no hook can express it, it's a candidate hook type (step 7). |
| `OPEN ...` (access widened) | Nothing: use the member from the helper; `finish` lists it under `opens` and BML opens it. `OPEN` names the class that declares the member; your code may reach it through a subclass. |
| `ADDED_METHOD ...` | A static method in the helper, taking the object as a parameter. |
| `ADDED_FIELD ...` | `@AddField` on the owner ([writing.md](writing.md), "Adding a field to a Bitwig class"), or a weak-key identity map in the helper when the value can't reach its owner and speed doesn't matter. A plain `WeakHashMap` compares keys with `equals`, and a value that references its key is never collected. |
| `ADDED_RESOURCE ...` | A resource of the mod (`"kind": "file"`), read by the mod's code. |
| `CHANGED_RESOURCE`, `REMOVED_*`, `CHANGED_SUPERCLASS`, `CHANGED_INTERFACES`, `FLAGS` | No BML equivalent: a mod can't replace or remove Bitwig's files or change a class's type. Report it (step 7). |

For every hook it can name (`ENTRY`, `BEFORE_RETURN`, `WRAP_RETURN`, `REPLACE_CALL`, `OVERRIDE_*`), `jar-diff` prints three things, each on its own line(s): the `@Hook` annotation (with a suggested `id` and the method's full descriptor), the hook method's signature with typed parameters, and the imports they need. Example:

```text
CHANGED_METHOD xyz(I)V  -> ENTRY
  @Hook(id = "abc-xyz-entry", target = Abc.class, method = "xyz(I)V", at = At.ENTRY)
  public static void abc_xyz(@This Abc self, @Arg(0) int arg0)
  import bml.api.Arg;
  ...
  import bitwig.obf.Abc;
  inserted (3):
    LOAD REFERENCE 0
    LOAD INT 1
    INVOKESTATIC com/example/mymod/MyPatch.before(LAbc;I)V
```

(`Abc`, `xyz`, `MyPatch` stand for your jar's names.)

Copy the annotation, the signature and the imports into the helper class; rename the method and the `id` if you like (keep the `id` stable once released). The `inserted (n):` lines show what your edit did: for `ENTRY`/`BEFORE_RETURN`, the parameters are the values your inserted code loaded; in most whole-jar mods it then calls one of your added classes, and that call becomes the hook method's body. For `REPLACE_CALL`, the hook runs instead of the original call: make the original call in the hook yourself (unless your edit dropped it) and return its result.

### 2. Split into mods

Group the lines by feature: which added classes, hooks and resources belong to which feature. Make one BML mod per feature that users would want to turn on and off on its own. A diagnostics-only feature goes with the feature whose code it shares (the same added class); if it shares nothing, it's a mod of its own.

- Each mod gets its own Java package (`com.example.mymod.feature`). Two enabled mods may not contain a class with the same name, so code shared by two mods is copied into each under that mod's package. Mods don't depend on one another, except on frameworks.
- Moving a class into the mod's package changes its name. If other code reads its public fields or methods by name (scripts, other tools), say the new name in the mod's README.
- Choose new mod IDs (lowercase words joined by `-`), starting with a prefix of your own (your project's name) so they can't collide with other mods' IDs.
- Your added code may call Bitwig methods you also hook (e.g. a setter called from inside its own hook); that re-enters the hook, as it re-entered your patched method in the jar. Make sure the second pass does nothing ([writing.md](writing.md), "Hooks"). Names follow "Area: What it does" ([mods.md](mods.md)).

Where a feature adds actions, menu items, Settings controls or Inspector content, use the framework for that part and drop the hooks that built it.

### 3. Write each mod

1. Create the mod folder ([mods.md](mods.md), "The source folder"): `mod.json` with `built_against` set to the version and platform **your jar** is (step 4 moves the version to 6.1.4 later), `build.json` with `"kit": true` (and `classpath_mods` for any framework).
2. Copy the added classes' source into `src\`, in the mod's package.
3. In that source, replace every reflection lookup of a Bitwig name with plain Java against the compile jar ([converting.md](converting.md), step 3), and every Bitwig class reference with its `bitwig.obf` import (unnamed-package classes) or its own package.
4. Make one helper class for the hooks (all `@Hook` methods of a mod must be in one class). For each hook line, paste the printed annotation, signature and imports; the body is the code your edit ran, usually a call into your added class. Either keep that split (a hooks class calling your logic class) or put the hooks into the added class itself if its methods already have the hook's signature; both work.
5. Inside each hook, catch exceptions around your own code only. A `REPLACE_CALL` hook still makes the original call (outside the `try`) so Bitwig always gets its result:

```java
public static Def abc_build(Abc receiver, String arg0) {
    try { MyFeature.before(receiver); } catch (Throwable t) { MOD.log("failed", t); }
    return receiver.make(arg0); // the original call
}
```
6. Delete what only the jar needed: the installer, jar hash checks, backup code, and any version switch on Bitwig's jar.
7. Comment every Bitwig name ([writing.md](writing.md)).

### 4. Move the names to 6.1.4

Skip this if your jar is Bitwig 6.1.4 or 6.1.3, on any platform: map packs translate from both.

- **Another Bitwig version:** build the mod once against your version (compile jar from your version's `bitwig.jar`, step 5), then port the source with the source porter to 6.1.4 of the same platform ([converting.md](converting.md), step 2, with your platform's map pack; it understands mod kit source, including `@Hook` method names) and set `"bitwig_version": "6.1.4"`.

### 5. Build and check

[building.md](building.md): build (the manual steps or `build_mod.py`), the version check (`mod_uses.py`), and the offline check on 6.1.4. Hook targets on 6.0–6.1.3 are checked only by preparing on those versions: in a BML workspace with "Other Bitwig versions offline"; outside one, only with a Bitwig of that version installed.

The first build has no finished `mod.json` yet: write it from that build's `hooks.json` (building.md step 7), then build again. A `REPLACE_CALL` on a call to an inherited non-public method gives two targets for one method (the call's class for the match, the declaring class under `opens`); that is correct.

Then compare `hooks.json` with your `jar-diff` output: one hook per `CHANGED_METHOD` and `ADDED_OVERRIDE` line you ported, on the same class and method. `hooks.json` uses the manifest's names for the kinds:

| `jar-diff` / `@Hook(at = ...)` | `hooks.json` `transform` |
|---|---|
| `ENTRY` | `INSERT_AT_ENTRY` |
| `BEFORE_RETURN` | `INSERT_BEFORE_FINAL_RETURN` |
| `WRAP_RETURN` | `WRAP_OBJECT_RETURN` |
| `REPLACE_CALL` | `REPLACE_INVOKE` |
| `OVERRIDE_AFTER_SUPER` / `OVERRIDE_BEFORE_SUPER` / `OVERRIDE_INSTEAD` | `ADD_METHOD` (body `CALL_SUPER_THEN_STATIC` / `CALL_STATIC_THEN_SUPER` / `CALL_STATIC_ONLY`) |

### 6. Test in Bitwig

Install the mods (and their frameworks) in BML, launch Bitwig from BML, and check every feature the jar mod had. Compare with the jar mod's own behaviour where you can.

### 7. Report what doesn't fit

For each change you couldn't port, report:

1. The `jar-diff` line(s) and, for a method, the removed and inserted instructions it printed.
2. What the change does for the user.
3. Why no hook fits (e.g. "code inserted inside a loop, no call nearby", "the class now implements another interface").

These are the candidates for new hook types in BML.
