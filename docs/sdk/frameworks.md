# Using the frameworks

BML has ten frameworks. Each is a mod that patches one area of Bitwig once, so other mods, themes and devices can add to that area without hooks of their own. This page covers seven of them; Framework: Browser, Framework: Plug-in Panels and Framework: Piano Roll are described in their own `README.md`.

**Use the frameworks.** Where a framework covers what a mod does, the mod must go through it, never through hooks of its own: actions and menu items, Settings controls, Inspector blocks, themes and native device registration. Hooks of a mod's own on Bitwig's builders for these are the wrong approach in BML: every such mod patches the same builders again and places its entries its own way, while the framework patches them once for all mods and puts every mod's entries in one consistent order.

Five frameworks take registrations or calls from mods' Java code:

| Framework | Mod ID | Version to compile against | Package | Lets a mod add |
|---|---|---|---|---|
| Framework: Actions and Menus | `action-menu-composition-framework` | `0.8.0-dev.1` | `bml.actions` | Bitwig actions (shortcuts, Commander) and items in Bitwig's menus |
| Framework: Settings | `preferences-composition-framework` | `1.0.0` | `bml.preferences` | Controls in Bitwig's Settings |
| Framework: Inspector | `inspector-composition-framework` | `0.3.0-dev.1` | `bml.inspector` | Blocks in Bitwig's Inspector, rows in its Device block |
| Framework: Panels | `panel-management-framework` | `0.2.0-dev.1` | `bml.panels` | Panels (views) in Bitwig's layout, scales for Bitwig's own panels, and layouts of Bitwig's panels |
| Framework: Notifications | `notifications-framework` | `0.3.0-dev.1` | `bml.notifications` | Toasts, messages and running tasks in Bitwig's notification centre; layouts of its rows, watchers of its entries and a toast handler |

**Framework: Browser** (`browser-framework`, package `bml.browser`, compile against `0.3.0-dev.1`) lets a mod hide parts of Bitwig's browsers while it is loaded (`Browser.hide`), add file kinds, and offer a view of the side browser, which may place the results column itself (`View.results`) or show the mod's own widget in the Browser panel, with helpers for previews, inserting at the play cursor, file drags, content locations, searching and listing Bitwig's library (plug-ins and devices included) with inserting and dragging its items as Bitwig's browser does, and listing the open project's tracks, devices and clips. `Features.set` and `Feature.SIDE_PANE` are deprecated. Its `README.md` is the reference.

**Framework: Piano Roll** (`piano-roll-framework`, package `bml.pianoroll`, compile against `0.1.0-dev.11`) and **Framework: Plug-in Panels** (`plugin-panels-framework`, compile against `0.3.0-dev.1`) are also described in their own `README.md`. Piano Roll's `PianoRoll.notification` is deprecated: post a toast with Framework: Notifications instead. A plug-in panel is a component of Plug-in Panels: a `.json` file in Patchbay's `components/` folder, chosen in Patchbay.

Two take content that needs no code; they are covered at the end of this page: **Framework: Themes** (`bte-theme-loader`, themes) and **Framework: Nitro Devices** (`custom-device-registrar`, native devices, modulators and Grid modules).

Compile against the versions in this table; a framework README's own examples may name an older version. Use a framework whenever it covers what the mod does: the mod then needs no hooks for it, and its entries sit with other mods' entries in one consistent order. Each framework's `README.md` (in its release ZIP and its installed folder) is the full reference for its API; this page shows the parts every mod uses.

## How a mod registers

The same three steps for Actions and Menus, Settings, Inspector, Panels and Browser, and for Notifications' row layouts, watchers and toast handler (posting a notification needs no registration):

**1. A registration in `mod.json`**, naming the framework as `consumer`:

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

The four keys are all required, and no others are allowed. `id` is the registration's ID within the mod. `descriptor` and `jar` are paths inside the mod's installed folder. A mod may register with several frameworks: one entry each, with different IDs and descriptors.

**2. The descriptor file**, in the mod's `package/` folder (so it lands next to `mod.json`):

```properties
api.version=1
provider.class=com.example.mymod.MyActions
```

**3. The provider class** in the mod's source: public, with a public no-argument constructor, implementing the framework's provider interface. The framework calls its `declare` method once.

