/**
 * Number matching (fallback when the QR code cannot be scanned); mirrors android ConfirmNumbers.kt.
 * The phone sends a random salt only after this browser registered its keys; this browser shows
 * number(salt, its own key fingerprint) and the phone offers number(salt, the keys the server listed).
 */
import { base64ToBytes } from "./crypto";

const domain = new TextEncoder().encode("sms-bridge confirm v1");

export async function confirmNumber(saltB64: string, encPublicB64: string, signPublicB64: string): Promise<number> {
  const salt = base64ToBytes(saltB64);
  const enc = base64ToBytes(encPublicB64);
  const sign = base64ToBytes(signPublicB64);
  const keys = new Uint8Array(enc.length + sign.length);
  keys.set(enc);
  keys.set(sign, enc.length);
  const fingerprint = new Uint8Array(await crypto.subtle.digest("SHA-256", keys));
  const input = new Uint8Array(domain.length + salt.length + fingerprint.length);
  input.set(domain);
  input.set(salt, domain.length);
  input.set(fingerprint, domain.length + salt.length);
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", input));
  const n = (((d[0]! & 0x7f) << 24) | (d[1]! << 16) | (d[2]! << 8) | d[3]!) >>> 0;
  return 10 + (n % 90);
}
