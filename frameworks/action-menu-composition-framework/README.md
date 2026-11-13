# Framework: Actions and Menus

Framework: Actions and Menus does nothing on its own. It gives modders an API for adding their own actions to Bitwig and placing them, or Bitwig's own actions, in Bitwig's menus.

Each action is a real Bitwig action: the user can give it a shortcut in Settings > Shortcuts and find it in the Commander, unless the mod makes it menu-only. The framework creates the Bitwig action, registers it, binds it in every project window (or on the widgets the mod names) and places it in the menus the mod names. The mod never touches Bitwig's action internals.

<!-- image: a right-click menu with Bitwig's entries, a separator, then a mod's submenu and actions -->

- Mod ID: `action-menu-composition-framework`. Java package: `bml.actions`.
- Built against Bitwig 6.1.4; prepares on Bitwig 6.0 to 6.1.4.
- Needs Patchbay 0.34.0-dev.7 or later (manifest `registrations` and `bitwig` resources, `bml.api.Bml`).

## Contributing actions

A mod contributes in three steps.

**1. Register with the framework in `mod.json`:**

```json
"registrations": [
  {
    "id": "actions",
    "consumer": "action-menu-composition-framework",
    "descriptor": "actions.properties",
    "jar": "helper.jar"
  }
]
```

The mod's registration jar is merged and translated by the loader like hook code, so the provider can use Bitwig's classes directly.

**2. Add the descriptor** (`actions.properties`) to the mod's `package/` folder, so it lands next to `mod.json`:

```properties
api.version=1
provider.class=com.example.mymod.MyActions
```

**3. Write the provider** in the mod's helper jar. Compile against the framework's helper jar (`"classpath_mods": ["action-menu-composition-framework@<version>"]` in `build.json`).

```java
package com.example.mymod;

import bml.actions.ActionProvider;
import bml.actions.Actions;
import bml.actions.Menu;

public final class MyActions implements ActionProvider {
    @Override
    public void declare(Actions actions) {
        actions.action("my_mod_randomise", "Randomise Visible Remote Controls")
               .in(Menu.DEVICE, Menu.selectionToolbar("device"))
               .category("my_mod")
               .onRun(context -> Randomiser.randomise(context.device()));
        actions.category("my_mod", "My Mod");

        actions.action("my_mod_randomise_parameter", "Randomise Parameter")
               .in(Menu.PARAMETER)
               .onRun(context -> Randomiser.randomise(context.parameter()));

        var slides = actions.submenu("slides", "Pitch Slides")
               .in(Menu.selection("arranger_clip_instrument_note"), Menu.selection("launcher_clip_instrument_note"));
        slides.action("my_mod_convert", "Convert Overlap to Pitch Slide")
              .onRun(context -> Slides.convert(context.selection()));
        slides.action("my_mod_merge", "Merge Overlapping Notes")
              .onRun(context -> Slides.merge(context.selection()));
    }
}
```

The provider class must be public with a public no-argument constructor. The framework calls `declare` once, after Bitwig has registered all of its own actions.

## Actions

`actions.action(actionId, label)` declares one Bitwig action and returns a builder:

| Call | Meaning |
|---|---|
| `.in(Menu...)` | The menus it appears in. None: it exists only as a Bitwig action (shortcut, Commander). |
| `.onRun(ActionContext -> void)` | Required. Runs when the action is chosen from a menu, a shortcut or the Commander. |
| `.menuOnly()` | Optional. The action exists only as rows in its menus: it can't be assigned a shortcut and isn't listed in the Commander or the shortcut settings. Its label, menu label, icon and `visibleWhen` work as usual; `.category` has no effect. It needs a menu, through `.in(...)` or a submenu; without one it is logged and skipped. |
| `.menuLabel(label)` | Optional. The label in menus when it differs from the action's label, which the Commander and the shortcut settings show. Bitwig's own actions do this, for example to end the menu label of an action that opens a dialog with "…". Default: the action's label. |
| `.visibleWhen(ActionContext -> boolean)` | Optional. Asked each time a menu opens; `false` leaves the item out of that menu. Not asked for shortcuts and the Commander. |
| `.category(categoryId)` | Optional. The group the action is listed under in the Commander and the shortcut settings: one of Bitwig's categories (below) or one the mod declares. Default: Bitwig's "General". |
| `.icon(resourceId)` | Optional. The action's icon: a resource the mod declares in its manifest (see Icons below). Shown with the action wherever Bitwig shows action icons. |
| `.bindOn(Class...)` | Optional. Bind the action on widgets of these classes and their subclasses, with no binding on the project window. See Where an action is bound below. |

