/**
 * "Add another phone": the QR code carries a pairing code (for the server) and a one-time secret
 * `k` that never leaves this page and the phone. The phone proves it scanned this QR code with
 * HMAC-SHA256(k, SHA-256(encPublic || signPublic)); this browser checks the proof against the keys
 * the server lists for the phone, so a server that swapped in its own keys would be caught, then
 * wraps the vault key for the phone (see android PairLink.kt / CloudRepository.handleLink).
 *
 *   smsbridge://pair?v=1&s=<server>&c=<pairing code>&k=<secret, base64url>
 */
import { base64ToBytes, type Bytes } from "./crypto";

const b64url = (b: Bytes) => btoa(String.fromCharCode(...b)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

export const newLinkSecret = (): Bytes => crypto.getRandomValues(new Uint8Array(16));

export function addPhoneLink(server: string, code: string, secret: Bytes): string {
  return `smsbridge://pair?${new URLSearchParams({ v: "1", s: server, c: code, k: b64url(secret) }).toString()}`;
}

/** The proof a phone with these public keys (standard base64) sends; standard base64. */
export async function linkProof(secret: Bytes, encPublicB64: string, signPublicB64: string): Promise<string> {
  const enc = base64ToBytes(encPublicB64);
  const sign = base64ToBytes(signPublicB64);
  const joined = new Uint8Array(enc.length + sign.length);
  joined.set(enc);
  joined.set(sign, enc.length);
  const fingerprint = new Uint8Array(await crypto.subtle.digest("SHA-256", joined));
  const key = await crypto.subtle.importKey("raw", secret, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return btoa(String.fromCharCode(...new Uint8Array(await crypto.subtle.sign("HMAC", key, fingerprint))));
}

/**
 * The link secret for a phone that typed the 8-digit add-phone code instead of scanning: derived
 * from the digits, so both sides know it. (Weaker than the QR's random secret: the server stores
 * the digits, so this trusts the server, like typing the sign-in code does.)
 */
export async function typedLinkSecret(digits: string): Promise<Bytes> {
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`sms-bridge typed link v1|${digits}`)));
  return hash.slice(0, 16);
}
