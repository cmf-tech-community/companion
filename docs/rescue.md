# Rescue a stuck watch

Watch tab › Rescue a stuck watch, on iOS and Android, works without a connected watch. It is for a watch that
restarts in a loop after a heavy face (BZ Rotator on a Watch 3 Pro: the face's images overflow the heap, the
firmware crashes, the watchdog reboots it, and the next boot loads the same face). The phone waits for the watch
without pause and, on every boot, sends one command the moment the stock session is up, until it lands. It is the
fmc.tools `/rescue` page (fmc_frontend `rescue.model.ts`, `ble.ts` `connect({ rescue: true })`, `authenticate`,
`factoryReset`) made to keep trying.

## What it needs

A key this app already holds for the watch: the stock authkey (`stock-<id>`, saved by a face install or a read of the
faces), else the pairing secret (`k-<id>`, saved by any read of the watch). With the authkey it authenticates as the
site does. If the watch refuses it (Nothing X bound again since), it binds again with the secret: `0x8047`, then
`0x8049` twice, since the second is answered without a tap on the watch (cmf-watch-sdk `docs/recon.md`, "Bind popup…").
The new authkey replaces the saved one.

Without either key the screen says so and offers nothing. That's on purpose. A stuck watch can't show the bind popup,
and the app doesn't read the secret off a watch it never knew: the screen only reaches watches this phone already holds
keys for (iOS: the CoreBluetooth identifier the keys are saved under; Android: the address whose hash names them).

## The command

- Factory reset: `0x009A/0x0001` with the marker byte, sealed with the session key; the watch doesn't answer it.
- Remove custom faces, offered when the app has the watch's last dial list: the dial-list set `0x9055`
  (`StockSession.setDials`) with only the stock dials of that list (`rescueKeep`), the first one shown. It is one
  command, as fast as the reset, and keeps the user's data. It's untried on a stuck watch. Deleting a face the
  `wf_upload new_id=0` way takes a whole transfer and is not offered.

## Speed

`RescuePlan` (WireKit `Rescue.swift`, Android `wire/Rescue.kt`) is pure: it seals every frame that doesn't depend on
the watch's nonce before the watch appears, and answers each notification with the next writes. The command goes
right behind the confirm (`0x804D`), without waiting for `0x0004`, and again once `0x0004` comes. It is checked
against `rescue-vectors.json`, made by `tools/rescue-vectors.ts` from the site's own `Watch.authenticate`,
`Watch.pair` and `Watch.factoryReset`.

- iOS (`ios/App/Rescue.swift`): CoreBluetooth on its own queue; a pending `connect` to each known peripheral (no
  timeout, so the controller connects on the next advertisement) and a duplicate-reporting scan that connects again
  on any advertisement seen while disconnected. Only service FFF0 and FFF1/FFF2 are discovered; iOS exchanges the MTU
  itself.
- Android (`Rescue.kt`): `connectGatt(autoConnect = true)` to each bonded known watch, and a `SCAN_MODE_LOW_LATENCY`
  scan that opens a direct connection on the first advertisement. It does ask for the MTU. The frames are 27 bytes,
  and an Android before 14 stays at 23 otherwise (14 and later have already exchanged it, so the call costs nothing).
  Bonded services come from the cache.
- Both re-arm at once after a drop, keep the screen on, and log every step with ms since the connect (Copy log, and
  the Developer log).

## What is known about the window

The emulator (cmf-watch-utils, stock 1.0.1.77 with BZ Rotator) has BLE up from ~4.1 s to the crash at
~4.3–4.5 s of guest time per boot. A real stuck Watch 3 Pro, in the site's rescue page in desktop Chrome, stayed
listed for ~27 s; `gatt.connect()` took 4.1 s and discovery 0.9 s. So on hardware the window is seconds long, and the
connection is the slow part, not the auth.
