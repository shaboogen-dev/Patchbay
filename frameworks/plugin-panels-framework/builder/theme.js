/*
 * Plug-in Panels layout builder: Bitwig's theme as the preview draws it, in layout units.
 *
 * The framework takes fonts, colours, knob sizes and widget heights from Bitwig's live theme
 * (body.jl_(): font table, colour table, knob and button styles). Bitwig builds that theme in code
 * whose names and values are encrypted in its jar, so they can't be read offline. Every value here
 * is marked with where it comes from:
 *   [framework]  a constant in the framework's code (exact)
 *   [measured]   measured from the user's Bitwig 6.1.4 screenshot of the VintageVerb panel
 *                (Internal results, vv2; about 1.44 screen pixels per layout unit)
 *   [theme file] Bitwig's resources/themes/default.theme (6.1.4); this file names typefaces Bitwig
 *                no longer ships (OpenSans), so its sizes may be outdated
 *   [approx]     a best guess; replace with the real value when the framework can export it
 *   [imported]   from default-theme.js: Bitwig's default theme, as the framework's Generate Theme Sheet
 *                exported it (exact); a BTE theme (.bte) then recolours it by name
 * The values above are the fallback when default-theme.js is missing.
 */
(function (root) {
  "use strict";

  const THEME = {
    // Fonts: [typeface weight, style, size in layout units]. The typeface Bitwig 6 ships is Lato
    // (resources/fonts: Lato Regular, Medium, Semibold, Light, Italic) [measured: glyph shapes].
    family: "Lato, 'Open Sans', 'Segoe UI', Arial, sans-serif",
    monoFamily: "'Source Code Pro', Consolas, monospace",
    fonts: {
      "default": { weight: 600, size: 11.2, src: "theme file" },
      "small": { weight: 400, size: 9, src: "theme file" },
      "tiny": { weight: 400, size: 8, src: "theme file" },
      "bold": { weight: 700, size: 11.2, src: "theme file" },
      "bold smaller": { weight: 700, size: 10.5, src: "theme file" },
      "bold bigger": { weight: 700, size: 12, src: "theme file" },
      "bold title": { weight: 700, size: 13, src: "theme file" },
      "menu": { weight: 600, size: 13, src: "theme file" },
      "console": { weight: 400, size: 12, mono: true, src: "theme file" },
      "italic": { weight: 400, size: 11.2, style: "italic", src: "approx" },
      "italic smaller": { weight: 400, size: 10.5, style: "italic", src: "approx" },
      "light large": { weight: 300, size: 16, src: "approx" },
      "section header": { weight: 700, size: 11.2, src: "approx" },
      "uppercase header": { weight: 700, size: 10, upper: true, src: "approx" },
      "title": { weight: 700, size: 13, src: "approx" },
      "title large": { weight: 700, size: 16, src: "approx" },
      "number display": { weight: 400, size: 12, mono: true, src: "approx" },
      "tree": { weight: 600, size: 11.2, src: "approx" },
      "alert regular": { weight: 400, size: 12, src: "approx" },
      "alert medium": { weight: 500, size: 12, src: "approx" },
      "alert bold": { weight: 700, size: 12, src: "approx" },
    },

    // Colours by theme name [approx unless marked]. "#rrggbb" values in a layout are exact.
    colours: {
      "grey 1": "#1e1e1e", "grey 2": "#262626", "grey 3": "#2e2e2e", "grey 4": "#3a3a3a", "grey 5": "#484848",
      "grey 6": "#5a5a5a", "panel body": "#3a3a3a", "panel orange": "#ff7d0f",
      "display waveform": "#ff7d0f", "display background": "#262626", "knob value color": "#ff7d0f",
    },
    defaults: {
      body: "#3a3a3a",        // device body [approx: the screenshot's body is the layout's own #3A3A3A]
      text: "#c8c8c8",        // a label's text colour when the layout gives none [approx]
      title: "#d6d6d6",       // [approx]
      divider: "#686868",     // Bitwig's separator line [measured]
      knobArc: "#ff7d0f",     // a plug-in knob's value arc [measured]
      knobTrack: "#5e5e5e",   // its track ring [measured]
      knobTop: "#838383",     // its body, top to bottom gradient [measured]
      knobBottom: "#535353",
      knobEdge: "#303030",    // its outline [measured]
      pointer: "#f2f2f2",     // its pointer [measured]
      dropTop: "#606060",     // dropdown face, top to bottom [measured]
      dropBottom: "#535353",
      dropBorder: "#282828",  // [measured]
      dropText: "#e8e8e8",    // [measured, approx]
      buttonOff: "#4c4c4c",   // toggle button, off [approx]
      buttonOn: "#ff7d0f",    // toggle button, on [approx]
      search: "#1c1c1c",      // the toolbar's search field [measured]
      strip: "#3c3c3c",       // the device's left strip [measured]
      frame: "#494949",       // the device frame [measured]
      displayWaveform: "#ff7d0f",   // the theme's "Display Waveform": an envelope's line [approx]
      displayBackground: "#262626", // the theme's "Display Background": an envelope's display [approx]
    },

    // Sizes in layout units.
    sizes: {
      // Bitwig's plug-in knob widget at each theme size, width x height. [measured] tiny, small, large:
      // the framework's row-aligner lines in the user's Bitwig log (3 Nov, TAL-Chorus-LX at UI scale 150%:
      // 31x25, 43x38 and 67x59 px). normal, huge [approx: between and beyond them]. small is Bitwig's default.
      knob: {
        tiny: { w: 31 / 1.5, h: 25 / 1.5 }, small: { w: 43 / 1.5, h: 38 / 1.5 }, normal: { w: 36, h: 32 },
        large: { w: 67 / 1.5, h: 59 / 1.5 }, huge: { w: 53, h: 47 },
      },
      row: 17,              // w3.MRY, Bitwig's row height: toggle buttons and page tabs [approx: its smallest standard size]
      dropdown: 20,         // a dropdown's natural height [approx; VintageVerb's footer sets 20]
      dropdownWidth: 80,    // a dropdown's natural width with no width set [approx]
      slider: [170, 17],    // Bitwig's parameter row: its list's width (Bitwig's body is 170), row height [approx]
      toolbar: 23,          // the plug-in toolbar row [measured: Bitwig's log, "toolbar and the layout 213, layout alone 190"]
      strip: 28,            // the device's left strip [measured: screenshot, 40 px at about 1.44 px per unit]
      available: 213,       // the device body's height in the user's chain [measured: "height available 213 units"]
    },
    uiScale: 1.5,           // the user's Bitwig UI scale [measured: needle meter 64 x 40 units painted at 96 x 60 px]
    // A plug-in knob's drawing, as fractions of its box (Bitwig's default geometry, box 37: radii 12 and 17,
    // track 2, pointer 3 [framework: KnobGeometry.DEFAULT]; ring and pointer widths [measured]).
    knob: { inner: 12 / 37, outer: 17 / 37, ring: 2.8 / 34, pointer: 2.1 / 34 },
    // Per theme knob size, Bitwig's geometry (imported only): {inner, arc, arcWidth, center, w, h}.
    knobGeometry: {},
    // Knob styles by name (imported only): {arc, track, body, edge, pointer, gradient}.
    knobStyles: {},
    buttonStyles: null, // the theme's button style names (imported only)
    fontNames: null,    // the theme's font and colour names as written in a layout (imported only)
    colourNames: null,
    // The framework's own constants [framework: Panels.java], also in a theme sheet's "framework".
    framework: {
      padding: 4, layout_gap: 4, row_gap: 4, column_gap: 2, page_gap: 3, title_gap: 8, line_gap: 1, tab_gap: 2,
      background_padding: 2, icon_size: 17, divider_width: 1, needle: [64, 40], bar: [13, 56], level: [4, 56],
      envelope: [120, 50], min_zoom: 0.6,
    },
    imported: null, // {bitwig, ui_scale} of an imported theme sheet, else null
  };

  const PRISTINE = JSON.stringify(THEME);
  const WEIGHTS = { light: 300, regular: 400, medium: 500, semibold: 600, bold: 700 };
  const isNum = (v) => typeof v === "number" && isFinite(v);
  const isColour = (v) => typeof v === "string" && /^#([0-9a-f]{6}|[0-9a-f]{8})$/i.test(v);

  /** Whether a parsed JSON value is a theme sheet (Generate Theme Sheet, version 1). */
  function isSheet(o) {
    return o !== null && typeof o === "object" && o.sheet === "plugin-panels-theme-sheet";
  }

  /**
   * The theme in use, in place (every module holds this object): the built-in values, then the default
   * theme (default-theme.js, Bitwig's own), then a BTE theme over it (parseBte). null: the default alone.
   */
  function apply(bte) {
    const fresh = JSON.parse(PRISTINE);
    for (const k of Object.keys(THEME)) delete THEME[k];
    Object.assign(THEME, fresh, { apply, isSheet, parseBte, applySheet });
    const base = isSheet(root.PPB_DEFAULT_THEME) ? root.PPB_DEFAULT_THEME : null;
    if (base) layer(base, null);
    THEME.imported = base ? { bitwig: base.bitwig || "", builtIn: true, name: "" } : null;
    if (bte && bte.colors) layerBte(bte, base);
    return THEME;
  }

  /** Developer path: a theme sheet (Generate Theme Sheet) over the default, to check one before it becomes default-theme.js. */
  function applySheet(sheet) {
    apply(null);
    if (isSheet(sheet)) layer(sheet, root.PPB_DEFAULT_THEME);
    return THEME;
  }

  // ---------- BTE themes ----------

  // [framework] BTE Theme Loader, ThemeRuntime: BTE_COLOR and BTE_GRADIENT, the same patterns
  const BTE_COLOR = /^\s*([^/:][^:]*)\s*:\s*(#[0-9A-Fa-f]{3,8})(?:\s*\/\/.*)?$/;
  const BTE_GRADIENT = /^\s*Gradient\s*:\s*(true|false)\s*(?:\/\/.*)?$/i;

  /**
   * A .bte file as the BTE Theme Loader reads it: "Name: #rgb | #rgba | #rrggbb | #rrggbbaa" lines (a "//"
   * comment may follow), "Gradient: true | false", every other line ignored. Colours as "#rrggbbaa".
   */
  function parseBte(text, name) {
    const colors = {}, invalid = [];
    let gradient = null;
    for (const line of String(text).split(/\r?\n/)) {
      const g = BTE_GRADIENT.exec(line);
      if (g) { gradient = g[1].toLowerCase() === "true"; continue; }
      const m = BTE_COLOR.exec(line);
      if (!m) continue;
      const key = m[1].trim(), v = bteColour(m[2].trim());
      if (v) colors[key] = v;
      else invalid.push(key);
    }
    return { name: name || "", colors, gradient, invalid };
  }

  /** ThemeRuntime.parseArgb: #rgb and #rgba double each digit; #rrggbb is opaque; other lengths are invalid. */
  function bteColour(v) {
    const h = v.replace(/^#/, "");
    if (!/^[0-9a-f]+$/i.test(h)) return null;
    if (h.length === 3 || h.length === 4) return "#" + [...(h.length === 3 ? h + "f" : h)].map((c) => c + c).join("").toLowerCase();
    if (h.length === 6) return "#" + h.toLowerCase() + "ff";
    if (h.length === 8) return "#" + h.toLowerCase();
    return null;
  }

  // Which named colour each knob style draws with, found by matching the default theme's resolved style
  // colours to its colour table (Bitwig 6.1.4): body, outline, track, value arc, pointer.
  const KNOB_STYLE_COLOURS = {
    "default": ["Knob Body", "Knob Stroke", "Knob Value Background", "Knob Value Color", "Knob Line"],
    "lighter": ["Knob Body Lighter", "Knob Stroke", "Knob Value Background", "Knob Value Color", "Knob Line Dark"],
    "lightest": ["Knob Body Lightest", "Knob Stroke", "Knob Value Background", "Knob Value Color", "Knob Line Dark"],
    "darkest": ["Knob Body Darkest", "Knob Stroke", "Knob Value Background (dark)", "Subtle Light Text", "Knob Line"],
    "direct monitoring": ["Knob Body", "Knob Stroke", "Knob Value Background", "Bitwig Red", "Knob Line"],
    "mixer send (pre) knob": ["Knob Body", "Knob Stroke", "Knob Value Background", "Send (pre) value color", "Knob Line"],
    "mixer send (post) knob": ["Knob Body", "Knob Stroke", "Knob Value Background", "Send (post) value color", "Knob Line"],
    "mixer feedback send (pre) knob": ["Knob Body Lighter", "Knob Stroke", "Knob Value Background", "Send (pre) value color", "Knob Line Dark"],
    "mixer feedback send (post) knob": ["Knob Body Lighter", "Knob Stroke", "Knob Value Background", "Send (post) value color", "Knob Line Dark"],
  };
  // Other drawing colours by name: the envelope display, the device's header strip, and [approx] a label's
  // default text and the divider (the default theme's text_colors equal these names' values).
  const NAMED_DEFAULTS = {
    displayWaveform: "Display Waveform", displayBackground: "Display Background", strip: "Device Header",
    text: "Medium Light Text", divider: "Subtler Light Text",
  };

  /**
   * A BTE theme over the default theme: a colour whose exact name is in Bitwig's colour table replaces it
   * (Bitwig matches names exactly); other names are ignored, as Bitwig ignores them. Knob styles and the
   * drawing colours above follow their names. "Gradient: false": flat knob bodies (no derived shades).
   * Fonts, knob sizes and spacing aren't in a .bte: they stay the default theme's.
   */
  function layerBte(bte, base) {
    const table = base && base.colors ? base.colors : {};
    const applied = [], unknown = [];
    for (const [name, v] of Object.entries(bte.colors)) {
      if (!Object.prototype.hasOwnProperty.call(table, name)) { unknown.push(name); continue; }
      THEME.colours[name.toLowerCase()] = v;
      applied.push(name);
    }
    const set = new Set(applied);
    const named = (n) => THEME.colours[n.toLowerCase()] || null;
    for (const [style, names] of Object.entries(KNOB_STYLE_COLOURS)) {
      const st = THEME.knobStyles[style];
      if (!st) continue;
      ["body", "edge", "track", "arc", "pointer"].forEach((f, i) => {
        if (set.has(names[i]) && named(names[i])) {
          st[f] = named(names[i]);
          st.themed = Object.assign(st.themed || {}, { [f]: true });
        }
      });
      if (bte.gradient === false) st.gradient = 0;
    }
    for (const [k, n] of Object.entries(NAMED_DEFAULTS)) if (set.has(n) && named(n)) THEME.defaults[k] = named(n);
    if (set.has(NAMED_DEFAULTS.text)) THEME.defaults.title = THEME.defaults.text;
    THEME.imported = {
      bitwig: (base && base.bitwig) || "", builtIn: false, bte: true, name: bte.name || "BTE theme",
      applied: applied.length, unknown, invalid: bte.invalid || [], gradient: bte.gradient,
    };
  }

  /** One sheet over what THEME holds; `under` (the default theme) fills a null colour of the sheet. */
  function layer(sheet, under) {
    if (isNum(sheet.ui_scale) && sheet.ui_scale > 0) THEME.uiScale = sheet.ui_scale;
    if (sheet.fonts && typeof sheet.fonts === "object") {
      THEME.fontNames = Object.keys(sheet.fonts);
      // the theme's font table only: a name it lacks is unknown in Bitwig too (logged, Bitwig's default font)
      const before = THEME.fonts;
      THEME.fonts = { "default": before["default"] };
      for (const [name, f] of Object.entries(sheet.fonts)) {
        const key = name.toLowerCase();
        if (!f || typeof f !== "object" || !isNum(f.size)) { // listed but not measured: the name is still known
          if (before[key]) THEME.fonts[key] = before[key];
          continue;
        }
        const old = before[key] || {};
        THEME.fonts[key] = {
          weight: WEIGHTS[String(f.weight || "").toLowerCase()] || old.weight || 400,
          style: String(f.style || "normal").toLowerCase() === "italic" ? "italic" : "normal",
          size: f.size,
          height: isNum(f.height) ? f.height : null, // the line's height at `size`; scaled for another size
          family: f.family ? `'${String(f.family).replace(/'/g, "")}', ${fresh().family}` : null,
          mono: old.mono && !f.family,
          upper: old.upper, // a style of the name, not of the typeface [approx]
          src: "imported",
        };
      }
    }
    if (sheet.colors && typeof sheet.colors === "object") {
      // over the default theme's table (a BTE theme recolours names, it doesn't remove them): the sheet's
      // values win, an extra name is added, and a null value (not resolved when copying) keeps the default's
      const fallback = under && under.colors ? under.colors : {};
      THEME.colourNames = [...new Set((THEME.colourNames || []).concat(Object.keys(sheet.colors)))];
      if (!under) THEME.colours = {};
      for (const [name, c] of Object.entries(sheet.colors)) {
        const v = isColour(c) ? c : isColour(fallback[name]) ? fallback[name] : null;
        if (v) THEME.colours[name.toLowerCase()] = v;
      }
      const named = (n) => THEME.colours[n.toLowerCase()] || null;
      if (named("Display Waveform")) THEME.defaults.displayWaveform = named("Display Waveform");
      if (named("Display Background")) THEME.defaults.displayBackground = named("Display Background");
    }
    const tc = sheet.text_colors || {};
    if (isColour(tc.text)) THEME.defaults.text = THEME.defaults.title = tc.text;
    if (isColour(tc.separator)) THEME.defaults.divider = tc.separator;
    if (sheet.knob_sizes && typeof sheet.knob_sizes === "object") {
      for (const [name, g] of Object.entries(sheet.knob_sizes)) {
        if (!g || !isNum(g.knob_width) || !isNum(g.knob_height)) continue;
        const geo = { inner: g.inner_radius, arc: g.arc_radius, arcWidth: g.arc_width, center: g.center_area_radius, w: g.knob_width, h: g.knob_height };
        THEME.knobGeometry[name.toLowerCase()] = geo;
        // Bitwig's plug-in knob widget: the box's width and 7/8 of its height (the arc leaves the bottom
        // eighth empty) [measured: tiny, small, large in the user's log, 31x25, 43x38, 67x59 px at 150%]
        THEME.sizes.knob[name.toLowerCase()] = { w: g.knob_width, h: (g.knob_height * 7) / 8, src: "imported" };
      }
    }
    if (sheet.knob_styles && typeof sheet.knob_styles === "object") {
      THEME.knobStyles = {};
      THEME.knobStyleNames = Object.keys(sheet.knob_styles); // as a layout writes them
      for (const [name, st] of Object.entries(sheet.knob_styles)) {
        if (!st || typeof st !== "object") continue;
        THEME.knobStyles[name.toLowerCase()] = {
          arc: isColour(st.value_color) ? st.value_color : null,
          track: isColour(st.value_background_color) ? st.value_background_color : null,
          body: isColour(st.fill_color) ? st.fill_color : null,
          edge: isColour(st.stroke_color) ? st.stroke_color : null,
          pointer: isColour(st.dot_color) ? st.dot_color : null,
          gradient: isNum(st.gradient_amount) ? st.gradient_amount : 0,
        };
      }
    }
    if (Array.isArray(sheet.button_styles)) THEME.buttonStyles = sheet.button_styles.filter((x) => typeof x === "string");
    if (Array.isArray(sheet.dropdown_styles)) THEME.dropdownStyles = sheet.dropdown_styles.filter((x) => typeof x === "string");
    const bs = sheet.bitwig_sizes || {};
    if (isNum(bs.row_height)) THEME.sizes.row = bs.row_height;
    if (isNum(bs.body_width)) THEME.sizes.slider = [bs.body_width, isNum(bs.row_height) ? bs.row_height : THEME.sizes.slider[1]];
    if (sheet.framework && typeof sheet.framework === "object")
      for (const [k, v] of Object.entries(sheet.framework)) if (k in THEME.framework && (isNum(v) || Array.isArray(v))) THEME.framework[k] = v;
  }

  const fresh = () => JSON.parse(PRISTINE);

  THEME.apply = apply;
  THEME.isSheet = isSheet;
  THEME.parseBte = parseBte;
  THEME.applySheet = applySheet;
  root.PPBTheme = THEME;
  apply(null); // the default theme, when default-theme.js is loaded
})(typeof window !== "undefined" ? window : globalThis);
