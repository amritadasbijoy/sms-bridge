/**
 * Account passwords that never leave this browser.
 *
 * - The login secret (PBKDF2 of the password, salted with the username) is what the server checks
 *   instead of the password.
 * - The password key: PBKDF2 of the password with a random per-account salt gives 64 bytes. The
 *   first 32 are an X25519 private key (the vault key is HPKE-wrapped for its public half, like for
 *   a device); the last 32 are a verifier the server keeps a hash of, so a new browser can prove it
 *   derived the key before it approves itself.
 *
 * The server never sees the password or anything that opens the vault without guessing it.
 */
import { base64ToBytes, type Bytes } from "./crypto";

export const PASSWORD_ITERATIONS = 600_000;
export const MIN_PASSWORD = 10;

const enc = new TextEncoder();
const toB64 = (b: Bytes) => btoa(String.fromCharCode(...b));
// PKCS#8 header for a raw X25519 private key (RFC 8410).
const PKCS8_X25519 = new Uint8Array([0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x04, 0x22, 0x04, 0x20]);

async function pbkdf2(password: string, salt: Bytes, iterations: number, bits: number): Promise<Bytes> {
  const key = await crypto.subtle.importKey("raw", enc.encode(password.normalize("NFKC")), "PBKDF2", false, ["deriveBits"]);
  return new Uint8Array(await crypto.subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations }, key, bits));
}

/** What is sent to the server as "the password" (base64, 44 characters). */
export async function loginSecret(username: string, password: string): Promise<string> {
  const salt = enc.encode(`sms-bridge login v1|${username.trim().toLowerCase()}`);
  return toB64(await pbkdf2(password, salt, PASSWORD_ITERATIONS, 256));
}

export interface PasswordKey {
  privateKey: CryptoKey;
  publicKey: Bytes;
  publicB64: string;
  verifierB64: string;
}

export async function passwordKey(password: string, saltB64: string, iterations: number): Promise<PasswordKey> {
  const bits = await pbkdf2(password, base64ToBytes(saltB64), iterations, 512);
  const pkcs8 = new Uint8Array(PKCS8_X25519.length + 32);
  pkcs8.set(PKCS8_X25519);
  pkcs8.set(bits.subarray(0, 32), PKCS8_X25519.length);
  try {
    const extractable = await crypto.subtle.importKey("pkcs8", pkcs8, { name: "X25519" }, true, ["deriveBits"]);
    const jwk = await crypto.subtle.exportKey("jwk", extractable);
    const publicKey = base64ToBytes(jwk.x!.replace(/-/g, "+").replace(/_/g, "/").padEnd(44, "="));
    const privateKey = await crypto.subtle.importKey("pkcs8", pkcs8, { name: "X25519" }, false, ["deriveBits"]);
    return { privateKey, publicKey, publicB64: toB64(publicKey), verifierB64: toB64(bits.slice(32)) };
  } finally {
    pkcs8.fill(0);
    bits.fill(0);
  }
}

export const newSalt = (): string => toB64(crypto.getRandomValues(new Uint8Array(16)));

/** Recipient id the vault key is bound to when wrapped for the password (hpke wrapContext). */
export const PASSWORD_RECIPIENT = "password";
