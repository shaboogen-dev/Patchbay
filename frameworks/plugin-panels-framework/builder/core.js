/*
 * Plug-in Panels layout builder: the layout schema, its validation, parameter references,
 * hints from a parameter sheet, and the JSON writer. No DOM here, so Node can run it too
 * (test/roundtrip.js).
 *
 * The validation follows the framework's own reader (Layouts.read, ValueMap.parse and the
 * parameter resolver): what it refuses is an error (Bitwig's own page is shown instead), what it
 * ignores or leaves out is a warning.
 */
(function (root) {
  "use strict";

  const KINDS = ["knob", "dropdown", "toggle", "value", "slider", "meter", "text", "row", "envelope", "spacer"]; // Layouts.KINDS
  const ENVELOPE_KEYS = ["delay", "attack", "hold", "decay", "sustain", "release"]; // Layouts.ENVELOPE_KEYS, in drawing order
  const ENVELOPE_SCALES = ["linear", "log"];
  const MODE_TARGETS = ["stripe", "knob_arc", "header", "divider", "background_tint", "envelope", "toolbar"]; // Layouts.MODE_TARGETS
  const STRIPE_HEIGHT = 2; // Layouts.Stripe.DEFAULT_HEIGHT
  const TOOLBAR_PARTS = ["pages", "sidechain", "outputs", "search", "position", "style", "size", "gap",
    "list", "order", "align", "background", "divider"]; // Layouts.TOOLBAR_PARTS
  const RIGHT_ONLY = ["style", "size", "gap", "list", "order", "align", "background", "divider"]; // Layouts.RIGHT_ONLY
  const BAR_ORDER = ["layout", "list", "programs", "sidechain", "outputs", "spacer", "container"]; // Layouts.Toolbar.ORDER
  const BAR_ENTRIES = ["layout", "list", "programs", "sidechain", "outputs", "container", "spacer", "pages"]; // ORDER_ENTRIES ("pages": layout, programs)
  const BAR_TINT = 0.25; // the mode colour mixed into the bar's background (mode target "toolbar")
  const MODE_MIX = { background_tint: 0.12, toolbar: 0.25 }; // mode_colors.mix defaults; every other target 1

  /** Layouts.order(): "pages" stands for "layout", "programs". */
  const expandOrder = (list) => list.flatMap((e) => (e === "pages" ? ["layout", "programs"] : [e]));
  const TOOLBAR_PIECES = ["pages", "sidechain", "outputs", "search"]; // the pieces a layout can leave out
  const BAR_SIZE = 21, BAR_GAP = 2; // Layouts.Toolbar.SIZE, GAP: the right-hand bar's buttons and spacing
  // Bitwig's dropdown styles (framework README; a theme sheet's dropdown_styles replaces them)
  const DROPDOWN_STYLES = ["Default", "Borderless", "Borderless (accent)", "Plain", "Stepper", "Underline", "Flat"];

  /**
   * Which toolbar pieces stay (Layouts.layout): {row, pages, sidechain, outputs, search}. The container
   * button (the arrow) always stays: in the row, or on the title line without one.
   */
  function toolbarParts(L) {
    const t = isObj(L) ? L.toolbar : undefined;
    if (t === false) return { row: false, right: false, pages: false, sidechain: false, outputs: false, search: false, parameters: false, programs: false };
    const o = isObj(t) ? t : {};
    const right = typeof o.position === "string" && o.position.trim().toLowerCase() === "right";
    const r = {
      row: true, right, pages: o.pages !== false, sidechain: o.sidechain !== false, outputs: o.outputs !== false,
      search: !right && o.search !== false && !(isObj(L) && L.search === false),
      // the right-hand bar: its buttons' style and size, the gap between them and beside the panel
      style: right ? (typeof o.style === "string" ? o.style.trim() : "Borderless") : "",
      size: right ? (isNum(o.size) ? Math.round(o.size) : BAR_SIZE) : 0,
      gap: right ? (isNum(o.gap) ? Math.round(o.gap) : BAR_GAP) : 0,
      list: right && o.list === true,
      order: right && Array.isArray(o.order) ? expandOrder(o.order.filter(textLike).map((e) => String(e).trim().toLowerCase())) : BAR_ORDER.slice(),
      align: right && typeof o.align === "string" ? (o.align.trim().toLowerCase() === "centre" ? "center" : o.align.trim().toLowerCase()) : "top",
      background: right && typeof o.background === "string" ? o.background.trim() : "",
      divider: right && o.divider === true,
    };
    // Layouts.Toolbar.barWidth: the buttons' size, the gap before the bar and, with a divider, the line and its gap
    r.barWidth = right ? r.size + r.gap + (r.divider ? 1 + r.gap : 0) : 0;
    if (right) { // PanelHooks.bar: a button shows when it's on and in the order
      const inOrder = (k) => r.order.includes(k);
      r.showPrograms = r.pages && inOrder("programs");
      r.showSidechain = r.sidechain && inOrder("sidechain");
      r.showOutputs = r.outputs && inOrder("outputs");
      r.showList = r.list && inOrder("list");
      // the Layout button: in the order, or put first when another page or the list can show (the way back)
      r.forcedLayout = !inOrder("layout") && (r.list || r.showPrograms || r.showSidechain || r.showOutputs);
      r.showLayout = inOrder("layout") || r.forcedLayout;
      r.modes = r.list; // with the list: Layout, List, Programs, sidechain, outputs act as radio buttons
    }
    // the Parameters tab stays while sidechain or outputs stays (their pages switch back through it); only
    // the Programs tab goes with pages: false
    r.programs = r.pages;
    r.parameters = r.pages || r.sidechain || r.outputs;
    return r;
  }
  const KNOB_SIZES = ["tiny", "small", "normal", "large", "huge"];
  const REFRESH = ["none", "settle"];
  const TYPING = ["plugin", "mapping", "normalised"];
  const FORMATS = ["VST2", "VST3", "CLAP"];
  const METER_STYLES = ["needle", "bar", "level"];
  const KNOB_STYLES = ["default", "Lighter", "Lightest", "Darkest", "Direct Monitoring"];
  const FONTS = ["Default", "Small", "Tiny", "Bold", "Bold Smaller", "Italic", "Italic Smaller", "Light Large",
    "Section Header", "Uppercase header", "Title", "Title large", "Number display", "Menu", "Tree", "Console",
    "Alert Regular", "Alert Medium", "Alert Bold"];
  const COLOURS = ["Grey 1", "Grey 2", "Grey 3", "Grey 4", "Grey 5", "Grey 6", "Panel body", "Panel Orange"];
  const BUTTON_STYLES = ["Borderless", "Borderless Bold", "Brighter Tag Pill"];

  // Keys per level, in the order the builder writes new keys (existing keys keep their place).
  const LAYOUT_KEYS = ["name", "match", "width", "padding", "gap", "dividers", "background", "title", "toolbar", "search",
    "page_chooser", "min_resolved", "refresh", "typing", "title_font", "title_color", "header_font", "header_color",
    "label_font", "label_color", "value_font", "value_color", "header_height", "label_height", "value_height",
    "footer_gap", "fill_height", "stripe", "mode_colors", "columns", "pages", "footer"];
  const PAGE_KEYS = ["name", "columns", "footer"];
  const FILE_KEYS = ["name", "layouts"]; // a file with several layouts: the panel's name, then its layouts
  const COLUMN_KEYS = ["header", "header_font", "header_color", "width", "padding", "gap", "align", "valign", "divider", "background", "items"];
  const ITEM_KEYS = KINDS.concat(["size", "label", "font", "color", "value_font", "value_color", "value_format",
    "knob_style", "style", "choices", "values", "refresh", "typing", "width", "height", "gap", "background", "align",
    "valign", "align_knobs", "padding", "scale", "points", "fill_color", "display_color"]);
  const VALUES_KEYS = ["min", "max", "unit", "curve", "points", "steps", "normalised"];

  // Which item keys mean something for which kind (others are read but have no effect: a warning).
  const KIND_KEYS = {
    knob: ["label", "font", "color", "value_font", "value_color", "value_format", "size", "knob_style", "values", "refresh", "typing"],
    dropdown: ["label", "font", "color", "choices", "style"],
    toggle: ["label", "font", "color", "style"],
    value: ["label", "font", "color", "value_font", "value_color", "value_format", "refresh"],
    slider: [],
    meter: ["style"],
    text: ["font", "color"],
    row: ["gap", "valign", "align_knobs"],
    envelope: ["scale", "points", "color", "fill_color", "display_color"],
    spacer: [],
  };
  const COMMON_ITEM_KEYS = ["width", "height", "background", "align", "valign"];

  // ---------- small helpers ----------

  const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
  const isNum = (v) => typeof v === "number" && isFinite(v);
  const has = (o, k) => isObj(o) && Object.prototype.hasOwnProperty.call(o, k);

  /** The kind key of an item, or null. */
  function kindOf(item) {
    if (!isObj(item)) return null;
    for (const k of KINDS) if (has(item, k)) return k;
    return null;
  }

  /** Sets o[key] = value, keeping existing keys in place and putting a new key where `order` says. */
  function setKey(o, key, value, order) {
    if (value === undefined) {
      delete o[key];
      return o;
    }
    if (has(o, key) || !order) {
      o[key] = value;
      return o;
    }
    const at = order.indexOf(key);
    const keys = Object.keys(o);
    let before = -1;
    if (at >= 0) {
      for (let i = 0; i < keys.length; i++) {
        const j = order.indexOf(keys[i]);
        if (j > at) { before = i; break; }
      }
    }
    if (before < 0) {
      o[key] = value;
      return o;
    }
    const tail = keys.slice(before).map((k) => [k, o[k]]);
    for (const [k] of tail) delete o[k];
    o[key] = value;
    for (const [k, v] of tail) o[k] = v;
    return o;
  }

  /** The key order a node's new keys follow, from its level. */
  function orderFor(level) {
    return { file: FILE_KEYS, layout: LAYOUT_KEYS, page: PAGE_KEYS, column: COLUMN_KEYS, item: ITEM_KEYS, values: VALUES_KEYS, envelope: ENVELOPE_KEYS }[level] || null;
  }

  // ---------- validation ----------

  /**
   * Checks a layout file (one layout, or {"layouts": [...]}) as the framework reads it.
   * Returns [{level: "error"|"warning"|"info", path: [...], message}]; paths are into the file's JSON.
   * `sheet` (optional) is a parameter sheet: references are then resolved against it.
   */
  function validate(file, sheet) {
    const out = [];
    const err = (path, message) => out.push({ level: "error", path, message });
    const warn = (path, message) => out.push({ level: "warning", path, message });
    if (!isObj(file)) {
      err([], "the file must be an object");
      return out;
    }
    if (has(file, "layouts")) {
      if (!Array.isArray(file.layouts)) err(["layouts"], "layouts must be a list");
      else file.layouts.forEach((l, i) => validateLayout(l, ["layouts", i], sheet, err, warn));
      for (const k of Object.keys(file)) if (k !== "layouts" && k !== "name") warn([k], `"${k}" beside "layouts" is ignored`);
      typeChecks(err).str(file.name, ["name"], "name");
    } else {
      validateLayout(file, [], sheet, err, warn);
    }
    return out;
  }

  function validateLayout(o, p, sheet, err, warn) {
    if (!isObj(o)) return err(p, "a layout must be an object");
    const T = typeChecks(err);
    unknownKeys(o, LAYOUT_KEYS, p, warn, "layout");
    if (!p.length) T.str(o.name, ["name"], "name"); // the panel's name (Patchbay's list): on the file
    else if (has(o, "name")) warn(p.concat("name"), "a layout's name is ignored in a file with several layouts: name the file (beside \"layouts\")");
    if (!isObj(o.match)) err(p.concat("match"), "match must be an object with a name");
    else {
      unknownKeys(o.match, ["name", "vendor", "format"], p.concat("match"), warn, "match");
      if (T.str(o.match.name, p.concat("match", "name"), "match.name", true) && String(o.match.name).trim() === "")
        err(p.concat("match", "name"), "match.name is empty");
      T.str(o.match.vendor, p.concat("match", "vendor"), "match.vendor");
      if (T.str(o.match.format, p.concat("match", "format"), "match.format") && o.match.format !== undefined &&
        String(o.match.format).trim() !== "" && !FORMATS.includes(String(o.match.format).trim().toUpperCase()))
        warn(p.concat("match", "format"), `match.format "${o.match.format}" matches no plug-in format (VST2, VST3, CLAP)`);
      if (sheet && sheet.plugin && typeof o.match.name === "string" && !matchesPlugin(o.match, sheet.plugin))
        warn(p.concat("match"), `match doesn't match the loaded sheet's plug-in (${sheet.plugin.vendor} / ${sheet.plugin.name} / ${sheet.plugin.format})`);
    }
    if (o.width !== undefined) T.num(o.width, p.concat("width"), "width");
    if (has(o, "pages")) {
      if (has(o, "columns")) {
        err(p, "a layout has either columns or pages, not both");
        validateColumns(o.columns, p.concat("columns"), T, err, warn);
      }
      if (has(o, "footer")) err(p.concat("footer"), "with pages, each page has its own footer");
      if (!Array.isArray(o.pages)) err(p.concat("pages"), "pages must be a list");
      else {
        if (o.pages.length === 0) err(p.concat("pages"), "no pages");
        o.pages.forEach((pg, i) => {
          const pp = p.concat("pages", i);
          if (!isObj(pg)) return err(pp, "a page must be an object");
          unknownKeys(pg, PAGE_KEYS, pp, warn, "page");
          T.str(pg.name, pp.concat("name"), "page name", true);
          validateColumns(pg.columns, pp.concat("columns"), T, err, warn);
          if (pg.footer !== undefined) validateItems(pg.footer, pp.concat("footer"), T, err, warn);
        });
      }
    } else {
      validateColumns(o.columns, p.concat("columns"), T, err, warn);
      if (o.footer !== undefined) validateItems(o.footer, p.concat("footer"), T, err, warn);
    }
    if (o.page_chooser !== undefined && T.str(o.page_chooser, p.concat("page_chooser"), "page_chooser")) {
      const c = String(o.page_chooser).trim().toLowerCase();
      if (c !== "" && c !== "tabs" && c !== "dropdown") err(p.concat("page_chooser"), "page_chooser must be tabs or dropdown");
    }
    for (const k of ["title_font", "title_color", "header_font", "header_color", "label_font", "label_color", "value_font", "value_color", "background"])
      T.str(o[k], p.concat(k), k);
    if (o.min_resolved !== undefined && T.num(o.min_resolved, p.concat("min_resolved"), "min_resolved")) {
      const m = Math.round(o.min_resolved);
      if (m < 1 || m > 100) err(p.concat("min_resolved"), "min_resolved must be 1 to 100 (percent)");
    }
    for (const k of ["header_height", "label_height", "value_height", "footer_gap"]) T.size(o[k], p.concat(k), k);
    T.padding(o.padding, p.concat("padding"), "padding");
    for (const k of ["dividers", "title", "search", "fill_height"]) T.bool(o[k], p.concat(k), k);
    // a column's valign places its content in the spare height of fill_height
    const colsOf = (pg) => (isObj(pg) && Array.isArray(pg.columns) ? pg.columns : []);
    if (o.fill_height !== true) (Array.isArray(o.pages) ? o.pages.flatMap(colsOf) : colsOf(o)).forEach((c, i) => {
      if (isObj(c) && c.valign !== undefined && !(Array.isArray(c.items) && c.items.some((it) => kindOf(it) === "spacer"))) warn(p.concat(Array.isArray(o.pages) ? [] : ["columns", i], "valign"), 'a column\'s valign works with the layout\'s "fill_height": true');
    });
    // Layouts.layout(): toolbar true, false, or {pages, sidechain, outputs, search}
    if (o.toolbar !== undefined && o.toolbar !== null && typeof o.toolbar !== "boolean") {
      if (!isObj(o.toolbar)) err(p.concat("toolbar"), "toolbar must be an object");
      else {
        const t = o.toolbar;
        for (const [k, v] of Object.entries(t)) {
          if (!TOOLBAR_PARTS.includes(k)) err(p.concat("toolbar", k), `toolbar: unknown key '${k}' (${TOOLBAR_PARTS.join(", ")})`);
          else if (TOOLBAR_PIECES.includes(k)) T.bool(v, p.concat("toolbar", k), "toolbar." + k);
        }
        T.choice(t.position, p.concat("toolbar", "position"), "toolbar.position", ["top", "right"]);
        const right = typeof t.position === "string" && t.position.trim().toLowerCase() === "right";
        if (!right) for (const k of RIGHT_ONLY) if (has(t, k)) err(p.concat("toolbar", k), `toolbar: ${k} is for "position": "right"`);
        if (right) {
          T.bool(t.list, p.concat("toolbar", "list"), "toolbar.list");
          T.bool(t.divider, p.concat("toolbar", "divider"), "toolbar.divider");
          T.str(t.background, p.concat("toolbar", "background"), "toolbar.background");
          // Layouts.order(): entries from the top, each once but spacer
          let order = BAR_ORDER;
          if (t.order !== undefined && t.order !== null) {
            if (!Array.isArray(t.order)) err(p.concat("toolbar", "order"), "toolbar.order must be a list");
            else {
              order = [];
              for (const e of t.order) {
                if (!textLike(e)) { err(p.concat("toolbar", "order"), "toolbar.order must be text"); continue; }
                const v = String(e).trim().toLowerCase();
                if (!BAR_ENTRIES.includes(v)) { err(p.concat("toolbar", "order"), `toolbar.order: '${v}' is not one of [${BAR_ENTRIES.join(", ")}]`); continue; }
                for (const one of expandOrder([v])) {
                  if (one !== "spacer" && order.includes(one)) err(p.concat("toolbar", "order"), `toolbar.order: '${one}' twice`);
                  order.push(one);
                }
              }
              if (!order.includes("container")) warn(p.concat("toolbar", "order"), "toolbar.order leaves out the container button: it goes at the end (it is always shown)");
              if (order.includes("list") && t.list !== true) warn(p.concat("toolbar", "order"), `"list" in the order shows only with "list": true`);
              const tp = toolbarParts(o);
              if (tp.forcedLayout) warn(p.concat("toolbar", "order"), "toolbar.order leaves out the Layout button while other pages or the list can show: it goes first");
            }
          }
          // Layouts.align(): top, center or bottom, for an order without a spacer
          if (t.align !== undefined && t.align !== null) {
            if (!textLike(t.align)) err(p.concat("toolbar", "align"), "toolbar.align must be text");
            else {
              const a = String(t.align).trim().toLowerCase();
              if (!["top", "center", "centre", "bottom"].includes(a)) err(p.concat("toolbar", "align"), "toolbar.align must be top, center or bottom");
              else if (order.includes("spacer")) err(p.concat("toolbar", "align"), "toolbar.align: the order has a spacer (the default order has one); give an order without spacers to align the buttons");
            }
          }
        }
        if (right && (t.search === true || o.search === true))
          err(p.concat("toolbar", "search"), 'toolbar: no search field with "position": "right" (leave search out)');
        if (t.size !== undefined) {
          if (!isNum(t.size)) err(p.concat("toolbar", "size"), "toolbar.size must be a number");
          else if (Math.round(t.size) < 12 || Math.round(t.size) > 60) err(p.concat("toolbar", "size"), "toolbar.size must be 12 to 60");
        }
        if (t.gap !== undefined) {
          if (!isNum(t.gap)) err(p.concat("toolbar", "gap"), "toolbar.gap must be a number");
          else if (Math.round(t.gap) < 0 || Math.round(t.gap) > 20) err(p.concat("toolbar", "gap"), "toolbar.gap must be 0 to 20");
        }
        T.str(t.style, p.concat("toolbar", "style"), "toolbar.style");
      }
    }
    T.gap(o.gap, p.concat("gap"), "gap");
    T.choice(o.refresh, p.concat("refresh"), "refresh", REFRESH);
    T.choice(o.typing, p.concat("typing"), "typing", TYPING);
    validateStripe(o.stripe, p.concat("stripe"), T, err);
    if (o.mode_colors !== undefined && o.mode_colors !== null) validateModes(o, p.concat("mode_colors"), T, err, warn);
    // references are checked only against the sheet of the plug-in the layout matches
    const forSheet = sheet && isObj(o.match) && typeof o.match.name === "string" && (!sheet.plugin || matchesPlugin(o.match, sheet.plugin));
    if (forSheet) checkReferences(o, p, sheet, err, warn);
    if (forSheet && isObj(o.mode_colors)) checkModes(o, p.concat("mode_colors"), sheet, err, warn);
    // Panels.typedValues(): a knob whose typing is "mapping" (its own, else the layout's) needs values
    walkItems(o, p, (item, path) => {
      if (kindOf(item) !== "knob") return;
      const typing = String(item.typing || o.typing || (item.values !== undefined ? "mapping" : "plugin")).trim().toLowerCase();
      if (typing === "mapping" && item.values === undefined)
        err(path, `"typing": "mapping"${item.typing ? "" : " (the layout's)"} needs "values" on this knob`);
    });
    // Panels.font(): a size above 200 is ignored
    const fontSize = (v, q) => {
      if (typeof v !== "string") return;
      const m = /\s(\d+(?:\.\d+)?)$/.exec(v.trim());
      if (m && parseFloat(m[1]) > 200) warn(q, `font size ${m[1]} too large: the font's own size is used`);
    };
    for (const k of ["title_font", "header_font", "label_font", "value_font"]) fontSize(o[k], p.concat(k));
    walkItems(o, p, (item, path) => { fontSize(item.font, path.concat("font")); fontSize(item.value_font, path.concat("value_font")); });
  }

  /** Layouts.stripe(): true, false or absent, or {color, height} (height 1 to 20). */
  function validateStripe(v, p, T, err) {
    if (v === undefined || v === null || typeof v === "boolean") return;
    if (!isObj(v)) return err(p, "stripe must be an object");
    for (const k of Object.keys(v)) if (k !== "color" && k !== "height") err(p.concat(k), `stripe: unknown key '${k}' (color, height)`);
    if (v.height !== undefined && v.height !== null) {
      if (!isNum(v.height)) err(p.concat("height"), "stripe.height must be a number");
      else if (Math.round(v.height) < 1 || Math.round(v.height) > 20) err(p.concat("height"), "stripe.height must be 1 to 20");
    }
    T.str(v.color, p.concat("color"), "stripe.color");
  }

  const hasStripe = (L) => isObj(L) && L.stripe !== undefined && L.stripe !== null && L.stripe !== false;

  /** Layouts.modes(): {param, map, apply}; the stripe and background_tint need the layout's stripe and background. */
  function validateModes(L, p, T, err, warn) {
    const m = L.mode_colors;
    if (!isObj(m)) return err(p, "mode_colors must be an object");
    for (const k of Object.keys(m)) if (!["param", "map", "apply", "mix"].includes(k)) err(p.concat(k), `mode_colors: unknown key '${k}' (param, map, apply, mix)`);
    if (!textLike(m.param)) err(p.concat("param"), "mode_colors.param must be text");
    else if (String(m.param).trim() === "" || isAuto(m.param)) err(p.concat("param"), "mode_colors.param must name a parameter");
    if (!isObj(m.map)) err(p.concat("map"), "mode_colors.map must be an object");
    else {
      const apply = modeTargets(L);
      for (const [k, v] of Object.entries(m.map)) {
        const w = `mode_colors.map.${k}`;
        if (isObj(v)) { // a colour per target
          for (const [t, c] of Object.entries(v)) {
            if (!MODE_TARGETS.includes(t)) err(p.concat("map", k, t), `${w}: '${t}' is not one of [${MODE_TARGETS.join(", ")}]`);
            else if (!textLike(c)) err(p.concat("map", k, t), `${w}.${t} must be text`);
            else if (String(c).trim() === "") err(p.concat("map", k, t), `${w}.${t} is empty`);
            else if (!apply.has(t)) err(p.concat("map", k, t), `${w}.${t}: ${t} is not in apply`);
          }
          if (!Object.keys(v).length) err(p.concat("map", k), `${w} is empty`);
        } else if (!textLike(v)) err(p.concat("map", k), `${w} must be text`);
        else if (String(v).trim() === "") err(p.concat("map", k), `${w} is empty`);
      }
      if (!Object.keys(m.map).length) err(p.concat("map"), "mode_colors.map is empty");
    }
    if (m.apply !== undefined && m.apply !== null) {
      if (!Array.isArray(m.apply)) return err(p.concat("apply"), "mode_colors.apply must be a list");
      for (const a of m.apply) {
        if (!textLike(a)) { err(p.concat("apply"), "mode_colors.apply must be text"); continue; }
        const t = String(a).trim().toLowerCase();
        if (!MODE_TARGETS.includes(t)) err(p.concat("apply"), `mode_colors.apply: '${t}' is not one of [${MODE_TARGETS.join(", ")}]`);
      }
      if (!m.apply.length) err(p.concat("apply"), "mode_colors.apply is empty");
    }
    const apply = modeTargets(L);
    if (m.mix !== undefined && m.mix !== null) {
      if (!isObj(m.mix)) err(p.concat("mix"), "mode_colors.mix must be an object");
      else for (const [t, v] of Object.entries(m.mix)) {
        if (!apply.has(t)) err(p.concat("mix", t), `mode_colors.mix.${t}: not a target in apply [${[...apply].join(", ")}]`);
        else if (!isNum(v)) err(p.concat("mix", t), `mode_colors.mix.${t} must be a number`);
        else if (v < 0 || v > 1) err(p.concat("mix", t), `mode_colors.mix.${t} must be 0 to 1`);
      }
    }
    if (apply.has("stripe") && !hasStripe(L)) err(p.concat("apply"), "mode_colors.apply: stripe needs the layout's stripe");
    if (apply.has("background_tint") && !(typeof L.background === "string" && L.background.trim()))
      err(p.concat("apply"), "mode_colors.apply: background_tint needs the layout's background");
    if (apply.has("toolbar")) {
      const tb = toolbarParts(L);
      if (!tb.right || !tb.background) err(p.concat("apply"), "mode_colors.apply: toolbar needs a right-hand toolbar with a background");
    }
  }

  /** The parts mode colours show on: the layout's apply, else stripe (with a stripe) and knob_arc. */
  function modeTargets(L) {
    const m = isObj(L) && isObj(L.mode_colors) ? L.mode_colors : null;
    if (!m) return new Set();
    if (Array.isArray(m.apply)) return new Set(m.apply.filter(textLike).map((a) => String(a).trim().toLowerCase()));
    return new Set(hasStripe(L) ? ["stripe", "knob_arc"] : ["knob_arc"]);
  }

  /**
   * Panels.modeColours() and ModeColours: the modes of the layout's mode_colors parameter (the choices of the
   * first dropdown with choices on it, else its step names) and the one shown: `override` when given, else
   * the nearest to the parameter's value. Without a sheet the dropdown is found by its reference text.
   * Returns {param, names: [{name, value}], current, colour, apply} or null.
   */
  function currentMode(L, params, override) {
    const m = isObj(L) && isObj(L.mode_colors) ? L.mode_colors : null;
    if (!m || !textLike(m.param)) return null;
    const ref = String(m.param).trim();
    const p = params && params.length ? resolveRef(ref, params) : null;
    const resolved = params && params.length ? resolveLayout(L, params) : null;
    let names = null;
    walkItems(L, [], (it, path) => {
      if (names || kindOf(it) !== "dropdown" || !Array.isArray(it.choices) || !it.choices.length) return;
      const same = p ? resolved.get(path.join("/")) === p : String(it.dropdown).trim().toLowerCase() === ref.toLowerCase();
      if (!same) return;
      names = it.choices.filter(Array.isArray).map((e) => (typeof e[0] === "string" ? { name: e[0], value: e[1] } : { name: String(e[1]), value: e[0] }));
    });
    if (!names && p && (p.step_names || []).length) {
      const n = p.step_names.length;
      names = p.step_names.map((s, i) => ({ name: s, value: n > 1 ? i / (n - 1) : 0 }));
    }
    names = names || [];
    let current = null;
    if (override && names.some((x) => x.name === override)) current = override;
    else if (p && isNum(p.value) && names.length) {
      let best = names[0];
      for (const x of names) if (Math.abs(x.value - p.value) < Math.abs(best.value - p.value)) best = x;
      current = best.name;
    } else if (names.length) current = names[0].name;
    let entry = null;
    if (current !== null && isObj(m.map))
      for (const [k, v] of Object.entries(m.map)) if (k.trim().toLowerCase() === String(current).trim().toLowerCase()) entry = v;
    const apply = modeTargets(L);
    // a mode's colour for a target: one colour for every target, or its own per target (left out: none)
    const colourFor = (t) => {
      if (!apply.has(t) || entry === null) return null;
      if (isObj(entry)) return textLike(entry[t]) && String(entry[t]).trim() ? String(entry[t]).trim() : null;
      return textLike(entry) ? String(entry).trim() : null;
    };
    const mixFor = (t) => (isObj(m.mix) && isNum(m.mix[t]) ? m.mix[t] : MODE_MIX[t] !== undefined ? MODE_MIX[t] : 1);
    return { param: p, ref, names, current, colour: isObj(entry) ? null : colourFor([...apply][0]), colourFor, mixFor, apply };
  }

  /** With a sheet: the parameter, its modes, and map names that aren't modes. */
  function checkModes(L, p, sheet, err, warn) {
    const m = L.mode_colors;
    if (!textLike(m.param) || !String(m.param).trim() || isAuto(m.param)) return;
    const params = sheet.parameters || [];
    const mode = currentMode(L, params, null);
    if (!mode.param) {
      const min = isNum(L.min_resolved) ? Math.round(L.min_resolved) : 100;
      if (min >= 100) err(p.concat("param"), `mode_colors: unknown parameter '${m.param}': the whole layout falls back to Bitwig's page`);
      else warn(p.concat("param"), `mode_colors: unknown parameter '${m.param}': no mode colours`);
      return;
    }
    if (!mode.names.length) return err(p.concat("param"), `mode_colors: '${m.param}' has no dropdown with choices and no step names to name its modes`);
    const known = new Set(mode.names.map((x) => String(x.name).trim().toLowerCase()));
    if (isObj(m.map)) for (const k of Object.keys(m.map)) if (!known.has(k.trim().toLowerCase()))
      warn(p.concat("map", k), `"${k}" isn't a mode of '${m.param}': never shown`);
  }

  function validateColumns(cols, p, T, err, warn) {
    if (!Array.isArray(cols)) return err(p, (p[p.length - 1] === "columns" ? "columns" : "") + " must be a list");
    if (cols.length === 0) err(p, "no columns");
    cols.forEach((c, i) => {
      const cp = p.concat(i);
      if (!isObj(c)) return err(cp, "a column must be an object");
      unknownKeys(c, COLUMN_KEYS, cp, warn, "column");
      for (const k of ["header", "header_font", "header_color", "background"]) T.str(c[k], cp.concat(k), k);
      T.bool(c.divider, cp.concat("divider"), "divider");
      T.size(c.width, cp.concat("width"), "width");
      T.place(c, cp);
      T.padding(c.padding, cp.concat("padding"), "padding");
      // Panels.columns(): a column with spacers gives them its spare height; its valign isn't used
      if (c.valign !== undefined && Array.isArray(c.items) && c.items.some((it) => kindOf(it) === "spacer"))
        warn(cp.concat("valign"), "valign has no effect on a column with spacers: they take its spare height");
      validateItems(c.items, cp.concat("items"), T, err, warn);
    });
  }

  function validateItems(items, p, T, err, warn) {
    if (!Array.isArray(items)) return err(p, "must be a list");
    items.forEach((it, i) => validateItem(it, p.concat(i), T, err, warn));
  }

  function validateItem(o, p, T, err, warn) {
    if (!isObj(o)) return err(p, "an item must be an object");
    const kinds = KINDS.filter((k) => has(o, k));
    if (kinds.length === 0) return err(p, `an item needs one of ${KINDS.join(", ")}; found [${Object.keys(o).join(", ")}]`);
    if (kinds.length > 1) return err(p, `an item has both ${kinds[0]} and ${kinds[1]}`);
    const kind = kinds[0];
    // Layouts.item(): {"spacer": true}, nothing else
    if (kind === "spacer") {
      if (o.spacer !== true) err(p.concat("spacer"), "spacer must be true");
      for (const k of Object.keys(o)) if (k !== "spacer") err(p.concat(k), `a spacer takes no other keys (found ${k})`);
      return;
    }
    unknownKeys(o, ITEM_KEYS, p, warn, "item");
    T.str(o.background, p.concat("background"), "background");
    T.place(o, p);
    // Layouts.item(): padding is for rows (and columns)
    if (kind !== "row" && has(o, "padding")) err(p.concat("padding"), "padding is for rows and columns (put the item in a row)");
    if (kind === "envelope") return validateEnvelope(o, p, T, err, warn);
    if (kind === "row") {
      validateItems(o.row, p.concat("row"), T, err, warn);
      T.size(o.width, p.concat("width"), "width");
      T.padding(o.padding, p.concat("padding"), "padding");
      if (o.height !== undefined) warn(p.concat("height"), heightIgnored(o));
      for (const k of Object.keys(o)) if (!["row", "gap", "valign", "align_knobs", "padding", "height"].concat(COMMON_ITEM_KEYS).includes(k))
        warn(p.concat(k), `"${k}" has no effect on a row`);
      return;
    }
    const used = new Set(KIND_KEYS[kind].concat(COMMON_ITEM_KEYS, [kind]));
    for (const k of Object.keys(o)) if (ITEM_KEYS.includes(k) && k !== "padding" && !used.has(k)) warn(p.concat(k), `"${k}" has no effect on a ${kind}`);
    if (o.height !== undefined && heightIgnored(o)) warn(p.concat("height"), heightIgnored(o));
    if (o.label !== undefined) {
      const l = o.label;
      if (l === true) err(p.concat("label"), "label: text, an object, or false (no label)");
      else if (isObj(l)) {
        unknownKeys(l, ["icon", "text", "size"], p.concat("label"), warn, "label");
        T.str(l.text, p.concat("label", "text"), "label.text");
        T.str(l.icon, p.concat("label", "icon"), "label.icon");
        T.size(l.size, p.concat("label", "size"), "label.size");
        if (!l.icon) warn(p.concat("label"), "a label object without an icon shows its text only");
      } else if (l !== false) T.str(l, p.concat("label"), "label");
    }
    if (o.size !== undefined) {
      if (isNum(o.size)) T.size(o.size, p.concat("size"), "size");
      else if (T.str(o.size, p.concat("size"), "size")) {
        const s = String(o.size).trim().toLowerCase();
        if (s !== "" && !KNOB_SIZES.includes(s)) err(p.concat("size"), `size must be a number or one of ${KNOB_SIZES.join(", ")}`);
      }
    }
    if (T.str(o[kind], p.concat(kind), kind, true) && kind !== "text" && String(o[kind]).trim() === "")
      err(p.concat(kind), "no parameter given");
    for (const k of ["style", "knob_style", "font", "color", "value_font", "value_color"]) T.str(o[k], p.concat(k), k);
    if (kind === "meter" && typeof o.meter === "string" && o.meter.trim().toLowerCase() !== "output")
      err(p.concat("meter"), `unknown meter source '${o.meter}' (output)`);
    if (kind === "meter" && typeof o.style === "string" && o.style.trim() !== "" && !METER_STYLES.includes(o.style.trim().toLowerCase()))
      err(p.concat("style"), `unknown meter style '${o.style}' (needle, bar, level)`);
    validateFormat(o.value_format, p.concat("value_format"), err);
    if (o.choices !== undefined) validateChoices(o.choices, p.concat("choices"), err);
    if (o.values !== undefined) validateValues(o.values, p.concat("values"), err, warn);
    T.choice(o.refresh, p.concat("refresh"), "refresh", REFRESH);
    T.choice(o.typing, p.concat("typing"), "typing", TYPING);
    T.size(o.width, p.concat("width"), "width");
    T.size(o.height, p.concat("height"), "height");
  }

  /**
   * Why an item's `height` does nothing (Panels.java), or null when it applies: a knob with a number `size` is a
   * fixed cell (cell(): only its width; the knob is `size` high), a text and a row have no size of their own.
   */
  function heightIgnored(o) {
    const k = kindOf(o);
    if (k === "knob" && isNum(o.size)) return "height has no effect on a knob cell (a number size): the knob is exactly size high, the label and value lines take the layout's label_height and value_height";
    if (k === "text") return "height has no effect on a text: it is its font's height";
    if (k === "row") return "height has no effect on a row: it is as tall as its tallest item";
    return null;
  }

  /** Layouts.envelope(): the envelope object, its keys and their order rules, values on time keys, scale. */
  function validateEnvelope(o, p, T, err, warn) {
    const used = new Set(KIND_KEYS.envelope.concat(COMMON_ITEM_KEYS, ["envelope", "font"]));
    for (const k of Object.keys(o)) if (ITEM_KEYS.includes(k) && k !== "padding" && !used.has(k)) warn(p.concat(k), `"${k}" has no effect on an envelope`);
    T.size(o.width, p.concat("width"), "width");
    T.size(o.height, p.concat("height"), "height");
    for (const k of ["color", "fill_color", "display_color", "font"]) T.str(o[k], p.concat(k), k);
    T.bool(o.points, p.concat("points"), "points");
    const w = p.concat("envelope");
    const e = o.envelope;
    if (!isObj(e)) return err(w, "envelope must be an object");
    for (const k of Object.keys(e)) if (!ENVELOPE_KEYS.includes(k)) err(w.concat(k), `unknown key '${k}' (${ENVELOPE_KEYS.join(", ")})`);
    let timed = 0, mapped = 0;
    for (const k of ENVELOPE_KEYS) {
      const v = e[k];
      if (v === undefined || v === null) continue;
      const kw = w.concat(k);
      let ref = v;
      let values = null;
      if (isObj(v)) {
        for (const mk of Object.keys(v)) if (mk !== "param" && mk !== "values") err(kw.concat(mk), `unknown key '${mk}' (param, values)`);
        ref = v.param;
        if (!textLike(ref)) { err(kw.concat("param"), "param must be text"); continue; }
        if (v.values !== undefined && v.values !== null) {
          if (k === "sustain") err(kw.concat("values"), "a level takes no values (it is drawn at its normalised value)");
          else {
            values = v.values;
            validateValues(values, kw.concat("values"), err, warn);
            const unit = isObj(values) && typeof values.unit === "string" ? values.unit.replace(/ /g, "").toLowerCase() : "";
            if (!["ms", "s", "sec"].includes(unit)) err(kw.concat("values", "unit"), "values.unit must be ms or s");
          }
        }
      } else if (!textLike(v)) { err(kw, `${k} must be text`); continue; }
      ref = String(ref).trim();
      if (ref === "") err(kw, `${k} is empty`);
      else if (isAuto(ref)) err(kw, "an envelope takes named references, not auto:");
      if (k !== "sustain") {
        timed++;
        if (values) mapped++;
      }
    }
    const hasKey = (k) => e[k] !== undefined && e[k] !== null;
    if (!hasKey("attack")) err(w, "envelope needs attack");
    if (hasKey("sustain") && !hasKey("decay")) err(w, "sustain needs decay");
    if (hasKey("release") && hasKey("decay") && !hasKey("sustain")) err(w, "release after decay needs sustain");
    if (mapped !== 0 && mapped !== timed) err(w, "values on every time key or on none");
    T.choice(o.scale, p.concat("scale"), "scale", ENVELOPE_SCALES);
    if (typeof o.scale === "string" && o.scale.trim().toLowerCase() === "log" && mapped === 0) err(p.concat("scale"), "scale: log needs values on every time key");
  }

  /** ValueMap.display(): the displayed value at normalised n, for a values mapping (null when it can't be read). */
  function displayValue(v, n) {
    if (!isObj(v)) return null;
    n = Math.max(0, Math.min(1, n));
    if (Array.isArray(v.points)) {
      const p = v.points.filter((e) => Array.isArray(e) && isNum(e[0]) && isNum(e[1])).slice().sort((a, b) => a[1] - b[1]);
      if (p.length < 2) return null;
      for (let i = 1; i < p.length; i++) {
        const a = p[i - 1], b = p[i];
        if (n <= b[1]) return n <= a[1] ? a[0] : a[0] + ((b[0] - a[0]) * (n - a[1])) / (b[1] - a[1]);
      }
      return p[p.length - 1][0];
    }
    if (!isNum(v.min) || !isNum(v.max)) return null;
    const c = v.curve;
    if (typeof c === "string" && ["log", "exp"].includes(c.trim().toLowerCase())) return v.min * Math.pow(v.max / v.min, n);
    if (isObj(c) && isNum(c.pow)) return v.min + (v.max - v.min) * Math.pow(n, c.pow);
    return v.min + (v.max - v.min) * n;
  }

  /**
   * EnvelopeDisplay.points(): the breakpoints from (0, 0), x across `width`, y as level 0..1. Delay flat
   * at 0, attack to 1, hold flat at 1, decay to the sustain level (or 0), the held level a fifth of the
   * width (all of it when every time is 0), release to 0. values[key]: each segment's normalised value.
   */
  function envelopePoints(item, values, width) {
    const PLATEAU = 0.2; // EnvelopeDisplay.PLATEAU
    const segs = envelopeSegments(item);
    const log = typeof item.scale === "string" && item.scale.trim().toLowerCase() === "log";
    const has = (k) => segs.some((s) => s.key === k);
    const size = segs.map((s) => {
      if (s.key === "sustain") return 0;
      const n = isNum(values[s.key]) ? values[s.key] : 0;
      if (!s.values) return Math.max(0, n);
      const d = displayValue(s.values, n);
      const unit = String(s.values.unit || "").replace(/ /g, "").toLowerCase();
      const seconds = Math.max(0, d === null ? 0 : unit === "ms" ? d / 1000 : d);
      return log ? Math.log10(1 + seconds * 1000) : seconds;
    });
    const total = size.reduce((a, b) => a + b, 0);
    const plateauShape = has("sustain") || (has("release") && !has("decay"));
    const plateau = !plateauShape ? 0 : total > 0 ? width * PLATEAU : width;
    const scale = total > 0 ? (width - plateau) / total : 0;
    const sustain = has("sustain") ? Math.max(0, Math.min(1, isNum(values.sustain) ? values.sustain : 0)) : has("decay") ? 0 : 1;
    const pts = [[0, 0]];
    let x = 0, y = 0;
    segs.forEach((s, i) => {
      if (s.key === "sustain") return;
      if (s.key === "release" && plateau > 0) pts.push([(x += plateau), y]);
      x += size[i] * scale;
      y = s.key === "attack" || s.key === "hold" ? 1 : s.key === "decay" ? sustain : 0;
      pts.push([x, y]);
    });
    if (plateau > 0 && !has("release")) pts.push([x + plateau, y]);
    return pts;
  }

  /** An envelope's segments that are present, in drawing order: [{key, ref, values}]. */
  function envelopeSegments(item) {
    const e = isObj(item) && isObj(item.envelope) ? item.envelope : {};
    const out = [];
    for (const k of ENVELOPE_KEYS) {
      const v = e[k];
      if (v === undefined || v === null) continue;
      const ref = isObj(v) ? v.param : v;
      out.push({ key: k, ref: ref === undefined || ref === null ? "" : String(ref).trim(), values: isObj(v) && isObj(v.values) ? v.values : null });
    }
    return out;
  }

  function validateFormat(o, p, err) {
    if (o === undefined) return;
    if (typeof o === "string") {
      const s = o.trim().toLowerCase();
      if (s !== "percent" && s !== "plugin") err(p, 'value_format must be "percent", "plugin" or an object');
      return;
    }
    if (!isObj(o)) return err(p, "value_format must be an object");
    if (has(o, "map")) return validateChoices(o.map, p.concat("map"), err);
    if (has(o, "range")) {
      if (!Array.isArray(o.range)) err(p.concat("range"), "range must be a list");
      else if (o.range.length !== 2) err(p.concat("range"), "range must be [low, high]");
      else if (!o.range.every(isNum)) err(p.concat("range"), "range must be numbers");
    }
    if (o.decimals !== undefined) {
      if (!isNum(o.decimals)) err(p.concat("decimals"), "decimals must be a number");
      else if (Math.round(o.decimals) < 0 || Math.round(o.decimals) > 6) err(p.concat("decimals"), "decimals must be 0 to 6");
    }
    if (o.unit !== undefined && !textLike(o.unit)) err(p.concat("unit"), "unit must be text");
  }

  function validateChoices(o, p, err) {
    if (!Array.isArray(o)) return err(p, "must be a list");
    if (o.length === 0) err(p, "is empty");
    o.forEach((e, i) => {
      const w = p.concat(i);
      if (!Array.isArray(e)) return err(w, "must be a list");
      if (e.length !== 2) return err(w, "must be [name, value]");
      const [a, b] = e;
      const name = typeof a === "string" ? a : b;
      const value = typeof a === "string" ? b : a;
      if (!textLike(name)) return err(w, "must be text");
      if (!isNum(value)) return err(w, "must be a number");
      if (value < 0 || value > 1) err(w, "values are normalised, 0 to 1");
    });
  }

  function validateValues(o, p, err, warn) {
    if (!isObj(o)) return err(p, "values must be an object");
    unknownKeys(o, VALUES_KEYS, p, warn, "values");
    const steps = isNum(o.steps) ? Math.round(o.steps) : 0;
    if (o.steps !== undefined && !isNum(o.steps)) warn(p.concat("steps"), "steps isn't a number and is ignored");
    if (steps === 1 || steps < 0) err(p.concat("steps"), "steps must be 2 or more");
    if (o.unit !== undefined && typeof o.unit !== "string") warn(p.concat("unit"), "unit isn't text and is ignored");
    if (o.normalised !== undefined && typeof o.normalised !== "boolean") warn(p.concat("normalised"), "normalised isn't true or false; read as false");
    if (has(o, "points")) {
      const pts = o.points;
      if (!Array.isArray(pts) || pts.length < 2) return err(p.concat("points"), "points must be a list of at least two [display, normalised] pairs");
      const list = [];
      for (const e of pts) {
        if (!Array.isArray(e) || e.length !== 2 || !isNum(e[0]) || !isNum(e[1]) || e[1] < 0 || e[1] > 1)
          return err(p.concat("points"), "points: each is [display, normalised 0..1]");
        list.push(e);
      }
      list.sort((a, b) => a[1] - b[1]);
      for (let i = 1; i < list.length; i++) {
        if (list[i][1] === list[i - 1][1]) return err(p.concat("points"), `points: two points at normalised ${list[i][1]}`);
        if (Math.sign(list[i][0] - list[i - 1][0]) !== Math.sign(list[list.length - 1][0] - list[0][0]))
          return err(p.concat("points"), "points: the display values must rise (or fall) all the way");
      }
      for (const k of ["min", "max", "curve"]) if (has(o, k)) warn(p.concat(k), `"${k}" is ignored when points are given`);
      return;
    }
    if (!isNum(o.min) || !isNum(o.max)) return err(p, "values needs min and max (display values at 0 and 1), or points");
    if (o.min === o.max) return err(p, "values: min and max are the same");
    const c = o.curve;
    let log = false;
    if (typeof c === "string") {
      const s = c.trim().toLowerCase();
      if (s === "log" || s === "exp") log = true;
      else if (s !== "linear") return err(p.concat("curve"), 'curve must be linear, log, exp or {"pow": k}');
    } else if (isObj(c)) {
      if (!(isNum(c.pow) && c.pow > 0)) return err(p.concat("curve"), 'curve must be linear, log, exp or {"pow": k}');
    } else if (c !== undefined && c !== null) return err(p.concat("curve"), 'curve must be linear, log, exp or {"pow": k}');
    if (log && (o.min <= 0 || o.max <= 0)) err(p, "values: a log curve needs min and max above 0");
  }

  /** The framework reads a whole number where text is expected as its digits. */
  function textLike(v) {
    return typeof v === "string" || (isNum(v) && v === Math.round(v));
  }

  function typeChecks(err) {
    return {
      str(v, p, what, required) {
        if (v === undefined || v === null) {
          if (required) err(p, `${what} must be text`);
          return !required && v === undefined;
        }
        if (!textLike(v)) {
          err(p, `${what} must be text`);
          return false;
        }
        return true;
      },
      num(v, p, what) {
        if (!isNum(v)) {
          err(p, `${what} must be a number`);
          return false;
        }
        return true;
      },
      bool(v, p, what) {
        if (v !== undefined && v !== null && typeof v !== "boolean") err(p, `${what} must be true or false`);
      },
      size(v, p, what) {
        if (v === undefined || v === null) return;
        if (!isNum(v)) return err(p, `${what} must be a number`);
        const n = Math.round(v);
        if (n <= 0 || n > 2000) err(p, `${what} must be between 1 and 2000`);
      },
      gap(v, p, what) {
        if (v === undefined || v === null) return;
        if (!isNum(v)) return err(p, `${what} must be a number`);
        const n = Math.round(v);
        if (n < 0 || n > 2000) err(p, `${what} must be between 0 and 2000`);
      },
      choice(v, p, what, allowed) {
        if (v === undefined || v === null) return;
        if (!textLike(v)) return err(p, `${what} must be text`);
        if (!allowed.includes(String(v).trim().toLowerCase())) err(p, `${what} must be one of [${allowed.join(", ")}]`);
      },
      /** Layouts.padding(): a number, or [left, right, top, bottom]; each 0 to 200. */
      padding(v, p, what) {
        if (v === undefined || v === null) return;
        if (isNum(v)) {
          const n = Math.round(v);
          if (n < 0 || n > 200) err(p, `${what} must be 0 to 200`);
        } else if (Array.isArray(v)) {
          if (v.length !== 4) err(p, `${what} must be a number or [left, right, top, bottom]`);
          else v.forEach((n) => {
            if (!isNum(n)) err(p, `${what} must be a number`);
            else if (Math.round(n) < 0 || Math.round(n) > 200) err(p, `${what} must be 0 to 200`);
          });
        } else err(p, `${what} must be a number or [left, right, top, bottom]`);
      },
      place(o, p) {
        for (const [k, a, b, c] of [["align", "left", "center", "right"], ["valign", "top", "center", "bottom"]]) {
          if (o[k] === undefined || o[k] === null) continue;
          if (!textLike(o[k])) { err(p.concat(k), `${k} must be text`); continue; }
          const v = String(o[k]).trim().toLowerCase();
          if (![a, b, c, "centre"].includes(v)) err(p.concat(k), `${k} must be ${a}, ${b} or ${c}`);
        }
        this.gap(o.gap, p.concat("gap"), "gap");
        this.bool(o.align_knobs, p.concat("align_knobs"), "align_knobs");
      },
    };
  }

  function unknownKeys(o, known, p, warn, what) {
    for (const k of Object.keys(o)) if (!known.includes(k)) warn(p.concat(k), `"${k}" isn't a ${what} key; the framework ignores it`);
  }

  function matchesPlugin(match, plugin) {
    const name = String(match.name || "").trim();
    if (name !== "*" && name.toLowerCase() !== String(plugin.name || "").trim().toLowerCase()) return false;
    const vendor = String(match.vendor || "").trim();
    if (vendor && vendor.toLowerCase() !== String(plugin.vendor || "").trim().toLowerCase()) return false;
    const format = String(match.format || "").trim();
    return !format || format.toLowerCase() === String(plugin.format || "").toLowerCase();
  }

  // ---------- parameter references ----------

  const loose = (s) => String(s).replace(/[ _-]/g, "").toLowerCase();

  /** The sheet parameter an explicit reference names, as the framework resolves it; null when none. */
  function resolveRef(ref, params) {
    if (typeof ref !== "string") ref = String(ref);
    ref = ref.trim();
    if (isAuto(ref)) return null;
    if (ref.indexOf("|") < 0) return resolveOne(ref, params);
    for (const alt of ref.split("|")) {
      if (alt.trim() === "") continue;
      const p = resolveOne(alt.trim(), params);
      if (p) return p;
    }
    return null;
  }

  function resolveOne(ref, params) {
    if (ref.startsWith("#")) {
      const n = /^\s*-?\d+\s*$/.test(ref.substring(1)) ? parseInt(ref.substring(1), 10) : NaN;
      return n >= 1 && n <= params.length ? params[n - 1] : null;
    }
    const id = /^id:/i.test(ref) ? ref.substring(3).trim() : ref;
    for (const p of params) if (String(p.id).toLowerCase() === id.toLowerCase()) return p;
    for (const p of params) if (String(p.name).trim().toLowerCase() === ref.toLowerCase()) return p;
    const key = loose(ref);
    if (key) for (const p of params) if (loose(p.name) === key) return p;
    return null;
  }

  const isAuto = (ref) => /^auto:/i.test(String(ref).trim());

  /**
   * Resolves every reference of a layout against a sheet, in the framework's order (explicit ones
   * first, then automatic ones take the next unused parameter). Returns a Map path-string -> param|null.
   */
  function resolveLayout(layout, params) {
    const result = new Map();
    const refs = [];
    walkItems(layout, [], (item, path) => {
      const k = kindOf(item);
      if (k === "envelope") {
        for (const s of envelopeSegments(item)) refs.push({ item, path: path.concat("envelope", s.key), kind: k, ref: s.ref });
        return;
      }
      if (!k || k === "row" || k === "text" || k === "meter" || k === "spacer") return;
      refs.push({ item, path, kind: k, ref: item[k] });
    });
    const used = new Set();
    for (const r of refs) if (!isAuto(r.ref)) {
      const p = resolveRef(r.ref, params);
      if (p) used.add(p);
      result.set(r.path.join("/"), p);
    }
    for (const r of refs) if (isAuto(r.ref) && r.kind !== "envelope") {
      const kind = String(r.ref).trim().toLowerCase();
      const p = params.find((q) => !used.has(q) && (
        kind === "auto:knob" ? q.steps === 0 :
          kind === "auto:list" ? q.steps >= 3 && (q.step_names || []).length >= 3 :
            kind === "auto:toggle" ? q.steps === 2 : false)) || null;
      if (p) used.add(p);
      result.set(r.path.join("/"), p);
    }
    return result;
  }

  /** Calls fn(item, path) for every item of a layout (columns, footers, rows), depth first. */
  function walkItems(layout, base, fn) {
    const items = (list, p) => {
      if (!Array.isArray(list)) return;
      list.forEach((it, i) => {
        if (!isObj(it)) return;
        fn(it, p.concat(i));
        if (Array.isArray(it.row)) items(it.row, p.concat(i, "row"));
      });
    };
    const cols = (list, p) => {
      if (!Array.isArray(list)) return;
      list.forEach((c, i) => isObj(c) && items(c.items, p.concat(i, "items")));
    };
    if (!isObj(layout)) return;
    if (Array.isArray(layout.pages)) layout.pages.forEach((pg, i) => {
      if (!isObj(pg)) return;
      cols(pg.columns, base.concat("pages", i, "columns"));
      items(pg.footer, base.concat("pages", i, "footer"));
    });
    else {
      cols(layout.columns, base.concat("columns"));
      items(layout.footer, base.concat("footer"));
    }
  }

  function checkReferences(layout, p, sheet, err, warn) {
    const params = sheet.parameters || [];
    const resolved = resolveLayout(layout, params);
    let explicit = 0, matched = 0;
    const missing = [];
    const empty = [];
    walkItems(layout, p, (item, path) => {
      const k = kindOf(item);
      if (k === "envelope") { // each reference counts; a missing one leaves the display empty (Panels.resolve)
        for (const s of envelopeSegments(item)) {
          if (!s.ref || isAuto(s.ref)) continue;
          explicit++;
          if (resolved.get(path.slice(p.length).concat("envelope", s.key).join("/"))) matched++;
          else empty.push([path.concat("envelope", s.key), s.ref]);
        }
        return;
      }
      if (!k || k === "row" || k === "text" || k === "meter" || k === "spacer") return;
      const param = resolved.get(path.slice(p.length).join("/"));
      if (isAuto(item[k])) {
        if (!["auto:knob", "auto:list", "auto:toggle"].includes(String(item[k]).trim().toLowerCase()))
          err(path.concat(k), `unknown automatic reference '${item[k]}' (auto:knob, auto:list, auto:toggle)`);
        else if (!param) warn(path.concat(k), `no parameter left for ${item[k]}; the item is left out`);
        return;
      }
      explicit++;
      if (param) {
        matched++;
        if (k === "dropdown" && item.choices === undefined && (param.step_names || []).length < 2 && !(param.steps >= 2))
          err(path, `'${param.name}' is continuous: a dropdown on it needs "choices" (the layout falls back to Bitwig's page)`);
        if (k === "toggle" && param.steps > 2) warn(path, `"${param.name}" has ${param.steps} steps; a toggle only sets 0 or 1`);
      } else missing.push(path.concat(k));
    });
    const min = isNum(layout.min_resolved) ? Math.round(layout.min_resolved) : 100;
    const share = explicit ? (100 * matched) / explicit : 100;
    for (const m of missing) {
      const ref = getAt(layout, m.slice(p.length));
      if (min >= 100) err(m, `unknown parameter '${ref}': the whole layout falls back to Bitwig's page (set min_resolved to allow it)`);
      else warn(m, `unknown parameter '${ref}': the item is left out`);
    }
    for (const [m, ref] of empty) {
      if (min >= 100) err(m, `unknown parameter '${ref}': the whole layout falls back to Bitwig's page (set min_resolved to allow it)`);
      else warn(m, `unknown parameter '${ref}': the envelope is drawn empty`);
    }
    if ((missing.length || empty.length) && min < 100 && share < min)
      err(p, `only ${Math.round(share)}% of references match this sheet (min_resolved ${min}): the layout falls back to Bitwig's page`);
  }

  // ---------- paths ----------

  function getAt(o, path) {
    for (const k of path) {
      if (o === null || o === undefined) return undefined;
      o = o[k];
    }
    return o;
  }

  // ---------- parameter sheet hints ----------

  /** "66.3 %" -> {n: 66.3, unit: "%", int: false}; null when the text isn't a number with a unit. */
  function parseText(t) {
    if (typeof t !== "string") return null;
    const m = /^\s*([+-]?(?:\d+\.?\d*|\.\d+))\s*([A-Za-z%°]*)\s*$/.exec(t.replace("−", "-"));
    if (!m) return null;
    return { n: parseFloat(m[1]), unit: m[2], int: !/[.]/.test(m[1]), raw: t };
  }

  const UNIT_SCALE = { ms: ["ms", 1], s: ["ms", 1000], sec: ["ms", 1000], hz: ["Hz", 1], khz: ["Hz", 1000] };

  /** Two parsed ends in one unit: ms/s and Hz/kHz convert, "sec" reads as s. Null when they can't. */
  function commonUnit(a, b) {
    if (a.unit.toLowerCase() === b.unit.toLowerCase()) {
      const u = a.unit.toLowerCase() === "sec" ? "s" : a.unit;
      return { min: a.n, max: b.n, unit: u };
    }
    const ua = UNIT_SCALE[a.unit.toLowerCase()], ub = UNIT_SCALE[b.unit.toLowerCase()];
    if (!ua || !ub || ua[0] !== ub[0]) return null;
    // Keep the larger unit when both ends read well in it (5 ms .. 10 s -> ms keeps 5 exact).
    return { min: a.n * ua[1], max: b.n * ub[1], unit: ua[0] };
  }

  const round = (x, d) => {
    const f = Math.pow(10, d === undefined ? 6 : d);
    return Math.round(x * f) / f;
  };

  /** A value in the parameter's own unit at normalised n, for a curve. */
  function curveAt(min, max, n, curve) {
    if (curve === "log") return min * Math.pow(max / min, n);
    return min + (max - min) * n;
  }

  /**
   * What the sheet says about one parameter, as hints the user may act on; nothing is decided.
   * Returns {unreliable, noAnswer, toggle, integers: {from, to, count}|null, values: {min,max,unit}|null,
   * curveNote, stepped, notes: [text]}.
   */
  function paramHints(p) {
    const h = { unreliable: false, noAnswer: false, toggle: false, integers: null, values: null, curve: null, stepped: false, notes: [] };
    if (!p) return h;
    if (p.min_text === null || p.max_text === null || p.min_text === undefined || p.max_text === undefined) {
      h.noAnswer = true;
      h.notes.push("The plug-in didn't answer for its texts at 0 or 1: check it in Bitwig.");
    } else if (p.min_text === p.max_text) {
      h.unreliable = true;
      h.notes.push(`Texts at 0 and 1 are both "${p.min_text}": the plug-in's text isn't reliable (late or not asked). Check it in Bitwig; "refresh": "settle" and a values mapping usually help.`);
    }
    if (p.steps > 0) {
      h.stepped = true;
      if (p.steps === 2) h.toggle = true;
      h.notes.push(p.steps === 2 ? "Two steps: a toggle fits." :
        `${p.steps} steps${(p.step_names || []).length ? " with names" : ""}: a dropdown or stepped knob shows them without choices.`);
      return h;
    }
    if (h.unreliable || h.noAnswer) return h;
    const a = parseText(p.min_text), b = parseText(p.max_text);
    const onOff = /^(off|on)$/i;
    if ((a && b && a.unit === "" && b.unit === "" && a.n === 0 && b.n === 1) ||
      (onOff.test(p.min_text.trim()) && onOff.test(p.max_text.trim()) && p.min_text.trim().toLowerCase() !== p.max_text.trim().toLowerCase())) {
      h.toggle = true;
      h.notes.push(`Ends are exactly ${p.min_text} and ${p.max_text}: if it is an on/off switch, a toggle fits (check in Bitwig that it only ever sits at 0 or 1).`);
      return h;
    }
    if (a && b && a.int && b.int && a.unit === "" && b.unit === "" && Math.abs(b.n - a.n) >= 2 && Math.abs(b.n - a.n) <= 64) {
      const count = Math.abs(b.n - a.n) + 1;
      h.integers = { from: a.n, to: b.n, count, unit: a.unit };
      let fit = "";
      const t = parseText(p.text);
      if (t && t.int && isNum(p.value)) {
        const expect = a.n + (b.n - a.n) * p.value;
        fit = Math.abs(expect - t.n) < 0.5 ? ` The current value (${round(p.value, 4)} → "${p.text}") fits evenly spaced steps.` :
          ` The current value (${round(p.value, 4)} → "${p.text}") doesn't fit evenly spaced steps: check it.`;
      }
      h.notes.push(`Continuous, but its texts are whole numbers ${a.n} to ${b.n}: probably ${count} choices. A dropdown or a stepped knob may fit.${fit}`);
    }
    if (a && b && !h.integers) {
      const v = commonUnit(a, b);
      if (v && v.min !== v.max) {
        h.values = { min: round(v.min), max: round(v.max), unit: v.unit };
        const t = parseText(p.text);
        if (t && isNum(p.value) && p.value > 0.02 && p.value < 0.98) {
          const tv = commonUnit(t, t) && commonUnit(a, t) ? commonUnit(a, t).max : null;
          if (tv !== null) {
            const lin = curveAt(v.min, v.max, p.value, "linear");
            const fits = [`linear gives ${fmt(lin)}`];
            let best = Math.abs(lin - tv) <= Math.max(Math.abs(tv) * 0.03, 0.02 * Math.abs(v.max - v.min)) ? "linear" : null;
            if (v.min > 0 && v.max > 0) {
              const lg = curveAt(v.min, v.max, p.value, "log");
              fits.push(`log gives ${fmt(lg)}`);
              if (!best && Math.abs(lg - tv) <= Math.abs(tv) * 0.03) best = "log";
            }
            h.curve = best;
            h.notes.push(`At its current value ${round(p.value, 4)} the text is "${p.text}" (${fmt(tv)}${v.unit ? " " + v.unit : ""}); ${fits.join(", ")}` +
              (best ? `: "${best}" fits.` : ": neither fits; try points."));
          }
        }
      } else if (!v) {
        h.notes.push(`Ends "${p.min_text}" and "${p.max_text}" aren't in one unit the builder can read.`);
      }
    } else if (!a || !b) {
      if (/[-−]\s*∞|-inf/i.test(p.min_text + p.max_text)) h.notes.push(`An end is "-∞": a min/max mapping can't express it; use points, or leave typing to the plug-in.`);
    }
    return h;
  }

  function fmt(x) {
    const a = Math.abs(x);
    return String(a >= 100 ? round(x, 1) : a >= 1 ? round(x, 3) : round(x, 4));
  }

  /** Dropdown choices for whole-number texts from..to: [["0", 0], ["1", 0.034483], ...]. */
  function integerChoices(from, to, unit) {
    const out = [];
    const count = Math.abs(to - from);
    for (let i = 0; i <= count; i++) {
      const n = from + Math.sign(to - from) * i;
      out.push([String(n) + (unit ? " " + unit : ""), round(i / count)]);
    }
    return out;
  }

  // ---------- JSON writer ----------

  const BLOCK_KEYS = new Set(["columns", "items", "pages", "footer", "layouts", "row"]);

  /**
   * Writes JSON the way the hand-written layouts are: short objects and lists on one line
   * ("{ "knob": "Mix", "size": 34 }"), longer ones over several lines, lists of items always so.
   */
  function stringify(value, width) {
    width = width || 140;
    const inline = (v) => {
      if (Array.isArray(v)) return v.length === 0 ? "[]" : "[" + v.map(inline).join(", ") + "]";
      if (isObj(v)) {
        const ks = Object.keys(v);
        return ks.length === 0 ? "{}" : "{ " + ks.map((k) => JSON.stringify(k) + ": " + inline(v[k])).join(", ") + " }";
      }
      return JSON.stringify(v === undefined ? null : v);
    };
    const containsBlock = (v) => isObj(v) && Object.keys(v).some((k) => BLOCK_KEYS.has(k) && Array.isArray(v[k]) && v[k].length && v[k].some(isObj));
    const write = (v, indent, keyLen, forceBlock) => {
      const one = inline(v);
      if (!forceBlock && !containsBlock(v) && indent.length + keyLen + one.length <= width) return one;
      const next = indent + "  ";
      if (Array.isArray(v)) {
        if (v.length === 0) return "[]";
        if (!v.some(isObj) && v.every((e) => !Array.isArray(e) || inline(e).length <= 40)) {
          // numbers, text or short pairs ([name, value]): wrapped like words
          const lines = [];
          let line = "";
          for (const e of v.map(inline)) {
            if (line && next.length + line.length + e.length + 2 > width) { lines.push(line + ","); line = ""; }
            line += (line ? ", " : "") + e;
          }
          lines.push(line);
          return "[\n" + lines.map((l) => next + l).join("\n") + "\n" + indent + "]";
        }
        return "[\n" + v.map((e) => next + write(e, next, 0, false)).join(",\n") + "\n" + indent + "]";
      }
      if (isObj(v)) {
        const ks = Object.keys(v);
        if (ks[0] === "row" && Array.isArray(v.row) && v.row.length) {
          // { "row": [ items, one per line ], "gap": 8 }, as layouts are written by hand
          const rest = ks.slice(1).map((k) => ", " + JSON.stringify(k) + ": " + inline(v[k])).join("");
          return "{ \"row\": [\n" + v.row.map((e) => next + write(e, next, 0, false)).join(",\n") + "\n" + indent + "]" + rest + " }";
        }
        return "{\n" + ks.map((k) => {
          const key = JSON.stringify(k) + ": ";
          const block = BLOCK_KEYS.has(k) && Array.isArray(v[k]) && v[k].some(isObj);
          return next + key + write(v[k], next, key.length, block);
        }).join(",\n") + "\n" + indent + "}";
      }
      return one;
    };
    return write(value, "", 0, true) + "\n";
  }

  /** Deep equality with key order ignored; numbers compared as numbers. */
  function sameJson(a, b) {
    if (Array.isArray(a)) return Array.isArray(b) && a.length === b.length && a.every((x, i) => sameJson(x, b[i]));
    if (isObj(a)) {
      if (!isObj(b)) return false;
      const ka = Object.keys(a).sort(), kb = Object.keys(b).sort();
      return sameJson(ka, kb) && ka.every((k) => sameJson(a[k], b[k]));
    }
    return a === b;
  }

  /** Whether two objects list their keys in the same order, all the way down. */
  function sameKeyOrder(a, b) {
    if (Array.isArray(a)) return Array.isArray(b) && a.every((x, i) => sameKeyOrder(x, b[i]));
    if (isObj(a)) {
      const ka = Object.keys(a), kb = Object.keys(b || {});
      return ka.join("\u0000") === kb.join("\u0000") && ka.every((k) => sameKeyOrder(a[k], b[k]));
    }
    return true;
  }

  /** Whether a parsed JSON value is a parameter sheet. */
  function isSheet(o) {
    return isObj(o) && o.sheet === "plugin-panels-parameter-sheet" && Array.isArray(o.parameters);
  }

  root.PPB = {
    KINDS, KNOB_SIZES, REFRESH, TYPING, FORMATS, METER_STYLES, KNOB_STYLES, FONTS, COLOURS, BUTTON_STYLES,
    LAYOUT_KEYS, PAGE_KEYS, COLUMN_KEYS, ITEM_KEYS, VALUES_KEYS, KIND_KEYS, COMMON_ITEM_KEYS,
    isObj, isNum, has, kindOf, setKey, orderFor, validate, resolveRef, resolveLayout, walkItems, isAuto, getAt,
    ENVELOPE_KEYS, ENVELOPE_SCALES, envelopeSegments, MODE_TARGETS, STRIPE_HEIGHT, TOOLBAR_PARTS, TOOLBAR_PIECES, BAR_SIZE, BAR_GAP, BAR_ORDER, BAR_ENTRIES, BAR_TINT, MODE_MIX, expandOrder, heightIgnored, DROPDOWN_STYLES, toolbarParts, currentMode, modeTargets, hasStripe, envelopePoints, displayValue,
    parseText, paramHints, integerChoices, stringify, sameJson, sameKeyOrder, isSheet, matchesPlugin, round,
  };
})(typeof window !== "undefined" ? window : globalThis);
