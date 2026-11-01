# Security and privacy

## Principles

1. Messages live on the user's devices. The server (later phases) only stores ciphertext.
2. Least privilege: permissions are requested one at a time, with an explanation, only on tap.
3. Nothing sensitive in logs, crash reports, analytics or Android cloud backup.
4. No silent destructive actions: no deletes, overwrites or default-app changes without the user.

## Phase 1 controls (implemented)

| Control | Where |
|---|---|
| `READ_SMS` / `RECEIVE_SMS` requested separately with plain-language explanations | `feature/permissions/SmsPermissionCard.kt` |
| Default SMS role never requested automatically (Phase 2 adds an explicit, explained opt-in) | `SmsPermissionManager` |
| SMS broadcast receiver protected by `android.permission.BROADCAST_SMS` | `AndroidManifest.xml` |
| `allowBackup=false`: archive is excluded from Google/device backup | `AndroidManifest.xml` |
| Single logging facade; exceptions reduced to class name; `toString()` of every SMS type redacted (tested) | `AppLog`, `RedactionTest` |
| Release builds strip `Log.v/d/i` | `proguard-rules.pro` |
| No `INTERNET` permission in Phases 1–4a. **Since Phase 4b `INTERNET` is present**, used only for local Wi-Fi TLS sockets between two paired phones (see Phase 4b below); there is still no server or other network call | `AndroidManifest.xml` |
| Archive never deletes rows; provider id reuse unlinks instead of overwriting (tested) | `SmsRepository` |
| No destructive Room migration fallback | `SmsBridgeDatabase` |
| Test fixtures are synthetic | `Synthetic.kt` |

Fingerprints are SHA-256 of content and stay on the device. Short messages could be
brute-forced from a bare hash, so they must never be sent to the server unencrypted.

## Phase 2 controls (default SMS app)

| Control | Where |
|---|---|
| Role is requested only from an explicit button, after an explanation dialog with an MMS warning, then the system dialog | `DefaultSmsAppCard` |
| SMS_DELIVER / WAP push receivers protected by `BROADCAST_SMS` / `BROADCAST_WAP_PUSH`; respond-via service by `SEND_RESPOND_VIA_MESSAGE` | `AndroidManifest.xml` |
| Status receiver not exported; PendingIntents explicit; only the delivery intent is mutable (required for the report PDU) | `SmsSender` |
| Notifications are `VISIBILITY_PRIVATE` with a public "New message" version: no sender or text on a secure lock screen | `MessageNotifier` |
| Provider writes refused when not default are caught and logged by exception class only | `AndroidSmsProviderWriter` |
| Sending is unavailable unless SMS Bridge is the default app | `SmsSender`, UI |
| `ComposeRequest.toString()` redacted (tested) | `ComposeRequest` |

## Phase 3 controls (backup and restore)

| Control | Where |
|---|---|
| Files only through user-picked SAF documents; no broad storage permission | `BackupScreen`, `BackupRepository` |
| Restore is preceded by full validation (checksums, manifest, counts) and an explicit confirmation | `inspectBackup`, `RestorePlanner` |
| Restores never delete or overwrite; duplicates skipped; pending outgoing restored as FAILED (never re-sent) | `RestoreEngine` |
| Restoring into the phone requires the SMS role through the same explained opt-in flow | `RestorePlanner` |
| CSV formula-injection guard | `SmsCsvWriter` |
| Error texts shown to the user are built from our own messages or exception class names only | `BackupWorkers` |

## Phase 4a: encrypted backups

Since Phase 4a, "Create encrypted backup" is the primary option. Unencrypted packages, JSON and
CSV remain available and are labelled as readable by anyone who gets the file.

| Aspect | Design |
|---|---|
| Cipher | Google Tink `AesGcmHkdfStreaming`: AES-256-GCM, HKDF-SHA256 per-file key and nonce prefix, 1 MiB authenticated segments. Detects modification, reordering and truncation. No custom crypto. |
| Key derivation | PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte random salt (built into Android). Iterations stored in the header, accepted range 100k–10M (no weak or denial-of-service headers). |
| Header binding | The whole header (`SBENC`, version, KDF id, iterations, salt) is Tink's associated data |
| Passphrase | Min 10 characters, entered twice, never stored or logged. The `CharArray` and `PBEKeySpec` are wiped after derivation. |
| Key lifetime | The derived key lives only in `EphemeralKeys` (memory). WorkManager jobs get an opaque id, because WorkManager persists inputs to disk. Keys are wiped when the job ends or the screen is left; process death loses them (the user re-enters the passphrase). |
| Wrong passphrase | Fails authentication on the first segment ("Wrong passphrase, or the file is damaged"); never produces garbage plaintext |

