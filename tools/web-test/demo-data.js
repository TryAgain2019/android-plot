// Synthetic data shaped like the reference screenshot (BTCUSDT 1D, Apr-Sep 2026), used to
// check the chart page's look and interactions in a desktop browser / headless Chromium.
'use strict';

function mulberry32(seed) {
  return function () {
    seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function interp(points, t) {
  for (let i = 1; i < points.length; i++) {
    if (t <= points[i][0]) {
      const [t0, v0] = points[i - 1];
      const [t1, v1] = points[i];
      return v0 + (v1 - v0) * (t - t0) / (t1 - t0);
    }
  }
  return points[points.length - 1][1];
}

const day = 86400;
const d = s => Date.UTC(2026, +s.slice(0, 2) - 1, +s.slice(3, 5)) / 1000;

const PRICE_PATH = [
  ['01-01', 88000], ['02-01', 79000], ['03-01', 70000], ['03-25', 66500], ['04-13', 72000], ['04-20', 74500], ['05-01', 78000],
  ['05-10', 82000], ['05-20', 78500], ['05-27', 77500], ['06-02', 73500], ['06-05', 66000], ['06-08', 61000],
  ['06-13', 64200], ['06-20', 63500], ['06-25', 61200], ['06-30', 59000], ['07-05', 63000], ['07-13', 64500],
  ['07-20', 66200], ['07-25', 64200], ['08-01', 64600], ['08-10', 63200], ['08-12', 64000], ['08-14', 69000],
  ['08-15', 74500], ['08-16', 79000], ['08-20', 79200], ['08-25', 80200], ['09-01', 80600], ['09-05', 78200],
  ['09-10', 76600], ['09-14', 76000], ['09-17', 81000], ['09-19', 86400], ['09-20', 84350], ['09-21', 84705.4],
].map(([s, v]) => [d(s), v]);

const OI_PATH = [
  ['01-01', 190000], ['03-25', 197000], ['04-13', 205000], ['05-01', 215000], ['05-12', 228000], ['06-01', 222000],
  ['06-10', 214000], ['07-01', 222000], ['07-10', 232000], ['08-01', 236000], ['08-12', 245000], ['08-14', 262000],
  ['08-17', 238000], ['08-20', 221000], ['09-01', 224000], ['09-10', 226000], ['09-15', 232000], ['09-19', 247000],
  ['09-20', 245000], ['09-21', 226192],
].map(([s, v]) => [d(s), v]);

function makeData(startStr = '01-01', endStr = '09-21') {
  const rnd = mulberry32(42);
  const price = [];
  const oi = [];
  const funding = { hyperliquid: [], okx: [], binance: [], bybit: [] };
  let prevClose = null;
  let prevOi = null;
  for (let t = d(startStr); t <= d(endStr); t += day) {
    const target = interp(PRICE_PATH, t + day);
    const open = prevClose === null ? interp(PRICE_PATH, t) : prevClose;
    const close = target * (1 + (rnd() - 0.5) * 0.012);
    const hi = Math.max(open, close) * (1 + rnd() * 0.012);
    const lo = Math.min(open, close) * (1 - rnd() * 0.012);
    price.push([t, round1(open), round1(hi), round1(lo), round1(close)]);
    prevClose = close;

    const oiTarget = interp(OI_PATH, t + day) * (1 + (rnd() - 0.5) * 0.01);
    const oiOpen = prevOi === null ? interp(OI_PATH, t) : prevOi;
    oi.push([t, oiOpen, Math.max(oiOpen, oiTarget) * (1 + rnd() * 0.004), Math.min(oiOpen, oiTarget) * (1 - rnd() * 0.004), oiTarget]);
    prevOi = oiTarget;

    const base = 0.0055 + 0.003 * Math.sin(t / (9 * day)) + (close - open) / open * 0.25;
    const f = (k, off, noise) => funding[k].push([t, round4(base + off + (rnd() - 0.5) * noise)]);
    f('hyperliquid', 0.0012, 0.006);
    f('okx', 0.0004, 0.005);
    f('binance', -0.0004, 0.004);
    f('bybit', -0.0012, 0.005);
  }
  // Last values from the screenshot.
  funding.hyperliquid[funding.hyperliquid.length - 1][1] = 0.0046;
  funding.okx[funding.okx.length - 1][1] = 0.0038;
  funding.binance[funding.binance.length - 1][1] = 0.0026;
  funding.bybit[funding.bybit.length - 1][1] = -0.0008;
  return { price, oi, funding };
}

function round1(v) { return Math.round(v * 10) / 10; }
function round4(v) { return Math.round(v * 1e4) / 1e4; }

if (typeof module !== 'undefined') module.exports = { makeData };
