/*
 * Self-test for the reactor planner simulation.
 *
 *   node tools/reactor-planner/selftest.mjs
 *
 * It loads index.html, runs its script in a tiny DOM stub (so no browser or
 * jsdom is needed) and checks the simulation against numbers derived by hand
 * from ReactorControllerBlockEntity.serverTick.
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const html = readFileSync(join(here, "index.html"), "utf8");

const match = html.match(/<script>([\s\S]*?)<\/script>/);
if (!match) throw new Error("no <script> block found in index.html");
const code = match[1];

/* ---------- minimal DOM stub ---------- */
function makeStub() {
  const fn = function () {};
  return new Proxy(fn, {
    get(target, prop) {
      if (prop === Symbol.toPrimitive) return () => 0;
      if (prop === Symbol.iterator) return function* () {};
      if (prop === "rows" || prop === "cells" || prop === "children") return [];
      if (prop === "length") return 0;
      if (prop === "then") return undefined;
      if (!(prop in target)) target[prop] = makeStub();
      return target[prop];
    },
    set(target, prop, value) { target[prop] = value; return true; },
    apply() { return makeStub(); },
  });
}

const win = makeStub();
const doc = makeStub();
doc.getElementById = () => makeStub();
doc.createElement = () => makeStub();

const factory = new Function("window", "document", "setInterval", "clearInterval", code);
factory(win, doc, () => 0, () => {});
const P = win.ReactorPlanner;
if (!P) throw new Error("window.ReactorPlanner was not exposed");

/* ---------- helpers ---------- */
const BLOCKS = P.BLOCKS;
const key = {
  ".": "empty", "s": "single", "d": "double", "q": "quad",
  "r": "reflector", "p": "pipe", "v": "vent", "a": "absorber", "c": "recovery",
};

/** Build a square grid from rows of single-character keys. */
function load(rows, cfgOverrides = {}) {
  Object.assign(P.cfg, P.DEFAULTS, cfgOverrides);
  const size = rows.length;
  const cells = [];
  for (const row of rows) {
    const parts = row.split(" ");
    if (parts.length !== size) throw new Error(`row "${row}" is not ${size} wide`);
    for (const p of parts) {
      const k = key[p];
      if (!k) throw new Error(`unknown cell "${p}"`);
      cells.push(k);
    }
  }
  P.setSize(size);
  P.setCells(cells);
  P.resetSim();
  return cells;
}

const results = [];
function check(name, actual, expected) {
  const ok = Object.is(actual, expected) ||
    (typeof actual === "number" && typeof expected === "number" && Math.abs(actual - expected) < 1e-9);
  results.push({ name, ok, actual, expected });
}

/* ---------- case A: lone single rod, no neighbours ---------- */
load([
  ". . .",
  ". s .",
  ". . .",
]);
let r = P.simulateTick();
// pulses 1 -> energy 64*1*1 = 64, heat (1/2*1+4) = 4
check("A energy", r.energyProduced, 64);
check("A heat produced", r.heatProduced, 4);
check("A heat removed", r.heatRemoved, 0);
check("A net heat", r.netHeat, 4);
check("A hottest", r.hottest, 4);
check("A rods", r.activeRods, 1);

/* ---------- case B: two adjacent single rods (mutual pulses) ---------- */
load([
  ". . . . .",
  ". s s . .",
  ". . . . .",
  ". . . . .",
  ". . . . .",
]);
r = P.simulateTick();
// each rod: internal 1 + neighbour rodCount 1 = 2 pulses -> 128 RF, heat (2/2*2+4)=6
check("B energy", r.energyProduced, 256);
check("B heat produced", r.heatProduced, 12);
check("B hottest", r.hottest, 6);
check("B active rods", r.activeRods, 2);

/* ---------- case C: single rod + reflector ---------- */
load([
  ". . .",
  "r s .",
  ". . .",
]);
r = P.simulateTick();
// pulses 1 + own rodCount 1 (reflector) = 2 -> 128 RF, heat 6
check("C energy", r.energyProduced, 128);
check("C heat produced", r.heatProduced, 6);

/* ---------- case D: vent removes min(h/100 + 4, h) ---------- */
load([
  ". . .",
  "s v .",
  ". . .",
]);
r = P.simulateTick();
// rod (left) makes 4 heat; the vent to its right is processed after it and sees the fresh heat
check("D heat removed", r.heatRemoved, 4);
check("D hottest", r.hottest, 0);
check("D net heat", r.netHeat, 0);

/* ---------- case D2: vent on a hotter neighbour (integer division) ---------- */
load([
  ". . .",
  "d v .",
  ". . .",
]);
r = P.simulateTick();
// double rod alone: internal 4 -> heat (4/2*4+4) = 12; vent: idiv(12,100)+4 = 4
check("D2 heat removed", r.heatRemoved, 4);
check("D2 hottest", r.hottest, 12 - 4);

/* ---------- case E: absorber over-cools into negatives (game quirk) ---------- */
load([
  ". . .",
  "s a .",
  ". . .",
]);
r = P.simulateTick();
check("E heat removed", r.heatRemoved, 16);
check("E hottest clamps at 0", r.hottest, 0);

