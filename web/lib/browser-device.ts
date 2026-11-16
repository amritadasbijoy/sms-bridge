/**
 * This browser as an approved device (like a second phone). Its X25519 private key is generated
 * non-extractable and kept in IndexedDB: page scripts can use it to unwrap the vault key the
 * phone wrapped for it, but nothing (including this code) can read the key's bytes. One record per
 * account, so several accounts can share a browser.
 *
 * Some browsers cannot store key objects (iOS browsers other than Safari throw DataCloneError). There
 * the private key is stored as PKCS#8 bytes in the same IndexedDB record instead: still private to
 * this site, but readable by its scripts. Without this fallback such browsers could never open messages.
 */
import { api, ApiError } from "./api";
import { base64ToBytes, safetyCode, type Bytes } from "./crypto";
import { hpkeOpen, hpkeSeal, wrapContext } from "./hpke";
import { newSalt, PASSWORD_ITERATIONS, PASSWORD_RECIPIENT, passwordKey } from "./password";

export interface DeviceRecord {
  userId: string;
  deviceId: string;
  encKeys: CryptoKeyPair;
  encPublic: string;
  signPublic: string;
}

const DB_NAME = "sms-bridge";
const STORE = "browser-device";

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: "userId" });
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function withStore<T>(mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const db = await openDb();
  try {
    return await new Promise<T>((resolve, reject) => {
      const req = fn(db.transaction(STORE, mode).objectStore(STORE));
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  } finally {
    db.close();
  }
}

/** How a record is stored when the browser cannot store CryptoKey objects. */
interface StoredBytes {
  userId: string;
  deviceId: string;
  encPrivate: string; // PKCS#8, base64
  encPublic: string;
  signPublic: string;
}

/** Private key bytes kept beside each key pair this page made, for browsers that need the fallback. */
const privateBytes = new WeakMap<CryptoKey, Bytes>();

async function fromBytes(r: StoredBytes): Promise<DeviceRecord> {
  const pkcs8 = base64ToBytes(r.encPrivate);
  const privateKey = await crypto.subtle.importKey("pkcs8", pkcs8, { name: "X25519" }, false, ["deriveBits"]);
  privateBytes.set(privateKey, pkcs8);
  const publicKey = await crypto.subtle.importKey("raw", base64ToBytes(r.encPublic), { name: "X25519" }, true, []);
  return { userId: r.userId, deviceId: r.deviceId, encKeys: { privateKey, publicKey }, encPublic: r.encPublic, signPublic: r.signPublic };
}

async function load(userId: string): Promise<DeviceRecord | undefined> {
  const r = await withStore<DeviceRecord | StoredBytes | undefined>("readonly", (s) => s.get(userId));
  return r && "encPrivate" in r ? fromBytes(r) : r;
}

async function save(r: DeviceRecord) {
  try {
    await withStore("readwrite", (s) => s.put(r));
  } catch (e) {
    const pkcs8 = privateBytes.get(r.encKeys.privateKey);
    if (!pkcs8) throw e;
    const { userId, deviceId, encPublic, signPublic } = r;
    const stored: StoredBytes = { userId, deviceId, encPrivate: toB64(pkcs8), encPublic, signPublic };
    await withStore("readwrite", (s) => s.put(stored));
  }
}
export const forgetLocal = (userId: string) => withStore("readwrite", (s) => s.delete(userId));

/** Forgets [r] only if it is still the stored record (a newer approved one may have replaced it). */
async function forgetIfCurrent(r: DeviceRecord) {
  const stored = await withStore<{ deviceId: string } | undefined>("readonly", (s) => s.get(r.userId));
  if (stored?.deviceId === r.deviceId) await forgetLocal(r.userId);
}

const toB64 = (b: ArrayBuffer | Bytes) => btoa(String.fromCharCode(...new Uint8Array(b)));

async function signingPublicKey(): Promise<string> {
  // Only its public half is used (it feeds the safety code; the browser uploads nothing to sign).
  try {
    const pair = (await crypto.subtle.generateKey({ name: "Ed25519" }, false, ["sign", "verify"])) as CryptoKeyPair;
    return toB64(await crypto.subtle.exportKey("raw", pair.publicKey));
  } catch {
    return toB64(crypto.getRandomValues(new Uint8Array(32)).buffer);
  }
}

const inFlight = new Map<string, Promise<DeviceRecord>>();

/**
 * The stored device for this account, registering a new one with the server if needed. Concurrent
 * callers share one registration, so a browser never registers twice at once.
 */
export function ensureDevice(userId: string): Promise<DeviceRecord> {
  const running = inFlight.get(userId);
  if (running) return running;
  const p = createOrLoad(userId).finally(() => inFlight.delete(userId));
  inFlight.set(userId, p);
  return p;
}

/**
 * A fresh key pair for this browser. The private key object can never be exported; its bytes are
 * kept in memory only so [save] can fall back to them where key objects cannot be stored.
 */
export async function generateKeys(): Promise<{ encKeys: CryptoKeyPair; encPublic: string; signPublic: string }> {
  const made = (await crypto.subtle.generateKey({ name: "X25519" }, true, ["deriveBits"])) as CryptoKeyPair;
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", made.privateKey));
  const privateKey = await crypto.subtle.importKey("pkcs8", pkcs8, { name: "X25519" }, false, ["deriveBits"]);
  privateBytes.set(privateKey, pkcs8);
  const encKeys = { privateKey, publicKey: made.publicKey };
  const encPublic = toB64(await crypto.subtle.exportKey("raw", encKeys.publicKey));
  return { encKeys, encPublic, signPublic: await signingPublicKey() };
}

/** Stores keys a phone approved during sign-in, so the dashboard opens with them. */
export async function saveApprovedDevice(userId: string, deviceId: string, keys: { encKeys: CryptoKeyPair; encPublic: string; signPublic: string }) {
  await save({ userId, deviceId, ...keys });
}

async function createOrLoad(userId: string): Promise<DeviceRecord> {
  const existing = await load(userId);
  if (existing) return existing;
  const { encKeys, encPublic, signPublic } = await generateKeys();
  const { deviceId } = await api.registerBrowser(encPublic, signPublic);
  const record: DeviceRecord = { userId, deviceId, encKeys, encPublic, signPublic };
  await save(record);
  return record;
}

export async function deviceSafetyCode(r: DeviceRecord): Promise<string> {
  return safetyCode(base64ToBytes(r.encPublic), base64ToBytes(r.signPublic));
}

export type DeviceKeysResult =
  | { state: "approved"; keys: Map<number, CryptoKey> }
  | { state: "waiting"; challenge: string | null }
  | { state: "revoked" };

/** Fetches and unwraps this browser's vault keys. A revoked device is forgotten locally. */
export async function fetchVaultKeys(r: DeviceRecord): Promise<DeviceKeysResult> {
  let res;
  try {
    res = await api.browserKeys(r.deviceId);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      await forgetIfCurrent(r);
      return { state: "revoked" };
    }
    throw e;
  }
  if (!res.trusted || res.keys.length === 0) return { state: "waiting", challenge: res.challenge ?? null };
  const keys = new Map<number, CryptoKey>();
  const pub = base64ToBytes(r.encPublic);
  for (const k of res.keys) {
    const raw: Bytes = await hpkeOpen(r.encKeys.privateKey, pub, base64ToBytes(k.wrappedKey), wrapContext(r.deviceId, k.keyVersion));
    try {
      keys.set(k.keyVersion, await crypto.subtle.importKey("raw", raw, { name: "AES-GCM" }, false, ["decrypt"]));
    } finally {
      raw.fill(0);
    }
  }
  return { state: "approved", keys };
}

/**
 * This browser's vault keys as raw bytes, to wrap them for a phone it adds ("Add another phone").
 * The caller zeroes them when done; they are never stored.
 */
export async function rawVaultKeys(r: DeviceRecord): Promise<Map<number, Bytes>> {
  const res = await api.browserKeys(r.deviceId);
  const pub = base64ToBytes(r.encPublic);
  const out = new Map<number, Bytes>();
  for (const k of res.keys) {
    out.set(k.keyVersion, await hpkeOpen(r.encKeys.privateKey, pub, base64ToBytes(k.wrappedKey), wrapContext(r.deviceId, k.keyVersion)));
  }
  return out;
}

/**
 * Signs this browser in with the account password alone: unwraps the vault key the password opens,
 * registers this browser, wraps the key for it and approves itself (the server checks the
 * password-derived verifier). Throws ApiError `no_password_vault` when the password does not open
 * messages yet; the dashboard then asks a phone instead.
 */
export async function adoptWithPassword(userId: string, password: string): Promise<DeviceRecord> {
  const vault = await api.passwordVault();
  const pk = await passwordKey(password, vault.salt, vault.iterations);
  const raw = new Map<number, Bytes>();
  try {
    for (const k of vault.keys) {
      raw.set(k.keyVersion, await hpkeOpen(pk.privateKey, pk.publicKey, base64ToBytes(k.wrappedKey), wrapContext(PASSWORD_RECIPIENT, k.keyVersion)));
    }
    const { encKeys, encPublic, signPublic } = await generateKeys();
    const { deviceId } = await api.registerBrowser(encPublic, signPublic);
    const own = base64ToBytes(encPublic);
    const keys = [];
    for (const [v, key] of raw) keys.push({ keyVersion: v, wrappedKey: toB64(await hpkeSeal(own, key, wrapContext(deviceId, v))) });
    await api.selfApprove(deviceId, pk.verifierB64, keys);
    const record: DeviceRecord = { userId, deviceId, encKeys, encPublic, signPublic };
    await save(record);
    return record;
  } finally {
    raw.forEach((k) => k.fill(0));
  }
}

/** After "Save your account": wraps this approved browser's vault keys for the password. */
export async function savePasswordVault(r: DeviceRecord, password: string): Promise<void> {
  const salt = newSalt();
  const pk = await passwordKey(password, salt, PASSWORD_ITERATIONS);
  const raw = await rawVaultKeys(r);
  try {
    const keys = [];
    for (const [v, key] of raw) keys.push({ keyVersion: v, wrappedKey: toB64(await hpkeSeal(pk.publicKey, key, wrapContext(PASSWORD_RECIPIENT, v))) });
    await api.setPasswordVault({ deviceId: r.deviceId, salt, iterations: PASSWORD_ITERATIONS, publicKey: pk.publicB64, verifier: pk.verifierB64, keys });
  } finally {
    raw.forEach((k) => k.fill(0));
  }
}
