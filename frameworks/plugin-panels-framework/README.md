# Framework: Plug-in Panels

Replaces the generic parameter page of a VST2, VST3 or CLAP plug-in in the device chain with a layout of Bitwig's own widgets: knobs, dropdowns, toggles, value text, parameter rows, output meters and envelope displays. A layout is a small JSON file that names the plug-in and lists its controls. Plug-ins without a layout keep Bitwig's page.

- Mod ID: `plugin-panels-framework`
- Built against Bitwig 6.1.4.
- Needs Framework: Actions and Menus 0.6.0 (for Generate Parameter Sheet, Generate Theme Sheet and Save Panel from Clipboard, rows in a plug-in device's right-click menu) and Patchbay loader 0.36.0-dev.11 or later.
- Development version: the layout format may still change.

## What it changes

When a plug-in is loaded, Bitwig shows a toolbar over the plug-in's parameter list: the Parameters and Programs tabs (the Programs tab only for a plug-in with programs), the sidechain and outputs buttons (only when the plug-in has a sidechain input or several outputs), a parameter search field, and at the right the **container button** (the arrow), which shows the plug-in's own FX container, where other plug-ins and devices can be stored inside it. With a matching layout, the **Parameters** tab shows the layout instead of the list, at the top of the device body, and the device takes the layout's width. Typing in the search field shows Bitwig's own list, filtered, in place of the layout, and clearing the text brings the layout back.

**toolbar** (on the layout) chooses which toolbar pieces stay:

- `true` or absent: Bitwig's whole toolbar.
- An object, each key `true` (default) or `false`: `pages` (the Parameters and Programs tabs; without them the device always shows the layout), `sidechain` (the button that opens the sidechain input page), `outputs` (the button that opens the outputs page), `search` (the search field; without it Bitwig's list can't be shown). No other keys. The toolbar row stays, the container button at its right.
- **The Parameters tab stays while `sidechain` or `outputs` stays.** Those buttons open pages of the same page switcher as the Parameters tab, so `"pages": false` with either of them kept would leave no way back to the panel: the Parameters tab is then kept (and the log says so once); only the Programs tab goes. To remove the Parameters tab, set `"pages": false, "sidechain": false, "outputs": false`. (The sidechain and outputs buttons show only for a plug-in with a sidechain input or several outputs; the rule holds either way.)
- `false`: no toolbar row, every piece above left out; the container button moves to the right end of the panel's title line (the line with the plug-in's name and the page chooser). When the layout has no title line (`"title": false` and one page), a line is made for the button alone. The panel then starts at the top like a native device.
- `"search": false` on the layout also leaves the search field out (the same as `"toolbar": { "search": false }`).
- `"position": "right"` in the object: the buttons go in a bar down the right edge of the device body instead of the row, and the panel gets the row's height back. The bar holds Bitwig's own buttons, working as in the row: the Parameters tab, the Programs tab (shown only for a plug-in with programs), the sidechain and outputs buttons (shown only when the plug-in has them), top down, and the container button at the bottom, always. `pages`, `sidechain` and `outputs` choose them as above (the Parameters-tab rule included). There is no search field: `"search": true` (in `toolbar` or on the layout) is an error with `"right"`; `false` is accepted. Further keys, only with `"right"`:
  - `style`: a theme button style for the bar's buttons (default `Borderless`, as Bitwig's device strip on the left);
  - `size`: the buttons' width and height, 12 to 60 layout units (default 21, Bitwig's toolbar buttons);
  - `gap`: the space between buttons, above the first and under the last, and between the panel and the bar, 0 to 20 (default 2);
  - `background`: the bar's own background colour (a theme colour name or `#rrggbb` / `#rrggbbaa`); without it, the body's background (the layout's `background`) shows through;
  - `divider`: `true` draws Bitwig's separator line between the panel and the bar (default `false`);
  - `list`: `true` turns the bar into **mode buttons**: **Layout** (Bitwig's Parameters icon: the layout), **List** (Bitwig's list icon: Bitwig's own full parameter list, with Bitwig's search field above it to filter it) and Bitwig's own Programs, sidechain and outputs buttons. They act as one set of radio buttons: exactly one is lit, clicking one shows it, and switching back works any number of times. Layout and List are two views of Bitwig's Parameters page; the search field shows in List mode only. A device starts in Layout mode whenever its body is built (loading the project or the plug-in): the mode isn't saved. Without `list`, the bar has Bitwig's own Parameters tab and no search field;
  - `order`: the bar's entries from the top, any of `layout` (the Layout button: with `list` ours, without it Bitwig's Parameters tab), `list` (the List button; only with `"list": true`), `programs` (Bitwig's Programs tab, shown only for a plug-in with programs), `sidechain`, `outputs` (shown only when the plug-in has them), `container`, `spacer`; `pages` is short for `layout`, `programs`. Each at most once, except `spacer`, which takes spare height (several share it). Default `["layout", "list", "programs", "sidechain", "outputs", "spacer", "container"]`: the mode buttons at the top, the container button at the bottom. A button left out of `order` isn't shown, except: the container button, always shown (at the end when left out, logged); and the Layout button, put first (logged) when it is left out while another page or the list can show, so there is always a way back to the layout. `pages`, `sidechain` and `outputs` set to `false` hide those buttons as in the row (`pages` hides the Programs tab). Hidden buttons take no space;
  - `align`: `top` (default), `center` or `bottom`: where the stack sits, for an `order` without a `spacer` (an error otherwise; the default order has one).
  The bar adds `size` + `gap` (and with `divider`, 1 + `gap`) to the device's width (on top of the layout's `width`, or of the width from its columns). It stays beside every page (Programs, sidechain, outputs), so the Parameters tab is always one click away, and it follows the layout even for a body opened on another page.
