# SMS Bridge

A privacy-first SMS manager for Android: a local, on-device archive of your text messages,
with backup/restore, device-to-device transfer and optional end-to-end-encrypted sync to a
server you host yourself.

**Status: Phases 1–5 done; Phase 6 (encrypted sync) next.** See [docs/PROGRESS.md](docs/PROGRESS.md).

What works today:

- **Archive:** reads every SMS on the phone into a private local database (tested with 21k real
  and 50k synthetic messages), captures new incoming SMS, and picks up outgoing ones. Duplicates
  are merged and nothing is ever deleted.
- **Default SMS app (optional, opt-in):** receive, notify, send (multipart, delivery reports),
  conversations. MMS is not supported.
- **Backup & restore:** encrypted backups (AES-256-GCM, passphrase), plain packages with
  checksums, JSON/CSV export, validated selective restore into the archive or the phone. Files
  from other SMS backup tools are readable.
- **Phone-to-phone transfer** over your own Wi-Fi: QR pairing, pinned TLS, resumable.
- **Self-hosted server** (not yet connected to the app): accounts, sessions, device pairing and
  revocation, Docker Compose stack with an HTTPS proxy and encrypted database backups.

## Repository layout

```
sms-bridge/
├── android/          Native Android app (Kotlin, Compose, Hilt, Room, WorkManager)
├── backend/          Self-hosted server (Kotlin, Ktor, PostgreSQL, Flyway)
├── infrastructure/   Docker Compose, Caddy, backup script
├── docs/             Architecture, security, development, roadmap, progress
├── tools/            Synthetic test-data generator
└── .github/          CI (Android build + unit tests; backend integration tests)
```

`web/` and `shared/` are added in the phases that build them (see [docs/roadmap.md](docs/roadmap.md));
empty placeholder folders are deliberately not committed. Deployment: [docs/deployment.md](docs/deployment.md).

## Quick start

```bash
cd android
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Full setup: [docs/development.md](docs/development.md).
Design: [docs/architecture.md](docs/architecture.md). Threat model: [docs/security.md](docs/security.md).
