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
| Default SMS role is never requested (handler not implemented yet) | `SmsPermissionManager` |
| SMS broadcast receiver protected by `android.permission.BROADCAST_SMS` | `AndroidManifest.xml` |
| `allowBackup=false`: archive is excluded from Google/device backup | `AndroidManifest.xml` |
| Single logging facade; exceptions reduced to class name; `toString()` of every SMS type redacted (tested) | `AppLog`, `RedactionTest` |
| Release builds strip `Log.v/d/i` | `proguard-rules.pro` |
| No `INTERNET` permission in Phase 1: the app physically cannot upload (verified in the merged manifest; WorkManager adds only `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`) | `AndroidManifest.xml` |
| Archive never deletes rows; provider id reuse unlinks instead of overwriting (tested) | `SmsRepository` |
| No destructive Room migration fallback | `SmsBridgeDatabase` |
| Test fixtures are synthetic | `Synthetic.kt` |

Fingerprints are SHA-256 of content and stay on the device. Short messages could be
brute-forced from a bare hash, so they must never be sent to the server unencrypted.

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
