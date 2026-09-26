// Replays a message transcript recorded by ControllerEndToEndTest (app/build/e2e/<test>.json)
// into the chart page, i.e. exactly what the Kotlin side would send, and saves a screenshot.
//   NODE_PATH=$(npm root -g) node tools/web-test/replay.js <transcript.json> <out.png> [portrait|landscape]
'use strict';
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const [transcript, out, orientation = 'portrait'] = process.argv.slice(2);
const messages = JSON.parse(fs.readFileSync(transcript, 'utf8'));
const pageUrl = 'file://' + path.resolve(__dirname, '../../app/src/main/assets/chart/index.html');

(async () => {
  const browser = await chromium.launch();
  const size = orientation === 'landscape' ? { width: 846, height: 411 } : { width: 411, height: 846 };
  const page = await browser.newPage({ viewport: size, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true, locale: 'en-US' });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  await page.addInitScript(() => {
    window.__calls = [];
    const rec = name => (...args) => window.__calls.push([name, ...args]);
    window.AndroidBridge = { ready: rec('ready'), setTimeframe: rec('setTimeframe'), visibleRange: rec('visibleRange'), setSetting: rec('setSetting'), retry: rec('retry'), openUrl: rec('openUrl') };
  });
  await page.goto(pageUrl);
  await page.waitForFunction(() => window.__calls.some(c => c[0] === 'ready'));
  const summary = await page.evaluate(msgs => {
    for (const m of msgs) window.chartApp.receive(m);
    const s = window.chartApp.state;
    return {
      gen: s.gen, tf: s.tf.code, price: s.price.length, oi: s.oi.length,
      funding: Object.fromEntries(Object.entries(s.funding).map(([k, v]) => [k, v.length])),
      legends: [...document.querySelectorAll('.legend')].map(e => e.textContent),
      status: document.getElementById('status-dot').className,
    };
  }, messages);
  await page.waitForTimeout(300);
  await page.screenshot({ path: out });
  console.log(JSON.stringify({ messages: messages.length, ...summary, errors }, null, 1));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
