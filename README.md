# SMS Bridge

A privacy-first SMS manager for Android: a local, on-device archive of your text messages,
with backup/restore, device-to-device transfer and optional end-to-end-encrypted sync to a
server you host yourself.

**Status: Phases 1–7 done; Phase 8 (multi-device sync polish) next.** See [docs/PROGRESS.md](docs/PROGRESS.md).

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
- **Self-hosted server + end-to-end encrypted sync:** accounts, device pairing and approval with safety codes, recovery key, signed encrypted uploads (the server cannot read messages);
  revocation, Docker Compose stack with an HTTPS proxy and encrypted database backups.
- **Web dashboard (read-only):** sign in with optional two-factor (TOTP), unlock with the recovery
  key, and read inbox, sent and conversations, decrypted only in the browser tab. Device pairing and
  revocation, sync status.

## Repository layout

```
sms-bridge/
├── android/          Native Android app (Kotlin, Compose, Hilt, Room, WorkManager)
├── backend/          Self-hosted server (Kotlin, Ktor, PostgreSQL, Flyway)
├── web/              Web dashboard (Next.js static export, TypeScript, Tailwind)
├── infrastructure/   Docker Compose, Caddy, backup script
├── docs/             Architecture, security, development, roadmap, progress
├── testdata/         Cross-language crypto vectors (made by the app, checked by the web tests)
├── tools/            Synthetic test-data generator
└── .github/          CI (Android, backend integration tests, web tests + build)
```

`shared/` is added in the phase that builds it (see [docs/roadmap.md](docs/roadmap.md));
empty placeholder folders are deliberately not committed. Deployment: [docs/deployment.md](docs/deployment.md).

## Quick start

```bash
cd android
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Full setup: [docs/development.md](docs/development.md).
Design: [docs/architecture.md](docs/architecture.md). Threat model: [docs/security.md](docs/security.md).