- `"position": "top"` (default): the row. `style`, `size` and `gap` are an error with `"top"`.

**The container button is never removed**: no layout option leaves it out, because it is how Bitwig reaches the plug-in's FX container, which is key to working with plug-ins in Bitwig (presets that hold other devices). Without the search field a filler takes its space, so the button stays at the right.

The toolbar follows the layout only when Bitwig builds the plug-in's body with the layout's panel on the Parameters page. A body opened on another page (Programs, sidechain or outputs, as saved in the project) keeps Bitwig's whole toolbar, so a device is never stuck on a page without its tabs: choose Parameters there to see the panel (the toolbar is then Bitwig's until the body is rebuilt, e.g. when the project is reopened on Parameters).

**Height**: every device in the chain has the same height. A layout that needs more is zoomed down to fit (to at most 60%; below that its bottom is cut off), and zoomed back up when it has room again. Bitwig's log has one line per panel with the height available and the height the layout needs, in layout units (`height available N units, needed M`): design to the available height for a layout at 100%. `"title": false` leaves out the plug-in's name line. The other tabs, and Bitwig's pages for a missing, loading or crashed plug-in, are unchanged.

The controls are Bitwig's own, bound to the plug-in's parameter objects, so dragging, undo, automation recording and modulation work as on Bitwig's own plug-in controls. A double-click on a knob resets the parameter to the plug-in's default, as one undo step; Ctrl-click opens value entry: type a value and press Enter; how it is read is the control's `typing` (below). The value text under a knob is the plug-in's own, asked again shortly after each change when the control's `refresh` is `"settle"`. Dropdowns and toggles use Bitwig's dropdown and toggle button; a change is one adjusting gesture, as a controller makes it.

When a layout can't be used (no match, a parameter the plug-in doesn't have, a file that isn't valid JSON, or any error while building), Bitwig's own page is shown and one line is written to the log.

## Generate Parameter Sheet

Right-click a plug-in device and choose **Generate Parameter Sheet**. It copies a JSON **parameter sheet** of the plug-in, for writing a layout by hand or in a layout builder, and saves it as `<plug-in> <format>.parameters.json` in the `sheets` folder of the framework's folder in Patchbay's settings folder (`mods/plugin-panels-framework/sheets/`; **Settings > Settings Folder** in Patchbay shows where). The copy happens a moment after the menu item (up to 3 seconds), once the plug-in has answered for each parameter's texts at 0 and 1.

```json
{
  "sheet": "plugin-panels-parameter-sheet", "version": 1,
  "plugin": { "name": "ValhallaVintageVerb", "vendor": "Valhalla DSP, LLC", "format": "VST3", "id": "...", "category": "Reverb" },
  "parameters": [
    { "index": 1, "id": "PID0", "name": "Mix", "value": 0.663, "text": "66.3 %", "steps": 0, "step_names": [], "min_text": "0.0 %", "max_text": "100.0 %" }
  ]
}
```

