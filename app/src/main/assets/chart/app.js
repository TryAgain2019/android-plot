'use strict';
/*
 * Chart page for BTC Plot. Rendering only: all market data is fetched by the Android side
 * (Kotlin) and pushed in through window.chartApp.receive(message). User actions go back through
 * the AndroidBridge JavaScript interface.
 *
 * Message types (Kotlin -> JS), times are UTC seconds:
 *   init    {settings, version}
 *   reset   {gen, tf, symbol, venue}                    start of a (re)load for a timeframe
 *   price   {gen, mode: set|prepend|live, bars:[[t,o,h,l,c]...]}
 *   oi      {gen, mode: set|live, bars:[[t,o,h,l,c]...]}
 *   funding {gen, mode: set|live, series:{hyperliquid:[[t,v]...], okx:..., binance:..., bybit:...}}
 *   busy    {gen, what: price|oi|funding, busy, detail}
 *   status  {live: ok|warn|error, sources:{key:{state, text}}}
 *   error   {gen, text}                                 shown as a banner with a retry button
 */
(function () {
  const L = window.LightweightCharts;

  const COLORS = {
    bg: '#212121',
    text: '#c8c8c8',
    legend: '#d6d6d6',
    line: '#333333',
    up: '#22cf90',
    down: '#d72f6a',
    oiLabel: '#22cf90',
    crosshair: '#80858f',
    crosshairLabel: '#3b3e46',
    zeroLine: '#7a7a7a',
  };

  // Funding lines; drawn in this order (later ones on top).
  const EXCHANGES = [
    { key: 'bybit', name: 'Bybit', color: '#a7f0d8' },
    { key: 'binance', name: 'Binance', color: '#efa3bd' },
    { key: 'okx', name: 'OKX', color: '#e83e78' },
    { key: 'hyperliquid', name: 'Hyperliquid', color: '#26c88e' },
  ];
  const LEGEND_ORDER = ['hyperliquid', 'okx', 'binance', 'bybit'];

  const TIMEFRAMES = [
    { code: '1m', label: '1m', sec: 60 },
    { code: '5m', label: '5m', sec: 300 },
    { code: '15m', label: '15m', sec: 900 },
    { code: '30m', label: '30m', sec: 1800 },
    { code: '1h', label: '1h', sec: 3600 },
    { code: '4h', label: '4h', sec: 14400 },
    { code: '1d', label: '1D', sec: 86400 },
    { code: '1w', label: '1W', sec: 604800 },
    { code: '1M', label: '1M', sec: 2629800 },
  ];

  const bridge = window.AndroidBridge || null;
  function call(method, ...args) {
    try {
      if (bridge && typeof bridge[method] === 'function') bridge[method](...args);
    } catch (e) {
      console.error('bridge.' + method, e);
    }
  }

  // ---------------------------------------------------------------- formatting

  const nfPrice = new Intl.NumberFormat('en-US', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

  function fmtPrice(v) {
    return nfPrice.format(v);
  }

  function trimZeros(s) {
    return s.indexOf('.') < 0 ? s : s.replace(/0+$/, '').replace(/\.$/, '');
  }

  function fmtOi(v) {
    const a = Math.abs(v);
    if (a >= 1e6) return (v / 1e6).toFixed(3) + 'M';
    if (a >= 1e3) return (v / 1e3).toFixed(3) + 'K';
    return v.toFixed(0);
  }

  function fmtOiTick(v) {
    const a = Math.abs(v);
    if (a >= 1e6) return trimZeros((v / 1e6).toFixed(3)) + 'M';
    if (a >= 1e3) return trimZeros((v / 1e3).toFixed(3)) + 'K';
    return v.toFixed(0);
  }

  function fmtFunding(v) {
    const s = v.toFixed(4);
    return s === '-0.0000' ? '0.0000' : s;
  }

  function fmtFundingTick(v) {
    const s = trimZeros(v.toFixed(4));
    return s === '-0' ? '0' : s;
  }

  function signed(v, fmt) {
    return (v > 0 ? '+' : v < 0 ? '−' : '') + fmt(Math.abs(v));
  }

  function esc(s) {
    return String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  }

  // ---------------------------------------------------------------- chart

  const chartEl = document.getElementById('chart');
  const chart = L.createChart(chartEl, {
    autoSize: true,
    layout: {
      background: { type: L.ColorType.Solid, color: COLORS.bg },
      textColor: COLORS.text,
      fontSize: 11,
      fontFamily: 'Roboto, -apple-system, "Segoe UI", "Helvetica Neue", sans-serif',
      attributionLogo: false,
      panes: { separatorColor: COLORS.line, separatorHoverColor: 'rgba(255,255,255,0.08)', enableResize: true },
    },
    localization: { locale: 'en-US', dateFormat: "dd MMM 'yy" },
    grid: { vertLines: { visible: false }, horzLines: { visible: false } },
    rightPriceScale: { borderColor: COLORS.line, borderVisible: true, minimumWidth: 52, entireTextOnly: true },
    timeScale: {
      borderColor: COLORS.line,
      // The newest bar stays against the price axis: no empty space after it, and zooming keeps
      // the right edge where it is instead of zooming around the fingers.
      rightOffset: 0,
      fixRightEdge: true,
      rightBarStaysOnScroll: true,
      barSpacing: 6,
      minBarSpacing: 0.4,
      timeVisible: false,
      secondsVisible: false,
      shiftVisibleRangeOnNewBar: true,
      lockVisibleTimeRangeOnResize: true,
    },
    crosshair: {
      mode: L.CrosshairMode.Normal,
      vertLine: { color: COLORS.crosshair, width: 1, style: L.LineStyle.Dashed, labelBackgroundColor: COLORS.crosshairLabel },
      horzLine: { color: COLORS.crosshair, width: 1, style: L.LineStyle.Dashed, labelBackgroundColor: COLORS.crosshairLabel },
    },
    handleScroll: { mouseWheel: true, pressedMouseMove: true, horzTouchDrag: true, vertTouchDrag: false },
    handleScale: { axisPressedMouseMove: { time: true, price: true }, axisDoubleClickReset: { time: true, price: true }, mouseWheel: true, pinch: true },
    kineticScroll: { touch: true, mouse: false },
    trackingMode: { exitMode: L.TrackingModeExitMode.OnNextTap },
  });

  const candleStyle = {
    upColor: COLORS.up,
    downColor: COLORS.down,
    borderVisible: false,
    wickUpColor: COLORS.up,
    wickDownColor: COLORS.down,
    priceLineVisible: false,
  };

  const priceSeries = chart.addSeries(L.CandlestickSeries, Object.assign({}, candleStyle, {
    lastValueVisible: true,
    priceFormat: { type: 'custom', minMove: 0.1, formatter: fmtPrice, tickmarksFormatter: ps => ps.map(fmtPrice) },
  }), 0);

  const oiFormat = { type: 'custom', minMove: 1, formatter: fmtOi, tickmarksFormatter: ps => ps.map(fmtOiTick) };
  const oiSeries = chart.addSeries(L.CandlestickSeries, Object.assign({}, candleStyle, {
    lastValueVisible: false,
    priceFormat: oiFormat,
  }), 1);
  // Invisible line carrying the OI closes, only to get a fixed-colour "Open Interest" axis label
  // (a candlestick series colours its label by the direction of the last bar).
  const oiLabelSeries = chart.addSeries(L.LineSeries, {
    color: COLORS.oiLabel,
    lineVisible: false,
    pointMarkersVisible: false,
    crosshairMarkerVisible: false,
    priceLineVisible: false,
    lastValueVisible: true,
    priceFormat: oiFormat,
  }, 1);

  const fundingFormat = { type: 'custom', minMove: 0.0001, formatter: fmtFunding, tickmarksFormatter: ps => ps.map(fmtFundingTick) };
  // Keeps 0 inside the funding scale so the zero line is always in view.
  const includeZero = original => {
    const info = original();
    if (info && info.priceRange) {
      info.priceRange.minValue = Math.min(info.priceRange.minValue, 0);
      info.priceRange.maxValue = Math.max(info.priceRange.maxValue, 0);
    }
    return info;
  };
  for (const ex of EXCHANGES) {
    ex.series = chart.addSeries(L.LineSeries, {
      color: ex.color,
      lineWidth: 1,
      lineType: L.LineType.WithSteps,
      priceLineVisible: false,
      lastValueVisible: true,
      crosshairMarkerVisible: false,
      priceFormat: fundingFormat,
      autoscaleInfoProvider: includeZero,
    }, 2);
    // Dashed zero line. Every funding series gets one (they draw on the same pixels) so it stays
    // when some exchanges are switched off.
    ex.series.createPriceLine({
      price: 0,
      color: COLORS.zeroLine,
      lineWidth: 1,
      lineStyle: L.LineStyle.Dashed,
      lineVisible: true,
      axisLabelVisible: false,
      title: '',
    });
  }

  // Pane heights as in the reference (about 62/18/18 %); short (landscape) screens give the lower
  // panes a bit more room so their stacked axis labels fit.
  let paneLayout = '';
  function applyPaneLayout() {
    const layout = chartEl.clientHeight > 0 && chartEl.clientHeight < 520 ? 'short' : 'tall';
    if (layout === paneLayout) return;
    paneLayout = layout;
    const panes = chart.panes();
    panes[0].setStretchFactor(layout === 'short' ? 2.3 : 3.4);
    panes[1].setStretchFactor(1);
    panes[2].setStretchFactor(1);
  }
  applyPaneLayout();
  if (window.ResizeObserver) new ResizeObserver(applyPaneLayout).observe(chartEl);
  chart.priceScale('right', 0).applyOptions({ scaleMargins: { top: 0.09, bottom: 0.05 } });
  chart.priceScale('right', 1).applyOptions({ scaleMargins: { top: 0.2, bottom: 0.08 } });
  // Room at the top for the two-line funding legend.
  chart.priceScale('right', 2).applyOptions({ scaleMargins: { top: 0.3, bottom: 0.08 } });

  // ---------------------------------------------------------------- state

  const state = {
    gen: 0,
    tf: TIMEFRAMES[6],
    symbol: 'BTCUSDT',
    venue: 'Binance-Futures',
    shift: 0, // seconds added to UTC timestamps for display (local time on intraday timeframes)
    price: [],
    oi: [],
    funding: {},
    busy: {},
    hover: null,
    needInitialView: true,
    settings: {
      tz: 'local',
      oi: { binance: true, bybit: true, okx: true, hyperliquid: true },
      funding: { binance: true, bybit: true, okx: true, hyperliquid: true },
    },
    sources: {},
  };

  // ---------------------------------------------------------------- legends

  const legends = [0, 1, 2].map(() => {
    const el = document.createElement('div');
    el.className = 'legend';
    return el;
  });

  function paneCell(index) {
    const pane = chart.panes()[index];
    const row = pane && pane.getHTMLElement();
    if (!row) return null;
    // A pane row holds [left scale, pane, right scale] cells; the pane cell is position:relative.
    for (const cell of row.children) {
      if (getComputedStyle(cell).position === 'relative') return cell;
    }
    return row.children[1] || null;
  }

  function placeLegends() {
    legends.forEach((el, i) => {
      const cell = paneCell(i);
      if (cell && el.parentElement !== cell) cell.appendChild(el);
    });
  }

  function barAt(arr, time) {
    let lo = 0;
    let hi = arr.length - 1;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      const t = arr[mid].time;
      if (t === time) return mid;
      if (t < time) lo = mid + 1; else hi = mid - 1;
    }
    return -1;
  }

  function busyText(what) {
    const b = state.busy[what];
    return b ? `<span class="busy">${esc(b.detail || 'loading…')}</span>` : '';
  }

  function ohlcHtml(bar, fmt) {
    const color = bar.close >= bar.open ? COLORS.up : COLORS.down;
    return ['O', 'H', 'L', 'C'].map((k, i) => {
      const v = [bar.open, bar.high, bar.low, bar.close][i];
      return `<span class="${i ? 'k' : 'k0'}">${k}</span><span style="color:${color}">${fmt(v)}</span>`;
    }).join('');
  }

  // Line 1: title (+ change for price) and loading state. Line 2: the values of the bar under the
  // crosshair; for funding always shown (latest values otherwise), since the exchange names are
  // not on the axis labels.
  function updateLegends() {
    const hovering = state.hover !== null;

    // price
    let idx = hovering ? barAt(state.price, state.hover) : state.price.length - 1;
    let line1 = `<span class="title">${esc(state.symbol)}, ${esc(state.tf.label)}, ${esc(state.venue)}</span>`;
    let line2 = '';
    if (idx >= 0 && state.price[idx]) {
      const bar = state.price[idx];
      if (hovering) line2 = ohlcHtml(bar, fmtPrice);
      const prev = idx > 0 ? state.price[idx - 1] : null;
      if (prev) {
        const ch = bar.close - prev.close;
        const pct = prev.close ? (ch / prev.close) * 100 : 0;
        line1 += `<span class="v">${signed(ch, fmtPrice)} (${signed(pct, v => v.toFixed(2))}%)</span>`;
      }
    }
    legends[0].innerHTML = legendHtml(line1 + busyText('price'), line2);

    // open interest
    idx = hovering ? barAt(state.oi, state.hover) : -1;
    line2 = idx >= 0 ? ohlcHtml(state.oi[idx], fmtOi) : '';
    legends[1].innerHTML = legendHtml('<span class="title">Aggregated Open Interest</span>' + busyText('oi'), line2);

    // funding
    line2 = '';
    for (const key of LEGEND_ORDER) {
      const ex = EXCHANGES.find(e => e.key === key);
      if (!state.settings.funding[key]) continue;
      const arr = state.funding[key] || [];
      const i = hovering ? barAt(arr, state.hover) : arr.length - 1;
      if (i >= 0) line2 += `<span class="k">${ex.name}</span><span style="color:${ex.color}">${fmtFunding(arr[i].value)}</span>`;
    }
    legends[2].innerHTML = legendHtml('<span class="title">Cross Exchange Funding</span>' + busyText('funding'), line2);
  }

  function legendHtml(line1, line2) {
    return `<div>${line1}</div>` + (line2 ? `<div class="l2">${line2}</div>` : '');
  }

  chart.subscribeCrosshairMove(param => {
    const hover = param && param.point && param.time !== undefined ? param.time : null;
    if (hover !== state.hover) {
      state.hover = hover;
      updateLegends();
    }
  });

  // ---------------------------------------------------------------- toolbar, banner, sheet

  const tfsEl = document.getElementById('tfs');
  for (const tf of TIMEFRAMES) {
    const b = document.createElement('button');
    b.className = 'tf';
    b.textContent = tf.label;
    b.dataset.tf = tf.code;
    b.addEventListener('click', () => {
      if (tf.code === state.tf.code && state.price.length) {
        scrollToLatest();
        return;
      }
      selectTimeframe(tf.code);
      call('setTimeframe', tf.code);
    });
    tfsEl.appendChild(b);
  }

  function selectTimeframe(code) {
    const tf = TIMEFRAMES.find(t => t.code === code);
    if (tf) state.tf = tf;
    for (const b of tfsEl.children) b.classList.toggle('active', b.dataset.tf === state.tf.code);
    const active = tfsEl.querySelector('.tf.active');
    if (active && active.scrollIntoView) active.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }

  // Jumps to the newest bar. Not the library's animated scrollToRealTime(): its animation can
  // stop a bar short of the end, leaving the newest candle cut off at the edge.
  function scrollToLatest() {
    chart.timeScale().scrollToPosition(0, false);
  }

  const realtimeBtn = document.getElementById('realtime');
  realtimeBtn.addEventListener('click', () => {
    scrollToLatest();
    chart.priceScale('right', 0).applyOptions({ autoScale: true });
    chart.priceScale('right', 1).applyOptions({ autoScale: true });
    chart.priceScale('right', 2).applyOptions({ autoScale: true });
  });

  function updateRealtimeButton(range) {
    range = range || chart.timeScale().getVisibleLogicalRange();
    const n = state.price.length;
    const show = !!range && n > 0 && range.to < n - 1.5;
    realtimeBtn.style.display = show ? 'block' : 'none';
  }

  const banner = document.getElementById('banner');
  document.getElementById('banner-retry').addEventListener('click', () => {
    hideBanner();
    call('retry');
  });
  function showBanner(text) {
    document.getElementById('banner-text').textContent = text;
    banner.style.display = 'block';
  }
  function hideBanner() {
    banner.style.display = 'none';
  }

  const sheet = document.getElementById('sheet');
  document.getElementById('info-btn').addEventListener('click', () => {
    renderSheet();
    sheet.classList.add('open');
  });
  document.getElementById('close-sheet').addEventListener('click', () => sheet.classList.remove('open'));
  sheet.addEventListener('click', e => {
    if (e.target === sheet) sheet.classList.remove('open');
  });
  sheet.addEventListener('click', e => {
    const a = e.target.closest && e.target.closest('a[href]');
    if (a && bridge) {
      e.preventDefault();
      call('openUrl', a.href);
    }
  });

  const NAMES = { binance: 'Binance', bybit: 'Bybit', okx: 'OKX', hyperliquid: 'Hyperliquid' };
  const COLOR_OF = Object.fromEntries(EXCHANGES.map(e => [e.key, e.color]));

  function toggleRow(label, color, checked, onChange) {
    const row = document.createElement('label');
    row.className = 'row';
    row.innerHTML = `<span><span class="swatch" style="background:${color}"></span>${esc(label)}</span>`;
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.checked = checked;
    box.addEventListener('change', () => onChange(box.checked));
    row.appendChild(box);
    return row;
  }

  function renderSheet() {
    const src = document.getElementById('sources');
    src.innerHTML = '';
    const keys = Object.keys(state.sources);
    if (!keys.length) src.innerHTML = '<div class="row"><span class="small">Waiting for data…</span></div>';
    for (const key of keys) {
      const s = state.sources[key];
      const row = document.createElement('div');
      row.className = 'row';
      row.innerHTML = `<span>${esc(s.name || NAMES[key] || key)}</span><span class="src-state ${esc(s.state)}">${esc(s.text || s.state)}</span>`;
      src.appendChild(row);
    }

    const oi = document.getElementById('oi-toggles');
    oi.innerHTML = '';
    for (const key of LEGEND_ORDER) {
      oi.appendChild(toggleRow(NAMES[key], COLOR_OF[key], !!state.settings.oi[key], on => {
        state.settings.oi[key] = on;
        call('setSetting', 'oi.' + key, on ? 'true' : 'false');
      }));
    }

    const fu = document.getElementById('funding-toggles');
    fu.innerHTML = '';
    for (const key of LEGEND_ORDER) {
      fu.appendChild(toggleRow(NAMES[key], COLOR_OF[key], !!state.settings.funding[key], on => {
        state.settings.funding[key] = on;
        applyFundingVisibility();
        call('setSetting', 'funding.' + key, on ? 'true' : 'false');
      }));
    }

    for (const b of document.querySelectorAll('#tz-seg button')) {
      b.classList.toggle('on', b.dataset.tz === state.settings.tz);
      b.onclick = () => {
        if (state.settings.tz === b.dataset.tz) return;
        state.settings.tz = b.dataset.tz;
        renderSheet();
        call('setSetting', 'tz', b.dataset.tz);
      };
    }
  }

  function applyFundingVisibility() {
    for (const ex of EXCHANGES) ex.series.applyOptions({ visible: !!state.settings.funding[ex.key] });
    updateLegends();
  }

  // ---------------------------------------------------------------- data handling

  function toCandle(b) {
    return { time: b[0] + state.shift, open: b[1], high: b[2], low: b[3], close: b[4] };
  }

  function liveUpdate(arr, series, item) {
    const n = arr.length;
    if (n && item.time < arr[n - 1].time) return false;
    if (n && item.time === arr[n - 1].time) arr[n - 1] = item; else arr.push(item);
    series.update(item);
    return true;
  }

  // Like the reference: about six months of daily bars, ending at the price axis. On a narrow
  // (portrait) screen fewer bars are shown so candles stay readable.
  function applyInitialView() {
    const n = state.price.length;
    const width = chart.timeScale().width() || chartEl.clientWidth || 360;
    const minSpacing = state.tf.code === '1d' ? 1.9 : 3;
    const maxBars = state.tf.code === '1d' ? 180 : state.tf.code === '1w' ? 104 : state.tf.code === '1M' ? 60 : 130;
    const visible = Math.max(30, Math.min(maxBars, Math.floor(width / minSpacing)));
    chart.timeScale().setVisibleLogicalRange({ from: Math.max(-2, n - 1 - visible), to: n - 1 });
  }

  function onReset(msg) {
    state.gen = msg.gen;
    selectTimeframe(msg.tf);
    state.symbol = msg.symbol || state.symbol;
    state.venue = msg.venue || state.venue;
    const intraday = state.tf.sec < 86400;
    state.shift = intraday && state.settings.tz === 'local' ? -new Date().getTimezoneOffset() * 60 : 0;
    state.price = [];
    state.oi = [];
    state.funding = {};
    state.busy = {};
    state.hover = null;
    state.needInitialView = true;
    priceSeries.setData([]);
    oiSeries.setData([]);
    oiLabelSeries.setData([]);
    for (const ex of EXCHANGES) ex.series.setData([]);
    chart.timeScale().applyOptions({ timeVisible: intraday, secondsVisible: false });
    hideBanner();
    placeLegends();
    updateLegends();
    updateRealtimeButton();
  }

  function onPrice(msg) {
    const bars = msg.bars.map(toCandle);
    if (msg.mode === 'set') {
      state.price = bars;
      priceSeries.setData(bars);
    } else if (msg.mode === 'prepend') {
      const first = state.price.length ? state.price[0].time : Infinity;
      const older = bars.filter(b => b.time < first);
      if (!older.length) return;
      state.price = older.concat(state.price);
      priceSeries.setData(state.price);
    } else {
      for (const b of bars) liveUpdate(state.price, priceSeries, b);
    }
    if (state.needInitialView && state.price.length) {
      state.needInitialView = false;
      // After the library has processed the new data (it lays out on the next frame), or its own
      // default view would replace ours.
      const gen = state.gen;
      requestAnimationFrame(() => {
        if (gen === state.gen) applyInitialView();
      });
    }
    hideBanner();
    updateLegends();
    updateRealtimeButton();
  }

  function onOi(msg) {
    const bars = msg.bars.map(toCandle);
    if (msg.mode === 'set') {
      state.oi = bars;
      oiSeries.setData(bars);
      oiLabelSeries.setData(bars.map(b => ({ time: b.time, value: b.close })));
    } else {
      for (const b of bars) {
        if (liveUpdate(state.oi, oiSeries, b)) oiLabelSeries.update({ time: b.time, value: b.close });
      }
    }
    updateLegends();
  }

  function onFunding(msg) {
    for (const ex of EXCHANGES) {
      const pts = msg.series && msg.series[ex.key];
      if (!pts) continue;
      const data = pts.map(p => ({ time: p[0] + state.shift, value: p[1] }));
      if (msg.mode === 'set') {
        state.funding[ex.key] = data;
        ex.series.setData(data);
      } else {
        const arr = state.funding[ex.key] || (state.funding[ex.key] = []);
        for (const d of data) liveUpdate(arr, ex.series, d);
      }
    }
    updateLegends();
  }

  function onStatus(msg) {
    const dot = document.getElementById('status-dot');
    dot.className = msg.live === 'ok' ? 'ok' : msg.live === 'error' ? 'error' : msg.live === 'warn' ? 'warn' : '';
    if (msg.sources) state.sources = msg.sources;
    if (sheet.classList.contains('open')) renderSheet();
  }

  function onInit(msg) {
    if (msg.settings) {
      const s = msg.settings;
      if (s.tz) state.settings.tz = s.tz;
      if (s.oi) Object.assign(state.settings.oi, s.oi);
      if (s.funding) Object.assign(state.settings.funding, s.funding);
      applyFundingVisibility();
    }
    if (msg.version) document.getElementById('version').textContent = 'Version ' + msg.version;
    if (msg.tf) selectTimeframe(msg.tf);
  }

  function receive(msg) {
    try {
      switch (msg.type) {
        case 'init': onInit(msg); break;
        case 'reset': onReset(msg); break;
        case 'status': onStatus(msg); break;
        case 'price': if (msg.gen === state.gen) onPrice(msg); break;
        case 'oi': if (msg.gen === state.gen) onOi(msg); break;
        case 'funding': if (msg.gen === state.gen) onFunding(msg); break;
        case 'busy':
          if (msg.gen !== state.gen) break;
          if (msg.busy) state.busy[msg.what] = { detail: msg.detail }; else delete state.busy[msg.what];
          updateLegends();
          break;
        case 'error':
          if (msg.gen === state.gen) showBanner(msg.text);
          break;
      }
    } catch (e) {
      console.error('receive ' + (msg && msg.type), e);
    }
  }

  // ---------------------------------------------------------------- viewport reporting

  let rangeTimer = 0;
  chart.timeScale().subscribeVisibleLogicalRangeChange(range => {
    updateRealtimeButton(range);
    clearTimeout(rangeTimer);
    rangeTimer = setTimeout(reportRange, 150);
  });

  function reportRange() {
    const range = chart.timeScale().getVisibleLogicalRange();
    const n = state.price.length;
    if (!range || !n) return;
    const i0 = Math.max(0, Math.min(n - 1, Math.floor(range.from)));
    const i1 = Math.max(0, Math.min(n - 1, Math.ceil(range.to)));
    call('visibleRange', state.gen, state.price[i0].time - state.shift, state.price[i1].time - state.shift, range.from);
  }

  /** Back button: closes the info sheet if it is open. */
  function back() {
    if (sheet.classList.contains('open')) {
      sheet.classList.remove('open');
      return true;
    }
    return false;
  }

  window.chartApp = { receive, back, chart, state };
  selectTimeframe(state.tf.code);
  placeLegends();
  updateLegends();
  requestAnimationFrame(() => {
    placeLegends();
    call('ready');
  });
})();
