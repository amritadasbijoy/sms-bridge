# Development

## Toolchain (verified 2026-11-01 on the primary dev Mac)

| Tool | Version | Notes |
|---|---|---|
| macOS | 27.2, arm64 | |
| JDK | Amazon Corretto 17.0.14 | Required by AGP 8.3 |
| Android SDK | platform 34, build-tools 34.0.0, platform-tools | `~/Library/Android/sdk` |
| SDK command-line tools | 23.0 (`cmdline-tools/latest`) | Installed 2026-11-01; `sdkmanager` now wraps the "Android CLI" |
| Emulator | 37.2.13 | Updated 2026-11-01 as a dependency of the system image |
| System image | `system-images;android-34;google_apis;arm64-v8a` r14 | Earlier images in this SDK were empty stubs (no `.img`) |
| Android Studio | not installed | Optional; the CLI build works without it |
| Gradle | 8.4 via wrapper | `android/gradlew` |
| Git | 2.54 | |
| Node.js | 26.13 | For `web/` from Phase 7 |
| Docker | 29.9 | For `infrastructure/` from Phase 5 |

Library versions live in `android/gradle/libs.versions.toml`. They are pinned to the newest
releases that support `compileSdk 34` with AGP 8.3 / Gradle 8.4, the SDK installed here. Lint
reports newer versions. Upgrading needs `platforms;android-35`+ installed and an AGP/Gradle bump,
so it is planned as its own task rather than done ad hoc.

## Build and test

```bash
cd android
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # JVM + Robolectric tests, no device needed
./gradlew lintDebug
./gradlew connectedDebugAndroidTest  # needs a device/emulator (migration test)
```

`local.properties` (git-ignored) must contain `sdk.dir=/path/to/Android/sdk`.

## Server (backend/)

```bash
cd backend
./gradlew test            # integration tests: needs Docker running (Testcontainers starts PostgreSQL 16)
./gradlew installDist     # build/install/sms-bridge-server, used by the Docker image
cd ../infrastructure
cp .env.example .env      # set POSTGRES_PASSWORD; SMSBRIDGE_COOKIE_SECURE=false for local http
docker compose up -d --build && curl -s http://127.0.0.1:8080/health
docker compose down       # stops; data stays in the db-data volume
```

## Web dashboard (web/)

```bash
cd web
npm ci
npm test                  # vitest: crypto against ../testdata vectors, message merging
npm run typecheck
npm run build             # static export to out/
npm run dev               # http://localhost:3000, /api forwarded to the local stack on :8080
```

The Compose stack serves the built dashboard at http://127.0.0.1:8081 (`web` service: Caddy with the
static files, `/api` proxied, so cookies behave as in production). Rebuild after changes:
`docker compose up -d --build web`. The API image copies `backend/build/install`, so run
`./gradlew installDist` before rebuilding `api`.

`testdata/sync-vectors-v1.json` is written by the app's `CrossLanguageVectorsTest` (real
`CloudCrypto`) and must decrypt in `web/test/vectors.test.ts`. Delete the file and rerun the Android
test to regenerate it. Synthetic content only.

Docker Engine 29 rejects API versions below 1.40, so the test task sets `-Dapi.version=1.44` for
Testcontainers' Docker client.

## Emulator workflow (preferred for testing)

Test on emulators with synthetic messages. Real phones in this household carry real messages;
use them only for read-only checks, with the owner's consent each time.

Two dedicated AVDs exist: `SmsBridge_A` and `SmsBridge_B` (Android 14 Google APIs arm64, no Play
Store, 2 GB RAM, 4 GB data). `SmsBridge_B` is for Phase 4 device-to-device transfer.

```bash
EMU=~/Library/Android/sdk/emulator/emulator
ADB=~/Library/Android/sdk/platform-tools/adb
$EMU -avd SmsBridge_A -port 5554 -no-boot-anim -no-audio &      # add -wipe-data for a clean device
$ADB -s emulator-5554 install -r android/app/build/outputs/apk/debug/app-debug.apk
```

Seed synthetic data (deterministic; 5,000 messages with Bangla, emoji, multipart, both SIMs,
drafts/failed, number-format variants, one duplicate, one MMS and one invalid row):

```bash
python3 tools/synthetic_sms.py 5000 /tmp/synthetic-sms-5000.xml
$ADB -s emulator-5554 push /tmp/synthetic-sms-5000.xml /sdcard/Download/
$ADB -s emulator-5554 shell content call --uri content://media --method scan_volume --arg external_primary
```

