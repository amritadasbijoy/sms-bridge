# Development

## Toolchain (verified 2026-11-01 on the primary dev Mac)

| Tool | Version | Notes |
|---|---|---|
| macOS | 27.2, arm64 | |
| JDK | Amazon Corretto 17.0.14 | Required by AGP 8.3 |
| Android SDK | platform 34, build-tools 34.0.0, platform-tools | `~/Library/Android/sdk` |
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

## Release signing

Release builds are minified and unsigned in the repo. Signing keys and passwords must never be
committed (`*.jks`, `*.keystore`, `keystore.properties` are git-ignored). Phase 10 adds a signing
config that reads from environment variables.

## Git workflow

- `main`: released states only. `develop`: integration. `feature/<name>`: one logical feature each.
- Commit after each green milestone (build + tests pass).
- Never commit secrets, real SMS data, or signing material. Tests use synthetic data from
  `app/src/test/.../testing/Synthetic.kt` (555-01xx fiction numbers).
