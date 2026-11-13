# BML Mod Kit

The mod kit lets a mod declare its hooks as Java annotations and call Bitwig in plain Java. It generates the mod's `mod.json` from the compiled classes. The manifest stays the contract: the loader reads only `mod.json`, and a hand-written manifest works just as well. The kit is optional.

It has two parts:

| Jar | What it is |
|---|---|
| `bml-api.jar` | The annotations (`bml.api.Hook`, `At`, `This`, `Arg`, `Return`) and the runtime classes `bml.api.Bml` and `bml.api.Settings`. Mods compile against it and don't ship it: the annotations are removed from the shipped classes, and the loader (0.34.0-dev.5 or later) provides them at runtime. Its source is the loader's, `loader/src/loader/api/src`. |
| `bml-modkit.jar` | The build tool: `compile-jar` and `finish`, and `jar-diff` for porting a modified Bitwig jar. Needs JDK 25. |

## Building a mod with the kit

1. **Compile jar.** `java -jar bml-modkit.jar compile-jar <bitwig.jar> <out.jar>` writes the jar mods compile against. It is made from your own Bitwig install and is never shipped: method bodies are removed, every class and member is public, and Bitwig's classes that have no package are moved to the package `bitwig.obf`, because Java code in a named package can't refer to classes without one.
2. **Compile** the mod with `javac`, with the compile jar, Bitwig's `libs` jar and `bml-api.jar` on the classpath.
3. **Finish.** `java -jar bml-modkit.jar finish <classes dir> <hooks.json> <bitwig.jar> [<libs.jar> ...]` checks every `@Hook` against the real Bitwig jar, writes the hooks to `hooks.json`, and rewrites the classes in place: `bitwig.obf.X` becomes `X` again, and the kit's annotations are removed. Code and stack maps are left exactly as javac wrote them; the annotations' names stay behind as unused strings in the class's constant pool.
4. **Manifest.** Add the hooks to your `mod.json`. In the BML repository, `tools/build_mod.py` does steps 1–4 for a mod whose `build.json` has `"kit": true` (see `mods/README.md`).

## Comparing a modified Bitwig jar

`java -jar bml-modkit.jar jar-diff <official bitwig.jar> <modified bitwig.jar>` lists every difference between a Bitwig jar someone has modified and the official jar of the same Bitwig version and platform, each sorted into what it becomes in a BML mod. It reads both jars and writes nothing.

| Line | Meaning |
|---|---|
| `ADDED_CLASS <class>` | A class of the mod's helper jar (in a package of its own). |
| `ADDED_RESOURCE` / `CHANGED_RESOURCE` / `REMOVED_*` | A resource of the mod's own, or no BML equivalent. |
| `CLASS <class>` | A changed class; the lines under it are its changes. |
| `OPEN <member>` | Access widened: nothing to port; `finish` opens what the helper uses. |
| `CHANGED_METHOD <method> -> ENTRY / BEFORE_RETURN / WRAP_RETURN / REPLACE_CALL` | The `@Hook` that replaces the edit: the annotation (suggested `id`, method with descriptor), the hook method's typed signature, and its imports, one per line. Parameters are the values the inserted code loaded where that can be read, else all of the method's. |
| `CHANGED_METHOD <method> -> INSERT_MID / REWRITE` | No single hook fits; the line says what to do and shows the removed and inserted instructions. |
| `ADDED_OVERRIDE <method>` | A new override of an inherited method: an `OVERRIDE_*` hook. |
| `ADDED_METHOD` | Move into the helper (a static method). |
| `ADDED_FIELD` | `@AddField`, or a weak-key identity map in the helper when the value can't reach its owner (`docs/sdk/writing.md`). |
| `CHANGED_SUPERCLASS` / `CHANGED_INTERFACES` / `FLAGS` | No BML equivalent: candidates for a new hook type. |

Code is compared instruction by instruction, ignoring constant-pool order, line numbers and branch offsets that only moved, so one edit in a method shows as that edit. A method changed in several places shows as `REWRITE`. The last line counts each kind (`JAR_DIFF_DONE ...`); `JAR_DIFF_WARNING` means more than 1,000 classes differ, so the two jars are probably different versions or platforms.

## Calling Bitwig

Write plain Java against the compile jar:

```java
import bitwig.obf.cNF; // the Commander panel
import bitwig.obf.cNI; // the Commander's invoke listener

cNF commander = listener.vhT; // the panel this listener belongs to
commander.Mxk();              // select the next result
```

The shipped class refers to `cNI.vhT` exactly as a class compiled inside Bitwig would. The loader translates these names to the installed Bitwig version with the map pack.

`finish` lists every non-public Bitwig class or member the code uses (`access` in `hooks.json`); the generated manifest names them under `opens`, and the loader (0.34.0-dev.4 or later) makes them public when it prepares Bitwig. It refuses a non-public instance method whose name and descriptor also appear in a superclass or subclass, because making it public would change which method Bitwig calls; reach such a method by reflection.

Bitwig names its obfuscated classes and members with short meaningless names, so give each one a short comment saying what it is.

