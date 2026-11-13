# Writing a mod with the mod kit

The mod kit lets a mod call Bitwig in plain Java and declare its hooks as annotations on its own methods. The build checks every Bitwig name against Bitwig's jar and writes the manifest's hooks for you. The kit's reference is `modkit/README.md`; this page is the part a mod author needs.

## The rule

Every Bitwig name a mod uses is written once, in its Java source, in Bitwig 6.1.4's names, with a short comment saying what it is. No Bitwig name appears in a string: no `Class.forName("dWb")`, no `getDeclaredMethod("HL")`, no names read from a properties file. Strings can't be checked by the build or translated by BML; Java references can.

## The compile jar

Java code in a package can't refer to a class in the unnamed package, and most of Bitwig's obfuscated classes are in the unnamed package. So mods compile against a **compile jar** made from Bitwig's jar:

```powershell
java -jar bml-modkit.jar compile-jar <bitwig.jar> <compile.jar>
```

In the compile jar, every class in the unnamed package is moved to the package `bitwig.obf` (`dWb` becomes `bitwig.obf.dWb`), every class and member is public, and method bodies are removed. After `javac`, the build renames `bitwig.obf.X` back to `X`, so the shipped classes refer to Bitwig's classes exactly as Bitwig's own code does. The compile jar is made from your own Bitwig install; never ship or share it.

Bitwig classes that are already in a package (`com.bitwig.flt.app.CHR`) keep their names: import them as they are.

## Calling Bitwig

```java
import bitwig.obf.dWb; // a vertical or horizontal group of widgets
import bitwig.obf.duY; // a text label

dWb column = dWb.UNj(2);        // a vertical group with room for two widgets
column.HL(duY.MIO("First row")); // add a label to it
```

- **Comment every Bitwig name**: each import, and each use of an obfuscated method or field whose meaning isn't obvious from the line, gets a short comment saying what it is. Keep comments in plain words; never repeat another version's name in a comment.
- **Non-public members:** the compile jar shows everything as public, so you can call a private method or read a private field. The build lists every non-public Bitwig class and member the code uses, writes them under `opens` in the manifest, and BML makes exactly those public when it prepares Bitwig. You do nothing. One exception: the build refuses a non-public instance method whose name and descriptor also appear in a superclass or subclass, because making it public would change which method Bitwig calls. Reach such a method by reflection, and say why in a comment.
- **Methods that differ only in return type** can't be called from Java source (about 240 of Bitwig's methods). Find another way, or use reflection with a comment.
- Bitwig objects that frameworks hand you as `Object` are cast to the Bitwig class you expect (`if (o instanceof Smw named) ...`).
- **Across platforms, avoid platform code.** A mod written on one platform runs on the others only if it doesn't hook or call that platform's own classes: the windowing packages (`com.bitwig.mac_windowing_system`, `com.bitwig.win32_windowing_system`, `com.bitwig.x11_windowing_system`), the file-notification classes (`com.bitwig.base.file_notification.*`) and any other package only one platform's jar has. They have no counterpart on the other platforms, and a manifest can't mark a hook as one platform's, so one such hook (or call) makes the whole mod unavailable there. Hook Bitwig's shared code instead, e.g. its own event dispatch rather than a platform window handler.

### Finding Bitwig names

What Bitwig does inside is not documented: a mod's author finds the classes and methods to use by reading Bitwig's jar. Read it in place, never unpacked: on Windows, unpacking `bitwig.jar` silently loses classes whose names differ only in case (`dWb` and `DWB` land in one file).

- **Signatures** (what you write against): the compile jar. `javap -p -cp <compile.jar> bitwig.obf.dWb` lists a class's fields and methods, private ones included.
- **Code** (what a method does): Bitwig's real jar; the compile jar has no method bodies. `javap -c -p -cp <bitwig.jar> dWb` prints every method's instructions; a class in a package is named with its package, `javap -c -p -cp <bitwig.jar> com.bitwig.flt.app.CHR`.
- **Entry points:** Bitwig's own unobfuscated strings. Its action IDs (the ones Commander and the shortcut settings use, e.g. `insert_silence`), names of saved fields, and the controller API's method names are all string constants in the class files that handle them. `docs/sdk/jarsearch.py` (in this folder) searches the jar without unpacking it, in about a second:

