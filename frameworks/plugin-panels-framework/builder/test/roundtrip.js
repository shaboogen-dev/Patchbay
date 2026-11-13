// Round trip check: node test/roundtrip.js <layout.json | parameters.json> ...
// A layout is read, checked and written again; the written JSON must read the same (key order is reported).
// A parameter sheet prints the builder's hints per parameter.
"use strict";
const fs = require("fs");
const path = require("path");
require(path.join(__dirname, "..", "core.js"));
const PPB = globalThis.PPB;

let failed = false;
const sheets = [];
const layouts = [];
for (const file of process.argv.slice(2)) {
  const json = JSON.parse(fs.readFileSync(file, "utf8"));
  (PPB.isSheet(json) ? sheets : layouts).push([file, json]);
}
for (const [file, sheet] of sheets) {
  console.log(`\n== ${path.basename(file)}`);
  for (const p of sheet.parameters) {
    const h = PPB.paramHints(p);
    const tags = [h.unreliable && "unreliable", h.noAnswer && "no-answer", h.toggle && "toggle",
      h.integers && `integers ${h.integers.from}..${h.integers.to}`,
      h.values && `values ${h.values.min}..${h.values.max} ${h.values.unit}`, h.curve && `curve ${h.curve}`].filter(Boolean);
    console.log(`  #${p.index} ${p.name}: ${tags.join(", ") || "-"}`);
  }
}
for (const [file, json] of layouts) {
  const text = PPB.stringify(json);
  const again = JSON.parse(text);
  const same = PPB.sameJson(json, again);
  const order = PPB.sameKeyOrder(json, again);
  const sheet = sheets.find(([, s]) => PPB.matchesPlugin(json.match || {}, s.plugin));
  const problems = PPB.validate(again, sheet && sheet[1]);
  console.log(`\n== ${path.basename(file)}: ${same ? "SAME" : "DIFFERENT"} after export; key order ${order ? "unchanged" : "changed"}` +
    `${sheet ? "; checked against " + path.basename(sheet[0]) : ""}`);
  for (const p of problems) console.log(`  ${p.level}: ${p.path.join(".")}: ${p.message}`);
  if (!same || problems.some((p) => p.level === "error")) failed = true;
  if (process.env.SHOW) process.stdout.write(text);
}
process.exit(failed ? 1 : 0);
