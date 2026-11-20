# Weather: hourly forecast and watch app — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `weather` answers with the next hours on iOS and Android, and `apps/weather` shows now, hours and days on the watch with stock LVGL components.

**Architecture:** The spec and the shared JSON vectors change first, then the two ports of the transport (`RPC.swift`, `Rpc.kt`) and their OS hosts. In the apps repo the shared `companion-rpc` crate learns the new record, then the app is three modules: `model.rs` (pure, host-tested), `screen.rs` (LVGL batches), `main.rs` (loop and RPC).

**Tech Stack:** Swift (WireKit, WeatherKit), Kotlin (Open-Meteo over the app's `fetch`), Rust `no_std` (`cmf_wp2_app::lv`, `stock`, `companion-rpc`).

**Spec:** `docs/superpowers/specs/2026-11-20-weather-app-design.md`

## Global Constraints

- Order in companion: `docs/miniapp-rpc.md`, then `rpc-vectors.json`, then both ports pass.
- `weather` args: `days:u8 [‖ hours:u8]`; `hours` 0–24, absent = 0; more than 24 or more than two bytes: `BAD_REQUEST`.
- Hourly record: `2 ‖ hour:u8 ‖ n × (temp:i8 ‖ code:u8 ‖ precip:u8)`; `precip` bits 0–6 percent, bit 7 night; record order current, hourly, daily; no hourly data: the record is left out.
- No position-revealing data on the channel (no sunrise or sunset).
- The app: LVGL only, stock fonts and theme, English, °C, 192 objects, 2048 B per batch, 4 font slots, 8 KiB stack, no heap, no floats, storage blob ≤ 4096 B behind a magic and version.
- Nothing is merged or released: a branch and PR in companion, two PRs in apps. No push to `main`.
- A real watch only with the owner's explicit yes.

## Review Focus

1. The hour list crosses midnight (first hour 22): labels go 22, 23, 0, 1, never 24 or 25.
2. A temperature between −0.5 and 0 is written `0`, never `-0`.
3. A week where every day has the same min and max, or all days share one range: the range bar does not divide by zero and stays inside its track.
4. A stored blob from another version, or cut short: ignored, the app starts empty instead of showing garbage.
5. The phone sends fewer hours than asked, or an hourly record with no entries: the Hours page shows what came, or the "update" line, and does not index past the end.

---

## Part A — companion, branch `weather-hours` off `main`

### Task 1: Spec and vectors

**Files:** Modify `docs/miniapp-rpc.md` (`0x30 weather`, the job `spec` row and line on kind 1), `ios/WireKit/Tests/WireKitTests/rpc-vectors.json`.

- [ ] **Step 1:** Rewrite `0x30 weather` in the spec with the args, the three records and the rules from Global Constraints; say a job's kind-1 `spec` is one or two bytes.
- [ ] **Step 2:** Add to `weather` in the vectors: `{"in":{"kind":"hours","hour":13,"hours":[{"temp":-3,"code":3,"precip":20,"night":false},{"temp":-4.4,"code":71,"precip":100,"night":true}]},"hex":"020dfd0314fc47e4"}`.
- [ ] **Step 3:** Add a group `weather_args`: `{"args":"03","days":3,"hours":0}`, `{"args":"030c","days":3,"hours":12}`, `{"args":"0718","days":7,"hours":24}`, `{"args":"0319","bad":true}`, `{"args":"030c00","bad":true}`, `{"args":"","bad":true}`.
- [ ] **Step 4:** Add to `jobs` one job.set payload with kind 1 and spec `030c` (same shape as the existing entries), and one with spec `0319` marked bad.
- [ ] **Step 5:** Commit.

### Task 2: iOS port

**Files:** Modify `ios/WireKit/Sources/WireKit/RPC.swift`, `ios/WireKit/Tests/WireKitTests/RPCTests.swift`.

**Interfaces — produces:**
- `public struct WeatherHour { var temp: Double, code: Int, precip: Int, night: Bool }`
- `public struct WeatherHours { var hour: Int; var hours: [WeatherHour] }`
- `public func weatherRecord(_ w: WeatherHours, limit: Int) -> Data?` — nil when `limit` or the list is empty; at most `min(limit, 24)` entries.
- `public func weatherArgs(_ a: Data) -> (days: Int, hours: Int)?` — nil for the bad cases.
- `RPCHost.weather()` returns `(now: WeatherNow, hours: WeatherHours?, days: [WeatherDay])`.

- [ ] **Step 1:** Tests: the `hours` vector through `weatherRecord(_:limit:)` with `limit` 24; every `weather_args` row through `weatherArgs`; `ask` on the fake host with args `030c` gives current, one hourly record of 12 entries, three days in that order, and `0319` gives `BAD_REQUEST`; the new `jobs` rows through `parseJob`. Run `swift test --package-path ios/WireKit --filter RPCTests`: they fail to compile.
- [ ] **Step 2:** Implement; the dispatcher and `parseJob` (kind 1) both go through `weatherArgs`. Tests pass.
- [ ] **Step 3:** Commit.

### Task 3: Android port

**Files:** Modify `android/app/src/main/java/tools/fmc/companion/wire/Rpc.kt`, `android/app/src/test/java/tools/fmc/companion/wire/RpcTest.kt`.

**Interfaces — produces:**
- `class WeatherHour(val temp: Double, val code: Int, val precip: Int, val night: Boolean)`
- `fun weatherHours(hour: Int, hours: List<WeatherHour>, limit: Int): ByteArray?`
- `fun weatherArgs(a: ByteArray): Pair<Int, Int>?`

- [ ] **Step 1:** The same tests as Task 2 Step 1, on the same vectors. Run `cd android && ./gradlew testDebugUnitTest --tests '*.wire.RpcTest'`: fail.
- [ ] **Step 2:** Implement; the cap lookup (`Method.WEATHER -> …`) and `parseJob` use `weatherArgs`. Tests pass.
- [ ] **Step 3:** Commit.

### Task 4: The hosts

**Files:** Modify `ios/App/PhoneServices.swift`, `android/app/src/main/java/tools/fmc/companion/Services.kt`, and wherever each platform words a job for the Apps tab.

- [ ] **Step 1:** iOS: add `.hourly` to the WeatherKit call and its cache; build `WeatherHours` from the forecast entries from the current hour on (`isDaylight` → `night`, `precipitationChance`).
- [ ] **Step 2:** Android: add `&hourly=temperature_2m,weather_code,precipitation_probability,is_day` to the URL; `weather(days, hours)` starts at the entry of the current local hour in `hourly.time`.
- [ ] **Step 3:** `make test`, then `make ios-sim` builds and `cd android && ./gradlew assembleDebug` builds.
- [ ] **Step 4:** Commit, push the branch, open the PR (what changed, what ran, that the hosts' live calls were only built).

## Part B — apps, branch `weather-hours` off `main`

### Task 5: The crate and docs

**Files:** Modify `crates/companion-rpc/src/lib.rs`, `crates/companion-rpc/tests/rpc-vectors.json` (copy from companion), `crates/companion-rpc/tests/vectors.rs`, `AGENTS.md`, `docs/companion.md`.

**Interfaces — produces:**
- `pub fn weather(id: u8, days: u8, hours: u8) -> Msg` (one arg byte when `hours` is 0)
- `Weather::Hours { hour: u8, n: u8, data: [u8; 72] }` from `Weather::decode`, with `fn hour(&self, i: usize) -> Option<Hour>` and `pub struct Hour { pub temp: i8, pub code: u8, pub precip: u8, pub night: bool }`
- `Job::Weather { days: u8, hours: u8 }`

- [ ] **Step 1:** Tests on the vectors (`hours`, `weather_args`); existing callers (`apps/calendar`, `apps/reminders`) still build. Fail, implement, pass: `make test`.
- [ ] **Step 2:** Docs: the method table row, and one line on old phones (`BAD_REQUEST` on Android, `OK` without hours on iOS).
- [ ] **Step 3:** Commit, push, PR.

## Part C — apps, branch `weather-app` on top of `weather-hours`

### Task 6: The model

**Files:** Create `apps/weather/` with `make new NAME=weather`; `src/model.rs`.

**Interfaces — produces:**
- `enum Icon { Clear, Partly, Cloud, Fog, Drizzle, Rain, Snow, Storm }` and `fn icon(code: u8) -> Icon` by the spec's WMO groups (0 clear; 1–2 partly; 3 cloud; 45, 48 fog; 51–57 drizzle; 61–67, 80–82 rain; 71–77, 85, 86 snow; 95–99 storm).
- `fn condition(code: u8) -> &'static str` ("Clear", "Partly cloudy", "Cloudy", "Fog", "Drizzle", "Rain", "Snow", "Storm").
- `struct State` holding place, current, up to 24 hours, up to 7 days, fetched-at; `fn apply(&mut self, rec: &[u8])`, `fn save(&self, out: &mut [u8]) -> usize`, `fn load(b: &[u8]) -> Option<State>`.
- `fn fmt_temp(t: i16 /* 0.1 °C */, out: &mut [u8]) -> &str`, `fn hour_label(first: u8, i: u8) -> u8`, `fn weekday(day0: u8, i: u8) -> &'static str`, `fn bar(min: i8, max: i8, lo: i8, hi: i8, width: i16) -> (i16, i16)` (x and length, length ≥ the bar's height).
- `fn widget(&self, title: &mut [u8], value: &mut [u8])` within 23 and 39 bytes.

- [ ] **Step 1:** Tests, including the five Review Focus cases: `hour_label(22, 3) == 1`; `fmt_temp(-4) == "0"`; `bar(3, 3, 3, 3, 100)` and `bar(-5, 1, -9, 1, 100)` inside `0..=100`; `load` of a blob with another version or cut short is `None`; `apply` of an hourly record with 0 or 5 entries leaves `hours()` 0 or 5 long. `make test APP=weather` fails.
- [ ] **Step 2:** Implement; pass.
- [ ] **Step 3:** Commit.

### Task 7: Screens, loop, listing

**Files:** `apps/weather/src/screen.rs`, `src/main.rs`, `icon.svg`, `store.toml`, `store.md`, `shots/`; README's app table.

- [ ] **Step 1:** `screen.rs`: the three pager pages, the page-next-to-the-face view, the status screens with the spec's exact lines, icons composed of `obj`s; a host test that builds every batch within the budgets, as `apps/reminders` does.
- [ ] **Step 2:** `main.rs`: `location` once, `weather(7, 12)`, the `BAD_REQUEST` fallback to `weather(7, 0)`, `ASK`/`DENIED`/`UNAVAILABLE`/no answer per AGENTS.md, storage, the widget card, `PAUSE`/`RESUME` on the page.
- [ ] **Step 3:** `make test APP=weather && make build APP=weather && make check`.
- [ ] **Step 4:** The emulator with a fake phone answering fixed data (the way `apps/reminders/README.md` describes): check `°`, take a screenshot of each page and state into `shots/`.
- [ ] **Step 5:** Store files, README row, commit, push, PR with what ran where and the screenshots.
