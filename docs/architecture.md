# Architecture

## Overview

```
                ┌──────────────── Android device ────────────────┐
 SMS_RECEIVED ─▶│ SmsReceiver ──┐                                 │
                │               ▼                                 │
 content://sms ◀┤ SmsContentProvider ◀─ SmsReader ◀─ SmsRepository├──▶ Room (sms_bridge.db)
   (observer) ─▶│ SmsContentObserver ─▶ SyncScheduler ─▶ WorkManager│        │
                │                        │   SmsReconcileWorker   │        ▼
                │                        └── SmsSyncWorker ──────▶│ SyncTransport ──▶ (Phase 6) HTTPS
                │ Compose UI ◀── ViewModels ◀── Flows from Room   │
                └─────────────────────────────────────────────────┘
```

Phases 5 to 8 add a Ktor + PostgreSQL backend and a Next.js dashboard; see [roadmap.md](roadmap.md).
The server only ever stores ciphertext plus routing metadata.

## Android module structure

Phase 1 is a single `:app` Gradle module with package-level layering. Splitting into Gradle
modules costs build time and boilerplate. It will pay off when a second consumer exists
(for example `core:model` + `core:crypto` shared with backup tooling) or when build times grow.
The packages already follow the target module boundaries, so extraction will be mechanical:

| Package | Future module | Contents |
|---|---|---|
| `core.common` | `core:common` | `AppLog` (redacting logger), dispatchers, `TimeSource` |
| `core.model` | `core:model` | `SmsMessage`, `SmsType`, sync event types. Pure Kotlin |
| `core.database` | `core:database` | Room entities, DAOs, migrations |
| `data.sms` | `data:sms` | Provider access, mapping, fingerprints, receiver, observer, repository |
| `data.sync` | `data:sync` | Outbox, scheduler, workers, `SyncTransport` |
| `data.settings` | `data:settings` | Key/value settings, local device identity |
| `data.background` | `data:background` | Capture coordinator, boot receiver, receiver entry point |
| `feature.*` | `feature:*` | Dashboard, messages, settings, permission UI |
| `ui` | `core:designsystem` | Theme, navigation shell, shared components |

MVVM: screens observe `StateFlow`s exposed by Hilt ViewModels, which combine repository `Flow`s.
The repositories are the only code that writes to Room.

## Identity and deduplication

- Every archived message gets an app UUID (`sms_messages.id`). Provider `_id` and `thread_id`
  are stored for reconciliation only. They are per-device, change on restore and can be reused.
- `fingerprint` = SHA-256(direction, normalized address, timestamp in seconds, body). The timestamp
  is `date_sent` (PDU time, identical in broadcast and provider) or `date` when absent.
- `content_key` = SHA-256(direction, normalized address, body), for linking when a provider omits `date_sent`.

When a provider row is reconciled it is matched in this order:

1. same `provider_id` with the same `content_key` → update mutable state;
2. same `provider_id` but different content → the provider reused the id; the archived row is
   **unlinked, not overwritten**, and the new row is inserted;
3. an unlinked row with the same `fingerprint` (e.g. captured from the broadcast);
4. an unlinked row with the same `content_key` within ±2 minutes;
5. otherwise insert.

Merges keep the original timestamps, address and body. Only box/type, read/seen, status,
error code and missing `date_sent`/SIM are updated.

## Capture paths (Phase 1, backup-only mode)

| Path | Trigger | Latency | Guarantee |
|---|---|---|---|
| Incoming broadcast | `SMS_RECEIVED` (RECEIVE_SMS) | immediate | Delivered while the app is not force-stopped |
| Provider observer | `ContentObserver` on `content://sms` | ~5 s debounce | Only while the process is alive |
| Reconcile after broadcast | WorkManager, +30 s | ~30 s | Links the broadcast row to the provider row |
| Periodic reconcile | WorkManager, 1 h, battery not low | ≤ 1 h + Doze | Best effort; Android may defer |
| App start / resume | `SmsCaptureCoordinator` | immediate | When the user opens the app |

### Incoming SMS workflow

1. `SmsReceiver.onReceive` parses PDUs and joins multipart segments (`IncomingSmsAssembler`).
2. `goAsync()` plus an 8 s timeout: `SmsRepository.captureIncoming` writes the message and an
   `SMS_RECEIVED` outbox event in one transaction. No network I/O happens in the receiver.
3. `SyncScheduler.scheduleSync()` enqueues unique work with a CONNECTED network constraint and
   exponential backoff. A reconcile runs 30 s later to link the default app's provider row.
4. (Phase 6) `SmsSyncWorker` encrypts the payloads, uploads them, receives acks and marks them `SYNCED`.

If the capture write fails or times out, the message is still in the provider (the default SMS
app stores it) and the scheduled reconciliation archives it.

### Incremental scans