To compile it, put the framework's `helper.jar` on the classpath: in `build.json`, `"classpath_mods": ["<framework-id>@<version>"]`; in a manual build, the jar itself ([building.md](building.md)). A mod using two frameworks lists both, `"classpath_mods": ["preferences-composition-framework@1.0.0", "action-menu-composition-framework@0.5.1-dev.1"]`, and has two registrations and two descriptors. All providers and any hooks live in the one `helper.jar`.

The registration jar is translated by BML like hook code, so a provider can use Bitwig's classes directly (`bitwig.obf...` with the mod kit).

A framework a mod registers with is one of its prerequisites ([mods.md](mods.md#prerequisites)): ticking the mod in the Patchbay window ticks the framework, and a mod whose framework is turned off or isn't installed is an error that stops Load until it is fixed. To ask for a minimum framework version, add it to the mod's `requires` list.

## Rules every framework follows

- `declare`, and every callback the mod hands in, run on Bitwig's UI thread: keep them short.
- An exception from mod code is logged with the mod's ID and contained: that entry is left out, everything else works.
- A descriptor that is missing or invalid, a provider that can't be loaded or throws in `declare`: the mod's entries are skipped and logged; other mods are unaffected.
- The framework decides only **where** entries go: after Bitwig's own, in a fixed order (alphabetical by label or title, details per framework). What the entries are and how they look is the mod's choice.
- IDs only need to be unique within the mod, except action IDs (below).

## Actions and menus (`bml.actions`)

Descriptor `actions.properties`; provider interface `bml.actions.ActionProvider`; `declare(Actions actions)` is called once, after Bitwig has registered its own actions.

```java
import bml.actions.ActionProvider;
import bml.actions.Actions;
import bml.actions.Menu;

public final class MyActions implements ActionProvider {
    @Override
    public void declare(Actions actions) {
        actions.category("my_mod", "My Mod");
        actions.action("my_mod_tidy", "Tidy Up")           // action ID, label
               .in(Menu.DEVICE, Menu.selectionToolbar("device"))
               .category("my_mod")
               .icon("tidy_icon")                         // a resource ID from mod.json
               .onRun(context -> Tidy.run(context.device()));

        var sub = actions.submenu("notes", "Note Tools")
               .in(Menu.selection("arranger_clip_instrument_note"));
        sub.action("my_mod_merge", "Merge Notes").onRun(context -> Notes.merge(context.selection()));

        actions.bitwigAction("paste_and_push")            // one of Bitwig's own actions, by Bitwig's ID
               .in(Menu.selection("arranger_clip_time_selection"));
    }
}
```

- `action(actionId, label)` creates a real Bitwig action: it can get a shortcut and appears in the Commander. Builder: `.in(Menu...)` (none: shortcut and Commander only), `.onRun(ActionContext -> void)` (required), `.menuLabel(...)`, `.visibleWhen(ActionContext -> boolean)`, `.category(id)`, `.icon(resourceId)`, `.menuOnly()` (a menu row with no Bitwig action), `.bindOn(Class...)` (below).
- **Action IDs are stored by Bitwig** in the user's shortcuts: never change one after release, and prefix it with the mod ID, hyphens turned into underscores (mod `my-mod`: `my_mod_tidy`). The framework refuses a second action with an ID already used.
- Category: use one of Bitwig's (the README lists them) when the action belongs with Bitwig's own there, e.g. `mixer` or `arranger`; declare one of the mod's own with `actions.category(...)` when a mod has several actions that belong together. Bitwig's Settings → Shortcuts page shows which category each of Bitwig's own actions is in; in the jar, the class that declares an action ID names its category ID next to it.
- Menus: `Menu.DEVICE`, `Menu.PARAMETER`, `Menu.SELECTION` / `Menu.selection(kind)`, `Menu.SELECTION_TOOLBAR` / `Menu.selectionToolbar(kind)`, `Menu.CLIP_SLOT`, `Menu.BROWSER_ITEM`, `Menu.PLAY` (the dropdown beside the transport's Play button, after Bitwig's Play options; `ActionContext` gives only `widget()` there). The selection kind IDs and Bitwig's category IDs are listed in the framework's README.
- `ActionContext` gives what the action was run on: `device()`, `parameter()`, `selectionKind()`, `selection()`, `slot()`, `item()`, `items()`, `menu()`, `widget()` (the parent for a dialog). Getters return `null` when they don't apply, including for shortcuts and the Commander. Bitwig objects come as `Object`; cast them.
- Order in a menu: after Bitwig's entries and a separator; submenus first, then items, each alphabetical by label.
- **Shared keys:** a user can put one key on several actions (Bitwig's own keymap does, and the mod Shortcuts: Share a Key adds an **Add** button to the shortcut dialog for it). Bitwig runs one action per key press, looking outwards from the focused widget, so an action bound on the project window (the default) comes before Bitwig's window-level actions on the same key. An action meant to share a key should use `.bindOn(widgetClass...)`: it is bound only on those Bitwig widgets and their subclasses, and anywhere else the key goes to Bitwig as if the action didn't exist. The framework's README has the details.
- **Recommended: register only what makes sense on its own.** BML's own mods add an action (a shortcut and Commander entry) or a menu entry only where it is useful without the mod's own window: an action that just opens or drives a control in the mod's window belongs in that window. This keeps Settings > Shortcuts and the Commander clean. It is a convention, not something the framework enforces.

## Settings (`bml.preferences`)

Descriptor `preferences.properties`; provider interface `bml.preferences.PreferenceProvider`; `declare(Preferences preferences)` is called once, when Bitwig first builds its Settings.

```java
import bml.preferences.Page;
import bml.preferences.PreferenceProvider;
import bml.preferences.Preferences;
import bml.preferences.Section;

public final class MySettings implements PreferenceProvider {
    @Override
    public void declare(Preferences preferences) {
        preferences.checkbox("open_window", "Open plug-in window on insert")
                   .in(Section.of("behaviour/device"))                       // one of Bitwig's sections
                   .value(MyMod::openWindow, MyMod::setOpenWindow);

        Section mine = preferences.section(Page.USER_INTERFACE, "my_mod", "My Mod"); // a new section
        preferences.number("height", "Minimum height", 20, 200).wholeNumbers()
                   .in(mine)
                   .value(MyMod::height, MyMod::setHeight);
    }
}
```

- Controls: `checkbox` (`BooleanSupplier`, `Consumer<Boolean>`), `choice` and `dropdown` (option position from 0: `IntSupplier`, `IntConsumer`), `number` and `slider` (`DoubleSupplier`, `DoubleConsumer`; `.wholeNumbers()` rounds). Every control needs `.in(Section)` and `.value(getter, setter)`.
- Every control except a checkbox is a labelled row. As on Bitwig's own pages, a section's labels share one column, 185 px wide or as wide as its widest label, so the section's widgets line up.
- `.decibels()` on a number field or slider shows the value as Bitwig's decibel fields do, for example `-60.0 dB`; the value is in decibels.
- **The mod keeps its values.** The framework stores nothing: the getter is called whenever Bitwig shows the control, the setter when the user changes it. Store values with `MOD.settings()` ([writing.md](writing.md)), or in a file inside a `preserve` directory resource. The default is whatever the getter returns before anything is stored.
- Places: `Section.of(sectionId)` for one of Bitwig's sections (IDs and headings in the framework's README; open Bitwig's Settings to see what each section already holds, and put a control next to related ones), `preferences.section(page, sectionId, title)` for a new section, `preferences.page(pageId, label)` for a page of the mod's own. Prefix new section and page IDs with the mod ID, hyphens turned into underscores, as for action IDs (`preferences.section(Page.RECORDING, "my_mod", "My Section")`).
- **Recommended: the shared Mods page.** BML's own mods put their options on one page, "Mods", below Bitwig's pages in the Settings sidebar, so users find every mod's options in one place. Each mod declares the page with exactly this ID and label, then a section of its own on it, titled with the mod's name without its area prefix ("Track Settings" for "Arranger: Track Settings"):

  ```java
  Page mods = preferences.page("mods", "Mods");        // the shared page: same ID and label in every mod
  Section mine = preferences.section(mods, "my_mod", "My Mod");
  ```

  This is a convention, not a requirement: Bitwig's own sections, a new section on one of Bitwig's pages, or a page of the mod's own all work the same way.
- A mod can't choose where a new section goes. It can't be placed next to one of Bitwig's sections (e.g. directly below Recording > Auto-arm): new sections always come after all of Bitwig's sections on the page, alphabetical by title.

## Inspector blocks (`bml.inspector`)

Descriptor `inspector-blocks.properties`; provider interface `bml.inspector.InspectorProvider`; `declare(Blocks blocks)` is called once, before the first Inspector panel is built.

```java
import bitwig.obf.dWb; // a vertical or horizontal group of widgets
import bitwig.obf.duY; // a text label
import bml.inspector.Blocks;
import bml.inspector.Context;
import bml.inspector.InspectorProvider;

public final class MyBlocks implements InspectorProvider {
    @Override
    public void declare(Blocks blocks) {
        blocks.block("summary", "My Summary")             // block ID, title shown in Bitwig's block header
              .in(Context.TRACK, Context.DEVICE)
              .content(context -> {
                  dWb column = dWb.UNj(2);                // a vertical group with room for two widgets
                  column.HL(duY.MIO("First row"));        // add a label
                  column.HL(duY.MIO("Second row"));
                  return column;
              });
        blocks.deviceRows("pages")                        // rows at the end of Bitwig's Device block
              .content(context -> duY.MIO("A row of my own"));
    }
}
```

- `block(blockId, title)`: `.in(Context...)` (required), `.top()` or `.bottom()` (default), `.content(BlockContext -> Object)` (required). `content` returns one of Bitwig's widgets, built each time Bitwig builds the panel; the framework puts it under Bitwig's own block header with `title`.
- Contexts: `TRACK`, `DEVICE`, `ARRANGER_CLIP`, `LAUNCHER_CLIP`, `SLOT`, `SCENE`, `NOTE`, `NOTE_SECTION`, `NOTE_OPERATORS`, `NOTE_EXPRESSIONS`, `MODULE`, `MODULATOR`, `CUE_MARKER`.
- `BlockContext`: `context()` (the `Context` being built) and `panel()` (Bitwig's Inspector panel, as `Object`).
- Order: `top` blocks before Bitwig's, `bottom` blocks after them, each alphabetical by title.
- `bml.inspector.Rows` builds Bitwig's own number fields, dropdowns and buttons bound to values the mod keeps, so a block of simple parameters needs no Bitwig names: `Rows.column(Rows.number(context, "Amount", 0, 100, 50, " %", false, get, set), Rows.button(context, "Apply", run))`. `Rows.when(context, condition, rows...)` shows rows only while a condition holds, asked again whenever a row of the block changes, so a block can show only what the current choice uses. `Rows.button(context, label, onClick, enabled)` greys a button out while `enabled` is false, and `Rows.onUserChange(context, listener)` runs after the user changes any row. The framework's README has the calls.
- `Rows.number(context, label, min, max, resetTo, suffix, signed, get, set)` is a whole-number field; when its range runs from below 0 to above 0, the value is drawn from 0, as Bitwig's Pan and Timbre fields draw from the centre.
- `Rows.steps(context, label, labels, resetTo, get, set)` (and with a final `enabled`) is a number field over an ordered list, `String[] labels`, for values that have an order, such as note lengths: it shows the label at the current place, dragging up moves towards the end of the list, typing a label chooses it (case and spaces ignored), and double-click resets to `resetTo`. `get` and `set` use the place in the list from 0. A list with no order is a `Rows.choice`.
- `Rows.text(context, label, text)` is a read-only line of text (`Supplier<String>`), read again whenever the block's rows are (`Rows.refresh`, or a change in another row); a null `label` gives the text the whole row.
- `blocks.onBlockBuilt(listener)` tells a mod about every block the Inspector builds, Bitwig's own and the mods', for a mod that changes how blocks look or behave. Inspector: Minimise Blocks puts its fold arrows on blocks this way. The listener gets a `BuiltBlock` right after the block's body is filled, while Bitwig is still building the block:
  - `context()`;
  - `title()`;
  - `bitwigBlock()`;
  - `modId()` and `blockId()`, both null for Bitwig's blocks;
  - `panel()`;
  - `widget()`, Bitwig's widget group with the header first, then the body. The listener may change it.

  Listeners run in mod-ID order each time Bitwig builds a panel. A mod's block is passed only when its content was built.
- The Inspector framework needs no other framework.

## Panels (`bml.panels`)

Framework: Panels lets a mod add its own panels, called **views**, to Bitwig's layout. Each view gets a button in the bottom bar, a **Show** action and a **scale** (75% to 200%, set in Settings or from the button's right-click menu). On its own, the framework changes nothing in Bitwig: everything comes from the views, panels and layouts mods register. It needs Framework: Actions and Menus and Framework: Settings.

A view is registered like the other frameworks' entries: a registration with `"consumer": "panel-management-framework"`, a descriptor (`api.version=1`, `provider.class=...`) and a provider class.

```java
import bml.panels.PanelProvider;
import bml.panels.Panels;

public final class MyPanels implements PanelProvider {
    @Override
    public void declare(Panels panels) {
        panels.view("my_view", "My View")
              .icon("my_icon")                                // a resource from mod.json
              .content(context -> MyWidget.create(context));  // one of Bitwig's widgets
    }
}
```

- **Where a view appears:** with a layout mod such as Panel: Streamlined Views, in its view stack; without one, as one more tab of each mode's lower panel.
- **Icons:** required, a resource of the mod: one of Bitwig's SVG icons by reference (`kind: bitwig`) or the mod's own 19 x 19 SVG.
- **Bitwig's own panels:** `panels.bitwigPanel(viewId, label, panelClass)` gives one of Bitwig's panels a scale wherever Bitwig shows it (Panel: Scale Each Panel does this for the main panels).
- **The scale setting:** a view or panel gets a Panel Scale control in Settings unless the mod calls `.scaleSetting(false)` and places it itself; `bml.panels.PanelScale` reads and sets the scale (`choices()`, `get`, `set`), applied at once in every window.
- **Layout mods** declare Bitwig panels they place themselves (`panels.bitwigView(...)`) and a layout policy (`panels.policy(...)`) that builds the view stack or side columns. Only this framework patches Bitwig's display-profile builders.

The framework's `README.md` is the full reference: `ViewContext`, saved values, `replaces("mixer")`, scales, layout policies and which display profiles show views.

## Notifications (`bml.notifications`)

Framework: Notifications lets a mod tell the user something the way Bitwig does: a **toast** (Bitwig's transient notification), a **message** in Bitwig's notification centre (the dot at the top right of the window), or a running **task** there with a status line, a progress bar and Cancel. Plain Java, no Bitwig names, safe from any thread. It needs no other mod.

Posting needs no registration: add `{ "id": "notifications-framework", "min_version": "0.3.0-dev.1" }` to the mod's `requires`, compile against the framework's `helper.jar`, and call `bml.notifications.Notifications`.

```java
import bml.notifications.Notifications;
import bml.notifications.Severity;
import bml.notifications.Task;

Notifications.toast("My Mod: nothing selected");                  // a reply to a click
Notifications.message(Severity.ERROR, "Export failed", "The disk is full.")
        .action("Open Folder", () -> openFolder(dir))             // a button; runs on Bitwig's UI thread
        .post();                                                  // or Notifications.post(severity, title, text)

Task task = Notifications.task("Render Stems", () -> job.cancel()); // null instead: no Cancel button
task.status("Rendering drums...");
task.progress(0.25);                                              // 0 to 1; progressUnknown() for the busy bar
task.finish();                                                    // always, however the work ends
```

- Which to use: a toast for a reply to what the user just clicked; a task for a job that takes a while; a message for an outcome to find later (done, failed, needs something installed).
- Messages: `Severity` `INFORMATION`, `WARNING` or `ERROR`; `.closable(boolean)`, `.dismissAfterSeconds(int)` (0, the default, keeps it), `.quiet(boolean)` (a quiet message doesn't open the centre; information is quiet by default). The returned `Message` can `replace(title, text)` and `dismiss()`.
- Tasks: the entry appears at once; while no progress is known it shows Bitwig's busy bar. `status`, `progress`, `progressUnknown` and `finish` return at once; Cancel runs the mod's callback once, and stopping the work is the mod's. Bitwig doesn't show a task that ends within 300 ms. To say how a task ended, post a message after `finish()`.
- Write a toast as "My Mod: what happened". A mod's toast handler may show it another way (Notifications: Pop-ups in the Centre makes it a centre message titled with the text before the colon).
- **Watching the centre:** a provider implementing `bml.notifications.CentreProvider` gets `declare(Centre centre)`; `centre.watch(CentreWatcher)` is told `shown(entry)` and `ended(entry)` for every entry the centre shows, from every mod and from Bitwig (`CentreEntry`: task or message, title, text, severity, cancelled, shown and ended times, `replaced()`). Every watcher is told. Notifications: History is built on it.
- **Taking toasts:** `centre.toasts(ToastHandler)` gets every toast before Bitwig shows it, as `Toast(text, fromController)`; return true to take it. One handler is used (the last by mod ID). Notifications: Pop-ups in the Centre is built on it.
- **Message rows outside the centre:** `Notifications.messageRow(severity, title, text, onClose)` gives Bitwig's own message row (laid out by the styler in use) for a mod's own views, never posted.
- **Row layouts:** a mod can lay out every task and message row in the centre, Bitwig's own included (Notifications: Tidy Task Rows does). It registers with `"consumer": "notifications-framework"`, provider interface `bml.notifications.RowProvider` (or `CentreProvider`, whose `Centre` extends `Rows`), with a descriptor of its own naming (`notification-centre.properties` in BML's own mods); `declare(Rows rows)` sets `rows.taskRows(...)` and `rows.messageRows(...)`, which get each row as Bitwig built it, with its parts under plain names, to move, replace, hide or add parts. A styler runs on Bitwig's UI thread each time Bitwig builds a row. One styler of each kind is used: when several mods declare one, the last by mod ID wins. A styler that throws is logged and not called again in the session. Without a styler, rows stay as Bitwig draws them.

The framework's `README.md` is the full reference: every call, the parts of a row and the text looks.

## Themes (`bte-theme-loader`)

Framework: Themes applies the theme the user chooses in Patchbay.


A theme changes Bitwig's colours, and optionally its icons and images, with no code. Make it a theme of this framework, not a mod with hooks on Bitwig's colours or resources.

- A theme is a `.bte` file in BML's `components/` folder, or a folder there holding one `.bte` file and optionally `overlays/`. BML recognises it from the framework's `detect` patterns and names it after the file or folder. Nothing else is needed.
- A folder can hold a `mod.json` to set the theme's name, author and description; it then decides everything about that theme. Renaming a theme with no `mod.json` makes it a new theme, so the user chooses it again.
- Its colours are a `.bte` file in the format of Berikai's [Bitwig Theme Editor](https://github.com/Berikai/bitwig-theme-editor): one `<Bitwig colour name>: <colour>` per line.
- Optional overlays replace Bitwig's icons and images by path (`overlays/icons/browser/device_instrument.svg` replaces Bitwig's file of that path). Use your own artwork: never ship copies of Bitwig's files, edited or not.
- One theme is active at a time; the user picks it under the framework's **Themes** tab in Patchbay. Restart Bitwig after changing a theme.

The framework's `README.md` has the optional manifest, the `.bte` keys it supports and how overlays are matched.

### Packages with no `mod.json`

Any framework with a component group can recognise its packages the same way. It declares a `detect` pattern for the group's resources in its manifest:

```json
"component_groups": {
  "themes": {
    "name": "Themes",
    "selection": "single",
    "required_resources": { "theme": { "kind": "file" } },
    "detect": { "theme": "*.bte", "overlays": "overlays/" }
  }
}
```

Each key is a resource slot and each value a pattern: a file name with `*` and `?` (case-insensitive), or `name/` for a folder. They look directly inside a folder in `components/`, and a file directly in `components/` counts when the group has one file pattern and needs nothing else. `MANIFEST-V3.md`, beside the loader, has the rules.

## Native devices (`custom-device-registrar`)

Framework: Nitro Devices makes Bitwig load native devices, modulators and Grid modules that don't ship with Bitwig (`.bwdevice`, `.bwmodulator`, `.bwmodule`), so they appear in Bitwig's browser like its own. It needs no code: the user lists the folders that hold them.

- The folders are chosen in Patchbay's **Device Folders** tool. At each start the framework scans them (and their subfolders), reads each file's UUID with Bitwig's own reader and registers it under its file name. A device author ships the files; nothing else is needed.
- The list is kept in `mods/custom-device-registrar/config.json` in the settings folder (**Settings > Settings Folder** in Patchbay shows where), as `"folders"`. An update of the framework leaves it alone. The older `directories` and `registrations` keys still work but are deprecated.
- A listed folder that is missing or unreadable is an error on Patchbay's Errors page and stops Load. Restart Bitwig after changing the folders or their files.

The framework's `README.md` has the config format and what the framework does with each file.
