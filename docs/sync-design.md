# Phase 6 design: encrypted sync and key management

**Status: approved 2026-11-01** (all three decisions as recommended: the model as proposed, the event type encrypted, a generated recovery key).

## Goals and non-goals

- The server stores and relays sync events but **can never read messages**. That rules out
  plaintext, unwrapped keys, and anything it could use to obtain them.
- Phones keep working fully offline; sync is opt-in per phone and pausable.
- New phones and (Phase 7) browsers get access only through explicit approval on a device the
  user already trusts, or through a recovery key the user wrote down.
- Not in Phase 6: browser decryption (Phase 7), downloading other phones' events (Phase 8), and
  writing one phone's messages into another phone's native inbox (never automatic).

## Keys

| Key | What | Where it lives |
|---|---|---|
| **Vault key (VK)** | Random 256-bit AES key per account and key version. Encrypts every event payload. | On each trusted device: in a Tink keyset encrypted by an Android Keystore master key (non-exportable). On the server: only *wrapped* copies (below). |
| **Device encryption key** | X25519 key pair per device (or browser) | Private key: Keystore-protected Tink keyset. Public key: registered with the server. |
| **Device signing key** | Ed25519 key pair per device | Same. The public key is registered; the server and other devices verify event signatures with it. |
| **Recovery key** | 128-bit random value, shown once as 26 characters in groups (`K7Q2-…`), for the user to write down | Only on paper (the user's). The server holds VK wrapped with a key derived from it. |

Why software keys inside a Keystore-encrypted keyset instead of keys generated in Keystore:
Android Keystore supports X25519/Ed25519 only from Android 12/13, and our minimum is Android 8.
This is the standard Tink `AndroidKeysetManager` pattern, so raw private keys are never stored
unencrypted.

**Wrapping** (giving VK to someone) uses **HPKE (RFC 9180)** from Tink:
`DHKEM(X25519, HKDF-SHA256) + HKDF-SHA256 + AES-256-GCM`. It is a published, reviewed construction,
not a custom protocol. The server stores the resulting blobs in a new table
`vault_keys(user_id, key_version, recipient, wrapped_key)`.

## Bringing devices into the vault

```
First phone that enables sync (account has no vault yet):
  1. generate VK v1 and device keys; register with a pairing code (Phase 5 flow, plus public keys)
  2. generate a recovery key → show it once → user confirms they wrote it down
  3. upload VK v1 wrapped for the recovery key (HKDF(recoveryKey) → AES-256-GCM)

Any later phone or browser:
  1. register with a pairing code (public keys included) → state "waiting for approval"
  2. it shows a 6-digit SAFETY CODE = derived from SHA-256(its public keys)
  3. a trusted device (or browser) gets the request, shows the new device name and its own
     computed safety code; the user checks the two codes match and taps Approve
  4. the trusted device HPKE-wraps every VK version for the new device's public key and uploads it
  5. the new device downloads the wrapped keys and unwraps them locally
  Alternative to 3–4: type the recovery key on the new device.
```

The safety code is what stops a malicious server from substituting its own public key: it could
swap the key, but it cannot make the codes on the two screens match.

## Events

Unchanged from Phase 1 locally: `sync_operations` is the outbox (event UUID, per-device sequence).
On upload each event becomes:

```json
{ "eventId": "uuid", "deviceSequence": 105, "keyVersion": 1,
  "ciphertext": "base64( nonce(12) | AES-256-GCM(VK_v, payload, AAD) )",
  "signature":  "base64( Ed25519(deviceKey, eventId|deviceId|deviceSequence|keyVersion|sha256(ciphertext)) )" }
```

- **payload** (encrypted): event type, message archive id, address, body, timestamps, box, read/seen,
  status, SIM, source device. It is built from the archive at upload time.
- **AAD** binds the ciphertext to `eventId | deviceId | deviceSequence | keyVersion`, so a payload
  cannot be moved to another event.
- **Server checks:** the device is registered and not revoked, the signature verifies with its
  registered Ed25519 key, and the batch fits size limits. It then stores the event idempotently
  (`event_id` primary key; `(device_id, device_sequence)` unique), assigns `server_sequence`, and acks.
- **Recommendation: the event type is encrypted too.** The server stores type `opaque`, so it
  cannot count received vs. sent messages or see when you text. It still sees per-device event
  counts, timing and sizes; that metadata is unavoidable for a relay.
- **First enable:** the whole archive is queued once as `SMS_IMPORTED` (e.g. 21k events, uploaded
  in batches of 500 in the background), then live events follow.

## Transport

- `POST /api/v1/sync/events` (device bearer token): a batch of up to 500 events / 4 MiB, returns
  `[{eventId, serverSequence}]`. Re-sending is harmless (idempotent).
- `GET /api/v1/sync/events?after=<seq>&limit=` (device or session): ciphertext only. It is used by
  the browser (Phase 7) and other phones (Phase 8).
- `GET /api/v1/sync/stream` WebSocket: content-free "new events up to seq N" notifications.
- Vault endpoints: `GET/POST /api/v1/vault/keys`, `GET /api/v1/vault/requests`,
  `POST /api/v1/vault/requests/{deviceId}/approve`.
- **Android:** the Phase 1 `SyncTransport` gets a real HTTPS implementation (Ktor client +
  kotlinx.serialization). The device token is stored encrypted with a Keystore-backed Tink AEAD.
  WorkManager keeps its network constraint and exponential backoff. Release builds require
  HTTPS; debug builds may use `http://10.0.2.2` (emulator → Mac) only.
- **App UI:** a new **Sync** tab with server address, pairing code, recovery key setup, an
  "approve new device" screen with the safety code, the visible states (Connected /
  Synchronizing / Pending / Offline / Error / Paused), and pause/resume.

## Revocation and key rotation

1. The user revokes a device (server: tokens dead, uploads refused; Phase 5 already does this).
2. A trusted device creates **VK v(n+1)**, wraps it for every remaining device and browser and for
   the recovery key, and uploads it. New events use v(n+1).
3. The revoked device keeps whatever it already downloaded and the old key versions (that cannot
   be undone), but gets nothing new: no new events, no new key.

Re-encrypting old events under the new key is optional and not planned (the revoked device saw them anyway).

## Limitations to accept

- If **every** device is lost **and** the recovery key is lost, the server copy cannot be decrypted.
  Local archives and backups on phones are unaffected.
- The server learns metadata: which devices, how many events, when, and sizes.
- Browsers (Phase 7) must trust the JavaScript the server sends. A compromised server could
  serve code that steals the vault key once the user unlocks it in that browser. This will be
  stated in the web UI.
- Real-time capture limits from Phases 1–2 still apply. Sync is best-effort, as WorkManager allows.

## Decisions needed

1. Approve the overall model: vault key, plus a recovery key on paper, plus new devices approved
   from a trusted device via a safety code.
2. Encrypt the event type (recommended) or keep it in plaintext as in the original spec example.
3. Recovery key: a **generated** high-entropy key (recommended: it cannot be brute-forced even
   though the server holds the wrapped copy) or a user-chosen passphrase (easier to remember, but
   a weak one could be cracked offline by whoever has the server database).