In menus, an action appears as one of Bitwig's own action rows: its label (or menu label), and its shortcut on the right when the user has assigned one.

<!-- image: Settings > Shortcuts with a mod's actions under a category of the mod's own -->

**Action IDs are stored by Bitwig** in the user's shortcut assignments. Never change one after release. Use the mod ID as a prefix (`my_mod_...`) so IDs from different mods can't collide; the framework refuses a second action with the same ID (the first, by mod ID, is kept).

## Where an action is bound

A shortcut runs an action only where the action has a binding. By default the framework binds every action on the project window, so its shortcut works anywhere in the window and the Commander always lists it. `.bindOn(Class...)` binds it on widgets of the named classes instead, with no binding on the project window.

**How Bitwig picks the action for a key.** Bitwig runs exactly one action per key press. It looks from the focused widget outwards; at each widget it runs the first of the key's actions that has a binding there. Rows with key contexts come before rows without, and among actions with no key contexts the later-registered action comes first. Mods' actions are registered after all of Bitwig's.

So two actions on one key both work where they are bound on different widgets: each runs where focus is inside its own widget. Where both have a binding on the same widget, only the first in that order runs there.

Users can put one key on several actions in Bitwig's shortcut settings (Bitwig's own keymap does this for keys such as Left, and the mod Shortcuts: Share a Key adds a button for it). **An action meant to share a key with Bitwig's own actions should use `.bindOn`.** Bound on the project window, it sits on the same widget as Bitwig's window-level actions, such as Focus widget to the left, and comes before them: wherever nothing deeper takes the key, the mod's action runs and Bitwig's doesn't.

```java
import bitwig.obf.aFq; // the arranger's track list widget

actions.action("my_mod_collapse", "Collapse Track")
       .bindOn(aFq.class)
       .onRun(context -> collapse((aFq) context.widget()));
```

- While focus is inside such a widget, the action comes before anything further out. Anywhere else the key goes to Bitwig as if the action didn't exist.
- `ActionContext.widget()` is the widget holding the binding, for the shortcut and the Commander.
- The Commander lists the action only while focus is inside such a widget, as it does for Bitwig's own narrow actions. Menu rows from `.in(...)` work as usual.
- A class that isn't one of Bitwig's widget classes, or a call with no class, is an error in `declare`: the mod's actions are skipped and logged. On a `.menuOnly()` action `.bindOn` has no effect, and that is logged.
- Two mods' actions on one key and one widget: only the later-registered runs there.

Keys follow focus, not the mouse: "over a device" means the device chain has focus. If a mod is later not loaded, Bitwig drops its actions' rows from the shortcut settings at its next save; other actions keep their keys.

## Categories

`actions.category(categoryId, label)` declares a category of the mod's own; its actions join it with `.category(categoryId)`. Prefix the ID with the mod ID. When two mods declare the same ID, the first (by mod ID) is used.

Bitwig's own categories (Bitwig 6.1.4; the label is what Bitwig shows):