The checkpoint `telephony_provider_scan` stores the highest `_id` and `date` seen. An incremental
scan reads rows with `_id > checkpoint` (new rows, including restored rows with old dates) **or**
`date >= checkpoint.date - 7 days` (recent rows whose read/sent state may have changed).
Pages are keyset-paginated on `_id`, and each cursor is closed after 500 rows.

The first run imports everything without emitting sync events. The initial cloud snapshot is
a separate Phase 6 operation, not 50,000 queued events.

## Default SMS app mode (Phase 2)

Holding the SMS role changes who is responsible for what. The default app must store every
message it receives and sends in the Telephony provider (the system no longer does it for that
app) and is the only app that notifies the user.

| Component | Role requirement | What it does |
|---|---|---|
| `SmsDeliverReceiver` | `SMS_DELIVER` (`BROADCAST_SMS`) | provider inbox insert → archive link → notification → sync event |
| `MmsWapPushReceiver` | `WAP_PUSH_DELIVER` (`BROADCAST_WAP_PUSH`) | MMS not supported: alerts the user |
| `HeadlessSmsSendService` | `RESPOND_VIA_MESSAGE` | Quick replies to incoming calls |
| `MainActivity` `SENDTO` filter | compose from `sms:` links | Opens the composer prefilled |
| `SmsStatusReceiver` | (own PendingIntents) | Per-part sent results and delivery reports |

`SmsReceiver` (`SMS_RECEIVED`) returns early while SMS Bridge is the default, so each message is
handled exactly once. Fingerprint dedup still covers any overlap.

### Send pipeline

```
compose → SmsSender.send
  → provider outbox insert (type OUTBOX)   → archive row + SMS_SENT event
  → SmsManager.sendMultipartTextMessage(parts, sentIntents[part], deliveryIntent[last])
SmsStatusReceiver (per part)
  failure on any part → FAILED (+ error_code), never flipped back
  success on final part → SENT (+ date_sent)          → SMS_STATUS_CHANGED
  delivery report (TP-Status / 3GPP2 error class) → STATUS_COMPLETE/PENDING/FAILED → SMS_STATUS_CHANGED
```

Each part's PendingIntent is unique by data URI (`smsbridge://status/<providerId>/<part>`). The
delivery intent is mutable (telephony attaches the report PDU) and explicit.

### Conversations

Threads are grouped by `address_key` (schema v2), not by provider `thread_id`. The key is
device-independent and also covers archived rows that are no longer in the provider.

## Backup, export and restore (Phase 3)

All file access goes through the Storage Access Framework: the user picks every destination and
source. Export and restore run as one unique WorkManager job (`backup-restore`) with progress,
recorded in `backup_history` (RUNNING → COMPLETED / FAILED / INTERRUPTED).

### Backup package (`sms-bridge-backup-YYYY-MM-DD-HHmm.zip`)

| Entry | Content |
|---|---|
| `messages.xml` | `<smses count backup_set backup_date smsbridge_format><sms …/></smses>`, the layout used by common SMS backup tools, plus `seen`, `sb_id` (archive UUID), `sb_source_device` |
| `metadata.json` | backup id, time, app version, source device id/name, SDK, time zone, counts, date range |
| `manifest.json` | `format`, `formatVersion` (1), `databaseSchemaVersion`, `messageCount`, files with SHA-256 and size |
| `checksums.sha256` | `sha256sum -c` compatible lines for the three entries above |

- **Streaming:** the export pages the archive by SQLite `rowid` (1,000 per page) up to a `MAX(rowid)`
  snapshot, so messages arriving mid-export are neither half-included nor miscounted. Hashes
  are computed while writing.
- **Lossless XML:** newlines and tabs in attributes are written as character references (parsers
  would otherwise turn them into spaces); XML-illegal control characters become numeric references.
  The round trip is tested with Bangla, emoji, quotes and control characters.

### Encrypted backup (`.sbbackup`, Phase 4a)

```
"SBENC" | version=1 | kdf=1 (PBKDF2-HMAC-SHA256) | iterations u32 BE | saltLen | salt
Tink AesGcmHkdfStreaming( ikm = PBKDF2(passphrase, salt, iterations, 256 bit), AAD = header )
  └─ plaintext: the unchanged backup package zip (messages.xml, metadata.json, manifest.json, checksums.sha256)
```

Validation and restore run the Phase 3 code on the decrypted stream, so checksums and counts
are verified as before. `BackupRepository.probe()` reads only the header to decide whether to
ask for a passphrase.

### JSON and CSV

These are reading formats, not restore formats. CSV is UTF-8 with a BOM (so Excel shows Bangla)
and quoted per RFC 4180. Cells beginning with `= + - @` get a leading apostrophe against
spreadsheet formula injection.

### Validation (`inspectBackup`)

Reads the whole file once and changes nothing:
- Packages: every entry is hashed and compared with `checksums.sha256` and `manifest.json`, and
  the message count is checked.
