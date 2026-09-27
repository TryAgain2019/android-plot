'use strict';
/*
 * Chart page for BTC Plot. Rendering only: all market data is fetched by the Android side
 * (Kotlin) and pushed in through window.chartApp.receive(message). User actions go back through
 * the AndroidBridge JavaScript interface.
 *
 * Message types (Kotlin -> JS), times are UTC seconds:
 *   init    {settings, version}
 *   reset   {gen, tf, symbol, venue}                    start of a (re)load for a timeframe
 *   price   {gen, mode: set|prepend|live, bars:[[t,o,h,l,c,volume]...]}
 *   oi      {gen, mode: set|live, bars:[[t,o,h,l,c]...]}
 *   funding {gen, mode: set|live, series:{hyperliquid:[[t,v]...], okx:..., binance:..., bybit:...}}
 *   heat    {gen, mode: set|live|off, market, name, binSize, since, data, current}   order book heatmap;
 *           data is base64 of bar records (see decodeHeat), current the latest snapshot as one record
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
    volumeUp: 'rgba(34, 207, 144, 0.5)',
    volumeDown: 'rgba(215, 47, 106, 0.5)',
  };

  // Order book heatmap in the style of Material Indicators' FireCharts: asks in fire colours,
  // bids in teal, on black. Stops run from the faintest shown quantity to the strongest. Bars from
  // before the recording started show the current book, dimmed.
  const HEAT = {
    bg: '#0a0a0b',
    asks: [[0, [40, 6, 8]], [0.22, [92, 12, 14]], [0.45, [168, 26, 20]], [0.65, [226, 84, 22]], [0.82, [252, 164, 38]], [1, [255, 236, 150]]],
    bids: [[0, [4, 30, 28]], [0.22, [8, 66, 60]], [0.45, [14, 116, 100]], [0.65, [28, 172, 136]], [0.82, [84, 226, 174]], [1, [200, 255, 230]]],
    gamma: 1.6, // > 1 keeps the everyday book dark so walls stand out
    dim: 0.6, // brightness of the current book on bars that were not recorded
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
  const nfInt = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });

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

  function fmtVolume(v) {
    const a = Math.abs(v);
    if (a >= 1e6) return (v / 1e6).toFixed(2) + 'M';
    if (a >= 1e3) return (v / 1e3).toFixed(2) + 'K';
    return v.toFixed(a >= 100 ? 0 : a >= 10 ? 1 : 2);
  }

  function fmtBtc(v) {
    return v >= 100 ? nfInt.format(v) : v.toFixed(v >= 10 ? 1 : v >= 1 ? 2 : 3);
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

  // Volume bars along the bottom of the price pane, on their own hidden scale.
  const volumeSeries = chart.addSeries(L.HistogramSeries, {
    priceScaleId: 'volume',
    priceFormat: { type: 'custom', minMove: 0.001, formatter: fmtVolume },
    lastValueVisible: false,
    priceLineVisible: false,
  }, 0);
  volumeSeries.priceScale().applyOptions({ scaleMargins: { top: 0.9, bottom: 0 } });

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
  chart.priceScale('right', 0).applyOptions({ scaleMargins: { top: 0.09, bottom: 0.12 } });
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
    hoverPrice: null, // price under the crosshair in the price pane
    needInitialView: true,
    settings: {
      tz: 'local',
      oi: { binance: true, bybit: true, okx: true, hyperliquid: true },
      funding: { binance: true, bybit: true, okx: true, hyperliquid: true },
      heat: { on: true, book: 'spot', bg: 'any', lo: 0, hi: 0.8, bars: 100 },
    },
    heat: {
      market: 'spot',
      name: '',
      binSize: 10,
      since: null, // first recorded snapshot (UTC seconds)
      bars: new Map(), // display time -> record
      first: Infinity, // display time of the oldest recorded bar
      current: null, // the latest snapshot: shown on bars from before the recording started
      version: 0,
      cMin: 1, // quantity codes spanning the data (percentiles), which the sensitivity range maps onto
      cMax: 255,
    },
    sources: {},
  };

  // ---------------------------------------------------------------- order book heatmap

  function heatOn() {
    return state.settings.heat.on;
  }

  /**
   * Heat data is a run of bar records (little endian): bar open time (u32, UTC seconds), top bid
   * bin (i32), bid count (u16), bottom ask bin (i32), ask count (u16), then one quantity code per
   * bin, bids from the top down and asks from the bottom up. Bin k spans [k, k + 1) * binSize; code
   * c stands for an average of 10^((c - 1) / 24 - 4) BTC resting there while the bar was open.
   */
  function decodeHeat(b64, into) {
    if (!b64) return;
    const raw = atob(b64);
    const bytes = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
    const dv = new DataView(bytes.buffer);
    let p = 0;
    while (p + 16 <= bytes.length) {
      const bidCount = dv.getUint16(p + 8, true);
      const askCount = dv.getUint16(p + 14, true);
      const off = p + 16;
      if (off + bidCount + askCount > bytes.length) break;
      into.set(dv.getUint32(p, true) + state.shift, {
        bidTop: dv.getInt32(p + 4, true),
        bidCount,
        askBottom: dv.getInt32(p + 10, true),
        askCount,
        bytes,
        bidOff: off,
        askOff: off + bidCount,
      });
      p = off + bidCount + askCount;
    }
  }

  function decodeOne(b64) {
    const one = new Map();
    decodeHeat(b64, one);
    return one.size ? one.values().next().value : null;
  }

  /** How many of the newest bars get heat (a setting). */
  function heatBarCount() {
    return state.settings.heat.bars;
  }

  /**
   * The heat of bar i: its recorded record, or the current book for bars among the last N that
   * are older than the recording (projected: shown dimmed and labelled). Null for no heat.
   */
  function heatOfBar(i) {
    const n = state.price.length;
    if (i < n - heatBarCount()) return null;
    const time = state.price[i].time;
    const r = state.heat.bars.get(time);
    if (r) return { r, projected: false };
    const cur = state.heat.current;
    if (cur && time < state.heat.first) return { r: cur, projected: true };
    return null;
  }

  function heatQuantity(code) {
    return code ? Math.pow(10, (code - 1) / 24 - 4) : 0;
  }

  /** Bid and ask codes of bin k in a bar record. */
  function heatCodes(r, k) {
    const bid = k <= r.bidTop && k > r.bidTop - r.bidCount ? r.bytes[r.bidOff + r.bidTop - k] : 0;
    const ask = k >= r.askBottom && k < r.askBottom + r.askCount ? r.bytes[r.askOff + k - r.askBottom] : 0;
    return { bid, ask };
  }

  // The sensitivity range spans the quantities the data actually holds (5th to 99.8th percentile),
  // so colours mean the same while panning and adapt to the timeframe's bin size.
  function updateHeatRange() {
    const counts = new Uint32Array(256);
    let total = 0;
    const records = [...state.heat.bars.values()];
    if (state.heat.current) records.push(state.heat.current);
    for (const r of records) {
      const end = r.askOff + r.askCount;
      for (let i = r.bidOff; i < end; i++) {
        const c = r.bytes[i];
        if (c) {
          counts[c]++;
          total++;
        }
      }
    }
    if (!total) {
      state.heat.cMin = 1;
      state.heat.cMax = 255;
      return;
    }
    const percentile = q => {
      let acc = 0;
      for (let c = 1; c < 256; c++) {
        acc += counts[c];
        if (acc >= q * total) return c;
      }
      return 255;
    };
    state.heat.cMin = percentile(0.05);
    state.heat.cMax = Math.max(state.heat.cMin + 4, percentile(0.998));
  }

  function stopColor(stops, t) {
    for (let i = 1; i < stops.length; i++) {
      if (t <= stops[i][0]) {
        const [t0, c0] = stops[i - 1];
        const [t1, c1] = stops[i];
        const f = t1 > t0 ? (t - t0) / (t1 - t0) : 1;
        return [0, 1, 2].map(k => Math.round(c0[k] + (c1[k] - c0[k]) * f));
      }
    }
    return stops[stops.length - 1][1];
  }

  // Colour per quantity code and side, as little-endian RGBA words for ImageData.
  const palette = {
    key: '',
    asks: new Uint32Array(256),
    bids: new Uint32Array(256),
    dimAsks: new Uint32Array(256),
    dimBids: new Uint32Array(256),
  };
  function heatPalette() {
    const h = state.heat;
    const s = state.settings.heat;
    const key = `${h.cMin}/${h.cMax}/${s.lo}/${s.hi}`;
    if (palette.key === key) return palette;
    palette.key = key;
    const lo = h.cMin + s.lo * (h.cMax - h.cMin);
    const hi = Math.max(lo + 0.5, h.cMin + s.hi * (h.cMax - h.cMin));
    for (let c = 0; c < 256; c++) {
      if (c === 0 || c < lo) {
        palette.asks[c] = palette.bids[c] = palette.dimAsks[c] = palette.dimBids[c] = 0;
        continue;
      }
      const t = Math.pow(Math.min(1, (c - lo) / (hi - lo)), HEAT.gamma);
      for (const [side, dim] of [['asks', 'dimAsks'], ['bids', 'dimBids']]) {
        const [r, g, b] = stopColor(HEAT[side], t);
        palette[side][c] = ((255 << 24) | (b << 16) | (g << 8) | r) >>> 0;
        const k = HEAT.dim;
        palette[dim][c] = ((255 << 24) | (Math.round(b * k) << 16) | (Math.round(g * k) << 8) | Math.round(r * k)) >>> 0;
      }
    }
    return palette;
  }

  // The visible part of the heatmap is rendered at one pixel per (bar, price row) into an
  // offscreen canvas, which the pane then scales up with a single drawImage.
  const heatCanvas = document.createElement('canvas');
  const heatCtx = heatCanvas.getContext('2d');
  let heatImage = null;
  let heatKey = '';
  let heatBids = new Uint8Array(0);
  let heatAsks = new Uint8Array(0);
  let fillBids = new Uint8Array(0); // the current book on bars from before the recording
  let fillAsks = new Uint8Array(0);

  function heatLayout(mediaHeight) {
    const n = state.price.length;
    if ((!state.heat.bars.size && !state.heat.current) || !n) return null;
    const ts = chart.timeScale();
    const range = ts.getVisibleLogicalRange();
    if (!range) return null;
    const i0 = Math.max(0, Math.floor(range.from));
    const i1 = Math.min(n - 1, Math.ceil(range.to));
    if (i1 < i0) return null;
    const x0 = ts.logicalToCoordinate(i0);
    const x1 = ts.logicalToCoordinate(i1);
    if (x0 === null || x1 === null) return null;
    const spacing = i1 > i0 ? (x1 - x0) / (i1 - i0) : ts.options().barSpacing;
    const pTop = priceSeries.coordinateToPrice(0);
    const pBottom = priceSeries.coordinateToPrice(mediaHeight);
    if (pTop === null || pBottom === null || !(pTop > pBottom) || !(spacing > 0)) return null;
    const bin = state.heat.binSize;
    const g = Math.max(1, Math.ceil((pTop - pBottom) / mediaHeight / bin)); // bins per row, rows >= 1px
    const rowSize = g * bin;
    const rTop = Math.floor(pTop / rowSize);
    const rows = rTop - Math.floor(pBottom / rowSize) + 1;
    const gx = Math.max(1, Math.ceil(1 / spacing)); // bars per column, columns >= 1px
    const cols = Math.floor((i1 - i0) / gx) + 1;
    return { i0, i1, x0, spacing, g, rowSize, rTop, rows, gx, cols };
  }

  function renderHeat(lay) {
    const pal = heatPalette();
    const n = state.price.length;
    const key = [lay.i0, lay.i1, lay.gx, lay.g, lay.rTop, lay.rows, n, heatBarCount(), state.heat.version, pal.key, state.gen].join(',');
    if (key === heatKey) return;
    heatKey = key;
    const { i0, i1, gx, g, rTop, rows, cols } = lay;
    const cells = cols * rows;
    if (heatBids.length < cells) {
      heatBids = new Uint8Array(cells);
      heatAsks = new Uint8Array(cells);
      fillBids = new Uint8Array(cells);
      fillAsks = new Uint8Array(cells);
    } else {
      heatBids.fill(0, 0, cells);
      heatAsks.fill(0, 0, cells);
      fillBids.fill(0, 0, cells);
      fillAsks.fill(0, 0, cells);
    }
    for (let i = Math.max(i0, n - heatBarCount()); i <= i1; i++) {
      const heat = heatOfBar(i);
      if (!heat) continue;
      const r = heat.r;
      const bidsCh = heat.projected ? fillBids : heatBids;
      const asksCh = heat.projected ? fillAsks : heatAsks;
      const col = Math.floor((i - i0) / gx);
      const b = r.bytes;
      for (let j = 0; j < r.bidCount; j++) { // bins going down the price axis
        const code = b[r.bidOff + j];
        if (!code) continue;
        const row = rTop - Math.floor((r.bidTop - j) / g);
        if (row < 0) continue;
        if (row >= rows) break;
        const idx = row * cols + col;
        if (code > bidsCh[idx]) bidsCh[idx] = code;
      }
      for (let j = 0; j < r.askCount; j++) { // bins going up
        const code = b[r.askOff + j];
        if (!code) continue;
        const row = rTop - Math.floor((r.askBottom + j) / g);
        if (row >= rows) continue;
        if (row < 0) break;
        const idx = row * cols + col;
        if (code > asksCh[idx]) asksCh[idx] = code;
      }
    }
    if (heatCanvas.width !== cols || heatCanvas.height !== rows) {
      heatCanvas.width = cols;
      heatCanvas.height = rows;
      heatImage = null;
    }
    if (!heatImage) heatImage = heatCtx.createImageData(cols, rows);
    const px = new Uint32Array(heatImage.data.buffer);
    for (let idx = 0; idx < cells; idx++) {
      const a = heatAsks[idx];
      const bd = heatBids[idx];
      if (a || bd) {
        px[idx] = a >= bd ? pal.asks[a] : pal.bids[bd]; // the bigger side wins where price crossed
      } else {
        const fa = fillAsks[idx];
        const fb = fillBids[idx];
        px[idx] = fa >= fb ? pal.dimAsks[fa] : pal.dimBids[fb];
      }
    }
    heatCtx.putImageData(heatImage, 0, 0);
  }

  let requestChartUpdate = () => {};

  const heatPrimitive = {
    attached(param) {
      requestChartUpdate = param.requestUpdate;
    },
    paneViews() {
      return heatPaneViews;
    },
    priceAxisPaneViews() {
      return heatAxisViews;
    },
  };
  const heatPaneViews = [{
    zOrder: () => 'bottom',
    renderer: () => ({
      draw(target) {
        if (!heatOn()) return;
        target.useBitmapCoordinateSpace(scope => {
          const ctx = scope.context;
          ctx.fillStyle = HEAT.bg;
          ctx.fillRect(0, 0, scope.bitmapSize.width, scope.bitmapSize.height);
          const lay = heatLayout(scope.bitmapSize.height / scope.verticalPixelRatio);
          if (!lay) return;
          const yTop = priceSeries.priceToCoordinate((lay.rTop + 1) * lay.rowSize);
          const yBottom = priceSeries.priceToCoordinate((lay.rTop - lay.rows + 1) * lay.rowSize);
          if (yTop === null || yBottom === null) return;
          renderHeat(lay);
          const hr = scope.horizontalPixelRatio;
          const vr = scope.verticalPixelRatio;
          ctx.imageSmoothingEnabled = false;
          ctx.drawImage(heatCanvas, 0, 0, lay.cols, lay.rows,
            (lay.x0 - lay.spacing / 2) * hr, yTop * vr, lay.cols * lay.gx * lay.spacing * hr, (yBottom - yTop) * vr);
        });
      },
    }),
  }];
  // The price axis beside the heatmap goes black as well (its border line stays).
  const heatAxisViews = [{
    zOrder: () => 'bottom',
    renderer: () => ({
      draw(target) {
        if (!heatOn()) return;
        target.useBitmapCoordinateSpace(scope => {
          const border = Math.max(1, Math.floor(scope.horizontalPixelRatio));
          scope.context.fillStyle = HEAT.bg;
          scope.context.fillRect(border, 0, scope.bitmapSize.width - border, scope.bitmapSize.height);
        });
      },
    }),
  }];
  priceSeries.attachPrimitive(heatPrimitive);

  // Highest high and lowest low of the visible bars, labelled like FireCharts does.
  function drawExtremeLabel(ctx, size, index, price, above) {
    const x = chart.timeScale().logicalToCoordinate(index);
    const y = priceSeries.priceToCoordinate(price);
    if (x === null || y === null || y < 0 || y > size.height) return;
    const text = nfInt.format(price);
    const w = Math.ceil(ctx.measureText(text).width) + 8;
    const h = 15;
    const cx = Math.min(size.width - w / 2 - 2, Math.max(w / 2 + 2, x));
    let top = above ? y - 5 - h : y + 5;
    if (top < 1) top = y + 5;
    if (top + h > size.height - 1) top = y - 5 - h;
    if (top < 1) return;
    ctx.fillStyle = 'rgba(46, 48, 54, 0.92)';
    ctx.beginPath();
    if (ctx.roundRect) ctx.roundRect(cx - w / 2, top, w, h, 2); else ctx.rect(cx - w / 2, top, w, h);
    ctx.fill();
    ctx.fillStyle = '#d9dbe0';
    ctx.fillText(text, cx, top + h / 2 + 0.5);
  }

  const extremesViews = [{
    zOrder: () => 'top',
    renderer: () => ({
      draw(target) {
        const n = state.price.length;
        const range = chart.timeScale().getVisibleLogicalRange();
        if (!n || !range) return;
        const i0 = Math.max(0, Math.ceil(range.from));
        const i1 = Math.min(n - 1, Math.floor(range.to));
        if (i1 - i0 < 2) return;
        let hi = i0;
        let lo = i0;
        for (let i = i0 + 1; i <= i1; i++) {
          if (state.price[i].high > state.price[hi].high) hi = i;
          if (state.price[i].low < state.price[lo].low) lo = i;
        }
        target.useMediaCoordinateSpace(({ context: ctx, mediaSize }) => {
          ctx.font = '10px Roboto, -apple-system, "Segoe UI", sans-serif';
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          drawExtremeLabel(ctx, mediaSize, hi, state.price[hi].high, true);
          drawExtremeLabel(ctx, mediaSize, lo, state.price[lo].low, false);
        });
      },
    }),
  }];
  priceSeries.attachPrimitive({ paneViews: () => extremesViews });

  // ---------------------------------------------------------------- legends

  const legends = [0, 1, 2].map(() => {
    const el = document.createElement('div');
    el.className = 'legend';
    return el;
  });

  // Sensitivity of the heatmap: the left handle hides quantities below it, the right one is where
  // colours reach full strength.
  const heatCtl = document.createElement('div');
  heatCtl.className = 'heat-ctl';
  heatCtl.innerHTML = '<span class="heat-lbl">SENSITIVITY</span><div class="heat-track"><div class="heat-grad"></div>' +
    '<div class="heat-dim lo"></div><div class="heat-dim hi"></div><div class="heat-h lo"></div><div class="heat-h hi"></div></div>';
  const heatTrack = heatCtl.querySelector('.heat-track');

  // The price pane's legend and the slider stack in one box.
  const priceBox = document.createElement('div');
  priceBox.className = 'pane-box';
  priceBox.appendChild(legends[0]);
  priceBox.appendChild(heatCtl);

  function renderSlider() {
    const { lo, hi } = state.settings.heat;
    heatCtl.style.display = heatOn() ? '' : 'none';
    heatCtl.querySelector('.heat-h.lo').style.left = lo * 100 + '%';
    heatCtl.querySelector('.heat-h.hi').style.left = hi * 100 + '%';
    heatCtl.querySelector('.heat-dim.lo').style.width = lo * 100 + '%';
    heatCtl.querySelector('.heat-dim.hi').style.left = hi * 100 + '%';
  }

  let dragging = null;
  function sliderValue(e) {
    const r = heatTrack.getBoundingClientRect();
    return Math.min(1, Math.max(0, (e.clientX - r.left) / r.width));
  }
  function moveSlider(v) {
    const s = state.settings.heat;
    if (dragging === 'lo') s.lo = Math.max(0, Math.min(v, s.hi - 0.05));
    else s.hi = Math.min(1, Math.max(v, s.lo + 0.05));
    renderSlider();
    requestChartUpdate();
  }
  heatCtl.addEventListener('pointerdown', e => {
    e.preventDefault();
    e.stopPropagation();
    const v = sliderValue(e);
    const { lo, hi } = state.settings.heat;
    dragging = v < lo || (v <= hi && v - lo < hi - v) ? 'lo' : 'hi';
    try {
      heatCtl.setPointerCapture(e.pointerId);
    } catch (err) { /* synthetic events in tests */ }
    moveSlider(v);
  });
  heatCtl.addEventListener('pointermove', e => {
    if (!dragging) return;
    e.preventDefault();
    moveSlider(sliderValue(e));
  });
  function endDrag() {
    if (!dragging) return;
    dragging = null;
    call('setSetting', 'heat.lo', state.settings.heat.lo.toFixed(3));
    call('setSetting', 'heat.hi', state.settings.heat.hi.toFixed(3));
  }
  heatCtl.addEventListener('pointerup', endDrag);
  heatCtl.addEventListener('pointercancel', endDrag);
  // Keep the chart from panning underneath.
  for (const type of ['touchstart', 'touchmove', 'mousedown', 'mousemove', 'wheel']) {
    heatCtl.addEventListener(type, e => e.stopPropagation(), { passive: true });
  }

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
    [priceBox, legends[1], legends[2]].forEach((el, i) => {
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
      if (hovering) {
        line2 = ohlcHtml(bar, fmtPrice) + (bar.volume ? `<span class="k">Vol</span><span>${fmtVolume(bar.volume)}</span>` : '') + bookHtml(bar.time);
      }
      const prev = idx > 0 ? state.price[idx - 1] : null;
      if (prev) {
        const ch = bar.close - prev.close;
        const pct = prev.close ? (ch / prev.close) * 100 : 0;
        line1 += `<span class="v">${signed(ch, fmtPrice)} (${signed(pct, v => v.toFixed(2))}%)</span>`;
      }
    }
    if (!hovering) line2 = heatNote();
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

  // Liquidity under the crosshair: the average resting in that price bin while the bar was open.
  function bookHtml(time) {
    if (!heatOn() || state.hoverPrice === null) return '';
    const i = barAt(state.price, time);
    const heat = i >= 0 ? heatOfBar(i) : null;
    if (!heat) return '';
    const bin = state.heat.binSize;
    const k = Math.floor(state.hoverPrice / bin);
    const { bid, ask } = heatCodes(heat.r, k);
    if (!bid && !ask) return '';
    const isAsk = ask >= bid;
    const q = heatQuantity(isAsk ? ask : bid);
    return `<br><span class="k0">${isAsk ? 'Asks' : 'Bids'}</span> <span style="color:${isAsk ? '#ff8f45' : '#3fe0b0'}">${fmtBtc(q)} BTC</span>` +
      `<span class="muted"> at ${nfInt.format(k * bin)}–${nfInt.format((k + 1) * bin)}${heat.projected ? ' (current book)' : ''}</span>`;
  }

  const fmtSince = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });

  // While the recording is young, say where the heatmap starts (or why nothing is recorded).
  function heatNote() {
    if (!heatOn()) return '';
    const book = state.sources.book;
    if (book && book.state === 'error') return `<span class="muted">Order book: ${esc(book.text)}</span>`;
    const since = state.heat.since;
    if (since === null) return '<span class="muted">Current order book (recording…)</span>';
    const recorded = esc(fmtSince.format(new Date(since * 1000)));
    const n = state.price.length;
    const oldest = n ? state.price[Math.max(0, n - heatBarCount())].time : Infinity;
    if (state.heat.current && oldest < state.heat.first) {
      return `<span class="muted">Recorded since ${recorded} · dimmed: current book</span>`;
    }
    if (Date.now() / 1000 - since > 7 * 86400) return '';
    return `<span class="muted">Order book recorded since ${recorded}</span>`;
  }

  function legendHtml(line1, line2) {
    return `<div>${line1}</div>` + (line2 ? `<div class="l2">${line2}</div>` : '');
  }

  chart.subscribeCrosshairMove(param => {
    const hover = param && param.point && param.time !== undefined ? param.time : null;
    const hoverPrice = hover !== null && param.paneIndex === 0 ? priceSeries.coordinateToPrice(param.point.y) : null;
    if (hover !== state.hover || hoverPrice !== state.hoverPrice) {
      state.hover = hover;
      state.hoverPrice = hoverPrice;
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

    renderHeatSettings();

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

  function segRow(label, options, value, onChange) {
    const row = document.createElement('div');
    row.className = 'row';
    row.innerHTML = `<span>${esc(label)}</span>`;
    const seg = document.createElement('span');
    seg.className = 'seg';
    for (const [v, text] of options) {
      const b = document.createElement('button');
      b.textContent = text;
      b.classList.toggle('on', v === value);
      b.addEventListener('click', () => {
        if (v !== value) onChange(v);
      });
      seg.appendChild(b);
    }
    row.appendChild(seg);
    return row;
  }

  function renderHeatSettings() {
    const heat = state.settings.heat;
    const box = document.getElementById('heat-settings');
    box.innerHTML = '';
    const toggle = toggleRow('Show in the price pane', 'linear-gradient(90deg, #b01d14, #ffb020 50%, #1cae88)', heat.on, on => {
      heat.on = on;
      if (!on) state.heat.bars = new Map();
      state.heat.version++;
      applyHeatVisibility();
      call('setSetting', 'heat', on ? 'on' : 'off');
      renderHeatSettings();
    });
    box.appendChild(toggle);
    if (!heat.on) return;
    box.appendChild(segRow('Order book', [['spot', 'Spot'], ['futures', 'Futures']], heat.book, v => {
      heat.book = v;
      state.heat.bars = new Map();
      state.heat.since = null;
      state.heat.version++;
      applyHeatVisibility();
      call('setSetting', 'heat.book', v);
      renderHeatSettings();
    }));
    box.appendChild(segRow('Bars with heat', [50, 100, 200, 500, 1000].map(v => [v, String(v)]), heat.bars, v => {
      heat.bars = v;
      applyHeatVisibility();
      call('setSetting', 'heat.bars', String(v));
      renderHeatSettings();
    }));
    box.appendChild(segRow('Record while closed', [['off', 'Off'], ['wifi', 'Wi-Fi'], ['any', 'Always']], heat.bg, v => {
      heat.bg = v;
      call('setSetting', 'heat.bg', v);
      renderHeatSettings();
    }));
    const since = state.heat.since;
    const note = document.createElement('p');
    note.className = 'small';
    note.textContent = (since ? `Recorded since ${fmtSince.format(new Date(since * 1000))}. ` : 'Nothing recorded yet. ') +
      'Binance keeps no order book history, so among the last bars, those from before the recording show today\'s book, dimmed. ' +
      (heat.book === 'spot'
        ? 'A spot snapshot (5000 price levels a side) is about 60 KB: roughly 4 MB an hour while the app is open, 6 MB a day while closed.'
        : 'A futures snapshot (1000 price levels a side) is about 8 KB: under 1 MB a day while closed.');
    box.appendChild(note);
  }

  function applyFundingVisibility() {
    for (const ex of EXCHANGES) ex.series.applyOptions({ visible: !!state.settings.funding[ex.key] });
    updateLegends();
  }

  // ---------------------------------------------------------------- data handling

  function toCandle(b) {
    return { time: b[0] + state.shift, open: b[1], high: b[2], low: b[3], close: b[4], volume: b[5] || 0 };
  }

  function toVolume(c) {
    if (!c.volume) return { time: c.time }; // no bar (not even a hairline) without volume
    return { time: c.time, value: c.volume, color: c.close >= c.open ? COLORS.volumeUp : COLORS.volumeDown };
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
    state.hoverPrice = null;
    state.needInitialView = true;
    state.heat.bars = new Map();
    state.heat.first = Infinity;
    state.heat.current = null;
    state.heat.version++;
    priceSeries.setData([]);
    volumeSeries.setData([]);
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
      volumeSeries.setData(bars.map(toVolume));
    } else if (msg.mode === 'prepend') {
      const first = state.price.length ? state.price[0].time : Infinity;
      const older = bars.filter(b => b.time < first);
      if (!older.length) return;
      state.price = older.concat(state.price);
      priceSeries.setData(state.price);
      volumeSeries.setData(state.price.map(toVolume));
    } else {
      for (const b of bars) {
        if (liveUpdate(state.price, priceSeries, b)) volumeSeries.update(toVolume(b));
      }
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

  function onHeat(msg) {
    const h = state.heat;
    if (msg.mode === 'off') {
      state.settings.heat.on = false;
      h.bars = new Map();
      h.current = null;
    } else {
      state.settings.heat.on = true;
      if (msg.mode === 'set') {
        h.bars = new Map();
        h.current = null;
        h.market = msg.market || h.market;
        h.name = msg.name || h.name;
        h.binSize = msg.binSize || h.binSize;
      }
      if (msg.since !== undefined) h.since = msg.since;
      decodeHeat(msg.data, h.bars);
      if (msg.current !== undefined) h.current = decodeOne(msg.current);
      h.first = Infinity;
      for (const t of h.bars.keys()) if (t < h.first) h.first = t;
      updateHeatRange();
    }
    h.version++;
    applyHeatVisibility();
  }

  function applyHeatVisibility() {
    renderSlider();
    requestChartUpdate();
    updateLegends();
  }

  function onStatus(msg) {
    const dot = document.getElementById('status-dot');
    dot.className = msg.live === 'ok' ? 'ok' : msg.live === 'error' ? 'error' : msg.live === 'warn' ? 'warn' : '';
    const bookBefore = JSON.stringify(state.sources.book || null);
    if (msg.sources) state.sources = msg.sources;
    if (JSON.stringify(state.sources.book || null) !== bookBefore) updateLegends();
    if (sheet.classList.contains('open')) renderSheet();
  }

  function onInit(msg) {
    if (msg.settings) {
      const s = msg.settings;
      if (s.tz) state.settings.tz = s.tz;
      if (s.oi) Object.assign(state.settings.oi, s.oi);
      if (s.funding) Object.assign(state.settings.funding, s.funding);
      if (s.heat) {
        const heat = state.settings.heat;
        heat.on = s.heat.on !== false;
        heat.book = s.heat.book || heat.book;
        heat.bg = s.heat.bg || heat.bg;
        const lo = parseFloat(s.heat.lo);
        const hi = parseFloat(s.heat.hi);
        if (lo >= 0 && hi <= 1 && lo < hi) {
          heat.lo = lo;
          heat.hi = hi;
        }
        const bars = parseInt(s.heat.bars, 10);
        if (bars > 0) heat.bars = bars;
      }
      applyFundingVisibility();
      applyHeatVisibility();
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
        case 'heat': if (msg.gen === state.gen) onHeat(msg); break;
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
  renderSlider();
  updateLegends();
  requestAnimationFrame(() => {
    placeLegends();
    call('ready');
  });
})();