- `plugin`: what a layout's `match` compares (`name`, `vendor`, `format`); `id` is the format's own plug-in ID.
- Per parameter: `index` (for `#N` references), `id` (for `id:` references), `name`; `value`, the current normalised value (0 to 1), and `text`, the plug-in's text for it; `steps` (0: continuous) and `step_names`; `min_text` and `max_text`, the plug-in's texts at 0 and 1 (`null` when it didn't answer). How a parameter behaves beyond that (whether its text follows the value at once, whether typed values work) is checked in Bitwig and set per control in the layout (below).

## Generate Theme Sheet

**A developer tool**, shown only in Patchbay's Debug Mode and to be removed or hidden before release: it regenerates the layout builder's default theme (Bitwig's own) after a Bitwig update. Other themes reach the builder as BTE theme files.

Right-click a plug-in device and choose **Generate Theme Sheet**. It copies a JSON **theme sheet** of the current theme, for a layout builder that draws panels as Bitwig will: every font, colour, knob size, knob style and button style a layout can name, resolved as the framework resolves them when it builds a panel, with Bitwig's standard sizes and the framework's own spacing. It is saved as `theme.sheet.json`, where parameter sheets are saved.

```json
{
  "sheet": "plugin-panels-theme-sheet", "version": 1,
  "bitwig": "6.1.4",
  "ui_scale": 1.5,
  "fonts": {
    "Default": { "typeface": "Lato-Regular.ttf", "family": "Lato", "weight": "regular", "style": "normal", "size": 11, "ascent": 10.4, "descent": 2.7, "height": 13.3 }
  },
  "colors": { "Grey 1": "#1e1e1eff", "Display Background": "#161616ff" },
  "knob_sizes": {
    "small": { "inner_radius": 12, "arc_radius": 17, "arc_width": 2, "center_area_radius": 3, "knob_width": 37, "knob_height": 37, "text_distance": 8 }
  },
  "knob_styles": {
    "default": { "stroke_color": "#...", "fill_color": "#...", "dot_color": "#...", "emboss_highlight_color": "#...", "emboss_shadow_color": "#...", "value_background_color": "#...", "value_color": "#...", "gradient_amount": 0.5, "use_color_for_line": false }
  },
  "button_styles": ["Borderless", "Borderless Bold"],
  "text_colors": { "text": "#...", "separator": "#..." },
  "bitwig_sizes": { "standard": [17, 21, 25, 29, 37, 41], "row_height": 21, "body_width": 170 },
  "framework": { "padding": 4, "layout_gap": 4, "row_gap": 4, "column_gap": 2, "page_gap": 3, "title_gap": 8, "line_gap": 1, "tab_gap": 2, "background_padding": 2, "icon_size": 17, "divider_width": 1, "needle": [64, 40], "bar": [13, 56], "level": [4, 56], "envelope": [120, 50], "min_zoom": 0.6 }
}
```

(Values here are examples; the sheet has the theme's own.) Sizes are in Bitwig's layout units unless stated; pixels = units x `ui_scale`.

- `sheet`, `version`: this format, version 1. `bitwig`: the running Bitwig's version ("" when unknown). `ui_scale`: pixels per layout unit at the moment of copying (Bitwig's UI scale).
- `fonts`: every font of the theme's font table, by the name a layout uses (`"font": "Bold"`). `typeface`: Bitwig's typeface file; `family`, `weight` (`light`, `regular`, `medium`, `semibold`, `bold`) and `style` (`normal`, `italic`) are read from that file name. `size`: the theme's size, in the units `"<name> <size>"` takes (`"Bold 16"` is this typeface at 16). `ascent`, `descent`, `height` (line height): measured as Bitwig draws the font, in layout units; `null` when it couldn't be measured. A font at another size (`"Bold 10"`) is the same typeface: scale `ascent`, `descent` and `height` by the size.
- `colors`: every colour of the theme's colour table, by the name a layout uses (`"background": "Grey 2"`), as `"#rrggbbaa"` (`null` when it couldn't be resolved). A layout can also give any `#rrggbb` / `#rrggbbaa` itself.
- `knob_sizes`: the knob geometry for each knob `size` name (`tiny`, `small`, `normal`, `large`, `huge`; `null` when the theme has none), by Bitwig's own field names: `inner_radius`, `arc_radius` (the value arc), `arc_width`, `center_area_radius`, `knob_width` and `knob_height` (the knob's box), `text_distance`. A knob with a number `size` scales `small`'s proportions to that box.
- `knob_styles`: every knob style a layout can name (`knob_style`), with its colours as `"#rrggbbaa"` by Bitwig's field names, `gradient_amount` and `use_color_for_line`.
- `button_styles`: every button style a layout can name (a toggle's `style`, the toolbar bar's `style`).
- `dropdown_styles`: every dropdown style a layout can name (a dropdown's `style`).
- `text_colors`: `text`, a label's default text colour (the envelope's fallback line colour too); `separator`, a divider's colour.
- `bitwig_sizes`: `standard`, Bitwig's control sizes (17 to 41); `row_height`, the height of toggles, page tabs and Bitwig's toolbar row; `body_width`, Bitwig's own plug-in body width.
- `framework`: the framework's defaults: `padding` around the panel; `layout_gap` between columns and footer items; `row_gap` between a row's items; `column_gap` between a column's items; `page_gap` under the title line; `title_gap` between the name and the page chooser; `line_gap` between a control's label, control and value text; `tab_gap` between page tabs; `background_padding` around an item with a `background`; `icon_size`, an icon label's default size; `divider_width`; `needle`, `bar` and `level` meters' and the `envelope`'s default [width, height]; `min_zoom`, the smallest zoom when a layout is too tall.

Not in the sheet: the natural heights of Bitwig's dropdown and text labels, which follow their font (a label is its font's `height`); measure them in Bitwig's log (`height available N units, needed M`) or give items a `height`.

## Layout files

A panel is a **component** of the framework: one `*.json` file in Patchbay's `components/` folder. Patchbay lists the panels there under the framework, by each panel's `name`, and you choose which to use: several at once, or none. A change of choice applies when Bitwig starts again. The framework ships no panels.

