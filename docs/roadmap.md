# Roadmap

Phases run in order; each must pass its acceptance criteria before the next starts.

| Phase | Scope | Status |
|---|---|---|
| 1 | Android project, permissions, SMS reader, Room, incoming receiver, WorkManager queue, dashboard | **Done: acceptance verified on Galaxy A52 (Android 14)** |
| 2 | Default SMS handler (SMS_DELIVER, send UI, multipart send, delivery reports, RoleManager flow), two-way capture | Next |
| 3 | Backup package (manifest.json, messages.xml, metadata.json, checksums.sha256), JSON/CSV export, SAF, restore with temporary default-role guidance | |
| 4 | Encrypted backup archive, Wi-Fi transfer (NSD + TLS + QR pairing, resumable batches), then Wi-Fi Direct | |
| 5 | Ktor backend, PostgreSQL, auth, device registration, OpenAPI, Docker Compose | |
| 6 | Encrypted event upload, server sequence, acks, checkpoints, WebSocket notify | |
| 7 | Next.js dashboard, browser vault unlock, client-side decryption | |
| 8 | Multi-device sync and device management/revocation | |
| 9 | Security, compatibility (Android 8 to 15, OEMs), performance testing | |
| 10 | Signed private APK, VPS production deployment | |

## Phase 2 design notes

- Manifest additions required for the SMS role: `SMS_DELIVER` receiver (`BROADCAST_SMS`),
  `WAP_PUSH_DELIVER` receiver (`BROADCAST_WAP_PUSH`, MMS minimally acknowledged), a
  `RESPOND_VIA_MESSAGE` service, and a `SENDTO` activity for `sms:`/`smsto:` URIs.
- As default app, SMS Bridge must write incoming messages to the provider itself.
- `SEND_SMS` permission is added only with the send UI.
- Role request via `RoleManager.createRequestRoleIntent(ROLE_SMS)` on Q+, and
  `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` on API 26 to 28, always after an explanation screen.
