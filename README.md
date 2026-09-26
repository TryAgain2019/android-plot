# BTC Plot

An Android app (built for a Galaxy S21, runs on Android 8+) that draws the three-pane chart from
the reference screenshot and keeps it updating live:

1. **BTCUSDT, Binance-Futures** candles
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
  A bar shows the time-weighted average of the rates that accrued during it. The bar in progress
  uses each exchange's current predicted rate.

### Loading speed

- Everything fetched is stored on the phone. A later launch draws the chart from storage at once and
  then downloads only what is new (about 8 small requests).
- Open interest and funding load the last four weeks first; older history follows in the background.
  History pages are fetched in parallel.
- After the first chart is up, the other timeframes' candles are refreshed in the background, so
  switching timeframe shows candles immediately.
- The first launch still downloads Binance's daily archive files for the 1D view once (about 170
  small files); they are kept afterwards.

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
  `run.js` exercises touch pan, pinch zoom and long-press, and saves screenshots. `replay.js`
  replays a message transcript recorded by the end-to-end tests (`app/build/e2e/*.json`):
  ```sh
  NODE_PATH=$(npm root -g) node tools/web-test/run.js out/
  NODE_PATH=$(npm root -g) node tools/web-test/replay.js app/build/e2e/dailyChartLoadsAllPanesAndGoesLive.json out/daily.png
  ```

## Layout

```
app/src/main/assets/chart/   chart page: index.html, app.js, TradingView Lightweight Charts v5.2.1
app/src/main/kotlin/.../
  MainActivity.kt            WebView host and JavaScript bridge
  ChartController.kt         loading, lazy history, live polling, messages to the page
  data/                      history loaders per exchange, OI and funding aggregation
  net/                       exchange API clients, Binance archive, price stream
  model/                     timeframes, bars, exchanges
tools/                       toolchain setup, browser tests
```

Charts are drawn with [TradingView Lightweight Charts™](https://www.tradingview.com/lightweight-charts/)
(Apache 2.0, © TradingView, Inc., https://www.tradingview.com/). This app is not affiliated with
Velo, TradingView or any exchange.