- Plain XML from other tools: parsed and counted. `"null"` placeholders are understood; `<mms>`
  and unreadable rows are counted as skipped.

The user sees counts, date range, source device and checksum state before choosing anything.

### Restore (`RestoreEngine`)

Selective by direction and by date range. There are two targets:

| Target | Needs | Duplicate check | Notes |
|---|---|---|---|
| SMS Bridge archive | nothing | archive fingerprints | Keeps `sb_source_device`; queues `SMS_IMPORTED` events |
| Phone's SMS database | SMS role (guided temporary switch) | fingerprints of every provider row, plus within the file | `bulkInsert` in 250-row batches; Android assigns new `_id`/`thread_id`; `sub_id` not written; OUTBOX/QUEUED restored as FAILED so nothing is re-sent; then a reconcile pulls the rows into the archive |

Re-running an interrupted restore is safe (everything already present is skipped). An
interrupted export leaves a package without a manifest, which validation rejects.

## Wi-Fi transfer (Phase 4b)

```
Receiver (B)                                    Sender (A)
Keystore EC key + self-signed cert
SSLServerSocket(0)  ── QR: smsbridge://pair?v=1&h=IP&p=PORT&fp=SHA256(cert)&t=TOKEN ──▶ scan / paste
                    ◀────────── TLS (cert pinned to fp) ──────────
        HELLO {v, token, deviceId, deviceName}      ◀──
    ──▶ WELCOME {deviceName}                               (A: user confirms "Send N?")
        INIT {transferId, total, batchSize}         ◀──
 (B: user confirms "Accept N?")
    ──▶ READY {resumeFrom}
        BATCH {seq, sha256, payload}                ◀──    repeated, stop-and-wait
    ──▶ ACK {seq}   (after RestoreSession.apply + transfer_sessions.last_batch_sequence)
        DONE                                        ◀──
    ──▶ RESULT {read, restored, duplicates, draftsSkipped}
```

- **Frames:** `u32 length | UTF-8 JSON`, at most 8 MiB. `ERROR {code, reason}` aborts with a user-facing reason.
- **Batches:** fixed-size pages (200 by default) of a `rowid` snapshot of the sender's archive, so
  batch *n* is identical on every attempt.
- **Resume:** the receiver persists `last_batch_sequence` per `transferId`. A reconnect with the same
  id (the sender's "Resume" button) gets `READY {resumeFrom = last + 1}`. Re-sent, already committed
  batches are acknowledged without being applied again.
- **Shared rules:** the receiver applies batches through the same `RestoreSession` as file restore
  (archive or phone target, duplicate skipping, drafts never into the phone, OUTBOX restored as FAILED).
- **Process model:** `TransferManager` (singleton, application scope) owns both sides, so leaving the
  screen does not stop a transfer. The screen is kept on while active. No foreground service.
- **Code:** the engines (`TransferServer`, `TransferClient`) are plain Kotlin and tested end to end over
  localhost TLS with test identities (`app/src/test/resources/test-tls-*.p12`, test-only keys).

## Local database (Room, schema v2)

| Table | Purpose |
|---|---|
| `sms_messages` | Archive. Indexed on id, fingerprint, content_key, address, date, type, source_device_id, provider_id |
| `sync_operations` | Immutable event outbox: event UUID, type, unique device sequence, state, attempts |
| `sync_checkpoints` | Named scan/sync cursors |
| `devices` | Local device identity now; paired devices later |
| `backup_history` | Phase 3 export records |
| `transfer_sessions` | Phase 4 transfer state (resumable) |
| `app_settings` | Pause flag, initial-import flag, device sequence counter |

Schemas are exported to `android/app/schemas/`. Every version bump requires a `Migration` in
`SmsBridgeMigrations.ALL` and a case in `DatabaseMigrationTest`. Destructive fallback is never enabled.

## Sync contract (for Phase 6)

`SyncTransport.upload(events)` receives `PendingSyncEvent`s (ids, type, sequence and no content).
The implementation must:

- build the payload from the archive and encrypt it with the vault key (AES-256-GCM) before sending;
- send `eventId` as the idempotency key and `deviceSequence` for ordering;
- return `Acknowledged(ids)`, `RetryableFailure` (→ WorkManager backoff) or `PermanentFailure`.

Phase 1 binds `UnconfiguredSyncTransport`. The worker sees `isConfigured == false` and leaves
events queued, so no upload attempts and no retry loops happen.

Planned event wire format:

```json
{ "eventId": "uuid", "deviceId": "uuid", "eventType": "SMS_RECEIVED",
  "deviceSequence": 105, "createdAt": 1793500000000, "encryptedPayload": "BASE64" }
```

Deletion events will never propagate without explicit user authorization. One phone's messages
are never written into another phone's native inbox automatically. The unified cloud archive
and native restore are separate features.
