# What a BML mod is

A BML mod is a folder. When BML prepares Bitwig, it reads the folder's `mod.json`, translates the Bitwig names in it and in the mod's `helper.jar` to the installed Bitwig version, patches the Bitwig methods the manifest names, and adds the helper jar to Bitwig's classpath.

## The installed folder

```text
<mod-id>/
  mod.json            the manifest: the only file BML reads on its own
  helper.jar          the mod's compiled code (when it has code)
  actions.properties  any other file the manifest names: framework descriptors,
  icons/...           resources, a README
  README.md
  images/...          the README's images
```

A mod's folder name in a BML install is its mod ID. Everything the mod needs at runtime is in its folder; it never contains Bitwig's classes or files.

## The source folder

Write a mod in this layout (the same layout BML's own mods use, so `BML/tools/build_mod.py` can build it inside a workspace):

```text
<mod-id>/
  mod.json      the manifest without helper, targets, hooks and opens (the build adds them):
                metadata, built_against, resources, registrations
  build.json    build settings
  src/          Java source; any non-Java file here goes into helper.jar unchanged
  README.md     the mod's README, copied into the installed folder
  images/       the README's images, if it has any (PNG, JPEG or GIF)
  package/      files copied into the installed folder unchanged (descriptors, icons)
```

### README images

A README can show images. Link them by relative path; an `images/` folder beside the README is the recommended place:

```markdown
![Bitwig's Settings window](images/settings-window.png)
```

The build ships each image the README references at the same path, so the README shows the same on GitHub and in Patchbay. Images must be PNG, JPEG or GIF (a GIF may animate); Patchbay can't show SVG. An image can sit anywhere in the mod's folder except `src/`, `releases/`, `test/` and `dev/`; `images/` is the recommended place. The build stops when a referenced image is missing, outside the mod's folder, or in another format, and leaves out files in `images/` that the README doesn't reference.

Patchbay shows an image only when its path is relative and stays inside the mod's folder. It never loads anything from the network: a web image, or one it can't show, appears as its alt text. Images are scaled down to fit the reading column, keeping their shape, and never shown larger than their own pixels, so take screenshots at the size you want them seen.

`build.json`:

```json
{
  "release": 21,
  "kit": true,
  "classpath_mods": ["action-menu-composition-framework@0.5.1-dev.1"]
}
```

- `release`: the `javac --release` level. Use `21`.
- `kit`: `true` builds the mod with the mod kit ([writing.md](writing.md)).
- `classpath_mods`: optional. The frameworks the code compiles against, as `<mod-id>@<version>` ([frameworks.md](frameworks.md)).

## mod.json

The shape of a built mod's manifest, with every top-level key. With the mod kit you write only the parts marked "you" in the table; the build adds the rest ([building.md](building.md)). Keys a mod doesn't use are left out, except `targets` and `hooks`, which are always there.

```json
{
  "manifest_version": 2,
  "mod": {
    "id": "example-device-name",
    "name": "Device: Show Device Name",
    "version": "0.1.0",
    "author": "Your Name",
    "description": "Adds Show Device Name to the device right-click menu."
  },
  "built_against": {
    "bitwig_version": "6.1.4",
    "platform": "windows",
    "architecture": "x64"
  },
  "helper": { "jar": "helper.jar", "class": "com.example.mymod.MyModHooks" },
  "targets": {},
  "hooks": [],
  "opens": [],
  "resources": {},
  "registrations": [],
  "requires": []
}
```

| Key | Who writes it | Required | Meaning |
|---|---|---|---|
| `manifest_version` | you | yes | Always `2`. |
| `mod.id` | you | yes | The mod's ID. Matches `^[A-Za-z][A-Za-z0-9_.-]*$`; use lowercase words joined by `-` (`example-device-name`). Never change it after release: BML stores which mods are enabled by ID, and frameworks use it to separate mods. |
| `mod.name` | you | yes | The name users see. Format: **"Area: What it does"**, where the area is a part of Bitwig users know (Arranger, Browser, Commander, Device, Mixer, Parameter, Piano Roll, Track, ...; pick the one where users meet the feature most), e.g. "Commander: Enter Selects First Result". A framework is named "Framework: <area>". |
| `mod.version` | you | yes | Semantic version: `1.0.0`, `0.2.0-dev.3`. |
| `mod.author`, `mod.description` | you | yes | Non-empty text. |
| `mod.area` | you | no | The part of Bitwig the mod belongs to (1 to 60 characters, e.g. `"Arranger"`). Patchbay lists mods by area, ignoring case, then by name. Without it the area is the `"Area: "` prefix of `mod.name`, else `Other`; frameworks use `Framework`. When `mod.name` starts with `"<area>: "`, the window shows the rest of the name next to the area in a muted colour. |
| `mod.homepage` | you | no | A web page about the mod (`http://` or `https://` only), e.g. its README on GitHub. Patchbay shows **Open on the Web** for it, beside **README**, and asks before opening it. Another scheme is a warning and is ignored. |
| `mod.documentation` | you | no | A resource ID naming the mod's Markdown README. Patchbay opens it in its reader: headings, lists, quotes, code, links and images ([README images](#readme-images)). |
| `mod.tags` | you | no | A list of short labels; Patchbay's search finds them. |
| `built_against` | you | yes | The Bitwig build whose names the mod uses: `bitwig_version`, `platform` (`windows`, `macos` or `linux`) and `architecture` (`x64` on Windows and Linux, `universal` on macOS). Use 6.1.4 on the platform whose jar you read, e.g. `"6.1.4"`, `"windows"`, `"x64"` or `"6.1.4"`, `"macos"`, `"universal"`. |
| `helper` | build | when there are hooks | The jar and the class that holds every hook method. |
| `targets` | build (you only for hand-written hooks) | yes, may be `{}` | Bitwig classes, methods and fields the manifest refers to, by a local ID. |
| `hooks` | build (you only for hand-written hooks) | yes, may be `[]` | The patches BML applies to Bitwig's methods. |
| `fields` | build (you only for hand-written ones) | no | Instance fields BML adds to Bitwig classes ([writing.md](writing.md), "Adding a field to a Bitwig class"). |
| `opens` | build | no | Target IDs of non-public Bitwig classes and members the helper uses directly; BML makes them public. |
| `resources` | you | no | Files the mod uses at runtime, by ID (below). |
| `registrations` | you | no | Registrations with frameworks ([frameworks.md](frameworks.md)). |
| `requires` | you | no | Other mods this mod needs, with an optional minimum version (below). |

A mod must have at least one hook or one registration (or a `trace_targets` entry, a diagnostic feature not covered here). The full manifest format, including hand-written hooks, is in the loader's `MANIFEST-FORMAT.md`; [converting.md](converting.md) lists what you need from it when converting a hand-written manifest.

## Resources

A resource is a file or folder the mod uses at runtime, declared by an ID. The code asks BML for its path (`Bml.mod("<mod-id>").resource("<id>")`, [writing.md](writing.md)); frameworks use resource IDs for icons.

```json
"resources": {
  "readme":     { "kind": "file",      "path": "README.md", "media_type": "text/markdown" },
  "my_icon":    { "kind": "file",      "path": "icons/my-action.svg", "media_type": "image/svg+xml" },
  "layouts":    { "kind": "directory", "path": "layouts", "persistence": "preserve" },
  "paste_icon": { "kind": "bitwig",    "path": "icons/action/insert_silence.svg" }
}
```

- `file` / `directory`: a file or folder of the mod's own, relative to the mod's folder. It must exist in the mod's folder when the mod is prepared. `persistence` is `replaceable` (the default: part of the package, replaced on update) or `preserve` (holds user data: see below).
- `bitwig`: one of Bitwig's own files, by its path inside the Bitwig install's `resources` folder (`icons/action/insert_silence.svg` is `C:\Program Files\Bitwig Studio\resources\icons\action\insert_silence.svg`). Nothing is copied: the mod names the file and Bitwig's own copy is used. Preparing fails if the installed Bitwig doesn't have the file.

**Icons** are always resources. Use one of Bitwig's icons by reference (`kind: bitwig`, a path under `icons/`), or draw your own SVG (`kind: file`): 21 x 21 (`viewBox="0 0 21 21"`), black on a transparent background (Bitwig tints icons itself). Never copy one of Bitwig's icons into a mod.

## What a mod keeps

Everything a mod keeps lives in its data folder, `mods/<mod-id>/` in BML's settings folder, so a new version of BML starts with it. The settings folder is per user: `%LOCALAPPDATA%\BML` on Windows, `~/Library/Application Support/BML` on macOS, `$XDG_CONFIG_HOME/bml` or else `~/.config/bml` on Linux. The mod's own folder gets replaced by a rebuild or an update: a mod never writes there. Nor does it keep anything anywhere else: no Java Preferences, no files of its own in the home folder. It asks BML for these paths and never builds them itself.

- **Settings** (a few named values): `MOD.settings()` ([writing.md](writing.md)). BML keeps them in `settings.properties` in the data folder.
- **Files** (a list the user edits, a folder of layouts): declare a `preserve` resource. `MOD.resource(id)` gives its path in the data folder, `<last part of the declared path>` inside it: `layouts` above is `mods/<mod-id>/layouts` in the settings folder. The mod reads and writes it there, and a change to it needs no new preparation. What the mod ships under the declared path is what it starts from. Each time the mod is prepared, BML copies across any shipped file it hasn't copied before, and never overwrites one that is there, so a new version's new file arrives, a user's copy wins over a newer shipped one, and a shipped file in a folder that the user deleted stays deleted. A `file` resource is the exception: the mod needs it, so one that is deleted comes back from what the mod ships. For settings files that don't exist on first run, declare a `preserve` directory and create the files inside it.

Two `preserve` resources of a mod can't end in the same name, and one can't be called `settings.properties` or `seeded-files.txt`, which BML keeps in the data folder.

## Prerequisites

A mod's prerequisites are the mods it needs to load. They come from two places in `mod.json`:

- **Registrations:** every framework a registration names as its `consumer` is a prerequisite. A mod that registers with a framework needs nothing more to declare it.
- **`requires`:** a list for anything else, and for a minimum version. Each entry has an `id` (the other mod's ID) and optionally a `min_version` (a semantic version: that version or later).

```json
"requires": [
  { "id": "panel-management-framework", "min_version": "0.1.0-dev.8" }
]
```

An entry may name a framework the mod also registers with: the registration makes it a prerequisite and the entry adds the minimum version. A mod can't require itself, and each ID appears once. Versions compare by semantic-version rules (`0.1.0-dev.10` is later than `0.1.0-dev.9`, and `0.1.0` is later than every `0.1.0-dev.N`).

What BML does with them:

| The prerequisite is | BML |
|---|---|
| installed and turned on | loads both. |
| turned off in the Patchbay window | an error while the mod is turned on: the Errors page lists `<mod name> needs <name>, which is turned off.` with the fix (`Tick <name>, or untick <mod name>.`), and Load refuses to start Bitwig. The error clears as soon as either is done. Preparation outside the window refuses the same way. |
| not available on the installed Bitwig version | the mod isn't available on this version either ([Running on several Bitwig versions](#running-on-several-bitwig-versions)), with `needs <name> (<id>), which isn't available on this version` as the reason. |
| not installed, or older than `min_version` | an error: the mod can't be turned on, the Patchbay window lists it on the Errors page (`Needs <id> <version> or later, which isn't installed.` or `Needs <name> <version> or later; <installed version> is installed.`), and preparation refuses while it is turned on. |
| installed but has errors itself | an error for the mod as well, naming the prerequisite. |

In the Patchbay window, ticking a mod also ticks its prerequisites (and theirs). Unticking a prerequisite leaves the mods that need it ticked, marks them in red with `Needs <name>` in their row, and adds an error for each to the Errors page. Load is greyed, with a red strip saying why, while any ticked mod has an error, and each error clears as soon as it is fixed. The window lists the mods present in `mods\` when it starts and on Refresh; a tick saved for a mod that isn't there any more is dropped.

**Disable Mod** (the mod's right-click menu, or the bar under the list) sets a mod aside without uninstalling it: it leaves the list, is never prepared or loaded, and its issues don't show on the Errors page or block Load. Choosing **Disabled** in the list's **Show** dropdown lists the disabled mods, dimmed, with **Enable Mod**; a mod enabled again starts unticked. A disabled prerequisite is enabled again when a mod that needs it is ticked, and a mod a ticked mod needs can't be disabled.

## Running on several Bitwig versions

Bitwig renames its obfuscated classes and members with every release, and obfuscates each platform's jar separately. A mod uses one build's names, the build in `built_against` (version and platform), and BML translates them when it prepares Bitwig:

- **Same build:** when the installed Bitwig is the `built_against` build, the names are used as written.
- **Other builds:** BML uses the map pack for the installed build. A map pack holds a remap from each reference build (6.1.3 and 6.1.4, each on Windows, macOS and Linux) to its own build, derived from the two Bitwig jars. BML translates every Bitwig name in the manifest and in `helper.jar`.
- **Not available:** the mod isn't available on the installed build when its map pack has no remap from the mod's `built_against` build, or when the remap has no counterpart for something the mod needs: a target of one of its hooks (the hooked method, a replaced call, an added method's class or source), a target it `opens`, or a Bitwig class or member in its `uses.tsv` (written into every release by `build_mod.py`). The usual case is a platform's own code: each platform has its own windowing system and file watching (`com.bitwig.mac_windowing_system`, `com.bitwig.win32_windowing_system`, `com.bitwig.x11_windowing_system`, ...), with no counterpart on the other platforms, so a mod that hooks macOS's Cocoa windows runs on macOS only. The Patchbay window shows such a mod greyed out with `Not available on <version>`, it can't be ticked, and a newly installed one starts unticked. A tick saved from before is an error on the Errors page (`<mod name> isn't available on Bitwig <version>.`, with the reason, fixed by unticking it), and Load is blocked until it is unticked. Preparation outside the window refuses the same way.
- **Other names:** a trace target or a target the helper reads through `BML_MOD_CONFIG` with no counterpart stops preparation with that name. A manifest target nothing uses gives a warning and isn't bound. A name used only inside `helper.jar`, in a mod without `uses.tsv`, stays untranslated: preparation gives a warning and continues, and that code fails in Bitwig when it runs. The offline check shows each of these ([building.md](building.md)).
- So a mod built against **6.1.4** (or 6.1.3) on any platform runs on 6.0, 6.1, 6.1.1, 6.1.2, 6.1.3 and 6.1.4 on Windows, macOS and Linux, as far as every Bitwig class and member it uses exists in each. A mod built against another version runs only on that exact build, until it is ported ([converting.md](converting.md)).
- Translation renames; it does not adapt. When Bitwig changed how something works (a method removed or its types changed), the mod's author decides how to handle that version.

[building.md](building.md) shows how to check offline which versions a mod's names translate to.