## Fields

`@AddField` on a `static final VarHandle` adds an instance field to a Bitwig class:

```java
@AddField(target = cNI.class, name = "my_mod$count", type = int.class)
static final VarHandle COUNT = MethodHandles.privateLookupIn(cNI.class, MethodHandles.lookup()).findVarHandle(cNI.class, "my_mod$count", int.class);
```

`finish` checks it against the Bitwig jar and writes it to `fields` in `hooks.json`; the generated manifest has it under `fields`, and the annotation is removed from the shipped class. The name starts with the mod's ID, every character but letters and digits as `_`, then `$`. How to use it and what BML refuses: `docs/sdk/writing.md`, "Adding a field to a Bitwig class". The loader (0.35.0-dev.14 or later) adds the field when it prepares Bitwig.

## Name, resources and logging

`bml.api.Bml` tells a running mod what BML knows about it:

```java
private static final Bml.Mod MOD = Bml.mod("my-mod"); // the mod's mod.id

MOD.name();                    // its display name; Bml.mod("other-mod").name() for another enabled mod
MOD.resource("config");        // the Path of a resource declared in the manifest's resources
MOD.settings();                // the mod's settings in this BML install (bml.api.Settings)
MOD.log("something happened"); // [my-mod] <time> <message> to standard error and the session's mod log
MOD.log("it failed", e);       // the same, with the stack trace
```

`Bml.mod(...)` and `log` never throw, so they are safe in static fields and error handlers. `name()` throws for a mod that isn't enabled, `resource(...)` for a resource the mod doesn't declare.

`MOD.settings()` (loader 0.35.0-dev.10 or later) reads and writes named values: `getBoolean`, `getInt`, `get` with a default, `putBoolean`, `putInt`, `put`, `remove`, `clear`. Reads are in memory, on any thread; changes reach `mods/<mod-id>/settings.properties` in BML's settings folder a moment later and before Bitwig quits, and `flush()` writes at once. A mod keeps what it keeps here or in a `preserve` resource, never in Java Preferences or in its own folder: `docs/sdk/mods.md`, "What a mod keeps".

## Hooks

A hook is a `public static` method in the mod's helper class, marked `@Hook`:

```java
@Hook(target = cNI.class, method = "eventOccurred", at = At.ENTRY)
public static void beforeInvoke(@This cNI listener) { ... }
```

- `target`: the Bitwig class to patch.
- `method`: the method's name. When the class has several methods with that name, add the JVM descriptor, e.g. `"vhT(LcNC;)V"`; the build lists the candidates.
- `id`: the hook's ID in the manifest. Default: the method name in kebab case (`beforeInvoke` → `before-invoke`).

| `at` | Runs | Hook parameters | Hook returns |
|---|---|---|---|
| `ENTRY` | before the method's first instruction | `@This` and/or `@Arg(n)` | `void` |
| `BEFORE_RETURN` | just before the method's final return | `@This` / `@Arg(n)`, objects only | `void` |
| `WRAP_RETURN` | on the value the method returns | `@Return` first, then `@This` / `@Arg(n)`, objects only | the value to return instead (the method's type or `Object`) |
| `REPLACE_CALL` | instead of one call inside the method | the call's receiver (unless static), then its arguments; no annotations | what the call returns |
| `OVERRIDE_AFTER_SUPER` | a new override of an inherited method: Bitwig's method, then the hook | `@This` / `@Arg(n)` | `void` |
| `OVERRIDE_BEFORE_SUPER` | a new override: the hook, then Bitwig's method | `@This` / `@Arg(n)` | `void` |
| `OVERRIDE_INSTEAD` | a new override: only the hook | `@This` / `@Arg(n)` | exactly the overridden method's type |

`@Arg(n)` counts the target method's parameters from 0. A hook parameter's type must accept the value: the same type, a supertype, or `Object`.

`REPLACE_CALL` names the call with `callOwner` (the class in the call instruction), `call` (name, or name and descriptor) and `occurrence` (which matching call, counting from 1 in the method):

```java
@Hook(target = abc.class, method = "build", at = At.REPLACE_CALL,
      callOwner = def.class, call = "add", occurrence = 2)
public static ghi addOwnRow(def menu, String label) { ... }
```

## Limitations

- **One helper class:** all `@Hook` methods must be in the class named as the manifest's helper.
- **`REPLACE_CALL`:** each call can be replaced by one hook. Several calls of the same method in one target method each take their own hook, with its `occurrence` counted in the original method; the kit writes the manifest's occurrences as the loader counts them (after the mod's own earlier hooks' replacements). Other mods' hooks don't change the numbering.
- **`WRAP_RETURN` with `@This` or `@Arg`:** the loader reads them at the return, where Bitwig's stack map frames may no longer hold them (javac drops locals a method no longer uses), and the class then fails verification (`Bad local variable type`; offline prepare catches it). Take only `@Return` there, and note what you need with an `ENTRY` hook on the same method.
- **Methods that differ only in return type** (240 of Bitwig 6.1.3's 289,266 methods) can't be called from Java source, which can't tell them apart.