Then in SMS Bridge, go to Backup → Choose backup file → restore into "This phone's messages" (needs the SMS role).
On the emulator, shortcuts are fine (never do these on a real phone):

```bash
$ADB -s emulator-5554 shell pm grant app.smsbridge.debug android.permission.READ_SMS
$ADB -s emulator-5554 shell pm grant app.smsbridge.debug android.permission.RECEIVE_SMS
$ADB -s emulator-5554 shell pm grant app.smsbridge.debug android.permission.POST_NOTIFICATIONS
$ADB -s emulator-5554 shell cmd role add-role-holder android.app.role.SMS app.smsbridge.debug
$ADB -s emulator-5554 emu sms send 15550104242 "Synthetic incoming"    # simulated incoming SMS
$ADB -s emulator-5554 shell run-as app.smsbridge.debug sqlite3 databases/sms_bridge.db "select type,count(*) from sms_messages group by 1;"
```

Note: `content query --uri content://sms` from the adb shell does not show draft and failed rows
to a caller that is not the default SMS app. Count those through the app's archive instead.

### Testing Wi-Fi transfer between the two emulators

Emulators sit behind separate virtual routers and cannot reach each other directly (and mDNS
does not cross them). Instead, forward a Mac port to the receiver and point the sender at the host:

1. Boot both (`SmsBridge_A` on 5554, `SmsBridge_B` on 5556) and install the APK on each.
2. On B, go to Transfer → Start receiving, and note the port shown ("Listening on 10.0.2.x:PORT").
3. `adb -s emulator-5556 emu redir add tcp:47001:PORT`
4. Get B's pairing link (decode the QR from a screenshot, or Copy pairing link). Then change
   `h=…&p=PORT` to `h=10.0.2.2&p=47001` and paste it on A under Transfer → "paste the pairing link".
5. Confirm on A ("Send"), then on B ("Accept").

On real phones on the same Wi-Fi, scanning the QR code is all that is needed.

## Installing on a phone

1. On the phone, enable Developer options → USB debugging. Connect over USB and accept the prompt.
2. `~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Open **SMS Bridge (debug)** (`app.smsbridge.debug`) and follow the permission cards.

Installing with `adb` avoids Android 13+'s "restricted settings" block on SMS permissions. If you
install the APK from a file manager or browser instead and the permission is greyed out: App info
→ ⋮ → *Allow restricted settings*, then grant SMS.

### Manual acceptance checks (physical device)

- [ ] Deny "Read existing messages" → dashboard shows the card, the app does not crash, Scan is disabled.
- [ ] Deny twice → card offers "Open app settings".
- [ ] Grant → import progress appears, then real counts and recent conversations.
- [ ] Send an SMS to the phone → it appears within seconds; Sync card shows "Pending, 1 change".
- [ ] Send an SMS from the stock messaging app → it appears after the observer debounce (app open) or the next reconcile.
- [ ] Toggle "Pause synchronization" → Sync card shows Paused.
- [ ] `adb logcat | grep SmsBridge` → counts and states only, never text or numbers.
- [ ] Reboot → periodic reconcile still scheduled (`adb shell dumpsys jobscheduler | grep smsbridge`).

## Release signing and publishing updates

The release key lives outside the repository in `~/.smsbridge/` (`release.jks` and
`keystore.properties`, both mode 600; certificate SHA-256 `40:BA:72:69:…:61:95`). **Back it up**
(e.g. an encrypted USB drive or password manager): updates must be signed with the same key, or
phones refuse them. Release builds are signed only when that file exists.

Publish an update (I run this for the owner):

```bash
# raise versionCode / versionName in android/app/build.gradle.kts, then:
tools/publish-release.sh vps "What's new" [--required]
```

It builds and signs the release, refuses anything not signed with the release key, uploads the APK
to the VPS, and announces it with the server's admin token (read on the server, never printed).
Every phone is notified at once; the app downloads, checks the SHA-256 and installs. On Android 12+
the first update asks to allow installs and to confirm; later updates install without a prompt.

`tools/publish-release.sh local …` does the same against the local stack with the test-only
`releaseTest` variant (release key and minification, but allowed to reach the local HTTP server).

## Git workflow

- `main`: released states only. `develop`: integration. `feature/<name>`: one logical feature each.
- Commit after each green milestone (build + tests pass).
- Never commit secrets, real SMS data, or signing material. Tests use synthetic data from
  `app/src/test/.../testing/Synthetic.kt` (555-01xx fiction numbers).
