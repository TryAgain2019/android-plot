// Checks the order book heatmap of the price pane in headless Chromium: colours above/below the
// price, the sensitivity slider, the long-press readout, the high/low labels and switching it off.
//   NODE_PATH=$(npm root -g) node tools/web-test/heat.js <out-dir>
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');
const { makeData, makeHourly, makeHeat } = require('./demo-data');

const pageUrl = 'file://' + path.resolve(__dirname, '../../app/src/main/assets/chart/index.html');
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

const s21 = { viewport: { width: 411, height: 846 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true, locale: 'en-US' };

async function open(browser, { tf, price, oi, funding, heat, binSize, since }) {
  const page = await browser.newPage(s21);
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(fakeBridge);
  await page.goto(pageUrl);
  await page.waitForFunction(() => window.__calls.some(c => c[0] === 'ready'));
  await page.evaluate(msg => {
    const app = window.chartApp;
    app.receive({ type: 'init', version: 'test', settings: { tz: 'utc', heat: { on: true, book: 'spot', bg: 'any', lo: null, hi: null } } });
    app.receive({ type: 'reset', gen: 1, tf: msg.tf, symbol: 'BTCUSDT', venue: 'Binance-Futures' });
    app.receive({ type: 'price', gen: 1, mode: 'set', bars: msg.price });
    if (msg.oi) app.receive({ type: 'oi', gen: 1, mode: 'set', bars: msg.oi });
    if (msg.funding) app.receive({ type: 'funding', gen: 1, mode: 'set', series: msg.funding });
    app.receive({ type: 'heat', gen: 1, mode: 'set', market: 'spot', name: 'Binance spot BTCUSDT', binSize: msg.binSize, since: msg.since, data: msg.heat });
  }, { tf, price, oi, funding, heat, binSize, since });
  await page.waitForTimeout(500);
  return { page, errors };
}

// Colour of the price pane's main canvas at a price, `dx` css px left of the newest bar.
async function colourAt(page, price, dx = 12) {
  return page.evaluate(({ price, dx }) => {
    const app = window.chartApp;
    const pane = app.chart.panes()[0];
    const canvas = pane.getHTMLElement().querySelector('canvas');
    const series = pane.getSeries()[0];
    const n = app.state.price.length;
    const x = app.chart.timeScale().logicalToCoordinate(n - 1) - dx;
    const y = series.priceToCoordinate(price);
    const ratio = canvas.width / canvas.getBoundingClientRect().width;
    const [r, g, b] = canvas.getContext('2d').getImageData(Math.round(x * ratio), Math.round(y * ratio), 1, 1).data;
    return { r, g, b };
  }, { price, dx });
}

async function touch(cdp, type, points) {
  await cdp.send('Input.dispatchTouchEvent', { type, touchPoints: points.map(([x, y], id) => ({ x, y, id })) });
}

async function main() {
  const browser = await chromium.launch();
  const results = {};
  const allErrors = [];

  // 1. Hourly chart like the post: 16 days of recorded order book.
  const hourly = makeHourly();
  const hourlyHeat = makeHeat(hourly, 20);
  {
    const { page, errors } = await open(browser, { tf: '1h', price: hourly, heat: hourlyHeat, binSize: 20, since: hourly[0][0] });
    // Candles have a black outline on the heatmap: scan right from a candle's centre at mid-body.
    await page.screenshot({ path: path.join(outDir, 'heat-1h-outlines.png') });
    results.outline = await page.evaluate(() => {
      const app = window.chartApp;
      const pane = app.chart.panes()[0];
      const canvas = pane.getHTMLElement().querySelector('canvas');
      const ratio = canvas.width / canvas.getBoundingClientRect().width;
      const series = pane.getSeries()[0];
      const n = app.state.price.length;
      const bodyOf = i => Math.abs(app.state.price[i].close - app.state.price[i].open);
      let best = n - 1;
      for (let i = n - 40; i < n; i++) if (bodyOf(i) > bodyOf(best)) best = i;
      const b = app.state.price[best];
      const x = Math.round(app.chart.timeScale().logicalToCoordinate(best) * ratio);
      const y = Math.round(series.priceToCoordinate((b.open + b.close) / 2) * ratio);
      const row = canvas.getContext('2d').getImageData(x, y, 40, 1).data;
      const px = k => [row[4 * k], row[4 * k + 1], row[4 * k + 2]].join();
      let k = 0;
      while (k < 40 && px(k) === px(0)) k++;
      let ring = 0;
      while (k + ring < 40 && px(k + ring) === '0,0,0') ring++;
      return { candle: px(0), halfBody: k, outline: ring, beyond: px(k + ring) };
    });
    await page.evaluate(() => window.chartApp.chart.timeScale().fitContent());
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(outDir, 'heat-1h-16days.png') });
    const last = hourly[hourly.length - 1][4];
    results.aboveLastPrice = await colourAt(page, last + 300, 2);
    results.belowLastPrice = await colourAt(page, last - 300, 2);
    results.wall85k = await colourAt(page, 85010, 20);
    results.range = await page.evaluate(() => ({ cMin: window.chartApp.state.heat.cMin, cMax: window.chartApp.state.heat.cMax }));

    // Drag the right handle of the slider to the left: more of the book reaches bright colours.
    const track = await page.locator('.heat-track').boundingBox();
    const cdp = await page.context().newCDPSession(page);
    const before = await colourAt(page, last - 900, 2);
    const hiX = track.x + track.width * 0.75;
    await touch(cdp, 'touchStart', [[hiX, track.y + track.height / 2]]);
    for (let i = 1; i <= 8; i++) await touch(cdp, 'touchMove', [[hiX - i * 8, track.y + track.height / 2]]);
    await touch(cdp, 'touchEnd', []);
    await page.waitForTimeout(300);
    const after = await colourAt(page, last - 900, 2);
    results.slider = {
      settings: await page.evaluate(() => ({ ...window.chartApp.state.settings.heat })),
      saved: await page.evaluate(() => window.__calls.filter(c => c[0] === 'setSetting')),
      before, after,
      chartDidNotPan: await page.evaluate(() => window.chartApp.chart.timeScale().getVisibleLogicalRange()),
    };
    await page.screenshot({ path: path.join(outDir, 'heat-1h-sensitive.png') });

    // Long-press on the 85,000 wall near the right edge: the legend reads the asks there.
    const point = await page.evaluate(() => {
      const app = window.chartApp;
      const x = app.chart.timeScale().logicalToCoordinate(app.state.price.length - 8);
      const y = app.chart.panes()[0].getSeries()[0].priceToCoordinate(85010);
      const rect = app.chart.panes()[0].getHTMLElement().getBoundingClientRect();
      return [rect.left + x, rect.top + y];
    });
    await touch(cdp, 'touchStart', [point]);
    await page.waitForTimeout(700);
    await touch(cdp, 'touchMove', [[point[0] + 1, point[1]]]);
    await page.waitForTimeout(200);
    results.readout = await page.evaluate(() => document.querySelector('.legend').textContent);
    await page.screenshot({ path: path.join(outDir, 'heat-1h-readout.png') });
    await touch(cdp, 'touchEnd', []);

    // A live bar replaces the newest record.
    results.liveBefore = await page.evaluate(() => window.chartApp.state.heat.bars.size);
    const lastBar = hourly[hourly.length - 1];
    const next = [lastBar[0] + 3600, lastBar[4], lastBar[4] + 80, lastBar[4] - 60, lastBar[4] + 30, 500];
    const liveHeat = makeHeat([...hourly, next], 20, 2);
    await page.evaluate(({ next, liveHeat }) => {
      const app = window.chartApp;
      app.receive({ type: 'price', gen: 1, mode: 'live', bars: [next] });
      app.receive({ type: 'heat', gen: 1, mode: 'live', since: 1, data: liveHeat });
    }, { next, liveHeat });
    results.liveAfter = await page.evaluate(() => window.chartApp.state.heat.bars.size);

    // Switched off: plain background again, no slider.
    await page.evaluate(() => window.chartApp.receive({ type: 'heat', gen: 1, mode: 'off' }));
    await page.waitForTimeout(200);
    results.off = {
      colour: await colourAt(page, last + 300, 2),
      slider: await page.evaluate(() => getComputedStyle(document.querySelector('.heat-ctl')).display),
    };
    await page.screenshot({ path: path.join(outDir, 'heat-off.png') });
    allErrors.push(...errors);
    await page.close();
  }

  // 2. Cumulative buy/sell balance: blocks of 10 bars alternate buy-heavy (bids x3, asks /3) and sell-heavy.
  {
    const skew = i => (Math.floor(i / 10) % 2 ? 1 / 3 : 3);
    const { page, errors } = await open(browser, { tf: '1h', price: hourly, heat: makeHeat(hourly, 20, hourly.length, skew), binSize: 20, since: hourly[0][0] });
    await page.tap('.heat-mode');
    await page.waitForTimeout(300);
    await page.screenshot({ path: path.join(outDir, 'heat-1h-balance.png') });
    const n = hourly.length;
    const colour = (i, dy) => page.evaluate(({ i, dy }) => {
      const app = window.chartApp;
      const pane = app.chart.panes()[0];
      const canvas = pane.getHTMLElement().querySelector('canvas');
      const ratio = canvas.width / canvas.getBoundingClientRect().width;
      const bar = app.state.price[i];
      const x = app.chart.timeScale().logicalToCoordinate(i) + 1.2; // beside the wick
      const y = pane.getSeries()[0].priceToCoordinate(bar.close + dy);
      const [r, g, b] = canvas.getContext('2d').getImageData(Math.round(x * ratio), Math.round(y * ratio), 1, 1).data;
      return { r, g, b };
    }, { i, dy });
    // Pick a buy-heavy and a sell-heavy bar among the newest, and look $400 below and above the close.
    const buyHeavy = [...Array(20).keys()].map(k => n - 1 - k).find(i => skew(i) > 1);
    const sellHeavy = [...Array(20).keys()].map(k => n - 1 - k).find(i => skew(i) < 1);
    results.balance = {
      mode: await page.evaluate(() => window.chartApp.state.settings.heat.mode),
      saved: await page.evaluate(() => window.__calls.filter(c => c[0] === 'setSetting' && c[1] === 'heat.mode')),
      buyHeavyBelow: await colour(buyHeavy, -400),
      buyHeavyAbove: await colour(buyHeavy, 400),
      sellHeavyAbove: await colour(sellHeavy, 400),
      sellHeavyBelow: await colour(sellHeavy, -400),
    };
    // Long-press reads both sides' totals.
    const cdp = await page.context().newCDPSession(page);
    const point = await page.evaluate(i => {
      const app = window.chartApp;
      const x = app.chart.timeScale().logicalToCoordinate(i);
      const y = app.chart.panes()[0].getSeries()[0].priceToCoordinate(app.state.price[i].close);
      const rect = app.chart.panes()[0].getHTMLElement().getBoundingClientRect();
      return [rect.left + x, rect.top + y];
    }, buyHeavy);
    await touch(cdp, 'touchStart', [point]);
    await page.waitForTimeout(700);
    await touch(cdp, 'touchMove', [[point[0] + 1, point[1]]]);
    await page.waitForTimeout(200);
    results.balance.readout = await page.evaluate(() => document.querySelector('.legend').textContent);
    await touch(cdp, 'touchEnd', []);
    // And back to price levels.
    await page.tap('.heat-mode');
    results.balance.back = await page.evaluate(() => window.chartApp.state.settings.heat.mode);
    allErrors.push(...errors);
    await page.close();
  }

  // 3. Daily chart where only the last month was recorded.
  {
    const data = makeData();
    const { page, errors } = await open(browser, { tf: '1d', ...data, heat: makeHeat(data.price, 100, 31), binSize: 100, since: data.price[data.price.length - 31][0] });
    await page.screenshot({ path: path.join(outDir, 'heat-1d.png') });
    results.dailyNote = await page.evaluate(() => document.querySelector('.legend').textContent);
    await page.tap('#info-btn');
    await page.waitForTimeout(200);
    await page.evaluate(() => document.getElementById('heat-settings').scrollIntoView());
    await page.screenshot({ path: path.join(outDir, 'heat-sheet.png') });
    results.sheet = await page.evaluate(() => document.getElementById('heat-settings').textContent);
    allErrors.push(...errors);
    await page.close();
  }

  // 4. Speed: re-rendering the visible heatmap while panning a zoomed-out hourly chart.
  {
    const { page, errors } = await open(browser, { tf: '1h', price: hourly, heat: hourlyHeat, binSize: 20, since: hourly[0][0] });
    results.renderMs = await page.evaluate(async () => {
      const ts = window.chartApp.chart.timeScale();
      ts.fitContent();
      await new Promise(r => requestAnimationFrame(r));
      const t0 = performance.now();
      const frames = 30;
      for (let i = 0; i < frames; i++) {
        ts.scrollToPosition(-i * 2, false);
        await new Promise(r => requestAnimationFrame(r));
      }
      return Math.round((performance.now() - t0) / frames * 10) / 10;
    });
    allErrors.push(...errors);
    await page.close();
  }

  await browser.close();
  results.errors = allErrors;
  console.log(JSON.stringify(results, null, 1));
}

main().catch(e => { console.error(e); process.exit(1); });
