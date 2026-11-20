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

- Factory reset: `0x009A/0x0001` with the marker byte, sealed with the session key; the watch doesn't answer it. It
  needs a time (`0x8004`) first: the handler (`0x100c8d6c` in 1.0.1.77) acts only with its link flag set, and only the
  time handler sets it (`Updata_User_Data id:154, Cmd:1 linkFlag 0` and nothing else without it; found in the
  emulator). A clock more than 2 min off is applied on the second `0x8004` only, so the time goes twice. The site's
  `connect({ rescue: true })` skips `fetchInfo` and with it the time, so its reset is ignored the same way.
- Remove custom faces, offered when the app has the watch's last dial list: the dial-list set `0x9055`
  (`StockSession.setDials`) with only the stock dials of that list (`rescueKeep`), the first one shown. It is one
  command, as fast as the reset, and keeps the user's data. It's untried on a stuck watch. Deleting a face the
  `wf_upload new_id=0` way takes a whole transfer and is not offered.

## Speed

`RescuePlan` (WireKit `Rescue.swift`, Android `wire/Rescue.kt`) is pure: it seals every frame that doesn't depend on
the watch's nonce before the watch appears, and answers each notification with the next writes. The command goes
right behind the confirm (`0x804D`) with the time twice, without waiting for `0x0004`, and the time and the command
again once `0x0004` comes. Until the watch answers `authName` (or the bind request) it is written again every 50 ms:
the watch drops every write until its own MTU exchange (to 247, started by the watch) has brought the command service
up, ~200 ms after the connect in the emulator. It is checked against `rescue-vectors.json`, made by
`tools/rescue-vectors.ts` from the site's own `Watch.authenticate`, `Watch.pair`, `Watch.fetchInfo` (the time) and
`Watch.factoryReset`.

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

The emulator (cmf-watch-utils `origin/main`, stock 1.0.1.77 with BZ Rotator, a paired fixture, a Python central over
netsim, 2026-11-20), in guest ms:

| Step | Healthy watch | Boot loop, best case |
|---|---|---|
| advertising starts | 0 | 0 |
| LE connect | +200–300 (2nd advertising event) | +43 … +261 |
| command service up (watch's MTU exchange) | connect +202–218 | +245 |
| authName → `0x0049` | service +2–5 | +246 |
| nonce → `0x004C` | +2–12 | – |
| confirm → `0x0004` | +15–20 | – |
| reset received, pipelined | service +51 | – |
| `recover factory`, `MSG_REBOOT recover:1` | reset +100, +251–272 | – |
| wipe done, reboot | reset +0.65–2.9 s | – |
| MPU fault | – | +380 (+280 on the first boot), every one of ~45 boots |

So in the emulator the window between the command service and the fault is at most ~178 ms. Auth completed before the
fault once in ~45 boots, and that run had no time before the reset, so the loop went on. After the fault the
controller still takes a connection, but nothing answers over ATT until the watchdog (11 s). On a healthy guest the
pipelined reset (time included) wipes the bind and reboots. The dial-list set is acknowledged without a time
(`A055 00`, `WfmPara_Save count:6`), but whether it survives the reboot can't be seen there (`WfmPara_Load cfgOk:0` on
every boot, even on the untouched fixture). A reset sealed with the previous connection's session key is refused:
each connection gets a new nonce, so there's no zero-round-trip shot. The command also has to be followed by ~100 ms
(`recover factory`) to ~270 ms (`MSG_REBOOT`) of a live watch before the fault, so the emulator's loop leaves no room.

A real stuck Watch 3 Pro, in the site's rescue page in desktop Chrome, stayed listed for ~27 s; `gatt.connect()` took
4.1 s and discovery 0.9 s, so its host answered ATT for at least that long: hardware has much longer windows than the
emulator (the users' alarm and calendar reminder say the same). Whether one is long enough for auth, the time and the
reset is what the first field log will show.
