# Weather: an hourly forecast in the RPC and a watch app on it

2026-11-20. Two repositories: `fmctools/companion` (this one) and `fmctools/apps` (`~/Developer/pet/apps`).

## Intent

A Weather app for the CMF Watch Pro 2 that looks and reads like the one on an Apple Watch: the place, a large
temperature, the condition, today's high and low, the next hours, the next days with range bars. The owner wants the
hourly forecast in it, which the `weather` method does not carry today, and wants the watch's own (stock LVGL)
components wherever they can do the job, the text at the very least.

## Goals

- `weather` can answer with the next hours, on iOS and Android, without breaking apps that ask the old way.
- `apps/weather`: a menu app and a page next to the watch face, built from stock LVGL components and stock fonts,
  plus the text widget card.
- It is the first watch app on `location` and `weather`, so both get tried end to end.

## Non-goals

- A custom-drawn widget card. The stub draws the card from two strings; changing that is a stub change in
  cmf-watch-sdk and its own project.
- Background refresh with `job.set`. The apps repo pins an SDK from before stub 8 and `PUSH`; moving the pin is the
  `sdk-update` skill and a PR of its own. Until then the app asks while it is open or its page is shown.
- °F, several places, feels-like, UV, air quality, precipitation maps.

## Anti-goals

- Copying Apple's artwork or glyphs. The layout and the feel are the reference; every icon is our own.
- Text drawn by the app's own pixel font. If a screen needs something LVGL cannot draw, it goes without rather than
  switching the app to the canvas kit (an app is LVGL or canvas, never both: abi-1.2, "Lifetime").
- Any personal data on the channel. The request carries two counts; the answer carries weather and the locality name
  that `location` already sends.

## Constraints found

- A reply is one message per record, a record ≤ 121 bytes (`docs/miniapp-rpc.md`, "Messages").
- LVGL on the watch has `obj`, `label`, `btn`, `arc`, `bar`, `slider`, `roller` and the round scroll bar. No images, no
  canvas, no lines. Budgets: 192 objects, 2048 bytes per batch, 4 font slots; the largest stock font is `48M`, and the
  firmware caches 6 of its glyphs (app-guide, "Rules that cost time").
- LVGL apps do not run in the simulator; they are verified by host tests and in the emulator.
- The watch fonts are documented for ASCII and Cyrillic only. Whether they have `°` is not known.
- iOS takes weather from WeatherKit (`.current`, `.daily` today), Android from Open-Meteo; both cache 30 minutes.

## Approaches

1. **The app on today's `weather`** (current + 7 days). Small, no companion release. Rejected by the owner: no hours.
2. **Hours in the protocol first, then the app.** Chosen by the owner.
3. **The app fetches Open-Meteo itself through `http.request`.** No protocol change, but the JSON for 24 hours does
   not fit the 2048-byte body, the user would have to allow a host and the position would go over BLE at 0.01°.
   Dropped.

