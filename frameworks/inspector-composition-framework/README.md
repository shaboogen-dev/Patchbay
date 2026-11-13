# Framework: Inspector

Lets a mod add its own blocks to Bitwig's Inspector, and rows of its own to Bitwig's Device block. A mod's block looks like Bitwig's: Bitwig's own block header with the mod's title, and below it the content the mod builds from Bitwig's widgets. A mod can also be told about every block the Inspector builds, Bitwig's and the mods', to change how blocks look or behave.

- Mod ID: `inspector-composition-framework`
- Built against Bitwig 6.1.4.
- Needs Patchbay 0.34.0-dev.6 or later (manifest `registrations`, `bml.api.Bml`).

## Contributing blocks

A mod contributes in three steps.

**1. Register with the framework in `mod.json`:**

```json
"registrations": [
  {
    "id": "inspector",
    "consumer": "inspector-composition-framework",
    "descriptor": "inspector-blocks.properties",
    "jar": "helper.jar"
  }
]
```

The mod's registration jar is merged and translated by the loader like hook code, so the provider can use Bitwig's classes directly.

**2. Add the descriptor to the mod folder** (`inspector-blocks.properties`):

```properties
api.version=1
provider.class=com.example.mymod.MyBlocks
```

**3. Write the provider** in the mod's helper jar. Compile against the framework's helper jar (`"classpath_mods": ["inspector-composition-framework@<version>"]` in `build.json`).

```java
package com.example.mymod;

import bitwig.obf.dWb; // a vertical or horizontal group of widgets (Bitwig 6.1.4 names)
import bitwig.obf.duY; // a text label
import bml.inspector.Blocks;
import bml.inspector.Context;
import bml.inspector.InspectorProvider;

public final class MyBlocks implements InspectorProvider {
    @Override
    public void declare(Blocks blocks) {
        blocks.block("summary", "My Summary")
              .in(Context.TRACK, Context.DEVICE)
              .content(context -> {
                  dWb column = dWb.UNj(2); // a vertical group
                  column.HL(duY.MIO("First row"));
                  column.HL(duY.MIO("Second row"));
                  return column;
              });

        blocks.deviceRows("pages")
              .content(context -> duY.MIO("A row at the end of Bitwig's Device block"));
    }
}
```

The provider class must be public with a public no-argument constructor. The framework calls `declare` once, before the first Inspector panel is built.

## Blocks

`blocks.block(blockId, title)` declares a block of the mod's own and returns a builder:

| Call | Meaning |
|---|---|
| `.in(Context...)` | Required. The Inspector contexts it appears in (below). |
| `.top()` / `.bottom()` | Before Bitwig's blocks, or after them (the default). |
| `.content(BlockContext -> Object)` | Required. Builds the block's content and returns it: a Bitwig widget. Called each time Bitwig builds the Inspector panel. |

`blockId` only needs to be unique within the mod. The block has Bitwig's own header with `title`. Keep titles short, as Bitwig's own are.

How the content looks is up to the mod: the framework puts the widget it returns below the header, as Bitwig puts its own rows there, and changes nothing inside it.

## Rows

`bml.inspector.Rows` builds Bitwig's own Inspector rows for a block's content, bound to values the mod keeps, so a mod with simple parameters needs no Bitwig names. The getter is asked whenever Bitwig shows a row, the setter runs when the user changes it.

```java
blocks.block("my_tool", "My Tool")
      .in(Context.NOTE)
      .content(context -> Rows.column(
              Rows.number(context, "Amount", 0, 100, 50, " %", false, MyTool::amount, MyTool::setAmount),
              Rows.choice(context, "Mode", MODES, MyTool::mode, MyTool::setMode).row(),
              Rows.button(context, "Transform", MyTool::transform)));
```