- `"name"` (optional, at the top of the file): the panel's name, as Patchbay lists it. Without it, the file's name.
- **Save Panel from Clipboard**, in a plug-in device's right-click menu, saves a panel copied from the layout builder (**Copy JSON**) as `<name>.json` in `components/` (the panel's `name`, else the plug-in name it matches). It checks the panel first and says why one can't be used. A file of that name is replaced, so saving an edited panel again updates it.
- A chosen file is read again when it changes, so an edited panel applies the next time Bitwig builds the plug-in's Parameters page: reload the plug-in or reopen the project (switching to the Programs tab and back may be enough).
- The chosen panels are tried in the order they were chosen; the first matching layout is used, and the log names any later one that also matches.
- A file holds one layout, or `{"name": ..., "layouts": [ ... ]}`.

```json
{
  "match": { "vendor": "Vendor Name", "name": "Plug-in Name", "format": "CLAP" },
  "width": 300,
  "background": "Grey 1",
  "title_font": "Title",
  "label_font": "Small",
  "columns": [
    {
      "header": "FILTER",
      "header_font": "Uppercase header",
      "background": "Grey 2",
      "items": [
        { "row": [
          { "knob": "Cutoff", "size": "large", "font": "Bold", "value_font": "Number display" },
          { "knob": "id:PID4", "size": "small", "label": "RES" },
          { "knob": "Width", "size": "small", "label": { "icon": "icons/device/stereo.svg", "text": "Stereo width" } }
        ], "gap": 8 },
        { "dropdown": "Mode" },
        { "toggle": "Bypass", "height": 25, "style": "Borderless Bold", "align": "right" },
        { "value": "Drive" },
        { "slider": "#7" }
      ]
    },
    {
      "header": "OUTPUT",
      "divider": true,
      "gap": 6,
      "items": [ { "meter": "output", "style": "needle", "width": 64, "height": 40 } ]
    }
  ]
}
```

**match**: `name` is required: the plug-in's name as Bitwig shows it, compared without regard to case; `"*"` matches every plug-in. `vendor` and `format` (`VST2`, `VST3`, `CLAP`) are optional. Vendor and name are the same for a plug-in's VST3 and CLAP versions, so one layout covers both.

