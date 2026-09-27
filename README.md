# BTC Plot

An Android app (built for a Galaxy S21, runs on Android 8+) that draws the three-pane chart from
the reference screenshot and keeps it updating live:

1. **BTCUSDT, Binance-Futures** candles, drawn over an **order book heatmap** in the style of
   Material Indicators' FireCharts (sell orders in fire colours, buy orders in teal): the order
   books of Binance, Bybit, OKX and Hyperliquid added together, with volume bars and the visible
   high/low labelled
2. **Aggregated Open Interest**: BTC perpetual OI, summed over Binance, Bybit, OKX and Hyperliquid
3. **Cross Exchange Funding**: funding rate per exchange in % per 8 h, as step lines

Timeframes: 1m, 5m, 15m, 30m, 1h, 4h, 1D, 1W, 1M. Drag to scroll back and forth (older history
loads as you go), pinch to zoom, drag the price axis to rescale it, long-press for a crosshair
with values. `»` jumps back to the latest bar, and ⓘ shows live source status and settings.

## Install

Download [`dist/android-plot.apk`](dist/android-plot.apk) on the phone and open it. Android asks
once to allow installs from that app (browser or My Files); allow it, then tap Install.

## Where the data in the screenshot comes from

The screenshot is a TradingView-style chart. The legend format (`BTCUSDT, 1D, Binance-Futures`)
is TradingView's charting library, and the two lower panes are **Velo** indicators
(`<Velo> Aggregated Open Interest`, `<Velo> Cross Exchange Funding`). Velo (velo.xyz) collects
exchange data and serves it through a paid API that needs a key (`api.velo.xyz`).

This app does not use Velo. It rebuilds the same three series straight from the exchanges' free
public APIs, with no keys needed:

| Pane | Source |
|---|---|
| Price | Binance USDⓈ-M futures: `GET /fapi/v1/klines` for history; live via the `btcusdt@kline_<tf>` WebSocket stream, with REST polling as a fallback |
| Open interest | Binance `/futures/data/openInterestHist` (last 30 days) + daily archive files on `data.binance.vision` (older); Bybit `/v5/market/open-interest`; OKX `/api/v5/rubik/stat/contracts/open-interest-history`; Hyperliquid `metaAndAssetCtxs` (live only). Live values are polled every 5 s |
| Funding | Binance `/fapi/v1/fundingRate` + `premiumIndex`; Bybit `/v5/market/funding/history` + tickers; OKX `funding-rate-history` + `funding-rate`; Hyperliquid `fundingHistory` + `metaAndAssetCtxs` |

How the panes are computed:

- **Open interest** is counted in BTC. Each bar is built from snapshots of every exchange (5 min
  to 1 day apart, depending on the timeframe), summed at the same moments. That gives
  open/high/low/close for each bar, and the current bar also uses the live 5-second polls.
- **Funding**: each settled rate is scaled to 8 hours (Hyperliquid pays hourly, so its rate × 8).
  A bar shows the rate in effect at its close, like a candle's close: the settled rate of the
  funding period its last moment falls in, or each exchange's current predicted rate for the bar
  in progress. So the latest value is the same on every timeframe.

### Loading speed

- Everything fetched is stored on the phone. A later launch draws the chart from storage at once and
  then downloads only what is new (about 8 small requests).
- Open interest and funding load the last four weeks first; older history follows in the background.
  History pages are fetched in parallel.
- After the first chart is up, the other timeframes' candles are refreshed in the background, so
  switching timeframe shows candles immediately.
- The first launch still downloads Binance's daily archive files for the 1D view once (about 170
  small files); they are kept afterwards.

## The order book heatmap

### Where the heatmap chart in the HODL15Capital post comes from

The chart in the post (black background, a `SENSITIVITY` slider, asks in red/orange/yellow above
the price, bids in teal below, "15d 2h ago … now" on the time axis) is **Material Indicators'
FireCharts** (materialindicators.com). It is a heatmap of **Binance's BTC/USDT spot order book**:
every horizontal line is limit orders resting at that price, brighter where more BTC sits. The
"fake sell orders at $85,000" in the post are such a wall that was pulled before price got there.
Material Indicators also run a FireCharts chatbot that posts a two-week BTC chart like this one
into partner Telegram communities every 1, 2 or 4 hours. The evenly spaced "2h ago" labels on the
time axis fit that.

Who has the data: Binance publishes its order book live, for free (REST depth snapshots and
WebSocket updates), but it keeps **no public order book history**. Its archive (data.binance.vision)
has trades and candles, and for futures only depth summed in ±1–5 % bands. Material Indicators
record the book around the clock on their own servers and sell that history (FireCharts, about
three years for Binance BTC and USDT pairs). Other paid tools do the same (CoinGlass liquidity
heatmap, Bookmap, TensorCharts, TapeSurf).

### How the app builds it

The app records the order books itself, from every exchange it uses, and adds them together:

| Book | Request | Levels a side | Snapshot |
|---|---|---|---|
| Binance spot BTCUSDT | `GET /api/v3/depth?limit=5000` (weight 250) | 5000 | ~60 KB |
| Binance futures BTCUSDT | `GET /fapi/v1/depth?limit=1000` (weight 20) | 1000 | ~8 KB |
| Bybit BTCUSDT (linear) | `GET /v5/market/orderbook?category=linear&limit=500` | 500 | ~4 KB |
| OKX BTC-USDT-SWAP | `GET /api/v5/market/books-full?sz=5000` (sizes in 0.01 BTC contracts) | 5000 | ~49 KB |
| Hyperliquid BTC | `POST /info {"type":"l2Book","nSigFigs":4}` and `nSigFigs: 3` | 20 of $10, 20 of $100 | ~1 KB |