| Call | Row |
|---|---|
| `Rows.column(rows...)` | The rows, one under another, with Bitwig's spacing. A block's `content` returns this |
| `Rows.number(context, label, min, max, resetTo, suffix, signed, get, set)` | A label and a number field for whole numbers, as Bitwig's Velocity field: drag it, type a number, double-click for `resetTo`. `suffix` follows the number; `signed` shows a plus sign above zero. A range from below zero to above zero draws the value from zero, as Bitwig's Pan and Timbre fields draw from the centre |
| `Rows.steps(context, label, labels, resetTo, get, set)` | A label and a number field over an ordered list (`String[] labels`), for values that have an order, such as note lengths: it shows the label at its place, dragging up moves towards the end of the list, typing a label chooses it (case and spaces ignored), double-click for `resetTo`. `get` and `set` use the place in the list from 0. A list of names with no order is a `choice` |
| `Rows.choice(context, label, options, get, set)` | A label and a dropdown; `get` and `set` use the option's position from 0. Returns a `Choice`: `row()` is the row, and `options(...)` gives the dropdown new options, when they depend on another choice |
| `Rows.button(context, label, onClick)` | A button across the row |
| `Rows.number(..., set, enabled)`, `Rows.steps(..., set, enabled)`, `Rows.choice(..., set, enabled)` | The same rows, greyed out and not changeable while `enabled` is false, as Bitwig's own disabled fields and dropdowns; the row keeps its value. `enabled` is asked whenever a row of the block changes, as a `when` condition is |
| `Rows.button(context, label, onClick, enabled)` | The same, greyed out and not clickable while `enabled` is false, as Bitwig's own disabled buttons. `enabled` is asked whenever the block's rows are read again |
| `Rows.text(context, label, text)` | A label and a line of text the user can't change, as Bitwig's Inspector shows read-only text, for showing something about the selection. `text` (a `Supplier<String>`) is asked when the row is made and whenever the block's rows are read again (`Rows.refresh`, or a change in another row of the block); null shows as empty. A null `label` gives the text the whole row |
| `Rows.when(context, condition, rows...)` | These rows, shown only while `condition` is true. Hidden rows take no space. Groups can sit inside each other |
| `Rows.refresh(context)` | Reads every row's value and every condition of this block again, after a change from outside its rows |
| `Rows.onUserChange(context, listener)` | Runs `listener` after the user changes any row of this block, once its rows and conditions have been read again. It runs during Bitwig's own handling of the change, so post anything that edits the project |

The rows work in any block. They run on Bitwig's UI thread, as all a mod's callbacks do.

**Showing rows by a choice.** A condition is asked again whenever a row of the same block changes, so this needs nothing else:

```java
.content(context -> Rows.column(
        Rows.choice(context, "Mode", MODES, MyTool::mode, MyTool::setMode).row(),
        Rows.when(context, () -> MyTool.mode() == 0,
                Rows.number(context, "Amount", 0, 100, 50, " %", false, MyTool::amount, MyTool::setAmount))))
```

Hiding a block's body (as Inspector: Minimise Blocks does) works apart from this: a hidden row stays hidden when the body is shown again, and a hidden body stays hidden when a condition changes.

## Device rows

