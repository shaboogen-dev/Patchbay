# BML — Manifest v3 technical preview

**Status:** Phase C Preview 1, synthetic-tested development candidate. NOT a released/Windows-runtime-accepted contract; Documentation Agent owns the canonical public BML reference.

## Package roots and compatibility

* `mods/<package>/mod.json`: Manifest v2 mods unchanged, plus Manifest v3 `package_type: "mod"` parents.
* `components/<package>/mod.json`: Manifest v3 `package_type: "component"` only. No executable targets, hooks, trace targets, helpers, or built-against declarations in components.
* Components are resource/metadata packages associated through declared stable parent ID and group ID, **not** folder names. One component belongs to exactly one parent and group.
* The existing v2 `loader/mod.schema.json` remains untouched. The new `loader/mod-v3.schema.json` validates the two v3 package shapes. Java manifests use existing filename `mod.json`.

## Parent manifest

A v3 mod adds `"manifest_version": 3`, `"package_type": "mod"` to the usual v2-style executable manifest (its `mod` object takes the same optional `area`, `homepage`, `documentation` and `tags` as v2, `MANIFEST-FORMAT.md`), and optionally declares `component_groups`:

```json
"component_groups": {
  "themes": {
    "name": "Themes",
    "selection": "single",
    "interface_version": "1",
    "required_resources": { "theme": { "kind": "file" } }
  }
}
```

In the window, a group is chosen in its parent's selection bar on the Mods page: a dropdown for `single`, tick boxes for `multiple`.

`selection` is `single` (exactly one) or `multiple` (at least `minimum`). An *unconfigured* `single` group gets a one-time default of the first usable component in the displayed catalogue order. This default is persisted; an existing nonempty saved ID is never replaced automatically, including when unavailable. `multiple` groups need an explicit choice. A parent with no groups functions like an ordinary v3 mod.

`minimum` is optional: a whole number, 0 or more, of components that must be chosen while the parent is enabled. It defaults to 1 and is only for `multiple`; a `single` group always needs one (`minimum` other than 1 is refused). With `"minimum": 0` the group may be empty: the parent prepares and loads with nothing chosen and with nothing installed (no `NO_USABLE_COMPONENTS` error), its bindings say `selected_count=0`, and the last tick box can be cleared while the parent is ticked. Use it when the framework works without components, for example one whose users make their first component with the framework itself:

```json
"panels": {
  "name": "Panels",
  "selection": "multiple",
  "minimum": 0,
  "required_resources": { "panel": { "kind": "file" } },
  "detect": { "panel": "*.json" }
}
```

A chosen component that is no longer installed is still an error with `minimum` 0: the saved choice is kept and blocks the parent until it comes back or is unticked.

`interface_version` is an optional **decimal-integer string** defaulting to `"1"`, exact-match between parent group and component. It is a separate *group interface* contract rather than a marketing/version range. A future change to its matching semantics requires an explicit contract revision.

## Packages with no `mod.json`: `detect`

A group can declare how to recognise its packages from their files:

```json
"themes": {
  "name": "Themes",
  "selection": "single",
  "required_resources": { "theme": { "kind": "file" } },
  "detect": { "theme": "*.bte", "overlays": "overlays/" }
}
```

Keys are the group's resource slots and every required resource must have one. A value is a file name pattern (`*` and `?`, at least one literal character, no path, case-insensitive) or `name/` for a folder (case-insensitive). A required slot's pattern must fit its kind, and at least one pattern must be a file pattern. A group with no `detect` recognises nothing this way. BML reads only these patterns: no framework ID is known to it, and what the files mean stays with the framework.

What `components/` then holds:

* **A folder with a `mod.json`:** that manifest decides, as below. A broken one is an error; the folder's files don't overrule it.
* **A folder with no `mod.json`:** the group whose file pattern matches something directly in the folder owns it. Each matched slot becomes a resource with `persistence: replaceable`. A pattern that matches two files is a blocking error on that package, naming them. A folder pattern that matches nothing is simply absent, unless it is required.
* **A file directly in `components/`:** recognised when it matches a group's file pattern and that group has exactly one file pattern and no other required resource. It fills that slot; its folder patterns stay empty. Any other file is left alone.
* **A folder nothing recognises:** a warning (`UNRECOGNISED_COMPONENT_FOLDER`) that says what is looked for, and it isn't listed. Names starting with `.` and `__MACOSX` are ignored, here and inside a package.

A package recognised this way is named after its folder, or its file without the extension. When a `.json` file fills one of its slots (the first such, in `detect` order) and its top level is an object with a `"name"` (non-blank text, or a whole number, shown as its digits), that name is shown instead, its runs of spaces and line breaks made one space, and cut at 120 characters. This applies to any group: BML reads only the top-level `name`. The ID still comes from the file or folder name, so changing the name inside the file keeps a saved choice. JSON that can't be read, or has no such name, keeps the file name and is listed as before: the framework decides what its content means. Version, author, description and tags are empty, and its interface version is the group's. Its ID is the name in lower case, with accents removed and each run of other characters replaced by `-`; with `c-` in front if it wouldn't start with a letter; or `c-` and 8 hex digits of the name's SHA-256 if no usable character remains. So a selection follows the name: renaming the file or folder makes a new package, and the saved ID is an error until the user chooses again.

* Two names that make one ID: a blocking error on both, naming them.
* A name that makes the ID of a package with a `mod.json`: that package isn't affected, and this one is a warning (`COMPONENT_ID_TAKEN`) and isn't listed.
* A file or folder two groups could own: a warning (`AMBIGUOUS_COMPONENT`) and it isn't listed.
* A file a pattern matches must be readable, UTF-8 text, not blank and at most 64 MiB, or it is a blocking error on that package (`UNREADABLE_COMPONENT_FILE`, `EMPTY_COMPONENT_FILE`). So `detect` suits text formats.