Within 2, the app is LVGL (the owner's wish for native components), not canvas.

## Part 1: the protocol (companion)

Order as CLAUDE.md says: the spec, then the vectors, then both ports.

`0x30 weather`, args `days:u8 [‖ hours:u8]`. `hours` is 0–24 and may be absent (= 0), so every existing caller and
every stored job keeps working. More than 24 or more than two bytes: `BAD_REQUEST`. A job's `spec` for kind 1 is
these same one or two bytes.

Records, in this order: current, then the hourly one if `hours` > 0, then the daily ones.

```
current: 0:u8 ‖ temp:i16 ‖ code:u8 ‖ wind:u8 ‖ humidity:u8 ‖ age:u8              (unchanged)
hourly:  2:u8 ‖ hour:u8 ‖ n × (temp:i8 ‖ code:u8 ‖ precip:u8)
daily:   1:u8 ‖ day:i8 ‖ min:i8 ‖ max:i8 ‖ code:u8 ‖ precip:u8                   (unchanged)
```

- One hourly record for all hours (24 hours are 74 bytes), not one per hour: one BLE message instead of 24, and
  current + hourly + 6 days still fit a job's 128-byte blob. *(My call.)*
- `hour` is the local hour (0–23) of the first entry, which is the hour that contains now. Entries are consecutive
  hours; `n` = `min(hours, what the source has)`, read from the record's length.
- `temp` in °C rounded, `code` WMO as elsewhere. `precip`: bits 0–6 the probability in %, bit 7 set when that hour
  is night (WeatherKit `isDaylight`, Open-Meteo `is_day`), so the app can draw a moon. *(My call. Sunrise and sunset
  times were dropped: day length and solar noon give away the position to about 0.1°, and `weather` promises the
  watch gets none.)*
- No hourly data from the source: the record is left out; the app shows what it has.

Work: `docs/miniapp-rpc.md`; `rpc-vectors.json` (`weather`: an hourly record, args with and without `hours`, the
`BAD_REQUEST` cases; `jobs`: a two-byte weather spec); `RPC.swift` and `Rpc.kt` with their tests; the hosts:
`PhoneServices.swift` adds `.hourly` to the WeatherKit call, `Services.kt` adds
`hourly=temperature_2m,weather_code,precipitation_probability,is_day` to the Open-Meteo URL; the
job description text on both ("Every 30 min: weather"). Then `make test`.

Old watch app with a new phone: unchanged bytes. New watch app with an old phone: Android today
answers `BAD_REQUEST` to two-byte args (`Rpc.kt`), iOS reads the first byte and answers `OK` without hours
(`RPC.swift`). The app handles both: on `BAD_REQUEST` it asks once more with `days` only, and with no hourly record it
shows the Hours page as "Update FMC Companion for the hourly forecast".

## Part 2: the shared crate and docs (apps)

`crates/companion-rpc`: `weather(id, days, hours)`, `Weather::Hours` in `decode`, `Job::Weather { days, hours }`, the
vectors file copied from the companion. `AGENTS.md` (method table), `docs/companion.md`, README's app table.

## Part 3: the app (apps/weather)

Surfaces: menu app, page (`PAGE`), widget card (`WIDGET`). Drawing: LVGL with `stock::font` / `stock::theme`.
English text. °C.

Three pages in the stock vertical pager (crown or swipe, page dots on the right):

```
        Now                       Hours                      Days
   ┌───────────┐             ┌───────────┐             ┌───────────┐
   │  Moscow   │             │ Now  ☁ -3°│             │Today ☁ -5 ▬▬▬━━ 1│
   │           │             │ 14   ☁ -3°│             │Sat   ❄ -7 ▬━━━▬ -1│
   │   -3°     │             │ 15   ❄ -4°│             │Sun   ☀ -9 ━━▬▬▬ -4│
   │  ☁ Cloudy │             │ 16   ❄ -4°│             │Mon   …            │
   │ H:1° L:-5°│             │ …         │             │ …                 │
   │ 5 m/s 88% │             │ (12 rows) │             │ (7 rows)          │
   └───────────┘             └───────────┘             └───────────┘
```

- **Now**: the locality from `location` (grey, small), the temperature in `48M`, a condition icon with its name,
  high and low from day 0, wind and humidity. A line "Updated 12 min ago" when the data is older than the phone's
  cache.
- **Hours**: a scrolling list of the next 12 hours: hour, icon, temperature, and the precipitation chance when it is
  30 % or more.
- **Days**: seven rows: weekday, icon, min, a range bar, max. The bar is a grey track with a coloured segment placed
  by the week's lowest and highest temperature, cold to warm by colour, as on the Apple Watch.
- **Icons** are composed of a few plain LVGL objects each (a yellow disc, a grey rounded cloud, short blue bars for
  rain, white dots for snow, a pale disc for the moon), about 1–4 objects per icon. Eight kinds, from the WMO code
  groups in the spec, with a night variant for clear and partly cloudy. If the 192-object budget does not hold all
  three pages, the builder builds a page when it is shown and deletes the one left (`DEL` frees at once).
- **Page next to the face**: the Now screen only, no pager. `PAUSE`/`RESUME` handled as the guide says; it asks again
  on `RESUME` when its copy is older than 30 minutes.
- **Widget card**: title = the locality, value = `-3° Cloudy` / `H 1° L -5°`, written when the values change.
- **States**: first start with no answer yet: "Open FMC Companion on your phone"; `ASK`: "Allow Weather in FMC
  Companion on your phone", asked again every ~10 s; `DENIED`: "Turn Weather on in FMC Companion"; `UNAVAILABLE` or no
  answer: the stored copy with its age, retry after 3 s and then less often (AGENTS.md's rules). `location` denied or
  unavailable: no place name, the weather still shows.
- **Storage**: the last answer in the 4096-byte blob behind a magic and version, so the app opens with data at once.
- **The degree sign**: tried in the emulator first. If the stock font has no `°`, temperatures are written `-3` with
  a small ring object after the number on the Now screen and bare numbers in the lists.

Code layout as in `apps/reminders`: `model.rs` (records to state, icon kind from a code and the night bit, bar
geometry, weekday names; no `Api`, covered by `make test APP=weather`), `screen.rs` (batches), `main.rs` (loop, RPC).

## Verification

- companion: `make test` (WireKit and Android unit tests on the shared vectors).
- apps: `make test APP=weather`, the crate's tests, `make build APP=weather && make check`.
- The emulator with a host that answers `location` and `weather` from fixed data, a screenshot of every page and
  state for the PR and for the store listing. A real watch only with the owner's explicit yes.
- The hosts' real calls (WeatherKit hourly, Open-Meteo hourly) are checked by running the companion once on a
  simulator or device and reading DevLog; they have no unit test today and get none here.

## Delivery

The work ends at branches and open PRs; nothing is merged or released by the builder. A push to `main` here with
changes under `android/` publishes a signed APK that the in-app updater offers to users, and a merge in apps
publishes the app in the store, so both are the owner's to do.

1. companion: one branch off `main`, protocol + both ports + hosts, as a PR. A new TestFlight build and an Android
   release are needed before the hours show on a real phone.
2. apps: one PR for the crate and docs, then one PR for `apps/weather` on top of it (the repo's "one app per PR").
   The app may reach the store before a companion with hours: it then shows everything but the Hours page.

## Left to the builder

The exact layout numbers on the round panel, font sizes below `48M`, icon shapes and colours, the bar's colour
stops, how pages share the object budget, retry timings within AGENTS.md's rules, the store text and the app icon
(the bundled style: a gradient circle with a white glyph).