```powershell
python docs\sdk\jarsearch.py <bitwig.jar> strings insert_silence          # classes whose constants contain the text
python docs\sdk\jarsearch.py <bitwig.jar> strings insert_silence --exact  # ... a constant that is exactly the text
python docs\sdk\jarsearch.py <bitwig.jar> callers <class> <member>         # classes that call or read <class>.<member>
python docs\sdk\jarsearch.py <bitwig.jar> subclasses <class>               # every class extending or implementing it
```

Class names are written as in the jar: `dWb` for an obfuscated class, `com/bitwig/flt/app/CHR` for one in a package. Obfuscated names differ only by case (`VpW`, `vpw`): when filtering `javap` output in PowerShell, use `Select-String -CaseSensitive`. A short word matches many unrelated strings (`arm` is in `harm` and `format`): search for longer pieces of an ID (`_silence`) or use `--exact`. Bitwig's action IDs come in families (`insert_silence`, `insert_silence_...`), so a substring search on one also finds its relatives, often including a variant that already does what you want.

`callers` matches the class named in the calling instruction, which is often a subclass of the class that declares the member, or an interface it implements: run `subclasses` on the declaring class, then `callers` on each subclass and on each interface they implement (the `implements` list on `javap`'s first line). Bitwig's short names are reused: a name is unique only together with its class and descriptor (`TMF(Z)V` and `TMF()V` are unrelated), so give `callers` the descriptor as a third argument. It prints the calling class; to see which of its methods makes the call, search that class's `javap -c -p` output for the member.

**Widgets (buttons, clicks, modifier-clicks).** UI code has no action IDs. Entry points that work:

1. The widget's icon file name: `strings record_arm.svg --exact` (any `*.svg` under Bitwig's `resources\icons\`) finds the classes that build that button.
2. A Settings preference ID that changes the widget's behaviour (preference IDs are strings too).
3. In the builder (`javap -c -p`), the icon string is followed by the call that creates the button and the calls that bind it to its model.
4. To copy how a similar widget handles a modifier-click, find its builder the same way and look for what it registers on the button besides the model (an action per set of held modifier keys, for example), then `callers` on that registration method to see who else uses it.
5. To change a widget, hook its builder: `ENTRY` + `WRAP_RETURN` on a static factory that returns the widget (`ENTRY` notes an argument in a `ThreadLocal`, `WRAP_RETURN` takes the widget), or `REPLACE_CALL` on the call that creates it inside a larger build method (call Bitwig's builder yourself, change the widget, return it).

**Pitfall: actions you build yourself.** Bitwig's action class takes an action **ID**, not a label, and looks the name up in Bitwig's translation tables (`action.<id>.name`). An action made with your label as its ID shows the raw key (`SHIFT+CLICK action.My label.name` in the status bar) and logs `base-localization error: Localized text for key ... not found` every time it is shown. For an action that should be a Bitwig action (shortcut, Commander, menus), use the Actions and Menus framework, which names it correctly. For one you must build yourself (e.g. a click action on a widget), give it an ID of your own (`<mod_id>_...`) and make a small subclass of Bitwig's action class that overrides its protected name-lookup method to return your label: find the class with `javap` (its one-`String` constructor takes the ID; the protected method that returns a `String` and calls the translation lookup is the one to override). `exclusive-arm/src/.../ShiftArmAction.java` in the Mods repository is an example.

An action ID string usually sits in a class that only declares actions: the ID is stored in a static field there. Run `callers` on that field to find where the action is bound to the code it runs.

`javap -c` shows a lambda or method reference as an `invokedynamic` instruction with a number; `javap -v -p` lists the class's `BootstrapMethods`, where that number's entry names the method the lambda runs (usually a `lambda$...` method of the same class).

A way to work that finds most behaviour:

1. Find an entry point by string: the action that does the thing by hand, or the controller API method for it.
2. Follow the code with `javap -c -p` down to the method that changes the state, usually a public setter on the object that holds it.
3. List that setter's callers (`callers`) and check each user path you care about reaches it: a click, an action, a shortcut, a controller. Note the paths that change the state without it (loading a project, undo); a hook on the setter doesn't see those, which is usually what you want.
4. Look for code Bitwig already has for the job (an action that does half of it is common) and call that instead of rewriting it.
5. Hook the narrowest method that every path you care about goes through. State Bitwig keeps in a project is usually set through a chain: a public setter, an overridable step that subclasses use to refuse or adjust the change, a check that the value really changed, and a raw write that notifies listeners. Loading and undo often use the raw write directly. The overridable step is usually the right place for a hook: user actions pass through it, after the subclass's own checks.

What happens to undo when a hook changes more of the project than the user's own change (whether it joins the same undo step) is not something this guide can promise: check it in Bitwig.

## Java packages

Put a mod's classes in a package of its own, named after you or your project (`com.example.mymod`, `io.github.<you>.<mod>`). Don't use a package Bitwig's jar has: BML refuses a helper jar with a class that shadows one of Bitwig's, and two mods with the same class name collide.

## Hooks

A hook runs one of the mod's methods from inside a Bitwig method. It is a `public static` method in the mod's **helper class**, marked `@Hook`:

```java
import bitwig.obf.cNC; // the listener that runs the selected Commander result when Enter is pressed
import bml.api.At;
import bml.api.Hook;
import bml.api.This;

public final class MyModHooks {
    @Hook(target = cNC.class, method = "eventOccurred", at = At.ENTRY)
    public static void beforeEnter(@This cNC listener) {
        // runs before Bitwig's eventOccurred
    }
}
```

- `target`: the Bitwig class to patch: the class that **declares** the method (`javap` on it lists the method), not a subclass that inherits it. The hook is added to that class's method body, so it runs for objects of subclasses too, except where a subclass overrides the method without calling `super` (check overrides with `subclasses` and `javap`). A subclass override that calls `super` after its own checks is a good sign: hooking the superclass method then runs the hook only once those checks have passed. A hook's target, private or protected, never needs `opens`: BML patches it in place. Classes in a package are written as they are (`target = Abc.class` after `import com.bitwig.some.pkg.Abc;`). `final` methods can be hooked.
- `method`: the method's name. When the class has several methods with that name, add the JVM descriptor, e.g. `"vhT(LcNC;)V"`; the build lists the candidates.
- `at`: where the hook runs (table below).
- `id`: optional, the hook's ID in the manifest. Default: the Java method name in kebab case (`beforeEnter` → `before-enter`). Give it explicitly so renaming the Java method doesn't change it.
- **All `@Hook` methods must be in one class**, the helper class. The build names it in the manifest's `helper`.

| `at` | Runs | Hook parameters | Hook returns |
|---|---|---|---|
| `ENTRY` | before the method's first instruction | `@This` and/or `@Arg(n)` | `void` |
| `BEFORE_RETURN` | just before the method's final return | `@This` / `@Arg(n)`, objects only | `void` |
| `WRAP_RETURN` | on the value the method returns | `@Return` first; see the limitation below | the value to return instead (the method's type or `Object`) |
| `REPLACE_CALL` | instead of one call inside the method | the call's receiver (unless the call is static), then its arguments, unannotated | what the call returns |
| `OVERRIDE_AFTER_SUPER` | adds an override of an inherited method to `target`: Bitwig's method runs, then the hook | `@This` / `@Arg(n)` | `void` |
| `OVERRIDE_BEFORE_SUPER` | a new override: the hook, then Bitwig's method | `@This` / `@Arg(n)` | `void` |
| `OVERRIDE_INSTEAD` | a new override: only the hook | `@This` / `@Arg(n)` | exactly the overridden method's type |

- `@This` receives the object the method runs on; `@Arg(n)` the target method's parameter `n`, counting from 0. A parameter's type must accept the value: the same type, a supertype, or `Object`. At `ENTRY`, a primitive parameter (`boolean`, `int`, `double`, ...) is taken with the same primitive type (`@Arg(1) int index`); `BEFORE_RETURN` and `WRAP_RETURN` take objects only.
- `REPLACE_CALL` names the call with `callOwner` (the class in the call instruction), `call` (the called method's name, or name and descriptor) and `occurrence` (which matching call in the target method, counting from 1 in Bitwig's original method):

```java
@Hook(target = abc.class, method = "build", at = At.REPLACE_CALL,
      callOwner = def.class, call = "add", occurrence = 2)
public static ghi addOwnRow(def menu, String label) { ... }
```

Limitations:

- Each call can be replaced by one hook in a mod. Other mods' hooks don't change your `occurrence`. If another mod replaces the same call, preparation stops and names both hooks and the method, unless the two can be chained (`loader/MANIFEST-FORMAT.md`, `REPLACE_INVOKE`).
- `WRAP_RETURN`: take only `@Return`. `@This` and `@Arg` are read at the return, where Bitwig's code may no longer hold them, and the class then fails verification (`Bad local variable type`, reported by the offline check). When you need an argument, note it with an `ENTRY` hook on the same method and keep it in a field.
- Keep hooks short and catch exceptions: a hook runs on whatever thread calls the Bitwig method, and an exception thrown from a hook reaches Bitwig's code. Assume any thread: make state shared between a hook and other code (a framework's callbacks run on Bitwig's UI thread) `volatile` or otherwise thread-safe.
- A hook that calls Bitwig methods may come back through itself (a hook on a setter that calls the same setter on other objects). Make sure that second pass does nothing: return early on the case you cause, or guard with a `ThreadLocal<Boolean>`.
- A hook that needs a primitive argument must run at `ENTRY`.
- Several hooks may target one method (e.g. an `ENTRY` and a `WRAP_RETURN`); give each its own `id`.
- `REPLACE_CALL`: `callOwner` is the class named in the call instruction. When `javap -c` prints `Method cfM:(...)` without a class, the class is the one being read, even if a superclass declares the method. Calling a non-public inherited method from the hook is fine: the build lists it under `opens`.
- Mod classes may implement Bitwig interfaces or extend Bitwig classes; BML translates the overriding methods' names with the rest of the helper jar (BML's own Actions and Menus framework relies on this on 6.0–6.1.4). A `Runnable` or other JDK type is simpler where Bitwig accepts one.
- One `helper.jar` holds everything: the helper class with the hooks, and any framework providers (they are separate classes; the manifest's `helper` names only the hook class).

## Adding a field to a Bitwig class

`@AddField` gives a Bitwig class a new instance field. Put it on the `static final VarHandle` that reaches the field:

```java
import bitwig.obf.btY; // the object the state belongs to
import bml.api.AddField;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

final class Fields { // its own class, so loading the hook class doesn't load btY
    @AddField(target = btY.class, name = "my_mod$context", type = Object.class)
    static final VarHandle CONTEXT = handle("my_mod$context", Object.class);

    @AddField(target = btY.class, name = "my_mod$marked", type = boolean.class)
    static final VarHandle MARKED = handle("my_mod$marked", boolean.class);

    private static VarHandle handle(String name, Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(btY.class, MethodHandles.lookup()).findVarHandle(btY.class, name, type);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
```

```java
Object context = Fields.CONTEXT.getVolatile(renderer);
Fields.CONTEXT.setVolatile(renderer, context);
boolean marked = (boolean) Fields.MARKED.get(renderer);
Fields.MARKED.set(renderer, true);
```

- `name`: the mod's ID with every character but letters and digits as `_`, then `$`, then your name: mod `my-mod` writes `my_mod$context`. The build refuses anything else. This is what keeps two mods apart. The name stays the same on every Bitwig build; BML translates `btY.class` and leaves the string alone.
- `type`: a primitive, a JDK class, a Bitwig class, or an array of those. For a class of your own use `Object` and cast. A field starts at zero or null.
- `id`: optional, its ID in the manifest. Default: the Java field's name in kebab case.
- The handle's access mode decides the memory semantics of each access: `getVolatile`/`setVolatile`, or `get`/`set` for a plain one. Use the same ones an equivalent Bitwig field would need.
- Reach the field only through a `VarHandle`. It isn't in the jar you compile against, and a `getfield` on it doesn't build.
- `Object.clone()` copies a field like any other, so a clone starts with the original's value.

BML refuses to load a mod whose field can't be added: on an interface, a record, an enum or a `Serializable` class (through any supertype); on a class where the class, a supertype or a subclass already has a field of that name; or when two ticked mods name the same field. Load stops and says which mod and which field. The build reports what it can against the jar it's built with.

**A field or a map in the helper?** A map keyed by the object (with weak keys, in the helper) is often enough, and it changes nothing in Bitwig's classes. Use a field when:

- the value can reach the object it belongs to. A weak-key map holds its values strongly, and Java has no ephemerons, so such a value keeps its own key alive and the entry is never collected. A field dies with its object.
- it's read on a paint path or similar, where a map's hash, and any lock around it, costs too much. Once its call site is linked, an access through a `static final VarHandle` is one field access, with no hash or lock.
- the class overrides `equals` or `hashCode`, and you don't have an identity map to hand. `WeakHashMap` compares keys with `equals`; Guava's `new MapMaker().weakKeys().makeMap()` (in Bitwig's `libs.jar`) compares by identity.

**First use isn't free.** The holder class initializes on first use, and each call site links the first time it runs. Both allocate and load classes, so either can fail, for example with `OutOfMemoryError`, and initializing the holder doesn't link the call sites. That matters when an access follows Bitwig's own changes and must not fail partway through. Prepare first: give the holder one `get` and one `set` method, so each access has one call site, and run them earlier, from an `ENTRY` hook on the same method:

```java
final class Flag {
    @AddField(target = bpZ.class, name = "my_mod$seen", type = boolean.class)
    static final VarHandle SEEN = handle(); // built as in the example above

    static boolean get(Object owner) { return (boolean) SEEN.get(owner); }
    static void set(Object owner, boolean value) { SEEN.set(owner, value); }
}

@Hook(target = bpZ.class, method = "vhT(Led;DD)V", at = At.ENTRY, id = "prepare")
public static void prepare(@This bpZ owner) {
    Flag.set(owner, Flag.get(owner)); // writes the same value back
}

@Hook(target = bpZ.class, method = "vhT(Led;DD)V", at = At.BEFORE_RETURN, id = "mark")
public static void mark(@This bpZ owner) {
    Flag.set(owner, true);
}
```

Any failure then comes before Bitwig's code runs. It reaches Bitwig through `ENTRY`, so catch it if the feature can go without. Call a static method to prepare, not `Class.forName`, which goes through BML's reflection bridge and does more work. A failure in the holder's static initializer is permanent for that session. A hook still adds stack frames, so unlike a stock `putfield` it can hit a `StackOverflowError`.

### Calling the original method for some objects

`OVERRIDE_INSTEAD` adds an override whose body is only your hook. When some objects should still get what they inherit, look up the superclass's method with `findSpecial`:

```java
final class Original { // its own class: see below
    static final MethodHandle CLICKED = find();

    private static MethodHandle find() {
        try {
            return MethodHandles.privateLookupIn(bhF.class, MethodHandles.lookup())
                    .findSpecial(bhG.class, "iJ_", MethodType.methodType(void.class), bhF.class); // bhG: the superclass that has it
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

@Hook(target = bhF.class, method = "iJ_", at = At.OVERRIDE_INSTEAD)
public static void clicked(@This bhF self) {
    try {
        if ((boolean) Fields.MARKED.get(self)) { /* yours */ return; }
        Original.CLICKED.invokeExact(self);
    } catch (Throwable t) {
        MOD.log("could not call the original", t);
    }
}
```

The call runs the superclass's method directly, without dispatching back to the override. A subclass of `bhF` that overrides the method and calls `super` still reaches the hook.

The method's name is a string here, and that changes what BML can check:

- On another Bitwig build, BML translates the string in the helper, from the class passed to `findSpecial` and its supertypes.
- `uses.tsv` and BML's availability check see classes and member references, not this string. The hook's own `method` is checked; a `findSpecial` on any other method isn't.
- A name that doesn't exist on the running build isn't refused at Load. `findSpecial` throws when `Original` initializes, the first time the hook needs it, as an `ExceptionInInitializerError`, and after that as `NoClassDefFoundError`. Keep the lookup in a class of its own, catch `Throwable` in the hook, and test the mod on each Bitwig version you claim. A missing name is left out of the offline check for the same reason: `PREPARE_OK` says nothing about it.

## bml.api.Bml

`bml.api.Bml` (in `bml-api.jar`) tells a running mod what BML knows about it. Compile against `bml-api.jar` but don't ship it: BML provides the class at runtime.

```java
private static final Bml.Mod MOD = Bml.mod("my-mod"); // the mod's mod.id

MOD.name();                    // its display name (mod.name)
MOD.resource("settings");      // the Path of a resource declared in the manifest
MOD.bitwigResource("icon");    // for a kind:bitwig resource, its declared path inside Bitwig's resources; else null
MOD.settings();                // the mod's settings in this BML install, below
MOD.log("something happened"); // writes "[my-mod] <time> <message>" to Bitwig's log and the session's mod log
MOD.log("it failed", e);       // the same, with the stack trace
```

`Bml.mod(...)` and `log` never throw, so they are safe in static fields and error handlers. `name()` throws for a mod that isn't enabled; `resource(...)` for a resource the mod doesn't declare.

Use `Bml` for logging and for finding the mod's files. Never read `BML_MOD_CONFIG` or BML's bindings file yourself.

A mod's settings are named values, kept in the mod's data folder ([mods.md](mods.md)):

```java
private static final Settings STORE = MOD.settings();   // bml.api.Settings; safe in a static field

boolean on = STORE.getBoolean("enabled", true);         // also getInt, get (strings); the default if there is no value
STORE.putBoolean("enabled", false);                     // also putInt, put; remove, clear
```

Reading is a lookup in memory, safe on any thread. A change takes effect at once and reaches the mod's file in BML's settings folder a moment later on BML's own thread, and always before Bitwig quits, so call these wherever the value changes. `flush()` writes at once on the calling thread, for the rare value that must be on disk before the next step. They need loader 0.35.0-dev.10 or later. Don't use Java Preferences or write into the mod's folder.

## A complete example

"Device: Show Device Name" adds one item to the device right-click menu, through the Actions and Menus framework ([frameworks.md](frameworks.md)). Choosing it shows the device's name in Bitwig's notification bar. It has no hooks: the framework does the patching.

```text
example-device-name/
  mod.json
  build.json
  package/actions.properties
  src/com/example/devicename/DeviceNameActions.java
```

`mod.json`:

```json
{
  "manifest_version": 2,
  "mod": {
    "id": "example-device-name",
    "name": "Device: Show Device Name",
    "version": "0.1.0",
    "author": "Your Name",
    "description": "Adds Show Device Name to the device right-click menu; it shows the device's name in Bitwig's notification bar."
  },
  "built_against": {
    "bitwig_version": "6.1.4",
    "platform": "windows",
    "architecture": "x64"
  },
  "resources": {
    "name_icon": { "kind": "bitwig", "path": "icons/action/rename.svg" }
  },
  "registrations": [
    {
      "id": "actions",
      "consumer": "action-menu-composition-framework",
      "descriptor": "actions.properties",
      "jar": "helper.jar"
    }
  ]
}
```

`build.json`:

```json
{
  "release": 21,
  "kit": true,
  "classpath_mods": ["action-menu-composition-framework@0.5.1-dev.1"]
}
```

`package/actions.properties`:

```properties
api.version=1
provider.class=com.example.devicename.DeviceNameActions
```

`src/com/example/devicename/DeviceNameActions.java`:

```java
package com.example.devicename;

import bitwig.obf.Smw; // something with a display name; devices are
import bml.actions.ActionProvider;
import bml.actions.Actions;
import bml.actions.Menu;
import bml.api.Bml;
import com.bitwig.flt.app.CHR; // the application window controller (shows notifications)

/** Device: Show Device Name. One action in the device right-click menu. */
public final class DeviceNameActions implements ActionProvider {
    private static final Bml.Mod MOD = Bml.mod("example-device-name");

    @Override
    public void declare(Actions actions) {
        actions.action("example_device_name_show", "Show Device Name")
               .in(Menu.DEVICE)
               .icon("name_icon")
               .onRun(context -> show(context.device()));
    }

    private static void show(Object device) {
        String name = device instanceof Smw named ? named.aG_() : null; // its display name
        if (name == null || name.isBlank()) {
            MOD.log("no device name");
            return;
        }
        CHR.Grk().hUR("Device: " + name); // Bitwig's on-screen notification
    }
}
```

Build and check it as [building.md](building.md) describes. Its finished `mod.json` gets `"targets": {}` and `"hooks": []`, and the installed folder holds `mod.json`, `helper.jar` and `actions.properties`. Install it together with the Actions and Menus framework.

A mod with both hooks and frameworks puts the `@Hook` methods in one helper class and the providers in their own classes, all in the same `helper.jar`; its `build.json` lists every framework it compiles against, e.g. `"classpath_mods": ["preferences-composition-framework@0.3.0-dev.3", "action-menu-composition-framework@0.5.1-dev.1"]`, and its `mod.json` has one registration per framework.

In the Mods repository, the source of "Commander: Enter Selects First Result" (`commander-first-result/src/`, also inside its release ZIP) is a complete example with one `ENTRY` and one `WRAP_RETURN` hook. (Its `WRAP_RETURN` hook also takes `@Arg(0)`; that passes the offline check for that method, but follow the limitation above in new code.)
