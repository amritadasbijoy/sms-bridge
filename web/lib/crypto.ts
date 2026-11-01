/**
 * Browser side of docs/sync-design.md, mirroring android/.../data/cloud/CloudCrypto.kt byte for
 * byte. Only WebCrypto primitives are used. The vault key is imported as a non-extractable
 * CryptoKey and is never written to storage; reloading the page locks the vault again.
 */

const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const NONCE = 12;
const encoder = new TextEncoder();

/** Bytes backed by a plain ArrayBuffer (what WebCrypto accepts). */
export type Bytes = Uint8Array<ArrayBuffer>;

/** Accepts any case, spaces and dashes, and the usual O/0, I/L/1 confusions. Null if malformed. */
export function parseRecoveryKey(text: string): Bytes | null {
  const clean = text
    .toUpperCase()
    .replace(/[^A-Z0-9]/g, "")
    .replace(/O/g, "0")
    .replace(/[IL]/g, "1");
  if (clean.length !== 26) return null;
  const out: number[] = [];
  let bits = 0;
  let value = 0;
  for (const c of clean) {
    const index = CROCKFORD.indexOf(c);
    if (index < 0) return null;
    value = ((value << 5) | index) & 0xffff;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return out.length === 16 ? new Uint8Array(out) : null;
}

export function base64ToBytes(b64: string): Bytes {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function recoveryKek(recoveryKey: Bytes): Promise<CryptoKey> {
  const ikm = await crypto.subtle.importKey("raw", recoveryKey, "HKDF", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: encoder.encode("sms-bridge recovery v1"), info: encoder.encode("vault key wrap") },
    ikm,
    { name: "AES-GCM", length: 256 },
    false,
    ["decrypt"],
  );
}

async function gcmDecrypt(key: CryptoKey, blob: Bytes, aad: Bytes): Promise<Bytes> {
  if (blob.length < NONCE + 16) throw new Error("ciphertext too short");
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: blob.subarray(0, NONCE), additionalData: aad, tagLength: 128 },
    key,
    blob.subarray(NONCE),
  );
  return new Uint8Array(plain);
}

/**
 * Unwraps a recovery-wrapped vault key and imports it as a non-extractable AES-GCM key.
 * Throws if the recovery key is wrong (the GCM tag does not verify).
 */
export async function unlockVaultKey(wrapped: Bytes, recoveryKey: Bytes, keyVersion: number): Promise<CryptoKey> {
  const kek = await recoveryKek(recoveryKey);
  const raw = await gcmDecrypt(kek, wrapped, encoder.encode(`sms-bridge recovery v${keyVersion}`));
  try {
    if (raw.length !== 32) throw new Error("unexpected vault key length");
    return await crypto.subtle.importKey("raw", raw, { name: "AES-GCM" }, false, ["decrypt"]);
  } finally {
    raw.fill(0);
  }
}

export function eventAad(eventId: string, deviceId: string, deviceSequence: number, keyVersion: number): Bytes {
  return encoder.encode(`sbsync-v1|${eventId}|${deviceId}|${deviceSequence}|${keyVersion}`);
}

export async function decryptEvent(
  vaultKey: CryptoKey,
  ciphertext: Bytes,
  eventId: string,
  deviceId: string,
  deviceSequence: number,
  keyVersion: number,
): Promise<Bytes> {
  return gcmDecrypt(vaultKey, ciphertext, eventAad(eventId, deviceId, deviceSequence, keyVersion));
}

/** Same six digits the phones show for a device ("123 456"). */
export async function safetyCode(encPublic: Bytes, signPublic: Bytes): Promise<string> {
  const joined = new Uint8Array(encPublic.length + signPublic.length);
  joined.set(encPublic);
  joined.set(signPublic, encPublic.length);
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", joined));
  const n = ((((d[0]! & 0x7f) << 24) | (d[1]! << 16) | (d[2]! << 8) | d[3]!) >>> 0) % 1_000_000;
  const s = n.toString().padStart(6, "0");
  return `${s.slice(0, 3)} ${s.slice(3)}`;
}
