import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { base64ToBytes } from "@/lib/crypto";
import { hpkeOpen, hpkeSeal, wrapContext } from "@/lib/hpke";
import { addPhoneLink, linkProof } from "@/lib/link";

const v = JSON.parse(readFileSync(path.join(__dirname, "../../testdata/hpke-vectors-v1.json"), "utf8"));
const sealed = JSON.parse(readFileSync(path.join(__dirname, "../../testdata/hpke-browser-sealed-v1.json"), "utf8"));
const b64url = (b64: string) => b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
const privateKey = () =>
  crypto.subtle.importKey("jwk", { kty: "OKP", crv: "X25519", d: b64url(v.encPrivate), x: b64url(v.encPublic) }, { name: "X25519" }, false, ["deriveBits"]);

describe("Add another phone", () => {
  it("builds the QR link the phone parses (android PairLinkTest)", () => {
    expect(addPhoneLink("https://sms.example.org", "AB12C-DE34F", Uint8Array.from({ length: 16 }, (_, i) => i))).toBe(
      "smsbridge://pair?v=1&s=https%3A%2F%2Fsms.example.org&c=AB12C-DE34F&k=AAECAwQFBgcICQoLDA0ODw",
    );
  });

  it("computes the same link proof as the phone (android PairLinkTest)", async () => {
    const secret = Uint8Array.from({ length: 16 }, (_, i) => i);
    expect(await linkProof(secret, v.encPublic, v.signPublic)).toBe("KFP4zjvvbRTPxhOZ1ATCcz/e8xyaJyTKUMMzzcVjnSk=");
    expect(await linkProof(secret, v.signPublic, v.encPublic)).not.toBe("KFP4zjvvbRTPxhOZ1ATCcz/e8xyaJyTKUMMzzcVjnSk=");
  });

  it("seals vault keys that open with the recipient's key and context only", async () => {
    const vk = crypto.getRandomValues(new Uint8Array(32));
    const box = await hpkeSeal(base64ToBytes(v.encPublic), vk, wrapContext(v.recipientId, 2));
    const out = await hpkeOpen(await privateKey(), base64ToBytes(v.encPublic), box, wrapContext(v.recipientId, 2));
    expect(Buffer.from(out)).toEqual(Buffer.from(vk));
    await expect(hpkeOpen(await privateKey(), base64ToBytes(v.encPublic), box, wrapContext(v.recipientId, 1))).rejects.toThrow();
  });

  it("the committed browser-sealed vector opens (android checks the same file with Tink)", async () => {
    const out = await hpkeOpen(await privateKey(), base64ToBytes(v.encPublic), base64ToBytes(sealed.wrapped), wrapContext(v.recipientId, sealed.keyVersion));
    expect(Buffer.from(out).toString("base64")).toBe(sealed.vaultKey);
  });
});

describe("typed add-phone codes", () => {
  it("give the same secret as the phone (PairLinkTest)", async () => {
    const { typedLinkSecret } = await import("@/lib/link");
    expect(btoa(String.fromCharCode(...(await typedLinkSecret("12345678"))))).toBe("HuFnGgPeh0X5k6VvhBDs6Q==");
  });
});