| ID | Label |
|---|---|
| `arranger` | Arranger |
| `browser` | Browser |
| `clip_launcher` | Clip Launcher |
| `comping` | Comping |
| `detail_editor` | Detail Editor |
| `dialog` | Dialogs |
| `document` | Project |
| `edit` | Editing |
| `file` | File |
| `general` | General |
| `help` | Help |
| `mixer` | Mixer |
| `multisample` | Multisample |
| `navigation` | Navigation |
| `panel_management` | Panel Management |
| `search` | Search |
| `selection` | Selection |
| `text_editing` | Text Editing |
| `window` | Window Management |
| `zoom` | Zooming |

A mod category with one of these IDs means Bitwig's. An unknown ID lists the action under General and is logged. The framework logs Bitwig's list (`Bitwig action categories: id=label, ...`) at startup, so a later Bitwig's categories can be read from a Debug session's log.

## Submenus

`actions.submenu(submenuId, label)` declares a submenu and returns a builder with `.in(Menu...)` and its own `.action(...)` and `.submenu(...)`. Submenus nest freely. A submenu is shown only when at least one of its items is visible. Submenu IDs must be unique within the mod.

## Bitwig's own actions in menus

`actions.bitwigAction(bitwigActionId)` (or `submenu.bitwigAction(...)`) places one of Bitwig's existing actions in menus, by Bitwig's ID for it. It appears as Bitwig shows it (its label, icon and shortcut) and runs as it does from Bitwig's own menus. Builder: `.in(Menu...)`, `.visibleWhen(...)`, and `.icon(resourceId)` (see Icons below), which gives the action that icon only if it has none of its own (it is then its icon everywhere Bitwig shows it; an action with an icon of its own keeps it, and the mod's is logged as not used). Bitwig's actions run as Bitwig's own rows in that menu do: in a right-click menu through the clicked widget's binding; in a toolbar dropdown through the panel that built it, which forwards to the focused editor (Bitwig's own toolbar rows work the same way, so the row is disabled when nothing can act on it). Where Bitwig has no binding for the action, it is left out of that menu and logged once. An ID the installed Bitwig doesn't have is logged and left out; an action two mods place in the same menu appears once.

Bitwig's action IDs are its own, unobfuscated names. Some in Bitwig 6.1.4's time-selection menu: `insert_silence` (Insert Silence, icon `action/insert_silence.svg`), `delete_and_pull` (Remove Time, `action/remove_time.svg`), `duplicate_and_push` (Duplicate Time, `action/duplicate_time.svg`), `cut_and_pull` (Cut Time, no icon), `paste_and_push` (Paste Time, no icon).

## Icons

An icon is always a **resource the mod declares in its manifest** (`resources` in `mod.json`), so a mod's manifest shows every icon it uses. Two kinds are supported, and a mod may mix them:

**1. One of Bitwig's own icons** (`"kind": "bitwig"`): a reference to the icon in the installed Bitwig, by its path in Bitwig's `resources` folder. Nothing is copied: Bitwig's icons are Bitwig's, so a mod never ships them; it names the one it wants and Bitwig loads it as it does for its own rows. Bitwig's icons are SVG files under `icons/` there (`icons/action/insert_silence.svg` is `<Bitwig>/resources/icons/action/insert_silence.svg`). The loader checks the file exists in the installed Bitwig when it prepares the mod; if it doesn't (an icon Bitwig renamed or dropped), preparation stops with the resource named, like a missing file of the mod's own.

**2. An icon of the mod's own** (`"kind": "file"`): an SVG file in the mod's folder. Draw it like Bitwig's so it sits in the menu like theirs: 21 x 21 (`viewBox="0 0 21 21"`), in black on a transparent background; Bitwig tints icons itself.

```json
"resources": {
  "paste_icon": { "kind": "bitwig", "path": "icons/action/insert_silence.svg" },
  "my_icon":    { "kind": "file",   "path": "icons/my-action.svg", "media_type": "image/svg+xml" }
}
```

```java
actions.action("my_mod_tidy", "Tidy Up").in(Menu.DEVICE).icon("my_icon").onRun(...);
actions.bitwigAction("paste_and_push").in(Menu.selection("arranger_clip_time_selection")).icon("paste_icon");
```