**width**: optional, the device body's width in Bitwig's layout units (Bitwig's own is 170). Default: when every column has a `width`, their sum with the gaps and the padding; otherwise the content's own width, taken once the panel is on screen and then kept. The device keeps its width on every page (Bitwig's Programs, sidechain and outputs pages are laid out within it), so the chain doesn't jump when switching; a page of Bitwig's that needs more than that width is narrowed to it. A width smaller than the content squeezes it: labels then shorten to "Abc...". The height is the device chain's, the same for every device: a layout must fit it, so put controls side by side in rows to keep it short.

**background**: optional, on the layout (the whole device body, toolbar included), a column or any item: one of the theme's colour names (as in a theme's `.bte` file, e.g. `Grey 1` … `Grey 6`, `Panel body`), so it follows the user's theme; or `#rrggbb` / `#rrggbbaa` for a fixed colour. An unknown colour is logged and left out.

**columns**: left to right; each has an optional `header`, `header_font`, `background`, `width`, `padding` and `divider` (`true`: Bitwig's separator line in the gap before the column), and a list of `items`, top to bottom. `"dividers": true` on the layout puts a line between all columns. When any column of a page has a header, every column of that page gets a header line (empty where it has none), so the labels under them line up. A column with a `width` is exactly that wide whatever its items show, and centres its items. Every panel starts with a line showing the plug-in's name, unless the layout says `"title": false`.

**footer**: a list of items in one row under the columns (on a layout, or on each page of a layout with pages), e.g. mode dropdowns. `footer_gap` is the space above it.

**Fonts**: names from the theme's font table, so they follow the user's theme: `Default`, `Small`, `Tiny`, `Bold`, `Bold Smaller`, `Italic`, `Italic Smaller`, `Light Large`, `Section Header`, `Uppercase header`, `Title`, `Title large`, `Number display`, `Menu`, `Tree`, `Console`, `Alert Regular`, `Alert Medium`, `Alert Bold` and others. A size after the name (`"Bold 16"`) uses that font's typeface at that size, so any size is possible; weights and styles come from the theme's typefaces (regular, bold, medium, light, italic). On the layout: `title_font` (the plug-in's name line), `header_font` (column headers), `label_font` and `value_font` (defaults for every item). On a column: `header_font`. On an item: `font` (its label, or a `text` item's text) and `value_font`. An unknown name is logged and Bitwig's default font is used.

**Value text** (`value_format` on a knob or a value item; display only, the plug-in keeps its own values, and Bitwig's own popups still show the plug-in's text):
- `"percent"`: 0 to 100%;
- `{ "range": [0, 200], "decimals": 1, "unit": "%" }`: the value mapped to that range;
- `{ "map": [[0, "Dry"], [0.5, "Even"], [1, "Wet"]] }`: the text of the nearest listed value;
- without it, the plug-in's own text.
Values in a layout are normalised, 0 to 1.

**Pages**: instead of `columns`, a layout can have `"pages": [ { "name": "Main", "columns": [ ... ] }, { "name": "Mod", "columns": [ ... ] } ]`. A chooser on the name line shows one page at a time: `"page_chooser": "tabs"` (default, Bitwig's toggle buttons) or `"dropdown"`. Every page is built with the panel; the chooser shows one and hides the others. A panel starts on the first page.

**Stripe**: `"stripe": true` on the layout draws an accent line along the panel's top edge, across its whole width (under Bitwig's toolbar, or at the top when `"toolbar": false`), 2 layout units thick, in the theme's `Knob Value Color` (the knobs' value arc colour). `{ "color": "Panel Orange", "height": 3 }` sets its colour (a theme colour name or `#rrggbb` / `#rrggbbaa`) and height (1 to 20 layout units); each key is optional, no other keys. The stripe sits above the layout's top `padding` and counts in the panel's height. `false` or no `stripe`: none.

**Mode colours**: a colour for each mode of a parameter (a reverb's mode, a compressor's character), shown live on the parts the layout names:

```json
"mode_colors": { "param": "ReverbMode",
                 "map": { "Concert Hall": "#E8A33D", "Plate": "#4FA3E0" },
                 "apply": ["stripe", "knob_arc", "header", "divider", "background_tint"] }
```

- `param` (required): a parameter reference, as for any item (not `auto:`). A parameter the plug-in doesn't have makes the layout fall back, unless `min_resolved` is under 100: then there are no mode colours (logged). It doesn't count towards `min_resolved`.
- **Which mode**: the name of the parameter's current value: the nearest of the `choices` of the first `dropdown` on the same parameter in the layout (any page or footer), else the parameter's step name. Never the plug-in's value text. A parameter with neither makes the layout fall back.
- `map` (required, not empty): mode name -> its colour, a theme colour name or `#rrggbb` / `#rrggbbaa`, for every target in `apply`; or an object of colours per target, e.g. `"Plate": { "toolbar": "#000000", "knob_arc": "#4FA3E0", "stripe": "#4FA3E0" }` (keys from `apply`'s list, each in `apply`; a target the object leaves out keeps its own colour in that mode). Names match without regard to case. A mode not in the map shows the parts' own colours.
- `mix` (optional): per target in `apply`, the mode colour's share mixed into the part's own colour, 0 to 1 (1: exactly the mode colour), e.g. `"mix": { "toolbar": 1.0 }` to make the bar exactly each mode's colour. Defaults: `background_tint` 0.12, `toolbar` 0.25, every other target 1. A name that isn't one of the modes is logged and never shown; an unknown colour is logged and left out.
- `apply`: the parts that take the mode colour, any of:
  - `stripe`: the stripe (needs the layout's `stripe`);
  - `knob_arc`: every knob's value arc (its other colours stay its `knob_style`'s);
  - `header`: the column header texts;
  - `divider`: the lines between columns;
  - `background_tint`: the layout's `background` with 12% of the mode colour mixed in (needs the layout's `background`);
  - `envelope`: the envelope's line and dots (and its default fill, the line colour faint);
  - `toolbar`: the right-hand bar's `background` with 25% of the mode colour mixed in (needs `"position": "right"` and a toolbar `background`).
  Default: `["stripe", "knob_arc"]` with a stripe, `["knob_arc"]` without. Other names are an error.
- A mode change repaints those parts only; nothing moves or is laid out again.

**Text colours**: `title_color`, `header_color`, `label_color`, `value_color` on the layout, `header_color` on a column, `color` and `value_color` on an item: a theme colour name or `#rrggbb`. Labels always show their whole text.

**fill_height**: `true` on the layout makes the columns as tall as the device body has room for: every column, with its `background` and the dividers between columns, runs from under the title line (or the top) down to the bottom of the body, or down to the `footer`, which stays under the columns at the bottom. The free height goes into each column: under its content, or as the column's `valign` says (`top`, the default: content at the top; `center`; `bottom`: content at the bottom). It changes nothing in the height fit: a layout taller than the chain is still zoomed down (the columns then only as tall as their content). With pages, the shown page fills. Default `false`: columns end at their content.

**Stacking**: a column stacks its items top to bottom, a row puts its items side by side, and rows can hold rows. So a section with two rows of knobs and a row of buttons under them is a column with three rows. Items don't overlap: there are no layers or absolute positions.

**Placement**:
- `align` on a column (`left`, `center`, `right`): all its items. `valign` on a row (`top`, `center`, `bottom`): all its items.
- `align` on an item in a column, `valign` on an item in a row: that item only.
- `gap`: the space between items, in layout units, 0 or more: on a row (between its items), a column (between its items, default 2) or the layout (between columns and between footer items, default 4).
- `padding` on the layout: the space around the panel, one number or `[left, right, top, bottom]` (default 4).
- `padding` on a column or a row (also a row in a `footer`): the space inside its edges, in the same form (default 0). Not on other items: put an item in a row to pad it. It sits **inside** the column's or row's `width`: a column with `"width": 120, "padding": [6, 6, 2, 4]` stays 120 wide, its header and items get 108, and a fixed-width row's equal cells share its width minus its left and right padding. Without a `width`, the padding adds to the content's own size. A row without a `width` in a fixed-width column takes the column's inner width as its width, then its own padding inside that. With a `background`, the colour fills the padding too, and (as on any item) adds its own 2 on every side outside it.
- Knobs in a row line up by themselves: their labels sit on one line and the knobs' centres on one line, whatever the label fonts and knob sizes (the framework measures the row once it is on screen and evens it out). `"align_knobs": false` on a row turns that off.

**Sizes**: `width` and `height` on any item, in Bitwig's layout units (they scale with Bitwig's UI scale), exactly: a dropdown with a width keeps it whatever name it shows. For a knob of a theme size they size the knob, for a needle meter the meter. Bitwig's own controls use 17, 21, 25, 29, 37 and 41.

