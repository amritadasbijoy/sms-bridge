/**
 * The dashboard's pairing QR code (read by the phone's "Scan" buttons; see android PairLink.kt):
 * smsbridge://pair?v=1&s=<server>&c=<pairing code>&d=<browser device id>&f=<fingerprint>
 * The fingerprint is SHA-256 over this browser's X25519 and Ed25519 public keys; the phone approves
 * the browser only if the keys the server lists for it hash to the same value.
 */
import { base64ToBytes } from "./crypto";

export async function deviceFingerprint(encPublicB64: string, signPublicB64: string): Promise<string> {
  const enc = base64ToBytes(encPublicB64);
  const sign = base64ToBytes(signPublicB64);
  const joined = new Uint8Array(enc.length + sign.length);
  joined.set(enc);
  joined.set(sign, enc.length);
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", joined));
  return btoa(String.fromCharCode(...digest)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function pairLink(p: { server: string; code?: string | null; deviceId?: string | null; fingerprint?: string | null }): string {
  const q = new URLSearchParams({ v: "1", s: p.server });
  if (p.code) q.set("c", p.code);
  if (p.deviceId && p.fingerprint) {
    q.set("d", p.deviceId);
    q.set("f", p.fingerprint);
  }
  return `smsbridge://pair?${q.toString()}`;
}
