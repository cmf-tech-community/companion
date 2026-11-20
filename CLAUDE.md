# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Phone companion for the CMF Watch Pro 2 and the fmc.tools watch face store: `ios/` (Swift/SwiftUI) and `android/`
(Kotlin/Compose), same scope, bundle id `tools.fmc.companion`. Topic docs are listed in [AGENTS.md](AGENTS.md):
read `docs/apps.md` before touching the Apps tab or RPC, `docs/miniapp-rpc.md` before changing an RPC method,
`docs/firmware-ota.md` before touching the firmware update.

## Commands

```bash
make test                                   # WireKit (swift test) + Android unit tests
swift test --package-path ios/WireKit --filter OtaTests          # one iOS test class
cd android && ./gradlew testDebugUnitTest --tests '*.wire.OtaTest' # one Android test class
make ios-sim       # build, install, launch on the "iPhone 17e" simulator (SIM=… to override)
make ios-device    # needs TEAM (and DEVICE) in git-ignored local.mk
make ios-testflight  # needs ASC_KEY_ID / ASC_ISSUER / ASC_KEY_PATH in local.mk; build number is the time
make android       # installDebug + launch; JDK 17, ANDROID_HOME, a device/emulator in adb
```

- `ios/project.yml` is the source of the Xcode project (`xcodegen generate`); the generated
  `ios/FMCCompanion.xcodeproj` is committed, so regenerate and commit it after adding files or changing settings.
- Android debug builds take intent extras: `adb shell am start -n tools.fmc.companion/.MainActivity --ei tab 1 --ez check true`.
- Pushing to `main` with changes under `android/` makes CI publish a signed APK as a GitHub release
  (`.github/workflows/android-release.yml`); the in-app updater (`Update.kt`) reads those releases.

## Architecture

**Two ports of one transport, held together by shared test vectors.** The watch protocol lives in `ios/WireKit`
(Swift package) and is ported to `android/app/src/main/java/tools/fmc/companion/wire` file for file (Frame, Session,
Channel/K key, Install, Loader, Face, Stock, Ota, RPC). Both test suites read the same JSON vectors in place from
`ios/WireKit/Tests/WireKitTests/` (Android via `wire/Vectors.kt`). A protocol change means: update the vector,
then make both ports pass. Some vectors are generated, not hand-written:
- `vectors.json`: from the Python host of cmf-watch-sdk.
- `watchface-vectors.json`, `ota-vectors.json`, `face-fit-vectors.json` (Watch 3 Pro memory budget, `fitForWatch`,
  ETC2): from the fmc.tools frontend's own code via `tools/*.ts` (bun,
  `FMC_FRONTEND` defaults to `~/Developer/pet/fcm-group/fmc_frontend`); the header of each script has the command.
- `rpc-vectors.json`: the miniapp RPC; new methods go into `docs/miniapp-rpc.md` first.

**Connection model.** The app does not pair. It rides the BLE link Nothing X already holds and opens a session with
the channel key K (cmf-watch-sdk `docs/abi-1.4.md`). Several sessions share that link (RPC listener, watch check),
and replies carry no request id, so requests go one at a time (`KLock` on iOS). Watch face installs need Nothing X
closed. Android's BLE path is not yet tested on a real watch.

**App layer.** Per platform, roughly parallel files: catalog/store (`Catalog*`), account (`Account`), face install,
firmware OTA (`Firmware`), watch apps + RPC services (`Apps`, `RPCListener`/`RpcService`,
`PhoneServices`/`Services`, `Permissions`/`Grants`), BLE link (`BLELink`/`BleLink`), watch state
(`WatchModel`/`Watch.kt`). Android puts most UI in `Screens.kt`. A feature added on one platform is expected on the
other.
