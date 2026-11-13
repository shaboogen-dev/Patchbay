# Framework: Browser

Hooks into Bitwig's browsers so mods can transform them. Its API lets a mod hide any part of the side browser and the pop-up browser's suggestions while it is loaded, add file kinds, and offer a **view**: a bespoke browser that takes those parts over while the user has it chosen, and may place the side browser's results itself or show its own widget in the side browser panel. The framework draws nothing of its own. With no mod using it, Bitwig behaves as it ships.

- Mod ID: `browser-framework`
- Built against Bitwig 6.1.4.
- Needs **Framework: Settings** 1.0.0 or later (the Settings section, on the Mods page).

## Settings

Settings → **Mods** → **Browser** → **Browser view**: Standard, or a view a mod offers. The section shows once a mod offers a view. Choosing a view that places the results or has its own content rebuilds the browser at once (its source, search and filters stay).

The choice of view is the framework's, because only one view can run the side browser at a time: the user picks among every view mods declare, and Standard (Bitwig's browser) is the default. Hiding parts is a mod's job (Browser: Hide Parts has the plain switches), and a view's own options are its mod's.

## Features

Every part is a `Feature`, on or off. Each row of the side browser has two: whether it is shown and whether its functions work, so a row can be hidden while its shortcuts still work. The value in effect is, first that wins:

1. the active view's **claim**;
2. **off** while any loaded mod hides it (`Browser.hide`); which mods hide it doesn't matter, and a mod that isn't loaded hides nothing;
3. the stored value: on by default (Bitwig as it ships), except `ALL_SOURCES_BUTTON`.

**Search suggestions** (per browser). Typing in a search field shows one suggestion at the end of the field, which Tab carries out. Bitwig shows the first kind that matches, in this order; a kind that is off finds nothing, so the next kind gets its turn.

| Feature | What it suggests |
|---|---|
| `SUGGEST_SOURCES` | "Go to" a collection or source whose name matches |
| `SUGGEST_INSTRUMENT_KINDS` | "Go to" a keyword such as Organ or Kick: Drums, Bass, Synths, Keys or Other Instruments, with the category set to the keyword |
| `SUGGEST_FILTER_VALUES` | "Set Category / Creator / Vendor / Tags to" a value, and the Drum Loop category |
| `SUGGEST_OTHER_CATEGORIES` | "Go to" a category the current results don't offer: Everything with that category |
| `SUGGEST_EVERYTHING` | "Go to Everything" when there are fewer than 10 results |

**Side browser:**

| Feature | What it covers |
|---|---|
| `QUICK_SOURCES_VISIBLE` | the row of source icons and the line under it |
| `QUICK_SOURCES_ACTIONS` | F1–F9 and their sub-source shortcuts |
| `SOURCE_HEADER_VISIBLE` | source icon, previous/next arrows, Favourites star, sub-sources; for instrument sources the title, star and instrument-kind buttons |
| `SOURCE_HEADER_ACTIONS` | previous/next source, show favourites only |
| `FILTERS_VISIBLE` | the grey filter area above the results |
| `FILTERING` | the filter shortcuts, the suggestions that set a filter, the results' drill-down arrows |
| `FILTER_AUTO_SHOW` | the filter area appears whenever a filter applies |
| `DETAILS_VISIBLE` | the selected item's type, category, vendor and collections |
| `DETAILS_ACTIONS` | moving keyboard focus to the pane |
| `FAVOURITE_ACTIONS` | toggle favourite, remove from collections (a result's context menu is unaffected) |
| `ALL_SOURCES_BUTTON` | Bitwig's All Sources button at the left of the search field, for a view that hides the Quick Sources toolbar; the field takes the rest of the row. **Off** by default; built with the side browser, so a view that turns it on or off rebuilds the browser at once |

`SIDE_PANE` is deprecated and builds nothing: a view places the results itself (`View.results`, below). A view that claims `SIDE_PANE` on without a results slot fails when chosen, so the browser keeps its source rows.

**Kinds.** A mod can add one of Bitwig's file kinds to the side browser with `Browser.kind` (below): Everything shows files of that kind, and it is listed as a kind in Bitwig's source picker (and wherever a view lists the side browser's kinds, such as the Pre-5.0 view's BY KIND), with the name and tile the mod declares (Projects, with Browser: Enable Project File Filter). A project saved with such a kind chosen opens on Everything when the mod isn't loaded.

- The All Sources shortcut works while either the Quick Sources or the source shortcuts are on, since both rows have a button for it. The button of `ALL_SOURCES_BUTTON` works whatever they are.
- Shortcuts that move keyboard focus into a row (the filter columns, the details pane) also need that row on screen.
- **Filters:** the filter area is shown when `FILTERS_VISIBLE` and `FILTERING` are both on, or, with `FILTER_AUTO_SHOW` on, while any filter applies. Turning `FILTERING` off clears the side browser's filters once; filters Bitwig sets itself afterwards (a restored project, a snapshot, a preset drill-down) stay, and auto-show still shows them.
- Hidden rows stay in place: Bitwig's own layout closes the gap.

## Views

A view is a way of running the browser that a mod offers. The user picks the active view in Settings (**Browser view**: Standard, then each view by name). Only the active view's **claims** apply: while it is active, a claimed feature has the view's value, whatever mods hide. Parts the view doesn't claim follow the mods that hide them. If the view's activation fails, or its mod is turned off, its claims lapse and the other values apply again; the choice is kept for when the mod returns.

**Results slot.** A view may place the side browser's results column itself (`View.results`): while the view is active, each side browser hands its results column to the view's factory as it lays out its rows, and shows the widget the factory returns in its place, normally one that holds the column beside or under the view's own widgets (the Pre-5.0 view's pane, header and type column). The slot (`ResultsSlot`) gives the side browser (`cFV`), its session (`Jp`), the column, `onFiltersChanged(listener)` (a filter of the session got or lost a value, or was offered or not) and `fail(reason)`. A factory that throws or returns something that isn't a widget makes the view's claims lapse and the results show alone. A launch marker is stored before the factory runs and cleared once its widget is on screen 3 s later; a launch that finds it (the last one crashed, hung or drew nothing) sets Browser view back to Standard.

