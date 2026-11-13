// Regenerates default-theme.js, the builder's built-in theme, from a theme sheet (the framework's Generate
// Theme Sheet, run in Bitwig with its default theme, no BTE theme active), after a Bitwig update:
//   node tools/make-default-theme.js <theme.sheet.json> [UI scale note]
// Sheets from Plug-in Panels 0.1.0-dev.9 wrote whole numbers as "18."; those are read as 18.
"use strict";
const fs = require("fs");
const path = require("path");

const src = process.argv[2];
if (!src) {
  console.error("usage: node tools/make-default-theme.js <theme.sheet.json>");
  process.exit(2);
}
let text = fs.readFileSync(src, "utf8");
let sheet;
try {
  sheet = JSON.parse(text);
} catch (e) {
  sheet = JSON.parse(text.replace(/(\d)\.(?=[^\d])/g, "$1"));
  console.log("repaired numbers written as \"18.\"");
}
if (sheet.sheet !== "plugin-panels-theme-sheet" || sheet.version !== 1) {
  console.error("not a version 1 theme sheet");
  process.exit(1);
}
const nulls = Object.entries(sheet.colors || {}).filter(([, v]) => v === null).map(([k]) => k);
const out = path.join(__dirname, "..", "default-theme.js");
fs.writeFileSync(out,
  "/*\n" +
  ` * Plug-in Panels layout builder: the default theme. Bitwig Studio ${sheet.bitwig || "?"}'s default theme at UI scale ` +
  `${Math.round((sheet.ui_scale || 1) * 100)}%,\n` +
  " * as the framework's Generate Theme Sheet exported it. Theme values only: names, sizes and colours. A BTE\n" +
  " * theme loaded in the builder recolours it. Regenerate with tools/make-default-theme.js.\n */\n" +
  "window.PPB_DEFAULT_THEME = " + JSON.stringify(sheet, null, 1) + ";\n");
console.log(`wrote ${out}: Bitwig ${sheet.bitwig}, ${Object.keys(sheet.fonts || {}).length} fonts, ` +
  `${Object.keys(sheet.colors || {}).length} colours${nulls.length ? ` (${nulls.length} unresolved: ${nulls.join(", ")})` : ""}`);