A resource that isn't declared, a `bitwig` resource outside `icons/`, or a file that isn't an SVG is logged and the action shown without an icon. The resource IDs are the mod's own; the framework reads them from the mod that declared the action.

## Where items appear

<!-- image: a toolbar dropdown (the device header menu) with a mod item after Bitwig's entries -->

The framework decides only position. In each menu, mod entries come after Bitwig's own entries, after a separator; among mod entries, **submenus first, then items, each in alphabetical order** by label (case-insensitive; equal labels are ordered by mod ID, then ID). The same rule applies inside submenus. How a mod structures its entries is up to the mod.

## Menus

| `Menu` | ID | Where | `ActionContext` provides |
|---|---|---|---|
| `DEVICE` | `device` | Device right-click (device panel) | `device()` |
| `PARAMETER` | `parameter` | Parameter right-click: device knobs, remote controls, modulator knobs, volume, pan, sends, mute | `parameter()` |
| `SELECTION` | `selection` | Every selection right-click menu (see the kinds below) | `selectionKind()`, `selection()` |
| `selection(kindId)` | `selection:<kind>` | The right-click menu of one kind of selection | `selectionKind()`, `selection()` |
| `SELECTION_TOOLBAR` | `selection-toolbar` | Every selection toolbar dropdown: the device header menu, the time-selection toolbar's menu, the track, clip, note, automation and cue marker toolbar menus | `selectionKind()` |
| `selectionToolbar(kindId)` | `selection-toolbar:<kind>` | The toolbar dropdown of one kind of selection (e.g. `selectionToolbar("device")` is the device header menu) | `selectionKind()` |
| `CLIP_SLOT` | `clip-slot` | Empty clip launcher slot right-click (a slot with a clip opens its clip's selection menu) | `slot()` |
| `BROWSER_ITEM` | `browser-item` | Right-click on an item in the browser's result list | `item()`, `items()` |
| `PLAY` | `play` | The Play menu: the dropdown beside the transport's Play button, after Bitwig's Play options | nothing (`widget()` only) |

A device's right-click menu is both `DEVICE` and the `device` selection menu; an action placed in both appears once.

Not covered (no mod needs them yet): the timeline rulers, the solo button's mode popup, transport displays and buttons (other than the Play menu), the browser's toolbar and snapshots, the mixer's section toggles and text fields.

### Selection kinds

Bitwig builds the right-click menu (and toolbar dropdown) of everything that can be selected through one builder per kind of selection. A kind's ID is Bitwig's own, unobfuscated name. Bitwig 6.1.4's kinds, by Bitwig's name for their family:

| Family | Kind IDs |
|---|---|
| Clip | `arranger_clip`, `launcher_clip`, `scene`, `slot`, `slot_and_scene` |
| Track | `track` |
| Device | `device` |
| Note | `arranger_clip_instrument_note`, `arranger_instrument_note`, `launcher_clip_instrument_note`, `launcher_instrument_note` |
| Event | `arranger_audio_note`, `arranger_clip_audio_note`, `arranger_clip_instrument_and_audio_note`, `arranger_instrument_and_audio_note`, `launcher_audio_note`, `launcher_clip_audio_note`, `launcher_clip_instrument_and_audio_note`, `launcher_instrument_and_audio_note`, `cue_marker`, `beat_marker`, `loop_region`, `onset` |
| Point | `arranger_automation`, `clip_automation`, `arranger_audio_note_expression`, `arranger_clip_audio_note_expression`, `arranger_clip_instrument_note_expression`, `arranger_instrument_note_expression`, `launcher_audio_note_expression`, `launcher_clip_audio_note_expression`, `launcher_clip_instrument_note_expression`, `launcher_instrument_note_expression` |
| Time | `arranger_automation_time_selection`, `arranger_clip_note_lane_time_selection`, `arranger_clip_note_time_selection`, `arranger_clip_time_selection`, `arranger_note_lane_time_selection`, `arranger_note_time_selection`, `clip_automation_time_selection`, `comp_region_time_selection`, `launcher_clip_note_lane_time_selection`, `launcher_clip_note_time_selection`, `launcher_note_time_selection` |
| Comp Region | `comp_region` |
| Automation Lane | `automation_lane` |
| Layer, Drum Chain, Zone, Module, Modulator | `layer`, `drum_chain`, `zone`, `module`, `modulator` |
| Item | `automation_pattern`, `chain_slot`, `clip_editor_automation_layer_selection`, `clip_editor_note_layer_selection`, `component`, `curve_point`, `note_pattern`, `panel_item`, `remote_controls_page`, `track_editor_automation_layer_selection`, `track_editor_note_layer_selection`, `zone_group` |

Seen in use (Bitwig 6.1.4): an arranger clip, automation clips included, is `arranger_clip` (Bitwig picks the menu by where the selection lives, not by the clip's content); a note in the detail editor is `arranger_clip_instrument_note` or `launcher_clip_instrument_note`; an audio event is `arranger_clip_audio_note` (or its launcher counterpart); a time selection in an arranger clip is `arranger_clip_time_selection`. Kinds with no right-click menu (such as `automation_lane`) never show items.

The framework logs each kind the first time its menu or toolbar dropdown opens (`selection menu opened: <kind> ...`, `selection toolbar menu opened: <kind>`), so a later Bitwig's kinds can be read from a Debug session's log.

## ActionContext

What the action was invoked on. Every getter except `widget()` returns `null` when it doesn't apply, including for shortcuts and the Commander (`menu()` is `null` then).

| Method | Returns |
|---|---|
| `menu()` | The `Menu` the item was chosen from: the specific one (`selection("track")`, not `SELECTION`), or `null`. |
| `widget()` | The widget the action was run from (Bitwig's widget object): the clicked widget for a right-click menu, the panel that built a toolbar dropdown; for a shortcut or the Commander, the project window, or the widget it is bound on with `.bindOn`. Use it as the parent of a dialog the action opens. |
| `device()` | The clicked device (Bitwig's device object). |
| `parameter()` | The clicked parameter (Bitwig's parameter object). |
| `selectionKind()` | The kind of selection the menu was opened on (its ID). |
| `selection()` | The view's selection object the menu was opened on (not for toolbar dropdowns, which act on the current selection). |
| `slot()` | The clicked empty clip slot (Bitwig's slot object). |
| `item()` | The clicked browser item (Bitwig's object for it). |
| `items()` | The browser items selected when the menu opened. |

Bitwig's objects are returned as `Object`; a mod built with the mod kit casts them to the Bitwig class it expects (`bitwig.obf...`). The context is captured when the menu opens, so it stays correct if the selection changes before the item is chosen.

`ActionContext`'s static methods and `withWidget` are the framework's own way of building contexts; mods only read contexts.

## Behaviour and failures

- `declare`, `onRun` and `visibleWhen` run on Bitwig's UI thread: keep them short.
- An exception from mod code is logged (`[action-menu-composition-framework] ...`, through `bml.api.Bml`) with the mod and action ID, and contained: the menu still opens, without that item if `visibleWhen` failed.
- A registration whose descriptor is missing or invalid, a provider class that can't be loaded or a provider that throws is logged and that mod's actions are skipped; other mods are unaffected. An action without `onRun`, or with an ID already used, is logged and skipped.
- A failure inside one of the framework's own hooks while Bitwig runs is logged and contained: that menu opens with Bitwig's entries only.
- The framework names no Bitwig internals outside its hooks and compiled code, so Patchbay checks all of them when it prepares the framework. On a Bitwig version where one can't be found, preparation stops (it fails closed) instead of starting with a menu silently missing.
- Turning the framework off removes every mod action and menu item. It stores nothing in projects; Bitwig drops the mods' rows from its shortcut settings at its next save.

