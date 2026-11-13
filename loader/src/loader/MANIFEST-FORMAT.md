# BML Manifest v2

Manifest v2 is the only mod manifest format accepted by this BML line. Legacy/v1 manifests are intentionally unsupported; the legacy Preview 9 line remains available while existing production mods are finished and converted.

## Core rule

A mod declares mod-local target IDs plus the concrete Bitwig JVM targets it was built against. BML translates those source physical identities to the active supported Bitwig build when compatibility data is available.

A target ID is the mod's own name for a target, chosen for readability; BML treats it as an ordinary mod-local ID with no meaning across mods, and the physical `built_against` declaration remains the runtime contract.

A mod may also own package-local resources/data. Resources remain outside Bitwig's installed files and are exposed to helper code through BML's generated mod bindings.

## Stable IDs

`mod.id`, hook IDs, target IDs, resource IDs, and trace IDs must match:

```text
^[A-Za-z][A-Za-z0-9_.-]*$
```

These IDs are machine-facing and should remain stable when display names change.

## Top-level sections

Required:

- `manifest_version`: must be `2`.
- `mod`: `id`, `name`, `version`, `author`, `description`.
- `built_against`: `bitwig_version`, `platform`, `architecture`.
- `targets`: local Bitwig target declarations.
- `hooks`: transform declarations. May be empty only when `trace_targets` is non-empty.

Conditionally required:

- `helper`: required when `hooks` is non-empty.

Optional:

- `resources`: mod-owned files/directories.
- `bml_tools`: tools in BML's window that edit one of the mod's resources. `"device-folders": { "resource": "<resource ID>", "key": "<top-level key>" }` gives BML's Device Folders tool a list of folders to keep under that key of the JSON resource; everything else in the file is left as it is. Mark the resource `"persistence": "preserve"`, so the list survives updates and a change to it needs no new preparation. While no installed mod declares it, the tool is greyed out in BML's sidebar. While the mod is ticked, each listed folder that is missing, not responding within 2 seconds or not readable is an error on BML's Errors page, and Load refuses until the folder is back or removed from the list.
- `trace_targets`: diagnostic targets.
- `opens`: non-public Bitwig classes and members the helper uses directly.
- `fields`: instance fields BML adds to Bitwig classes.
- `requires`: other mods this mod needs, each `{ "id": "<mod-id>", "min_version": "<semver>" }` (`min_version` optional). Together with every registration's `consumer` these are the mod's prerequisites: one not installed, older than `min_version`, with blocking issues of its own or turned off while the mod is turned on is an error of the mod, and preparation refuses until it is fixed; one not available on this Bitwig version makes the mod not available either (an error too while the mod is turned on).

A package with zero hooks and zero trace targets is invalid.

## Source build

Example:

```json
"built_against": {
  "bitwig_version": "6.1.3",
  "platform": "windows",
  "architecture": "x64"
}
```

Every physical Bitwig target declared by the mod is interpreted in this source-build context.

## Physical target forms

Use JVM internal class names (`com/example/Foo`) for packaged classes. BML also accepts dotted class names and normalizes them internally.

```json
"targets": {
  "panel_class": {
    "kind": "class",
    "class": "abc"
  },

  "pointer_dispatch": {
    "kind": "method",
    "owner": "abc",
    "name": "xyz",
    "descriptor": "(Ljava/lang/Object;)V"
  },

  "selection_field": {
    "kind": "field",
    "owner": "abc",
    "name": "f1",
    "descriptor": "Ldef;"
  }
}
```

A physical Bitwig dependency should normally be declared once and referenced elsewhere by its local target ID.

## Target resolution

For every declared target BML records one of these successful states:

- `EXACT`: the active Bitwig build equals `built_against`; the source physical target is used directly.
- `MAPPED`: BML found the equivalent physical target for the active supported build.

If a cross-build equivalent is unknown, preparation fails closed before Bitwig launches.

Same-build use does not require the target to exist in BML compatibility data. This lets a third-party author target the exact Bitwig build they developed against without depending on BML's private reverse-engineering catalogue.

Debug preparation writes:

```text
loader/mod-runtime/target-resolution.tsv
```

## Unified mod bindings

BML writes targets and resources to:

```text
loader/mod-runtime/mod-bindings.properties
```

and exposes its path to helper code through:

```text
BML_MOD_CONFIG
```

Bindings are namespaced by mod ID and local ID.

Every enabled mod also gets its display name from `mod.name`, so frameworks can show or order contributions by name without repeating it in code:

```text
mod.example-mod.name=Example Mod
```

Target example:

```text
mod.example-mod.target.selection_field.kind=field
mod.example-mod.target.selection_field.owner=abc
mod.example-mod.target.selection_field.name=f1
mod.example-mod.target.selection_field.descriptor=Ldef;
mod.example-mod.target.selection_field.status=MAPPED
```

Resource example:

```text
mod.example-mod.resource.menu_icon.kind=file
mod.example-mod.resource.menu_icon.path=C:/.../mods/example-mod/resources/icons/action.svg
mod.example-mod.resource.menu_icon.uri=file:///C:/.../mods/example-mod/resources/icons/action.svg
mod.example-mod.resource.menu_icon.persistence=replaceable
mod.example-mod.resource.menu_icon.media_type=image/svg+xml
```

The Preview 1 `BML_TARGET_CONFIG` / `target-bindings.properties` contract is superseded before runtime acceptance; converted mods should use `BML_MOD_CONFIG`.

## Mod-owned resources

A Manifest v2 resource is a **mod-owned package-local file or directory whose validated runtime location BML exposes through `BML_MOD_CONFIG`**.

Resources may contain:

- immutable/distribution assets such as icons, images, SVGs, themes and templates; or
- mod-managed mutable data such as portable configuration and persistent state.

BML validates ownership/location and lifecycle metadata but does not interpret the resource's feature semantics.

Example:

```json
"resources": {
  "menu_icon": {
    "kind": "file",
    "path": "resources/icons/action.svg",
    "media_type": "image/svg+xml"
  },

  "alias_config_directory": {
    "kind": "directory",
    "path": "resources/config",
    "persistence": "preserve"
  }
}
```

Supported kinds:

```text
file        a file of the mod's own
directory   a folder of the mod's own
bitwig      one of Bitwig's own files, referenced by its path in the installed Bitwig's resources folder
```

`kind` describes where the resource is and its filesystem shape only. It does not mean icon/theme/config/state/etc.

A `bitwig` resource lets a mod use one of Bitwig's files (an icon, for example) without copying it into the mod: Bitwig's files are Bitwig's, and mods never ship them. Its `path` is relative to the `resources` folder of the Bitwig installation (`icons/action/insert_silence.svg` is `<Bitwig>/resources/icons/action/insert_silence.svg`); it must exist as a file in the installed Bitwig when the mod is prepared, or preparation fails, like a missing file of the mod's own. It has no `persistence`. Loader 0.34.0-dev.7 or later.

```json
"resources": {
  "paste_icon": { "kind": "bitwig", "path": "icons/action/insert_silence.svg" },
  "own_icon":   { "kind": "file",   "path": "resources/icons/my-action.svg", "media_type": "image/svg+xml" }
}
```

Optional lifecycle property:

```text
persistence: replaceable | preserve | created
```

If omitted, BML uses:

```text
replaceable
```

Semantics:

