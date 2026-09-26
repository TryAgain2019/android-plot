// Renders the chart page in headless Chromium with a fake Android bridge and synthetic data,
// saves screenshots and exercises touch interactions (pan, pinch, long-press, timeframe tap).
//   NODE_PATH=$(npm root -g) node tools/web-test/run.js <out-dir>
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const { makeData } = require('./demo-data');

const page_url = 'file://' + path.resolve(__dirname, '../../app/src/main/assets/chart/index.html');
const outDir = path.resolve(process.argv[2] || 'web-test-out');
fs.mkdirSync(outDir, { recursive: true });

const fakeBridge = () => {
  window.__calls = [];
  const rec = name => (...args) => { window.__calls.push([name, ...args]); };
  window.AndroidBridge = {
    ready: rec('ready'), setTimeframe: rec('setTimeframe'), visibleRange: rec('visibleRange'),
    setSetting: rec('setSetting'), retry: rec('retry'), openUrl: rec('openUrl'),
  };
};

async function loadWithData(page, data, tf = '1d') {
  await page.goto(page_url);
  await page.waitForFunction(() => window.__calls.some(c => c[0] === 'ready'));
  await page.evaluate(({ data, tf }) => {
    const app = window.chartApp;
    app.receive({ type: 'init', version: 'test', settings: { tz: 'utc' } });
    app.receive({ type: 'reset', gen: 1, tf, symbol: 'BTCUSDT', venue: 'Binance-Futures' });
    app.receive({ type: 'price', gen: 1, mode: 'set', bars: data.price });
    app.receive({ type: 'oi', gen: 1, mode: 'set', bars: data.oi });
    app.receive({ type: 'funding', gen: 1, mode: 'set', series: data.funding });
    app.receive({ type: 'status', live: 'ok', sources: { binance: { name: 'Binance', state: 'ok', text: 'live' } } });
  }, { data, tf });
  await page.waitForTimeout(400);
}

async function touch(cdp, type, points) {
  await cdp.send('Input.dispatchTouchEvent', { type, touchPoints: points.map(([x, y], id) => ({ x, y, id })) });
}

