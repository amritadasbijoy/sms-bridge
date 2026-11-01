# Roadmap

Phases run in order; each must pass its acceptance criteria before the next starts.

| Phase | Scope | Status |
|---|---|---|
| 1 | Android project, permissions, SMS reader, Room, incoming receiver, WorkManager queue, dashboard | **Done: acceptance verified on Galaxy A52 (Android 14)** |
| 2 | Default SMS handler (SMS_DELIVER, send UI, multipart send, delivery reports, RoleManager flow), two-way capture | **Built and tested on JVM; on-device role test pending** |
| 3 | Backup package (manifest.json, messages.xml, metadata.json, checksums.sha256), JSON/CSV export, SAF, restore with temporary default-role guidance | |
| 4 | Encrypted backup archive, Wi-Fi transfer (NSD + TLS + QR pairing, resumable batches), then Wi-Fi Direct | |
| 5 | Ktor backend, PostgreSQL, auth, device registration, OpenAPI, Docker Compose | |
| 6 | Encrypted event upload, server sequence, acks, checkpoints, WebSocket notify | |
| 7 | Next.js dashboard, browser vault unlock, client-side decryption | |
| 8 | Multi-device sync and device management/revocation | |
| 9 | Security, compatibility (Android 8 to 15, OEMs), performance testing | |
| 10 | Signed private APK, VPS production deployment | |

## Deferred from Phase 2 (candidates for later phases)

- **MMS.** Downloading and parsing MMS needs either hidden `com.google.android.mms` APIs (not
  allowed) or a reimplementation of the PDU codec. Until then MMS is unsupported and the user is told.
- **SIM picker.** Sends use the system default SMS SIM, or the SIM the conversation last arrived
  on. A picker needs `READ_PHONE_STATE` to list subscriptions.
- **Contact names.** Needs `READ_CONTACTS`; addresses are shown as numbers.
- **Deletion and blocking** in the messaging UI. Deliberately absent (no silent deletes).
- **Group/broadcast SMS.** The composer sends one message per recipient.
