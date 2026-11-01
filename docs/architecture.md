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