**Knob cells**: a knob whose `size` is a number is a cell of fixed size: its label, Bitwig's knob at exactly that size, and the value text under it. The cell is as wide as its column (`width` on the column), its share of a row with a fixed width, or its own `width`; the label and value lines are that wide, with the text centred, so a value that gets longer never moves anything. `label_height` and `value_height` on the layout fix the height of those lines, `header_height` the column header line.

**Items**: exactly one kind key, plus optional keys:

| Kind | Shows |
|---|---|
| `knob` | The label, Bitwig's plug-in knob and the plug-in's value text. `size`: a number (a knob cell, above) or one of the theme's knob sizes, `tiny`, `small` (Bitwig's plug-in knob default), `normal`, `large`, `huge`. `knob_style`: one of the theme's knob styles, `default`, `Lighter`, `Lightest`, `Darkest`, `Direct Monitoring`. On a stepped parameter the knob snaps to its steps and the value text shows the step's name; there are no tick marks |
| `dropdown` | The label and Bitwig's dropdown. `style`: one of the theme's dropdown styles (`Default`, `Borderless`, `Borderless (accent)`, `Plain`, `Underline`, `Flat`, ...; Generate Theme Sheet lists them as `dropdown_styles`; not the button styles); the shown value's font and colour come from the style. `choices`: presets, `[["Subtle", 0.2], ["Wide", 1.0]]` (normalised values; picking one sets it, one undo step). Without `choices`: the parameter's step names, or, when the plug-in gives none, its own text for each step. A continuous parameter needs `choices` |
| `toggle` | Bitwig's text toggle button, lit when the parameter is at 0.5 or above; a click sets 0 or 1 (meant for on/off parameters). `style`: one of the theme's button styles (e.g. `Borderless`, `Borderless Bold`) |
| `value` | The label and the plug-in's value text |
| `slider` | Bitwig's own parameter row from its list: name, slider and value |
| `meter` | `"output"`: the device's output level. `style`: `needle` (default, a VU needle), `bar` (Bitwig's VU meter) or `level` (Bitwig's meter widget, one per channel) |
| `text` | Text of the layout's own: `{ "text": "MODULATION", "font": "Bold", "color": "Panel Orange" }` |
| `envelope` | An envelope shape drawn from the plug-in's parameters, read-only (below) |
| `spacer` | `{ "spacer": true }` (no other keys): empty space that takes the spare height of its column, or the spare width of its row; several spacers share it equally; without spare space it takes nothing. With `fill_height`, a column `[knob, knob, spacer, toggle]` has the knobs at the top and the toggle at the bottom (a column with spacers ignores its `valign`). In a row, spare width exists when the row is wider than its items (a row `width`, or a fixed-width column); spacers don't count as cells when a fixed-width row shares its width into equal cells |
| `row` | A list of items, laid out side by side: `{ "row": [ ... ] }`; `gap`, `valign`, `width`, `padding`, `align_knobs` |

**Per-control behaviour** (on a knob; the same keys on the layout set the default for all its controls):

| Key | Values | Default |
|---|---|---|
| `refresh` | `"none"`: the plug-in's value text as Bitwig gets it. `"settle"`: also asked again 150, 500 and 1000 ms after each change, for a plug-in whose text lags or drifts | `"none"` |
| `typing` | `"plugin"`: typed text goes to the plug-in's own text-to-value (Bitwig's own path). `"mapping"`: read in display units and converted by the control's `values`, nothing asked of the plug-in. `"normalised"`: a number from 0 to 1 | `"mapping"` when the control has `values`, else `"plugin"` |

**values** (on a knob): the value mapping for typed values, from the parameter's normalised value (0 to 1) to the value the plug-in displays:

```json
{ "knob": "Decay", "size": 34, "values": { "min": 0.2, "max": 70, "unit": "s", "curve": "log" } }
```

- `min`, `max`: the displayed value at 0 and at 1.
- `unit`: the display unit (`%`, `ms`, `s`, `Hz`, `dB`, `X`, ...). Typed values may leave it out or use a related unit: ms and s convert into each other, Hz and kHz too, and `k` multiplies by 1000 ("8k" is 8000 Hz).
- `curve`: `"linear"` (default); `"log"` or `"exp"` (geometric: value = min x (max / min)^n, for frequencies and times; min and max above 0); `{ "pow": 2 }` (value = min + (max - min) x n^2, any exponent).
- `points`: `[[0, 0], [10, 0.5], [100, 1]]`, pairs of [display, normalised] for any other taper, interpolated between them; instead of `min`, `max` and `curve`.
- `steps`: the number of steps; the typed value snaps to the nearest.
- `normalised`: `true` to also take a bare number from 0 to 1 as a normalised value (otherwise "0.5" is 0.5 in display units).

A typed value outside the range is clamped to it. To find a mapping, read the plug-in's own window at known normalised values (type 0, 0.25, 0.5, 0.75 and 1 with `"typing": "normalised"`); `min_text` and `max_text` in the parameter sheet give the ends.

**Envelopes**: an `envelope` item draws the shape of an envelope from the plug-in's parameters, in the look of Bitwig's own envelope displays, and redraws whenever one of them changes (in the plug-in's window, on a knob, from automation or a preset). It is read-only: change the shape with the parameters' own controls.

```json
{ "envelope": {
    "attack":  { "param": "Amp Attack", "values": { "min": 1, "max": 10000, "unit": "ms", "curve": "log" } },
    "decay":   { "param": "Amp Decay",  "values": { "min": 1, "max": 10000, "unit": "ms", "curve": "log" } },
    "sustain": "Amp Sustain",
    "release": { "param": "Amp Release", "values": { "min": 1, "max": 10000, "unit": "ms", "curve": "log" } }
  }, "scale": "log", "width": 120, "height": 50 }
```

- **Keys** of the `envelope` object, drawn in this order: `delay` (flat at 0), `attack` (rising to full level), `hold` (flat at full level), `decay` (falling to the sustain level, or to 0 without `sustain`), `sustain` (the level held), `release` (falling to 0). `attack` is required. `sustain` needs `decay`; `release` after a `decay` needs `sustain`. So ADSR, AHDSR, DAHDSR, ADS, AD, AHD and AR (`attack` and `release`: held at full level between them) all work. No other keys.
- **Each key** is a parameter reference (as for any item; not `auto:`), or `{ "param": <reference>, "values": <value mapping> }` for a time key (every key but `sustain`). `param` and `values` are its only keys.
- **Widths**: without `values`, a segment is as wide as its parameter's normalised value (0 to 1) relative to the others. With `values` (the same mapping as a knob's, its `unit` `ms` or `s`), it is as wide as its time. Either every time key has `values` or none has. The shape always fills the item's width: segments are scaled to it together.
- **Sustain**: the level is the parameter's normalised value (0 to 1, the item's height); `sustain` takes no `values`. The held level (sustain, or the full level of an AR shape) takes a fifth of the width (all of it when every time is 0).
- `scale`: `"linear"` (default) or `"log"`: each segment as wide as log10(1 + its time in ms), so short times stay visible next to long ones. `"log"` needs `values` on every time key.
- `points`: `false` leaves out the dots on the breakpoints (default `true`).
- Colours, each a theme colour name or `#rrggbb` / `#rrggbbaa`: `color` (the line and dots; default the theme's `Display Waveform`), `fill_color` (the area under the line; default the line colour, faint), `display_color` (the display's background; default the theme's `Display Background`; `"none"` for no background). `background` is as on any item, a box around the display.
- `width` and `height`: the display's size, exactly (default 120 x 50); the shape never changes the panel's size.
- A reference that matches nothing makes the whole layout fall back to Bitwig's page, as for any item, unless the layout sets `min_resolved` (the envelope's references count towards it, one each): then the envelope keeps its size and is drawn empty (its display background only), and the log names the missing references once.

**label**: text that replaces the parameter's name (`"label": "RES"`), `false` for no label, or `{ "icon": "<path>", "text": "<tooltip>", "size": 17 }` to show one of Bitwig's own icons instead (`size` in layout units, default 17), by its path in Bitwig's resources (`icons/device/stereo.svg`); the text becomes its tooltip. Icons are always Bitwig's files, referenced, never copied. On a toggle, the icon goes on the button.

