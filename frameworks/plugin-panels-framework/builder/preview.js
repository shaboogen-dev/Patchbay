/*
 * Plug-in Panels layout builder: the preview. It builds the same tree of boxes the framework builds
 * (Panels.java: Builder.build, columns, add, widget, knob, cell, ...) with the same sizes and rules, in
 * layout units (1 CSS px = 1 unit; the page scales it to Bitwig's UI scale). Sizes that come from
 * Bitwig's theme are in theme.js, each marked with its source.
 *
 * Bitwig's boxes (dWb) used by the framework: a vertical (qFI) or horizontal (ELS) list with a spacing
 * (TMF; 0 when not set [measured: VintageVerb's 190 units in Bitwig's log need it]) that places every
 * child by the box's own cross alignment (UNj; start when not set). Children keep their own size.
 * Every element carries data-path, the JSON path of the node it shows, for selecting it.
 */
(function (root) {
  "use strict";
  const PPB = root.PPB, TH = root.PPBTheme;
  const { isObj, isNum, kindOf } = PPB;

  // [framework] Panels.java constants, as TH.framework holds them (a theme sheet's "framework" replaces them):
  // padding, layout_gap, row_gap 4, column_gap 2, page_gap 3 (page.TMF), title_gap 8, tab_gap 2,
  // line_gap 1 (box.TMF(1) in knob, cell, dropdownBox, value), background_padding 2, icon_size 17, divider_width 1,
  // needle 64 x 40, bar 13 x 56, level 4 x 56 (two, 1 apart), envelope 120 x 50.
  const F = () => TH.framework;
  const ENV_MARGIN = 2, ENV_DOT = 2, ENV_LINE = 1.5; // EnvelopeDisplay: MARGIN, DOT, line width 1.5 units

  // ---------- theme lookups ----------

  /** A font spec ("Bold", "Bold 16") as the framework reads it: the last word is a size when it is a number. */
  function font(spec, fallback) {
    let name = String(spec || "").trim();
    if (!name) name = fallback || "Default";
    let size = 0;
    const sp = name.lastIndexOf(" ");
    if (sp > 0 && /^\d+(\.\d+)?$/.test(name.substring(sp + 1))) {
      size = parseFloat(name.substring(sp + 1));
      name = name.substring(0, sp).trim();
    }
    const f = TH.fonts[name.toLowerCase()];
    const base = f || TH.fonts["default"];
    const px = size > 0 && size <= 200 ? size : base.size;
    // an imported font's measured line height, scaled for another size ("Bold 10"); else the measured rule
    const height = isNum(base.height) && base.size > 0 ? (base.height * px) / base.size : lineHeight(px);
    return {
      known: !!f,
      src: f ? f.src : "unknown",
      size: px,
      height,
      css: `font-family:${base.family || (base.mono ? TH.monoFamily : TH.family)};font-size:${px}px;font-weight:${base.weight};font-style:${base.style || "normal"};` +
        (base.upper ? "text-transform:uppercase;" : ""),
    };
  }

  /** A label's height for a font size [measured: 20 px for 11.2, 16 px for 8 and 9 at 150%]. */
  function lineHeight(size) {
    return Math.max(16 / 1.5, Math.round(size * 1.8) / 1.5);
  }

  /** A colour name or #rrggbb(aa) as CSS; null when the builder doesn't know it. */
  function colour(name) {
    if (name === undefined || name === null || String(name).trim() === "") return null;
    const s = String(name).trim();
    if (/^#([0-9a-f]{6}|[0-9a-f]{8})$/i.test(s)) return s;
    return TH.colours[s.toLowerCase()] || null;
  }

  // ---------- DOM helpers ----------

  function box(vertical, spacing, align, path) {
    const e = document.createElement("div");
    e.className = vertical ? "pb pb-v" : "pb pb-h";
    e.style.gap = (spacing || 0) + "px";
    e.style.alignItems = align === undefined || align < 0 ? "flex-start" : align === 0.5 ? "center" : align >= 1 ? "flex-end" : "flex-start";
    if (path) e.dataset.path = JSON.stringify(path);
    return e;
  }

  function el(cls, path, style) {
    const e = document.createElement("div");
    e.className = cls;
    if (path) e.dataset.path = JSON.stringify(path);
    if (style) e.setAttribute("style", style);
    return e;
  }

  /** A text label: always its whole text (style(): label.a_(OpO.cfM())), one line of its font's height. */
  function label(text, f, colourCss) {
    const e = el("pv-text", null, f.css + `height:${f.height}px;line-height:${f.height}px;color:${colourCss};`);
    e.textContent = text;
    return e;
  }

  /** fixLine(): a line of a fixed width and/or height with the text centred in it; the label itself when neither. */
  function fixLine(node, width, height) {
    if (!(width > 0) && !(height > 0)) return node;
    const line = el("pv-fix");
    if (width > 0) line.style.width = width + "px";
    if (height > 0) line.style.height = height + "px";
    line.append(node);
    return line;
  }

  const svgNS = "http://www.w3.org/2000/svg";
  function svg(w, h, inner) {
    const s = document.createElementNS(svgNS, "svg");
    s.setAttribute("width", w);
    s.setAttribute("height", h);
    s.setAttribute("viewBox", `0 0 ${w} ${h}`);
    s.innerHTML = inner;
    return s;
  }

  let gradientId = 0;
  /**
   * Bitwig's plug-in knob drawn in a box: track ring, value arc from the minimum, body and pointer.
   * geo: the theme's geometry for its size (imported: inner and arc radius, arc width), else the built-in
   * proportions [measured]; style: the knob style's colours (imported), else the measured ones.
   */
  function knobSvg(w, h, value, geo, style) {
    const D = TH.defaults, K = TH.knob, S = style || {};
    const size = Math.min(w, h), cx = w / 2, cy = geo ? geo.h / 2 : h / 2;
    const ro = geo ? geo.arc : size * K.outer, ri = geo ? geo.inner : size * K.inner;
    const ring = Math.max(1, geo ? geo.arcWidth : size * K.ring), pw = Math.max(1, size * K.pointer);
    const top = S.body ? shade(S.body, S.gradient || 0) : D.knobTop, bottom = S.body ? shade(S.body, -(S.gradient || 0)) : D.knobBottom;
    const a0 = 135, sweep = 270;
    const pt = (deg, rad) => [cx + rad * Math.cos((deg * Math.PI) / 180), cy + rad * Math.sin((deg * Math.PI) / 180)];
    const arc = (from, to, rad) => {
      const [x1, y1] = pt(from, rad), [x2, y2] = pt(to, rad);
      return `M${x1.toFixed(2)} ${y1.toFixed(2)} A${rad} ${rad} 0 ${to - from > 180 ? 1 : 0} 1 ${x2.toFixed(2)} ${y2.toFixed(2)}`;
    };
    const v = isNum(value) ? Math.max(0, Math.min(1, value)) : 0;
    const end = a0 + sweep * v;
    const [px, py] = pt(end, ri * 0.92);
    const [qx, qy] = pt(end, ri * 0.35);
    const g = "kg" + gradientId++;
    // for the alignment guides: the ring's outer edge (arc_radius as the stroke's centre line) and the centre, in the box
    const marks = (s) => { s.dataset.ringTop = String(cy - ro - ring / 2); s.dataset.centre = String(cy); return s; };
    return marks(svg(w, h,
      `<defs><linearGradient id="${g}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${top}"/><stop offset="1" stop-color="${bottom}"/></linearGradient></defs>` +
      `<path d="${arc(a0, a0 + sweep, ro)}" stroke="${S.track || D.knobTrack}" stroke-width="${ring}" fill="none"/>` +
      (v > 0.002 ? `<path d="${arc(a0, end, ro)}" stroke="${S.arc || D.knobArc}" stroke-width="${ring}" fill="none"/>` : "") +
      `<circle cx="${cx}" cy="${cy}" r="${ri.toFixed(2)}" fill="url(#${g})" stroke="${S.edge || D.knobEdge}" stroke-width="0.7"/>` +
      `<line x1="${qx.toFixed(2)}" y1="${qy.toFixed(2)}" x2="${px.toFixed(2)}" y2="${py.toFixed(2)}" stroke="${S.pointer || D.pointer}" stroke-width="${pw.toFixed(2)}" stroke-linecap="round"/>`));
  }

  /** A colour lighter (amount > 0) or darker by up to `amount` (a knob style's gradient_amount), as #rrggbb. */
  function shade(hex, amount) {
    const n = parseInt(hex.slice(1, 7), 16);
    const f = (c) => Math.round(amount >= 0 ? c + (255 - c) * amount * 0.5 : c * (1 + amount * 0.5));
    const r = f((n >> 16) & 255), g = f((n >> 8) & 255), b = f(n & 255);
    return "#" + [r, g, b].map((c) => Math.max(0, Math.min(255, c)).toString(16).padStart(2, "0")).join("");
  }

  /** A knob's geometry: its theme size's, or for an exact size small's scaled to it (KnobGeometry). */
  function knobGeometry(sizeName, px) {
    const g = TH.knobGeometry || {};
    if (px > 0) {
      const s = g.small;
      if (!s || !(s.w > 0)) return null;
      const k = px / s.w;
      return { inner: s.inner * k, arc: s.arc * k, arcWidth: Math.max(s.arcWidth, Math.round(s.arcWidth * k)), w: px, h: px };
    }
    return g[sizeName] || null;
  }

  /**
   * A knob style's colours. The default style's arc and pointer are the sheet's; its body and track are
   * the measured ones: the sheet's resolved fill (#232323) and track (#353535) aren't what Bitwig draws for
   * a plug-in knob on a device body [measured: body #838383 to #535353, track #5e5e5e], so Bitwig resolves
   * them relative to the background.
   */
  /**
   * knob_arc: the mode colour on the value arc only (Bitwig's knob takes the arc colour in dNv.JqA(): the
   * knob's own colour, or the style's value_color; the framework sets both). The track, stroke ring, body and
   * pointer stay the knob style's; an unmapped mode keeps the style's value_color. [to confirm in Bitwig]
   */
  function arcFor(B, style) {
    return B.mode.on("knob_arc") ? Object.assign({}, style || {}, { arc: B.mode.tint("knob_arc", (style && style.arc) || TH.defaults.knobArc) }) : style;
  }

  /** ModeColours.mix: `b` mixed into `a` by t, as #rrggbb. */
  function mix(a, b, t) {
    const rgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
    const x = rgb(a), y = rgb(b);
    return "#" + x.map((v, i) => Math.round(v + (y[i] - v) * t).toString(16).padStart(2, "0")).join("");
  }

  function knobStyle(name) {
    const st = TH.knobStyles || {};
    const key = String(name || "default").trim().toLowerCase();
    const s = st[key] || st["default"] || null;
    if (!s || (key !== "default" && st[key])) return s;
    // a BTE theme that names Knob Body, Knob Stroke or Knob Value Background sets them [approx: drawn as given]
    const t = s.themed || {};
    return { arc: s.arc, pointer: s.pointer, body: t.body ? s.body : null, edge: t.edge ? s.edge : null, track: t.track ? s.track : null, gradient: s.gradient };
  }

  function needleSvg(w, h) {
    const cx = w / 2, cy = h * 0.95, r = Math.min(w * 0.45, h * 0.85);
    const a = (deg, rr) => [cx + rr * Math.cos((deg * Math.PI) / 180), cy + rr * Math.sin((deg * Math.PI) / 180)];
    let ticks = "";
    for (let d = -150; d <= -30; d += 15) {
      const [tx, ty] = a(d, r), [ix, iy] = a(d, r - 4);
      ticks += `<line x1="${ix.toFixed(1)}" y1="${iy.toFixed(1)}" x2="${tx.toFixed(1)}" y2="${ty.toFixed(1)}" stroke="${d > -60 ? "#d06a4a" : "#8a8a8a"}" stroke-width="1"/>`;
    }
    const [nx, ny] = a(-112, r);
    return svg(w, h, `<rect x="0.5" y="0.5" width="${w - 1}" height="${h - 1}" rx="2" fill="#2a2a2a" stroke="#1d1d1d"/>` + ticks +
      `<line x1="${cx}" y1="${cy}" x2="${nx.toFixed(1)}" y2="${ny.toFixed(1)}" stroke="#e8e8e8" stroke-width="1.2"/>`);
  }

  // ---------- the builder ----------

  /**
   * ctx: {layout, sheet, page, selected (path key), problems (Map key -> level)}.
   * Returns {node, body, page, pages, width, layoutHeight, toolbar}.
   */
  function render(ctx) {
    const L = ctx.layout;
    const params = (ctx.sheet && ctx.sheet.parameters) || [];
    const B = {
      L, params, resolved: PPB.resolveLayout(L, params),
      texts: {
        title: [L.title_font, L.title_color], header: [L.header_font, L.header_color],
        label: [L.label_font, L.label_color], value: [L.value_font, L.value_color],
      },
      sizes: { header: L.header_height, label: L.label_height, value: L.value_height },
      cellWidth: 0, aligner: null,
      // mode colours (Panels.tinted / ModeColours): the current mode's colour on the parts the layout names
      mode: null,
    };
    // the sheet's plug-in when the layout matches it, else the layout's own name
    const sheetFits = ctx.sheet && ctx.sheet.plugin && isObj(L.match) && PPB.matchesPlugin(L.match, ctx.sheet.plugin);
    const plugin = sheetFits ? ctx.sheet.plugin : { name: (L.match && L.match.name) || "Plug-in", format: (L.match && L.match.format) || "" };
    const usePages = Array.isArray(L.pages);
    const pages = usePages ? L.pages : [{ columns: L.columns, footer: L.footer }];
    const pageIndex = Math.max(0, Math.min(ctx.page || 0, pages.length - 1));
    const base = usePages ? ["pages", pageIndex] : [];
    const D = TH.defaults;

    // Bitwig's device: left strip, then the plug-in body (approximate chrome, not part of the layout)
    const device = el("pv-device");
    const strip = el("pv-strip", null, `width:${TH.sizes.strip}px;background:${D.strip}`);
    strip.append(el("pv-power"), Object.assign(el("pv-vname"), { textContent: plugin.name.toUpperCase() }));
    device.append(strip, el("pv-stripsep", null, `background:${D.frame}`));

    const MC = PPB.currentMode(L, params, ctx.mode);
    // ModeColours: the current mode's colour for a target (a mode not in the map, or a target its colours leave
    // out: none), mixed into the part's own colour by the target's mix (background_tint 0.12, toolbar 0.25, else 1)
    const modeFor = (t) => (MC ? colour(MC.colourFor(t)) : null);
    B.mode = {
      on: (t) => !!modeFor(t),
      tint: (t, own) => { const c = modeFor(t); if (!c) return own; const a = MC.mixFor(t); return own && a < 1 ? mix(own, c, a) : c; },
    };
    // background_tint: the layout's background with 12% of the mode colour (Panels.BACKGROUND_TINT)
    const bodyBg = colour(L.background) ? B.mode.tint("background_tint", colour(L.background)) : D.body;
    const body = el("pv-body", [], `background:${bodyBg}`);
    const width = isNum(L.width) && Math.round(L.width) > 0 ? Math.round(L.width) : fixedWidth(L, pages);
    // Panels: the body is the layout's width plus the right-hand bar (size + gap)
    if (width > 0) body.style.width = width + PPB.toolbarParts(L).barWidth + "px";
    device.append(body);
    // Bitwig's toolbar (PanelHooks): Parameters and Programs tabs, sidechain, outputs, search, then the
    // container button (the arrow), which no option removes. Programs, sidechain and outputs only show for a
    // plug-in that has them: drawn faint, as the builder can't know.
    const parts = PPB.toolbarParts(L);
    const toolbar = parts.row && !parts.right; // the row on top
    if (toolbar) {
      const tb = el("pv-toolbar", null, `height:${TH.sizes.toolbar}px`);
      const piece = (cls, title) => { const e = el(cls); e.title = title; tb.append(e); };
      if (parts.parameters) piece("pv-tbicon", parts.pages ? "Parameters tab" : "Parameters tab: kept while sidechain or outputs stays");
      if (parts.programs) piece("pv-tbtab maybe", "Programs tab (only for a plug-in with programs)");
      if (parts.sidechain) piece("pv-tbbtn maybe", "Sidechain (only for a plug-in with a sidechain input)");
      if (parts.outputs) piece("pv-tbbtn maybe", "Outputs (only for a plug-in with several outputs)");
      if (parts.search) {
        const s = el("pv-search", null, `background:${D.search}`);
        s.innerHTML = '<span class="pv-mag">⌕</span><span class="grow"></span><span class="pv-x">×</span>';
        tb.append(s);
      } else tb.append(el("grow")); // a filler keeps the arrow at the right
      tb.append(containerButton());
      body.append(tb);
    }

    // build(): padding, then the page: title line, the pages (only the shown one takes space), the probe
    const pad = padding(L);
    // ModeColours.Striped: the stripe along the top of the padded page, the top padding grown by its height
    const st = PPB.hasStripe(L) ? (isObj(L.stripe) ? L.stripe : {}) : null;
    const stripeH = st ? (isNum(st.height) ? Math.round(st.height) : PPB.STRIPE_HEIGHT) : 0;
    const padded = el("pv-padded", null, `padding:${pad[2] + stripeH}px ${pad[1]}px ${pad[3]}px ${pad[0]}px`);
    if (st) {
      const own = st.color ? colour(st.color) : colour("Knob Value Color") || D.knobArc;
      const s = el("pv-stripe", null, `height:${stripeH}px;background:${B.mode.tint("stripe", own) || "transparent"}`);
      padded.append(s);
    }
    const page = box(true, F().page_gap);
    let top = null;
    if (L.title !== false || pages.length > 1 || !parts.row) {
      top = box(false, F().title_gap, 0.5);
      top.classList.add("pv-titleline");
      if (L.title !== false) {
        const f = font(B.texts.title[0], "Default");
        top.append(label(plugin.name, f, colour(B.texts.title[1]) || D.title));
      }
      if (pages.length > 1) top.append(chooser(L, pages, pageIndex));
      if (!parts.row) { // Panels.build(): a filler, then the container button at the line's right end
        top.style.alignSelf = "stretch";
        top.append(el("grow pv-titlefill"), containerButton());
      }
      page.append(top);
    }
    const pg = isObj(pages[pageIndex]) ? pages[pageIndex] : {};
    const view = pageView(B, pg, base);
    page.append(view);
    page.append(el("pv-probe", null, "width:1px;height:1px")); // HeightFit's probe: 1 x 1, under the pages
    padded.append(page);
    if (parts.right) { // the panel and, beside it, the bar down the right edge
      const main = el("pv-main", null, `gap:${parts.gap}px`); // the panel, (a divider,) the bar, gap apart
      const view = ctx.view && ctx.view !== "layout" ? ctx.view : null; // another mode: its page in place of the layout
      // the pages' area beside the bar: the layout, or another mode's page over it (the device keeps one width)
      const area = el("pv-pagesarea");
      area.append(padded);
      if (view) { padded.style.visibility = "hidden"; area.append(otherView(view, params)); }
      main.append(area);
      if (parts.divider) main.append(el("pv-divider", null, `width:1px;background:${B.mode.tint("divider", TH.defaults.divider)}`));
      main.append(rightBar(parts, B, ctx.view || "layout"));
      body.append(main);
    } else body.append(padded);
    mark(device, ctx);
    const viewing = parts.right && ctx.view && ctx.view !== "layout" ? ctx.view : null;
    return { node: device, body, padded, page: pageIndex, pages: pages.length, toolbar, titleLine: top, view, viewing };
  }

  /**
   * PanelHooks.bar: the toolbar's buttons in a bar down the right edge, `size` wide, in the layout's `order`
   * (spacers take the spare height; without one, `align` places the stack), `gap` apart, with `gap` above the
   * first and under the last, on the bar's own `background` (25% of the mode colour mixed in with the mode
   * target "toolbar"). The Parameters tab kept by its rule goes first; the container button is always shown,
   * at the end when the order leaves it out. Programs, sidechain and outputs are drawn faint: Bitwig shows
   * them only for a plug-in that has them.
   */
  function rightBar(parts, B, view) {
    const bg = colour(parts.background);
    const fill = bg ? B.mode.tint("toolbar", bg) : null; // an unmapped mode: the bar's own background
    const bar = el("pv-rightbar", null, `width:${parts.size}px;gap:${parts.gap}px;padding:${parts.gap}px 0;` + (fill ? `background:${fill};` : ""));
    const btn = (cls, title, text, v) => {
      const b = el("pv-barbtn " + cls + (v && v === view ? " lit" : ""), null, `width:${parts.size}px;height:${parts.size}px`);
      b.title = title + (parts.style ? ` (style ${parts.style})` : "") + (v ? " — click to preview" : "");
      if (v) b.dataset.view = v;
      if (text) b.textContent = text;
      bar.append(b);
    };
    const order = parts.order.slice();
    if (!order.includes("container")) order.push("container");
    if (parts.forcedLayout) order.unshift("layout"); // the way back to the layout: first
    const spacers = order.includes("spacer");
    if (!spacers && parts.align !== "top") bar.append(el("grow")); // center, bottom: room above
    for (const e of order) {
      if (e === "spacer") bar.append(el("grow"));
      else if (e === "layout") btn("tab", parts.modes ? "Layout mode: the layout" : "Bitwig's Parameters tab", "", "layout");
      else if (e === "list" && parts.showList) btn("list", "List mode: Bitwig's full parameter list, with its search field", "≡", "list");
      else if (e === "programs" && parts.showPrograms) btn("maybe", "Programs tab (only for a plug-in with programs)", "", "programs");
      else if (e === "sidechain" && parts.showSidechain) btn("maybe", "Sidechain (only for a plug-in with a sidechain input)", "", "sidechain");
      else if (e === "outputs" && parts.showOutputs) btn("maybe", "Outputs (only for a plug-in with several outputs)", "", "outputs");
      else if (e === "container") btn("arrow", "Bitwig's container button: the plug-in's FX container (always shown)", "→");
    }
    if (!spacers && parts.align !== "bottom") bar.append(el("grow")); // top, center: room below
    return bar;
  }

  /**
   * Another page in place of the layout (the bar's modes): List (Bitwig's search field over its full
   * parameter list), Programs, sidechain or outputs (Bitwig's own pages, sketched). Not Bitwig's drawing.
   */
  function otherView(view, params) {
    // like Bitwig's list, it has no width of its own: it fills the pages' area beside the bar (the device keeps its width)
    const v = el("pv-otherview");
    if (view === "list") {
      const s = el("pv-search", null, `background:${TH.defaults.search};margin:4px`);
      s.innerHTML = '<span class="pv-mag">⌕</span><span class="grow"></span>';
      v.append(s);
      for (const p of params.slice(0, 9)) {
        const r = el("pv-slider", null, `margin:0 4px;height:${TH.sizes.row}px;` + font("", "Small").css);
        const name = el("pv-slidername"), bar = el("pv-sliderbar"), val = el("pv-slidervalue");
        name.textContent = p.name;
        bar.append(el("pv-sliderfill", null, `width:${((isNum(p.value) ? p.value : 0) * 100).toFixed(1)}%`));
        val.textContent = p.text;
        r.append(name, bar, val);
        v.append(r);
      }
      if (!params.length) v.append(Object.assign(el("pv-empty"), { textContent: "Bitwig's parameter list (load the sheet to see it)" }));
    } else v.append(Object.assign(el("pv-empty"), { textContent: { programs: "Bitwig's Programs page", sidechain: "Bitwig's sidechain page", outputs: "Bitwig's outputs page" }[view] || view }));
    return v;
  }

  /** Bitwig's FX container button (the arrow) [approx size: Bitwig's row height]. */
  function containerButton() {
    const b = el("pv-arrow", null, `height:${TH.sizes.row}px;min-width:${TH.sizes.row * 0.8}px`);
    b.textContent = "→";
    b.title = "Bitwig's container button: the plug-in's FX container (always shown)";
    return b;
  }

  /** A dropdown style's look [approx: Bitwig draws each style from its theme; the builder suggests it]. */
  function dropdownStyleClass(style) {
    const s = String(style || "").trim().toLowerCase();
    if (!s || s === "default") return "";
    if (s.startsWith("borderless")) return " dd-borderless" + (s.includes("accent") ? " dd-accent" : "");
    if (s === "plain" || s === "flat") return " dd-flat";
    if (s === "underline") return " dd-underline";
    if (s === "stepper") return " dd-stepper";
    return "";
  }

  function padding(L) {
    if (Array.isArray(L.padding) && L.padding.length === 4) return L.padding.map((n) => Math.round(n));
    if (isNum(L.padding)) return [0, 0, 0, 0].map(() => Math.round(L.padding));
    return [F().padding, F().padding, F().padding, F().padding];
  }

  /** fixedWidth(): with every column of every page fixed, columns + gaps + left and right padding; else 0. */
  function fixedWidth(L, pages) {
    const gap = isNum(L.gap) ? Math.round(L.gap) : F().layout_gap;
    let widest = 0;
    for (const p of pages) {
      const cols = (isObj(p) && Array.isArray(p.columns)) ? p.columns : [];
      let w = 0;
      for (const c of cols) {
        if (!isObj(c) || !(isNum(c.width) && Math.round(c.width) > 0)) return 0;
        w += Math.round(c.width);
      }
      w += gap * Math.max(0, cols.length - 1);
      widest = Math.max(widest, w);
    }
    const pad = padding(L);
    return widest + pad[0] + pad[1];
  }

  /** chooser(): Bitwig's toggle buttons, 2 apart, Bitwig's row height; or its dropdown. */
  function chooser(L, pages, shown) {
    if (String(L.page_chooser || "tabs").trim().toLowerCase() === "dropdown") {
      const d = dropdownFace(String((pages[shown] && pages[shown].name) || ""), 0, 0);
      d.dataset.path = JSON.stringify(["pages", shown]);
      return d;
    }
    const tabs = box(false, F().tab_gap, -1);
    pages.forEach((p, i) => {
      const b = el("pv-button" + (i === shown ? " on" : ""), ["pages", i], `height:${TH.sizes.row}px;` + font("", "Default").css);
      b.textContent = String((p && p.name) || "");
      b.dataset.page = String(i);
      tabs.append(b);
    });
    return tabs;
  }

  /** pageView(): the columns; with a footer, a vertical box of the columns, the footer gap and the footer row. */
  function pageView(B, pg, base) {
    const columns = columnsView(B, pg, base);
    const footer = Array.isArray(pg.footer) ? pg.footer : [];
    if (!footer.length) return columns;
    const view = box(true, 0);
    view.append(columns);
    if (isNum(B.L.footer_gap) && B.L.footer_gap > 0) view.append(el("pv-spacer pv-footergap", null, `width:1px;height:${Math.round(B.L.footer_gap)}px`));
    const fp = base.concat("footer");
    const row = box(false, isNum(B.L.gap) ? Math.round(B.L.gap) : F().layout_gap, 0.5, fp);
    row.classList.add("pv-footerrow");
    const outer = B.cellWidth;
    B.cellWidth = 0;
    footer.forEach((it, i) => add(B, row, it, true, fp.concat(i)));
    B.cellWidth = outer;
    view.append(row);
    return view;
  }

  /** columns(): a row of columns, gaps as spacers, a divider in the middle of its gap; every column the full height. */
  function columnsView(B, pg, base) {
    const L = B.L, D = TH.defaults;
    const cp = base.concat("columns");
    const row = box(false, 0, -1, cp);
    row.classList.add("pv-columnsrow");
    row.style.alignItems = "stretch"; // TtY(true)
    const cols = Array.isArray(pg.columns) ? pg.columns : [];
    const gap = isNum(L.gap) ? Math.round(L.gap) : F().layout_gap;
    const headers = cols.some((c) => isObj(c) && c.header !== undefined && String(c.header) !== "");
    cols.forEach((c, i) => {
      if (!isObj(c)) return;
      if (i > 0) {
        if (c.divider === true || L.dividers === true) {
          const before = Math.max(0, Math.floor((gap - F().divider_width) / 2));
          row.append(el("pv-spacer", null, `width:${before}px`));
          row.append(el("pv-divider", null, `width:${F().divider_width}px;background:${B.mode.tint("divider", D.divider)}`));
          row.append(el("pv-spacer", null, `width:${Math.max(0, gap - F().divider_width - before)}px`));
        } else row.append(el("pv-spacer", null, `width:${gap}px`));
      }
      const path = cp.concat(i);
      const outerW = isNum(c.width) && Math.round(c.width) > 0 ? Math.round(c.width) : 0;
      const cpad = sides(c.padding);
      // Panels.columns(): the padding is inside the width; header, items and cells get what's left
      const width = outerW > 0 ? Math.max(1, outerW - (cpad ? cpad[0] + cpad[1] : 0)) : 0;
      const align = alignValue(c.align, ["left", "center", "right"]);
      const items = box(true, isNum(c.gap) ? Math.round(c.gap) : F().column_gap, align >= 0 ? align : width > 0 ? 0.5 : -1, path.concat("items"));
      if (width > 0) items.style.width = width + "px";
      // Panels.spacers(): a column with spacers gives them its spare height (the row of columns stretches every
      // column to the tallest, TtY(true)); its valign isn't used
      const spacers = (Array.isArray(c.items) ? c.items : []).some((it) => kindOf(it) === "spacer");
      let col = items;
      if (headers) {
        col = box(true, 0);
        const f = font(c.header_font || L.header_font, "Default");
        const h = label(c.header === undefined || String(c.header) === "" ? " " : String(c.header), f, B.mode.tint("header", colour(c.header_color || L.header_color) || D.text));
        col.append(fixLine(h, width, isNum(B.sizes.header) ? Math.round(B.sizes.header) : 0));
        col.append(items);
        if (spacers) items.style.flex = "1 0 auto"; // col.cfM(items, 1f): the items take the spare height
      }
      col.dataset.path = JSON.stringify(path);
      col.classList.add("pv-col");
      const outer = B.cellWidth;
      B.cellWidth = width;
      (Array.isArray(c.items) ? c.items : []).forEach((it, j) => add(B, items, it, false, path.concat("items", j)));
      B.cellWidth = outer;
      let full = col;
      if (L.fill_height === true && !spacers) { // Panels.columns(): the column as tall as its row, the spare height placed by valign
        full = el("pv-tall", null, `justify-content:${({ center: "center", centre: "center", bottom: "flex-end" })[String(c.valign || "").trim().toLowerCase()] || "flex-start"}`);
        full.append(col);
      }
      const pcol = padded(full, cpad);
      if (pcol !== col) { pcol.dataset.path = col.dataset.path; pcol.classList.add("pv-col"); col.classList.remove("pv-col"); delete col.dataset.path; }
      if (full !== col && pcol === full) { full.dataset.path = col.dataset.path || full.dataset.path; }
      row.append(background(pcol, c.background));
    });
    return row;
  }

  /** "left"/"center"/"right" (or top/center/bottom, "centre") as 0, 0.5, 1; -1 when absent. */
  function alignValue(v, names) {
    if (v === undefined || v === null) return -1;
    const s = String(v).trim().toLowerCase();
    if (s === names[0]) return 0;
    if (s === names[1] || s === "centre") return 0.5;
    if (s === names[2]) return 1;
    return -1;
  }

  /** background(): a padding of 2 around the widget, filled with the colour; a colour the builder doesn't know is drawn hatched. */
  function background(node, name) {
    if (name === undefined || name === null || String(name).trim() === "") return node;
    const c = colour(name);
    const wrap = el("pv-bg", null, `padding:${F().background_padding}px;` + (c ? `background:${c}` : ""));
    if (!c) wrap.classList.add("pv-unknown-colour");
    wrap.dataset.path = node.dataset.path || "";
    if (!wrap.dataset.path) delete wrap.dataset.path;
    wrap.append(node);
    return wrap;
  }

  /** add(): an item in a column or a row; its own alignment across the box gives it a box of its own. */
  function add(B, parent, item, inRow, path) {
    if (kindOf(item) === "spacer") { // Panels.add(): a Filler that takes the parent's spare length (several share it; none: 0)
      const s = el("pv-spaceritem " + (inRow ? "pv-spacer-h" : "pv-spacer-v"), path);
      parent.append(s);
      return;
    }
    const w = widget(B, item, path);
    if (!w) return;
    w.dataset.path = JSON.stringify(path);
    let placed = background(w, isObj(item) ? item.background : undefined);
    const across = isObj(item) ? (inRow ? alignValue(item.valign, ["top", "center", "bottom"]) : alignValue(item.align, ["left", "center", "right"])) : -1;
    if (across >= 0 && kindOf(item) !== "row") placed.style.alignSelf = across === 0 ? "flex-start" : across === 0.5 ? "center" : "flex-end";
    parent.append(placed);
  }

  function widget(B, item, path) {
    if (!isObj(item)) return el("pv-bad");
    const kind = kindOf(item);
    if (!kind) return el("pv-bad");
    if (kind === "row") return rowView(B, item, path);
    if (kind === "meter") return meter(item);
    if (kind === "envelope") return envelope(B, item, path);
    if (kind === "text") {
      const f = font(item.font || B.L.label_font, "Default");
      return label(String(item.text), f, colour(item.color || B.L.label_color) || TH.defaults.text);
    }
    const p = B.resolved.get(path.join("/")) || null;
    if (!p && PPB.isAuto(item[kind])) return null; // an automatic item with nothing left: left out
    const node = (() => {
      switch (kind) {
        case "knob": return isNum(item.size) ? cell(B, item, p) : knob(B, item, p);
        case "dropdown": return dropdown(B, item, p);
        case "toggle": return toggle(B, item, p);
        case "value": return value(B, item, p);
        case "slider": return slider(item, p);
        default: return el("pv-bad");
      }
    })();
    if (!p && B.params.length) {
      node.classList.add("pv-missing");
      node.title = `No parameter in the sheet matches '${item[kind]}'`;
    }
    return node;
  }

  /** A row: horizontal, its gap (4), its items' valign; a fixed width shared in equal cells. */
  function rowView(B, item, path) {
    const gap = isNum(item.gap) ? Math.round(item.gap) : F().row_gap;
    const line = box(false, gap, alignValue(item.valign, ["top", "center", "bottom"]));
    line.classList.add("pv-row");
    const kids = Array.isArray(item.row) ? item.row : [];
    const outerCell = B.cellWidth, outerAligner = B.aligner;
    const rpad = sides(item.padding);
    // Panels.widget(row): its width (or its cell's), less its left and right padding, shared in equal cells
    const outerWidth = isNum(item.width) && Math.round(item.width) > 0 ? Math.round(item.width) : B.cellWidth;
    const rowWidth = outerWidth > 0 ? Math.max(1, outerWidth - (rpad ? rpad[0] + rpad[1] : 0)) : 0;
    const n = kids.filter((c) => kindOf(c) !== "spacer").length; // the cells: spacers take only what is left
    if (rowWidth > 0) line.style.width = rowWidth + "px";
    B.cellWidth = rowWidth > 0 && n > 0 ? Math.max(1, Math.floor((rowWidth - gap * (n - 1)) / n)) : 0;
    const knobs = kids.some((c) => kindOf(c) === "knob");
    const fixedKnobs = kids.every((c) => kindOf(c) !== "knob" || isNum(c.size));
    B.aligner = knobs && !fixedKnobs && item.align_knobs !== false ? [] : null;
    kids.forEach((c, i) => add(B, line, c, true, path.concat("row", i)));
    if (B.aligner && B.aligner.length) align(B.aligner);
    B.cellWidth = outerCell;
    B.aligner = outerAligner;
    if (!kids.length) line.append(Object.assign(el("pv-empty"), { textContent: "empty row" }));
    const p = padded(line, rpad);
    if (p !== line) p.classList.add("pv-row");
    return p;
  }

  /** A column's or row's padding as [left, right, top, bottom]; null when none (Layouts.padding). */
  function sides(v) {
    if (isNum(v)) { const n = Math.round(v); return [n, n, n, n]; }
    if (Array.isArray(v) && v.length === 4 && v.every(isNum)) return v.map((n) => Math.round(n));
    return null;
  }

  /** Panels.padded(): a padding box around the widget (left, right, top, bottom); the widget itself without. */
  function padded(node, pad) {
    if (!pad) return node;
    const b = el("pv-padbox", null, `padding:${pad[2]}px ${pad[1]}px ${pad[3]}px ${pad[0]}px`);
    b.append(node);
    return b;
  }

  /** RowAligner: label lines bottom-aligned on the tallest, knob centres on the tallest knob's line. */
  function align(knobs) {
    const labelLine = Math.max(...knobs.map((k) => k.labelH)), knobLine = Math.max(...knobs.map((k) => k.knobH));
    for (const k of knobs) {
      const middle = Math.floor((knobLine - k.knobH) / 2);
      k.top.style.height = labelLine - k.labelH + "px";
      k.middle.style.height = middle + "px";
      k.bottom.style.height = knobLine - k.knobH - middle + "px";
    }
  }

  function labelFor(B, item, p, kind) {
    const text = typeof item.label === "string" || isNum(item.label) ? String(item.label) : p ? p.name : String(item[kind] || "").split("|")[0].replace(/^id:/i, "");
    if (isObj(item.label) && item.label.icon) {
      const s = isNum(item.label.size) && item.label.size > 0 ? Math.round(item.label.size) : F().icon_size;
      const icon = el("pv-icon", null, `width:${s}px;height:${s}px`);
      icon.title = item.label.icon + (item.label.text ? " — " + item.label.text : "");
      return { node: icon, height: s };
    }
    const f = font(item.font || B.L.label_font, "Default");
    return { node: label(isObj(item.label) ? String(item.label.text || (p ? p.name : "")) : text, f, colour(item.color || B.L.label_color) || TH.defaults.text), height: f.height };
  }

  function valueLine(B, item, p) {
    const f = font(item.value_font || B.L.value_font, "Default");
    const n = label(valueText(item, p), f, colour(item.value_color || B.L.value_color) || TH.defaults.text);
    return { node: n, height: f.height };
  }

  /** knob(): label, Bitwig's plug-in knob at its theme size (or the item's width/height), the value; centred. */
  function knob(B, item, p) {
    const sz = TH.sizes.knob[String(item.size || "small").trim().toLowerCase()] || TH.sizes.knob.small;
    const w = isNum(item.width) && item.width > 0 ? Math.round(item.width) : sz.w;
    const h = isNum(item.height) && item.height > 0 ? Math.round(item.height) : sz.h;
    const k = el("pv-knob", null, `width:${w}px;height:${h}px`);
    k.append(knobSvg(w, h, p ? p.value : 0.5, knobGeometry(String(item.size || "small").trim().toLowerCase(), 0), arcFor(B, knobStyle(item.knob_style))));
    const lab = item.label === false ? null : labelFor(B, item, p, "knob");
    const val = valueLine(B, item, p);
    if (!B.aligner) {
      const b = box(true, F().line_gap, 0.5);
      if (lab) b.append(lab.node);
      b.append(k, val.node);
      return b;
    }
    const b = box(true, 0, 0.5);
    const top = el("pv-spacer"), middle = el("pv-spacer", null, "height:1px"), bottom = el("pv-spacer");
    b.append(top);
    if (lab) b.append(lab.node);
    b.append(middle, k, bottom, val.node);
    B.aligner.push({ labelH: lab ? lab.height : 0, knobH: h, top, middle, bottom });
    return b;
  }

  /** cell(): a knob cell of fixed size: label line, the knob at exactly `size`, value line, each the cell's width. */
  function cell(B, item, p) {
    const px = Math.round(item.size);
    const width = isNum(item.width) && item.width > 0 ? Math.round(item.width) : B.cellWidth;
    const b = box(true, F().line_gap, 0.5);
    if (width > 0) b.style.width = width + "px";
    if (item.label !== false) {
      const lab = labelFor(B, item, p, "knob");
      b.append(lab.node.classList.contains("pv-icon") ? lab.node : fixLine(lab.node, width, isNum(B.sizes.label) ? Math.round(B.sizes.label) : 0));
    }
    const k = el("pv-knob", null, `width:${px}px;height:${px}px`);
    k.append(knobSvg(px, px, p ? p.value : 0.5, knobGeometry("", px), arcFor(B, knobStyle(item.knob_style))));
    b.append(k);
    b.append(fixLine(valueLine(B, item, p).node, width, isNum(B.sizes.value) ? Math.round(B.sizes.value) : 0));
    return b;
  }

  function dropdownFace(text, w, h, style) {
    const D = TH.defaults;
    const d = el("pv-dropdown" + dropdownStyleClass(style), null, `height:${h || TH.sizes.dropdown}px;` + (w ? `width:${w}px;` : `min-width:${TH.sizes.dropdownWidth}px;`) +
      `background:linear-gradient(${D.dropTop},${D.dropBottom});border-color:${D.dropBorder};color:${D.dropText};` + font("", "Default").css);
    const t = el("pv-ddtext");
    t.textContent = text;
    d.append(t, Object.assign(el("pv-caret"), { textContent: "▾" }));
    return d;
  }

  /** dropdown(): Bitwig's dropdown at its natural height (or the item's size); its label above, 1 apart. */
  function dropdown(B, item, p) {
    let shown = p ? p.text : "—";
    if (Array.isArray(item.choices) && item.choices.length) {
      const n = p && isNum(p.value) ? p.value : 0;
      let best = null, bd = Infinity;
      for (const e of item.choices) {
        if (!Array.isArray(e)) continue;
        const [name, v] = typeof e[0] === "string" ? [e[0], e[1]] : [e[1], e[0]];
        if (isNum(v) && Math.abs(v - n) < bd) { bd = Math.abs(v - n); best = name; }
      }
      if (best !== null) shown = String(best);
    } else if (p) shown = valueText({}, p);
    const menu = dropdownFace(shown, isNum(item.width) ? Math.round(item.width) : 0, isNum(item.height) ? Math.round(item.height) : 0, item.style);
    if (item.label === false) return menu;
    const b = box(true, F().line_gap);
    b.append(labelFor(B, item, p, "dropdown").node, menu);
    return b;
  }

  /** toggle(): Bitwig's text toggle button, Bitwig's row height, lit at 0.5 and above. */
  function toggle(B, item, p) {
    const lit = p && isNum(p.value) && p.value >= 0.5;
    const style = String(item.style || "");
    const D = TH.defaults;
    const b = el("pv-button" + (lit ? " on" : "") + (/borderless/i.test(style) ? " borderless" : "") + (/pill/i.test(style) ? " pill" : ""), null,
      `height:${isNum(item.height) ? Math.round(item.height) : TH.sizes.row}px;` + (isNum(item.width) ? `width:${Math.round(item.width)}px;` : "") +
      font("", "Default").css + `--on:${D.buttonOn};--off:${D.buttonOff};`);
    if (isObj(item.label) && item.label.icon) {
      const s = isNum(item.label.size) ? item.label.size : F().icon_size;
      b.append(el("pv-icon", null, `width:${s}px;height:${s}px`));
    } else b.textContent = typeof item.label === "string" ? item.label : p ? p.name : String(item.toggle).split("|")[0];
    return b;
  }

  /** value(): the label and the value text, 1 apart; the item's size on the value text. */
  function value(B, item, p) {
    const b = box(true, F().line_gap);
    if (item.label !== false) b.append(labelFor(B, item, p, "value").node);
    const v = valueLine(B, item, p).node;
    if (isNum(item.width)) v.style.width = Math.round(item.width) + "px";
    if (isNum(item.height)) v.style.height = Math.round(item.height) + "px";
    b.append(v);
    return b;
  }

  /** slider(): Bitwig's parameter row from its list [approx size]. */
  function slider(item, p) {
    const [w0, h0] = TH.sizes.slider;
    const row = el("pv-slider", null, `width:${isNum(item.width) ? Math.round(item.width) : w0}px;height:${isNum(item.height) ? Math.round(item.height) : h0}px;` + font("", "Small").css);
    const n = p && isNum(p.value) ? p.value : 0.5;
    const name = el("pv-slidername"), bar = el("pv-sliderbar"), v = el("pv-slidervalue");
    name.textContent = p ? p.name : String(item.slider);
    bar.append(el("pv-sliderfill", null, `width:${(n * 100).toFixed(1)}%`));
    v.textContent = p ? valueText({}, p) : "—";
    row.append(name, bar, v);
    return row;
  }

  /** meter(): needle 64 x 40 (or its size), bar 13 x 56 (or its size), level: two 4 x 56, 1 apart. */
  function meter(item) {
    const style = String(item.style || "needle").trim().toLowerCase();
    if (style === "bar") {
      const m = el("pv-meterbars", null, `width:${isNum(item.width) ? Math.round(item.width) : F().bar[0]}px;height:${isNum(item.height) ? Math.round(item.height) : F().bar[1]}px`);
      m.append(el("pv-meterbar", null, "height:62%"), el("pv-meterbar", null, "height:55%"));
      return m;
    }
    if (style === "level") {
      const pair = box(false, 1, -1);
      pair.append(el("pv-meterbar pv-level", null, `width:${F().level[0]}px;height:${F().level[1]}px`), el("pv-meterbar pv-level", null, `width:${F().level[0]}px;height:${F().level[1]}px`));
      return pair;
    }
    const w = isNum(item.width) ? Math.round(item.width) : F().needle[0], h = isNum(item.height) ? Math.round(item.height) : F().needle[1];
    const m = el("pv-needle", null, `width:${w}px;height:${h}px`);
    m.append(needleSvg(w, h));
    return m;
  }

  /**
   * Panels.envelope() and EnvelopeDisplay: the display at the item's size (120 x 50), its background
   * (display_color; "none" for none), the shape filled (fill_color, else the line colour at 20%) and
   * outlined (color, 1.5 units), a dot (radius 2) on each breakpoint after the first unless points is false;
   * 4 units in from the edges (margin 2 + dot 2). A reference the sheet doesn't have: the display only.
   */
  function envelope(B, item, path) {
    const D = TH.defaults;
    const w = isNum(item.width) && item.width > 0 ? Math.round(item.width) : F().envelope[0];
    const h = isNum(item.height) && item.height > 0 ? Math.round(item.height) : F().envelope[1];
    const disp = String(item.display_color || "").trim();
    const back = disp.toLowerCase() === "none" ? null : disp ? colour(disp) : D.displayBackground;
    const own = item.color ? colour(item.color) || D.text : D.displayWaveform; // an unknown colour: the text colour
    const line = B.mode.tint("envelope", own);
    const fill = item.fill_color ? colour(item.fill_color) : null;
    const node = el("pv-envelope", null, `width:${w}px;height:${h}px`);
    const segs = PPB.envelopeSegments(item);
    const values = {};
    let empty = !segs.length;
    for (const s of segs) {
      const p = B.resolved.get(path.concat("envelope", s.key).join("/"));
      if (!p) empty = true;
      else values[s.key] = isNum(p.value) ? p.value : 0;
    }
    let inner = back ? `<rect x="0" y="0" width="${w}" height="${h}" fill="${back}"/>` : "";
    const m = ENV_MARGIN + ENV_DOT, left = m, right = w - m, top = m, bottom = h - m;
    if (!empty && right - left >= 2 && bottom - top >= 2) {
      const pts = PPB.envelopePoints(item, values, right - left).map(([x, y]) => [left + x, bottom - y * (bottom - top)]);
      const d = pts.map(([x, y], i) => `${i ? "L" : "M"}${x.toFixed(2)} ${y.toFixed(2)}`).join(" ");
      const area = `${d} L${pts[pts.length - 1][0].toFixed(2)} ${bottom} L${pts[0][0].toFixed(2)} ${bottom} Z`;
      inner += `<path d="${area}" fill="${fill || line}" fill-opacity="${fill ? 1 : 0.2}"/>`;
      inner += `<path d="${d}" fill="none" stroke="${line}" stroke-width="${ENV_LINE}" stroke-linejoin="round"/>`;
      if (item.points !== false) for (const [x, y] of pts.slice(1)) inner += `<circle cx="${x.toFixed(2)}" cy="${y.toFixed(2)}" r="${ENV_DOT}" fill="${line}"/>`;
    }
    node.append(svg(w, h, inner));
    if (empty && B.params.length) node.title = "A reference the sheet doesn't have: Bitwig draws the display empty";
    return node;
  }

  /** The text a knob or value shows: its value_format (FormattedValue), else the plug-in's text from the sheet. */
  function valueText(item, p) {
    const vf = item.value_format;
    const n = p && isNum(p.value) ? p.value : 0.5;
    if (isObj(vf) && Array.isArray(vf.map) && vf.map.length) {
      let best = null, bd = Infinity;
      for (const e of vf.map) {
        if (!Array.isArray(e)) continue;
        const [name, v] = typeof e[0] === "string" ? [e[0], e[1]] : [e[1], e[0]];
        if (isNum(v) && Math.abs(v - n) < bd) { bd = Math.abs(v - n); best = name; }
      }
      if (best !== null) return String(best);
    }
    if ((typeof vf === "string" && vf.trim().toLowerCase() === "percent") || (isObj(vf) && !has(vf, "map"))) {
      const pc = typeof vf === "string";
      const lo = pc ? 0 : Array.isArray(vf.range) ? vf.range[0] : 0;
      const hi = pc ? 100 : Array.isArray(vf.range) ? vf.range[1] : 1;
      const dec = pc ? 0 : isNum(vf.decimals) ? Math.max(0, Math.min(6, Math.round(vf.decimals))) : 0;
      const unit = pc ? "%" : String(vf.unit || "");
      const num = (lo + n * (hi - lo)).toFixed(dec);
      return unit === "" ? num : unit === "%" ? num + "%" : num + " " + unit;
    }
    if (!p) return "—";
    if (p.steps > 0 && (p.step_names || []).length) return p.step_names[Math.round(n * (p.steps - 1))] || p.text;
    return p.text;
  }
  const has = (o, k) => isObj(o) && Object.prototype.hasOwnProperty.call(o, k);

  /** Marks the selected node and nodes with problems. */
  function mark(node, ctx) {
    node.querySelectorAll("[data-path]").forEach((e) => {
      const key = JSON.parse(e.dataset.path).join("/");
      if (ctx.selected !== undefined && key === ctx.selected && !e.dataset.page) e.classList.add("pv-sel");
      const level = ctx.problems && ctx.problems.get(key);
      if (level) e.classList.add("pv-" + level);
    });
  }

  root.PPBPreview = { render, font, colour };
})(window);
