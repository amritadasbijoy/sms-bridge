/**
 * HPKE receiver (RFC 9180, base mode) for DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM:
 * the suite the phone's Tink wraps vault keys with (CloudCrypto.wrapForDevice). Tink's NO_PREFIX
 * output is `enc (32 bytes) || ciphertext`, the HPKE `info` is the context string and the AAD is empty.
 * Built only from WebCrypto primitives (X25519, HMAC-SHA256, AES-GCM); verified against vectors
 * produced by Tink (testdata/hpke-vectors-v1.json).
 */
import type { Bytes } from "./crypto";

const enc = new TextEncoder();
const KEM_ID = 0x0020; // DHKEM(X25519, HKDF-SHA256)
const KDF_ID = 0x0001; // HKDF-SHA256
const AEAD_ID = 0x0002; // AES-256-GCM
const N_ENC = 32;

function concat(...parts: Uint8Array[]): Bytes {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

function i2osp(value: number, length: number): Bytes {
  const out = new Uint8Array(length);
  for (let i = length - 1; i >= 0; i--) {
    out[i] = value & 0xff;
    value >>>= 8;
  }
  return out;
}

const KEM_SUITE = concat(enc.encode("KEM"), i2osp(KEM_ID, 2));
const HPKE_SUITE = concat(enc.encode("HPKE"), i2osp(KEM_ID, 2), i2osp(KDF_ID, 2), i2osp(AEAD_ID, 2));

async function hmac(key: Bytes, data: Bytes): Promise<Bytes> {
  const k = await crypto.subtle.importKey("raw", key.length ? key : new Uint8Array(32), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", k, data));
}

/** HKDF-Extract with an all-zero salt when the salt is empty (RFC 5869). */
const extract = (salt: Bytes, ikm: Bytes) => hmac(salt, ikm);

async function expand(prk: Bytes, info: Bytes, length: number): Promise<Bytes> {
  const out = new Uint8Array(length);
  let t: Bytes = new Uint8Array(0);
  for (let i = 1, o = 0; o < length; i++) {
    t = await hmac(prk, concat(t, info, new Uint8Array([i])));
    out.set(t.subarray(0, Math.min(t.length, length - o)), o);
    o += t.length;
  }
  return out;
}

const labeledExtract = (suite: Bytes, salt: Bytes, label: string, ikm: Bytes) =>
  extract(salt, concat(enc.encode("HPKE-v1"), suite, enc.encode(label), ikm));

const labeledExpand = (suite: Bytes, prk: Bytes, label: string, info: Bytes, length: number) =>
  expand(prk, concat(i2osp(length, 2), enc.encode("HPKE-v1"), suite, enc.encode(label), info), length);

/**
 * Opens a single-shot HPKE message. `privateKey` may be non-extractable (X25519, usage deriveBits);
 * `publicKey` is the recipient's raw 32-byte public key.
 */
export async function hpkeOpen(privateKey: CryptoKey, publicKey: Bytes, message: Bytes, info: Bytes): Promise<Bytes> {
  if (message.length < N_ENC + 16) throw new Error("HPKE message too short");
  const encapsulated = message.slice(0, N_ENC);
  const ephemeral = await crypto.subtle.importKey("raw", encapsulated, { name: "X25519" }, false, []);
  const dh = new Uint8Array(await crypto.subtle.deriveBits({ name: "X25519", public: ephemeral }, privateKey, 256));
  if (dh.every((b) => b === 0)) throw new Error("invalid HPKE public key");

  // Decap: shared_secret = ExtractAndExpand(dh, enc || pkR)
  const eaePrk = await labeledExtract(KEM_SUITE, new Uint8Array(0), "eae_prk", dh);
  const shared = await labeledExpand(KEM_SUITE, eaePrk, "shared_secret", concat(encapsulated, publicKey), 32);

  // KeySchedule, mode_base (no PSK)
  const empty = new Uint8Array(0);
  const pskIdHash = await labeledExtract(HPKE_SUITE, empty, "psk_id_hash", empty);
  const infoHash = await labeledExtract(HPKE_SUITE, empty, "info_hash", info);
  const context = concat(new Uint8Array([0x00]), pskIdHash, infoHash);
  const secret = await labeledExtract(HPKE_SUITE, shared, "secret", empty);
  const key = await labeledExpand(HPKE_SUITE, secret, "key", context, 32);
  const nonce = await labeledExpand(HPKE_SUITE, secret, "base_nonce", context, 12);

  try {
    const aes = await crypto.subtle.importKey("raw", key, { name: "AES-GCM" }, false, ["decrypt"]);
    // Sequence number 0: the nonce is the base nonce. AAD is empty.
    return new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: nonce, additionalData: empty, tagLength: 128 }, aes, message.subarray(N_ENC)));
  } finally {
    for (const b of [dh, shared, secret, key]) b.fill(0);
  }
}

/** The HPKE info the phone binds each wrapped vault key to (CloudCrypto.wrapContext). */
export const wrapContext = (recipientId: string, keyVersion: number): Bytes =>
  enc.encode(`sms-bridge vault key v${keyVersion} for ${recipientId}`);