**Content.** A view may show its own widget in the side browser panel (`View.content`). While the view is active, each window's Browser panel shows the view's widget in place of Bitwig's browser content; Bitwig's browser stays built underneath, hidden, so its search session, filters, cue player and library keep working. Settings is the only switch between Bitwig's browser and the view: choosing the view, or another view, rebuilds the browser (its source, search and filters stay), and the panel shows the chosen one. The framework draws nothing of its own in the panel.

The widget comes with a `Panel`: the panel's helpers for what a browser does, each through Bitwig's own code.

| Helper | What it does |
|---|---|
| `preview(file)`, `stopPreview()`, `previewPlaying()`, `playFrom(beat)` | Bitwig's cue player of that panel (the one its own preview player uses): loads the file and plays it once loaded; the same file again plays it again |
| `onPreviewLoaded(listener)` | called when a file the panel asked for has loaded |
| `previewDurationBeats()`, `playheadBeats()` | the loaded file's length in beats as the cue player reports it; where playback is, worked out from when it started and the tempo it plays at (the cue player doesn't report a position) |
| `stretchMode()`, `setStretchMode(mode)` | Bitwig's preview stretch preference: `STRETCH_SYNC_TO_PROJECT`, `STRETCH_ORIGINAL_SPEED`, `STRETCH_RAW` (the setting the preview player's own button changes) |
| `insertAtPlayCursor(file, done)` | imports an audio file as a clip on the selected track's arranger lane at the play cursor, with Bitwig's clip import; `done` gets null when Bitwig reports it complete, else the reason |
| `insertWithBitwig(file)` | inserts a file as Bitwig's browser inserts a chosen result (presets, clips, devices) |
| `dragFiles(files, dragEvent)` | Bitwig's file drag, from the drag event Bitwig gave the widget |
| `contentLocations()` | Bitwig's content locations (Settings > Locations): folder and name |
| `projectFolder()` | the open project's folder, or null |
| `revealInFileManager(file)` | Bitwig's own Show in Explorer / Finder |
| `searchIndex(kinds, text, limit, cancelled, results)` | searches Bitwig's library index on its library thread: files of the given kinds (Bitwig's kind names: `samples`, `presets`, `clips`, `multisamples`, `devices`, ...) in the content locations, matching the text with Bitwig's search syntax, at most `limit`, in the order the library finds them (not ranked); the results arrive once, on the UI thread |
| `searchLibrary(kinds, text, limit, cancelled, results)` | as `searchIndex`, for every item of Bitwig's library, those without a file included, in every place Bitwig indexes (content locations, plug-in folders, packages): `plugins` (VST2, VST3 and CLAP as Bitwig's plug-in scan found them), `devices` (Bitwig's own) and the file kinds |
| `listLibrary(kinds, limit, cancelled, results)` | every item of the given kinds, at most `limit`: `searchLibrary` without search text (all plug-ins, to group by vendor) |
| `insert(hit)` | inserts a library item as Bitwig's side browser inserts a result on Enter or a double-click: a plug-in or device on the selected track, a preset, a clip, a file |
| `dragLibraryItems(hits, dragEvent)` | Bitwig's drag of library items, as dragging results out of its browser starts one |
| `projectItems(cancelled, results)` | the open project's tracks, and on each its devices and clips, in Bitwig's order, read from Bitwig's project model on the UI thread in short slices (a clip's notes and audio events aren't walked; at most 50,000 project objects are looked at); the list arrives once, on the UI thread; each call lists the project as it is then, so call again to refresh |
| `dragProjectItems(items, dragEvent)` | starts Bitwig's drag with project items, carrying Bitwig's own objects for them as its own drags of tracks, devices and clips do; items Bitwig no longer has are left out |
| `focus()`, `showPanel()` | gives the widget the keyboard focus (Bitwig's own focus request, as its search field asks for it); shows the window's Browser panel if it is hidden |
| `searchField(widget)`, `focusSearch()` | declares the view's own search field (one of Bitwig's widgets in the view's content, or null); gives it the keyboard focus, false when there is none |
| `state()` | a map for the view's own state, the same for every panel of a project while the project is open, so state survives the rebuilds |
| `onDispose(action)` | runs when the panel goes (its window is rebuilt or closed) |

The panel retains and activates the cue player while it previews, and lets it go when disposed. Bitwig's own preview player is hidden with the rest of Bitwig's browser.

**Search field.** While a view's content shows, Bitwig's own search field is hidden under it, so a mod that focuses "the side browser's search field" would focus a hidden field. A view that has a search field of its own declares it with `Panel.searchField(widget)`. Such a mod calls `bml.browser.ViewSearch.focus(sideBrowser)` first, with the side browser (`cFV`) it was about to focus: true means the view's declared field now has the focus; false (Bitwig's browser shows, or the view declared none) means it focuses Bitwig's field as before. A mod that should work with or without this framework reaches it without `requires`: `Class.forName("bml.browser.ViewSearch", false, <its own class>.getClassLoader()).getMethod("focus", Object.class)`, treating `ClassNotFoundException` (the framework not installed or not turned on) and `NoSuchMethodException` (an older framework) as false.

## Using the framework from a mod

**1. Register with the framework in `mod.json`:**

```json
"registrations": [
  {
    "id": "browser",
    "consumer": "browser-framework",
    "descriptor": "browser.properties",
    "jar": "helper.jar"
  }
]
```

**2. The descriptor** (`browser.properties`, in the mod's `package/` folder):

```properties
api.version=1
provider.class=com.example.mymod.MyBrowser
```

**3. The provider**, compiled against this framework's `helper.jar` (`"classpath_mods": ["browser-framework@0.3.0-dev.1"]`):

```java
import bml.browser.Browser;
import bml.browser.BrowserProvider;
import bml.browser.Feature;
import bml.browser.Features;
import bml.browser.Scope;

public final class MyBrowser implements BrowserProvider {
    @Override
    public void declare(Browser browser) {
        Features features = browser.features();
        if (features.get(Feature.FILTERING, Scope.SIDE)) { /* ... */ }
        features.onChange(() -> { /* a value in effect may have changed */ });

        browser.hide(Feature.DETAILS_VISIBLE, Scope.SIDE, true); // while this mod is loaded; false gives it back

        browser.view("compact", "Compact")
               .claims(Feature.FILTERS_VISIBLE, false)
               .claims(Feature.FILTER_AUTO_SHOW, false)
               .onActive(context -> { /* the user chose this view */ })
               .onInactive(context -> { /* the user chose another */ });

        browser.view("sidebar", "Sidebar")
               .claims(Feature.SOURCE_HEADER_VISIBLE, false)
               .results(slot -> new MySidebar(slot)); // one of Bitwig's widgets holding slot.results()

        browser.view("files", "Files")
               .content(panel -> {
                   MyFilesWidget widget = new MyFilesWidget(panel); // one of Bitwig's widgets (a dve)
                   panel.onDispose(widget::close);
                   return widget;
               });
    }
}
```

- `Browser`: `hide(feature, scope, hidden)` holds a feature off while the mod is loaded (nothing stored; the mod keeps its own options in its own settings), `hides(feature, scope)`, `rebuild(why)` rebuilds the side browser for a change that applies only when it is built (a view's own options for its slot), `features()`, `view(...)`, `kind(...)`.
- `Features`: `get(feature, scope)` (the value in effect), `userValue(feature, scope)` (the stored value), `controlledBy(feature, scope)` (the claiming view's name, or null), `onChange(listener)`. `set(feature, scope, on)` is deprecated: it stores the value in the framework, so it stays applied after the mod that set it is turned off; it still works (refused while the active view claims the feature). Use `Browser.hide`.
- `Scope` is `SIDE`, `POPUP` or `BOTH`. It matters only for per-browser features (`Feature.perBrowser()`, the suggestions); the others apply to the side browser.
- `kind(fileKind, name, tileResource, iconName)`: adds one of Bitwig's file kinds (its id, such as `"projects"`) to the side browser: Everything shows files of that kind (from the first filter list on), the file kind filter offers it, and it is listed as a kind with this name.
  - `tileResource`: the kind's tile, a raster tile as Bitwig draws its own kinds' (`images/library/<id>.webp`): a resource in your `mod.json`, either one of Bitwig's tiles by reference (`"kind": "bitwig"`, `"path": "images/library/templates.webp"`) or your own WebP file; never a copy of Bitwig's files. An SVG (`icons/...`) isn't drawn: Bitwig shows its missing-image mark.
  - `iconName`: the small icon where Bitwig draws the file kind by name (the file kind filter's values), one of Bitwig's icon names such as `"browser/document.svg"`; null keeps Bitwig's.
  - The first mod to declare a file kind wins; an id that isn't one of Bitwig's file kinds is skipped (logged).
- `View`: `claims(feature, value)`, `claims(feature, scope, value)`, `onActive(context)`, `onInactive(context)`, `content(factory)`, `results(factory)` (the results slot, above), `active()` (true while the view is chosen and working). `ViewContext` gives `viewId()`, `features()` and `panels()` (the live panels showing the view's content, the most recently built last; a mod's action uses it to show and focus one).
- `ContentFactory.create(panel)`: returns the view's widget for one panel, one of Bitwig's widgets, new each time; called while the side browser is built, once per window's Browser panel.
- `Panel`: the helpers above, and `viewId()`, `live()`, `content()` (the widget), `browser()` (Bitwig's side browser).
- Records: `ContentLocation(folder, name)`; `IndexHit(file, name, kind, key, vendor, category, format, deviceType)`: `file` is null for an item without one (only from `searchLibrary` and `listLibrary`), `key` is Bitwig's own path for the item (a file's path; for a plug-in its binary and its ID in it), `vendor` a plug-in's vendor, `category` a device's or plug-in's category in Bitwig's index, `format` `vst2`, `vst3` or `clap` for a plug-in, `deviceType` `audio`, `note`, `instrument` or `note_detector` for a device or plug-in, each empty where it doesn't apply (`new IndexHit(file, name, kind)` makes a file's hit); `ProjectItem(id, kind, name, track, depth)`: `kind` is `track`, `device` or `clip`, `track` the name of the track it is on, `depth` how many listed items it is inside (0 for a top-level track), `id` the framework's handle for it.
- Call on Bitwig's UI thread; `declare` is called once, before any browser session builds its filter list (so before the first browser is built), and the chosen view is active from then on.

## Rules

- A failure in the framework's hooks is logged and falls back to Bitwig's own behaviour.
- A provider that can't be loaded or throws in `declare` is logged and skipped; other mods are unaffected. A view whose `onActive` throws loses its claims.
- A view's content or results factory that throws or returns something that isn't a widget, and any callback a view hands a panel that throws (`onDispose`, `onPreviewLoaded`, `done`, `results`), make the view's claims and content lapse (logged): the browser is rebuilt with Bitwig's own content.
- A panel helper that Bitwig refuses or that fails is logged and returns `false`, null, `NaN`, `-1` or an empty list; it never throws.
- The framework keeps only the chosen view and the stored values of the deprecated `set`, in `mods/browser-framework/settings.properties` in BML's settings folder (**Settings > Settings Folder** in BML's window shows where). What a mod hides isn't stored.
