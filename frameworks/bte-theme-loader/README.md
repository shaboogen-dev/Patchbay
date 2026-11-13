# Framework: Themes

Lets a theme change how Bitwig Studio looks: its colours, in the `.bte` format of Berikai's [Bitwig Theme Editor](https://github.com/Berikai/bitwig-theme-editor), and optionally its icons and images. A theme is a BML component of this framework, so a theme author writes no code. Bitwig's files are never changed: disable the framework and restart, and Bitwig looks as before.

- Mod ID: `bte-theme-loader`
- Built against Bitwig 6.1.4.
- Needs BML loader 0.35.0-dev.12 or later. An earlier loader refuses this framework's manifest and lists it on Errors.
- Ships no theme. Choose one in BML's component selection: one theme is selected at a time.

## Adding a theme

Drop it in the `components/` folder of your BML folder. Either of these works, with nothing else to write:

- **A `.bte` file**, directly in `components/`: `components/Dark Neon.bte`. This is what Bitwig Theme Editor saves.
- **A folder** holding one `.bte` file, and optionally `overlays/` for icons and images: `components/Dark Neon/my-theme.bte`, `components/Dark Neon/overlays/icons/…`.

The theme appears under this framework's **Themes** tab in BML, named after the file or folder. Tick the framework, choose the theme there and click **Load**.

- A folder with two `.bte` files is an error on BML's **Errors** page that names them: keep one, or give each its own folder.
- A folder BML doesn't recognise is a warning on **Errors** and stops nothing, so you can keep other folders here.
- A problem with a theme stops **Load** only while that theme is selected.
- Your selection follows the theme's name. Rename its file or folder and it counts as a new theme: choose it again.
- `components/` belongs to one BML folder: when you unpack a new version of BML, copy your themes across.

The theme and its overlays are read once, when Bitwig starts: restart Bitwig after changing them.

## The `.bte` file

One entry per line; other lines are ignored. The file is UTF-8 text.

```
// comments start with //
Gradient: false
Window background: #2B2D30
Selection: #E68E4366   // #rgb, #rgba, #rrggbb or #rrggbbaa
```

## Overlays

Files under the overlay folder's `icons/` and `images/` folders replace Bitwig's own file with the same path in its `resources/` folder: `overlays/icons/browser/device_instrument.svg` replaces `resources/icons/browser/device_instrument.svg`. A more specific path wins over a shorter one. Use your own artwork: a theme must not ship copies of Bitwig's files.

## Giving a theme a name, author and description

A folder can hold a `mod.json`, which then decides everything about the theme: its name, its ID, where its files are, and the author, description and README that BML shows. A theme doesn't need one.

```json
{
  "manifest_version": 3,
  "package_type": "component",
  "component": {
    "id": "dark-neon",
    "name": "Dark Neon",
    "version": "1.0.0",
    "author": "Example",
    "description": "A dark theme with neon accents.",
    "parent": "bte-theme-loader",
    "group": "themes",
    "interface_version": "1",
    "documentation": "readme"
  },
  "resources": {
    "theme": { "kind": "file", "path": "resources/dark-neon.bte" },
    "overlays": { "kind": "directory", "path": "resources/overlays" },
    "readme": { "kind": "file", "path": "README.md", "media_type": "text/markdown" }
  }
}
```

| Resource | Kind | Required | What it is |
|---|---|---|---|
| `theme` | file | yes | The `.bte` file. |
| `overlays` | directory | no | Icons and images that replace Bitwig's own. |

## What a theme can set

| Entry | Effect |
|---|---|
| `<Bitwig colour name>: <colour>` | Replaces Bitwig's colour of that name when Bitwig creates it. Names are Bitwig Theme Editor's (Bitwig's own colour names). A colour the theme leaves out keeps Bitwig's value. |
| `Gradient: true \| false` | `false` turns off the shades Bitwig derives from a colour (lighter, darker, more or less saturated): the colour itself is used instead. Default `true`. |
| `EQ Band 1` … `EQ Band 8` | The EQ's band colours, by pitch (lowest band 1). With `Gradient: false` the curve uses `EQ Curve Line` instead. |
| `EQ Curve Line` | The EQ curve when `Gradient: false`. |
| `Note FX Chain 1` … `Note FX Chain 8` | The colours of a Note FX container's chains, by position. |

The EQ and Note FX keys are Bitwig Theme Editor 2.4.0's. A named colour that Bitwig resolves for a relative style (e.g. knob bodies) also takes the theme's value for that name.

Not supported: piano roll key colours (`Piano Roll Black Key`, `Piano Roll White Key`).

## Credits

The colour keys follow Berikai's Bitwig Theme Editor (MIT licence), whose research this framework builds on.