/* ---------- case F: recovery port converts heat into energy ---------- */
load([
  ". . .",
  "s c .",
  ". . .",
]);
r = P.simulateTick();
// rod 64 RF + 4 heat; recovery removes min(8,4)=4 -> 4 * 2 * 1 = 8 RF
check("F energy", r.energyProduced, 72);
check("F energy from recovery", r.energyFromRecovery, 8);
check("F heat removed", r.heatRemoved, 4);
check("F hottest", r.hottest, 0);

/* ---------- case G: stack height scales energy, not cooling ---------- */
load([
  ". . .",
  "s v .",
  ". . .",
], { height: 10 });
r = P.simulateTick();
// rod: 64 * 1 * 10 = 640 RF, heat 4 (slope 0 -> mult 1)
// vent still removes only 4 from the layer
check("G energy", r.energyProduced, 640);
check("G heat produced", r.heatProduced, 4);
check("G heat removed", r.heatRemoved, 4);
check("G mult", r.mult, 1);

/* ---------- case H: heatHeightSlope scales heat and the meltdown test ---------- */
load([
  ". . .",
  ". s .",
  ". . .",
], { height: 3, slope: 0.25 });
r = P.simulateTick();
// mult = 1 + 2*0.25 = 1.5 -> heat = 4 * 1.5 = 6 ; energy scales with height, not with slope
check("H mult", P.heatHeightMult(), 1.5);
check("H heat produced", r.heatProduced, 6);
check("H energy", r.energyProduced, 64 * 3);
// normalized heat is compared against maxHeat, so a tall reactor is not punished twice
check("H per-layer heat", r.normalized, Math.trunc(6 / 1.5));

/* ---------- case I: quad rod surrounded by four reflectors ---------- */
load([
  ". r .",
  "r q r",
  ". r .",
]);
r = P.simulateTick();
// internal 12 + 4 reflectors * own rodCount(4) = 28 pulses
check("I energy", r.energyProduced, 64 * 28);
check("I heat", r.heatProduced, 28 / 2 * 28 + 4);

/* ---------- case J1: already above the limit -> prediction 0 ---------- */
load([
  ". . .",
  ". q .",
  ". . .",
], { maxHeat: 2000 });
for (let i = 0; i < 150; i++) P.step();     // quad alone: 76 K/t, so far past 2000
check("J1 heating upwards", P.heatTrend() > 0, true);
check("J1 already over limit", P.getLast().normalized >= 2000, true);
check("J1 prediction is 0", P.ticksUntilMeltdown(), 0);

/* ---------- case J2: below the limit but heating -> finite positive ETA ---------- */
load([
  ". . .",
  ". s .",
  ". . .",
], { maxHeat: 200000 });
for (let i = 0; i < 150; i++) P.step();     // single rod: 4 K/t, huge headroom
check("J2 below limit", P.getLast().normalized < 200000, true);
const ttm = P.ticksUntilMeltdown();
check("J2 prediction is finite", Number.isFinite(ttm) && ttm > 0, true);
// 4 K/t against the remaining headroom, computed independently
const expectedEta = Math.ceil((200000 - P.getLast().normalized) / P.heatTrend());
check("J2 prediction matches headroom/trend", ttm, expectedEta);

/* ---------- case K: a cooled reactor reports as stable ---------- */
load([
  "v v v",
  "v s v",
  "v v v",
]);
for (let i = 0; i < 200; i++) P.step();
check("K trend not rising", P.heatTrend() <= 0.01, true);
check("K no meltdown prediction", P.ticksUntilMeltdown(), -1);

/* ---------- case L: single-pass ordering — a cooler LEFT of the rod sees last tick's heat ---------- */
load([
  ". . .",
  "v s .",
  ". . .",
]);
r = P.simulateTick();
// the vent (left of the rod) is processed first; the rod is still cold, so nothing is removed
check("L first tick removed", r.heatRemoved, 0);
check("L first tick hottest", r.hottest, 4);
check("L first tick net", r.netHeat, 4);
// on the next tick the vent sees the stored heat and removes it
r = P.simulateTick();
check("L second tick removed", r.heatRemoved, 4);

/* ---------- case M: P >= 20 layouts are flagged as un-coolable ---------- */
// quad rod with two reflectors: internal 12 + 2 * own rodCount(4) = 20 pulses
load([
  ". r .",
  "r q .",
  ". . .",
]);
let msgs = P.computeValidationMessages();
check("M flags P>=20", msgs.some(m => m.includes("必然熔毁")), true);

// one reflector only: 12 + 4 = 16 pulses -> still coolable, no warning
load([
  ". r .",
  "p q p",
  "p . .",
]);
msgs = P.computeValidationMessages();
check("M does not flag P=16", msgs.some(m => m.includes("必然熔毁")), false);

/* ---------- report ---------- */
let failed = 0;
for (const t of results) {
  if (!t.ok) failed++;
  const status = t.ok ? "ok  " : "FAIL";
  const detail = t.ok ? "" : `  (actual ${t.actual}, expected ${t.expected})`;
  console.log(`${status}  ${t.name}${detail}`);
}
console.log(`\n${results.length - failed}/${results.length} checks passed`);
process.exit(failed ? 1 : 0);