As everywhere in `components/`, a blocking error on a package stops Load only while it is the selected one, symbolic links and reparse points are refused, and no code runs.

## Component manifest

```json
{
  "manifest_version": 3,
  "package_type": "component",
  "component": {
    "id": "dark-neon",
    "name": "Dark Neon",
    "version": "1.0.0",
    "author": "Example",
    "description": "A theme choice",
    "parent": "bte-theme-loader",
    "group": "themes",
    "interface_version": "1",
    "tags": ["Theme", "Dark"],
    "documentation": "readme"
  },
  "resources": {
    "theme": { "kind": "file", "path": "resources/dark-neon.bte" },
    "readme": { "kind": "file", "path": "README.md", "media_type": "text/markdown" }
  }
}
```

Resources retain the v2 shape (`kind: file|directory`, optional `persistence: replaceable|preserve`, optional `media_type`); a component has no `created` resources, which only a mod writes. The loader validates paths, symlinks, reparse points, presence and required filesystem shapes. The parent helper—not BML—interprets the contents (e.g. BTE theme parsing). Optional missing Markdown is a warning unless the path violates the security boundary.

## Persisted component selection

The GUI writes `state/selected-components.properties`, independent of installed folders. Keys have this shape:

`component.<encoded-parent>.group.<encoded-group>.ids=componentA,componentB`

A `.` within an identifier is encoded as `%2E` in the **key** segment; comma-separated values are stable component IDs. The number and order of selected IDs are significant for `multiple` groups. Missing saved identities stay saved and block the parent; the first-usable default applies only when the group has no selected ID.

Newly installed **v3 parents** default unselected (existing **v2 mods** preserve default enabled behavior). At refresh and before command-line preparation, each `single` group that lacks an ID is assigned its first usable installed option, persisted for subsequent sessions. A `multiple` group with fewer than its `minimum` chosen requires manual selection, and preparation remains unavailable until all groups are satisfied. Zero usable components is a blocking installation error, except in a group with `"minimum": 0`. Unselected invalid installations are diagnosed but isolated during preparation.

## Helper bindings, emitted into `BML_MOD_CONFIG` → `mod-bindings.properties`

v2 `mod.<id>.resource.*` keys remain unchanged. Selected v3 resource-only packages emit separate, deterministic keys, in declared group ID order and selected-component choice order:

```properties
component.bte-theme-loader.group.themes.selection=single
component.bte-theme-loader.group.themes.interface_version=1
component.bte-theme-loader.group.themes.selected_count=1
component.bte-theme-loader.group.themes.selected.0.id=dark-neon
component.bte-theme-loader.group.themes.selected.0.version=1.0.0
component.bte-theme-loader.group.themes.selected.0.author=Example
component.bte-theme-loader.group.themes.selected.0.resource.theme.kind=file
component.bte-theme-loader.group.themes.selected.0.resource.theme.path=C:/path/to/theme.bte
component.bte-theme-loader.group.themes.selected.0.resource.theme.uri=file:///C:/path/to/theme.bte
component.bte-theme-loader.group.themes.selected.0.resource.theme.persistence=replaceable
```

A group with nothing chosen (`"minimum": 0`) has its `selection`, `interface_version` and `selected_count=0` keys and no `selected.*` keys.

The components folder itself is bound too, as `loader.components.path` (loader 0.36.0-dev.11 or later), and a mod reads it with `bml.api.Bml.componentsFolder()`. A framework that saves a component the user made (such as a panel) writes it there, rather than working the place out from its own folder: where components are kept may change.

Every selected component resource additionally appears in `resource-resolution.tsv`. Keys are disjoint from v2 `mod.*`, and selection-indexed bindings avoid collisions between similarly named children. BML does not execute components. Paths are resolved from their independent package roots on each preparation, never from a folder-name allowlist. Runtime changes take effect only after next prepare/launch, not hot-loading.

## Errors and evidence

Misplaced manifests, orphans, duplicates, incompatible interface versions, missing required resources and unsafe paths are reported in Errors. A parent with unavailable requested components remains inspectable and blocked. Multiple-selection groups requiring their first choice are configuration-required rather than erroneous installs. A group with no usable installed component is blocking, unless it has `"minimum": 0`. Installing/restoring the exact requested package re-enables preparation after refresh.

**Evidence update (2026-10-11):** the user has reported successful Windows loading of the separately converted Framework: Themes. This Preview 6 change remains synthetic/headful-tested only and awaits its own Windows review; no fresh Bitwig runtime trace is claimed.


## Experimental optional diagnostics extension (Phase 2A Preview 14)

The additive v3.1 authoring revision **keeps** integer `"manifest_version": 3`.
Only executable `package_type: "mod"` packages may declare a top-level
`"diagnostics": {"outputs": {"runtime": {"kind": "file", "path": "runtime.log"}}}`.
Files/folders named in `outputs` are *destinations*, not packaged resources.
They need not exist and do not cause a missing-resource warning. Paths must be
safe session-root-relative paths. Components may not declare diagnostics.

In a GUI-managed Debug launch, each enabled mod receives optional bindings in
`BML_MOD_CONFIG` (`loader/mod-runtime/mod-bindings.properties`):
`mod.<encoded-mod-id>.diagnostics.enabled`, `.root.path`, `.root.uri` and
`.output.<encoded-output-id>.kind/path/uri`. Periods in IDs are encoded as
`%2E`, matching the existing component-binding convention. Normal launches
omit these bindings. The diagnostics are owned by that launch's session folder.
This is an **implementation checkpoint**, not accepted real Windows runtime
proof; unified Sessions GUI, final collection/recovery/ZIP/delete and legacy
migration are still pending.