Each can be switched off in ⓘ. Hyperliquid only returns 20 levels a side, so it is asked twice:
grouped by $10 near the price and by $100 further out. The part of a $100 level that the $10 levels
do not hold is spread evenly over its $10 bins.

- **While the app is open**: all enabled books at once, every minute (about 7 MB an hour with all
  five on).
- **While it is closed**: a JobScheduler job does the same about every 15 minutes (Android may
  delay it when the phone sleeps deeply), about 11 MB a day with all five. ⓘ → *Record while
  closed* sets it to always, Wi-Fi only or off.
- Every snapshot is summed into $10 price bins (up to 10 % from the price) and kept on the phone
  for 31 days, at about 2 bytes per bin, **per exchange**. Switching an exchange off therefore also
  takes it out of the history already recorded, and switching it back on brings it back.
- The heatmap adds up the exchanges' books of each sampling round (the requests go out together, so
  a round's snapshots are seconds apart). If an exchange misses a round, its last book counts for up
  to 30 minutes.
- Each bar shows the **average quantity** resting in each price band while the bar was open. A
  round counts until the next one, but for at most 30 minutes, so gaps in the recording stay
  black. Bands are $10 (1m, 5m), $20 (15m–1h), $50 (4h), $100 (1D), $250 (1W) and $500 (1M).
- **Sensitivity**: the slider's left handle hides quantities below it, the right handle is where
  colours reach full strength. The range adapts to the timeframe's data (5th to 99.8th
  percentile). Long-press a band to read its quantity.

The heat builds up from the moment the app starts recording: older bars stay black. FireCharts
shows years because Material Indicators have been recording since. A REST snapshot also only
holds the price levels nearest the price (Binance spot's 5000 reach about ±1 %; Hyperliquid's $100
levels about ±2 %). ⓘ shows the range each exchange's latest snapshot covered; walls further away
are not seen.

### Differences from Velo's numbers

- Velo does not publish exactly which markets its aggregate includes. This app sums the four
  USDT/USDC-margined BTC perpetuals shown in the funding pane. Exchanges can be switched off in ⓘ.
- Hyperliquid publishes no open-interest history. Its OI is recorded while the app runs (and kept
  on the phone). Before the first recording, its earliest known value is carried back, so older
  bars treat Hyperliquid's OI as constant.
- OKX only serves about 3 months of funding history, so its funding line starts there. It also
  keeps only the latest 1440 OI snapshots per resolution, so long histories use coarser (daily)
  OKX snapshots.
- Intraday OI history is interpolated between snapshots (5-minute snapshots at the finest).
  The live bar is exact.
- Binance, Bybit and OKX block some countries (for example the US). The ⓘ panel then shows which
  source failed (e.g. `HTTP 451`), and the aggregate uses the exchanges that answer.

## Build

The Gradle build compiles Kotlin against `android.jar` and calls `aapt2`, D8, `zipalign` and
`apksigner` itself. It does not use the Android Gradle Plugin, because the environment the app was
written in cannot reach Google's Maven repository.

```sh
tools/setup-toolchain.sh          # android.jar (API 34), D8, and aapt2/zipalign/apksigner via apt
./gradlew :app:test :app:assembleApk   # tests, then dist/android-plot.apk
```

With a regular Android SDK in `ANDROID_HOME` (platform 34 + build-tools), the build uses the
SDK's tools instead; only D8 still comes from `tools/setup-toolchain.sh`.

The APK is signed with `keystore/android-plot.jks`. That file is not in the repository (anyone
could sign "updates" with a published key). The first build creates one. Android only installs a
new build over an existing install if both are signed with the same key. Otherwise, uninstall
first (you only lose the app's cached history and settings).

### Tests

- `./gradlew :app:test`: parser tests with each exchange's documented payloads, bar-building
  tests, and end-to-end runs of the whole data layer (loading, paging, archive, live polling,
  WebSocket stream, outages) against a local fake of all four exchanges
  (`app/src/test/.../FakeExchanges.kt`). `LoadTimingTest` prints how fast the panes appear with a
  150 ms round trip per request, on a first launch and on a later one.
- `tools/web-test/`: renders the chart page in headless Chromium with a fake Android bridge.
  `run.js` exercises touch pan, pinch zoom and long-press, and saves screenshots. `heat.js` checks
  the heatmap (colours, sensitivity slider, long-press readout, switching it off) with synthetic
  order books. `replay.js` replays a message transcript recorded by the end-to-end tests
  (`app/build/e2e/*.json`), which checks the heatmap's binary format end to end:
  ```sh
  NODE_PATH=$(npm root -g) node tools/web-test/run.js out/
  NODE_PATH=$(npm root -g) node tools/web-test/heat.js out/
  NODE_PATH=$(npm root -g) node tools/web-test/replay.js app/build/e2e/recordedOrderBookShowsAsHeatOnTheHourlyChart.json out/heat.png
  ```

## Layout

```
app/src/main/assets/chart/   chart page: index.html, app.js, TradingView Lightweight Charts v5.2.1
app/src/main/kotlin/.../
  MainActivity.kt            WebView host and JavaScript bridge
  ChartController.kt         loading, lazy history, live polling, messages to the page
  BookRecordJob.kt           background order book recording (JobScheduler)
  data/                      history loaders per exchange, OI and funding aggregation,
                             order book storage (BookStore) and heatmap building (Heatmap.kt)
  net/                       exchange API clients, Binance archive, price stream
  model/                     timeframes, bars, exchanges
tools/                       toolchain setup, browser tests
```

Charts are drawn with [TradingView Lightweight Charts™](https://www.tradingview.com/lightweight-charts/)
(Apache 2.0, © TradingView, Inc., https://www.tradingview.com/). This app is not affiliated with
Velo, TradingView, Material Indicators or any exchange.