async function main() {
  const browser = await chromium.launch();
  const errors = [];
  const data = makeData();
  const results = {};

  // 1. Desktop-sized render to compare against the reference screenshot (1080x722).
  {
    const page = await browser.newPage({ viewport: { width: 1080, height: 760 }, deviceScaleFactor: 1, locale: 'en-US' });
    page.on('pageerror', e => errors.push('desktop: ' + e.message));
    await page.addInitScript(fakeBridge);
    await loadWithData(page, data);
    await page.screenshot({ path: path.join(outDir, 'desktop-1d.png') });
    await page.close();
  }

  // 2. Galaxy S21 portrait (411x914 css px @ 2.625), minus status/nav bars.
  const s21 = { viewport: { width: 411, height: 846 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true, locale: 'en-US' };
  const page = await browser.newPage(s21);
  page.on('pageerror', e => errors.push('s21: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.addInitScript(fakeBridge);
  await loadWithData(page, data);
  await page.screenshot({ path: path.join(outDir, 's21-1d.png') });

  const cdp = await page.context().newCDPSession(page);
  const range = () => page.evaluate(() => window.chartApp.chart.timeScale().getVisibleLogicalRange());
  // Empty space after the newest bar, in bars (0 = the last candle touches the price axis).
  const gap = () => page.evaluate(() => {
    const r = window.chartApp.chart.timeScale().getVisibleLogicalRange();
    return Math.round((r.to - (window.chartApp.state.price.length - 1)) * 100) / 100;
  });
  results.initialRange = await range();
  results.gap = { initial: await gap() };
  // The funding pane's dashed zero line is within the pane.
  results.zeroLine = await page.evaluate(() => {
    const pane = window.chartApp.chart.panes()[2];
    const series = pane.getSeries();
    const y = series[0].priceToCoordinate(0);
    return { inView: y !== null && y >= 0 && y <= pane.getHeight(), onEverySeries: series.every(s => s.priceLines().some(l => l.options().price === 0)) };
  });

  // Drag right-to-left (towards the future): must not open a gap.
  await touch(cdp, 'touchStart', [[300, 300]]);
  for (let i = 1; i <= 10; i++) await touch(cdp, 'touchMove', [[300 - i * 20, 300]]);
  await touch(cdp, 'touchEnd', []);
  await page.waitForTimeout(700);
  results.gap.afterDragTowardsFuture = await gap();

  // Pinch in near the right side, like zooming on the newest candles.
  await touch(cdp, 'touchStart', [[250, 300], [290, 300]]);
  for (let i = 1; i <= 10; i++) await touch(cdp, 'touchMove', [[250 - i * 8, 300], [290 + i * 4, 300]]);
  await touch(cdp, 'touchEnd', []);
  await page.waitForTimeout(400);
  results.gap.afterZoomIn = await gap();
  await page.screenshot({ path: path.join(outDir, 's21-zoomed-right.png') });

  // Pinch out again.
  await touch(cdp, 'touchStart', [[150, 300], [300, 300]]);
  for (let i = 1; i <= 10; i++) await touch(cdp, 'touchMove', [[150 + i * 6, 300], [300 - i * 6, 300]]);
  await touch(cdp, 'touchEnd', []);
  await page.waitForTimeout(400);
  results.gap.afterZoomOut = await gap();

  // Pan: drag right by 150px -> older bars come into view.
  await touch(cdp, 'touchStart', [[250, 300]]);
  for (let i = 1; i <= 10; i++) await touch(cdp, 'touchMove', [[250 + i * 15, 300]]);
  await touch(cdp, 'touchEnd', []);
  await page.waitForTimeout(600);
  results.afterPan = await range();

  // Pinch out (zoom in) around the middle.
  const before = await range();
  await touch(cdp, 'touchStart', [[180, 300], [220, 300]]);
  for (let i = 1; i <= 10; i++) await touch(cdp, 'touchMove', [[180 - i * 10, 300], [220 + i * 10, 300]]);
  await touch(cdp, 'touchEnd', []);
  await page.waitForTimeout(400);
  const afterZoom = await range();
  results.zoom = { beforeWidth: before.to - before.from, afterWidth: afterZoom.to - afterZoom.from };
  await page.screenshot({ path: path.join(outDir, 's21-zoomed.png') });

  // Long press -> crosshair with OHLC in legends.
  await touch(cdp, 'touchStart', [[200, 260]]);
  await page.waitForTimeout(700);
  await touch(cdp, 'touchMove', [[205, 262]]);
  await page.waitForTimeout(200);
  results.legendWhileTracking = await page.evaluate(() => [...document.querySelectorAll('.legend')].map(e => e.textContent));
  await page.screenshot({ path: path.join(outDir, 's21-crosshair.png') });
  await touch(cdp, 'touchEnd', []);

  // Live update of the last candle / new bar.
  await page.evaluate(() => {
    const app = window.chartApp;
    const last = app.state.price[app.state.price.length - 1];
    app.receive({ type: 'price', gen: 1, mode: 'live', bars: [[last.time, last.open, last.high + 500, last.low, last.close + 400]] });
    app.receive({ type: 'price', gen: 1, mode: 'live', bars: [[last.time + 86400, last.close + 400, last.close + 450, last.close + 300, last.close + 420]] });
    app.receive({ type: 'oi', gen: 1, mode: 'live', bars: [[last.time + 86400, 226192, 227000, 226000, 226900]] });
    app.receive({ type: 'funding', gen: 1, mode: 'live', series: { okx: [[last.time + 86400, 0.0041]] } });
  });
  results.afterLive = await page.evaluate(() => ({ n: window.chartApp.state.price.length, legend: document.querySelector('.legend').textContent }));
  // Leave tracking mode, go back to the newest bar, then a new bar arrives: still no gap.
  await page.tap('#info-btn');
  await page.tap('#close-sheet');
  results.realtimeButtonShown = await page.evaluate(() => getComputedStyle(document.getElementById('realtime')).display !== 'none');
  await page.tap('#realtime');
  await page.waitForTimeout(300);
  results.gap.afterRealtimeButton = await gap();
  results.realtimeButtonHidden = await page.evaluate(() => getComputedStyle(document.getElementById('realtime')).display === 'none');
  await page.evaluate(() => {
    const app = window.chartApp;
    const last = app.state.price[app.state.price.length - 1];
    app.receive({ type: 'price', gen: 1, mode: 'live', bars: [[last.time + 86400, last.close, last.close + 10, last.close - 10, last.close + 5]] });
  });
  await page.waitForTimeout(300);
  results.gap.afterNewBar = await gap();
  results.fundingLegend = await page.evaluate(() => document.querySelectorAll('.legend')[2].textContent);
  await page.screenshot({ path: path.join(outDir, 's21-after-live.png') });

  // Prepend older history (lazy load) keeps the right edge anchored.
  const beforePrepend = await range();
  await page.evaluate(() => {
    const app = window.chartApp;
    const first = app.state.price[0];
    const older = [];
    for (let i = 60; i >= 1; i--) older.push([first.time - i * 86400, 90000, 90500, 89500, 90100]);
    app.receive({ type: 'price', gen: 1, mode: 'prepend', bars: older });
  });
  await page.waitForTimeout(200);
  results.prepend = { before: beforePrepend, after: await range() };

  // Timeframe tap.
  await page.tap('button.tf[data-tf="4h"]');
  results.calls = await page.evaluate(() => window.__calls.filter(c => c[0] !== 'visibleRange'));
  results.visibleRangeCalls = await page.evaluate(() => window.__calls.filter(c => c[0] === 'visibleRange').slice(-2));

  // Info sheet.
  await page.tap('#info-btn');
  await page.waitForTimeout(200);
  await page.screenshot({ path: path.join(outDir, 's21-sheet.png') });

  // 3. Landscape.
  const land = await browser.newPage({ viewport: { width: 846, height: 411 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true, locale: 'en-US' });
  land.on('pageerror', e => errors.push('landscape: ' + e.message));
  await land.addInitScript(fakeBridge);
  await loadWithData(land, data);
  await land.screenshot({ path: path.join(outDir, 's21-landscape.png') });

  await browser.close();
  results.errors = errors;
  console.log(JSON.stringify(results, null, 1));
}

main().catch(e => { console.error(e); process.exit(1); });
