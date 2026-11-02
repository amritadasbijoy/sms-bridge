/**
 * This browser as an approved device (like a second phone). Its X25519 private key is generated
 * non-extractable and kept in IndexedDB: page scripts can use it to unwrap the vault key the
 * phone wrapped for it, but nothing (including this code) can read the key's bytes. One record per
 * account, so several accounts can share a browser.
 */
import { api, ApiError } from "./api";
import { base64ToBytes, safetyCode, type Bytes } from "./crypto";
import { hpkeOpen, wrapContext } from "./hpke";

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

const load = (userId: string) => withStore<DeviceRecord | undefined>("readonly", (s) => s.get(userId));
const save = (r: DeviceRecord) => withStore("readwrite", (s) => s.put(r));
export const forgetLocal = (userId: string) => withStore("readwrite", (s) => s.delete(userId));

const toB64 = (b: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(b)));

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

async function createOrLoad(userId: string): Promise<DeviceRecord> {
  const existing = await load(userId);
  if (existing) return existing;
  const encKeys = (await crypto.subtle.generateKey({ name: "X25519" }, false, ["deriveBits"])) as CryptoKeyPair;
  const encPublic = toB64(await crypto.subtle.exportKey("raw", encKeys.publicKey));
  const signPublic = await signingPublicKey();
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
  | { state: "waiting" }
  | { state: "revoked" };

/** Fetches and unwraps this browser's vault keys. A revoked device is forgotten locally. */
export async function fetchVaultKeys(r: DeviceRecord): Promise<DeviceKeysResult> {
  let res;
  try {
    res = await api.browserKeys(r.deviceId);
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) {
      await forgetLocal(r.userId);
      return { state: "revoked" };
    }
    throw e;
  }
  if (!res.trusted || res.keys.length === 0) return { state: "waiting" };
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