`blocks.deviceRows(rowsId)` declares rows of the mod's own at the end of Bitwig's Device block in the Device Inspector (inside Bitwig's block, not a block of their own). Builder: `.content(BlockContext -> Object)` (required), as for blocks. Rows of different mods are separated by a one-unit divider in Bitwig's block background.

## Contexts

| `Context` | ID | Where |
|---|---|---|
| `TRACK` | `track` | Track Inspector (every track type) |
| `DEVICE` | `device` | Device Inspector |
| `ARRANGER_CLIP` | `arranger-clip` | Arranger clip |
| `LAUNCHER_CLIP` | `launcher-clip` | Launcher clip |
| `SLOT` | `slot` | Clip launcher slot |
| `SCENE` | `scene` | Scene |
| `NOTE` | `note` | Note Inspector, as a whole |
| `NOTE_SECTION` | `note.section` | Note Inspector, next to Bitwig's NOTE block (`top`: before it, `bottom`: after it) |
| `NOTE_OPERATORS` | `note.operators` | Note Inspector, next to Bitwig's OPERATORS block |
| `NOTE_EXPRESSIONS` | `note.expressions` | Note Inspector, next to Bitwig's EXPRESSIONS block |
| `MODULE` | `module` | Grid module |
| `MODULATOR` | `modulator` | Modulator |
| `CUE_MARKER` | `cue-marker` | Cue marker |

Not supported: Bitwig's **Time Range** block. It is a standalone widget of its own rather than one of the Inspector's blocks, so mods can't add blocks there and block listeners aren't told about it.

## Where blocks appear

The framework decides only position. Blocks at `top` come before Bitwig's blocks, blocks at `bottom` after them; within each, in **alphabetical order** by title (case-insensitive; equal titles are ordered by mod ID, then block ID). In the note contexts the same order applies next to the Bitwig block named. Device rows come in order of mod ID, then in the order the mod declared them. How a mod structures its content is up to the mod. Blocks can't be moved by the user.

## BlockContext

| Method | Returns |
|---|---|
| `context()` | The `Context` being built (`DEVICE` for device rows; for the note contexts the one the block was placed with). |
| `panel()` | Bitwig's Inspector panel being built (Bitwig's object). |

Bitwig's objects are returned as `Object`; a mod built with the BML mod kit casts them to the Bitwig class it expects (`bitwig.obf...`).

## Built blocks

`blocks.onBlockBuilt(listener)` asks to be told about every block the Inspector builds, Bitwig's own and the mods', for a mod that changes how blocks look or behave. The listener (`Consumer<BuiltBlock>`) is called right after a block's body is filled, while Bitwig is still building the block, each time Bitwig builds a panel. A mod's block is passed only when its content was built. Listeners run in the order of their mods' IDs.

| `BuiltBlock` | Returns |
|---|---|
| `context()` | The `Context` the panel shows; for a mod's block in a note context, the one it was placed with. Null for one of Bitwig's blocks in a panel the framework has no context for |
| `title()` | The title in the block's header |
| `bitwigBlock()` | True for one of Bitwig's own blocks |
| `modId()`, `blockId()` | The mod and block ID of a mod's block; null for Bitwig's |
| `panel()` | Bitwig's Inspector panel being built (Bitwig's object) |
| `widget()` | The block (Bitwig's widget group): its header first, then the widgets of its body. The listener may change it |

A listener that throws is logged once and contained; the block is shown as it is.

## Track block height

When a mod adds a block to the Track Inspector, Bitwig's Track block keeps a minimum height (1256 layout units) with one track selected, so it doesn't shrink when other blocks share the Inspector. With several tracks selected (TRACKS (n)), or with every widget below its header hidden, it keeps Bitwig's own height. Without a mod's block in the Track Inspector, the framework leaves the Track block alone.

## Behaviour and failures

- `declare` and `content` run on Bitwig's UI thread: keep them short. `content` runs each time Bitwig builds the panel.
- An exception from `content`, or content that isn't a Bitwig widget, is logged (`[inspector-composition-framework] ...`, through `bml.api.Bml`) with the mod and block ID, once, and contained: that block isn't shown (no header without a body), every other block is.
- A registration whose descriptor is missing or invalid, a provider class that can't be loaded or a provider that throws is logged and that mod's blocks are skipped; other mods are unaffected. A block without a title, context or content, or with an ID the mod already used, is logged and skipped.
- A failure inside one of the framework's own hooks is logged once and contained: the Inspector shows Bitwig's blocks as usual.
- The note contexts find Bitwig's NOTE, OPERATORS and EXPRESSIONS blocks by their titles as Bitwig 6.1.4 shows them in English.
- The framework names no Bitwig internals outside its hooks and compiled code, so the loader checks all of them when it prepares the framework. On a Bitwig version where one can't be found, the loader stops preparing (it fails closed) instead of starting with a block silently missing.