Known limitations:
- Compose `TextField` holds the passphrase as an immutable `String` while typing, which cannot be wiped.
- PBKDF2 is less resistant to GPU attacks than Argon2id. Use a long passphrase; Argon2id is a candidate once a reviewed Android implementation is adopted.
- There is no recovery: a forgotten passphrase means the backup cannot be opened. This is by design, and the UI says so.

## Phase 4b: Wi-Fi device-to-device transfer

Trust model: **physical proximity**. The receiver shows a QR code, and only a phone that scanned
it (or was handed its pairing link) can connect and send.

| Control | Design |
|---|---|
| Receiver identity | Fresh EC P-256 key generated **inside Android Keystore** per receive session, with a self-signed certificate valid for 2 days; deleted when the session ends |
| Server authentication | Sender pins the SHA-256 fingerprint of that certificate from the QR code (`PinnedTrustManager`, constant-time compare). No CAs, no host names; any other certificate fails the TLS handshake before data is sent |
| Sender authentication | 32-byte random one-time token in the QR code, checked in constant time inside TLS; 5 wrong tokens stop the session |
| Transport | TLS 1.3 (1.2 fallback) via the platform provider. No plaintext socket exists |
| Consent | Sender confirms "Send N messages to <device>?"; receiver confirms "<device> wants to send N messages"; declining ends the session |
| Integrity | Every batch carries a SHA-256 of its payload; batches must arrive in order; each is acknowledged only after it is committed |
| Data safety | Same duplicate rules as restore; nothing is deleted or overwritten; drafts are never written into the phone's SMS database |
| Limits | Frames above 8 MiB and batches above 1,000 messages are refused (memory-exhaustion guard) |
| Leakage | The token is never logged (`PairingLink.toString()` hides it); the copied pairing link is marked sensitive on the clipboard; logs contain counts and exception classes only |
| Permissions | `INTERNET` (local sockets only); `CAMERA` only when "Scan QR code" is tapped (requested by the scanner) |

Known limitations:
- Anyone who obtains the QR or link while the session is open can send messages *to* this phone
  (never read from it). The UI warns not to share it; the session ends after one transfer.
- The receiver listens on all interfaces of the current network while the screen is open.
- No discovery yet (mDNS/NSD): the QR carries the IP address. Wi-Fi Direct is deferred.

### Known Phase 1 gaps

- The local database is not encrypted at rest beyond Android's file-based encryption and app
  sandbox. A rooted or forensically imaged unlocked device can read it. SQLCipher with an Android
  Keystore-wrapped key is planned with encrypted backups (Phase 4).
- Messages are shown in the UI without app-level lock (biometric lock is a Phase 9 item).

## Planned cryptography (Phases 4 to 8)

Use vetted primitives only (Android Keystore, Tink or libsodium bindings, Web Crypto). No
custom protocol without review.

| Purpose | Primitive |
|---|---|
| Device identity / signatures | Ed25519 (key in Keystore where supported, else Keystore-wrapped) |
| Device pairing key agreement | X25519 + HKDF-SHA-256, verified with a QR code / safety number |
| Payload and backup encryption | AES-256-GCM with random 96-bit nonces, AAD = event id + device id + sequence |
| Account vault key | Random 256-bit key, wrapped per device and per authorized browser |
| Recovery | Vault key wrapped with a recovery passphrase (Argon2id) the user stores offline |
| Rotation / revocation | New vault key on revocation; remaining devices re-wrap; revoked device's tokens invalidated |

### Browser limitation (must be shown to users)

Browser-side decryption only protects against a *passive* server. A compromised server can serve
modified JavaScript that exfiltrates the unlocked vault key. Mitigations: self-hosting, Subresource
Integrity, a strict CSP, optional pinned/offline dashboard build, and never persisting the
decrypted vault key (memory only; non-extractable `CryptoKey`; never `localStorage`).

## Android platform constraints

- Outgoing SMS sent by another app cannot be observed in real time without being the default SMS
  app. The observer plus reconciliation is best effort.
- A force-stopped app receives no broadcasts until the user opens it again.
- Doze, App Standby buckets and OEM battery managers can delay WorkManager jobs by hours.
