# SMS Bridge

A privacy-first SMS manager for Android: a local, on-device archive of your text messages,
with backup/restore, device-to-device transfer and optional end-to-end-encrypted sync to a
server you host yourself.

**Status: Phase 1 (Android foundation).** See [docs/PROGRESS.md](docs/PROGRESS.md).

What works today:

- Reads every SMS already on the phone (with `READ_SMS`) into a private Room database,
  streaming in 500-row pages so 50,000-message phones are fine.
- Captures new incoming SMS from the system broadcast (with `RECEIVE_SMS`), including multipart messages.
- Picks up outgoing messages sent from your normal messaging app through a provider observer
  plus periodic reconciliation (best effort; see limitations).
- Deduplicates across all capture paths and never deletes archived messages.
- Queues an immutable sync event per change in a local outbox (WorkManager). Nothing is
  uploaded yet: there is no server until Phase 5/6.
- Dashboard with real counts, recent conversations, sync status and a pause switch; a paged
  message list with incoming/outgoing filters.

## Repository layout

```
sms-bridge/
├── android/          Native Android app (Kotlin, Compose, Hilt, Room, WorkManager)
├── docs/             Architecture, security, development, roadmap, progress
└── .github/          CI (Android build + unit tests)
```

`backend/`, `web/`, `shared/` and `infrastructure/` are added in the phases that build them
(see [docs/roadmap.md](docs/roadmap.md)); empty placeholder folders are deliberately not committed.

## Quick start

```bash
cd android
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Full setup: [docs/development.md](docs/development.md).
Design: [docs/architecture.md](docs/architecture.md). Threat model: [docs/security.md](docs/security.md).