**Parameter references**:
- a name, as in the parameter sheet (without regard to case; when nothing matches exactly, spaces, underscores and hyphens are ignored too, so `PreDelay` finds "Pre Delay");
- several references separated by `|` (`"Dry / Wet|Mix"`): the first that matches is used, for names that differ between formats or plug-in versions;
- `id:<ID>` or the ID itself (`PID…`, from the parameter sheet). IDs differ between formats (VST2, VST3 and CLAP number parameters differently); names usually don't;
- `#N`: the Nth parameter in the parameter sheet (`index`);
- `auto:knob`, `auto:list`, `auto:toggle`: the next parameter the layout doesn't already show that is continuous, has three or more step names, or has two steps. An automatic item with no parameter left is left out (logged); every other reference that matches nothing makes the whole layout fall back to Bitwig's page, unless the layout sets `min_resolved`: the share of its references (percent) that must match. Then the items whose parameter is missing are left out (logged), and the layout falls back only when fewer match.

## Schema summary

For a layout builder: every key a layout file can hold. A file holds one layout, or `{"layouts": [ ... ]}`; either may start with `name` (text: the panel's name).

| Level | Keys |
|---|---|
| Layout | `match` (`name` required, `vendor`, `format`); `columns` and `footer`, or `pages`; `width`, `padding`, `gap`, `dividers`, `background`; `title`, `toolbar` (`true`, `false` or `{pages, sidechain, outputs, search, position, style, size, gap, background, divider, list, order, align}`), `search`, `page_chooser`; `title_font`, `header_font`, `label_font`, `value_font` and their `_color`; `header_height`, `label_height`, `value_height`, `footer_gap`, `fill_height`; `min_resolved`; `refresh`, `typing` (defaults for every control); `stripe`, `mode_colors` |
| Page (in `pages`) | `name`, `columns`, `footer` |
| Column | `items`; `header`, `header_font`, `header_color`; `width`, `padding`, `gap`, `align`, `valign` (with `fill_height`), `divider`, `background` |
| Item | one kind: `knob`, `dropdown`, `toggle`, `value`, `slider`, `meter`, `text`, `row`, `envelope`, `spacer` (`true`, alone); then `label` (text, `{icon, text, size}` or `false`), `font`, `color`, `value_font`, `value_color`, `value_format`; `size` (theme size or a number), `knob_style`, `style`; `choices`; `values`, `refresh`, `typing`; `width`, `height`, `background`, `align`, `valign` |
| Row | `row` (items), `gap`, `valign`, `width`, `padding`, `align_knobs`, `background` |
| `stripe` | `true`, `false`, or `{color, height}` (height 1 to 20, default 2; colour default the theme's `Knob Value Color`) |
| `mode_colors` | `param` (required), `map` (required: mode name -> colour, or -> {target: colour}), `mix` ({target: 0..1}), `apply` (`stripe`, `knob_arc`, `header`, `divider`, `background_tint`, `envelope`, `toolbar`; default `stripe` (with a stripe) and `knob_arc`) |
| `padding` | on the layout, a column or a row: a number (every side) or `[left, right, top, bottom]`, each 0 to 200; inside `width` |
| `values` | `min`, `max`, `unit`, `curve` (`"linear"`, `"log"`, `"exp"`, `{"pow": k}`), `points`, `steps`, `normalised` |
| Envelope item | `envelope` (object: `delay`, `attack` (required), `hold`, `decay`, `sustain`, `release`; each a reference, or `{param, values}` on a time key); `scale` (`"linear"`, `"log"`), `points`, `color`, `fill_color`, `display_color` (also `"none"`); `width`, `height`, `background`, `align`, `valign` |

## Credits

Several parts of this framework are adapted from adoxe (Daniil)'s plug-in cards in "Design: Device Panels", with thanks: knob cells of fixed size with Bitwig's knob at an exact size, double-click reset, the value text that asks the plug-in again after a change, pages shown and hidden in one panel, the panel beside Bitwig's list with search, the panel's top alignment and width from its columns, the accent stripe, and mode colours (his card tints: a theme colour that answers the current mode, and a knob style with a tinted arc).

## Limitations

- Panels come from the panels chosen in Patchbay only; mods can't register panels yet. A newly chosen panel needs Bitwig to start again.
- Bitwig's full list shows only while text is typed in the search field (or with the layout's file removed).
- Double-click on a knob resets to the plug-in's own default. A layout can't set another default; use a preset dropdown for chosen values.
- A layout must fit the device chain's height; it doesn't scroll.
- A layout added or removed while a plug-in is open changes the toolbar only when Bitwig rebuilds the plug-in's body, e.g. after reloading the plug-in.
- The container button can't be removed (by design, above).
- Parameters the plug-in hides or doesn't publish can't be shown.
- Envelopes are read-only, drawn with straight segments (no curve shapes), and show no playing position: a plug-in doesn't report where its envelope is.
- Some plug-ins report their value text late or approximately (ValhallaVintageVerb does, in Bitwig's own list too): give their controls `"refresh": "settle"`.
- Some plug-ins don't convert typed text (ValhallaVintageVerb doesn't, in Bitwig's own list either) or read it only as a normalised number: give their controls `values` (and `"typing": "mapping"`, the default with `values`).