- `replaceable`: distribution/package material that future install/update/cleanup operations may replace or regenerate as part of the mod package.
- `preserve`: the resource holds user or mod-created state. It lives in the mod's data folder, `mods/<mod-id>/<last part of the declared path>` in BML's per-user settings folder (`docs/sdk/mods.md`), not in the mod's folder. The declared path says what the mod ships to start it from: each time the mod is prepared, BML copies across each file it hasn't copied before and never overwrites one that is there. `seeded-files.txt` in the data folder records them, so a shipped file in a `directory` resource that the user deleted stays deleted. A `file` resource that is deleted comes back from what the mod ships. The contents are not part of what decides whether a mod needs preparing again. Two `preserve` resources of a mod can't end in the same name, and one can't be called `settings.properties` (the mod's settings, `Bml.mod(id).settings()`) or `seeded-files.txt`.
- `created` (loader 0.36.0-dev.11 or later): a file or folder the mod writes itself in its data folder, with nothing shipped to start it from. Its `path` is the plain name it has there (`"config.json"`, no folders). A `file` may not exist until the mod, or a BML tool such as Device Folders, writes it, so the mod treats a missing file as empty; a `directory` is made, empty, when the mod is prepared. BML never copies, replaces or deletes it. It shares the data folder's names with `preserve` resources: no two can end in the same name, and none can be called `settings.properties` or `seeded-files.txt`. `bitwig` resources and components have no `created`.

`persistence` describes BML lifecycle semantics, not whether the filesystem object is technically writable.

`media_type` remains optional metadata.

Resource paths:

- must be package-relative (for `bitwig`: relative to Bitwig's resources folder);
- must exist in the mod's folder when the mod is prepared (a `preserve` resource too: it is what the data folder starts from), except a `created` one, which is never in the mod's folder;
- must remain below the mod directory (for `bitwig`: below Bitwig's resources folder) after normalization;
- must match the declared file/directory kind;
- must not traverse filesystem reparse points/symlinks.

For a configuration file that doesn't exist until the mod or a tool writes it, declare it `persistence: created`; ship a `preserve` file only when there is a real starting content.

BML writes:

```text
loader/mod-runtime/resource-resolution.tsv
```

and exports, for each mod, `mod.<mod-id>.data.path` (its data folder), and for each resource:

```text
mod.<mod-id>.resource.<resource-id>.kind
mod.<mod-id>.resource.<resource-id>.path
mod.<mod-id>.resource.<resource-id>.uri
mod.<mod-id>.resource.<resource-id>.persistence
```

plus `media_type` when declared, and for a `bitwig` resource `bitwig_path` (its declared path inside Bitwig's resources folder; `path` is the file in the installed Bitwig).

Mods read these through `bml.api.Bml`: `Bml.mod(id).resource(resourceId)` (the path) and `bitwigResource(resourceId)` (the declared Bitwig path, or `null` for the mod's own files). For a `preserve` or `created` resource, `path` is the place in the data folder. `Bml.componentsFolder()` is the components folder (`loader.components.path`, `MANIFEST-V3.md`).

Resources do **not** mean "replace this Bitwig resource". Any future Bitwig-resource replacement capability must be explicit and separate.

## Hooks

Supported transforms:

- `INSERT_BEFORE_FINAL_RETURN`
- `INSERT_AT_ENTRY`
- `WRAP_OBJECT_RETURN`
- `REPLACE_INVOKE`
- `ADD_METHOD`

Normal hook `target` values reference a local `kind: method` target.

### Composable `INSERT_AT_ENTRY`

Multiple active `INSERT_AT_ENTRY` hooks may target the same physical Bitwig method.

If no ordering constraints are declared, BML gives them a deterministic execution order:

```text
mod.id lexical order
then hook.id lexical order
```

This is deliberately independent of mod installation/filesystem enumeration order.

A hook may declare optional relative ordering:

```json
"ordering": {
  "after": [
    { "mod": "other-mod", "hook": "capture-context" }
  ],
  "before": [
    { "mod": "third-mod", "hook": "observe-state" }
  ]
}
```

Rules:

- `ordering` is currently valid only for `INSERT_AT_ENTRY`.
- A referenced hook that is not active is ignored.
- If a referenced hook is active, it must also be `INSERT_AT_ENTRY` on the same resolved physical method.
- Cyclic constraints fail closed before launch.
- Unconstrained hooks are tie-broken deterministically by mod ID and hook ID.

BML resolves an execution order first and then applies the entry patches in the reverse patching order required by the current prepend-style typed-local patcher. `composition-report.tsv` records both the patch/application sequence and the resulting entry execution order.

A mod author should declare ordering only when feature semantics actually require it. Independent entry hooks need no ordering metadata.

### REPLACE_INVOKE

For a Bitwig call, `match.target` should reference a local method target so BML can translate it:

```json
"match": {
  "opcode": "INVOKEVIRTUAL",
  "target": "native_gate",
  "occurrence": 1
}
```

`occurrence` picks one of the target method's matching calls, counting from 1 (the default is 1). Within a mod, it counts the calls left after that mod's earlier hooks on the same call, in manifest order: `occurrence: 1` twice means the first two calls. Other mods' hooks don't change the count, so each mod numbers against Bitwig's original method.

Two mods can reach the same call. BML chains their bridges, in application order, when all of these hold: the call is `INVOKESTATIC`; its return type is a reference type and is also its last parameter's type; each bridge returns exactly that type and takes the call's arguments, each the same type or `java.lang.Object` for a reference; and the hooks are adjacent in the class's application order. Otherwise preparation stops and names both hooks and the method. An occurrence beyond the method's matching calls also stops preparation.

A raw `owner` / `name` / `descriptor` match remains available for deliberately fixed non-Bitwig JVM calls such as JDK methods.

Raw Bitwig matches should not be used because BML cannot translate them.

### ADD_METHOD

`ADD_METHOD` requires an explicit class target for the receiving class and a separate method target for the inherited/existing method identity:

```json
{
  "id": "local-override",
  "target": "panel_class",
  "transform": "ADD_METHOD",

  "method": {
    "from_target": "inherited_paint"
  },

  "body": {
    "template": "CALL_SUPER_THEN_STATIC"
  },

  "bridge": {
    "method": "afterPaint",
    "descriptor": "(Ljava/lang/Object;)V",
    "args": ["this"]
  }
}
```

Supported body templates:

```text
CALL_SUPER_THEN_STATIC
CALL_STATIC_THEN_SUPER
CALL_STATIC_ONLY
```

An optional `method.descriptor` is an assertion and must match the descriptor resolved from `method.from_target`.

## Bridge arguments

Bridge args currently use:

```text
this
arg0
arg1
arg2
...
```

`argN` is a raw JVM local slot. BML's typed-local entry path derives the correct load opcode from the target descriptor and fails on incompatible bridge types.

Do not assume all parameters are object references or occupy one JVM local slot.

## Opens

A helper that uses a non-public Bitwig class, field or method directly (not by reflection) lists it in `opens`, by local target ID:

```json
"targets": {
  "listener": { "kind": "class", "class": "cNI" },
  "listener.panel": { "kind": "field", "owner": "cNI", "name": "vhT", "descriptor": "LcNF;" }
},
"opens": ["listener", "listener.panel"]
```

BML translates each target like any other and makes it public in the prepared runtime. Only access flags change; Bitwig's code is not touched. A field or method target names the class that declares it.

A non-public instance method is refused, and preparation fails, when making it public would change which method Bitwig calls: when a supertype declares the same name and descriptor, or a subclass does (for a package-private method, only classes outside its package count). Reach such a method by reflection instead.

The BML mod kit writes `opens` automatically (`modkit/README.md`).

## Fields

`fields` adds instance fields to Bitwig classes:

```json
"targets": {
  "renderer": { "kind": "class", "class": "btY" },
  "colour": { "kind": "class", "class": "VJL" }
},
"fields": [
  { "id": "context", "owner": "renderer", "name": "my_mod$context", "descriptor": "Ljava/lang/Object;" },
  { "id": "last-colour", "owner": "renderer", "name": "my_mod$lastColour", "descriptor": "LVJL;" },
  { "id": "marked", "owner": "renderer", "name": "my_mod$marked", "descriptor": "Z" }
]
```

- `owner`: a local class target, translated like any other. The mod isn't available on a build its map can't translate it to.
- `name`: the mod's ID with every character but letters and digits as `_`, then `$`, then a name of letters, digits, `_` and `$`. It's the mod's own name, not a Bitwig one, so a map has no row for it and it stays the same on every build.
- `descriptor`: a primitive, a JDK class (`java/...`), a Bitwig class declared as a class target, or an array of those. A Bitwig class is translated through its target. A mod's own type is refused: use `Ljava/lang/Object;` and cast.

The field is public, transient and synthetic, and starts at zero or null. Helpers reach it through a `VarHandle`; a `getfield` in a helper is refused at build by the mod kit, and `mod_uses.py` reports it as missing.

Preparation refuses, and Load with it, when:

- the owner is an interface, record or enum, or is `Serializable` through any supertype;
- the owner, a supertype or a subclass already has a field of that name, whatever its type;
- two ticked mods declare that name on one class, or on a class and its supertype (it names both).

Fields are checked before any class is patched, so a refusal leaves no prepared class. They are added after the class's hooks and before its `opens`, and they don't affect either. A prepared class without declared fields is not touched by any of this.

## Trace targets

A Bitwig trace target should reference a local method target:

```json
{
  "id": "dispatch",
  "target": "pointer_dispatch"
}
```

A raw `class` / `method` / `descriptor` trace remains available for a mod's own helper classes or other deliberately fixed non-Bitwig classes.

A diagnostic target may opt into version-tolerant omission using `"optional": true`:

```json
{
  "id": "native-optional-debug",
  "target": "optional_debug_method",
  "optional": true
}
```

An optional trace that has no mapping on the installed build is omitted from `targets.tsv` and reported as `OPTIONAL_TRACE_UNMAPPED`; its declaration remains `UNMAPPED` in target-resolution diagnostics. Missing local declarations are still errors. Omitting `optional` (or setting it to `false`) preserves fail-closed behavior. This option **does not apply to hooks, invocation-match targets or helper bindings**.

A trace-only package is valid with:

```text
0 hooks
1+ trace_targets
```

## Helper JAR boundary

Helper JARs contain mod code, not Bitwig/vendor classes.

BML rejects helper JAR entries that shadow classes present in `bitwig.jar`, and rejects helper-entry collisions between active mods.

Compile-time Bitwig stubs must not be packaged into `helper.jar`.

## Public compatibility data

This BML line ships only the physical equivalence information needed for runtime translation.

Mapping evidence and research notes are not part of the runtime package.

Same-build `EXACT` use bypasses compatibility lookup. Cross-build `MAPPED` use consults anonymous physical-equivalence groups.

---

## Optional `mod` metadata: area, homepage, documentation, tags

Four **optional** fields inside the `mod` object (v2, and v3 `package_type: mod`)
describe the mod to the window. No existing mod must be changed:

- `area`: the part of Bitwig the mod belongs to, 1 to 60 characters. The window
  groups mods by area (ignoring case), then by name. Without it the area is the
  `"Area: "` prefix of `mod.name`, else `Other`; frameworks use `Framework`. When
  the name starts with `"<area>: "`, the rest of the name is shown beside the
  muted area. An invalid value is a warning and is ignored.
- `homepage`: an `http://` or `https://` address of a web page about the mod. The
  window offers "Open on the Web" for it and asks before opening it. Another
  scheme is a warning and is ignored.
- `documentation` and `tags`, below.

```json
{
  "mod": {
    "id": "example",
    "name": "Example",
    "version": "1.0",
    "author": "Author",
    "description": "Example mod",
    "area": "Arranger",
    "homepage": "https://example.org/example-mod",
    "documentation": "readme",
    "tags": ["Editing", "Workflow"]
  },
  "resources": {
    "readme": { "kind": "file", "path": "README.md", "media_type": "text/markdown" }
  }
}
```

This is an **illustrative fragment**, not a full valid manifest. The
`mod.documentation` value is a resource ID referring to a declared package-local
Markdown `file`; `.md` or `media_type: text/markdown` is expected. Absence of a
documentation declaration is normal. An optional missing documentation file
is a non-blocking warning in this build and is not bound to the helper;
unsafe resource paths remain blocking. The window shows the README as text
(headings, lists, quotes, code, links that ask before opening); images are left
out. `mod.tags` is a list of simple nonblank labels (max 80 characters per
label), which the window's search finds, compared case-insensitively.

These metadata extensions do not introduce Manifest v3, independent components,
new resource purposes or changes to EXISTING Bitwig physical target resolution.
The authoritative installed v2 schema for **this preview** is
`loader/mod.schema.json`. The project Documentation Agent maintains the
canonical BML reference separately.
