import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { base64ToBytes, safetyCode } from "@/lib/crypto";
import { hpkeOpen, wrapContext } from "@/lib/hpke";

// Produced by the phone's real Tink HPKE (android HpkeVectorsTest). Throwaway keys.
interface Vectors {
  recipientId: string;
  encPrivate: string;
  encPublic: string;
  signPublic: string;
  safetyCode: string;
  cases: { keyVersion: number; vaultKey: string; wrapped: string }[];
}
const v: Vectors = JSON.parse(readFileSync(path.join(__dirname, "../../testdata/hpke-vectors-v1.json"), "utf8"));
const b64url = (b64: string) => b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

/** Imports the test private key as a browser would hold its own: non-extractable, deriveBits only. */
async function privateKey(): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "jwk",
    { kty: "OKP", crv: "X25519", d: b64url(v.encPrivate), x: b64url(v.encPublic) },
    { name: "X25519" },
    false,
    ["deriveBits"],
  );
}

describe("HPKE against Tink-produced vectors", () => {
  it("unwraps each vault key exactly", async () => {
    const sk = await privateKey();
    for (const c of v.cases) {
      const out = await hpkeOpen(sk, base64ToBytes(v.encPublic), base64ToBytes(c.wrapped), wrapContext(v.recipientId, c.keyVersion));
      expect(Buffer.from(out).toString("base64")).toBe(c.vaultKey);
    }
  });

  it("rejects the wrong recipient id, key version or a modified message", async () => {
    const sk = await privateKey();
    const c = v.cases[0]!;
    const wrapped = base64ToBytes(c.wrapped);
    const pk = base64ToBytes(v.encPublic);
    await expect(hpkeOpen(sk, pk, wrapped, wrapContext("someone-else", c.keyVersion))).rejects.toThrow();
    await expect(hpkeOpen(sk, pk, wrapped, wrapContext(v.recipientId, c.keyVersion + 5))).rejects.toThrow();
    const tampered = wrapped.slice();
    tampered[tampered.length - 1]! ^= 1;
    await expect(hpkeOpen(sk, pk, tampered, wrapContext(v.recipientId, c.keyVersion))).rejects.toThrow();
  });

  it("rejects a different private key", async () => {
    const other = (await crypto.subtle.generateKey({ name: "X25519" }, false, ["deriveBits"])) as CryptoKeyPair;
    const c = v.cases[0]!;
    await expect(
      hpkeOpen(other.privateKey, base64ToBytes(v.encPublic), base64ToBytes(c.wrapped), wrapContext(v.recipientId, c.keyVersion)),
    ).rejects.toThrow();
  });

  it("shows the same safety code as the phone for this key pair", async () => {
    expect(await safetyCode(base64ToBytes(v.encPublic), base64ToBytes(v.signPublic))).toBe(v.safetyCode);
  });
});
