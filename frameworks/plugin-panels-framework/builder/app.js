/*
 * Plug-in Panels layout builder: the page. The layout is edited as its own JSON object, so keys the
 * builder doesn't change keep their values and their order; new keys go where the schema lists them.
 * Paths ([..."columns", 0, "items", 2]) are relative to the layout being edited.
 */
(function () {
  "use strict";
  const PPB = window.PPB, PV = window.PPBPreview, PT = window.PPBTheme;
  const { isObj, isNum, has, kindOf, getAt, setKey } = PPB;
  const $ = (id) => document.getElementById(id);
  const STORE = "plugin-panels-builder";
  const DEV = /[?&]dev\b/.test(location.search); // developer path: theme sheets (see loadText)

  const S = {
    file: null, // the layout file as JSON: one layout, or {"layouts": [...]}
    li: 0, // which layout of {"layouts": [...]}
    fileName: "",
    sheet: null,
    sheetName: "",
    theme: null, // an imported theme sheet (Generate Theme Sheet), kept between visits
    sel: [],
    page: 0,
    undo: [],
    redo: [],
    editing: null, // the field being typed in (one undo step per field visit)
    pending: new WeakMap(), // item -> {values} prefilled from the sheet, not yet confirmed
    zoom: window.PPBTheme.uiScale,
    chain: "",
    guides: { rulers: true, zones: true, boxes: false, fit: true, edges: false }, // the preview's guides (kept between visits)
    overlay: null, // a screenshot (data URL) over the preview; not kept between visits
    overlayOpacity: 0.5,
    overlayX: 0,
    overlayY: 0,
    openParams: new Set(),
    problems: [],
  };

  // ---------- state ----------

  function layout() {
    if (!S.file) return null;
    return Array.isArray(S.file.layouts) ? S.file.layouts[S.li] : S.file;
  }
  /** Path prefix of the edited layout inside the file (for problems). */
  const prefix = () => (S.file && Array.isArray(S.file.layouts) ? ["layouts", S.li] : []);
  const usesPages = () => Array.isArray((layout() || {}).pages);
  const pageBase = () => (usesPages() ? ["pages", Math.min(S.page, layout().pages.length - 1)] : []);
  const params = () => (S.sheet && S.sheet.parameters) || [];

  function save() {
    try {
      localStorage.setItem(STORE, JSON.stringify({ file: S.file, li: S.li, fileName: S.fileName, sheet: S.sheet, sheetName: S.sheetName, theme: S.theme, zoom: S.zoom, chain: S.chain, guides: S.guides }));
    } catch (e) { /* storage unavailable: nothing kept between visits */ }
  }

  function restore() {
    try {
      const o = JSON.parse(localStorage.getItem(STORE) || "null");
      if (!o) return false;
      if (o.theme && !PT.isSheet(o.theme)) PT.apply(o.theme); // a BTE theme (an older theme sheet is dropped)
      Object.assign(S, { file: o.file || null, li: o.li || 0, fileName: o.fileName || "", sheet: o.sheet || null, sheetName: o.sheetName || "", theme: o.theme && !PT.isSheet(o.theme) ? o.theme : null, zoom: o.zoom || PT.uiScale, chain: o.chain || "", guides: Object.assign(S.guides, o.guides || {}) });
      return !!S.file;
    } catch (e) {
      return false;
    }
  }

  function snapshot() {
    S.undo.push(JSON.stringify({ file: S.file, li: S.li }));
    if (S.undo.length > 300) S.undo.shift();
    S.redo = [];
  }

  function undoRedo(from, to) {
    if (!from.length) return;
    to.push(JSON.stringify({ file: S.file, li: S.li }));
    const o = JSON.parse(from.pop());
    S.file = o.file;
    S.li = o.li;
    S.editing = null;
    if (!getAt(layout(), S.sel)) S.sel = [];
    renderAll();
  }

  /** A change made by a button or a drop: one undo step, then everything is drawn again. */
  function edit(fn, sel) {
    snapshot();
    S.editing = null;
    const r = fn();
    if (sel !== undefined) select(sel, true);
    else if (r && typeof r === "object" && !Array.isArray(r)) select(pathOf(r) || S.sel, true);
    renderAll();
  }

  /** A change typed in a field: one undo step per field visit; the inspector keeps its focus. */
  function fieldEdit(id, fn) {
    if (S.editing !== id) {
      snapshot();
      S.editing = id;
    }
    fn();
    renderAll({ inspector: false });
  }

  // ---------- paths and nodes ----------

  /** "layout", "page", "column", "item", "footer" or null. */
  function nodeType(path) {
    if (path.length === 0) return "layout";
    const last = path[path.length - 1];
    if (last === "footer") return "footer";
    if (typeof last !== "number") return null;
    const parent = path[path.length - 2];
    if (parent === "pages") return "page";
    if (parent === "columns") return "column";
    if (parent === "items" || parent === "row" || parent === "footer") return "item";
    return null;
  }

  /** The node a path points into (for a problem at "…values.min", the knob). */
  function nodeOf(path) {
    for (let n = path.length; n >= 0; n--) {
      const p = path.slice(0, n);
      if (nodeType(p) && (nodeType(p) === "footer" || getAt(layout(), p) !== undefined)) return p;
    }
    return [];
  }

  /** The path of an object in the layout, found by identity. */
  function pathOf(target) {
    let found = null;
    const visit = (o, p) => {
      if (found) return;
      if (o === target) { found = p; return; }
      if (Array.isArray(o)) o.forEach((e, i) => visit(e, p.concat(i)));
      else if (isObj(o)) for (const k of ["pages", "columns", "items", "row", "footer"]) if (Array.isArray(o[k])) visit(o[k], p.concat(k));
    };
    visit(layout(), []);
    return found;
  }

  const key = (p) => p.join("/");
  const startsWith = (p, q) => q.length <= p.length && q.every((k, i) => p[i] === k);

  function select(path, keepInspector) {
    if (key(path) !== key(S.sel) && S.align) { S.align.picking = false; S.alignShow = null; } // Align's guides are for the item picked
    S.sel = path;
    S.editing = null;
    const i = path.indexOf("pages");
    if (i === 0 && isNum(path[1])) S.page = path[1];
    if (!keepInspector) renderAll();
  }

  // ---------- loading ----------

  function loadText(text, name) {
    let json;
    try {
      json = JSON.parse(text);
    } catch (e) {
      // not JSON: a BTE theme (a .bte file, or its text pasted)
      if (/\.bte$/i.test(name || "") || /^\s*[^/:{][^:]*:\s*#[0-9a-f]{3,8}\b/im.test(text)) return loadBte(text, name);
      return "Not valid JSON: " + e.message;
    }
    if (PT.isSheet(json)) {
      // developer path only (index.html?dev): preview a Generate Theme Sheet before it becomes default-theme.js
      if (!DEV) return "This is a theme sheet. The builder takes BTE themes (.bte); theme sheets only regenerate default-theme.js (see the README).";
      PT.applySheet(json);
      S.theme = null;
      renderAll();
      toast("Developer: theme sheet previewed over the default theme (not kept)");
      return null;
    }
    if (PPB.isSheet(json)) {
      S.sheet = json;
      S.sheetName = name || (json.plugin ? `${json.plugin.name} ${json.plugin.format}` : "pasted sheet");
      S.openParams.clear();
      if (!S.file) newLayout(true);
      renderAll();
      toast(`Parameter sheet loaded: ${json.parameters.length} parameters of ${json.plugin ? json.plugin.name : "the plug-in"}`);
      return null;
    }
    if (isObj(json) && (has(json, "match") || has(json, "layouts") || has(json, "columns") || has(json, "pages"))) {
      edit(() => {
        S.file = json;
        S.li = 0;
        S.fileName = name || "";
        S.page = 0;
        S.view = null; // a device starts in Layout mode
      }, []);
      const n = Array.isArray(json.layouts) ? `${json.layouts.length} layouts` : "layout";
      toast(`Opened ${n}${name ? " from " + name : ""}`);
      return null;
    }
    return "This is neither a parameter sheet nor a layout.";
  }

  /** A BTE theme file: its colours over the default theme, by Bitwig's colour names. */
  function loadBte(text, name) {
    const theme = PT.parseBte(text, String(name || "").replace(/\.bte$/i, "") || "BTE theme");
    if (!Object.keys(theme.colors).length) return "No colours found: a BTE theme has lines like \"Window background: #2B2D30\".";
    S.theme = theme;
    PT.apply(theme);
    renderAll();
    const im = PT.imported;
    toast(`${im.name}: ${im.applied} colours applied` + (im.unknown.length ? `, ${im.unknown.length} names outside Bitwig ${im.bitwig}'s colour table (not used by panels)` : "") +
      (im.invalid.length ? `, ${im.invalid.length} invalid` : ""));
    return null;
  }

  function newLayout(quiet) {
    const p = S.sheet && S.sheet.plugin;
    const L = { match: p ? { vendor: p.vendor, name: p.name } : { name: "" }, columns: [newColumn()] };
    const apply = () => {
      S.file = L;
      S.li = 0;
      S.fileName = "";
      S.page = 0;
    };
    if (quiet) {
      apply();
      S.sel = [];
    } else edit(apply, []);
  }

  /** Everything back to the beginning: no layout, no parameter sheet, no overlay, the default theme. */
  function startOver() {
    if (S.file && !confirm("Start over? The layout, the parameter sheet and the overlay are cleared, and the theme goes back to Bitwig's default. Download the layout first if you want to keep it.")) return;
    S.sheet = null;
    S.sheetName = "";
    S.openParams.clear();
    S.theme = null;
    PT.apply(null);
    S.zoom = PT.uiScale;
    $("zoom").value = String(S.zoom);
    S.overlay = null;
    $("overlay-tools").hidden = true;
    S.chain = "";
    $("chain").value = "";
    $("param-filter").value = "";
    S.undo = [];
    S.redo = [];
    newLayout(true);
    renderAll();
    toast("Started over");
  }

  function readFiles(files) {
    const list = Array.from(files || []);
    // sheets first, so a layout opened with its sheet is checked against it
    Promise.all(list.map((f) => f.text().then((t) => [f.name, t]))).then((pairs) => {
      pairs.sort((a, b) => (/\.parameters\.json$/i.test(b[0]) ? 1 : 0) - (/\.parameters\.json$/i.test(a[0]) ? 1 : 0));
      for (const [name, text] of pairs) {
        const err = loadText(text, name);
        if (err) toast(`${name}: ${err}`);
      }
    });
  }

  // ---------- structure edits ----------

  function ensureList(obj, k) {
    if (!Array.isArray(obj[k])) setKey(obj, k, [], PPB.orderFor(obj === layout() ? "layout" : "page"));
    return obj[k];
  }

  /** Where an added item goes: [list, index]. */
  /**
   * Where an added control goes: [list, index]. Rows are assumed: after the selected item (in its row),
   * at the end of the selected row, or at the end of the last row of the selected (or last) column.
   * A new row starts only with Add > Row.
   */
  function insertionPoint(kind) {
    const L = layout();
    const t = nodeType(S.sel);
    const node = getAt(L, S.sel);
    if (kind === "row") { // a new row: after the selected row or item's row, else at the end of the column
      if (t === "item") {
        const p = S.sel[S.sel.length - 2] === "row" ? S.sel.slice(0, -2) : S.sel;
        return [getAt(L, p.slice(0, -1)), p[p.length - 1] + 1];
      }
      const col = t === "column" ? node : lastColumn(L);
      return [col.items || (col.items = []), (col.items || []).length];
    }
    if (kind === "spacer") return spacerPoint(L, t, node);
    if (t === "item" && kindOf(node) === "row") return [node.row || (node.row = []), (node.row || []).length];
    if (t === "item") return [getAt(L, S.sel.slice(0, -1)), S.sel[S.sel.length - 1] + 1];
    if (t === "column") { const r = rowOf(node); return [r, r.length]; }
    if (t === "footer") {
      const owner = getAt(L, S.sel.slice(0, -1));
      return [ensureList(owner, "footer"), owner.footer.length];
    }
    const r = rowOf(lastColumn(L));
    return [r, r.length];
  }

  /**
   * Where Add › Spacer goes: [list, index, replaced]. A spacer grows along its parent, so it goes into the column
   * (its spare height): after the selected row, or after the row of the selected item. It goes into a row only
   * when the user targets one that has spare width to give: an item selected in a row with its own width, an
   * item or row inside a row, or the footer. An empty row selected in a column becomes the spacer.
   */
  function spacerPoint(L, t, node) {
    const sel = S.sel;
    if (t === "column") return [node.items || (node.items = []), node.items.length];
    if (t === "footer") { const owner = getAt(L, sel.slice(0, -1)); return [ensureList(owner, "footer"), owner.footer.length]; }
    if (t !== "item") { const c = lastColumn(L); return [c.items || (c.items = []), c.items.length]; }
    const list = getAt(L, sel.slice(0, -1)), at = sel[sel.length - 1], parentKey = sel[sel.length - 2];
    if (parentKey === "row") {
      const row = getAt(L, sel.slice(0, -2));
      if (kindOf(node) === "row" || (isNum(row.width) && row.width > 0)) return [list, at + 1]; // into the row: its spare width
      let p = sel.slice(0, -2); // else into the column (or footer) after the outermost row
      while (p[p.length - 2] === "row") p = p.slice(0, -2);
      return [getAt(L, p.slice(0, -1)), p[p.length - 1] + 1];
    }
    if (kindOf(node) === "row" && parentKey === "items" && !(node.row || []).length) return [list, at, 1]; // an empty row: replaced
    return [list, at + 1];
  }

  /** A spacer in a column-level row moved into the column (its spare height): before the row when it led it, else after; an emptied row goes. */
  function spacerToColumn(spPath) {
    const L = layout();
    const rowPath = spPath.slice(0, -2), row = getAt(L, rowPath), colItems = getAt(L, rowPath.slice(0, -1));
    const ri = rowPath[rowPath.length - 1], si = spPath[spPath.length - 1];
    const [sp] = row.row.splice(si, 1);
    if (!row.row.length) colItems.splice(ri, 1, sp);
    else colItems.splice(si === 0 ? ri : ri + 1, 0, sp);
    return sp;
  }

  function lastColumn(L) {
    const cols = ensureList(getAt(L, pageBase()), "columns");
    if (!cols.length) cols.push(newColumn());
    return cols[cols.length - 1];
  }

  // The builder's defaults, written out in the JSON so the framework draws what the preview shows: controls
  // go side by side in rows, each a cell of the same width; knobs are cells of one size (VintageVerb's:
  // knob 34 in a 54-wide cell), label, knob and value centred, a longer text never widening its cell.
  const CELL_WIDTH = 54, KNOB_SIZE = 34, ROW_GAP = 4;

  function newItem(kind, param) {
    const ref = param ? refFor(param) : "";
    switch (kind) {
      case "meter": return { meter: "output", style: "needle" };
      case "envelope": return { envelope: { attack: ref } };
      case "text": return { text: "TEXT", font: "Bold" };
      case "spacer": return { spacer: true }; // Layouts.item(): no other keys
      case "row": return newRow();
      case "knob": return { knob: ref, size: KNOB_SIZE, width: CELL_WIDTH };
      case "slider": return { slider: ref };
      default: return { [kind]: ref, width: CELL_WIDTH }; // dropdown, toggle, value: one cell wide
    }
  }

  const newRow = () => ({ row: [], gap: ROW_GAP });
  const newColumn = () => ({ items: [newRow()] });

  /** The row new controls go into in a column: its last item when that's a row, else a new row at its end. */
  function rowOf(col) {
    if (!Array.isArray(col.items)) col.items = [];
    const last = col.items[col.items.length - 1];
    if (kindOf(last) === "row") return last.row || (last.row = []);
    const r = newRow();
    col.items.push(r);
    return r.row;
  }

  /** How the builder names a parameter: its name, or its ID when another parameter has the same name. */
  function refFor(p) {
    const same = params().filter((q) => q.name.trim().toLowerCase() === p.name.trim().toLowerCase());
    return same.length > 1 ? "id:" + p.id : p.name;
  }

  function add(kind) {
    const L = layout();
    if (!L) return;
    if (kind === "column") return addColumn();
    if (kind === "page") return addPage();
    if (kind === "footer") {
      const owner = getAt(L, pageBase());
      return edit(() => {
        const it = newItem("dropdown");
        ensureList(owner, "footer").push(it);
        return it;
      });
    }
    edit(() => {
      const [list, at, replaced] = insertionPoint(kind);
      const it = newItem(kind);
      list.splice(at, replaced || 0, it);
      return it;
    });
  }

  /** Adds a control for a sheet parameter, with what the sheet suggests waiting for confirmation. */
  function addForParam(p, kind, extra) {
    if (!layout()) newLayout(true);
    edit(() => {
      const [list, at] = insertionPoint();
      const it = newItem(kind, p);
      if (extra) Object.assign(it, extra);
      list.splice(at, 0, it);
      const h = PPB.paramHints(p);
      if (kind === "knob" && h.values && !it.values) S.pending.set(it, { values: Object.assign({}, h.values, h.curve === "log" ? { curve: "log" } : {}) });
      return it;
    });
  }

  function addColumn() {
    const L = layout();
    edit(() => {
      const t = nodeType(S.sel);
      const col = newColumn();
      let colPath = null;
      if (t === "column") colPath = S.sel;
      else if (t === "item") colPath = S.sel.slice(0, S.sel.lastIndexOf("columns") + 2);
      if (colPath && colPath.length >= 2 && colPath[colPath.length - 2] === "columns") {
        getAt(L, colPath.slice(0, -1)).splice(colPath[colPath.length - 1] + 1, 0, col);
      } else ensureList(getAt(L, pageBase()), "columns").push(col);
      return col;
    });
  }

  function addPage() {
    const L = layout();
    edit(() => {
      if (!usesPages()) toPages(L);
      const pg = { name: "Page " + (L.pages.length + 1), columns: [newColumn()] };
      L.pages.push(pg);
      S.page = L.pages.length - 1;
      return pg;
    });
  }

  /** columns and footer become the first page. */
  function toPages(L) {
    const pg = { name: "Main", columns: Array.isArray(L.columns) ? L.columns : [{ items: [] }] };
    if (has(L, "footer")) pg.footer = L.footer;
    delete L.footer;
    const keys = Object.keys(L);
    const entries = keys.map((k) => (k === "columns" ? ["pages", [pg]] : [k, L[k]]));
    if (!keys.includes("columns")) entries.push(["pages", [pg]]);
    for (const k of keys) delete L[k];
    for (const [k, v] of entries) L[k] = v;
  }

  /** A single page becomes columns (and footer) again. */
  function fromPages(L) {
    const pg = L.pages[0];
    const keys = Object.keys(L);
    const entries = [];
    for (const k of keys) {
      if (k === "pages") {
        entries.push(["columns", pg.columns || []]);
        if (pg.footer) entries.push(["footer", pg.footer]);
      } else if (k !== "page_chooser") entries.push([k, L[k]]);
    }
    for (const k of keys) delete L[k];
    for (const [k, v] of entries) L[k] = v;
  }

  function removeNode(path) {
    const L = layout();
    const t = nodeType(path);
    if (t === "layout") return;
    if (t === "footer") {
      edit(() => delete getAt(L, path.slice(0, -1)).footer, path.slice(0, -1));
      return;
    }
    const list = getAt(L, path.slice(0, -1));
    const i = path[path.length - 1];
    edit(() => {
      list.splice(i, 1);
      if (t === "page") S.page = Math.max(0, Math.min(S.page, list.length - 1));
    }, list.length > 1 ? path.slice(0, -1).concat(Math.max(0, Math.min(i, list.length - 2))) : nodeOf(path.slice(0, -2)));
  }

  function moveBy(path, d) {
    const list = getAt(layout(), path.slice(0, -1));
    const i = path[path.length - 1];
    if (!Array.isArray(list) || i + d < 0 || i + d >= list.length) return;
    edit(() => {
      const [n] = list.splice(i, 1);
      list.splice(i + d, 0, n);
      return n;
    });
  }

  function duplicate(path) {
    const list = getAt(layout(), path.slice(0, -1));
    const i = path[path.length - 1];
    edit(() => {
      const copy = JSON.parse(JSON.stringify(list[i]));
      list.splice(i + 1, 0, copy);
      return copy;
    });
  }

  function wrapInRow(path) {
    const list = getAt(layout(), path.slice(0, -1));
    const i = path[path.length - 1];
    edit(() => {
      const row = { row: [list[i]] };
      list[i] = row;
      return row;
    });
  }

  function unwrapRow(path) {
    const list = getAt(layout(), path.slice(0, -1));
    const i = path[path.length - 1];
    const kids = list[i].row || [];
    edit(() => {
      list.splice(i, 1, ...kids);
      return kids[0] || null;
    }, kids.length ? undefined : path.slice(0, -1).length ? nodeOf(path.slice(0, -1)) : []);
  }

  /**
   * Drag and drop. payload: {path} of a node, or {param} from the sheet. target: a node path;
   * "into" drops into a container (column, row, footer, page), otherwise before the target.
   */
  function drop(payload, target, into) {
    const L = layout();
    const tType = nodeType(target);
    const src = payload.path ? getAt(L, payload.path) : null;
    const sType = payload.path ? nodeType(payload.path) : "item";
    let list = null, at = 0;
    if (sType === "item") {
      if (into && tType === "column") { const c = getAt(L, target); list = c.items || (c.items = []); at = list.length; }
      else if (into && tType === "item" && kindOf(getAt(L, target)) === "row") { const r = getAt(L, target); list = r.row; at = list.length; }
      else if (into && tType === "footer") { const o = getAt(L, target.slice(0, -1)); list = ensureList(o, "footer"); at = list.length; }
      else if (tType === "item") { list = getAt(L, target.slice(0, -1)); at = target[target.length - 1]; }
    } else if (sType === "column") {
      if (into && tType === "page") { const p = getAt(L, target); list = p.columns || (p.columns = []); at = list.length; }
      else if (tType === "column") { list = getAt(L, target.slice(0, -1)); at = target[target.length - 1]; }
    } else if (sType === "page" && tType === "page") {
      list = L.pages;
      at = target[target.length - 1];
    }
    if (!list) return toast("It can't go there");
    if (payload.path && startsWith(target, payload.path)) return; // into itself
    if (payload.param) return addAt(payload.param, list, at);
    const from = getAt(L, payload.path.slice(0, -1));
    const fi = payload.path[payload.path.length - 1];
    edit(() => {
      if (from === list && fi < at) at--;
      from.splice(fi, 1);
      list.splice(at, 0, src);
      return src;
    });
  }

  function addAt(p, list, at) {
    const h = PPB.paramHints(p);
    const kind = h.toggle ? "toggle" : h.stepped && p.steps > 2 ? "dropdown" : "knob";
    edit(() => {
      const it = newItem(kind, p);
      list.splice(at, 0, it);
      if (kind === "knob" && h.values) S.pending.set(it, { values: Object.assign({}, h.values, h.curve === "log" ? { curve: "log" } : {}) });
      return it;
    });
  }

  // ---------- problems ----------

  function computeProblems() {
    const L = layout();
    if (!S.file) return [];
    const pre = prefix();
    const out = PPB.validate(S.file, S.sheet).map((p) => Object.assign({}, p, { path: startsWith(p.path, pre) ? p.path.slice(pre.length) : null }));
    // fonts and colours the builder doesn't know (others in the theme work too; the preview approximates them)
    const fontKeys = ["font", "value_font", "header_font", "title_font", "label_font"];
    const colourKeys = ["color", "value_color", "header_color", "title_color", "label_color", "background", "fill_color", "display_color"];
    // with a theme sheet the names are the theme's own: a name it lacks is one Bitwig doesn't know either
    const themed = !!PT.imported;
    const check = (o, p) => {
      for (const k of fontKeys) if (typeof o[k] === "string" && o[k].trim() && !PV.font(o[k]).known)
        out.push(themed ? { level: "warning", path: p.concat(k), message: `font "${o[k]}" isn't in the theme: Bitwig logs it and uses its default font` }
          : { level: "info", path: p.concat(k), message: `font "${o[k]}" isn't one the builder knows: the preview shows Default (Bitwig logs an unknown name and uses its default font)` });
      for (const k of colourKeys) if (typeof o[k] === "string" && o[k].trim() && !PV.colour(o[k]) && !(k === "display_color" && o[k].trim().toLowerCase() === "none"))
        out.push(themed ? { level: "warning", path: p.concat(k), message: `colour "${o[k]}" isn't in the theme: Bitwig logs it and leaves it out` }
          : { level: "info", path: p.concat(k), message: `colour "${o[k]}" isn't one the builder knows: the preview leaves it out (a theme colour name works in Bitwig if the theme has it)` });
    };
    if (isObj(L)) {
      check(L, []);
      if (isObj(L.stripe)) check({ color: L.stripe.color }, ["stripe"]);
      if (isObj(L.mode_colors) && isObj(L.mode_colors.map))
        for (const [k, v] of Object.entries(L.mode_colors.map)) {
          if (isObj(v)) for (const [t, c] of Object.entries(v)) check({ color: c }, ["mode_colors", "map", k, t]);
          else check({ color: v }, ["mode_colors", "map", k]);
        }
      const cols = (list, p) => Array.isArray(list) && list.forEach((c, i) => isObj(c) && check(c, p.concat(i)));
      if (Array.isArray(L.pages)) L.pages.forEach((pg, i) => isObj(pg) && cols(pg.columns, ["pages", i, "columns"]));
      else cols(L.columns, ["columns"]);
      const styleCheck = (name, list, p, what) => {
        if (typeof name !== "string" || !name.trim() || list.includes(name.trim())) return;
        out.push({ level: themed ? "warning" : "info", path: p, message: `${what} "${name}" isn't one of the theme's: Bitwig logs it and uses its own` });
      };
      if (isObj(L.toolbar)) styleCheck(L.toolbar.style, buttonStyleList(), ["toolbar", "style"], "button style");
      PPB.walkItems(L, [], (it, p) => {
        if (kindOf(it) === "spacer") spacerCheck(L, p);
        if (kindOf(it) === "dropdown") styleCheck(it.style, dropdownStyleList(), p.concat("style"), "dropdown style");
        if (kindOf(it) === "toggle") styleCheck(it.style, buttonStyleList(), p.concat("style"), "button style");
        check(it, p);
        if (isObj(it.label) && it.label.icon) out.push({ level: "info", path: p.concat("label"), message: `icon "${it.label.icon}" is one of Bitwig's files: the preview shows a placeholder` });
      });
    }
    /** A spacer that can't take anything where it is (Panels: a Filler in a row with no spare width takes 0). */
    function spacerCheck(L, p) {
      const parent = p[p.length - 2];
      if (parent === "footer") return out.push({ level: "warning", path: p, message: "a spacer in the footer takes nothing: the footer row is as wide as its items (put it in a footer row with a width)" });
      if (parent !== "row") return;
      const rowPath = p.slice(0, -2), row = getAt(L, rowPath);
      if (rowPath[rowPath.length - 2] !== "items") return; // a row inside a row: its width depends on its cell
      if (row.row.every((c) => kindOf(c) === "spacer"))
        return out.push({ level: "warning", path: p, message: "a row holding only spacers takes spare width, not the column's spare height: select the spacer and press Move to column" });
      const col = getAt(L, rowPath.slice(0, -2));
      if (!(isNum(row.width) && row.width > 0) && !(isObj(col) && isNum(col.width) && col.width > 0))
        out.push({ level: "warning", path: p, message: "this spacer's row has no spare width (no width on the row or its column): it takes nothing; give the row a width, or Move to column for spare height" });
    }
    const order = { error: 0, warning: 1, info: 2 };
    return out.sort((a, b) => order[a.level] - order[b.level]);
  }

  // ---------- rendering ----------

  function renderAll(opts) {
    opts = opts || {};
    S.problems = computeProblems();
    renderTop();
    renderOutline();
    renderPreview();
    renderProblems();
    if (!opts.keepJson) renderJson();
    renderParams();
    if (opts.inspector !== false) renderInspector();
    save();
  }

  // the names the inspector offers: the imported theme's, else the framework README's
  const fontList = () => PT.fontNames || PPB.FONTS;
  const colourList = () => PT.colourNames || PPB.COLOURS;
  const buttonStyleList = () => PT.buttonStyles || PPB.BUTTON_STYLES;
  const dropdownStyleList = () => PT.dropdownStyles || PPB.DROPDOWN_STYLES;
  const knobStyleList = () => PT.knobStyleNames || PPB.KNOB_STYLES;

  /** The Basics panel, with the numbers of the theme and the framework in use. */
  function fillBasics() {
    const F = PT.framework, def = PT.fonts["default"] || {};
    const set = (id, v) => { const e = $(id); if (e) e.textContent = v; };
    set("bx-scale", Math.round(PT.uiScale * 100) + "%");
    set("bx-scale-px", fmt(PT.uiScale));
    set("bx-avail", fmt(PT.sizes.available));
    set("bx-toolbar", fmt(PT.sizes.toolbar));
    set("bx-usable", fmt(PT.sizes.available - PT.sizes.toolbar));
    set("bx-minzoom", Math.round(F.min_zoom * 100) + "%");
    set("bx-maxlayout", fmt((PT.sizes.available - PT.sizes.toolbar) / F.min_zoom));
    set("bx-row", fmt(PT.sizes.row));
    set("bx-label", fmt(def.height || 13.2));
    set("bx-label2", fmt(def.height || 13.2));
    set("bx-line2", F.line_gap);
    set("bx-pad", F.padding); set("bx-lgap", F.layout_gap); set("bx-cgap", F.column_gap); set("bx-rgap", F.row_gap);
    set("bx-pgap", F.page_gap); set("bx-line", F.line_gap); set("bx-bg", F.background_padding);
    set("bx-knob", ["tiny", "small", "normal", "large", "huge"].map((k) => `${k} ${fmt(PT.sizes.knob[k].w)}×${fmt(PT.sizes.knob[k].h)}`).join(", "));
  }

  function renderTop() {
    const t = $("theme-status");
    const im = PT.imported;
    t.textContent = !im ? "Theme: built-in, partly approximate" : im.bte ? `Theme: ${im.name} (BTE)` : `Theme: Bitwig ${im.bitwig} default`;
    t.title = im && im.bte ? `${im.applied} colours applied over Bitwig ${im.bitwig}'s default theme` +
      (im.unknown.length ? `; outside Bitwig's colour table, not used by panels (EQ and Note FX keys, or unknown): ${im.unknown.slice(0, 12).join(", ")}${im.unknown.length > 12 ? ", ..." : ""}` : "") +
      (im.invalid.length ? `; invalid: ${im.invalid.join(", ")}` : "") + (im.gradient === false ? "; Gradient: false" : "") +
      ". Fonts, knob sizes and spacing are the default theme's."
      : "Another theme: drop its BTE theme file (.bte) here";
    $("b-theme-clear").hidden = !im || im.builtIn;
    $("b-undo").disabled = !S.undo.length;
    $("b-redo").disabled = !S.redo.length;
    const pick = $("layout-pick");
    if (S.file && Array.isArray(S.file.layouts)) {
      pick.hidden = false;
      pick.innerHTML = "";
      S.file.layouts.forEach((l, i) => pick.append(new Option(`Layout ${i + 1}: ${(l && l.match && l.match.name) || "?"}`, i)));
      pick.value = String(S.li);
    } else pick.hidden = true;
    $("sheet-name").textContent = S.sheet ? "· " + S.sheetName : "";
    $("b-sheet-clear").hidden = !S.sheet;
  }

  function problemLevels() {
    const m = new Map();
    for (const p of S.problems) {
      if (!p.path || p.level === "info") continue;
      const k = key(nodeOf(p.path));
      if (!m.has(k) || p.level === "error") m.set(k, p.level);
    }
    return m;
  }

  // outline
  let dragPayload = null;

  function renderOutline() {
    const box = $("outline");
    box.innerHTML = "";
    const L = layout();
    if (!L) { box.innerHTML = '<div class="empty-note">Open or paste a parameter sheet or a layout, or press New.</div>'; return; }
    const levels = problemLevels();
    const resolved = PPB.resolveLayout(L, params());
    const row = (path, kind, name, opts) => {
      const r = document.createElement("div");
      r.className = "on-row" + (key(path) === key(S.sel) ? " sel" : "") + (levels.get(key(path)) ? " " + levels.get(key(path)) : "");
      r.dataset.path = JSON.stringify(path);
      const k = document.createElement("span"); k.className = "kind"; k.textContent = kind;
      const n = document.createElement("span"); n.className = "name"; n.textContent = name;
      r.append(k, n);
      if (opts && opts.badge) { const b = document.createElement("span"); b.className = "badge muted"; b.textContent = opts.badge; r.append(b); }
      if (opts && opts.draggable) {
        r.draggable = true;
        r.addEventListener("dragstart", (e) => { dragPayload = { path }; e.dataTransfer.setData("text/plain", "node"); document.body.classList.add("dragging"); });
      }
      return r;
    };
    const zone = (path) => {
      const z = document.createElement("div");
      z.className = "on-zone";
      z.dataset.path = JSON.stringify(path);
      z.dataset.into = "1";
      return z;
    };
    const items = (list, p, parent) => {
      const kids = document.createElement("div");
      kids.className = "on-children";
      (list || []).forEach((it, i) => {
        const ip = p.concat(i);
        const k = kindOf(it);
        if (k === "row") {
          kids.append(row(ip, "row", `${(it.row || []).length} items`, { draggable: true }));
          kids.append(items(it.row, ip.concat("row"), ip));
        } else {
          let name = k === "envelope" ? PPB.envelopeSegments(it).map((s) => s.key[0].toUpperCase()).join("") || "(empty)" : k === "spacer" ? "spare space" : k ? String(it[k]) : "?";
          if (k && it.label !== undefined) name = (typeof it.label === "string" ? it.label : it.label === false ? "(no label)" : isObj(it.label) ? "(icon)" : "(bad label)") + " ← " + name;
          const prm = resolved.get(key(ip));
          kids.append(row(ip, k || "?", name, { draggable: true, badge: prm ? "#" + prm.index : k === "text" || k === "meter" || k === "spacer" ? "" : S.sheet ? "?" : "" }));
        }
      });
      kids.append(zone(parent));
      return kids;
    };
    const columns = (cols, p, pagePath) => {
      const wrap = document.createElement("div");
      (cols || []).forEach((c, i) => {
        const cp = p.concat(i);
        const h = isObj(c) && c.header !== undefined && c.header !== "" ? `"${c.header}"` : `${i + 1}`;
        wrap.append(row(cp, "column", h, { draggable: true, badge: isObj(c) && isNum(c.width) ? c.width + "u" : "" }));
        wrap.append(items(isObj(c) ? c.items : [], cp.concat("items"), cp));
      });
      if (pagePath) wrap.append(zone(pagePath));
      return wrap;
    };
    box.append(row([], "layout", (L.match && L.match.name) || "(no name)"));
    const top = document.createElement("div");
    top.className = "on-children";
    if (usesPages()) {
      L.pages.forEach((pg, i) => {
        const pp = ["pages", i];
        top.append(row(pp, "page", (pg && pg.name) || "(no name)", { draggable: true }));
        const inner = document.createElement("div");
        inner.className = "on-children";
        inner.append(columns(pg && pg.columns, pp.concat("columns"), pp));
        inner.append(row(pp.concat("footer"), "footer", pg && Array.isArray(pg.footer) ? `${pg.footer.length} items` : "(none)"));
        inner.append(items(pg && pg.footer, pp.concat("footer"), pp.concat("footer")));
        top.append(inner);
      });
    } else {
      top.append(columns(L.columns, ["columns"], null));
      top.append(row(["footer"], "footer", Array.isArray(L.footer) ? `${L.footer.length} items` : "(none)"));
      top.append(items(L.footer, ["footer"], ["footer"]));
    }
    box.append(top);
  }

  function setupOutlineEvents() {
    const box = $("outline");
    box.addEventListener("click", (e) => {
      const r = e.target.closest(".on-row");
      if (r) select(JSON.parse(r.dataset.path));
    });
    const targetOf = (e) => e.target.closest(".on-row, .on-zone");
    box.addEventListener("dragover", (e) => {
      const t = targetOf(e);
      if (!t || !dragPayload) return;
      e.preventDefault();
      box.querySelectorAll(".drop-before, .drop-into").forEach((n) => n.classList.remove("drop-before", "drop-into"));
      const into = t.dataset.into || dropsInto(JSON.parse(t.dataset.path), e, t);
      t.classList.add(into ? "drop-into" : "drop-before");
    });
    box.addEventListener("dragleave", (e) => { const t = targetOf(e); if (t) t.classList.remove("drop-before", "drop-into"); });
    box.addEventListener("drop", (e) => {
      const t = targetOf(e);
      if (!t || !dragPayload) return;
      e.preventDefault();
      const path = JSON.parse(t.dataset.path);
      const into = !!t.dataset.into || dropsInto(path, e, t);
      const p = dragPayload;
      endDrag();
      drop(p, path, into);
    });
  }

  /** Dropping on a container row (column, row, footer, page) puts it inside, unless near its top edge. */
  function dropsInto(path, e, el) {
    const t = nodeType(path);
    const n = getAt(layout(), path);
    const container = t === "footer" || (t === "column" && (!dragPayload.path || nodeType(dragPayload.path) === "item")) ||
      (t === "item" && kindOf(n) === "row" && (!dragPayload.path || nodeType(dragPayload.path) === "item")) ||
      (t === "page" && dragPayload.path && nodeType(dragPayload.path) === "column");
    if (!container) return false;
    if (t === "footer" || (t === "page" && nodeType(dragPayload.path) === "column")) return true;
    const r = el.getBoundingClientRect();
    return e.clientY > r.top + r.height * 0.35;
  }

  function endDrag() {
    dragPayload = null;
    document.body.classList.remove("dragging");
    document.querySelectorAll(".drop-before, .drop-into, .pv-drop").forEach((n) => n.classList.remove("drop-before", "drop-into", "pv-drop"));
  }

  // preview
  /**
   * The preview inside the device's real limits: the body is the chain's available height (HeightFit:
   * the body's visible height, measured in Bitwig; 213 units in the user's log), Bitwig's toolbar on top,
   * the layout's zones labelled beside it, free room or overflow, the zoom Bitwig applies (HeightFit:
   * (available - toolbar) / layout, not below min_zoom), rulers in layout units and Bitwig's default width.
   */
  function renderPreview() {
    const box = $("preview");
    box.innerHTML = "";
    const L = layout();
    const note = $("stage-note");
    if (!L) { note.textContent = ""; $("preview-wrap").style.width = $("preview-wrap").style.height = "0"; return; }
    let res;
    try {
      res = PV.render({ layout: L, sheet: S.sheet, page: S.page, selected: key(S.sel), problems: problemLevels(), mode: S.modePreview, view: S.view });
    } catch (e) {
      note.textContent = "The preview can't draw this layout yet: " + e.message;
      return;
    }
    S.page = res.page;
    if (res.viewing) return previewOtherView(res);
    const G = S.guides;
    const M = { top: G.rulers ? 22 : 6, left: G.rulers ? 30 : 6, right: (G.zones ? 190 : 8) + (guideColumn() ? 92 : 0), bottom: 30 }; // room for the guides, in units
    const frame = document.createElement("div");
    frame.className = "pv-frame";
    frame.style.padding = `${M.top}px ${M.right}px ${M.bottom}px ${M.left}px`;
    frame.append(res.node);
    box.append(frame);
    if (G.boxes) res.node.classList.add("pv-boxes");

    const body = res.body, padded = res.padded;
    const TB = res.toolbar ? PT.sizes.toolbar : 0;
    const avail = parseFloat(S.chain) > 0 ? parseFloat(S.chain) : PT.sizes.available;
    let layoutH = padded.offsetHeight;
    // HeightFit: what the body needs is its minimum height. The right-hand bar sits beside the page area, so it
    // counts only when its button stack is taller than the layout (then it sets the minimum)
    const bp = PPB.toolbarParts(L);
    const barEl = res.body.querySelector(".pv-rightbar");
    let barMin = 0;
    if (bp.right && barEl) {
      const kids = [...barEl.children];
      const buttons = kids.filter((k) => k.classList.contains("pv-barbtn")).length;
      barMin = 2 * bp.gap + buttons * bp.size + Math.max(0, kids.length - 1) * bp.gap; // gaps above, under and between
    }
    let needed = Math.max(TB + layoutH, barMin);
    // fill_height (HeightFit.filled): when the layout fits, the shown page's row of columns takes the spare
    // height, so the columns (backgrounds, dividers) reach the bottom (or the footer); a layout too tall is zoomed as it is
    let spare = 0;
    const colsRow = res.view.classList.contains("pv-columnsrow") ? res.view : res.view.querySelector(".pv-columnsrow");
    if (L.fill_height === true && colsRow && needed < avail) {
      spare = avail - needed;
      colsRow.style.minHeight = colsRow.offsetHeight + spare + "px";
      layoutH = padded.offsetHeight;
      needed = Math.max(TB + layoutH, barMin);
    }
    for (const s of res.view.querySelectorAll(".pv-spaceritem")) s.title = s.classList.contains("pv-spacer-h")
      ? `spacer: ${fmt(s.offsetWidth)} units of the row's spare width` : `spacer: ${fmt(s.offsetHeight)} units of the column's spare height`;
    const barRight = bp.right ? bp.barWidth : 0; // the free and overflow zones cover the page area only
    // HeightFit: the layout (not the toolbar) zooms down until it fits, to at most min_zoom
    const minZoom = PT.framework.min_zoom;
    const fitZoom = needed > avail ? Math.max(minZoom, (layoutH - (needed - avail)) / layoutH) : 1; // HeightFit: the share of the layout that fits
    const zoom = G.fit ? fitZoom : 1;
    body.style.height = avail + "px";
    if (zoom < 1) {
      padded.style.transformOrigin = "0 0";
      padded.style.transform = `scale(${zoom})`;
      padded.style.marginBottom = `${-layoutH * (1 - zoom)}px`;
    }
    const drawnBottom = TB + layoutH * zoom;

    // zones, top to bottom (unscaled measurements, the layout's scaled by the zoom)
    const top0 = body.getBoundingClientRect().top;
    const scale = body.getBoundingClientRect().height / body.offsetHeight || 1; // the stage's own CSS scale (set below: none yet)
    const at = (el) => (el.getBoundingClientRect().top - top0) / scale;
    const ht = (el) => el.getBoundingClientRect().height / scale;
    const padTop = parseFloat(padded.style.paddingTop) || 0, padBottom = parseFloat(padded.style.paddingBottom) || 0;
    const zones = [];
    if (TB) zones.push({ name: "Bitwig's toolbar", y: 0, h: TB, cls: "z-bitwig", why: `Bitwig's plug-in toolbar (tabs, search): ${TB} units, measured from Bitwig's log` });
    const stripe = padded.querySelector(".pv-stripe");
    const stripeH = stripe ? stripe.offsetHeight : 0;
    if (stripeH) zones.push({ name: "stripe", y: TB, h: stripeH * zoom, cls: "z-title", why: "The layout's stripe, above its top padding (height " + stripeH + ")" });
    zones.push({ name: "layout padding top", y: TB + stripeH * zoom, h: (padTop - stripeH) * zoom, cls: "z-pad", select: [],
      why: `The layout's padding, top (${fmt(padTop - stripeH)}; default ${PT.framework.padding}): click to edit the layout` });
    if (res.titleLine) {
      const arrowOnly = L.title === false && res.pages < 2;
      zones.push({ name: arrowOnly ? "arrow line" : "title line", y: at(res.titleLine), h: ht(res.titleLine), cls: "z-title",
        why: arrowOnly ? "No toolbar row and no title: a line for Bitwig's container button (→) alone" :
          "The plug-in's name (title_font), the page chooser" + (res.toolbar ? "" : " and Bitwig's container button (→)") + " (Bitwig's row height " + PT.sizes.row + ")" });
      zones.push({ name: "page gap", y: at(res.titleLine) + ht(res.titleLine), h: PT.framework.page_gap * zoom, cls: "z-pad",
        why: `The framework's spacing under the title line (${PT.framework.page_gap}, fixed: Panels.build, page.TMF(3))` });
    }
    const cols = res.view.classList.contains("pv-columnsrow") ? res.view : res.view.querySelector(".pv-columnsrow");
    if (cols) zones.push({ name: spare ? `columns (${fmt(spare)} spare inside)` : "columns", y: at(cols), h: ht(cols), cls: "z-cols",
      why: spare ? `fill_height: the columns reach the bottom; ${fmt(spare)} units of them are free, placed by each column's valign (a column with spacers gives them its spare height instead)` : "The columns: as tall as the tallest column" + (cols.querySelector(".pv-spacer-v") ? "; a column with spacers gives them its spare height" : "") });
    const fgap = res.view.querySelector(".pv-footergap"), frow = res.view.querySelector(".pv-footerrow");
    if (fgap) zones.push({ name: "footer gap", y: at(fgap), h: ht(fgap), cls: "z-pad", select: [], why: "The layout's footer_gap: click to edit the layout" });
    if (frow) zones.push({ name: "footer", y: at(frow), h: ht(frow), cls: "z-foot", why: "The footer row: as tall as its tallest item" });
    const restY = frow ? at(frow) + ht(frow) : cols ? at(cols) + ht(cols) : TB + padTop * zoom;
    // under the page, itemised: the framework's page gap and its 1-unit probe (fixed), then the layout's bottom padding
    {
      let y = restY;
      const part = (name, h, why, select) => { zones.push({ name, y, h: h * zoom, cls: "z-pad", why, select }); y += h * zoom; };
      part("page gap", PT.framework.page_gap, `The framework's spacing between the page and the probe under it (${PT.framework.page_gap}, fixed: Panels.build, page.TMF(3))`);
      part("probe", 1, "The framework's 1×1 probe under the page, which starts the height fit (1, fixed: HeightFit.probe)");
      part("layout padding bottom", padBottom, `The layout's padding, bottom (${fmt(padBottom)}; default ${PT.framework.padding}): click to edit the layout`, []);
    }
    if (drawnBottom <= avail + 0.01) zones.push({ name: "free", y: drawnBottom, h: avail - drawnBottom, cls: "z-free", why: "Room left in the device's height" });
    else zones.push({ name: "cut off", y: avail, h: drawnBottom - avail, cls: "z-over", why: `Taller than the device even at ${Math.round(minZoom * 100)}%: Bitwig cuts this off` });

    // free room and overflow inside the body
    if (drawnBottom < avail - 0.5) body.append(el("pv-zone-free", `top:${drawnBottom}px;height:${avail - drawnBottom}px;right:${barRight}px`, `free: ${fmt(avail - drawnBottom)} units`));
    if (drawnBottom > avail + 0.5) body.append(el("pv-zone-over", `top:${avail}px;height:${drawnBottom - avail}px;right:${barRight}px`, `cut off: ${fmt(drawnBottom - avail)} units`));
    if (!G.fit && needed > avail) body.append(el("pv-zone-line", `top:${avail}px`, ""));
    if (zoom < 1) body.append(el("pv-zoom-badge", `top:${TB + 2}px`, `Bitwig zooms this layout to ${Math.round(zoom * 100)}%`));

    const width = body.offsetWidth; // free: the framework sets the body to exactly the layout's width

    // zone labels beside the device
    if (G.zones) {
      const col = el("pv-zones", `left:${body.offsetWidth + 12}px`, "");
      const head = el("pv-zones-head", "", `Height: fixed at ${fmt(avail)}`);
      head.title = "Every device in Bitwig's chain has this height; it can't be changed. The parts below add up to it.";
      col.append(head);
      let next = -Infinity; // labels at least 9 units apart, each joined to its zone by a short line
      for (const z of zones) {
        if (z.h < 0.05 && z.cls !== "z-over") continue;
        const r = el("pv-zone " + z.cls, `top:${z.y}px;height:${Math.max(0.5, z.h)}px`, "");
        r.title = z.why;
        col.append(r);
        const mid = z.y + z.h / 2, y = Math.max(mid, next);
        next = y + 9;
        const t = el("pv-zone-text " + z.cls + (z.select ? " pickable" : ""), `top:${y}px`, `${z.name} ${fmt(z.h)}`);
        t.title = z.why;
        if (z.select) t.dataset.select = JSON.stringify(z.select);
        col.append(t);
        if (Math.abs(y - mid) > 0.5) col.append(el("pv-zone-lead", `top:${Math.min(mid, y)}px;height:${Math.abs(y - mid)}px`, ""));
      }
      const total = el("pv-zone-total", `top:${avail}px`, `◀ ${fmt(avail)} available`); // the device's bottom edge
      total.title = parseFloat(S.chain) > 0 ? "Set in the Available box" : "The device chain's height in the user's Bitwig 6.1.4 log (height available 213 units): the framework measures it per panel";
      col.append(total);
      body.append(col);
    }

    // rulers in layout units, from the body's top left
    if (G.rulers) {
      const span = width + 20;
      const top = el("pv-ruler pv-ruler-x", `width:${span}px`, "");
      for (let u = 0; u <= span; u += 10) top.append(el(u % 50 ? "tick" : "tick big", `left:${u}px`, u % 50 ? "" : String(u)));
      top.append(el("pv-ruler-mark", `left:${width}px`, `${fmt(width)}`));
      body.append(top);
      const left = el("pv-ruler pv-ruler-y", `height:${Math.max(avail, drawnBottom) + 10}px;left:${-(body.offsetLeft + 26)}px`, "");
      for (let u = 0; u <= Math.max(avail, drawnBottom) + 10; u += 10) left.append(el(u % 50 ? "tick" : "tick big", `top:${u}px`, u % 50 ? "" : String(u)));
      body.append(left);
    }

    box.style.transform = `scale(${S.zoom})`;
    const w = frame.offsetWidth, h = frame.offsetHeight;
    $("preview-wrap").style.width = w * S.zoom + "px";
    $("preview-wrap").style.height = h * S.zoom + "px";
    S.deviceOffset = [(M.left) * S.zoom, (M.top) * S.zoom];
    S.measure = { body, padded, zoom };
    S.measuredColumn = guideColumn();
    drawGuides();
    renderModePicker(L);
    renderViewPicker(L);
    renderOverlay();
    const pct = Math.round(fitZoom * 100);
    S.fit = { needed, avail, zoom: fitZoom, cut: TB + layoutH * minZoom > avail && needed > avail };
    note.textContent = `Page ${res.page + 1} of ${res.pages}: ${fmt(width)} units wide; the layout needs ${fmt(TB + layoutH)} of ${fmt(avail)}` +
      (TB ? ` (layout ${fmt(layoutH)} + toolbar ${TB})` : bp.right ? " (the bar beside it takes no height)" : "") +
      (needed > avail ? `; Bitwig zooms it to ${pct}%${TB + layoutH * minZoom > avail ? " and cuts off the bottom" : ""}` : "") +
      (barMin > avail ? `. The bar's buttons stack ${fmt(barMin)} high, more than the device: that sets the zoom (fewer or smaller buttons fix it)` : "") +
      `. Drawn at ${Math.round(S.zoom * 100)}% UI scale.` + (S.sheet ? "" : " Load the parameter sheet to see values and check references.");

    function el(cls, style, text) {
      const e = document.createElement("div");
      e.className = cls;
      if (style) e.setAttribute("style", style);
      if (text) e.textContent = text;
      return e;
    }
  }

  const fmt = (v) => String(Math.round(v * 10) / 10);

  // ---------- alignment guides ----------

  /** An item's (or column's) preview node: the innermost element with its path (a background wraps it with the same path). */
  function itemNode(path) {
    const p = JSON.stringify(path);
    const all = [...document.querySelectorAll(".pv-body [data-path]")].filter((n) => n.dataset.path === p);
    return all[all.length - 1] || null;
  }

  /**
   * The edges of the item (or column) at `path`, in layout units from the device body's top, as Bitwig lays the
   * panel out before HeightFit zooms it: [{name, y}]. A knob: its top, label top, ring top (the arc's outer edge:
   * KnobGeometry scales small's arc_radius and arc_width to the box; the radius taken as the stroke's centre line),
   * ring centre, value top and bottom. A dropdown: its top, the menu's top and centre, its bottom. Anything else:
   * top, centre, bottom.
   */
  function edgesOf(path) {
    const m = S.measure;
    let n = itemNode(path);
    if (!m || !n || !m.body.isConnected) return [];
    if (n.classList.contains("pv-padbox") && n.firstElementChild) n = n.firstElementChild; // a row's or column's content, inside its padding
    const br = m.body.getBoundingClientRect(), pr = m.padded.getBoundingClientRect();
    const s = br.height / m.body.offsetHeight || 1; // the stage's scale (UI scale)
    const y = (v) => (pr.top - br.top) / s + (v - pr.top) / (s * m.zoom); // the layout itself is zoomed by HeightFit
    const r = n.getBoundingClientRect();
    const out = [];
    const add = (name, v) => out.push({ name, y: Math.round(y(v) * 10) / 10 });
    const kind = nodeType(path) === "item" ? kindOf(getAt(layout(), path)) : "column";
    add("top", r.top);
    if (kind === "knob") {
      const k = n.querySelector(".pv-knob"), sv = k && k.querySelector("svg");
      const parts = [...n.children].filter((c) => !c.classList.contains("pv-spacer"));
      const ki = parts.indexOf(k);
      if (ki > 0) { const lr = parts[0].getBoundingClientRect(); add("label top", lr.top); add("label bottom", lr.bottom); }
      if (sv) {
        const kr = k.getBoundingClientRect(), u = kr.height / k.offsetHeight; // screen px per unit of the knob's box
        const top = parseFloat(sv.dataset.ringTop), c = parseFloat(sv.dataset.centre);
        add("ring top", kr.top + top * u);
        add("ring centre", kr.top + c * u);
        add("ring bottom", kr.top + (2 * c - top) * u); // the ring's full circle; the arc leaves its bottom open
      }
      if (ki >= 0 && ki < parts.length - 1) { const vr = parts[parts.length - 1].getBoundingClientRect(); add("value top", vr.top); add("value bottom", vr.bottom); }
    } else if (kind === "dropdown" && n.querySelector(".pv-dropdown") && n.querySelector(".pv-dropdown") !== n) {
      const f = n.querySelector(".pv-dropdown").getBoundingClientRect();
      add("menu top", f.top);
      add("menu centre", (f.top + f.bottom) / 2);
      add("menu bottom", f.bottom);
    } else if (kind !== "row" && kind !== "column") add("centre", (r.top + r.bottom) / 2);
    add("bottom", r.bottom);
    return out;
  }

  /** Draws the guides again; the whole preview when their label column comes or goes (the margin changes). */
  function syncGuides() {
    if (guideColumn() !== S.measuredColumn) renderPreview();
    else drawGuides();
  }

  /** Whether guide labels need their column beside the device (the guides are on, or Align is picking). */
  const guideColumn = () => !!(S.guides.edges || S.alignShow);

  /**
   * The alignment guides: faint lines across the device body at item edges, their labels (the height in layout
   * units) in a column of their own right of the device (and of the height zones), never over the panel. With
   * "guides" on: the selected item's edges and the hovered item's. While Align is picking (S.alignShow): the two
   * edges it would line up, whether or not guides are on.
   */
  function drawGuides() {
    const m = S.measure;
    if (!m || !m.body.isConnected) return;
    for (const g of m.body.querySelectorAll(".pv-guide, .pv-guide-label, .pv-guide-lead")) g.remove();
    const sets = [];
    const usable = (p) => p && ["item", "column"].includes(nodeType(p));
    if (S.alignShow && usable(S.alignShow.path) && usable(S.alignShow.target)) {
      sets.push({ path: S.alignShow.path, cls: "align", only: S.alignShow.own, who: "this" });
      sets.push({ path: S.alignShow.target, cls: "align target", only: S.alignShow.edge, who: "target" });
    } else if (S.guides.edges) {
      if (usable(S.sel)) sets.push({ path: S.sel, cls: "" });
      if (usable(S.hoverPath) && key(S.hoverPath) !== key(S.sel)) sets.push({ path: S.hoverPath, cls: "hover" });
    }
    if (!sets.length) return;
    const br = m.body.getBoundingClientRect(), s = br.height / m.body.offsetHeight || 1;
    const padTop = (m.padded.getBoundingClientRect().top - br.top) / s;
    const width = m.body.offsetWidth, labelX = width + (S.guides.zones ? 186 : 8); // beyond the height zones' labels
    const labels = [];
    for (const set of sets) for (const e of edgesOf(set.path)) {
      if (set.only && e.name !== set.only) continue;
      const y = padTop + (e.y - padTop) * m.zoom; // where the zoomed layout draws it
      const g = document.createElement("div");
      g.className = "pv-guide " + set.cls + (/ring|menu/.test(e.name) ? " key" : "");
      g.style.top = y + "px";
      m.body.append(g);
      labels.push({ y, text: `${set.who ? set.who + ": " : ""}${e.name} ${fmt(e.y)}`, cls: set.cls });
    }
    labels.sort((a, b) => a.y - b.y);
    let next = -Infinity; // labels at least 8 units apart, each joined to its line's height by a short lead
    for (const l of labels) {
      const at = Math.max(l.y, next);
      next = at + 8;
      const lead = document.createElement("div"); // from the device's right edge to the label, faint
      lead.className = "pv-guide-lead " + l.cls;
      lead.style.cssText = `left:${width}px;width:${labelX - width}px;top:${l.y}px`;
      m.body.append(lead);
      const t = document.createElement("div");
      t.className = "pv-guide-label " + l.cls;
      t.style.cssText = `left:${labelX}px;top:${at}px`;
      t.textContent = l.text;
      m.body.append(t);
    }
  }

  /** The items on the shown page that have a preview node, as [path, name]. */
  function pageItems() {
    const L = layout(), base = pageBase(), out = [];
    PPB.walkItems(L, [], (it, p) => {
      if (!startsWith(p, base) || !itemNode(p)) return;
      const k = kindOf(it);
      if (k === "spacer") return;
      const name = k === "row" ? `row (${(it.row || []).length} items)` : typeof it.label === "string" ? it.label :
        k === "text" ? String(it.text) : k === "envelope" || k === "meter" ? k : String(it[k] || "").split("|")[0];
      out.push([p, `${k}: ${name}`]);
    });
    return out;
  }

  /** The item's padding sides [l, r, t, b] from a number, a list or nothing. */
  const padSides = (v) => (isNum(v) ? [v, v, v, v] : Array.isArray(v) && v.length === 4 ? v.slice() : [0, 0, 0, 0]);

  /**
   * How the item at `path` sits in its column (Panels.columns): "bottom" when a spacer above it pushes it down,
   * or its column has valign bottom with fill_height; "centre" for valign center with fill_height; else "top".
   * Null in the footer.
   */
  function anchorOf(path) {
    const L = layout(), ci = path.lastIndexOf("columns");
    if (ci < 0) return null;
    const col = getAt(L, path.slice(0, ci + 2)), i = path[ci + 3], items = Array.isArray(col.items) ? col.items : [];
    if (items.slice(0, i).some((it) => kindOf(it) === "spacer")) return "bottom";
    if (items.some((it) => kindOf(it) === "spacer")) return "top";
    const v = String(col.valign || "").trim().toLowerCase();
    if (L.fill_height === true && v === "bottom") return "bottom";
    if (L.fill_height === true && (v === "center" || v === "centre")) return "centre";
    return "top";
  }

  /**
   * Moves the item at `path` down by `d` units (up when negative) with padding on the side it is pushed from: top
   * padding for an item that sits from the top, bottom padding for one that sits from the bottom (anchorOf). The
   * padding goes on the item when it is a row, on the row it sits in, on its column when it is the column's first
   * (top) or last (bottom) item, else on a row of its own made around it (its column align kept on that row).
   * Returns {item, used, applied} (applied: the nominal move down, in whole units) or {error}; `dry` changes nothing.
   */
  function shift(path, d, dry) {
    const L = layout(), it = getAt(L, path), parent = path[path.length - 2], i = path[path.length - 1];
    const side = anchorOf(path) === "bottom" ? "bottom" : "top";
    let node, where;
    if (kindOf(it) === "row") [node, where] = [it, "this row"];
    else if (parent === "row") [node, where] = [getAt(L, path.slice(0, -2)), "its row"];
    else if (parent !== "items") return { error: "Footer items line up on the footer row: align a row in the footer instead" };
    else if (side === "top" ? i === 0 : i === getAt(L, path.slice(0, -1)).length - 1) [node, where] = [getAt(L, path.slice(0, -2)), "its column"];
    else [node, where] = [null, "a row of its own"];
    const sides = padSides(node ? node.padding : undefined); // a row of its own starts with none
    const k = side === "top" ? 2 : 3;
    const v = Math.round(sides[k] + (side === "top" ? d : -d));
    if (v < 0) return { error: `It would have to move ${fmt(-v)} units ${side === "top" ? "higher" : "lower"} than its ${side} padding allows: align the other item to this one instead` };
    if (v > 200) return { error: "That needs more than 200 units of padding" };
    if (side === "bottom" && v > sides[3]) { // up: as far as the spacers above it give (beyond, the column only grows)
      const ci = path.lastIndexOf("columns"), col = JSON.stringify(path.slice(0, ci + 2)).slice(0, -1);
      const spare = [...document.querySelectorAll(".pv-body .pv-spacer-v")].filter((s) => s.dataset.path.startsWith(col + ",\"items\","))
        .filter((s) => JSON.parse(s.dataset.path)[ci + 3] < path[ci + 3]).reduce((n, s) => n + s.offsetHeight, 0);
      if (v - sides[3] > spare + 0.5) return { error: `Only ${fmt(spare)} units of spare height above it (its spacer): align the other item to this one instead, or take the spacer out` };
    }
    const used = `${side} padding ${v} on ${where}`, applied = side === "top" ? v - sides[2] : sides[3] - v;
    if (dry || !applied) return { item: path, used, applied };
    let item = path;
    if (!node) { // a row of its own, in its place
      node = { row: [it], gap: ROW_GAP };
      if (it.align !== undefined) { node.align = it.align; delete it.align; }
      getAt(L, path.slice(0, -1)).splice(i, 1, node);
      item = path.concat("row", 0);
    }
    sides[k] = v;
    setKey(node, "padding", sides.every((x) => x === 0) ? undefined : sides.every((x) => x === sides[0]) ? sides[0] : sides,
      node.row ? PPB.ITEM_KEYS : PPB.orderFor("column"));
    return { item, used, applied };
  }

  /**
   * Whether lining up `own` (a bottom edge) with an edge lower down is better done by a spacer above the item: it
   * sits from the top as the last item of a fill_height column without spacers, so a spacer takes it to the bottom
   * (and keeps it there whatever else changes); bottom padding then sets it exactly.
   */
  function spacerFits(path, own, d) {
    const L = layout(), ci = path.lastIndexOf("columns");
    if (!/bottom/.test(own) || d <= 0.5 || ci < 0 || L.fill_height !== true || anchorOf(path) !== "top") return false;
    const items = getAt(L, path.slice(0, ci + 2)).items || [];
    return path[ci + 3] === items.length - 1 && path[ci + 3] > 0 && !items.some((x) => kindOf(x) === "spacer");
  }

  /**
   * Lines up the item's `own` edge (top, centre, bottom, ring top, ...) with another item's edge, either way: a
   * spacer above it when that fits (spacerFits), then padding on the side it sits from (shift), measured again and
   * corrected each round (a centred column, or a target in the same column, moves by part of it), at most 6
   * rounds. One undo step; the toast says what it used.
   */
  function alignItem(path, own, targetPath, edge) {
    const at = (p, name) => (edgesOf(p).find((e) => e.name === name) || {}).y;
    let a = at(path, own), b = at(targetPath, edge);
    if (!isNum(a) || !isNum(b)) return toast("Those edges aren't on the page shown");
    snapshot();
    S.editing = null;
    let p = path, tp = targetPath, stuck = "", gain = 1;
    const used = [];
    // the closest state reached, kept when a later round moves it away (no spare room left, the target moving along)
    let best = { err: Math.abs(b - a), file: JSON.stringify(S.file), p, used: [] };
    const remeasure = () => {
      renderAll(); a = at(p, own); b = at(tp, edge);
      if (!isNum(a) || !isNum(b)) return false;
      if (Math.abs(b - a) < best.err - 0.05) best = { err: Math.abs(b - a), file: JSON.stringify(S.file), p, used: used.slice() };
      return true;
    };
    if (spacerFits(p, own, b - a)) { // a spacer above it in its column; the target, below it there, moves down one
      const ci = p.lastIndexOf("columns"), at0 = p[ci + 3];
      getAt(layout(), p.slice(0, ci + 3)).splice(at0, 0, { spacer: true });
      p = p.slice(); p[ci + 3]++;
      if (startsWith(tp, p.slice(0, ci + 3)) && tp[ci + 3] >= at0) { tp = tp.slice(); tp[ci + 3]++; }
      S.sel = p;
      used.push("a spacer above it");
      if (!remeasure()) return toast("Lined up as far as the preview can tell");
    }
    for (let round = 0; round < 6 && Math.abs(b - a) > 0.5; round++) {
      const r = shift(p, (b - a) / gain);
      if (r.error) {
        if (!used.length) { S.undo.pop(); return toast(r.error); } // nothing changed: no undo step
        stuck = r.error;
        break;
      }
      if (!r.applied) break; // under half a unit: whole units can't do better
      const before = a - b;
      p = r.item; // a row made around it replaces it in place: every other path stays
      S.sel = p;
      used[used.length && /padding/.test(used[used.length - 1]) ? used.length - 1 : used.length] = r.used; // the latest padding
      if (!remeasure()) break;
      const moved = (a - b) - before; // towards the target, relative to it
      if (Math.abs(b - a) <= 0.5) break;
      if (moved * r.applied <= 0.05 * Math.abs(r.applied)) {
        stuck = "Padding stops moving it towards the target here (no spare room left on that side, or the target moves with it): align the other item to this one instead";
        break;
      }
      gain = Math.max(0.25, Math.min(2, moved / r.applied));
    }
    if (Math.abs(b - a) > best.err + 0.05 || !isNum(a)) { // back to the closest state reached
      S.file = JSON.parse(best.file);
      p = best.p;
      used.length = 0;
      used.push(...best.used);
      S.sel = p;
      remeasure();
      if (!used.length && best.p === path) { S.undo.pop(); renderAll(); return toast(stuck || "Couldn't move it closer with padding"); } // nothing changed
    }
    if (S.align) S.align.picking = false; // done picking: the temporary guides go
    S.alignShow = null;
    renderAll();
    const left = isNum(a) && isNum(b) ? b - a : NaN;
    const how = used.length ? ` — ${used.join(", then ")}` : "";
    toast(stuck ? stuck + how : isNum(left) && Math.abs(left) <= 0.5 ? `Aligned: ${own} at ${fmt(a)}${how}` + (Math.abs(left) > 0.05 ? ` (${fmt(Math.abs(left))} off: whole units)` : "") :
      `Not lined up: ${fmt(Math.abs(left))} units ${left > 0 ? "higher" : "lower"} than the target${how}`);
  }

  /** The inspector's Align section: an edge of this item, an item on the page, its edge; the difference and Apply. */
  function alignSection(box, path) {
    const mine = edgesOf(path);
    const others = pageItems().filter(([p]) => !startsWith(p, path) && !startsWith(path, p));
    if (!mine.length || !others.length) return;
    box.append(h3("Align"));
    const st = S.align || (S.align = { own: "top", target: null, edge: "top" });
    const sel = (opts, value, on) => {
      const s = document.createElement("select");
      for (const [v, t] of opts) s.append(new Option(t, v));
      s.value = value;
      s.addEventListener("change", () => { on(s.value); st.picking = true; renderInspector(); });
      return s;
    };
    if (!mine.some((e) => e.name === st.own)) st.own = "top";
    // Align top / centre / bottom with…: this item's edge; any other of its edges in the list below
    const quick = document.createElement("div");
    quick.className = "node-tools";
    for (const [name, text] of [["top", "Align top with…"], ["centre", "Align centre with…"], ["bottom", "Align bottom with…"]]) {
      if (!mine.some((e) => e.name === name)) continue;
      const q = document.createElement("button");
      q.className = "small" + (st.own === name ? " on" : "");
      q.textContent = text;
      q.addEventListener("click", () => { st.own = name; st.picking = true; renderInspector(); });
      quick.append(q);
    }
    box.append(quick);
    box.append(field("this item's", sel(mine.map((e) => [e.name, `${e.name} (${fmt(e.y)})`]), st.own, (v) => { st.own = v; })));
    const tKey = st.target && others.some(([p]) => key(p) === st.target) ? st.target : key(others[0][0]);
    st.target = tKey;
    box.append(field("with", sel(others.map(([p, n]) => [key(p), n]), tKey, (v) => { st.target = v; })));
    const tPath = others.find(([p]) => key(p) === tKey)[0];
    const theirs = edgesOf(tPath);
    if (!theirs.some((e) => e.name === st.edge)) st.edge = theirs.some((e) => e.name === "ring top") ? "ring top" : "top";
    box.append(field("edge", sel(theirs.map((e) => [e.name, `${e.name} (${fmt(e.y)})`]), st.edge, (v) => { st.edge = v; })));
    const a = mine.find((e) => e.name === st.own).y, b = theirs.find((e) => e.name === st.edge).y;
    const d = b - a;
    const r = document.createElement("div");
    r.className = "resolved";
    const plan = spacerFits(path, st.own, d) ? { used: "a spacer above it, then bottom padding" } : shift(path, d, true);
    r.className = "resolved" + (plan.error && Math.abs(d) > 0.5 ? " bad" : "");
    r.textContent = Math.abs(d) <= 0.5 ? `Lined up (${fmt(a)}` + (Math.abs(d) > 0.05 ? `; ${fmt(Math.abs(d))} off, the nearest whole unit of padding` : "") + ")." :
      `${st.own} ${fmt(a)}, target ${fmt(b)}: ${fmt(Math.abs(d))} units ${d > 0 ? "down" : "up"}, ` + (plan.error ? "but " + plan.error.charAt(0).toLowerCase() + plan.error.slice(1) : `by ${plan.used}`) + ".";
    box.append(r);
    const b2 = document.createElement("button");
    b2.className = "small";
    b2.textContent = "Apply";
    b2.disabled = Math.abs(d) <= 0.5 || !!plan.error;
    b2.title = "Write the padding (or spacer) that lines the edges up";
    b2.addEventListener("click", () => alignItem(path, st.own, tPath, st.edge));
    box.append(field("", b2));
    // while picking (an Align button or a choice above): the two edges as guides, whatever the guides toggle says
    S.alignShow = st.picking ? { path, target: tPath, own: st.own, edge: st.edge } : null;
    syncGuides();
  }

  /** Another of the bar's views (List, Programs, sidechain, outputs): drawn plain, without the height guides. */
  function previewOtherView(res) {
    const box = $("preview");
    const frame = document.createElement("div");
    frame.className = "pv-frame";
    frame.style.padding = "6px";
    frame.append(res.node);
    box.innerHTML = "";
    box.append(frame);
    res.body.style.height = (parseFloat(S.chain) > 0 ? parseFloat(S.chain) : PT.sizes.available) + "px";
    box.style.transform = `scale(${S.zoom})`;
    $("preview-wrap").style.width = frame.offsetWidth * S.zoom + "px";
    $("preview-wrap").style.height = frame.offsetHeight * S.zoom + "px";
    S.deviceOffset = [6 * S.zoom, 6 * S.zoom];
    renderOverlay();
    renderModePicker(layout());
    renderViewPicker(layout());
    $("stage-note").textContent = `Showing the ${res.viewing} view (Bitwig's own page, sketched). Choose Layout to edit and see the heights again.`;
  }

  /** With the right-hand bar: a selector of its views (also: click a button in the bar). */
  function renderViewPicker(L) {
    const box = $("view-picker");
    const parts = PPB.toolbarParts(L);
    box.hidden = !parts.right;
    if (box.hidden) { S.view = null; return; }
    const views = [["layout", parts.modes ? "Layout" : "Parameters"]];
    if (parts.showList) views.push(["list", "List"]);
    if (parts.showPrograms) views.push(["programs", "Programs"]);
    if (parts.showSidechain) views.push(["sidechain", "Sidechain"]);
    if (parts.showOutputs) views.push(["outputs", "Outputs"]);
    if (!views.some(([v]) => v === S.view)) S.view = null;
    const s = $("view-select");
    s.innerHTML = "";
    for (const [v, t] of views) s.append(new Option(t, v));
    s.value = S.view || "layout";
  }

  /** With mode_colors: a selector to preview each mode (the parameter's own value by default). */
  function renderModePicker(L) {
    const box = $("mode-picker");
    const mode = PPB.currentMode(L, params(), null);
    box.hidden = !mode || !mode.names.length;
    if (box.hidden) return;
    const s = $("mode-select");
    s.innerHTML = "";
    s.append(new Option(`${mode.current} (the parameter's value)`, ""));
    const map = isObj(L.mode_colors.map) ? L.mode_colors.map : {};
    const has = (n) => Object.keys(map).some((k) => k.trim().toLowerCase() === n.trim().toLowerCase());
    for (const x of mode.names) s.append(new Option(x.name + (has(x.name) ? "" : " (no colour)"), x.name));
    s.value = S.modePreview && mode.names.some((x) => x.name === S.modePreview) ? S.modePreview : "";
  }

  // a Bitwig screenshot over the preview, at its own pixels, for comparing
  function renderOverlay() {
    const old = $("preview-wrap").querySelector(".pv-overlay");
    if (old) old.remove();
    if (!S.overlay) return;
    const img = document.createElement("img");
    img.className = "pv-overlay";
    img.src = S.overlay;
    img.style.opacity = String(S.overlayOpacity);
    const [dx, dy] = S.deviceOffset || [0, 0];
    img.style.left = dx + S.overlayX + "px";
    img.style.top = dy + S.overlayY + "px";
    $("preview-wrap").append(img);
  }

  function setupPreviewEvents() {
    const box = $("preview");
    box.addEventListener("click", (e) => {
      const t = e.target.closest("[data-page]");
      if (t && t.classList.contains("pv-pagetab")) {
        S.page = parseInt(t.dataset.page, 10);
        return select(["pages", S.page]);
      }
      const n = e.target.closest("[data-path]");
      if (n) select(nodeOf(JSON.parse(n.dataset.path)));
    });
    box.addEventListener("dragover", (e) => {
      if (!dragPayload) return;
      const n = e.target.closest("[data-path]");
      if (!n) return;
      e.preventDefault();
      box.querySelectorAll(".pv-drop").forEach((x) => x.classList.remove("pv-drop"));
      n.classList.add("pv-drop");
    });
    box.addEventListener("drop", (e) => {
      const n = e.target.closest("[data-path]");
      if (!n || !dragPayload) return;
      e.preventDefault();
      let path = JSON.parse(n.dataset.path);
      let into = false;
      // a column's item list or an empty area: into the column; a row: into the row
      const last = path[path.length - 1];
      if (last === "items" || last === "row") { path = path.slice(0, -1); into = true; }
      else if (last === "columns") { path = pageBase(); into = true; }
      const t = nodeType(path);
      if (t === "column" || t === "footer") into = true;
      if (t === "item" && kindOf(getAt(layout(), path)) === "row" && n.classList.contains("pv-row")) into = true;
      const p = dragPayload;
      endDrag();
      if (t === "page" || path.length === 0) {
        const cols = getAt(layout(), pageBase()).columns || [];
        if (!cols.length) return toast("Add a column first");
        return drop(p, pageBase().concat("columns", cols.length - 1), true);
      }
      drop(p, path, into);
    });
  }

  // problems
  function renderProblems() {
    const ul = $("problems");
    ul.innerHTML = "";
    const n = S.problems.filter((p) => p.level === "error").length, w = S.problems.filter((p) => p.level === "warning").length;
    $("problem-count").textContent = S.file ? `(${n} errors, ${w} warnings)` : "";
    if (!S.file) return;
    if (!S.problems.length) {
      const li = document.createElement("li");
      li.className = "ok";
      li.textContent = S.sheet ? "No problems: the framework reads this layout, and every reference matches the sheet." : "No problems found. Load the parameter sheet to check the references too.";
      ul.append(li);
      return;
    }
    for (const p of S.problems) {
      const li = document.createElement("li");
      li.className = p.level;
      const lvl = document.createElement("span"); lvl.className = "lvl"; lvl.textContent = p.level;
      const msg = document.createElement("span"); msg.textContent = p.message;
      const where = document.createElement("span"); where.className = "where"; where.textContent = p.path ? p.path.join(".") : "";
      li.append(lvl, msg, where);
      if (p.path) li.addEventListener("click", () => select(nodeOf(p.path)));
      ul.append(li);
    }
  }

  function exportText() {
    return S.file ? PPB.stringify(S.file) : "";
  }

  function renderJson() {
    if (document.activeElement === $("json")) return;
    $("json").value = exportText();
    $("json-msg").textContent = "";
  }

  // parameters
  function renderParams() {
    const box = $("params");
    box.innerHTML = "";
    if (!S.sheet) {
      box.innerHTML = '<div class="empty-note">No parameter sheet. In Bitwig, right-click the plug-in and choose <b>Generate Parameter Sheet</b>; it copies the sheet and saves <code>&lt;plug-in&gt; &lt;format&gt;.parameters.json</code> in the <code>sheets</code> folder of the framework&rsquo;s folder in Patchbay&rsquo;s settings folder. Drop that file here, or paste it.</div>';
      return;
    }
    const f = $("param-filter").value.trim().toLowerCase();
    const used = new Set();
    if (layout()) for (const p of PPB.resolveLayout(layout(), params()).values()) if (p) used.add(p);
    for (const p of params()) {
      if (f && !(p.name.toLowerCase().includes(f) || String(p.id).toLowerCase().includes(f) || ("#" + p.index) === f)) continue;
      const h = PPB.paramHints(p);
      const d = document.createElement("div");
      d.className = "param" + (used.has(p) ? " used" : "");
      d.draggable = true;
      d.title = "Drag onto the outline or the preview, or open it for options";
      d.addEventListener("dragstart", (e) => { dragPayload = { param: p }; e.dataTransfer.setData("text/plain", "param"); document.body.classList.add("dragging"); });
      d.innerHTML = `<div class="ptop"><span class="pidx">${p.index}</span><span class="pname"></span><span class="ptext"></span></div><div class="prange"></div><div class="ptags"></div>`;
      d.querySelector(".pname").textContent = p.name;
      d.querySelector(".ptext").textContent = p.text;
      d.querySelector(".prange").textContent = `${p.min_text === null ? "—" : p.min_text} → ${p.max_text === null ? "—" : p.max_text}` + (p.steps ? ` · ${p.steps} steps` : "");
      const tags = d.querySelector(".ptags");
      const tag = (cls, t, title) => { const s = document.createElement("span"); s.className = "tag " + cls; s.textContent = t; s.title = title || ""; tags.append(s); };
      if (h.unreliable) tag("warn", "text unreliable", "min_text equals max_text");
      if (h.noAnswer) tag("warn", "no answer");
      if (h.stepped) tag("steps", p.steps + " steps");
      if (h.toggle) tag("toggle", "toggle?");
      if (h.integers) tag("int", `${h.integers.count} whole numbers`);
      if (h.values) tag("vals", `${h.values.min}…${h.values.max}${h.values.unit ? " " + h.values.unit : ""}${h.curve ? " · " + h.curve : ""}`);
      if (S.openParams.has(p.index)) d.append(paramDetails(p, h));
      d.addEventListener("click", (e) => {
        if (e.target.closest("button")) return;
        if (S.openParams.has(p.index)) S.openParams.delete(p.index); else S.openParams.add(p.index);
        renderParams();
      });
      box.append(d);
    }
  }

  function paramDetails(p, h) {
    const m = document.createElement("div");
    m.className = "pmore";
    m.innerHTML = `<p class="muted">id ${p.id} · value ${PPB.round(p.value, 6)}</p>`;
    for (const n of h.notes) { const q = document.createElement("p"); q.textContent = n; m.append(q); }
    const a = document.createElement("div");
    a.className = "pactions";
    const b = (t, fn) => { const x = document.createElement("button"); x.className = "small"; x.textContent = t; x.addEventListener("click", fn); a.append(x); };
    b("+ Knob", () => addForParam(p, "knob"));
    if (h.integers) {
      b(`+ Dropdown (${h.integers.count})`, () => addForParam(p, "dropdown", { choices: PPB.integerChoices(h.integers.from, h.integers.to) }));
      b("+ Stepped knob", () => addForParam(p, "knob", { values: { min: h.integers.from, max: h.integers.to, steps: h.integers.count } }));
    } else b("+ Dropdown", () => addForParam(p, "dropdown"));
    b("+ Toggle", () => addForParam(p, "toggle"));
    b("+ Value", () => addForParam(p, "value"));
    b("+ Slider", () => addForParam(p, "slider"));
    m.append(a);
    return m;
  }

  // ---------- inspector ----------

  function renderInspector() {
    closePicker();
    const box = $("inspector");
    box.innerHTML = "";
    const L = layout();
    if (!L) { $("insp-title").textContent = "Inspector"; return; }
    if (getAt(L, S.sel) === undefined && nodeType(S.sel) !== "footer") S.sel = [];
    const t = nodeType(S.sel);
    const node = getAt(L, S.sel);
    const kind = t === "item" ? kindOf(node) : null;
    $("insp-title").textContent = t === "item" ? (kind || "item") : t;
    // the layout at the device's height: Bitwig zooms it down, so sizes show smaller than written
    if (S.fit && S.fit.needed > S.fit.avail + 0.5) {
      const z = document.createElement("div");
      z.className = "hint";
      z.textContent = `The layout needs ${fmt(S.fit.needed)} of ${fmt(S.fit.avail)} units: Bitwig zooms it to ${Math.round(S.fit.zoom * 100)}%` +
        (S.fit.cut ? " and cuts off the bottom" : "") + ". Heights you add make it smaller still; make it shorter (rows side by side) to keep 100%.";
      box.append(z);
    } else if (S.fit && S.fit.avail - S.fit.needed < 1) {
      box.append(help(`The layout fills the device's height exactly (${fmt(S.fit.needed)} of ${fmt(S.fit.avail)}): anything taller is zoomed down.`));
    }
    if (t !== "layout") box.append(nodeTools(t, kind));
    if (t === "layout") inspectLayout(box, L);
    else if (t === "page") inspectPage(box, node);
    else if (t === "column") inspectColumn(box, node);
    else if (t === "footer") inspectFooter(box);
    else if (kind === "row") inspectRow(box, node);
    else if (kind) inspectItem(box, node, kind);
    else box.append(help("This item has no kind key; the framework refuses it. Fix it in the JSON tab."));
    const here = S.problems.filter((p) => p.path && key(nodeOf(p.path)) === key(S.sel));
    if (here.length) {
      box.append(h3("Problems here"));
      for (const p of here) box.append(help(`${p.level}: ${p.message}`));
    }
  }

  function nodeTools(t, kind) {
    const d = document.createElement("div");
    d.className = "node-tools";
    const b = (label, fn, title) => { const x = document.createElement("button"); x.className = "small"; x.textContent = label; if (title) x.title = title; x.addEventListener("click", fn); d.append(x); };
    if (t === "footer") {
      b("Remove footer", () => removeNode(S.sel));
      return d;
    }
    b("↑", () => moveBy(S.sel, -1), "Move up / left (Alt+↑)");
    b("↓", () => moveBy(S.sel, 1), "Move down / right (Alt+↓)");
    b("Duplicate", () => duplicate(S.sel), "Ctrl+D");
    if (t === "item" && kind !== "row") b("Wrap in row", () => wrapInRow(S.sel));
    if (kind === "row") b("Unwrap row", () => unwrapRow(S.sel));
    b("Delete", () => removeNode(S.sel), "Delete");
    return d;
  }

  // field builders: each edits obj[k]; an empty value removes the key
  function h3(t) { const e = document.createElement("h3"); e.textContent = t; return e; }
  function help(t) { const e = document.createElement("div"); e.className = "help"; e.textContent = t; return e; }

  function field(label, ctl, title, unused) {
    const f = document.createElement("div");
    f.className = "field";
    const l = document.createElement("label");
    l.textContent = label;
    if (title) l.title = title;
    if (unused) l.className = "unused";
    const c = document.createElement("div");
    c.className = "ctl";
    for (const x of [].concat(ctl)) c.append(x);
    f.append(l, c);
    return f;
  }

  let fieldSeq = 0;
  function setVal(obj, k, v, level) {
    if (v === undefined || v === "") delete obj[k];
    else setKey(obj, k, v, PPB.orderFor(level));
  }

  function input(type, value, placeholder) {
    const i = document.createElement("input");
    i.type = type;
    if (value !== undefined && value !== null) i.value = typeof value === "object" ? JSON.stringify(value) : String(value);
    if (placeholder) i.placeholder = placeholder;
    return i;
  }

  /**
   * The picker's list under a field: every option, whatever the field holds (a datalist shows only matches), the
   * current one marked; first "default (…)" when the key is optional. Picking sets the value (undefined: the key
   * goes). Closes on a pick, Escape, or a click elsewhere.
   */
  function openPicker(anchor, field, options, current, onPick) {
    closePicker();
    const pop = document.createElement("div");
    pop.className = "picker";
    const r = anchor.getBoundingClientRect();
    pop.style.left = r.left + "px";
    pop.style.top = r.bottom + 2 + "px";
    pop.style.minWidth = r.width + "px";
    let mark = null;
    for (const o of options) {
      const row = document.createElement("div");
      row.className = "picker-row" + (o.value === undefined ? " def" : "");
      if (o.swatch !== undefined) {
        const sw = document.createElement("span");
        sw.className = "picker-swatch" + (o.swatch ? "" : " none");
        if (o.swatch) sw.style.background = o.swatch;
        row.append(sw);
      }
      row.append(document.createTextNode(o.text));
      if (o.value === current || (o.value === undefined && (current === undefined || current === ""))) { row.classList.add("on"); mark = row; }
      row.addEventListener("mousedown", (e) => { e.preventDefault(); closePicker(); onPick(o.value); });
      pop.append(row);
    }
    document.body.append(pop);
    const spare = innerHeight - r.bottom - 8; // open upwards when there's more room above
    if (pop.offsetHeight > spare && r.top > spare) { pop.style.top = ""; pop.style.bottom = innerHeight - r.top + 2 + "px"; }
    pop.style.maxHeight = Math.max(120, Math.max(spare, r.top - 8)) + "px";
    if (mark) mark.scrollIntoView({ block: "nearest" });
    S.picker = { pop, field };
  }

  function closePicker() {
    if (S.picker) S.picker.pop.remove();
    S.picker = null;
  }

  /**
   * Makes a text input a combobox: type freely, or open the full list (its ▾ button, a click in the field, or
   * Arrow Down), first "default (placeholder)" unless opts.optional is false; colours get swatches. Returns the
   * button. `pick(value)` gets undefined for the default.
   */
  function combo(i, list, opts, current, pick) {
    const options = (opts.optional === false ? [] : [{ value: undefined, text: `default (${opts.placeholder || "not set"})`, swatch: opts.colour ? null : undefined }])
      .concat([...new Set(list)].map((v) => ({ value: v, text: v, swatch: opts.colour ? PV.colour(v) || null : undefined })));
    const chosen = (v) => { i.value = v === undefined ? "" : v; pick(v); };
    const open = () => openPicker(i, i, options, current(), chosen);
    const b = document.createElement("button");
    b.className = "small picker-open";
    b.type = "button";
    b.textContent = "▾";
    b.title = "Choose from the list";
    b.addEventListener("mousedown", (e) => { e.preventDefault(); if (S.picker && S.picker.field === i) closePicker(); else open(); });
    i.addEventListener("mousedown", () => { if (!(S.picker && S.picker.field === i)) setTimeout(open); });
    i.addEventListener("keydown", (e) => {
      if (e.key === "Escape") closePicker();
      if (e.key === "ArrowDown" && !S.picker) { e.preventDefault(); open(); }
    });
    i.addEventListener("input", closePicker); // typing: free text
    i.addEventListener("blur", closePicker);
    return b;
  }

  function fText(obj, k, label, level, opts) {
    opts = opts || {};
    const id = "f" + fieldSeq++;
    const i = input("text", obj[k], opts.placeholder);
    const parts = [i];
    if (opts.list) parts.push(combo(i, opts.list, opts, () => obj[k], (v) => {
      fieldEdit(id, () => setVal(obj, k, v, level));
      S.editing = null;
      renderInspector();
    }));
    if (opts.colour) {
      const c = input("color");
      const cur = PV.colour(obj[k]);
      c.value = cur ? cur.slice(0, 7) : "#3b3b3b";
      c.title = "Pick a fixed colour (#rrggbb)";
      c.addEventListener("input", () => { i.value = c.value.toUpperCase(); fieldEdit(id, () => setVal(obj, k, i.value, level)); });
      parts.push(c);
    }
    i.addEventListener("input", () => fieldEdit(id, () => setVal(obj, k, i.value === "" ? undefined : i.value, level)));
    i.addEventListener("blur", () => { S.editing = null; });
    return field(label, parts, opts.title, opts.unused);
  }

  function fNum(obj, k, label, level, opts) {
    opts = opts || {};
    const id = "f" + fieldSeq++;
    const i = input("number", isNum(obj[k]) ? obj[k] : obj[k] === undefined ? "" : obj[k], opts.placeholder);
    if (opts.step) i.step = opts.step;
    i.addEventListener("input", () => {
      const s = i.value.trim();
      if (s === "") return fieldEdit(id, () => setVal(obj, k, undefined, level));
      const n = Number(s);
      i.classList.toggle("bad", !isFinite(n));
      if (isFinite(n)) fieldEdit(id, () => setVal(obj, k, n, level));
    });
    i.addEventListener("blur", () => { S.editing = null; });
    return field(label, i, opts.title, opts.unused);
  }

  /** A select; options are [value, text]; "" is "not set". Non-listed current values are kept as an option. */
  function fSelect(obj, k, label, level, options, opts) {
    opts = opts || {};
    const s = document.createElement("select");
    const cur = obj[k];
    const curStr = cur === undefined ? "" : JSON.stringify(cur);
    let found = false;
    for (const [v, t] of options) {
      const val = v === undefined ? "" : JSON.stringify(v);
      if (val === curStr) found = true;
      s.append(new Option(t, val));
    }
    if (!found) s.append(new Option(`${JSON.stringify(cur)} (not valid)`, curStr));
    s.value = curStr;
    s.addEventListener("change", () => edit(() => setVal(obj, k, s.value === "" ? undefined : JSON.parse(s.value), level), S.sel));
    return field(label, s, opts.title, opts.unused);
  }

  const tri = (def) => [[undefined, `default (${def})`], [true, "true"], [false, "false"]];

  // layout
  function inspectLayout(box, L) {
    if (!isObj(L.match)) L.match = { name: "" };
    box.append(h3("Panel file"));
    box.append(fText(S.file, "name", "panel name", Array.isArray(S.file.layouts) ? "file" : "layout",
      { placeholder: "the plug-in's name", title: "The panel's name: Patchbay lists the panel by it, and Save Panel from Clipboard names the file after it" }));
    box.append(h3("Match"));
    box.append(fText(L.match, "name", "name", null, { placeholder: "required", title: "The plug-in's name as Bitwig shows it; * matches every plug-in" }));
    box.append(fText(L.match, "vendor", "vendor", null, { placeholder: "any vendor" }));
    box.append(fSelect(L.match, "format", "format", null, [[undefined, "any format"], ["VST2", "VST2"], ["VST3", "VST3"], ["CLAP", "CLAP"]],
      { title: "Vendor and name are the same for VST3 and CLAP, so leaving format out covers both" }));
    if (S.sheet && S.sheet.plugin) {
      const b = document.createElement("button");
      b.className = "small";
      b.textContent = "Match the loaded sheet";
      b.addEventListener("click", () => edit(() => { setVal(L.match, "vendor", S.sheet.plugin.vendor); setVal(L.match, "name", S.sheet.plugin.name); }, []));
      box.append(field("", b));
    }
    stripeEditor(box, L); // the panel's look first: easy to find
    modeEditor(box, L);
    box.append(h3("Panel"));
    box.append(fNum(L, "width", "width", "layout", { placeholder: "from the columns", title: "The device body's width: any width, the chain scrolls sideways" }));
    box.append(paddingField(L, "layout", 4, "Around the panel: one number or [left, right, top, bottom]"));
    box.append(fNum(L, "gap", "gap", "layout", { placeholder: "4", title: "Between columns and between footer items" }));
    box.append(fText(L, "background", "background", "layout", { list: colourList(), colour: true, placeholder: "theme default" }));
    box.append(fSelect(L, "dividers", "dividers", "layout", tri("false"), { title: "A line between all columns" }));
    box.append(fSelect(L, "fill_height", "fill height", "layout", tri("false"), { title: "true: the columns (backgrounds, dividers) run down to the bottom of the device, or to the footer; each column's valign places its content" }));
    box.append(fSelect(L, "title", "title line", "layout", tri("true"), { title: "The plug-in's name line" }));
    toolbarEditor(box, L);
    if (usesPages()) box.append(fSelect(L, "page_chooser", "page chooser", "layout", [[undefined, "default (tabs)"], ["tabs", "tabs"], ["dropdown", "dropdown"]]));
    box.append(fNum(L, "min_resolved", "min_resolved", "layout", { placeholder: "100", title: "Share (percent) of references that must match; below 100 the missing items are left out" }));
    box.append(h3("Control defaults"));
    box.append(fSelect(L, "refresh", "refresh", "layout", [[undefined, "default (none)"], ["none", "none"], ["settle", "settle"]], { title: "settle: the value text is asked again 150, 500 and 1000 ms after each change" }));
    box.append(fSelect(L, "typing", "typing", "layout", [[undefined, "default (mapping with values, else plugin)"], ["plugin", "plugin"], ["mapping", "mapping"], ["normalised", "normalised"]]));
    box.append(h3("Fonts and colours"));
    for (const w of ["title", "header", "label", "value"]) {
      box.append(fText(L, w + "_font", w + " font", "layout", { list: fontList(), placeholder: "theme default" }));
      box.append(fText(L, w + "_color", w + " colour", "layout", { list: colourList(), colour: true, placeholder: "theme default" }));
    }
    box.append(h3("Fixed line heights"));
    box.append(fNum(L, "header_height", "header height", "layout"));
    box.append(fNum(L, "label_height", "label height", "layout"));
    box.append(fNum(L, "value_height", "value height", "layout"));
    box.append(fNum(L, "footer_gap", "footer gap", "layout"));
    box.append(h3("Pages"));
    const pb = document.createElement("button");
    pb.className = "small";
    if (usesPages()) {
      pb.textContent = "Back to one page";
      pb.disabled = L.pages.length !== 1;
      pb.title = L.pages.length !== 1 ? "Only with a single page" : "";
      pb.addEventListener("click", () => edit(() => fromPages(L), []));
    } else {
      pb.textContent = "Use pages";
      pb.addEventListener("click", () => edit(() => toPages(L), ["pages", 0]));
    }
    box.append(field("", pb));
    otherKeys(box, L, PPB.LAYOUT_KEYS, "layout");
  }

  /**
   * "padding" on the layout, a column or a row: one number, or each side ([left, right, top, bottom], the
   * schema's order) as labelled boxes on their own line. On a column or row it sits inside its width.
   */
  function paddingField(o, level, def, title) {
    const id = "f" + fieldSeq++;
    const four = Array.isArray(o.padding);
    const mode = document.createElement("select");
    mode.className = "mode";
    mode.append(new Option("one", "one"), new Option("each side", "four"));
    mode.value = four ? "four" : "one";
    const parts = [mode];
    let sides = null;
    if (four) {
      sides = document.createElement("div");
      sides.className = "sides";
      [["L", "left"], ["R", "right"], ["T", "top"], ["B", "bottom"]].forEach(([short, side], i) => {
        const n = input("number", o.padding[i]);
        n.title = `Padding ${side} (layout units, 0 to 200)`;
        n.min = "0";
        n.addEventListener("input", () => { const v = Number(n.value); if (n.value !== "" && isFinite(v)) fieldEdit(id, () => { o.padding[i] = v; }); });
        const l = document.createElement("label");
        l.title = n.title;
        l.append(short, n);
        sides.append(l);
      });
    } else {
      const n = input("number", isNum(o.padding) ? o.padding : "", String(def));
      n.min = "0";
      n.addEventListener("input", () => fieldEdit(id, () => setVal(o, "padding", n.value === "" ? undefined : Number(n.value), level)));
      parts.push(n);
    }
    mode.addEventListener("change", () => edit(() => {
      const cur = isNum(o.padding) ? o.padding : def;
      setVal(o, "padding", mode.value === "four" ? [cur, cur, cur, cur] : Array.isArray(o.padding) ? o.padding[0] : undefined, level);
    }, S.sel));
    const f = field("padding", parts, title);
    if (!sides) return f;
    const wrap = document.createElement("div");
    wrap.append(f, sides);
    return wrap;
  }

  /**
   * toolbar: Bitwig's whole row, chosen pieces (pages, sidechain, outputs, search), or no row. The container
   * button (the arrow) is Bitwig's FX container and always stays: in the row, or on the title line.
   */
  function toolbarEditor(box, L) {
    const s = document.createElement("select");
    s.append(new Option("Bitwig's whole row", "all"), new Option("chosen pieces (top row)", "some"),
      new Option("bar on the right", "right"), new Option("no row (→ on the title line)", "none"));
    const parts = PPB.toolbarParts(L);
    s.value = L.toolbar === false ? "none" : parts.right ? "right" : isObj(L.toolbar) ? "some" : "all";
    s.addEventListener("change", () => edit(() => {
      const keep = PPB.toolbarParts(L);
      delete L.search; // carried into toolbar.search (and none with the bar)
      if (s.value === "all") setVal(L, "toolbar", undefined, "layout");
      else if (s.value === "none") setVal(L, "toolbar", false, "layout");
      else {
        const o = s.value === "right" ? { position: "right" } : {};
        for (const k of PPB.TOOLBAR_PIECES) if (keep.row && !keep[k] && !(s.value === "right" && k === "search")) o[k] = false;
        setVal(L, "toolbar", o, "layout");
      }
    }, S.sel));
    box.append(field("toolbar", s, "Bitwig's toolbar: a row over the panel, a bar down its right edge, or none; the container button (→) always stays"));
    const wrap = document.createElement("div");
    wrap.className = "apply-list one-col";
    const labels = { pages: "Parameters / Programs tabs", sidechain: "sidechain", outputs: "outputs", search: "search field" };
    for (const k of PPB.TOOLBAR_PIECES) {
      if (k === "search" && parts.right) continue; // no search field in the bar
      const l = document.createElement("label");
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = parts[k];
      cb.disabled = !isObj(L.toolbar);
      cb.addEventListener("change", () => edit(() => {
        setKey(L.toolbar, k, cb.checked ? undefined : false, PPB.TOOLBAR_PARTS); // true is the default: left out
        if (cb.checked) delete L.toolbar[k];
        if (k === "search") delete L.search;
      }, S.sel));
      l.append(cb, " " + labels[k]);
      wrap.append(l);
    }
    const arrow = document.createElement("label");
    const lock = document.createElement("input");
    lock.type = "checkbox";
    lock.checked = true;
    lock.disabled = true;
    arrow.title = "Bitwig's FX container button: always there (in the row, at the bottom of the bar, or at the end of the title line)";
    arrow.append(lock, " → container (always)");
    wrap.append(arrow);
    box.append(field(parts.row ? "pieces" : "", wrap, isObj(L.toolbar) ? "Untick a piece to leave it out" : "Choose \"chosen pieces\" or \"bar on the right\" to leave pieces out"));
    if (parts.row && !parts.pages && parts.parameters)
      box.append(help("Only the Programs tab goes: the Parameters tab stays while sidechain or outputs is kept (their pages switch back through it). Untick sidechain and outputs too to remove it."));
    if (parts.right) { // the bar's own settings
      const sub = document.createElement("div");
      sub.className = "sub";
      sub.append(fText(L.toolbar, "style", "button style", null, { list: buttonStyleList(), placeholder: "Borderless", title: "A theme button style for the bar's buttons" }));
      sub.append(fNum(L.toolbar, "size", "size", null, { placeholder: String(PPB.BAR_SIZE), title: "The buttons' width and height, 12 to 60" }));
      sub.append(fNum(L.toolbar, "gap", "gap", null, { placeholder: String(PPB.BAR_GAP), title: "Between buttons, above the first, under the last and beside the panel, 0 to 20" }));
      sub.append(fText(L.toolbar, "background", "background", null, { list: colourList(), colour: true, placeholder: "the layout's", title: "The bar's own background; without it the layout's background shows through" }));
      sub.append(fSelect(L.toolbar, "divider", "divider", null, tri("false"), { title: "Bitwig's separator line between the panel and the bar (adds 1 + gap to the width)" }));
      sub.append(fSelect(L.toolbar, "list", "list switch", null, tri("false"), { title: "A toggle with Bitwig's list icon: Bitwig's full parameter list in place of the layout (starts on the layout each time)" }));
      barOrderEditor(sub, L.toolbar);
      sub.append(help(`The bar adds ${parts.barWidth} units (size ${parts.size} + gap ${parts.gap}${parts.divider ? ` + divider 1 + gap ${parts.gap}` : ""}) to the device's width, and the panel gets the toolbar row's height back.`));
      box.append(sub);
    }
    if (L.search !== undefined) box.append(fSelect(L, "search", "search", "layout", tri("true"), { title: "The same as toolbar: {search: false}" }));
  }

  /**
   * The bar's order (PanelHooks.bar): its entries from the top, up and down, add and remove. The container
   * button is always shown (locked: appended when the order leaves it out); spacer may come several times;
   * align places the stack only without a spacer.
   */
  function barOrderEditor(box, t) {
    const order = Array.isArray(t.order) ? PPB.expandOrder(t.order.map((e) => String(e).trim().toLowerCase())) : PPB.BAR_ORDER.slice();
    const write = (next) => edit(() => {
      const same = next.length === PPB.BAR_ORDER.length && next.every((e, i) => e === PPB.BAR_ORDER[i]);
      setKey(t, "order", same ? undefined : next, PPB.TOOLBAR_PARTS); // the default order isn't written
      if (same) delete t.order;
      if (next.includes("spacer")) delete t.align; // align needs an order without a spacer
    }, S.sel);
    const names = { layout: t.list === true ? "Layout (mode button)" : "Parameters tab", list: "List (mode button)", programs: "Programs tab", sidechain: "sidechain", outputs: "outputs", container: "→ container (always)", spacer: "spacer (spare height)" };
    const list = document.createElement("div");
    list.className = "bar-order";
    const parts = PPB.toolbarParts(layout());
    if (parts.forcedLayout) { // put first by the framework: the way back to the layout
      const r = document.createElement("div");
      r.className = "bar-order-row implied";
      r.title = "Not in the order, but the list, Programs, sidechain or outputs can show: the framework puts it first";
      r.append(Object.assign(document.createElement("span"), { textContent: names.layout + " (first, always)" }));
      list.append(r);
    }
    const shown = order.includes("container") ? order : order.concat(["container"]);
    shown.forEach((e, i) => {
      const r = document.createElement("div");
      r.className = "bar-order-row" + (i >= order.length ? " implied" : "");
      const n = document.createElement("span");
      n.textContent = names[e] || e;
      if (e === "list" && t.list !== true) n.title = "Shows only with the list switch on";
      r.append(n);
      const b = (label, title, fn, off) => { const x = document.createElement("button"); x.className = "small"; x.textContent = label; x.title = title; x.disabled = !!off; x.addEventListener("click", fn); r.append(x); };
      const real = i < order.length;
      b("↑", "Move up", () => { const o = order.slice(); [o[i - 1], o[i]] = [o[i], o[i - 1]]; write(o); }, !real || i === 0);
      b("↓", "Move down", () => { const o = order.slice(); [o[i + 1], o[i]] = [o[i], o[i + 1]]; write(o); }, !real || i >= order.length - 1);
      b("×", e === "container" ? "The container button is always shown" : "Leave it out (it isn't shown)", () => { const o = order.slice(); o.splice(i, 1); write(o); }, e === "container" || !real);
      list.append(r);
    });
    const addable = PPB.BAR_ENTRIES.filter((e) => e !== "pages" && (e === "spacer" || !order.includes(e)));
    const add = document.createElement("select");
    add.append(new Option("+ add…", ""));
    for (const e of addable) add.append(new Option(names[e] || e, e));
    add.addEventListener("change", () => { if (!add.value) return; const o = order.slice(); const at = o.indexOf("container"); o.splice(at < 0 ? o.length : at, 0, add.value); write(o); });
    const wrap = document.createElement("div");
    wrap.append(list, add);
    box.append(field("order", wrap, "The bar's entries from the top; a spacer takes the spare height"));
    if (!order.includes("spacer"))
      box.append(fSelect(t, "align", "align", null, [[undefined, "default (top)"], ["top", "top"], ["center", "center"], ["bottom", "bottom"]], { title: "Where the stack sits (only without a spacer)" }));
    else box.append(help("With a spacer in the order, the spacer places the buttons (align is for an order without one)."));
  }

  /** stripe: none, the defaults (Knob Value Color, 2), or a colour and height of its own. */
  function stripeEditor(box, L) {
    box.append(h3("Stripe"));
    const s = document.createElement("select");
    s.append(new Option("none", "none"), new Option("default (Knob Value Color, 2)", "true"), new Option("colour and height", "object"));
    s.value = !PPB.hasStripe(L) ? "none" : isObj(L.stripe) ? "object" : "true";
    s.addEventListener("change", () => edit(() => setVal(L, "stripe", s.value === "none" ? undefined : s.value === "true" ? true : { color: "Knob Value Color", height: PPB.STRIPE_HEIGHT }, "layout"), S.sel));
    box.append(field("stripe", s, "An accent line across the top of the panel, above the top padding; counts in the height"));
    if (isObj(L.stripe)) {
      const sub = document.createElement("div");
      sub.className = "sub";
      sub.append(fText(L.stripe, "color", "colour", null, { list: colourList(), colour: true, placeholder: "Knob Value Color" }));
      sub.append(fNum(L.stripe, "height", "height", null, { placeholder: "2", title: "1 to 20 layout units" }));
      box.append(sub);
    }
  }

  /**
   * mode_colors: the parameter, a colour per mode (the modes of its dropdown's choices, else its step names)
   * and the parts that take it.
   */
  function modeEditor(box, L) {
    box.append(h3("Mode colours"));
    const m = L.mode_colors;
    if (!isObj(m)) {
      const b = document.createElement("button");
      b.className = "small";
      b.textContent = "Add mode colours";
      b.title = "A colour for each mode of a parameter (a reverb's mode), on the parts you choose";
      const first = [];
      PPB.walkItems(L, [], (it) => { if (kindOf(it) === "dropdown" && Array.isArray(it.choices) && it.choices.length) first.push(it); });
      b.addEventListener("click", () => edit(() => setVal(L, "mode_colors", { param: first.length ? String(first[0].dropdown) : "", map: {} }, "layout"), S.sel));
      box.append(help("A colour for each mode of a parameter, shown live on the stripe, the knobs' arcs, the headers, the dividers, the background or the envelopes."), field("", b));
      return;
    }
    const refs = [];
    PPB.walkItems(L, [], (it) => { if (kindOf(it) === "dropdown") refs.push(String(it.dropdown)); });
    box.append(fText(m, "param", "parameter", null, { list: refs.concat(params().map((p) => p.name)), optional: false, placeholder: "required", title: "The parameter whose modes pick the colour; its modes are the choices of its first dropdown with choices, else its step names" }));
    const mode = PPB.currentMode(L, params(), S.modePreview);
    if (!isObj(m.map)) m.map = {};
    const map = m.map;
    const keyOf = (name) => Object.keys(map).find((k) => k.trim().toLowerCase() === String(name).trim().toLowerCase());
    const names = mode ? mode.names.map((x) => x.name) : [];
    const extra = Object.keys(map).filter((k) => !names.some((n) => n.trim().toLowerCase() === k.trim().toLowerCase()));
    box.append(help(names.length ? "A colour for each mode; leave one empty for the parts' own colours." : "No modes found: give the parameter a dropdown with choices (or load the sheet for its step names)."));
    const apply = [...PPB.modeTargets(L)];
    for (const name of names.concat(extra)) {
      const k = keyOf(name);
      if (k !== undefined && isObj(map[k])) { modePartsEditor(box, map, k, name, apply, mode, extra.includes(name)); continue; }
      const id = "f" + fieldSeq++;
      const t = input("text", k !== undefined ? map[k] : "", "own colours");
      const dl = combo(t, colourList(), { colour: true, placeholder: "the parts' own colours" }, () => { const key = keyOf(name); return key !== undefined ? map[key] : undefined; },
        (v) => { put(v === undefined ? "" : v); S.editing = null; renderInspector(); });
      const c = input("color");
      const cur = k !== undefined ? PV.colour(map[k]) : null;
      c.value = cur ? cur.slice(0, 7) : "#808080";
      const put = (v) => fieldEdit(id, () => {
        const key = keyOf(name);
        if (v === "") { if (key !== undefined) delete map[key]; }
        else map[key !== undefined ? key : name] = v;
      });
      t.addEventListener("input", () => put(t.value.trim()));
      c.addEventListener("input", () => { t.value = c.value.toUpperCase(); put(t.value); });
      t.addEventListener("blur", () => { S.editing = null; });
      const split = document.createElement("button");
      split.className = "small";
      split.textContent = "per part";
      split.title = "A colour per part (stripe, knob arc, header, ...) for this mode";
      split.addEventListener("click", () => edit(() => {
        const key = keyOf(name);
        const one = key !== undefined && typeof map[key] === "string" ? map[key] : "#808080";
        const o = {};
        for (const tg of apply) o[tg] = one;
        map[key !== undefined ? key : name] = o;
      }, S.sel));
      const f = field(name + (mode && name === mode.current ? " ●" : ""), [t, dl, c, split], extra.includes(name) ? "Not a mode of the parameter: never shown" : `Colour for "${name}" on every part`, extra.includes(name));
      box.append(f);
    }
    mixEditor(box, m, apply);
    // apply: the parts that take the mode colour
    const targets = PPB.modeTargets(L);
    const wrap = document.createElement("div");
    wrap.className = "apply-list";
    for (const t of PPB.MODE_TARGETS) {
      const l = document.createElement("label");
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = targets.has(t);
      cb.addEventListener("change", () => edit(() => {
        const set = new Set(PPB.modeTargets(L));
        if (cb.checked) set.add(t); else set.delete(t);
        setKey(m, "apply", PPB.MODE_TARGETS.filter((x) => set.has(x)), ["param", "map", "apply"]);
      }, S.sel));
      l.append(cb, " " + t.replace("_", " "));
      wrap.append(l);
    }
    box.append(field("apply to", wrap, Array.isArray(m.apply) ? "The parts that take the mode colour" : "Default: the stripe (with a stripe) and the knobs' arcs"));
    const rm = document.createElement("button");
    rm.className = "small";
    rm.textContent = "Remove mode colours";
    rm.addEventListener("click", () => edit(() => delete L.mode_colors, S.sel));
    box.append(field("", rm));
  }

  /** One mode's colours per part: a colour per target in apply; an empty one keeps the part's own colour. */
  function modePartsEditor(box, map, k, name, apply, mode, extra) {
    const o = map[k];
    const head = document.createElement("div");
    head.className = "mode-parts-head";
    head.textContent = name + (mode && name === mode.current ? " ●" : "");
    const one = document.createElement("button");
    one.className = "small";
    one.textContent = "one colour";
    one.title = "Back to one colour for every part";
    one.addEventListener("click", () => edit(() => { const first = Object.values(o).find((v) => typeof v === "string" && v.trim()); if (first) map[k] = first; else delete map[k]; }, S.sel));
    head.append(one);
    box.append(head);
    const sub = document.createElement("div");
    sub.className = "sub";
    for (const tg of apply) {
      const id = "f" + fieldSeq++;
      const t = input("text", typeof o[tg] === "string" ? o[tg] : "", "own colour");
      const dl = combo(t, colourList(), { colour: true, placeholder: "the part's own colour" }, () => o[tg],
        (v) => { put(v === undefined ? "" : v); S.editing = null; renderInspector(); });
      const c = input("color");
      const cur = PV.colour(o[tg]);
      c.value = cur ? cur.slice(0, 7) : "#808080";
      const put = (v) => fieldEdit(id, () => { if (v === "") delete o[tg]; else o[tg] = v; });
      t.addEventListener("input", () => put(t.value.trim()));
      c.addEventListener("input", () => { t.value = c.value.toUpperCase(); put(t.value); });
      t.addEventListener("blur", () => { S.editing = null; });
      sub.append(field(tg.replace("_", " "), [t, dl, c], extra ? "Not a mode of the parameter: never shown" : `"${name}" on ${tg.replace("_", " ")}`));
    }
    box.append(sub);
  }

  /** mode_colors.mix: per target in apply, the mode colour's share (0 to 1) mixed into the part's own colour. */
  function mixEditor(box, m, apply) {
    box.append(help("Mix: how much of the mode colour goes into each part (1: the mode colour itself)."));
    for (const tg of apply) {
      const def = PPB.MODE_MIX[tg] !== undefined ? PPB.MODE_MIX[tg] : 1;
      const cur = isObj(m.mix) && isNum(m.mix[tg]) ? m.mix[tg] : def;
      const id = "f" + fieldSeq++;
      const r = document.createElement("input");
      r.type = "range"; r.min = "0"; r.max = "1"; r.step = "0.01"; r.value = String(cur);
      const out = document.createElement("span");
      out.className = "mix-value" + (isObj(m.mix) && isNum(m.mix[tg]) ? "" : " muted");
      out.textContent = cur.toFixed(2) + (isObj(m.mix) && isNum(m.mix[tg]) ? "" : " (default)");
      r.addEventListener("input", () => {
        const v = Math.round(parseFloat(r.value) * 100) / 100;
        out.textContent = v.toFixed(2) + (v === def ? " (default)" : "");
        fieldEdit(id, () => {
          if (v === def) { if (isObj(m.mix)) { delete m.mix[tg]; if (!Object.keys(m.mix).length) delete m.mix; } }
          else { if (!isObj(m.mix)) setKey(m, "mix", {}, ["param", "map", "mix", "apply"]); m.mix[tg] = v; }
        });
      });
      r.addEventListener("change", () => { S.editing = null; });
      box.append(field("mix " + tg.replace("_", " "), [r, out], `Default ${def}`));
    }
  }

  function inspectPage(box, pg) {
    box.append(fText(pg, "name", "name", "page", { placeholder: "required" }));
    otherKeys(box, pg, PPB.PAGE_KEYS, "page");
  }

  function inspectColumn(box, c) {
    box.append(fText(c, "header", "header", "column", { title: "When any column of a page has a header, every column gets a header line" }));
    box.append(fText(c, "header_font", "header font", "column", { list: fontList(), placeholder: `layout's: ${layout().header_font || "Default"}` }));
    box.append(fText(c, "header_color", "header colour", "column", { list: colourList(), colour: true, placeholder: `layout's: ${layout().header_color || "theme text"}` }));
    box.append(fNum(c, "width", "width", "column", { placeholder: "its content", title: "Exactly this wide; centres its items" }));
    box.append(paddingField(c, "column", 0, "Space inside the column's edges, inside its width (the header and items get the rest)"));
    box.append(fNum(c, "gap", "gap", "column", { placeholder: "2" }));
    box.append(fSelect(c, "align", "align", "column", [[undefined, isNum(c.width) && c.width > 0 ? "default (center: a fixed width)" : "default (left)"], ["left", "left"], ["center", "center"], ["right", "right"]]));
    const fill = layout().fill_height === true;
    const vf = fSelect(c, "valign", "valign", "column", [[undefined, "default (top)"], ["top", "top"], ["center", "center"], ["bottom", "bottom"]],
      { title: fill ? "Where the content sits in the column's spare height" : "Works with the layout's fill height (off now)", unused: !fill && c.valign !== undefined });
    if (!fill && c.valign === undefined) vf.querySelector("select").disabled = true;
    box.append(vf);
    box.append(fSelect(c, "divider", "divider", "column", tri("false"), { title: "Bitwig's separator line in the gap before this column" }));
    box.append(fText(c, "background", "background", "column", { list: colourList(), colour: true, placeholder: "none: the layout's shows" }));
    otherKeys(box, c, PPB.COLUMN_KEYS, "column");
  }

  function inspectFooter(box) {
    box.append(help("Items in one row under the columns, e.g. mode dropdowns. Select the footer, then use Add (or drop parameters on it). The layout's footer gap sets the space above it."));
  }

  function inspectRow(box, r) {
    const kids = Array.isArray(r.row) ? r.row : [];
    if (kids.length && kids.every((c) => kindOf(c) === "spacer") && S.sel[S.sel.length - 2] === "items") { // a row of spacers in a column
      const rowSel = S.sel;
      const b = document.createElement("button");
      b.className = "small";
      b.textContent = "Move spacer to column";
      b.title = "Replace this row with its spacer, in the column, where it takes the column's spare height";
      b.addEventListener("click", () => edit(() => { getAt(layout(), rowSel.slice(0, -1)).splice(rowSel[rowSel.length - 1], 1, ...kids); return kids[0]; }));
      box.append(help("This row holds only spacers: in a row they take spare width, not the column's spare height."));
      box.append(field("vertical", b));
    }
    const tools = document.createElement("div");
    tools.className = "node-tools";
    const tool = (label, title, fn) => { const b = document.createElement("button"); b.className = "small"; b.textContent = label; b.title = title; b.addEventListener("click", fn); tools.append(b); };
    tool("Same knob size", "Every knob in this row gets the first knob's size (a number: a fixed cell)", () => edit(() => {
      const first = kids.find((c) => kindOf(c) === "knob");
      const size = first && first.size !== undefined ? first.size : KNOB_SIZE;
      for (const c of kids) if (kindOf(c) === "knob") setKey(c, "size", size, PPB.ITEM_KEYS);
    }, S.sel));
    tool("Equal widths", "Every item in this row gets the widest item's width (at least one cell), so the cells are equal", () => edit(() => {
      const w = Math.max(CELL_WIDTH, ...kids.map((c) => (isObj(c) && isNum(c.width) ? c.width : 0)));
      for (const c of kids) if (isObj(c) && kindOf(c) !== "row") setKey(c, "width", w, PPB.ITEM_KEYS);
    }, S.sel));
    box.append(tools);
    box.append(fNum(r, "gap", "gap", "item", { placeholder: "4" }));
    box.append(fSelect(r, "valign", "valign", "item", [[undefined, "default (top)"], ["top", "top"], ["center", "center"], ["bottom", "bottom"]]));
    box.append(fNum(r, "width", "width", "item", { title: "A fixed width: its items share it equally" }));
    box.append(paddingField(r, "item", 0, "Space inside the row's edges, inside its width (its cells share the rest)"));
    box.append(fSelect(r, "align_knobs", "align knobs", "item", tri("true"), { title: "Knobs line up their labels and centres" }));
    box.append(fText(r, "background", "background", "item", { list: colourList(), colour: true, placeholder: "none" }));
    box.append(fSelect(r, "align", "align", "item", [[undefined, "default (the column's)"], ["left", "left"], ["center", "center"], ["right", "right"]], { title: "In a column: this row only" }));
    alignSection(box, S.sel);
    otherKeys(box, r, ["row", "gap", "valign", "width", "padding", "align_knobs", "background", "align"], "item"); // a row's height does nothing: listed to remove
  }

  function inspectItem(box, it, kind) {
    if (kind === "spacer") return inspectSpacer(box, it);
    const rel = new Set(PPB.KIND_KEYS[kind].concat(PPB.COMMON_ITEM_KEYS));
    // kind
    const ks = document.createElement("select");
    for (const k of PPB.KINDS.filter((k) => k !== "row" && k !== "spacer")) ks.append(new Option(k, k));
    ks.value = kind;
    ks.addEventListener("change", () => edit(() => changeKind(it, kind, ks.value), S.sel));
    box.append(field("kind", ks));
    if (kind === "envelope") return inspectEnvelope(box, it);
    // reference
    let param = null;
    if (kind === "text") box.append(fText(it, "text", "text", "item"));
    else if (kind === "meter") box.append(fSelect(it, "meter", "source", "item", [["output", "output"]]));
    else {
      const names = params().map((p) => p.name).concat(params().map((p) => "#" + p.index), ["auto:knob", "auto:list", "auto:toggle"]);
      box.append(fText(it, kind, "parameter", "item", { list: names, optional: false, title: "A name, names separated by |, id:<ID>, #N, or auto:knob / auto:list / auto:toggle" }));
      param = PPB.resolveLayout(layout(), params()).get(key(S.sel)) || null;
      const r = document.createElement("div");
      r.className = "resolved" + (!param && S.sheet ? " bad" : "");
      r.textContent = !S.sheet ? "Load the sheet to check the reference." :
        param ? `→ #${param.index} ${param.name} (${param.id}): "${param.text}"${param.steps ? `, ${param.steps} steps` : ""}` :
          PPB.isAuto(it[kind]) ? "No parameter left for it." : "No parameter matches.";
      box.append(r);
    }
    hintsFor(box, it, kind, param);
    // label
    if (rel.has("label")) box.append(labelField(it, kind));
    if (kind === "knob") {
      box.append(sizeField(it));
      box.append(fSelect(it, "knob_style", "knob style", "item", [[undefined, "not set (default)"]].concat(knobStyleList().map((s) => [s, s]))));
    }
    if (kind === "toggle") box.append(fText(it, "style", "style", "item", { list: buttonStyleList(), placeholder: "Bitwig's default", title: "One of the theme's button styles" }));
    if (kind === "meter") box.append(fSelect(it, "style", "style", "item", [[undefined, "default (needle)"], ["needle", "needle"], ["bar", "bar"], ["level", "level"]]));
    if (rel.has("font")) box.append(fText(it, "font", kind === "text" ? "font" : "label font", "item", { list: fontList(), placeholder: `layout's: ${layout().label_font || "Default"}` }));
    if (rel.has("color")) box.append(fText(it, "color", kind === "text" ? "colour" : "label colour", "item", { list: colourList(), colour: true, placeholder: `layout's: ${layout().label_color || "theme text"}` }));
    if (rel.has("value_font")) {
      box.append(fText(it, "value_font", "value font", "item", { list: fontList(), placeholder: `layout's: ${layout().value_font || "Default"}` }));
      box.append(fText(it, "value_color", "value colour", "item", { list: colourList(), colour: true, placeholder: `layout's: ${layout().value_color || "theme text"}` }));
    }
    if (rel.has("value_format")) formatEditor(box, it);
    if (kind === "dropdown") {
      box.append(fText(it, "style", "style", "item", { list: dropdownStyleList(), placeholder: "Default", title: "One of the theme's dropdown styles (not the button styles); the shown value's font and colour come from it" }));
      choicesEditor(box, it, "choices", param);
    }
    if (kind === "knob" || kind === "value") {
      box.append(h3("Behaviour"));
      const L = layout();
      box.append(fSelect(it, "refresh", "refresh", "item", [[undefined, `layout's (${L.refresh || "none"})`], ["none", "none"], ["settle", "settle"]],
        { title: "settle: the plug-in's text is asked again 150, 500 and 1000 ms after each change, for text that lags or drifts" }));
      if (kind === "knob") {
        const def = L.typing || (it.values ? "mapping" : "plugin");
        box.append(fSelect(it, "typing", "typing", "item", [[undefined, `default (${def})`], ["plugin", "plugin"], ["mapping", "mapping"], ["normalised", "normalised"]],
          { title: "plugin: the plug-in converts typed text; mapping: converted by values; normalised: a number 0 to 1" }));
        valuesEditor(box, it, param);
      }
    }
    box.append(h3("Placement"));
    box.append(fNum(it, "width", "width", "item"));
    const noHeight = PPB.heightIgnored(it);
    const hf = fNum(it, "height", "height", "item", noHeight ? { title: noHeight, placeholder: "no effect here" } : {});
    if (noHeight && it.height === undefined) { hf.querySelector("input").disabled = true; hf.querySelector("label").classList.add("unused"); }
    box.append(hf);
    if (noHeight) box.append(help(noHeight.charAt(0).toUpperCase() + noHeight.slice(1) + "."));
    box.append(fText(it, "background", "background", "item", { list: colourList(), colour: true, placeholder: "none" }));
    box.append(fSelect(it, "align", "align", "item", [[undefined, "default (the column's)"], ["left", "left"], ["center", "center"], ["right", "right"]], { title: "In a column: this item only" }));
    box.append(fSelect(it, "valign", "valign", "item", [[undefined, "default (the row's)"], ["top", "top"], ["center", "center"], ["bottom", "bottom"]], { title: "In a row: this item only" }));
    alignSection(box, S.sel);
    otherKeys(box, it, PPB.KINDS.concat([...rel]), "item");
  }

  /** A spacer ({"spacer": true}, no other keys): what it takes, as the preview draws it (Panels.add: a Filler). */
  function inspectSpacer(box, it) {
    const L = layout();
    const sel = S.sel;
    const across = sel[sel.length - 2] === "row" || sel[sel.length - 2] === "footer"; // in a row (or the footer row): it grows sideways
    const r = document.createElement("div");
    r.className = "resolved";
    r.textContent = across ? "↔ Grows horizontally: in a row, it takes the row's spare width." : "↕ Grows vertically: in a column, it takes the column's spare height.";
    box.append(r);
    const node = [...document.querySelectorAll(".pv-body .pv-spaceritem")].find((n) => n.dataset.path === JSON.stringify(sel));
    const m = document.createElement("div");
    if (node) {
      const size = across ? node.offsetWidth : node.offsetHeight;
      const none = size < 0.5;
      m.className = "resolved" + (none ? " bad" : "");
      m.textContent = `Takes ${fmt(size)} units ${across ? "of width" : "of height"} now.` + (!none ? "" : across
        ? (sel[sel.length - 2] === "footer" ? " No spare width: the footer row is as wide as its items." : " No spare width: give the row a width.")
        : L && L.fill_height !== true ? " No spare height: turn on fill_height (layout), or make a column beside it taller." : " No spare height: the column's content fills it.");
    } else {
      m.className = "resolved";
      m.textContent = "Not on the page shown.";
    }
    box.append(m);
    // a spacer in a row that sits in a column: it can move into the column, to take spare height instead
    if (sel[sel.length - 2] === "row" && sel[sel.length - 4] === "items") {
      const only = getAt(L, sel.slice(0, -2)).row.every((c) => kindOf(c) === "spacer");
      const b = document.createElement("button");
      b.className = "small";
      b.textContent = "Move to column";
      b.title = "Take it out of the row into the column, where it takes the column's spare height";
      b.addEventListener("click", () => edit(() => spacerToColumn(sel)));
      box.append(field("vertical", b, only ? "This row holds only spacers: as a row it takes spare width, not the column's height" : ""));
      if (only) box.append(help("This row holds nothing but spacers, so it takes spare width, not the column's spare height. Move it to the column to push the items above and below it apart."));
    }
    box.append(help(across
      ? "Several spacers share the spare width equally. A row has spare width only with a width (or in a column with a width). In a row of equal cells, spacers aren't cells: they take what the cells leave."
      : "Several spacers share the spare height equally; the column's valign isn't used. A column has spare height with the layout's fill_height, or when a column beside it is taller."));
    otherKeys(box, it, ["spacer"], "item");
  }

  function changeKind(it, from, to) {
    let v = it[from];
    // an envelope's reference is its attack's; another kind's reference becomes the attack
    if (from === "envelope") { const a = PPB.envelopeSegments(it).find((s) => s.key === "attack"); v = a ? a.ref : ""; }
    if (to === "envelope") v = { attack: typeof v === "string" ? v : "" };
    const keys = Object.keys(it);
    const entries = keys.map((k) => (k === from ? [to, to === "meter" ? "output" : to === "text" && typeof v !== "string" ? "TEXT" : v] : [k, it[k]]));
    for (const k of keys) delete it[k];
    for (const [k, val] of entries) it[k] = val;
  }

  /** Keys present that have no effect here: listed, with a way to remove them. */
  function otherKeys(box, o, known, level) {
    const extra = Object.keys(o).filter((k) => !known.includes(k));
    if (!extra.length) return;
    box.append(h3("Other keys"));
    for (const k of extra) {
      const b = document.createElement("button");
      b.className = "small";
      b.textContent = "Remove";
      const code = document.createElement("code");
      code.textContent = JSON.stringify(o[k]).slice(0, 60);
      const f = field(k, [code, b], "No effect here (see the problems list)", true);
      f.classList.add("other-keys");
      b.addEventListener("click", () => edit(() => delete o[k], S.sel));
      box.append(f);
    }
  }

  function labelField(it, kind) {
    const l = it.label;
    const mode = l === undefined ? "param" : l === false ? "none" : isObj(l) ? "icon" : "text";
    const s = document.createElement("select");
    s.append(new Option("parameter name", "param"), new Option("text", "text"), new Option("no label", "none"), new Option("Bitwig icon", "icon"));
    s.value = mode;
    s.addEventListener("change", () => edit(() => {
      const p = PPB.resolveLayout(layout(), params()).get(key(S.sel));
      const v = s.value === "param" ? undefined : s.value === "none" ? false : s.value === "icon" ? { icon: "icons/device/stereo.svg", text: p ? p.name : "" } : (p ? p.name : String(it[kind] || "").split("|")[0]);
      setVal(it, "label", v, "item");
    }, S.sel));
    const wrap = document.createElement("div");
    wrap.append(field("label", s, "Text that replaces the parameter's name, no label, or one of Bitwig's own icons (referenced by path, never copied)"));
    if (mode === "text") wrap.append(fText(it, "label", "text", "item"));
    if (mode === "icon") {
      const sub = document.createElement("div");
      sub.className = "sub";
      sub.append(fText(l, "icon", "icon path", null, { placeholder: "icons/device/stereo.svg", title: "A path in Bitwig's resources" }));
      sub.append(fText(l, "text", "tooltip", null));
      sub.append(fNum(l, "size", "size", null, { placeholder: "17" }));
      wrap.append(sub);
    }
    return wrap;
  }

  function sizeField(it) {
    const id = "f" + fieldSeq++;
    const s = document.createElement("select");
    s.append(new Option("default (small)", ""));
    for (const k of PPB.KNOB_SIZES) s.append(new Option(k, k));
    s.append(new Option("exact (knob cell)", "#"));
    const cur = it.size;
    s.value = cur === undefined ? "" : isNum(cur) ? "#" : PPB.KNOB_SIZES.includes(String(cur).toLowerCase()) ? String(cur).toLowerCase() : "";
    const parts = [s];
    if (isNum(cur)) {
      const n = input("number", cur);
      n.addEventListener("input", () => { const v = Number(n.value); if (n.value !== "" && isFinite(v)) fieldEdit(id, () => { it.size = v; }); });
      parts.push(n);
    }
    s.addEventListener("change", () => edit(() => setVal(it, "size", s.value === "" ? undefined : s.value === "#" ? 34 : s.value, "item"), S.sel));
    return field("size", parts, "A theme size, or a number: a knob cell of that exact size (label, knob, value text)");
  }

  function formatEditor(box, it) {
    const vf = it.value_format;
    const mode = vf === undefined || vf === "plugin" ? "plugin" : vf === "percent" ? "percent" : isObj(vf) && has(vf, "map") ? "map" : "range";
    const s = document.createElement("select");
    s.append(new Option("the plug-in's text", "plugin"), new Option("percent", "percent"), new Option("range", "range"), new Option("map", "map"));
    s.value = mode;
    s.addEventListener("change", () => edit(() => setVal(it, "value_format",
      s.value === "plugin" ? undefined : s.value === "percent" ? "percent" : s.value === "range" ? { range: [0, 100], decimals: 0, unit: "" } : { map: [[0, "Min"], [1, "Max"]] }, "item"), S.sel));
    box.append(h3("Value text"));
    box.append(field("value format", s, "Display only: the plug-in keeps its own values"));
    if (mode === "range") {
      const sub = document.createElement("div");
      sub.className = "sub";
      const id = "f" + fieldSeq++;
      const lo = input("number", Array.isArray(vf.range) ? vf.range[0] : 0), hi = input("number", Array.isArray(vf.range) ? vf.range[1] : 1);
      const upd = () => { const a = Number(lo.value), b = Number(hi.value); if (isFinite(a) && isFinite(b) && lo.value !== "" && hi.value !== "") fieldEdit(id, () => setKey(vf, "range", [a, b], ["range", "decimals", "unit"])); };
      lo.addEventListener("input", upd);
      hi.addEventListener("input", upd);
      sub.append(field("range", [lo, hi], "The value mapped from 0..1 to this range"));
      sub.append(fNum(vf, "decimals", "decimals", null, { placeholder: "0" }));
      sub.append(fText(vf, "unit", "unit", null));
      box.append(sub);
    }
    if (mode === "map") {
      const sub = document.createElement("div");
      sub.className = "sub";
      choicesEditor(sub, vf, "map", null, true);
      box.append(sub);
    }
  }

  /**
   * A list of [name, value] pairs (dropdown choices) or [value, name] (a value_format map): the
   * pair order already in the file is kept.
   */
  function choicesEditor(box, obj, k, param, isMap) {
    const list = obj[k];
    if (!isMap) {
      box.append(h3("Choices"));
      box.append(help(param && param.steps > 0 && !list ? `Without choices the dropdown shows the parameter's ${param.steps} steps.` :
        param && param.steps === 0 && !list ? "A continuous parameter needs choices: each picks a normalised value." : "Presets: picking one sets the value (normalised 0..1)."));
    }
    const pairs = document.createElement("div");
    pairs.className = "pairs";
    const nameFirst = (e) => !Array.isArray(e) || typeof e[0] === "string" || (!isMap && typeof e[1] !== "string");
    const id = "f" + fieldSeq++;
    (Array.isArray(list) ? list : []).forEach((e, i) => {
      const nf = nameFirst(e);
      const name = input("text", Array.isArray(e) ? (nf ? e[0] : e[1]) : "");
      const val = input("number", Array.isArray(e) ? (nf ? e[1] : e[0]) : "");
      val.step = "0.01";
      const x = document.createElement("button");
      x.textContent = "×";
      x.title = "Remove";
      const upd = () => {
        const v = Number(val.value);
        if (val.value === "" || !isFinite(v)) return;
        fieldEdit(id + "-" + i, () => { list[i] = nf ? [name.value, v] : [v, name.value]; });
      };
      name.addEventListener("input", upd);
      val.addEventListener("input", upd);
      x.addEventListener("click", () => edit(() => { list.splice(i, 1); if (!list.length) delete obj[k]; }, S.sel));
      const r = document.createElement("div");
      r.className = "pair";
      r.append(name, val, x);
      pairs.append(r);
    });
    box.append(pairs);
    const tools = document.createElement("div");
    tools.className = "pair-tools";
    const b = (t, fn, title) => { const x = document.createElement("button"); x.className = "small"; x.textContent = t; if (title) x.title = title; x.addEventListener("click", fn); tools.append(x); };
    b("+ Add", () => edit(() => {
      const cur = Array.isArray(obj[k]) ? obj[k] : [];
      const order = isMap ? ["map"] : PPB.ITEM_KEYS;
      const nf = cur.length ? nameFirst(cur[0]) : !isMap;
      cur.push(nf ? ["", 1] : [1, ""]);
      setKey(obj, k, cur, order);
    }, S.sel));
    if (!isMap) {
      const n = input("number", "", "N");
      n.style.width = "46px";
      n.min = "2";
      b("Evenly spaced", () => {
        const c = Math.round(Number(n.value));
        if (!(c >= 2 && c <= 512)) return toast("Enter how many choices (2 or more)");
        edit(() => setVal(obj, k, Array.from({ length: c }, (_, i) => [String(i + 1), PPB.round(i / (c - 1))]), "item"), S.sel);
      }, "N choices at 0, 1/(N-1), ... 1");
      tools.append(n);
      if (param && (param.step_names || []).length) b("From step names", () => edit(() => setVal(obj, k, param.step_names.map((s, i) => [s, PPB.round(i / Math.max(1, param.step_names.length - 1))]), "item"), S.sel));
      const h = param && PPB.paramHints(param);
      if (h && h.integers) b(`Whole numbers ${h.integers.from}–${h.integers.to}`, () => edit(() => setVal(obj, k, PPB.integerChoices(h.integers.from, h.integers.to), "item"), S.sel));
      if (param && param.steps === 0 && !h.integers && !h.unreliable && param.min_text !== null && param.max_text !== param.min_text)
        b("Ends from the sheet", () => edit(() => setVal(obj, k, [[param.min_text, 0], [param.max_text, 1]], "item"), S.sel), "Two choices with the plug-in's texts at 0 and 1");
    }
    if (Array.isArray(list) && list.length) b("Sort by value", () => edit(() => list.sort((a, c) => (nameFirst(a) ? a[1] : a[0]) - (nameFirst(c) ? c[1] : c[0])), S.sel));
    box.append(tools);
  }

  function valuesEditor(box, it, param, title) {
    const v = it.values;
    const pending = S.pending.get(it);
    box.append(h3(title || "Values (typed entry)"));
    if (pending && !v) {
      const d = document.createElement("div");
      d.className = "hint pending";
      const pv = pending.values;
      d.innerHTML = "<p></p><div class=actions></div>";
      d.querySelector("p").textContent = `Prefilled from the sheet, not in the layout until you confirm: min ${pv.min}, max ${pv.max}${pv.unit ? ", unit " + pv.unit : ""}${pv.curve ? ", curve " + pv.curve : ""}.`;
      const a = d.querySelector(".actions");
      const ok = document.createElement("button"); ok.className = "small primary"; ok.textContent = "Confirm";
      ok.addEventListener("click", () => edit(() => { setVal(it, "values", Object.assign({}, pv), "item"); S.pending.delete(it); }, S.sel));
      const no = document.createElement("button"); no.className = "small"; no.textContent = "Dismiss";
      no.addEventListener("click", () => { S.pending.delete(it); renderInspector(); });
      a.append(ok, no);
      box.append(d);
    }
    const mode = v === undefined ? "none" : isObj(v) && has(v, "points") ? "points" : "range";
    const s = document.createElement("select");
    s.append(new Option("none", "none"), new Option("min / max", "range"), new Option("points", "points"));
    s.value = mode;
    s.addEventListener("change", () => edit(() => {
      const h = param ? PPB.paramHints(param) : {};
      setVal(it, "values", s.value === "none" ? undefined : s.value === "range" ? Object.assign({ min: 0, max: 100 }, h.values || {}) :
        { points: [[isObj(v) && isNum(v.min) ? v.min : 0, 0], [isObj(v) && isNum(v.max) ? v.max : 100, 1]] }, "item");
      S.pending.delete(it);
    }, S.sel));
    box.append(field("values", s, "From the normalised value (0..1) to the value the plug-in displays; typed values are read in those units"));
    if (mode === "none") return;
    const sub = document.createElement("div");
    sub.className = "sub";
    if (mode === "range") {
      sub.append(fNum(v, "min", "min", "values", { step: "any", title: "Displayed value at 0" }));
      sub.append(fNum(v, "max", "max", "values", { step: "any", title: "Displayed value at 1" }));
      const id = "f" + fieldSeq++;
      const c = v.curve;
      const cs = document.createElement("select");
      for (const [val, t] of [["", "linear (default)"], ["linear", "linear"], ["log", "log"], ["exp", "exp (same as log)"], ["pow", "pow"]]) cs.append(new Option(t, val));
      cs.value = c === undefined ? "" : isObj(c) ? "pow" : String(c).toLowerCase();
      const parts = [cs];
      if (isObj(c)) {
        const k = input("number", c.pow);
        k.step = "any";
        k.title = "exponent";
        k.addEventListener("input", () => { const n = Number(k.value); if (k.value !== "" && isFinite(n)) fieldEdit(id, () => { c.pow = n; }); });
        parts.push(k);
      }
      cs.addEventListener("change", () => edit(() => setVal(v, "curve", cs.value === "" ? undefined : cs.value === "pow" ? { pow: 2 } : cs.value, "values"), S.sel));
      sub.append(field("curve", parts, "log/exp: min × (max/min)^n (min and max above 0); pow: min + (max − min) × n^k"));
    } else {
      const pts = Array.isArray(v.points) ? v.points : [];
      const pairs = document.createElement("div");
      pairs.className = "pairs";
      const id = "f" + fieldSeq++;
      pts.forEach((p, i) => {
        const d = input("number", Array.isArray(p) ? p[0] : ""), n = input("number", Array.isArray(p) ? p[1] : "");
        d.step = "any"; n.step = "0.01"; d.title = "display value"; n.title = "normalised 0..1";
        const x = document.createElement("button"); x.textContent = "×";
        const upd = () => { const a = Number(d.value), b = Number(n.value); if (d.value !== "" && n.value !== "" && isFinite(a) && isFinite(b)) fieldEdit(id + i, () => { pts[i] = [a, b]; }); };
        d.addEventListener("input", upd); n.addEventListener("input", upd);
        x.addEventListener("click", () => edit(() => pts.splice(i, 1), S.sel));
        const r = document.createElement("div"); r.className = "pair"; r.append(d, n, x); pairs.append(r);
      });
      sub.append(help("Pairs of [display value, normalised 0..1], interpolated between them."));
      sub.append(pairs);
      const add = document.createElement("button"); add.className = "small"; add.textContent = "+ Point";
      add.addEventListener("click", () => edit(() => pts.push([0, 0.5]), S.sel));
      sub.append(add);
    }
    sub.append(fText(v, "unit", "unit", "values", { list: ["%", "ms", "s", "Hz", "kHz", "dB", "X"], placeholder: "none", title: "ms/s and Hz/kHz convert into each other when typing; k multiplies by 1000" }));
    sub.append(fNum(v, "steps", "steps", "values", { placeholder: "continuous", title: "Typed values snap to the nearest of this many steps" }));
    sub.append(fSelect(v, "normalised", "normalised", "values", tri("false"), { title: "true: a bare number from 0 to 1 is also read as normalised" }));
    otherKeys(sub, v, PPB.VALUES_KEYS, "values");
    box.append(sub);
  }

  /**
   * An envelope (Layouts.envelope, EnvelopeDisplay): a reference per key, with or without a time
   * mapping; scale, points, colours and size.
   */
  function inspectEnvelope(box, it) {
    if (!isObj(it.envelope)) {
      box.append(help("The envelope must be an object; fix it in the JSON tab, or press the button."));
      const b = document.createElement("button");
      b.className = "small";
      b.textContent = "Start an envelope";
      b.addEventListener("click", () => edit(() => { it.envelope = { attack: "" }; }, S.sel));
      box.append(b);
      return;
    }
    const e = it.envelope;
    box.append(help("Drawn in this order: delay, attack (required), hold, decay, sustain, release. sustain needs decay; release after decay needs sustain. Each segment is as wide as its normalised value, or with values on every time key as wide as its time (ms or s)."));
    const resolved = PPB.resolveLayout(layout(), params());
    const names = params().map((p) => p.name).concat(params().map((p) => "#" + p.index));
    const order = PPB.ENVELOPE_KEYS;
    for (const k of order) {
      const v = e[k];
      const mode = v === undefined ? "none" : isObj(v) ? (isObj(v.values) ? "values" : "object") : "ref";
      box.append(h3(k));
      const s = document.createElement("select");
      s.append(new Option("not used", "none"), new Option("parameter", "ref"));
      if (k !== "sustain") s.append(new Option("parameter + time mapping", "values"));
      if (mode === "object") s.append(new Option("parameter (object)", "object"));
      s.value = mode;
      s.addEventListener("change", () => edit(() => {
        const ref = isObj(e[k]) ? e[k].param : e[k];
        const nv = s.value === "none" ? undefined : s.value === "ref" ? String(ref || "") :
          { param: String(ref || ""), values: isObj(e[k]) && isObj(e[k].values) ? e[k].values : { min: 1, max: 10000, unit: "ms", curve: "log" } };
        if (nv === undefined) delete e[k];
        else setKey(e, k, nv, PPB.ENVELOPE_KEYS); // an empty reference is kept, to be filled in
      }, S.sel));
      box.append(field("use", s));
      if (mode === "none") continue;
      const holder = isObj(v) ? v : e;
      const refKey = isObj(v) ? "param" : k;
      box.append(fText(holder, refKey, "parameter", null, { list: names, optional: false, placeholder: "required", title: "A name, names separated by |, id:<ID> or #N (not auto:)" }));
      const p = resolved.get(key(S.sel.concat("envelope", k))) || null;
      const r = document.createElement("div");
      r.className = "resolved" + (!p && S.sheet ? " bad" : "");
      r.textContent = !S.sheet ? "Load the sheet to check the reference." : p ? `→ #${p.index} ${p.name}: "${p.text}" (value ${PPB.round(p.value, 4)})` : "No parameter matches.";
      box.append(r);
      if (mode === "values") {
        const sub = document.createElement("div");
        sub.className = "sub";
        valuesEditor(sub, v, p, "Time mapping (unit ms or s)");
        box.append(sub);
      }
    }
    const timed = order.filter((k) => k !== "sustain" && e[k] !== undefined);
    const mapped = timed.filter((k) => isObj(e[k]) && isObj(e[k].values));
    if (mapped.length && mapped.length < timed.length) {
      const src = e[mapped[0]].values;
      const b = document.createElement("button");
      b.className = "small";
      b.textContent = `Use ${mapped[0]}'s mapping on every time key`;
      b.title = "Values on every time key or on none";
      b.addEventListener("click", () => edit(() => {
        for (const k of timed) if (!(isObj(e[k]) && isObj(e[k].values)))
          e[k] = { param: String((isObj(e[k]) ? e[k].param : e[k]) || ""), values: JSON.parse(JSON.stringify(src)) };
      }, S.sel));
      box.append(field("", b));
    }
    box.append(h3("Display"));
    box.append(fSelect(it, "scale", "scale", "item", [[undefined, "default (linear)"], ["linear", "linear"], ["log", "log"]],
      { title: "log: each segment as wide as log10(1 + its time in ms); needs values on every time key" }));
    box.append(fSelect(it, "points", "points", "item", tri("true"), { title: "Dots on the breakpoints" }));
    box.append(fText(it, "color", "line colour", "item", { list: colourList().concat(["Display Waveform"]), colour: true, placeholder: "Display Waveform" }));
    box.append(fText(it, "fill_color", "fill colour", "item", { list: colourList(), colour: true, placeholder: "the line, faint" }));
    box.append(fText(it, "display_color", "display colour", "item", { list: ["none", "Display Background"].concat(colourList()), colour: true, placeholder: "Display Background", title: "none: no background" }));
    box.append(h3("Placement"));
    box.append(fNum(it, "width", "width", "item", { placeholder: "120" }));
    box.append(fNum(it, "height", "height", "item", { placeholder: "50" }));
    box.append(fText(it, "background", "background", "item", { list: colourList(), colour: true, placeholder: "none", title: "A box of 2 units around the display" }));
    box.append(fSelect(it, "align", "align", "item", [[undefined, "default (the column's)"], ["left", "left"], ["center", "center"], ["right", "right"]], { title: "In a column: this item only" }));
    box.append(fSelect(it, "valign", "valign", "item", [[undefined, "default (the row's)"], ["top", "top"], ["center", "center"], ["bottom", "bottom"]], { title: "In a row: this item only" }));
    otherKeys(box, it, ["envelope", "scale", "points", "color", "fill_color", "display_color", "font", "width", "height", "background", "align", "valign"], "item");
  }

  /** What the sheet says about the item's parameter, with buttons; nothing is applied without a click. */
  function hintsFor(box, it, kind, p) {
    if (!p) return;
    const h = PPB.paramHints(p);
    const actions = [];
    const L = layout();
    const refresh = it.refresh || L.refresh || "none";
    if (h.unreliable && refresh !== "settle" && (kind === "knob" || kind === "value"))
      actions.push(["Set refresh: settle", () => edit(() => setVal(it, "refresh", "settle", "item"), S.sel)]);
    if (h.toggle && kind !== "toggle") actions.push(["Make it a toggle", () => edit(() => changeKind(it, kind, "toggle"), S.sel)]);
    if (h.integers && kind !== "dropdown")
      actions.push([`Dropdown with ${h.integers.count} choices`, () => edit(() => { changeKind(it, kind, "dropdown"); setVal(it, "choices", PPB.integerChoices(h.integers.from, h.integers.to), "item"); }, S.sel)]);
    if (h.integers && kind === "knob" && !(isObj(it.values) && it.values.steps))
      actions.push([`Stepped knob (${h.integers.count} steps)`, () => edit(() => setVal(it, "values", { min: h.integers.from, max: h.integers.to, steps: h.integers.count }, "item"), S.sel)]);
    if (h.values && kind === "knob" && !S.pending.get(it)) {
      const v = it.values;
      const same = isObj(v) && v.min === h.values.min && v.max === h.values.max && (v.unit || "") === h.values.unit;
      if (!same) actions.push([`Values ${h.values.min}…${h.values.max}${h.values.unit ? " " + h.values.unit : ""} from the sheet`, () => edit(() => {
        const nv = isObj(it.values) && !has(it.values, "points") ? it.values : {};
        setKey(nv, "min", h.values.min, PPB.VALUES_KEYS); setKey(nv, "max", h.values.max, PPB.VALUES_KEYS);
        setVal(nv, "unit", h.values.unit || undefined, "values");
        setVal(it, "values", nv, "item");
      }, S.sel)]);
      if (h.curve === "log" && !(isObj(v) && v.curve === "log") && isObj(v) && !has(v, "points"))
        actions.push(["Use curve log", () => edit(() => setVal(v, "curve", "log", "values"), S.sel)]);
    }
    if (!h.notes.length) return;
    const d = document.createElement("div");
    d.className = "hint";
    for (const n of h.notes) { const q = document.createElement("p"); q.textContent = n; d.append(q); }
    if (actions.length) {
      const a = document.createElement("div");
      a.className = "actions";
      for (const [t, fn] of actions) { const b = document.createElement("button"); b.className = "small"; b.textContent = t; b.addEventListener("click", fn); a.append(b); }
      d.append(a);
    }
    box.append(d);
  }

  // ---------- export ----------

  function fileName() {
    if (S.fileName && !/\.parameters\.json$/i.test(S.fileName)) return S.fileName;
    // as the framework's Save Panel from Clipboard names it: the panel's name, else the plug-in name it matches
    const L = layout();
    const m = L && L.match && typeof L.match.name === "string" ? L.match.name.trim() : "";
    const n = (typeof S.file.name === "string" && S.file.name.trim()) || (m === "*" ? "All plug-ins" : m);
    const safe = n.replace(/[^A-Za-z0-9 ._()-]/g, "_").trim().replace(/^\.+/, "");
    return (safe || "panel") + ".json";
  }

  function download() {
    if (!S.file) return;
    const errors = S.problems.filter((p) => p.level === "error").length;
    if (errors && !confirm(`The layout has ${errors} error(s): the framework will show Bitwig's own page instead. Save it anyway?`)) return;
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([exportText()], { type: "application/json" }));
    a.download = fileName();
    document.body.append(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  }

  function copy() {
    if (!S.file) return;
    const t = exportText();
    (navigator.clipboard ? navigator.clipboard.writeText(t) : Promise.reject()).then(() => toast("Layout JSON copied"), () => {
      $("json").value = t;
      showTab("json");
      $("json").select();
      toast("Copy it from the JSON tab (Ctrl+C)");
    });
  }

  let toastTimer = 0;
  function toast(t) {
    const e = $("toast");
    e.textContent = t;
    e.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { e.hidden = true; }, 3200);
  }

  function showTab(name) {
    document.querySelectorAll(".tabs button").forEach((b) => b.classList.toggle("on", b.dataset.tab === name));
    $("tab-problems").hidden = name !== "problems";
    $("tab-json").hidden = name !== "json";
  }

  // ---------- events ----------

  function setup() {
    $("b-new").addEventListener("click", () => newLayout(false));
    $("b-start-over").addEventListener("click", startOver);
    $("b-open").addEventListener("click", () => $("file-input").click());
    $("file-input").addEventListener("change", (e) => { readFiles(e.target.files); e.target.value = ""; });
    $("b-paste").addEventListener("click", () => { $("paste-msg").textContent = ""; $("paste-text").value = ""; $("paste-dialog").showModal(); });
    $("paste-load").addEventListener("click", (e) => {
      const err = loadText($("paste-text").value, "");
      if (err) { e.preventDefault(); $("paste-msg").textContent = err; }
    });
    $("b-undo").addEventListener("click", () => undoRedo(S.undo, S.redo));
    $("b-redo").addEventListener("click", () => undoRedo(S.redo, S.undo));
    $("b-copy").addEventListener("click", copy);
    $("b-download").addEventListener("click", download);
    $("layout-pick").addEventListener("change", (e) => { S.li = parseInt(e.target.value, 10); S.sel = []; S.page = 0; renderAll(); });
    document.querySelectorAll("[data-add]").forEach((b) => b.addEventListener("click", () => {
      if (!S.file) newLayout(true);
      add(b.dataset.add);
    }));
    document.querySelectorAll(".tabs button").forEach((b) => b.addEventListener("click", () => showTab(b.dataset.tab)));
    $("b-json-apply").addEventListener("click", () => {
      let json;
      try { json = JSON.parse($("json").value); } catch (e) { $("json-msg").textContent = "Not valid JSON: " + e.message; return; }
      if (!isObj(json)) { $("json-msg").textContent = "A layout file is an object"; return; }
      edit(() => { S.file = json; if (Array.isArray(json.layouts)) S.li = Math.min(S.li, Math.max(0, json.layouts.length - 1)); }, getAt(Array.isArray(json.layouts) ? json.layouts[S.li] : json, S.sel) !== undefined ? S.sel : []);
      $("json").blur();
      renderJson();
      toast("Edited JSON applied");
    });
    $("param-filter").addEventListener("input", renderParams);
    $("zoom").value = String(S.zoom);
    $("zoom").addEventListener("change", (e) => { S.zoom = parseFloat(e.target.value); renderPreview(); save(); });
    $("chain").value = S.chain;
    $("chain").addEventListener("input", (e) => { S.chain = e.target.value; renderPreview(); save(); });
    $("chain").placeholder = String(PT.sizes.available);
    $("b-sheet-clear").addEventListener("click", () => {
      S.sheet = null;
      S.sheetName = "";
      S.openParams.clear();
      $("param-filter").value = "";
      renderAll();
      toast("Parameter sheet unloaded");
    });
    for (const g of ["rulers", "zones", "boxes", "fit", "edges"]) {
      const c = $("g-" + g);
      c.checked = !!S.guides[g];
      c.addEventListener("change", () => { S.guides[g] = c.checked; renderPreview(); save(); });
    }
    // a picker's list closes when the inspector scrolls or re-renders under it
    document.addEventListener("scroll", (e) => { if (!(S.picker && S.picker.pop.contains(e.target))) closePicker(); }, true);
    addEventListener("resize", closePicker);
    // the hovered item's edges as guides (the selected item's stay drawn)
    $("preview").addEventListener("mouseover", (e) => {
      const t = e.target.closest(".pv-body [data-path]");
      const p = t ? JSON.parse(t.dataset.path) : null;
      const k = p ? key(p) : "";
      if (k === S.hoverGuide) return;
      S.hoverGuide = k;
      S.hoverPath = p;
      if (S.guides.edges) drawGuides();
    });
    $("preview").addEventListener("mouseleave", () => { S.hoverGuide = ""; S.hoverPath = null; if (S.guides.edges) drawGuides(); });
    // with boxes on: a box's size in layout units on hover
    $("preview").addEventListener("mouseover", (e) => {
      if (!S.guides.boxes) return;
      const t = e.target.closest(".pb, .pv-col, .pv-fix, .pv-knob, .pv-dropdown, .pv-button, .pv-envelope, .pv-needle, .pv-text, .pv-slider");
      if (!t) return;
      const r = t.getBoundingClientRect();
      const kind = t.classList.contains("pv-col") ? "column" : t.classList.contains("pv-row") ? "row" : t.classList.contains("pv-fix") ? "fixed line" :
        t.classList.contains("pb") ? (t.classList.contains("pb-h") ? "row box" : "column box") : "control";
      t.title = `${kind}: ${fmt(r.width / S.zoom)} × ${fmt(r.height / S.zoom)} units`;
    });
    $("b-basics").addEventListener("click", () => { fillBasics(); $("basics").showModal(); });
    // Stripe & modes: the layout's settings, scrolled to the stripe and mode colours
    $("b-look").addEventListener("click", () => {
      if (!layout()) newLayout(true);
      select([]);
      const h = [...document.querySelectorAll("#inspector h3")].find((x) => x.textContent === "Stripe");
      if (!h) return;
      h.scrollIntoView({ block: "start" });
      h.classList.add("flash");
      setTimeout(() => h.classList.remove("flash"), 1200);
    });
    // a height zone's label: select the object its setting belongs to
    $("preview").addEventListener("click", (e) => {
      const z = e.target.closest(".pv-zone-text[data-select]");
      if (!z) return;
      e.stopPropagation();
      select(JSON.parse(z.dataset.select));
    }, true);
    $("view-select").addEventListener("change", (e) => { S.view = e.target.value === "layout" ? null : e.target.value; renderPreview(); });
    $("preview").addEventListener("click", (e) => {
      const b = e.target.closest(".pv-barbtn[data-view]");
      if (!b) return;
      e.stopPropagation();
      S.view = b.dataset.view === "layout" ? null : b.dataset.view;
      renderPreview();
    }, true);
    $("mode-select").addEventListener("change", (e) => { S.modePreview = e.target.value || null; renderPreview(); });
    $("b-theme-clear").addEventListener("click", () => { S.theme = null; PT.apply(null); renderAll(); toast("Back to the default theme"); });
    $("b-overlay").addEventListener("click", () => $("overlay-input").click());
    $("overlay-input").addEventListener("change", (e) => {
      const f = e.target.files && e.target.files[0];
      e.target.value = "";
      if (!f) return;
      const r = new FileReader();
      r.onload = () => { S.overlay = r.result; $("overlay-tools").hidden = false; renderOverlay(); };
      r.readAsDataURL(f);
    });
    $("overlay-opacity").addEventListener("input", (e) => { S.overlayOpacity = parseFloat(e.target.value); renderOverlay(); });
    $("overlay-x").addEventListener("input", (e) => { S.overlayX = parseFloat(e.target.value) || 0; renderOverlay(); });
    $("overlay-y").addEventListener("input", (e) => { S.overlayY = parseFloat(e.target.value) || 0; renderOverlay(); });
    $("overlay-clear").addEventListener("click", () => { S.overlay = null; $("overlay-tools").hidden = true; renderOverlay(); });
    setupOutlineEvents();
    setupPreviewEvents();
    document.addEventListener("dragend", endDrag);

    // files dropped on the page
    let depth = 0;
    const isFiles = (e) => e.dataTransfer && Array.from(e.dataTransfer.types || []).includes("Files");
    window.addEventListener("dragenter", (e) => { if (isFiles(e)) { depth++; $("drop-hint").hidden = false; } });
    window.addEventListener("dragleave", (e) => { if (isFiles(e) && --depth <= 0) { depth = 0; $("drop-hint").hidden = true; } });
    window.addEventListener("dragover", (e) => { if (isFiles(e)) e.preventDefault(); });
    window.addEventListener("drop", (e) => {
      if (!isFiles(e)) return;
      e.preventDefault();
      depth = 0;
      $("drop-hint").hidden = true;
      readFiles(e.dataTransfer.files);
    });
    window.addEventListener("paste", (e) => {
      if (e.target.closest && e.target.closest("input, textarea")) return;
      const t = e.clipboardData && e.clipboardData.getData("text");
      if (t && t.trim().startsWith("{")) { const err = loadText(t, ""); if (err) toast(err); }
    });

    document.addEventListener("keydown", (e) => {
      const typing = e.target.closest && e.target.closest("input, textarea, select");
      const mod = e.ctrlKey || e.metaKey;
      if (mod && e.key.toLowerCase() === "z" && !typing) { e.preventDefault(); undoRedo(e.shiftKey ? S.redo : S.undo, e.shiftKey ? S.undo : S.redo); return; }
      if (mod && e.key.toLowerCase() === "y" && !typing) { e.preventDefault(); undoRedo(S.redo, S.undo); return; }
      if (typing || !layout()) return;
      const t = nodeType(S.sel);
      if ((e.key === "Delete" || e.key === "Backspace") && t && t !== "layout") { e.preventDefault(); removeNode(S.sel); }
      else if (mod && e.key.toLowerCase() === "d" && t && t !== "layout" && t !== "footer") { e.preventDefault(); duplicate(S.sel); }
      else if (e.altKey && (e.key === "ArrowUp" || e.key === "ArrowDown") && t && t !== "layout" && t !== "footer") { e.preventDefault(); moveBy(S.sel, e.key === "ArrowUp" ? -1 : 1); }
      else if (e.key === "Escape") select(nodeOf(S.sel.slice(0, -2)));
    });
  }

  restore();
  setup();
  renderAll();
})();
